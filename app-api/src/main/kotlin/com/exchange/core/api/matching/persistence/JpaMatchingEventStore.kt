package com.exchange.core.api.matching.persistence

import com.exchange.core.matching.MatchingEvent
import jakarta.transaction.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * matching event를 JSON payload와 검색용 컬럼으로 변환해 JPA로 저장한다.
 *
 * 한 command에서 생성된 event 목록 전체를 [append]의 단일 트랜잭션으로 저장한다.
 * `(market_id, engine_sequence)` unique constraint가 중복 또는 순서 충돌을 최종 차단한다.
 *
 * @property repository matching_events 테이블 접근 객체
 * @property objectMapper payload JSON 직렬화기
 */
open class JpaMatchingEventStore(
    private val repository: MatchingEventRepository,
    private val objectMapper: ObjectMapper,
) : MatchingEventStore {

    /**
     * engine이 만든 순서대로 event를 entity로 변환해 한 번에 저장한다.
     *
     * 빈 목록은 DB 호출 없이 끝낸다. 각 event는 먼저 [MatchingEventPayload]로 변환되며,
     * 같은 payload 값으로 JSON과 검색용 컬럼을 구성한다.
     *
     * @param events 한 matching command에서 순서대로 생성된 event 목록
     */
    @Transactional
    override fun append(events: List<MatchingEvent>) {
        if (events.isEmpty()) {
            return
        }

        // entities 순서는 input event 순서와 같아 saveAll에도 engine 순서를 그대로 전달한다.
        val entities = events.map { event ->
            val payloadJson = objectMapper.writeValueAsString(event.toPayload())

            MatchingEventEntity.of(
                event = event,
                payloadJson = payloadJson,
            )
        }

        repository.saveAll(entities)
    }
}
