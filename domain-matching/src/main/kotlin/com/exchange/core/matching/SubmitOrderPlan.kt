package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import java.util.Collections

/**
 * 엔진이 발급한 새 주문의 매칭 후보. 생성만으로 주문장·ID·순번을 소비하지 않는다.
 *
 * [events]는 아직 반영하지 않은 결과다. 발급한 엔진의 같은 마켓 상태에서만 적용할 수
 * 있으며, 가변 주문 참조나 수정 가능한 목록은 보관하지 않는다. 전송·복구 포맷이 아니다.
 */
class SubmitOrderPlan internal constructor(
    private val engineToken: Any,
    internal val baseSequence: Long,
    val command: SubmitOrderCommand,
    fills: List<MakerFill>,
    internal val restingQuantity: Quantity,
    events: List<MatchingEvent>,
) {
    val fills: List<MakerFill> = Collections.unmodifiableList(fills.toList())
    val events: List<MatchingEvent> = Collections.unmodifiableList(events.toList())
    val firstEngineSequence: Long get() = events.first().engineSequence
    val lastEngineSequence: Long get() = events.last().engineSequence

    internal fun belongsTo(token: Any): Boolean = engineToken === token
}

/** 실제 BookOrder를 대신해 후보가 보존하는 maker의 변경 전 값과 체결량. */
@ConsistentCopyVisibility
data class MakerFill internal constructor(
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val price: Price,
    val originalQuantity: Quantity,
    val remainingQuantity: Quantity,
    val filledQuantity: Quantity,
) {
    internal fun matches(order: BookOrder): Boolean =
        orderId == order.orderId && userId == order.userId && side == order.side &&
            price == order.price && originalQuantity == order.originalQuantity &&
            remainingQuantity == order.remainingQuantity
}
