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
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.LiquidityRole
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * BUY/SELL 체결의 예약 감소, hold 소비·반환과 지급액을 검사한다.
 * 실제 수수료의 자산·금액도 확인하며, 무료 정책에서는 수수료가 0인지 검사한다.
 * SELL 부분 체결은 이전 소수 나머지가 다음 정산의 청구액과 순지급액에 반영되는지 확인한다.
 * BUY 부분 체결은 누적 수수료를 청구하면서 남은 주문에 필요한 예약액을 유지하는지 확인한다.
 */
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
            AssetId("KRW"),
            plan.feeAssetId,
        )
        assertEquals(
            Amount.ZERO,
            plan.actualFeeAmount,
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
            AssetId("KRW"),
            plan.feeAssetId,
        )
        assertEquals(
            Amount.ZERO,
            plan.actualFeeAmount,
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

        assertEquals(
            AssetId("KRW"),
            plan.feeAssetId,
        )
        assertEquals(
            Amount(1_800),
            plan.actualFeeAmount,
        )
    }

    @Test
    fun `maker SELL 체결은 판매 대금에서 실제 수수료를 차감한다`() {
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
                orderId = OrderId("sell-order-with-fee"),
                userId = UserId("seller"),
                side = Side.SELL,
                limitPrice = Price(80_000),
                quantity = Quantity(5),
                requirement =
                    ReservationRequirement(
                        assetId = market.baseAssetId,
                        tradeReserveAmount = Amount(5),
                        feeReserveAmount = Amount.ZERO,
                    ),
                feePolicySnapshot = feePolicySnapshot,
            )

        val plan =
            calculator.calculate(
                market = market,
                reservation = reservation,
                executionPrice = Price(90_000),
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
            market.quoteAssetId,
            plan.creditAssetId,
        )
        assertEquals(
            Amount(179_100),
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
            Amount.ZERO,
            plan.updatedReservation.remainingFeeReserveAmount,
        )
        assertEquals(
            OrderReservationStatus.ACTIVE,
            plan.updatedReservation.status,
        )

        assertEquals(
            AssetId("KRW"),
            plan.feeAssetId,
        )
        assertEquals(
            Amount(900),
            plan.actualFeeAmount,
        )
    }

    @Test
    fun `SELL 부분 체결은 이전 수수료 나머지를 합산해 순지급액을 계산한다`() {
        val reservation =
            sellReservation().copy(
                limitPrice = Price(51),
                feePolicySnapshot =
                    feeFreePolicySnapshot.copy(
                        feeRates =
                            MakerTakerFeeRates(
                                makerFeeRate = FeeRate(10_000),
                                takerFeeRate = FeeRate(10_000),
                            ),
                    ),
            )

        val firstPlan =
            calculator.calculate(
                market = market,
                reservation = reservation,
                executionPrice = Price(51),
                filledQuantity = Quantity(1),
                liquidityRole = LiquidityRole.MAKER,
            )

        // 첫 정산에서 갱신된 주문 예약을 전달해 나머지가 다음 계산으로 이어지게 한다.
        val secondPlan =
            calculator.calculate(
                market = market,
                reservation = firstPlan.updatedReservation,
                executionPrice = Price(51),
                filledQuantity = Quantity(1),
                liquidityRole = LiquidityRole.MAKER,
            )

        assertEquals(
            Amount.ZERO,
            firstPlan.actualFeeAmount,
        )
        assertEquals(
            Amount(51),
            firstPlan.creditAmount,
        )
        assertEquals(
            FeeRemainder(510_000),
            firstPlan.updatedReservation.feeRemainder,
        )

        assertEquals(
            Amount(1),
            secondPlan.actualFeeAmount,
        )
        assertEquals(
            Amount(50),
            secondPlan.creditAmount,
        )
        assertEquals(
            FeeRemainder(20_000),
            secondPlan.updatedReservation.feeRemainder,
        )
    }

    @Test
    fun `BUY 분할 체결은 필요한 수수료 예약액을 유지하고 전량 정산한다`() {
        val feePolicySnapshot =
            feeFreePolicySnapshot.copy(
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate(5_000),
                        takerFeeRate = FeeRate(10_000),
                    ),
            )

        var reservation =
            OrderReservation.create(
                marketId = market.marketId,
                orderId = OrderId("buy-order-with-fee-remainder"),
                userId = UserId("buyer"),
                side = Side.BUY,
                limitPrice = Price(51),
                quantity = Quantity(5),
                requirement =
                    ReservationRequirement(
                        assetId = market.quoteAssetId,
                        tradeReserveAmount = Amount(255),
                        feeReserveAmount = Amount(3),
                    ),
                feePolicySnapshot = feePolicySnapshot,
            )

        val plans = mutableListOf<OrderFillSettlementPlan>()

        // 255의 대금을 네 번 나누어 체결해도 수수료 합계는 버림한 2가 되어야 한다.
        for (filledQuantityValue in listOf(1L, 1L, 1L, 2L)) {
            val plan =
                calculator.calculate(
                    market = market,
                    reservation = reservation,
                    executionPrice = Price(51),
                    filledQuantity = Quantity(filledQuantityValue),
                    liquidityRole = LiquidityRole.TAKER,
                )

            plans.add(plan)

            // 새 예약에 보관된 나머지와 수수료 예약액을 다음 체결에서 이어받는다.
            reservation = plan.updatedReservation
        }

        assertEquals(
            listOf(0L, 1L, 0L, 1L),
            plans.map { it.actualFeeAmount.value },
        )

        assertEquals(
            listOf(510_000L, 20_000L, 530_000L, 550_000L),
            plans.map { it.updatedReservation.feeRemainder.numerator },
        )

        assertEquals(
            listOf(3L, 2L, 2L, 0L),
            plans.map { it.updatedReservation.remainingFeeReserveAmount.value },
        )

        assertEquals(
            listOf(51L, 52L, 51L, 103L),
            plans.map { it.holdAmountToConsume.value },
        )

        assertEquals(
            listOf(0L, 0L, 0L, 1L),
            plans.map { it.holdAmountToRelease.value },
        )

        assertEquals(
            Quantity.ZERO,
            reservation.remainingQuantity,
        )
        assertEquals(
            Amount.ZERO,
            reservation.remainingAmount,
        )
        assertEquals(
            OrderReservationStatus.SETTLED,
            reservation.status,
        )
    }

    @Test
    fun `BUY 부분 체결은 이월 나머지가 올림 경계를 넘으면 수수료 예약을 유지한다`() {
        val reservation =
            OrderReservation.create(
                marketId = market.marketId,
                orderId = OrderId("buy-order-with-reserve-rounding"),
                userId = UserId("buyer"),
                side = Side.BUY,
                limitPrice = Price(80),
                quantity = Quantity(2),
                requirement =
                    ReservationRequirement(
                        assetId = market.quoteAssetId,
                        tradeReserveAmount = Amount(160),
                        feeReserveAmount = Amount(2),
                    ),
                feePolicySnapshot =
                    feeFreePolicySnapshot.copy(
                        feeRates =
                            MakerTakerFeeRates(
                                makerFeeRate = FeeRate(10_000),
                                takerFeeRate = FeeRate(10_000),
                            ),
                    ),
            )

        val firstPlan =
            calculator.calculate(
                market = market,
                reservation = reservation,
                executionPrice = Price(80),
                filledQuantity = Quantity(1),
                liquidityRole = LiquidityRole.TAKER,
            )

        // 남은 대금의 수수료 0.8 + 이월된 0.8을 올림한 2를 유지해야 한다.
        assertEquals(
            Amount.ZERO,
            firstPlan.actualFeeAmount,
        )
        assertEquals(
            FeeRemainder(800_000),
            firstPlan.updatedReservation.feeRemainder,
        )
        assertEquals(
            Amount(2),
            firstPlan.updatedReservation.remainingFeeReserveAmount,
        )
        assertEquals(
            Amount(82),
            firstPlan.updatedReservation.remainingAmount,
        )
        assertEquals(
            Amount.ZERO,
            firstPlan.holdAmountToRelease,
        )

        val finalPlan =
            calculator.calculate(
                market = market,
                reservation = firstPlan.updatedReservation,
                executionPrice = Price(80),
                filledQuantity = Quantity(1),
                liquidityRole = LiquidityRole.TAKER,
            )

        assertEquals(
            Amount(1),
            finalPlan.actualFeeAmount,
        )
        assertEquals(
            FeeRemainder(600_000),
            finalPlan.updatedReservation.feeRemainder,
        )
        assertEquals(
            Amount(81),
            finalPlan.holdAmountToConsume,
        )
        assertEquals(
            Amount(1),
            finalPlan.holdAmountToRelease,
        )
        assertEquals(
            Amount.ZERO,
            finalPlan.updatedReservation.remainingFeeReserveAmount,
        )
        assertEquals(
            Amount.ZERO,
            finalPlan.updatedReservation.remainingAmount,
        )
        assertEquals(
            OrderReservationStatus.SETTLED,
            finalPlan.updatedReservation.status,
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
