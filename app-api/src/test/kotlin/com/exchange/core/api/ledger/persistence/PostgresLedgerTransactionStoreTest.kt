package com.exchange.core.api.ledger.persistence

import com.exchange.core.api.config.LedgerPersistenceConfig
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.support.PostgresTestConfiguration
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 실제 PostgreSQL에서 원장 저장, 부분 저장 롤백과 원본 이벤트 중복 거절을 검증한다.
 * 테스트 자체의 트랜잭션을 끄고 저장소의 트랜잭션이 종료된 뒤 DB 상태를 확인한다.
 *
 * 공통 PostgreSQL 설정을 사용하되, 클래스 종료 시 context와 컨테이너를 닫아 다른 클래스와 격리한다.
 */
@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "exchange.ledger.persistence.enabled=true",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LedgerPersistenceConfig::class, PostgresTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgresLedgerTransactionStoreTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var store: LedgerTransactionStore

    /** 테스트 전용 DB에서 외래 키를 참조하는 항목을 먼저 지운 뒤 거래 정보를 지운다. */
    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("delete from ledger_postings")
        jdbcTemplate.update("delete from ledger_transactions")
    }

    @Test
    fun `원장 거래와 항목들을 저장한다`() {
        val transaction =
            LedgerTransaction(
                ledgerTransactionId = "ledger-reserve-1",
                sourceEventId = "order-reserved-1",
                transactionType = LedgerTransactionType.RESERVE,
                occurredAt = Instant.parse("2026-09-05T00:00:00Z"),
                postings =
                    listOf(
                        LedgerPosting(
                            accountId = "USER:buyer:KRW:AVAILABLE",
                            assetId = AssetId("KRW"),
                            side = LedgerPostingSide.DEBIT,
                            amount = Amount(400),
                        ),
                        LedgerPosting(
                            accountId = "USER:buyer:KRW:HOLD",
                            assetId = AssetId("KRW"),
                            side = LedgerPostingSide.CREDIT,
                            amount = Amount(400),
                        ),
                    ),
            )

        store.append(transaction)

        val savedTransactionCount =
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from ledger_transactions
                where ledger_transaction_id = ?
                  and source_event_id = ?
                  and transaction_type = ?
                  and occurred_at = ?
                """.trimIndent(),
                Long::class.java,
                transaction.ledgerTransactionId,
                transaction.sourceEventId,
                transaction.transactionType.name,
                Timestamp.from(transaction.occurredAt),
            )

        assertEquals(1L, savedTransactionCount)

        val savedPostings =
            jdbcTemplate.query(
                """
                select account_id, asset_id, side, amount
                from ledger_postings
                where ledger_transaction_id = ?
                order by posting_sequence
                """.trimIndent(),
                { resultSet, _ ->
                    LedgerPosting(
                        accountId = resultSet.getString("account_id"),
                        assetId = AssetId(resultSet.getString("asset_id")),
                        side =
                            LedgerPostingSide.valueOf(
                                resultSet.getString("side"),
                            ),
                        amount = Amount(resultSet.getLong("amount")),
                    )
                },
                transaction.ledgerTransactionId,
            )

        assertEquals(transaction.postings, savedPostings)
    }

    @Test
    fun `두 번째 항목 저장이 실패하면 거래와 첫 번째 항목도 롤백한다`() {
        // 도메인 검증은 통과하되 DB의 varchar(256) 제약으로 두 번째 INSERT만 실패시킨다.
        val tooLongAccountId = "A".repeat(257)

        val transaction =
            LedgerTransaction(
                ledgerTransactionId = "ledger-rollback-1",
                sourceEventId = "order-reserved-rollback-1",
                transactionType = LedgerTransactionType.RESERVE,
                occurredAt = Instant.parse("2026-09-05T00:00:00Z"),
                postings =
                    listOf(
                        LedgerPosting(
                            accountId = "USER:buyer:KRW:AVAILABLE",
                            assetId = AssetId("KRW"),
                            side = LedgerPostingSide.DEBIT,
                            amount = Amount(400),
                        ),
                        LedgerPosting(
                            accountId = tooLongAccountId,
                            assetId = AssetId("KRW"),
                            side = LedgerPostingSide.CREDIT,
                            amount = Amount(400),
                        ),
                    ),
            )

        assertFailsWith<DataIntegrityViolationException> {
            store.append(transaction)
        }

        val savedTransactionCount =
            jdbcTemplate.queryForObject(
                "select count(*) from ledger_transactions",
                Long::class.java,
            )

        val savedPostingCount =
            jdbcTemplate.queryForObject(
                "select count(*) from ledger_postings",
                Long::class.java,
            )

        assertEquals(0L, savedTransactionCount)
        assertEquals(0L, savedPostingCount)
    }

    @Test
    fun `원장 거래 ID가 달라도 같은 원본 이벤트는 중복 저장할 수 없다`() {
        val originalTransaction =
            LedgerTransaction(
                ledgerTransactionId = "ledger-original-1",
                sourceEventId = "order-reserved-duplicate-1",
                transactionType = LedgerTransactionType.RESERVE,
                occurredAt = Instant.parse("2026-09-05T00:00:00Z"),
                postings =
                    listOf(
                        LedgerPosting(
                            accountId = "USER:buyer:KRW:AVAILABLE",
                            assetId = AssetId("KRW"),
                            side = LedgerPostingSide.DEBIT,
                            amount = Amount(400),
                        ),
                        LedgerPosting(
                            accountId = "USER:buyer:KRW:HOLD",
                            assetId = AssetId("KRW"),
                            side = LedgerPostingSide.CREDIT,
                            amount = Amount(400),
                        ),
                    ),
            )

        // 기본 키 중복이 아니라 source_event_id 고유 제약을 검증하려고 거래 ID는 다르게 둔다.
        val duplicateTransaction =
            LedgerTransaction(
                ledgerTransactionId = "ledger-duplicate-1",
                sourceEventId = originalTransaction.sourceEventId,
                transactionType = originalTransaction.transactionType,
                occurredAt = originalTransaction.occurredAt,
                postings = originalTransaction.postings,
            )

        store.append(originalTransaction)

        assertFailsWith<DuplicateKeyException> {
            store.append(duplicateTransaction)
        }

        val savedTransactionIds =
            jdbcTemplate.queryForList(
                "select ledger_transaction_id from ledger_transactions",
                String::class.java,
            )

        assertEquals(
            listOf(originalTransaction.ledgerTransactionId),
            savedTransactionIds,
        )

        val savedPostingCount =
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from ledger_postings
                where ledger_transaction_id = ?
                """.trimIndent(),
                Long::class.java,
                originalTransaction.ledgerTransactionId,
            )

        assertEquals(2L, savedPostingCount)
    }
}
