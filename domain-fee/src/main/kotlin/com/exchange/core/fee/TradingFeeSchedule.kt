package com.exchange.core.fee

/**
 * 특정 상품과 버전에 적용되는 등급별 maker/taker 거래 수수료 정책.
 *
 * @property productType 정책을 적용할 거래 상품
 * @property version 정책 변경을 추적하기 위한 양수 버전
 * @param ratesByTier 각 사용자 등급에 설정한 maker/taker 수수료율
 */
class TradingFeeSchedule(
    val productType: FeeProductType,
    val version: Long,
    ratesByTier: Map<FeeTier, MakerTakerFeeRates>,
) {
    private val ratesByTier = ratesByTier.toMap()

    init {
        require(version > 0) {
            "trading fee schedule version must be positive"
        }

        require(this.ratesByTier.keys == FeeTier.entries.toSet()) {
            "fee rates must be configured for every fee tier"
        }
    }

    /** 지정한 사용자 등급에 설정된 maker/taker 수수료율을 반환한다. */
    fun ratesFor(feeTier: FeeTier): MakerTakerFeeRates =
        checkNotNull(ratesByTier[feeTier]) {
            "fee rates are not configured for tier $feeTier"
        }
}
