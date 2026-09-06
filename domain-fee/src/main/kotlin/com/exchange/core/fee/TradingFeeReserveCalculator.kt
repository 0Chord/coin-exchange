package com.exchange.core.fee

import com.exchange.core.common.Amount
import java.math.BigInteger

/**
 * 신규 주문이나 부분 체결 후 남은 주문에 확보할 수수료 예약 금액을 계산한다.
 *
 * 이월된 소수 나머지가 있으면 합산하고, 최소 화폐 단위 미만의 결과를 올림한다.
 */
class TradingFeeReserveCalculator {
    /**
     * 이월된 소수 나머지 없이 주문 대금과 최대 수수료율로 예약 금액을 계산한다.
     *
     * @param feeReserveBaseAmount 수수료를 제외한 기준 거래대금
     * @param maximumFeeRate maker와 taker 수수료율 중 높은 값
     * @return 최소 화폐 단위로 올림한 수수료 예약 금액
     */
    fun calculateReserve(
        feeReserveBaseAmount: Amount,
        maximumFeeRate: FeeRate,
    ): Amount =
        calculateReserve(
            feeReserveBaseAmount = feeReserveBaseAmount,
            maximumFeeRate = maximumFeeRate,
            feeRemainder = FeeRemainder.ZERO,
        )

    /**
     * 기준 대금의 최대 수수료에 이월된 소수 나머지를 합산해 올림한다.
     *
     * `기준 대금 × 최대 요율의 백만분율 정수 + 나머지 분자`를 [FeeRate.DENOMINATOR]로
     * 나누고, 나머지가 있으면 몫에 1을 더한다. 중간 연산은 [BigInteger]로 수행한다.
     * 나머지에는 이전 요율이 이미 반영되어 있으므로 요율을 다시 곱하지 않는다.
     * 주문 상태는 알지 못하며, 전량 체결 시 예약액을 0으로 정리하는 것은 호출부의 책임이다.
     *
     * @param feeReserveBaseAmount 앞으로 체결될 주문의 지정가 기준 대금
     * @param maximumFeeRate 주문에 적용 가능한 최대 수수료율
     * @param feeRemainder 직전 수수료 계산에서 다음 체결로 넘길 소수 나머지
     * @return 최소 금액 단위로 올림한 수수료 예약액
     */
    fun calculateReserve(
        feeReserveBaseAmount: Amount,
        maximumFeeRate: FeeRate,
        feeRemainder: FeeRemainder,
    ): Amount {
        val numerator =
            BigInteger
                .valueOf(feeReserveBaseAmount.value)
                .multiply(
                    BigInteger.valueOf(maximumFeeRate.partsPerMillion),
                )
                .add(
                    BigInteger.valueOf(feeRemainder.numerator),
                )

        val denominator =
            BigInteger.valueOf(FeeRate.DENOMINATOR)

        val quotientAndRemainder =
            numerator.divideAndRemainder(denominator)

        val roundedUpAmount =
            if (quotientAndRemainder[1] == BigInteger.ZERO) {
                quotientAndRemainder[0]
            } else {
                quotientAndRemainder[0].add(BigInteger.ONE)
            }

        return Amount(roundedUpAmount.longValueExact())
    }
}
