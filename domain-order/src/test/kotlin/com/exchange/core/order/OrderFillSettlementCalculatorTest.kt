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

class OrderFillSettlementCalculatorTest {
    private val feeFreePolicySnapshot =
        TradingFeePolicySnapshot(
            productType = FeeProductType.SPOT,
            feeTier = FeeTier.NORMAL,
            scheduleVersion = 1,
            feeRates =
                MakerTakerFeeRates(
                    makerFeeRate = FeeRate.ZERO,
                    takerFeeRate = FeeRate.ZERO,
                ),
        )

    private val calculator =
        OrderFillSettlementCalculator(
            tradingFeeCalculator = TradingFeeCalculator(),
            tradingFeeReserveCalculator = TradingFeeReserveCalculator(),
        )

    private val market =
        MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 0,
        )

    @Test
    fun `BUY 체결은 실제 대금을 소비하고 가격 개선분을 반환한다`() {
        val reservation = buyReservation()

        val plan =
            calculator.calculate(
                market = market,
                reservation = reservation,
                executionPrice = Price(90),
                filledQuantity = Quantity(2),
                liquidityRole = LiquidityRole.MAKER,
            )

        assertEquals(
            Amount(200),
            plan.reservedAmountToReduce,
        )
        assertEquals(
            Amount(180),
            plan.holdAmountToConsume,
        )
        assertEquals(
            Amount(20),
            plan.holdAmountToRelease,
        )

        assertEquals(
            AssetId("BTC"),
            plan.creditAssetId,
        )
        assertEquals(
            Amount(2),
            plan.creditAmount,
        )

        assertEquals(
            Quantity(3),
            plan.updatedReservation.remainingQuantity,
        )
        assertEquals(
            Amount(300),
            plan.updatedReservation.remainingAmount,
        )
        assertEquals(
            OrderReservationStatus.ACTIVE,
            plan.updatedReservation.status,
        )
    }

    @Test
    fun `SELL 체결은 base hold를 소비하고 quote 대금을 지급한다`() {
        val reservation = sellReservation()

        val plan =
            calculator.calculate(
                market = market,
                reservation = reservation,
                executionPrice = Price(90),
                filledQuantity = Quantity(2),
                liquidityRole = LiquidityRole.MAKER,
            )

        assertEquals(
            Amount(2),
            plan.reservedAmountToReduce,
        )
        assertEquals(
            Amount(2),
            plan.holdAmountToConsume,
        )
        assertEquals(
            Amount.ZERO,
            plan.holdAmountToRelease,
        )

        assertEquals(
            AssetId("KRW"),
            plan.creditAssetId,
        )
        assertEquals(
            Amount(180),
            plan.creditAmount,
        )

        assertEquals(
            Quantity(3),
            plan.updatedReservation.remainingQuantity,
        )
        assertEquals(
            Amount(3),
            plan.updatedReservation.remainingAmount,
        )
        assertEquals(
            OrderReservationStatus.ACTIVE,
            plan.updatedReservation.status,
        )
    }

    @Test
    fun `전량 체결은 reservation을 SETTLED로 만든다`() {
        val reservation = buyReservation()

        val plan =
            calculator.calculate(
                market = market,
                reservation = reservation,
                executionPrice = Price(90),
                filledQuantity = Quantity(5),
                liquidityRole = LiquidityRole.MAKER,
            )

        assertEquals(
            Quantity.ZERO,
            plan.updatedReservation.remainingQuantity,
        )
        assertEquals(
            Amount.ZERO,
            plan.updatedReservation.remainingAmount,
        )
        assertEquals(
            OrderReservationStatus.SETTLED,
            plan.updatedReservation.status,
        )

        assertEquals(
            Amount(500),
            plan.reservedAmountToReduce,
        )
        assertEquals(
            Amount(450),
            plan.holdAmountToConsume,
        )
        assertEquals(
            Amount(50),
            plan.holdAmountToRelease,
        )
    }

    @Test
    fun `BUY 주문은 지정가보다 비싼 가격으로 체결할 수 없다`() {
        val reservation = buyReservation()

        assertFailsWith<IllegalArgumentException> {
            calculator.calculate(
                market = market,
                reservation = reservation,
                executionPrice = Price(101),
                filledQuantity = Quantity(1),
                liquidityRole = LiquidityRole.MAKER,
            )
        }
    }

    @Test
    fun `SELL 주문은 지정가보다 싼 가격으로 체결할 수 없다`() {
        val reservation = sellReservation()

        assertFailsWith<IllegalArgumentException> {
            calculator.calculate(
                market = market,
                reservation = reservation,
                executionPrice = Price(79),
                filledQuantity = Quantity(1),
                liquidityRole = LiquidityRole.MAKER,
            )
        }
    }

    @Test
    fun `reservation과 market이 다르면 정산할 수 없다`() {
        val reservation = buyReservation()

        val otherMarket =
            MarketDefinition(
                marketId = MarketId("ETH-KRW"),
                baseAssetId = AssetId("ETH"),
                quoteAssetId = AssetId("KRW"),
                baseAssetScale = 0,
            )

        assertFailsWith<IllegalArgumentException> {
            calculator.calculate(
                market = otherMarket,
                reservation = reservation,
                executionPrice = Price(90),
                filledQuantity = Quantity(1),
                liquidityRole = LiquidityRole.MAKER,
            )
        }
    }

    @Test
    fun `taker BUY 체결은 실제 수수료를 소비하고 남는 예약액을 반환한다`() {
        val feePolicySnapshot =
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 1,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate(5_000),
                        takerFeeRate = FeeRate(10_000),
                    ),
            )

        val reservation =
            OrderReservation.create(
                marketId = market.marketId,
                orderId = OrderId("buy-order-with-fee"),
                userId = UserId("buyer"),
                side = Side.BUY,
                limitPrice = Price(100_000),
                quantity = Quantity(5),
                requirement =
                    ReservationRequirement(
                        assetId = market.quoteAssetId,
                        tradeReserveAmount = Amount(500_000),
                        feeReserveAmount = Amount(5_000),
                    ),
                feePolicySnapshot = feePolicySnapshot,
            )

        val plan =
            calculator.calculate(
                market = market,
                reservation = reservation,
                executionPrice = Price(90_000),
                filledQuantity = Quantity(2),
                liquidityRole = LiquidityRole.TAKER,
            )

        assertEquals(
            Amount(202_000),
            plan.reservedAmountToReduce,
        )
        assertEquals(
            Amount(181_800),
            plan.holdAmountToConsume,
        )
        assertEquals(
            Amount(20_200),
            plan.holdAmountToRelease,
        )

        assertEquals(
            Quantity(3),
            plan.updatedReservation.remainingQuantity,
        )
        assertEquals(
            Amount(303_000),
            plan.updatedReservation.remainingAmount,
        )
        assertEquals(
            Amount(3_000),
            plan.updatedReservation.remainingFeeReserveAmount,
        )
        assertEquals(
            OrderReservationStatus.ACTIVE,
            plan.updatedReservation.status,
        )

        assertEquals(
            market.baseAssetId,
            plan.creditAssetId,
        )
        assertEquals(
            Amount(2),
            plan.creditAmount,
        )
    }

    private fun buyReservation(): OrderReservation =
        OrderReservation.create(
            marketId = market.marketId,
            orderId = OrderId("buy-order"),
            userId = UserId("buyer"),
            side = Side.BUY,
            limitPrice = Price(100),
            quantity = Quantity(5),
            requirement =
                ReservationRequirement(
                    assetId = market.quoteAssetId,
                    amount = Amount(500),
                ),
            feePolicySnapshot = feeFreePolicySnapshot,
        )

    private fun sellReservation(): OrderReservation =
        OrderReservation.create(
            marketId = market.marketId,
            orderId = OrderId("sell-order"),
            userId = UserId("seller"),
            side = Side.SELL,
            limitPrice = Price(80),
            quantity = Quantity(5),
            requirement =
                ReservationRequirement(
                    assetId = market.baseAssetId,
                    amount = Amount(5),
                ),
            feePolicySnapshot = feeFreePolicySnapshot,
        )
}
