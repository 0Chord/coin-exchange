package com.exchange.core.api.matching

import com.exchange.core.api.matching.publish.MatchingEventPublisher
import com.exchange.core.matching.MarketCommandProcessor
import com.exchange.core.matching.MatchingCommand
import com.exchange.core.matching.MatchingEvent
import org.springframework.stereotype.Service

/**
 * HTTP 계층과 matching core 사이의 application service.
 *
 * command를 processor로 보내고, 발생한 event를 publisher에 넘긴다.
 */
@Service
class MatchingApplicationService(
    private val processor: MarketCommandProcessor,
    private val publisher: MatchingEventPublisher
) {
    /**
     * 하나의 matching command를 처리하고 발생한 event를 반환한다.
     */
    fun process(command: MatchingCommand): List<MatchingEvent> {
        val events = processor.submit(command).get()

        publisher.publish(events)

        return events
    }
}
