package com.exchange.core.fee

/**
 * 하나의 사용자 등급에 적용되는 maker와 taker 수수료율.
 *
 * @property makerFeeRate maker 체결에 적용할 수수료율
 * @property takerFeeRate taker 체결에 적용할 수수료율
 */
data class MakerTakerFeeRates(
    val makerFeeRate: FeeRate,
    val takerFeeRate: FeeRate,
) {
    /** 체결 역할에 해당하는 수수료율을 반환한다. */
    fun rateFor(liquidityRole: LiquidityRole): FeeRate =
        when (liquidityRole) {
            LiquidityRole.MAKER -> makerFeeRate
            LiquidityRole.TAKER -> takerFeeRate
        }

    /** 주문 생성 시 수수료 예약에 사용할 두 요율 중 높은 값을 반환한다. */
    fun maximumRate(): FeeRate = maxOf(makerFeeRate, takerFeeRate)
}
