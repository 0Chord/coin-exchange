package com.exchange.core.api.trading.application

import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryStore
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingIdentifiers
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class OrderQueryUseCaseTest {
    @Test
    fun `준비된 행은 관찰 기준의 최신성과 수량을 반환하고 조회로 갱신하지 않는다`() {
        val store = StubStore(OrderQuerySnapshot(progress(), record()))
        val useCase = OrderQueryUseCase(store)
        val found = useCase.get(MARKET, ORDER_ID, 1)
        assertEquals(OrderQueryCode.ORDER_FOUND, found.code)
        assertSame(store.snapshot.record, found.record)
        assertEquals(OrderQueryFreshness.CAUGHT_UP, found.freshness)
        store.snapshot = OrderQuerySnapshot(progress(observed = 2), record())
        val lagging = useCase.get(MARKET, ORDER_ID)
        assertEquals(OrderQueryCode.ORDER_FOUND, lagging.code)
        assertEquals(OrderQueryFreshness.LAGGING, lagging.freshness)
        assertEquals(2, store.reads)
        assertEquals(0, store.writes)
    }

    @Test
    fun `최소 접수 prefix가 미반영이면 행이 있어도 상태를 확정하지 않는다`() {
        val store = StubStore(OrderQuerySnapshot(progress(), record()))
        val result = OrderQueryUseCase(store).get(MARKET, ORDER_ID, 2)
        assertEquals(OrderQueryCode.QUERY_CATCHING_UP, result.code)
        assertNull(result.record)
        assertEquals(1L, result.progress?.coveredAdmissionSequence)
    }

    @Test
    fun `행이 없어도 지연 중이면 확인 중이며 관찰 기준까지 반영했을 때만 기준부 부재다`() {
        val store = StubStore(OrderQuerySnapshot(progress(observed = 2), null))
        val useCase = OrderQueryUseCase(store)
        assertEquals(OrderQueryCode.QUERY_CATCHING_UP, useCase.get(MARKET, ORDER_ID).code)
        store.snapshot = OrderQuerySnapshot(progress(), null)
        val absent = useCase.get(MARKET, ORDER_ID)
        assertEquals(OrderQueryCode.ORDER_NOT_FOUND_AS_OF, absent.code)
        assertEquals(1L, absent.progress?.appliedRevision)
        assertNull(absent.record)
        val differentLineageId = TradingIdentifiers.order(ClusterLineage(9, 9), 1).value
        assertEquals(OrderQueryCode.ORDER_NOT_FOUND_AS_OF, useCase.get(MARKET, differentLineageId).code)
    }

    @Test
    fun `준비 전이나 차단 상태는 남은 행과 최소 prefix보다 먼저 평가한다`() {
        val store = StubStore(OrderQuerySnapshot(null, null))
        val useCase = OrderQueryUseCase(store)
        assertEquals(OrderQueryCode.QUERY_NOT_READY, useCase.get(MARKET, ORDER_ID).code)
        listOf(OrderQueryLifecycle.INITIALIZING, OrderQueryLifecycle.BLOCKED).forEach { lifecycle ->
            store.snapshot = OrderQuerySnapshot(progress().copy(lifecycle = lifecycle), record())
            val result = useCase.get(MARKET, ORDER_ID, 9)
            assertEquals(OrderQueryCode.QUERY_NOT_READY, result.code)
            assertNull(result.record)
        }
    }

    @Test
    fun `DB 실패와 행 및 진행 정보 모순을 부재나 입력 오류로 바꾸지 않는다`() {
        val store = StubStore(OrderQuerySnapshot(progress(), null))
        val useCase = OrderQueryUseCase(store)
        store.failure = OrderQueryUnavailableException("read failed")
        assertEquals(OrderQueryCode.QUERY_UNAVAILABLE, useCase.get(MARKET, ORDER_ID).code)
        store.failure = OrderQueryInconsistentException("damaged metadata")
        assertEquals(OrderQueryCode.QUERY_INCONSISTENT, useCase.get(MARKET, ORDER_ID).code)
        store.failure = IllegalArgumentException("damaged row conversion")
        assertEquals(OrderQueryCode.QUERY_INCONSISTENT, useCase.get(MARKET, ORDER_ID).code)
        store.failure = null
        store.snapshot = OrderQuerySnapshot(progress(covered = 0), record())
        assertEquals(OrderQueryCode.QUERY_INCONSISTENT, useCase.get(MARKET, ORDER_ID).code)
        store.snapshot =
            OrderQuerySnapshot(progress().copy(lifecycle = OrderQueryLifecycle.INITIALIZING), record().copy(marketId = "ETH-KRW"))
        assertEquals(OrderQueryCode.QUERY_INCONSISTENT, useCase.get(MARKET, ORDER_ID).code)
        store.snapshot = OrderQuerySnapshot(progress(), record().copy(admissionSequence = 2))
        assertEquals(OrderQueryCode.QUERY_INCONSISTENT, useCase.get(MARKET, ORDER_ID).code)
    }

    @Test
    fun `행이 존재하는데 namespace 진행 정보가 없으면 모순이며 빈 초기 DB와 구분한다`() {
        val store = StubStore(OrderQuerySnapshot(null, record()))
        val useCase = OrderQueryUseCase(store)
        val inconsistent = useCase.get(MARKET, ORDER_ID)
        assertEquals(OrderQueryCode.QUERY_INCONSISTENT, inconsistent.code)
        assertNull(inconsistent.record)
        store.snapshot = OrderQuerySnapshot(null, null)
        assertEquals(OrderQueryCode.QUERY_NOT_READY, useCase.get(MARKET, ORDER_ID).code)
    }

    @Test
    fun `입력과 지원 마켓 오류는 DB 읽기보다 먼저 거절한다`() {
        val store = StubStore(OrderQuerySnapshot(null, null)).apply { failure = OrderQueryUnavailableException("offline") }
        val useCase = OrderQueryUseCase(store)
        assertFailsWith<IllegalArgumentException> { useCase.get("ETH-KRW", ORDER_ID) }
        assertFailsWith<IllegalArgumentException> { useCase.get(" ", ORDER_ID) }
        assertFailsWith<IllegalArgumentException> { useCase.get(MARKET, " ") }
        assertFailsWith<IllegalArgumentException> { useCase.get(MARKET, "O2'; SELECT * FROM orders") }
        assertFailsWith<IllegalArgumentException> { useCase.get(MARKET, ORDER_ID, -1) }
        assertEquals(0, store.reads)
        assertEquals(0, store.writes)
    }

    private class StubStore(
        var snapshot: OrderQuerySnapshot,
    ) : OrderQueryStore {
        override val namespace = OrderQueryNamespace(FUNDS, setOf(MARKET))
        var failure: RuntimeException? = null
        var reads = 0
        var writes = 0

        override fun initialize() = unexpectedWrite()

        override fun observe(observedRevision: Long) = unexpectedWrite()

        override fun transitionLifecycle(lifecycle: OrderQueryLifecycle) = unexpectedWrite()

        override fun apply(batch: OrderQueryBatch): Long {
            unexpectedWrite()
            return 0
        }

        override fun read(
            marketId: String,
            orderId: String,
        ): OrderQuerySnapshot {
            reads++
            failure?.let { throw it }
            return snapshot
        }

        private fun unexpectedWrite() {
            writes++
            error("queries must not write")
        }
    }

    private fun progress(
        observed: Long = 1,
        covered: Long = 1,
    ): OrderQueryProgress = OrderQueryProgress(OrderQueryLifecycle.READY, 1, observed, covered, Instant.EPOCH)

    private fun record(): OrderQueryRecord =
        OrderQueryRecord(
            FUNDS,
            MARKET,
            "buyer",
            Side.BUY,
            OrderType.LIMIT,
            TimeInForce.GTC,
            100,
            3,
            1,
            1,
            0,
            null,
            0,
            0,
            OrderQueryOrderState.MATCHING_PENDING,
        )

    private companion object {
        const val MARKET = "BTC-KRW"
        val FUNDS = ClusterLineage(1, 2)
        val ORDER_ID = TradingIdentifiers.order(FUNDS, 1).value
    }
}
