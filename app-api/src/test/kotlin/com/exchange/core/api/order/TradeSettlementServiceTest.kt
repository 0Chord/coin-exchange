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
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.BalanceNotFoundException
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderReservationStore
import com.exchange.core.order.ReservationRequirement
import com.exchange.core.order.Side
import com.exchange.core.support.PostgresTestConfiguration
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 실제 PostgreSQL에서 체결 정산의 잔고·예약 변경, 수수료 원장 기록과 전체 롤백을 검증한다.
 *
 * 테스트 전체를 감싸는 트랜잭션은 사용하지 않는다. Spring이 주입한 [TradeSettlementService]의
 * 트랜잭션이 끝난 뒤 DB를 조회하여 서비스 자체의 커밋·롤백 결과를 확인한다.
 * 분할 체결의 소수 나머지, maker/taker 전환, 남은 예약 해제와 실패 후 재시도도 검사한다.
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
    private lateinit var fundingService: OrderFundingService

    @Autowired
    private lateinit var releaseService: OrderReservationReleaseService

    @Autowired
    private lateinit var reservationStore: OrderReservationStore

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    /**
     * 이전 테스트 데이터를 비우고 수수료 없는 BUY·SELL 예약과 지급받을 자산의 잔고를 준비한다.
     * 원장은 외래 키를 가진 분개부터 삭제하며, 준비한 데이터는 정산 트랜잭션 밖에 저장한다.
     */
    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("delete from ledger_postings")
        jdbcTemplate.update("delete from ledger_transactions")
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

    /**
     * 200원을 예약한 BUY가 180원에 전량 체결되면 20원을 반환하고 양쪽 예약을 정산 완료한다.
     */
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

    /** 지정가 100원에 수량 2개를 사기 위해 KRW 200원을 예약한 체결 전 BUY 주문을 만든다. */
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

    /** 지정가 90원에 수량 2개를 팔기 위해 BTC 최소 단위 2개를 예약한 체결 전 SELL 주문을 만든다. */
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

    /** 테스트 준비용 사용자·자산 잔고를 최소 단위의 available과 hold 값으로 직접 저장한다. */
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

    /** 실제 DB에서 읽은 available과 hold가 기대한 최소 단위 금액과 같은지 확인한다. */
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

    /**
     * 체결 대금 180,000원에서 taker BUY 수수료 1,800원과 maker SELL 수수료 900원을 반영한다.
     * 두 수수료의 합계 2,700원이 거래소 수익 계정에 기록되고 원장 거래는 하나만 생성되어야 한다.
     */
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

        val actualFeeRevenue =
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
            )

        assertEquals(
            2_700L,
            actualFeeRevenue,
            "구매자와 판매자의 수수료가 거래소 수익 계정에 기록되어야 한다",
        )

        val settlementTransactionCount =
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from ledger_transactions
                where transaction_type = 'SETTLEMENT'
                """.trimIndent(),
                Long::class.java,
            )

        assertEquals(
            1L,
            settlementTransactionCount,
            "한 체결의 양쪽 정산은 하나의 원장 거래로 기록되어야 한다",
        )
    }

    /**
     * 마지막 구매자 BTC 지급이 실패하면 먼저 저장한 원장과 양쪽 예약·잔고 변경도 취소한다.
     * 구매자 BTC 잔고 행의 삭제는 정산 호출 전 준비 작업이므로 정산 롤백 대상이 아니다.
     */
    @Test
    fun `잔고 지급이 실패하면 원장과 양쪽 예약 및 잔고 변경을 모두 롤백한다`() {
        // 판매자 정산과 구매자의 KRW 소비·반환 이후 BTC 지급 단계에서 실패하도록 준비한다.
        val deletedRows =
            jdbcTemplate.update(
                """
                delete from balance_projection
                where user_id = ?
                  and asset_id = ?
                """.trimIndent(),
                BUYER_USER_ID.value,
                BTC_ASSET_ID.value,
            )

        assertEquals(1, deletedRows)

        val trade =
            TradeExecuted(
                marketId = MARKET.marketId,
                engineSequence = 3,
                makerOrderId = SELLER_ORDER_ID,
                takerOrderId = BUYER_ORDER_ID,
                makerUserId = SELLER_USER_ID,
                takerUserId = BUYER_USER_ID,
                side = Side.BUY,
                price = Price(90),
                quantity = Quantity(2),
            )

        val exception =
            assertFailsWith<BalanceNotFoundException> {
                service.settle(
                    market = MARKET,
                    trade = trade,
                )
            }

        assertEquals(BUYER_USER_ID, exception.userId)
        assertEquals(BTC_ASSET_ID, exception.assetId)

        // 상태뿐 아니라 남은 수량과 예약 금액까지 체결 전 객체와 같아야 한다.
        assertEquals(
            buyerReservation(),
            reservationStore.find(
                marketId = MARKET.marketId,
                orderId = BUYER_ORDER_ID,
            ),
        )
        assertEquals(
            sellerReservation(),
            reservationStore.find(
                marketId = MARKET.marketId,
                orderId = SELLER_ORDER_ID,
            ),
        )

        assertPersistedBalance(
            userId = BUYER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 800,
            hold = 200,
        )

        assertPersistedBalance(
            userId = SELLER_USER_ID,
            assetId = BTC_ASSET_ID,
            available = 8,
            hold = 2,
        )
        assertPersistedBalance(
            userId = SELLER_USER_ID,
            assetId = KRW_ASSET_ID,
            available = 0,
            hold = 0,
        )

        // 잔고 변경보다 먼저 INSERT한 원장 거래와 분개도 함께 롤백되어야 한다.
        val transactionCount =
            jdbcTemplate.queryForObject(
                "select count(*) from ledger_transactions",
                Long::class.java,
            )

        val postingCount =
            jdbcTemplate.queryForObject(
                "select count(*) from ledger_postings",
                Long::class.java,
            )

        assertEquals(0L, transactionCount)
        assertEquals(0L, postingCount)
    }

    /**
     * 255원 주문을 51·51·51·102원으로 나눠 정산한다.
     * 매번 DB에서 재조회한 나머지가 다음 정산으로 이어지고 BUY 2원·SELL 1원이 청구되어야 한다.
     */
    @Test
    fun `BUY와 SELL 분할 체결은 DB 나머지를 이어받아 잔고와 수수료 원장을 정산한다`() {
        prepareFractionalFeeOrders()

        val quantities = listOf(1L, 1L, 1L, 2L)
        val buyerRemainders = listOf(510_000L, 20_000L, 530_000L, 550_000L)
        val sellerRemainders = listOf(255_000L, 510_000L, 765_000L, 275_000L)
        val buyerFeeReserves = listOf(3L, 2L, 2L, 0L)
        val buyerHolds = listOf(207L, 155L, 104L, 0L)
        val sellerCredits = listOf(51L, 102L, 153L, 254L)
        val feeRevenues = listOf(0L, 1L, 1L, 3L)
        var totalFilledQuantity = 0L

        for ((index, quantity) in quantities.withIndex()) {
            service.settle(MARKET, fractionalBuyTrade(index.toLong() + 1, quantity))
            totalFilledQuantity += quantity

            val buyer = readReservation(BUYER_ORDER_ID)
            val seller = readReservation(SELLER_ORDER_ID)
            val expectedStatus =
                if (totalFilledQuantity == 5L) {
                    OrderReservationStatus.SETTLED
                } else {
                    OrderReservationStatus.ACTIVE
                }

            assertEquals(FeeRemainder(buyerRemainders[index]), buyer.feeRemainder)
            assertEquals(FeeRemainder(sellerRemainders[index]), seller.feeRemainder)
            assertEquals(Amount(buyerFeeReserves[index]), buyer.remainingFeeReserveAmount)
            assertEquals(Amount.ZERO, seller.remainingFeeReserveAmount)
            assertEquals(Amount(buyerHolds[index]), buyer.remainingAmount)
            assertEquals(Amount(5 - totalFilledQuantity), seller.remainingAmount)
            assertEquals(Quantity(5 - totalFilledQuantity), buyer.remainingQuantity)
            assertEquals(Quantity(5 - totalFilledQuantity), seller.remainingQuantity)
            assertEquals(expectedStatus, buyer.status)
            assertEquals(expectedStatus, seller.status)

            assertPersistedBalance(
                BUYER_USER_ID,
                KRW_ASSET_ID,
                available = if (totalFilledQuantity == 5L) 743 else 742,
                hold = buyerHolds[index],
            )
            assertPersistedBalance(BUYER_USER_ID, BTC_ASSET_ID, totalFilledQuantity, 0)
            assertPersistedBalance(SELLER_USER_ID, BTC_ASSET_ID, 5, 5 - totalFilledQuantity)
            assertPersistedBalance(SELLER_USER_ID, KRW_ASSET_ID, sellerCredits[index], 0)
            assertSettlementLedger(index.toLong() + 1, feeRevenues[index])
        }
    }

    /**
     * BUY가 첫 51원은 taker, 나머지 204원은 maker로 체결된다.
     * 이전 나머지 0.51원에 새 maker 수수료 1.02원을 더해 총 1원만 청구해야 한다.
     */
    @Test
    fun `같은 BUY 주문이 taker에서 maker로 바뀌어도 이전 수수료 나머지를 유지한다`() {
        prepareFractionalFeeOrders(sellerQuantity = 1)
        service.settle(MARKET, fractionalBuyTrade(sequence = 1, quantity = 1))

        assertEquals(FeeRemainder(510_000), readReservation(BUYER_ORDER_ID).feeRemainder)
        assertEquals(OrderReservationStatus.SETTLED, readReservation(SELLER_ORDER_ID).status)

        // 최초 SELL은 전량 체결됐다. 남은 BUY를 새 SELL 주문이 taker로 체결한다.
        val nextSellerOrderId = OrderId("next-seller-order")
        fundingService.reserve(
            market = MARKET,
            orderId = nextSellerOrderId,
            userId = SELLER_USER_ID,
            side = Side.SELL,
            limitPrice = Price(51),
            quantity = Quantity(4),
            feePolicySnapshot = fractionalFeePolicy(),
        )

        service.settle(
            market = MARKET,
            trade =
                TradeExecuted(
                    marketId = MARKET.marketId,
                    engineSequence = 2,
                    makerOrderId = BUYER_ORDER_ID,
                    takerOrderId = nextSellerOrderId,
                    makerUserId = BUYER_USER_ID,
                    takerUserId = SELLER_USER_ID,
                    side = Side.SELL,
                    price = Price(51),
                    quantity = Quantity(4),
                ),
        )

        val buyer = readReservation(BUYER_ORDER_ID)
        val seller = readReservation(nextSellerOrderId)
        assertEquals(FeeRemainder(530_000), buyer.feeRemainder)
        assertEquals(FeeRemainder(40_000), seller.feeRemainder)
        assertEquals(Amount.ZERO, buyer.remainingAmount)
        assertEquals(Amount.ZERO, buyer.remainingFeeReserveAmount)
        assertEquals(Amount.ZERO, seller.remainingAmount)
        assertEquals(OrderReservationStatus.SETTLED, buyer.status)
        assertEquals(OrderReservationStatus.SETTLED, seller.status)
        assertPersistedBalance(BUYER_USER_ID, KRW_ASSET_ID, 744, 0)
        assertPersistedBalance(BUYER_USER_ID, BTC_ASSET_ID, 5, 0)
        assertPersistedBalance(SELLER_USER_ID, KRW_ASSET_ID, 253, 0)
        assertPersistedBalance(SELLER_USER_ID, BTC_ASSET_ID, 5, 0)
        assertSettlementLedger(expectedTransactionCount = 2, expectedFeeRevenue = 3)
    }

    /**
     * 두 번 체결한 뒤 남은 BUY·SELL 예약을 해제한다.
     * 청구된 BUY 수수료 1원은 유지하고, 미사용 예약만 반환하며 중복 해제는 잔고를 늘리지 않는다.
     */
    @Test
    fun `부분 체결 후 예약을 해제하면 이미 청구한 수수료는 유지하고 미사용 금액만 반환한다`() {
        prepareFractionalFeeOrders()
        service.settle(MARKET, fractionalBuyTrade(sequence = 1, quantity = 1))
        service.settle(MARKET, fractionalBuyTrade(sequence = 2, quantity = 1))

        val buyerBeforeRelease = readReservation(BUYER_ORDER_ID)
        val sellerBeforeRelease = readReservation(SELLER_ORDER_ID)
        val postingsBeforeRelease = readPostings()
        assertEquals(Amount(155), buyerBeforeRelease.remainingAmount)
        assertEquals(Amount(2), buyerBeforeRelease.remainingFeeReserveAmount)
        assertSettlementLedger(expectedTransactionCount = 2, expectedFeeRevenue = 1)

        // HTTP나 주문장은 이 테스트의 범위가 아니다. 취소 후 자금 해제 서비스를 검증한다.
        releaseService.release(MARKET.marketId, BUYER_ORDER_ID)
        releaseService.release(MARKET.marketId, SELLER_ORDER_ID)
        releaseService.release(MARKET.marketId, BUYER_ORDER_ID)
        releaseService.release(MARKET.marketId, SELLER_ORDER_ID)

        val buyer = readReservation(BUYER_ORDER_ID)
        val seller = readReservation(SELLER_ORDER_ID)
        assertEquals(buyerBeforeRelease.release(), buyer)
        assertEquals(sellerBeforeRelease.release(), seller)
        assertEquals(FeeRemainder(20_000), buyer.feeRemainder)
        assertEquals(FeeRemainder(510_000), seller.feeRemainder)
        assertPersistedBalance(BUYER_USER_ID, KRW_ASSET_ID, 897, 0)
        assertPersistedBalance(BUYER_USER_ID, BTC_ASSET_ID, 2, 0)
        assertPersistedBalance(SELLER_USER_ID, KRW_ASSET_ID, 102, 0)
        assertPersistedBalance(SELLER_USER_ID, BTC_ASSET_ID, 8, 0)
        assertEquals(postingsBeforeRelease, readPostings())
        assertSettlementLedger(expectedTransactionCount = 2, expectedFeeRevenue = 1)
    }

    /**
     * 첫 체결로 소수 나머지를 저장한 뒤 두 번째 정산의 마지막 지급을 실패시킨다.
     * 먼저 반영한 양쪽 예약·잔고·수수료 분개가 모두 롤백되고, 같은 이벤트 재시도는 한 번만 반영된다.
     */
    @Test
    fun `분할 정산 실패는 나머지와 수수료 원장도 롤백하고 재시도에서 한 번만 청구한다`() {
        prepareFractionalFeeOrders()
        service.settle(MARKET, fractionalBuyTrade(sequence = 1, quantity = 1))

        assertEquals(
            1,
            jdbcTemplate.update(
                "delete from balance_projection where user_id = ? and asset_id = ?",
                BUYER_USER_ID.value,
                BTC_ASSET_ID.value,
            ),
        )
        val buyerBeforeFailure = readReservation(BUYER_ORDER_ID)
        val sellerBeforeFailure = readReservation(SELLER_ORDER_ID)
        val balancesBeforeFailure = readBalances()
        val postingsBeforeFailure = readPostings()
        val transactionsBeforeFailure = readLedgerTransactions()
        val trade = fractionalBuyTrade(sequence = 2, quantity = 2)

        val exception =
            assertFailsWith<BalanceNotFoundException> {
                service.settle(MARKET, trade)
            }

        assertEquals(BUYER_USER_ID, exception.userId)
        assertEquals(BTC_ASSET_ID, exception.assetId)
        assertEquals(buyerBeforeFailure, readReservation(BUYER_ORDER_ID))
        assertEquals(sellerBeforeFailure, readReservation(SELLER_ORDER_ID))
        assertEquals(balancesBeforeFailure, readBalances())
        assertEquals(postingsBeforeFailure, readPostings())
        assertEquals(transactionsBeforeFailure, readLedgerTransactions())
        assertSettlementLedger(expectedTransactionCount = 1, expectedFeeRevenue = 0)

        // 실패 원인이었던 지급 계좌를 첫 체결 후 잔고로 복구하고 같은 이벤트를 다시 정산한다.
        insertBalance(BUYER_USER_ID, BTC_ASSET_ID, available = 1, hold = 0)
        service.settle(MARKET, trade)

        assertEquals(FeeRemainder(530_000), readReservation(BUYER_ORDER_ID).feeRemainder)
        assertEquals(FeeRemainder(765_000), readReservation(SELLER_ORDER_ID).feeRemainder)
        assertEquals(Amount(2), readReservation(BUYER_ORDER_ID).remainingFeeReserveAmount)
        assertPersistedBalance(BUYER_USER_ID, KRW_ASSET_ID, 742, 104)
        assertPersistedBalance(BUYER_USER_ID, BTC_ASSET_ID, 3, 0)
        assertPersistedBalance(SELLER_USER_ID, KRW_ASSET_ID, 153, 0)
        assertPersistedBalance(SELLER_USER_ID, BTC_ASSET_ID, 5, 2)
        assertSettlementLedger(expectedTransactionCount = 2, expectedFeeRevenue = 1)
    }

    /** 기본 무수수료 fixture를 소액 분할 체결용으로 교체하고 실제 서비스로 자금을 예약한다. */
    private fun prepareFractionalFeeOrders(sellerQuantity: Long = 5) {
        jdbcTemplate.update("delete from order_reservations")
        jdbcTemplate.update("delete from balance_projection")
        insertBalance(BUYER_USER_ID, KRW_ASSET_ID, 1_000, 0)
        insertBalance(BUYER_USER_ID, BTC_ASSET_ID, 0, 0)
        insertBalance(SELLER_USER_ID, KRW_ASSET_ID, 0, 0)
        insertBalance(SELLER_USER_ID, BTC_ASSET_ID, 10, 0)

        fundingService.reserve(
            market = MARKET,
            orderId = BUYER_ORDER_ID,
            userId = BUYER_USER_ID,
            side = Side.BUY,
            limitPrice = Price(51),
            quantity = Quantity(5),
            feePolicySnapshot = fractionalFeePolicy(),
        )
        fundingService.reserve(
            market = MARKET,
            orderId = SELLER_ORDER_ID,
            userId = SELLER_USER_ID,
            side = Side.SELL,
            limitPrice = Price(51),
            quantity = Quantity(sellerQuantity),
            feePolicySnapshot = fractionalFeePolicy(),
        )
    }

    /** 소액 체결에서 서로 다른 소수 나머지가 생기도록 maker 0.5%·taker 1%를 적용한다. */
    private fun fractionalFeePolicy(): TradingFeePolicySnapshot =
        feeFreePolicySnapshot.copy(
            feeRates =
                MakerTakerFeeRates(
                    makerFeeRate = FeeRate(5_000),
                    takerFeeRate = FeeRate(10_000),
                ),
        )

    /** 기존 SELL이 maker이고 BUY가 taker인 51원 체결을 만든다. 매칭 엔진 자체는 실행하지 않는다. */
    private fun fractionalBuyTrade(sequence: Long, quantity: Long): TradeExecuted =
        TradeExecuted(
            marketId = MARKET.marketId,
            engineSequence = sequence,
            makerOrderId = SELLER_ORDER_ID,
            takerOrderId = BUYER_ORDER_ID,
            makerUserId = SELLER_USER_ID,
            takerUserId = BUYER_USER_ID,
            side = Side.BUY,
            price = Price(51),
            quantity = Quantity(quantity),
        )

    /** 직전 서비스 트랜잭션이 커밋한 예약을 DB에서 다시 읽는다. */
    private fun readReservation(orderId: OrderId): OrderReservation =
        requireNotNull(reservationStore.find(MARKET.marketId, orderId))

    /** 실패 전후의 실제 DB 행 전체를 비교한다. */
    private fun readBalances(): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("select * from balance_projection order by user_id, asset_id")

    /** 분개 순서를 고정해 실패·예약 해제 전후에 기존 수수료 기록이 바뀌지 않는지 확인한다. */
    private fun readPostings(): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("select * from ledger_postings order by posting_id")

    /** 실패한 정산의 원장 거래가 남지 않고 기존 거래도 유지되는지 확인한다. */
    private fun readLedgerTransactions(): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("select * from ledger_transactions order by source_event_id")

    /** 정산별·자산별 차대 일치와 실제 수수료 수익 계정의 순 CREDIT을 DB에서 검증한다. */
    private fun assertSettlementLedger(expectedTransactionCount: Long, expectedFeeRevenue: Long) {
        assertEquals(
            expectedTransactionCount,
            jdbcTemplate.queryForObject(
                "select count(*) from ledger_transactions where transaction_type = 'SETTLEMENT'",
                Long::class.java,
            ),
        )
        assertEquals(
            expectedFeeRevenue,
            jdbcTemplate.queryForObject(
                """
                select coalesce(sum(case when side = 'CREDIT' then amount else -amount end), 0)
                from ledger_postings
                where account_id = 'SYSTEM:KRW:FEE_REVENUE'
                  and asset_id = 'KRW'
                """.trimIndent(),
                Long::class.java,
            ),
        )

        val unbalancedTransactions =
            jdbcTemplate.queryForList(
                """
                select ledger_transaction_id, asset_id
                from ledger_postings
                group by ledger_transaction_id, asset_id
                having sum(case when side = 'DEBIT' then amount else -amount end) <> 0
                """.trimIndent(),
            )
        assertTrue(unbalancedTransactions.isEmpty(), "각 정산은 자산별 차변과 대변 합계가 같아야 한다")
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
    }
}
