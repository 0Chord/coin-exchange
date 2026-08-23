package com.exchange.core.fee

import com.exchange.core.common.Amount
import java.math.BigInteger

/**
 * 체결 기준 금액과 수수료율로 실제 거래 수수료를 계산한다.
 *
 * 중간 곱셈 overflow를 방지하기 위해 [BigInteger]를 사용하고,
 * 최소 화폐 단위보다 작은 결과는 버린다.
 */
class TradingFeeCalculator {
    /**
     * 실제 체결에 부과할 수수료를 계산한다.
     *
     * @param feeBaseAmount 수수료율을 곱할 체결 기준 금액
     * @param feeRate 사용자 등급과 maker/taker 역할로 결정된 수수료율
     * @return `기준 금액 × 수수료율`의 최소 화폐 단위 미만을 버린 금액
     */
    fun calculateFee(
        feeBaseAmount: Amount,
        feeRate: FeeRate,
    ): Amount {
        val numerator =
            BigInteger
                .valueOf(feeBaseAmount.value)
                .multiply(
                    BigInteger.valueOf(feeRate.partsPerMillion),
                )

        val feeAmount =
            numerator.divide(
                BigInteger.valueOf(FeeRate.DENOMINATOR),
            )

        return Amount(feeAmount.longValueExact())
    }
}
