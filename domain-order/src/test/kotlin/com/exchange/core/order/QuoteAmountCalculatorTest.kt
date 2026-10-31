package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuoteAmountCalculatorTest {
    @Test
    fun `일반 대금과 Long 경계 및 0 잔량을 정확히 계산한다`() {
        assertEquals(Amount(10_000), calculateQuoteAmount(Price(10_000), Quantity(100_000_000), 8))
        assertEquals(Amount(Long.MAX_VALUE), calculateQuoteAmount(Price(Long.MAX_VALUE), Quantity(1), 0))
        assertEquals(Amount.ZERO, calculateQuoteAmount(Price(Long.MAX_VALUE), Quantity.ZERO, 18))
    }

    @Test
    fun `scale 0부터 18까지 base 한 단위의 대금은 가격과 같다`() {
        for (scale in 0..18) {
            val quantity = BigInteger.TEN.pow(scale).longValueExact()
            for (price in listOf(1L, 10_000L, Long.MAX_VALUE)) {
                val input = Input(price, quantity, scale)
                assertEquals(Amount(price), calculate(input), input.toString())
                assertMatchesOracle(input)
            }
        }
    }

    @Test
    fun `중간 곱이 Long을 넘어도 나눈 대금이 범위 안이면 성공한다`() {
        val inputs =
            listOf(
                Input(4_000_000_000_000_000_000L, 5, 1),
                Input(Long.MAX_VALUE, 1_000_000_000_000_000_000L, 18),
                Input(1_000_000_000_000_000_000L, 1_000_000_000_000_000_000L, 18),
            )
        val amounts = listOf(2_000_000_000_000_000_000L, Long.MAX_VALUE, 1_000_000_000_000_000_000L)
        inputs.zip(amounts).forEach { (input, amount) ->
            assertEquals(Amount(amount), calculate(input), input.toString())
            assertMatchesOracle(input)
        }
    }

    @Test
    fun `나머지는 최종 대금 overflow보다 먼저 거절한다`() {
        val inputs =
            listOf(
                Input(1, 1, 1),
                Input(Long.MAX_VALUE, 2, 1),
                Input(Long.MAX_VALUE, Long.MAX_VALUE, 1),
            )
        inputs.forEach { input ->
            val failure = assertFailsWith<IllegalArgumentException> { calculate(input) }
            assertEquals("reservation amount must align with base asset scale", failure.message)
            assertNull(failure.cause)
            assertMatchesOracle(input)
        }
    }

    @Test
    fun `정확한 몫이 Long을 넘으면 기존 메시지와 산술 원인을 보존한다`() {
        val inputs =
            listOf(
                Input(Long.MAX_VALUE, 2, 0),
                Input(2_000_000_000_000_000_000L, Long.MAX_VALUE, 18),
            )
        inputs.forEach { input ->
            val failure = assertFailsWith<IllegalArgumentException> { calculate(input) }
            assertEquals("reservation amount overflow", failure.message)
            assertIs<ArithmeticException>(failure.cause)
            assertMatchesOracle(input)
        }
    }

    @Test
    fun `Long 곱셈 경계의 양쪽과 표 밖 scale도 기존 계산과 같다`() {
        listOf(
            Input(Long.MAX_VALUE / 3, 3, 0),
            Input(Long.MAX_VALUE / 3 + 1, 3, 0),
            Input(1_000_000_000_000_000_000L, 10, 19),
            Input(1_000_000_000_000_000_000L, 100, 20),
            Input(Long.MAX_VALUE, 0, 19),
            Input(1, 1, -1),
            Input(1, 0, -1),
        ).forEach(::assertMatchesOracle)
    }

    @Test
    fun `고정 seed의 다양한 가격 수량과 scale은 BigInteger 기준과 같다`() {
        val random = Random(148)
        repeat(1_000) {
            val scale = random.nextInt(0, 19)
            assertMatchesOracle(Input(random.nextLong(1, Long.MAX_VALUE), random.nextLong(0, Long.MAX_VALUE), scale))
            assertMatchesOracle(Input(random.nextLong(1, 1_000_001), random.nextLong(0, 1_000_001), scale))
        }
    }

    private data class Input(
        val price: Long,
        val quantity: Long,
        val scale: Int,
    )

    private fun calculate(input: Input): Amount = calculateQuoteAmount(Price(input.price), Quantity(input.quantity), input.scale)

    private fun assertMatchesOracle(input: Input) {
        val expected = runCatching { oracle(input) }
        val actual = runCatching { calculate(input) }
        val expectedFailure = expected.exceptionOrNull()
        if (expectedFailure == null) {
            assertEquals(expected.getOrThrow(), actual.getOrThrow(), input.toString())
        } else {
            val actualFailure = assertNotNull(actual.exceptionOrNull(), input.toString())
            assertEquals(expectedFailure::class, actualFailure::class, input.toString())
            assertEquals(expectedFailure.message, actualFailure.message, input.toString())
            assertEquals(expectedFailure.cause?.javaClass, actualFailure.cause?.javaClass, input.toString())
            assertEquals(expectedFailure.cause?.message, actualFailure.cause?.message, input.toString())
        }
    }

    /** 분기와 Long 중간 연산을 사용하지 않는 기존 수학적 계약이다. */
    private fun oracle(input: Input): Amount {
        val numerator = BigInteger.valueOf(input.price).multiply(BigInteger.valueOf(input.quantity))
        val quotientAndRemainder = numerator.divideAndRemainder(BigInteger.TEN.pow(input.scale))
        require(quotientAndRemainder[1] == BigInteger.ZERO) {
            "reservation amount must align with base asset scale"
        }
        val value =
            try {
                quotientAndRemainder[0].longValueExact()
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("reservation amount overflow", failure)
            }
        return Amount(value)
    }
}
