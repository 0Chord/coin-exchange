package com.exchange.core.api.order

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
import com.exchange.core.ledger.InsufficientHoldException
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationNotFoundException
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderReservationStore
import com.exchange.core.order.ReservationRequirement
import com.exchange.core.order.Side
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "exchange.ledger.persistence.enabled=true",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LedgerPersistenceConfig::class)
@Testcontainers
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

    @BeforeEach
    fun setUp() {
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

        val second =
            service.release(
                marketId = MARKET_ID,
                orderId = ORDER_ID,
            )

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
        setBalance(
            available = 600,
            hold = 400,
        )

        val error =
            assertFailsWith<InsufficientHoldException> {
                service.release(
                    marketId = MARKET_ID,
                    orderId = ORDER_ID,
                )
            }

        assertEquals(Amount(400), error.hold)
        assertEquals(Amount(500), error.requested)

        val saved =
            requireNotNull(
                reservationStore.find(
                    marketId = MARKET_ID,
                    orderId = ORDER_ID,
                ),
            )

        assertEquals(Amount(500), saved.remainingAmount)
        assertEquals(OrderReservationStatus.ACTIVE, saved.status)

        assertPersistedBalance(
            available = 600,
            hold = 400,
        )
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

    private fun reservation(): OrderReservation =
        OrderReservation.create(
            marketId = MARKET_ID,
            orderId = ORDER_ID,
            userId = USER_ID,
            side = Side.BUY,
            limitPrice = Price(100),
            quantity = Quantity(5),
            requirement =
                ReservationRequirement(
                    assetId = ASSET_ID,
                    amount = Amount(500),
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

    private fun runConcurrently(
        operation: () -> OrderReservation,
    ): List<Result<OrderReservation>> {
        val ready = CountDownLatch(CONCURRENT_TASK_COUNT)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENT_TASK_COUNT)

        return try {
            val futures =
                List(CONCURRENT_TASK_COUNT) {
                    executor.submit<Result<OrderReservation>> {
                        ready.countDown()
                        start.await()
                        runCatching(operation)
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
        private const val CONCURRENT_TASK_COUNT = 2

        private val MARKET_ID = MarketId("BTC-KRW")
        private val ORDER_ID = OrderId("order-1")
        private val USER_ID = UserId("user-1")
        private val ASSET_ID = AssetId("KRW")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(
                DockerImageName.parse("postgres:16-alpine"),
            )

        @DynamicPropertySource
        @JvmStatic
        fun registerPostgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
