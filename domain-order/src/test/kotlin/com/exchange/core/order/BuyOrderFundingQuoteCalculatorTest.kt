package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuyOrderFundingQuoteCalculatorTest {
    private val market =
        MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 8,
        )

    private val calculator =
        BuyOrderFundingQuoteCalculator(
            feeReserveCalculator = TradingFeeReserveCalculator(),
        )

    @Test
    fun `9900만원 BUY 거래 예산에 최대 1퍼센트인 99만원을 수수료로 예약한다`() {
        val feePolicy =
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 1,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate(8_000),
                        takerFeeRate = FeeRate(10_000),
                    ),
            )

        assertEquals(
            BuyOrderFundingQuote(
                quoteAssetId = AssetId("KRW"),
                tradeBudgetAmount = Amount(99_000_000),
                feeReserveAmount = Amount(990_000),
                totalRequiredAmount = Amount(99_990_000),
                feeReserveRate = FeeRate(10_000),
            ),
            calculator.calculate(
                market = market,
                tradeBudgetAmount = Amount(99_000_000),
                feePolicySnapshot = feePolicy,
            ),
        )
    }

    @Test
    fun `무료 수수료 정책이면 거래 예산 외에 추가로 필요한 금액이 없다`() {
        val freeFeePolicy =
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 2,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate.ZERO,
                        takerFeeRate = FeeRate.ZERO,
                    ),
            )

        assertEquals(
            BuyOrderFundingQuote(
                quoteAssetId = AssetId("KRW"),
                tradeBudgetAmount = Amount(100_000_000),
                feeReserveAmount = Amount.ZERO,
                totalRequiredAmount = Amount(100_000_000),
                feeReserveRate = FeeRate.ZERO,
            ),
            calculator.calculate(
                market = market,
                tradeBudgetAmount = Amount(100_000_000),
                feePolicySnapshot = freeFeePolicy,
            ),
        )
    }

    @Test
    fun `수수료 예약에 최소 단위 일부가 필요하면 올림한다`() {
        val feePolicy =
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 1,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate(500),
                        takerFeeRate = FeeRate(1_000),
                    ),
            )

        assertEquals(
            BuyOrderFundingQuote(
                quoteAssetId = AssetId("KRW"),
                tradeBudgetAmount = Amount(1_001),
                feeReserveAmount = Amount(2),
                totalRequiredAmount = Amount(1_003),
                feeReserveRate = FeeRate(1_000),
            ),
            calculator.calculate(
                market = market,
                tradeBudgetAmount = Amount(1_001),
                feePolicySnapshot = feePolicy,
            ),
        )
    }

    @Test
    fun `현물 BUY 예산에는 선물 수수료 정책을 사용할 수 없다`() {
        val futuresFeePolicy =
            TradingFeePolicySnapshot(
                productType = FeeProductType.PERPETUAL_FUTURES,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 1,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate(8_000),
                        takerFeeRate = FeeRate(10_000),
                    ),
            )

        assertFailsWith<IllegalArgumentException> {
            calculator.calculate(
                market = market,
                tradeBudgetAmount = Amount(100_000_000),
                feePolicySnapshot = futuresFeePolicy,
            )
        }
    }

    @Test
    fun `BUY 거래 예산이 0이면 거부한다`() {
        val feePolicy =
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 1,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate(8_000),
                        takerFeeRate = FeeRate(10_000),
                    ),
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                calculator.calculate(
                    market = market,
                    tradeBudgetAmount = Amount.ZERO,
                    feePolicySnapshot = feePolicy,
                )
            }

        assertEquals(
            "BUY trade budget must be positive",
            error.message,
        )
    }

    @Test
    fun `거래 예산과 수수료 예약액의 합이 Long 범위를 넘으면 거부한다`() {
        val feePolicy =
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 1,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate.ZERO,
                        takerFeeRate = FeeRate(1_000_000),
                    ),
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                calculator.calculate(
                    market = market,
                    tradeBudgetAmount = Amount(Long.MAX_VALUE),
                    feePolicySnapshot = feePolicy,
                )
            }

        assertEquals(
            "BUY total required amount overflow",
            error.message,
        )
    }
}
