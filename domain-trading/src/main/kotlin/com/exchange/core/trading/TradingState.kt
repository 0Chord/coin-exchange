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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Collections

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
    fun executePending(
        admissionSequence: Long,
        occurredAt: Instant,
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
            val plan = engine.prepareSubmit(command)
            val reservations = validateParticipants(record, plan)
            val balances = participantBalances(reservations, original.market)
            val executions = plan.events.filterIsInstance<TradeExecuted>().map { it.toSettlement(occurredAt) }
            val settlement = settlementCalculator.calculate(order, original.market, executions, reservations, balances)
            validateJointResult(record, plan, settlement, executions)
            admission.validateSettlement(settlement)
            engine.validate(plan)
            val executionId = "EXECUTE:v1:$admissionSequence"
            check(resultState.find(order.marketId, executionId) == null) { "전체 완료 없이 매칭 결과만 존재합니다" }
            check(engine.lastEngineSequence(order.marketId) == resultState.lastEngineSequence(order.marketId)) {
                "주문장과 결과 이력의 이전 순번이 다릅니다"
            }
            resultState.validateRegistration(
                executionId,
                command,
                plan.events.size,
                plan.firstEngineSequence,
                plan.lastEngineSequence,
                plan.events,
            )

            // 여기부터 실제 값이 바뀐다. 완료 기록까지 끝나기 전에 정상 사용을 허용하지 않는다.
            isBlocked = true
            engine.apply(plan)
            admission.applySettlement(settlement)
            val matchingResult =
                resultState.register(
                    executionId,
                    command,
                    plan.events.size,
                    plan.firstEngineSequence,
                    plan.lastEngineSequence,
                    plan.events,
                )
            admission.completePending(record)
            val completion = Completion(record, executionId, occurredAt, matchingResult, settlement)
            completions[admissionSequence] = completion
            isBlocked = false
            return completion
        } catch (failure: Throwable) {
            isBlocked = true
            admission.block()
            throw failure
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
        val holdByOwner = linkedMapOf<Pair<UserId, AssetId>, Long>()
        reservations.forEach {
            val key = it.userId to it.assetId
            holdByOwner[key] = Math.addExact(holdByOwner[key] ?: 0, it.remainingAmount.value)
        }
        holdByOwner.forEach { (key, required) ->
            check(admission.currentBalance(key.first, key.second).hold.value >= required) { "현재 hold가 예약보다 작습니다" }
        }
        return reservations
    }

    private fun validateReservation(
        record: OrderAdmissionState.Record,
        current: OrderReservation,
    ) {
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
        settlementCalculator.validateReservation(record.candidate.market, current)
    }

    private fun participantBalances(
        reservations: List<OrderReservation>,
        market: MarketDefinition,
    ): List<Balance> =
        reservations.flatMap { listOf(it.userId to market.baseAssetId, it.userId to market.quoteAssetId) }.distinct().map {
            admission.currentBalance(it.first, it.second)
        }

    private fun validateJointResult(
        record: OrderAdmissionState.Record,
        plan: SubmitOrderPlan,
        candidate: OrderSettlementCandidate,
        executions: List<SettlementExecution>,
    ) {
        check(candidate.order == record.candidate.order && candidate.market == record.candidate.market)
        check(candidate.settlements.map { it.execution } == executions) { "정산 체결과 실제 매칭 순서가 다릅니다" }
        val nextReservations = candidate.reservationChanges.associate { it.next.orderId to it.next }
        if (executions.isNotEmpty()) {
            plan.fills.forEach {
                check(
                    nextReservations.getValue(it.orderId).remainingQuantity.value == it.remainingQuantity.value - it.filledQuantity.value,
                ) {
                    "정산 후 maker 잔량이 주문장 후보와 다릅니다"
                }
            }
            val remaining = (plan.events.last() as? OrderEnteredBook)?.remainingQuantity?.value ?: 0L
            check(nextReservations.getValue(record.candidate.order.orderId).remainingQuantity.value == remaining) {
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
    val bytes =
        ByteBuffer
            .allocate(4 + Int.SIZE_BYTES + market.size + Long.SIZE_BYTES)
            .put(byteArrayOf(83, 84, 76, 49))
            .putInt(market.size)
            .put(market)
            .putLong(engineSequence)
            .array()
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return "SETTLEMENT:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
