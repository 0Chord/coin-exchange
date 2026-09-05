package com.exchange.core.api.matching.persistence

import com.exchange.core.api.matching.MatchingApplicationService
import com.exchange.core.api.matching.publish.MatchingEventPublisher
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.support.ExchangeIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 실제 매칭 결과가 PostgreSQL 이벤트 저장소에 연결되는지 검증한다.
 * 모든 서비스는 실제 Bean으로 조립하되, 매칭 서비스를 직접 호출해 이벤트 저장 연결을 검사한다.
 * 주문 자금 예약·반환·정산은 HTTP 통합 테스트와 주문 E2E에서 검증한다.
 */
class MatchingPersistenceIntegrationTest : ExchangeIntegrationTest() {
    @Autowired
    private lateinit var applicationService: MatchingApplicationService

    @Autowired
    private lateinit var eventPublisher: MatchingEventPublisher

    @Autowired
    private lateinit var repository: MatchingEventRepository

    @Test
    fun `matching 결과를 PostgreSQL event store에 저장한다`() {
        assertIs<PersistentMatchingEventPublisher>(eventPublisher)

        val events =
            applicationService.process(
                SubmitOrderCommand(
                    marketId = MarketId("PERSISTENCE-WIRING"),
                    orderId = OrderId("order-1"),
                    userId = UserId("user-1"),
                    side = Side.BUY,
                    orderType = OrderType.LIMIT,
                    timeInForce = TimeInForce.GTC,
                    price = Price(100),
                    quantity = Quantity(5),
                ),
            )

        val event = assertIs<OrderEnteredBook>(events.single())

        assertEquals("PERSISTENCE-WIRING", event.marketId.value)
        assertEquals(1, event.engineSequence)
        assertEquals("order-1", event.orderId.value)

        val saved =
            repository
                .findByMarketIdOrderByEngineSequenceAsc("PERSISTENCE-WIRING")
                .single()

        assertEquals("PERSISTENCE-WIRING", saved.marketId)
        assertEquals(1, saved.engineSequence)
        assertEquals(MatchingEventType.ORDER_ENTERED_BOOK, saved.eventType)
        assertEquals("order-1", saved.orderId)
        assertEquals("user-1", saved.userId)
        assertEquals("BUY", saved.side)
        assertEquals(100, saved.price)
        assertEquals(5, saved.remainingQuantity)
        assertTrue(saved.payloadJson.contains("\"type\":\"ORDER_ENTERED_BOOK\""))
        assertTrue(saved.payloadJson.contains("\"orderId\":\"order-1\""))
    }
}
