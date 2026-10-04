package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import java.time.Instant

/** 개발용 최초 자금의 원본 입력. DB 저장과 현재 잔고 조회는 하지 않는다. */
data class OpeningBalance(
    val preparationId: String,
    val userId: UserId,
    val assetId: AssetId,
    val amount: Amount,
) {
    init {
        require(preparationId.isNotBlank() && validText(sourceEventId, 128)) { "잘못된 준비 식별자" }
        validateAccount(userId, assetId)
        require(amount.value > 0) { "개시 금액은 양수여야 합니다" }
    }

    val sourceEventId: String get() = "OPENING:$preparationId"
    val availableAccount: String get() = "USER:${userId.value}:${assetId.value}:AVAILABLE"
    val holdAccount: String get() = "USER:${userId.value}:${assetId.value}:HOLD"

    fun transaction(
        id: String,
        time: Instant,
    ): LedgerTransaction =
        LedgerTransaction(
            id,
            sourceEventId,
            LedgerTransactionType.OPENING,
            time,
            listOf(
                LedgerPosting("SYSTEM:${assetId.value}:DEVELOPMENT_FUNDING", assetId, LedgerPostingSide.DEBIT, amount),
                LedgerPosting(availableAccount, assetId, LedgerPostingSide.CREDIT, amount),
            ),
        )

    /** 잔고가 다시 0이 됐더라도 과거 사용 이력이 있으면 재개시하지 않는다. */
    fun requireUnused(
        balance: Balance,
        hasHistory: Boolean,
    ) {
        if (balance.userId != userId || balance.assetId != assetId ||
            balance.available.value != 0L || balance.hold.value != 0L || hasHistory
        ) {
            throw OpeningBalanceConflictException("이미 사용했거나 설명되지 않는 잔고입니다")
        }
    }

    companion object {
        fun validateAccount(
            userId: UserId,
            assetId: AssetId,
        ) {
            require(validText(userId.value, 64) && ':' !in userId.value) { "잘못된 개시 사용자" }
            require(validText(assetId.value, 64) && ':' !in assetId.value) { "잘못된 개시 자산" }
        }

        private fun validText(
            value: String,
            max: Int,
        ) = value.isNotBlank() &&
            value.codePointCount(0, value.length) <= max && '\u0000' !in value

        /** 균형 검증에 더해 개시의 정확한 두 계정·방향·금액을 대조한다. */
        fun fromTransaction(transaction: LedgerTransaction): OpeningBalance {
            try {
                require(transaction.transactionType == LedgerTransactionType.OPENING)
                require(transaction.sourceEventId.startsWith("OPENING:"))
                require(transaction.postings.size == 2)
                val credit = transaction.postings[1]
                val parts = credit.accountId.split(':')
                require(parts.size == 4 && parts[0] == "USER" && parts[3] == "AVAILABLE")
                val opening =
                    OpeningBalance(
                        transaction.sourceEventId.removePrefix("OPENING:"),
                        UserId(parts[1]),
                        AssetId(parts[2]),
                        credit.amount,
                    )
                require(credit.assetId == opening.assetId)
                require(transaction.postings == opening.transaction(transaction.ledgerTransactionId, transaction.occurredAt).postings)
                return opening
            } catch (error: IllegalArgumentException) {
                throw OpeningBalanceStateException("개시 원장의 종류·항목이 완전하지 않습니다", error)
            }
        }
    }
}

/** 현재 잔고를 섞지 않은 원본 준비 결과. 재호출에도 최초 원장 ID·시각을 유지한다. */
data class OpeningBalanceResult(
    val opening: OpeningBalance,
    val ledgerTransactionId: String,
    val occurredAt: Instant,
    val alreadyPrepared: Boolean,
)

class OpeningBalanceRequestConflictException(
    message: String,
) : IllegalStateException(message)

class OpeningBalanceConflictException(
    message: String,
) : IllegalStateException(message)

class OpeningBalanceStateException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** DB 오류만으로 지급 성공 여부를 판단하지 않는다. 같은 준비 ID로 다시 확인해야 한다. */
class OpeningBalanceUnconfirmedException(
    cause: Throwable,
) : IllegalStateException("준비 결과를 확인하지 못했습니다. 같은 준비 ID로 다시 확인하세요", cause)
