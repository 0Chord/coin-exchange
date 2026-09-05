package com.exchange.core.api.matching

import com.exchange.core.api.matching.publish.MatchingEventPublisher
import com.exchange.core.matching.MarketCommandProcessor
import com.exchange.core.matching.MatchingCommand
import com.exchange.core.matching.MatchingEvent
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * HTTP 계층과 matching core 사이의 application service.
 *
 * command를 processor로 보내고 같은 마켓 작업 스레드에서 사전 작업, 매칭, 이벤트 발행과
 * 후속 작업을 순서대로 실행한다. 주문 접수에서는 사전 작업으로 자금 예약을, 후속 작업으로
 * 체결 정산을 전달하므로 다음 command 전에 현재 command의 정산까지 끝난다.
 *
 * @property processor market별로 command를 직렬 처리하는 진입점
 * @property publisher 생성된 matching event의 후속 저장 또는 발행 포트
 */
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
     * 대기 시간 초과는 worker 작업을 취소하거나 이미 반영한 변경을 롤백하지 않는다.
     *
     * @param command controller가 요청 DTO에서 변환한 새 주문 또는 취소 command
     * @param beforeMatching 같은 마켓 작업 스레드에서 매칭 직전에 실행할 작업. 없으면 생략한다.
     * @param afterMatching publisher 성공 후 실행할 체결 정산 등의 작업. 기본값은 아무 일도 하지 않는다.
     * @return 매칭, publisher와 후속 작업까지 끝난 event 목록
     * @throws TimeoutException 3초 안에 처리가 끝나지 않은 경우
     * @throws IllegalStateException 결과 대기 중 thread가 interrupt된 경우
     */
    fun process(
        command: MatchingCommand,
        beforeMatching: (() -> Unit)? = null,
        afterMatching: (List<MatchingEvent>) -> Unit = {},
    ): List<MatchingEvent> {
        return try {
            // 같은 worker에서 publisher가 성공한 뒤에만 후속 정산을 실행한다.
            processor
                .submit(
                    command = command,
                    beforeMatching = beforeMatching,
                    eventHandler = { events ->
                        publisher.publish(events)
                        afterMatching(events)
                    },
                ).get(3, TimeUnit.SECONDS)
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
