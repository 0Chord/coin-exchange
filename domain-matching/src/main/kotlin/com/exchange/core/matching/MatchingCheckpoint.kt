package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import java.util.Collections

/** Active books and market high-water marks; a durable owner preserves terminal order identities. */
class MatchingCheckpoint(
    markets: List<Market>,
) {
    val markets: List<Market> = Collections.unmodifiableList(markets.toList())

    class Market(
        val marketId: MarketId,
        val lastEngineSequence: Long,
        orders: List<Order>,
    ) {
        /** Bids then asks, in price priority and FIFO order within each price. */
        val orders: List<Order> = Collections.unmodifiableList(orders.toList())
    }

    data class Order(
        val orderId: OrderId,
        val userId: UserId,
        val side: Side,
        val price: Price,
        val originalQuantity: Quantity,
        val remainingQuantity: Quantity,
    )
}
