package com.exchange.core.fee

/**
 * 거래 수수료율을 백만분율(parts per million) 정수로 표현한다.
 *
 * 예를 들어 `FeeRate(10_000)`은 1%를 의미한다.
 *
 * @property partsPerMillion 백만분율로 표현한 0% 이상 100% 이하의 수수료율
 */
@JvmInline
value class FeeRate(
    val partsPerMillion: Long,
) : Comparable<FeeRate> {
    init {
        require(partsPerMillion in 0..DENOMINATOR) {
            "fee rate must be between 0% and 100%"
        }
    }

    override fun compareTo(other: FeeRate): Int =
        partsPerMillion.compareTo(other.partsPerMillion)

    companion object {
        /** 백만분율 계산에 사용하는 분모. */
        const val DENOMINATOR: Long = 1_000_000

        /** 수수료가 부과되지 않는 0% 요율. */
        val ZERO = FeeRate(0)
    }
}
