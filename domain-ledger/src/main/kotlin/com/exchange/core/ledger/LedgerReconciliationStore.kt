package com.exchange.core.ledger

/** 같은 DB 시점의 기록을 읽는다. 중간 실패 시 부분 결과를 반환하지 않는다. */
interface LedgerReconciliationStore {
    fun read(scope: LedgerReconciliationScope): LedgerReconciliationSnapshot
}

class LedgerReconciliationUnavailableException(
    val failure: ReconciliationFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
