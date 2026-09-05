package com.exchange.core.api.ledger.persistence

import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionStore
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
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
}
