package com.exchange.core.api.matching

import com.exchange.core.api.matching.persistence.MatchingEventRepository
import com.exchange.core.api.matching.persistence.MatchingEventType
import com.exchange.core.common.Amount
import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderReservationStore
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.support.ExchangeIntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * HTTP 요청을 실제 주문 서비스·매칭 엔진·PostgreSQL까지 실행하는 컨트롤러 통합 테스트.
 * 호출 횟수 대신 응답 JSON과 저장된 예약·잔고·이벤트를 확인한다.
 * 각 테스트는 새 context에서 시작하므로 남은 주문이나 엔진 순번이 다른 테스트에 섞이지 않는다.
 */
@AutoConfigureMockMvc
class MatchingControllerTest : ExchangeIntegrationTest() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var market: MarketDefinition

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var reservationStore: OrderReservationStore

    @Autowired
    private lateinit var eventRepository: MatchingEventRepository

    /** 구매자의 KRW와 판매자의 BTC, 체결 시 받을 반대편 자산의 빈 잔고를 준비한다. */
    @BeforeEach
    fun setUp() {
        insertBalance("buyer-1", "KRW", 1_000_000)
        insertBalance("buyer-1", "BTC", 0)
        insertBalance("seller-1", "BTC", 10)
        insertBalance("seller-1", "KRW", 0)
    }

    @Test
    fun `주문을 접수하면 자금을 예약하고 book entered event를 반환한다`() {
        submitOrder(orderId = "order-1")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("ORDER_ENTERED_BOOK"))
            .andExpect(jsonPath("$.events[0].marketId").value(market.marketId.value))
            .andExpect(jsonPath("$.events[0].engineSequence").value(1))
            .andExpect(jsonPath("$.events[0].orderId").value("order-1"))
            .andExpect(jsonPath("$.events[0].userId").value("buyer-1"))
            .andExpect(jsonPath("$.events[0].side").value("BUY"))
            .andExpect(jsonPath("$.events[0].price").value(100))
            .andExpect(jsonPath("$.events[0].remainingQuantity").value(5))

        val reservation = findReservation("order-1")
        assertEquals(OrderReservationStatus.ACTIVE, reservation.status)
        assertEquals(Amount(505), reservation.remainingAmount)
        assertEquals(Amount(5), reservation.remainingFeeReserveAmount)
        assertBalance("buyer-1", "KRW", available = 999_495, hold = 505)
        assertEquals(
            MatchingEventType.ORDER_ENTERED_BOOK,
            eventRepository.findAll().single().eventType,
        )
    }

    /** 체결 대금 300원에서 구매자 수수료 3원, 판매자 수수료 버림값 1원이 실제 반영된다. */
    @Test
    fun `주문이 체결되면 정산 후 trade executed event를 반환한다`() {
        submitOrder(
            orderId = "ask-1",
            userId = "seller-1",
            side = Side.SELL,
            quantity = 3,
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("ORDER_ENTERED_BOOK"))

        submitOrder(orderId = "bid-1", price = 110, quantity = 3)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("TRADE_EXECUTED"))
            .andExpect(jsonPath("$.events[0].marketId").value(market.marketId.value))
            .andExpect(jsonPath("$.events[0].engineSequence").value(2))
            .andExpect(jsonPath("$.events[0].makerOrderId").value("ask-1"))
            .andExpect(jsonPath("$.events[0].takerOrderId").value("bid-1"))
            .andExpect(jsonPath("$.events[0].side").value("BUY"))
            .andExpect(jsonPath("$.events[0].price").value(100))
            .andExpect(jsonPath("$.events[0].quantity").value(3))

        assertEquals(OrderReservationStatus.SETTLED, findReservation("ask-1").status)
        assertEquals(OrderReservationStatus.SETTLED, findReservation("bid-1").status)
        assertBalance("buyer-1", "KRW", available = 999_697, hold = 0)
        assertBalance("buyer-1", "BTC", available = 3, hold = 0)
        assertBalance("seller-1", "KRW", available = 299, hold = 0)
        assertBalance("seller-1", "BTC", available = 7, hold = 0)
        val savedEventTypes =
            eventRepository
                .findByMarketIdOrderByEngineSequenceAsc(market.marketId.value)
                .map { it.eventType }
        assertEquals(
            listOf(MatchingEventType.ORDER_ENTERED_BOOK, MatchingEventType.TRADE_EXECUTED),
            savedEventTypes,
        )
    }

    @Test
    fun `취소 요청은 예약금을 반환하고 cancelled event를 반환한다`() {
        submitOrder(orderId = "cancel-1").andExpect(status().isOk)

        cancelOrder(orderId = "cancel-1")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("ORDER_CANCELLED"))
            .andExpect(jsonPath("$.events[0].marketId").value(market.marketId.value))
            .andExpect(jsonPath("$.events[0].engineSequence").value(2))
            .andExpect(jsonPath("$.events[0].orderId").value("cancel-1"))
            .andExpect(jsonPath("$.events[0].userId").value("buyer-1"))
            .andExpect(jsonPath("$.events[0].remainingQuantity").value(5))

        val reservation = findReservation("cancel-1")
        assertEquals(OrderReservationStatus.RELEASED, reservation.status)
        assertEquals(Amount.ZERO, reservation.remainingAmount)
        assertEquals(Amount.ZERO, reservation.remainingFeeReserveAmount)
        assertBalance("buyer-1", "KRW", available = 1_000_000, hold = 0)
        assertEquals(
            MatchingEventType.ORDER_CANCELLED,
            eventRepository.findByMarketIdOrderByEngineSequenceAsc(market.marketId.value).last().eventType,
        )
    }

    @Test
    fun `없는 주문을 취소하면 자금 변경 없이 cancel rejected event를 반환한다`() {
        cancelOrder(orderId = "missing-order")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("ORDER_CANCEL_REJECTED"))
            .andExpect(jsonPath("$.events[0].marketId").value(market.marketId.value))
            .andExpect(jsonPath("$.events[0].engineSequence").value(1))
            .andExpect(jsonPath("$.events[0].orderId").value("missing-order"))
            .andExpect(jsonPath("$.events[0].userId").value("buyer-1"))
            .andExpect(jsonPath("$.events[0].reason").value("order not found"))

        assertNull(reservationStore.find(market.marketId, OrderId("missing-order")))
        assertBalance("buyer-1", "KRW", available = 1_000_000, hold = 0)
        assertEquals(
            MatchingEventType.ORDER_CANCEL_REJECTED,
            eventRepository.findAll().single().eventType,
        )
    }

    @Test
    fun `가격이 0이면 자금을 예약하지 않고 bad request를 반환한다`() {
        submitOrder(orderId = "bad-price-1", price = 0)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("price must be positive"))

        assertNull(reservationStore.find(market.marketId, OrderId("bad-price-1")))
        assertBalance("buyer-1", "KRW", available = 1_000_000, hold = 0)
        assertEquals(0L, eventRepository.count())
    }

    /** 같은 주문을 다시 보내도 최초 예약만 남고 hold를 두 번 차감하지 않아야 한다. */
    @Test
    fun `중복 주문은 기존 예약을 유지하고 bad request를 반환한다`() {
        submitOrder(orderId = "duplicate-1").andExpect(status().isOk)
        val originalReservation = findReservation("duplicate-1")

        submitOrder(orderId = "duplicate-1")
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.message").value(
                    "order reservation already exists: marketId=${market.marketId.value}, orderId=duplicate-1",
                ),
            )

        assertEquals(originalReservation, findReservation("duplicate-1"))
        assertBalance("buyer-1", "KRW", available = 999_495, hold = 505)
        assertEquals(1L, eventRepository.count())
    }

    @Test
    fun `지원하지 않는 주문 타입이면 자금 변경 없이 bad request를 반환한다`() {
        submitOrder(orderId = "market-1", orderType = OrderType.MARKET)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("only LIMIT order is supported"))

        assertNull(reservationStore.find(market.marketId, OrderId("market-1")))
        assertBalance("buyer-1", "KRW", available = 1_000_000, hold = 0)
        assertEquals(0L, eventRepository.count())
    }

    /** 취소 거절 이벤트를 성공으로 취급해 다른 사람의 예약금을 반환하지 않는지 확인한다. */
    @Test
    fun `다른 사용자의 취소 요청은 원래 주문의 예약금을 유지한다`() {
        submitOrder(orderId = "owner-order").andExpect(status().isOk)
        val originalReservation = findReservation("owner-order")

        cancelOrder(orderId = "owner-order", userId = "seller-1")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("ORDER_CANCEL_REJECTED"))
            .andExpect(jsonPath("$.events[0].reason").value("order owner mismatch"))

        assertEquals(originalReservation, findReservation("owner-order"))
        assertBalance("buyer-1", "KRW", available = 999_495, hold = 505)
        assertBalance("seller-1", "KRW", available = 0, hold = 0)
    }

    @Test
    fun `이미 취소한 주문을 다시 취소해도 예약금을 두 번 반환하지 않는다`() {
        submitOrder(orderId = "cancel-twice").andExpect(status().isOk)
        cancelOrder(orderId = "cancel-twice")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("ORDER_CANCELLED"))

        cancelOrder(orderId = "cancel-twice")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("ORDER_CANCEL_REJECTED"))
            .andExpect(jsonPath("$.events[0].reason").value("order not found"))

        val reservation = findReservation("cancel-twice")
        assertEquals(OrderReservationStatus.RELEASED, reservation.status)
        assertEquals(Amount.ZERO, reservation.remainingAmount)
        assertEquals(Amount.ZERO, reservation.remainingFeeReserveAmount)
        assertEquals(Quantity(5), reservation.remainingQuantity)
        assertBalance("buyer-1", "KRW", available = 1_000_000, hold = 0)
    }

    /** 서비스 메서드가 아니라 HTTP 경계로 주문을 제출한다. 기본 주문은 100원 BUY 수량 5개다. */
    private fun submitOrder(
        orderId: String,
        userId: String = "buyer-1",
        side: Side = Side.BUY,
        orderType: OrderType = OrderType.LIMIT,
        price: Long = 100,
        quantity: Long = 5,
    ): ResultActions =
        mockMvc.perform(
            post("/api/markets/{marketId}/orders", market.marketId.value)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "orderId": "$orderId",
                      "userId": "$userId",
                      "side": "${side.name}",
                      "orderType": "${orderType.name}",
                      "timeInForce": "GTC",
                      "price": $price,
                      "quantity": $quantity
                    }
                    """.trimIndent(),
                ),
        )

    private fun cancelOrder(
        orderId: String,
        userId: String = "buyer-1",
    ): ResultActions =
        mockMvc.perform(
            delete("/api/markets/{marketId}/orders/{orderId}", market.marketId.value, orderId)
                .param("userId", userId),
        )

    private fun findReservation(orderId: String): OrderReservation =
        assertNotNull(reservationStore.find(market.marketId, OrderId(orderId)))

    /** 실제 DB에 초기 잔고를 준비하며, 이 시드 금액의 원장 기록은 테스트 범위 밖이다. */
    private fun insertBalance(
        userId: String,
        assetId: String,
        available: Long,
    ) {
        jdbcTemplate.update(
            "insert into balance_projection (user_id, asset_id, available, hold) values (?, ?, ?, 0)",
            userId,
            assetId,
            available,
        )
    }

    /** 요청 처리 뒤 커밋된 잔고를 조회해 사용 가능 금액과 동결 금액을 검증한다. */
    private fun assertBalance(
        userId: String,
        assetId: String,
        available: Long,
        hold: Long,
    ) {
        val saved =
            jdbcTemplate.queryForMap(
                "select available, hold from balance_projection where user_id = ? and asset_id = ?",
                userId,
                assetId,
            )
        assertEquals(available, (saved["available"] as Number).toLong())
        assertEquals(hold, (saved["hold"] as Number).toLong())
    }
}
