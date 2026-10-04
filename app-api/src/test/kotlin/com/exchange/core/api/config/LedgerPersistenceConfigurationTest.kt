package com.exchange.core.api.config

import com.exchange.core.api.ledger.infrastructure.persistence.PostgresBalanceStore
import com.exchange.core.api.ledger.infrastructure.persistence.PostgresLedgerTransactionStore
import com.exchange.core.api.order.application.OrderFundingService
import com.exchange.core.api.order.application.OrderReservationReleaseService
import com.exchange.core.api.order.application.TradeSettlementService
import com.exchange.core.api.order.infrastructure.persistence.PostgresOrderReservationStore
import com.exchange.core.ledger.DevelopmentBalanceStore
import com.exchange.core.api.ledger.application.PrepareDevelopmentBalanceUseCase
import org.springframework.transaction.PlatformTransactionManager
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.order.OrderReservationStore
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Bean 조건과 포트 주입을 쿼리 없이 검증한다. 실제 JDBC·트랜잭션 결과는 기존 DB 테스트의 범위다. */
class LedgerPersistenceConfigurationTest {
    @Test
    fun `원장 영속화가 true이면 세 저장 포트와 예약 해제 정산 서비스를 등록한다`() {
        val jdbcTemplate = mock(NamedParameterJdbcTemplate::class.java)

        ledgerContext(enabled = "true", jdbcTemplate = jdbcTemplate).use { context ->
            val balanceStores = context.getBeansOfType(BalanceStore::class.java)
            val reservationStores = context.getBeansOfType(OrderReservationStore::class.java)
            val transactionStores = context.getBeansOfType(LedgerTransactionStore::class.java)

            assertEquals(1, balanceStores.size)
            assertIs<PostgresBalanceStore>(balanceStores.values.single())
            assertEquals(1, reservationStores.size)
            assertIs<PostgresOrderReservationStore>(reservationStores.values.single())
            assertEquals(1, transactionStores.size)
            assertIs<PostgresLedgerTransactionStore>(transactionStores.values.single())
            assertEquals(1, context.getBeansOfType(OrderFundingService::class.java).size)
            assertEquals(1, context.getBeansOfType(OrderReservationReleaseService::class.java).size)
            assertEquals(1, context.getBeansOfType(TradeSettlementService::class.java).size)
            assertEquals(1, context.getBeansOfType(DevelopmentBalanceStore::class.java).size)
            assertEquals(1, context.getBeansOfType(PrepareDevelopmentBalanceUseCase::class.java).size)
            verifyNoInteractions(jdbcTemplate)
        }
    }

    @Test
    fun `원장 영속화가 false이면 JDBC 의존성 없이 저장 포트와 서비스를 등록하지 않는다`() {
        ledgerContext(enabled = "false").use(::assertNoPersistenceBeans)
    }

    @Test
    fun `원장 영속화 설정이 없으면 JDBC 의존성 없이 저장 포트와 서비스를 등록하지 않는다`() {
        ledgerContext(enabled = null).use(::assertNoPersistenceBeans)
    }

    private fun ledgerContext(
        enabled: String?,
        jdbcTemplate: NamedParameterJdbcTemplate? = null,
    ): AnnotationConfigApplicationContext =
        AnnotationConfigApplicationContext().apply {
            if (enabled != null) {
                environment.propertySources.addFirst(
                    MapPropertySource(
                        "ledger-test",
                        mapOf("exchange.ledger.persistence.enabled" to enabled),
                    ),
                )
            }
            if (jdbcTemplate != null) {
                beanFactory.registerSingleton("namedParameterJdbcTemplate", jdbcTemplate)
                beanFactory.registerSingleton("transactionManager", mock(PlatformTransactionManager::class.java))
            }
            register(LedgerPersistenceConfig::class.java)
            refresh()
        }

    private fun assertNoPersistenceBeans(context: AnnotationConfigApplicationContext) {
        assertTrue(context.getBeansOfType(DevelopmentBalanceStore::class.java).isEmpty())
        assertTrue(context.getBeansOfType(PrepareDevelopmentBalanceUseCase::class.java).isEmpty())
        assertTrue(context.getBeansOfType(BalanceStore::class.java).isEmpty())
        assertTrue(context.getBeansOfType(OrderReservationStore::class.java).isEmpty())
        assertTrue(context.getBeansOfType(LedgerTransactionStore::class.java).isEmpty())
        assertTrue(context.getBeansOfType(OrderFundingService::class.java).isEmpty())
        assertTrue(context.getBeansOfType(OrderReservationReleaseService::class.java).isEmpty())
        assertTrue(context.getBeansOfType(TradeSettlementService::class.java).isEmpty())
        assertTrue(context.getBeansOfType(NamedParameterJdbcTemplate::class.java).isEmpty())
    }
}
