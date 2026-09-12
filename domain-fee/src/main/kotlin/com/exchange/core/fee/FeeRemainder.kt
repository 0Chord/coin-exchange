package com.exchange.core.fee

/**
 * 누적 수수료 계산에서 다음 체결로 넘기기 위한 소수 나머지.
 *
 * `numerator / FeeRate.DENOMINATOR`만큼의 최소 금액 단위를 나타낸다.
 * 예를 들어 최소 단위가 1원이면 분자 510,000은 0.51원이다.
 * 예약 잔액이나 이미 청구한 금액과는 다르며, 이 객체 자체는 계산·청구·저장을 수행하지 않는다.
 *
 * @property numerator 0 이상 [FeeRate.DENOMINATOR] 미만인 소수 나머지의 정수 분자
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
