package com.exchange.core.api.matching.persistence

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchingEventPayloadTest {
    @Test
    fun `trade executed event를 payload로 변환한다`() {
        val event = TradeExecuted(
            marketId = MarketId("BTC-KRW"),
            engineSequence = 10,
            makerOrderId = OrderId("ask-1"),
            takerOrderId = OrderId("bid-1"),
            makerUserId = UserId("seller-1"),
            takerUserId = UserId("buyer-1"),
            side = Side.BUY,
            price = Price(100),
            quantity = Quantity(3),
        )

        val payload = event.toPayload()

        assertEquals(MatchingEventType.TRADE_EXECUTED, event.toEventType())
        assertEquals(MatchingEventType.TRADE_EXECUTED, payload.type)
        assertEquals("BTC-KRW", payload.marketId)
        assertEquals(10, payload.engineSequence)
        assertEquals("ask-1", payload.makerOrderId)
        assertEquals("bid-1", payload.takerOrderId)
        assertEquals("seller-1", payload.makerUserId)
        assertEquals("buyer-1", payload.takerUserId)
        assertEquals("BUY", payload.side)
        assertEquals(100, payload.price)
        assertEquals(3, payload.quantity)
    }

    @Test
    fun `order entered event를 payload로 변환한다`() {
        val event = OrderEnteredBook(
            marketId = MarketId("BTC-KRW"),
            engineSequence = 1,
            orderId = OrderId("bid-1"),
            userId = UserId("user-1"),
            side = Side.BUY,
            price = Price(100),
            remainingQuantity = Quantity(5),
        )

        val payload = event.toPayload()

        assertEquals(MatchingEventType.ORDER_ENTERED_BOOK, event.toEventType())
        assertEquals(MatchingEventType.ORDER_ENTERED_BOOK, payload.type)
        assertEquals("BTC-KRW", payload.marketId)
        assertEquals(1, payload.engineSequence)
        assertEquals("bid-1", payload.orderId)
        assertEquals("user-1", payload.userId)
        assertEquals("BUY", payload.side)
        assertEquals(100, payload.price)
        assertEquals(5, payload.remainingQuantity)
    }

    @Test
    fun `order cancelled event를 payload로 변환한다`() {
        val event = OrderCancelled(
            marketId = MarketId("BTC-KRW"),
            engineSequence = 2,
            orderId = OrderId("bid-1"),
            userId = UserId("user-1"),
            remainingQuantity = Quantity(5),
        )

        val payload = event.toPayload()

        assertEquals(MatchingEventType.ORDER_CANCELLED, event.toEventType())
        assertEquals(MatchingEventType.ORDER_CANCELLED, payload.type)
        assertEquals("BTC-KRW", payload.marketId)
        assertEquals(2, payload.engineSequence)
        assertEquals("bid-1", payload.orderId)
        assertEquals("user-1", payload.userId)
        assertEquals(5, payload.remainingQuantity)
    }

    @Test
    fun `cancel rejected event를 payload로 변환한다`() {
        val event = OrderCancelRejected(
            marketId = MarketId("BTC-KRW"),
            engineSequence = 3,
            orderId = OrderId("missing"),
            userId = UserId("user-1"),
            reason = "order not found",
        )

        val payload = event.toPayload()

        assertEquals(MatchingEventType.ORDER_CANCEL_REJECTED, event.toEventType())
        assertEquals(MatchingEventType.ORDER_CANCEL_REJECTED, payload.type)
        assertEquals("BTC-KRW", payload.marketId)
        assertEquals(3, payload.engineSequence)
        assertEquals("missing", payload.orderId)
        assertEquals("user-1", payload.userId)
        assertEquals("order not found", payload.reason)
    }
}
