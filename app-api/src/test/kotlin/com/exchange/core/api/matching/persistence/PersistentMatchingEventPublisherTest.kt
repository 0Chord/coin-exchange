package com.exchange.core.api.matching.persistence

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals

class PersistentMatchingEventPublisherTest {
    @Test
    fun `publish는 event를 store에 append한다`() {
        val store = RecordingMatchingEventStore()
        val publisher = PersistentMatchingEventPublisher(store)
        val events: List<MatchingEvent> = listOf(
            OrderEnteredBook(
                marketId = MarketId("BTC-KRW"),
                engineSequence = 1,
                orderId = OrderId("bid-1"),
                userId = UserId("user-1"),
                side = Side.BUY,
                price = Price(100),
                remainingQuantity = Quantity(5),
            ),
        )

        publisher.publish(events)

        assertEquals(events, store.appendedEvents)
    }

    private class RecordingMatchingEventStore : MatchingEventStore {
        val appendedEvents = mutableListOf<MatchingEvent>()

        override fun append(events: List<MatchingEvent>) {
            appendedEvents += events
        }
    }
}
