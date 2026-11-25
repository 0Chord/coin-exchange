package com.exchange.core.api.trading.application

import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.TradingIdentifiers
import java.time.Instant
import java.util.Collections

/** 한 funds lineage의 모든 지원 마켓이 같은 전역 접수 prefix를 공유한다. */
class OrderQueryNamespace(
    val fundsLineage: ClusterLineage,
    markets: Set<String>,
) {
    val markets: Set<String> = Collections.unmodifiableSet(markets.toSet())

    init {
        require(this.markets.isNotEmpty()) { "query markets must not be empty" }
        this.markets.forEach(::requireOrderQueryText)
    }

    fun requireRecord(record: OrderQueryRecord) {
        require(record.fundsLineage == fundsLineage && record.marketId in markets) { "record is outside the query namespace" }
    }
}

enum class OrderQueryOrderState { MATCHING_PENDING, RESTING, PARTIALLY_FILLED, FILLED, CANCELLED, NOT_EXECUTED }

enum class OrderQueryFinancialStatus { PENDING, APPLIED }

enum class OrderQueryPendingReason { MATCHING, RESULT_PROCESSING, SETTLEMENT, RELEASE }

enum class OrderQueryLifecycle { INITIALIZING, READY, BLOCKED }

enum class OrderQueryFreshness { CAUGHT_UP, LAGGING }

/** 원본의 완결된 상태를 받는다. 조회 계층에서 체결 수량이나 금액을 추정하지 않는다. */
data class OrderQueryRecord(
    val fundsLineage: ClusterLineage,
    val marketId: String,
    val userId: String,
    val side: Side,
    val orderType: OrderType,
    val timeInForce: TimeInForce,
    val price: Long,
    val initialQuantity: Long,
    val admissionSequence: Long,
    val marketAdmissionSequence: Long,
    val filledQuantity: Long,
    val remainingQuantity: Long?,
    val cancelledQuantity: Long,
    val notExecutedQuantity: Long,
    val state: OrderQueryOrderState,
    val resultStream: MatchingResultStream? = null,
    val lastAffectedResultSequence: Long? = null,
    val financialThrough: Long? = null,
) {
    val orderId: String get() = TradingIdentifiers.order(fundsLineage, admissionSequence).value

    val financialStatus: OrderQueryFinancialStatus
        get() =
            if (lastAffectedResultSequence != null && financialThrough != null && financialThrough >= lastAffectedResultSequence) {
                OrderQueryFinancialStatus.APPLIED
            } else {
                OrderQueryFinancialStatus.PENDING
            }

    val pendingReason: OrderQueryPendingReason?
        get() =
            if (financialStatus == OrderQueryFinancialStatus.APPLIED) {
                null
            } else {
                when (state) {
                    OrderQueryOrderState.MATCHING_PENDING -> OrderQueryPendingReason.MATCHING
                    OrderQueryOrderState.RESTING -> OrderQueryPendingReason.RESULT_PROCESSING
                    OrderQueryOrderState.PARTIALLY_FILLED, OrderQueryOrderState.FILLED -> OrderQueryPendingReason.SETTLEMENT
                    OrderQueryOrderState.CANCELLED, OrderQueryOrderState.NOT_EXECUTED -> OrderQueryPendingReason.RELEASE
                }
            }

    init {
        requireOrderQueryText(marketId)
        requireOrderQueryText(userId)
        require(price > 0 && initialQuantity > 0 && admissionSequence > 0 && marketAdmissionSequence > 0)
        require(orderType == OrderType.LIMIT && timeInForce == TimeInForce.GTC) { "only LIMIT/GTC queries are supported" }
        require(filledQuantity >= 0 && cancelledQuantity >= 0 && notExecutedQuantity >= 0)
        require(remainingQuantity == null || remainingQuantity >= 0)
        requireQuantities()
        if (state == OrderQueryOrderState.MATCHING_PENDING) {
            require(resultStream == null && lastAffectedResultSequence == null && financialThrough == null)
        } else {
            require(resultStream != null && lastAffectedResultSequence != null && lastAffectedResultSequence > 0)
            require(financialThrough != null && financialThrough >= 0)
            require(resultStream.fundsLineage == fundsLineage && resultStream.marketId.value == marketId)
        }
    }

    /** 이미 확인한 사실을 되돌리거나 서로 다른 결과 stream 위치를 비교하지 않는다. */
    fun requireSuccessorOf(previous: OrderQueryRecord) {
        require(
            fundsLineage == previous.fundsLineage && marketId == previous.marketId && userId == previous.userId &&
                side == previous.side && orderType == previous.orderType && timeInForce == previous.timeInForce &&
                price == previous.price && initialQuantity == previous.initialQuantity &&
                admissionSequence == previous.admissionSequence && marketAdmissionSequence == previous.marketAdmissionSequence,
        ) { "immutable order origin changed" }
        require(filledQuantity >= previous.filledQuantity && cancelledQuantity >= previous.cancelledQuantity)
        require(notExecutedQuantity >= previous.notExecutedQuantity)
        require(allowedSuccessor(previous.state, state)) { "order state regressed" }
        previous.resultStream?.let {
            require(resultStream == it) { "matching result stream changed" }
            require(lastAffectedResultSequence!! >= previous.lastAffectedResultSequence!!)
            require(financialThrough!! >= previous.financialThrough!!)
            if (lastAffectedResultSequence == previous.lastAffectedResultSequence) {
                require(
                    state == previous.state && filledQuantity == previous.filledQuantity &&
                        remainingQuantity == previous.remainingQuantity && cancelledQuantity == previous.cancelledQuantity &&
                        notExecutedQuantity == previous.notExecutedQuantity,
                ) { "the same matching result changed order facts" }
            }
            if (previous.state in TERMINAL_STATES) {
                require(lastAffectedResultSequence == previous.lastAffectedResultSequence) { "terminal matching result changed" }
            }
        }
    }

    private fun requireQuantities() {
        // 상태별 차감식으로 검사하므로 큰 수량의 덧셈 overflow도 허용되지 않는다.
        val valid =
            when (state) {
                OrderQueryOrderState.MATCHING_PENDING -> {
                    filledQuantity == 0L && remainingQuantity == null && cancelledQuantity == 0L && notExecutedQuantity == 0L
                }

                OrderQueryOrderState.RESTING -> {
                    filledQuantity == 0L && remainingQuantity == initialQuantity && cancelledQuantity == 0L && notExecutedQuantity == 0L
                }

                OrderQueryOrderState.PARTIALLY_FILLED -> {
                    filledQuantity in 1 until initialQuantity && remainingQuantity == initialQuantity - filledQuantity &&
                        cancelledQuantity == 0L && notExecutedQuantity == 0L
                }

                OrderQueryOrderState.FILLED -> {
                    filledQuantity == initialQuantity && remainingQuantity == 0L && cancelledQuantity == 0L && notExecutedQuantity == 0L
                }

                OrderQueryOrderState.CANCELLED -> {
                    filledQuantity < initialQuantity && cancelledQuantity == initialQuantity - filledQuantity &&
                        remainingQuantity == 0L && notExecutedQuantity == 0L
                }

                OrderQueryOrderState.NOT_EXECUTED -> {
                    filledQuantity == 0L && remainingQuantity == 0L && cancelledQuantity == 0L && notExecutedQuantity == initialQuantity
                }
            }
        require(valid) { "order quantities contradict its state" }
    }

    private fun allowedSuccessor(
        previous: OrderQueryOrderState,
        next: OrderQueryOrderState,
    ): Boolean =
        when (previous) {
            OrderQueryOrderState.MATCHING_PENDING -> true
            OrderQueryOrderState.RESTING -> next in RESTING_SUCCESSORS
            OrderQueryOrderState.PARTIALLY_FILLED -> next in PARTIAL_SUCCESSORS
            OrderQueryOrderState.FILLED, OrderQueryOrderState.CANCELLED, OrderQueryOrderState.NOT_EXECUTED -> next == previous
        }

    private companion object {
        val TERMINAL_STATES = setOf(OrderQueryOrderState.FILLED, OrderQueryOrderState.CANCELLED, OrderQueryOrderState.NOT_EXECUTED)
        val RESTING_SUCCESSORS =
            setOf(
                OrderQueryOrderState.RESTING,
                OrderQueryOrderState.PARTIALLY_FILLED,
                OrderQueryOrderState.FILLED,
                OrderQueryOrderState.CANCELLED,
            )
        val PARTIAL_SUCCESSORS =
            setOf(OrderQueryOrderState.PARTIALLY_FILLED, OrderQueryOrderState.FILLED, OrderQueryOrderState.CANCELLED)
    }
}

data class OrderQueryProgress(
    val lifecycle: OrderQueryLifecycle,
    val appliedRevision: Long,
    val observedRevision: Long,
    val coveredAdmissionSequence: Long,
    val updatedAt: Instant,
) {
    val freshness: OrderQueryFreshness
        get() = if (observedRevision == appliedRevision) OrderQueryFreshness.CAUGHT_UP else OrderQueryFreshness.LAGGING

    init {
        require(appliedRevision >= 0 && observedRevision >= appliedRevision && coveredAdmissionSequence >= 0)
    }
}

/** 생성 뒤 호출자가 목록을 바꿔 중복 판정의 의미를 바꿀 수 없다. */
class OrderQueryBatch(
    val batchId: String,
    val expectedRevision: Long,
    val coveredAdmissionSequence: Long,
    records: List<OrderQueryRecord>,
) {
    val records: List<OrderQueryRecord> = Collections.unmodifiableList(records.toList())

    init {
        requireOrderQueryText(batchId)
        require(expectedRevision >= 0 && coveredAdmissionSequence >= 0)
        require(this.records.all { it.admissionSequence <= coveredAdmissionSequence })
        require(
            this.records
                .map { it.orderId }
                .distinct()
                .size == this.records.size,
        ) { "duplicate order in batch" }
        require(
            this.records
                .map { it.marketId to it.marketAdmissionSequence }
                .distinct()
                .size == this.records.size,
        )
    }
}

data class OrderQuerySnapshot(
    val progress: OrderQueryProgress?,
    val record: OrderQueryRecord?,
)

data class OrderListQuerySnapshot(
    val progress: OrderQueryProgress?,
    val orders: List<OrderQueryRecord>,
)

internal fun requireOrderQueryText(value: String) {
    require(value.isNotBlank() && value.length <= 256 && value.none { Character.isISOControl(it) }) { "invalid query text" }
}
