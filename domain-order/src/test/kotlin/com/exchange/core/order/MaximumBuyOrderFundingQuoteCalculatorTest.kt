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

class MaximumBuyOrderFundingQuoteCalculatorTest {
    private val market =
        MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 8,
        )

    private val fundingQuoteCalculator =
        BuyOrderFundingQuoteCalculator(
            feeReserveCalculator = TradingFeeReserveCalculator(),
        )

    private val calculator =
        MaximumBuyOrderFundingQuoteCalculator(
            fundingQuoteCalculator = fundingQuoteCalculator,
        )

    @Test
    fun `1억원 잔고 안에서 1퍼센트 수수료를 포함한 최대 BUY 거래 예산을 계산한다`() {
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
                tradeBudgetAmount = Amount(99_009_900),
                feeReserveAmount = Amount(990_099),
                totalRequiredAmount = Amount(99_999_999),
                feeReserveRate = FeeRate(10_000),
            ),
            calculator.calculate(
                market = market,
                availableQuoteAmount = Amount(100_000_000),
                feePolicySnapshot = feePolicy,
            ),
        )
    }

    @Test
    fun `무료 수수료 정책이면 사용 가능 잔고 전부를 거래 예산으로 사용한다`() {
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
                availableQuoteAmount = Amount(100_000_000),
                feePolicySnapshot = freeFeePolicy,
            ),
        )
    }

    @Test
    fun `수수료를 제외하면 거래에 사용할 최소 금액도 남지 않는 경우 거부한다`() {
        val feePolicy =
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 1,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate(1_000_000),
                        takerFeeRate = FeeRate(1_000_000),
                    ),
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                calculator.calculate(
                    market = market,
                    availableQuoteAmount = Amount(1),
                    feePolicySnapshot = feePolicy,
                )
            }

        assertEquals(
            "available quote amount must fund a positive BUY trade budget",
            error.message,
        )
    }

    @Test
    fun `사용 가능 잔고가 0이면 거부한다`() {
        val feePolicy =
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

        val error =
            assertFailsWith<IllegalArgumentException> {
                calculator.calculate(
                    market = market,
                    availableQuoteAmount = Amount.ZERO,
                    feePolicySnapshot = feePolicy,
                )
            }

        assertEquals(
            "available quote amount must be positive",
            error.message,
        )
    }
}
