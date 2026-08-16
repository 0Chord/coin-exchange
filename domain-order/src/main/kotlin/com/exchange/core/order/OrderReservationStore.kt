package com.exchange.core.order

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId

interface OrderReservationStore {
    fun create(reservation: OrderReservation)

    fun find(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation?

    fun findForUpdate(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation?

    fun update(reservation: OrderReservation)
}

class OrderReservationAlreadyExistsException(
    val marketId: MarketId,
    val orderId: OrderId,
) : IllegalStateException(
    "order reservation already exists: " +
        "marketId=${marketId.value}, " +
        "orderId=${orderId.value}",
)

class OrderReservationNotFoundException(
    val marketId: MarketId,
    val orderId: OrderId,
) : IllegalStateException(
    "order reservation not found: " +
        "marketId=${marketId.value}, " +
        "orderId=${orderId.value}",
)
