package com.exchange.core.api.ledger.infrastructure.persistence

import com.exchange.core.api.config.LedgerPersistenceConfig
import com.exchange.core.api.ledger.application.PrepareDevelopmentBalanceUseCase
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.ledger.DevelopmentBalanceStore
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceConflictException
import com.exchange.core.ledger.OpeningBalanceRequestConflictException
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.ledger.OpeningBalanceStateException
import com.exchange.core.ledger.OpeningBalanceUnconfirmedException
import com.exchange.core.support.PostgresTestConfiguration
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@DataJpaTest(
    properties = ["spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "exchange.ledger.persistence.enabled=true"],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LedgerPersistenceConfig::class, PostgresTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgresDevelopmentBalanceStoreTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var useCase: PrepareDevelopmentBalanceUseCase

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var namedJdbc: NamedParameterJdbcTemplate

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Autowired private lateinit var ledgerStore: LedgerTransactionStore

    @Autowired private lateinit var balanceStore: BalanceStore
    private val buyer = UserId("buyer")
    private val krw = AssetId("KRW")

    @BeforeEach fun clear() {
        jdbc.update("delete from ledger_postings")
        jdbc.update("delete from ledger_transactions")
        jdbc.update("delete from order_reservations")
        jdbc.update("delete from balance_projection")
    }

    private fun prepare() = useCase.prepare("seed-1", buyer, krw, Amount(1000))

    private fun count(table: String) = jdbc.queryForObject("select count(*) from $table", Long::class.java)!!

    @Test fun `초기 1000과 원장 두 항목을 함께 저장하고 반복에는 원본 결과만 반환한다`() {
        val first = prepare()
        val repeated = prepare()
        assertFalse(first.alreadyPrepared)
        assertTrue(repeated.alreadyPrepared)
        assertEquals(first.copy(alreadyPrepared = true), repeated)
        assertEquals(1000L, repeated.opening.amount.value)
        assertEquals(1L, count("ledger_transactions"))
        assertEquals(2L, count("ledger_postings"))
        assertEquals(listOf(1000L, 0L), balance())
        assertEquals(
            listOf("SYSTEM:KRW:DEVELOPMENT_FUNDING|KRW|DEBIT|1000", "USER:buyer:KRW:AVAILABLE|KRW|CREDIT|1000"),
            jdbc.queryForList(
                "select account_id || '|' || asset_id || '|' || side || '|' || amount as posting from ledger_postings order by posting_sequence",
                String::class.java,
            ),
        )
    }

    @Test fun `수령 영 잔고는 없는 행만 만들고 기존 잔고와 갱신 시각을 유지한다`() {
        useCase.ensureReceivingBalance(buyer, krw)
        useCase.ensureReceivingBalance(buyer, krw)
        assertEquals(1L, count("balance_projection"))
        assertEquals(listOf(0L, 0L), balance())
        assertEquals(0L, count("ledger_transactions"))
        prepare()
        val timestamp = jdbc.queryForObject("select updated_at from balance_projection", java.sql.Timestamp::class.java)
        useCase.ensureReceivingBalance(buyer, krw)
        assertEquals(listOf(1000L, 0L), balance())
        assertEquals(timestamp, jdbc.queryForObject("select updated_at from balance_projection", java.sql.Timestamp::class.java))
    }

    @Test fun `사용 후 반복은 원본 1000을 반환하고 현재 700 또는 hold 300을 건드리지 않는다`() {
        val first = prepare()
        balanceStore.reserve(buyer, krw, Amount(300))
        val held = prepare()
        assertEquals(first.copy(alreadyPrepared = true), held)
        assertEquals(listOf(700L, 300L), balance())
        balanceStore.consumeHold(buyer, krw, Amount(300))
        assertEquals(first.copy(alreadyPrepared = true), prepare())
        assertEquals(listOf(700L, 0L), balance())
        assertEquals(1L, count("ledger_transactions"))
    }

    @Test fun `같은 ID의 다른 금액 사용자 자산은 요청 충돌이고 새 대상 행도 남지 않는다`() {
        prepare()
        for (opening in listOf(
            OpeningBalance("seed-1", buyer, krw, Amount(2000)),
            OpeningBalance("seed-1", UserId("seller"), krw, Amount(1000)),
            OpeningBalance("seed-1", buyer, AssetId("BTC"), Amount(1000)),
        )) {
            assertFailsWith<OpeningBalanceRequestConflictException> { call(opening) }
        }
        assertEquals(1L, count("balance_projection"))
        assertEquals(1L, count("ledger_transactions"))
        assertEquals(listOf(1000L, 0L), balance())
    }

    @Test fun `새 ID로 이미 사용한 계정에 개시하지 않으며 잔고가 다시 영이어도 거절한다`() {
        prepare()
        assertFailsWith<OpeningBalanceConflictException> { useCase.prepare("seed-2", buyer, krw, Amount(1000)) }
        balanceStore.reserve(buyer, krw, Amount(1000))
        balanceStore.consumeHold(buyer, krw, Amount(1000))
        assertFailsWith<OpeningBalanceConflictException> { useCase.prepare("seed-2", buyer, krw, Amount(1000)) }
        assertEquals(listOf(0L, 0L), balance())
        assertEquals(1L, count("ledger_transactions"))
    }

    @Test fun `원장 없는 비영 잔고를 초기화하거나 지급하지 않는다`() {
        jdbc.update("insert into balance_projection(user_id,asset_id,available,hold) values ('buyer','KRW',100,0)")
        assertFailsWith<OpeningBalanceConflictException> { prepare() }
        assertEquals(listOf(100L, 0L), balance())
        assertEquals(0L, count("ledger_transactions"))
        jdbc.update("update balance_projection set available=0,hold=100")
        assertFailsWith<OpeningBalanceConflictException> { prepare() }
        assertEquals(listOf(0L, 100L), balance())
        assertEquals(0L, count("ledger_transactions"))
    }

    @Test fun `잔고 없는 과거 예약과 원장 이력은 자동 복원하거나 새 개시하지 않는다`() {
        jdbc.update(
            """
            insert into order_reservations(market_id,order_id,user_id,side,asset_id,limit_price,
                initial_quantity,remaining_quantity,reserved_amount,remaining_amount,status,fee_remainder_numerator,fee_product_type,fee_tier,fee_schedule_version,
                maker_fee_rate_ppm,taker_fee_rate_ppm,initial_fee_reserve_amount,remaining_fee_reserve_amount)
            values ('m','o','buyer','BUY','KRW',1,1,0,1,0,'RELEASED',0,'SPOT','NORMAL',1,0,0,0,0)
            """.trimIndent(),
        )
        assertFailsWith<OpeningBalanceConflictException> { prepare() }
        assertEquals(0L, count("balance_projection"))
        assertEquals(1L, count("order_reservations"))
        jdbc.update("delete from order_reservations")
        ledgerStore.append(OpeningBalance("history", buyer, krw, Amount(1)).transaction("history", java.time.Instant.EPOCH))
        assertFailsWith<OpeningBalanceConflictException> { prepare() }
        assertEquals(0L, count("balance_projection"))
        assertEquals(1L, count("ledger_transactions"))
    }

    @Test fun `완료 기록에 잔고나 항목이 없거나 방향 종류가 바뀌면 성공으로 숨기지 않는다`() {
        prepare()
        jdbc.update("delete from balance_projection")
        assertFailsWith<OpeningBalanceStateException> { prepare() }
        assertEquals(0L, count("balance_projection"))
        clear()
        prepare()
        jdbc.update("delete from ledger_postings where posting_sequence=2")
        assertFailsWith<OpeningBalanceStateException> { prepare() }
        assertEquals(1L, count("ledger_postings"))
        assertEquals(listOf(1000L, 0L), balance())
        clear()
        prepare()
        jdbc.update("update ledger_postings set side=case side when 'DEBIT' then 'CREDIT' else 'DEBIT' end")
        assertFailsWith<OpeningBalanceStateException> { prepare() }
        clear()
        prepare()
        jdbc.update("update ledger_transactions set transaction_type='RESERVE'")
        assertFailsWith<OpeningBalanceStateException> { prepare() }
    }

    @Test fun `동일 요청 동시 실행은 최초와 이미 완료로 한 번만 지급한다`() {
        val input = OpeningBalance("seed-1", buyer, krw, Amount(1000))
        val outcomes = concurrent(input, input)
        assertEquals(listOf(false, true), outcomes.map { (it as OpeningBalanceResult).alreadyPrepared }.sorted())
        assertEquals(1L, count("ledger_transactions"))
        assertEquals(2L, count("ledger_postings"))
        assertEquals(listOf(1000L, 0L), balance())
    }

    @Test fun `다른 ID 같은 계정 동시 실행은 하나만 개시하고 다른 입력 금액도 한 번만 지급한다`() {
        val first = OpeningBalance("seed-1", buyer, krw, Amount(1000))
        var outcomes = concurrent(first, first.copy(preparationId = "seed-2"))
        assertEquals(1, outcomes.count { it is OpeningBalanceResult })
        assertEquals(1, outcomes.count { it is OpeningBalanceConflictException })
        assertEquals(listOf(1000L, 0L), balance())
        assertEquals(1L, count("ledger_transactions"))
        clear()
        outcomes = concurrent(first, first.copy(amount = Amount(2000)))
        assertEquals(1, outcomes.count { it is OpeningBalanceResult })
        assertEquals(1, outcomes.count { it is OpeningBalanceRequestConflictException })
        val winner = outcomes.filterIsInstance<OpeningBalanceResult>().single()
        assertEquals(listOf(winner.opening.amount.value, 0L), balance())
        assertEquals(1L, count("ledger_transactions"))
    }

    @Test fun `서로 다른 계정의 동일 ID 경합은 원본 고유 제약으로 실패 계정까지 롤백한다`() {
        val first = OpeningBalance("seed-1", buyer, krw, Amount(1000))
        // 두 트랜잭션이 모두 원본 부재를 읽고 INSERT 직전에 만나게 해 고유 제약 경로를 강제한다.
        val arrived = CountDownLatch(2)
        val writer =
            object : LedgerTransactionStore by ledgerStore {
                override fun append(transaction: LedgerTransaction) {
                    arrived.countDown()
                    check(arrived.await(10, TimeUnit.SECONDS))
                    ledgerStore.append(transaction)
                }
            }
        val store = customStore(writer)
        val outcomes = concurrent(first, first.copy(userId = UserId("seller")), store)
        assertEquals(1, outcomes.count { it is OpeningBalanceResult })
        assertEquals(1, outcomes.count { it is OpeningBalanceRequestConflictException })
        assertEquals(1L, count("balance_projection"))
        assertEquals(1L, count("ledger_transactions"))
        assertEquals(2L, count("ledger_postings"))
    }

    @Test fun `다른 ID 다른 계정은 독립적으로 준비된다`() {
        val first = OpeningBalance("seed-1", buyer, krw, Amount(1000))
        val outcomes = concurrent(first, first.copy(preparationId = "seed-2", userId = UserId("seller")))
        assertEquals(2, outcomes.count { it is OpeningBalanceResult && !it.alreadyPrepared })
        assertEquals(2L, count("balance_projection"))
        assertEquals(2L, count("ledger_transactions"))
        assertEquals(2000L, jdbc.queryForObject("select sum(available) from balance_projection", Long::class.java))
    }

    @Test fun `첫 원장 항목 저장 뒤 실제 SQL 실패는 새 영 잔고와 원장 모두 롤백한다`() {
        val failing =
            object : LedgerTransactionStore by ledgerStore {
                override fun append(transaction: LedgerTransaction) {
                    // 원래 writer가 첫 항목을 실제로 쓴 뒤 두 번째 varchar 제약에서 실패하도록 입력만 바꾼다.
                    ledgerStore.append(
                        LedgerTransaction(
                            transaction.ledgerTransactionId,
                            transaction.sourceEventId,
                            transaction.transactionType,
                            transaction.occurredAt,
                            listOf(transaction.postings[0], transaction.postings[1].copy(accountId = "x".repeat(257))),
                        ),
                    )
                }
            }
        assertFailsWith<DataAccessException> { customStore(failing).prepare(OpeningBalance("seed-1", buyer, krw, Amount(1000))) }
        assertEquals(0L, count("ledger_transactions"))
        assertEquals(0L, count("ledger_postings"))
        assertEquals(0L, count("balance_projection"))
        assertFalse(prepare().alreadyPrepared)
        assertEquals(1L, count("ledger_transactions"))
        assertEquals(listOf(1000L, 0L), balance())
    }

    @Test fun `원장 저장 후 잔고 쓰기 실패는 원장도 롤백하고 원래 행과 다른 계정은 유지한다`() {
        useCase.ensureReceivingBalance(buyer, krw)
        useCase.prepare("seller-seed", UserId("seller"), krw, Amount(700))
        val timestamp =
            jdbc.queryForObject(
                "select updated_at from balance_projection where user_id='buyer'",
                java.sql.Timestamp::class.java,
            )
        val failingBalance =
            object : BalanceStore by balanceStore {
                override fun credit(
                    userId: UserId,
                    assetId: AssetId,
                    amount: Amount,
                ): Balance {
                    balanceStore.credit(userId, assetId, amount)
                    jdbc.execute("select 1/0")
                    error("도달하지 않는 구간")
                }
            }
        assertFailsWith<DataAccessException> {
            customStore(ledgerStore, failingBalance).prepare(OpeningBalance("seed-1", buyer, krw, Amount(1000)))
        }
        assertEquals(listOf(0L, 0L), balance())
        assertEquals(1L, count("ledger_transactions"))
        assertEquals(2L, count("ledger_postings"))
        assertEquals(
            timestamp,
            jdbc.queryForObject("select updated_at from balance_projection where user_id='buyer'", java.sql.Timestamp::class.java),
        )
        assertEquals(700L, jdbc.queryForObject("select available from balance_projection where user_id='seller'", Long::class.java))
    }

    @Test fun `원장 기본키 중복은 원본 ID 중복 성공으로 바꾸지 않는다`() {
        useCase.prepare("seller-seed", UserId("seller"), krw, Amount(700))
        val existing = jdbc.queryForObject("select ledger_transaction_id from ledger_transactions", String::class.java)!!
        val writer =
            object : LedgerTransactionStore by ledgerStore {
                override fun append(transaction: LedgerTransaction) =
                    ledgerStore.append(
                        LedgerTransaction(
                            existing,
                            transaction.sourceEventId,
                            transaction.transactionType,
                            transaction.occurredAt,
                            transaction.postings,
                        ),
                    )
            }
        assertFailsWith<DuplicateKeyException> { customStore(writer).prepare(OpeningBalance("seed-1", buyer, krw, Amount(1000))) }
        assertEquals(1L, count("ledger_transactions"))
        assertEquals(1L, count("balance_projection"))
    }

    @Test fun `응답을 버린 뒤 동일 ID를 호출하면 원본을 확인하며 외부 트랜잭션은 거절한다`() {
        prepare()
        assertTrue(prepare().alreadyPrepared)
        assertEquals(1L, count("ledger_transactions"))
        TransactionTemplate(transactionManager).executeWithoutResult {
            assertFailsWith<IllegalStateException> { useCase.prepare("seed-2", UserId("seller"), krw, Amount(1)) }
        }
        assertEquals(1L, count("balance_projection"))
    }

    @Test fun `잘못된 입력은 아무것도 쓰지 않고 허용 상한은 정확히 저장한다`() {
        for (id in listOf(" ", "x".repeat(121))) assertFailsWith<IllegalArgumentException> { useCase.prepare(id, buyer, krw, Amount(1)) }
        assertFailsWith<IllegalArgumentException> { useCase.prepare("zero", buyer, krw, Amount(0)) }
        assertFailsWith<IllegalArgumentException> { useCase.prepare("negative", buyer, krw, Amount(-1)) }
        assertFailsWith<IllegalArgumentException> { useCase.ensureReceivingBalance(UserId("buyer:x"), krw) }
        assertEquals(0L, count("balance_projection"))
        assertEquals(0L, count("ledger_transactions"))
        useCase.prepare("max", buyer, krw, Amount(Long.MAX_VALUE))
        assertEquals(listOf(Long.MAX_VALUE, 0L), balance())
        assertEquals(Long.MAX_VALUE, jdbc.queryForObject("select amount from ledger_postings where side='CREDIT'", Long::class.java))
    }

    @Test fun `V6 데이터에 V7을 적용해 기존 네 종류와 항목 잔고 제약을 보존한다`() {
        val schema = "migration71"
        jdbc.execute("create schema $schema")
        try {
            Flyway
                .configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .target("6")
                .load()
                .migrate()
            for ((index, kind) in listOf("RESERVE", "RELEASE", "SETTLEMENT", "REVERSAL").withIndex()) {
                jdbc.update(
                    "insert into $schema.ledger_transactions values (?, ?, ?, current_timestamp, current_timestamp)",
                    "old-$index",
                    "event-$index",
                    kind,
                )
                jdbc.update(
                    "insert into $schema.ledger_postings(ledger_transaction_id,posting_sequence,account_id,asset_id,side,amount) values (?,1,'old','KRW','DEBIT',1)",
                    "old-$index",
                )
            }
            jdbc.update("insert into $schema.balance_projection(user_id,asset_id,available,hold) values ('old','KRW',7,3)")
            Flyway
                .configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .load()
                .migrate()
            assertEquals(
                listOf("RESERVE", "RELEASE", "SETTLEMENT", "REVERSAL"),
                jdbc.queryForList(
                    "select transaction_type from $schema.ledger_transactions order by ledger_transaction_id",
                    String::class.java,
                ),
            )
            assertEquals(4L, jdbc.queryForObject("select count(*) from $schema.ledger_postings", Long::class.java))
            assertEquals(7L, jdbc.queryForObject("select available from $schema.balance_projection", Long::class.java))
            jdbc.update(
                "insert into $schema.ledger_transactions values ('opening','OPENING:test','OPENING',current_timestamp,current_timestamp)",
            )
            assertFailsWith<DataAccessException> {
                jdbc.update(
                    "insert into $schema.ledger_transactions values ('invalid','invalid','INVALID',current_timestamp,current_timestamp)",
                )
            }
            assertFailsWith<DataAccessException> {
                jdbc.update(
                    "insert into $schema.ledger_transactions values ('duplicate','OPENING:test','OPENING',current_timestamp,current_timestamp)",
                )
            }
            assertFailsWith<DataAccessException> { jdbc.update("update $schema.balance_projection set available=-1") }
            assertFailsWith<DataAccessException> { jdbc.update("update $schema.ledger_postings set amount=0") }
        } finally {
            jdbc.execute("drop schema $schema cascade")
        }
    }

    @Test fun `이미 커밋됐지만 확인 응답이 실패하면 미지급으로 단정하지 않고 동일 ID로 확인한다`() {
        val commitResponseLost =
            object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
                    transactionManager.getTransaction(definition)

                override fun rollback(status: TransactionStatus) = transactionManager.rollback(status)

                override fun commit(status: TransactionStatus) {
                    transactionManager.commit(status)
                    throw TransactionSystemException("테스트: 커밋 후 응답을 받지 못함")
                }
            }
        val store = PostgresDevelopmentBalanceStore(namedJdbc, commitResponseLost, ledgerStore, balanceStore)
        assertFailsWith<OpeningBalanceUnconfirmedException> { store.prepare(OpeningBalance("seed-1", buyer, krw, Amount(1000))) }
        assertEquals(1L, count("ledger_transactions"))
        assertEquals(listOf(1000L, 0L), balance())
        assertTrue(prepare().alreadyPrepared)
        assertEquals(1L, count("ledger_transactions"))
        assertEquals(2L, count("ledger_postings"))
    }

    @Test fun `원본을 읽지 못하는 SQL 실패는 이미 완료로 바꾸지 않는다`() {
        prepare()
        jdbc.execute("alter table ledger_transactions rename to temporarily_unavailable_transactions")
        try {
            assertFailsWith<DataAccessException> { prepare() }
            assertEquals(listOf(1000L, 0L), balance())
        } finally {
            jdbc.execute("alter table temporarily_unavailable_transactions rename to ledger_transactions")
        }
        assertTrue(prepare().alreadyPrepared)
        assertEquals(1L, count("ledger_transactions"))
    }

    @Test fun `JPA가 데이터 접근 예외로 변환한 커밋 장애도 확인 필요로 반환한다`() {
        val translated =
            org.springframework.orm.jpa.vendor.HibernateJpaDialect().translateExceptionIfPossible(
                org.hibernate.TransactionException("커밋 연결 단절", java.sql.SQLTransientConnectionException("응답 유실", "08006")),
            )!!
        assertIs<org.springframework.dao.TransientDataAccessResourceException>(translated)
        val commitResponseLost =
            object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
                    transactionManager.getTransaction(definition)

                override fun rollback(status: TransactionStatus) = transactionManager.rollback(status)

                override fun commit(status: TransactionStatus) {
                    transactionManager.commit(status)
                    throw translated
                }
            }
        val store = PostgresDevelopmentBalanceStore(namedJdbc, commitResponseLost, ledgerStore, balanceStore)
        val failure =
            assertFailsWith<OpeningBalanceUnconfirmedException> { store.prepare(OpeningBalance("seed-1", buyer, krw, Amount(1000))) }
        assertSame(translated, failure.cause)
        assertEquals(1L, count("ledger_transactions"))
        assertEquals(listOf(1000L, 0L), balance())
        assertTrue(prepare().alreadyPrepared)
        assertEquals(1L, count("ledger_transactions"))
        assertEquals(2L, count("ledger_postings"))
    }

    private fun call(opening: OpeningBalance) = useCase.prepare(opening.preparationId, opening.userId, opening.assetId, opening.amount)

    private fun customStore(
        writer: LedgerTransactionStore,
        balances: BalanceStore = balanceStore,
    ) = PostgresDevelopmentBalanceStore(namedJdbc, transactionManager, writer, balances)

    private fun concurrent(
        a: OpeningBalance,
        b: OpeningBalance,
        store: DevelopmentBalanceStore? = null,
    ): List<Any> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(a, b).map { input ->
                    executor.submit<Any> {
                        check(start.await(10, TimeUnit.SECONDS))
                        try {
                            if (store == null) call(input) else store.prepare(input)
                        } catch (error: Exception) {
                            error
                        }
                    }
                }
            start.countDown()
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun balance() =
        jdbc
            .query(
                "select available, hold from balance_projection where user_id='buyer' and asset_id='KRW'",
                { rs, _ -> listOf(rs.getLong(1), rs.getLong(2)) },
            ).single()
}
