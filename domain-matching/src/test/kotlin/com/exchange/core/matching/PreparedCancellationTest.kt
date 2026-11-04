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
import kotlin.test.assertTrue

class PreparedCancellationTest {
    private val market = MarketId("A-QUOTE")
    private val user = UserId("buyer")
    private val target = OrderId("buy")

    @Test
    fun `성공 후보 준비 검증은 주문장과 순번을 바꾸지 않고 현재 잔량만 취소한다`() {
        val engine = MatchingEngine()
        engine.process(submit(target, Side.BUY, 3))
        engine.process(submit(OrderId("sell"), Side.SELL, 1))
        val before = engine.restingOrders(market)
        val plan = engine.prepareCancel(CancelOrderCommand(market, target, user))
        engine.validate(plan)
        assertEquals(listOf(OrderCancelled(market, 3, target, user, Quantity(2))), plan.events)
        assertEquals(before, engine.restingOrders(market))
        assertEquals(2L, engine.lastEngineSequence(market))
        assertEquals(plan.events, engine.apply(plan))
        assertTrue(engine.restingOrders(market).isEmpty())
        val following = engine.process(submit(OrderId("following"), Side.SELL, 2))
        assertTrue(following.single() is OrderEnteredBook)
        assertFailsWith<IllegalStateException> { engine.apply(plan) }
        assertEquals(4L, engine.lastEngineSequence(market))
    }

    @Test
    fun `없는 대상과 타인 소유 거절 후보도 읽기 전용이며 직접 취소와 사건이 같다`() {
        val engine = MatchingEngine()
        val missing = engine.prepareCancel(CancelOrderCommand(market, target, user))
        engine.validate(missing)
        assertEquals(0L, engine.lastEngineSequence(market))
        assertTrue(engine.activeCheckpoint().markets.isEmpty())
        assertEquals(listOf(OrderCancelRejected(market, 1, target, user, "order not found")), engine.apply(missing))
        engine.process(submit(target, Side.BUY, 3))
        val intruder = UserId("intruder")
        val command = CancelOrderCommand(market, target, intruder)
        val before = engine.restingOrders(market)
        val rejected = engine.prepareCancel(command)
        assertEquals(before, engine.restingOrders(market))
        assertEquals(listOf(OrderCancelRejected(market, 3, target, intruder, "order owner mismatch")), engine.apply(rejected))
        assertEquals(before, engine.restingOrders(market))
    }

    @Test
    fun `거절을 포함한 같은 마켓 전진과 다른 엔진은 후보를 적용하지 않는다`() {
        val engine = MatchingEngine()
        engine.process(submit(target, Side.BUY, 3))
        val plan = engine.prepareCancel(CancelOrderCommand(market, target, user))
        val foreign = MatchingEngine()
        assertFailsWith<IllegalArgumentException> { foreign.apply(plan) }
        assertEquals(0L, foreign.lastEngineSequence(market))
        engine.process(CancelOrderCommand(market, OrderId("missing"), user))
        val before = engine.restingOrders(market)
        assertFailsWith<IllegalStateException> { engine.validate(plan) }
        assertFailsWith<IllegalStateException> { engine.apply(plan) }
        assertEquals(before, engine.restingOrders(market))
        assertEquals(2L, engine.lastEngineSequence(market))
    }

    private fun submit(
        id: OrderId,
        side: Side,
        quantity: Long,
    ) = SubmitOrderCommand(market, id, user, side, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(quantity))
}
