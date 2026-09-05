package com.exchange.core.matching

import com.exchange.core.common.MarketId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 매칭 엔진 앞단의 command 처리 입구.
 *
 * MatchingEngine은 내부에 mutable order book을 들고 있으므로 여러 thread가 동시에
 * process()를 호출하면 안 된다. 이 processor는 외부 동시 요청을 받아 market별
 * worker로 넘기고, 같은 market의 command가 한 줄로 처리되도록 만드는 경계다.
 *
 * 현재는 in-memory 구현만 둔다. 나중에 Kafka partition, coroutine channel,
 * bounded queue로 바뀌어도 외부 호출자는 이 인터페이스만 바라보게 하는 것이 목적이다.
 */
interface MarketCommandProcessor : AutoCloseable {
    /**
     * command 처리를 요청한다.
     *
     * 반환값은 즉시 완성된 결과가 아니라 worker가 나중에 채워 넣을 future다.
     * 성공하면 MatchingEvent 목록이 들어가고, 처리 중 예외가 나면 future가 실패 상태가 된다.
     * 사전 작업, 매칭과 [eventHandler]는 같은 market worker thread에서 순서대로 실행된다.
     * 사전 작업에 자금 예약을 전달하면 예약 성공 전에 엔진이 주문을 처리하지 않는다.
     * 사전 작업 자체의 실패 시 원상 복구는 해당 작업의 책임이며, processor가 DB를 롤백하지 않는다.
     *
     * @param command 순서대로 처리할 matching 입력
     * @param beforeMatching 매칭 직전에 같은 worker에서 실행할 함수. null이면 생략한다.
     * @param eventHandler 생성된 event의 저장·발행과 체결 정산 등을 실행하는 후속 처리 함수
     * @return 사전 작업, 매칭과 후속 처리가 모두 끝날 때 완료되는 future
     */
    fun submit(
        command: MatchingCommand,
        beforeMatching: (() -> Unit)? = null,
        eventHandler: (List<MatchingEvent>) -> Unit = {},
    ): CompletableFuture<List<MatchingEvent>>
}

/**
 * JVM 메모리 안에서 market별 worker를 관리하는 구현체.
 *
 * 아직 프로덕션 완성형은 아니다. 현재 목적은 MatchingEngine을 직접 동시 호출하지 않고
 * market별 single-writer 구조를 테스트할 수 있는 최소 경계를 만드는 것이다.
 *
 * 다음 단계에서 보강할 것:
 *
 * - queue 크기 제한과 backpressure
 * - worker 개수 제한
 * - graceful shutdown timeout
 * - queue depth metric
 */
class InMemoryMarketCommandProcessor : MarketCommandProcessor {
    /**
     * marketId별 worker 저장소.
     *
     * 여러 thread가 동시에 submit할 수 있으므로 일반 HashMap이 아니라 ConcurrentHashMap을 쓴다.
     */
    private val workers = ConcurrentHashMap<MarketId, MarketWorker>()

    /** processor 종료가 시작되었는지 나타내는 thread-safe flag. */
    private val closed = AtomicBoolean(false)

    /**
     * command가 속한 마켓의 worker를 찾아 queue에 제출한다.
     *
     * 같은 marketId의 최초 요청만 [MarketWorker]를 생성하고 이후 요청은 기존 worker를
     * 공유한다. processor가 닫힌 뒤 들어온 요청은 실행하지 않고 실패한 future를 반환한다.
     *
     * @param command 처리할 새 주문 또는 취소 command
     * @param beforeMatching 매칭 전에 실행할 자금 예약 등의 작업
     * @param eventHandler matching 직후 같은 worker에서 실행할 후속 처리
     * @return worker가 완료시킬 event future
     */
    override fun submit(
        command: MatchingCommand,
        beforeMatching: (() -> Unit)?,
        eventHandler: (List<MatchingEvent>) -> Unit,
    ): CompletableFuture<List<MatchingEvent>> {
        if (closed.get()) {
            return failedFuture(RejectedExecutionException("market command processor is closed"))
        }

        // market worker가 없으면 새로 만들고, 이미 있으면 기존 worker를 재사용한다.
        // computeIfAbsent를 쓰면 같은 market worker가 map에 하나만 남는다.
        val worker =
            workers.computeIfAbsent(command.marketId) { marketId ->
                MarketWorker(marketId)
            }

        if (closed.get()) {
            worker.close()
            return failedFuture(RejectedExecutionException("market command processor is closed"))
        }

        return worker.submit(
            command = command,
            beforeMatching = beforeMatching,
            eventHandler = eventHandler,
        )
    }

    /**
     * 새 command 접수를 막고 현재 생성된 모든 market executor를 종료한다.
     *
     * [AtomicBoolean.compareAndSet]으로 최초 호출만 실제 shutdown을 수행하므로 여러 번
     * 호출해도 안전하다.
     */
    override fun close() {
        // 지금은 단순 shutdown만 한다.
        // 나중에는 timeout을 두고 awaitTermination까지 처리하는 쪽이 더 안전하다.
        if (closed.compareAndSet(false, true)) {
            workers.values.forEach { worker -> worker.close() }
        }
    }
}

/**
 * 하나의 market을 담당하는 worker.
 *
 * 이 worker 안의 MatchingEngine은 이 worker의 single thread executor에서만 호출된다.
 * 그래서 MatchingEngine 자체를 synchronized/lock 기반으로 만들지 않고도
 * 같은 market 안의 price-time priority와 sequence 순서를 지킬 수 있다.
 */
private class MarketWorker(
    /** 이 worker가 전담하는 마켓. 다른 마켓 command는 받을 수 없다. */
    private val marketId: MarketId,
    /** 이 worker thread에서만 접근하는 해당 마켓의 mutable matching state. */
    private val engine: MatchingEngine = MatchingEngine(),
) : AutoCloseable {
    /**
     * market command를 하나씩 실행하는 executor.
     *
     * 작업은 여러 개 들어올 수 있지만 실행 thread는 하나다.
     * 따라서 같은 market의 command는 동시에 실행되지 않고 queue 순서대로 처리된다.
     */
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "matching-worker-${marketId.value}").apply {
                isDaemon = true
            }
        }

    /** executor 종료 여부를 여러 submit thread가 안전하게 확인하기 위한 flag. */
    private val closed = AtomicBoolean(false)

    /**
     * 사전 작업 성공 후 엔진 처리 또는 eventHandler에서 최초로 발생한 치명적 실패.
     *
     * 예약만 남거나 엔진 상태와 저장된 event가 달라질 수 있으므로,
     * 이 값을 설정한 뒤에는 같은 마켓의 후속 command를 모두 실패시킨다.
     */
    private val failure = AtomicReference<Throwable?>(null)

    /**
     * command를 이 마켓의 단일 thread queue에 넣는다.
     *
     * [beforeMatching], 엔진 처리, [eventHandler] 순서로 실행하고 모두 성공해야 완료된다.
     * 사전 작업 자체의 실패는 해당 command만 거절한다. 사전 작업 성공 후 엔진이 실패하거나
     * eventHandler가 실패하면 worker를 unavailable 상태로 만든다.
     * 사전 작업 없이 엔진 검증에서 실패한 경우는 기존처럼 해당 command만 거절한다.
     * 마켓 중단은 추가 처리를 막을 뿐이며, 이미 반영한 예약·엔진 상태·저장 결과를 복구하지 않는다.
     *
     * @param command 이 worker의 [marketId]와 일치해야 하는 입력
     * @param beforeMatching 엔진 실행 전에 완료해야 할 함수. null이면 바로 엔진을 실행한다.
     * @param eventHandler 엔진 상태 변경 직후 실행할 event 저장 또는 발행 함수
     * @return 처리 결과 또는 실패 원인을 전달하는 future
     * @throws IllegalArgumentException command의 market이 worker market과 다른 경우
     */
    fun submit(
        command: MatchingCommand,
        beforeMatching: (() -> Unit)?,
        eventHandler: (List<MatchingEvent>) -> Unit,
    ): CompletableFuture<List<MatchingEvent>> {
        // worker가 담당하는 market과 command market이 다르면 processor 구현 버그다.
        require(command.marketId == marketId) {
            "command marketId must match worker marketId"
        }

        if (closed.get()) {
            return failedFuture(RejectedExecutionException("market worker is closed"))
        }

        // 앞선 처리 실패로 중단한 마켓에서는 복구 전까지 새 작업을 실행하지 않는다.
        failure.get()?.let { cause ->
            return failedFuture(marketUnavailable(cause))
        }

        // submit을 호출한 thread는 matching을 직접 수행하지 않는다.
        // 결과를 담을 future만 만들고, 실제 처리는 executor에 맡긴다.
        val future = CompletableFuture<List<MatchingEvent>>()

        try {
            executor.execute {
                val previousFailure = failure.get()

                if (previousFailure != null) {
                    future.completeExceptionally(
                        marketUnavailable(previousFailure),
                    )
                    return@execute
                }

                try {
                    // 사전 작업이 실패하면 엔진을 실행하지 않고 이 요청만 실패시킨다.
                    beforeMatching?.invoke()
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                    return@execute
                }

                // 엔진이 정상 반환했는지 구분하여 후속 작업 실패 시 마켓을 중단한다.
                var matchingCompleted = false

                try {
                    val events = engine.process(command)
                    matchingCompleted = true

                    eventHandler(events)
                    future.complete(events)
                } catch (error: Throwable) {
                    // 성공한 사전 작업이나 엔진 변경이 남을 수 있으면 다음 명령을 받지 않는다.
                    if (beforeMatching != null || matchingCompleted) {
                        failure.compareAndSet(null, error)
                    }

                    future.completeExceptionally(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            future.completeExceptionally(error)
        }

        return future
    }

    /**
     * 앞선 처리 실패 때문에 마켓 처리를 계속할 수 없음을 나타내는 예외를 만든다.
     *
     * @param cause 마켓 중단을 유발한 최초 실패 원인
     * @return 후속 command future에 전달할 거절 예외
     */
    private fun marketUnavailable(
        cause: Throwable,
    ): RejectedExecutionException =
        RejectedExecutionException(
            "market ${marketId.value} is unavailable after command processing failure",
            cause,
        )

    /**
     * 이 마켓의 executor가 새 작업을 받지 않도록 정상 shutdown을 시작한다.
     *
     * 이미 queue에 들어간 작업은 JVM ExecutorService의 shutdown 규칙에 따라 계속 실행된다.
     */
    override fun close() {
        // executor가 가진 worker thread를 정리한다.
        if (closed.compareAndSet(false, true)) {
            executor.shutdown()
        }
    }
}

/**
 * Java 버전과 무관하게 이미 실패한 CompletableFuture를 만든다.
 *
 * @param error future가 전달할 실패 원인
 * @return [error]로 exceptionally completed된 future
 */
private fun failedFuture(error: Throwable): CompletableFuture<List<MatchingEvent>> {
    val future = CompletableFuture<List<MatchingEvent>>()
    future.completeExceptionally(error)
    return future
}
