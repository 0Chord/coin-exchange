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

class MatchingCheckpointTest {
    private val market = MarketId("checkpoint-market")

    @Test
    fun `active checkpoint preserves partial quantity price priority FIFO and empty market sequence`() {
        val original = MatchingEngine()
        original.process(order("S1", Side.SELL, 100, 4))
        original.process(order("S2", Side.SELL, 100, 2))
        original.process(order("T1", Side.BUY, 100, 1))
        val emptyMarket = MarketId("empty-market")
        original.process(order("E1", Side.BUY, 10, 1).copy(marketId = emptyMarket))
        original.process(order("E2", Side.SELL, 10, 1).copy(marketId = emptyMarket))
        val checkpoint = original.activeCheckpoint()
        val restored = MatchingEngine()

        restored.restoreActiveCheckpoint(checkpoint)

        assertEquals(original.restingOrders(market), restored.restingOrders(market))
        assertEquals(Quantity(4), restored.restingOrders(market).first().originalQuantity)
        assertEquals(Quantity(3), restored.restingOrders(market).first().remainingQuantity)
        assertEquals(2L, restored.lastEngineSequence(emptyMarket))
        assertEquals(emptyList(), restored.restingOrders(emptyMarket))
        val next = order("T2", Side.BUY, 100, 4)
        assertEquals(original.process(next), restored.process(next))
        assertEquals(listOf(OrderId("S2")), restored.restingOrders(market).map { it.orderId })
        assertEquals(Quantity(1), restored.restingOrders(market).single().remainingQuantity)
    }

    @Test
    fun `even empty restoration invalidates prepared matching and result plans`() {
        val engine = MatchingEngine()
        val command = order("B1", Side.BUY, 100, 1)
        val plan = engine.prepareSubmit(command)
        val results = MatchingResultState()
        val registration = results.prepareRegistration("C1", command, plan.events.size, 1, 1, plan.events)

        engine.restoreActiveCheckpoint(MatchingCheckpoint(emptyList()))
        results.restoreActiveCheckpoint(emptyMap(), emptyList())

        assertFailsWith<IllegalArgumentException> { engine.apply(plan) }
        assertFailsWith<IllegalArgumentException> { results.register(registration) }
        assertEquals(emptyList(), engine.restingOrders(market))
        assertEquals(emptyList(), results.snapshot().results)
        assertFailsWith<IllegalStateException> { engine.restoreActiveCheckpoint(MatchingCheckpoint(emptyList())) }
        assertFailsWith<IllegalStateException> { results.restoreActiveCheckpoint(emptyMap(), emptyList()) }
    }

    @Test
    fun `invalid checkpoint book is rejected before the empty engine is changed`() {
        val buy = MatchingCheckpoint.Order(OrderId("B"), UserId("buyer"), Side.BUY, Price(100), Quantity(1), Quantity(1))
        val sell = MatchingCheckpoint.Order(OrderId("S"), UserId("seller"), Side.SELL, Price(100), Quantity(1), Quantity(1))
        val invalid =
            listOf(
                MatchingCheckpoint.Market(market, 2, listOf(buy, sell)),
                MatchingCheckpoint.Market(market, 2, listOf(buy, buy)),
                MatchingCheckpoint.Market(market, 0, listOf(buy)),
                MatchingCheckpoint.Market(market, 2, listOf(buy.copy(remainingQuantity = Quantity(2)))),
            )
        for (entry in invalid) {
            val engine = MatchingEngine()
            assertFailsWith<IllegalArgumentException> { engine.restoreActiveCheckpoint(MatchingCheckpoint(listOf(entry))) }
            assertEquals(0L, engine.lastEngineSequence(market))
            assertEquals(emptyList(), engine.restingOrders(market))
        }
    }

    private fun order(
        id: String,
        side: Side,
        price: Long,
        quantity: Long,
    ) = SubmitOrderCommand(market, OrderId(id), UserId("U-$id"), side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity))
}
