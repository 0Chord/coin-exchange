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
 *
 * [originalQuantity]는 주문 당시 값이라 바뀌지 않고, [remainingQuantity]만 체결 때마다
 * 감소한다. 같은 가격에서는 이 객체가 [PriceLevel]에 들어간 순서가 체결 우선순위다.
 *
 * @property orderId 취소와 체결 event에서 주문을 추적하는 식별자
 * @property userId 주문 소유자
 * @property side BUY 또는 SELL 주문 방향
 * @property price book에 들어간 뒤 바뀌지 않는 지정가
 * @property originalQuantity 처음 제출한 전체 수량
 * @property remainingQuantity 아직 체결되지 않은 수량
 */
data class BookOrder(
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val price: Price,
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
     *
     * `remainingQuantity' = remainingQuantity - quantity`로 변경되며, 이 객체 자체의
     * [remainingQuantity]를 수정한다.
     *
     * @param quantity 이번 체결에서 이 maker 주문에 배정된 수량
     * @throws IllegalArgumentException [quantity]가 0이거나 현재 잔량보다 큰 경우
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
     *
     * @return 주문이 전량 체결되었으면 `true`
     */
    fun isFilled(): Boolean = remainingQuantity.isZero()
}
