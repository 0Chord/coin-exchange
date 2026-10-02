package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import com.exchange.core.order.OrderType
import com.exchange.core.order.TimeInForce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MatchingStateOwnershipTest {
    private val market = MarketId("BTC-KRW")
    private fun order() = BookOrder(OrderId("s1"), UserId("seller"), Side.SELL, Price(100), Quantity(10), Quantity(10))

    @Test fun `부분 체결과 전량 체결은 같은 내부 주문의 잔량만 줄인다`() {
        val order = order()
        order.fill(Quantity(4))
        assertEquals(Quantity(6), order.remainingQuantity)
        assertEquals(Quantity(10), order.originalQuantity)
        assertFalse(order.isFilled())
        order.fill(Quantity(6))
        assertEquals(Quantity.ZERO, order.remainingQuantity)
        assertEquals(Quantity(10), order.originalQuantity)
        assertTrue(order.isFilled())
    }

    @Test fun `0 또는 잔량 초과 체결은 거절하고 주문 상태를 보존한다`() {
        val order = order()
        order.fill(Quantity(4))
        for (quantity in listOf(Quantity(0), Quantity(7))) {
            assertFailsWith<IllegalArgumentException> { order.fill(quantity) }
            assertEquals(Quantity(6), order.remainingQuantity)
            assertEquals(Quantity(10), order.originalQuantity)
        }
    }

    @Test fun `초기 잔량은 양수이며 원수량을 넘을 수 없다`() {
        for (remaining in listOf(Quantity(0), Quantity(11))) {
            assertFailsWith<IllegalArgumentException> {
                BookOrder(OrderId("s1"), UserId("seller"), Side.SELL, Price(100), Quantity(10), remaining)
            }
        }
    }

    @Test fun `엔진 내부 주문장 조회는 같은 가변 주문을 사용한다`() {
        val order = order()
        val book = OrderBook()
        book.addRestingOrder(order)
        assertSame(order, book.find(order.orderId))
        assertSame(order, book.bestAskLevel()!!.firstOrder())
        order.fill(Quantity(4))
        assertEquals(Quantity(6), book.find(order.orderId)!!.remainingQuantity)
    }

    @Test fun `후속 체결과 취소는 이미 반환한 이벤트의 수량을 바꾸지 않는다`() {
        val engine = MatchingEngine()
        val original = assertIs<OrderEnteredBook>(engine.process(SubmitOrderCommand(
            marketId = market, orderId = OrderId("s1"), userId = UserId("seller"),
            side = Side.SELL, orderType = OrderType.LIMIT, timeInForce = TimeInForce.GTC, price = Price(100), quantity = Quantity(10),
        )).single())
        val trade = assertIs<TradeExecuted>(engine.process(SubmitOrderCommand(
            marketId = market, orderId = OrderId("b1"), userId = UserId("buyer"),
            side = Side.BUY, orderType = OrderType.LIMIT, timeInForce = TimeInForce.GTC, price = Price(100), quantity = Quantity(4),
        )).single())
        val cancellation = assertIs<OrderCancelled>(engine.process(CancelOrderCommand(
            marketId = market, orderId = OrderId("s1"), userId = UserId("seller"),
        )).single())
        assertEquals(Quantity(10), original.remainingQuantity)
        assertEquals(Quantity(4), trade.quantity)
        assertEquals(Quantity(6), cancellation.remainingQuantity)
    }
}
