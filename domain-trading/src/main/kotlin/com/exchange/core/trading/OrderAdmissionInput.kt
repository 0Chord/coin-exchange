package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

/** 접수 후보를 계산할 주문 원본. 접수 여부나 처리 순서는 포함하지 않는다. */
data class OrderAdmissionInput(
    val marketId: MarketId,
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val orderType: OrderType,
    val timeInForce: TimeInForce,
    val price: Price,
    val quantity: Quantity,
)
