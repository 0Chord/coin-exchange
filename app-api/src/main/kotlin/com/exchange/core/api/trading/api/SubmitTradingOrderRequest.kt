package com.exchange.core.api.trading.api

import com.exchange.core.api.trading.application.TradingOrderSubmission
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

/** 가격은 quote 정수 저장 단위, 수량은 해당 마켓의 base 최소 단위 개수다. */
data class SubmitTradingOrderRequest(
    @field:NotBlank @field:Size(max = 256) val requestId: String,
    @field:NotBlank @field:Size(max = 256) val orderId: String,
    @field:NotBlank @field:Size(max = 256) val userId: String,
    val side: Side,
    val orderType: OrderType,
    val timeInForce: TimeInForce,
    @field:Positive val price: Long,
    @field:Positive val quantity: Long,
) {
    fun toSubmission(marketId: String): TradingOrderSubmission =
        TradingOrderSubmission(
            requestId,
            OrderAdmissionInput(
                MarketId(marketId),
                OrderId(orderId),
                UserId(userId),
                side,
                orderType,
                timeInForce,
                Price(price),
                Quantity(quantity),
            ),
        )
}
