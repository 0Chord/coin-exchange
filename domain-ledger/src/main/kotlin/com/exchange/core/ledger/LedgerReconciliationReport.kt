package com.exchange.core.ledger

import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import java.math.BigInteger

enum class ReconciliationStatus { MATCHED, MISMATCHED, UNAVAILABLE, EMPTY }

enum class ReconciliationItem { AVAILABLE, HOLD, RESERVATION_HOLD, MISSING_BALANCE, INVALID_RECORD }

enum class ReconciliationFailure { DB_READ_FAILED, OUTER_TRANSACTION, OTHER_MARKET_HOLD }

/** 금액 차이는 실제 DB 값에서 비교 기준을 뺀 값이다. 누락/손상은 0으로 꾸미지 않는다. */
data class ReconciliationDifference(
    val item: ReconciliationItem,
    val userId: UserId? = null,
    val assetId: AssetId? = null,
    val expected: BigInteger? = null,
    val actual: BigInteger? = null,
    val source: String? = null,
    val reason: String? = null,
) {
    val delta: BigInteger? get() = if (expected != null && actual != null) actual - expected else null
}

data class ReconciliationAccount(
    val userId: UserId,
    val assetId: AssetId,
    val ledgerAvailable: BigInteger?,
    val ledgerHold: BigInteger?,
    val reservationHold: BigInteger,
    val available: BigInteger?,
    val hold: BigInteger?,
)

/** MATCHED는 읽은 자금 기록의 일치이며, 주문 복구 완료나 거래 재개 허가는 아니다. */
data class LedgerReconciliationReport(
    val scope: LedgerReconciliationScope,
    val status: ReconciliationStatus,
    val accounts: List<ReconciliationAccount> = emptyList(),
    val differences: List<ReconciliationDifference> = emptyList(),
    val failure: ReconciliationFailure? = null,
    val detail: String? = null,
)
