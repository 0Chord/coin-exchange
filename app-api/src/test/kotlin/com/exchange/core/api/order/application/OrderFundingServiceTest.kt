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
import com.exchange.core.ledger.InsufficientBalanceException
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationAlreadyExistsException
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderReservationStore
import com.exchange.core.order.Side
import com.exchange.core.support.PostgresTestConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제 주문 자금 서비스와 PostgreSQL로 예약 저장·잔고 동결의 원자성을 검증한다.
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
class OrderFundingServiceTest {
    @Autowired
    private lateinit var service: OrderFundingService

    @Autowired
    private lateinit var reservationStore: OrderReservationStore

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var ledgerStore: LedgerTransactionStore

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val feePolicySnapshot =
        TradingFeePolicySnapshot(
            productType = FeeProductType.SPOT,
            feeTier = FeeTier.NORMAL,
            scheduleVersion = 1,
            feeRates =
                MakerTakerFeeRates(
                    makerFeeRate = FeeRate(5_000),
                    takerFeeRate = FeeRate(10_000),
                ),
        )

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("delete from ledger_postings")
        jdbcTemplate.update("delete from ledger_transactions")
        jdbcTemplate.update("delete from order_reservations")
        jdbcTemplate.update("delete from balance_projection")

        insertBalance(
            available = 1_000,
            hold = 0,
        )
    }

    @Test
    fun `BUY 주문 자금을 동결하면 수수료를 포함한 예약을 저장하고 잔고를 이동한다`() {
        val reservation = reserveOrder()

        assertEquals(MARKET.marketId, reservation.marketId)
        assertEquals(ORDER_ID, reservation.orderId)
        assertEquals(USER_ID, reservation.userId)
        assertEquals(AssetId("KRW"), reservation.assetId)
        assertEquals(Amount(505), reservation.reservedAmount)
        assertReserveLedger(reservation, 505)

        assertEquals(
            reservation,
            reservationStore.find(
                marketId = MARKET.marketId,
                orderId = ORDER_ID,
            ),
        )

        assertPersistedBalance(
            available = 495,
            hold = 505,
        )
    }

    @Test
    fun `사용 가능한 잔고 전액을 거래와 수수료 예약으로 이동할 수 있다`() {
        setBalance(
            available = 505,
            hold = 0,
        )

        val reservation = reserveOrder()

        assertEquals(ORDER_ID, reservation.orderId)
        assertEquals(USER_ID, reservation.userId)
        assertEquals(MARKET.quoteAssetId, reservation.assetId)
        assertEquals(Amount(505), reservation.reservedAmount)
        assertEquals(Amount(505), reservation.remainingAmount)
        assertEquals(Amount(5), reservation.initialFeeReserveAmount)
        assertEquals(Amount(5), reservation.remainingFeeReserveAmount)
        assertEquals(Quantity(5), reservation.remainingQuantity)
        assertEquals(OrderReservationStatus.ACTIVE, reservation.status)
        assertEquals(reservation, reservationStore.find(MARKET.marketId, ORDER_ID))
        assertEquals(1, reservationCount())
        assertReserveLedger(reservation, 505)
        assertPersistedBalance(available = 0, hold = 505)
    }

    @Test
    fun `잔고가 부족하면 주문 예약 저장도 롤백한다`() {
        setBalance(
            available = 400,
            hold = 0,
        )

        assertFailsWith<InsufficientBalanceException> {
            reserveOrder()
        }

        assertNull(
            reservationStore.find(
                marketId = MARKET.marketId,
                orderId = ORDER_ID,
            ),
        )

        assertPersistedBalance(
            available = 400,
            hold = 0,
        )
        assertNoLedger()
    }

    @Test
    fun `잔고 행이 없으면 먼저 저장한 주문 예약도 롤백한다`() {
        assertEquals(
            1,
            jdbcTemplate.update(
                "delete from balance_projection where user_id = ? and asset_id = ?",
                USER_ID.value,
                MARKET.quoteAssetId.value,
            ),
        )

        val error =
            assertFailsWith<BalanceNotFoundException> {
                reserveOrder()
            }

        assertEquals(USER_ID, error.userId)
        assertEquals(MARKET.quoteAssetId, error.assetId)
        assertNull(reservationStore.find(MARKET.marketId, ORDER_ID))
        assertEquals(0, reservationCount())
        assertEquals(
            0L,
            jdbcTemplate.queryForObject("select count(*) from balance_projection", Long::class.java),
        )
        assertNoLedger()
    }

    @Test
    fun `서로 다른 주문이 잔고를 경쟁하면 성공한 주문 예약만 남는다`() {
        val orderIds = listOf(OrderId("competing-order-1"), OrderId("competing-order-2"))

        // 거래 700원과 수수료 7원을 각각 요구하므로 1,000원에서는 한 주문만 성공한다.
        val results =
            runConcurrently { index ->
                service.reserve(
                    market = MARKET,
                    orderId = orderIds[index],
                    userId = USER_ID,
                    side = Side.BUY,
                    limitPrice = Price(100),
                    quantity = Quantity(7),
                    feePolicySnapshot = feePolicySnapshot,
                )
            }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        val winnerIndex = results.indexOfFirst { it.isSuccess }
        val loserIndex = results.indexOfFirst { it.isFailure }
        val winner = results[winnerIndex].getOrThrow()
        val error = assertIs<InsufficientBalanceException>(results[loserIndex].exceptionOrNull())

        assertEquals(USER_ID, error.userId)
        assertEquals(MARKET.quoteAssetId, error.assetId)
        assertEquals(Amount(293), error.available)
        assertEquals(Amount(707), error.requested)
        assertEquals(orderIds[winnerIndex], winner.orderId)
        assertEquals(Amount(707), winner.reservedAmount)
        assertEquals(Amount(707), winner.remainingAmount)
        assertEquals(Amount(7), winner.remainingFeeReserveAmount)
        assertEquals(Quantity(7), winner.remainingQuantity)
        assertEquals(OrderReservationStatus.ACTIVE, winner.status)
        assertEquals(winner, reservationStore.find(MARKET.marketId, orderIds[winnerIndex]))
        assertNull(reservationStore.find(MARKET.marketId, orderIds[loserIndex]))
        assertEquals(1, reservationCount())
        assertPersistedBalance(available = 293, hold = 707)
        assertReserveLedger(winner, 707)
    }

    @Test
    fun `같은 주문을 다시 동결하면 잔고를 두 번 동결하지 않는다`() {
        reserveOrder()
        val before = readDatabaseState()

        assertFailsWith<OrderReservationAlreadyExistsException> {
            reserveOrder()
        }

        assertPersistedBalance(
            available = 495,
            hold = 505,
        )

        assertEquals(1, reservationCount())
        assertEquals(before, readDatabaseState())
    }

    @Test
    fun `수수료 없는 BUY 300을 예약하면 같은 자산의 두 분개를 남긴다`() {
        val reservation = reserveOrder(quantity = Quantity(3), policy = feeFreePolicy())
        assertPersistedBalance(available = 700, hold = 300)
        assertEquals(1, reservationCount())
        assertReserveLedger(reservation, 300)
        assertEquals(DEFAULT_SOURCE_ID, readTransactions().single()["source_event_id"])
    }

    @Test
    fun `SELL은 base 수량만 예약하고 quote와 수수료 수익을 바꾸지 않는다`() {
        insertBalance(available = 10, hold = 0, assetId = MARKET.baseAssetId)
        val reservation = reserveOrder(side = Side.SELL, quantity = Quantity(3))
        assertEquals(Amount(3), reservation.reservedAmount)
        assertReserveLedger(reservation, 3)
        assertEquals(
            mapOf("available" to 7L, "hold" to 3L),
            jdbcTemplate.queryForMap(
                "select available, hold from balance_projection where user_id = ? and asset_id = ?",
                USER_ID.value,
                MARKET.baseAssetId.value,
            ),
        )
        assertPersistedBalance(available = 1_000, hold = 0)
    }

    @Test
    fun `같은 주문의 입력이 달라도 중복 오류이며 원본 전체 상태를 보존한다`() {
        insertBalance(available = 10, hold = 0, assetId = MARKET.baseAssetId)
        insertBalance(available = 1_000, hold = 0, userId = UserId("other-user"))
        reserveOrder()
        val before = readDatabaseState()
        val attempts =
            listOf<() -> OrderReservation>(
                { reserveOrder(userId = UserId("other-user")) },
                { reserveOrder(side = Side.SELL) },
                { reserveOrder(price = Price(90)) },
                { reserveOrder(quantity = Quantity(3)) },
                { reserveOrder(policy = feeFreePolicy().copy(scheduleVersion = 2)) },
            )
        for (attempt in attempts) {
            val error = assertFailsWith<OrderReservationAlreadyExistsException>(block = attempt)
            assertEquals(MARKET.marketId, error.marketId)
            assertEquals(ORDER_ID, error.orderId)
            assertEquals(before, readDatabaseState())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `같은 주문 동시 요청은 최초 성공 하나와 중복 오류 하나만 남긴다`(differentInput: Boolean) {
        val results =
            runConcurrently { index ->
                reserveOrder(quantity = Quantity(if (differentInput && index == 1) 3 else 5))
            }
        assertEquals(1, results.count { it.isSuccess })
        val winner = results.single { it.isSuccess }.getOrThrow()
        val error = assertIs<OrderReservationAlreadyExistsException>(results.single { it.isFailure }.exceptionOrNull())
        assertEquals(ORDER_ID, error.orderId)
        assertEquals(MARKET.marketId, error.marketId)
        assertEquals(winner, reservationStore.find(MARKET.marketId, ORDER_ID))
        assertEquals(1, reservationCount())
        assertPersistedBalance(1_000 - winner.reservedAmount.value, winner.reservedAmount.value)
        assertReserveLedger(winner, winner.reservedAmount.value)
    }

    @ParameterizedTest
    @ValueSource(longs = [505, 100])
    fun `예약 없이 같은 source 원장이 있으면 같은 내용도 오류로 거절하고 새 변경을 롤백한다`(previousAmount: Long) {
        ledgerStore.append(existingLedger(DEFAULT_SOURCE_ID, previousAmount))
        val before = readDatabaseState()
        val error = assertFailsWith<DuplicateKeyException> { reserveOrder() }
        val sql = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().first()
        assertEquals("23505", sql.sqlState)
        assertTrue(sql.message.orEmpty().contains("uk_ledger_transactions_source_event"))
        assertEquals(before, readDatabaseState())
        assertNull(reservationStore.find(MARKET.marketId, ORDER_ID))
    }

    @ParameterizedTest
    @ValueSource(strings = ["reservation", "balance", "header", "second-posting"])
    fun `실제 DB 저장 실패는 이전 원장을 보존하고 이번 예약 잔고 원장 전체를 롤백한다`(stage: String) {
        ledgerStore.append(existingLedger("previous-event", 7))
        val before = readDatabaseState()
        val sequenceBefore = jdbcTemplate.queryForObject("select last_value from ledger_postings_posting_id_seq", Long::class.java)!!
        val (table, condition) =
            when (stage) {
                "reservation" -> "order_reservations" to "order_id <> 'order-1'"
                "balance" -> "balance_projection" to "available <> 495"
                "header" -> "ledger_transactions" to "source_event_id <> '$DEFAULT_SOURCE_ID'"
                "second-posting" -> "ledger_postings" to "posting_sequence <> 2"
                else -> error("알 수 없는 실패 지점")
            }
        // NOT VALID로 기존 기록은 보존하고 새 insert/update에만 테스트 제약을 적용한다.
        jdbcTemplate.execute("alter table $table add constraint issue72_failure check ($condition) not valid")
        try {
            val error = assertFailsWith<DataIntegrityViolationException> { reserveOrder() }
            val sql = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().first()
            assertEquals("23514", sql.sqlState)
            assertTrue(sql.message.orEmpty().contains("issue72_failure"))
            assertEquals(before, readDatabaseState())
            assertNull(reservationStore.find(MARKET.marketId, ORDER_ID))
            assertPersistedBalance(1_000, 0)
            if (stage == "second-posting") {
                // 시퀀스는 롤백되지 않으므로 앞선 분개 insert가 실제로 실행됐는지 확인한다.
                assertEquals(
                    sequenceBefore + 2,
                    jdbcTemplate.queryForObject("select last_value from ledger_postings_posting_id_seq", Long::class.java),
                )
            }
        } finally {
            jdbcTemplate.execute("alter table $table drop constraint issue72_failure")
        }
    }

    @Test
    fun `외부 트랜잭션 롤백은 참여한 예약 잔고 원장도 함께 되돌린다`() {
        val before = readDatabaseState()
        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            val reservation = reserveOrder()
            assertReserveLedger(reservation, 505)
            status.setRollbackOnly()
        }
        assertEquals(before, readDatabaseState())
    }

    @Test
    fun `콜론과 긴 Unicode 식별자도 원본 바이트 기준으로 구분하여 저장한다`() {
        val cases =
            listOf(
                Triple("a:b", "c", "RESERVE:v1:540e8f1c5ae653a7d7e2fe88f7eb8dcabea924d661b1542ad191bb1848e0c33d"),
                Triple("a", "b:c", "RESERVE:v1:e482a79a788392ccae4952360dd438820641e4c162b4952b42d35e78260d70be"),
                Triple(
                    "시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장시장",
                    "주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문주문",
                    "RESERVE:v1:ac256d40776a018737c2d977de4f03a1608e6c92a8d616a5d2b70f0d51ebeb12",
                ),
                Triple("BTC-KRW", "different-order", "RESERVE:v1:a9712b9199ca884278157c6a701481f7b8fbf9cb7e1c44e559f9b57ffad48d39"),
            )
        val sources = mutableSetOf<String>()
        for ((marketId, orderId, expectedSource) in cases) {
            val market = MARKET.copy(marketId = MarketId(marketId))
            val reservation = reserveOrder(market = market, orderId = OrderId(orderId), quantity = Quantity(1), policy = feeFreePolicy())
            assertReserveLedger(reservation, 100, expectedSource)
            assertTrue(sources.add(expectedSource))
            assertEquals(75, expectedSource.length)
        }
        assertPersistedBalance(1_000 - cases.size * 100L, cases.size * 100L)
        assertEquals(cases.size, readTransactions().size)
    }

    @Test
    fun `서로 다른 마켓의 같은 주문 ID는 각각 예약과 원장을 남긴다`() {
        val first = reserveOrder(quantity = Quantity(3), policy = feeFreePolicy())
        val second = reserveOrder(market = MARKET.copy(marketId = MarketId("ETH-KRW")), quantity = Quantity(3), policy = feeFreePolicy())
        assertEquals(first, reservationStore.find(MARKET.marketId, ORDER_ID))
        assertEquals(second, reservationStore.find(MarketId("ETH-KRW"), ORDER_ID))
        assertReserveLedger(first, 300, DEFAULT_SOURCE_ID)
        assertReserveLedger(second, 300, "RESERVE:v1:f7f92ee0562ad2a9877bafabbd6b0e5184bf513fdd827063b96f5825e86d4fff")
        assertPersistedBalance(400, 600)
        assertEquals(2, readTransactions().size)
    }

    @Test
    fun `DB 길이 제한과 계산 overflow는 저장 성공으로 처리하지 않는다`() {
        val before = readDatabaseState()
        assertFailsWith<DataIntegrityViolationException> { reserveOrder(orderId = OrderId("o".repeat(65))) }
        assertEquals(before, readDatabaseState())
        // 기존 도메인은 산술 overflow를 입력 검증 오류로 감싸서 전달한다.
        val overflow = assertFailsWith<IllegalArgumentException> { reserveOrder(price = Price(Long.MAX_VALUE)) }
        assertIs<ArithmeticException>(overflow.cause)
        assertEquals(before, readDatabaseState())
        assertFailsWith<IllegalArgumentException> { reserveOrder(quantity = Quantity.ZERO) }
        assertEquals(before, readDatabaseState())
    }

    private fun feeFreePolicy() = feePolicySnapshot.copy(feeRates = MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))

    private fun existingLedger(
        source: String,
        amount: Long,
    ) = LedgerTransaction(
        ledgerTransactionId = "previous-transaction",
        sourceEventId = source,
        transactionType = LedgerTransactionType.RESERVE,
        occurredAt = Instant.parse("2026-10-01T00:00:00Z"),
        postings =
            listOf(
                LedgerPosting("USER:user-1:KRW:AVAILABLE", MARKET.quoteAssetId, LedgerPostingSide.DEBIT, Amount(amount)),
                LedgerPosting("USER:user-1:KRW:HOLD", MARKET.quoteAssetId, LedgerPostingSide.CREDIT, Amount(amount)),
            ),
    )

    private fun readTransactions() = jdbcTemplate.queryForList("select * from ledger_transactions order by source_event_id")

    private fun readDatabaseState() =
        listOf(
            jdbcTemplate.queryForList("select * from order_reservations order by market_id, order_id"),
            jdbcTemplate.queryForList("select * from balance_projection order by user_id, asset_id"),
            readTransactions(),
            jdbcTemplate.queryForList("select * from ledger_postings order by posting_id"),
        )

    private fun assertNoLedger() {
        assertTrue(readTransactions().isEmpty())
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from ledger_postings", Long::class.java))
    }

    private fun assertReserveLedger(
        reservation: OrderReservation,
        amount: Long,
        source: String? = null,
    ) {
        val transactions =
            if (source == null) {
                jdbcTemplate.queryForList("select * from ledger_transactions where transaction_type = 'RESERVE'")
            } else {
                jdbcTemplate.queryForList(
                    "select * from ledger_transactions where transaction_type = 'RESERVE' and source_event_id = ?",
                    source,
                )
            }
        assertEquals(1, transactions.size, "예약과 함께 RESERVE 원장 한 건이 커밋되어야 한다")
        val postings =
            jdbcTemplate.queryForList(
                "select account_id, asset_id, side, amount from ledger_postings where ledger_transaction_id = ? order by posting_sequence",
                transactions.single()["ledger_transaction_id"],
            )
        assertEquals(2, postings.size)
        assertEquals(listOf("DEBIT", "CREDIT"), postings.map { it["side"] })
        assertEquals(
            listOf(
                "USER:${reservation.userId.value}:${reservation.assetId.value}:AVAILABLE",
                "USER:${reservation.userId.value}:${reservation.assetId.value}:HOLD",
            ),
            postings.map {
                it["account_id"]
            },
        )
        assertEquals(listOf(reservation.assetId.value, reservation.assetId.value), postings.map { it["asset_id"] })
        assertEquals(listOf(amount, amount), postings.map { (it["amount"] as Number).toLong() })
    }

    private fun reserveOrder(
        market: MarketDefinition = MARKET,
        orderId: OrderId = ORDER_ID,
        userId: UserId = USER_ID,
        side: Side = Side.BUY,
        price: Price = Price(100),
        quantity: Quantity = Quantity(5),
        policy: TradingFeePolicySnapshot = feePolicySnapshot,
    ) = service.reserve(market, orderId, userId, side, price, quantity, policy)

    private fun insertBalance(
        available: Long,
        hold: Long,
        userId: UserId = USER_ID,
        assetId: AssetId = MARKET.quoteAssetId,
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
            userId.value,
            assetId.value,
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
            MARKET.quoteAssetId.value,
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
                MARKET.quoteAssetId.value,
            )

        assertEquals(available, (saved["available"] as Number).toLong())
        assertEquals(hold, (saved["hold"] as Number).toLong())
    }

    private fun reservationCount(): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from order_reservations
                where market_id = ?
                """.trimIndent(),
                Int::class.java,
                MARKET.marketId.value,
            ),
        )

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
        private const val DEFAULT_SOURCE_ID = "RESERVE:v1:581141f7da5ee3ed56e0e03e2ba3dfad2b1973854bdf680b3f376dcfab204cba"
        private const val CONCURRENT_TASK_COUNT = 2

        private val USER_ID = UserId("user-1")
        private val ORDER_ID = OrderId("order-1")

        private val MARKET =
            MarketDefinition(
                marketId = MarketId("BTC-KRW"),
                baseAssetId = AssetId("BTC"),
                quoteAssetId = AssetId("KRW"),
                baseAssetScale = 0,
            )
    }
}
