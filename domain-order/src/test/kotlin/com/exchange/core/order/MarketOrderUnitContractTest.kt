package com.exchange.core.order

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
import com.exchange.core.fee.LiquidityRole
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MarketOrderUnitContractTest {
    private val feeReserve = TradingFeeReserveCalculator()
    private val reservationCalculator = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(feeReserve))
    private val settlementCalculator = OrderFillSettlementCalculator(TradingFeeCalculator(), feeReserve)
    private val policy =
        TradingFeePolicySnapshot(
            FeeProductType.SPOT,
            FeeTier.NORMAL,
            1,
            MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO),
        )

    @Test
    fun `기본 단위는 BUY SELL 모두 base 일부 수량을 거절하고 정수 수량을 허용한다`() {
        val market = MarketDefinition(MarketId("B-Q"), AssetId("B"), AssetId("Q"), 1)

        Side.entries.forEach { side ->
            assertFailsWith<IllegalArgumentException> { requirement(market, side, price = 10, quantity = 1) }
            val requirement = requirement(market, side, price = 11, quantity = 10)

            assertEquals(if (side == Side.BUY) AssetId("Q") else AssetId("B"), requirement.assetId)
            assertEquals(Amount(if (side == Side.BUY) 11 else 10), requirement.tradeReserveAmount)
            assertEquals(Amount.ZERO, requirement.feeReserveAmount)
        }
    }

    @Test
    fun `전체 대금이 정확해도 BUY SELL 가격이 마켓 tick과 다르면 거절한다`() {
        val market = market(tick = 10, step = 1)

        Side.entries.forEach { side ->
            assertFailsWith<IllegalArgumentException> { requirement(market, side, price = 11, quantity = 10) }
        }
    }

    @Test
    fun `전체 대금이 정확해도 BUY SELL 수량이 마켓 step과 다르면 거절한다`() {
        val market = market(tick = 2, step = 5)

        Side.entries.forEach { side ->
            assertFailsWith<IllegalArgumentException> { requirement(market, side, price = 20, quantity = 1) }
            assertFailsWith<IllegalArgumentException> { requirement(market, side, price = 20, quantity = 0) }
        }
    }

    @Test
    fun `큰 단위의 정확한 quote 대금은 BUY SELL 모두 overflow 없이 예약한다`() {
        val market = market(tick = 4_000_000_000_000_000_000, step = 5)

        val buy = requirement(market, Side.BUY, price = 4_000_000_000_000_000_000, quantity = 5)
        val sell = requirement(market, Side.SELL, price = 4_000_000_000_000_000_000, quantity = 5)

        assertEquals(Amount(2_000_000_000_000_000_000), buy.tradeReserveAmount)
        assertEquals(Amount(5), sell.tradeReserveAmount)
        assertEquals(Amount.ZERO, sell.feeReserveAmount)
    }

    @Test
    fun `0점1 base 부분 체결도 가격 개선분과 남은 예약을 정확히 유지한다`() {
        val market = market(tick = 10, step = 1)
        val buy = reservation(market, Side.BUY, price = 20, quantity = 10)
        val sell = reservation(market, Side.SELL, price = 10, quantity = 1)

        val buyPlan = settle(market, buy, price = 10, quantity = 1)
        val sellPlan = settle(market, sell, price = 10, quantity = 1)

        assertEquals(Amount(2), buyPlan.reservedAmountToReduce)
        assertEquals(Amount(1), buyPlan.holdAmountToConsume)
        assertEquals(Amount(1), buyPlan.holdAmountToRelease)
        assertEquals(Quantity(9), buyPlan.updatedReservation.remainingQuantity)
        assertEquals(Amount(18), buyPlan.updatedReservation.remainingAmount)
        assertEquals(Amount(1), buyPlan.creditAmount)
        assertEquals(Amount(1), sellPlan.holdAmountToConsume)
        assertEquals(Amount(1), sellPlan.creditAmount)
        assertEquals(OrderReservationStatus.SETTLED, sellPlan.updatedReservation.status)
        assertEquals(Quantity(10), buy.remainingQuantity)
        assertEquals(Amount(20), buy.remainingAmount)
    }

    @Test
    fun `가격2 수량5 단위의 연속 분할 체결은 BUY SELL 대금과 잔량을 정확히 정산한다`() {
        val market = market(tick = 2, step = 5)
        val original = reservation(market, Side.BUY, price = 12, quantity = 15)
        var current = original

        listOf(Triple(10L, 5L, 1L), Triple(8L, 4L, 2L), Triple(6L, 3L, 3L)).forEachIndexed { index, (price, amount, release) ->
            val buyPlan = settle(market, current, price, quantity = 5)
            val sellPlan = settle(market, reservation(market, Side.SELL, price, quantity = 5), price, quantity = 5)

            assertEquals(Amount(6), buyPlan.reservedAmountToReduce)
            assertEquals(Amount(amount), buyPlan.holdAmountToConsume)
            assertEquals(Amount(release), buyPlan.holdAmountToRelease)
            assertEquals(Quantity(10L - index * 5L), buyPlan.updatedReservation.remainingQuantity)
            assertEquals(Amount(12L - index * 6L), buyPlan.updatedReservation.remainingAmount)
            assertEquals(Amount(5), buyPlan.creditAmount)
            assertEquals(Amount(5), sellPlan.holdAmountToConsume)
            assertEquals(Amount(amount), sellPlan.creditAmount)
            assertEquals(OrderReservationStatus.SETTLED, sellPlan.updatedReservation.status)
            current = buyPlan.updatedReservation
        }

        assertEquals(OrderReservationStatus.SETTLED, current.status)
        assertEquals(Quantity(15), original.remainingQuantity)
        assertEquals(Amount(18), original.remainingAmount)
    }

    @Test
    fun `정산은 개별 대금이 정확해도 마켓 밖 실행 가격과 체결 수량을 거절한다`() {
        val market = market(tick = 2, step = 5)

        Side.entries.forEach { side ->
            val reservation = reservation(market, side, price = if (side == Side.BUY) 20 else 10, quantity = 15)
            val preserved = reservation.copy()

            assertFailsWith<IllegalArgumentException> { settle(market, reservation, price = 11, quantity = 10) }
            assertFailsWith<IllegalArgumentException> { settle(market, reservation, price = 10, quantity = 1) }
            assertEquals(preserved, reservation)
        }
    }

    @Test
    fun `정산은 예약의 원래 수량과 현재 잔량도 마켓 단위로 검증한다`() {
        val market = market(tick = 2, step = 5)

        Side.entries.forEach { side ->
            val reservation = reservation(market, side, price = if (side == Side.BUY) 20 else 10, quantity = 15)
            listOf(
                reservation.copy(initialQuantity = Quantity(16)),
                reservation.copy(remainingQuantity = Quantity(14)),
                reservation.copy(limitPrice = Price(if (side == Side.BUY) 21 else 9)),
            ).forEach { invalid ->
                assertFailsWith<IllegalArgumentException> { settle(market, invalid, price = 10, quantity = 5) }
            }
        }
    }

    private fun market(
        tick: Long,
        step: Long,
    ) = MarketDefinition(MarketId("B-Q"), AssetId("B"), AssetId("Q"), 1, tick, step)

    private fun requirement(
        market: MarketDefinition,
        side: Side,
        price: Long,
        quantity: Long,
    ) = reservationCalculator.calculate(market, side, Price(price), Quantity(quantity), policy)

    private fun reservation(
        market: MarketDefinition,
        side: Side,
        price: Long,
        quantity: Long,
    ) = OrderReservation.create(
        market.marketId,
        OrderId("order-${side.name}"),
        UserId("user-${side.name}"),
        side,
        Price(price),
        Quantity(quantity),
        requirement(market, side, price, quantity),
        policy,
    )

    private fun settle(
        market: MarketDefinition,
        reservation: OrderReservation,
        price: Long,
        quantity: Long,
    ) = settlementCalculator.calculate(market, reservation, Price(price), Quantity(quantity), LiquidityRole.MAKER)
}
