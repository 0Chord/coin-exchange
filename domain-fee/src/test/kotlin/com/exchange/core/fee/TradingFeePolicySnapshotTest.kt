package com.exchange.core.fee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TradingFeePolicySnapshotTest {
    private val snapshot =
        TradingFeePolicySnapshot(
            productType = FeeProductType.SPOT,
            feeTier = FeeTier.VIP,
            scheduleVersion = 3,
            feeRates =
                MakerTakerFeeRates(
                    makerFeeRate = FeeRate(6_000),
                    takerFeeRate = FeeRate(8_000),
                ),
        )

    @Test
    fun `체결 역할에 해당하는 주문 생성 당시 수수료율을 반환한다`() {
        assertEquals(
            FeeRate(6_000),
            snapshot.rateFor(LiquidityRole.MAKER),
        )
        assertEquals(
            FeeRate(8_000),
            snapshot.rateFor(LiquidityRole.TAKER),
        )
    }

    @Test
    fun `주문 예약에는 maker와 taker 중 높은 수수료율을 사용한다`() {
        assertEquals(
            FeeRate(8_000),
            snapshot.maximumRate(),
        )
    }

    @Test
    fun `무료 정책 snapshot은 최대 수수료율도 0이다`() {
        val freeSnapshot =
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 4,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate.ZERO,
                        takerFeeRate = FeeRate.ZERO,
                    ),
            )

        assertEquals(
            FeeRate.ZERO,
            freeSnapshot.maximumRate(),
        )
    }

    @Test
    fun `수수료 정책 버전은 양수여야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 0,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate.ZERO,
                        takerFeeRate = FeeRate.ZERO,
                    ),
            )
        }
    }
}
