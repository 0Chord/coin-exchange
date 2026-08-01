package com.exchange.core.matching

import com.exchange.core.common.MarketId
import java.lang.AutoCloseable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 매칭 엔진 앞단의 command 처리 입구.
 *
 * MatchingEngine은 내부에 mutable order book을 들고 있으므로 여러 thread가 동시에
 * process()를 호출하면 안 된다. 이 processor는 외부 동시 요청을 받아 market별
 * worker로 넘기고, 같은 market의 command가 한 줄로 처리되도록 만드는 경계다.
 *
 * Phase 1에서는 in-memory 구현만 둔다. 나중에 Kafka partition, coroutine channel,
 * bounded queue로 바뀌어도 외부 호출자는 이 인터페이스만 바라보게 하는 것이 목적이다.
 */
interface MarketCommandProcessor : AutoCloseable {
    /**
     * command 처리를 요청한다.
     *
     * 반환값은 즉시 완성된 결과가 아니라 worker가 나중에 채워 넣을 future다.
     * 성공하면 MatchingEvent 목록이 들어가고, 처리 중 예외가 나면 future가 실패 상태가 된다.
     */
    fun submit(command: MatchingCommand): CompletableFuture<List<MatchingEvent>>
}

/**
 * JVM 메모리 안에서 market별 worker를 관리하는 구현체.
 *
 * 아직 프로덕션 완성형은 아니다. 현재 목적은 MatchingEngine을 직접 동시 호출하지 않고
 * market별 single-writer 구조를 테스트할 수 있는 최소 경계를 만드는 것이다.
 *
 * 다음 단계에서 보강할 것:
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
    private val closed = AtomicBoolean(false)

    override fun submit(command: MatchingCommand): CompletableFuture<List<MatchingEvent>> {
        if (closed.get()) {
            return failedFuture(RejectedExecutionException("market command processor is closed"))
        }

        // market worker가 없으면 새로 만들고, 이미 있으면 기존 worker를 재사용한다.
        // computeIfAbsent를 쓰면 같은 market worker가 map에 하나만 남는다.
        val worker = workers.computeIfAbsent(command.marketId) { marketId ->
            MarketWorker(marketId)
        }

        if (closed.get()) {
            worker.close()
            return failedFuture(RejectedExecutionException("market command processor is closed"))
        }

        return worker.submit(command)
    }

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
    private val marketId: MarketId,
    private val engine: MatchingEngine = MatchingEngine()
) : AutoCloseable {

    /**
     * market command를 하나씩 실행하는 executor.
     *
     * 작업은 여러 개 들어올 수 있지만 실행 thread는 하나다.
     * 따라서 같은 market의 command는 동시에 실행되지 않고 queue 순서대로 처리된다.
     */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "matching-worker-${marketId.value}").apply {
            isDaemon = true
        }
    }
    private val closed = AtomicBoolean(false)

    fun submit(command: MatchingCommand): CompletableFuture<List<MatchingEvent>> {
        // worker가 담당하는 market과 command market이 다르면 processor 구현 버그다.
        require(command.marketId == marketId) {
            "command marketId must match worker marketId"
        }

        if (closed.get()) {
            return failedFuture(RejectedExecutionException("market worker is closed"))
        }

        // submit을 호출한 thread는 matching을 직접 수행하지 않는다.
        // 결과를 담을 future만 만들고, 실제 처리는 executor에 맡긴다.
        val future = CompletableFuture<List<MatchingEvent>>()

        try {
            executor.execute {
                try {
                    // 이 블록은 worker의 single thread에서 실행된다.
                    val events = engine.process(command)
                    future.complete(events)
                } catch (error: Throwable) {
                    // worker thread 안에서 난 예외를 submit 호출자에게 전달한다.
                    future.completeExceptionally(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            future.completeExceptionally(error)
        }

        return future
    }

    override fun close() {
        // executor가 가진 worker thread를 정리한다.
        if (closed.compareAndSet(false, true)) {
            executor.shutdown()
        }
    }

}

private fun failedFuture(error: Throwable): CompletableFuture<List<MatchingEvent>> {
    val future = CompletableFuture<List<MatchingEvent>>()
    future.completeExceptionally(error)
    return future
}
