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
 * Aeron 자동 실행·영구 저장·재시작 복구는 이 코어의 역할이 아니다.
 */
class TradingState(
    admissionCalculator: OrderAdmissionCalculator,
    private val settlementCalculator: OrderSettlementCalculator,
) {
    private val admission = OrderAdmissionState(emptyList(), admissionCalculator)
    private val engine = MatchingEngine()
    private val resultState = MatchingResultState()
    private val completions: MutableMap<Long, Completion> = linkedMapOf()

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
    ): OpeningBalanceResult = admissionCall { admission.prepareDevelopmentBalance(opening, ledgerTransactionId, occurredAt) }

    fun admit(
        requestId: String,
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): OrderAdmissionState.Result =
        admissionCall { admission.admit(requestId, order, market, feePolicySnapshot, ledgerTransactionId, occurredAt) }

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
    ): Boolean {
        checkUsable()
        return admission.hasRequest(userId, requestId)
    }

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
        require(admissionSequence > 0) { "승인 순번은 양수여야 합니다" }
        val record = requireNotNull(admission.accepted(admissionSequence)) { "승인된 주문이 없습니다" }
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
                if (observerFailure !== businessFailure) businessFailure.addSuppressed(observerFailure)
            }
        }
    }

    /** 정상 조회만 복사한다. 주문 실행 중에는 전체 상태 snapshot을 만들지 않는다. */
    fun snapshot(): Snapshot {
        checkUsable()
        val current = admission.snapshot()
        return Snapshot(
            current.balances,
            admission.reservations(),
            current.pendingOrders,
            immutableList(completions.values),
            admission.transactions(),
            resultState.snapshot().results,
            current.lastSequence,
            current.requests,
            current.preparations,
        )
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
        SettlementExecution(
            marketId,
            engineSequence,
            makerOrderId,
            takerOrderId,
            makerUserId,
            takerUserId,
            side,
            price,
            quantity,
            settlementTransactionId(marketId, engineSequence),
            occurredAt,
        )

    private inline fun <T> admissionCall(action: () -> T): T {
        checkUsable()
        try {
            return action()
        } finally {
            if (admission.isBlocked) isBlocked = true
        }
    }

    private fun checkUsable() = check(!isBlocked) { "공통 거래 상태가 차단됐습니다" }

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
