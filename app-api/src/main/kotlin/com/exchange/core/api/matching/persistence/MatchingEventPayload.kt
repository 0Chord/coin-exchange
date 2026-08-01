package com.exchange.core.api.matching.persistence

import com.exchange.core.matching.*

/**
 * payload_json에 저장할 납작한 event payload.
 */
data class MatchingEventPayload(
    val type: MatchingEventType,
    val marketId: String,
    val engineSequence: Long,
    val makerOrderId: String? = null,
    val takerOrderId: String? = null,
    val orderId: String? = null,
    val userId: String? = null,
    val makerUserId: String? = null,
    val takerUserId: String? = null,
    val side: String? = null,
    val price: Long? = null,
    val quantity: Long? = null,
    val remainingQuantity: Long? = null,
    val reason: String? = null
)

fun MatchingEvent.toEventType(): MatchingEventType =
    when (this) {
        is TradeExecuted -> MatchingEventType.TRADE_EXECUTED
        is OrderEnteredBook -> MatchingEventType.ORDER_ENTERED_BOOK
        is OrderCancelled -> MatchingEventType.ORDER_CANCELLED
        is OrderCancelRejected -> MatchingEventType.ORDER_CANCEL_REJECTED
    }


fun MatchingEvent.toPayload(): MatchingEventPayload =
    when (this) {
        is TradeExecuted ->
            MatchingEventPayload(
                type = MatchingEventType.TRADE_EXECUTED,
                marketId = marketId.value,
                engineSequence = engineSequence,
                makerOrderId = makerOrderId.value,
                takerOrderId = takerOrderId.value,
                makerUserId = makerUserId.value,
                takerUserId = takerUserId.value,
                side = side.name,
                price = price.value,
                quantity = quantity.value,
            )

        is OrderEnteredBook ->
            MatchingEventPayload(
                type = MatchingEventType.ORDER_ENTERED_BOOK,
                marketId = marketId.value,
                engineSequence = engineSequence,
                orderId = orderId.value,
                userId = userId.value,
                side = side.name,
                price = price.value,
                remainingQuantity = remainingQuantity.value,
            )

        is OrderCancelled ->
            MatchingEventPayload(
                type = MatchingEventType.ORDER_CANCELLED,
                marketId = marketId.value,
                engineSequence = engineSequence,
                orderId = orderId.value,
                userId = userId.value,
                remainingQuantity = remainingQuantity.value,
            )

        is OrderCancelRejected ->
            MatchingEventPayload(
                type = MatchingEventType.ORDER_CANCEL_REJECTED,
                marketId = marketId.value,
                engineSequence = engineSequence,
                orderId = orderId.value,
                userId = userId.value,
                reason = reason,
            )
    }