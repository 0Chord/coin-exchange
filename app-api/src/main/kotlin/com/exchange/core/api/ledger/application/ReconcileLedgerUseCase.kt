package com.exchange.core.api.ledger.application

import com.exchange.core.ledger.LedgerReconciliation
import com.exchange.core.ledger.LedgerReconciliationReport
import com.exchange.core.ledger.LedgerReconciliationScope
import com.exchange.core.ledger.LedgerReconciliationStore
import com.exchange.core.ledger.LedgerReconciliationUnavailableException
import com.exchange.core.ledger.ReconciliationStatus

/** 읽기가 모두 끝난 뒤 비교한다. 조회 실패는 불일치나 빈 대상과 구분한다. */
class ReconcileLedgerUseCase(
    private val store: LedgerReconciliationStore,
) {
    fun reconcile(scope: LedgerReconciliationScope): LedgerReconciliationReport =
        try {
            LedgerReconciliation().compare(scope, store.read(scope))
        } catch (failure: LedgerReconciliationUnavailableException) {
            LedgerReconciliationReport(scope, ReconciliationStatus.UNAVAILABLE, failure = failure.failure, detail = failure.message)
        }
}
