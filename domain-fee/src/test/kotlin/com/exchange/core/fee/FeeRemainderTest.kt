package com.exchange.core.fee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 수수료 소수 나머지의 정수 표현, ZERO와 허용 범위를 검증한다.
 *
 * 최소 금액 단위 미만의 값만 허용하고, 음수와 분모 이상인 값은 거부해야 한다.
 */
class FeeRemainderTest {
    @Test
    fun `최소 금액 단위의 소수 부분을 정수 분자로 보관한다`() {
        val remainder = FeeRemainder(510_000)

        assertEquals(510_000L, remainder.numerator)
    }

    @Test
    fun `소수 나머지가 없으면 ZERO로 표현한다`() {
        assertEquals(FeeRemainder(0), FeeRemainder.ZERO)
        assertEquals(0L, FeeRemainder.ZERO.numerator)
    }

    @Test
    fun `최소 금액 단위보다 작은 최대 나머지를 허용한다`() {
        val remainder = FeeRemainder(999_999)

        assertEquals(999_999L, remainder.numerator)
    }

    @Test
    fun `음수 나머지를 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            FeeRemainder(-1)
        }
    }

    @Test
    fun `최소 금액 단위 하나에 해당하는 값은 나머지로 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            FeeRemainder(FeeRate.DENOMINATOR)
        }
    }

    @Test
    fun `Long 최대값처럼 범위를 크게 초과하는 나머지를 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            FeeRemainder(Long.MAX_VALUE)
        }
    }
}
