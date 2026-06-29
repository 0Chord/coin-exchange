package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.order.Side
import java.util.TreeMap

/**
 * 한 마켓의 오더북.
 *
 * OrderBook은 매수 주문(bids), 매도 주문(asks), 취소용 인덱스(orderIndex)를 관리한다.
 *
 * bids는 높은 가격이 먼저 와야 하므로 내림차순으로 정렬하고,
 * asks는 낮은 가격이 먼저 와야 하므로 기본 오름차순 정렬을 사용한다.
 *
 * 같은 가격 안에서의 FIFO 순서는 PriceLevel이 책임진다.
 */
class OrderBook {
    /**
     * 매수 book.
     *
     * 높은 가격이 우선이므로 내림차순 TreeMap을 사용한다.
     */
    private val bids = TreeMap<Price, PriceLevel>(compareByDescending { it.value })

    /**
     * 매도 book.
     *
     * 낮은 가격이 우선이므로 기본 오름차순 TreeMap을 사용한다.
     */
    private val asks = TreeMap<Price, PriceLevel>()

    /**
     * 주문 취소용 인덱스.
     *
     * cancel command에는 orderId만 들어오므로,
     * orderId로 주문의 side와 price를 바로 찾기 위해 둔다.
     */
    private val orderIndex = HashMap<OrderId, OrderRef>()

    /**
     * 주문이 어느 book 위치에 있는지 나타내는 내부 참조값.
     */
    private data class OrderRef(
        val side: Side,
        val price: Price,
    )

    /**
     * 체결되지 않고 남은 주문을 book에 넣는다.
     */
    fun addRestingOrder(order: BookOrder) {
        val bookSide = bookSide(order.side)
        val priceLevel = bookSide.getOrPut(order.price) {
            PriceLevel(order.price)
        }

        priceLevel.add(order)
        orderIndex[order.orderId] = OrderRef(
            side = order.side,
            price = order.price,
        )
    }

    /**
     * book에 남아 있는 주문을 취소한다.
     *
     * 주문이 있으면 제거된 BookOrder를 반환하고,
     * 없으면 null을 반환한다.
     */
    fun cancel(orderId: OrderId): BookOrder? {
        val orderRef = orderIndex.remove(orderId) ?: return null
        val bookSide = bookSide(orderRef.side)
        val priceLevel = bookSide[orderRef.price] ?: return null

        val removedOrder = priceLevel.remove(orderId)

        if (priceLevel.isEmpty()) {
            bookSide.remove(orderRef.price)
        }

        return removedOrder
    }

    /**
     * 현재 가장 높은 매수 가격.
     */
    fun bestBid(): Price? = bids.firstEntry()?.key

    /**
     * 현재 가장 낮은 매도 가격.
     */
    fun bestAsk(): Price? = asks.firstEntry()?.key

    /**
     * side에 맞는 book을 반환한다.
     */
    private fun bookSide(side: Side): TreeMap<Price, PriceLevel> =
        when (side) {
            Side.BUY -> bids
            Side.SELL -> asks
        }
}
