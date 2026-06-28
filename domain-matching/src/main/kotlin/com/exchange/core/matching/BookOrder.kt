package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side

/**
 * 오더북 안에 저장되는 주문.
 *
 * SubmitOrderCommand는 외부에서 들어온 요청이고,
 * BookOrder는 체결되지 않고 book에 남아 있는 내부 상태다.
 */
data class BookOrder(
    /**
     * 주문 식별자. 취소와 체결 event에서 이 값으로 주문을 추적한다.
     */
    val orderId: OrderId,
    /**
     * 주문을 낸 사용자.
     */
    val userId: UserId,
    /**
     * 매수 또는 매도 방향.
     */
    val side: Side,
    /**
     * 주문 가격. book에 들어간 뒤에는 바뀌지 않는다.
     */
    val price: Price,
    /**
     * 처음 주문 수량.
     */
    val originalQuantity: Quantity,
    /**
     * 아직 체결되지 않은 수량.
     *
     * 체결될 때마다 줄어들기 때문에 var로 둔다.
     */
    var remainingQuantity: Quantity,
) {
    init {
        require(originalQuantity.value > 0) {
            "originalQuantity must be positive"
        }
        require(remainingQuantity.value > 0) {
            "remainingQuantity must be positive"
        }
        require(remainingQuantity <= originalQuantity) {
            "remainingQuantity must be less than or equal to originalQuantity"
        }
    }

    /**
     * 체결 수량만큼 잔량을 줄인다.
     */
    fun fill(quantity: Quantity) {
        require(quantity.value > 0) {
            "fill quantity must be positive"
        }
        require(quantity <= remainingQuantity) {
            "fill quantity must be less than or equal to remainingQuantity"
        }

        remainingQuantity -= quantity
    }

    /**
     * 잔량이 0인지 확인한다.
     */
    fun isFilled(): Boolean = remainingQuantity.isZero()
}
