package com.exchange.core.api.ledger.infrastructure.persistence

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.ledger.LedgerTransactionType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp

/**
 * `ledger_transactions`와 `ledger_postings`에 원장 거래를 추가하는 PostgreSQL 저장소.
 *
 * 거래 정보를 먼저 저장하고, 이를 참조하는 항목들을 입력 목록 순서대로 저장한다.
 * DB의 기본 키와 원본 이벤트 고유 제약으로 중복을 거절하며, 기존 원장 기록을
 * 덮어쓰거나 사용자 잔고 및 주문 예약을 변경하지 않는다.
 *
 * @property jdbcTemplate 이름 기반 SQL parameter를 사용하는 Spring JDBC 도구
 */
open class PostgresLedgerTransactionStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : LedgerTransactionStore {
    /**
     * 거래와 항목을 함께 저장하고, DB 저장 오류가 발생하면 함께 롤백한다.
     *
     * Spring Bean을 통해 호출하면 기존 트랜잭션에 참여하고, 없으면 새로 시작한다.
     * 항목 순번은 목록의 0부터 시작하는 index를 DB의 1부터 시작하는 값으로 변환한다.
     *
     * @param transaction 자산별 차변·대변 균형 검증을 마친 원장 거래
     * @throws DuplicateKeyException 원장 거래 식별자 또는 원본 이벤트 식별자가 이미 저장된 경우
     * @throws DataIntegrityViolationException 컬럼 길이 등 DB의 데이터 제약을 위반한 경우
     */
    @Transactional
    override fun append(transaction: LedgerTransaction) {
        // 항목의 외래 키가 참조할 거래 정보를 먼저 저장한다.
        jdbcTemplate.update(
            """
            insert into ledger_transactions (
                ledger_transaction_id,
                source_event_id,
                transaction_type,
                occurred_at
            ) values (
                :ledgerTransactionId,
                :sourceEventId,
                :transactionType,
                :occurredAt
            )
            """.trimIndent(),
            mapOf(
                "ledgerTransactionId" to transaction.ledgerTransactionId,
                "sourceEventId" to transaction.sourceEventId,
                "transactionType" to transaction.transactionType.name,
                "occurredAt" to Timestamp.from(transaction.occurredAt),
            ),
        )

        for ((index, posting) in transaction.postings.withIndex()) {
            jdbcTemplate.update(
                """
                insert into ledger_postings (
                    ledger_transaction_id,
                    posting_sequence,
                    account_id,
                    asset_id,
                    side,
                    amount
                ) values (
                    :ledgerTransactionId,
                    :postingSequence,
                    :accountId,
                    :assetId,
                    :side,
                    :amount
                )
                """.trimIndent(),
                mapOf(
                    "ledgerTransactionId" to transaction.ledgerTransactionId,
                    "postingSequence" to index + 1,
                    "accountId" to posting.accountId,
                    "assetId" to posting.assetId.value,
                    "side" to posting.side.name,
                    "amount" to posting.amount.value,
                ),
            )
        }
    }

    /** 한 SQL snapshot으로 헤더와 항목을 읽으며, 항목 없는 헤더를 미존재로 숨기지 않는다. */
    override fun findBySourceEventId(sourceEventId: String): LedgerTransaction? =
        jdbcTemplate.query(
            """
            select t.ledger_transaction_id, t.source_event_id, t.transaction_type, t.occurred_at,
                   p.posting_id, p.account_id, p.asset_id, p.side, p.amount
            from ledger_transactions t
            left join ledger_postings p on p.ledger_transaction_id = t.ledger_transaction_id
            where t.source_event_id = :sourceEventId
            order by p.posting_sequence
            """.trimIndent(),
            mapOf("sourceEventId" to sourceEventId),
            ResultSetExtractor { rows ->
                if (!rows.next()) {
                    null
                } else {
                    val id = rows.getString("ledger_transaction_id")
                    val source = rows.getString("source_event_id")
                    val type = rows.getString("transaction_type")
                    val occurredAt = rows.getTimestamp("occurred_at").toInstant()
                    try {
                        val postings = mutableListOf<LedgerPosting>()
                        do {
                            check(rows.getObject("posting_id") != null) { "ledger header has no postings: $source" }
                            postings +=
                                LedgerPosting(
                                    accountId = rows.getString("account_id"),
                                    assetId = AssetId(rows.getString("asset_id")),
                                    side = LedgerPostingSide.valueOf(rows.getString("side")),
                                    amount = Amount(rows.getLong("amount")),
                                )
                        } while (rows.next())
                        LedgerTransaction(
                            id,
                            source,
                            com.exchange.core.ledger.LedgerTransactionType
                                .valueOf(type),
                            occurredAt,
                            postings,
                        )
                    } catch (error: IllegalArgumentException) {
                        throw IllegalStateException("ledger record is invalid: $source", error)
                    }
                }
            },
        )
}
