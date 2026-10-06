package com.exchange.core.api.ledger.infrastructure.persistence

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerReconciliation
import com.exchange.core.ledger.LedgerReconciliationScope
import com.exchange.core.ledger.LedgerReconciliationSnapshot
import com.exchange.core.ledger.LedgerReconciliationStore
import com.exchange.core.ledger.LedgerReconciliationUnavailableException
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.ReconciliationDifference
import com.exchange.core.ledger.ReconciliationFailure
import com.exchange.core.ledger.ReconciliationItem
import com.exchange.core.ledger.ReservationHold
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant

/**
 * 원장·잔고·예약을 PostgreSQL의 한 읽기 전용 스냅샷에서 가져온다.
 * 낮은 격리 수준의 외부 트랜잭션에 합류하지 않으며, 조회 실패 시 전체 결과를 버린다.
 * 읽을 수 있지만 손상된 기록은 원본 식별자와 함께 불일치 근거로 보존한다.
 */
class PostgresLedgerReconciliationStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : LedgerReconciliationStore {
    private val transaction =
        TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            isReadOnly = true
        }

    override fun read(scope: LedgerReconciliationScope): LedgerReconciliationSnapshot {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw LedgerReconciliationUnavailableException(ReconciliationFailure.OUTER_TRANSACTION, "대조 검사는 외부 트랜잭션 밖에서 호출해야 합니다")
        }
        var stage = "읽기 전용 트랜잭션 준비"
        try {
            return checkNotNull(
                transaction.execute {
                    // Spring의 힌트에만 의존하지 않고 실제 PostgreSQL 트랜잭션에 쓰기를 금지한다.
                    jdbc.jdbcTemplate.execute("SET TRANSACTION READ ONLY")
                    val problems = mutableListOf<ReconciliationDifference>()
                    stage = "원장 조회"
                    val rows = jdbc.query(LEDGER_SQL, emptyMap<String, Any>()) { row, _ -> rawLedger(row) }
                    val transactions =
                        rows.groupBy { it.id }.mapNotNull { (id, entries) ->
                            val owners =
                                entries
                                    .mapNotNull { entry ->
                                        val asset = entry.asset?.takeIf { it.isNotBlank() }?.let(::AssetId) ?: return@mapNotNull null
                                        val user =
                                            entry.account?.let { LedgerReconciliation.parseUserAccount(it, asset)?.first }
                                                ?: return@mapNotNull null
                                        user to asset
                                    }.distinct()
                            record(problems, "원장/$id", owners) {
                                val header = entries.first()
                                require(entries.map { it.sequence } == (1..entries.size).toList()) { "분개가 누락됐거나 순서가 유효하지 않습니다" }
                                LedgerTransaction(
                                    header.id,
                                    header.source,
                                    LedgerTransactionType.valueOf(header.type),
                                    header.time,
                                    entries.map { entry ->
                                        LedgerPosting(
                                            requireNotNull(entry.account),
                                            AssetId(requireNotNull(entry.asset)),
                                            LedgerPostingSide.valueOf(requireNotNull(entry.side)),
                                            Amount(requireNotNull(entry.amount)),
                                        )
                                    },
                                )
                            }
                        }
                    val parameters = mapOf("assets" to scope.assets.map { it.value })
                    stage = "잔고 조회"
                    val balances =
                        jdbc
                            .query(
                                "select user_id, asset_id, available, hold from balance_projection where asset_id in (:assets) order by user_id, asset_id",
                                parameters,
                            ) { row, _ ->
                                record(problems, "잔고/${row.getString("user_id")}/${row.getString("asset_id")}", owners(row)) {
                                    Balance(
                                        UserId(row.getString("user_id")),
                                        AssetId(row.getString("asset_id")),
                                        Amount(row.getLong("available")),
                                        Amount(row.getLong("hold")),
                                    )
                                }
                            }.filterNotNull()
                    stage = "예약 조회"
                    val reservations =
                        jdbc
                            .query(
                                "select market_id, order_id, user_id, asset_id, status, remaining_amount from order_reservations where asset_id in (:assets) order by market_id, order_id",
                                parameters,
                            ) { row, _ ->
                                record(problems, "예약/${row.getString("market_id")}/${row.getString("order_id")}", owners(row)) {
                                    ReservationHold(
                                        row.getString("market_id"),
                                        row.getString("order_id"),
                                        UserId(row.getString("user_id")),
                                        AssetId(row.getString("asset_id")),
                                        row.getString("status"),
                                        row.getLong("remaining_amount"),
                                    )
                                }
                            }.filterNotNull()
                    LedgerReconciliationSnapshot(transactions, balances, reservations, problems)
                },
            )
        } catch (failure: DataAccessException) {
            throw LedgerReconciliationUnavailableException(
                ReconciliationFailure.DB_READ_FAILED,
                "$stage 실패: ${failure.mostSpecificCause.message}",
                failure,
            )
        } catch (failure: TransactionException) {
            throw LedgerReconciliationUnavailableException(
                ReconciliationFailure.DB_READ_FAILED,
                "$stage 트랜잭션 실패: ${failure.message}",
                failure,
            )
        }
    }

    private fun <T> record(
        problems: MutableList<ReconciliationDifference>,
        source: String,
        owners: List<Pair<UserId, AssetId>> = emptyList(),
        convert: () -> T,
    ): T? =
        try {
            convert()
        } catch (failure: IllegalArgumentException) {
            if (owners.isEmpty()) {
                problems += ReconciliationDifference(ReconciliationItem.INVALID_RECORD, source = source, reason = failure.message)
            } else {
                for ((user, asset) in owners) {
                    problems +=
                        ReconciliationDifference(ReconciliationItem.INVALID_RECORD, user, asset, source = source, reason = failure.message)
                }
            }
            null
        }

    private fun owners(row: ResultSet): List<Pair<UserId, AssetId>> {
        val user = row.getString("user_id").takeIf { it.isNotBlank() } ?: return emptyList()
        val asset = row.getString("asset_id").takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(UserId(user) to AssetId(asset))
    }

    private fun rawLedger(row: ResultSet) =
        RawLedger(
            row.getString("ledger_transaction_id"),
            row.getString("source_event_id"),
            row.getString("transaction_type"),
            row.getTimestamp("occurred_at").toInstant(),
            row.getObject("posting_sequence")?.let { (it as Number).toInt() },
            row.getString("account_id"),
            row.getString("asset_id"),
            row.getString("side"),
            row.getObject("amount")?.let { (it as Number).toLong() },
        )

    private data class RawLedger(
        val id: String,
        val source: String,
        val type: String,
        val time: Instant,
        val sequence: Int?,
        val account: String?,
        val asset: String?,
        val side: String?,
        val amount: Long?,
    )

    companion object {
        private const val LEDGER_SQL = """
            select t.ledger_transaction_id, t.source_event_id, t.transaction_type, t.occurred_at,
                   p.posting_sequence, p.account_id, p.asset_id, p.side, p.amount
            from ledger_transactions t left join ledger_postings p on p.ledger_transaction_id=t.ledger_transaction_id
            order by t.ledger_transaction_id, p.posting_sequence
        """
    }
}
