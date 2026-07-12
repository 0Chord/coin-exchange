package com.exchange.core.api.matching

import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

/**
 * 주문 접수 요청 body.
 */
data class SubmitOrderRequest(
    val orderId: String,
    val userId: String,
    val side: Side,
    val orderType: OrderType,
    val timeInForce: TimeInForce,
    val price: Long,
    val quantity: Long
)

/**
 * matching API 공통 응답.
 *
 * command 하나가 여러 event를 만들 수 있으므로 list로 반환한다.
 */
data class MatchingResponse(
    val events: List<MatchingEventResponse>
)

/**
 * MatchingEvent를 HTTP 응답으로 내보내기 위한 납작한 DTO.
 *
 * event 종류마다 쓰는 필드가 달라서, 사용하지 않는 값은 null로 둔다.
 */
data class MatchingEventResponse(
    val type: String,
    val marketId: String,
    val engineSequence: Long,
    val tradeId: String? = null,
    val makerOrderId: String? = null,
    val takerOrderId: String? = null,
    val orderId: String? = null,
    val userId: String? = null,
    val side: String? = null,
    val price: Long? = null,
    val quantity: Long? = null,
    val remainingQuantity: Long? = null,
    val reason: String? = null,
)
