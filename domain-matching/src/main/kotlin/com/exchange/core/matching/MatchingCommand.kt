package com.exchange.core.matching

import com.exchange.core.common.*
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

/**
 * 매칭 엔진에 넣는 입력.
 *
 * 외부 요청을 그대로 처리하지 않고 command로 바꿔 넣으면,
 * 엔진은 API, DB, Kafka를 몰라도 순수하게 주문 처리만 할 수 있다.
 */
sealed interface MatchingCommand {
    /**
     * command가 적용될 마켓.
     */
    val marketId: MarketId
}

/**
 * 새 주문을 넣는 command.
 *
 * 이 command가 들어오면 엔진은 반대편 book과 먼저 매칭하고,
 * 남은 수량이 있으면 TimeInForce 규칙에 따라 book에 넣거나 취소한다.
 */
data class SubmitOrderCommand(
    override val marketId: MarketId,
    /**
     * 새로 등록되는 주문 id.
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
     * 지정가, 시장가 같은 주문 방식.
     */
    val orderType: OrderType,
    /**
     * 미체결 잔량 처리 방식.
     */
    val timeInForce: TimeInForce,
    /**
     * 지정가 주문의 가격.
     */
    val price: Price,
    /**
     * 주문 총 수량.
     */
    val quantity: Quantity
) : MatchingCommand {
    init {
        require(quantity.value > 0) {
            "quantity must be positive"
        }
    }
}

/**
 * 기존 주문을 취소하는 command.
 *
 * 취소는 orderId로 book 안의 주문을 찾고, userId로 요청자를 함께 남긴다.
 */
data class CancelOrderCommand(
    override val marketId: MarketId,
    val orderId: OrderId,
    val userId: UserId
) : MatchingCommand
