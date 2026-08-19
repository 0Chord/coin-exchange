package com.exchange.core.api.matching.persistence

import com.exchange.core.api.matching.publish.MatchingEventPublisher
import com.exchange.core.matching.MatchingEvent

/**
 * MatchingEvent를 store에 저장하는 publisher.
 *
 * processor의 market worker thread에서 호출되며, [store] 저장이 끝나야 matching
 * command의 future도 완료된다. 저장 예외를 삼키지 않아 해당 market worker가 후속 처리를
 * 중단하게 한다.
 *
 * @property store event 목록을 원자적으로 저장하는 영속성 포트
 */
class PersistentMatchingEventPublisher(
    private val store: MatchingEventStore,
) : MatchingEventPublisher {
    /**
     * matching event 목록을 순서 그대로 store에 위임한다.
     *
     * @param events 한 command가 생성한 event 목록
     */
    override fun publish(events: List<MatchingEvent>) {
        store.append(events)
    }
}
