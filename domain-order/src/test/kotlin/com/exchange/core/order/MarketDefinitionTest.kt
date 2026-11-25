package com.exchange.core.order

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MarketDefinitionTest {
    @Test
    fun `단위를 생략하면 정수 가격과 base 자산 1단위 수량을 사용한다`() {
        listOf(0 to 1L, 1 to 10L, 8 to 100_000_000L, 18 to 1_000_000_000_000_000_000L).forEach { (scale, step) ->
            val market = MarketDefinition(MarketId("B-Q"), AssetId("B"), AssetId("Q"), scale)

            assertEquals(1L, market.priceTick)
            assertEquals(step, market.quantityStep)
            market.requireOrderUnits(Price(1), Quantity(step))
        }
    }

    @Test
    fun `scale 범위는 수량 단위 생략과 명시 모두에서 검증한다`() {
        listOf(-1, 19).forEach { scale ->
            assertFailsWith<IllegalArgumentException> {
                MarketDefinition(MarketId("B-Q"), AssetId("B"), AssetId("Q"), scale)
            }
            assertFailsWith<IllegalArgumentException> {
                MarketDefinition(MarketId("B-Q"), AssetId("B"), AssetId("Q"), scale, quantityStep = 1)
            }
        }
    }

    @Test
    fun `가격과 수량 단위의 0과 음수는 설정할 수 없다`() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { market(scale = 0, tick = invalid, step = 1) }
            assertFailsWith<IllegalArgumentException> { market(scale = 0, tick = 1, step = invalid) }
        }
    }

    @Test
    fun `최소 가격과 수량의 곱이 quote 최소 단위로 나뉘지 않으면 설정을 거절한다`() {
        assertFailsWith<IllegalArgumentException> { market(tick = 2, step = 3) }
        assertFailsWith<IllegalArgumentException> { market(scale = 2, tick = 5, step = 5) }
    }

    @Test
    fun `단위 곱이 Long 범위를 넘어도 수학적으로 정확하면 설정할 수 있다`() {
        val market = market(tick = 4_000_000_000_000_000_000, step = 5)

        assertEquals(4_000_000_000_000_000_000, market.priceTick)
        assertEquals(5L, market.quantityStep)
        market.requireOrderUnits(Price(8_000_000_000_000_000_000), Quantity(10))
    }

    @Test
    fun `큰 단위 곱도 나머지를 잃지 않고 부적합한 설정을 거절한다`() {
        assertFailsWith<IllegalArgumentException> { market(tick = Long.MAX_VALUE, step = Long.MAX_VALUE) }
    }

    @Test
    fun `가격과 수량은 각각 설정 단위의 배수여야 한다`() {
        val market = market(tick = 2, step = 5)

        market.requireOrderUnits(Price(4), Quantity(15))
        market.requireOrderUnits(Price(4), Quantity.ZERO)
        assertFailsWith<IllegalArgumentException> { market.requireOrderUnits(Price(3), Quantity(10)) }
        assertFailsWith<IllegalArgumentException> { market.requireOrderUnits(Price(4), Quantity(11)) }
    }

    @Test
    fun `가격 단위와 함께 명시하면 base 최소 단위 수량도 허용한다`() {
        val market = market(tick = 10, step = 1)

        market.requireOrderUnits(Price(20), Quantity(1))
    }

    private fun market(
        scale: Int = 1,
        tick: Long,
        step: Long,
    ) = MarketDefinition(MarketId("B-Q"), AssetId("B"), AssetId("Q"), scale, tick, step)
}
