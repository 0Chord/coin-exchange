package com.exchange.core.api.matching.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface MatchingEventRepository : JpaRepository<MatchingEventEntity, Long> {
    fun findByMarketIdOrderByEngineSequenceAsc(marketId: String): List<MatchingEventEntity>
}