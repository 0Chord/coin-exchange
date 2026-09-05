package com.exchange.core.api.matching

import com.exchange.core.api.matching.persistence.MatchingEventRepository
import com.exchange.core.api.matching.persistence.MatchingEventStore
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
import org.springframework.dao.DataIntegrityViolationException
import java.util.concurrent.RejectedExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 실제 PostgreSQL 이벤트 순번 충돌로 저장 실패를 일으켜 마켓별 장애 격리를 검증한다.
 * 발행자 대역이나 호출 횟수 대신 전파된 예외, 후속 명령의 결과와 DB 저장 상태를 확인한다.
 * 주문 자금 처리가 아니라 매칭 이벤트 저장 실패 경계를 검증하므로 매칭 서비스를 직접 호출한다.
 */
class MatchingApplicationServicePersistenceFailureTest : ExchangeIntegrationTest() {
    @Autowired
    private lateinit var service: MatchingApplicationService

    @Autowired
    private lateinit var eventStore: MatchingEventStore

    @Autowired
    private lateinit var repository: MatchingEventRepository

    @Test
    fun `publisher의 실제 DB 저장 실패를 호출자에게 전달한다`() {
        createSequenceConflict("FAILURE-PROPAGATION")

        val error =
            assertFailsWith<DataIntegrityViolationException> {
                service.process(
                    order(marketId = "FAILURE-PROPAGATION", orderId = "ask-1"),
                )
            }

        assertTrue(
            error.mostSpecificCause.message.orEmpty().contains("uk_matching_events_market_sequence"),
        )
        assertEquals("previous-event", repository.findAll().single().orderId)
    }

    /** DB 충돌 원인을 제거해도 장애 마켓을 자동 재개하지 않고 후속 명령을 거절해야 한다. */
    @Test
    fun `event 저장 실패 후 같은 market의 다음 command를 거부한다`() {
        createSequenceConflict("FAILURE-CONTINUATION")
        assertFailsWith<DataIntegrityViolationException> {
            service.process(
                order(marketId = "FAILURE-CONTINUATION", orderId = "ask-1"),
            )
        }
        repository.deleteAll()

        assertFailsWith<RejectedExecutionException> {
            service.process(
                order(marketId = "FAILURE-CONTINUATION", orderId = "bid-1", side = Side.BUY),
            )
        }

        assertEquals(0L, repository.count())
    }

    @Test
    fun `event 저장 실패는 다른 market의 command와 저장을 막지 않는다`() {
        createSequenceConflict("FAILED-MARKET")
        assertFailsWith<DataIntegrityViolationException> {
            service.process(
                order(marketId = "FAILED-MARKET", orderId = "failed-ask-1"),
            )
        }

        val events =
            service.process(
                order(marketId = "HEALTHY-MARKET", orderId = "healthy-ask-1"),
            )

        val event = assertIs<OrderEnteredBook>(events.single())
        assertEquals(MarketId("HEALTHY-MARKET"), event.marketId)
        assertEquals(1L, event.engineSequence)
        assertEquals(
            "healthy-ask-1",
            repository.findByMarketIdOrderByEngineSequenceAsc("HEALTHY-MARKET").single().orderId,
        )
        assertEquals(
            "previous-event",
            repository.findByMarketIdOrderByEngineSequenceAsc("FAILED-MARKET").single().orderId,
        )
        assertEquals(2L, repository.count())
    }

    /** 메모리 엔진의 첫 이벤트와 충돌할 순번 1을 실제 저장소에 미리 커밋한다. */
    private fun createSequenceConflict(marketId: String) {
        eventStore.append(
            listOf(
                OrderEnteredBook(
                    marketId = MarketId(marketId),
                    engineSequence = 1,
                    orderId = OrderId("previous-event"),
                    userId = UserId("previous-user"),
                    side = Side.SELL,
                    price = Price(100),
                    remainingQuantity = Quantity(5),
                ),
            ),
        )
    }

    private fun order(
        marketId: String,
        orderId: String,
        side: Side = Side.SELL,
    ): SubmitOrderCommand =
        SubmitOrderCommand(
            marketId = MarketId(marketId),
            orderId = OrderId(orderId),
            userId = UserId(if (side == Side.SELL) "seller-1" else "buyer-1"),
            side = side,
            orderType = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            price = Price(100),
            quantity = Quantity(5),
        )
}
