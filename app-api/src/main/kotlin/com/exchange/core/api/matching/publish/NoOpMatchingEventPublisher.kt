package com.exchange.core.api.matching.publish

import com.exchange.core.matching.MatchingEvent

/**
 * 아직 외부 발행을 하지 않는 publisher.
 *
 * API 연결을 먼저 검증하기 위한 임시 구현이다.
 * persistence가 비활성화된 환경에서 event를 의도적으로 버리되 matching 결과 자체는
 * HTTP 응답으로 계속 반환할 수 있게 한다.
 */
class NoOpMatchingEventPublisher : MatchingEventPublisher {
    /**
     * event를 저장하거나 외부로 보내지 않고 정상 완료한다.
     *
     * @param events 의도적으로 사용하지 않는 matching 결과
     */
    override fun publish(events: List<MatchingEvent>) {
        // External event publishing is intentionally disabled for the first API slice.
    }
}
