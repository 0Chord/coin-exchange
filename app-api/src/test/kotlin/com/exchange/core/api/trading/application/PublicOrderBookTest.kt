package com.exchange.core.api.trading.application

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsCancelDispatch
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MarketMatchingIntakeLimits
import com.exchange.core.trading.MatchingIntakeConfiguration
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.MatchingIntakeStatus
import com.exchange.core.trading.MatchingOrderEffect
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PublicOrderBookTest {
    @Test
    fun `같은 가격의 주문은 합쳐지고 부분 체결과 잔량 취소는 각각 한 번 차감된다`() {
        val fixture = Market()
        val book = PublicOrderBook(fixture.stream)
        val first = fixture.input(1, Side.BUY, 100, 3)
        val second = fixture.input(2, Side.BUY, 100, 2)
        book.apply(fixture.apply(first))
        book.apply(fixture.apply(second))
        val initial = book.snapshot()
        assertEquals(PublicOrderBookSnapshot(2, listOf(PublicOrderBookLevel(100, "5")), emptyList()), initial)

        book.apply(fixture.apply(fixture.input(3, Side.SELL, 100, 1)))
        assertEquals(PublicOrderBookSnapshot(3, listOf(PublicOrderBookLevel(100, "4")), emptyList()), book.snapshot())

        val cancel = fixture.cancel(second, dispatchSequence = 4, cancelSequence = 1)
        book.apply(fixture.apply(cancel))
        val afterCancel = PublicOrderBookSnapshot(4, listOf(PublicOrderBookLevel(100, "2")), emptyList())
        assertEquals(afterCancel, book.snapshot())
        assertEquals(MatchingIntakeStatus.DUPLICATE, fixture.owner.apply(cancel).status)
        assertEquals(afterCancel, book.snapshot())
        assertEquals(listOf(PublicOrderBookLevel(100, "5")), initial.bids)
        assertFailsWith<UnsupportedOperationException> { (initial.bids as MutableList<*>).clear() }
    }

    @Test
    fun `여러 가격대가 체결되면 maker 지정가와 신규 taker에 남은 잔량을 각각 반영한다`() {
        val fixture = Market()
        val book = PublicOrderBook(fixture.stream)
        book.apply(fixture.apply(fixture.input(1, Side.SELL, 90, 1)))
        book.apply(fixture.apply(fixture.input(2, Side.SELL, 95, 2)))
        val before = book.snapshot()
        val taker = fixture.apply(fixture.input(3, Side.BUY, 100, 4))
        book.apply(taker)

        assertEquals(listOf(PublicOrderBookLevel(90, "1"), PublicOrderBookLevel(95, "2")), before.asks)
        assertEquals(PublicOrderBookSnapshot(3, listOf(PublicOrderBookLevel(100, "1")), emptyList()), book.snapshot())
    }

    @Test
    fun `매수와 매도 상위 열 개를 정렬하고 최우선 호가를 취소하면 열한 번째가 나타난다`() {
        val fixture = Market()
        val book = PublicOrderBook(fixture.stream)
        val bids = (0L..10L).map { offset -> fixture.input(offset + 1, Side.BUY, 90 - offset, 1) }
        val asks = (0L..10L).map { offset -> fixture.input(offset + 12, Side.SELL, 100 + offset, 1) }
        for (input in bids + asks) {
            book.apply(fixture.apply(input))
        }
        val before = book.snapshot()
        assertEquals((90L downTo 81L).toList(), before.bids.map { it.price })
        assertEquals((100L..109L).toList(), before.asks.map { it.price })

        book.apply(fixture.apply(fixture.cancel(bids.first(), dispatchSequence = 23, cancelSequence = 1)))
        book.apply(fixture.apply(fixture.cancel(asks.first(), dispatchSequence = 24, cancelSequence = 2)))
        val after = book.snapshot()
        assertEquals((89L downTo 80L).toList(), after.bids.map { it.price })
        assertEquals((101L..110L).toList(), after.asks.map { it.price })
    }

    @Test
    fun `최종 거절과 취소 거절은 가격별 잔량을 바꾸지 않고 결과 순번을 진행시킨다`() {
        val fixture = Market(maxActiveOrders = 1)
        val book = PublicOrderBook(fixture.stream)
        val first = fixture.input(1, Side.BUY, 100, 3)
        book.apply(fixture.apply(first))

        val rejected = fixture.apply(fixture.input(2, Side.BUY, 99, 1))
        assertEquals(MatchingOutcome.NOT_EXECUTED, rejected.outcome)
        book.apply(rejected)
        assertEquals(PublicOrderBookSnapshot(2, listOf(PublicOrderBookLevel(100, "3")), emptyList()), book.snapshot())

        book.apply(fixture.apply(fixture.cancel(first, dispatchSequence = 3, cancelSequence = 1)))
        val rejectedCancel = fixture.apply(fixture.cancel(first, dispatchSequence = 4, cancelSequence = 2))
        assertEquals(MatchingOutcome.CANCEL_REJECTED, rejectedCancel.outcome)
        book.apply(rejectedCancel)
        assertEquals(PublicOrderBookSnapshot(4, emptyList(), emptyList()), book.snapshot())
    }

    @Test
    fun `한 가격대의 전체 수량이 Long 범위를 넘더라도 정확하게 집계한다`() {
        val fixture = Market()
        val book = PublicOrderBook(fixture.stream)
        book.apply(fixture.apply(fixture.input(1, Side.SELL, 1, Long.MAX_VALUE)))
        book.apply(fixture.apply(fixture.input(2, Side.SELL, 1, Long.MAX_VALUE)))
        assertEquals(listOf(PublicOrderBookLevel(1, "18446744073709551614")), book.snapshot().asks)
    }

    @Test
    fun `다른 계보나 순번 공백을 받으면 현재 호가와 위치를 유지한다`() {
        val fixture = Market()
        val book = PublicOrderBook(fixture.stream)
        val first = fixture.apply(fixture.input(1, Side.BUY, 100, 3))
        val second = fixture.apply(fixture.input(2, Side.BUY, 100, 2))
        val unrelated = copyResult(first, stream = first.stream.copy(ownershipEpoch = 2))
        assertFailsWith<IllegalArgumentException> { book.apply(second) }
        assertFailsWith<IllegalArgumentException> { book.apply(unrelated) }
        assertEquals(PublicOrderBookSnapshot(0, emptyList(), emptyList()), book.snapshot())
        book.apply(first)
        book.apply(second)
        assertFailsWith<IllegalArgumentException> { book.apply(second) }
        assertEquals(PublicOrderBookSnapshot(2, listOf(PublicOrderBookLevel(100, "5")), emptyList()), book.snapshot())
    }

    @Test
    fun `한 결과에서 두 번째 가격대의 수량이 틀리면 첫 가격대도 바꾸지 않는다`() {
        val fixture = Market()
        val book = PublicOrderBook(fixture.stream)
        book.apply(fixture.apply(fixture.input(1, Side.SELL, 90, 1)))
        book.apply(fixture.apply(fixture.input(2, Side.SELL, 95, 1)))
        val before = book.snapshot()
        val taker = fixture.apply(fixture.input(3, Side.BUY, 100, 3))
        val effects = taker.effects.toMutableList()
        effects[2] = effects[2].copy(beforeQuantity = Quantity(2))
        assertFailsWith<IllegalArgumentException> { book.apply(copyResult(taker, effects = effects)) }
        assertEquals(before, book.snapshot())
        book.apply(taker)
        assertEquals(PublicOrderBookSnapshot(3, listOf(PublicOrderBookLevel(100, "1")), emptyList()), book.snapshot())
    }

    private fun copyResult(
        result: MatchingResultEnvelope,
        stream: MatchingResultStream = result.stream,
        effects: List<MatchingOrderEffect> = result.effects,
    ) = MatchingResultEnvelope(
        stream = stream,
        resultSequence = result.resultSequence,
        input = result.input,
        outcome = result.outcome,
        reason = result.reason,
        engineSequenceBefore = result.engineSequenceBefore,
        engineSequenceAfter = result.engineSequenceAfter,
        events = result.events,
        effects = effects,
    )

    private class Market(
        maxActiveOrders: Int = 4_096,
    ) {
        private val funds = ClusterLineage(1, 2)
        private val matching = ClusterLineage(3, 4)
        private val market = MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), AssetId("USDT"), 0)
        private val fees = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
        private val context = TradingMarketContext(market, fees, 0)
        private val manifest =
            TradingClusterManifest(funds, "depth-test", "0".repeat(64), "1".repeat(64), 1, listOf(context), TradingResourceLimits())
        private val topology = TradingRoleTopology(manifest, listOf(TradingMarketAssignment(market.marketId, matching, 1)))
        private val declaration =
            TradingRoleDeclaration(TradingServiceRole.MATCHING, matching, 1, listOf(context), mapOf(market.marketId to 1L))
        private val configuration =
            MatchingIntakeConfiguration(
                topology,
                declaration,
                MatchingIntakeLimits(mapOf(market.marketId to MarketMatchingIntakeLimits(maxActiveOrders = maxActiveOrders))),
                inputContractVersion = 2,
                archiveQueryEnabled = true,
            )
        private val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))
        val stream = configuration.resultStream(market.marketId)
        val owner = ReservedMatchingState(configuration).apply { initialize() }

        fun input(
            sequence: Long,
            side: Side,
            price: Long,
            quantity: Long,
        ): FundsOrderDispatch {
            val order =
                OrderAdmissionInput(
                    market.marketId,
                    TradingIdentifiers.order(funds, sequence),
                    UserId("user-$sequence"),
                    side,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    Price(price),
                    Quantity(quantity),
                )
            val requirement = reservations.calculate(market, side, order.price, order.quantity, fees)
            val reservation =
                OrderReservation.create(market.marketId, order.orderId, order.userId, side, order.price, order.quantity, requirement, fees)
            return FundsOrderDispatch(
                stream = configuration.stream(market.marketId),
                dispatchSequence = sequence,
                admissionSequence = sequence,
                marketAdmissionSequence = sequence,
                order = order,
                reservation = reservation,
                context = context,
                inputContractVersion = 2,
            )
        }

        fun cancel(
            input: FundsOrderDispatch,
            dispatchSequence: Long,
            cancelSequence: Long,
        ): FundsOrderDispatch =
            input.copy(
                dispatchSequence = dispatchSequence,
                cancel =
                    FundsCancelDispatch(
                        cancelId = TradingIdentifiers.cancel(funds, cancelSequence),
                        requestToken = RequestToken(input.order.userId, channel = 0, epoch = 1, sequence = cancelSequence),
                        cancelSequence = cancelSequence,
                        targetInputSequence = input.dispatchSequence,
                    ),
            )

        fun apply(input: FundsOrderDispatch): MatchingResultEnvelope {
            val reply = owner.apply(input)
            assertEquals(MatchingIntakeStatus.APPLIED, reply.status)
            return assertNotNull(reply.result)
        }
    }
}
