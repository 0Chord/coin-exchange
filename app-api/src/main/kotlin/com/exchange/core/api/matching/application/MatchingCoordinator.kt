package com.exchange.core.api.matching.application

import com.exchange.core.api.matching.application.port.MatchingEventPublisher
import com.exchange.core.matching.MarketCommandProcessor
import com.exchange.core.matching.MatchingCommand
import com.exchange.core.matching.MatchingEvent
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 주문 유즈케이스의 사전·후속 작업과 매칭 코어를 연결한다.
 *
 * 같은 마켓 worker에서 사전 작업 → 매칭 → publisher → 후속 작업을 직렬 실행한다.
 * publisher가 성공한 뒤에만 후속 작업을 실행하며, 다음 명령은 이 작업까지 끝난 뒤 처리한다.
 *
 * @property processor market별로 command를 직렬 처리하는 진입점
 * @property publisher 생성된 matching event의 후속 저장 또는 발행 포트
 */
class MatchingCoordinator(
    private val processor: MarketCommandProcessor,
    private val publisher: MatchingEventPublisher,
) {
    /**
     * 하나의 matching command를 처리하고 발생한 event를 반환한다.
     *
     * 호출자 스레드는 결과를 최대 3초 기다린다. worker 오류는 [ExecutionException]에서
     * 원인을 꺼내 전달하고, 대기 interrupt는 호출자 스레드의 interrupt flag를 복구한다.
     * timeout이나 interrupt로 대기가 끝나도 worker 작업을 취소하거나 변경을 롤백하지 않는다.
     *
     * @param command 유즈케이스가 전달한 새 주문 또는 취소 명령
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
    ): List<MatchingEvent> =
        try {
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
