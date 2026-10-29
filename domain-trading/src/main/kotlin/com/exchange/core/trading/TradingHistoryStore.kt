package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.order.OrderReservation

/**
 * Exact, lossless history for one newly created trading owner. Implementations keep bounded
 * staging, provide read-your-writes, and never interpret an I/O failure as a missing value.
 * An append is atomic across its indexes. Only the contiguous durable ordinal permits eviction.
 * This history alone is not a live-state recovery checkpoint.
 */
interface TradingHistoryStore {
    val durableOrdinal: Long

    fun append(batch: TradingHistoryBatch)

    /** Waits for every preceding append to become durable, or throws. */
    fun flush(): Long

    fun findRequest(
        userId: UserId,
        requestId: String,
    ): OrderAdmissionState.RequestRecord?

    fun findAdmission(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderAdmissionState.Record?

    fun findAdmission(sequence: Long): OrderAdmissionState.Record?

    fun findCompletion(sequence: Long): TradingState.Completion?

    fun findPreparation(preparationId: String): OrderAdmissionState.PreparationRecord?

    fun containsTransactionId(id: String): Boolean

    fun containsSourceEventId(id: String): Boolean

    fun requests(): Sequence<OrderAdmissionState.RequestRecord>

    fun admissions(): Sequence<OrderAdmissionState.Record>

    fun completions(): Sequence<TradingState.Completion>

    fun preparations(): Sequence<OrderAdmissionState.PreparationRecord>

    /** The latest reservation per order, including terminal orders, in first-insertion order. */
    fun reservations(): Sequence<OrderReservation>

    fun transactions(): Sequence<LedgerTransaction>
}

/** One complete business transition; rejected requests also receive their own ordinal. */
data class TradingHistoryBatch(
    val ordinal: Long,
    val request: OrderAdmissionState.RequestRecord? = null,
    val admission: OrderAdmissionState.Record? = null,
    val completion: TradingState.Completion? = null,
    val preparation: OrderAdmissionState.PreparationRecord? = null,
    val reservations: List<OrderReservation> = emptyList(),
    val ledgerTransactions: List<LedgerTransaction> = emptyList(),
)

/** Retained Java objects, distinct from total durable history and active business state. */
data class TradingHistoryRetention(
    val lastOrdinal: Long,
    val durableOrdinal: Long,
    val stagedBatches: Int,
    val retainedRequests: Int,
    val retainedAdmissions: Int,
    val retainedReservations: Int,
    val retainedTransactions: Int,
    val retainedTransactionIds: Int,
    val retainedSourceEventIds: Int,
    val retainedPreparations: Int,
    val pendingOrders: Int,
    val activeOrders: Int,
    val retainedCompletions: Int = 0,
)
