package com.exchange.core.api.trading.api

import com.exchange.core.api.common.TradingOrderHttpExceptionHandler
import com.exchange.core.api.config.OrderQueryConfig
import com.exchange.core.api.trading.application.BoundedTradingOrderGateway
import com.exchange.core.api.trading.application.BoundedTradingOrderUseCase
import com.exchange.core.api.trading.application.OrderQueryBatch
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryOrderState
import com.exchange.core.api.trading.application.OrderQueryRecord
import com.exchange.core.api.trading.application.port.OrderQueryStore
import com.exchange.core.api.trading.infrastructure.persistence.PostgresOrderQueryStore
import com.exchange.core.common.MarketId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.support.OrderQueryTestDatabase
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.TradingIdentifiers
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 제품 조회 Controller/설정을 실제 PostgreSQL과 HTTP로 검사하는 테스트 조립이다. 실제 거래 연결은 포함하지 않는다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderQueryHttpTest {
    private lateinit var database: OrderQueryTestDatabase
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var store: OrderQueryStore
    private lateinit var url: String
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = JsonMapper.builder().build()
    private val lineage = ClusterLineage(41, 42)

    @BeforeAll
    fun start() {
        database = OrderQueryTestDatabase()
        openContext()
    }

    @AfterAll
    fun stop() {
        if (::context.isInitialized) context.close()
        client.close()
        if (::database.isInitialized) database.close()
    }

    @BeforeEach
    fun resetProjection() {
        database.jdbc().execute(
            """
            truncate order_query.settlement_posting, order_query.settlement_transaction, order_query.source_receipt,
                order_query.feed_stream, order_query.feed_consumer, order_query.applied_batch,
                order_query.order_record, order_query.query_progress
            """.trimIndent(),
        )
        store.initialize()
        (store as PostgresOrderQueryStore).verifySourceReads()
    }

    @Test
    fun `HTTP quantities and money processing remain separate for all confirmed order states`() {
        val records =
            listOf(
                record(1, OrderQueryOrderState.MATCHING_PENDING),
                record(2, OrderQueryOrderState.RESTING),
                record(3, OrderQueryOrderState.PARTIALLY_FILLED),
                record(4, OrderQueryOrderState.FILLED),
                record(5, OrderQueryOrderState.CANCELLED),
                record(6, OrderQueryOrderState.NOT_EXECUTED),
            )
        apply(records)
        store.transitionLifecycle(OrderQueryLifecycle.READY)
        val expected =
            listOf(
                listOf(0L, null, 0L, 0L, "MATCHING"),
                listOf(0L, 3L, 0L, 0L, "RESULT_PROCESSING"),
                listOf(1L, 2L, 0L, 0L, "SETTLEMENT"),
                listOf(3L, 0L, 0L, 0L, "SETTLEMENT"),
                listOf(1L, 0L, 2L, 0L, "RELEASE"),
                listOf(0L, 0L, 0L, 3L, "RELEASE"),
            )
        records.zip(expected).forEach { (record, quantities) ->
            val response = get(record.orderId)
            assertEquals(200, response.statusCode())
            val body = body(response)
            assertEquals("ORDER_FOUND", body.path("code").asString())
            assertEquals(record.orderId, body.path("orderId").asString())
            assertEquals("BTC-USDT", body.path("marketId").asString())
            assertEquals("U1", body.path("userId").asString())
            assertEquals("BUY", body.path("side").asString())
            assertEquals("LIMIT", body.path("orderType").asString())
            assertEquals("GTC", body.path("timeInForce").asString())
            assertEquals(100L, body.path("price").longValue())
            assertEquals(3L, body.path("initialQuantity").longValue())
            assertEquals(quantities[0], body.path("filledQuantity").longValue())
            if (quantities[1] ==
                null
            ) {
                assertTrue(body.path("remainingQuantity").isNull)
            } else {
                assertEquals(quantities[1], body.path("remainingQuantity").longValue())
            }
            assertEquals(quantities[2], body.path("cancelledQuantity").longValue())
            assertEquals(quantities[3], body.path("notExecutedQuantity").longValue())
            assertEquals(record.state.name, body.path("state").asString())
            assertEquals("PENDING", body.path("financialStatus").asString())
            assertEquals(quantities[4], body.path("pendingReason").asString())
            if (record.state == OrderQueryOrderState.MATCHING_PENDING) {
                assertTrue(body.path("resultStream").isNull)
            } else {
                assertEquals(51L, body.path("resultStream").path("matchingLineageHigh").longValue())
                assertEquals(41L, body.path("resultStream").path("fundsLineageHigh").longValue())
                assertEquals("BTC-USDT", body.path("resultStream").path("marketId").asString())
                assertEquals(1L, body.path("lastAffectedResultSequence").longValue())
                assertEquals(0L, body.path("financialThrough").longValue())
            }
            assertEquals(1L, body.path("appliedRevision").longValue())
            assertEquals(1L, body.path("observedRevision").longValue())
            assertEquals(6L, body.path("coveredAdmissionSequence").longValue())
            assertEquals("CAUGHT_UP", body.path("freshness").asString())
            Instant.parse(body.path("updatedAt").asString())
        }
    }

    @Test
    fun `confirmed cancellation and final non execution retain quantities when release becomes applied`() {
        val cancelled = record(1, OrderQueryOrderState.CANCELLED)
        val notExecuted = record(2, OrderQueryOrderState.NOT_EXECUTED)
        apply(listOf(cancelled, notExecuted))
        store.transitionLifecycle(OrderQueryLifecycle.READY)
        val pending = body(get(cancelled.orderId))
        assertEquals("PENDING", pending.path("financialStatus").asString())
        assertEquals("RELEASE", pending.path("pendingReason").asString())
        apply(listOf(cancelled.copy(financialThrough = 1), notExecuted.copy(financialThrough = 1)), expectedRevision = 1, covered = 2)
        listOf(cancelled, notExecuted).forEach { record ->
            val body = body(get(record.orderId))
            assertEquals("APPLIED", body.path("financialStatus").asString())
            assertTrue(body.path("pendingReason").isNull)
            assertEquals(record.cancelledQuantity, body.path("cancelledQuantity").longValue())
            assertEquals(record.notExecutedQuantity, body.path("notExecutedQuantity").longValue())
        }
    }

    @Test
    fun `initializing blocked and minimum admission checks precede found records`() {
        val record = record(1, OrderQueryOrderState.FILLED)
        apply(listOf(record))
        assertResponse(503, "QUERY_NOT_READY", get(record.orderId, "?minAdmissionSequence=9"), hideOrder = true)
        store.transitionLifecycle(OrderQueryLifecycle.READY)
        assertResponse(202, "QUERY_CATCHING_UP", get(record.orderId, "?minAdmissionSequence=2"), hideOrder = true)
        assertResponse(200, "ORDER_FOUND", get(record.orderId, "?minAdmissionSequence=1"))
        store.transitionLifecycle(OrderQueryLifecycle.BLOCKED)
        assertResponse(503, "QUERY_NOT_READY", get(record.orderId), hideOrder = true)
        assertResponse(503, "QUERY_NOT_READY", get(orderId(99)), hideOrder = true)
        database.jdbc().execute(
            """
            truncate order_query.settlement_posting, order_query.settlement_transaction, order_query.source_receipt,
                order_query.feed_stream, order_query.feed_consumer, order_query.applied_batch,
                order_query.order_record, order_query.query_progress
            """.trimIndent(),
        )
        assertResponse(503, "QUERY_NOT_READY", get(orderId(99)), hideOrder = true)
    }

    @Test
    fun `observed revision distinguishes stale found orders pending absence and absence as of the observed prefix`() {
        val record = record(1, OrderQueryOrderState.RESTING)
        apply(listOf(record))
        store.transitionLifecycle(OrderQueryLifecycle.READY)
        store.observe(2)
        val found = get(record.orderId)
        assertResponse(200, "ORDER_FOUND", found)
        assertEquals("LAGGING", body(found).path("freshness").asString())
        assertResponse(202, "QUERY_CATCHING_UP", get(orderId(99)), hideOrder = true)
        store.apply(OrderQueryBatch("no-new-admissions", 1, 1, emptyList()))
        val absent = get(orderId(99))
        assertResponse(404, "ORDER_NOT_FOUND_AS_OF", absent, hideOrder = true)
        assertEquals("CAUGHT_UP", body(absent).path("freshness").asString())
        assertEquals(1L, body(absent).path("coveredAdmissionSequence").longValue())
        assertEquals(2L, body(absent).path("appliedRevision").longValue())
        assertResponse(202, "QUERY_CATCHING_UP", get(orderId(99), "?minAdmissionSequence=2"), hideOrder = true)
    }

    @Test
    fun `체결 조회 커서는 두 양수 순번을 받고 빈 값과 빠진 값을 거절한다`() {
        val orderId = orderId(1)
        val invalidPages =
            listOf(
                "afterResultSequence=",
                "afterResultSequence=&afterEngineSequence=",
                "afterResultSequence=1",
                "afterResultSequence=1&afterEngineSequence=0",
                "afterResultSequence=1&afterEngineSequence=invalid",
                "limit=",
                "limit=101",
            )
        for (query in invalidPages) {
            val response = get(orderId, "/fills?$query")
            assertEquals(400, response.statusCode(), query)
            assertEquals("INVALID_INPUT", body(response).path("status").asString(), query)
        }
        assertResponse(503, "QUERY_NOT_READY", get(orderId, "/fills?afterResultSequence=1&afterEngineSequence=1&limit=1"))
    }

    @Test
    fun `path validation conversion and unsupported market failures preserve the existing POST error contract`() {
        val failures =
            listOf(
                get(orderId(1), "?minAdmissionSequence=-1"),
                get(orderId(1), "?minAdmissionSequence=not-an-integer"),
                get(orderId(1), "?minAdmissionSequence=9223372036854775808"),
                get("bad-order-id"),
                get("O2" + "a".repeat(33)),
                get(orderId(1), market = "UNKNOWN"),
                get(orderId(1), market = "a".repeat(257)),
                get("O2%27%20OR%201%3D1%20--"),
            )
        val invalidPost =
            post(
                """{"userId":"U1","channel":0,"epoch":1,"sequence":1,"expectedConfigurationVersion":1,"side":"BUY","orderType":"LIMIT","timeInForce":"GTC","price":0,"quantity":3}""",
            )
        assertEquals(400, invalidPost.statusCode())
        val expected = body(invalidPost)
        assertEquals("INVALID_INPUT", expected.path("status").asString())
        assertEquals("INVALID_REQUEST", expected.path("reason").asString())
        failures.forEach { response ->
            assertEquals(400, response.statusCode(), response.body())
            assertEquals(expected, body(response))
        }
        verifyNoInteractions(context.getBean(BoundedTradingOrderGateway::class.java))
    }

    @Test
    fun `database inconsistency and read failures return 503 while invalid input still takes priority`() {
        val record = record(1, OrderQueryOrderState.FILLED)
        apply(listOf(record))
        store.transitionLifecycle(OrderQueryLifecycle.READY)
        database.jdbc().execute(
            "update order_query.order_record set record_payload = jsonb_set(record_payload, '{filledQuantity}', '-1'::jsonb)",
        )
        assertResponse(503, "QUERY_INCONSISTENT", get(record.orderId), hideOrder = true)
        assertEquals(400, get(record.orderId, market = "UNKNOWN").statusCode())
        database.jdbc().execute("alter table order_query.order_record rename to unavailable_order_record")
        try {
            assertResponse(503, "QUERY_UNAVAILABLE", get(record.orderId), hideOrder = true)
            assertEquals(400, get(record.orderId, market = "UNKNOWN").statusCode())
        } finally {
            database.jdbc().execute("alter table order_query.unavailable_order_record rename to order_record")
        }
    }

    @Test
    fun `트랜잭션을 시작할 수 없으면 두 조회가 503을 반환하고 잘못된 입력은 400을 유지한다`() {
        val order = orderId(1)
        val pool = context.getBean(DataSource::class.java).unwrap(HikariDataSource::class.java)
        try {
            // 연결을 빌릴 수 없어도 잘못된 요청은 트랜잭션을 기다리지 않는다.
            val heldConnections = List(pool.maximumPoolSize) { pool.connection }
            try {
                assertEquals(400, get(order, market = "UNKNOWN").statusCode())
                assertEquals(400, get(order, "/fills", market = "UNKNOWN").statusCode())
                assertEquals(400, get(order, "/fills?limit=101").statusCode())
            } finally {
                heldConnections.forEach { it.close() }
            }

            pool.close()
            assertResponse(503, "QUERY_UNAVAILABLE", get(order), hideOrder = true)
            assertResponse(503, "QUERY_UNAVAILABLE", get(order, "/fills"))
        } finally {
            context.close()
            openContext()
        }
    }

    @Test
    fun `ready absence never exposes an order from another supported market or funds lineage`() {
        val record = record(1, OrderQueryOrderState.FILLED)
        apply(listOf(record))
        store.transitionLifecycle(OrderQueryLifecycle.READY)
        assertResponse(404, "ORDER_NOT_FOUND_AS_OF", get(record.orderId, market = "ETH-USDT"), hideOrder = true)
        val otherLineageOrder = TradingIdentifiers.order(ClusterLineage(51, 52), 1).value
        assertResponse(404, "ORDER_NOT_FOUND_AS_OF", get(otherLineageOrder), hideOrder = true)
    }

    @Test
    fun `repeated GET leaves every query row unchanged and terminal records survive context reopening`() {
        val record = record(1, OrderQueryOrderState.CANCELLED).copy(financialThrough = 1)
        apply(listOf(record))
        store.transitionLifecycle(OrderQueryLifecycle.READY)
        val before = databaseRows()
        val expected = body(get(record.orderId))
        repeat(3) { assertEquals(expected, body(get(record.orderId))) }
        assertEquals(before, databaseRows())
        verifyNoInteractions(context.getBean(BoundedTradingOrderGateway::class.java))
        context.close()
        openContext()
        assertEquals(expected, body(get(record.orderId)))
        assertEquals(before, databaseRows())
    }

    @Test
    fun `same WAS exposes submission and query without a profile or separate query database`() {
        assertTrue(context.environment.activeProfiles.isEmpty())
        assertEquals(1, context.getBeansOfType(OrderQueryController::class.java).size)
        assertEquals(1, context.getBeansOfType(BoundedTradingOrderController::class.java).size)
        assertEquals(setOf("dataSource"), context.getBeansOfType(DataSource::class.java).keys)
        assertEquals(
            "V10__create_order_query.sql",
            database.jdbc().queryForObject("select script from flyway_schema_history where version = '10'", String::class.java),
        )
        assertResponse(503, "QUERY_NOT_READY", get(orderId(1)), hideOrder = true)
    }

    @Test
    fun `소비자 설정 없이 재시작하면 이전 READY와 종료 주문을 보존해도 원본 재검증 전까지 503이다`() {
        val record = record(1, OrderQueryOrderState.CANCELLED).copy(financialThrough = 1)
        apply(listOf(record))
        store.transitionLifecycle(OrderQueryLifecycle.READY)
        val before = databaseRows()
        assertResponse(200, "ORDER_FOUND", get(record.orderId))
        context.close()
        openContext(sourceVerified = false)
        assertFalse(context.containsBean("orderQueryFeedWorker"))
        assertResponse(503, "QUERY_NOT_READY", get(record.orderId), hideOrder = true)
        assertResponse(503, "QUERY_NOT_READY", get(orderId(99)), hideOrder = true)
        assertEquals(before, databaseRows())
        verifyNoInteractions(context.getBean(BoundedTradingOrderGateway::class.java))
    }

    private fun openContext(sourceVerified: Boolean = true) {
        val args =
            database.properties.map { (key, value) -> "--$key=$value" } +
                listOf("--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")
        context = SpringApplicationBuilder(OrderQueryHttpTestConfig::class.java).run(*args.toTypedArray())
        store = context.getBean(OrderQueryStore::class.java)
        if (sourceVerified) {
            // 이 기반 검사는 직접 준비한 검증된 원본을 전제로 GET·DB를 검사한다. 실제 수집은 native 검사에서 다룬다.
            (store as PostgresOrderQueryStore).verifySourceReads()
        }
        url = "http://127.0.0.1:${(context as ServletWebServerApplicationContext).webServer!!.port}"
    }

    private fun record(
        sequence: Long,
        state: OrderQueryOrderState,
    ): OrderQueryRecord {
        val pending = state == OrderQueryOrderState.MATCHING_PENDING
        return OrderQueryRecord(
            fundsLineage = lineage,
            marketId = "BTC-USDT",
            userId = "U1",
            side = Side.BUY,
            orderType = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            price = 100,
            initialQuantity = 3,
            admissionSequence = sequence,
            marketAdmissionSequence = sequence,
            filledQuantity =
                if (state ==
                    OrderQueryOrderState.FILLED
                ) {
                    3
                } else if (state in
                    setOf(OrderQueryOrderState.PARTIALLY_FILLED, OrderQueryOrderState.CANCELLED)
                ) {
                    1
                } else {
                    0
                },
            remainingQuantity =
                if (pending) {
                    null
                } else if (state ==
                    OrderQueryOrderState.RESTING
                ) {
                    3
                } else if (state == OrderQueryOrderState.PARTIALLY_FILLED) {
                    2
                } else {
                    0
                },
            cancelledQuantity = if (state == OrderQueryOrderState.CANCELLED) 2 else 0,
            notExecutedQuantity = if (state == OrderQueryOrderState.NOT_EXECUTED) 3 else 0,
            state = state,
            resultStream = if (pending) null else MatchingResultStream(ClusterLineage(51, 52), lineage, MarketId("BTC-USDT"), 1, 1),
            lastAffectedResultSequence = if (pending) null else 1,
            financialThrough = if (pending) null else 0,
        )
    }

    private fun apply(
        records: List<OrderQueryRecord>,
        expectedRevision: Long = 0,
        covered: Long = records.maxOf { it.admissionSequence },
    ) {
        store.observe(expectedRevision + 1)
        store.apply(OrderQueryBatch("http-batch-${expectedRevision + 1}", expectedRevision, covered, records))
    }

    private fun orderId(sequence: Long): String = TradingIdentifiers.order(lineage, sequence).value

    private fun get(
        orderId: String,
        suffix: String = "",
        market: String = "BTC-USDT",
    ): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(
                    URI.create("$url/api/trading/v3/markets/$market/orders/$orderId$suffix"),
                ).timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(payload: String): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("$url/api/trading/v3/markets/BTC-USDT/orders"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun body(response: HttpResponse<String>): JsonNode = json.readTree(response.body())

    private fun assertResponse(
        status: Int,
        code: String,
        response: HttpResponse<String>,
        hideOrder: Boolean = false,
    ) {
        assertEquals(status, response.statusCode(), response.body())
        val body = body(response)
        assertEquals(code, body.path("code").asString())
        if (hideOrder) {
            assertTrue(body.path("state").isNull)
            assertTrue(body.path("orderId").isNull)
        }
        assertFalse(response.headers().firstValue("Set-Cookie").isPresent)
    }

    private fun databaseRows(): List<String?> =
        listOf("query_progress", "order_record", "applied_batch").flatMap { table ->
            database.jdbc().queryForList(
                "select row_to_json(t)::text from order_query.$table t order by row_to_json(t)::text",
                String::class.java,
            )
        }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    excludeName = [
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
    ],
)
@Import(OrderQueryConfig::class, OrderQueryController::class, BoundedTradingOrderController::class, TradingOrderHttpExceptionHandler::class)
internal class OrderQueryHttpTestConfig {
    @Bean
    fun orderQueryHttpGateway(): BoundedTradingOrderGateway = mock(BoundedTradingOrderGateway::class.java)

    @Bean
    fun orderQueryHttpOrderUseCase(gateway: BoundedTradingOrderGateway): BoundedTradingOrderUseCase = BoundedTradingOrderUseCase(gateway)
}
