package com.exchange.core.trading

import com.exchange.core.ledger.LedgerTransaction

/** Identity is local to an owner; recovery compares exact historical values before sharing one restored record. */
internal fun requireSameAdmission(
    first: OrderAdmissionState.Record,
    second: OrderAdmissionState.Record,
) {
    val a = first.candidate
    val b = second.candidate
    require(
        first.sequence == second.sequence && a.order == b.order && a.market == b.market &&
            a.feePolicySnapshot == b.feePolicySnapshot && a.previousBalance == b.previousBalance &&
            a.nextBalance == b.nextBalance && a.reservation == b.reservation &&
            sameTransaction(a.reserveTransaction, b.reserveTransaction),
    ) { "checkpoint admission differs from its historical original" }
}

internal fun requireSameCompletion(
    first: TradingState.Completion,
    second: TradingState.Completion,
) {
    requireSameAdmission(first.admission, second.admission)
    val a = first.settlement
    val b = second.settlement
    require(
        first.executionId == second.executionId && first.occurredAt == second.occurredAt &&
            first.matchingResult == second.matchingResult && a.order == b.order && a.market == b.market &&
            a.balanceChanges == b.balanceChanges && a.reservationChanges == b.reservationChanges &&
            a.settlements.size == b.settlements.size &&
            a.settlements.zip(b.settlements).all { (left, right) ->
                left.execution == right.execution && left.makerPlan == right.makerPlan && left.takerPlan == right.takerPlan &&
                    sameTransaction(left.transaction, right.transaction)
            },
    ) { "checkpoint completion differs from its historical first result" }
}

private fun sameTransaction(
    first: LedgerTransaction,
    second: LedgerTransaction,
): Boolean =
    first.ledgerTransactionId == second.ledgerTransactionId && first.sourceEventId == second.sourceEventId &&
        first.transactionType == second.transactionType && first.occurredAt == second.occurredAt && first.postings == second.postings
