package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import java.util.Collections

/** 활성 주문장과 시장별 최대 순번을 담는다. 종료 주문의 식별자는 영속 저장을 맡은 소유자가 보존한다. */
class MatchingCheckpoint(
    markets: List<Market>,
) {
    val markets: List<Market> = Collections.unmodifiableList(markets.toList())

    class Market(
        val marketId: MarketId,
        val lastEngineSequence: Long,
        orders: List<Order>,
    ) {
        /** 매수 다음 매도 순서이며, 가격 우선순위와 같은 가격 안의 FIFO 순서를 따른다. */
        val orders: List<Order> = Collections.unmodifiableList(orders.toList())
    }

    data class Order(
        val orderId: OrderId,
        val userId: UserId,
        val side: Side,
        val price: Price,
        val originalQuantity: Quantity,
        val remainingQuantity: Quantity,
    )
}
