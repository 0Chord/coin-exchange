package com.exchange.core.api.ledger.infrastructure.persistence

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.*
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 원장과 잔고의 커밋 경계를 소유한다. 외부 트랜잭션 중첩은 거절한다.
 * 동일 계정은 잔고 행 잠금으로, 서로 다른 계정의 동일 준비 ID는 원본 고유 제약으로 직렬화한다.
 */
class PostgresDevelopmentBalanceStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val ledgerTransactionStore: LedgerTransactionStore,
    private val balanceStore: BalanceStore,
) : DevelopmentBalanceStore {
    private val transactions = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    override fun prepare(opening: OpeningBalance): OpeningBalanceResult {
        requireNoOuterTransaction()
        try {
            return prepareTransaction { prepareLocked(opening) }
        } catch (duplicate: DuplicateKeyException) {
            if (!sourceIdConflict(duplicate)) throw duplicate
            // 실패한 트랜잭션이 종료된 뒤 새 경계에서만 원본을 대조한다.
            return prepareTransaction {
                val original = findTransaction(opening.sourceEventId)
                    ?: throw OpeningBalanceUnconfirmedException(duplicate)
                repeated(opening, original)
            }
        }
    }

    private fun prepareTransaction(action: () -> OpeningBalanceResult): OpeningBalanceResult {
        var bodyFinished = false
        try {
            return transactions.execute { action().also { bodyFinished = true } }
        } catch (error: RuntimeException) {
            // JPA는 커밋 장애도 DataAccessException으로 변환한다. 본문 종료 후 오류는 롤백을 단정하지 않는다.
            if (bodyFinished || error is TransactionException) throw OpeningBalanceUnconfirmedException(error)
            throw error
        }
    }

    override fun ensureReceivingBalance(userId: UserId, assetId: AssetId) {
        OpeningBalance.validateAccount(userId, assetId)
        requireNoOuterTransaction()
        transactions.executeWithoutResult { ensureRow(userId, assetId) }
    }

    private fun prepareLocked(opening: OpeningBalance): OpeningBalanceResult {
        findTransaction(opening.sourceEventId)?.let { return repeated(opening, it) }
        ensureRow(opening.userId, opening.assetId)
        val balance = lockBalance(opening.userId, opening.assetId)
            ?: throw OpeningBalanceStateException("잠글 잔고가 없습니다")
        findTransaction(opening.sourceEventId)?.let { return repeated(opening, it) }
        opening.requireUnused(balance, hasHistory(opening))
        // PostgreSQL timestamp의 마이크로초 정밀도와 최초 결과를 맞춰 재호출에도 같은 시각을 돌려준다.
        val transaction = opening.transaction(UUID.randomUUID().toString(), Instant.now().truncatedTo(ChronoUnit.MICROS))
        ledgerTransactionStore.append(transaction)
        balanceStore.credit(opening.userId, opening.assetId, opening.amount)
        return OpeningBalanceResult(opening, transaction.ledgerTransactionId, transaction.occurredAt, false)
    }

    private fun repeated(opening: OpeningBalance, transaction: LedgerTransaction): OpeningBalanceResult {
        val original = OpeningBalance.fromTransaction(transaction)
        if (original != opening) throw OpeningBalanceRequestConflictException("같은 준비 ID에 다른 입력이 저장되어 있습니다")
        if (lockBalance(original.userId, original.assetId) == null) {
            throw OpeningBalanceStateException("완료 기록의 잔고가 없습니다. 자동 복원하지 않습니다")
        }
        return OpeningBalanceResult(original, transaction.ledgerTransactionId, transaction.occurredAt, true)
    }

    private fun ensureRow(userId: UserId, assetId: AssetId) {
        jdbcTemplate.update("""
            insert into balance_projection (user_id, asset_id, available, hold)
            values (:userId, :assetId, 0, 0)
            on conflict (user_id, asset_id) do nothing
        """.trimIndent(), accountParameters(userId, assetId))
    }

    private fun lockBalance(userId: UserId, assetId: AssetId): Balance? = jdbcTemplate.query("""
        select user_id, asset_id, available, hold from balance_projection
        where user_id = :userId and asset_id = :assetId for update
    """.trimIndent(), accountParameters(userId, assetId)) { rs, _ ->
        Balance(UserId(rs.getString("user_id")), AssetId(rs.getString("asset_id")),
            Amount(rs.getLong("available")), Amount(rs.getLong("hold")))
    }.singleOrNull()

    private fun hasHistory(opening: OpeningBalance): Boolean = jdbcTemplate.queryForObject("""
        select exists(select 1 from ledger_postings
            where asset_id = :assetId and account_id in (:availableAccount, :holdAccount))
            or exists(select 1 from order_reservations where user_id = :userId and asset_id = :assetId)
    """.trimIndent(), accountParameters(opening.userId, opening.assetId) + mapOf(
        "availableAccount" to opening.availableAccount, "holdAccount" to opening.holdAccount,
    ), Boolean::class.java)!!

    private fun findTransaction(sourceEventId: String): LedgerTransaction? {
        val headers = jdbcTemplate.query("""
            select ledger_transaction_id, source_event_id, transaction_type, occurred_at
            from ledger_transactions where source_event_id = :sourceEventId
        """.trimIndent(), mapOf("sourceEventId" to sourceEventId)) { rs, _ ->
            Triple(rs.getString("ledger_transaction_id"), rs.getString("transaction_type"), rs.getTimestamp("occurred_at").toInstant())
        }
        val header = headers.singleOrNull() ?: return null
        try {
            val postings = jdbcTemplate.query("""
                select account_id, asset_id, side, amount from ledger_postings
                where ledger_transaction_id = :id order by posting_sequence
            """.trimIndent(), mapOf("id" to header.first)) { rs, _ ->
                LedgerPosting(rs.getString("account_id"), AssetId(rs.getString("asset_id")),
                    LedgerPostingSide.valueOf(rs.getString("side")), Amount(rs.getLong("amount")))
            }
            return LedgerTransaction(header.first, sourceEventId, LedgerTransactionType.valueOf(header.second), header.third, postings)
        } catch (error: IllegalArgumentException) {
            throw OpeningBalanceStateException("저장된 원장 항목이 불완전합니다", error)
        }
    }

    private fun sourceIdConflict(error: DuplicateKeyException): Boolean =
        generateSequence<Throwable>(error) { it.cause }.any {
            it is SQLException && it.sqlState == "23505" &&
                it.message.orEmpty().contains("uk_ledger_transactions_source_event")
        }

    private fun requireNoOuterTransaction() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "개시 준비는 외부 트랜잭션 없이 호출해야 합니다" }
    }

    private fun accountParameters(userId: UserId, assetId: AssetId) = mapOf("userId" to userId.value, "assetId" to assetId.value)
}
