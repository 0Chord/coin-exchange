package com.exchange.core.api.matching.persistence

import com.exchange.core.matching.MatchingEvent
import jakarta.persistence.*
import java.time.Instant

/**
 * matching engine이 만든 event를 DB에 남기는 row.
 *
 * marketId + engineSequence는 event 순서를 보장하는 핵심 값이다.
 * payloadJson에는 event 원본 내용을 JSON 문자열로 저장한다.
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