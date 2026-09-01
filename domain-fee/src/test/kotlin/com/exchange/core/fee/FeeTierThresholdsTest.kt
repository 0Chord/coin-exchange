package com.exchange.core.fee

import com.exchange.core.common.Amount
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FeeTierThresholdsTest {
    @Test
    fun `VIP 최소 거래량은 0보다 커야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            FeeTierThresholds(
                vipMinimumVolume = Amount.ZERO,
                vvipMinimumVolume = Amount(100_000_000),
                vvvipMinimumVolume = Amount(1_000_000_000),
            )
        }
    }

    @Test
    fun `VVIP 최소 거래량은 VIP 최소 거래량보다 커야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            FeeTierThresholds(
                vipMinimumVolume = Amount(10_000_000),
                vvipMinimumVolume = Amount(10_000_000),
                vvvipMinimumVolume = Amount(1_000_000_000),
            )
        }
    }

    @Test
    fun `VVVIP 최소 거래량은 VVIP 최소 거래량보다 커야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            FeeTierThresholds(
                vipMinimumVolume = Amount(10_000_000),
                vvipMinimumVolume = Amount(100_000_000),
                vvvipMinimumVolume = Amount(100_000_000),
            )
        }
    }
}