package com.exchange.core.fee

import com.exchange.core.common.Amount
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TradingFeeArithmeticEquivalenceTest {
    private val calculator = TradingFeeCalculator()
    private val reserveCalculator = TradingFeeReserveCalculator()
    private val denominator = BigInteger.valueOf(FeeRate.DENOMINATOR)

    @Test
    fun `작은 금액과 중간 곱셈 및 덧셈 overflow의 청구액과 나머지가 정확한 정수 계산과 같다`() {
        for ((base, rate, remainder) in cases()) {
            val expected = numerator(base, rate, remainder).divideAndRemainder(denominator)
            val actual = calculator.calculateFee(Amount(base), FeeRate(rate), FeeRemainder(remainder))

            assertEquals(expected[0].longValueExact(), actual.actualFeeAmount.value, "$base * $rate + $remainder")
            assertEquals(expected[1].longValueExact(), actual.remainder.numerator, "$base * $rate + $remainder")
        }
    }

    @Test
    fun `수수료 예약 올림과 최종 Long 범위 초과 오류가 정확한 정수 계산과 같다`() {
        for ((base, rate, remainder) in cases()) {
            val division = numerator(base, rate, remainder).divideAndRemainder(denominator)
            val expected = division[0].add(if (division[1] == BigInteger.ZERO) BigInteger.ZERO else BigInteger.ONE)

            if (expected > BigInteger.valueOf(Long.MAX_VALUE)) {
                assertFailsWith<ArithmeticException> {
                    reserveCalculator.calculateReserve(Amount(base), FeeRate(rate), FeeRemainder(remainder))
                }
            } else {
                assertEquals(
                    expected.longValueExact(),
                    reserveCalculator.calculateReserve(Amount(base), FeeRate(rate), FeeRemainder(remainder)).value,
                    "$base * $rate + $remainder",
                )
            }
        }
    }

    private fun numerator(
        base: Long,
        rate: Long,
        remainder: Long,
    ): BigInteger = BigInteger.valueOf(base).multiply(BigInteger.valueOf(rate)).add(BigInteger.valueOf(remainder))

    private fun cases(): List<Triple<Long, Long, Long>> {
        val values =
            listOf(
                0L,
                1L,
                999L,
                FeeRate.DENOMINATOR,
                Long.MAX_VALUE / FeeRate.DENOMINATOR,
                Long.MAX_VALUE / FeeRate.DENOMINATOR + 1,
                Long.MAX_VALUE - 1,
                Long.MAX_VALUE,
            )
        val rates = listOf(0L, 1L, 1_000L, 10_000L, FeeRate.DENOMINATOR - 1, FeeRate.DENOMINATOR)
        val remainders = listOf(0L, 1L, FeeRate.DENOMINATOR - 1)
        val random = Random(127)

        return buildList {
            for (base in values) {
                for (rate in rates) {
                    for (remainder in remainders) add(Triple(base, rate, remainder))
                }
            }
            repeat(256) {
                add(
                    Triple(
                        random.nextLong().ushr(1),
                        random.nextLong(FeeRate.DENOMINATOR + 1),
                        random.nextLong(FeeRate.DENOMINATOR),
                    ),
                )
            }
        }
    }
}
