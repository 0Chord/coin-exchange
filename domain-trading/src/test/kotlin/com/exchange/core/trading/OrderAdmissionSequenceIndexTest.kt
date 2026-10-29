package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 원본 조회와 Long 순번 경계를 실제 접수 경로로 검사하며, 메모리 효과를 측정하지 않는다. */
class OrderAdmissionSequenceIndexTest {
    private val user = UserId("sequence-user")
    private val quote = AssetId("USDT")
    private val market = MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), quote, 0)
    private val policy =
        TradingFeePolicySnapshot(
            FeeProductType.SPOT,
            FeeTier.NORMAL,
            1,
            MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO),
        )
    private val time = Instant.parse("2026-10-27T00:00:00Z")
    private val calculator =
        OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())))

    @Test
    fun `첫 구간의 양끝과 다음 구간의 첫 승인도 같은 원본을 돌려주며 대기 해제 뒤에도 보존한다`() {
        val state = state()
        val accepted = (1..1_025).map { admit(state, "dense-$it").record }

        listOf(1L, 1_023L, 1_024L, 1_025L).forEach { sequence ->
            val original = accepted[(sequence - 1).toInt()]
            assertSame(original, state.accepted(sequence))
            assertSame(original, state.accepted(market.marketId, original.candidate.order.orderId))
            assertSame(original.candidate, state.accepted(sequence)!!.candidate)
        }
        val before = state.snapshot()
        listOf(Long.MIN_VALUE, -1L, 0L, 1_026L, 2_049L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE).forEach {
            assertNull(state.accepted(it))
        }
        assertEquals(before, state.snapshot())

        state.completePending(accepted.first())

        assertSame(accepted.first(), state.accepted(1L))
        assertSame(accepted.last(), state.accepted(1_025L))
        assertEquals(1_024, state.pendingOrders(market.marketId).size)
        assertEquals(1_025L, state.snapshot().lastSequence)
    }

    @Test
    fun `Int 경계와 Int로 접히는 먼 Long 구간과 Long 최대값까지 기존 원본을 덮어쓰지 않는다`() {
        val state = state()
        val first = admit(state, "first").record
        // (sequence - 1) / 1024 = 2^32. 구간 키를 Int로 줄이면 첫 구간과 충돌한다.
        val distant = 4_398_046_511_105L
        val sequences =
            listOf(
                Int.MAX_VALUE.toLong() - 1,
                Int.MAX_VALUE.toLong(),
                Int.MAX_VALUE.toLong() + 1,
                Int.MAX_VALUE.toLong() + 2,
                distant,
                distant + 1,
                Long.MAX_VALUE - 1,
                Long.MAX_VALUE,
            )
        val originals = linkedMapOf(1L to first)
        sequences.forEachIndexed { index, sequence ->
            setLastSequence(state, sequence - 1)
            val record = admit(state, "sparse-$index").record
            assertEquals(sequence, record.sequence)
            originals[sequence] = record
            originals.forEach { (key, original) -> assertSame(original, state.accepted(key)) }
        }
        listOf(Long.MIN_VALUE, -1L, 0L, 2L, 1_024L, distant - 1, distant + 2, Long.MAX_VALUE - 2).forEach {
            assertNull(state.accepted(it))
        }
        val before = state.snapshot()

        assertFailsWith<ArithmeticException> { admit(state, "overflow") }

        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
        assertFalse(state.hasRequest(user, "request-overflow"))
        assertSame(originals.getValue(Long.MAX_VALUE), state.accepted(Long.MAX_VALUE))
        assertSame(first, state.accepted(1L))
    }

    @Test
    fun `없는 Long 순번 조회는 비어 있는 상태를 바꾸거나 범위를 좁히지 않는다`() {
        val state = state()
        val before = state.snapshot()

        listOf(Long.MIN_VALUE, -1L, 0L, 1L, 1_024L, 1_025L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE).forEach {
            assertNull(state.accepted(it))
        }

        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
        val first = admit(state, "after-missing").record
        assertEquals(1L, first.sequence)
        assertSame(first, state.accepted(1L))
    }

    @Test
    fun `새 구간 등록과 기존 구간 참조 쓰기의 전후 Exception과 Error는 정상 조회와 재전송을 차단한다`() {
        listOf(false, true).forEach { newSegment ->
            listOf(false, true).forEach { afterWrite ->
                listOf(false, true).forEach { error ->
                    val state = state()
                    val first = admit(state, "first").record
                    if (newSegment) setLastSequence(state, 1_024)
                    val before = state.snapshot()
                    val failure = if (error) AssertionError("순번 쓰기 Error") else IllegalStateException("순번 쓰기 실패")
                    failSequenceWrite(state, newSegment, afterWrite, failure)

                    assertSame(failure, assertFailsWith<Throwable> { admit(state, "failing") })

                    assertTrue(state.isBlocked)
                    listOf(Long.MIN_VALUE, 0L, 1L, 2L, Long.MAX_VALUE).forEach {
                        assertFailsWith<IllegalStateException> { state.accepted(it) }
                    }
                    assertFailsWith<IllegalStateException> { state.accepted(market.marketId, first.candidate.order.orderId) }
                    assertFailsWith<IllegalStateException> { admit(state, "first") }
                    assertFailsWith<IllegalStateException> { admit(state, "failing") }
                    assertFailsWith<IllegalStateException> { admit(state, "new") }
                    assertFailsWith<IllegalStateException> { state.snapshot() }
                    assertFailsWith<IllegalStateException> { state.pendingOrders(market.marketId) }
                    assertFailsWith<IllegalStateException> { state.hasRequest(user, "request-first") }
                    // SQL rollback을 가정하지 않는다. 오류 전에 반환한 값만 그대로 관찰한다.
                    assertSame(first, before.pendingOrders.single())
                    assertEquals(Amount(9_999), before.balances.single().available)
                }
            }
        }
    }

    @Test
    fun `구간 양쪽의 실제 매칭 정산과 완료 재실행도 같은 최초 결과와 잔고를 보존한다`() {
        val fixture = TradingStateFixtures
        val state = fixture.state()
        fixture.fund(state, fixture.seller, fixture.btc, 1_024)
        fixture.fund(state, fixture.buyer, fixture.usdt, 1_024)
        val sell = fixture.admit(state, fixture.order("boundary-maker", fixture.seller, Side.SELL, 1, 1_024))
        state.executePending(sell.record.sequence, fixture.time)
        val completions =
            (1..1_024).map { index ->
                val input = fixture.order("boundary-buy-$index", fixture.buyer, Side.BUY, 1, 1)
                val accepted = fixture.admit(state, input)
                assertEquals(index + 1L, accepted.record.sequence)
                val completion = state.executePending(accepted.record.sequence, fixture.time)
                assertSame(accepted.record, completion.admission)
                assertSame(accepted.record, fixture.admit(state, input).record)
                completion
            }
        val before = state.snapshot()
        assertEquals(1_025L, before.lastAdmissionSequence)
        assertEquals(1_025, before.completions.size)
        assertEquals(2_051, before.ledgerTransactions.size)
        assertEquals(emptyList(), before.pendingOrders)
        assertEquals(emptyList(), state.book(fixture.market.marketId))
        assertEquals(
            setOf(
                fixture.balance(fixture.buyer, fixture.usdt, 0),
                fixture.balance(fixture.buyer, fixture.btc, 1_024),
                fixture.balance(fixture.seller, fixture.btc, 0),
                fixture.balance(fixture.seller, fixture.usdt, 1_024),
            ),
            before.balances.toSet(),
        )
        listOf(1_024L, 1_025L).forEach { sequence ->
            assertSame(completions[(sequence - 2).toInt()], state.executePending(sequence, fixture.time.plusSeconds(10)))
        }
        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
    }

    private fun state() = OrderAdmissionState(listOf(Balance(user, quote, Amount(10_000), Amount.ZERO)), calculator)

    private fun admit(
        state: OrderAdmissionState,
        id: String,
    ): OrderAdmissionState.Result.Accepted =
        assertIs<OrderAdmissionState.Result.Accepted>(
            state.admit(
                "request-$id",
                OrderAdmissionInput(
                    market.marketId,
                    OrderId(id),
                    user,
                    Side.BUY,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    Price(1),
                    Quantity(1),
                ),
                market,
                policy,
                "ledger-$id",
                time,
            ),
        )

    private fun setLastSequence(
        state: OrderAdmissionState,
        sequence: Long,
    ) = field("lastSequence").setLong(state, sequence)

    /** 공개 장애 API 없이 실제 구간 map put 또는 실제 참조 set 한 지점만 통제한다. */
    private fun failSequenceWrite(
        state: OrderAdmissionState,
        newSegment: Boolean,
        afterWrite: Boolean,
        failure: Throwable,
    ) {
        @Suppress("UNCHECKED_CAST")
        val original = field("recordsBySequence").get(state) as MutableMap<Long, MutableList<OrderAdmissionState.Record?>>
        if (newSegment) {
            field("recordsBySequence").set(
                state,
                object : MutableMap<Long, MutableList<OrderAdmissionState.Record?>> by original {
                    override fun put(
                        key: Long,
                        value: MutableList<OrderAdmissionState.Record?>,
                    ): MutableList<OrderAdmissionState.Record?>? {
                        if (afterWrite) original[key] = value
                        throw failure
                    }
                },
            )
        } else {
            val segment = original.getValue(0L)
            original[0L] =
                object : MutableList<OrderAdmissionState.Record?> by segment {
                    override fun set(
                        index: Int,
                        element: OrderAdmissionState.Record?,
                    ): OrderAdmissionState.Record? {
                        if (afterWrite) segment[index] = element
                        throw failure
                    }
                }
        }
    }

    private fun field(name: String) = OrderAdmissionState::class.java.getDeclaredField(name).apply { isAccessible = true }
}
