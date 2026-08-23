package com.exchange.core.fee

import kotlin.test.Test
import kotlin.test.assertEquals

class MakerTakerFeeRatesTest {
    private val rates =
        MakerTakerFeeRates(
            makerFeeRate = FeeRate(8_000),
            takerFeeRate = FeeRate(10_000),
        )

    @Test
    fun `MAKER 역할에는 maker 수수료율을 반환한다`() {
        assertEquals(
            FeeRate(8_000),
            rates.rateFor(LiquidityRole.MAKER),
        )
    }

    @Test
    fun `TAKER 역할에는 taker 수수료율을 반환한다`() {
        assertEquals(
            FeeRate(10_000),
            rates.rateFor(LiquidityRole.TAKER),
        )
    }

    @Test
    fun `주문 예약에는 maker와 taker 중 높은 수수료율을 사용한다`() {
        assertEquals(
            FeeRate(10_000),
            rates.maximumRate(),
        )
    }
}