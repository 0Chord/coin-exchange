package com.exchange.core.matching

import com.exchange.core.common.*
import com.exchange.core.order.Side

// 매칭 엔진 처리 결과
sealed interface MatchingEvent {
    val marketId: MarketId

    // market 안에서 증가하는 event 순번
    val engineSequence: Long
}

// maker 주문과 taker 주문이 체결됨
data class TradeExecuted(
    override val marketId: MarketId,
    override val engineSequence: Long,
    val makerOrderId: OrderId,
    val takerOrderId: OrderId,
    val makerUserId: UserId,
    val takerUserId: UserId,

    // taker 기준 방향
    val side: Side,

    val price: Price,
    val quantity: Quantity,
) : MatchingEvent

// 체결되지 않은 잔량이 book에 들어감
data class OrderEnteredBook(
    override val marketId: MarketId,
    override val engineSequence: Long,
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val price: Price,
    val remainingQuantity: Quantity,
) : MatchingEvent


// book에 있던 주문이 취소됨
data class OrderCancelled(
    override val marketId: MarketId,
    override val engineSequence: Long,
    val orderId: OrderId,
    val userId: UserId,
    val remainingQuantity: Quantity,
) : MatchingEvent

// 취소할 주문을 찾지 못함
data class OrderCancelRejected(
    override val marketId: MarketId,
    override val engineSequence: Long,
    val orderId: OrderId,
    val userId: UserId,
    val reason: String,
) : MatchingEvent
