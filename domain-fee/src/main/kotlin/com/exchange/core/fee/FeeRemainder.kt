package com.exchange.core.fee

/**
 * 다음 체결로 이월할 수수료 나머지.
 * `numerator / FeeRate.DENOMINATOR` 최소 금액 단위이며 예약액·청구액이 아니다.
 * 최소 단위가 1원이면 분자 510,000은 0.51원이다.
 *
 * @property numerator 0 이상 [FeeRate.DENOMINATOR] 미만인 정수 분자
 * @throws IllegalArgumentException 분자가 허용 범위를 벗어난 경우
 */
@JvmInline
value class FeeRemainder(
    val numerator: Long,
) {
    init {
        require(
            numerator >= 0 && numerator < FeeRate.DENOMINATOR,
        ) {
            "fee remainder numerator must be non-negative and less than the denominator"
        }
    }

    companion object {
        /** 다음 체결로 넘길 소수 나머지가 없는 상태. */
        val ZERO = FeeRemainder(0)
    }
}
