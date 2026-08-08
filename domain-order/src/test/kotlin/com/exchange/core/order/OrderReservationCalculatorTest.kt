package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrderReservationCalculatorTest {
    private val calculator = OrderReservationCalculator()

    @Test
    fun `BUY 주문은 quote asset을 동결한다`() {
        val market = MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 0,
        )

        val requirement = calculator.calculate(
            market = market,
            side = Side.BUY,
            price = Price(100),
            quantity = Quantity(5),
        )

        assertEquals(
            ReservationRequirement(
                assetId = AssetId("KRW"),
                amount = Amount(500),
            ),
            requirement,
        )
    }

    @Test
    fun `SELL 주문은 base asset을 동결한다`() {
        val market = MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 0,
        )

        val requirement = calculator.calculate(
            market = market,
            side = Side.SELL,
            price = Price(100),
            quantity = Quantity(5),
        )

        assertEquals(
            ReservationRequirement(
                assetId = AssetId("BTC"),
                amount = Amount(5),
            ),
            requirement,
        )
    }

    @Test
    fun `BUY 동결 금액은 base asset scale을 적용한다`() {
        val market = MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 8,
        )

        val requirement = calculator.calculate(
            market = market,
            side = Side.BUY,
            price = Price(100_000_000),
            quantity = Quantity(50_000_000),
        )

        assertEquals(
            ReservationRequirement(
                assetId = AssetId("KRW"),
                amount = Amount(50_000_000),
            ),
            requirement,
        )
    }

    @Test
    fun `BUY 동결 금액이 최소 단위로 정확히 나눠지지 않으면 거부한다`() {
        val market = MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 8,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            calculator.calculate(
                market = market,
                side = Side.BUY,
                price = Price(1),
                quantity = Quantity(1),
            )
        }

        assertEquals(
            "reservation amount must align with base asset scale",
            error.message,
        )
    }

    @Test
    fun `BUY 동결 금액이 Long 범위를 넘으면 거부한다`() {
        val market = MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 0,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            calculator.calculate(
                market = market,
                side = Side.BUY,
                price = Price(Long.MAX_VALUE),
                quantity = Quantity(2),
            )
        }

        assertEquals(
            "reservation amount overflow",
            error.message,
        )
    }
}
