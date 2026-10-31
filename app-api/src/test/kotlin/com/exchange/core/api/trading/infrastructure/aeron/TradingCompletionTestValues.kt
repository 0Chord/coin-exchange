package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.trading.TradingState

/** History reads reconstruct identity-bearing objects; compare every business value, not their JVM references. */
internal fun tradingCompletionValues(
    completion: TradingState.Completion,
    includeExecutionTime: Boolean = true,
): List<Any?> {
    val admission = completion.admission
    val candidate = admission.candidate
    val settlement = completion.settlement
    return listOf(
        listOf(
            admission.sequence,
            candidate.order,
            candidate.market,
            candidate.feePolicySnapshot,
            candidate.previousBalance,
            candidate.nextBalance,
            candidate.reservation,
            completionTransactionValues(candidate.reserveTransaction),
        ),
        completion.executionId,
        completion.occurredAt.takeIf { includeExecutionTime },
        completion.matchingResult,
        listOf(
            settlement.order,
            settlement.market,
            settlement.settlements.map { item ->
                val execution = item.execution
                listOf(
                    listOf(
                        execution.marketId,
                        execution.engineSequence,
                        execution.makerOrderId,
                        execution.takerOrderId,
                        execution.makerUserId,
                        execution.takerUserId,
                        execution.side,
                        execution.price,
                        execution.quantity,
                        execution.ledgerTransactionId,
                        execution.occurredAt.takeIf { includeExecutionTime },
                    ),
                    item.makerPlan,
                    item.takerPlan,
                    completionTransactionValues(item.transaction, includeExecutionTime),
                )
            },
            settlement.balanceChanges,
            settlement.reservationChanges,
        ),
    )
}

private fun completionTransactionValues(
    transaction: LedgerTransaction,
    includeTime: Boolean = true,
): List<Any?> =
    listOf(
        transaction.ledgerTransactionId,
        transaction.sourceEventId,
        transaction.transactionType,
        transaction.occurredAt.takeIf { includeTime },
        transaction.postings,
    )
