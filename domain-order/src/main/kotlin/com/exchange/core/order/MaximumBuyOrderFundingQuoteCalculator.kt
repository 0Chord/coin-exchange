package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.TradingFeePolicySnapshot
import java.math.BigInteger

/**
 * 사용 가능한 quote 자산 잔고 안에서 수수료를 포함한 최대 BUY 자금 견적을 계산한다.
 */
class MaximumBuyOrderFundingQuoteCalculator(
    private val fundingQuoteCalculator: BuyOrderFundingQuoteCalculator,
) {
    /**
     * 수수료 예약액을 포함해 잔고를 넘지 않는 최대 거래 예산을 계산한다.
     *
     * @param market BUY 주문이 제출될 현물 마켓
     * @param availableQuoteAmount BUY에 사용할 수 있는 quote 자산 잔고
     * @param feePolicySnapshot 주문 접수 시점에 적용할 거래 수수료 정책
     * @return 사용 가능한 잔고 안에서 만든 최대 BUY 자금 견적
     */
    fun calculate(
        market: MarketDefinition,
        availableQuoteAmount: Amount,
        feePolicySnapshot: TradingFeePolicySnapshot,
    ): BuyOrderFundingQuote {
        require(availableQuoteAmount.value > 0) {
            "available quote amount must be positive"
        }

        val maximumFeeRate = feePolicySnapshot.maximumRate()

        val maximumTradeBudgetAmount =
            calculateMaximumTradeBudget(
                availableQuoteAmount = availableQuoteAmount,
                maximumFeeRate = maximumFeeRate,
            )

        require(maximumTradeBudgetAmount.value > 0) {
            "available quote amount must fund a positive BUY trade budget"
        }

        return fundingQuoteCalculator.calculate(
            market = market,
            tradeBudgetAmount = maximumTradeBudgetAmount,
            feePolicySnapshot = feePolicySnapshot,
        )
    }

    /**
     * 수수료를 포함한 총 필요 금액이 잔고를 넘지 않는 최대 거래 예산을 구한다.
     *
     * 계산식은 `floor(사용 가능 잔고 × 비율 분모 ÷ (비율 분모 + 수수료율))`이다.
     * 분모와 수수료율은 모두 백만분율 정수이며, 결과를 버림해야 총 필요 금액이
     * 사용 가능한 잔고를 초과하지 않는다.
     */
    private fun calculateMaximumTradeBudget(
        availableQuoteAmount: Amount,
        maximumFeeRate: FeeRate,
    ): Amount {
        // 사용 가능 잔고에 백만분율 분모를 곱해 정수 나눗셈의 분자를 만든다.
        val numerator =
            BigInteger
                .valueOf(availableQuoteAmount.value)
                .multiply(
                    BigInteger.valueOf(FeeRate.DENOMINATOR),
                )

        // 거래 금액 100%와 최대 수수료율을 합한 전체 필요 비율이다.
        val denominator =
            BigInteger.valueOf(
                FeeRate.DENOMINATOR +
                    maximumFeeRate.partsPerMillion,
            )

        // BigInteger 정수 나눗셈은 소수 부분을 버려 잔고 초과를 방지한다.
        val maximumTradeBudget =
            numerator.divide(denominator)

        return Amount(
            maximumTradeBudget.longValueExact(),
        )
    }
}
