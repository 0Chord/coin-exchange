package com.exchange.core.api.matching.persistence

import com.exchange.core.api.matching.publish.MatchingEventPublisher
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.order.Side
import com.exchange.core.support.ExchangeIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 실제 발행자와 저장소 Bean으로 이벤트 저장 결과와 한 번의 발행 단위 롤백을 검증한다. */
class PersistentMatchingEventPublisherTest : ExchangeIntegrationTest() {
    @Autowired
    private lateinit var publisher: MatchingEventPublisher

    @Autowired
    private lateinit var repository: MatchingEventRepository

    @Test
    fun `publish는 event를 실제 PostgreSQL에 저장한다`() {
        assertIs<PersistentMatchingEventPublisher>(publisher)

        publisher.publish(listOf(enteredBook(engineSequence = 1, orderId = "bid-1")))

        val saved = repository.findByMarketIdOrderByEngineSequenceAsc("BTC-KRW").single()
        assertEquals(1L, saved.engineSequence)
        assertEquals(MatchingEventType.ORDER_ENTERED_BOOK, saved.eventType)
        assertEquals("bid-1", saved.orderId)
        assertEquals("user-1", saved.userId)
        assertEquals("BUY", saved.side)
        assertEquals(100L, saved.price)
        assertEquals(5L, saved.remainingQuantity)
        assertTrue(saved.payloadJson.contains("\"orderId\":\"bid-1\""))
    }

    /** 두 번째 이벤트가 중복 순번이면 같은 발행 목록의 첫 번째 insert도 롤백되어야 한다. */
    @Test
    fun `발행 목록의 뒤쪽 이벤트 저장이 실패하면 앞쪽 이벤트도 남지 않는다`() {
        publisher.publish(listOf(enteredBook(engineSequence = 1, orderId = "existing-order")))

        assertFailsWith<DataIntegrityViolationException> {
            publisher.publish(
                listOf(
                    enteredBook(engineSequence = 2, orderId = "must-rollback"),
                    enteredBook(engineSequence = 1, orderId = "duplicate-sequence"),
                ),
            )
        }

        val saved = repository.findByMarketIdOrderByEngineSequenceAsc("BTC-KRW").single()
        assertEquals(1L, saved.engineSequence)
        assertEquals("existing-order", saved.orderId)
    }

    private fun enteredBook(
        engineSequence: Long,
        orderId: String,
    ): OrderEnteredBook =
        OrderEnteredBook(
            marketId = MarketId("BTC-KRW"),
            engineSequence = engineSequence,
            orderId = OrderId(orderId),
            userId = UserId("user-1"),
            side = Side.BUY,
            price = Price(100),
            remainingQuantity = Quantity(5),
        )
}
