package com.exchange.core.matching

import com.exchange.core.common.MarketId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 가변 주문장을 가진 [MatchingEngine]을 같은 마켓에서 동시에 호출하지 않도록
 * 외부 명령을 마켓별 worker에서 직렬 처리하는 경계.
 */
interface MarketCommandProcessor : AutoCloseable {
    /**
     * command 처리를 요청한다.
     *
     * 사전 작업 → 매칭 → [eventHandler]를 같은 마켓 worker에서 실행한다.
     * 모두 성공하면 future에 이벤트 목록을 반환하고, 처리 예외는 실패로 전달한다.
     * 사전 작업 성공 전에는 엔진을 실행하지 않는다. 사전 작업 실패의 복구는 해당 작업의
     * 책임이며, processor가 DB를 롤백하지 않는다.
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
 * JVM 메모리에서 마켓별 단일 스레드 worker를 관리한다.
 *
 * 실행 방식·용량·종료의 현재 한계는 저장소 `engineering/flow-and-scope-contract.md`의
 * ‘실행기의 종료와 현재 한계’에서 설명한다.
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
     * 새 명령을 거절하고 모든 마켓 worker의 종료를 시작한다.
     * 접수된 작업은 취소하지 않으며 종료 완료를 기다리지 않는다.
     *
     * [AtomicBoolean.compareAndSet]으로 최초 호출만 실제 shutdown을 수행하므로 여러 번
     * 호출해도 안전하다.
     */
    override fun close() {
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
     * [submit]의 실패 분기에 따라 마켓 중단을 유발한 최초 원인.
     *
     * 예약만 남거나 엔진 상태와 저장된 event가 달라질 수 있으므로,
     * 이 값을 설정한 뒤에는 같은 마켓의 후속 command를 모두 실패시킨다.
     */
    private val failure = AtomicReference<Throwable?>(null)

    /**
     * command를 이 마켓의 단일 thread queue에 넣는다.
     *
     * [beforeMatching] → 엔진 → [eventHandler] 순서로 실행하고 모두 성공해야 완료된다.
     * 사전 작업 실패는 이 명령만 실패시킨다. 사전 작업 정상 반환 뒤 엔진이 실패하거나
     * eventHandler가 실패하면 같은 마켓의 대기·신규 명령을 최초 원인과 함께 거절한다.
     * 이때 중단 조건은 실제 부작용이 아닌 `beforeMatching != null || matchingCompleted`다.
     * 사전 작업 없는 엔진 실패는 이 명령만 실패시키며 상태 복구를 보장하지 않는다.
     * 마켓 중단도 이미 반영한 예약·엔진 상태·저장 결과를 되돌리지 않는다.
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
