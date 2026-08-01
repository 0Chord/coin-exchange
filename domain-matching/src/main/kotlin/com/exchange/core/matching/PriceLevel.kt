package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.common.Price

/**
 * 하나의 가격 레벨.
 *
 * 예를 들어 ask 100에 주문이 여러 개 걸려 있으면,
 * PriceLevel(100) 하나가 그 주문들을 들어온 순서대로 들고 있다.
 *
 * 같은 가격 안에서는 먼저 들어온 주문이 먼저 체결되어야 하므로
 * LinkedHashMap을 사용해 입력 순서를 유지한다.
 */
class PriceLevel(
    /**
     * 이 가격 레벨의 가격.
     *
     * 이 PriceLevel에는 같은 price를 가진 주문만 들어올 수 있다.
     */
    val price: Price,
) {
    /**
     * 같은 가격에 걸린 주문 목록.
     *
     * key는 OrderId다. 주문 취소 시 orderId로 빠르게 찾아 제거하기 위해서다.
     * value는 BookOrder다. 실제 주문의 userId, side, price, 남은 수량을 들고 있다.
     *
     * LinkedHashMap은 삽입 순서를 유지하므로 같은 가격 안에서 FIFO를 지킬 수 있다.
     */
    private val orders = LinkedHashMap<OrderId, BookOrder>()

    /**
     * 이 가격 레벨에 주문을 추가한다.
     *
     * 다른 가격의 주문이 들어오면 잘못된 상태이므로 바로 예외를 낸다.
     */
    fun add(order: BookOrder) {
        require(order.price == price) {
            "order price must match price level"
        }

        orders[order.orderId] = order
    }

    /**
     * orderId에 해당하는 주문을 제거한다.
     *
     * 주문이 있으면 제거된 BookOrder를 반환하고, 없으면 null을 반환한다.
     */
    fun remove(orderId: OrderId): BookOrder? = orders.remove(orderId)

    /**
     * 이 가격에서 가장 먼저 들어온 주문을 반환한다.
     *
     * 같은 가격에서는 이 주문이 가장 먼저 체결 대상이 된다.
     * 주문이 없으면 null을 반환한다.
     */
    fun firstOrder(): BookOrder? = orders.values.firstOrNull()

    /**
     * 이 가격 레벨에 남은 주문이 없는지 확인한다.
     */
    fun isEmpty(): Boolean = orders.isEmpty()

    /**
     * 특정 주문이 이 가격 레벨에 있는지 확인한다.
     */
    fun contains(orderId: OrderId): Boolean = orders.containsKey(orderId)

    /**
     * 특정 주문을 조회한다.
     *
     * 취소 요청자가 주문 주인인지 확인할 때 사용한다.
     */
    fun get(orderId: OrderId): BookOrder? = orders[orderId]

    /**
     * 현재 가격 레벨의 주문 목록을 복사해서 반환한다.
     *
     * 내부 LinkedHashMap을 직접 노출하지 않기 위해 List로 변환한다.
     */
    fun snapshot(): List<BookOrder> = orders.values.toList()
}
