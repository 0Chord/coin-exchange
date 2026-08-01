package com.exchange.core.api.matching.persistence

import com.exchange.core.api.matching.MatchingApplicationService
import com.exchange.core.api.matching.publish.MatchingEventPublisher
import com.exchange.core.common.*
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "exchange.matching.persistence.enabled=true",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
    ]
)
@Testcontainers
class MatchingPersistenceIntegrationTest {

    @Autowired
    private lateinit var applicationService: MatchingApplicationService

    @Autowired
    private lateinit var eventPublisher: MatchingEventPublisher

    @Autowired
    private lateinit var repository: MatchingEventRepository

    @BeforeEach
    fun setUp() {
        repository.deleteAll()
    }

    @Test
    fun `matching 결과를 PostgreSQL event store에 저장한다`() {
        assertIs<PersistentMatchingEventPublisher>(eventPublisher)

        val events = applicationService.process(
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

        val saved = repository
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

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(
                DockerImageName.parse("postgres:16-alpine"),
            )

        @DynamicPropertySource
        @JvmStatic
        fun registerPostgresProperties(
            registry: DynamicPropertyRegistry,
        ) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}