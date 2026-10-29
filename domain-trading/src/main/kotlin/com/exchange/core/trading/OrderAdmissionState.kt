package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.UserId
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceRequestConflictException
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.Side
import java.time.Instant
import java.util.Collections

private const val ADMISSION_SEQUENCE_SEGMENT_SIZE = 1_024

/**
 * 한 실행자가 순서대로 호출하는 접수 상태. 여러 마켓이 사용자·자산별 최신 잔고를 공유한다.
 * 저장 매체와 동시 호출은 소유하지 않는다. 공통 거래 소유자가 복구를 조율하며,
 * 반영 중 오류 뒤에는 재전송과 정상 조회도 차단한다.
 */
class OrderAdmissionState(
    initialBalances: List<Balance>,
    private val calculator: OrderAdmissionCalculator,
    private val historyStore: TradingHistoryStore? = null,
    private val historyBatchLimit: Int = 1_024,
    private val onHistoryEvicted: (TradingHistoryBatch) -> Unit = {},
) {
    private data class BalanceKey(
        val userId: UserId,
        val assetId: AssetId,
    )

    private data class OrderKey(
        val marketId: MarketId,
        val orderId: OrderId,
    )

    private data class RequestKey(
        val userId: UserId,
        val requestId: String,
    )

    private val balances: MutableMap<BalanceKey, Balance> = linkedMapOf()
    private val records: MutableMap<OrderKey, Record> = linkedMapOf()

    // 순번 순회는 하지 않는다. Long 구간 키마다 고정 크기의 원본 참조만 보관한다.
    private val recordsBySequence: MutableMap<Long, MutableList<Record?>> = hashMapOf()
    private val sequenceSegmentSizes: MutableMap<Long, Int> = hashMapOf()
    private val terminalOrdinals: MutableMap<OrderKey, Long> = hashMapOf()
    private val pendingByMarket: MutableMap<MarketId, ArrayDeque<Record>> = linkedMapOf()
    private val currentReservations: MutableMap<OrderKey, OrderReservation> = linkedMapOf()
    private val ledgerTransactions: MutableList<LedgerTransaction> = if (historyStore == null) mutableListOf() else java.util.LinkedList()
    private val transactionIds = mutableSetOf<String>()
    private val sourceEventIds = mutableSetOf<String>()
    private val requestRecords: MutableMap<RequestKey, RequestRecord> = linkedMapOf()
    private val preparations: MutableMap<String, PreparationRecord> = linkedMapOf()
    private var preparedSettlement: PreparedSettlement? = null
    private val usedBalances =
        initialBalances
            .filter { it.available != Amount.ZERO }
            .map {
                BalanceKey(
                    it.userId,
                    it.assetId,
                )
            }.toMutableSet()
    private var lastSequence = 0L
    private var lastHistoryOrdinal = 0L
    private var durableHistoryOrdinal = 0L
    private val historyBatches = ArrayDeque<TradingHistoryBatch>()
    private var checkpointRestored = false
    private var contextCheckInProgress = false

    /** 차단 여부만 진단할 수 있다. 부분 반영 가능성이 있는 잔고를 정상 조회로 내보내지 않는다. */
    var isBlocked: Boolean = false
        private set

    init {
        require(historyBatchLimit > 0) { "history batch limit must be positive" }
        require(historyStore == null || historyStore.durableOrdinal == 0L) { "history requires a fresh live-state generation" }
        initialBalances.forEach { balance ->
            require(balance.hold == Amount.ZERO) { "초기 예약이 없는 상태의 hold는 0이어야 합니다" }
            val key = BalanceKey(balance.userId, balance.assetId)
            require(key !in balances) { "초기 사용자·자산 잔고가 중복됐습니다" }
            balances[key] = balance
        }
    }

    /** 예약·원본·정책·원장을 후보에서 공유하고, 승인된 접수에만 순번을 붙인다. */
    data class Record internal constructor(
        val sequence: Long,
        val candidate: OrderAdmissionCandidate,
    )

    /** 당시 서버 문맥과 최초 업무 결과. 거절을 보관해도 원장을 발행했다는 뜻은 아니다. */
    class RequestRecord internal constructor(
        val requestId: String,
        val order: OrderAdmissionInput,
        val market: MarketDefinition,
        val feePolicySnapshot: TradingFeePolicySnapshot,
        val ledgerTransactionId: String,
        val occurredAt: Instant,
        val result: Result,
    )

    sealed class Result {
        data class Accepted(
            val record: Record,
        ) : Result()

        data class Rejected(
            val reason: OrderAdmissionResult.Reason,
            val insufficientBalance: OrderAdmissionResult.InsufficientBalance? = null,
        ) : Result()
    }

    data class Snapshot internal constructor(
        val balances: List<Balance>,
        val pendingOrders: List<Record>,
        val lastSequence: Long,
        val requests: List<RequestRecord> = emptyList(),
        val preparations: List<PreparationRecord> = emptyList(),
    )

    /** 최초 지급 원본·결과와 실제 OPENING. 현재 잔고는 별도의 balances에서 관찰한다. */
    class PreparationRecord internal constructor(
        val result: OpeningBalanceResult,
        val transaction: LedgerTransaction,
    )

    /** 같은 준비는 최초 결과를 돌려주며, 신규 지급만 주문과 같은 잔고·원장 ID 이력에 반영한다. */
    fun prepareDevelopmentBalance(
        opening: OpeningBalance,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): OpeningBalanceResult {
        checkUsable()
        val previous = preparations[opening.preparationId] ?: historyRead { findPreparation(opening.preparationId) }
        if (previous != null) {
            if (previous.result.opening != opening) {
                throw OpeningBalanceRequestConflictException("같은 준비 ID의 사용자·자산·금액이 다릅니다")
            }
            return previous.result.copy(alreadyPrepared = true)
        }

        prepareHistoryCapacity()
        val key = BalanceKey(opening.userId, opening.assetId)
        val current = balances[key] ?: Balance(opening.userId, opening.assetId, Amount.ZERO, Amount.ZERO)
        val hasHistory =
            key in usedBalances ||
                preparations.values.any { it.result.opening.userId == opening.userId && it.result.opening.assetId == opening.assetId } ||
                records.values.any { it.candidate.order.userId == opening.userId && it.candidate.reservation.assetId == opening.assetId }
        opening.requireUnused(current, hasHistory)
        require(calculator.validIdentifier(ledgerTransactionId)) { "원장 거래 식별자가 잘못됐습니다" }
        val next = current.credit(opening.amount)
        val transaction = opening.transaction(ledgerTransactionId, occurredAt)
        checkUnusedTransaction(transaction)
        val result = OpeningBalanceResult(opening, ledgerTransactionId, occurredAt, alreadyPrepared = false)
        val record = PreparationRecord(result, transaction)

        // 쓰기를 시작한 뒤에는 일부만 반영됐을 수 있다. 전부 끝나야 정상 사용을 허용한다.
        isBlocked = true
        balances[key] = next
        preparations[opening.preparationId] = record
        transactionIds.add(transaction.ledgerTransactionId)
        sourceEventIds.add(transaction.sourceEventId)
        ledgerTransactions.add(transaction)
        usedBalances.add(key)
        if (historyStore != null) {
            appendHistory(TradingHistoryBatch(nextHistoryOrdinal(), preparation = record, ledgerTransactions = listOf(transaction)))
        }
        isBlocked = false
        return result
    }

    /** 차단 여부를 먼저 확인하고 요청 키의 존재만 조회한다. 원본 비교와 결과 판단은 admit에서 수행한다. */
    fun hasRequest(
        userId: UserId,
        requestId: String,
    ): Boolean {
        checkUsable()
        return RequestKey(userId, requestId) in requestRecords || historyRead { findRequest(userId, requestId) } != null
    }

    /**
     * 유효한 사용자·요청 ID의 첫 결과를 보존한다. 같은 원본의 재전송은 현재 서버 문맥을 쓰지 않는다.
     * 입구 식별자 오류·내용 충돌·반영 전 내부 오류는 새 최초 결과로 저장하지 않는다.
     */
    fun admit(
        requestId: String,
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): Result {
        checkUsable()
        invalidIdentifiers(requestId, order)?.let { return it }

        val key = RequestKey(order.userId, requestId)
        val previous = requestRecords[key] ?: historyRead { findRequest(order.userId, requestId) }
        return admitResolved(requestId, order, market, feePolicySnapshot, ledgerTransactionId, occurredAt, previous)
    }

    /**
     * 한 소유 호출 안에서 최초 요청을 한 번 조회하고 외부 문맥 검사 뒤에 같은 원본으로 판단한다.
     * 문맥 거절의 null은 요청 이력에 남지 않는다. 문맥 검사에서 상태에 재진입할 수 없으며,
     * 그 결과나 조회한 원본을 외부에 넘겨 다음 호출의 검증을 생략하지 않는다.
     */
    fun admitWithContextCheck(
        requestId: String,
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
        checkContext: (knownRequest: Boolean) -> Boolean,
    ): Result? {
        checkUsable()
        val key = RequestKey(order.userId, requestId)
        val previous = requestRecords[key] ?: historyRead { findRequest(order.userId, requestId) }
        contextCheckInProgress = true
        val acceptedContext =
            try {
                checkContext(previous != null)
            } finally {
                contextCheckInProgress = false
            }
        checkUsable()
        if (!acceptedContext) return null
        invalidIdentifiers(requestId, order)?.let { return it }
        return admitResolved(requestId, order, market, feePolicySnapshot, ledgerTransactionId, occurredAt, previous)
    }

    private fun invalidIdentifiers(
        requestId: String,
        order: OrderAdmissionInput,
    ): Result.Rejected? =
        when {
            !calculator.validIdentifier(requestId) -> Result.Rejected(OrderAdmissionResult.Reason.INVALID_REQUEST_IDENTIFIER)
            !calculator.validOrderIdentifiers(order) -> Result.Rejected(OrderAdmissionResult.Reason.INVALID_ORDER_IDENTIFIER)
            else -> null
        }

    private fun admitResolved(
        requestId: String,
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
        previous: RequestRecord?,
    ): Result {
        if (previous != null) {
            return if (previous.order == order) previous.result else Result.Rejected(OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT)
        }

        prepareHistoryCapacity()
        val result = prepare(order, market, feePolicySnapshot, ledgerTransactionId, occurredAt)
        val request = RequestRecord(requestId, order, market, feePolicySnapshot, ledgerTransactionId, occurredAt, result)
        when (result) {
            is Result.Accepted -> applyAccepted(result.record, request)
            is Result.Rejected -> rememberRejected(request)
        }
        return result
    }

    /**
     * 요청 ID 없는 기존 내부 호출의 계약을 유지한다. 이 입구 자체는 최초 요청 결과를 보존하지 않는다.
     * 외부 잔고나 후보를 받지 않고 소유한 최신 잔고로 계산하며, 반영 중 오류는 SQL식 롤백을 보장하지 않는다.
     */
    fun admit(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): Result {
        checkUsable()
        prepareHistoryCapacity()
        val result = prepare(order, market, feePolicySnapshot, ledgerTransactionId, occurredAt)
        if (result is Result.Accepted) applyAccepted(result.record)
        return result
    }

    /** 반환 목록은 내부 자료와 분리되며 이후 접수로 이미 반환한 값이 바뀌지 않는다. */
    fun snapshot(): Snapshot {
        checkUsable()
        return Snapshot(
            immutableList(balances.values),
            immutableList(pendingByMarket.values.flatMap { it }.sortedBy { it.sequence }),
            lastSequence,
            historyRead { requests().toList() }?.let(::immutableList) ?: immutableList(requestRecords.values),
            historyRead { preparations().toList() }?.let(::immutableList) ?: immutableList(preparations.values),
        )
    }

    internal fun checkpoint(
        matching: MatchingCheckpoint,
        completions: List<TradingState.Completion>,
    ): TradingCheckpoint {
        checkUsable()
        check(historyStore != null) { "live checkpoint requires exact durable history" }
        check(historyBatches.isEmpty() && durableHistoryOrdinal == lastHistoryOrdinal) { "checkpoint history is not durable" }
        check(currentReservations.values.all { it.status == OrderReservationStatus.ACTIVE }) { "checkpoint retained terminal state" }
        return TradingCheckpoint(
            balances.values.toList(),
            usedBalances.map { TradingBalanceKey(it.userId, it.assetId) },
            records.values.sortedBy { it.sequence },
            currentReservations.values.toList(),
            pendingByMarket.values
                .flatMap { it }
                .map { it.sequence }
                .sorted(),
            completions,
            lastSequence,
            lastHistoryOrdinal,
            matching,
        )
    }

    internal fun requireEmptyForRestore() {
        checkUsable()
        check(
            !checkpointRestored && lastSequence == 0L && lastHistoryOrdinal == 0L && balances.isEmpty() &&
                records.isEmpty() && currentReservations.isEmpty() && requestRecords.isEmpty() && preparations.isEmpty() &&
                pendingByMarket.isEmpty() && historyBatches.isEmpty() && usedBalances.isEmpty(),
        ) { "trading checkpoint requires an empty state" }
    }

    /** All checks run before any live-state collection is changed. The caller validates matching relationships too. */
    internal fun validateCheckpointForRestore(checkpoint: TradingCheckpoint) {
        requireEmptyForRestore()
        val store = checkNotNull(historyStore) { "live checkpoint requires exact durable history" }
        require(checkpoint.historyOrdinal >= 0 && checkpoint.lastAdmissionSequence in 0..checkpoint.historyOrdinal) {
            "invalid checkpoint admission or history sequence"
        }
        check(store.durableOrdinal == checkpoint.historyOrdinal) { "checkpoint and restored history ordinals differ" }
        check(store.flush() == checkpoint.historyOrdinal) { "restored history has unacknowledged transitions beyond checkpoint" }
        val balanceKeys = hashSetOf<TradingBalanceKey>()
        for (balance in checkpoint.balances) {
            require(balanceKeys.add(TradingBalanceKey(balance.userId, balance.assetId))) { "duplicate checkpoint balance" }
        }
        require(checkpoint.usedBalances.size == checkpoint.usedBalances.toSet().size) { "duplicate checkpoint used balance" }
        require(checkpoint.usedBalances.toSet() == balanceKeys) { "checkpoint used balances must match all touched balances" }
        val sequenceKeys = hashSetOf<Long>()
        val orderKeys = hashSetOf<OrderKey>()
        var previousSequence = 0L
        for (record in checkpoint.admissions) {
            require(record.sequence in 1..checkpoint.lastAdmissionSequence && sequenceKeys.add(record.sequence)) {
                "invalid or duplicate checkpoint admission sequence"
            }
            require(record.sequence > previousSequence) { "checkpoint admissions are not ordered" }
            previousSequence = record.sequence
            record.candidate.validate()
            val order = record.candidate.order
            require(orderKeys.add(OrderKey(order.marketId, order.orderId))) { "duplicate checkpoint order" }
            val historical = checkNotNull(store.findAdmission(record.sequence)) { "checkpoint admission is absent from history" }
            requireSameAdmission(record, historical)
            val indexed = checkNotNull(store.findAdmission(order.marketId, order.orderId)) { "checkpoint order index is absent" }
            requireSameAdmission(record, indexed)
        }
        val reservationKeys = hashSetOf<OrderKey>()
        for (reservation in checkpoint.reservations) {
            require(reservation.status == OrderReservationStatus.ACTIVE) { "checkpoint reservation is not active" }
            require(reservationKeys.add(OrderKey(reservation.marketId, reservation.orderId))) { "duplicate checkpoint reservation" }
        }
        require(orderKeys == reservationKeys) { "checkpoint admissions and reservations differ" }
        var lastPending = 0L
        for (sequence in checkpoint.pendingAdmissionSequences) {
            require(sequence > lastPending && sequence in sequenceKeys) { "invalid checkpoint pending order sequence" }
            require(store.findCompletion(sequence) == null) { "checkpoint pending order is already completed in history" }
            lastPending = sequence
        }
    }

    /** Called only after the common owner has checked the entire checkpoint. */
    internal fun applyCheckpoint(checkpoint: TradingCheckpoint) {
        requireEmptyForRestore()
        isBlocked = true
        for (balance in checkpoint.balances) balances[BalanceKey(balance.userId, balance.assetId)] = balance
        for (key in checkpoint.usedBalances) usedBalances.add(BalanceKey(key.userId, key.assetId))
        val pending = checkpoint.pendingAdmissionSequences.toHashSet()
        for (record in checkpoint.admissions) {
            val order = record.candidate.order
            records[OrderKey(order.marketId, order.orderId)] = record
            val index = record.sequence - 1
            val segmentKey = index / ADMISSION_SEQUENCE_SEGMENT_SIZE
            val segment = recordsBySequence.getOrPut(segmentKey) { MutableList(ADMISSION_SEQUENCE_SEGMENT_SIZE) { null } }
            segment[(index % ADMISSION_SEQUENCE_SEGMENT_SIZE).toInt()] = record
            sequenceSegmentSizes[segmentKey] = (sequenceSegmentSizes[segmentKey] ?: 0) + 1
            if (record.sequence in pending) pendingByMarket.getOrPut(order.marketId) { ArrayDeque() }.addLast(record)
        }
        for (reservation in checkpoint.reservations) {
            currentReservations[OrderKey(reservation.marketId, reservation.orderId)] = reservation
        }
        lastSequence = checkpoint.lastAdmissionSequence
        lastHistoryOrdinal = checkpoint.historyOrdinal
        durableHistoryOrdinal = checkpoint.historyOrdinal
        checkpointRestored = true
        isBlocked = false
    }

    fun pendingOrders(marketId: MarketId): List<Record> {
        checkUsable()
        return immutableList(pendingByMarket[marketId].orEmpty())
    }

    private fun prepare(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): Result {
        val orderKey = OrderKey(order.marketId, order.orderId)
        if (orderKey in records ||
            historyRead { findAdmission(order.marketId, order.orderId) } != null
        ) {
            return Result.Rejected(OrderAdmissionResult.Reason.DUPLICATE_ORDER)
        }

        val assetId = if (order.side == Side.BUY) market.quoteAssetId else market.baseAssetId
        val balanceKey = BalanceKey(order.userId, assetId)
        val currentBalance = balances[balanceKey] ?: Balance(order.userId, assetId, Amount.ZERO, Amount.ZERO)
        val calculation = calculator.calculate(order, market, feePolicySnapshot, currentBalance, ledgerTransactionId, occurredAt)
        if (calculation is OrderAdmissionResult.Rejected) return Result.Rejected(calculation.reason, calculation.insufficientBalance)

        val candidate = (calculation as OrderAdmissionResult.Calculated).candidate
        check(candidate.hasPreviousBalance(currentBalance)) { "후보가 읽은 잔고와 현재 잔고가 다릅니다" }
        check(candidate.order == order && candidate.market == market && candidate.feePolicySnapshot == feePolicySnapshot) {
            "후보의 주문·마켓·정책 연결이 다릅니다"
        }
        checkUnusedTransaction(candidate.reserveTransaction)
        return Result.Accepted(Record(Math.addExact(lastSequence, 1), candidate))
    }

    private fun applyAccepted(
        record: Record,
        request: RequestRecord? = null,
    ) {
        val candidate = record.candidate
        val nextBalance = candidate.nextBalance
        val balanceKey = BalanceKey(candidate.order.userId, nextBalance.assetId)
        val orderKey = OrderKey(candidate.order.marketId, candidate.order.orderId)

        // 최초 결과도 이 구간에서 기록한다. 어느 쓰기든 실패하면 차단 상태가 남는다.
        isBlocked = true
        balances[balanceKey] = nextBalance
        records[orderKey] = record
        val sequenceIndex = record.sequence - 1
        val segmentKey = sequenceIndex / ADMISSION_SEQUENCE_SEGMENT_SIZE
        val segmentOffset = (sequenceIndex % ADMISSION_SEQUENCE_SEGMENT_SIZE).toInt()
        val segment =
            recordsBySequence.getOrPut(segmentKey) {
                MutableList(ADMISSION_SEQUENCE_SEGMENT_SIZE) { null }
            }
        segment[segmentOffset] = record
        if (historyStore != null) sequenceSegmentSizes[segmentKey] = (sequenceSegmentSizes[segmentKey] ?: 0) + 1
        pendingByMarket.getOrPut(candidate.order.marketId) { ArrayDeque() }.addLast(record)
        currentReservations[orderKey] = candidate.reservation
        ledgerTransactions.add(candidate.reserveTransaction)
        usedBalances.add(balanceKey)
        transactionIds.add(candidate.reserveTransaction.ledgerTransactionId)
        sourceEventIds.add(candidate.reserveTransaction.sourceEventId)
        lastSequence = record.sequence
        if (request != null) {
            requestRecords[RequestKey(request.order.userId, request.requestId)] = request
        }
        if (historyStore != null) {
            appendHistory(
                TradingHistoryBatch(
                    nextHistoryOrdinal(),
                    request = request,
                    admission = record,
                    reservations = listOf(candidate.reservation),
                    ledgerTransactions = listOf(candidate.reserveTransaction),
                ),
            )
        }
        isBlocked = false
    }

    private fun rememberRejected(request: RequestRecord) {
        val key = RequestKey(request.order.userId, request.requestId)
        // 정상 거절도 요청 이력은 바뀐다. 기록이 끝나지 않으면 첫 답을 신뢰할 수 없다.
        isBlocked = true
        requestRecords[key] = request
        if (historyStore != null) appendHistory(TradingHistoryBatch(nextHistoryOrdinal(), request = request))
        isBlocked = false
    }

    // 공통 소유자만 사용하는 참여 계약. 최초 기록을 현재 예약으로 덮어쓰지 않는다.
    internal fun accepted(sequence: Long): Record? {
        checkUsable()
        if (sequence <= 0) return null
        val sequenceIndex = sequence - 1
        val segmentKey = sequenceIndex / ADMISSION_SEQUENCE_SEGMENT_SIZE
        val segmentOffset = (sequenceIndex % ADMISSION_SEQUENCE_SEGMENT_SIZE).toInt()
        return recordsBySequence[segmentKey]?.get(segmentOffset) ?: historyRead { findAdmission(sequence) }
    }

    internal fun accepted(
        marketId: MarketId,
        orderId: OrderId,
    ): Record {
        checkUsable()
        return checkNotNull(records[OrderKey(marketId, orderId)] ?: historyRead { findAdmission(marketId, orderId) }) { "승인 원본이 없습니다" }
    }

    internal fun firstPending(marketId: MarketId): Record? {
        checkUsable()
        return pendingByMarket[marketId]?.firstOrNull()
    }

    internal fun currentReservation(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation {
        checkUsable()
        return checkNotNull(currentReservations[OrderKey(marketId, orderId)]) { "현재 예약이 없습니다" }
    }

    internal fun currentBalance(
        userId: UserId,
        assetId: AssetId,
    ): Balance {
        checkUsable()
        return balances[BalanceKey(userId, assetId)] ?: Balance(userId, assetId, Amount.ZERO, Amount.ZERO)
    }

    internal fun reservations(): List<OrderReservation> {
        checkUsable()
        return historyRead { reservations().toList() }?.let(::immutableList) ?: immutableList(currentReservations.values)
    }

    internal fun transactions(): List<LedgerTransaction> {
        checkUsable()
        return historyRead { transactions().toList() }?.let(::immutableList) ?: immutableList(ledgerTransactions)
    }

    private fun validateSettlement(candidate: OrderSettlementCandidate) {
        checkUsable()
        candidate.balanceChanges.forEach {
            check(currentBalance(it.previous.userId, it.previous.assetId) == it.previous) { "정산 기준 잔고가 다릅니다" }
            check(it.next.userId == it.previous.userId && it.next.assetId == it.previous.assetId)
        }
        candidate.reservationChanges.forEach {
            check(currentReservation(it.previous.marketId, it.previous.orderId) == it.previous) { "정산 기준 예약이 다릅니다" }
            check(it.next.marketId == it.previous.marketId && it.next.orderId == it.previous.orderId)
        }
        if (candidate.settlements.size == 1) {
            checkUnusedTransaction(candidate.settlements[0].transaction)
        } else if (candidate.settlements.size > 1) {
            val batchTransactions = hashSetOf<String>()
            val batchEvents = hashSetOf<String>()
            candidate.settlements.forEach {
                checkUnusedTransaction(it.transaction)
                check(batchTransactions.add(it.transaction.ledgerTransactionId)) { "정산 묶음의 거래 ID가 중복됐습니다" }
                check(batchEvents.add(it.transaction.sourceEventId)) { "정산 묶음의 사건 ID가 중복됐습니다" }
            }
        }
    }

    internal class PreparedSettlement internal constructor(
        internal val candidate: OrderSettlementCandidate,
    )

    /** 자금 검증 뒤 정상 입구를 잠가 반영 시점까지 기준 잔고·예약·원장 이력을 보존한다. */
    internal fun prepareSettlement(candidate: OrderSettlementCandidate): PreparedSettlement {
        validateSettlement(candidate)
        val preparation = PreparedSettlement(candidate)
        preparedSettlement = preparation
        isBlocked = true
        return preparation
    }

    /** 이 소유자가 발급해 아직 사용하지 않은 검증 결과만 한 번 반영한다. 실패하면 차단을 유지한다. */
    internal fun applySettlement(
        preparation: PreparedSettlement,
        beforeApply: () -> Unit,
    ) {
        check(isBlocked && preparedSettlement === preparation) { "이 상태의 미사용 정산 준비가 아닙니다" }
        preparedSettlement = null
        val candidate = preparation.candidate
        beforeApply()
        candidate.balanceChanges.forEach {
            val key = BalanceKey(it.next.userId, it.next.assetId)
            balances[key] = it.next
            usedBalances.add(key)
        }
        candidate.reservationChanges.forEach { currentReservations[OrderKey(it.next.marketId, it.next.orderId)] = it.next }
        candidate.settlements.forEach {
            transactionIds.add(it.transaction.ledgerTransactionId)
            sourceEventIds.add(it.transaction.sourceEventId)
            ledgerTransactions.add(it.transaction)
        }
        isBlocked = false
    }

    internal fun completePending(record: Record) {
        checkUsable()
        val pending = checkNotNull(pendingByMarket[record.candidate.order.marketId])
        check(pending.first() === record) { "처리할 접수 순서가 다릅니다" }
        pending.removeFirst()
    }

    internal fun block() {
        preparedSettlement = null
        isBlocked = true
    }

    internal fun completion(sequence: Long): TradingState.Completion? = historyRead { findCompletion(sequence) }

    internal fun completionHistory(): List<TradingState.Completion>? = historyRead { completions().toList() }

    internal fun retainedAdmission(
        marketId: MarketId,
        orderId: OrderId,
    ): Record? = records[OrderKey(marketId, orderId)]

    internal fun isActive(
        marketId: MarketId,
        orderId: OrderId,
    ): Boolean = currentReservations[OrderKey(marketId, orderId)]?.status == OrderReservationStatus.ACTIVE

    internal fun recordCompletion(completion: TradingState.Completion) {
        if (historyStore == null) return
        isBlocked = true
        appendHistory(
            TradingHistoryBatch(
                nextHistoryOrdinal(),
                completion = completion,
                reservations = completion.settlement.reservationChanges.map { it.next },
                ledgerTransactions = completion.settlement.settlements.map { it.transaction },
            ),
        )
        isBlocked = false
    }

    internal fun prepareHistoryCapacity() {
        if (historyStore == null) return
        try {
            drainHistory()
            if (historyBatches.size >= historyBatchLimit) {
                // One acknowledged prefix frees capacity; later writes may continue in parallel.
                val target = historyBatches.first().ordinal
                val durable = historyStore.awaitDurable(target)
                check(durable in target..lastHistoryOrdinal) { "invalid history capacity acknowledgement" }
                drainHistory()
                check(historyBatches.size < historyBatchLimit) { "history staging did not drain" }
            }
        } catch (failure: Throwable) {
            isBlocked = true
            throw failure
        }
    }

    /** Explicit durability barrier, including when no further commands arrive. */
    fun flushHistory() {
        checkUsable()
        val store = historyStore ?: return
        try {
            val durable = store.flush()
            check(durable == lastHistoryOrdinal) { "history flush did not acknowledge all appends" }
            drainHistory()
        } catch (failure: Throwable) {
            isBlocked = true
            throw failure
        }
    }

    fun historyRetention(): TradingHistoryRetention {
        checkUsable()
        return TradingHistoryRetention(
            lastHistoryOrdinal,
            durableHistoryOrdinal,
            historyBatches.size,
            requestRecords.size,
            records.size,
            currentReservations.size,
            ledgerTransactions.size,
            transactionIds.size,
            sourceEventIds.size,
            preparations.size,
            pendingByMarket.values.sumOf { it.size },
            currentReservations.values.count { it.status == OrderReservationStatus.ACTIVE },
        )
    }

    private fun nextHistoryOrdinal(): Long = if (historyStore == null) 0L else Math.addExact(lastHistoryOrdinal, 1L)

    private fun appendHistory(batch: TradingHistoryBatch) {
        val store = historyStore ?: return
        try {
            check(batch.ordinal == Math.addExact(lastHistoryOrdinal, 1L))
            batch.admission?.let { record ->
                val order = record.candidate.order
                check(records[OrderKey(order.marketId, order.orderId)] === record) { "history admission is not the live original" }
            }
            batch.completion?.let { completion ->
                val original = completion.admission
                val order = original.candidate.order
                check(records[OrderKey(order.marketId, order.orderId)] === original) { "history completion is not the live original" }
            }
            val reservationSequences =
                batch.reservations.map { reservation ->
                    checkNotNull(records[OrderKey(reservation.marketId, reservation.orderId)]) {
                        "history reservation has no live original"
                    }.sequence
                }
            store.appendPrepared(PreparedTradingHistoryBatch(store, batch, reservationSequences))
            for (reservation in batch.reservations) {
                if (reservation.status != OrderReservationStatus.ACTIVE) {
                    terminalOrdinals[OrderKey(reservation.marketId, reservation.orderId)] = batch.ordinal
                }
            }
            lastHistoryOrdinal = batch.ordinal
            historyBatches.addLast(batch)
            drainHistory()
        } catch (failure: Throwable) {
            isBlocked = true
            throw failure
        }
    }

    private fun drainHistory() {
        val store = historyStore ?: return
        val durable = store.durableOrdinal
        check(durable in durableHistoryOrdinal..lastHistoryOrdinal) { "invalid history durability acknowledgement" }
        durableHistoryOrdinal = durable
        while (historyBatches.isNotEmpty() && historyBatches.first().ordinal <= durable) {
            val batch = historyBatches.first()
            // The owner trims matching state before admission references are released.
            onHistoryEvicted(batch)
            batch.request?.let { requestRecords.remove(RequestKey(it.order.userId, it.requestId)) }
            batch.preparation?.let { preparations.remove(it.result.opening.preparationId) }
            for (reservation in batch.reservations) {
                if (reservation.status != OrderReservationStatus.ACTIVE) {
                    records[OrderKey(reservation.marketId, reservation.orderId)]?.let(::evictTerminalAdmission)
                }
            }
            for (transaction in batch.ledgerTransactions) {
                transactionIds.remove(transaction.ledgerTransactionId)
                sourceEventIds.remove(transaction.sourceEventId)
            }
            for (transaction in batch.ledgerTransactions) {
                check(ledgerTransactions.first() === transaction) { "history ledger append order changed" }
                ledgerTransactions.removeAt(0)
            }
            historyBatches.removeFirst()
        }
    }

    private fun evictTerminalAdmission(record: Record) {
        val key = OrderKey(record.candidate.order.marketId, record.candidate.order.orderId)
        if (currentReservations[key]?.status == OrderReservationStatus.ACTIVE) return
        if ((terminalOrdinals[key] ?: Long.MAX_VALUE) > durableHistoryOrdinal) return
        // A terminal reservation only arises after this order has left the pending queue.
        records.remove(key)
        currentReservations.remove(key)
        terminalOrdinals.remove(key)
        val index = record.sequence - 1
        val segmentKey = index / ADMISSION_SEQUENCE_SEGMENT_SIZE
        recordsBySequence[segmentKey]?.let { segment ->
            segment[(index % ADMISSION_SEQUENCE_SEGMENT_SIZE).toInt()] = null
            val remaining = checkNotNull(sequenceSegmentSizes[segmentKey]) - 1
            if (remaining == 0) {
                recordsBySequence.remove(segmentKey)
                sequenceSegmentSizes.remove(segmentKey)
            } else {
                sequenceSegmentSizes[segmentKey] = remaining
            }
        }
    }

    private inline fun <T> historyRead(read: TradingHistoryStore.() -> T): T? {
        val store = historyStore ?: return null
        try {
            return store.read()
        } catch (failure: Throwable) {
            isBlocked = true
            throw failure
        }
    }

    private fun checkUsable() {
        check(!isBlocked) { "반영 중 오류로 접수 상태가 차단됐습니다" }
        check(!contextCheckInProgress) { "접수 문맥 검사에서 상태에 재진입할 수 없습니다" }
    }

    private fun checkUnusedTransaction(transaction: LedgerTransaction) {
        check(
            transaction.ledgerTransactionId !in transactionIds &&
                historyRead { containsTransactionId(transaction.ledgerTransactionId) } != true,
        ) { "원장 거래 ID가 이미 사용됐습니다" }
        check(
            transaction.sourceEventId !in sourceEventIds && historyRead {
                containsSourceEventId(transaction.sourceEventId)
            } != true,
        ) { "원장 사건 ID가 이미 사용됐습니다" }
    }

    private fun <T> immutableList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
}
