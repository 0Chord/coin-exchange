package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.common.Amount
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.ExecuteAdmission
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingState
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.AgentTerminationException
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.NanoClock
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedAdapterTelemetryTest {
    private val workload = TradingEngineProbeWorkload
    private val market = workload.markets.first()
    private val token = RequestToken(workload.buyer, 0, 1, 1)
    private val intent =
        OrderIntent(market.marketId, 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, workload.price, workload.quantity(market))

    @Test
    fun `재시도의 실제 전송 결과와 idle 시간을 기존 phase에 기록하고 최초 예약을 유지한다`() {
        val fixture =
            fixture(
                executionResults = listOf(Publication.BACK_PRESSURED, Publication.ADMIN_ACTION, 777),
                replyResults = listOf(Publication.BACK_PRESSURED, Publication.ADMIN_ACTION, 888),
            )

        fixture.send(BoundedTradingCommand.Admit(token, intent))

        val first = fixture.codec.decodeResponse(fixture.replies.single())
        assertEquals(AdmissionCode.ACCEPTED, first.admission?.code)
        val sample = fixture.samples.single()
        assertEquals(TradingNodeCallbackKind.ADMISSION, sample.kind)
        assertEquals(3, sample.executionOfferAttempts)
        assertEquals(1, sample.executionBackPressured)
        assertEquals(1, sample.executionAdminActions)
        assertEquals(777L, sample.executionOfferPosition)
        assertEquals(3, sample.replyAttempts)
        assertEquals(1, sample.replyBackPressured)
        assertEquals(1, sample.replyAdminActions)
        assertEquals(888L, sample.replyOfferPosition)
        assertEquals("OFFERED", sample.replyStatus)
        for (phase in listOf(TradingNodePhase.DECODE, TradingNodePhase.EXECUTION_ENCODE, TradingNodePhase.REPLY_ENCODE)) {
            assertTrue(sample.duration(phase) > 0, phase.name)
        }
        // 모의 idle마다 노드 시계가 1,000ns 진행한다. 비차단 publication 호출 시간뿐 아니라
        // 재시도 대기 시간도 포함해 보고해야 한다.
        assertTrue(sample.duration(TradingNodePhase.EXECUTION_OFFER) >= 2_000)
        assertTrue(sample.duration(TradingNodePhase.REPLY_OFFER) >= 2_000)
        assertEquals(0, sample.timingErrors)
        assertFalse(sample.failed)
        verify(fixture.idle, times(4)).idle()
        inOrder(fixture.idle).apply {
            verify(fixture.idle).reset()
            verify(fixture.idle, times(2)).idle()
            verify(fixture.idle).reset()
            verify(fixture.idle, times(2)).idle()
            verifyNoMoreInteractions()
        }
        fixture.executions.forEach { assertContentEquals(fixture.executions.first(), it) }
        fixture.attemptedReplies.forEach { assertContentEquals(fixture.attemptedReplies.first(), it) }
        assertReserved(fixture)

        val before = fixture.state.recoveryState()
        fixture.send(BoundedTradingCommand.Admit(token, intent))

        assertEquals(first.admission, fixture.codec.decodeResponse(fixture.replies.last()).admission)
        assertEquals(3, fixture.executions.size, "same-token replay must not submit another Execute")
        assertEquals(0, fixture.samples.last().executionOfferAttempts)
        assertEquals(-1L, fixture.samples.last().duration(TradingNodePhase.EXECUTION_ENCODE))
        assertEquals(before.balances, fixture.state.recoveryState().balances)
        assertEquals(before.liveOrders, fixture.state.recoveryState().liveOrders)
        assertTrue(fixture.service.telemetryOperational)
    }

    @Test
    fun `첫 전송에 성공하면 재시도 idle을 초기화하거나 호출하지 않는다`() {
        val fixture = fixture()

        fixture.send(BoundedTradingCommand.Admit(token, intent))

        val response = fixture.codec.decodeResponse(fixture.replies.single())
        assertEquals(AdmissionCode.ACCEPTED, response.admission?.code)
        verify(fixture.idle, times(0)).reset()
        verify(fixture.idle, times(0)).idle()
        assertReserved(fixture)
    }

    @Test
    fun `follower도 같은 내부 Execute를 제출하지만 외부 응답 encode와 offer를 기록하지 않는다`() {
        val fixture = fixture(role = Cluster.Role.FOLLOWER)

        fixture.send(BoundedTradingCommand.Admit(token, intent))

        val sample = fixture.samples.single()
        val command = fixture.codec.decode(fixture.executions.single()).command as BoundedTradingCommand.Execution
        assertEquals(ExecuteAdmission(haBoundedProbeManifest().lineage, market.marketId, 1, 1), command.command)
        assertEquals(1, sample.executionOfferAttempts)
        assertTrue(sample.duration(TradingNodePhase.EXECUTION_OFFER) > 0)
        assertEquals(0, sample.replyAttempts)
        assertEquals(-1L, sample.duration(TradingNodePhase.REPLY_ENCODE))
        assertEquals(-1L, sample.duration(TradingNodePhase.REPLY_OFFER))
        assertTrue(fixture.attemptedReplies.isEmpty())
        assertReserved(fixture)
    }

    @Test
    fun `내부 제출 상한과 종단 오류는 기존처럼 노드를 종료하며 예약을 임의 해제하지 않는다`() {
        val results =
            listOf(Publication.BACK_PRESSURED, Publication.NOT_CONNECTED, Publication.CLOSED, Publication.MAX_POSITION_EXCEEDED)
        for (result in results) {
            val attempts = if (result == Publication.BACK_PRESSURED) 3 else 1
            val fixture = fixture(executionResults = List(3) { result })

            assertFailsWith<AgentTerminationException> { fixture.send(BoundedTradingCommand.Admit(token, intent)) }

            val sample = fixture.samples.single()
            assertEquals(attempts, sample.executionOfferAttempts)
            assertEquals(if (result == Publication.BACK_PRESSURED) 3 else 0, sample.executionBackPressured)
            assertNull(sample.executionOfferPosition)
            assertEquals(0, sample.replyAttempts)
            assertTrue(sample.duration(TradingNodePhase.EXECUTION_OFFER) > 0)
            assertTrue(sample.failed)
            assertEquals(0, sample.timingErrors)
            assertFalse(fixture.service.started)
            assertEquals(1, fixture.failures.size)
            assertTrue(fixture.attemptedReplies.isEmpty())
            assertReserved(fixture)
        }
    }

    @Test
    fun `응답 재시도 상한은 금융 실패가 아니며 같은 토큰으로 최초 결과를 다시 받는다`() {
        val fixture = fixture(replyResults = List(3) { Publication.BACK_PRESSURED })

        fixture.send(BoundedTradingCommand.Admit(token, intent))

        val original = fixture.codec.decodeResponse(fixture.attemptedReplies.first()).admission
        val sample = fixture.samples.single()
        assertEquals("ATTEMPTS_EXHAUSTED", sample.replyStatus)
        assertEquals(3, sample.replyAttempts)
        assertEquals(3, sample.replyBackPressured)
        assertNull(sample.replyOfferPosition)
        assertTrue(sample.duration(TradingNodePhase.REPLY_OFFER) >= 2_000)
        assertFalse(sample.failed)
        assertTrue(fixture.replies.isEmpty())
        assertTrue(fixture.service.started)
        assertTrue(fixture.failures.isEmpty())
        assertReserved(fixture)
        val before = fixture.state.recoveryState()

        fixture.send(BoundedTradingCommand.Admit(token, intent))

        assertEquals(original, fixture.codec.decodeResponse(fixture.replies.single()).admission)
        assertEquals(1, fixture.executions.size)
        assertEquals(before.balances, fixture.state.recoveryState().balances)
        assertEquals(before.liveOrders, fixture.state.recoveryState().liveOrders)
    }

    @Test
    fun `자금 준비와 잘못된 외부 바이트도 기존 phase와 callback 분류로 관측한다`() {
        val fixture = fixture()
        val funding =
            DevelopmentBalanceCommandPayload(
                OpeningBalance("seller", workload.seller, market.baseAssetId, Amount(workload.quantity(market).value)),
                "opening-seller",
                workload.time,
            )

        fixture.send(BoundedTradingCommand.Funding(funding))

        val sample = fixture.samples.single()
        assertEquals("PREPARED", fixture.codec.decodeResponse(fixture.replies.single()).code)
        assertEquals(TradingNodeCallbackKind.FUNDING, sample.kind)
        assertTrue(sample.duration(TradingNodePhase.DECODE) > 0)
        assertTrue(sample.duration(TradingNodePhase.FUNDING_APPLY) > 0)
        val before = fixture.state.recoveryState()

        fixture.sendBytes(byteArrayOf(1, 2))

        val malformed = fixture.samples.last()
        assertEquals(TradingNodeCallbackKind.MALFORMED, malformed.kind)
        assertTrue(malformed.duration(TradingNodePhase.DECODE) > 0)
        assertEquals(-1L, malformed.duration(TradingNodePhase.FUNDING_APPLY))
        assertEquals(0, malformed.replyAttempts)
        assertEquals(before.balances, fixture.state.recoveryState().balances)
        assertEquals(before.preparations, fixture.state.recoveryState().preparations)
        assertTrue(fixture.service.started)
    }

    @Test
    fun `확정 내부 실행은 기존 실행 phase를 기록하고 과거 Execute는 재적용하지 않는다`() {
        val fixture = fixture()
        fixture.send(BoundedTradingCommand.Admit(token, intent))
        val execution = fixture.codec.decode(fixture.executions.single()).command

        fixture.send(execution, internal = true)

        val sample = fixture.samples.last()
        assertEquals(TradingNodeCallbackKind.EXECUTION, sample.kind)
        assertEquals(1L, sample.admissionSequence)
        assertTrue(sample.duration(TradingNodePhase.DECODE) > 0)
        assertTrue(sample.duration(TradingNodePhase.EXECUTION_TOTAL) > 0)
        assertEquals(0, sample.replyAttempts)
        assertEquals(0, sample.executionOfferAttempts)
        assertNotNull(fixture.service.observation?.compactExecution)
        val before = fixture.state.recoveryState()
        assertEquals(0, before.markets.sumOf { it.pendingAdmissionSequences.size })

        fixture.send(execution, internal = true)

        assertNull(fixture.service.observation?.compactExecution)
        assertEquals(before.balances, fixture.state.recoveryState().balances)
        assertEquals(before.liveOrders, fixture.state.recoveryState().liveOrders)
        assertEquals(1, fixture.executions.size)
        assertEquals(0, fixture.samples.last().timingErrors)
    }

    @Test
    fun `내부 Execute 본문이 같아도 원래 Aeron 헤더의 외부 세션과 다른 서비스 발신은 거절한다`() {
        val fixture = fixture()
        fixture.send(BoundedTradingCommand.Admit(token, intent))
        val bytes = fixture.executions.single()
        fixture.sendBytes(bytes, internal = false)
        assertEquals("UNAUTHORIZED_INTERNAL_COMMAND", fixture.service.observation?.replyStatus)
        assertEquals(TradingNodeCallbackKind.MALFORMED, fixture.samples.last().kind)
        assertReserved(fixture)
        fixture.sendBytes(bytes, internal = true)
        assertEquals(TradingNodeCallbackKind.EXECUTION, fixture.samples.last().kind)
        assertNotNull(fixture.service.observation?.compactExecution)

        val wrongService = this.fixture()
        wrongService.send(BoundedTradingCommand.Admit(token, intent))
        assertFailsWith<AgentTerminationException> {
            wrongService.sendBytes(wrongService.executions.single(), internal = true, serviceId = 1)
        }
        assertFalse(wrongService.service.started)
        assertReserved(wrongService)
    }

    private fun assertReserved(fixture: Fixture) {
        val balance = fixture.state.currentBalance(workload.buyer, workload.quote)
        assertEquals(Amount(1_000_000 - workload.RESERVE_PER_BUY), balance.available)
        assertEquals(Amount(workload.RESERVE_PER_BUY), balance.hold)
        val recovery = fixture.state.recoveryState()
        assertEquals(1, recovery.liveOrders.size)
        assertEquals(1, recovery.markets.sumOf { it.pendingAdmissionSequences.size })
    }

    private fun fixture(
        role: Cluster.Role = Cluster.Role.LEADER,
        executionResults: List<Long> = emptyList(),
        replyResults: List<Long> = emptyList(),
    ): Fixture {
        val calculators = workload.calculators()
        val state = TradingState(calculators.first, calculators.second, boundedMode = true)
        state.initialize(haBoundedProbeManifest())
        state.prepareDevelopmentBalance(
            OpeningBalance("buyer", workload.buyer, workload.quote, Amount(1_000_000)),
            "opening-buyer",
            workload.time,
        )
        state.rotateEpoch(workload.buyer, 0, 0)
        val cluster = mock(Cluster::class.java)
        val session = mock(ClientSession::class.java)
        val idle = mock(IdleStrategy::class.java)
        `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
        `when`(cluster.role()).thenReturn(role)
        `when`(cluster.logPosition()).thenReturn(1_024L)
        `when`(cluster.idleStrategy()).thenReturn(idle)
        val clock = AtomicLong(1)
        doAnswer {
            clock.addAndGet(1_000)
            null
        }.`when`(idle).idle()
        val executions = mutableListOf<ByteArray>()
        val attemptedReplies = mutableListOf<ByteArray>()
        val replies = mutableListOf<ByteArray>()
        val executionOffers = ArrayDeque(executionResults)
        val replyOffers = ArrayDeque(replyResults)
        doAnswer { invocation ->
            clock.addAndGet(100)
            val bytes = ByteArray(invocation.getArgument<Int>(2))
            invocation.getArgument<DirectBuffer>(0).getBytes(invocation.getArgument<Int>(1), bytes)
            executions.add(bytes)
            executionOffers.removeFirstOrNull() ?: 777L
        }.`when`(cluster).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
        doAnswer { invocation ->
            clock.addAndGet(100)
            val bytes = ByteArray(invocation.getArgument<Int>(2))
            invocation.getArgument<DirectBuffer>(0).getBytes(invocation.getArgument<Int>(1), bytes)
            attemptedReplies.add(bytes)
            val result = replyOffers.removeFirstOrNull() ?: 888L
            if (result > 0) replies.add(bytes)
            result
        }.`when`(session).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
        val samples = mutableListOf<TradingNodeCallbackTelemetry>()
        val failures = mutableListOf<Throwable>()
        val codec = BoundedTradingCodec()
        val service =
            DevelopmentFundingAdapter(
                state,
                maxReplyAttempts = 3,
                maxExecutionOfferAttempts = 3,
                telemetry = TradingNodeTelemetry { samples.add(it) },
                telemetryNanoTime = NanoClock { clock.getAndAdd(10) },
                boundedManifest = haBoundedProbeManifest(),
                trustedBoundedControl = true,
                onFatal = { failures.add(it) },
            )
        service.onStart(cluster, null)
        return Fixture(state, service, session, idle, executions, attemptedReplies, replies, samples, failures, codec)
    }

    private fun TradingNodeCallbackTelemetry.duration(phase: TradingNodePhase): Long = phaseDurationsNs[phase.ordinal]

    private class Fixture(
        val state: TradingState,
        val service: DevelopmentFundingAdapter,
        private val session: ClientSession,
        val idle: IdleStrategy,
        val executions: MutableList<ByteArray>,
        val attemptedReplies: MutableList<ByteArray>,
        val replies: MutableList<ByteArray>,
        val samples: MutableList<TradingNodeCallbackTelemetry>,
        val failures: MutableList<Throwable>,
        val codec: BoundedTradingCodec,
    ) {
        private var correlation = 1L

        fun send(
            command: BoundedTradingCommand,
            internal: Boolean = false,
        ) = sendBytes(codec.encode(if (command is BoundedTradingCommand.Execution) 0 else correlation++, command), internal)

        fun sendBytes(
            bytes: ByteArray,
            internal: Boolean = false,
            serviceId: Int = 0,
        ) {
            service.onSessionMessage(
                if (internal) null else session,
                TradingEngineProbeWorkload.time.toEpochMilli(),
                clusterFrame(bytes, if (internal) Long.MIN_VALUE or (serviceId.toLong() shl 56) else 1),
                AeronCluster.SESSION_HEADER_LENGTH,
                bytes.size,
                Header(0, 0),
            )
        }
    }
}
