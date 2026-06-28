package com.exchange.core.matching

import com.exchange.core.common.*
import com.exchange.core.order.Side

/**
 * 매칭 엔진 처리 결과.
 *
 * command는 엔진에 넣는 입력이고, event는 엔진이 실제로 만든 결과다.
 */
sealed interface MatchingEvent {
    /**
     * event가 발생한 마켓.
     */
    val marketId: MarketId

    /**
     * market 안에서 증가하는 event 순번.
     *
     * 같은 command 순서라면 같은 event 순서가 나와야 하므로
     * 결정성 테스트에서 중요한 값이다.
     */
    val engineSequence: Long
}

/**
 * maker 주문과 taker 주문이 체결된 결과.
 *
 * 가격은 항상 maker 주문의 가격을 사용한다.
 */
data class TradeExecuted(
    override val marketId: MarketId,
    override val engineSequence: Long,
    /**
     * book에 먼저 걸려 있던 주문.
     */
    val makerOrderId: OrderId,
    /**
     * 새로 들어와 체결을 발생시킨 주문.
     */
    val takerOrderId: OrderId,
    val makerUserId: UserId,
    val takerUserId: UserId,

    /**
     * taker 기준 방향.
     */
    val side: Side,

    val price: Price,
    val quantity: Quantity,
) : MatchingEvent

/**
 * 체결되지 않은 잔량이 book에 들어간 결과.
 */
data class OrderEnteredBook(
    override val marketId: MarketId,
    override val engineSequence: Long,
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val price: Price,
    val remainingQuantity: Quantity,
) : MatchingEvent


/**
 * book에 있던 주문이 취소된 결과.
 */
data class OrderCancelled(
    override val marketId: MarketId,
    override val engineSequence: Long,
    val orderId: OrderId,
    val userId: UserId,
    val remainingQuantity: Quantity,
) : MatchingEvent

/**
 * 취소할 주문을 찾지 못한 결과.
 */
data class OrderCancelRejected(
    override val marketId: MarketId,
    override val engineSequence: Long,
    val orderId: OrderId,
    val userId: UserId,
    val reason: String,
) : MatchingEvent
