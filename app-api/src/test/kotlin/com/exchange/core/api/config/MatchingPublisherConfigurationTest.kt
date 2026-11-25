package com.exchange.core.api.config

import com.exchange.core.api.matching.application.MatchingCoordinator
import com.exchange.core.api.matching.application.port.MatchingEventPublisher
import com.exchange.core.api.matching.application.port.MatchingEventStore
import com.exchange.core.api.matching.infrastructure.persistence.JpaMatchingEventStore
import com.exchange.core.api.matching.infrastructure.persistence.MatchingEventRepository
import com.exchange.core.api.matching.infrastructure.persistence.PersistentMatchingEventPublisher
import com.exchange.core.api.matching.infrastructure.publish.NoOpMatchingEventPublisher
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 두 매칭 설정의 Bean 선택과 NoOp 이후 처리만 검증하며 실제 DB 저장은 기존 통합 검사에 맡긴다. */
class MatchingPublisherConfigurationTest {
    @Test
    fun `영속화가 true이면 Jpa Store와 영속 발행자 하나를 선택한다`() {
        matchingContext(enabled = "true").use { context ->
            val publishers = context.getBeansOfType(MatchingEventPublisher::class.java)
            val stores = context.getBeansOfType(MatchingEventStore::class.java)

            assertEquals(1, publishers.size)
            assertIs<PersistentMatchingEventPublisher>(publishers.values.single())
            assertEquals(1, stores.size)
            assertIs<JpaMatchingEventStore>(stores.values.single())
            assertTrue(context.getBeansOfType(NoOpMatchingEventPublisher::class.java).isEmpty())
        }
    }

    @Test
    fun `영속화가 false이면 저장 의존성 없이 NoOp 하나로 후속 처리와 결과 반환을 완료한다`() {
        matchingContext(enabled = "false").use { context ->
            assertNoOpSelection(context)
            assertNoOpCompletesCommand(context)
        }
    }

    @Test
    fun `영속화 설정이 없으면 저장 의존성 없이 NoOp 하나로 후속 처리와 결과 반환을 완료한다`() {
        matchingContext(enabled = null).use { context ->
            assertNoOpSelection(context)
            assertNoOpCompletesCommand(context)
        }
    }

    private fun matchingContext(enabled: String?): AnnotationConfigApplicationContext =
        AnnotationConfigApplicationContext().apply {
            if (enabled != null) {
                environment.propertySources.addFirst(
                    MapPropertySource(
                        "matching-test",
                        mapOf("exchange.matching.persistence.enabled" to enabled),
                    ),
                )
            }
            if (enabled == "true") {
                beanFactory.registerSingleton(
                    "matchingEventRepository",
                    mock(MatchingEventRepository::class.java),
                )
                beanFactory.registerSingleton("objectMapper", jacksonObjectMapper())
            }
            register(MatchingConfig::class.java, MatchingPersistenceConfig::class.java)
            refresh()
        }

    private fun assertNoOpSelection(context: AnnotationConfigApplicationContext) {
        val publishers = context.getBeansOfType(MatchingEventPublisher::class.java)

        assertEquals(1, publishers.size)
        assertIs<NoOpMatchingEventPublisher>(publishers.values.single())
        assertTrue(context.getBeansOfType(MatchingEventStore::class.java).isEmpty())
        assertTrue(context.getBeansOfType(JpaMatchingEventStore::class.java).isEmpty())
        assertTrue(context.getBeansOfType(PersistentMatchingEventPublisher::class.java).isEmpty())
        assertTrue(context.getBeansOfType(MatchingEventRepository::class.java).isEmpty())
        assertTrue(context.getBeansOfType(ObjectMapper::class.java).isEmpty())
    }

    private fun assertNoOpCompletesCommand(context: AnnotationConfigApplicationContext) {
        val coordinator = context.getBean(MatchingCoordinator::class.java)
        val callbackEvents = AtomicReference<List<MatchingEvent>>()
        val events =
            coordinator.process(
                command =
                    SubmitOrderCommand(
                        marketId = MarketId("BTC-KRW"),
                        orderId = OrderId("ask-1"),
                        userId = UserId("seller-1"),
                        side = Side.SELL,
                        orderType = OrderType.LIMIT,
                        timeInForce = TimeInForce.GTC,
                        price = Price(100),
                        quantity = Quantity(5),
                    ),
                afterMatching = { callbackEvents.set(it) },
            )

        assertSame(events, callbackEvents.get())
        val event = assertIs<OrderEnteredBook>(events.single())
        assertEquals(MarketId("BTC-KRW"), event.marketId)
        assertEquals(OrderId("ask-1"), event.orderId)
        assertEquals(1L, event.engineSequence)
    }
}
