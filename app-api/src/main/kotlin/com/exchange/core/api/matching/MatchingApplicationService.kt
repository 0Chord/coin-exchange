package com.exchange.core.api.matching

import com.exchange.core.api.matching.publish.MatchingEventPublisher
import com.exchange.core.matching.MarketCommandProcessor
import com.exchange.core.matching.MatchingCommand
import com.exchange.core.matching.MatchingEvent
import org.springframework.stereotype.Service
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * HTTP 계층과 matching core 사이의 application service.
 *
 * command를 processor로 보내고, 발생한 event를 publisher에 넘긴다.
 * processor의 market worker 안에서 matching과 publish를 연속 실행하므로, 같은 마켓에서는
 * 다음 command가 시작되기 전에 현재 command의 event 저장까지 끝난다.
 *
 * @property processor market별로 command를 직렬 처리하는 진입점
 * @property publisher 생성된 matching event의 후속 저장 또는 발행 포트
 */
@Service
class MatchingApplicationService(
    private val processor: MarketCommandProcessor,
    private val publisher: MatchingEventPublisher,
) {
    /**
     * 하나의 matching command를 처리하고 발생한 event를 반환한다.
     *
     * HTTP 요청 thread는 비동기 processor 결과를 최대 3초 기다린다. worker 내부 예외는
     * [ExecutionException] wrapper를 벗겨 실제 도메인 또는 저장 오류를 호출자에게 전달한다.
     * 대기 중 interrupt가 발생하면 현재 thread의 interrupt flag를 복구한다.
     *
     * @param command controller가 요청 DTO에서 변환한 새 주문 또는 취소 command
     * @return matching state가 바뀌고 publisher 처리까지 끝난 event 목록
     * @throws java.util.concurrent.TimeoutException 3초 안에 처리가 끝나지 않은 경우
     * @throws IllegalStateException 결과 대기 중 thread가 interrupt된 경우
     */
    fun process(command: MatchingCommand): List<MatchingEvent> {
        return try {
            // publisher는 market worker thread에서 실행되어 event 순서를 그대로 보존한다.
            processor.submit(command) { events ->
                publisher.publish(events)
            }.get(3, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(
                "matching command interrupted",
                error,
            )
        }
    }
}
