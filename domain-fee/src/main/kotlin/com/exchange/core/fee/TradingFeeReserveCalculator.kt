package com.exchange.core.fee

import com.exchange.core.common.Amount
import java.math.BigInteger

/**
 * 주문 전에 확보할 수수료 예약 금액을 계산한다.
 *
 * 실제 수수료가 예약 금액을 초과하지 않도록 최소 화폐 단위 미만의 결과를 올림한다.
 */
class TradingFeeReserveCalculator {
    /**
     * 주문 예산과 적용 가능한 최대 수수료율로 예약 금액을 계산한다.
     *
     * @param feeReserveBaseAmount 수수료 예약액을 계산할 기준 예산
     * @param maximumFeeRate maker와 taker 수수료율 중 높은 값
     * @return 최소 화폐 단위로 올림한 수수료 예약 금액
     */
    fun calculateReserve(
        feeReserveBaseAmount: Amount,
        maximumFeeRate: FeeRate,
    ): Amount {
        val numerator =
            BigInteger
                .valueOf(feeReserveBaseAmount.value)
                .multiply(
                    BigInteger.valueOf(maximumFeeRate.partsPerMillion),
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
