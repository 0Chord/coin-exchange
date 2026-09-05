package com.exchange.core.api.matching

import com.exchange.core.api.order.OrderSubmissionService
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.CancelOrderCommand
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import org.junit.jupiter.api.Test
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.mockito.Mockito.`when` as whenever

/**
 * HTTP 입력 변환, 서비스 위임과 응답 변환을 검사한다.
 *
 * 서비스는 테스트 대역으로 분리한다. 새 주문이 주문 접수 서비스를 거치고 취소가 매칭 서비스로
 * 전달되는지 확인한다. 실제 예약·매칭·정산 연결은 OrderLifecycleE2ETest, 매칭 규칙은
 * domain-matching 테스트에서 검사한다. 이 테스트의 취소 응답 검증은 예약금 반환을 보장하지 않는다.
 */
@WebMvcTest(MatchingController::class)
class MatchingControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var orderSubmissionService: OrderSubmissionService

    @MockitoBean
    private lateinit var matchingService: MatchingApplicationService

    @Test
    fun `주문을 접수하면 book entered event를 반환한다`() {
        val command =
            orderCommand(
                marketId = "API-TEST-ENTER",
                orderId = "order-1",
            )

        whenever(orderSubmissionService.submit(command))
            .thenReturn(listOf(enteredBook(command)))

        mockMvc.perform(
            post("/api/markets/API-TEST-ENTER/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "orderId": "order-1",
                      "userId": "user-1",
                      "side": "BUY",
                      "orderType": "LIMIT",
                      "timeInForce": "GTC",
                      "price": 100,
                      "quantity": 5
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("ORDER_ENTERED_BOOK"))
            .andExpect(jsonPath("$.events[0].marketId").value("API-TEST-ENTER"))
            .andExpect(jsonPath("$.events[0].engineSequence").value(1))
            .andExpect(jsonPath("$.events[0].orderId").value("order-1"))
            .andExpect(jsonPath("$.events[0].userId").value("user-1"))
            .andExpect(jsonPath("$.events[0].side").value("BUY"))
            .andExpect(jsonPath("$.events[0].price").value(100))
            .andExpect(jsonPath("$.events[0].remainingQuantity").value(5))

        verify(orderSubmissionService).submit(command)
        verifyNoInteractions(matchingService)
    }

    @Test
    fun `주문 접수 서비스의 체결 결과를 응답으로 반환한다`() {
        val sellCommand =
            orderCommand(
                marketId = "API-TEST-CROSS",
                orderId = "ask-1",
                userId = "seller-1",
                side = Side.SELL,
                quantity = 3,
            )
        val buyCommand =
            orderCommand(
                marketId = "API-TEST-CROSS",
                orderId = "bid-1",
                userId = "buyer-1",
                price = 110,
                quantity = 3,
            )

        whenever(orderSubmissionService.submit(sellCommand))
            .thenReturn(listOf(enteredBook(sellCommand)))
        whenever(orderSubmissionService.submit(buyCommand))
            .thenReturn(
                listOf(
                    TradeExecuted(
                        marketId = sellCommand.marketId,
                        engineSequence = 2,
                        makerOrderId = sellCommand.orderId,
                        takerOrderId = buyCommand.orderId,
                        makerUserId = sellCommand.userId,
                        takerUserId = buyCommand.userId,
                        side = Side.BUY,
                        price = Price(100),
                        quantity = Quantity(3),
                    ),
                ),
            )

        mockMvc.perform(
            post("/api/markets/API-TEST-CROSS/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "orderId": "ask-1",
                      "userId": "seller-1",
                      "side": "SELL",
                      "orderType": "LIMIT",
                      "timeInForce": "GTC",
                      "price": 100,
                      "quantity": 3
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("ORDER_ENTERED_BOOK"))

        mockMvc.perform(
            post("/api/markets/API-TEST-CROSS/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "orderId": "bid-1",
                      "userId": "buyer-1",
                      "side": "BUY",
                      "orderType": "LIMIT",
                      "timeInForce": "GTC",
                      "price": 110,
                      "quantity": 3
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("TRADE_EXECUTED"))
            .andExpect(jsonPath("$.events[0].marketId").value("API-TEST-CROSS"))
            .andExpect(jsonPath("$.events[0].engineSequence").value(2))
            .andExpect(jsonPath("$.events[0].makerOrderId").value("ask-1"))
            .andExpect(jsonPath("$.events[0].takerOrderId").value("bid-1"))
            .andExpect(jsonPath("$.events[0].side").value("BUY"))
            .andExpect(jsonPath("$.events[0].price").value(100))
            .andExpect(jsonPath("$.events[0].quantity").value(3))

        verify(orderSubmissionService).submit(sellCommand)
        verify(orderSubmissionService).submit(buyCommand)
        verifyNoInteractions(matchingService)
    }

    @Test
    fun `취소 요청을 전달하고 cancelled event를 반환한다`() {
        val submitCommand =
            orderCommand(
                marketId = "API-TEST-CANCEL",
                orderId = "cancel-1",
            )
        val cancelCommand =
            CancelOrderCommand(
                marketId = submitCommand.marketId,
                orderId = submitCommand.orderId,
                userId = submitCommand.userId,
            )

        whenever(orderSubmissionService.submit(submitCommand))
            .thenReturn(listOf(enteredBook(submitCommand)))
        whenever(matchingService.process(cancelCommand))
            .thenReturn(
                listOf(
                    OrderCancelled(
                        marketId = cancelCommand.marketId,
                        engineSequence = 2,
                        orderId = cancelCommand.orderId,
                        userId = cancelCommand.userId,
                        remainingQuantity = Quantity(5),
                    ),
                ),
            )

        mockMvc.perform(
            post("/api/markets/API-TEST-CANCEL/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "orderId": "cancel-1",
                      "userId": "user-1",
                      "side": "BUY",
                      "orderType": "LIMIT",
                      "timeInForce": "GTC",
                      "price": 100,
                      "quantity": 5
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            delete("/api/markets/API-TEST-CANCEL/orders/cancel-1")
                .param("userId", "user-1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("ORDER_CANCELLED"))
            .andExpect(jsonPath("$.events[0].marketId").value("API-TEST-CANCEL"))
            .andExpect(jsonPath("$.events[0].engineSequence").value(2))
            .andExpect(jsonPath("$.events[0].orderId").value("cancel-1"))
            .andExpect(jsonPath("$.events[0].userId").value("user-1"))
            .andExpect(jsonPath("$.events[0].remainingQuantity").value(5))

        verify(orderSubmissionService).submit(submitCommand)
        verify(matchingService).process(cancelCommand)
    }

    @Test
    fun `없는 주문을 취소하면 cancel rejected event를 반환한다`() {
        val command =
            CancelOrderCommand(
                marketId = MarketId("API-TEST-MISSING"),
                orderId = OrderId("missing-order"),
                userId = UserId("user-1"),
            )

        whenever(matchingService.process(command))
            .thenReturn(
                listOf(
                    OrderCancelRejected(
                        marketId = command.marketId,
                        engineSequence = 1,
                        orderId = command.orderId,
                        userId = command.userId,
                        reason = "order not found",
                    ),
                ),
            )

        mockMvc.perform(
            delete("/api/markets/API-TEST-MISSING/orders/missing-order")
                .param("userId", "user-1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].type").value("ORDER_CANCEL_REJECTED"))
            .andExpect(jsonPath("$.events[0].marketId").value("API-TEST-MISSING"))
            .andExpect(jsonPath("$.events[0].engineSequence").value(1))
            .andExpect(jsonPath("$.events[0].orderId").value("missing-order"))
            .andExpect(jsonPath("$.events[0].userId").value("user-1"))
            .andExpect(jsonPath("$.events[0].reason").value("order not found"))

        verify(matchingService).process(command)
        verifyNoInteractions(orderSubmissionService)
    }

    @Test
    fun `가격이 0이면 bad request를 반환한다`() {
        mockMvc.perform(
            post("/api/markets/API-TEST-BAD-PRICE/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "orderId": "bad-price-1",
                      "userId": "user-1",
                      "side": "BUY",
                      "orderType": "LIMIT",
                      "timeInForce": "GTC",
                      "price": 0,
                      "quantity": 5
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("price must be positive"))

        verifyNoInteractions(orderSubmissionService, matchingService)
    }

    @Test
    fun `주문 접수 서비스의 중복 주문 거절을 bad request로 반환한다`() {
        val command =
            orderCommand(
                marketId = "API-TEST-DUPLICATE",
                orderId = "duplicate-1",
            )

        whenever(orderSubmissionService.submit(command))
            .thenReturn(listOf(enteredBook(command)))
            .thenThrow(IllegalArgumentException("order already exists"))

        val requestBody =
            """
            {
              "orderId": "duplicate-1",
              "userId": "user-1",
              "side": "BUY",
              "orderType": "LIMIT",
              "timeInForce": "GTC",
              "price": 100,
              "quantity": 5
            }
            """.trimIndent()

        mockMvc.perform(
            post("/api/markets/API-TEST-DUPLICATE/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody),
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/markets/API-TEST-DUPLICATE/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("order already exists"))

        verify(orderSubmissionService, times(2)).submit(command)
        verifyNoInteractions(matchingService)
    }

    @Test
    fun `지원하지 않는 주문 타입이면 bad request를 반환한다`() {
        val command =
            orderCommand(
                marketId = "API-TEST-MARKET",
                orderId = "market-1",
                orderType = OrderType.MARKET,
            )

        whenever(orderSubmissionService.submit(command))
            .thenThrow(IllegalArgumentException("only LIMIT order is supported"))

        mockMvc.perform(
            post("/api/markets/API-TEST-MARKET/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "orderId": "market-1",
                      "userId": "user-1",
                      "side": "BUY",
                      "orderType": "MARKET",
                      "timeInForce": "GTC",
                      "price": 100,
                      "quantity": 5
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("only LIMIT order is supported"))

        verify(orderSubmissionService).submit(command)
        verifyNoInteractions(matchingService)
    }

    /** HTTP 입력이 변환되어 서비스에 전달되어야 하는 GTC 주문 명령을 만든다. */
    private fun orderCommand(
        marketId: String,
        orderId: String,
        userId: String = "user-1",
        side: Side = Side.BUY,
        orderType: OrderType = OrderType.LIMIT,
        price: Long = 100,
        quantity: Long = 5,
    ): SubmitOrderCommand =
        SubmitOrderCommand(
            marketId = MarketId(marketId),
            orderId = OrderId(orderId),
            userId = UserId(userId),
            side = side,
            orderType = orderType,
            timeInForce = TimeInForce.GTC,
            price = Price(price),
            quantity = Quantity(quantity),
        )

    /** 매칭 엔진을 실행하지 않고 서비스 대역이 반환할 book 진입 이벤트를 만든다. */
    private fun enteredBook(command: SubmitOrderCommand): OrderEnteredBook =
        OrderEnteredBook(
            marketId = command.marketId,
            engineSequence = 1,
            orderId = command.orderId,
            userId = command.userId,
            side = command.side,
            price = command.price,
            remainingQuantity = command.quantity,
        )
}
