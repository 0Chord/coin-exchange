package com.exchange.core.api.matching.persistence

import com.exchange.core.matching.*

/**
 * payload_json에 저장할 납작한 event payload.
 *
 * 모든 event subtype을 한 JSON schema로 저장하기 때문에 공통 필드 외 값은 nullable이다.
 * [type]을 먼저 확인한 뒤 그 event에 해당하는 필드만 사용한다.
 *
 * @property type event subtype discriminator
 * @property marketId event가 발생한 마켓
 * @property engineSequence 마켓 안 event 순번
 * @property makerOrderId 체결 event의 maker 주문
 * @property takerOrderId 체결 event의 taker 주문
 * @property orderId book 진입 또는 취소 계열 event의 주문
 * @property userId book 진입 또는 취소 계열 event의 사용자
 * @property makerUserId maker 주문 소유자
 * @property takerUserId taker 주문 소유자
 * @property side 체결에서는 taker 방향, book 진입에서는 주문 방향
 * @property price 실제 체결 가격 또는 book 대기 가격
 * @property quantity 체결된 base 자산 최소 단위 수량
 * @property remainingQuantity book 진입 또는 취소 시 남은 수량
 * @property reason 취소 거절 이유
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
    val reason: String? = null,
)

/**
 * domain event subtype을 DB enum 값으로 변환한다.
 *
 * @receiver matching engine이 생성한 event
 * @return entity의 event_type 컬럼에 저장할 값
 */
fun MatchingEvent.toEventType(): MatchingEventType =
    when (this) {
        is TradeExecuted -> MatchingEventType.TRADE_EXECUTED
        is OrderEnteredBook -> MatchingEventType.ORDER_ENTERED_BOOK
        is OrderCancelled -> MatchingEventType.ORDER_CANCELLED
        is OrderCancelRejected -> MatchingEventType.ORDER_CANCEL_REJECTED
    }
/**
 * domain event의 value class와 enum을 JSON 직렬화용 원시값 payload로 변환한다.
 *
 * @receiver matching engine이 생성한 event
 * @return subtype에 해당하는 nullable 필드만 채워진 payload
 */
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
