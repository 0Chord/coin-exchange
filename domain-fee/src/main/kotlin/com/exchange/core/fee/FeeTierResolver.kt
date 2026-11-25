package com.exchange.core.fee

import com.exchange.core.common.Amount

/** 월간 실제 체결 금액을 설정된 기준에 맞는 [FeeTier]로 변환한다. */
class FeeTierResolver(
    private val thresholds: FeeTierThresholds,
) {
    /**
     * 월간 실제 체결 금액에 해당하는 수수료 등급을 반환한다.
     *
     * @param monthlyExecutedQuoteVolume quote 자산 기준 월간 실제 체결 금액
     */
    fun resolve(monthlyExecutedQuoteVolume: Amount): FeeTier =
        when {
            monthlyExecutedQuoteVolume >= thresholds.vvvipMinimumVolume -> FeeTier.VVVIP
            monthlyExecutedQuoteVolume >= thresholds.vvipMinimumVolume -> FeeTier.VVIP
            monthlyExecutedQuoteVolume >= thresholds.vipMinimumVolume -> FeeTier.VIP
            else -> FeeTier.NORMAL
        }
}
