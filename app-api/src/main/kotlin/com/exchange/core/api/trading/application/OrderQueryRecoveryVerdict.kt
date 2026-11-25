package com.exchange.core.api.trading.application

import com.exchange.core.ledger.ReconciliationDifference

enum class OrderQueryRecoveryStatus { RECOVERING, READY, BLOCKED }

enum class OrderQueryRecoveryFailure {
    LEDGER_INCOMPLETE,
    LEDGER_MISMATCH,
    BALANCE_MISMATCH,
    ORDERS_MISMATCH,
    DB_UNAVAILABLE,
    SOURCE_UNAVAILABLE,
    SOURCE_PROGRESS_MISMATCH,
    SCOPE_UNSUPPORTED,
}

/** 복구 당시의 판정과 그 근거가 된 자금 위치다. */
data class OrderQueryRecoveryVerdict(
    val status: OrderQueryRecoveryStatus,
    val reason: OrderQueryRecoveryFailure? = null,
    val logPosition: Long? = null,
    val ledgerThrough: Long? = null,
    val differences: List<ReconciliationDifference> = emptyList(),
)
