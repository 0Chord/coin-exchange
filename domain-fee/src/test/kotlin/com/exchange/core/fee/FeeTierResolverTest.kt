package com.exchange.core.fee

import com.exchange.core.common.Amount
import kotlin.test.Test
import kotlin.test.assertEquals

class FeeTierResolverTest {
    private val resolver =
        FeeTierResolver(
            thresholds =
                FeeTierThresholds(
                    vipMinimumVolume = Amount(10_000_000),
                    vvipMinimumVolume = Amount(100_000_000),
                    vvvipMinimumVolume = Amount(1_000_000_000),
                ),
        )

    @Test
    fun `월간 실제 체결 금액에 따라 수수료 등급을 결정한다`() {
        assertEquals(
            FeeTier.NORMAL,
            resolver.resolve(Amount(0)),
        )
        assertEquals(
            FeeTier.NORMAL,
            resolver.resolve(Amount(9_999_999)),
        )
        assertEquals(
            FeeTier.VIP,
            resolver.resolve(Amount(10_000_000)),
        )
        assertEquals(
            FeeTier.VIP,
            resolver.resolve(Amount(99_999_999)),
        )
        assertEquals(
            FeeTier.VVIP,
            resolver.resolve(Amount(100_000_000)),
        )
        assertEquals(
            FeeTier.VVIP,
            resolver.resolve(Amount(999_999_999)),
        )
        assertEquals(
            FeeTier.VVVIP,
            resolver.resolve(Amount(1_000_000_000)),
        )
    }
}
