package com.exchange.core.order

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId

interface OrderReservationStore {
    fun create(reservation: OrderReservation)

    fun find(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation?
}

class OrderReservationAlreadyExistsException(
    val marketId: MarketId,
    val orderId: OrderId,
) : IllegalStateException(
    "order reservation already exists: " +
        "marketId=${marketId.value}, " +
        "orderId=${orderId.value}",
)
