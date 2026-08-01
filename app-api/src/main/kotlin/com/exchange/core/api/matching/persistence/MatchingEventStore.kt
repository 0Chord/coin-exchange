package com.exchange.core.api.matching.persistence

import com.exchange.core.matching.MatchingEvent

/**
 * MatchingEvent를 저장하는 포트.
 *
 * 구현체는 JPA, 파일, Kafka outbox 등으로 바뀔 수 있다.
 */
interface MatchingEventStore {
    fun append(events: List<MatchingEvent>)
}