package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.order.OrderReservation
import java.util.Collections

/**
 * Exact, lossless history for one newly created trading owner. Implementations keep bounded
 * staging, provide read-your-writes, and never interpret an I/O failure as a missing value.
 * An append is atomic across its indexes. Only the contiguous recoverable ordinal permits eviction:
 * values must be materialized for exact reads and recoverable from either this store's durable data
 * or a preserved authoritative log/checkpoint. A derived index must never acknowledge solely on
 * an unbacked memory write. This history alone is not a live-state recovery checkpoint.
 */
interface TradingHistoryStore {
    val durableOrdinal: Long

    fun append(batch: TradingHistoryBatch)

    /** The live owner has already checked its canonical admissions and reservation links. */
    fun appendPrepared(prepared: PreparedTradingHistoryBatch) {
        prepared.requireOwner(this)
        append(prepared.batch)
    }

    /** Waits until every preceding append is materialized and recoverable under the store's contract, or throws. */
    fun flush(): Long

    /** Same recovery barrier for a previously appended ordinal; may also acknowledge later appends. */
    fun awaitDurable(ordinal: Long): Long {
        require(ordinal >= 0) { "history durability target must not be negative" }
        // Stores without partial barriers retain the existing full-flush contract.
        val durable = flush()
        check(durable >= ordinal) { "history did not acknowledge the requested ordinal" }
        return durable
    }

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

/**
 * Issued only inside the domain after a transition has passed the live owner's checks.
 * It belongs to one history generation and does not acknowledge materialization or durability.
 * Raw callers continue to use [TradingHistoryStore.append] and its full input checks.
 */
class PreparedTradingHistoryBatch internal constructor(
    private val ownerStore: TradingHistoryStore,
    batch: TradingHistoryBatch,
    reservationSequences: List<Long>,
) {
    val batch: TradingHistoryBatch =
        batch.copy(
            reservations = Collections.unmodifiableList(batch.reservations.toList()),
            ledgerTransactions = Collections.unmodifiableList(batch.ledgerTransactions.toList()),
        )
    val reservationSequences: List<Long> = Collections.unmodifiableList(reservationSequences.toList())

    init {
        require(reservationSequences.size == batch.reservations.size)
        require(reservationSequences.all { it > 0 })
    }

    fun requireOwner(store: TradingHistoryStore) {
        require(ownerStore === store) { "prepared history transition belongs to a different store" }
    }
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
