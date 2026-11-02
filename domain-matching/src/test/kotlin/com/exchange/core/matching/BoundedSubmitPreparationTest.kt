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
import kotlin.test.assertIs

class BoundedSubmitPreparationTest {
    private val market = MarketId("A-Q")

    @Test
    fun `정확히 한 이벤트 한도에서 무체결 후보를 만들고 적용 전 상태를 유지한다`() {
        val engine = MatchingEngine()
        val prepared = assertIs<BoundedSubmitPreparation.Prepared>(engine.prepareSubmitBounded(submit("B", Side.BUY, 100, 3), 1))
        assertEquals(0L, engine.lastEngineSequence(market))
        assertEquals(emptyList(), engine.restingOrders(market))
        assertEquals(
            listOf(OrderEnteredBook(market, 1, OrderId("B"), UserId("uB"), Side.BUY, Price(100), Quantity(3))),
            prepared.plan.events,
        )
        engine.apply(prepared.plan)
        assertEquals(1L, engine.lastEngineSequence(market))
        assertEquals(Quantity(3), engine.restingOrders(market).single().remainingQuantity)
    }

    @Test
    fun `두 체결과 잔량의 세 이벤트가 한도 둘을 넘으면 어떤 체결도 적용하지 않는다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 90, 1))
        engine.process(submit("S2", Side.SELL, 95, 1))
        val before = engine.restingOrders(market)
        val command = submit("B", Side.BUY, 100, 3)
        assertEquals(BoundedSubmitPreparation.TooLarge, engine.prepareSubmitBounded(command, 2))
        assertEquals(before, engine.restingOrders(market))
        assertEquals(2L, engine.lastEngineSequence(market))

        // 한도를 늘려 같은 ID로 재계산할 수 있어야 거절이 ID를 소비하지 않았음을 알 수 있다.
        val plan = assertIs<BoundedSubmitPreparation.Prepared>(engine.prepareSubmitBounded(command, 3)).plan
        assertEquals(listOf(3L, 4L, 5L), plan.events.map { it.engineSequence })
        assertEquals(listOf(Price(90), Price(95)), plan.fills.map { it.price })
        assertEquals(before, engine.restingOrders(market))
        engine.apply(plan)
        assertEquals(5L, engine.lastEngineSequence(market))
        assertEquals(Quantity(1), engine.restingOrders(market).single().remainingQuantity)
    }

    @Test
    fun `마지막 체결로 수량이 끝나면 남은 대기 이벤트 없이 정확한 한도를 허용한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 90, 1))
        engine.process(submit("S2", Side.SELL, 95, 1))
        val plan = assertIs<BoundedSubmitPreparation.Prepared>(engine.prepareSubmitBounded(submit("B", Side.BUY, 100, 2), 2)).plan
        assertEquals(2, plan.events.size)
        assertEquals(Quantity.ZERO, plan.restingQuantity)
        engine.apply(plan)
        assertEquals(emptyList(), engine.restingOrders(market))
        assertEquals(4L, engine.lastEngineSequence(market))
    }

    @Test
    fun `첫 체결 한도 이전에 중단해 기존 정상 후보와 주문장을 유지한다`() {
        val engine = MatchingEngine()
        repeat(10) { index -> engine.process(submit("S$index", Side.SELL, 100, 1)) }
        val allowed = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))
        val before = engine.restingOrders(market)
        assertEquals(BoundedSubmitPreparation.TooLarge, engine.prepareSubmitBounded(submit("B2", Side.BUY, 100, 10), 1))
        assertEquals(before, engine.restingOrders(market))
        assertEquals(10L, engine.lastEngineSequence(market))
        engine.apply(allowed)
        assertEquals(11L, engine.lastEngineSequence(market))
    }

    @Test
    fun `잘못된 한도와 미지원 주문과 중복 ID는 쓰기 전에 거절한다`() {
        val engine = MatchingEngine()
        val command = submit("B", Side.BUY, 100, 1)
        for (limit in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { engine.prepareSubmitBounded(command, limit) }
        }
        assertFailsWith<IllegalArgumentException> { engine.prepareSubmitBounded(command.copy(orderType = OrderType.MARKET), 1) }
        assertFailsWith<IllegalArgumentException> { engine.prepareSubmitBounded(command.copy(timeInForce = TimeInForce.IOC), 1) }
        assertEquals(0L, engine.lastEngineSequence(market))
        engine.process(command)
        val before = engine.restingOrders(market)
        assertFailsWith<IllegalArgumentException> { engine.prepareSubmitBounded(command, 1) }
        assertEquals(before, engine.restingOrders(market))
        assertEquals(1L, engine.lastEngineSequence(market))
    }

    @Test
    fun `이벤트 마지막 순번 자체는 허용하고 다음 신규 후보는 소진으로 남긴다`() {
        val engine = MatchingEngine()
        engine.restoreActiveCheckpoint(MatchingCheckpoint(listOf(MatchingCheckpoint.Market(market, Long.MAX_VALUE - 1, emptyList()))))
        val command = submit("B", Side.BUY, 100, 1)
        val plan = assertIs<BoundedSubmitPreparation.Prepared>(engine.prepareSubmitBounded(command, 1)).plan
        assertEquals(Long.MAX_VALUE, plan.events.single().engineSequence)
        engine.apply(plan)
        val before = engine.restingOrders(market)
        assertEquals(BoundedSubmitPreparation.SequenceExhausted, engine.prepareSubmitBounded(submit("B2", Side.BUY, 100, 1), 1))
        assertEquals(before, engine.restingOrders(market))
        assertEquals(Long.MAX_VALUE, engine.lastEngineSequence(market))
        assertFailsWith<ArithmeticException> { engine.prepareSubmit(submit("B2", Side.BUY, 100, 1)) }
    }

    @Test
    fun `여러 이벤트의 뒤쪽 순번이 넘치면 앞 체결도 적용하지 않는다`() {
        val engine = MatchingEngine()
        val maker = MatchingCheckpoint.Order(OrderId("S"), UserId("uS"), Side.SELL, Price(90), Quantity(1), Quantity(1))
        engine.restoreActiveCheckpoint(MatchingCheckpoint(listOf(MatchingCheckpoint.Market(market, Long.MAX_VALUE - 1, listOf(maker)))))
        val before = engine.restingOrders(market)
        for (maxEvents in listOf(1, 2)) {
            assertEquals(BoundedSubmitPreparation.SequenceExhausted, engine.prepareSubmitBounded(submit("B", Side.BUY, 100, 2), maxEvents))
        }
        assertEquals(before, engine.restingOrders(market))
        assertEquals(Long.MAX_VALUE - 1, engine.lastEngineSequence(market))
        val exact = assertIs<BoundedSubmitPreparation.Prepared>(engine.prepareSubmitBounded(submit("B", Side.BUY, 100, 1), 1)).plan
        engine.apply(exact)
        assertEquals(Long.MAX_VALUE, engine.lastEngineSequence(market))
        assertEquals(emptyList(), engine.restingOrders(market))
    }

    private fun submit(
        id: String,
        side: Side,
        price: Long,
        quantity: Long,
    ) = SubmitOrderCommand(market, OrderId(id), UserId("u$id"), side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity))
}
