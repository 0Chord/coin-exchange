package com.exchange.core.fee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TradingFeeScheduleTest {
    private val normalRates =
        MakerTakerFeeRates(
            makerFeeRate = FeeRate(8_000),
            takerFeeRate = FeeRate(10_000),
        )

    private val vipRates =
        MakerTakerFeeRates(
            makerFeeRate = FeeRate(6_000),
            takerFeeRate = FeeRate(8_000),
        )

    private val vvipRates =
        MakerTakerFeeRates(
            makerFeeRate = FeeRate(4_000),
            takerFeeRate = FeeRate(6_000),
        )

    private val vvvipRates =
        MakerTakerFeeRates(
            makerFeeRate = FeeRate(2_000),
            takerFeeRate = FeeRate(4_000),
        )

    private val ratesByTier =
        mapOf(
            FeeTier.NORMAL to normalRates,
            FeeTier.VIP to vipRates,
            FeeTier.VVIP to vvipRates,
            FeeTier.VVVIP to vvvipRates,
        )

    @Test
    fun `사용자 등급에 설정된 maker와 taker 수수료율을 반환한다`() {
        val schedule =
            TradingFeeSchedule(
                productType = FeeProductType.SPOT,
                version = 1,
                ratesByTier = ratesByTier,
            )

        assertEquals(
            normalRates,
            schedule.ratesFor(FeeTier.NORMAL),
        )
        assertEquals(
            vipRates,
            schedule.ratesFor(FeeTier.VIP),
        )
        assertEquals(
            vvipRates,
            schedule.ratesFor(FeeTier.VVIP),
        )
        assertEquals(
            vvvipRates,
            schedule.ratesFor(FeeTier.VVVIP),
        )
    }

    @Test
    fun `모든 수수료 등급의 요율이 설정되어야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            TradingFeeSchedule(
                productType = FeeProductType.SPOT,
                version = 1,
                ratesByTier =
                    ratesByTier - FeeTier.VVVIP,
            )
        }
    }

    @Test
    fun `수수료 정책 버전은 양수여야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            TradingFeeSchedule(
                productType = FeeProductType.SPOT,
                version = 0,
                ratesByTier = ratesByTier,
            )
        }
    }

    @Test
    fun `특정 거래 상품의 모든 등급을 수수료 무료로 설정할 수 있다`() {
        val freeRates =
            MakerTakerFeeRates(
                makerFeeRate = FeeRate.ZERO,
                takerFeeRate = FeeRate.ZERO,
            )

        val freeRatesByTier =
            FeeTier.entries.associateWith {
                freeRates
            }

        val schedule =
            TradingFeeSchedule(
                productType = FeeProductType.PERPETUAL_FUTURES,
                version = 1,
                ratesByTier = freeRatesByTier,
            )

        FeeTier.entries.forEach { feeTier ->
            assertEquals(
                FeeRate.ZERO,
                schedule.ratesFor(feeTier).rateFor(LiquidityRole.MAKER),
            )
            assertEquals(
                FeeRate.ZERO,
                schedule.ratesFor(feeTier).rateFor(LiquidityRole.TAKER),
            )
        }
    }
}
