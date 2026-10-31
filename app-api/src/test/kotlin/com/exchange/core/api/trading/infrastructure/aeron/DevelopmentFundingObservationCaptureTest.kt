package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionCodec
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.Header
import org.agrona.concurrent.AgentTerminationException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DevelopmentFundingObservationCaptureTest {
    @Test
    fun `자금 접수 실행과 재실행은 전체 상태를 복사하지 않고 해당 불변 완료만 공개한다`() {
        val f = Fixture()
        f.fund()
        f.admit()
        assertIs<AdmissionResultPayload.Accepted>(f.service.observation?.admissionResult)
        assertNull(f.service.observation?.snapshot)
        assertNull(f.service.observation?.executionCompletion)

        f.execute()
        val first = assertNotNull(f.service.observation?.executionCompletion)
        assertEquals(1L, first.admission.sequence)
        assertEquals(Instant.ofEpochMilli(2_000), first.occurredAt)
        f.execute(timestamp = 3_000)
        assertSame(first, f.service.observation?.executionCompletion, "중복 실행은 최초 완료를 그대로 관찰한다")
        verify(f.state, never()).snapshot()
        assertNull(f.service.observation?.snapshot)

        val cheap = f.service.observation
        val captured = assertNotNull(f.service.captureObservation().get(1, TimeUnit.SECONDS))
        verify(f.state, times(1)).snapshot()
        val snapshot = assertNotNull(captured.snapshot)
        assertSame(first, snapshot.completions.single())
        assertTrue(snapshot.pendingOrders.isEmpty())
        assertSame(cheap, f.service.observation, "명시적 복사본은 최신 명령 슬롯을 바꾸지 않는다")
        assertFailsWith<UnsupportedOperationException> { (snapshot.completions as MutableList).clear() }
        f.fund("U2")
        assertEquals(1, snapshot.preparations.size, "이후 자금 준비가 앞선 불변 복사본에 보이지 않는다")
        assertEquals(
            2,
            f.service
                .captureSnapshotOnServiceThread()
                .preparations.size,
        )
    }

    @Test
    fun `외부 요청은 업무 스레드의 background 전에는 상태를 읽지 않는다`() {
        val f = Fixture()
        f.fund()
        val pending = externalCapture(f)
        val concurrent = externalCapture(f)
        assertFalse(pending.isDone)
        assertFalse(concurrent.isDone)
        verify(f.state, never()).snapshot()
        val illegalRead =
            assertFailsWith<ExecutionException> {
                CompletableFuture.supplyAsync { f.service.captureSnapshotOnServiceThread() }.get(1, TimeUnit.SECONDS)
            }
        assertIs<IllegalStateException>(illegalRead.cause)

        assertEquals(2, f.service.doBackgroundWork(0))

        val captured = assertNotNull(pending.get(1, TimeUnit.SECONDS))
        assertEquals(1, assertNotNull(captured.snapshot).preparations.size)
        assertSame(captured, concurrent.get(1, TimeUnit.SECONDS), "같은 시점의 외부 조회는 불변 복사본 한 개를 나눠 받는다")
        verify(f.state, times(1)).snapshot()
        assertEquals(0, f.service.doBackgroundWork(0))
    }

    @Test
    fun `외부 관찰 요청은 유한하며 취소한 요청은 복사하지 않는다`() {
        val f = Fixture()
        f.fund()
        val pending = (1..16).map { externalCapture(f) }
        val overflow = externalCapture(f)
        val failure = assertFailsWith<ExecutionException> { overflow.get(1, TimeUnit.SECONDS) }
        assertIs<IllegalStateException>(failure.cause)
        assertTrue(failure.cause!!.message!!.contains("버퍼"))
        pending.forEach { it.cancel(false) }
        assertEquals(16, f.service.doBackgroundWork(0))
        assertEquals(0, f.service.doBackgroundWork(0))
        verify(f.state, never()).snapshot()
        assertTrue(f.service.started)
    }

    @Test
    fun `종료와 fatal은 대기 조회를 예외로 끝내고 새 조회도 거절한다`() {
        listOf(false, true).forEach { fatal ->
            val f = Fixture()
            f.fund()
            val pending = externalCapture(f)
            if (fatal) {
                assertFailsWith<AgentTerminationException> { f.fund("U2", ledger = "") }
            } else {
                f.service.onTerminate(f.cluster)
            }
            assertFailsWith<ExecutionException> { pending.get(1, TimeUnit.SECONDS) }
            assertFailsWith<ExecutionException> { f.service.captureObservation().get(1, TimeUnit.SECONDS) }
            assertFalse(f.service.started)
            verify(f.state, never()).snapshot()
        }
    }

    @Test
    fun `차단한 상태는 명시적 복사에도 노출하지 않고 앞선 명령 관찰은 유지한다`() {
        val f = Fixture()
        f.fund()
        val previous = f.service.observation
        val blocked = com.exchange.core.trading.TradingState::class.java.getDeclaredField("isBlocked").apply { isAccessible = true }
        blocked.setBoolean(f.state, true)

        val failure = assertFailsWith<ExecutionException> { f.service.captureObservation().get(1, TimeUnit.SECONDS) }

        assertIs<IllegalStateException>(failure.cause)
        assertTrue(failure.cause!!.message!!.contains("차단"))
        assertSame(previous, f.service.observation)
        assertNull(previous?.snapshot)
        assertTrue(f.state.isBlocked)
    }

    private fun externalCapture(f: Fixture): CompletableFuture<FundingObservation?> =
        CompletableFuture.supplyAsync { f.service.captureObservation() }.get(1, TimeUnit.SECONDS)

    private class Fixture {
        val state = spy(tradingState())
        val cluster = mock(Cluster::class.java)
        val service = DevelopmentFundingAdapter(state, onFatal = {})
        private val time = Instant.parse("2026-10-17T00:00:00Z")

        init {
            `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
            `when`(cluster.role()).thenReturn(Cluster.Role.LEADER)
            `when`(cluster.logPosition()).thenReturn(256L)
            ClusterExecutionClaimStub(cluster)
            service.onStart(cluster, null)
        }

        fun fund(
            user: String = "U1",
            ledger: String = "OPEN-$user",
        ) {
            val command =
                DevelopmentBalanceCommandPayload(
                    OpeningBalance("P-$user", UserId(user), AssetId("USDT"), Amount(1_000_000_000)),
                    ledger,
                    time,
                )
            send(DevelopmentBalanceCommandCodec().encode(command))
        }

        fun admit() {
            val command = admissionCommand().let { it.copy(order = it.order.copy(userId = UserId("U1"))) }
            send(AdmissionCommandCodec().encode(command))
        }

        fun execute(timestamp: Long = 2_000) {
            send(ExecuteAdmissionCodec().encode(ExecuteAdmissionPayload(1)), internal = true, timestamp = timestamp)
        }

        private fun send(
            bytes: ByteArray,
            internal: Boolean = false,
            timestamp: Long = 2_000,
        ) {
            service.onSessionMessage(
                null,
                timestamp,
                clusterFrame(bytes, if (internal) Long.MIN_VALUE else 1L),
                AeronCluster.SESSION_HEADER_LENGTH,
                bytes.size,
                Header(0, 0),
            )
        }
    }
}
