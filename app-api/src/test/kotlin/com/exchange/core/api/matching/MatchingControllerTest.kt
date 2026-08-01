package com.exchange.core.api.matching

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
class MatchingControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `주문을 접수하면 book entered event를 반환한다`() {
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
    }

    @Test
    fun `crossing 주문은 trade executed event를 반환한다`() {
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
    }

    @Test
    fun `book에 남아 있는 주문을 취소하면 cancelled event를 반환한다`() {
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
    }

    @Test
    fun `없는 주문을 취소하면 cancel rejected event를 반환한다`() {
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
    }

    @Test
    fun `같은 orderId를 같은 market에 다시 넣으면 bad request를 반환한다`() {
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
    }

    @Test
    fun `지원하지 않는 주문 타입이면 bad request를 반환한다`() {
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
    }
}
