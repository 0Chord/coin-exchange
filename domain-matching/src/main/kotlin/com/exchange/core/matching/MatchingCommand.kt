package com.exchange.core.matching

import com.exchange.core.common.*
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

sealed interface MatchingCommand {
    val marketId: MarketId
}

data class SubmitOrderCommand(
    override val marketId: MarketId,
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val orderType: OrderType,
    val timeInForce: TimeInForce,
    val price: Price,
    val quantity: Quantity
) : MatchingCommand {
    init {
        require(quantity.value > 0) {
            "quantity must be positive"
        }
    }
}

data class CancelOrderCommand(
    override val marketId: MarketId,
    val orderId: OrderId,
    val userId: UserId
) : MatchingCommand