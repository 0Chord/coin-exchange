package com.exchange.core.api.matching

import com.exchange.core.api.matching.publish.MatchingEventPublisher
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.InMemoryMarketCommandProcessor
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.RejectedExecutionException
import kotlin.test.assertFailsWith

class MatchingApplicationServicePersistenceFailureTest {
    private lateinit var processor: InMemoryMarketCommandProcessor

    @BeforeEach
    fun setUp() {
        processor = InMemoryMarketCommandProcessor()
    }

    @AfterEach
    fun tearDown() {
        processor.close()
    }

    @Test
    fun `publisher 저장 실패를 호출자에게 전달한다`() {
        val publisher = AlwaysFailingMatchingEventPublisher()
        val service = MatchingApplicationService(
            processor = processor,
            publisher = publisher,
        )

        val error = assertFailsWith<IllegalStateException> {
            service.process(
                sellOrder(
                    marketId = "FAILURE-PROPAGATION",
                    orderId = "ask-1",
                ),
            )
        }

        assertEquals(
            "matching event persistence failed",
            error.message,
        )
    }

    @Test
    fun `event 저장 실패 후 같은 market의 다음 command를 거부한다`() {
        val publisher = FailOnceMatchingEventPublisher()
        val service = MatchingApplicationService(
            processor = processor,
            publisher = publisher,
        )

        assertFailsWith<IllegalStateException> {
            service.process(
                sellOrder(
                    marketId = "FAILURE-CONTINUATION",
                    orderId = "ask-1",
                ),
            )
        }

        assertFailsWith<RejectedExecutionException> {
            service.process(
                buyOrder(
                    marketId = "FAILURE-CONTINUATION",
                    orderId = "bid-1",
                ),
            )
        }

        assertEquals(1, publisher.publishAttempts)
    }

    @Test
    fun `event 저장 실패는 다른 market의 command를 막지 않는다`() {
        val publisher = FailOnceMatchingEventPublisher()
        val service = MatchingApplicationService(
            processor = processor,
            publisher = publisher,
        )

        assertFailsWith<IllegalStateException> {
            service.process(
                sellOrder(
                    marketId = "FAILED-MARKET",
                    orderId = "failed-ask-1",
                ),
            )
        }

        val events = service.process(
            sellOrder(
                marketId = "HEALTHY-MARKET",
                orderId = "healthy-ask-1",
            ),
        )

        assertEquals(1, events.size)
        assertEquals(
            MarketId("HEALTHY-MARKET"),
            events.single().marketId,
        )
        assertEquals(1L, events.single().engineSequence)
        assertEquals(2, publisher.publishAttempts)
    }
    private fun sellOrder(
        marketId: String,
        orderId: String,
    ): SubmitOrderCommand =
        SubmitOrderCommand(
            marketId = MarketId(marketId),
            orderId = OrderId(orderId),
            userId = UserId("seller-1"),
            side = Side.SELL,
            orderType = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            price = Price(100),
            quantity = Quantity(5),
        )

    private fun buyOrder(
        marketId: String,
        orderId: String,
    ): SubmitOrderCommand =
        SubmitOrderCommand(
            marketId = MarketId(marketId),
            orderId = OrderId(orderId),
            userId = UserId("buyer-1"),
            side = Side.BUY,
            orderType = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            price = Price(100),
            quantity = Quantity(5),
        )


    private class AlwaysFailingMatchingEventPublisher : MatchingEventPublisher {
        override fun publish(events: List<MatchingEvent>) {
            throw IllegalStateException(
                "matching event persistence failed",
            )
        }
    }

    private class FailOnceMatchingEventPublisher : MatchingEventPublisher {
        var publishAttempts: Int = 0
            private set

        override fun publish(events: List<MatchingEvent>) {
            publishAttempts += 1

            if (publishAttempts == 1) {
                throw IllegalStateException(
                    "matching event persistence failed",
                )
            }
        }
    }
}
