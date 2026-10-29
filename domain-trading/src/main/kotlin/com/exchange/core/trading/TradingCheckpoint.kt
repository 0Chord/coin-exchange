package com.exchange.core.trading

import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.order.OrderReservation
import java.util.Collections

data class TradingBalanceKey(
    val userId: UserId,
    val assetId: AssetId,
)

/**
 * Live state at one durable history ordinal. Historical request IDs and first results belong to
 * the separately restored history store, never to this active-state checkpoint.
 */
class TradingCheckpoint(
    balances: List<Balance>,
    usedBalances: List<TradingBalanceKey>,
    admissions: List<OrderAdmissionState.Record>,
    reservations: List<OrderReservation>,
    pendingAdmissionSequences: List<Long>,
    completions: List<TradingState.Completion>,
    val lastAdmissionSequence: Long,
    val historyOrdinal: Long,
    val matching: MatchingCheckpoint,
) {
    val balances: List<Balance> = immutable(balances)
    val usedBalances: List<TradingBalanceKey> = immutable(usedBalances)
    val admissions: List<OrderAdmissionState.Record> = immutable(admissions)
    val reservations: List<OrderReservation> = immutable(reservations)
    val pendingAdmissionSequences: List<Long> = immutable(pendingAdmissionSequences)
    val completions: List<TradingState.Completion> = immutable(completions)

    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
}
