package com.exchange.core.api.matching.publish

import com.exchange.core.matching.MatchingEvent

/**
 * 아직 외부 발행을 하지 않는 publisher.
 *
 * API 연결을 먼저 검증하기 위한 임시 구현이다.
 */
class NoOpMatchingEventPublisher : MatchingEventPublisher {
    override fun publish(events: List<MatchingEvent>) {
        // External event publishing is intentionally disabled for the first API slice.
    }
}
