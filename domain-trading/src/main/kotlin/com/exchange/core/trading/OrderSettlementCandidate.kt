package com.exchange.core.trading

import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementPlan
import com.exchange.core.order.OrderReservation
import java.util.Collections

/** 전체 계산을 마쳤지만 실제 잔고·예약·원장에는 반영하지 않은 한 주문의 정산 후보. */
class OrderSettlementCandidate private constructor(
    val order: OrderAdmissionInput,
    val market: MarketDefinition,
    val settlements: List<Settlement>,
    val balanceChanges: List<BalanceChange>,
    val reservationChanges: List<ReservationChange>,
    private val compactHistory: Boolean,
) {
    internal constructor(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        settlements: List<Settlement>,
        balanceChanges: List<BalanceChange>,
        reservationChanges: List<ReservationChange>,
    ) : this(
        order,
        market,
        immutableHistoryCopy(settlements),
        immutableHistoryCopy(balanceChanges),
        immutableHistoryCopy(reservationChanges),
        false,
    )

    /** 완료 이력의 과거 잔고만 숫자로 보관한다. 이미 공유하는 체결·예약·원장은 그대로 유지한다. */
    internal fun compactForHistory(): OrderSettlementCandidate {
        if (compactHistory || balanceChanges.isEmpty()) return this
        return OrderSettlementCandidate(
            order,
            market,
            settlements,
            Collections.unmodifiableList(CompactSettlementBalanceHistory(balanceChanges)),
            reservationChanges,
            true,
        )
    }

    data class Settlement(
        val execution: SettlementExecution,
        val makerPlan: OrderFillSettlementPlan,
        val takerPlan: OrderFillSettlementPlan,
        val transaction: LedgerTransaction,
    )

    /** 없는 수취 잔고의 previous는 같은 사용자·자산의 0 잔고다. */
    data class BalanceChange(
        val previous: Balance,
        val next: Balance,
    )

    data class ReservationChange(
        val previous: OrderReservation,
        val next: OrderReservation,
    )
}

// 체결 없이 주문장에 들어간 주문마다 빈 목록 wrapper 세 개를 누적하지 않는다.
private val emptyHistoryList: List<Nothing> = Collections.unmodifiableList(emptyList())

private fun <T> immutableHistoryCopy(values: List<T>): List<T> =
    if (values.isEmpty()) emptyHistoryList else Collections.unmodifiableList(values.toList())
