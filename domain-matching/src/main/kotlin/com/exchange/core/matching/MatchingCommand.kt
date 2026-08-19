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
 *
 * @property marketId 주문을 처리할 독립 마켓
 * @property orderId 새 주문 식별자. 같은 마켓에서 재사용할 수 없다
 * @property userId 주문 소유자
 * @property side BUY 또는 SELL
 * @property orderType 지정가 또는 시장가 주문 방식
 * @property timeInForce 미체결 잔량 처리 방식
 * @property price base 자산 1단위의 quote 자산 지정가
 * @property quantity 주문할 base 자산의 최소 단위 수량
 * @throws IllegalArgumentException [quantity]가 0인 경우
 */
data class SubmitOrderCommand(
    override val marketId: MarketId,
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val orderType: OrderType,
    val timeInForce: TimeInForce,
    val price: Price,
    val quantity: Quantity,
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
 *
 * @property marketId 취소할 주문이 들어 있는 마켓
 * @property orderId 취소할 주문 식별자
 * @property userId 취소 요청자. 원래 주문 소유자와 일치해야 한다
 */
data class CancelOrderCommand(
    override val marketId: MarketId,
    val orderId: OrderId,
    val userId: UserId,
) : MatchingCommand
