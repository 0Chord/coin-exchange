package com.exchange.core.api.trading.infrastructure.persistence

import com.exchange.core.api.trading.application.OrderQueryBatch
import com.exchange.core.api.trading.application.OrderQueryFinancialStatus
import com.exchange.core.api.trading.application.OrderQueryFreshness
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.OrderQueryOrderState
import com.exchange.core.api.trading.application.OrderQueryPendingReason
import com.exchange.core.api.trading.application.OrderQueryRecord
import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import com.exchange.core.api.trading.application.port.OrderQueryWriteConflictException
import com.exchange.core.common.MarketId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.MatchingResultStream
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresOrderQueryStoreTest {
    private val database = PostgreSQLContainer("postgres:16-alpine")
    private val namespaces = AtomicLong()
    private lateinit var dataSource: DriverManagerDataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun startDatabase() {
        database.start()
        dataSource = newDataSource()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    @AfterAll
    fun stopDatabase() {
        database.stop()
    }

    @Test
    fun `접수 미확인과 부분 체결 뒤 취소 반환을 보존하고 재개방해도 종료 주문이 남는다`() {
        val store = store()
        val pending = pending(store)
        store.observe(5)
        assertEquals(1L, store.apply(batch("accepted", 0, 1, pending)))
        assertEquals(OrderQueryOrderState.MATCHING_PENDING, store.read(BTC, pending.orderId).record!!.state)
        assertNull(store.read(BTC, pending.orderId).record!!.remainingQuantity)
        assertEquals(OrderQueryPendingReason.MATCHING, store.read(BTC, pending.orderId).record!!.pendingReason)
        val partial = matched(pending, OrderQueryOrderState.PARTIALLY_FILLED, filled = 1, remaining = 2, result = 1)
        assertEquals(2L, store.apply(batch("partial", 1, 1, partial)))
        val cancelled = matched(partial, OrderQueryOrderState.CANCELLED, filled = 1, cancelled = 2, result = 2)
        assertEquals(3L, store.apply(batch("cancelled", 2, 1, cancelled)))
        assertEquals(OrderQueryPendingReason.RELEASE, store.read(BTC, pending.orderId).record!!.pendingReason)
        assertEquals(4L, store.apply(batch("released", 3, 1, cancelled.copy(financialThrough = 2))))
        store.transitionLifecycle(OrderQueryLifecycle.READY)

        val reopened = newStore(newDataSource(), store.namespace)
        reopened.initialize()
        val saved = reopened.read(BTC, pending.orderId)
        assertEquals(3L, saved.record!!.initialQuantity)
        assertEquals(1L, saved.record!!.filledQuantity)
        assertEquals(0L, saved.record!!.remainingQuantity)
        assertEquals(2L, saved.record!!.cancelledQuantity)
        assertEquals(0L, saved.record!!.notExecutedQuantity)
        assertEquals(OrderQueryFinancialStatus.APPLIED, saved.record!!.financialStatus)
        assertEquals(OrderQueryLifecycle.READY, saved.progress!!.lifecycle)
        assertEquals(4L, saved.progress!!.appliedRevision)
        assertEquals(5L, saved.progress!!.observedRevision)
        val next = pending(reopened, admission = 2, marketAdmission = 2)
        reopened.apply(batch("later-order", 4, 2, next))
        assertEquals(saved.record, reopened.read(BTC, pending.orderId).record)
        assertEquals(1L, reopened.apply(batch("accepted", 0, 1, pending)))
        assertEquals(5L, reopened.read(BTC, next.orderId).progress!!.appliedRevision)
    }

    @Test
    fun `maker taker 두 행과 한 진행 위치를 함께 저장하고 추가 체결로 완전 체결된다`() {
        val store = store()
        val maker = matched(pending(store), OrderQueryOrderState.RESTING, remaining = 3, result = 1)
        store.observe(3)
        store.apply(batch("maker", 0, 1, maker))
        val makerPartial = matched(maker, OrderQueryOrderState.PARTIALLY_FILLED, filled = 1, remaining = 2, result = 2)
        val taker =
            matched(
                pending(store, admission = 2, marketAdmission = 2).copy(initialQuantity = 1, side = Side.SELL),
                OrderQueryOrderState.FILLED,
                filled = 1,
                result = 2,
            )

        assertEquals(2L, store.apply(batch("trade", 1, 2, makerPartial, taker)))
        assertEquals(1L, store.read(BTC, maker.orderId).record!!.filledQuantity)
        assertEquals(2L, store.read(BTC, maker.orderId).record!!.remainingQuantity)
        assertEquals(1L, store.read(BTC, taker.orderId).record!!.filledQuantity)
        assertEquals(0L, store.read(BTC, taker.orderId).record!!.remainingQuantity)
        assertEquals(2L, store.read(BTC, taker.orderId).progress!!.appliedRevision)
        val filled = matched(makerPartial, OrderQueryOrderState.FILLED, filled = 3, result = 3).copy(financialThrough = 3)
        store.apply(batch("filled", 2, 2, filled))
        val reopened = newStore(newDataSource(), store.namespace)
        assertEquals(filled, reopened.read(BTC, maker.orderId).record)
    }

    @Test
    fun `최종 미실행은 취소 수량과 구별하고 반환 완료만 전진시킨다`() {
        val store = store()
        val rejected = matched(pending(store), OrderQueryOrderState.NOT_EXECUTED, notExecuted = 3, result = 1)
        store.observe(2)
        store.apply(batch("not-executed", 0, 1, rejected))
        val saved = store.read(BTC, rejected.orderId).record!!
        assertEquals(0L, saved.filledQuantity)
        assertEquals(0L, saved.cancelledQuantity)
        assertEquals(3L, saved.notExecutedQuantity)
        assertEquals(OrderQueryPendingReason.RELEASE, saved.pendingReason)
        store.apply(batch("returned", 1, 1, rejected.copy(financialThrough = 1)))
        assertEquals(OrderQueryFinancialStatus.APPLIED, store.read(BTC, rejected.orderId).record!!.financialStatus)
    }

    @Test
    fun `정규화 행 순서가 다른 중복은 최초 결과와 시각을 보존하고 내용 변경은 무변경 충돌이다`() {
        val store = store()
        val first = pending(store)
        val second = pending(store, admission = 2, marketAdmission = 2)
        store.observe(2)
        val original = batch("same", 0, 2, first, second)
        assertEquals(1L, store.apply(original))
        val snapshot = store.read(BTC, first.orderId)
        val originalRows = rawState(store)
        assertEquals(1L, store.apply(batch("same", 0, 2, second, first)))
        assertEquals(snapshot, store.read(BTC, first.orderId))
        assertEquals(originalRows, rawState(store))

        assertFailsWith<OrderQueryWriteConflictException> { store.apply(batch("same", 0, 2, first.copy(price = 101), second)) }
        assertEquals(originalRows, rawState(store))
        store.apply(batch("later", 1, 2, first, second))
        assertEquals(1L, store.apply(original))
        assertEquals(2L, store.read(BTC, first.orderId).progress!!.appliedRevision)
    }

    @Test
    fun `같은 expected revision의 두 writer 중 하나만 커밋한다`() {
        val store = store()
        val first = pending(store)
        val second = pending(store, admission = 2, marketAdmission = 2)
        store.observe(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results =
            Executors.newFixedThreadPool(2).use { executor ->
                val futures =
                    listOf(batch("one", 0, 2, first), batch("two", 0, 2, second)).map { input ->
                        executor.submit(
                            Callable {
                                ready.countDown()
                                check(start.await(5, TimeUnit.SECONDS))
                                runCatching { newStore(newDataSource(), store.namespace).apply(input) }
                            },
                        )
                    }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                futures.map { it.get(10, TimeUnit.SECONDS) }
            }

        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is OrderQueryWriteConflictException)
        assertEquals(1L, store.read(BTC, first.orderId).progress!!.appliedRevision)
        assertEquals(1, count(store, "order_record"))
        assertEquals(1, count(store, "applied_batch"))
    }

    @Test
    fun `다른 마켓의 전역 주문 재연결과 마켓 접수 순번 충돌은 batch 전체를 롤백한다`() {
        val store = store()
        val first = pending(store)
        store.observe(3)
        store.apply(batch("first", 0, 1, first))
        val saved = rawState(store)
        val beforeConflict = pending(store, admission = 2, marketAdmission = 2)
        val wrongMarket = first.copy(marketId = SUI)
        assertFailsWith<OrderQueryWriteConflictException> {
            store.apply(batch("global-collision", 1, 2, beforeConflict, wrongMarket))
        }
        assertEquals(saved, rawState(store))
        assertNull(store.read(BTC, beforeConflict.orderId).record)
        assertNull(store.read(SUI, first.orderId).record)
        val localCollision = pending(store, admission = 3, marketAdmission = 1)
        assertFailsWith<OrderQueryWriteConflictException> { store.apply(batch("local-collision", 1, 3, localCollision)) }
        assertEquals(saved, rawState(store))
    }

    @Test
    fun `두 번째 행의 실제 PostgreSQL check 오류는 첫 행 batch와 진행 위치까지 롤백한다`() {
        val store = store()
        val original = pending(store)
        store.observe(2)
        store.apply(batch("original", 0, 1, original))
        val saved = rawState(store)
        jdbc.jdbcTemplate.execute(
            """
            alter table order_query.order_record add constraint test_query_write_failure
            check (record_payload ->> 'userId' <> 'force-sql-failure')
            """.trimIndent(),
        )
        try {
            val changed = matched(original, OrderQueryOrderState.RESTING, remaining = 3, result = 1)
            val failsInSql = pending(store, admission = 2, marketAdmission = 2).copy(userId = "force-sql-failure")
            assertFailsWith<OrderQueryWriteConflictException> {
                store.apply(batch("sql-fails", 1, 2, changed, failsInSql))
            }
            assertEquals(saved, rawState(store))
            assertEquals(original, store.read(BTC, original.orderId).record)
            assertNull(store.read(BTC, failsInSql.orderId).record)
        } finally {
            jdbc.jdbcTemplate.execute("alter table order_query.order_record drop constraint test_query_write_failure")
        }
    }

    @Test
    fun `저장된 JSON 수량 형식 불변 정보와 식별자 손상은 입력 오류가 아닌 조회 모순이다`() {
        listOf(
            "jsonb_set(record_payload, '{filledQuantity}', '1'::jsonb)",
            "jsonb_set(record_payload, '{initialQuantity}', '\"3\"'::jsonb)",
            "jsonb_set(record_payload, '{orderType}', '\"MARKET\"'::jsonb)",
            "record_payload || '{\"financialStatus\":\"APPLIED\"}'::jsonb",
            "record_payload - 'filledQuantity'",
        ).forEach { expression ->
            val store = store()
            val pending = pending(store)
            store.observe(1)
            store.apply(batch("record", 0, 1, pending))
            jdbc.update(
                "update order_query.order_record set record_payload = $expression where namespace = :namespace",
                parameters(store),
            )
            assertFailsWith<OrderQueryInconsistentException> { store.read(BTC, pending.orderId) }
        }
        val store = store()
        val pending = pending(store)
        store.observe(1)
        store.apply(batch("record", 0, 1, pending))
        val malformedId = "O2" + "0".repeat(38)
        jdbc.update(
            "update order_query.order_record set order_id = :malformedId where namespace = :namespace",
            parameters(store) + ("malformedId" to malformedId),
        )
        assertFailsWith<OrderQueryInconsistentException> { store.read(BTC, malformedId) }
    }

    @Test
    fun `조회 연결 실패는 부재가 아닌 unavailable이다`() {
        val unavailable =
            DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/missing?connectTimeout=1", "private-user", "private-password")
        val store = newStore(unavailable, OrderQueryNamespace(ClusterLineage(99, 1), setOf(BTC)))
        val error = assertFailsWith<OrderQueryUnavailableException> { store.read(BTC, "unknown") }
        assertNull(error.cause)
        assertFalse(error.message!!.contains("private"))
    }

    @Test
    fun `초기화는 기존 범위를 덮어쓰지 않고 BLOCKED는 명시적 재준비 없이 풀리지 않는다`() {
        val store = store()
        assertEquals(OrderQueryLifecycle.INITIALIZING, store.read(BTC, "unknown").progress!!.lifecycle)
        store.observe(1)
        store.apply(batch("first", 0, 1, pending(store)))
        store.transitionLifecycle(OrderQueryLifecycle.BLOCKED)
        val saved = rawState(store)
        store.initialize()
        assertEquals(saved, rawState(store))
        assertFailsWith<OrderQueryWriteConflictException> { store.transitionLifecycle(OrderQueryLifecycle.READY) }
        assertFailsWith<OrderQueryWriteConflictException> { store.apply(batch("blocked", 1, 1)) }
        assertEquals(saved, rawState(store))
        val otherMarkets = newStore(newDataSource(), OrderQueryNamespace(store.namespace.fundsLineage, setOf(BTC)))
        assertFailsWith<OrderQueryInconsistentException> { otherMarkets.initialize() }
        store.transitionLifecycle(OrderQueryLifecycle.INITIALIZING)
        store.transitionLifecycle(OrderQueryLifecycle.READY)
        assertEquals(OrderQueryLifecycle.READY, store.read(BTC, "unknown").progress!!.lifecycle)
    }

    @Test
    fun `두 마켓은 전역 prefix를 공유하고 접수 없는 체결 지연은 revision으로 드러난다`() {
        val store = store()
        val btc = pending(store)
        val sui = pending(store, admission = 2, marketAdmission = 1, market = SUI)
        store.observe(1)
        store.apply(batch("markets", 0, 2, btc, sui))
        store.transitionLifecycle(OrderQueryLifecycle.READY)
        assertEquals(2L, store.read(SUI, sui.orderId).progress!!.coveredAdmissionSequence)
        assertEquals(OrderQueryFreshness.CAUGHT_UP, store.read(BTC, btc.orderId).progress!!.freshness)
        store.observe(2)
        assertEquals(OrderQueryFreshness.LAGGING, store.read(BTC, btc.orderId).progress!!.freshness)
        val partial = matched(btc, OrderQueryOrderState.PARTIALLY_FILLED, filled = 1, remaining = 2, result = 1)
        store.apply(batch("execution-only", 1, 2, partial))
        assertEquals(2L, store.read(BTC, btc.orderId).progress!!.coveredAdmissionSequence)
        assertEquals(OrderQueryFreshness.CAUGHT_UP, store.read(BTC, btc.orderId).progress!!.freshness)
        val saved = rawState(store)
        assertFailsWith<OrderQueryWriteConflictException> { store.observe(1) }
        assertEquals(saved, rawState(store))
    }

    @Test
    fun `위치 불변 정보와 종료 상태 역행은 기존 저장을 바꾸지 않는다`() {
        val store = store()
        val initial = matched(pending(store), OrderQueryOrderState.RESTING, remaining = 3, result = 1)
        store.observe(4)
        store.apply(batch("initial", 0, 1, initial))
        val saved = rawState(store)
        listOf(initial.copy(price = 101), initial.copy(marketAdmissionSequence = 2))
            .forEachIndexed { index, invalid ->
                assertFailsWith<OrderQueryWriteConflictException> { store.apply(batch("invalid-$index", 1, 1, invalid)) }
                assertEquals(saved, rawState(store))
            }
        val filled = matched(initial, OrderQueryOrderState.FILLED, filled = 3, result = 2)
        store.apply(batch("filled", 1, 1, filled))
        val terminal = rawState(store)
        assertFailsWith<OrderQueryWriteConflictException> {
            store.apply(
                batch("revive", 2, 1, initial.copy(lastAffectedResultSequence = 3)),
            )
        }
        assertEquals(terminal, rawState(store))
        assertFailsWith<OrderQueryWriteConflictException> { store.apply(batch("prefix-back", 2, 0)) }
        assertEquals(terminal, rawState(store))
    }

    @Test
    fun `반복 읽기와 SQL 모양 식별자는 어떤 조회 행도 갱신하지 않는다`() {
        val store = store()
        val record = pending(store)
        store.observe(1)
        store.apply(batch("read-only", 0, 1, record))
        val before = rawState(store)
        repeat(3) { assertEquals(record, store.read(BTC, record.orderId).record) }
        assertNull(store.read(BTC, "' OR '1'='1").record)
        assertNull(store.read("BTC-USDT'; DROP TABLE order_query.order_record; --", record.orderId).record)
        assertEquals(before, rawState(store))
        assertEquals(1, count(store, "order_record"))
    }

    @Test
    fun `모델의 최대 길이 마켓과 batch 식별자는 실제 DB에서도 저장된다`() {
        val market = "M".repeat(256)
        val store =
            newStore(
                dataSource,
                OrderQueryNamespace(ClusterLineage(60, namespaces.incrementAndGet()), setOf(market)),
            )
        store.initialize()
        store.observe(1)
        val record = pending(store, market = market).copy(userId = "U".repeat(256))
        assertEquals(1L, store.apply(batch("B".repeat(256), 0, 1, record)))
        assertEquals(record, store.read(market, record.orderId).record)
        assertFailsWith<IllegalArgumentException> { batch("B".repeat(257), 1, 1, record) }
        assertEquals(1L, store.read(market, record.orderId).progress!!.appliedRevision)
    }

    private fun store(): PostgresOrderQueryStore =
        newStore(dataSource, OrderQueryNamespace(ClusterLineage(60, namespaces.incrementAndGet()), setOf(BTC, SUI)))
            .also { it.initialize() }

    private fun newStore(
        source: DriverManagerDataSource,
        namespace: OrderQueryNamespace,
    ): PostgresOrderQueryStore =
        PostgresOrderQueryStore(namespace, NamedParameterJdbcTemplate(source), DataSourceTransactionManager(source))

    private fun newDataSource(): DriverManagerDataSource =
        DriverManagerDataSource(
            database.jdbcUrl + (if ('?' in database.jdbcUrl) "&" else "?") + "connectTimeout=5&socketTimeout=5",
            database.username,
            database.password,
        )

    private fun pending(
        store: PostgresOrderQueryStore,
        admission: Long = 1,
        marketAdmission: Long = 1,
        market: String = BTC,
    ): OrderQueryRecord =
        OrderQueryRecord(
            store.namespace.fundsLineage,
            market,
            "user-$admission",
            Side.BUY,
            OrderType.LIMIT,
            TimeInForce.GTC,
            100,
            3,
            admission,
            marketAdmission,
            0,
            null,
            0,
            0,
            OrderQueryOrderState.MATCHING_PENDING,
        )

    private fun matched(
        previous: OrderQueryRecord,
        state: OrderQueryOrderState,
        filled: Long = 0,
        remaining: Long = 0,
        cancelled: Long = 0,
        notExecuted: Long = 0,
        result: Long,
    ): OrderQueryRecord =
        previous.copy(
            filledQuantity = filled,
            remainingQuantity = remaining,
            cancelledQuantity = cancelled,
            notExecutedQuantity = notExecuted,
            state = state,
            resultStream = MatchingResultStream(ClusterLineage(61, 1), previous.fundsLineage, MarketId(previous.marketId), 1, 1),
            lastAffectedResultSequence = result,
            financialThrough = previous.financialThrough ?: 0,
        )

    private fun batch(
        id: String,
        expected: Long,
        covered: Long,
        vararg records: OrderQueryRecord,
    ): OrderQueryBatch = OrderQueryBatch(id, expected, covered, records.toList())

    private fun parameters(store: PostgresOrderQueryStore): Map<String, String> =
        mapOf("namespace" to "${store.namespace.fundsLineage.high}:${store.namespace.fundsLineage.low}")

    private fun count(
        store: PostgresOrderQueryStore,
        table: String,
    ): Int {
        require(table in setOf("order_record", "applied_batch"))
        return jdbc.queryForObject(
            "select count(*) from order_query.$table where namespace = :namespace",
            parameters(store),
            Int::class.java,
        )!!
    }

    private fun rawState(store: PostgresOrderQueryStore): List<Map<String, Any?>> =
        listOf("query_progress", "order_record", "applied_batch").flatMap { table ->
            jdbc.queryForList(
                "select row_to_json(t)::text as state from order_query.$table t where namespace = :namespace",
                parameters(store),
            )
        }

    private companion object {
        const val BTC = "BTC-USDT"
        const val SUI = "SUI-USDT"
    }
}
