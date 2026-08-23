package com.exchange.core.fee

/**
 * 주문 접수 시점에 확정되어 해당 주문에 계속 적용되는 거래 수수료 정책.
 *
 * @property productType 주문이 속한 거래 상품
 * @property feeTier 주문 접수 당시 사용자의 수수료 등급
 * @property scheduleVersion 적용한 거래 수수료 정책 버전
 * @property feeRates 주문에 적용할 maker와 taker 수수료율
 */
data class TradingFeePolicySnapshot(
    val productType: FeeProductType,
    val feeTier: FeeTier,
    val scheduleVersion: Long,
    val feeRates: MakerTakerFeeRates,
) {
    init {
        require(scheduleVersion > 0) {
            "trading fee schedule version must be positive"
        }
    }

    /** 실제 체결 역할에 적용할 수수료율을 반환한다. */
    fun rateFor(liquidityRole: LiquidityRole): FeeRate =
        feeRates.rateFor(liquidityRole)

    /** 주문 생성 시 수수료 예약에 사용할 최대 수수료율을 반환한다. */
    fun maximumRate(): FeeRate = feeRates.maximumRate()
}
