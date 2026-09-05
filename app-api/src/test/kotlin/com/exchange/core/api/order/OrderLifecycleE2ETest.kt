package com.exchange.core.api.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderReservationStore
import com.exchange.core.order.Side
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * LIMIT/GTC 주문의 HTTP 접수부터 자금 예약, 매칭, 이벤트 저장, 정산과 수수료 원장까지 검증한다.
 *
 * 서비스 대역 없이 MockMvc와 실제 PostgreSQL을 사용한다. 한 번의 전량 체결과 미체결
 * BUY·SELL 취소를 검증하며, 장애 복구·재시도는 포함하지 않는다. 초기 잔고는 원장 없이 직접 준비하므로
 * 체결 원장의 차변·대변 균형 검증이 전체 잔고 대사를 의미하지는 않는다.
 */
@SpringBootTest(
    properties = [
        "exchange.matching.persistence.enabled=true",
        "exchange.ledger.persistence.enabled=true",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@AutoConfigureMockMvc
@Import(OrderLifecycleE2ETest.TestOrderLifecycleConfig::class)
@Testcontainers
class OrderLifecycleE2ETest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var reservationStore: OrderReservationStore

    /**
     * 테스트 DB를 비우고 구매자 KRW 1,000,000원과 판매자 BTC 최소 단위 10개를 준비한다.
     * 반대편 자산을 지급할 수 있도록 구매자 BTC와 판매자 KRW의 빈 잔고 행도 만든다.
     */
    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("delete from ledger_postings")
        jdbcTemplate.update("delete from ledger_transactions")
        jdbcTemplate.update("delete from matching_events")
        jdbcTemplate.update("delete from order_reservations")
        jdbcTemplate.update("delete from balance_projection")

        insertBalance(
            userId = BUYER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 1_000_000,
            hold = 0,
        )

        insertBalance(
            userId = BUYER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 0,
            hold = 0,
        )

        insertBalance(
            userId = SELLER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 10,
            hold = 0,
        )

        insertBalance(
            userId = SELLER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 0,
            hold = 0,
        )
    }

    /**
     * 90,000원 SELL 수량 2개를 먼저 대기시킨 뒤 100,000원 BUY 수량 2개로 전량 체결한다.
     * 체결 대금 180,000원, 구매자 수수료 1,800원, 판매자 수수료 900원을 반영하고
     * 양쪽 예약 종료, 잔고 이동과 거래소 수수료 수익 2,700원을 확인한다.
     */
    @Test
    fun `SELL 주문과 BUY 주문이 체결되면 양쪽 예약과 잔고가 정산된다`() {
        submitOrder(
            orderId = SELLER_ORDER_ID.value,
            userId = SELLER_USER_ID,
            side = Side.SELL,
            price = 90_000,
            quantity = 2,
        )
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.events[0].type")
                    .value("ORDER_ENTERED_BOOK"),
            )
            .andExpect(
                jsonPath("$.events[0].orderId")
                    .value(SELLER_ORDER_ID.value),
            )

        assertPersistedBalance(
            userId = SELLER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 8,
            hold = 2,
        )

        val activeSellerReservation =
            findReservation(SELLER_ORDER_ID)

        assertEquals(
            OrderReservationStatus.ACTIVE,
            activeSellerReservation.status,
        )
        assertEquals(
            Amount(2),
            activeSellerReservation.remainingAmount,
        )
        assertEquals(
            Amount.ZERO,
            activeSellerReservation.remainingFeeReserveAmount,
        )

        submitOrder(
            orderId = BUYER_ORDER_ID.value,
            userId = BUYER_USER_ID,
            side = Side.BUY,
            price = 100_000,
            quantity = 2,
        )
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.events[0].type")
                    .value("TRADE_EXECUTED"),
            )
            .andExpect(
                jsonPath("$.events[0].makerOrderId")
                    .value(SELLER_ORDER_ID.value),
            )
            .andExpect(
                jsonPath("$.events[0].takerOrderId")
                    .value(BUYER_ORDER_ID.value),
            )
            .andExpect(
                jsonPath("$.events[0].price")
                    .value(90_000),
            )
            .andExpect(
                jsonPath("$.events[0].quantity")
                    .value(2),
            )

        val settledBuyerReservation =
            findReservation(BUYER_ORDER_ID)

        val settledSellerReservation =
            findReservation(SELLER_ORDER_ID)

        assertEquals(
            OrderReservationStatus.SETTLED,
            settledBuyerReservation.status,
        )
        assertEquals(
            Amount.ZERO,
            settledBuyerReservation.remainingAmount,
        )
        assertEquals(
            Amount.ZERO,
            settledBuyerReservation.remainingFeeReserveAmount,
        )

        assertEquals(
            OrderReservationStatus.SETTLED,
            settledSellerReservation.status,
        )
        assertEquals(
            Amount.ZERO,
            settledSellerReservation.remainingAmount,
        )
        assertEquals(
            Amount.ZERO,
            settledSellerReservation.remainingFeeReserveAmount,
        )

        assertPersistedBalance(
            userId = BUYER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 818_200,
            hold = 0,
        )

        assertPersistedBalance(
            userId = BUYER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 2,
            hold = 0,
        )

        assertPersistedBalance(
            userId = SELLER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 8,
            hold = 0,
        )

        assertPersistedBalance(
            userId = SELLER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 179_100,
            hold = 0,
        )

        val matchingEventCount =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    """
                    select count(*)
                    from matching_events
                    where market_id = ?
                    """.trimIndent(),
                    Long::class.java,
                    MARKET.marketId.value,
                ),
            )

        assertEquals(
            2L,
            matchingEventCount,
        )

        assertPersistedFeeRevenue(
            expectedAmount = 2_700L,
        )

        assertLedgerTransactionsBalanced()
    }

    /**
     * 100,000원 BUY 수량 2개에 예약한 거래 대금 200,000원과 수수료 2,000원을
     * 취소 시 모두 반환한다. 체결되지 않았으므로 거래소 수수료 수익은 발생하지 않는다.
     */
    @Test
    fun `미체결 BUY 주문을 취소하면 거래 대금과 수수료 예약금을 모두 반환한다`() {
        val orderId = OrderId("e2e-cancel-buy-order")

        submitOrder(
            orderId = orderId.value,
            userId = BUYER_USER_ID,
            side = Side.BUY,
            price = 100_000,
            quantity = 2,
        )
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.events[0].type")
                    .value("ORDER_ENTERED_BOOK"),
            )

        val activeReservation = findReservation(orderId)

        assertEquals(
            OrderReservationStatus.ACTIVE,
            activeReservation.status,
        )
        assertEquals(
            Amount(202_000),
            activeReservation.remainingAmount,
        )
        assertEquals(
            Amount(2_000),
            activeReservation.remainingFeeReserveAmount,
        )

        assertPersistedBalance(
            userId = BUYER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 798_000,
            hold = 202_000,
        )

        mockMvc.perform(
            delete(
                "/api/markets/{marketId}/orders/{orderId}",
                MARKET.marketId.value,
                orderId.value,
            ).param("userId", BUYER_USER_ID.value),
        )
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.events[0].type")
                    .value("ORDER_CANCELLED"),
            )
            .andExpect(
                jsonPath("$.events[0].orderId")
                    .value(orderId.value),
            )

        val releasedReservation = findReservation(orderId)

        assertEquals(
            OrderReservationStatus.RELEASED,
            releasedReservation.status,
        )
        assertEquals(
            Amount.ZERO,
            releasedReservation.remainingAmount,
        )
        assertEquals(
            Amount.ZERO,
            releasedReservation.remainingFeeReserveAmount,
        )

        assertEquals(
            Quantity(2),
            releasedReservation.remainingQuantity,
        )

        assertPersistedBalance(
            userId = BUYER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 1_000_000,
            hold = 0,
        )

        assertPersistedFeeRevenue(
            expectedAmount = 0L,
        )
    }

    /**
     * 미체결 SELL 주문에 예약한 BTC를 모두 반환한다.
     * 체결되지 않았으므로 KRW 지급이나 수수료 수익은 발생하지 않는다.
     */
    @Test
    fun `미체결 SELL 주문을 취소하면 예약한 BTC를 모두 반환한다`() {
        val orderId = OrderId("e2e-cancel-sell-order")

        submitOrder(
            orderId = orderId.value,
            userId = SELLER_USER_ID,
            side = Side.SELL,
            price = 90_000,
            quantity = 2,
        )
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.events[0].type")
                    .value("ORDER_ENTERED_BOOK"),
            )

        val activeReservation = findReservation(orderId)

        assertEquals(
            OrderReservationStatus.ACTIVE,
            activeReservation.status,
        )
        assertEquals(BTC_ASSET_ID, activeReservation.assetId)
        assertEquals(Amount(2), activeReservation.remainingAmount)
        assertEquals(
            Amount.ZERO,
            activeReservation.remainingFeeReserveAmount,
        )

        assertPersistedBalance(
            userId = SELLER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 8,
            hold = 2,
        )

        mockMvc.perform(
            delete(
                "/api/markets/{marketId}/orders/{orderId}",
                MARKET.marketId.value,
                orderId.value,
            ).param("userId", SELLER_USER_ID.value),
        )
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.events[0].type")
                    .value("ORDER_CANCELLED"),
            )
            .andExpect(
                jsonPath("$.events[0].orderId")
                    .value(orderId.value),
            )
            .andExpect(
                jsonPath("$.events[0].remainingQuantity")
                    .value(2),
            )

        val releasedReservation = findReservation(orderId)

        assertEquals(
            OrderReservationStatus.RELEASED,
            releasedReservation.status,
        )
        assertEquals(Amount.ZERO, releasedReservation.remainingAmount)
        assertEquals(
            Amount.ZERO,
            releasedReservation.remainingFeeReserveAmount,
        )
        assertEquals(Quantity(2), releasedReservation.remainingQuantity)

        assertPersistedBalance(
            userId = SELLER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 10,
            hold = 0,
        )
        assertPersistedBalance(
            userId = SELLER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 0,
            hold = 0,
        )
        assertPersistedFeeRevenue(expectedAmount = 0L)
    }

    /** KRW 수수료 수익 계정의 CREDIT 합계에서 DEBIT 합계를 뺀 실제 기록 금액을 확인한다. */
    private fun assertPersistedFeeRevenue(expectedAmount: Long) {
        val actualFeeRevenue =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    """
                    select coalesce(
                        sum(
                            case
                                when side = 'CREDIT' then amount
                                when side = 'DEBIT' then -amount
                                else 0
                            end
                        ),
                        0
                    )
                    from ledger_postings
                    where account_id = ?
                      and asset_id = ?
                    """.trimIndent(),
                    Long::class.java,
                    "SYSTEM:KRW:FEE_REVENUE",
                    KRW_ASSET_ID.value,
                ),
            )

        assertEquals(
            expectedAmount,
            actualFeeRevenue,
            "구매자와 판매자의 수수료가 거래소 수익 계정에 기록되어야 한다",
        )
    }

    /** 각 원장 거래를 자산별로 묶었을 때 차변·대변 합계가 다른 그룹이 없어야 한다. */
    private fun assertLedgerTransactionsBalanced() {
        val unbalancedGroupCount =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    """
                    select count(*)
                    from (
                        select ledger_transaction_id, asset_id
                        from ledger_postings
                        group by ledger_transaction_id, asset_id
                        having
                            sum(
                                case when side = 'DEBIT' then amount else 0 end
                            )
                            <>
                            sum(
                                case when side = 'CREDIT' then amount else 0 end
                            )
                    ) as unbalanced_groups
                    """.trimIndent(),
                    Long::class.java,
                ),
            )

        assertEquals(
            0L,
            unbalancedGroupCount,
            "각 원장 거래에서 자산별 차변·대변 합계가 일치해야 한다",
        )
    }

    /** LIMIT/GTC 주문을 HTTP 요청으로 전달하고 응답 검증을 이어갈 수 있는 결과를 반환한다. */
    private fun submitOrder(
        orderId: String,
        userId: UserId,
        side: Side,
        price: Long,
        quantity: Long,
    ): ResultActions =
        mockMvc.perform(
            post(
                "/api/markets/${MARKET.marketId.value}/orders",
            )
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "orderId": "$orderId",
                      "userId": "${userId.value}",
                      "side": "${side.name}",
                      "orderType": "LIMIT",
                      "timeInForce": "GTC",
                      "price": $price,
                      "quantity": $quantity
                    }
                    """.trimIndent(),
                ),
        )

    /** 테스트 마켓에서 해당 주문의 실제 예약을 조회하며, 없으면 테스트를 실패시킨다. */
    private fun findReservation(orderId: OrderId): OrderReservation =
        requireNotNull(
            reservationStore.find(
                marketId = MARKET.marketId,
                orderId = orderId,
            ),
        )

    /** 최소 단위의 available과 hold를 사용하여 테스트 시작 잔고를 직접 저장한다. */
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

    /** HTTP 주문 처리가 끝난 뒤 실제 DB의 available과 hold를 기대값과 비교한다. */
    private fun assertPersistedBalance(
        userId: UserId,
        assetId: AssetId,
        available: Long,
        hold: Long,
    ) {
        val savedBalance =
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
            (savedBalance["available"] as Number).toLong(),
        )
        assertEquals(
            hold,
            (savedBalance["hold"] as Number).toLong(),
        )
    }

    /** 운영 설정 대신 이 E2E에서 사용할 단일 마켓과 고정 수수료 정책을 제공한다. */
    @TestConfiguration(proxyBeanMethods = false)
    class TestOrderLifecycleConfig {
        /** 수량 scale이 0인 BTC-KRW 테스트 마켓을 제공한다. */
        @Bean
        fun marketDefinition(): MarketDefinition = MARKET

        /** 현물 NORMAL 등급의 maker 0.5%, taker 1% 수수료 정책을 제공한다. */
        @Bean
        fun tradingFeePolicySnapshot(): TradingFeePolicySnapshot =
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

        private val BUYER_ORDER_ID =
            OrderId("e2e-buyer-order")

        private val SELLER_ORDER_ID =
            OrderId("e2e-seller-order")

        private val BUYER_USER_ID =
            UserId("e2e-buyer")

        private val SELLER_USER_ID =
            UserId("e2e-seller")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(
                DockerImageName.parse("postgres:16-alpine"),
            )

        @DynamicPropertySource
        @JvmStatic
        fun registerPostgresProperties(
            registry: DynamicPropertyRegistry,
        ) {
            registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl,
            )
            registry.add(
                "spring.datasource.username",
                postgres::getUsername,
            )
            registry.add(
                "spring.datasource.password",
                postgres::getPassword,
            )
        }
    }
}
