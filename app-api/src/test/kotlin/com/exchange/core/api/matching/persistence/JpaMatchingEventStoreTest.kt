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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class JpaMatchingEventStoreTest {
    @Autowired
    private lateinit var repository: MatchingEventRepository

    private lateinit var store: JpaMatchingEventStore

    @BeforeEach
    fun setUp() {
        store = JpaMatchingEventStore(
            repository = repository,
            objectMapper = jacksonObjectMapper(),
        )
    }

    @Test
    fun `빈 event 목록은 아무 row도 저장하지 않는다`() {
        store.append(emptyList())

        assertEquals(0, repository.count())
    }

    @Test
    fun `OrderEnteredBook event를 주요 컬럼과 payloadJson으로 저장한다`() {
        store.append(
            listOf(
                OrderEnteredBook(
                    marketId = MarketId("BTC-KRW"),
                    engineSequence = 1,
                    orderId = OrderId("bid-1"),
                    userId = UserId("user-1"),
                    side = Side.BUY,
                    price = Price(100_000_000),
                    remainingQuantity = Quantity(30_000),
                ),
            ),
        )

        val saved = repository.findByMarketIdOrderByEngineSequenceAsc("BTC-KRW").single()

        assertNotNull(saved.id)
        assertEquals("BTC-KRW", saved.marketId)
        assertEquals(1, saved.engineSequence)
        assertEquals(MatchingEventType.ORDER_ENTERED_BOOK, saved.eventType)
        assertEquals("bid-1", saved.orderId)
        assertEquals("user-1", saved.userId)
        assertEquals("BUY", saved.side)
        assertEquals(100_000_000, saved.price)
        assertEquals(30_000, saved.remainingQuantity)
        assertNotNull(saved.createdAt)
        assertPayloadField(saved.payloadJson, "type", "ORDER_ENTERED_BOOK")
        assertPayloadField(saved.payloadJson, "orderId", "bid-1")
    }

    @Test
    fun `TradeExecuted event를 maker taker 컬럼과 payloadJson으로 저장한다`() {
        store.append(
            listOf(
                TradeExecuted(
                    marketId = MarketId("BTC-KRW"),
                    engineSequence = 2,
                    makerOrderId = OrderId("ask-1"),
                    takerOrderId = OrderId("bid-1"),
                    makerUserId = UserId("seller-1"),
                    takerUserId = UserId("buyer-1"),
                    side = Side.BUY,
                    price = Price(100_000_000),
                    quantity = Quantity(10_000),
                ),
            ),
        )

        val saved = repository.findByMarketIdOrderByEngineSequenceAsc("BTC-KRW").single()

        assertEquals(MatchingEventType.TRADE_EXECUTED, saved.eventType)
        assertEquals("ask-1", saved.makerOrderId)
        assertEquals("bid-1", saved.takerOrderId)
        assertEquals("seller-1", saved.makerUserId)
        assertEquals("buyer-1", saved.takerUserId)
        assertEquals("BUY", saved.side)
        assertEquals(100_000_000, saved.price)
        assertEquals(10_000, saved.quantity)
        assertPayloadField(saved.payloadJson, "type", "TRADE_EXECUTED")
        assertPayloadField(saved.payloadJson, "makerOrderId", "ask-1")
    }

    @Test
    fun `OrderCancelled event를 취소 주문 컬럼으로 저장한다`() {
        store.append(
            listOf(
                OrderCancelled(
                    marketId = MarketId("BTC-KRW"),
                    engineSequence = 3,
                    orderId = OrderId("bid-1"),
                    userId = UserId("user-1"),
                    remainingQuantity = Quantity(20_000),
                ),
            ),
        )

        val saved = repository.findByMarketIdOrderByEngineSequenceAsc("BTC-KRW").single()

        assertEquals(MatchingEventType.ORDER_CANCELLED, saved.eventType)
        assertEquals("bid-1", saved.orderId)
        assertEquals("user-1", saved.userId)
        assertEquals(20_000, saved.remainingQuantity)
        assertPayloadField(saved.payloadJson, "type", "ORDER_CANCELLED")
        assertPayloadField(saved.payloadJson, "remainingQuantity", "20000")
    }

    @Test
    fun `OrderCancelRejected event를 실패 이유와 함께 저장한다`() {
        store.append(
            listOf(
                OrderCancelRejected(
                    marketId = MarketId("BTC-KRW"),
                    engineSequence = 4,
                    orderId = OrderId("missing-order"),
                    userId = UserId("user-1"),
                    reason = "order not found",
                ),
            ),
        )

        val saved = repository.findByMarketIdOrderByEngineSequenceAsc("BTC-KRW").single()

        assertEquals(MatchingEventType.ORDER_CANCEL_REJECTED, saved.eventType)
        assertEquals("missing-order", saved.orderId)
        assertEquals("user-1", saved.userId)
        assertEquals("order not found", saved.reason)
        assertPayloadField(saved.payloadJson, "type", "ORDER_CANCEL_REJECTED")
        assertPayloadField(saved.payloadJson, "reason", "order not found")
    }

    @Test
    fun `marketId와 engineSequence 기준으로 오름차순 조회한다`() {
        store.append(
            listOf(
                enteredBook(engineSequence = 3, orderId = "bid-3"),
                enteredBook(engineSequence = 1, orderId = "bid-1"),
                enteredBook(engineSequence = 2, orderId = "bid-2"),
                enteredBook(marketId = "ETH-KRW", engineSequence = 1, orderId = "eth-bid-1"),
            ),
        )

        val saved = repository.findByMarketIdOrderByEngineSequenceAsc("BTC-KRW")

        assertEquals(listOf(1L, 2L, 3L), saved.map { it.engineSequence })
        assertEquals(listOf("bid-1", "bid-2", "bid-3"), saved.map { it.orderId })
    }

    @Test
    fun `같은 marketId와 engineSequence는 중복 저장할 수 없다`() {
        store.append(
            listOf(
                enteredBook(marketId = "BTC-KRW", engineSequence = 1, orderId = "bid-1"),
            ),
        )
        repository.flush()

        assertFailsWith<DataIntegrityViolationException> {
            store.append(
                listOf(
                    enteredBook(marketId = "BTC-KRW", engineSequence = 1, orderId = "bid-duplicate"),
                ),
            )
            repository.flush()
        }
    }

    @Test
    fun `marketId가 다르면 같은 engineSequence도 저장할 수 있다`() {
        store.append(
            listOf(
                enteredBook(marketId = "BTC-KRW", engineSequence = 1, orderId = "btc-bid-1"),
                enteredBook(marketId = "ETH-KRW", engineSequence = 1, orderId = "eth-bid-1"),
            ),
        )
        repository.flush()

        assertEquals(2, repository.count())
    }

    private fun enteredBook(
        marketId: String = "BTC-KRW",
        engineSequence: Long,
        orderId: String,
    ): OrderEnteredBook =
        OrderEnteredBook(
            marketId = MarketId(marketId),
            engineSequence = engineSequence,
            orderId = OrderId(orderId),
            userId = UserId("user-$orderId"),
            side = Side.BUY,
            price = Price(100_000_000),
            remainingQuantity = Quantity(10_000),
        )

    private fun assertPayloadField(
        payloadJson: String,
        fieldName: String,
        expectedValue: String,
    ) {
        val actual = jacksonObjectMapper().readTree(payloadJson).path(fieldName).asString()

        assertEquals(expectedValue, actual)
        assertTrue(payloadJson.contains(fieldName))
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))

        @DynamicPropertySource
        @JvmStatic
        fun registerPostgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
