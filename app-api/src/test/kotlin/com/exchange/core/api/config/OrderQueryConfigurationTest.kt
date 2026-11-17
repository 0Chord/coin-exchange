package com.exchange.core.api.config

import com.exchange.core.TradingGatewayApplication
import com.exchange.core.api.ledger.infrastructure.persistence.PostgresLedgerTransactionStore
import com.exchange.core.api.matching.application.MatchingCoordinator
import com.exchange.core.api.order.application.OrderFundingService
import com.exchange.core.api.order.infrastructure.persistence.PostgresOrderReservationStore
import com.exchange.core.api.trading.api.OrderQueryController
import com.exchange.core.api.trading.application.OrderQueryCode
import com.exchange.core.api.trading.application.OrderQueryUseCase
import com.exchange.core.api.trading.application.port.OrderQueryStore
import com.exchange.core.support.OrderQueryTestDatabase
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingIdentifiers
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.BeansException
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 제품 조회 설정만 조립한다. 제품 main의 Aeron 접속 완주를 의미하지 않는다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderQueryConfigurationTest {
    private lateinit var database: OrderQueryTestDatabase

    @BeforeAll
    fun start() {
        database = OrderQueryTestDatabase()
    }

    @AfterAll
    fun stop() {
        if (::database.isInitialized) database.close()
    }

    @BeforeEach
    fun resetSchema() {
        database.jdbc().execute("drop schema if exists order_query cascade")
    }

    @Test
    fun `기본 비활성과 명시적 비활성은 잘못된 설정에서도 조회 경로와 DB를 만들지 않는다`() {
        listOf(emptyMap(), mapOf("exchange.order-query.enabled" to "false")).forEach { switch ->
            queryContext(
                switch +
                    mapOf(
                        "exchange.order-query.jdbc-url" to "not-a-database",
                        "exchange.order-query.funds-lineage-high" to "not-a-number",
                    ),
            ).use { context ->
                assertTrue(context.getBeansOfType(OrderQueryController::class.java).isEmpty())
                assertTrue(context.getBeansOfType(OrderQueryUseCase::class.java).isEmpty())
                assertTrue(context.getBeansOfType(OrderQueryStore::class.java).isEmpty())
                assertTrue(context.getBeansOfType(DataSource::class.java).isEmpty())
                assertTrue(context.getBeansOfType(Flyway::class.java).isEmpty())
                assertTrue(context.getBeansOfType(NamedParameterJdbcTemplate::class.java).isEmpty())
                assertTrue(context.getBeansOfType(PlatformTransactionManager::class.java).isEmpty())
            }
        }
        assertFalse(
            database.jdbc().queryForObject(
                "select exists(select 1 from pg_namespace where nspname = 'order_query')",
                Boolean::class.java,
            )!!,
        )
    }

    @Test
    fun `enabled query configuration migrates only its schema and starts unready without legacy writers`() {
        queryContext(database.properties).use { context ->
            assertEquals(1, context.getBeansOfType(OrderQueryController::class.java).size)
            assertEquals(1, context.getBeansOfType(DataSource::class.java).size)
            assertEquals(1, context.getBeansOfType(Flyway::class.java).size)
            assertEquals(1, context.getBeansOfType(NamedParameterJdbcTemplate::class.java).size)
            assertEquals(1, context.getBeansOfType(PlatformTransactionManager::class.java).size)
            assertTrue(context.getBeansOfType(OrderFundingService::class.java).isEmpty())
            val orderId = TradingIdentifiers.order(ClusterLineage(41, 42), 1).value
            assertEquals(OrderQueryCode.QUERY_NOT_READY, context.getBean(OrderQueryUseCase::class.java).get("BTC-USDT", orderId).code)
            val tables =
                database
                    .jdbc()
                    .queryForList(
                        "select tablename from pg_tables where schemaname = 'order_query'",
                        String::class.java,
                    ).toSet()
            assertEquals(setOf("query_progress", "order_record", "applied_batch", "flyway_order_query_history"), tables)
            assertEquals(0, database.jdbc().queryForObject("select count(*) from pg_tables where schemaname = 'public'", Int::class.java))
        }
    }

    @Test
    fun `제품 메인의 자동 설정 제외는 조회 DB를 켜도 기존 JPA 저장소와 금융 쓰기를 활성화하지 않는다`() {
        queryAutoConfigurationContext().use { context ->
            assertEquals(setOf("orderQueryDataSource"), context.getBeansOfType(DataSource::class.java).keys)
            assertEquals(1, context.getBeansOfType(OrderQueryStore::class.java).size)
            assertTrue(TradingGatewayApplication::class.java.packageName in AutoConfigurationPackages.get(context))
            assertTrue(context.getBeansOfType(EntityManagerFactory::class.java).isEmpty())
            assertTrue(context.getBeansOfType(JpaRepository::class.java).isEmpty())
            assertTrue(context.getBeansOfType(MatchingCoordinator::class.java).isEmpty())
            assertTrue(context.getBeansOfType(OrderFundingService::class.java).isEmpty())
            assertTrue(context.getBeansOfType(PostgresOrderReservationStore::class.java).isEmpty())
            assertTrue(context.getBeansOfType(PostgresLedgerTransactionStore::class.java).isEmpty())
        }
    }

    @Test
    fun `enabled settings fail assembly for missing namespace bad database or failed migration`() {
        assertFailsWith<BeansException> { queryContext(database.properties - "exchange.order-query.funds-lineage-high") }
        assertFailsWith<BeansException> {
            queryContext(
                database.properties + ("exchange.order-query.jdbc-url" to "jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1"),
            )
        }
        database.jdbc().execute("create schema order_query")
        database.jdbc().execute("create table order_query.unmanaged_table(id bigint)")
        assertFailsWith<BeansException> { queryContext(database.properties) }
        assertFalse(database.jdbc().queryForObject("select exists(select 1 from order_query.unmanaged_table)", Boolean::class.java)!!)
    }

    @Test
    fun `enabled flag in another profile leaves the bounded query absent`() {
        queryContext(database.properties, "aeron-gateway").use { context ->
            assertTrue(context.getBeansOfType(OrderQueryController::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DataSource::class.java).isEmpty())
        }
    }

    private fun queryContext(
        properties: Map<String, String>,
        profile: String = "aeron-bounded-gateway",
    ): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        try {
            context.environment.setActiveProfiles(profile)
            context.environment.propertySources.addFirst(MapPropertySource("order-query-test", properties))
            context.register(OrderQueryConfig::class.java, OrderQueryController::class.java)
            context.refresh()
            return context
        } catch (error: Throwable) {
            context.close()
            throw error
        }
    }

    /** 제품 main의 패키지와 제외 목록을 재사용하며 Aeron 시작 구성은 가져오지 않는다. */
    private fun queryAutoConfigurationContext(): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        try {
            val application = TradingGatewayApplication::class.java
            val exclusions = application.getAnnotation(SpringBootApplication::class.java).excludeName
            context.environment.setActiveProfiles("aeron-bounded-gateway")
            context.environment.propertySources.addFirst(
                MapPropertySource(
                    "order-query-product-auto-configuration-test",
                    database.properties + ("spring.autoconfigure.exclude" to exclusions.joinToString(",")),
                ),
            )
            AutoConfigurationPackages.register(context, application.packageName)
            context.register(OrderQueryProductAutoConfigurationTestConfig::class.java, OrderQueryConfig::class.java)
            context.refresh()
            return context
        } catch (error: Throwable) {
            context.close()
            throw error
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
internal class OrderQueryProductAutoConfigurationTestConfig
