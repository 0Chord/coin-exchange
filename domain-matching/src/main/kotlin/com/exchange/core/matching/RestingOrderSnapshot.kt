package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side

/** 한 시점의 주문장 대기 주문 값. 이후 매칭이 이 복사본을 변경하지 않는다. */
@ConsistentCopyVisibility
data class RestingOrderSnapshot internal constructor(
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val price: Price,
    val originalQuantity: Quantity,
    val remainingQuantity: Quantity,
)
