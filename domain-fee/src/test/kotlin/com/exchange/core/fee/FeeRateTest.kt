package com.exchange.core.fee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeeRateTest {
    @Test
    fun `백만분율 정수로 수수료율을 생성한다`() {
        val feeRate = FeeRate(10_000)

        assertEquals(
            10_000,
            feeRate.partsPerMillion,
        )
    }

    @Test
    fun `0퍼센트 수수료율을 허용한다`() {
        assertEquals(
            FeeRate.ZERO,
            FeeRate(0),
        )
    }

    @Test
    fun `음수 수수료율을 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            FeeRate(-1)
        }
    }

    @Test
    fun `100퍼센트를 초과하는 수수료율을 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            FeeRate(1_000_001)
        }
    }
}