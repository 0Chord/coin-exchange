package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerReconciliation
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import java.util.Collections

/**
 * 새 거래 소유자 하나에 대한 정확하고 손실 없는 이력이다. 구현체는 대기 공간에 한도를 두고,
 * 자신이 쓴 값을 읽을 수 있게 하며, I/O 실패를 값이 없다는 뜻으로 처리하지 않는다.
 * append는 모든 관련 인덱스에 원자적으로 반영한다. 복구 가능한 연속 순번까지만 해제할 수 있다.
 * 값은 정확한 조회가 가능하도록 저장되어야 하며, 이 저장소의 영속 데이터나 보존한 복구 원본
 * 로그·체크포인트에서 복구할 수 있어야 한다. 파생 인덱스는 복구 원본에 보존되지 않은
 * 메모리 쓰기만으로 저장을 확인해서는 안 된다. 이력만으로는 현재 상태를 복구하는 체크포인트가 되지 않는다.
 */
interface TradingHistoryStore {
    val durableOrdinal: Long

    fun append(batch: TradingHistoryBatch)

    /** 현재 소유자가 표준 승인 값과 예약 연결을 이미 검사한 상태다. */
    fun appendPrepared(prepared: PreparedTradingHistoryBatch) {
        prepared.requireOwner(this)
        append(prepared.batch)
    }

    /** 앞선 모든 append가 저장소 계약에 따라 저장·복구 가능해질 때까지 기다리며, 실패하면 예외를 던진다. */
    fun flush(): Long

    /** 이미 추가한 순번에 같은 복구 장벽을 적용한다. 이후 append의 저장도 함께 확인할 수 있다. */
    fun awaitDurable(ordinal: Long): Long {
        require(ordinal >= 0) { "history durability target must not be negative" }
        // 일부 구간만 기다릴 수 없는 저장소는 기존의 전체 flush 계약을 유지한다.
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

    /**
     * 복구할 영속 이력 전체의 최대 순번이다. 호출자는 먼저 flush로 복구 순번을 확정한다.
     * 주문 처리나 체크포인트 생성에서는 호출하지 않는다. 구현체는 같은 값을 정확한 인덱스로 제공해도 된다.
     */
    fun recoveryWatermarks(): TradingHistoryWatermarks {
        val lastAdmissionSequence = admissions().maxOfOrNull { it.sequence } ?: 0L
        val lastEngineSequences = linkedMapOf<MarketId, Long>()
        for (completion in completions()) {
            val result = completion.matchingResult
            val marketId = result.command.marketId
            lastEngineSequences[marketId] = maxOf(lastEngineSequences[marketId] ?: 0L, result.lastEngineSequence)
        }
        return TradingHistoryWatermarks(lastAdmissionSequence, Collections.unmodifiableMap(lastEngineSequences))
    }

    /**
     * 복구할 영속 이력에서 주문별 최신 활성 예약만 읽는다. 호출자는 먼저 flush를 완료한다.
     * 복구 검증 전용이며, 구현체는 같은 값을 활성 예약 인덱스로 제공해도 된다.
     */
    fun recoveryReservations(): Sequence<OrderReservation> = reservations().filter { it.status == OrderReservationStatus.ACTIVE }

    /** 영속 원장에서 전체 사용자 잔고를 복구 검증용으로 합산한다. 최종 0인 사용 이력도 보존한다. */
    fun recoveryBalances(): Sequence<Balance> = LedgerReconciliation().reconstructBalances(transactions()).asSequence()

    fun preparations(): Sequence<OrderAdmissionState.PreparationRecord>

    /** 종료 주문까지 포함한 주문별 최신 예약이며, 최초 삽입 순서를 따른다. */
    fun reservations(): Sequence<OrderReservation>

    fun transactions(): Sequence<LedgerTransaction>
}

/** 종료된 주문과 빈 주문장까지 포함한 이력 전체의 최대 순번이다. */
data class TradingHistoryWatermarks(
    val lastAdmissionSequence: Long,
    val lastEngineSequences: Map<MarketId, Long>,
)

/**
 * 전이가 현재 소유자의 검사를 통과한 뒤 도메인 내부에서만 발행한다.
 * 하나의 이력 세대에 속하며, 저장 완료나 내구성을 확인하는 값은 아니다.
 * 일반 호출자는 계속 [TradingHistoryStore.append]와 전체 입력 검사를 사용한다.
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

/** 완전한 업무 전이 하나다. 거절한 요청에도 별도 순번을 부여한다. */
data class TradingHistoryBatch(
    val ordinal: Long,
    val request: OrderAdmissionState.RequestRecord? = null,
    val admission: OrderAdmissionState.Record? = null,
    val completion: TradingState.Completion? = null,
    val preparation: OrderAdmissionState.PreparationRecord? = null,
    val reservations: List<OrderReservation> = emptyList(),
    val ledgerTransactions: List<LedgerTransaction> = emptyList(),
)

/** 보관 중인 Java 객체 수이며, 전체 영속 이력이나 현재 업무 상태와는 별개다. */
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
