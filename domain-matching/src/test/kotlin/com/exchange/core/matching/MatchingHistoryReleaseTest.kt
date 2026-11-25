package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchingHistoryReleaseTest : MatchingResultTestFixture() {
    @Test
    fun `engine refuses active identity release and preserves market sequence after terminal release`() {
        val engine = MatchingEngine()
        val sell = submit("S", "seller", Side.SELL, 90, 1)
        engine.process(sell)
        assertFailsWith<IllegalStateException> { engine.releaseTerminalOrderIdentity(market, sell.orderId) }
        assertFailsWith<IllegalArgumentException> { engine.process(sell) }
        engine.process(submit("B", "buyer", Side.BUY, 100, 1))
        engine.releaseTerminalOrderIdentity(market, sell.orderId)
        engine.releaseTerminalOrderIdentity(market, OrderId("B"))
        assertEquals(2L, engine.lastEngineSequence(market))
        assertTrue(engine.restingOrders(market).isEmpty())
    }

    @Test
    fun `result release verifies identity without resetting event sequence and restore validates historical shape`() {
        val state = prepared()
        val original = state.find(market, "C1")!!
        assertFailsWith<IllegalArgumentException> { state.releaseHistory(market, "C1", OrderId("S2")) }
        assertEquals(original, state.find(market, "C1"))
        state.releaseHistory(market, "C1", OrderId("S1"))
        assertNull(state.find(market, "C1"))
        assertEquals(2L, state.lastEngineSequence(market))
        val second = state.find(market, "C2")!!
        val restored = MatchingCommandResult.restore("C2", second.command, 1, 2, 2, second.events)
        assertNotSame(second, restored)
        assertEquals(second, restored)
        assertFailsWith<MatchingResultException> {
            MatchingCommandResult.restore("C2", second.command, 1, 2, 2, original.events)
        }
    }
}
