package com.exchange.core.api.trading.api

import com.exchange.core.api.common.TradingOrderHttpExceptionHandler
import com.exchange.core.api.config.OrderQueryConfig
import com.exchange.core.api.trading.application.BoundedTradingOrderGateway
import com.exchange.core.api.trading.application.BoundedTradingOrderUseCase
import com.exchange.core.api.trading.application.CandleInterval
import com.exchange.core.api.trading.application.OrderQueryBatch
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.OrderQueryOrderState
import com.exchange.core.api.trading.application.OrderQueryRecord
import com.exchange.core.api.trading.application.port.OrderQueryStore
import com.exchange.core.api.trading.infrastructure.persistence.PostgresCandleProjection
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            truncate order_query.candle, order_query.candle_progress, order_query.balance_projection, order_query.balance_progress,
                order_query.settlement_posting, order_query.settlement_transaction, order_query.source_receipt,
                order_query.funds_ledger_batch, order_query.feed_stream, order_query.feed_consumer, order_query.applied_batch,
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
            truncate order_query.balance_projection, order_query.balance_progress,
                order_query.settlement_posting, order_query.settlement_transaction, order_query.source_receipt,
                order_query.funds_ledger_batch, order_query.feed_stream, order_query.feed_consumer, order_query.applied_batch,
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
                latestTrade(market = "UNKNOWN"),
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
    fun `최근 체결가는 체결 없음과 조회 준비 및 DB 오류를 구분한다`() {
        store.transitionLifecycle(OrderQueryLifecycle.READY)
        assertResponse(503, "QUERY_NOT_READY", latestTrade())
        prepareSettlementStreams()

        val empty = latestTrade()
        assertResponse(200, "NO_SETTLED_TRADE_AS_OF", empty)
        val emptyBody = body(empty)
        assertTrue(emptyBody.path("trade").isNull)
        assertEquals(json.readTree("0"), emptyBody.path("asOfSettlementResultSequence"))

        val queryStore = store as PostgresOrderQueryStore
        queryStore.suspendSourceReads()
        assertResponse(503, "QUERY_NOT_READY", latestTrade())
        queryStore.verifySourceReads()

        database.jdbc().execute("alter table order_query.settlement_transaction rename to unavailable_settlement_transaction")
        try {
            val unavailable = latestTrade()
            assertResponse(503, "QUERY_UNAVAILABLE", unavailable)
            assertTrue(body(unavailable).path("trade").isNull)
            assertTrue(body(unavailable).path("asOfSettlementResultSequence").isNull)
        } finally {
            database.jdbc().execute("alter table order_query.unavailable_settlement_transaction rename to settlement_transaction")
        }

        store.transitionLifecycle(OrderQueryLifecycle.BLOCKED)
        assertResponse(503, "QUERY_INCONSISTENT", latestTrade())
        verifyNoInteractions(context.getBean(BoundedTradingOrderGateway::class.java))
    }

    @Test
    fun `최근 체결가는 같은 마켓과 계보의 정산 범위에서 가장 높은 엔진 순번을 읽는다`() {
        val jdbc = database.jdbc()
        prepareSettlementStreams()
        jdbc.execute(
            """
            insert into order_query.query_progress
                (namespace, supported_markets, lifecycle, applied_revision, observed_revision, covered_admission_sequence)
            select '51:52', supported_markets, lifecycle, applied_revision, observed_revision, covered_admission_sequence
            from order_query.query_progress where namespace = '41:42';
            insert into order_query.feed_consumer (namespace, consumer_id, configuration_payload)
            values ('51:52', 'other', '{"settlementEnabled":true}');
            insert into order_query.feed_stream (namespace, market_id, settlement_through)
            values ('51:52', 'BTC-USDT', 10);
            update order_query.feed_stream set settlement_through = 2 where namespace = '41:42';
            insert into order_query.settlement_transaction
                (namespace, market_id, result_sequence, settlement_id, source_event_id, engine_sequence,
                 maker_order_id, taker_order_id, maker_user_id, taker_user_id, taker_side,
                 base_asset_id, quote_asset_id, price, quantity, maker_fee, taker_fee, occurred_at)
            select namespace, market_id, result_sequence, 'fill-' || engine_sequence, 'source-' || engine_sequence,
                engine_sequence, 'maker-order', 'taker-order', 'maker', 'taker', 'BUY',
                'BASE', 'USDT', price, quantity, 0, 0, timestamptz '2026-11-24 10:00:02+00'
            from (values
                ('41:42', 'BTC-USDT', 2, 6, 105, 2),
                ('41:42', 'BTC-USDT', 2, 5, 100, 1),
                ('41:42', 'BTC-USDT', 4, 12, 999, 1),
                ('41:42', 'ETH-USDT', 2, 9, 222, 1),
                ('51:52', 'BTC-USDT', 10, 13, 333, 1)
            ) as fills(namespace, market_id, result_sequence, engine_sequence, price, quantity)
            """.trimIndent(),
        )

        fun assertLatestTrade(through: Long) {
            val response = latestTrade()
            assertEquals(200, response.statusCode(), response.body())
            val expected =
                json.readTree(
                    """
                    {"code":"LATEST_SETTLED_TRADE","marketId":"BTC-USDT","asOfSettlementResultSequence":$through,
                     "trade":{"price":105,"quantity":2,"settledAt":"2026-11-24T10:00:02Z"}}
                    """.trimIndent(),
                )
            assertEquals(expected, body(response))
        }
        assertLatestTrade(2)
        jdbc.execute(
            "update order_query.feed_stream set settlement_through = 3 where namespace = '41:42' and market_id = 'BTC-USDT'",
        )
        repeat(2) { assertLatestTrade(3) }
        assertEquals(222L, body(latestTrade(market = "ETH-USDT")).path("trade").path("price").asLong())
        assertEquals(
            listOf(5L, 6L, 9L, 12L, 13L),
            jdbc.queryForList("select engine_sequence from order_query.settlement_transaction order by engine_sequence", Long::class.java),
        )
        assertEquals(
            listOf(3L, 2L, 10L),
            jdbc.queryForList("select settlement_through from order_query.feed_stream order by namespace, market_id", Long::class.java),
        )
        verifyNoInteractions(context.getBean(BoundedTradingOrderGateway::class.java))
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
                assertEquals(400, latestTrade(market = "UNKNOWN").statusCode())
                assertEquals(400, candles(market = "UNKNOWN").statusCode())
            } finally {
                heldConnections.forEach { it.close() }
            }

            pool.close()
            assertResponse(503, "QUERY_UNAVAILABLE", get(order), hideOrder = true)
            assertResponse(503, "QUERY_UNAVAILABLE", get(order, "/fills"))
            assertResponse(503, "QUERY_UNAVAILABLE", latestTrade())
            assertResponse(503, "QUERY_UNAVAILABLE", candles())
        } finally {
            context.close()
            openContext()
        }
    }

    @Test
    fun `차트는 준비 중과 빈 기간을 구분하고 집계 테이블만 조회한다`() {
        assertResponse(503, "QUERY_NOT_READY", candles())
        for (query in listOf("?interval=1s", "?interval=", "?count=0", "?count=201", "?count=abc")) {
            assertEquals(400, candles(query).statusCode(), query)
        }
        prepareSettlementStreams()
        assertResponse(202, "QUERY_CATCHING_UP", candles())

        projection().applyNextPage("BTC-USDT")
        val empty = candles()
        assertResponse(200, "CANDLES_FOUND", empty)
        assertEquals("1m", body(empty).path("interval").asString())
        assertEquals(json.readTree("[]"), body(empty).path("candles"))

        val jdbc = database.jdbc()
        jdbc.execute("alter table order_query.settlement_transaction rename to unavailable_settlement_transaction")
        try {
            assertEquals(body(empty), body(candles()))
        } finally {
            jdbc.execute("alter table order_query.unavailable_settlement_transaction rename to settlement_transaction")
        }
        jdbc.execute("alter table order_query.candle rename to unavailable_candle")
        try {
            assertResponse(503, "QUERY_UNAVAILABLE", candles())
        } finally {
            jdbc.execute("alter table order_query.unavailable_candle rename to candle")
        }
        jdbc.execute("update order_query.query_progress set supported_markets = '[\"BTC-USDT\"]' where namespace = '41:42'")
        try {
            assertResponse(503, "QUERY_INCONSISTENT", latestTrade())
            assertResponse(503, "QUERY_INCONSISTENT", candles())
        } finally {
            jdbc.execute(
                "update order_query.query_progress set supported_markets = '[\"BTC-USDT\",\"ETH-USDT\"]' where namespace = '41:42'",
            )
        }
        store.transitionLifecycle(OrderQueryLifecycle.BLOCKED)
        assertResponse(503, "QUERY_INCONSISTENT", candles())
        verifyNoInteractions(context.getBean(BoundedTradingOrderGateway::class.java))
    }

    @Test
    fun `배치가 결과를 가르지 않고 빈 정산 결과까지 처리 순번이 전진한다`() {
        prepareSettlementStreams()
        val settledAt = Instant.now().minusSeconds(90)

        fun insertFills(
            first: Int,
            last: Int,
        ) {
            database.jdbc().update(
                """
                insert into order_query.settlement_transaction
                    (namespace, market_id, result_sequence, settlement_id, source_event_id, engine_sequence,
                     maker_order_id, taker_order_id, maker_user_id, taker_user_id, taker_side,
                     base_asset_id, quote_asset_id, price, quantity, maker_fee, taker_fee, occurred_at)
                select '41:42', 'BTC-USDT',
                    case when n <= 510 then n when n <= 520 then 511 when n = 521 then 512 else 515 end,
                    'fill-' || n, 'source-' || n, n, 'maker-order', 'taker-order', 'maker', 'taker', 'BUY',
                    'BASE', 'USDT', 100, 1, 0, 0, ?
                from generate_series(?, ?) as n
                """.trimIndent(),
                settledAt.atOffset(ZoneOffset.UTC),
                first,
                last,
            )
        }

        insertFills(1, 521)
        advanceSettlementThrough(514)

        assertTrue(projection().applyNextPage("BTC-USDT"))
        val catchingUp = candles()
        assertResponse(202, "QUERY_CATCHING_UP", catchingUp)
        assertEquals(511L, body(catchingUp).path("asOfSettlementResultSequence").longValue())
        assertEquals(514L, body(catchingUp).path("sourceSettlementResultSequence").longValue())
        assertEquals(
            List(8) { "520" },
            database.jdbc().queryForList("select volume::text from order_query.candle", String::class.java),
        )

        insertFills(522, 1033)
        advanceSettlementThrough(516)
        assertTrue(projection().applyNextPage("BTC-USDT"))
        val completed = candles()
        assertResponse(200, "CANDLES_FOUND", completed)
        assertEquals(515L, body(completed).path("asOfSettlementResultSequence").longValue())
        assertEquals(516L, body(completed).path("sourceSettlementResultSequence").longValue())
        val completedCandle = body(completed).path("candles").single()
        assertEquals("1033", completedCandle.path("volume").asString())
        assertEquals(json.readTree("[]"), body(candles("?count=1")).path("candles"))
        assertTrue(projection().applyNextPage("BTC-USDT"))
        val afterCancellation = body(candles())
        assertEquals(516L, afterCancellation.path("asOfSettlementResultSequence").longValue())
        assertEquals(body(completed).path("candles"), afterCancellation.path("candles"))
        assertFalse(projection().applyNextPage("BTC-USDT"))
        advanceSettlementThrough(515)
        assertResponse(503, "QUERY_INCONSISTENT", candles())
        assertFalse(projection().applyNextPage("BTC-USDT"))
    }

    @Test
    fun `두 분의 새 체결을 상위 일곱 주기에 한 번씩 합산한다`() {
        prepareSettlementStreams()
        val minute = Instant.ofEpochSecond(Instant.now().epochSecond / 900 * 900 - 900)
        insertSettlement(result = 1, engine = 1, price = 100, quantity = 1, at = minute)
        insertSettlement(result = 2, engine = 2, price = 105, quantity = 2, at = minute.plusSeconds(10))
        insertSettlement(result = 3, engine = 3, price = 90, quantity = 4, at = minute.plusSeconds(60))
        advanceSettlementThrough(3)
        assertTrue(projection().applyNextPage("BTC-USDT"))

        val minuteResponse = candles("?interval=1m")
        assertEquals(200, minuteResponse.statusCode())
        val minutes = body(minuteResponse).path("candles")
        assertEquals(2, minutes.size())
        assertEquals(minute.toString(), minutes[0].path("startedAt").asString())
        assertEquals("3", minutes[0].path("volume").asString())
        assertEquals(minute.plusSeconds(60).toString(), minutes[1].path("startedAt").asString())
        assertEquals("4", minutes[1].path("volume").asString())
        for (interval in listOf("5m", "15m", "1h", "1d", "1w", "1mo", "1y")) {
            val response = candles("?interval=$interval")
            assertEquals(200, response.statusCode(), interval)
            val candle = body(response).path("candles").single()
            val fields = listOf("open", "high", "low", "close", "volume")
            assertEquals(listOf("100", "105", "90", "90", "7"), fields.map { candle.path(it).asString() }, interval)
        }
    }

    @Test
    fun `봉과 순번은 함께 롤백하고 커밋 응답 유실 뒤 WAS를 재시작해도 수량이 중복되지 않는다`() {
        prepareSettlementStreams()
        val minute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(120)
        val sameTime = minute.plusSeconds(10)
        insertSettlement(result = 1, engine = 2, price = 105, quantity = 2, at = sameTime)
        insertSettlement(result = 1, engine = 1, price = 100, quantity = 1, at = sameTime)
        advanceSettlementThrough(2)
        assertTrue(projection().applyNextPage("BTC-USDT"))
        assertCandleValues(projected = 2, source = 2, open = 100, low = 100, volume = "3")

        insertSettlement(result = 3, engine = 3, price = 90, quantity = Long.MAX_VALUE, at = minute.plusSeconds(5))
        advanceSettlementThrough(3)
        assertCandleValues(projected = 2, source = 3, open = 100, low = 100, volume = "3")
        val jdbc = database.jdbc()
        jdbc.execute(
            """
            create function order_query.reject_candle_progress() returns trigger language plpgsql as
            'begin raise exception ''chart checkpoint rejected''; end';
            create trigger reject_candle_progress before update on order_query.candle_progress
                for each row when (new.projected_through = 3) execute function order_query.reject_candle_progress()
            """.trimIndent(),
        )
        try {
            assertFailsWith<RuntimeException> { projection().applyNextPage("BTC-USDT") }
        } finally {
            jdbc.execute("drop trigger reject_candle_progress on order_query.candle_progress")
            jdbc.execute("drop function order_query.reject_candle_progress()")
        }
        assertCandleValues(projected = 2, source = 3, open = 100, low = 100, volume = "3")

        val dataSource = context.getBean(DataSource::class.java)
        val commitWithLostReply =
            object : DataSourceTransactionManager(dataSource) {
                override fun doCommit(status: DefaultTransactionStatus) {
                    super.doCommit(status)
                    throw TransactionSystemException("PostgreSQL 커밋 성공 뒤 응답 유실을 가정한다")
                }
            }
        val queryStore = store as PostgresOrderQueryStore
        val chartWriter = PostgresCandleProjection(queryStore, NamedParameterJdbcTemplate(dataSource))
        assertFailsWith<TransactionSystemException> {
            TransactionTemplate(commitWithLostReply).executeWithoutResult {
                check(chartWriter.applyNextPage("BTC-USDT"))
            }
        }

        context.close()
        openContext(sourceVerified = false)
        assertResponse(503, "QUERY_NOT_READY", candles())
        (store as PostgresOrderQueryStore).verifySourceReads()
        assertFalse(projection().applyNextPage("BTC-USDT"))
        assertCandleValues(projected = 3, source = 3, open = 90, low = 90, volume = "9223372036854775810")

        insertSettlement(result = 4, engine = 4, price = 102, quantity = 7, at = sameTime.minusSeconds(1))
        insertSettlement(result = 1, engine = 1, price = 999, quantity = 999, at = sameTime, marketId = "ETH-USDT")
        advanceSettlementThrough(4)
        advanceSettlementThrough(1, marketId = "ETH-USDT")
        val start = CountDownLatch(1)
        val worker = projection()
        Executors.newFixedThreadPool(2).use { executors ->
            val writers =
                List(2) {
                    executors.submit(
                        Callable {
                            check(start.await(5, TimeUnit.SECONDS))
                            worker.applyNextPage("BTC-USDT")
                        },
                    )
                }
            start.countDown()
            assertEquals(1, writers.count { it.get(10, TimeUnit.SECONDS) })
        }
        assertCandleValues(projected = 4, source = 4, open = 90, low = 90, volume = "9223372036854775817")
        assertTrue(projection().applyNextPage("ETH-USDT"))
        val etherCandle = body(candles(market = "ETH-USDT")).path("candles").single()
        assertEquals("999", etherCandle.path("volume").asString())

        val reopenedDataSource = context.getBean(DataSource::class.java)
        val otherJdbc = NamedParameterJdbcTemplate(reopenedDataSource)
        val otherTransactions = DataSourceTransactionManager(reopenedDataSource)
        val otherNamespace = OrderQueryNamespace(ClusterLineage(51, 52), setOf("BTC-USDT"))
        val otherSource = PostgresOrderQueryStore(otherNamespace, otherJdbc, otherTransactions)
        otherSource.initialize()
        otherSource.transitionLifecycle(OrderQueryLifecycle.READY)
        jdbc.execute(
            """
            insert into order_query.feed_consumer (namespace, consumer_id, configuration_payload)
            values ('51:52', 'other', '{"settlementEnabled":true}');
            insert into order_query.feed_stream (namespace, market_id)
            values ('51:52', 'BTC-USDT');
            """.trimIndent(),
        )
        insertSettlement(result = 1, engine = 1, price = 500, quantity = 99, at = sameTime, namespace = "51:52")
        advanceSettlementThrough(1, namespace = "51:52")
        val otherProjection = PostgresCandleProjection(otherSource, otherJdbc)
        TransactionTemplate(otherTransactions).executeWithoutResult {
            assertTrue(otherProjection.applyNextPage("BTC-USDT"))
        }
        assertCandleValues(projected = 4, source = 4, open = 90, low = 90, volume = "9223372036854775817")
    }

    @Test
    fun `모든 봉은 UTC 경계를 따르고 주 월 연 경계와 윤년을 올바르게 나눈다`() {
        val boundaries =
            listOf(
                Triple(CandleInterval.ONE_MINUTE, "2024-02-29T23:59:00Z", "2024-03-01T00:00:00Z"),
                Triple(CandleInterval.FIVE_MINUTES, "2024-04-01T00:00:00Z", "2024-04-01T00:05:00Z"),
                Triple(CandleInterval.FIFTEEN_MINUTES, "2024-04-01T00:00:00Z", "2024-04-01T00:15:00Z"),
                Triple(CandleInterval.ONE_HOUR, "2024-04-01T00:00:00Z", "2024-04-01T01:00:00Z"),
                Triple(CandleInterval.ONE_DAY, "2024-02-29T00:00:00Z", "2024-03-01T00:00:00Z"),
                Triple(CandleInterval.ONE_WEEK, "2024-12-23T00:00:00Z", "2024-12-30T00:00:00Z"),
                Triple(CandleInterval.ONE_MONTH, "2024-02-01T00:00:00Z", "2024-03-01T00:00:00Z"),
                Triple(CandleInterval.ONE_YEAR, "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z"),
            )
        for ((interval, previous, boundary) in boundaries) {
            val from = Instant.parse(previous)
            val next = Instant.parse(boundary)
            assertEquals(from, interval.start(next.minusNanos(1_000)))
            assertEquals(next, interval.start(next))
            assertEquals(next, interval.shift(from, 1))
            assertEquals(from, interval.shift(next, -1))
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

    private fun prepareSettlementStreams() {
        database.jdbc().execute(
            """
            insert into order_query.feed_consumer (namespace, consumer_id, configuration_payload)
            values ('41:42', 'http-test', '{"settlementEnabled":true}');
            insert into order_query.feed_stream (namespace, market_id)
            values ('41:42', 'BTC-USDT'), ('41:42', 'ETH-USDT');
            """.trimIndent(),
        )
        store.transitionLifecycle(OrderQueryLifecycle.READY)
    }

    private fun get(
        orderId: String,
        suffix: String = "",
        market: String = "BTC-USDT",
    ): HttpResponse<String> = getPath("/markets/$market/orders/$orderId$suffix")

    private fun latestTrade(market: String = "BTC-USDT"): HttpResponse<String> = getPath("/markets/$market/trades/latest")

    private fun candles(
        query: String = "",
        market: String = "BTC-USDT",
    ): HttpResponse<String> = getPath("/markets/$market/candles$query")

    private fun projection() = context.getBean(PostgresCandleProjection::class.java)

    private fun insertSettlement(
        result: Long,
        engine: Long,
        price: Long,
        quantity: Long,
        at: Instant,
        marketId: String = "BTC-USDT",
        namespace: String = "41:42",
    ) {
        database.jdbc().update(
            """
            insert into order_query.settlement_transaction
                (namespace, market_id, result_sequence, settlement_id, source_event_id, engine_sequence,
                 maker_order_id, taker_order_id, maker_user_id, taker_user_id, taker_side,
                 base_asset_id, quote_asset_id, price, quantity, maker_fee, taker_fee, occurred_at)
            values (?, ?, ?, ?, ?, ?, 'maker-order', 'taker-order', 'maker', 'taker', 'BUY',
                'BASE', 'USDT', ?, ?, 0, 0, ?)
            """.trimIndent(),
            namespace,
            marketId,
            result,
            "fill-$marketId-$engine",
            "source-$marketId-$engine",
            engine,
            price,
            quantity,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun advanceSettlementThrough(
        sequence: Long,
        marketId: String = "BTC-USDT",
        namespace: String = "41:42",
    ) {
        assertEquals(
            1,
            database.jdbc().update(
                "update order_query.feed_stream set settlement_through = ? where namespace = ? and market_id = ?",
                sequence,
                namespace,
                marketId,
            ),
        )
    }

    private fun assertCandleValues(
        projected: Long,
        source: Long,
        open: Long,
        low: Long,
        volume: String,
    ) {
        for (interval in listOf("1m", "5m", "15m", "1h", "1d", "1w", "1mo", "1y")) {
            val response = candles("?interval=$interval")
            assertResponse(200, "CANDLES_FOUND", response)
            val body = body(response)
            assertEquals(interval, body.path("interval").asString())
            assertEquals(projected, body.path("asOfSettlementResultSequence").longValue())
            assertEquals(source, body.path("sourceSettlementResultSequence").longValue())
            val candle = body.path("candles").single()
            Instant.parse(candle.path("startedAt").asString())
            assertEquals(open, candle.path("open").longValue())
            assertEquals(105L, candle.path("high").longValue())
            assertEquals(low, candle.path("low").longValue())
            assertEquals(105L, candle.path("close").longValue())
            assertTrue(candle.path("volume").isString)
            assertEquals(volume, candle.path("volume").asString())
        }
    }

    private fun getPath(path: String): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("$url/api/trading/v3$path"))
                .timeout(Duration.ofSeconds(5))
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
