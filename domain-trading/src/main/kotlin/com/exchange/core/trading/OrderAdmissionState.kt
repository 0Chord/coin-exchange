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
 * 영구 저장·동시 호출·복구는 제공하지 않으며, 반영 중 오류 뒤에는 접수와 정상 조회를 차단한다.
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

    private val balances: MutableMap<BalanceKey, Balance> = linkedMapOf()
    private val records: MutableMap<OrderKey, Record> = linkedMapOf()
    private val transactionIds = mutableSetOf<String>()
    private val sourceEventIds = mutableSetOf<String>()
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
    )

    /**
     * 외부 잔고나 후보를 받지 않고 소유한 최신 잔고로 계산한다.
     * 정상 거절과 첫 변경 전 오류는 상태를 유지한다. 반영 중 오류의 SQL식 롤백은 보장하지 않는다.
     */
    fun admit(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): Result {
        checkUsable()
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
        val sequence = Math.addExact(lastSequence, 1)
        val record = Record(sequence, candidate)
        val result = Result.Accepted(record)

        // 첫 변경 전에 차단해 둔다. 아래 반영이 끝나지 않으면 어떤 예외든 차단 상태가 남는다.
        isBlocked = true
        balances[balanceKey] = candidate.nextBalance
        records[orderKey] = record
        transactionIds.add(candidate.reserveTransaction.ledgerTransactionId)
        sourceEventIds.add(candidate.reserveTransaction.sourceEventId)
        lastSequence = sequence
        isBlocked = false
        return result
    }

    /** 반환 목록은 내부 자료와 분리되며 이후 접수로 이미 반환한 값이 바뀌지 않는다. */
    fun snapshot(): Snapshot {
        checkUsable()
        return Snapshot(immutableList(balances.values), immutableList(records.values), lastSequence)
    }

    fun pendingOrders(marketId: MarketId): List<Record> {
        checkUsable()
        return immutableList(records.values.filter { it.candidate.order.marketId == marketId })
    }

    private fun checkUsable() = check(!isBlocked) { "반영 중 오류로 접수 상태가 차단됐습니다" }

    private fun <T> immutableList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
}
