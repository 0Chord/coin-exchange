package com.exchange.core.api.matching.publish

import com.exchange.core.matching.MatchingEvent

/**
 * 매칭 결과 event를 외부 후속 처리로 넘기는 포트.
 *
 * 이후 outbox, Kafka, Redis, WebSocket 발행 구현이 이 인터페이스를 따른다.
 */
interface MatchingEventPublisher {
    fun publish(events: List<MatchingEvent>)
}
