package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side

data class BookOrder(
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val price: Price,
    val originalQuantity: Quantity,
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

    fun fill(quantity: Quantity) {
        require(quantity.value > 0) {
            "fill quantity must be positive"
        }
        require(quantity <= remainingQuantity) {
            "fill quantity must be less than or equal to remainingQuantity"
        }

        remainingQuantity -= quantity
    }

    fun isFilled(): Boolean = remainingQuantity.isZero()
}