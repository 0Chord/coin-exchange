package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.order.Side
import java.util.TreeMap

/**
 * 한 마켓의 오더북.
 *
 * OrderBook은 가격별 주문 줄을 관리한다.
 *
 * 예:
 * bids
 *   Price(101) -> PriceLevel(101)
 *                  order-a
 *                  order-b
 *
 *   Price(100) -> PriceLevel(100)
 *                  order-c
 *
 * asks
 *   Price(102) -> PriceLevel(102)
 *                  order-d
 *
 * PriceLevel은 같은 가격에 걸린 주문 줄이다.
 * OrderBook은 어떤 가격을 먼저 볼지 결정하고,
 * PriceLevel은 같은 가격 안에서 어떤 주문을 먼저 볼지 결정한다.
 *
 * bids는 높은 가격이 먼저 와야 하므로 내림차순으로 정렬하고,
 * asks는 낮은 가격이 먼저 와야 하므로 기본 오름차순 정렬을 사용한다.
 */
class OrderBook {
    /**
     * 매수 book.
     *
     * 높은 가격이 우선이므로 내림차순 TreeMap을 사용한다.
     * key는 가격이고, value는 그 가격에 걸린 주문 줄이다.
     */
    private val bids = TreeMap<Price, PriceLevel>(compareByDescending { it.value })

    /**
     * 매도 book.
     *
     * 낮은 가격이 우선이므로 기본 오름차순 TreeMap을 사용한다.
     * key는 가격이고, value는 그 가격에 걸린 주문 줄이다.
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
     * book에 해당 주문이 남아 있는지 확인한다.
     */
    fun contains(orderId: OrderId): Boolean =
        orderIndex.containsKey(orderId)

    /**
     * 현재 가장 높은 매수 가격 레벨.
     *
     * MatchingEngine은 매도 주문을 처리할 때 이 PriceLevel의 첫 주문부터 체결한다.
     */
    fun bestBidLevel(): PriceLevel? = bids.firstEntry()?.value

    /**
     * 현재 가장 낮은 매도 가격 레벨.
     *
     * MatchingEngine은 매수 주문을 처리할 때 이 PriceLevel의 첫 주문부터 체결한다.
     */
    fun bestAskLevel(): PriceLevel? = asks.firstEntry()?.value

    /**
     * 전량 체결된 주문을 book과 취소용 인덱스에서 제거한다.
     *
     * maker 주문의 remainingQuantity가 0이 되었을 때 호출한다.
     */
    fun removeFilledOrder(order: BookOrder) {
        val bookSide = bookSide(order.side)
        val priceLevel = bookSide[order.price] ?: return

        priceLevel.remove(order.orderId)
        orderIndex.remove(order.orderId)

        if (priceLevel.isEmpty()) {
            bookSide.remove(order.price)
        }
    }

    /**
     * side에 맞는 book을 반환한다.
     */
    private fun bookSide(side: Side): TreeMap<Price, PriceLevel> =
        when (side) {
            Side.BUY -> bids
            Side.SELL -> asks
        }
}
