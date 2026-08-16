package com.exchange.core.api.order.persistence

import com.exchange.core.api.config.LedgerPersistenceConfig
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationAlreadyExistsException
import com.exchange.core.order.OrderReservationNotFoundException
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
class PostgresOrderReservationStoreTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var store: OrderReservationStore

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("delete from order_reservations")
    }

    @Test
    fun `주문 동결 정보를 저장하고 조회한다`() {
        val reservation = reservation()

        store.create(reservation)

        val saved =
            store.find(
                marketId = reservation.marketId,
                orderId = reservation.orderId,
            )

        assertEquals(reservation, saved)
    }

    @Test
    fun `존재하지 않는 주문 동결 정보를 조회하면 null을 반환한다`() {
        val saved =
            store.find(
                marketId = MarketId("BTC-KRW"),
                orderId = OrderId("missing-order"),
            )

        assertNull(saved)
    }

    @Test
    fun `같은 marketId와 orderId는 중복 저장할 수 없다`() {
        val reservation = reservation()

        store.create(reservation)

        val error =
            assertFailsWith<OrderReservationAlreadyExistsException> {
                store.create(reservation)
            }

        assertEquals(reservation.marketId, error.marketId)
        assertEquals(reservation.orderId, error.orderId)
    }

    @Test
    fun `같은 orderId라도 marketId가 다르면 저장할 수 있다`() {
        val btcReservation =
            reservation(
                marketId = MarketId("BTC-KRW"),
                orderId = OrderId("order-1"),
            )

        val ethReservation =
            reservation(
                marketId = MarketId("ETH-KRW"),
                orderId = OrderId("order-1"),
            )

        store.create(btcReservation)
        store.create(ethReservation)

        assertEquals(
            btcReservation,
            store.find(
                marketId = btcReservation.marketId,
                orderId = btcReservation.orderId,
            ),
        )

        assertEquals(
            ethReservation,
            store.find(
                marketId = ethReservation.marketId,
                orderId = ethReservation.orderId,
            ),
        )
    }

    @Test
    fun `주문 동결 정보를 잠금 조회한다`() {
        val reservation = reservation()

        store.create(reservation)

        val locked =
            store.findForUpdate(
                marketId = reservation.marketId,
                orderId = reservation.orderId,
            )

        assertEquals(reservation, locked)
    }

    @Test
    fun `주문 동결 정보를 업데이트한다`() {
        val reservation = reservation()
        store.create(reservation)

        val released = reservation.release()

        store.update(released)

        assertEquals(
            released,
            store.find(
                marketId = reservation.marketId,
                orderId = reservation.orderId,
            ),
        )
    }

    @Test
    fun `존재하지 않는 주문 동결 정보는 업데이트할 수 없다`() {
        val reservation = reservation()

        val error =
            assertFailsWith<OrderReservationNotFoundException> {
                store.update(reservation)
            }

        assertEquals(reservation.marketId, error.marketId)
        assertEquals(reservation.orderId, error.orderId)
    }

    private fun reservation(
        marketId: MarketId = MarketId("BTC-KRW"),
        orderId: OrderId = OrderId("order-1"),
    ): OrderReservation =
        OrderReservation.create(
            marketId = marketId,
            orderId = orderId,
            userId = UserId("user-1"),
            side = Side.BUY,
            limitPrice = Price(100),
            quantity = Quantity(5),
            requirement =
                ReservationRequirement(
                    assetId = AssetId("KRW"),
                    amount = Amount(500),
                ),
        )

    companion object {
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
