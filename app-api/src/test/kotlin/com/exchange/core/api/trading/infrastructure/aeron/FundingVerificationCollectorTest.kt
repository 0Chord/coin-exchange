package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.trading.TradingState
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.AgentTerminationException
import org.agrona.concurrent.IdleStrategy
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 어댑터·코어의 결과를 수집한다. 수집 실패가 서비스의 결과와 생명주기에 영향을 주면 실패한다. */
class FundingVerificationCollectorTest {
    private val commands = DevelopmentBalanceCommandCodec()

    @Test
    fun `소비가 늦어도 두 적용을 보존하고 앞 상태는 바뀌지 않는다`() {
        val fixture = fixture()
        `when`(fixture.cluster.logPosition()).thenReturn(256L, 512L)

        send(fixture, command())
        send(fixture, command("P2", "U2", "L2"))

        val observations = fixture.collector.drainObservations()
        assertEquals(listOf(256L, 512L), observations.map { it.position })
        val first = assertNotNull(observations[0].snapshot)
        val second = assertNotNull(observations[1].snapshot)
        assertEquals(listOf("U1"), first.balances.map { it.userId.value })
        assertEquals(listOf("U1", "U2"), second.balances.map { it.userId.value })
        assertEquals(1, first.preparations.size)
        assertEquals(2, second.preparations.size)
        assertTrue(fixture.collector.drainObservations().isEmpty())
        assertNull(fixture.collector.verificationFailure)
    }

    @Test
    fun `응답 재시도가 끝난 한 callback의 최종 관찰만 한 번 수집한다`() {
        val fixture = fixture(offers = listOf(Publication.BACK_PRESSURED, Publication.ADMIN_ACTION, 99L))

        send(fixture, command())

        val applied = fixture.collector.drainObservations().single()
        assertEquals(256L, applied.position)
        assertEquals("OFFERED", applied.replyStatus)
        assertEquals(3, applied.replyAttempts)
        assertEquals(fixture.service.observation, applied.copy(snapshot = null))
        assertTrue(fixture.collector.drainObservations().isEmpty())
    }

    @Test
    fun `잘못된 메시지도 다음 정상 적용에 덮이지 않는다`() {
        val fixture = fixture()
        `when`(fixture.cluster.logPosition()).thenReturn(128L, 256L)

        sendBytes(fixture, ByteArray(7))
        send(fixture, command())

        val observations = fixture.collector.drainObservations()
        assertEquals(listOf(128L, 256L), observations.map { it.position })
        assertEquals(1L, observations[0].malformedMessages)
        assertTrue(observations[0].replyStatus!!.startsWith("MALFORMED_OR_UNSUPPORTED"))
        assertNull(observations[0].snapshot)
        assertNotNull(observations[1].snapshot)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `버퍼 초과는 검증만 실패시키고 현재 응답과 다음 업무 처리를 유지한다`() {
        val fixture = fixture(capacity = 1)
        `when`(fixture.cluster.logPosition()).thenReturn(256L, 512L, 768L)
        send(fixture, command())

        send(fixture, command("P2", "U2", "L2"))

        val failure = assertNotNull(fixture.collector.verificationFailure)
        assertTrue(failure.contains("관찰 버퍼 초과"))
        assertTrue(failure.contains("capacity=1"))
        assertTrue(fixture.service.started)
        assertFalse(fixture.state.isBlocked)
        assertEquals(
            2,
            fixture.state
                .snapshot()
                .preparations.size,
        )
        assertEquals("OFFERED", fixture.service.observation!!.replyStatus)
        assertEquals(2, fixture.replyCount())
        assertEquals(listOf(256L), fixture.collector.drainObservations().map { it.position })

        send(fixture, command("P3", "U3", "L3"))

        assertEquals(
            3,
            fixture.state
                .snapshot()
                .preparations.size,
        )
        assertEquals(3, fixture.replyCount())
        assertEquals(listOf(768L), fixture.collector.drainObservations().map { it.position })
        assertSame(failure, fixture.collector.verificationFailure, "배출 후 정상 결과가 나와도 검증 실패는 남는다")
        assertTrue(fixture.service.started)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `버퍼가 차 있어도 실제 코어 종료 오류와 원래 예외를 보존한다`() {
        val fixture = fixture(capacity = 1)
        send(fixture, command())

        val failure = assertFailsWith<AgentTerminationException> { send(fixture, command("P2", "U2", "")) }

        val observations = fixture.collector.drainObservations()
        assertEquals(2, observations.size)
        assertNotNull(observations[0].snapshot)
        assertTrue(observations[1].failure!!.contains("원장"))
        assertNull(observations[1].snapshot)
        assertSame(observations[1], fixture.service.observation)
        assertSame(fixture.failures.single(), failure.cause)
        assertTrue(fixture.collector.verificationFailure!!.contains("서비스 적용 실패"))
        assertEquals(
            1,
            fixture.state
                .snapshot()
                .preparations.size,
        )
        assertEquals(1, fixture.replyCount())
        assertFalse(fixture.service.started)
        assertTrue(fixture.collector.drainObservations().isEmpty())
    }

    @Test
    fun `검증 decorator도 외부 상태 관찰의 background 처리를 실제 서비스에 전달한다`() {
        val fixture = fixture()
        send(fixture, command())
        val capture =
            CompletableFuture.supplyAsync { fixture.service.captureObservation() }.get(1, TimeUnit.SECONDS)
        assertFalse(capture.isDone)

        assertEquals(1, fixture.decorated.doBackgroundWork(0))

        val snapshot = assertNotNull(capture.get(1, TimeUnit.SECONDS)?.snapshot)
        assertEquals(listOf("U1"), snapshot.balances.map { it.userId.value })
        assertEquals(1, snapshot.preparations.size)
        assertNull(fixture.service.observation?.snapshot, "명시적 조회가 일반 명령 슬롯에 전체 상태를 붙이지 않는다")
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `관찰 용량은 메모리 할당 전에 제한한다`() {
        listOf(-1, 0, 3, 2048, Int.MAX_VALUE).forEach { capacity ->
            assertFailsWith<IllegalArgumentException> { FundingVerificationCollector(capacity) }
        }
    }

    private fun fixture(
        capacity: Int = 16,
        offers: List<Long> = listOf(1L),
    ): Fixture {
        val state = tradingState()
        val cluster = mock(Cluster::class.java)
        val session = mock(ClientSession::class.java)
        val failures = mutableListOf<Throwable>()
        var replyCount = 0
        `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
        `when`(cluster.role()).thenReturn(Cluster.Role.LEADER)
        `when`(cluster.logPosition()).thenReturn(256L)
        `when`(cluster.idleStrategy()).thenReturn(mock(IdleStrategy::class.java))
        doAnswer { offers[(replyCount++).coerceAtMost(offers.lastIndex)] }
            .`when`(session)
            .offer(any(DirectBuffer::class.java), anyInt(), anyInt())
        val service = DevelopmentFundingAdapter(state, onFatal = { failures += it })
        val collector = FundingVerificationCollector(capacity)
        val decorated = collector.decorate(service)
        assertFalse(service.started)
        decorated.onStart(cluster, null)
        assertTrue(service.started, "수집 래퍼는 실제 서비스의 시작 callback을 위임한다")
        return Fixture(state, cluster, session, service, collector, decorated, { replyCount }, failures)
    }

    private fun command(
        preparationId: String = "P1",
        userId: String = "U1",
        ledgerId: String = "L1",
    ) = DevelopmentBalanceCommandPayload(
        OpeningBalance(preparationId, UserId(userId), AssetId("USDT"), Amount(1_000_000_000)),
        ledgerId,
        Instant.parse("2026-10-17T00:00:00.123456789Z"),
    )

    private fun send(
        fixture: Fixture,
        command: DevelopmentBalanceCommandPayload,
    ) = sendBytes(fixture, commands.encode(command))

    private fun sendBytes(
        fixture: Fixture,
        bytes: ByteArray,
    ) = fixture.decorated.onSessionMessage(
        fixture.session,
        Long.MAX_VALUE,
        clusterFrame(bytes),
        AeronCluster.SESSION_HEADER_LENGTH,
        bytes.size,
        Header(0, 0),
    )

    private data class Fixture(
        val state: TradingState,
        val cluster: Cluster,
        val session: ClientSession,
        val service: DevelopmentFundingAdapter,
        val collector: FundingVerificationCollector,
        val decorated: ClusteredService,
        val replyCount: () -> Int,
        val failures: List<Throwable>,
    )
}
