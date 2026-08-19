package com.exchange.core.api.matching.persistence

import com.exchange.core.matching.MatchingEvent
import jakarta.persistence.*
import java.time.Instant

/**
 * matching engine이 만든 event를 DB에 남기는 row.
 *
 * marketId + engineSequence는 event 순서를 보장하는 핵심 값이다.
 * payloadJson에는 event 원본 내용을 JSON 문자열로 저장한다.
 * nullable 검색 컬럼에는 event 종류별 핵심 값도 함께 펼쳐 저장해 JSON parsing 없이
 * 조회할 수 있게 한다. JPA 외부에서 불완전한 row를 만들지 못하도록 생성자는 protected이고
 * [of]를 쓴다.
 *
 * @property id DB가 생성하는 surrogate primary key
 * @property marketId event가 발생한 마켓
 * @property engineSequence 마켓 안에서 단조 증가하는 event 순번
 * @property eventType 저장된 domain event 종류
 * @property orderId book 진입 또는 취소 계열 event의 주문
 * @property userId book 진입 또는 취소 계열 event의 사용자
 * @property makerOrderId 체결 event의 기존 book 주문
 * @property takerOrderId 체결 event를 발생시킨 새 주문
 * @property makerUserId maker 주문 소유자
 * @property takerUserId taker 주문 소유자
 * @property side 체결에서는 taker 방향, book 진입에서는 주문 방향
 * @property price 체결 가격 또는 book 대기 가격
 * @property quantity 체결된 base 자산 최소 단위 수량
 * @property remainingQuantity book에 들어가거나 취소된 미체결 수량
 * @property reason 취소 거절 이유
 * @property payloadJson event 전체를 복원하거나 감사할 수 있는 JSON snapshot
 * @property createdAt DB row가 생성된 시각
 */
@Entity
@Table(
    name = "matching_events",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_matching_events_market_sequence",
            columnNames = ["market_id", "engine_sequence"],
        ),
    ],
)
class MatchingEventEntity protected constructor(
    id: Long? = null,
    marketId: String = "",
    engineSequence: Long = 0,
    eventType: MatchingEventType = MatchingEventType.ORDER_ENTERED_BOOK,
    orderId: String? = null,
    userId: String? = null,
    makerOrderId: String? = null,
    takerOrderId: String? = null,
    makerUserId: String? = null,
    takerUserId: String? = null,
    side: String? = null,
    price: Long? = null,
    quantity: Long? = null,
    remainingQuantity: Long? = null,
    reason: String? = null,
    payloadJson: String = "",
    createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set

    @Column(name = "market_id", nullable = false, length = 64)
    var marketId: String = marketId
        protected set

    @Column(name = "engine_sequence", nullable = false)
    var engineSequence: Long = engineSequence
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    var eventType: MatchingEventType = eventType
        protected set

    @Column(name = "order_id", length = 64)
    var orderId: String? = orderId
        protected set

    @Column(name = "user_id", length = 64)
    var userId: String? = userId
        protected set

    @Column(name = "maker_order_id", length = 64)
    var makerOrderId: String? = makerOrderId
        protected set

    @Column(name = "taker_order_id", length = 64)
    var takerOrderId: String? = takerOrderId
        protected set

    @Column(name = "maker_user_id", length = 64)
    var makerUserId: String? = makerUserId
        protected set

    @Column(name = "taker_user_id", length = 64)
    var takerUserId: String? = takerUserId
        protected set

    @Column(name = "side", length = 16)
    var side: String? = side
        protected set

    @Column(name = "price")
    var price: Long? = price
        protected set

    @Column(name = "quantity")
    var quantity: Long? = quantity
        protected set

    @Column(name = "remaining_quantity")
    var remainingQuantity: Long? = remainingQuantity
        protected set

    @Column(name = "reason", length = 512)
    var reason: String? = reason
        protected set

    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    var payloadJson: String = payloadJson
        protected set

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = createdAt
        protected set

    companion object {
        /**
         * domain event와 직렬화된 payload를 하나의 영속 entity로 만든다.
         *
         * [event]를 [MatchingEventPayload]로 한 번 펼쳐 공통 컬럼과 subtype별 검색 컬럼을
         * 채운다. [payloadJson]은 같은 payload를 JSON으로 직렬화한 원본 보관 값이다.
         *
         * @param event matching engine이 생성한 event
         * @param payloadJson [event] payload의 JSON 문자열
         * @return repository에 바로 저장할 새 entity
         */
        fun of(
            event: MatchingEvent,
            payloadJson: String,
        ): MatchingEventEntity {
            val payload = event.toPayload()

            return MatchingEventEntity(
                marketId = payload.marketId,
                engineSequence = payload.engineSequence,
                eventType = payload.type,
                orderId = payload.orderId,
                userId = payload.userId,
                makerOrderId = payload.makerOrderId,
                takerOrderId = payload.takerOrderId,
                makerUserId = payload.makerUserId,
                takerUserId = payload.takerUserId,
                side = payload.side,
                price = payload.price,
                quantity = payload.quantity,
                remainingQuantity = payload.remainingQuantity,
                reason = payload.reason,
                payloadJson = payloadJson,
            )
        }
    }
}
