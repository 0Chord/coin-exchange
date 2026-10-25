package com.exchange.core.trading

import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementPlan
import com.exchange.core.order.OrderReservation
import java.util.Collections

/** 전체 계산을 마쳤지만 실제 잔고·예약·원장에는 반영하지 않은 한 주문의 정산 후보. */
class OrderSettlementCandidate internal constructor(
    val order: OrderAdmissionInput,
    val market: MarketDefinition,
    settlements: List<Settlement>,
    balanceChanges: List<BalanceChange>,
    reservationChanges: List<ReservationChange>,
) {
    val settlements: List<Settlement> = Collections.unmodifiableList(settlements.toList())
    val balanceChanges: List<BalanceChange> = Collections.unmodifiableList(balanceChanges.toList())
    val reservationChanges: List<ReservationChange> = Collections.unmodifiableList(reservationChanges.toList())

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
