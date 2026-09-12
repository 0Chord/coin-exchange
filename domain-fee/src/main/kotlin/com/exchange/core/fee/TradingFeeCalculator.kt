package com.exchange.core.fee

import com.exchange.core.common.Amount
import java.math.BigInteger

/**
 * 체결 기준 금액과 수수료율로 이번에 청구할 거래 수수료를 계산한다.
 *
 * 중간 곱셈 overflow를 방지하기 위해 [BigInteger]를 사용한다.
 * 이전 소수 나머지를 전달하면 이번 수수료에 합산하고, 다음 체결로 넘길 나머지도 반환한다.
 * 계산기 자체는 주문별 상태를 저장하거나 잔고에서 수수료를 차감하지 않는다.
 */
class TradingFeeCalculator {
    /**
     * 이전 소수 나머지 없이 한 번의 체결에 대한 수수료만 계산한다.
     *
     * 기존 호출부를 위한 함수로, 최소 금액 단위 미만의 나머지는 반환하지 않는다.
     * 주문별 누적 계산에는 `previousRemainder`를 받는 함수를 사용해야 한다.
     *
     * @param feeBaseAmount 수수료율을 곱할 체결 기준 금액
     * @param feeRate 사용자 등급과 maker/taker 역할로 결정된 수수료율
     * @return `기준 금액 × 수수료율`의 최소 화폐 단위 미만을 버린 금액
     */
    fun calculateFee(
        feeBaseAmount: Amount,
        feeRate: FeeRate,
    ): Amount =
        calculateFee(
            feeBaseAmount = feeBaseAmount,
            feeRate = feeRate,
            previousRemainder = FeeRemainder.ZERO,
        ).actualFeeAmount

    /**
     * 같은 주문의 이전 소수 나머지를 합산해 이번 수수료와 다음 나머지를 계산한다.
     *
     * `이번 체결 금액 × 이번 요율의 백만분율 정수 + 이전 나머지 분자`를
     * [FeeRate.DENOMINATOR]로 나눈 몫은 이번 청구액, 나머지는 다음 체결로 넘길 분자다.
     * 이전 나머지는 당시 요율이 이미 반영된 값이므로 이번 요율을 다시 곱하지 않는다.
     * 호출부가 반환된 나머지를 주문에 보관하고 다음 체결 때 전달해야 한다.
     *
     * @param feeBaseAmount 수수료를 계산할 이번 체결 금액으로, 수수료 자산의 최소 단위로 표현한다.
     * @param feeRate 이번 체결의 maker/taker 역할에 적용할 수수료율
     * @param previousRemainder 같은 주문의 이전 계산에서 넘겨받은 소수 나머지. 최초 계산은 [FeeRemainder.ZERO].
     * @return 이번에 청구할 정수 금액과 다음 체결로 넘길 소수 나머지
     */
    fun calculateFee(
        feeBaseAmount: Amount,
        feeRate: FeeRate,
        previousRemainder: FeeRemainder,
    ): TradingFeeCalculation {
        val currentFeeNumerator =
            BigInteger
                .valueOf(feeBaseAmount.value)
                .multiply(BigInteger.valueOf(feeRate.partsPerMillion))

        val totalFeeNumerator =
            currentFeeNumerator.add(
                BigInteger.valueOf(previousRemainder.numerator),
            )

        val quotientAndRemainder =
            totalFeeNumerator.divideAndRemainder(
                BigInteger.valueOf(FeeRate.DENOMINATOR),
            )

        return TradingFeeCalculation(
            actualFeeAmount =
                Amount(
                    quotientAndRemainder[0].longValueExact(),
                ),
            remainder =
                FeeRemainder(
                    quotientAndRemainder[1].longValueExact(),
                ),
        )
    }
}
