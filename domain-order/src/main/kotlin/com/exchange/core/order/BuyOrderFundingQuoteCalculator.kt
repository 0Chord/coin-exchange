package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator

/** 현물 BUY 거래 예산에 필요한 수수료 예약액과 총 필요 금액을 계산한다. */
class BuyOrderFundingQuoteCalculator(
    private val feeReserveCalculator: TradingFeeReserveCalculator,
) {
    /**
     * maker/taker 중 높은 수수료율을 사용해 BUY 자금 요구사항을 계산한다.
     *
     * @param market BUY 주문이 제출될 현물 마켓
     * @param tradeBudgetAmount 실제 자산 구매에 사용할 quote 자산 금액
     * @param feePolicySnapshot 주문 접수 시점에 적용할 거래 수수료 정책
     * @return 거래 예산, 수수료 예약액과 총 필요 금액
     */
    fun calculate(
        market: MarketDefinition,
        tradeBudgetAmount: Amount,
        feePolicySnapshot: TradingFeePolicySnapshot,
    ): BuyOrderFundingQuote {
        require(tradeBudgetAmount.value > 0) {
            "BUY trade budget must be positive"
        }

        require(
            feePolicySnapshot.productType == FeeProductType.SPOT,
        ) {
            "BUY order funding requires a SPOT fee policy"
        }

        val feeReserveRate =
            feePolicySnapshot.maximumRate()

        val feeReserveAmount =
            feeReserveCalculator.calculateReserve(
                feeReserveBaseAmount = tradeBudgetAmount,
                maximumFeeRate = feeReserveRate,
            )

        val totalRequiredAmount =
            calculateTotalRequiredAmount(
                tradeBudgetAmount = tradeBudgetAmount,
                feeReserveAmount = feeReserveAmount,
            )

        return BuyOrderFundingQuote(
            quoteAssetId = market.quoteAssetId,
            tradeBudgetAmount = tradeBudgetAmount,
            feeReserveAmount = feeReserveAmount,
            totalRequiredAmount = totalRequiredAmount,
            feeReserveRate = feeReserveRate,
        )
    }

    /** 거래 예산과 수수료 예약액을 더하고 Long 범위를 넘으면 실패시킨다. */
    private fun calculateTotalRequiredAmount(
        tradeBudgetAmount: Amount,
        feeReserveAmount: Amount,
    ): Amount {
        val totalRequiredValue =
            try {
                Math.addExact(
                    tradeBudgetAmount.value,
                    feeReserveAmount.value,
                )
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException(
                    "BUY total required amount overflow",
                    error,
                )
            }

        return Amount(totalRequiredValue)
    }
}
