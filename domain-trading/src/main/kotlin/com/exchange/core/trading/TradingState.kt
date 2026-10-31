package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.UserId
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.matching.MatchingCommandResult
import com.exchange.core.matching.MatchingEngine
import com.exchange.core.matching.MatchingResultState
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.RestingOrderSnapshot
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.SubmitOrderPlan
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.Side
import java.time.Instant
import java.util.Base64
import java.util.Collections

enum class TradingExecutionPhase {
    MATCHING_CANDIDATE,
    SETTLEMENT_INPUT_VALIDATION,
    SETTLEMENT_CANDIDATE,
    RESULT_VALIDATION,
    APPLY,
    COMPLETION,
}

/**
 * 실행 단계의 선택적 관측 경계다. 시간·저장·집계는 호출자가 소유하며 도메인 상태에는 보관하지 않는다.
 * 콜백 실패는 거래 결과와 차단 판정에 영향을 주지 않는다. 관측자는 실행 상태를 변경하지 않아야 한다.
 */
interface TradingExecutionObserver {
    fun onPhaseStarted(
        admissionSequence: Long,
        phase: TradingExecutionPhase,
    ) {}

    fun onPhaseCompleted(
        admissionSequence: Long,
        phase: TradingExecutionPhase,
        failure: Throwable?,
    ) {}

    companion object {
        val NONE: TradingExecutionObserver = object : TradingExecutionObserver {}
    }
}

/**
 * 한 실행자가 접수·매칭·정산을 순서대로 처리하는 공통 소유자.
 * 계산을 모두 검사한 뒤 실제 상태를 쓰며, 쓰기 중 오류는 원상복구 대신 모든 정상 입구를 차단한다.
 * 복구 값의 검증·반영을 제공하며, Aeron 실행·저장 매체·로그 재생은 바깥 실행자가 소유한다.
 */
class TradingState(
    private val admissionCalculator: OrderAdmissionCalculator,
    private val settlementCalculator: OrderSettlementCalculator,
    historyStore: TradingHistoryStore? = null,
    historyBatchLimit: Int = 1_024,
    val boundedMode: Boolean = false,
) {
    private lateinit var admission: OrderAdmissionState

    init {
        require(!boundedMode || historyStore == null) { "bounded mode cannot own a historical index" }
        if (!boundedMode) {
            admission = OrderAdmissionState(emptyList(), admissionCalculator, historyStore, historyBatchLimit, ::evictHistory)
        }
    }

    private var engine = MatchingEngine()
    private var resultState = MatchingResultState()
    private val completions: MutableMap<Long, Completion> = linkedMapOf()
    private var bounded: BoundedTradingData? = null
    val lifecycle: TradingLifecycle
        get() = if (!boundedMode) TradingLifecycle.RUNNING else bounded?.lifecycle ?: TradingLifecycle.UNINITIALIZED

    var isBlocked: Boolean = false
        private set

    /** 최초 처리의 불변 결과다. 이후 maker 체결로 달라진 현재 예약과는 구분한다. */
    class Completion internal constructor(
        val admission: OrderAdmissionState.Record,
        val executionId: String,
        val occurredAt: Instant,
        val matchingResult: MatchingCommandResult,
        val settlement: OrderSettlementCandidate,
    )

    @ConsistentCopyVisibility
    data class Snapshot internal constructor(
        val balances: List<Balance>,
        val reservations: List<OrderReservation>,
        val pendingOrders: List<OrderAdmissionState.Record>,
        val completions: List<Completion>,
        val ledgerTransactions: List<LedgerTransaction>,
        val matchingResults: List<MatchingCommandResult>,
        val lastAdmissionSequence: Long,
        val requests: List<OrderAdmissionState.RequestRecord>,
        val preparations: List<OrderAdmissionState.PreparationRecord>,
    )

    fun prepareDevelopmentBalance(
        opening: OpeningBalance,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): OpeningBalanceResult =
        if (boundedMode) {
            prepareBoundedBalance(opening, occurredAt)
        } else {
            admissionCall {
                admission.prepareDevelopmentBalance(opening, ledgerTransactionId, occurredAt)
            }
        }

    fun admit(
        requestId: String,
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): OrderAdmissionState.Result =
        admissionCall { admission.admit(requestId, order, market, feePolicySnapshot, ledgerTransactionId, occurredAt) }

    fun admitWithContextCheck(
        requestId: String,
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
        checkContext: (knownRequest: Boolean) -> Boolean,
    ): OrderAdmissionState.Result? =
        admissionCall {
            admission.admitWithContextCheck(requestId, order, market, feePolicySnapshot, ledgerTransactionId, occurredAt, checkContext)
        }

    fun admit(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): OrderAdmissionState.Result = admissionCall { admission.admit(order, market, feePolicySnapshot, ledgerTransactionId, occurredAt) }

    fun hasRequest(
        userId: UserId,
        requestId: String,
    ): Boolean = admissionCall { admission.hasRequest(userId, requestId) }

    fun pendingOrders(marketId: MarketId): List<OrderAdmissionState.Record> {
        checkUsable()
        return admission.pendingOrders(marketId)
    }

    /** 완료를 먼저 재사용하므로 후속 이벤트가 순번을 전진시켜도 다시 계산하지 않는다. */
    @JvmOverloads
    fun executePending(
        admissionSequence: Long,
        occurredAt: Instant,
        observer: TradingExecutionObserver = TradingExecutionObserver.NONE,
    ): Completion {
        checkUsable()
        completions[admissionSequence]?.let { return it }
        admissionCall { admission.completion(admissionSequence) }?.let { return it }
        admissionCall { admission.prepareHistoryCapacity() }
        require(admissionSequence > 0) { "승인 순번은 양수여야 합니다" }
        val record = requireNotNull(admissionCall { admission.accepted(admissionSequence) }) { "승인된 주문이 없습니다" }
        val first = admission.firstPending(record.candidate.order.marketId)
        if (first != null) require(first.sequence == admissionSequence) { "같은 마켓의 앞선 대기 주문부터 처리해야 합니다" }

        // 대상 선택 오류와 달리, 승인된 주문의 내부 계산·관계 오류는 공통 상태를 불신하게 한다.
        try {
            check(first === record) { "승인 원본과 처리 대기가 연결되지 않습니다" }
            val original = record.candidate
            val order = original.order
            val command =
                SubmitOrderCommand(
                    order.marketId,
                    order.orderId,
                    order.userId,
                    order.side,
                    order.orderType,
                    order.timeInForce,
                    order.price,
                    order.quantity,
                )
            val plan =
                observedExecutionPhase(admissionSequence, TradingExecutionPhase.MATCHING_CANDIDATE, observer) {
                    engine.prepareSubmit(command)
                }
            val reservations =
                observedExecutionPhase(admissionSequence, TradingExecutionPhase.SETTLEMENT_INPUT_VALIDATION, observer) {
                    validateParticipants(record, plan)
                }
            val executions = ArrayList<SettlementExecution>(plan.fills.size)
            val settlement =
                observedExecutionPhase(admissionSequence, TradingExecutionPhase.SETTLEMENT_CANDIDATE, observer) {
                    val balances = participantBalances(reservations, original.market)
                    for (event in plan.events) {
                        if (event is TradeExecuted) executions.add(event.toSettlement(occurredAt))
                    }
                    settlementCalculator.calculate(order, original.market, executions, reservations, balances)
                }
            val executionId = "EXECUTE:v1:$admissionSequence"
            lateinit var preparedSettlement: OrderAdmissionState.PreparedSettlement
            lateinit var completedSettlement: OrderSettlementCandidate
            val registration =
                observedExecutionPhase(admissionSequence, TradingExecutionPhase.RESULT_VALIDATION, observer) {
                    validateJointResult(record, plan, settlement, executions)
                    check(resultState.find(order.marketId, executionId) == null) { "전체 완료 없이 매칭 결과만 존재합니다" }
                    check(engine.lastEngineSequence(order.marketId) == resultState.lastEngineSequence(order.marketId)) {
                        "주문장과 결과 이력의 이전 순번이 다릅니다"
                    }
                    val preparedRegistration =
                        resultState.prepareRegistration(
                            executionId,
                            command,
                            plan.events.size,
                            plan.firstEngineSequence,
                            plan.lastEngineSequence,
                            plan.events,
                        )
                    // 실제 쓰기 전에 이력 보관 공간도 준비한다. 조회는 당시 값만 복원한다.
                    completedSettlement = settlement.compactForHistory()
                    preparedSettlement = admission.prepareSettlement(settlement)
                    preparedRegistration
                }

            // 여기부터 실제 값이 바뀐다. 완료 기록까지 끝나기 전에 정상 사용을 허용하지 않는다.
            val matchingResult =
                observedExecutionPhase(admissionSequence, TradingExecutionPhase.APPLY, observer) {
                    isBlocked = true
                    admission.applySettlement(preparedSettlement) { engine.apply(plan) }
                    resultState.register(registration)
                }
            return observedExecutionPhase(admissionSequence, TradingExecutionPhase.COMPLETION, observer) {
                admission.completePending(record)
                val completion = Completion(record, executionId, occurredAt, matchingResult, completedSettlement)
                completions[admissionSequence] = completion
                admission.recordCompletion(completion)
                isBlocked = false
                completion
            }
        } catch (failure: Throwable) {
            isBlocked = true
            admission.block()
            throw failure
        }
    }

    private inline fun <T> observedExecutionPhase(
        admissionSequence: Long,
        phase: TradingExecutionPhase,
        observer: TradingExecutionObserver,
        action: () -> T,
    ): T {
        if (observer === TradingExecutionObserver.NONE) return action()
        try {
            observer.onPhaseStarted(admissionSequence, phase)
        } catch (_: Exception) {
            // 관측 실패는 금융 상태를 변경하거나 정상 실행을 차단하지 않는다.
        }
        var failure: Throwable? = null
        try {
            return action()
        } catch (businessFailure: Throwable) {
            failure = businessFailure
            throw businessFailure
        } finally {
            try {
                observer.onPhaseCompleted(admissionSequence, phase, failure)
            } catch (_: Exception) {
                // 원래의 업무 실패 또는 성공을 그대로 유지한다.
            } catch (observerFailure: Error) {
                // VM 오류는 숨기지 않는다. 업무 실패가 있으면 원본을 유지하며 함께 전달한다.
                val businessFailure = failure
                if (businessFailure == null) throw observerFailure
                // 검증 실패가 분류되어 있어도 치명적인 observer 오류를 복구 가능한 격리 상태로 바꾸어서는 안 된다.
                isBlocked = true
                if (observerFailure !== businessFailure) businessFailure.addSuppressed(observerFailure)
            }
        }
    }

    /** 정상 조회만 복사한다. 주문 실행 중에는 전체 상태 snapshot을 만들지 않는다. */
    fun snapshot(): Snapshot =
        admissionCall {
            val current = admission.snapshot()
            val coldHistory = admission.completionHistory()
            val history = if (coldHistory == null) immutableList(completions.values) else immutableList(coldHistory)
            Snapshot(
                current.balances,
                admission.reservations(),
                current.pendingOrders,
                history,
                admission.transactions(),
                if (coldHistory == null) resultState.snapshot().results else immutableList(history.map { it.matchingResult }),
                current.lastSequence,
                current.requests,
                current.preparations,
            )
        }

    /** 이력의 영속 저장을 기다린 뒤 저장이 확인된 과거 객체를 해제한다. */
    fun flushHistory() = admissionCall { admission.flushHistory() }

    fun historyRetention(): TradingHistoryRetention =
        admissionCall { admission.historyRetention().copy(retainedCompletions = completions.size) }

    /** 한도가 있는 대기 공간의 연속 구간을 flush한 뒤 현재 잔고·주문·활성 최초 결과만 복사한다. */
    fun checkpoint(): TradingCheckpoint =
        admissionCall {
            admission.flushHistory()
            admission.checkpoint(engine.activeCheckpoint(), completions.values.sortedBy { it.admission.sequence })
        }

    /**
     * 외부 Snapshot reader가 먼저 이 빈 소유자의 저장소에 정확한 과거 이력을 복원한다.
     * 검증이 실패하면 소유자를 차단하며, 일부만 복원한 상태를 정상 조회에 공개하지 않는다.
     */
    fun restoreCheckpoint(checkpoint: TradingCheckpoint) {
        checkUsable()
        try {
            admission.validateCheckpointForRestore(checkpoint)
            check(completions.isEmpty() && resultState.snapshot().results.isEmpty()) { "trading checkpoint requires an empty owner" }
            val restoredCompletions = validateCheckpoint(checkpoint)
            val restoredEngine = MatchingEngine().also { it.restoreActiveCheckpoint(checkpoint.matching) }
            val restoredResults =
                MatchingResultState().also {
                    it.restoreActiveCheckpoint(
                        checkpoint.matching.markets.associate { market -> market.marketId to market.lastEngineSequence },
                        restoredCompletions.values.map { completion -> completion.matchingResult },
                    )
                }
            isBlocked = true
            admission.applyCheckpoint(checkpoint)
            engine = restoredEngine
            resultState = restoredResults
            completions.putAll(restoredCompletions)
            isBlocked = false
        } catch (failure: Throwable) {
            isBlocked = true
            admission.block()
            throw failure
        }
    }

    private fun validateCheckpoint(checkpoint: TradingCheckpoint): Map<Long, Completion> {
        val records = checkpoint.admissions.associateBy { it.sequence }
        val orderRecords = checkpoint.admissions.associateBy { it.candidate.order.marketId to it.candidate.order.orderId }
        val reservations = checkpoint.reservations.associateBy { it.marketId to it.orderId }
        val pending = checkpoint.pendingAdmissionSequences.toHashSet()
        val restoredCompletions = linkedMapOf<Long, Completion>()
        val markets = linkedMapOf<MarketId, MarketDefinition>()
        val expectedHold = hashMapOf<TradingBalanceKey, Long>()
        for (record in checkpoint.admissions) {
            val candidate = record.candidate
            val previousMarket = markets.putIfAbsent(candidate.market.marketId, candidate.market)
            require(previousMarket == null || previousMarket == candidate.market) { "checkpoint market definitions differ" }
            val reservation = reservations.getValue(candidate.order.marketId to candidate.order.orderId)
            validateReservation(record, reservation)
            settlementCalculator.validateReservation(candidate.market, reservation)
            if (record.sequence in pending) {
                require(reservation == candidate.reservation) { "checkpoint pending reservation differs from admission" }
            }
            val balanceKey = TradingBalanceKey(reservation.userId, reservation.assetId)
            expectedHold[balanceKey] = Math.addExact(expectedHold[balanceKey] ?: 0L, reservation.remainingAmount.value)
        }
        for (balance in checkpoint.balances) {
            val key = TradingBalanceKey(balance.userId, balance.assetId)
            require(balance.hold.value == (expectedHold.remove(key) ?: 0L)) { "checkpoint hold does not match active reservations" }
        }
        require(expectedHold.isEmpty()) { "checkpoint reservation balance is missing" }
        for (completion in checkpoint.completions) {
            val record = requireNotNull(records[completion.admission.sequence]) { "checkpoint completion has no live admission" }
            require(record.sequence !in pending) { "checkpoint order is both pending and complete" }
            requireSameAdmission(record, completion.admission)
            val historical = checkNotNull(admission.completion(record.sequence)) { "checkpoint completion is absent from history" }
            requireSameCompletion(completion, historical)
            val canonical =
                TradingHistoryValues.completion(
                    record,
                    completion.executionId,
                    completion.occurredAt,
                    completion.matchingResult,
                    completion.settlement,
                )
            require(restoredCompletions.put(record.sequence, canonical) == null) { "duplicate checkpoint completion" }
        }
        require(restoredCompletions.keys + pending == records.keys) { "checkpoint admission has no execution status" }
        val resting = hashSetOf<Long>()
        for (market in checkpoint.matching.markets) {
            val lastAtPrice = hashMapOf<Pair<Side, com.exchange.core.common.Price>, Long>()
            for (order in market.orders) {
                val record = requireNotNull(orderRecords[market.marketId to order.orderId]) { "checkpoint book order has no admission" }
                require(
                    record.sequence in restoredCompletions && resting.add(record.sequence),
                ) { "checkpoint book order is not a distinct maker" }
                val reservation = reservations.getValue(market.marketId to order.orderId)
                require(
                    order.userId == reservation.userId && order.side == reservation.side && order.price == reservation.limitPrice &&
                        order.originalQuantity == reservation.initialQuantity && order.remainingQuantity == reservation.remainingQuantity,
                ) { "checkpoint book and reservation differ" }
                val key = order.side to order.price
                require(record.sequence > (lastAtPrice[key] ?: 0L)) { "checkpoint book FIFO differs from admission order" }
                lastAtPrice[key] = record.sequence
            }
            val firstPending =
                checkpoint.admissions.firstOrNull {
                    it.sequence in pending && it.candidate.order.marketId == market.marketId
                }
            require(
                firstPending == null ||
                    restoredCompletions.values.none {
                        it.admission.candidate.order.marketId == market.marketId && it.admission.sequence > firstPending.sequence
                    },
            ) { "checkpoint pending order precedes a completed order in its market" }
        }
        require(resting == restoredCompletions.keys) { "checkpoint active completions and resting orders differ" }
        return restoredCompletions
    }

    private fun evictHistory(batch: TradingHistoryBatch) {
        for (reservation in batch.reservations) {
            if (reservation.status != OrderReservationStatus.ACTIVE) {
                val record = admission.retainedAdmission(reservation.marketId, reservation.orderId)
                if (record != null) {
                    completions.remove(record.sequence)?.let { completion ->
                        resultState.releaseHistory(reservation.marketId, completion.executionId, reservation.orderId)
                    }
                    engine.releaseTerminalOrderIdentity(reservation.marketId, reservation.orderId)
                }
            }
        }
    }

    fun book(marketId: MarketId): List<RestingOrderSnapshot> {
        checkUsable()
        return engine.restingOrders(marketId)
    }

    fun lastEngineSequence(marketId: MarketId): Long {
        checkUsable()
        return engine.lastEngineSequence(marketId)
    }

    private fun validateParticipants(
        record: OrderAdmissionState.Record,
        plan: SubmitOrderPlan,
    ): List<OrderReservation> {
        val original = record.candidate
        val taker = admission.currentReservation(original.order.marketId, original.order.orderId)
        check(taker == original.reservation) { "최초 매칭 전 예약이 최초 승인과 다릅니다" }
        validateReservation(record, taker)
        val reservations = mutableListOf(taker)
        for (fill in plan.fills) {
            val makerRecord = admission.accepted(original.order.marketId, fill.orderId)
            val maker = admission.currentReservation(original.order.marketId, fill.orderId)
            check(completions[makerRecord.sequence]?.admission === makerRecord) { "maker의 최초 처리가 완료되지 않았습니다" }
            check(makerRecord.candidate.market == original.market) { "maker와 taker의 마켓 정의가 다릅니다" }
            validateReservation(makerRecord, maker)
            check(
                maker.userId == fill.userId && maker.side == fill.side && maker.limitPrice == fill.price &&
                    maker.initialQuantity == fill.originalQuantity && maker.remainingQuantity == fill.remainingQuantity,
            ) { "주문장 maker와 현재 예약이 다릅니다" }
            reservations.add(maker)
        }
        // 체결이 있으면 정산 계산기가 참여자의 예약 합계와 현재 hold를 함께 검사한다.
        // 체결 없는 신규 대기 주문도 최초 예약을 실제로 보유하는지는 확인한다.
        if (plan.fills.isEmpty()) {
            settlementCalculator.validateReservation(original.market, taker)
            check(admission.currentBalance(taker.userId, taker.assetId).hold >= taker.remainingAmount) { "현재 hold가 예약보다 작습니다" }
        }
        return reservations
    }

    private fun validateReservation(
        record: OrderAdmissionState.Record,
        current: OrderReservation,
    ) {
        // 보관한 최초 원본의 손상도 차단한다. 검증은 비교용 분개를 만들지 않는다.
        record.candidate.validate()
        val original = record.candidate.reservation
        check(
            current.marketId == original.marketId && current.orderId == original.orderId && current.userId == original.userId &&
                current.side == original.side && current.assetId == original.assetId && current.limitPrice == original.limitPrice &&
                current.initialQuantity == original.initialQuantity && current.reservedAmount == original.reservedAmount &&
                current.initialFeeReserveAmount == original.initialFeeReserveAmount &&
                current.feePolicySnapshot == original.feePolicySnapshot,
        ) { "현재 예약의 원본·정책이 승인과 다릅니다" }
        check(current.status == OrderReservationStatus.ACTIVE) { "현재 예약이 활성 상태가 아닙니다" }
        // 현재 금액의 계산 검증은 이어지는 정산 계산기에서 한 번 수행한다.
    }

    private fun participantBalances(
        reservations: List<OrderReservation>,
        market: MarketDefinition,
    ): List<Balance> {
        val keys = linkedSetOf<Pair<UserId, AssetId>>()
        for (reservation in reservations) {
            keys.add(reservation.userId to market.baseAssetId)
            keys.add(reservation.userId to market.quoteAssetId)
        }
        return keys.map { admission.currentBalance(it.first, it.second) }
    }

    private fun validateJointResult(
        record: OrderAdmissionState.Record,
        plan: SubmitOrderPlan,
        candidate: OrderSettlementCandidate,
        executions: List<SettlementExecution>,
    ) {
        check(candidate.order == record.candidate.order && candidate.market == record.candidate.market)
        check(candidate.settlements.size == executions.size) { "정산 체결과 실제 매칭 개수가 다릅니다" }
        candidate.settlements.forEachIndexed { index, settlement ->
            check(settlement.execution == executions[index]) { "정산 체결과 실제 매칭 순서가 다릅니다" }
        }
        if (executions.isNotEmpty()) {
            val nextReservations =
                if (executions.size > 1) candidate.reservationChanges.associate { it.next.orderId to it.next } else null

            fun nextReservation(orderId: OrderId): OrderReservation {
                if (nextReservations != null) return nextReservations.getValue(orderId)
                // associate와 동일하게 마지막 키를 선택하고, 누락된 키는 getValue의 예외로 거절한다.
                for (index in candidate.reservationChanges.lastIndex downTo 0) {
                    val next = candidate.reservationChanges[index].next
                    if (next.orderId == orderId) return next
                }
                return emptyMap<OrderId, OrderReservation>().getValue(orderId)
            }

            plan.fills.forEach {
                check(
                    nextReservation(it.orderId).remainingQuantity.value == it.remainingQuantity.value - it.filledQuantity.value,
                ) {
                    "정산 후 maker 잔량이 주문장 후보와 다릅니다"
                }
            }
            val remaining = (plan.events.last() as? OrderEnteredBook)?.remainingQuantity?.value ?: 0L
            check(nextReservation(record.candidate.order.orderId).remainingQuantity.value == remaining) {
                "정산 후 taker 잔량이 주문장 후보와 다릅니다"
            }
        } else {
            check(candidate.balanceChanges.isEmpty() && candidate.reservationChanges.isEmpty())
        }
        candidate.settlements.forEach {
            val execution = it.execution
            val transaction = it.transaction
            check(transaction.transactionType == LedgerTransactionType.SETTLEMENT)
            check(transaction.ledgerTransactionId == execution.ledgerTransactionId && transaction.occurredAt == execution.occurredAt)
            check(transaction.sourceEventId == "MATCHING:${execution.marketId.value}:${execution.engineSequence}")
            check(
                it.makerPlan.updatedReservation.orderId == execution.makerOrderId &&
                    it.takerPlan.updatedReservation.orderId == execution.takerOrderId,
            )
        }
    }

    private fun TradeExecuted.toSettlement(occurredAt: Instant) =
        toSettlement(occurredAt, settlementTransactionId(marketId, engineSequence))

    private fun TradeExecuted.toSettlement(
        occurredAt: Instant,
        ledgerTransactionId: String,
    ) = SettlementExecution(
        marketId,
        engineSequence,
        makerOrderId,
        takerOrderId,
        makerUserId,
        takerUserId,
        side,
        price,
        quantity,
        ledgerTransactionId,
        occurredAt,
    )

    /** 명시적으로 선택한 최초 상태다. 이 모드의 소유자는 기존 이력 저장소를 생성하지 않는다. */
    fun initialize(manifest: TradingClusterManifest): InitializationReceipt {
        check(boundedMode && !isBlocked) { "bounded initialization requires a usable bounded owner" }
        bounded?.let {
            require(it.manifest == manifest) { "cluster manifest cannot change" }
            return InitializationReceipt.ALREADY_INITIALIZED
        }
        validateBoundedManifest(manifest)
        bounded = BoundedTradingData(manifest)
        return InitializationReceipt.INITIALIZED
    }

    private fun validateBoundedManifest(manifest: TradingClusterManifest) {
        require(manifest.markets.size <= manifest.limits.maxMarkets)
        val assets = manifest.markets.flatMap { listOf(it.market.baseAssetId, it.market.quoteAssetId) }.toSet()
        require(assets.size <= manifest.limits.maxAssets)
        for (context in manifest.markets) {
            require(context.feePolicySnapshot.productType == com.exchange.core.fee.FeeProductType.SPOT)
            require(admissionCalculator.validIdentifier(context.market.marketId.value))
            require(admissionCalculator.validIdentifier(context.market.baseAssetId.value, accountPart = true))
            require(admissionCalculator.validIdentifier(context.market.quoteAssetId.value, accountPart = true))
            require(tradingTextBytes(context.market.marketId.value) <= manifest.limits.maxIdentifierBytes)
            require(tradingTextBytes(context.market.baseAssetId.value) <= manifest.limits.maxIdentifierBytes)
            require(tradingTextBytes(context.market.quoteAssetId.value) <= manifest.limits.maxIdentifierBytes)
        }
    }

    fun rotateEpoch(
        userId: UserId,
        channel: Int,
        expectedEpoch: Long,
    ): EpochReceipt {
        val current = runningBounded()
        if (!admissionCalculator.validIdentifier(userId.value, accountPart = true) ||
            tradingTextBytes(userId.value) > current.manifest.limits.maxIdentifierBytes
        ) {
            return EpochReceipt(EpochStatus.INVALID_CHANNEL, 0)
        }
        if (userId !in current.accounts && current.accounts.size >= current.manifest.limits.maxAccounts) {
            return EpochReceipt(EpochStatus.RETRYABLE_CAPACITY, 0)
        }
        return boundedMutation {
            val result = current.inbox.rotateEpoch(userId, channel, expectedEpoch)
            if (result.status == EpochStatus.ISSUED || result.status == EpochStatus.ALREADY_ISSUED ||
                result.status == EpochStatus.SUPERSEDED
            ) {
                current.accounts.add(userId)
            }
            result
        }
    }

    fun acknowledgeReceipts(
        userId: UserId,
        channel: Int,
        epoch: Long,
        through: Long,
    ): AckReceipt {
        val current = runningBounded()
        return boundedMutation { current.inbox.acknowledge(userId, channel, epoch, through) }
    }

    fun admit(
        token: RequestToken,
        intent: OrderIntent,
        at: Instant,
    ): AdmissionReply {
        val current = runningBounded()
        when (val lookup = current.inbox.lookup(token, intent)) {
            is InboxLookup.Repeated -> return AdmissionReply.Recorded(lookup.receipt, newlyRecorded = false)
            is InboxLookup.Rejected -> return AdmissionReply.Refused(lookup.reason.name)
            InboxLookup.Ready -> Unit
        }
        val context = current.contexts[intent.marketId]
        if (context == null || intent.expectedConfigurationVersion != current.manifest.configurationVersion) {
            return AdmissionReply.Refused("EXECUTION_CONTEXT_MISMATCH")
        }
        val progress = current.markets.getValue(intent.marketId)
        val limits = current.manifest.limits
        val baseKey = TradingBalanceKey(token.userId, context.market.baseAssetId)
        val quoteKey = TradingBalanceKey(token.userId, context.market.quoteAssetId)
        val baseExists = baseKey in current.balances
        val quoteExists = quoteKey in current.balances
        val requiredKeys = (if (baseExists) 0 else 1) + (if (quoteExists) 0 else 1)
        if (current.liveBySequence.size >= limits.maxActiveOrders || progress.activeCount >= limits.maxMarketActiveOrders ||
            current.pendingCount >= limits.maxPendingOrders || progress.pending.size >= limits.maxMarketPendingOrders ||
            requiredKeys > limits.maxBalanceKeys - current.balances.size ||
            (token.userId !in current.accounts && current.accounts.size >= limits.maxAccounts)
        ) {
            return AdmissionReply.Refused("RETRYABLE_CAPACITY")
        }
        val sequence = Math.addExact(current.lastAdmissionSequence, 1L)
        val marketSequence = Math.addExact(progress.lastAssigned, 1L)
        val identifiers = TradingIdentifiers.admission(current.manifest.lineage, sequence)
        val input =
            OrderAdmissionInput(
                intent.marketId,
                identifiers.orderId,
                token.userId,
                intent.side,
                intent.orderType,
                intent.timeInForce,
                intent.price,
                intent.quantity,
            )
        val reservedKey = if (intent.side == Side.BUY) quoteKey else baseKey
        val balance = current.balances[reservedKey] ?: Balance(token.userId, reservedKey.assetId, Amount.ZERO, Amount.ZERO)
        val calculation =
            admissionCalculator.calculateTransition(
                input,
                context.market,
                context.feePolicySnapshot,
                balance,
            )
        if (calculation is AdmissionTransitionResult.Rejected) {
            val receipt =
                AdmissionReceipt(AdmissionCode.REJECTED, reason = calculation.reason, insufficientBalance = calculation.insufficientBalance)
            boundedMutation { current.inbox.remember(token, intent, receipt) }
            return AdmissionReply.Recorded(receipt, newlyRecorded = true)
        }
        val candidate = calculation as AdmissionTransitionResult.Calculated
        check(candidate.hasPreviousBalance(balance))
        val live = BoundedLiveOrder(sequence, marketSequence, input, candidate.reservation, LiveOrderPhase.PENDING)
        val receipt = AdmissionReceipt(AdmissionCode.ACCEPTED, input.orderId, sequence, marketSequence)
        boundedMutation {
            current.accounts.add(token.userId)
            if (!baseExists) current.balances[baseKey] = Balance(baseKey.userId, baseKey.assetId, Amount.ZERO, Amount.ZERO)
            if (!quoteExists) current.balances[quoteKey] = Balance(quoteKey.userId, quoteKey.assetId, Amount.ZERO, Amount.ZERO)
            current.usedBalances.add(baseKey)
            current.usedBalances.add(quoteKey)
            current.balances[reservedKey] = candidate.nextBalance
            current.liveBySequence[sequence] = live
            current.liveByOrder[input.marketId to input.orderId] = live
            progress.pending.addLast(live)
            progress.activeCount++
            current.pendingCount++
            progress.lastAssigned = marketSequence
            current.lastAdmissionSequence = sequence
            current.inbox.remember(token, intent, receipt)
        }
        return AdmissionReply.Recorded(receipt, newlyRecorded = true)
    }

    private fun prepareBoundedBalance(
        opening: OpeningBalance,
        at: Instant,
    ): OpeningBalanceResult {
        val current = runningBounded()
        current.preparations[opening.preparationId]?.let {
            if (it.opening !=
                opening
            ) {
                throw com.exchange.core.ledger.OpeningBalanceRequestConflictException(
                    "same preparation ID has different funding content",
                )
            }
            return it.copy(alreadyPrepared = true)
        }
        val limits = current.manifest.limits
        if (opening.assetId !in current.assets) throw TradingFundingInputException(TradingFundingRejection.UNKNOWN_ASSET)
        if (tradingTextBytes(opening.userId.value) > limits.maxIdentifierBytes ||
            tradingTextBytes(opening.preparationId) > limits.maxIdentifierBytes
        ) {
            throw TradingFundingInputException(TradingFundingRejection.INVALID_IDENTIFIER)
        }
        val key = TradingBalanceKey(opening.userId, opening.assetId)
        val old = current.balances[key] ?: Balance(opening.userId, opening.assetId, Amount.ZERO, Amount.ZERO)
        opening.requireUnused(old, key in current.usedBalances)
        val bytes = preparationBytes(opening)
        if (current.preparations.size >= limits.maxPreparations || bytes > limits.maxPreparationBytes - current.preparationBytes ||
            (key !in current.balances && current.balances.size >= limits.maxBalanceKeys) ||
            (opening.userId !in current.accounts && current.accounts.size >= limits.maxAccounts)
        ) {
            throw TradingCapacityException("development funding capacity exceeded")
        }
        val sequence = Math.addExact(current.lastPreparationSequence, 1L)
        val ledgerId = TradingIdentifiers.opening(current.manifest.lineage, sequence)
        val next = old.credit(opening.amount)
        opening.transaction(ledgerId, at) // 원장 항목 그래프를 보관하지 않고 같은 OPENING의 균형을 검증한다.
        val result = OpeningBalanceResult(opening, ledgerId, at, alreadyPrepared = false)
        boundedMutation {
            current.accounts.add(opening.userId)
            current.balances[key] = next
            current.usedBalances.add(key)
            current.preparations[opening.preparationId] = result
            current.preparationBytes = Math.addExact(current.preparationBytes, bytes)
            current.lastPreparationSequence = sequence
        }
        return result
    }

    @JvmOverloads
    fun executePending(
        command: ExecuteAdmission,
        at: Instant,
        observer: TradingExecutionObserver = TradingExecutionObserver.NONE,
    ): ExecutionReply {
        val current = runningBounded()
        require(command.lineage == current.manifest.lineage) { "execution lineage differs" }
        val context = requireNotNull(current.contexts[command.marketId]) { "execution market is not registered" }
        val progress = current.markets.getValue(command.marketId)
        require(command.marketAdmissionSequence > 0 && command.admissionSequence > 0)
        if (command.marketAdmissionSequence <= progress.executedThrough) return ExecutionReply.AlreadyPassed
        require(command.marketAdmissionSequence == Math.addExact(progress.executedThrough, 1L)) { "execution is out of order" }
        val live = requireNotNull(progress.pending.firstOrNull()) { "execution has no pending order" }
        require(live.admissionSequence == command.admissionSequence && live.marketAdmissionSequence == command.marketAdmissionSequence) {
            "execution target differs from first pending order"
        }
        var applying = false
        try {
            validateBoundedStage(TradingInvariantReason.PARTICIPANT_RELATION_INVALID, TradingExecutionPhase.SETTLEMENT_INPUT_VALIDATION) {
                check(live.phase == LiveOrderPhase.PENDING)
            }
            val order = live.order
            val matchingCommand =
                SubmitOrderCommand(
                    order.marketId,
                    order.orderId,
                    order.userId,
                    order.side,
                    order.orderType,
                    order.timeInForce,
                    order.price,
                    order.quantity,
                )
            val plan =
                observedExecutionPhase(command.admissionSequence, TradingExecutionPhase.MATCHING_CANDIDATE, observer) {
                    validateBoundedStage(TradingInvariantReason.MATCHING_CANDIDATE_INVALID, TradingExecutionPhase.MATCHING_CANDIDATE) {
                        engine.prepareSubmit(matchingCommand)
                    }
                }
            val restingQuantity =
                (plan.events.lastOrNull() as? OrderEnteredBook)?.remainingQuantity ?: com.exchange.core.common.Quantity.ZERO
            val participants = ArrayList<OrderReservation>(plan.fills.size + 1)
            val participantBalances =
                observedExecutionPhase(command.admissionSequence, TradingExecutionPhase.SETTLEMENT_INPUT_VALIDATION, observer) {
                    validateBoundedStage(
                        TradingInvariantReason.PARTICIPANT_RELATION_INVALID,
                        TradingExecutionPhase.SETTLEMENT_INPUT_VALIDATION,
                    ) {
                        validateBoundedReservationRelationship(live, context)
                        if (plan.fills.isEmpty()) settlementCalculator.validateReservation(context.market, live.reservation)
                        check(
                            live.reservation.remainingQuantity == order.quantity &&
                                live.reservation.remainingAmount == live.reservation.reservedAmount,
                        )
                        participants.add(live.reservation)
                        for (fill in plan.fills) {
                            val maker = checkNotNull(current.liveByOrder[order.marketId to fill.orderId]) { "book maker has no live order" }
                            check(maker.phase == LiveOrderPhase.RESTING) { "book maker has not completed first execution" }
                            validateBoundedReservationRelationship(maker, context)
                            check(
                                maker.order.userId == fill.userId && maker.order.side == fill.side && maker.order.price == fill.price &&
                                    maker.order.quantity == fill.originalQuantity &&
                                    maker.reservation.remainingQuantity == fill.remainingQuantity,
                            )
                            participants.add(maker.reservation)
                        }
                    }
                    validateBoundedStage(
                        TradingInvariantReason.PARTICIPANT_RELATION_INVALID,
                        TradingExecutionPhase.SETTLEMENT_INPUT_VALIDATION,
                    ) {
                        if (plan.fills.isEmpty()) {
                            validateBoundedNoFillBalances(current, live, context)
                            emptyList()
                        } else {
                            val keys = linkedSetOf<TradingBalanceKey>()
                            for (reservation in participants) {
                                keys.add(TradingBalanceKey(reservation.userId, context.market.baseAssetId))
                                keys.add(TradingBalanceKey(reservation.userId, context.market.quoteAssetId))
                            }
                            keys.map { checkNotNull(current.balances[it]) { "participant balance capacity was not reserved" } }
                        }
                    }
                }
            lateinit var executions: List<SettlementExecution>
            val settlement =
                observedExecutionPhase(command.admissionSequence, TradingExecutionPhase.SETTLEMENT_CANDIDATE, observer) {
                    executions = boundedSettlementExecutions(current, context, plan, at)
                    validateBoundedStage(
                        TradingInvariantReason.SETTLEMENT_CALCULATION_INVALID,
                        TradingExecutionPhase.SETTLEMENT_CANDIDATE,
                    ) {
                        try {
                            if (executions.isEmpty()) {
                                settlementCalculator.calculateEmpty(order, context.market)
                            } else {
                                settlementCalculator.calculate(order, context.market, executions, participants, participantBalances)
                            }
                        } catch (_: InitialReservationValidationFailure) {
                            throw TradingInvariantViolation(
                                TradingInvariantReason.PARTICIPANT_RELATION_INVALID,
                                TradingExecutionPhase.SETTLEMENT_INPUT_VALIDATION,
                            )
                        }
                    }
                }
            val feeAsset = context.market.quoteAssetId
            val nextFeeRevenue =
                observedExecutionPhase(command.admissionSequence, TradingExecutionPhase.RESULT_VALIDATION, observer) {
                    validateBoundedExecutionResult(current, live, matchingCommand, plan, settlement, executions)
                    validateBoundedFeeRevenue(current, settlement, feeAsset)
                }
            val receipt =
                ExecutionReceipt(
                    live.admissionSequence,
                    live.marketAdmissionSequence,
                    order.marketId,
                    plan.lastEngineSequence,
                    executions.size,
                    !restingQuantity.isZero(),
                )
            applying = true
            observedExecutionPhase(command.admissionSequence, TradingExecutionPhase.APPLY, observer) {
                boundedMutation {
                    engine.apply(plan)
                    for (change in settlement.balanceChanges) {
                        current.balances[TradingBalanceKey(change.next.userId, change.next.assetId)] =
                            change.next
                    }
                    for (change in settlement.reservationChanges) {
                        current.liveByOrder.getValue(change.next.marketId to change.next.orderId).reservation = change.next
                    }
                    nextFeeRevenue?.let { current.feeRevenue[feeAsset] = it }
                    if (!restingQuantity.isZero()) live.phase = LiveOrderPhase.RESTING
                    val terminal = ArrayList<BoundedLiveOrder>()
                    for (reservation in participants) {
                        val participant = current.liveByOrder.getValue(reservation.marketId to reservation.orderId)
                        if (participant.reservation.status != OrderReservationStatus.ACTIVE) terminal.add(participant)
                    }
                    // 전량 체결한 taker의 예약은 종료 상태이며, 체결이 없는 taker는 RESTING이 된다.
                    for (finished in terminal) {
                        current.liveByOrder.remove(finished.order.marketId to finished.order.orderId)
                        current.liveBySequence.remove(finished.admissionSequence)
                        engine.releaseTerminalOrderIdentity(finished.order.marketId, finished.order.orderId)
                        progress.activeCount--
                    }
                    check(progress.pending.removeFirst() === live)
                    current.pendingCount--
                    progress.executedThrough = live.marketAdmissionSequence
                }
            }
            return observedExecutionPhase(command.admissionSequence, TradingExecutionPhase.COMPLETION, observer) {
                ExecutionReply.Applied(receipt)
            }
        } catch (failure: TradingInvariantViolation) {
            // 쓰기 전에 명시적으로 분류한 검증 실패만 안전하게 격리 상태로 저장할 수 있다.
            if (applying) isBlocked = true
            throw failure
        } catch (failure: Throwable) {
            isBlocked = true
            throw failure
        }
    }

    private fun validateBoundedNoFillBalances(
        current: BoundedTradingData,
        live: BoundedLiveOrder,
        context: TradingMarketContext,
    ) {
        val order = live.order
        val baseKey = TradingBalanceKey(order.userId, context.market.baseAssetId)
        val quoteKey = TradingBalanceKey(order.userId, context.market.quoteAssetId)
        val base = checkNotNull(current.balances[baseKey]) { "participant balance capacity was not reserved" }
        val quote = checkNotNull(current.balances[quoteKey]) { "participant balance capacity was not reserved" }
        check(base.userId == baseKey.userId && base.assetId == baseKey.assetId)
        check(quote.userId == quoteKey.userId && quote.assetId == quoteKey.assetId)
        val reserved = if (order.side == Side.BUY) quote else base
        check(reserved.hold >= live.reservation.remainingAmount)
    }

    private fun boundedSettlementExecutions(
        current: BoundedTradingData,
        context: TradingMarketContext,
        plan: SubmitOrderPlan,
        at: Instant,
    ): List<SettlementExecution> {
        val executions = ArrayList<SettlementExecution>(plan.fills.size)
        for (event in plan.events) {
            if (event is TradeExecuted) {
                executions.add(
                    event.toSettlement(
                        at,
                        TradingIdentifiers.settlement(current.manifest.lineage, context.ordinal, event.engineSequence),
                    ),
                )
            }
        }
        return executions
    }

    private fun validateBoundedExecutionResult(
        current: BoundedTradingData,
        live: BoundedLiveOrder,
        matchingCommand: SubmitOrderCommand,
        plan: SubmitOrderPlan,
        settlement: OrderSettlementCandidate,
        executions: List<SettlementExecution>,
    ) {
        validateBoundedStage(TradingInvariantReason.RESULT_RELATION_INVALID, TradingExecutionPhase.RESULT_VALIDATION) {
            validateBoundedJoint(live, plan, settlement, executions)
            MatchingResultState.validateCompleteResult(
                "EXECUTE:${live.marketAdmissionSequence}",
                matchingCommand,
                plan,
            )
            for (change in settlement.balanceChanges) {
                check(current.balances[TradingBalanceKey(change.previous.userId, change.previous.assetId)] == change.previous)
            }
            for (change in settlement.reservationChanges) {
                check(current.liveByOrder[change.previous.marketId to change.previous.orderId]?.reservation == change.previous)
            }
        }
    }

    private fun validateBoundedFeeRevenue(
        current: BoundedTradingData,
        settlement: OrderSettlementCandidate,
        feeAsset: AssetId,
    ): java.math.BigInteger? {
        // SPOT 정산에서는 두 참여자 모두에게 이 시장의 quote 자산으로 수수료를 부과한다.
        // 체결별 임시 맵이나 maker·taker 목록 없이 정확한 수익을 한 번만 누적한다.
        val transactionIds = hashSetOf<String>()
        val eventIds = hashSetOf<String>()
        return validateBoundedStage(TradingInvariantReason.FEE_REVENUE_INVALID, TradingExecutionPhase.RESULT_VALIDATION) {
            var feeDelta = java.math.BigInteger.ZERO
            var ledgerRevenue = java.math.BigInteger.ZERO
            for (fill in settlement.settlements) {
                check(transactionIds.add(fill.transaction.ledgerTransactionId) && eventIds.add(fill.transaction.sourceEventId))
                check(fill.makerPlan.feeAssetId == feeAsset && fill.takerPlan.feeAssetId == feeAsset)
                feeDelta =
                    feeDelta
                        .add(java.math.BigInteger.valueOf(fill.makerPlan.actualFeeAmount.value))
                        .add(java.math.BigInteger.valueOf(fill.takerPlan.actualFeeAmount.value))
                for (posting in fill.transaction.postings) {
                    if (posting.accountId == "SYSTEM:${posting.assetId.value}:FEE_REVENUE") {
                        check(posting.assetId == feeAsset && posting.side == com.exchange.core.ledger.LedgerPostingSide.CREDIT)
                        ledgerRevenue = ledgerRevenue.add(java.math.BigInteger.valueOf(posting.amount.value))
                    }
                }
            }
            check(feeDelta == ledgerRevenue) { "fee delta differs from ledger revenue" }
            if (settlement.settlements.isEmpty()) {
                null
            } else {
                val next = (current.feeRevenue[feeAsset] ?: java.math.BigInteger.ZERO).add(feeDelta)
                check(next.signum() >= 0 && next.bitLength() <= feeBitLimit(current.manifest.limits))
                if (next.signum() != 0) next else null
            }
        }
    }

    private inline fun <T> validateBoundedStage(
        reason: TradingInvariantReason,
        stage: TradingExecutionPhase,
        action: () -> T,
    ): T =
        try {
            action()
        } catch (failure: TradingInvariantViolation) {
            throw failure
        } catch (_: IllegalArgumentException) {
            throw TradingInvariantViolation(reason, stage)
        } catch (_: IllegalStateException) {
            throw TradingInvariantViolation(reason, stage)
        }

    private fun validateBoundedReservation(
        live: BoundedLiveOrder,
        context: TradingMarketContext,
    ) {
        validateBoundedReservationRelationship(live, context)
        settlementCalculator.validateReservation(context.market, live.reservation)
    }

    private fun validateBoundedReservationRelationship(
        live: BoundedLiveOrder,
        context: TradingMarketContext,
    ) {
        val order = live.order
        val reservation = live.reservation
        check(order.marketId == context.market.marketId && reservation.marketId == order.marketId && reservation.orderId == order.orderId)
        check(reservation.userId == order.userId && reservation.side == order.side && reservation.limitPrice == order.price)
        check(reservation.initialQuantity == order.quantity && reservation.feePolicySnapshot == context.feePolicySnapshot)
        check(reservation.status == OrderReservationStatus.ACTIVE)
    }

    private fun validateBoundedJoint(
        live: BoundedLiveOrder,
        plan: SubmitOrderPlan,
        settlement: OrderSettlementCandidate,
        executions: List<SettlementExecution>,
    ) {
        check(settlement.order == live.order && settlement.settlements.size == executions.size)
        for (index in executions.indices) check(settlement.settlements[index].execution == executions[index])
        if (executions.isEmpty()) {
            check(settlement.balanceChanges.isEmpty() && settlement.reservationChanges.isEmpty())
            return
        }
        val next = settlement.reservationChanges.associate { it.next.orderId to it.next }
        for (fill in plan.fills) {
            check(next.getValue(fill.orderId).remainingQuantity.value == fill.remainingQuantity.value - fill.filledQuantity.value)
        }
        val restingQuantity = (plan.events.lastOrNull() as? OrderEnteredBook)?.remainingQuantity ?: com.exchange.core.common.Quantity.ZERO
        check(next.getValue(live.order.orderId).remainingQuantity == restingQuantity)
        for (fill in settlement.settlements) {
            check(fill.transaction.transactionType == LedgerTransactionType.SETTLEMENT)
            check(
                fill.transaction.ledgerTransactionId == fill.execution.ledgerTransactionId &&
                    fill.transaction.occurredAt == fill.execution.occurredAt,
            )
            check(fill.transaction.sourceEventId == "MATCHING:${fill.execution.marketId.value}:${fill.execution.engineSequence}")
        }
    }

    fun quarantine(
        position: Long,
        reason: String,
    ) {
        check(boundedMode && !isBlocked)
        val current = checkNotNull(bounded) { "trading is not initialized" }
        require(position >= 0 && reason.isNotBlank() && reason.length <= 128)
        val incident = TradingIncident(position, reason)
        if (current.lifecycle == TradingLifecycle.QUARANTINED) {
            require(current.incident == incident) { "first quarantine incident cannot change" }
            return
        }
        boundedMutation {
            current.incident = incident
            current.lifecycle = TradingLifecycle.QUARANTINED
        }
    }

    fun boundedRetention(): TradingBoundedRetention {
        val current = runningBounded()
        return TradingBoundedRetention(
            current.balances.size,
            current.accounts.size,
            current.preparations.size,
            current.liveBySequence.size,
            current.pendingCount,
            current.inbox.entryCount,
            current.inbox.canonicalBytes,
        )
    }

    fun currentBalance(
        userId: UserId,
        assetId: AssetId,
    ): Balance {
        val current = runningBounded()
        return current.balances[TradingBalanceKey(userId, assetId)] ?: Balance(userId, assetId, Amount.ZERO, Amount.ZERO)
    }

    fun recoveryState(): TradingRecoveryState {
        check(boundedMode && !isBlocked) { "cannot snapshot a failed or legacy owner" }
        val current = checkNotNull(bounded) { "trading is not initialized" }
        return TradingRecoveryState(
            current.manifest,
            current.lifecycle,
            current.balances.values.sortedWith(compareBy({ it.userId.value }, { it.assetId.value })),
            current.feeRevenue.toSortedMap(compareBy { it.value }),
            current.usedBalances.sortedWith(compareBy({ it.userId.value }, { it.assetId.value })),
            current.preparations.values.toList(),
            current.accounts.sortedBy { it.value },
            current.liveBySequence.values
                .sortedBy { it.admissionSequence }
                .map { it.snapshot() },
            current.markets.entries.sortedBy { it.key.value }.map { (market, progress) ->
                TradingMarketProgressSnapshot(
                    market,
                    progress.lastAssigned,
                    progress.executedThrough,
                    progress.pending.map { it.admissionSequence },
                )
            },
            engine.activeCheckpoint(),
            current.inbox.snapshot(),
            current.lastAdmissionSequence,
            current.lastPreparationSequence,
            current.preparationBytes,
            current.incident,
        )
    }

    val isBounded: Boolean get() = boundedMode

    /** 크기가 제한된 설정 시장 벡터만 복사하므로 주문·이력 수가 늘어도 작업량은 일정하다. */
    fun progress(): BoundedTradingProgress {
        check(boundedMode && !isBlocked)
        val current = bounded ?: return BoundedTradingProgress(TradingLifecycle.UNINITIALIZED, 0L, emptyList())
        return BoundedTradingProgress(
            current.lifecycle,
            current.lastAdmissionSequence,
            current.markets.entries.map { (market, state) ->
                BoundedMarketExecutionProgress(market, state.lastAssigned, state.executedThrough)
            },
        )
    }

    /** 이 빈 소유자의 현재 상태를 교체하기 전에 내부 후보를 검증한다. */
    fun restoreRecoveryState(saved: TradingRecoveryState) {
        check(boundedMode && !isBlocked && bounded == null) { "bounded restore requires an empty owner" }
        try {
            val manifest = saved.manifest
            val limits = manifest.limits
            validateBoundedManifest(manifest)
            val candidate = BoundedTradingData(manifest)
            require(candidate.assets.size <= limits.maxAssets)
            require(saved.lifecycle != TradingLifecycle.UNINITIALIZED)
            require((saved.lifecycle == TradingLifecycle.QUARANTINED) == (saved.incident != null))
            saved.incident?.let { require(it.position >= 0 && it.reason.isNotBlank() && it.reason.length <= 128) }
            require(saved.lastAdmissionSequence >= 0 && saved.lastPreparationSequence >= 0 && saved.preparationCanonicalBytes >= 0)
            require(saved.accounts.size <= limits.maxAccounts && saved.accounts.size == saved.accounts.distinct().size)
            for (account in saved.accounts) {
                require(admissionCalculator.validIdentifier(account.value, accountPart = true))
                require(tradingTextBytes(account.value) <= limits.maxIdentifierBytes)
                candidate.accounts.add(account)
            }
            require(saved.balances.size <= limits.maxBalanceKeys)
            for (balance in saved.balances) {
                require(balance.userId in candidate.accounts && balance.assetId in candidate.assets)
                require(
                    candidate.balances.put(TradingBalanceKey(balance.userId, balance.assetId), balance) == null,
                ) { "duplicate recovery balance" }
            }
            require(saved.usedBalances.size == saved.usedBalances.distinct().size && saved.usedBalances.toSet() == candidate.balances.keys)
            candidate.usedBalances.addAll(saved.usedBalances)
            require(saved.preparations.size <= limits.maxPreparations && saved.lastPreparationSequence == saved.preparations.size.toLong())
            val supplied = linkedMapOf<AssetId, java.math.BigInteger>()
            val preparedKeys = hashSetOf<TradingBalanceKey>()
            for ((index, preparation) in saved.preparations.withIndex()) {
                val opening = preparation.opening
                val key = TradingBalanceKey(opening.userId, opening.assetId)
                require(key in candidate.usedBalances && preparedKeys.add(key)) { "duplicate or unknown prepared balance" }
                require(!preparation.alreadyPrepared)
                require(preparation.ledgerTransactionId == TradingIdentifiers.opening(manifest.lineage, index.toLong() + 1))
                require(candidate.preparations.put(opening.preparationId, preparation) == null) { "duplicate preparation identity" }
                val bytes = preparationBytes(opening)
                require(tradingTextBytes(opening.preparationId) <= limits.maxIdentifierBytes)
                candidate.preparationBytes = Math.addExact(candidate.preparationBytes, bytes)
                supplied[opening.assetId] =
                    (supplied[opening.assetId] ?: java.math.BigInteger.ZERO).add(java.math.BigInteger.valueOf(opening.amount.value))
            }
            require(
                candidate.preparationBytes == saved.preparationCanonicalBytes && candidate.preparationBytes <= limits.maxPreparationBytes,
            )
            for ((asset, fee) in saved.feeRevenueByAsset) {
                require(asset in candidate.assets && fee.signum() >= 0 && fee.bitLength() <= feeBitLimit(limits))
                if (fee.signum() != 0) candidate.feeRevenue[asset] = fee
            }
            require(saved.liveOrders.size <= limits.maxActiveOrders)
            val marketOrderSequences = hashSetOf<Pair<MarketId, Long>>()
            for (order in saved.liveOrders) {
                require(order.admissionSequence in 1..saved.lastAdmissionSequence && order.marketAdmissionSequence > 0)
                require(order.order.orderId == TradingIdentifiers.order(manifest.lineage, order.admissionSequence))
                require(order.order.userId in candidate.accounts)
                require(
                    marketOrderSequences.add(order.order.marketId to order.marketAdmissionSequence),
                ) { "duplicate live market sequence" }
                val context = requireNotNull(candidate.contexts[order.order.marketId])
                require(admissionCalculator.validOrderIdentifiers(order.order))
                require(admissionCalculator.supports(order.order.orderType, order.order.timeInForce))
                val live =
                    BoundedLiveOrder(order.admissionSequence, order.marketAdmissionSequence, order.order, order.reservation, order.phase)
                validateBoundedReservation(live, context)
                require(candidate.liveBySequence.put(order.admissionSequence, live) == null)
                require(candidate.liveByOrder.put(order.order.marketId to order.order.orderId, live) == null)
                require(TradingBalanceKey(order.order.userId, context.market.baseAssetId) in candidate.balances)
                require(TradingBalanceKey(order.order.userId, context.market.quoteAssetId) in candidate.balances)
                candidate.markets.getValue(order.order.marketId).activeCount++
            }
            require(
                saved.markets.size == candidate.markets.size && saved.markets
                    .map { it.marketId }
                    .distinct()
                    .size == saved.markets.size,
            )
            var assignedTotal = 0L
            val pendingSequences = hashSetOf<Long>()
            for (market in saved.markets) {
                val progress = requireNotNull(candidate.markets[market.marketId])
                require(market.executedThrough >= 0 && market.lastAssigned >= market.executedThrough)
                require(progress.activeCount <= limits.maxMarketActiveOrders)
                require(market.pendingAdmissionSequences.size <= limits.maxMarketPendingOrders)
                require(market.lastAssigned - market.executedThrough == market.pendingAdmissionSequences.size.toLong())
                progress.lastAssigned = market.lastAssigned
                progress.executedThrough = market.executedThrough
                assignedTotal = Math.addExact(assignedTotal, market.lastAssigned)
                for ((index, sequence) in market.pendingAdmissionSequences.withIndex()) {
                    val order = requireNotNull(candidate.liveBySequence[sequence])
                    require(
                        order.order.marketId == market.marketId && order.phase == LiveOrderPhase.PENDING && pendingSequences.add(sequence),
                    )
                    require(order.marketAdmissionSequence == Math.addExact(market.executedThrough, index.toLong() + 1L))
                    require(
                        order.reservation.remainingQuantity == order.order.quantity &&
                            order.reservation.remainingAmount == order.reservation.reservedAmount,
                    )
                    require(
                        order.reservation.remainingFeeReserveAmount == order.reservation.initialFeeReserveAmount &&
                            order.reservation.feeRemainder == com.exchange.core.fee.FeeRemainder.ZERO,
                    )
                    progress.pending.addLast(order)
                }
                candidate.pendingCount = Math.addExact(candidate.pendingCount, progress.pending.size)
            }
            require(assignedTotal == saved.lastAdmissionSequence && candidate.pendingCount <= limits.maxPendingOrders)
            val holds = linkedMapOf<TradingBalanceKey, Long>()
            for (order in candidate.liveBySequence.values) {
                val progress = candidate.markets.getValue(order.order.marketId)
                require(order.marketAdmissionSequence <= progress.lastAssigned)
                require((order.phase == LiveOrderPhase.PENDING) == (order.admissionSequence in pendingSequences))
                if (order.phase == LiveOrderPhase.RESTING) require(order.marketAdmissionSequence <= progress.executedThrough)
                val key = TradingBalanceKey(order.reservation.userId, order.reservation.assetId)
                holds[key] = Math.addExact(holds[key] ?: 0L, order.reservation.remainingAmount.value)
            }
            val totals = linkedMapOf<AssetId, java.math.BigInteger>()
            for ((key, balance) in candidate.balances) {
                require(balance.hold.value == (holds.remove(key) ?: 0L)) { "hold differs from active reservations" }
                val amount =
                    java.math.BigInteger
                        .valueOf(balance.available.value)
                        .add(java.math.BigInteger.valueOf(balance.hold.value))
                totals[balance.assetId] = (totals[balance.assetId] ?: java.math.BigInteger.ZERO).add(amount)
            }
            require(holds.isEmpty())
            for (asset in candidate.assets) {
                require(
                    (totals[asset] ?: java.math.BigInteger.ZERO).add(candidate.feeRevenue[asset] ?: java.math.BigInteger.ZERO) ==
                        (supplied[asset] ?: java.math.BigInteger.ZERO),
                ) {
                    "recovery asset conservation differs from opening funding"
                }
            }
            val restoredEngine = MatchingEngine().also { it.restoreActiveCheckpoint(saved.matching) }
            val restingSequences = hashSetOf<Long>()
            for (market in saved.matching.markets) {
                val progress = requireNotNull(candidate.markets[market.marketId])
                require(progress.executedThrough > 0 && market.lastEngineSequence >= progress.executedThrough)
                val pricePriority = hashMapOf<Pair<Side, com.exchange.core.common.Price>, Long>()
                for (bookOrder in market.orders) {
                    val order = requireNotNull(candidate.liveByOrder[market.marketId to bookOrder.orderId])
                    require(order.phase == LiveOrderPhase.RESTING && restingSequences.add(order.admissionSequence))
                    require(
                        bookOrder.userId == order.order.userId && bookOrder.side == order.order.side &&
                            bookOrder.price == order.order.price,
                    )
                    require(
                        bookOrder.originalQuantity == order.order.quantity &&
                            bookOrder.remainingQuantity == order.reservation.remainingQuantity,
                    )
                    val key = bookOrder.side to bookOrder.price
                    require(order.marketAdmissionSequence > (pricePriority[key] ?: 0L)) { "recovery maker FIFO differs" }
                    pricePriority[key] = order.marketAdmissionSequence
                }
            }
            require(
                saved.matching.markets
                    .map { it.marketId }
                    .toSet() == candidate.markets.filterValues { it.executedThrough > 0 }.keys,
            )
            require(
                restingSequences ==
                    candidate.liveBySequence.values
                        .filter { it.phase == LiveOrderPhase.RESTING }
                        .map { it.admissionSequence }
                        .toSet(),
            )
            candidate.inbox.restore(saved.inbox)
            val acceptedSequences = hashSetOf<Long>()
            val acceptedMarketSequences = hashSetOf<Pair<MarketId, Long>>()
            for (channel in saved.inbox.channels) {
                require(channel.userId in candidate.accounts)
                for (entry in channel.entries) {
                    val context = requireNotNull(candidate.contexts[entry.intent.marketId])
                    require(entry.intent.expectedConfigurationVersion == manifest.configurationVersion)
                    val receipt = entry.receipt
                    if (receipt.code == AdmissionCode.ACCEPTED) {
                        val sequence = requireNotNull(receipt.admissionSequence)
                        require(acceptedSequences.add(sequence)) { "multiple request tokens claim one admission" }
                        require(
                            sequence <= saved.lastAdmissionSequence &&
                                receipt.orderId == TradingIdentifiers.order(manifest.lineage, sequence),
                        )
                        require(
                            requireNotNull(receipt.marketAdmissionSequence) <=
                                candidate.markets.getValue(context.market.marketId).lastAssigned,
                        )
                        require(acceptedMarketSequences.add(context.market.marketId to receipt.marketAdmissionSequence)) {
                            "multiple request tokens claim one market admission"
                        }
                        admissionCalculator.validateAcceptedOrder(
                            OrderAdmissionInput(
                                entry.intent.marketId,
                                requireNotNull(receipt.orderId),
                                channel.userId,
                                entry.intent.side,
                                entry.intent.orderType,
                                entry.intent.timeInForce,
                                entry.intent.price,
                                entry.intent.quantity,
                            ),
                            context.market,
                            context.feePolicySnapshot,
                        )
                        candidate.liveBySequence[sequence]?.let { live ->
                            require(
                                live.order.userId == channel.userId && live.order.marketId == entry.intent.marketId &&
                                    live.marketAdmissionSequence == receipt.marketAdmissionSequence &&
                                    live.order.side == entry.intent.side &&
                                    live.order.orderType == entry.intent.orderType && live.order.timeInForce == entry.intent.timeInForce &&
                                    live.order.price == entry.intent.price && live.order.quantity == entry.intent.quantity,
                            )
                        }
                    }
                }
            }
            candidate.lastAdmissionSequence = saved.lastAdmissionSequence
            candidate.lastPreparationSequence = saved.lastPreparationSequence
            candidate.lifecycle = saved.lifecycle
            candidate.incident = saved.incident
            isBlocked = true
            engine = restoredEngine
            bounded = candidate
            isBlocked = false
        } catch (failure: Throwable) {
            isBlocked = true
            throw failure
        }
    }

    private fun runningBounded(): BoundedTradingData {
        check(boundedMode && !isBlocked) { "bounded trading owner is not usable" }
        val current = checkNotNull(bounded) { "trading is not initialized" }
        check(current.lifecycle == TradingLifecycle.RUNNING) { "trading lineage is quarantined" }
        return current
    }

    private inline fun <T> boundedMutation(action: () -> T): T {
        check(!isBlocked)
        isBlocked = true
        val result = action()
        isBlocked = false
        return result
    }

    private fun preparationBytes(opening: OpeningBalance): Long =
        128L + tradingTextBytes(opening.preparationId) + tradingTextBytes(opening.userId.value) + tradingTextBytes(opening.assetId.value)

    private fun feeBitLimit(limits: TradingResourceLimits): Int =
        java.math.BigInteger
            .valueOf(limits.maxBalanceKeys.toLong())
            .multiply(java.math.BigInteger.valueOf(Long.MAX_VALUE))
            .bitLength()

    private inline fun <T> admissionCall(action: () -> T): T {
        check(!boundedMode) { "legacy history API is unavailable in bounded mode" }
        checkUsable()
        try {
            return action()
        } finally {
            if (admission.isBlocked) isBlocked = true
        }
    }

    private fun checkUsable() {
        check(!isBlocked) { "공통 거래 상태가 차단됐습니다" }
        if (boundedMode) check(bounded?.lifecycle == TradingLifecycle.RUNNING) { "bounded trading is not ready or quarantined" }
    }

    private fun <T> immutableList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
}

/** 구분자나 로컬 시각에 기대지 않는 v1 체결 원장 ID. 기존 64자 한도 안의 ASCII 57자다. */
internal fun settlementTransactionId(
    marketId: MarketId,
    engineSequence: Long,
): String {
    require(engineSequence > 0) { "체결 순번은 양수여야 합니다" }
    val market = marketId.value.toByteArray(Charsets.UTF_8)
    val bytes = ByteArray(4 + Int.SIZE_BYTES + market.size + Long.SIZE_BYTES)
    writeTradingIdentifierInt(bytes, 0, 0x53544C31)
    writeTradingIdentifierInt(bytes, 4, market.size)
    market.copyInto(bytes, 4 + Int.SIZE_BYTES)
    writeTradingIdentifierLong(bytes, 4 + Int.SIZE_BYTES + market.size, engineSequence)
    val digest = tradingIdentifierSha256(bytes)
    return "SETTLEMENT:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
