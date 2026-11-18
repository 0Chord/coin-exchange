package com.exchange.core.api.config

import com.exchange.core.ExchangeCoreApplication
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

/** 제품의 공용 DB 자동 설정과 조회 구성을 검사하며 실제 Aeron 접속은 포함하지 않는다. */
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
    fun resetProjection() {
        if (database.jdbc().queryForObject("select to_regclass('order_query.query_progress') is not null", Boolean::class.java)!!) {
            database.jdbc().execute(
                """
                truncate order_query.settlement_posting, order_query.settlement_transaction, order_query.source_receipt,
                    order_query.feed_stream, order_query.feed_consumer, order_query.applied_batch,
                    order_query.order_record, order_query.query_progress
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `프로필과 조회 활성화 설정 없이 공용 DB 자원을 한 번씩 등록한다`() {
        queryContext(database.properties).use { context ->
            assertTrue(context.environment.activeProfiles.isEmpty())
            assertEquals(1, context.getBeansOfType(OrderQueryController::class.java).size)
            assertEquals(1, context.getBeansOfType(OrderQueryUseCase::class.java).size)
            assertEquals(1, context.getBeansOfType(OrderQueryStore::class.java).size)
            assertEquals(setOf("dataSource"), context.getBeansOfType(DataSource::class.java).keys)
            assertEquals(setOf("flyway"), context.getBeansOfType(Flyway::class.java).keys)
            assertEquals(setOf("namedParameterJdbcTemplate"), context.getBeansOfType(NamedParameterJdbcTemplate::class.java).keys)
            assertEquals(setOf("transactionManager"), context.getBeansOfType(PlatformTransactionManager::class.java).keys)
            assertFalse(context.containsBean("orderQueryDataSource"))
            assertFalse(context.containsBean("orderQueryFlyway"))
        }
    }

    @Test
    fun `공용 마이그레이션 뒤 조회를 초기화하며 실제 입력 전에는 준비되지 않은 상태다`() {
        queryContext(database.properties).use { context ->
            val orderId = TradingIdentifiers.order(ClusterLineage(41, 42), 1).value
            assertEquals(OrderQueryCode.QUERY_NOT_READY, context.getBean(OrderQueryUseCase::class.java).get("BTC-USDT", orderId).code)
            val tables =
                database
                    .jdbc()
                    .queryForList(
                        "select tablename from pg_tables where schemaname = 'order_query'",
                        String::class.java,
                    ).toSet()
            assertEquals(
                setOf(
                    "query_progress",
                    "order_record",
                    "applied_batch",
                    "feed_consumer",
                    "feed_stream",
                    "source_receipt",
                    "settlement_transaction",
                    "settlement_posting",
                ),
                tables,
            )
            assertEquals(
                (1..12).map(Int::toString),
                database.jdbc().queryForList(
                    "select version from flyway_schema_history where success order by installed_rank",
                    String::class.java,
                ),
            )
            assertTrue(
                database.jdbc().queryForObject(
                    "select to_regclass('public.market_configuration_release') is not null",
                    Boolean::class.java,
                )!!,
            )
        }
    }

    @Test
    fun `제품 공용 DB 자동 설정은 기존 JPA 저장소와 금융 쓰기를 활성화하지 않는다`() {
        queryContext(database.properties).use { context ->
            assertTrue(ExchangeCoreApplication::class.java.packageName in AutoConfigurationPackages.get(context))
            assertTrue(context.getBeansOfType(EntityManagerFactory::class.java).isEmpty())
            assertTrue(context.getBeansOfType(JpaRepository::class.java).isEmpty())
            assertTrue(context.getBeansOfType(MatchingCoordinator::class.java).isEmpty())
            assertTrue(context.getBeansOfType(OrderFundingService::class.java).isEmpty())
            assertTrue(context.getBeansOfType(PostgresOrderReservationStore::class.java).isEmpty())
            assertTrue(context.getBeansOfType(PostgresLedgerTransactionStore::class.java).isEmpty())
        }
    }

    @Test
    fun `기존 V9 RDB에 주문 조회와 정산 원장을 추가해도 마켓 설정 원문은 보존된다`() {
        OrderQueryTestDatabase().use { existing ->
            Flyway
                .configure()
                .dataSource(
                    existing.properties.getValue("spring.datasource.url"),
                    existing.properties.getValue("spring.datasource.username"),
                    existing.properties.getValue("spring.datasource.password"),
                ).locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate()
            existing.jdbc().execute("insert into market_configuration_release(config_version, markets_document) values (7, '[]')")
            val before = existing.jdbc().queryForMap("select * from market_configuration_release where config_version = 7")
            queryContext(existing.properties).use { context ->
                assertEquals(before, existing.jdbc().queryForMap("select * from market_configuration_release where config_version = 7"))
                assertEquals(
                    12,
                    existing.jdbc().queryForObject("select count(*) from flyway_schema_history where success", Int::class.java),
                )
                val orderId = TradingIdentifiers.order(ClusterLineage(41, 42), 1).value
                assertEquals(OrderQueryCode.QUERY_NOT_READY, context.getBean(OrderQueryUseCase::class.java).get("BTC-USDT", orderId).code)
            }
        }
    }

    @Test
    fun `조회 범위 누락 잘못된 마켓과 공용 DB 연결 실패는 시작을 실패시킨다`() {
        assertFailsWith<BeansException> { queryContext(database.properties - "exchange.order-query.funds-lineage-high") }
        assertFailsWith<BeansException> {
            queryContext(database.properties + ("exchange.order-query.supported-markets" to "BTC-USDT,BTC-USDT"))
        }
        assertFailsWith<BeansException> {
            queryContext(
                database.properties + ("spring.datasource.url" to "jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1"),
            )
        }
    }

    @Test
    fun `공용 V10 적용 실패는 기존 테이블을 덮어쓰거나 조회를 시작하지 않는다`() {
        OrderQueryTestDatabase().use { conflicting ->
            conflicting.jdbc().execute("create schema order_query")
            conflicting.jdbc().execute("create table order_query.query_progress(id bigint)")
            conflicting.jdbc().execute("insert into order_query.query_progress values (7)")
            assertFailsWith<BeansException> { queryContext(conflicting.properties) }
            assertEquals(7L, conflicting.jdbc().queryForObject("select id from order_query.query_progress", Long::class.java))
            assertFalse(
                conflicting.jdbc().queryForObject("select to_regclass('order_query.order_record') is not null", Boolean::class.java)!!,
            )
            assertEquals(
                0,
                conflicting.jdbc().queryForObject("select count(*) from flyway_schema_history where version = '10'", Int::class.java),
            )
        }
    }

    private fun queryContext(properties: Map<String, String>): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        try {
            val application = ExchangeCoreApplication::class.java
            val exclusions = application.getAnnotation(SpringBootApplication::class.java).excludeName
            context.environment.propertySources.addFirst(
                MapPropertySource(
                    "order-query-common-database-test",
                    properties + ("spring.autoconfigure.exclude" to exclusions.joinToString(",")),
                ),
            )
            AutoConfigurationPackages.register(context, application.packageName)
            context.register(
                OrderQueryProductAutoConfigurationTestConfig::class.java,
                OrderQueryConfig::class.java,
                OrderQueryController::class.java,
            )
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
