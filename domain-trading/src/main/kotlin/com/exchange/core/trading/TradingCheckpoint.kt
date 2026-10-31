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
 * 하나의 영속 이력 순번에 대응하는 현재 상태다. 과거 요청 ID와 최초 결과는 이 활성 상태
 * 체크포인트가 아니라 별도로 복원하는 이력 저장소에 속한다.
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
