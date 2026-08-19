package com.exchange.core.api.matching.persistence

import com.exchange.core.matching.MatchingEvent

/**
 * MatchingEvent를 저장하는 포트.
 *
 * 구현체는 JPA, 파일, Kafka outbox 등으로 바뀔 수 있다.
 */
interface MatchingEventStore {
    /**
     * 한 command가 만든 event를 전달된 순서대로 영속화한다.
     *
     * 구현체는 일부 event만 저장되는 상태를 피하도록 목록 전체를 한 트랜잭션으로
     * 처리해야 한다.
     *
     * @param events 같은 마켓의 연속된 engine sequence를 가진 event 목록
     */
    fun append(events: List<MatchingEvent>)
}
