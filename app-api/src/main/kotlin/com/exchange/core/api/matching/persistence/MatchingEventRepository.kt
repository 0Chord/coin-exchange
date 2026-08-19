package com.exchange.core.api.matching.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * [MatchingEventEntity]의 Spring Data JPA repository.
 */
interface MatchingEventRepository : JpaRepository<MatchingEventEntity, Long> {
    /**
     * 한 마켓의 event log를 engine 발생 순서대로 조회한다.
     *
     * @param marketId 조회할 마켓 문자열
     * @return [MatchingEventEntity.engineSequence] 오름차순 event 목록
     */
    fun findByMarketIdOrderByEngineSequenceAsc(marketId: String): List<MatchingEventEntity>
}
