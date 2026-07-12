package com.exchange.core.api.matching

import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted

/**
 * domain event를 API response DTO로 변환한다.
 */
fun MatchingEvent.toResponse(): MatchingEventResponse =
    when (this) {
        is TradeExecuted ->
            MatchingEventResponse(
                type = "TRADE_EXECUTED",
                marketId = marketId.value,
                engineSequence = engineSequence,
                makerOrderId = makerOrderId.value,
                takerOrderId = takerOrderId.value,
                side = side.name,
                price = price.value,
                quantity = quantity.value,
            )

        is OrderEnteredBook ->
            MatchingEventResponse(
                type = "ORDER_ENTERED_BOOK",
                marketId = marketId.value,
                engineSequence = engineSequence,
                orderId = orderId.value,
                userId = userId.value,
                side = side.name,
                price = price.value,
                remainingQuantity = remainingQuantity.value,
            )

        is OrderCancelled ->
            MatchingEventResponse(
                type = "ORDER_CANCELLED",
                marketId = marketId.value,
                engineSequence = engineSequence,
                orderId = orderId.value,
                userId = userId.value,
                remainingQuantity = remainingQuantity.value,
            )

        is OrderCancelRejected ->
            MatchingEventResponse(
                type = "ORDER_CANCEL_REJECTED",
                marketId = marketId.value,
                engineSequence = engineSequence,
                orderId = orderId.value,
                userId = userId.value,
                reason = reason,
            )
    }
