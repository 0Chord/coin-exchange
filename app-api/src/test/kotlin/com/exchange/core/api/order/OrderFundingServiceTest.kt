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
import com.exchange.core.ledger.InsufficientBalanceException
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservationAlreadyExistsException
import com.exchange.core.order.OrderReservationStore
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
class OrderFundingServiceTest {
    @Autowired
    private lateinit var service: OrderFundingService

    @Autowired
    private lateinit var reservationStore: OrderReservationStore

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

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
    }

    @Test
    fun `같은 주문을 다시 동결하면 잔고를 두 번 동결하지 않는다`() {
        reserveOrder()

        assertFailsWith<OrderReservationAlreadyExistsException> {
            reserveOrder()
        }

        assertPersistedBalance(
            available = 495,
            hold = 505,
        )

        assertEquals(1, reservationCount())
    }

    private fun reserveOrder() =
        service.reserve(
            market = MARKET,
            orderId = ORDER_ID,
            userId = USER_ID,
            side = Side.BUY,
            limitPrice = Price(100),
            quantity = Quantity(5),
            feePolicySnapshot = feePolicySnapshot,
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
            MARKET.quoteAssetId.value,
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
                  and order_id = ?
                """.trimIndent(),
                Int::class.java,
                MARKET.marketId.value,
                ORDER_ID.value,
            ),
        )

    companion object {
        private val USER_ID = UserId("user-1")
        private val ORDER_ID = OrderId("order-1")

        private val MARKET =
            MarketDefinition(
                marketId = MarketId("BTC-KRW"),
                baseAssetId = AssetId("BTC"),
                quoteAssetId = AssetId("KRW"),
                baseAssetScale = 0,
            )

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
