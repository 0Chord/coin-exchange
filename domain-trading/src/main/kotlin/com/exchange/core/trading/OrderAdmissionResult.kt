package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId

/** 예상 가능한 거절만 결과로 표현한다. 잘못 연결된 서버 문맥은 예외로 중단한다. */
sealed class OrderAdmissionResult {
    data class Calculated(
        val candidate: OrderAdmissionCandidate,
    ) : OrderAdmissionResult()

    data class Rejected(
        val reason: Reason,
        val insufficientBalance: InsufficientBalance? = null,
    ) : OrderAdmissionResult()

    data class InsufficientBalance(
        val assetId: AssetId,
        val available: Amount,
        val required: Amount,
        val shortfall: Amount,
    )

    enum class Reason {
        UNSUPPORTED_ORDER_CONDITION,
        INVALID_QUANTITY,
        INVALID_ORDER_IDENTIFIER,
        UNREPRESENTABLE_RESERVATION,
        INSUFFICIENT_BALANCE,
    }
}
