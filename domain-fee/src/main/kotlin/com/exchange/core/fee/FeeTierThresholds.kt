package com.exchange.core.fee

import com.exchange.core.common.Amount

/**
 * 사용자 수수료 등급을 결정하는 월간 실제 체결 금액 기준.
 *
 * @property vipMinimumVolume VIP가 되기 위한 최소 체결 금액
 * @property vvipMinimumVolume VVIP가 되기 위한 최소 체결 금액
 * @property vvvipMinimumVolume VVVIP가 되기 위한 최소 체결 금액
 */
data class FeeTierThresholds(
    val vipMinimumVolume: Amount,
    val vvipMinimumVolume: Amount,
    val vvvipMinimumVolume: Amount,
) {
    init {
        require(vipMinimumVolume > Amount.ZERO) {
            "VIP minimum volume must be positive"
        }
        require(vipMinimumVolume < vvipMinimumVolume) {
            "VVIP minimum must be greater than VIP minimum volume"
        }
        require(vvipMinimumVolume < vvvipMinimumVolume) {
            "VVVIP minimum must be greater than VVIP minimum volume"
        }
    }
}
