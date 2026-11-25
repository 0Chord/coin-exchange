package com.exchange.core.fee

import com.exchange.core.common.Amount
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 체결 수수료 계산과 소수 나머지 이월, 동일 요율에서의 분할 체결 전후 결과 일치를 검증한다.
 */
class TradingFeeCalculatorTest {
    private val calculator = TradingFeeCalculator()

    @Test
    fun `체결 금액 9900만원에 1퍼센트 수수료를 계산한다`() {
        assertEquals(
            Amount(990_000),
            calculator.calculateFee(
                feeBaseAmount = Amount(99_000_000),
                feeRate = FeeRate(10_000),
            ),
        )
    }

    @Test
    fun `동일한 체결 금액에 maker 수수료율을 계산한다`() {
        assertEquals(
            Amount(792_000),
            calculator.calculateFee(
                feeBaseAmount = Amount(99_000_000),
                feeRate = FeeRate(8_000),
            ),
        )
    }

    @Test
    fun `수수료율이 0이면 체결 금액과 관계없이 수수료도 0이다`() {
        assertEquals(
            Amount.ZERO,
            calculator.calculateFee(
                feeBaseAmount = Amount(99_000_000),
                feeRate = FeeRate.ZERO,
            ),
        )
    }

    @Test
    fun `최소 화폐 단위보다 작은 수수료는 청구하지 않고 나머지로 보관한다`() {
        val calculation =
            calculator.calculateFee(
                feeBaseAmount = Amount(999),
                feeRate = FeeRate(1_000),
                previousRemainder = FeeRemainder.ZERO,
            )

        assertEquals(
            Amount.ZERO,
            calculation.actualFeeAmount,
        )
        assertEquals(
            FeeRemainder(999_000),
            calculation.remainder,
        )
    }

    @Test
    fun `Long 최대 금액도 중간 곱셈 overflow 없이 계산한다`() {
        assertEquals(
            Amount(Long.MAX_VALUE),
            calculator.calculateFee(
                feeBaseAmount = Amount(Long.MAX_VALUE),
                feeRate = FeeRate(1_000_000),
            ),
        )
    }

    @Test
    fun `이전 소수 나머지를 합산해 이번 수수료와 다음 나머지를 계산한다`() {
        val calculation =
            calculator.calculateFee(
                feeBaseAmount = Amount(51),
                feeRate = FeeRate(10_000),
                previousRemainder = FeeRemainder(510_000),
            )

        assertEquals(
            Amount(1),
            calculation.actualFeeAmount,
        )
        assertEquals(
            FeeRemainder(20_000),
            calculation.remainder,
        )
    }

    @Test
    fun `같은 요율이면 체결을 나누어도 총수수료와 최종 나머지는 같다`() {
        val feeRate = FeeRate(10_000)

        // 같은 총금액을 한 번에 체결했을 때의 결과를 비교 기준으로 사용한다.
        val singleFillCalculation =
            calculator.calculateFee(
                feeBaseAmount = Amount(255),
                feeRate = feeRate,
                previousRemainder = FeeRemainder.ZERO,
            )

        var totalChargedFeeValue = 0L
        var previousRemainder = FeeRemainder.ZERO

        // 각 체결의 청구액을 합산하고, 소수 나머지는 다음 체결 계산으로 넘긴다.
        for (fillAmount in listOf(51L, 51L, 51L, 102L)) {
            val calculation =
                calculator.calculateFee(
                    feeBaseAmount = Amount(fillAmount),
                    feeRate = feeRate,
                    previousRemainder = previousRemainder,
                )

            totalChargedFeeValue += calculation.actualFeeAmount.value
            previousRemainder = calculation.remainder
        }

        assertEquals(
            TradingFeeCalculation(
                actualFeeAmount = Amount(2),
                remainder = FeeRemainder(550_000),
            ),
            singleFillCalculation,
        )

        assertEquals(
            singleFillCalculation,
            TradingFeeCalculation(
                actualFeeAmount = Amount(totalChargedFeeValue),
                remainder = previousRemainder,
            ),
        )
    }
}
