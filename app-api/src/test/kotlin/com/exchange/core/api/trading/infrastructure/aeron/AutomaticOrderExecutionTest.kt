package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionCodec
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.matching.MatchingEngine
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.TradingState
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.client.ClusterException
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.AgentTerminationException
import org.agrona.concurrent.IdleStrategy
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 돈·매칭·정산은 실물 코어, 전송 반환값과 callback 시점만 대역으로 통제한다. */
class AutomaticOrderExecutionTest {
    private val acceptedAt = Instant.parse("2026-10-26T00:00:00Z")
    private val commands = AdmissionCommandCodec()
    private val executions = ExecuteAdmissionCodec()

    @Test
    fun `신규 SELL은 내부 제출 뒤 접수 응답하고 확정 실행 전에는 대기와 예약만 남는다`() {
        val f = fixture()
        fund(f, "seller", "BTC", 1)
        val sell = command(f, "S1", "seller", Side.SELL, 100, 1)

        val accepted = assertIs<AdmissionResultPayload.Accepted>(admit(f, sell))

        assertEquals(1L, accepted.sequence)
        assertEquals(listOf("INTERNAL", "REPLY"), f.trace)
        assertEquals(ExecuteAdmissionPayload(1), executions.decode(f.offers.single()))
        assertEquals(
            1,
            f.state
                .snapshot()
                .pendingOrders.size,
        )
        assertTrue(
            f.state
                .snapshot()
                .completions
                .isEmpty(),
        )
        assertTrue(f.state.book(sell.market.marketId).isEmpty())
        assertBalance(f, "seller", "BTC", 0, 1)

        execute(f, 0, timestamp = 2_000)

        val snapshot = f.state.snapshot()
        assertTrue(snapshot.pendingOrders.isEmpty())
        assertEquals(1, snapshot.completions.size)
        assertEquals(Instant.ofEpochMilli(2_000), snapshot.completions.single().occurredAt)
        assertEquals(listOf("S1"), f.state.book(sell.market.marketId).map { it.orderId.value })
        assertEquals(
            listOf(LedgerTransactionType.OPENING, LedgerTransactionType.RESERVE),
            snapshot.ledgerTransactions.map { it.transactionType },
        )
        assertEquals(1L, f.service.observation?.executionSequence)
        assertEquals("EXECUTED", f.service.observation?.replyStatus)
        assertTrue(f.failures.isEmpty())
    }

    @Test
    fun `SELL100 BUY100은 기존 접수와 내부 callback만으로 잔고와 정산 원장을 완성한다`() {
        val f = fullTrade()

        assertBalance(f, "buyer", "USDT", 900, 0)
        assertBalance(f, "buyer", "BTC", 1, 0)
        assertBalance(f, "seller", "USDT", 100, 0)
        assertBalance(f, "seller", "BTC", 0, 0)
        val snapshot = f.state.snapshot()
        assertTrue(snapshot.pendingOrders.isEmpty())
        assertTrue(f.state.book(f.context.market.marketId).isEmpty())
        assertEquals(2, snapshot.completions.size)
        assertEquals(2L, snapshot.lastAdmissionSequence)
        assertEquals(2, snapshot.ledgerTransactions.count { it.transactionType == LedgerTransactionType.OPENING })
        assertEquals(2, snapshot.ledgerTransactions.count { it.transactionType == LedgerTransactionType.RESERVE })
        assertEquals(1, snapshot.ledgerTransactions.count { it.transactionType == LedgerTransactionType.SETTLEMENT })
        assertTrue(snapshot.reservations.all { it.remainingAmount.value == 0L })
        assertEquals(2, f.offers.size)
    }

    @Test
    fun `90과 95의 체결을 이어 계산하고 BUY 잔량1은 예약100과 함께 호가에 남는다`() {
        val f = fixture()
        fund(f, "buyer", "USDT", 1_000)
        fund(f, "seller", "BTC", 2)
        admitAndExecute(f, command(f, "S1", "seller", Side.SELL, 90, 1))
        admitAndExecute(f, command(f, "S2", "seller", Side.SELL, 95, 1))

        admitAndExecute(f, command(f, "B1", "buyer", Side.BUY, 100, 3))

        assertBalance(f, "buyer", "USDT", 715, 100)
        assertBalance(f, "buyer", "BTC", 2, 0)
        assertBalance(f, "seller", "USDT", 185, 0)
        assertBalance(f, "seller", "BTC", 0, 0)
        val resting = f.state.book(f.context.market.marketId).single()
        assertEquals("B1", resting.orderId.value)
        assertEquals(Side.BUY, resting.side)
        assertEquals(1L, resting.remainingQuantity.value)
        val snapshot = f.state.snapshot()
        assertTrue(snapshot.pendingOrders.isEmpty())
        assertEquals(3, snapshot.completions.size)
        assertEquals(2, snapshot.ledgerTransactions.count { it.transactionType == LedgerTransactionType.SETTLEMENT })
        assertEquals(
            listOf(90L, 95L),
            snapshot.completions
                .last()
                .settlement.settlements
                .map { it.execution.price.value },
        )
    }

    @Test
    fun `접수 당시 1000ppm 정책으로 양쪽 수수료10과 수익 원장20을 반영한다`() {
        val f = fixture(feePpm = 1_000)
        fund(f, "buyer", "USDT", 100_000)
        fund(f, "seller", "BTC", 1)
        admitAndExecute(f, command(f, "S1", "seller", Side.SELL, 10_000, 1))

        admitAndExecute(f, command(f, "B1", "buyer", Side.BUY, 10_000, 1))

        assertBalance(f, "buyer", "USDT", 89_990, 0)
        assertBalance(f, "buyer", "BTC", 1, 0)
        assertBalance(f, "seller", "USDT", 9_990, 0)
        assertBalance(f, "seller", "BTC", 0, 0)
        val settlement =
            f.state
                .snapshot()
                .ledgerTransactions
                .single { it.transactionType == LedgerTransactionType.SETTLEMENT }
        assertEquals(20L, settlement.postings.filter { it.accountId == "SYSTEM:USDT:FEE_REVENUE" }.sumOf { it.amount.value })
        assertTrue(
            f.state
                .snapshot()
                .reservations
                .all { it.remainingAmount.value == 0L },
        )
    }

    @Test
    fun `외부 재전송은 내부 명령을 늘리지 않고 내부 재전송은 최초 완료와 시각을 재사용한다`() {
        val f = fullTrade()
        val buy = command(f, "B1", "buyer", Side.BUY, 100, 1)
        val before = f.state.snapshot()
        val completed = before.completions.last()
        val engineSequence = f.state.lastEngineSequence(f.context.market.marketId)

        val replay = assertIs<AdmissionResultPayload.Accepted>(admit(f, buy))
        execute(f, 1, timestamp = 99_999)

        val after = f.state.snapshot()
        assertEquals(2L, replay.sequence)
        assertEquals(2, f.offers.size)
        assertSame(completed, after.completions.last())
        assertEquals(Instant.ofEpochMilli(2_001), after.completions.last().occurredAt)
        assertEquals(before.balances, after.balances)
        assertEquals(before.reservations, after.reservations)
        assertEquals(before.ledgerTransactions, after.ledgerTransactions)
        assertEquals(before.requests, after.requests)
        assertEquals(engineSequence, f.state.lastEngineSequence(f.context.market.marketId))
        assertTrue(after.pendingOrders.isEmpty())
    }

    @Test
    fun `역압력과 ADMIN_ACTION은 같은 바이트와 버퍼를 재시도하고 수용만으로 실행하지 않는다`() {
        val f = fixture(offerResults = listOf(Publication.BACK_PRESSURED, Publication.ADMIN_ACTION, 1L))
        fund(f, "seller", "BTC", 1)

        assertIs<AdmissionResultPayload.Accepted>(admit(f, command(f, "S1", "seller", Side.SELL, 100, 1)))

        assertEquals(3, f.offers.size)
        f.offers.forEach { assertContentEquals(f.offers.first(), it) }
        f.offerBuffers.forEach { assertSame(f.offerBuffers.first(), it) }
        verify(f.idle, times(2)).idle()
        assertEquals(listOf("INTERNAL", "INTERNAL", "INTERNAL", "REPLY"), f.trace)
        assertEquals(
            1,
            f.state
                .snapshot()
                .pendingOrders.size,
        )
        assertTrue(
            f.state
                .snapshot()
                .completions
                .isEmpty(),
        )

        execute(f, 0)

        assertEquals(
            1,
            f.state
                .snapshot()
                .completions.size,
        )
        assertEquals(3, f.offers.size, "실행 callback은 새 실행을 제출하지 않는다")
    }

    @Test
    fun `내부 제출 횟수 초과는 예약을 유지하고 노드 실패를 알리며 접수 성공을 보내지 않는다`() {
        val f = fixture(offerResults = listOf(Publication.BACK_PRESSURED), maxExecutionAttempts = 3)
        fund(f, "seller", "BTC", 1)

        val fatal =
            assertFailsWith<AgentTerminationException> {
                admit(f, command(f, "S1", "seller", Side.SELL, 100, 1))
            }

        assertEquals(3, f.offers.size)
        assertSame(fatal.cause, f.failures.single())
        assertFalse(f.service.started)
        assertFalse(f.state.isBlocked, "전송 실패는 돈 계산 오류와 다르다")
        assertBalance(f, "seller", "BTC", 0, 1)
        assertEquals(
            1,
            f.state
                .snapshot()
                .pendingOrders.size,
        )
        assertTrue(
            f.state
                .snapshot()
                .completions
                .isEmpty(),
        )
        assertTrue(f.replies.isEmpty())
        assertFailsWith<AgentTerminationException> { execute(f, 0) }
        assertEquals(1, f.failures.size)
    }

    @Test
    fun `내부 제출 시간 한도를 넘기면 같은 콜백을 무한 반복하지 않는다`() {
        val f = fixture(offerResults = listOf(Publication.BACK_PRESSURED), executionTimeoutNs = 1_000_000)
        doAnswer { Thread.sleep(20) }.`when`(f.idle).idle()
        fund(f, "seller", "BTC", 1)

        assertFailsWith<AgentTerminationException> { admit(f, command(f, "S1", "seller", Side.SELL, 100, 1)) }

        assertEquals(1, f.offers.size)
        assertFalse(f.service.started)
        assertTrue(f.replies.isEmpty())
        assertBalance(f, "seller", "BTC", 0, 1)
    }

    @Test
    fun `내부 전송의 종단 반환과 예외는 정상 거절로 변환하지 않는다`() {
        listOf(Publication.NOT_CONNECTED, Publication.CLOSED, Publication.MAX_POSITION_EXCEEDED, 0L).forEach { terminal ->
            val f = fixture(offerResults = listOf(terminal))
            fund(f, "seller", "BTC", 1)
            assertFailsWith<AgentTerminationException> { admit(f, command(f, "S1", "seller", Side.SELL, 100, 1)) }
            assertEquals(1, f.offers.size)
            assertEquals(1, f.failures.size)
            assertFalse(f.service.started)
            assertTrue(f.replies.isEmpty())
            assertBalance(f, "seller", "BTC", 0, 1)
        }
        val failure = ClusterException("통제한 내부 publication 오류")
        val f = fixture(offerFailure = failure)
        fund(f, "seller", "BTC", 1)
        val fatal = assertFailsWith<AgentTerminationException> { admit(f, command(f, "S1", "seller", Side.SELL, 100, 1)) }
        assertSame(failure, fatal.cause)
        assertSame(failure, f.failures.single())
        assertTrue(f.replies.isEmpty())
        assertBalance(f, "seller", "BTC", 0, 1)
    }

    @Test
    fun `팔로워도 같은 실행 명령을 제출하고 확정 입력을 적용하지만 외부 응답은 보내지 않는다`() {
        val f = fixture(role = Cluster.Role.FOLLOWER)
        fund(f, "seller", "BTC", 1)

        assertIs<AdmissionResultPayload.Accepted>(admit(f, command(f, "S1", "seller", Side.SELL, 100, 1)))

        assertEquals(1, f.offers.size)
        assertTrue(f.replies.isEmpty())
        assertEquals("NOT_LEADER", f.service.observation?.replyStatus)
        execute(f, 0)
        assertEquals(
            1,
            f.state
                .snapshot()
                .completions.size,
        )
        assertEquals("FOLLOWER", f.service.observation?.appliedRole)
        assertTrue(f.failures.isEmpty())
    }

    @Test
    fun `접수 응답 전송이 예외여도 내부 실행 의도는 먼저 수용되어 있다`() {
        val failure = IllegalStateException("통제한 외부 응답 오류")
        val f = fixture(replyFailure = failure)
        fund(f, "seller", "BTC", 1)

        val fatal = assertFailsWith<AgentTerminationException> { admit(f, command(f, "S1", "seller", Side.SELL, 100, 1)) }

        assertSame(failure, fatal.cause)
        assertEquals(listOf("INTERNAL", "REPLY"), f.trace)
        assertEquals(1, f.offers.size)
        assertEquals(ExecuteAdmissionPayload(1), executions.decode(f.offers.single()))
        assertEquals(
            1,
            f.state
                .snapshot()
                .pendingOrders.size,
        )
        assertTrue(
            f.state
                .snapshot()
                .completions
                .isEmpty(),
        )
        assertBalance(f, "seller", "BTC", 0, 1)
        assertFalse(f.service.started)
    }

    @Test
    fun `정상 거절과 그 재전송은 실행을 제출하지 않고 새 요청의 승인은 제출한다`() {
        val f = fixture()
        val buy = command(f, "B1", "buyer", Side.BUY, 100, 1)

        val rejected = assertIs<AdmissionResultPayload.Rejected>(admit(f, buy))
        fund(f, "buyer", "USDT", 1_000)
        assertEquals(rejected, admit(f, buy))

        assertEquals(AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE, rejected.reason)
        assertTrue(f.offers.isEmpty())
        assertTrue(
            f.state
                .snapshot()
                .pendingOrders
                .isEmpty(),
        )
        assertBalance(f, "buyer", "USDT", 1_000, 0)
        val fresh = buy.copy(requestId = "new-request")
        assertIs<AdmissionResultPayload.Accepted>(admit(f, fresh))
        assertEquals(1, f.offers.size)
        assertBalance(f, "buyer", "USDT", 900, 100)
    }

    @Test
    fun `신뢰한 내부 입력의 손상과 없는 승인은 외부 malformed처럼 건너뛰지 않는다`() {
        val wrongKindFixture = fixture()
        val wrongKind = commands.encode(command(wrongKindFixture, "B1", "buyer", Side.BUY, 100, 1))
        listOf(ByteArray(7), executions.encode(ExecuteAdmissionPayload(99)), wrongKind).forEach { bytes ->
            val f = fixture()
            val fatal = assertFailsWith<AgentTerminationException> { dispatch(f, bytes, true) }
            assertSame(fatal.cause, f.failures.single())
            assertFalse(f.service.started)
            assertEquals(0L, f.service.observation?.malformedMessages)
            assertNotNull(f.service.observation?.failure)
            assertTrue(
                f.state
                    .snapshot()
                    .balances
                    .isEmpty(),
            )
            assertTrue(f.replies.isEmpty())
        }
    }

    @Test
    fun `같은 마켓의 앞 대기를 건너뛴 내부 실행은 돈을 바꾸지 않고 해당 노드를 실패시킨다`() {
        val f = fixture()
        fund(f, "buyer", "USDT", 1_000)
        admit(f, command(f, "B1", "buyer", Side.BUY, 100, 1))
        admit(f, command(f, "B2", "buyer", Side.BUY, 100, 1))
        val before = f.state.snapshot()

        assertFailsWith<AgentTerminationException> { execute(f, 1) }

        assertFalse(f.service.started)
        assertFalse(f.state.isBlocked)
        assertEquals(before, f.state.snapshot())
        assertBalance(f, "buyer", "USDT", 800, 200)
        assertTrue(f.state.book(f.context.market.marketId).isEmpty())
        assertEquals(1, f.failures.size)
    }

    @Test
    fun `정산 계산 overflow는 실제 값을 보존하되 공통 상태와 해당 노드를 차단한다`() {
        val f = fixture()
        fund(f, "seller", "BTC", 1)
        fund(f, "seller", "USDT", Long.MAX_VALUE)
        fund(f, "buyer", "USDT", 1_000)
        admitAndExecute(f, command(f, "S1", "seller", Side.SELL, 100, 1))
        admit(f, command(f, "B1", "buyer", Side.BUY, 100, 1))
        val before = rawStateValues(f.state)

        val fatal = assertFailsWith<AgentTerminationException> { execute(f, 1) }

        assertIs<ArithmeticException>(fatal.cause)
        assertSame(fatal.cause, f.failures.single())
        assertEquals(before, rawStateValues(f.state), "계산 오류는 실제 잔고·예약·원장·주문장 반영 전이다")
        assertBlocked(f)
    }

    @Test
    fun `실제 잔고 put 직후 오류는 일부 쓰기를 남기고 공통 상태와 해당 노드를 차단한다`() {
        val f = fixture()
        fund(f, "seller", "BTC", 1)
        fund(f, "buyer", "USDT", 1_000)
        admitAndExecute(f, command(f, "S1", "seller", Side.SELL, 100, 1))
        admit(f, command(f, "B1", "buyer", Side.BUY, 100, 1))
        val before = rawStateValues(f.state)
        val failure = IllegalStateException("실제 잔고 쓰기 직후 실패")
        val admission = readPrivate(f.state, "admission")
        val balancesField = admission.javaClass.getDeclaredField("balances").apply { isAccessible = true }
        val balances = mutableMap(admission, "balances")
        var writes = 0
        balancesField.set(
            admission,
            object : MutableMap<Any, Any> by balances {
                override fun put(
                    key: Any,
                    value: Any,
                ): Any? {
                    balances[key] = value
                    writes++
                    throw failure
                }
            },
        )

        val fatal = assertFailsWith<AgentTerminationException> { execute(f, 1) }

        assertSame(failure, fatal.cause)
        assertSame(failure, f.failures.single())
        assertEquals(1, writes)
        assertNotEquals(before, rawStateValues(f.state), "실제 쓰기를 마친 오류를 원상복구됐다고 취급하지 않는다")
        assertBlocked(f)
    }

    @Test
    fun `밀리초가 아닌 Cluster clock은 거래 처리 전에 노드를 실패시킨다`() {
        val state = tradingState()
        val failures = mutableListOf<Throwable>()
        val service = DevelopmentFundingAdapter(state, onFatal = { failures += it })
        val cluster = mock(Cluster::class.java)
        `when`(cluster.timeUnit()).thenReturn(TimeUnit.MICROSECONDS)

        val fatal = assertFailsWith<AgentTerminationException> { service.onStart(cluster, null) }

        assertIs<IllegalStateException>(fatal.cause)
        assertSame(fatal.cause, failures.single())
        assertFalse(service.started)
        assertTrue(state.snapshot().balances.isEmpty())
    }

    @Test
    fun `새 Cluster의 null clock으로 시작한 뒤 밀리초 leadership term을 받으면 정상 주문을 처리한다`() {
        val f = fixture(start = false, clockUnit = null)

        f.service.onStart(f.cluster, null)
        assertTrue(f.service.started)
        f.service.onNewLeadershipTermEvent(1, 0, 1_000, 0, 0, 42, TimeUnit.MILLISECONDS, 0)
        `when`(f.cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
        fund(f, "seller", "BTC", 1)

        assertIs<AdmissionResultPayload.Accepted>(admit(f, command(f, "S1", "seller", Side.SELL, 100, 1)))
        assertEquals(1, f.offers.size)
        assertTrue(f.failures.isEmpty())
    }

    @Test
    fun `null clock이 업무 시점까지 남거나 새 term이 마이크로초이면 해당 노드가 실패한다`() {
        listOf(false, true).forEach { invalidTerm ->
            val f = fixture(start = false, clockUnit = null)
            f.service.onStart(f.cluster, null)

            val fatal =
                assertFailsWith<AgentTerminationException> {
                    if (invalidTerm) {
                        f.service.onNewLeadershipTermEvent(1, 0, 1_000, 0, 0, 42, TimeUnit.MICROSECONDS, 0)
                    } else {
                        admit(f, command(f, "B1", "buyer", Side.BUY, 100, 1))
                    }
                }

            assertIs<IllegalStateException>(fatal.cause)
            assertSame(fatal.cause, f.failures.single())
            assertFalse(f.service.started)
            assertTrue(
                f.state
                    .snapshot()
                    .balances
                    .isEmpty(),
            )
            assertTrue(
                f.state
                    .snapshot()
                    .requests
                    .isEmpty(),
            )
            assertTrue(f.offers.isEmpty())
        }
    }

    private fun fullTrade(): Fixture {
        val f = fixture()
        fund(f, "buyer", "USDT", 1_000)
        fund(f, "seller", "BTC", 1)
        admitAndExecute(f, command(f, "S1", "seller", Side.SELL, 100, 1))
        admitAndExecute(f, command(f, "B1", "buyer", Side.BUY, 100, 1))
        return f
    }

    private fun fixture(
        role: Cluster.Role = Cluster.Role.LEADER,
        feePpm: Long = 0,
        offerResults: List<Long> = listOf(1L),
        offerFailure: Throwable? = null,
        replyFailure: Throwable? = null,
        maxExecutionAttempts: Int = 1_000,
        executionTimeoutNs: Long = TimeUnit.SECONDS.toNanos(1),
        start: Boolean = true,
        clockUnit: TimeUnit? = TimeUnit.MILLISECONDS,
    ): Fixture {
        val context =
            DevelopmentMarketContext.BTC_USDT.let { original ->
                original.copy(
                    market = MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), AssetId("USDT"), 0),
                    feePolicySnapshot = original.feePolicySnapshot.copy(feeRates = MakerTakerFeeRates(FeeRate(feePpm), FeeRate(feePpm))),
                )
            }
        val state = tradingState()
        val cluster = mock(Cluster::class.java)
        val session = mock(ClientSession::class.java)
        val idle = mock(IdleStrategy::class.java)
        val offers = mutableListOf<ByteArray>()
        val offerBuffers = mutableListOf<DirectBuffer>()
        val replies = mutableListOf<ByteArray>()
        val trace = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        `when`(cluster.role()).thenReturn(role)
        `when`(cluster.timeUnit()).thenReturn(clockUnit)
        `when`(cluster.logPosition()).thenReturn(128L)
        `when`(cluster.idleStrategy()).thenReturn(idle)
        doAnswer { call ->
            trace += "INTERNAL"
            val buffer = call.getArgument<DirectBuffer>(0)
            val bytes = ByteArray(call.getArgument<Int>(2))
            buffer.getBytes(call.getArgument<Int>(1), bytes)
            offers += bytes
            offerBuffers += buffer
            if (offerFailure != null) throw offerFailure
            offerResults[(offers.size - 1).coerceAtMost(offerResults.lastIndex)]
        }.`when`(cluster).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
        doAnswer { call ->
            trace += "REPLY"
            val buffer = call.getArgument<DirectBuffer>(0)
            val bytes = ByteArray(call.getArgument<Int>(2))
            buffer.getBytes(call.getArgument<Int>(1), bytes)
            replies += bytes
            if (replyFailure != null) throw replyFailure
            1L
        }.`when`(session).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
        val service =
            DevelopmentFundingAdapter(
                state,
                markets = DevelopmentMarketRegistry(listOf(context)),
                maxExecutionOfferAttempts = maxExecutionAttempts,
                executionOfferTimeoutNs = executionTimeoutNs,
                onFatal = { failures += it },
            )
        if (start) service.onStart(cluster, null)
        return Fixture(state, context, cluster, session, idle, service, offers, offerBuffers, replies, trace, failures)
    }

    private fun fund(
        f: Fixture,
        user: String,
        asset: String,
        amount: Long,
    ) {
        f.state.prepareDevelopmentBalance(
            OpeningBalance("P-$user-$asset", UserId(user), AssetId(asset), Amount(amount)),
            "OPEN-$user-$asset",
            acceptedAt,
        )
    }

    private fun command(
        f: Fixture,
        orderId: String,
        user: String,
        side: Side,
        price: Long,
        quantity: Long,
    ) = AdmissionCommandPayload(
        "R-$orderId",
        OrderAdmissionInput(
            f.context.market.marketId,
            OrderId(orderId),
            UserId(user),
            side,
            OrderType.LIMIT,
            TimeInForce.GTC,
            Price(price),
            Quantity(quantity),
        ),
        f.context.market,
        f.context.feePolicySnapshot,
        "RESERVE-$orderId",
        acceptedAt,
    )

    private fun admit(
        f: Fixture,
        command: AdmissionCommandPayload,
    ): AdmissionResultPayload {
        dispatch(f, commands.encode(command), false)
        return assertNotNull(f.service.observation?.admissionResult)
    }

    private fun admitAndExecute(
        f: Fixture,
        command: AdmissionCommandPayload,
    ) {
        assertIs<AdmissionResultPayload.Accepted>(admit(f, command))
        execute(f, f.offers.lastIndex, timestamp = 2_000L + f.offers.lastIndex)
    }

    private fun execute(
        f: Fixture,
        offerIndex: Int,
        timestamp: Long = 2_000,
    ) = dispatch(f, f.offers[offerIndex], true, timestamp)

    private fun dispatch(
        f: Fixture,
        bytes: ByteArray,
        internal: Boolean,
        timestamp: Long = 1_000,
    ) {
        val buffer = clusterFrame(bytes, if (internal) Long.MIN_VALUE + 1 else 1)
        f.service.onSessionMessage(
            if (internal) null else f.session,
            timestamp,
            buffer,
            AeronCluster.SESSION_HEADER_LENGTH,
            bytes.size,
            mock(Header::class.java),
        )
    }

    private fun assertBalance(
        f: Fixture,
        user: String,
        asset: String,
        available: Long,
        hold: Long,
    ) {
        val balance =
            f.state
                .snapshot()
                .balances
                .single { it.userId.value == user && it.assetId.value == asset }
        assertEquals(available, balance.available.value, "$user $asset 사용 가능")
        assertEquals(hold, balance.hold.value, "$user $asset 예약")
    }

    private fun assertBlocked(f: Fixture) {
        assertTrue(f.state.isBlocked)
        assertFalse(f.service.started)
        assertTrue(assertNotNull(f.service.observation).blocked)
        assertEquals(null, f.service.observation?.snapshot)
        assertFailsWith<IllegalStateException> { f.state.snapshot() }
        assertFailsWith<IllegalStateException> { f.state.book(f.context.market.marketId) }
        assertFailsWith<AgentTerminationException> { dispatch(f, f.offers.last(), true) }
        assertEquals(1, f.failures.size)
    }

    /** 실패 뒤 정상 조회가 금지되므로 테스트 안에서만 실제 저장 필드를 복사해 대조한다. */
    private fun rawStateValues(state: TradingState): List<Any> {
        val admission = readPrivate(state, "admission")
        val engine = readPrivate(state, "engine") as MatchingEngine
        return listOf(
            mutableMap(admission, "balances").toMap(),
            mutableMap(admission, "currentReservations").toMap(),
            (readPrivate(admission, "ledgerTransactions") as List<*>).toList(),
            mutableMap(state, "completions").toMap(),
            mutableMap(admission, "pendingByMarket").mapValues { (_, pending) -> (pending as Collection<*>).toList() },
            engine.restingOrders(MarketId("BTC-USDT")),
            engine.lastEngineSequence(MarketId("BTC-USDT")),
        )
    }

    private fun readPrivate(
        owner: Any,
        name: String,
    ): Any =
        assertNotNull(
            owner.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(owner),
        )

    @Suppress("UNCHECKED_CAST")
    private fun mutableMap(
        owner: Any,
        name: String,
    ): MutableMap<Any, Any> = readPrivate(owner, name) as MutableMap<Any, Any>

    private data class Fixture(
        val state: TradingState,
        val context: DevelopmentMarketContext,
        val cluster: Cluster,
        val session: ClientSession,
        val idle: IdleStrategy,
        val service: DevelopmentFundingAdapter,
        val offers: MutableList<ByteArray>,
        val offerBuffers: MutableList<DirectBuffer>,
        val replies: MutableList<ByteArray>,
        val trace: MutableList<String>,
        val failures: MutableList<Throwable>,
    )
}
