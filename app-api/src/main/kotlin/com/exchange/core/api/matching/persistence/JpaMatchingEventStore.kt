package com.exchange.core.api.matching.persistence

import com.exchange.core.matching.MatchingEvent
import jakarta.transaction.Transactional
import tools.jackson.databind.ObjectMapper

open class JpaMatchingEventStore(
    private val repository: MatchingEventRepository,
    private val objectMapper: ObjectMapper
) : MatchingEventStore {

    @Transactional
    override fun append(events: List<MatchingEvent>) {
        if (events.isEmpty()) {
            return
        }

        val entities = events.map { event ->
            val payloadJson = objectMapper.writeValueAsString(event.toPayload())

            MatchingEventEntity.of(
                event = event,
                payloadJson = payloadJson
            )
        }

        repository.saveAll(entities)
    }
}
