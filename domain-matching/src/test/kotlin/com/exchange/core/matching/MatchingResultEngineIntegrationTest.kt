package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MatchingResultEngineIntegrationTest : MatchingResultTestFixture() {
    @Test
    fun `실제 엔진의 S1 S2 B1 결과를 고정 oracle과 대조해 한 묶음씩 등록한다`() {
        val engine = MatchingEngine()
        val state = MatchingResultState()
        val first = submit("S1", "U-S1", Side.SELL, 90, 1)
        val second = submit("S2", "U-S2", Side.SELL, 95, 1)
        val buy = buy()
        val firstEvents = engine.process(first)
        val secondEvents = engine.process(second)

        assertEquals(listOf(entered(1, "S1", "U-S1", Side.SELL, 90, 1)), firstEvents)
        assertEquals(listOf(entered(2, "S2", "U-S2", Side.SELL, 95, 1)), secondEvents)
        state.register("C1", first, 1, 1, 1, firstEvents)
        state.register("C2", second, 1, 2, 2, secondEvents)

        val events = engine.process(buy)
        val result = state.register("C3", buy, 3, 3, 5, events)

        assertEquals(buyEvents(), events)
        assertEquals(buyEvents(), result.events)
        assertEquals(buy, result.command)
        assertEquals(3, result.expectedEventCount)
        assertEquals(3L, result.firstEngineSequence)
        assertEquals(5L, result.lastEngineSequence)
        assertSame(result, state.find(market, "C3"))
        assertEquals(listOf("C1", "C2", "C3"), state.snapshot().results.map { it.commandId })
        assertEquals(mapOf(market to 5L), state.snapshot().lastEngineSequences)

        val cancellation = CancelOrderCommand(market, OrderId("B1"), UserId("U-B1"))
        val cancelledEvents = engine.process(cancellation)
        val expectedCancellation = OrderCancelled(market, 6, OrderId("B1"), UserId("U-B1"), Quantity(1))
        assertEquals(listOf(expectedCancellation), cancelledEvents)
        state.register("C4", cancellation, 1, 6, 6, cancelledEvents)
        assertEquals(buyEvents(), result.events)
        assertSame(result, state.register("C3", buy, 3, 3, 5, events))
        assertEquals(mapOf(market to 6L), state.snapshot().lastEngineSequences)
    }

    @Test
    fun `실제 엔진의 매도 결과도 높은 maker 가격 두 건과 마지막 잔량을 보존한다`() {
        val engine = MatchingEngine()
        val state = MatchingResultState()
        val first = submit("B1", "U-B1", Side.BUY, 110, 1)
        val second = submit("B2", "U-B2", Side.BUY, 105, 1)
        val sell = submit("S1", "U-S1", Side.SELL, 100, 3)
        val firstEvents = engine.process(first)
        val secondEvents = engine.process(second)
        assertEquals(listOf(entered(1, "B1", "U-B1", Side.BUY, 110, 1)), firstEvents)
        assertEquals(listOf(entered(2, "B2", "U-B2", Side.BUY, 105, 1)), secondEvents)
        state.register("C1", first, 1, 1, 1, firstEvents)
        state.register("C2", second, 1, 2, 2, secondEvents)
        val expected =
            listOf(
                trade(3, "B1", "S1", "U-B1", "U-S1", Side.SELL, 110, 1),
                trade(4, "B2", "S1", "U-B2", "U-S1", Side.SELL, 105, 1),
                entered(5, "S1", "U-S1", Side.SELL, 100, 1),
            )

        val events = engine.process(sell)
        val result = state.register("C3", sell, 3, 3, 5, events)

        assertEquals(expected, events)
        assertEquals(expected, result.events)
        assertEquals(sell, result.command)
        assertEquals(listOf("C1", "C2", "C3"), state.snapshot().results.map { it.commandId })
        assertEquals(mapOf(market to 5L), state.snapshot().lastEngineSequences)
    }
}
