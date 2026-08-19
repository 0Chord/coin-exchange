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
 * [side]는 maker가 아니라 새로 들어온 taker 주문 기준이다.
 *
 * @property marketId 체결이 발생한 마켓
 * @property engineSequence 해당 마켓 안에서 이 event의 순번
 * @property makerOrderId book에 먼저 대기하던 주문 식별자
 * @property takerOrderId 이번 command로 들어온 주문 식별자
 * @property makerUserId maker 주문 소유자
 * @property takerUserId taker 주문 소유자
 * @property side taker 기준 BUY 또는 SELL 방향
 * @property price maker 주문에서 가져온 실제 체결 가격
 * @property quantity 두 주문에서 공통으로 차감할 체결 수량
 */
data class TradeExecuted(
    override val marketId: MarketId,
    override val engineSequence: Long,
    val makerOrderId: OrderId,
    val takerOrderId: OrderId,
    val makerUserId: UserId,
    val takerUserId: UserId,
    val side: Side,
    val price: Price,
    val quantity: Quantity,
) : MatchingEvent

/**
 * 체결되지 않은 잔량이 book에 들어간 결과.
 *
 * @property marketId 주문이 대기하게 된 마켓
 * @property engineSequence 해당 마켓 안에서 이 event의 순번
 * @property orderId book에 추가된 주문
 * @property userId 주문 소유자
 * @property side 주문 방향
 * @property price 주문이 대기하는 가격 레벨
 * @property remainingQuantity 즉시 체결 후 book에 남은 수량
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
 *
 * @property marketId 취소가 발생한 마켓
 * @property engineSequence 해당 마켓 안에서 이 event의 순번
 * @property orderId 취소되어 book에서 제거된 주문
 * @property userId 주문 소유자
 * @property remainingQuantity 취소 시점에 아직 체결되지 않았던 수량
 */
data class OrderCancelled(
    override val marketId: MarketId,
    override val engineSequence: Long,
    val orderId: OrderId,
    val userId: UserId,
    val remainingQuantity: Quantity,
) : MatchingEvent

/**
 * 취소 요청을 처리할 수 없었던 결과.
 *
 * 이 event가 발생해도 book 상태는 바뀌지 않는다.
 *
 * @property marketId 취소를 요청한 마켓
 * @property engineSequence 해당 마켓 안에서 이 event의 순번
 * @property orderId 취소하려던 주문
 * @property userId 취소 요청자
 * @property reason 거절 이유. 예: 주문 없음 또는 소유자 불일치
 */
data class OrderCancelRejected(
    override val marketId: MarketId,
    override val engineSequence: Long,
    val orderId: OrderId,
    val userId: UserId,
    val reason: String,
) : MatchingEvent
