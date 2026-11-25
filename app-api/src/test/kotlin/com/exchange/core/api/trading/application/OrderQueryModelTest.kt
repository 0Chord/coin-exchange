package com.exchange.core.api.trading.application

import com.exchange.core.common.MarketId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.TradingIdentifiers
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OrderQueryModelTest {
    @Test
    fun `접수 후 대기 수량은 미확인이며 매칭과 금융 처리를 구분한다`() {
        val pending = pending()
        assertEquals(TradingIdentifiers.order(FUNDS, 1).value, pending.orderId)
        assertEquals(OrderQueryFinancialStatus.PENDING, pending.financialStatus)
        assertEquals(OrderQueryPendingReason.MATCHING, pending.pendingReason)
        assertNull(pending.remainingQuantity)

        val resting = confirmed(OrderQueryOrderState.RESTING, 0, 3)
        assertEquals(OrderQueryPendingReason.RESULT_PROCESSING, resting.pendingReason)
        val partial = confirmed(OrderQueryOrderState.PARTIALLY_FILLED, 1, 2)
        assertEquals(OrderQueryPendingReason.SETTLEMENT, partial.pendingReason)
        val filled = confirmed(OrderQueryOrderState.FILLED, 3, 0)
        assertEquals(OrderQueryPendingReason.SETTLEMENT, filled.pendingReason)
        val cancelled = confirmed(OrderQueryOrderState.CANCELLED, 1, 0, cancelled = 2)
        assertEquals(listOf(3L, 1L, 0L, 2L, 0L), cancelled.quantities())
        assertEquals(OrderQueryPendingReason.RELEASE, cancelled.pendingReason)
        val notExecuted = confirmed(OrderQueryOrderState.NOT_EXECUTED, 0, 0, notExecuted = 3)
        assertEquals(listOf(3L, 0L, 0L, 0L, 3L), notExecuted.quantities())
        assertEquals(OrderQueryPendingReason.RELEASE, notExecuted.pendingReason)

        listOf(resting, partial, filled, cancelled, notExecuted).forEach { record ->
            val applied = record.copy(financialThrough = 7)
            assertEquals(OrderQueryFinancialStatus.APPLIED, applied.financialStatus)
            assertNull(applied.pendingReason)
        }
    }

    @Test
    fun `합계가 맞아도 주문 상태와 수량이 어긋나면 거절한다`() {
        val resting = confirmed(OrderQueryOrderState.RESTING, 0, 3)
        assertFailsWith<IllegalArgumentException> { resting.copy(filledQuantity = 1, remainingQuantity = 2) }
        assertFailsWith<IllegalArgumentException> { resting.copy(state = OrderQueryOrderState.PARTIALLY_FILLED) }
        assertFailsWith<IllegalArgumentException> {
            resting.copy(state = OrderQueryOrderState.PARTIALLY_FILLED, filledQuantity = 3, remainingQuantity = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            resting.copy(state = OrderQueryOrderState.FILLED, filledQuantity = 2, remainingQuantity = 0, cancelledQuantity = 1)
        }
        assertFailsWith<IllegalArgumentException> { pending().copy(remainingQuantity = 3) }
        assertFailsWith<IllegalArgumentException> { resting.copy(remainingQuantity = null) }
        assertFailsWith<IllegalArgumentException> { resting.copy(cancelledQuantity = -1) }
        assertFailsWith<IllegalArgumentException> { resting.copy(price = 0) }
        assertFailsWith<IllegalArgumentException> {
            resting.copy(initialQuantity = Long.MAX_VALUE, remainingQuantity = Long.MAX_VALUE, filledQuantity = Long.MAX_VALUE)
        }
        assertFailsWith<IllegalArgumentException> { resting.copy(orderType = OrderType.MARKET) }
        assertFailsWith<IllegalArgumentException> { resting.copy(timeInForce = TimeInForce.IOC) }
    }

    @Test
    fun `결과 위치는 같은 funds lineage와 마켓의 매칭 확인 상태에만 존재한다`() {
        val resting = confirmed(OrderQueryOrderState.RESTING, 0, 3)
        assertFailsWith<IllegalArgumentException> { pending().copy(resultStream = STREAM) }
        assertFailsWith<IllegalArgumentException> { resting.copy(resultStream = null) }
        assertFailsWith<IllegalArgumentException> { resting.copy(lastAffectedResultSequence = 0) }
        assertFailsWith<IllegalArgumentException> { resting.copy(financialThrough = null) }
        assertFailsWith<IllegalArgumentException> { resting.copy(financialThrough = -1) }
        assertFailsWith<IllegalArgumentException> { resting.copy(resultStream = STREAM.copy(marketId = MarketId("ETH-KRW"))) }
        assertFailsWith<IllegalArgumentException> { resting.copy(resultStream = STREAM.copy(fundsLineage = ClusterLineage(30, 40))) }
    }

    @Test
    fun `후속 결과는 체결 증가와 최종 취소를 보존하고 금융 확인을 별도로 전진한다`() {
        val resting = confirmed(OrderQueryOrderState.RESTING, 0, 3).copy(financialThrough = 7)
        resting.requireSuccessorOf(pending())
        val partial = confirmed(OrderQueryOrderState.PARTIALLY_FILLED, 1, 2).copy(lastAffectedResultSequence = 8, financialThrough = 7)
        partial.requireSuccessorOf(resting)
        assertEquals(OrderQueryFinancialStatus.PENDING, partial.financialStatus)
        val cancelled =
            confirmed(
                OrderQueryOrderState.CANCELLED,
                1,
                0,
                cancelled = 2,
            ).copy(lastAffectedResultSequence = 9, financialThrough = 7)
        cancelled.requireSuccessorOf(partial)
        val released = cancelled.copy(financialThrough = 9)
        released.requireSuccessorOf(cancelled)
        assertEquals(OrderQueryFinancialStatus.APPLIED, released.financialStatus)
        assertFailsWith<IllegalArgumentException> { partial.requireSuccessorOf(cancelled) }
        assertFailsWith<IllegalArgumentException> {
            cancelled
                .copy(
                    filledQuantity = 0,
                    cancelledQuantity = 3,
                ).requireSuccessorOf(cancelled)
        }
        assertFailsWith<IllegalArgumentException> { cancelled.copy(lastAffectedResultSequence = 10).requireSuccessorOf(cancelled) }
        assertFailsWith<IllegalArgumentException> { cancelled.copy(financialThrough = 8).requireSuccessorOf(released) }
    }

    @Test
    fun `같은 결과의 체결 사실 변경과 원 주문 변경 및 위치 역행은 거절한다`() {
        val resting = confirmed(OrderQueryOrderState.RESTING, 0, 3).copy(financialThrough = 7)
        val partial = confirmed(OrderQueryOrderState.PARTIALLY_FILLED, 1, 2)
        assertFailsWith<IllegalArgumentException> { partial.requireSuccessorOf(resting) }
        partial.copy(lastAffectedResultSequence = 8, financialThrough = 7).requireSuccessorOf(resting)
        assertFailsWith<IllegalArgumentException> { resting.copy(userId = "other").requireSuccessorOf(resting) }
        assertFailsWith<IllegalArgumentException> { resting.copy(price = 101).requireSuccessorOf(resting) }
        assertFailsWith<IllegalArgumentException> { resting.copy(side = Side.SELL).requireSuccessorOf(resting) }
        assertFailsWith<IllegalArgumentException> { resting.copy(initialQuantity = 4, remainingQuantity = 4).requireSuccessorOf(resting) }
        assertFailsWith<IllegalArgumentException> { resting.copy(admissionSequence = 2).requireSuccessorOf(resting) }
        assertFailsWith<IllegalArgumentException> { resting.copy(marketAdmissionSequence = 2).requireSuccessorOf(resting) }
        assertFailsWith<IllegalArgumentException> { resting.copy(lastAffectedResultSequence = 6).requireSuccessorOf(resting) }
        assertFailsWith<IllegalArgumentException> { resting.copy(financialThrough = 6).requireSuccessorOf(resting) }
        assertFailsWith<IllegalArgumentException> {
            resting.copy(resultStream = STREAM.copy(ownershipEpoch = 2)).requireSuccessorOf(resting)
        }
    }

    @Test
    fun `범위와 batch는 호출자의 이후 컬렉션 변경에 영향받지 않고 prefix와 중복을 검사한다`() {
        val markets = mutableSetOf(MARKET)
        val namespace = OrderQueryNamespace(FUNDS, markets)
        markets.clear()
        assertEquals(setOf(MARKET), namespace.markets)
        namespace.requireRecord(pending())
        assertFailsWith<IllegalArgumentException> { namespace.requireRecord(pending().copy(marketId = "ETH-KRW")) }
        val records = mutableListOf(pending())
        val batch = OrderQueryBatch("one", 0, 1, records)
        records.clear()
        assertEquals(listOf(pending()), batch.records)
        assertFailsWith<IllegalArgumentException> { OrderQueryBatch("one", 0, 0, listOf(pending())) }
        assertFailsWith<IllegalArgumentException> { OrderQueryBatch("one", 0, 1, listOf(pending(), pending())) }
        assertFailsWith<IllegalArgumentException> {
            OrderQueryBatch("one", 0, 2, listOf(pending(), pending().copy(admissionSequence = 2)))
        }
        assertFailsWith<IllegalArgumentException> { progress(-1, 0) }
        assertFailsWith<IllegalArgumentException> { progress(2, 1) }
        assertEquals(OrderQueryFreshness.CAUGHT_UP, progress(1, 1).freshness)
        assertEquals(OrderQueryFreshness.LAGGING, progress(1, 2).freshness)
    }

    private fun pending(): OrderQueryRecord =
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

    private fun confirmed(
        state: OrderQueryOrderState,
        filled: Long,
        remaining: Long,
        cancelled: Long = 0,
        notExecuted: Long = 0,
    ): OrderQueryRecord =
        pending().copy(
            state = state,
            filledQuantity = filled,
            remainingQuantity = remaining,
            cancelledQuantity = cancelled,
            notExecutedQuantity = notExecuted,
            resultStream = STREAM,
            lastAffectedResultSequence = 7,
            financialThrough = 0,
        )

    private fun OrderQueryRecord.quantities(): List<Long?> =
        listOf(initialQuantity, filledQuantity, remainingQuantity, cancelledQuantity, notExecutedQuantity)

    private fun progress(
        applied: Long,
        observed: Long,
    ): OrderQueryProgress = OrderQueryProgress(OrderQueryLifecycle.READY, applied, observed, 1, Instant.EPOCH)

    private companion object {
        const val MARKET = "BTC-KRW"
        val FUNDS = ClusterLineage(1, 2)
        val STREAM = MatchingResultStream(ClusterLineage(3, 4), FUNDS, MarketId(MARKET), 1, 1)
    }
}
