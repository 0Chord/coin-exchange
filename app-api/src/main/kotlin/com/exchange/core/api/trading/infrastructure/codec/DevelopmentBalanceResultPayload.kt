package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceConflictException
import com.exchange.core.ledger.OpeningBalanceRequestConflictException
import com.exchange.core.ledger.OpeningBalanceResult

enum class DevelopmentBalanceRejectionReason {
    REQUEST_CONTENT_CONFLICT,
    ACCOUNT_ALREADY_USED,
}

sealed interface DevelopmentBalanceResultPayload {
    data class Prepared(
        val result: OpeningBalanceResult,
    ) : DevelopmentBalanceResultPayload

    data class Rejected(
        val opening: OpeningBalance,
        val reason: DevelopmentBalanceRejectionReason,
    ) : DevelopmentBalanceResultPayload

    companion object {
        fun from(
            command: DevelopmentBalanceCommandPayload,
            result: OpeningBalanceResult,
        ): Prepared {
            check(result.opening == command.opening) { "준비 결과가 제출 원본과 다릅니다" }
            if (!result.alreadyPrepared) {
                check(result.ledgerTransactionId == command.ledgerTransactionId && result.occurredAt == command.occurredAt) {
                    "최초 준비 결과가 제출한 원장 문맥과 다릅니다"
                }
            }
            return Prepared(result)
        }

        fun from(
            command: DevelopmentBalanceCommandPayload,
            error: OpeningBalanceRequestConflictException,
        ): Rejected = Rejected(command.opening, DevelopmentBalanceRejectionReason.REQUEST_CONTENT_CONFLICT)

        fun from(
            command: DevelopmentBalanceCommandPayload,
            error: OpeningBalanceConflictException,
        ): Rejected = Rejected(command.opening, DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED)
    }
}
