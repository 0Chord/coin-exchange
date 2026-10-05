package com.exchange.core.api.order.application

import com.exchange.core.api.config.LedgerPersistenceConfig
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
import com.exchange.core.ledger.BalanceNotFoundException
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.ledger.InsufficientHoldException
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationNotFoundException
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderReservationStore
import com.exchange.core.order.ReservationRequirement
import com.exchange.core.order.Side
import com.exchange.core.support.PostgresTestConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 실제 PostgreSQL에서 예약 해제·잔고 반환의 원자성과 중복·동시 취소를 검증한다.
 * 테스트 트랜잭션 없이 서비스의 커밋·롤백 결과를 확인하며, 각 테스트 전에 데이터를 준비한다.
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
class OrderReservationReleaseServiceTest {
    private val feeFreePolicySnapshot =
        TradingFeePolicySnapshot(
            productType = FeeProductType.SPOT,
            feeTier = FeeTier.NORMAL,
            scheduleVersion = 1,
            feeRates =
                MakerTakerFeeRates(
                    makerFeeRate = FeeRate.ZERO,
                    takerFeeRate = FeeRate.ZERO,
                ),
        )

    @Autowired
    private lateinit var service: OrderReservationReleaseService

    @Autowired
    private lateinit var reservationStore: OrderReservationStore

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var ledgerStore: LedgerTransactionStore

    @Autowired private lateinit var balanceStore: BalanceStore

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("delete from ledger_postings")
        jdbcTemplate.update("delete from ledger_transactions")
        jdbcTemplate.update("delete from order_reservations")
        jdbcTemplate.update("delete from balance_projection")

        insertBalance(
            available = 500,
            hold = 500,
        )

        reservationStore.create(reservation())
    }

    @Test
    fun `주문 예약을 해제하면 동결 금액을 available로 반환한다`() {
        val released =
            service.release(
                marketId = MARKET_ID,
                orderId = ORDER_ID,
            )

        assertEquals(Amount.ZERO, released.remainingAmount)
        assertEquals(OrderReservationStatus.RELEASED, released.status)
        assertEquals(Amount(500), released.releasedAmount)
        assertReleaseLedger(released, 500)
        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                "select count(*) from ledger_transactions where transaction_type = 'RELEASE'",
                Long::class.java,
            ),
        )
        assertEquals(
            listOf(500L, 500L),
            jdbcTemplate.queryForList(
                "select amount from ledger_postings order by posting_sequence",
                Long::class.java,
            ),
        )

        assertEquals(
            released,
            reservationStore.find(
                marketId = MARKET_ID,
                orderId = ORDER_ID,
            ),
        )

        assertPersistedBalance(
            available = 1_000,
            hold = 0,
        )
    }

    @Test
    fun `이미 해제된 주문 예약은 잔고를 다시 반환하지 않는다`() {
        val first =
            service.release(
                marketId = MARKET_ID,
                orderId = ORDER_ID,
            )

        val before = databaseState()
        val second =
            service.release(
                marketId = MARKET_ID,
                orderId = ORDER_ID,
            )

        assertEquals(before, databaseState())
        assertEquals(first, second)
        assertEquals(OrderReservationStatus.RELEASED, second.status)

        assertPersistedBalance(
            available = 1_000,
            hold = 0,
        )
    }

    @Test
    fun `존재하지 않는 주문 예약은 해제할 수 없다`() {
        val missingOrderId = OrderId("missing-order")

        val error =
            assertFailsWith<OrderReservationNotFoundException> {
                service.release(
                    marketId = MARKET_ID,
                    orderId = missingOrderId,
                )
            }

        assertEquals(MARKET_ID, error.marketId)
        assertEquals(missingOrderId, error.orderId)

        assertPersistedBalance(
            available = 500,
            hold = 500,
        )
    }

    @Test
    fun `잔고 반환에 실패하면 주문 예약 상태 변경도 롤백한다`() {
        val original = reservation()
        setBalance(
            available = 600,
            hold = 400,
        )

        val before = databaseState()
        val error =
            assertFailsWith<InsufficientHoldException> {
                service.release(
                    marketId = MARKET_ID,
                    orderId = ORDER_ID,
                )
            }

        assertEquals(USER_ID, error.userId)
        assertEquals(ASSET_ID, error.assetId)
        assertEquals(Amount(400), error.hold)
        assertEquals(Amount(500), error.requested)

        val saved =
            requireNotNull(
                reservationStore.find(
                    marketId = MARKET_ID,
                    orderId = ORDER_ID,
                ),
            )

        assertEquals(original, saved)
        assertEquals(before, databaseState())

        assertPersistedBalance(
            available = 600,
            hold = 400,
        )
    }

    @Test
    fun `서로 다른 예약의 합이 실제 hold보다 크면 해제 경쟁의 패자는 전체 예약을 유지한다`() {
        jdbcTemplate.update("delete from order_reservations")
        setBalance(available = 900, hold = 100)
        val originals =
            listOf(OrderId("competing-order-1"), OrderId("competing-order-2")).map { orderId ->
                reservation(
                    orderId = orderId,
                    limitPrice = Price(10),
                    quantity = Quantity(7),
                    reserveAmount = Amount(70),
                )
            }
        originals.forEach(reservationStore::create)

        // 롤백 경계를 검증하려고 ACTIVE 예약 합 140원과 실제 hold 100원을 의도적으로 불일치시킨다.
        val results =
            runConcurrently { index ->
                service.release(
                    marketId = MARKET_ID,
                    orderId = originals[index].orderId,
                )
            }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        val winnerIndex = results.indexOfFirst { it.isSuccess }
        val loserIndex = results.indexOfFirst { it.isFailure }
        val error = assertIs<InsufficientHoldException>(results[loserIndex].exceptionOrNull())
        assertEquals(USER_ID, error.userId)
        assertEquals(ASSET_ID, error.assetId)
        assertEquals(Amount(30), error.hold)
        assertEquals(Amount(70), error.requested)

        val expectedWinner =
            originals[winnerIndex].copy(
                remainingAmount = Amount.ZERO,
                status = OrderReservationStatus.RELEASED,
                releasedAmount = Amount(70),
            )
        assertEquals(expectedWinner, results[winnerIndex].getOrThrow())
        assertEquals(expectedWinner, reservationStore.find(MARKET_ID, originals[winnerIndex].orderId))
        assertEquals(originals[loserIndex], reservationStore.find(MARKET_ID, originals[loserIndex].orderId))
        assertPersistedBalance(available = 970, hold = 30)
        val sources =
            listOf(
                "RELEASE:v1:9d1572cd62669dc70a318a8b511dc71c469d5619c1510aca4cb736ba7571a768",
                "RELEASE:v1:903373168db7bd50f9f1d40f409b2d6ffb03925e28a6a988928807ec3df8f8d9",
            )
        assertReleaseLedger(expectedWinner, 70, sources[winnerIndex])
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from ledger_transactions", Long::class.java))
    }

    @Test
    fun `동시에 같은 주문 예약을 해제해도 잔고는 한 번만 반환한다`() {
        val results =
            runConcurrently {
                service.release(
                    marketId = MARKET_ID,
                    orderId = ORDER_ID,
                )
            }

        assertEquals(2, results.count { it.isSuccess })
        assertEquals(results[0].getOrThrow(), results[1].getOrThrow())
        assertReleaseLedger(results[0].getOrThrow(), 500)

        assertPersistedBalance(
            available = 1_000,
            hold = 0,
        )

        val saved =
            requireNotNull(
                reservationStore.find(
                    marketId = MARKET_ID,
                    orderId = ORDER_ID,
                ),
            )

        assertEquals(Amount.ZERO, saved.remainingAmount)
        assertEquals(OrderReservationStatus.RELEASED, saved.status)
    }

    @Test
    fun `다른 주문으로 잔고가 바뀐 뒤 반복 해제는 현재 잔고와 모든 완료 기록을 유지한다`() {
        val original = service.release(MARKET_ID, ORDER_ID)
        balanceStore.reserve(USER_ID, ASSET_ID, Amount(300))
        val before = databaseState()
        assertEquals(original, service.release(MARKET_ID, ORDER_ID))
        assertEquals(before, databaseState())
        assertPersistedBalance(700, 300)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "missing", "no-postings", "one-posting", "unbalanced", "amount", "account",
            "asset", "side", "extra", "type", "source", "reverse-order",
        ],
    )
    fun `반복 해제는 정확한 두 반환 항목만 완료로 인정한다`(damage: String) {
        val original = service.release(MARKET_ID, ORDER_ID)
        val id = jdbcTemplate.queryForObject("select ledger_transaction_id from ledger_transactions", String::class.java)!!
        when (damage) {
            "missing" -> {
                jdbcTemplate.update("delete from ledger_postings")
                jdbcTemplate.update("delete from ledger_transactions")
            }

            "no-postings" -> {
                jdbcTemplate.update("delete from ledger_postings")
            }

            "one-posting" -> {
                jdbcTemplate.update("delete from ledger_postings where posting_sequence = 2")
            }

            "unbalanced" -> {
                jdbcTemplate.update("update ledger_postings set amount = 400 where posting_sequence = 1")
            }

            "amount" -> {
                jdbcTemplate.update("update ledger_postings set amount = 400")
            }

            "account" -> {
                jdbcTemplate.update("update ledger_postings set account_id = replace(account_id, 'user-1', 'other-user')")
            }

            "asset" -> {
                jdbcTemplate.update("update ledger_postings set asset_id = 'BTC'")
            }

            "side" -> {
                jdbcTemplate.update("update ledger_postings set side = case when side = 'DEBIT' then 'CREDIT' else 'DEBIT' end")
            }

            "extra" -> {
                jdbcTemplate.update(
                    """
                    insert into ledger_postings (ledger_transaction_id, posting_sequence, account_id, asset_id, side, amount)
                    select ledger_transaction_id, posting_sequence + 2, account_id, asset_id, side, 1 from ledger_postings
                    """.trimIndent(),
                )
            }

            "type" -> {
                jdbcTemplate.update("update ledger_transactions set transaction_type = 'RESERVE'")
            }

            "source" -> {
                jdbcTemplate.update("update ledger_transactions set source_event_id = 'other-source'")
            }

            "reverse-order" -> {
                jdbcTemplate.update("update ledger_postings set posting_sequence = posting_sequence + 10")
                jdbcTemplate.update("update ledger_postings set posting_sequence = 13 - posting_sequence")
            }
        }
        val before = databaseState()
        if (damage == "reverse-order") {
            assertEquals(original, service.release(MARKET_ID, ORDER_ID))
            assertEquals(id, ledgerStore.findBySourceEventId(DEFAULT_SOURCE_ID)!!.ledgerTransactionId)
        } else {
            assertFailsWith<IllegalStateException> { service.release(MARKET_ID, ORDER_ID) }
        }
        assertEquals(before, databaseState())
    }

    @ParameterizedTest
    @ValueSource(longs = [500, 100])
    fun `ACTIVE인데 반환 원장이 있으면 같은 금액도 거절하고 전체 상태를 보존한다`(amount: Long) {
        ledgerStore.append(existingLedger(DEFAULT_SOURCE_ID, amount))
        val before = databaseState()
        val error = assertFailsWith<IllegalStateException> { service.release(MARKET_ID, ORDER_ID) }
        assertTrue(error.message.orEmpty().contains("ACTIVE"))
        assertEquals(before, databaseState())
    }

    @Test
    fun `과거 RELEASED의 알 수 없는 반환액은 원장이 있어도 추측하지 않는다`() {
        reservationStore.update(reservation().copy(remainingAmount = Amount.ZERO, status = OrderReservationStatus.RELEASED))
        ledgerStore.append(existingLedger(DEFAULT_SOURCE_ID, 500))
        val before = databaseState()
        val error = assertFailsWith<IllegalStateException> { service.release(MARKET_ID, ORDER_ID) }
        assertTrue(error.message.orEmpty().contains("unknown"))
        assertEquals(before, databaseState())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `전량 체결된 예약은 반환할 수 없고 반환 기록과 동시 존재하면 모순이다`(ledgerExists: Boolean) {
        reservationStore.update(
            reservation().copy(remainingAmount = Amount.ZERO, remainingQuantity = Quantity.ZERO, status = OrderReservationStatus.SETTLED),
        )
        if (ledgerExists) ledgerStore.append(existingLedger(DEFAULT_SOURCE_ID, 500))
        val before = databaseState()
        val error = assertFailsWith<IllegalStateException> { service.release(MARKET_ID, ORDER_ID) }
        assertTrue(error.message.orEmpty().contains(if (ledgerExists) "SETTLED" else "only active"))
        assertEquals(before, databaseState())
    }

    @ParameterizedTest
    @ValueSource(strings = ["reservation", "balance", "header", "first-posting", "second-posting"])
    fun `각 실제 저장 단계의 SQL 실패는 이번 반환 전체를 롤백하고 재시도 한 번만 완료한다`(stage: String) {
        ledgerStore.append(existingLedger("previous-event", 7))
        val before = databaseState()
        val sequenceBefore = jdbcTemplate.queryForObject("select last_value from ledger_postings_posting_id_seq", Long::class.java)!!
        val (table, condition) =
            when (stage) {
                "reservation" -> "order_reservations" to "status <> 'RELEASED'"
                "balance" -> "balance_projection" to "available <> 1000"
                "header" -> "ledger_transactions" to "source_event_id <> '$DEFAULT_SOURCE_ID'"
                "first-posting" -> "ledger_postings" to "posting_sequence <> 1"
                "second-posting" -> "ledger_postings" to "posting_sequence <> 2"
                else -> error("알 수 없는 실패 지점")
            }
        // 기존 기록을 보존하며 새 저장에만 실제 DB 오류를 발생시킨다.
        jdbcTemplate.execute("alter table $table add constraint issue73_failure check ($condition) not valid")
        try {
            val error = assertFailsWith<DataIntegrityViolationException> { service.release(MARKET_ID, ORDER_ID) }
            val sql = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().first()
            assertEquals("23514", sql.sqlState)
            assertTrue(sql.message.orEmpty().contains("issue73_failure"))
            assertEquals(before, databaseState())
            if (stage == "second-posting") {
                assertEquals(
                    sequenceBefore + 2,
                    jdbcTemplate.queryForObject("select last_value from ledger_postings_posting_id_seq", Long::class.java),
                )
            }
        } finally {
            jdbcTemplate.execute("alter table $table drop constraint issue73_failure")
        }
        val released = service.release(MARKET_ID, ORDER_ID)
        assertReleaseLedger(released, 500)
        val completed = databaseState()
        assertEquals(released, service.release(MARKET_ID, ORDER_ID))
        assertEquals(completed, databaseState())
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-balance", "overflow"])
    fun `잔고 행이 없거나 반환 합계가 overflow면 예약과 원장도 그대로다`(failure: String) {
        if (failure == "missing-balance") {
            jdbcTemplate.update("delete from balance_projection")
        } else {
            setBalance(Long.MAX_VALUE - 100, 500)
        }
        val before = databaseState()
        if (failure == "missing-balance") {
            assertFailsWith<BalanceNotFoundException> { service.release(MARKET_ID, ORDER_ID) }
        } else {
            val error = assertFailsWith<DataAccessException> { service.release(MARKET_ID, ORDER_ID) }
            assertEquals("22003", generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().first().sqlState)
        }
        assertEquals(before, databaseState())
    }

    @Test
    fun `외부 트랜잭션 롤백은 예약 반환 잔고 원장과 시각을 모두 되돌린다`() {
        val before = databaseState()
        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            val released = service.release(MARKET_ID, ORDER_ID)
            assertReleaseLedger(released, 500)
            status.setRollbackOnly()
        }
        assertEquals(before, databaseState())
    }

    @Test
    fun `원장 조회 오류는 미존재로 숨기지 않고 후속 저장 없이 끝난다`() {
        val before = databaseState()
        jdbcTemplate.execute("alter table ledger_postings rename to issue73_hidden_postings")
        try {
            assertFailsWith<DataAccessException> { service.release(MARKET_ID, ORDER_ID) }
        } finally {
            jdbcTemplate.execute("alter table issue73_hidden_postings rename to ledger_postings")
        }
        assertEquals(before, databaseState())
    }

    @Test
    fun `원장 부재 조회 뒤 다른 writer가 저장하면 고유 충돌로 이번 반환만 롤백한다`() {
        var collisionState: Map<String, List<Map<String, Any?>>>? = null
        val writer =
            object : LedgerTransactionStore by ledgerStore {
                override fun findBySourceEventId(sourceEventId: String): LedgerTransaction? {
                    assertEquals(null, ledgerStore.findBySourceEventId(sourceEventId))
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        executor.submit { ledgerStore.append(existingLedger(sourceEventId, 500)) }.get(5, TimeUnit.SECONDS)
                    } finally {
                        executor.shutdownNow()
                    }
                    collisionState = databaseState()
                    return null
                }
            }
        val custom = OrderReservationReleaseService(balanceStore, reservationStore, writer)
        val error =
            assertFailsWith<DuplicateKeyException> {
                TransactionTemplate(transactionManager).execute { custom.release(MARKET_ID, ORDER_ID) }
            }
        assertEquals("23505", generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().first().sqlState)
        assertEquals(collisionState, databaseState())
        assertEquals(reservation(), reservationStore.find(MARKET_ID, ORDER_ID))
    }

    @Test
    fun `콜론 Unicode와 같은 주문 다른 마켓은 독립 고정 원본 키로 기록된다`() {
        jdbcTemplate.update("delete from order_reservations")
        val cases =
            listOf(
                Triple("a:b", "c", "540e8f1c5ae653a7d7e2fe88f7eb8dcabea924d661b1542ad191bb1848e0c33d"),
                Triple("a", "b:c", "e482a79a788392ccae4952360dd438820641e4c162b4952b42d35e78260d70be"),
                Triple("시장".repeat(32), "주문".repeat(32), "ac256d40776a018737c2d977de4f03a1608e6c92a8d616a5d2b70f0d51ebeb12"),
                Triple("ETH-KRW", "order-1", "f7f92ee0562ad2a9877bafabbd6b0e5184bf513fdd827063b96f5825e86d4fff"),
                Triple("BTC-KRW", "order-1", "581141f7da5ee3ed56e0e03e2ba3dfad2b1973854bdf680b3f376dcfab204cba"),
            )
        for ((market, order, hash) in cases) {
            val original = reservation(orderId = OrderId(order), reserveAmount = Amount(50)).copy(marketId = MarketId(market))
            reservationStore.create(original)
            val released = service.release(original.marketId, original.orderId)
            assertReleaseLedger(released, 50, "RELEASE:v1:$hash")
            assertEquals(75, "RELEASE:v1:$hash".length)
        }
        assertEquals(cases.size.toLong(), jdbcTemplate.queryForObject("select count(*) from ledger_transactions", Long::class.java))
        assertPersistedBalance(750, 250)
    }

    private fun databaseState(): Map<String, List<Map<String, Any?>>> =
        mapOf(
            "reservations" to jdbcTemplate.queryForList("select * from order_reservations order by market_id, order_id"),
            "balances" to jdbcTemplate.queryForList("select * from balance_projection order by user_id, asset_id"),
            "transactions" to jdbcTemplate.queryForList("select * from ledger_transactions order by source_event_id"),
            "postings" to jdbcTemplate.queryForList("select * from ledger_postings order by posting_id"),
        )

    private fun assertReleaseLedger(
        reservation: OrderReservation,
        amount: Long,
        source: String = DEFAULT_SOURCE_ID,
    ) {
        assertEquals(Amount(amount), reservation.releasedAmount)
        val ledger = checkNotNull(ledgerStore.findBySourceEventId(source))
        assertEquals(source, ledger.sourceEventId)
        assertEquals(LedgerTransactionType.RELEASE, ledger.transactionType)
        assertEquals(
            listOf(
                LedgerPosting(
                    "USER:${reservation.userId.value}:${reservation.assetId.value}:HOLD",
                    reservation.assetId,
                    LedgerPostingSide.DEBIT,
                    Amount(amount),
                ),
                LedgerPosting(
                    "USER:${reservation.userId.value}:${reservation.assetId.value}:AVAILABLE",
                    reservation.assetId,
                    LedgerPostingSide.CREDIT,
                    Amount(amount),
                ),
            ),
            ledger.postings,
        )
    }

    private fun existingLedger(
        source: String,
        amount: Long,
    ) = LedgerTransaction(
        java.util.UUID
            .randomUUID()
            .toString(),
        source,
        LedgerTransactionType.RELEASE,
        Instant.parse("2026-09-05T00:00:00Z"),
        listOf(
            LedgerPosting("USER:user-1:KRW:HOLD", ASSET_ID, LedgerPostingSide.DEBIT, Amount(amount)),
            LedgerPosting("USER:user-1:KRW:AVAILABLE", ASSET_ID, LedgerPostingSide.CREDIT, Amount(amount)),
        ),
    )

    private fun reservation(
        orderId: OrderId = ORDER_ID,
        limitPrice: Price = Price(100),
        quantity: Quantity = Quantity(5),
        reserveAmount: Amount = Amount(500),
    ): OrderReservation =
        OrderReservation.create(
            marketId = MARKET_ID,
            orderId = orderId,
            userId = USER_ID,
            side = Side.BUY,
            limitPrice = limitPrice,
            quantity = quantity,
            requirement =
                ReservationRequirement(
                    assetId = ASSET_ID,
                    amount = reserveAmount,
                ),
            feePolicySnapshot = feeFreePolicySnapshot,
        )

    private fun insertBalance(
        available: Long,
        hold: Long,
    ) {
        jdbcTemplate.update(
            """
            insert into balance_projection (
                user_id,
                asset_id,
                available,
                hold
            ) values (?, ?, ?, ?)
            """.trimIndent(),
            USER_ID.value,
            ASSET_ID.value,
            available,
            hold,
        )
    }

    private fun setBalance(
        available: Long,
        hold: Long,
    ) {
        jdbcTemplate.update(
            """
            update balance_projection
            set available = ?,
                hold = ?
            where user_id = ?
              and asset_id = ?
            """.trimIndent(),
            available,
            hold,
            USER_ID.value,
            ASSET_ID.value,
        )
    }

    private fun assertPersistedBalance(
        available: Long,
        hold: Long,
    ) {
        val saved =
            jdbcTemplate.queryForMap(
                """
                select available, hold
                from balance_projection
                where user_id = ?
                  and asset_id = ?
                """.trimIndent(),
                USER_ID.value,
                ASSET_ID.value,
            )

        assertEquals(available, (saved["available"] as Number).toLong())
        assertEquals(hold, (saved["hold"] as Number).toLong())
    }

    private fun runConcurrently(operation: (Int) -> OrderReservation): List<Result<OrderReservation>> {
        val ready = CountDownLatch(CONCURRENT_TASK_COUNT)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENT_TASK_COUNT)

        return try {
            val futures =
                List(CONCURRENT_TASK_COUNT) { index ->
                    executor.submit<Result<OrderReservation>> {
                        ready.countDown()
                        start.await()
                        runCatching { operation(index) }
                    }
                }

            assertTrue(
                ready.await(5, TimeUnit.SECONDS),
                "두 작업이 시작 준비를 마치지 못했다",
            )

            start.countDown()

            futures.map { future ->
                future.get(5, TimeUnit.SECONDS)
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    companion object {
        private const val DEFAULT_SOURCE_ID = "RELEASE:v1:581141f7da5ee3ed56e0e03e2ba3dfad2b1973854bdf680b3f376dcfab204cba"
        private const val CONCURRENT_TASK_COUNT = 2

        private val MARKET_ID = MarketId("BTC-KRW")
        private val ORDER_ID = OrderId("order-1")
        private val USER_ID = UserId("user-1")
        private val ASSET_ID = AssetId("KRW")
    }
}
