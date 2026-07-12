package com.exchange.core.api.matching.persistence

import com.exchange.core.api.matching.publish.MatchingEventPublisher
import com.exchange.core.matching.MatchingEvent

/**
 * MatchingEvent를 store에 저장하는 publisher.
 */
class PersistentMatchingEventPublisher(
    private val store: MatchingEventStore,
) : MatchingEventPublisher {
    override fun publish(events: List<MatchingEvent>) {
        store.append(events)
    }
}
