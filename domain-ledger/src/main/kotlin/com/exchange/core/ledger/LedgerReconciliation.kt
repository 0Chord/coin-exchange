package com.exchange.core.ledger

import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import java.math.BigInteger

/** 전달받은 기록만으로 사용자·자산별 세 금액을 대조한다. 저장소나 외부 호출은 없다. */
class LedgerReconciliation {
    fun compare(
        scope: LedgerReconciliationScope,
        snapshot: LedgerReconciliationSnapshot,
    ): LedgerReconciliationReport {
        val reservations = snapshot.reservations.filter { it.assetId in scope.assets }
        if (reservations.any { it.status == "ACTIVE" && it.marketId != scope.marketId.value }) {
            return LedgerReconciliationReport(
                scope,
                ReconciliationStatus.UNAVAILABLE,
                failure = ReconciliationFailure.OTHER_MARKET_HOLD,
                detail = "같은 자산에 다른 마켓의 활성 예약이 있어 hold 소유 범위를 검증할 수 없습니다",
            )
        }
        val problems = snapshot.problems.toMutableList()
        val totals = mutableMapOf<AccountKey, MutableMap<ReconciliationItem, BigInteger>>()
        for (transaction in snapshot.transactions) {
            for (posting in transaction.postings) {
                val key =
                    parseUserAccount(posting.accountId, posting.assetId)?.let { (user, item) ->
                        AccountKey(user, posting.assetId) to
                            item
                    }
                if (key == null) {
                    if (posting.accountId !in
                        setOf(
                            "SYSTEM:${posting.assetId.value}:DEVELOPMENT_FUNDING",
                            "SYSTEM:${posting.assetId.value}:FEE_REVENUE",
                        )
                    ) {
                        problems +=
                            ReconciliationDifference(
                                ReconciliationItem.INVALID_RECORD,
                                assetId = posting.assetId,
                                source = transaction.ledgerTransactionId,
                                reason = "계정과 분개 자산을 해석할 수 없습니다: ${posting.accountId}",
                            )
                    }
                    continue
                }
                if (key.first.assetId !in scope.assets) continue
                val amount = BigInteger.valueOf(posting.amount.value)
                val signed = if (posting.side == LedgerPostingSide.CREDIT) amount else -amount
                val account = totals.getOrPut(key.first) { mutableMapOf() }
                account[key.second] = account.getOrDefault(key.second, BigInteger.ZERO) + signed
            }
        }
        val balances = snapshot.balances.filter { it.assetId in scope.assets }
        if (balances.distinctBy { AccountKey(it.userId, it.assetId) }.size != balances.size) {
            problems += ReconciliationDifference(ReconciliationItem.INVALID_RECORD, reason = "같은 사용자·자산 잔고가 중복되었습니다")
        }
        val balancesByKey = balances.associateBy { AccountKey(it.userId, it.assetId) }
        for (reservation in reservations) {
            if (reservation.status !in setOf("ACTIVE", "SETTLED", "RELEASED") ||
                reservation.remainingAmount < 0 ||
                (reservation.status == "ACTIVE" && reservation.remainingAmount == 0L) ||
                (reservation.status != "ACTIVE" && reservation.remainingAmount != 0L)
            ) {
                problems +=
                    ReconciliationDifference(
                        ReconciliationItem.INVALID_RECORD,
                        reservation.userId,
                        reservation.assetId,
                        source = "${reservation.marketId}/${reservation.orderId}",
                        reason = "예약 상태와 남은 금액이 유효하지 않습니다",
                    )
            }
        }
        val keys =
            (
                totals.keys + balancesByKey.keys + reservations.map { AccountKey(it.userId, it.assetId) } +
                    problems.mapNotNull { problem ->
                        val user = problem.userId
                        val asset = problem.assetId
                        if (user != null && asset != null && asset in scope.assets) AccountKey(user, asset) else null
                    }
            ).distinct()
                .sortedWith(compareBy({ it.userId.value }, { it.assetId.value }))
        val differences = problems.toMutableList()
        val accounts =
            keys.map { key ->
                val balance = balancesByKey[key]
                // 손상된 기록이 있으면 정상 기록만의 부분 합계를 원장 잔고로 제시하지 않는다.
                val ledgerAvailable = if (problems.isEmpty()) totals[key]?.get(ReconciliationItem.AVAILABLE) ?: BigInteger.ZERO else null
                val ledgerHold = if (problems.isEmpty()) totals[key]?.get(ReconciliationItem.HOLD) ?: BigInteger.ZERO else null
                val held =
                    reservations
                        .filter { it.userId == key.userId && it.assetId == key.assetId && it.status == "ACTIVE" }
                        .fold(BigInteger.ZERO) { sum, reservation -> sum + BigInteger.valueOf(reservation.remainingAmount) }
                val account =
                    ReconciliationAccount(
                        key.userId,
                        key.assetId,
                        ledgerAvailable,
                        ledgerHold,
                        held,
                        balance?.let { BigInteger.valueOf(it.available.value) },
                        balance?.let { BigInteger.valueOf(it.hold.value) },
                    )
                if (balance == null) {
                    differences +=
                        ReconciliationDifference(
                            ReconciliationItem.MISSING_BALANCE,
                            key.userId,
                            key.assetId,
                            reason = "원장 또는 예약 근거의 DB 잔고 행이 없습니다",
                        )
                } else {
                    difference(account, ReconciliationItem.AVAILABLE, ledgerAvailable, account.available)?.let(differences::add)
                    difference(account, ReconciliationItem.HOLD, ledgerHold, account.hold)?.let(differences::add)
                    difference(account, ReconciliationItem.RESERVATION_HOLD, held, account.hold)?.let(differences::add)
                }
                account
            }
        val status =
            when {
                differences.isNotEmpty() -> ReconciliationStatus.MISMATCHED
                keys.isEmpty() -> ReconciliationStatus.EMPTY
                else -> ReconciliationStatus.MATCHED
            }
        return LedgerReconciliationReport(
            scope,
            status,
            accounts,
            differences.sortedWith(
                compareBy({ it.userId?.value ?: "" }, { it.assetId?.value ?: "" }, { it.item.name }, { it.source ?: "" }),
            ),
        )
    }

    private fun difference(
        account: ReconciliationAccount,
        item: ReconciliationItem,
        expected: BigInteger?,
        actual: BigInteger?,
    ): ReconciliationDifference? =
        if (expected != null && actual != null && expected != actual) {
            ReconciliationDifference(item, account.userId, account.assetId, expected, actual)
        } else {
            null
        }

    companion object {
        /** 손상 거래에서도 식별 가능한 사용자는 같은 해석 규칙으로 검사 대상에 보존한다. */
        fun parseUserAccount(
            accountId: String,
            assetId: AssetId,
        ): Pair<UserId, ReconciliationItem>? {
            if (!accountId.startsWith("USER:")) return null
            for (item in listOf(ReconciliationItem.AVAILABLE, ReconciliationItem.HOLD)) {
                val suffix = ":${assetId.value}:${item.name}"
                if (accountId.endsWith(suffix)) {
                    val user = accountId.removePrefix("USER:").removeSuffix(suffix)
                    if (user.isNotBlank()) return UserId(user) to item
                }
            }
            return null
        }
    }

    private data class AccountKey(
        val userId: UserId,
        val assetId: AssetId,
    )
}
