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
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
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
import kotlin.test.Test
import kotlin.test.assertEquals

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
class TradeSettlementServiceTest {
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
    private lateinit var service: TradeSettlementService

    @Autowired
    private lateinit var reservationStore: OrderReservationStore

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("delete from order_reservations")
        jdbcTemplate.update("delete from balance_projection")

        /*
         * 구매자는 원래 KRW 1,000원을 가지고 있었다.
         *
         * 지정가 100원 × 수량 2개 = 200원을 주문에 예약했으므로:
         * available = 800
         * hold = 200
         */
        insertBalance(
            userId = BUYER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 800,
            hold = 200,
        )

        /*
         * 구매자가 체결 후 BTC를 받을 수 있도록
         * BTC Balance row도 미리 준비한다.
         */
        insertBalance(
            userId = BUYER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 0,
            hold = 0,
        )

        /*
         * 판매자는 원래 BTC 최소 단위 10개를 가지고 있었다.
         *
         * SELL 수량 2개를 예약했으므로:
         * available = 8
         * hold = 2
         */
        insertBalance(
            userId = SELLER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 8,
            hold = 2,
        )

        /*
         * 판매자가 체결 대금 KRW를 받을 수 있도록
         * KRW Balance row도 미리 준비한다.
         */
        insertBalance(
            userId = SELLER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 0,
            hold = 0,
        )

        reservationStore.create(buyerReservation())
        reservationStore.create(sellerReservation())
    }

    @Test
    fun `taker BUY 체결은 양쪽 예약과 잔고를 한 트랜잭션으로 정산한다`() {
        /*
         * maker는 90원에 BTC 2개를 팔려는 SELL 주문이다.
         * taker는 최대 100원까지 허용한 BUY 주문이다.
         *
         * maker 가격인 90원에 2개가 체결된다.
         */
        val trade =
            TradeExecuted(
                marketId = MARKET.marketId,
                engineSequence = 1,
                makerOrderId = SELLER_ORDER_ID,
                takerOrderId = BUYER_ORDER_ID,
                makerUserId = SELLER_USER_ID,
                takerUserId = BUYER_USER_ID,
                side = Side.BUY,
                price = Price(90),
                quantity = Quantity(2),
            )

        service.settle(
            market = MARKET,
            trade = trade,
        )

        val savedBuyerReservation =
            requireNotNull(
                reservationStore.find(
                    marketId = MARKET.marketId,
                    orderId = BUYER_ORDER_ID,
                ),
            )

        val savedSellerReservation =
            requireNotNull(
                reservationStore.find(
                    marketId = MARKET.marketId,
                    orderId = SELLER_ORDER_ID,
                ),
            )

        /*
         * 두 주문 모두 수량 2개를 주문했고 2개가 전부 체결됐으므로
         * 남은 수량과 예약 금액은 0이고 상태는 SETTLED다.
         */
        assertEquals(
            Quantity.ZERO,
            savedBuyerReservation.remainingQuantity,
        )
        assertEquals(
            Amount.ZERO,
            savedBuyerReservation.remainingAmount,
        )
        assertEquals(
            OrderReservationStatus.SETTLED,
            savedBuyerReservation.status,
        )

        assertEquals(
            Quantity.ZERO,
            savedSellerReservation.remainingQuantity,
        )
        assertEquals(
            Amount.ZERO,
            savedSellerReservation.remainingAmount,
        )
        assertEquals(
            OrderReservationStatus.SETTLED,
            savedSellerReservation.status,
        )

        /*
         * 구매자 KRW:
         *
         * 지정가 예약 금액 = 100 × 2 = 200
         * 실제 체결 대금 = 90 × 2 = 180
         * 가격 개선 반환액 = 200 - 180 = 20
         *
         * available: 800 + 20 = 820
         * hold: 200 - 180 - 20 = 0
         */
        assertPersistedBalance(
            userId = BUYER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 820,
            hold = 0,
        )

        /*
         * 구매자는 체결된 BTC 최소 단위 2개를 지급받는다.
         */
        assertPersistedBalance(
            userId = BUYER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 2,
            hold = 0,
        )

        /*
         * 판매자가 예약한 BTC 2개는 전부 거래에 사용된다.
         *
         * available은 이미 주문 예약 시 10에서 8로 줄었으므로
         * 체결에서는 hold만 2에서 0으로 줄어든다.
         */
        assertPersistedBalance(
            userId = SELLER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 8,
            hold = 0,
        )

        /*
         * 판매자는 실제 체결 대금 90 × 2 = 180 KRW를 지급받는다.
         */
        assertPersistedBalance(
            userId = SELLER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 180,
            hold = 0,
        )
    }

    private fun buyerReservation(): OrderReservation =
        OrderReservation.create(
            marketId = MARKET.marketId,
            orderId = BUYER_ORDER_ID,
            userId = BUYER_USER_ID,
            side = Side.BUY,
            limitPrice = Price(100),
            quantity = Quantity(2),
            requirement =
                ReservationRequirement(
                    assetId = KRW_ASSET_ID,
                    amount = Amount(200),
                ),
            feePolicySnapshot = feeFreePolicySnapshot,
        )

    private fun sellerReservation(): OrderReservation =
        OrderReservation.create(
            marketId = MARKET.marketId,
            orderId = SELLER_ORDER_ID,
            userId = SELLER_USER_ID,
            side = Side.SELL,
            limitPrice = Price(90),
            quantity = Quantity(2),
            requirement =
                ReservationRequirement(
                    assetId = BTC_ASSET_ID,
                    amount = Amount(2),
                ),
            feePolicySnapshot = feeFreePolicySnapshot,
        )

    private fun insertBalance(
        userId: UserId,
        assetId: AssetId,
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
            userId.value,
            assetId.value,
            available,
            hold,
        )
    }

    private fun assertPersistedBalance(
        userId: UserId,
        assetId: AssetId,
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
                userId.value,
                assetId.value,
            )

        assertEquals(
            available,
            (saved["available"] as Number).toLong(),
        )
        assertEquals(
            hold,
            (saved["hold"] as Number).toLong(),
        )
    }

    @Test
    fun `taker BUY와 maker SELL 수수료를 각각 잔고에 반영한다`() {
        val feeBuyerUserId = UserId("fee-buyer")
        val feeSellerUserId = UserId("fee-seller")
        val feeBuyerOrderId = OrderId("fee-buyer-order")
        val feeSellerOrderId = OrderId("fee-seller-order")

        val feePolicySnapshot =
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

        insertBalance(
            userId = feeBuyerUserId,
            assetId = KRW_ASSET_ID,
            available = 798_000,
            hold = 202_000,
        )

        insertBalance(
            userId = feeBuyerUserId,
            assetId = BTC_ASSET_ID,
            available = 0,
            hold = 0,
        )

        insertBalance(
            userId = feeSellerUserId,
            assetId = BTC_ASSET_ID,
            available = 8,
            hold = 2,
        )

        insertBalance(
            userId = feeSellerUserId,
            assetId = KRW_ASSET_ID,
            available = 0,
            hold = 0,
        )

        reservationStore.create(
            OrderReservation.create(
                marketId = MARKET.marketId,
                orderId = feeBuyerOrderId,
                userId = feeBuyerUserId,
                side = Side.BUY,
                limitPrice = Price(100_000),
                quantity = Quantity(2),
                requirement =
                    ReservationRequirement(
                        assetId = KRW_ASSET_ID,
                        tradeReserveAmount = Amount(200_000),
                        feeReserveAmount = Amount(2_000),
                    ),
                feePolicySnapshot = feePolicySnapshot,
            ),
        )

        reservationStore.create(
            OrderReservation.create(
                marketId = MARKET.marketId,
                orderId = feeSellerOrderId,
                userId = feeSellerUserId,
                side = Side.SELL,
                limitPrice = Price(90_000),
                quantity = Quantity(2),
                requirement =
                    ReservationRequirement(
                        assetId = BTC_ASSET_ID,
                        tradeReserveAmount = Amount(2),
                        feeReserveAmount = Amount.ZERO,
                    ),
                feePolicySnapshot = feePolicySnapshot,
            ),
        )

        val trade =
            TradeExecuted(
                marketId = MARKET.marketId,
                engineSequence = 2,
                makerOrderId = feeSellerOrderId,
                takerOrderId = feeBuyerOrderId,
                makerUserId = feeSellerUserId,
                takerUserId = feeBuyerUserId,
                side = Side.BUY,
                price = Price(90_000),
                quantity = Quantity(2),
            )

        service.settle(
            market = MARKET,
            trade = trade,
        )

        val savedBuyerReservation =
            requireNotNull(
                reservationStore.find(
                    marketId = MARKET.marketId,
                    orderId = feeBuyerOrderId,
                ),
            )

        val savedSellerReservation =
            requireNotNull(
                reservationStore.find(
                    marketId = MARKET.marketId,
                    orderId = feeSellerOrderId,
                ),
            )

        assertEquals(
            Quantity.ZERO,
            savedBuyerReservation.remainingQuantity,
        )
        assertEquals(
            Amount.ZERO,
            savedBuyerReservation.remainingAmount,
        )
        assertEquals(
            Amount.ZERO,
            savedBuyerReservation.remainingFeeReserveAmount,
        )
        assertEquals(
            OrderReservationStatus.SETTLED,
            savedBuyerReservation.status,
        )

        assertEquals(
            Quantity.ZERO,
            savedSellerReservation.remainingQuantity,
        )
        assertEquals(
            Amount.ZERO,
            savedSellerReservation.remainingAmount,
        )
        assertEquals(
            Amount.ZERO,
            savedSellerReservation.remainingFeeReserveAmount,
        )
        assertEquals(
            OrderReservationStatus.SETTLED,
            savedSellerReservation.status,
        )

        assertPersistedBalance(
            userId = feeBuyerUserId,
            assetId = KRW_ASSET_ID,
            available = 818_200,
            hold = 0,
        )

        assertPersistedBalance(
            userId = feeBuyerUserId,
            assetId = BTC_ASSET_ID,
            available = 2,
            hold = 0,
        )

        assertPersistedBalance(
            userId = feeSellerUserId,
            assetId = BTC_ASSET_ID,
            available = 8,
            hold = 0,
        )

        assertPersistedBalance(
            userId = feeSellerUserId,
            assetId = KRW_ASSET_ID,
            available = 179_100,
            hold = 0,
        )
    }

    companion object {
        private val MARKET =
            MarketDefinition(
                marketId = MarketId("BTC-KRW"),
                baseAssetId = AssetId("BTC"),
                quoteAssetId = AssetId("KRW"),
                baseAssetScale = 0,
            )

        private val BTC_ASSET_ID = MARKET.baseAssetId
        private val KRW_ASSET_ID = MARKET.quoteAssetId

        private val BUYER_ORDER_ID = OrderId("buyer-order")
        private val SELLER_ORDER_ID = OrderId("seller-order")

        private val BUYER_USER_ID = UserId("buyer")
        private val SELLER_USER_ID = UserId("seller")

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