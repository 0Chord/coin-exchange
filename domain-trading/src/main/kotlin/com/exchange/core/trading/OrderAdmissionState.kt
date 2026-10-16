package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.UserId
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.Side
import java.time.Instant
import java.util.Collections

/**
 * 한 실행자가 순서대로 호출하는 접수 상태. 여러 마켓이 사용자·자산별 최신 잔고를 공유한다.
 * 영구 저장·동시 호출·복구는 제공하지 않으며, 반영 중 오류 뒤에는 재전송과 정상 조회도 차단한다.
 */
class OrderAdmissionState(
    initialBalances: List<Balance>,
    private val calculator: OrderAdmissionCalculator,
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
    private val transactionIds = mutableSetOf<String>()
    private val sourceEventIds = mutableSetOf<String>()
    private val requestRecords: MutableMap<RequestKey, RequestRecord> = linkedMapOf()
    private var lastSequence = 0L

    /** 차단 여부만 진단할 수 있다. 부분 반영 가능성이 있는 잔고를 정상 조회로 내보내지 않는다. */
    var isBlocked: Boolean = false
        private set

    init {
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
    )

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
        if (!calculator.validIdentifier(requestId)) return Result.Rejected(OrderAdmissionResult.Reason.INVALID_REQUEST_IDENTIFIER)
        if (!calculator.validOrderIdentifiers(order)) return Result.Rejected(OrderAdmissionResult.Reason.INVALID_ORDER_IDENTIFIER)

        val key = RequestKey(order.userId, requestId)
        val previous = requestRecords[key]
        if (previous != null) {
            return if (previous.order == order) previous.result else Result.Rejected(OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT)
        }

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
        val result = prepare(order, market, feePolicySnapshot, ledgerTransactionId, occurredAt)
        if (result is Result.Accepted) applyAccepted(result.record)
        return result
    }

    /** 반환 목록은 내부 자료와 분리되며 이후 접수로 이미 반환한 값이 바뀌지 않는다. */
    fun snapshot(): Snapshot {
        checkUsable()
        return Snapshot(immutableList(balances.values), immutableList(records.values), lastSequence, immutableList(requestRecords.values))
    }

    fun pendingOrders(marketId: MarketId): List<Record> {
        checkUsable()
        return immutableList(records.values.filter { it.candidate.order.marketId == marketId })
    }

    private fun prepare(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): Result {
        val orderKey = OrderKey(order.marketId, order.orderId)
        if (orderKey in records) return Result.Rejected(OrderAdmissionResult.Reason.DUPLICATE_ORDER)

        val assetId = if (order.side == Side.BUY) market.quoteAssetId else market.baseAssetId
        val balanceKey = BalanceKey(order.userId, assetId)
        val currentBalance = balances[balanceKey] ?: Balance(order.userId, assetId, Amount.ZERO, Amount.ZERO)
        val calculation = calculator.calculate(order, market, feePolicySnapshot, currentBalance, ledgerTransactionId, occurredAt)
        if (calculation is OrderAdmissionResult.Rejected) return Result.Rejected(calculation.reason, calculation.insufficientBalance)

        val candidate = (calculation as OrderAdmissionResult.Calculated).candidate
        check(candidate.previousBalance == currentBalance) { "후보가 읽은 잔고와 현재 잔고가 다릅니다" }
        check(candidate.order == order && candidate.market == market && candidate.feePolicySnapshot == feePolicySnapshot) {
            "후보의 주문·마켓·정책 연결이 다릅니다"
        }
        check(candidate.reserveTransaction.ledgerTransactionId !in transactionIds) { "원장 거래 ID가 이미 사용됐습니다" }
        check(candidate.reserveTransaction.sourceEventId !in sourceEventIds) { "원장 사건 ID가 이미 사용됐습니다" }
        return Result.Accepted(Record(Math.addExact(lastSequence, 1), candidate))
    }

    private fun applyAccepted(
        record: Record,
        request: RequestRecord? = null,
    ) {
        val candidate = record.candidate
        val balanceKey = BalanceKey(candidate.order.userId, candidate.nextBalance.assetId)
        val orderKey = OrderKey(candidate.order.marketId, candidate.order.orderId)

        // 최초 결과도 이 구간에서 기록한다. 어느 쓰기든 실패하면 차단 상태가 남는다.
        isBlocked = true
        balances[balanceKey] = candidate.nextBalance
        records[orderKey] = record
        transactionIds.add(candidate.reserveTransaction.ledgerTransactionId)
        sourceEventIds.add(candidate.reserveTransaction.sourceEventId)
        lastSequence = record.sequence
        if (request != null) {
            requestRecords[RequestKey(request.order.userId, request.requestId)] = request
        }
        isBlocked = false
    }

    private fun rememberRejected(request: RequestRecord) {
        val key = RequestKey(request.order.userId, request.requestId)
        // 정상 거절도 요청 이력은 바뀐다. 기록이 끝나지 않으면 첫 답을 신뢰할 수 없다.
        isBlocked = true
        requestRecords[key] = request
        isBlocked = false
    }

    private fun checkUsable() = check(!isBlocked) { "반영 중 오류로 접수 상태가 차단됐습니다" }

    private fun <T> immutableList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
}
