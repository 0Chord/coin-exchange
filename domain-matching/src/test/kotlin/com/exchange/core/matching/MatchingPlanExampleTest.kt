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

class MatchingPlanExampleTest {
    private val market = MarketId("BTC-USDT")
    private val buy = submit("B1", Side.BUY, 100, 3)
    private val expected =
        listOf(
            TradeExecuted(market, 3, OrderId("S1"), OrderId("B1"), UserId("U-S1"), UserId("U-B1"), Side.BUY, Price(90), Quantity(1)),
            TradeExecuted(market, 4, OrderId("S2"), OrderId("B1"), UserId("U-S2"), UserId("U-B1"), Side.BUY, Price(95), Quantity(1)),
            OrderEnteredBook(market, 5, OrderId("B1"), UserId("U-B1"), Side.BUY, Price(100), Quantity(1)),
        )

    @Test
    fun `매칭 후보 생성과 검증을 반복하고 버려도 실제 주문은 같은 순번과 수량으로 처리된다`() {
        val engine = preparedEngine()

        val plans = listOf(engine.prepareSubmit(buy), engine.prepareSubmit(buy))
        for (plan in plans) {
            engine.validate(plan)
            assertEquals(buy, plan.command)
            assertEquals(expected, plan.events)
            assertEquals(3L, plan.firstEngineSequence)
            assertEquals(5L, plan.lastEngineSequence)
        }

        // 버린 후보가 ID/순번/잔량을 소비했다면 이 제출이 실패하거나 결과가 달라진다.
        assertEquals(expected, engine.process(buy))
        assertEquals(
            listOf(OrderCancelled(market, 6, OrderId("B1"), UserId("U-B1"), Quantity(1))),
            engine.process(CancelOrderCommand(market, buy.orderId, buy.userId)),
        )
    }

    @Test
    fun `명시적 적용은 두 체결과 잔량을 함께 반영하고 과거 후보 값은 보존한다`() {
        val engine = preparedEngine()
        val plan = engine.prepareSubmit(buy)

        assertEquals(expected, engine.apply(plan))
        assertFailsWith<IllegalArgumentException> { engine.process(buy) }
        assertEquals(
            listOf(OrderCancelRejected(market, 6, OrderId("S1"), UserId("U-S1"), "order not found")),
            engine.process(CancelOrderCommand(market, OrderId("S1"), UserId("U-S1"))),
        )
        assertEquals(
            listOf(OrderCancelRejected(market, 7, OrderId("S2"), UserId("U-S2"), "order not found")),
            engine.process(CancelOrderCommand(market, OrderId("S2"), UserId("U-S2"))),
        )
        assertEquals(
            listOf(OrderCancelled(market, 8, OrderId("B1"), UserId("U-B1"), Quantity(1))),
            engine.process(CancelOrderCommand(market, buy.orderId, buy.userId)),
        )
        assertFailsWith<IllegalArgumentException> { engine.process(buy) }
        assertEquals(expected, plan.events)
    }

    private fun preparedEngine() =
        MatchingEngine().also {
            it.process(submit("S1", Side.SELL, 90, 1))
            it.process(submit("S2", Side.SELL, 95, 1))
        }

    private fun submit(
        id: String,
        side: Side,
        price: Long,
        quantity: Long,
    ) = SubmitOrderCommand(market, OrderId(id), UserId("U-$id"), side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity))
}
