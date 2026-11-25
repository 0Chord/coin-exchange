package com.exchange.core.api.matching.infrastructure.publish

import com.exchange.core.api.matching.application.port.MatchingEventPublisher
import com.exchange.core.matching.MatchingEvent

/**
 * 영속화가 비활성화된 설정에서 이벤트를 저장하지 않고 정상 완료한다.
 *
 * 저장 오류를 대신 처리하는 fallback이 아니다. 매칭 결과와 후속 작업은 계속 전달한다.
 */
class NoOpMatchingEventPublisher : MatchingEventPublisher {
    /**
     * event를 저장하거나 외부로 보내지 않고 정상 완료한다.
     *
     * @param events 의도적으로 사용하지 않는 matching 결과
     */
    override fun publish(events: List<MatchingEvent>) {
        // 비활성 설정에서 의도적으로 저장하지 않는다.
    }
}
