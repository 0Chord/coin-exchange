package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.fee.FeeRate

/**
 * BUY 거래 예산에 필요한 수수료 예약액과 총 필요 금액을 계산한 결과.
 *
 * @property quoteAssetId 거래 대금과 수수료를 표현하는 quote 자산
 * @property tradeBudgetAmount 실제 자산 구매에 사용할 거래 예산
 * @property feeReserveAmount maker/taker 중 높은 요율로 미리 예약할 수수료 금액
 * @property totalRequiredAmount 거래 예산과 수수료 예약액을 합한 총 필요 금액
 * @property feeReserveRate 수수료 예약에 사용한 maker/taker 중 높은 요율
 */
data class BuyOrderFundingQuote(
    val quoteAssetId: AssetId,
    val tradeBudgetAmount: Amount,
    val feeReserveAmount: Amount,
    val totalRequiredAmount: Amount,
    val feeReserveRate: FeeRate,
) {
    init {
        require(tradeBudgetAmount.value > 0) {
            "BUY trade budget must be positive"
        }

        require(tradeBudgetAmount <= totalRequiredAmount) {
            "BUY trade budget must not exceed total required amount"
        }

        require(feeReserveAmount <= totalRequiredAmount) {
            "fee reserve must not exceed total required amount"
        }

        require(
            feeReserveAmount.value ==
                totalRequiredAmount.value - tradeBudgetAmount.value,
        ) {
            "BUY trade budget and fee reserve must equal total required amount"
        }
    }
}
