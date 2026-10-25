package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MatchingReadViewTest {
    private val market = MarketId("BTC-USDT")

    @Test
    fun `읽기 전용 주문장은 가격 FIFO 순서와 원수량 현재 잔량을 복사하고 이후 체결에서 보존한다`() {
        val engine = MatchingEngine()
        assertEquals(emptyList(), engine.restingOrders(market))
        assertEquals(0L, engine.lastEngineSequence(market))
        engine.process(submit("S2", Side.SELL, 110, 3))
        engine.process(submit("S1", Side.SELL, 100, 4))
        engine.process(submit("S3", Side.SELL, 100, 2))
        engine.process(submit("B1", Side.BUY, 90, 1))
        engine.process(submit("B2", Side.BUY, 95, 1))
        val before = engine.restingOrders(market)

        assertEquals(listOf("B2", "B1", "S1", "S3", "S2"), before.map { it.orderId.value })
        assertEquals(listOf(1L, 1L, 4L, 2L, 3L), before.map { it.originalQuantity.value })
        assertEquals(listOf(1L, 1L, 4L, 2L, 3L), before.map { it.remainingQuantity.value })
        val mutable = before as MutableList<RestingOrderSnapshot>
        assertFailsWith<UnsupportedOperationException> { mutable.clear() }

        engine.process(submit("T1", Side.BUY, 100, 1))

        assertEquals(4L, before.single { it.orderId == OrderId("S1") }.remainingQuantity.value)
        val current = engine.restingOrders(market).single { it.orderId == OrderId("S1") }
        assertEquals(UserId("U-S1"), current.userId)
        assertEquals(Side.SELL, current.side)
        assertEquals(Price(100), current.price)
        assertEquals(Quantity(4), current.originalQuantity)
        assertEquals(Quantity(3), current.remainingQuantity)
        assertEquals(6L, engine.lastEngineSequence(market))
    }

    @Test
    fun `공개 maker 기준값은 불변 목록이며 적용 이후에도 체결 전 잔량을 보존한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 4))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))
        // internal getter의 JVM 이름 대신 외부 모듈에 공개하는 getter인지 함께 확인한다.
        SubmitOrderPlan::class.java.getMethod("getFills")
        val fill = plan.fills.single()
        assertEquals(OrderId("S1"), fill.orderId)
        assertEquals(UserId("U-S1"), fill.userId)
        assertEquals(Side.SELL, fill.side)
        assertEquals(Price(100), fill.price)
        assertEquals(Quantity(4), fill.originalQuantity)
        assertEquals(Quantity(4), fill.remainingQuantity)
        assertEquals(Quantity(1), fill.filledQuantity)
        assertFailsWith<UnsupportedOperationException> { (plan.fills as MutableList<MakerFill>).clear() }

        engine.apply(plan)

        assertEquals(Quantity(4), fill.remainingQuantity)
        assertEquals(Quantity(3), engine.restingOrders(market).single().remainingQuantity)
    }

    private fun submit(
        orderId: String,
        side: Side,
        price: Long,
        quantity: Long,
    ) = SubmitOrderCommand(
        market,
        OrderId(orderId),
        UserId("U-$orderId"),
        side,
        OrderType.LIMIT,
        TimeInForce.GTC,
        Price(price),
        Quantity(quantity),
    )
}
