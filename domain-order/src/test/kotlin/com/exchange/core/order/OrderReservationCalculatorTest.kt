package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrderReservationCalculatorTest {
    private val calculator =
        OrderReservationCalculator(
            buyOrderFundingQuoteCalculator =
                BuyOrderFundingQuoteCalculator(
                    feeReserveCalculator = TradingFeeReserveCalculator(),
                ),
        )

    private val feePolicySnapshot =
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

    @Test
    fun `BUY 주문은 quote asset의 거래 대금과 최대 수수료를 예약한다`() {
        val market = MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 0,
        )

        val requirement = calculator.calculate(
            market = market,
            side = Side.BUY,
            price = Price(100),
            quantity = Quantity(5),
            feePolicySnapshot = feePolicySnapshot,
        )

        assertEquals(
            ReservationRequirement(
                assetId = AssetId("KRW"),
                tradeReserveAmount = Amount(500),
                feeReserveAmount = Amount(5),
            ),
            requirement,
        )
    }

    @Test
    fun `SELL 주문은 base asset을 동결한다`() {
        val market = MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 0,
        )

        val requirement = calculator.calculate(
            market = market,
            side = Side.SELL,
            price = Price(100),
            quantity = Quantity(5),
            feePolicySnapshot = feePolicySnapshot,
        )

        assertEquals(
            ReservationRequirement(
                assetId = AssetId("BTC"),
                tradeReserveAmount = Amount(5),
                feeReserveAmount = Amount.ZERO,
            ),
            requirement,
        )
    }

    @Test
    fun `BUY 동결 금액은 base asset scale을 적용한다`() {
        val market = MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 8,
        )

        val requirement = calculator.calculate(
            market = market,
            side = Side.BUY,
            price = Price(100_000_000),
            quantity = Quantity(50_000_000),
            feePolicySnapshot = feePolicySnapshot,
        )

        assertEquals(
            ReservationRequirement(
                assetId = AssetId("KRW"),
                tradeReserveAmount = Amount(50_000_000),
                feeReserveAmount = Amount(500_000),
            ),
            requirement,
        )
    }

    @Test
    fun `BUY 동결 금액이 최소 단위로 정확히 나눠지지 않으면 거부한다`() {
        val market = MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 8,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            calculator.calculate(
                market = market,
                side = Side.BUY,
                price = Price(1),
                quantity = Quantity(1),
                feePolicySnapshot = feePolicySnapshot,
            )
        }

        assertEquals(
            "reservation amount must align with base asset scale",
            error.message,
        )
    }

    @Test
    fun `BUY 동결 금액이 Long 범위를 넘으면 거부한다`() {
        val market = MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 0,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            calculator.calculate(
                market = market,
                side = Side.BUY,
                price = Price(Long.MAX_VALUE),
                quantity = Quantity(2),
                feePolicySnapshot = feePolicySnapshot,
            )
        }

        assertEquals(
            "reservation amount overflow",
            error.message,
        )
    }
}
