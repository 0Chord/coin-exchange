package com.exchange.core.api.ledger.infrastructure.persistence

import com.exchange.core.api.config.LedgerPersistenceConfig
import com.exchange.core.api.ledger.application.PrepareDevelopmentBalanceUseCase
import com.exchange.core.api.ledger.application.ReconcileLedgerUseCase
import com.exchange.core.api.order.application.OrderFundingService
import com.exchange.core.api.order.application.OrderReservationReleaseService
import com.exchange.core.api.order.application.TradeSettlementService
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerReconciliationReport
import com.exchange.core.ledger.LedgerReconciliationScope
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.ReconciliationFailure
import com.exchange.core.ledger.ReconciliationItem
import com.exchange.core.ledger.ReconciliationStatus
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.Side
import com.exchange.core.support.PostgresTestConfiguration
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigInteger
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest(
    properties = ["spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "exchange.ledger.persistence.enabled=true"],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LedgerPersistenceConfig::class, PostgresTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgresLedgerReconciliationStoreTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var useCase: ReconcileLedgerUseCase

    @Autowired private lateinit var opening: PrepareDevelopmentBalanceUseCase

    @Autowired private lateinit var funding: OrderFundingService

    @Autowired private lateinit var release: OrderReservationReleaseService

    @Autowired private lateinit var settlement: TradeSettlementService

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var ledgerStore: LedgerTransactionStore

    private val buyer = UserId("buyer")
    private val seller = UserId("seller")
    private val btc = AssetId("BTC")
    private val krw = AssetId("KRW")
    private val market = MarketDefinition(MarketId("BTC-KRW"), btc, krw, 0)
    private val scope = LedgerReconciliationScope(market.marketId, btc, krw)

    private fun policy(
        maker: Long = 0,
        taker: Long = 0,
    ) = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(maker), FeeRate(taker)))

    @BeforeEach fun clear() {
        for (table in listOf(
            "ledger_postings",
            "ledger_transactions",
            "order_reservations",
            "balance_projection",
        )) {
            jdbc.update("delete from $table")
        }
    }

    private fun reserve(
        price: Long = 100,
        quantity: Long = 3,
        fees: TradingFeePolicySnapshot = policy(),
    ) = funding.reserve(market, OrderId("buy"), buyer, Side.BUY, Price(price), Quantity(quantity), fees)

    private fun seed(amount: Long = 1000) = opening.prepare("buyer-opening", buyer, krw, Amount(amount))

    private fun result() = useCase.reconcile(scope)

    private fun buyerKrw(report: LedgerReconciliationReport) = report.accounts.single { it.userId == buyer && it.assetId == krw }

    private fun number(value: Long) = BigInteger.valueOf(value)

    private fun databaseContents() =
        listOf("ledger_transactions", "ledger_postings", "balance_projection", "order_reservations")
            .associateWith { jdbc.queryForList("select * from $it order by 1, 2") }

    @Test fun `초기 자금과 예약을 실제 DB에서 대조하고 반복 조회는 모든 저장값을 유지한다`() {
        seed()
        reserve()
        val before = databaseContents()
        repeat(2) {
            val report = result()
            assertEquals(ReconciliationStatus.MATCHED, report.status, report.detail)
            val account = buyerKrw(report)
            assertEquals(number(700), account.ledgerAvailable)
            assertEquals(number(300), account.ledgerHold)
            assertEquals(number(300), account.reservationHold)
            assertTrue(report.differences.isEmpty())
        }
        assertEquals(before, databaseContents())
    }

    @Test fun `DB 사용 가능 금액만 1 늘어나면 원장 기준과 차이 1을 보고한다`() {
        seed()
        reserve()
        jdbc.update("update balance_projection set available=701 where user_id='buyer' and asset_id='KRW'")
        val report = result()
        assertEquals(ReconciliationStatus.MISMATCHED, report.status, report.detail)
        val difference = report.differences.single()
        assertEquals(ReconciliationItem.AVAILABLE, difference.item)
        assertEquals(number(700), difference.expected)
        assertEquals(number(701), difference.actual)
        assertEquals(number(1), difference.delta)
    }

    private fun tradeSetup(
        amount: Long = 1000,
        sellerAmount: Long = 10,
    ) {
        seed(amount)
        opening.prepare("seller-opening", seller, btc, Amount(sellerAmount))
        opening.ensureReceivingBalance(buyer, btc)
        opening.ensureReceivingBalance(seller, krw)
    }

    private fun sell(
        price: Long = 100,
        quantity: Long = 1,
        fees: TradingFeePolicySnapshot = policy(),
    ) = funding.reserve(market, OrderId("sell"), seller, Side.SELL, Price(price), Quantity(quantity), fees)

    private fun trade(
        price: Long = 100,
        quantity: Long = 1,
    ) = settlement.settle(
        market,
        TradeExecuted(market.marketId, 1, OrderId("sell"), OrderId("buy"), seller, buyer, Side.BUY, Price(price), Quantity(quantity)),
    )

    private fun cancelled() {
        tradeSetup()
        reserve()
        sell()
        trade()
        release.release(market.marketId, OrderId("buy"))
    }

    @Test fun `한 개 체결하고 두 개 취소한 뒤 네 잔고와 예약 합계를 대조한다`() {
        cancelled()
        val before = databaseContents()
        val report = result()
        assertEquals(ReconciliationStatus.MATCHED, report.status, report.detail)
        assertEquals(number(900), buyerKrw(report).ledgerAvailable)
        assertEquals(number(0), buyerKrw(report).reservationHold)
        assertEquals(
            mapOf("buyer/BTC" to 1L, "buyer/KRW" to 900L, "seller/BTC" to 9L, "seller/KRW" to 100L),
            report.accounts.associate { "${it.userId.value}/${it.assetId.value}" to it.available!!.longValueExact() },
        )
        assertEquals(before, databaseContents())
        jdbc.update("update balance_projection set available=901 where user_id='buyer' and asset_id='KRW'")
        val damaged = databaseContents()
        val difference = result().differences.single()
        assertEquals(ReconciliationItem.AVAILABLE, difference.item)
        assertEquals(number(900), difference.expected)
        assertEquals(number(901), difference.actual)
        assertEquals(number(1), difference.delta)
        assertEquals(damaged, databaseContents())
    }

    @Test fun `수수료와 가격 개선 반환이 있는 실제 정산도 자산별로 일치한다`() {
        tradeSetup(1000000)
        val fees = policy(5000, 10000)
        reserve(100000, 2, fees)
        sell(90000, 2, fees)
        trade(90000, 2)
        val report = result()
        assertEquals(ReconciliationStatus.MATCHED, report.status, report.detail)
        assertEquals(
            mapOf("buyer/BTC" to 2L, "buyer/KRW" to 818200L, "seller/BTC" to 8L, "seller/KRW" to 179100L),
            report.accounts.associate { "${it.userId.value}/${it.assetId.value}" to it.available!!.longValueExact() },
        )
        assertTrue(report.accounts.all { it.hold == number(0) && it.reservationHold == number(0) })
        assertEquals(
            2700L,
            jdbc.queryForObject("select sum(amount) from ledger_postings where account_id='SYSTEM:KRW:FEE_REVENUE'", Long::class.java),
        )
    }

    @Test fun `반환 원장만 삭제하거나 효과를 중복 기록하면 정상 잔고와 다른 합계를 보고한다`() {
        cancelled()
        val releaseId =
            jdbc.queryForObject(
                "select ledger_transaction_id from ledger_transactions where transaction_type='RELEASE'",
                String::class.java,
            )!!
        jdbc.update(
            "insert into ledger_transactions(ledger_transaction_id,source_event_id,transaction_type,occurred_at) select 'duplicate','duplicate',transaction_type,occurred_at from ledger_transactions where ledger_transaction_id=?",
            releaseId,
        )
        jdbc.update(
            "insert into ledger_postings(ledger_transaction_id,posting_sequence,account_id,asset_id,side,amount) select 'duplicate',posting_sequence,account_id,asset_id,side,amount from ledger_postings where ledger_transaction_id=?",
            releaseId,
        )
        val duplicate = result()
        assertEquals(ReconciliationStatus.MISMATCHED, duplicate.status)
        assertEquals(number(1100), buyerKrw(duplicate).ledgerAvailable)
        assertEquals(number(-200), buyerKrw(duplicate).ledgerHold)
        jdbc.update("delete from ledger_postings where ledger_transaction_id in (?, 'duplicate')", releaseId)
        jdbc.update("delete from ledger_transactions where ledger_transaction_id in (?, 'duplicate')", releaseId)
        val missing = result()
        assertEquals(ReconciliationStatus.MISMATCHED, missing.status)
        assertEquals(number(700), buyerKrw(missing).ledgerAvailable)
        assertEquals(number(200), buyerKrw(missing).ledgerHold)
    }

    @Test fun `분개 없는 머리글과 일부 삭제와 계정 자산 손상은 부분 합계로 통과시키지 않는다`() {
        for (damage in listOf("header", "posting", "asset")) {
            clear()
            seed()
            when (damage) {
                "header" -> jdbc.update("delete from ledger_postings")
                "posting" -> jdbc.update("delete from ledger_postings where posting_sequence=1")
                "asset" -> jdbc.update("update ledger_postings set account_id='USER:buyer:BTC:AVAILABLE' where posting_sequence=2")
            }
            val before = databaseContents()
            val report = result()
            assertEquals(ReconciliationStatus.MISMATCHED, report.status, damage)
            assertTrue(report.differences.any { it.item == ReconciliationItem.INVALID_RECORD && it.source != null }, damage)
            assertEquals(before, databaseContents())
        }
    }

    @Test fun `잔고 행을 삭제해도 원장과 예약 사용자를 검사 대상에서 없애지 않는다`() {
        seed()
        reserve()
        jdbc.update("delete from balance_projection")
        val report = result()
        assertEquals(ReconciliationStatus.MISMATCHED, report.status, report.detail)
        assertEquals(1, report.accounts.size)
        assertNull(buyerKrw(report).available)
        assertEquals(ReconciliationItem.MISSING_BALANCE, report.differences.single().item)
        jdbc.update("delete from ledger_postings")
        jdbc.update("delete from ledger_transactions")
        assertEquals(ReconciliationItem.MISSING_BALANCE, result().differences.single().item)
    }

    @Test fun `빈 대상과 실제 영 잔고 그리고 관련 없는 자산을 구분한다`() {
        assertEquals(ReconciliationStatus.EMPTY, result().status)
        opening.prepare("other", buyer, AssetId("USDT"), Amount(100))
        assertEquals(ReconciliationStatus.EMPTY, result().status)
        opening.ensureReceivingBalance(buyer, krw)
        assertEquals(ReconciliationStatus.MATCHED, result().status)
        assertEquals(1, result().accounts.size)
    }

    @Test fun `외부 트랜잭션과 다른 마켓의 활성 예약은 검증 불가다`() {
        val outer = TransactionTemplate(transactionManager).execute { result() }
        assertEquals(ReconciliationStatus.UNAVAILABLE, outer.status)
        assertEquals(ReconciliationFailure.OUTER_TRANSACTION, outer.failure)
        seed()
        reserve()
        jdbc.update("update order_reservations set market_id='ETH-KRW'")
        val report = result()
        assertEquals(ReconciliationStatus.UNAVAILABLE, report.status)
        assertEquals(ReconciliationFailure.OTHER_MARKET_HOLD, report.failure)
        assertTrue(report.accounts.isEmpty())
    }

    @Test fun `예약 조회 SQL이 실패하면 앞서 읽은 원장과 잔고도 결과에서 버린다`() {
        seed()
        reserve()
        val before = databaseContents()
        jdbc.execute("alter table order_reservations rename to temporarily_unavailable_reservations")
        try {
            val report = result()
            assertEquals(ReconciliationStatus.UNAVAILABLE, report.status)
            assertEquals(ReconciliationFailure.DB_READ_FAILED, report.failure)
            assertTrue(report.detail!!.contains("예약 조회"))
            assertTrue(report.accounts.isEmpty())
            assertTrue(report.differences.isEmpty())
        } finally {
            jdbc.execute("alter table temporarily_unavailable_reservations rename to order_reservations")
        }
        assertEquals(before, databaseContents())
    }

    @Test fun `첫 조회 뒤 예약이 커밋돼도 나머지 조회는 이전 스냅샷을 읽는다`() {
        seed()
        val read = CountDownLatch(1)
        val written = CountDownLatch(1)
        var settings: List<String> = emptyList()
        val observingJdbc =
            object : NamedParameterJdbcTemplate(dataSource) {
                override fun <T : Any?> query(
                    sql: String,
                    paramMap: Map<String, *>,
                    rowMapper: RowMapper<T>,
                ): List<T> {
                    val rows = super.query(sql, paramMap, rowMapper)
                    if (sql.contains("left join ledger_postings")) {
                        settings =
                            listOf(
                                jdbcTemplate.queryForObject("show transaction_isolation", String::class.java)!!,
                                jdbcTemplate.queryForObject("show transaction_read_only", String::class.java)!!,
                            )
                        read.countDown()
                        check(written.await(15, TimeUnit.SECONDS)) { "예약 커밋 대기 실패" }
                    }
                    return rows
                }
            }
        val pool = Executors.newSingleThreadExecutor()
        val future =
            pool.submit<LedgerReconciliationReport> {
                ReconcileLedgerUseCase(PostgresLedgerReconciliationStore(observingJdbc, transactionManager)).reconcile(scope)
            }
        try {
            assertTrue(read.await(15, TimeUnit.SECONDS))
            reserve()
            written.countDown()
            val report = future.get(15, TimeUnit.SECONDS)
            assertEquals(listOf("repeatable read", "on"), settings)
            assertEquals(ReconciliationStatus.MATCHED, report.status, report.detail)
            assertEquals(number(1000), buyerKrw(report).available)
            assertEquals(number(0), buyerKrw(report).hold)
            assertEquals(number(0), buyerKrw(report).reservationHold)
            val nextReport = result()
            assertEquals(ReconciliationStatus.MATCHED, nextReport.status)
            assertEquals(number(700), buyerKrw(nextReport).available)
            assertEquals(number(300), buyerKrw(nextReport).hold)
            assertEquals(number(300), buyerKrw(nextReport).reservationHold)
        } finally {
            written.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `총액이 같아도 분류 오류와 예약 대비 hold 부족 초과를 각각 보고한다`() {
        seed()
        reserve()
        jdbc.update("update balance_projection set available=800, hold=200")
        assertEquals(
            mapOf(
                ReconciliationItem.AVAILABLE to number(100),
                ReconciliationItem.HOLD to number(-100),
                ReconciliationItem.RESERVATION_HOLD to number(-100),
            ),
            result().differences.associate { it.item to it.delta },
        )
        for (hold in listOf(299L, 301L)) {
            jdbc.update("update balance_projection set available=700, hold=?", hold)
            val report = result()
            assertEquals(ReconciliationStatus.MISMATCHED, report.status)
            assertEquals(setOf(ReconciliationItem.HOLD, ReconciliationItem.RESERVATION_HOLD), report.differences.map { it.item }.toSet())
            assertTrue(report.differences.all { it.delta == number(hold - 300) })
        }
    }

    @Test fun `기존 원장이 허용한 빈 식별자에 새 제약을 덧붙이지 않는다`() {
        opening.ensureReceivingBalance(buyer, krw)
        ledgerStore.append(
            LedgerTransaction(
                "",
                "",
                LedgerTransactionType.OPENING,
                Instant.EPOCH,
                listOf(
                    LedgerPosting("SYSTEM:KRW:DEVELOPMENT_FUNDING", krw, LedgerPostingSide.DEBIT, Amount(100)),
                    LedgerPosting("USER:buyer:KRW:AVAILABLE", krw, LedgerPostingSide.CREDIT, Amount(100)),
                ),
            ),
        )
        assertNotNull(ledgerStore.findBySourceEventId(""))
        jdbc.update("update balance_projection set available=100")
        val report = result()
        assertEquals(ReconciliationStatus.MATCHED, report.status)
        assertEquals(number(100), buyerKrw(report).ledgerAvailable)
    }

    @Test fun `손상된 원장에만 남은 사용자도 대상과 누락 잔고를 보고한다`() {
        seed()
        jdbc.update("delete from ledger_postings where posting_sequence=1")
        jdbc.update("delete from balance_projection")
        val report = result()
        assertEquals(ReconciliationStatus.MISMATCHED, report.status)
        assertEquals(1, report.accounts.size)
        val account = buyerKrw(report)
        assertNull(account.available)
        assertNull(account.ledgerAvailable)
        assertTrue(report.differences.any { it.item == ReconciliationItem.MISSING_BALANCE && it.userId == buyer && it.assetId == krw })
        assertTrue(report.differences.any { it.item == ReconciliationItem.INVALID_RECORD && it.source != null })
    }

    @Test fun `모든 조회 뒤 트랜잭션 종료가 실패하면 완료 단계와 검증 불가를 보고한다`() {
        seed()
        reserve()
        val before = databaseContents()
        var completionReached = false
        val failingManager =
            object : PlatformTransactionManager by transactionManager {
                override fun commit(status: TransactionStatus) {
                    completionReached = true
                    transactionManager.rollback(status)
                    throw TransactionSystemException("테스트에서 종료 실패를 주입했습니다")
                }
            }
        val report =
            ReconcileLedgerUseCase(
                PostgresLedgerReconciliationStore(NamedParameterJdbcTemplate(dataSource), failingManager),
            ).reconcile(scope)
        assertTrue(completionReached)
        assertEquals(ReconciliationStatus.UNAVAILABLE, report.status)
        assertEquals(ReconciliationFailure.DB_READ_FAILED, report.failure)
        assertTrue(report.detail!!.contains("트랜잭션 완료"), report.detail)
        assertTrue(report.accounts.isEmpty())
        assertTrue(report.differences.isEmpty())
        assertEquals(before, databaseContents())
    }

    @Test fun `취소된 예약 상태만 ACTIVE로 손상되면 금액이 맞아도 불일치로 보고한다`() {
        seed()
        reserve()
        release.release(market.marketId, OrderId("buy"))
        val normal = result()
        assertEquals(ReconciliationStatus.MATCHED, normal.status)
        assertEquals(number(1000), buyerKrw(normal).ledgerAvailable)
        assertEquals(number(0), buyerKrw(normal).ledgerHold)
        assertEquals(number(0), buyerKrw(normal).reservationHold)

        // DB 제약은 ACTIVE/0을 허용하지만 도메인 상태 규칙은 허용하지 않는다.
        assertEquals(
            1,
            jdbc.update("update order_reservations set status='ACTIVE', released_amount=null where market_id='BTC-KRW' and order_id='buy'"),
        )
        val before = databaseContents()
        val report = result()
        assertEquals(ReconciliationStatus.MISMATCHED, report.status)
        val problem = report.differences.single { it.item == ReconciliationItem.INVALID_RECORD }
        assertEquals(buyer, problem.userId)
        assertEquals(krw, problem.assetId)
        assertEquals("BTC-KRW/buy", problem.source)
        val account = buyerKrw(report)
        assertEquals(number(1000), account.available)
        assertEquals(number(0), account.hold)
        assertEquals(number(0), account.reservationHold)
        assertNull(account.ledgerAvailable)
        assertNull(account.ledgerHold)
        assertEquals(before, databaseContents())
    }
}
