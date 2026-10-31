package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.TradingAdmissionResult
import com.exchange.core.api.trading.application.TradingOrderGateway
import com.exchange.core.api.trading.application.TradingOrderSubmission
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageException
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import io.aeron.Publication
import org.agrona.concurrent.SleepingMillisIdleStrategy
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 연결은 한 스레드가 소유한다. HTTP 동시 요청은 bounded 대기와 최초 요청 키로 연결한다. */
internal class AeronOrderGateway(
    private val settings: AeronGatewaySettings,
    private val markets: DevelopmentMarketRegistry,
    private val metrics: GatewayMetrics = GatewayMetrics(),
    // 드레인 상한은 주입 시계로 검증한다. 요청 기한은 실제 단조 시계를 그대로 쓴다.
    private val pollNanoTime: () -> Long = System::nanoTime,
    private val openTransport: () -> GatewayOrderTransport = { AeronOrderTransport(settings) },
) : TradingOrderGateway,
    AutoCloseable {
    private data class RequestKey(
        val userId: String,
        val requestId: String,
    )

    private class Pending(
        val submission: TradingOrderSubmission,
        val enqueuedAt: Long,
        val phaseDurations: LongArray?,
        val result: CompletableFuture<TradingAdmissionResult> = CompletableFuture(),
        // 인코딩과 연결 I/O는 worker만 소유한다. 재시도해도 최초 바이트를 바꾸지 않는다.
        var bytes: ByteArray? = null,
        var offered: Boolean = false,
        var selectedAt: Long? = null,
        var firstOfferAt: Long? = null,
        var offeredAt: Long? = null,
        var terminalRecorded: Boolean = false,
        var completionTracked: Boolean = false,
        var resultReadyAt: Long = 0,
    )

    private val logger = LoggerFactory.getLogger(AeronOrderGateway::class.java)

    // submit 간 예약과 드문 연결 교체만 직렬화한다. 전송·응답·기한 확인은 이 잠금을 잡지 않는다.
    private val lock = ReentrantLock()
    private val requests = ConcurrentHashMap<RequestKey, Pending>(settings.queueCapacity + settings.maxInFlight)
    private val queue = ConcurrentLinkedQueue<Pending>()
    private val queuedCount = AtomicInteger()

    // 큐에서 옮긴 순서와 모든 변경은 연결 소유 스레드만 관리한다.
    private val inFlight = linkedMapOf<RequestKey, Pending>()
    private val codec = AdmissionCommandCodec()
    private val ready = CountDownLatch(1)
    private val startupFailure = AtomicReference<Throwable?>()
    private val shutdownFailure = AtomicReference<Throwable?>()
    private val completionPermits = Semaphore(settings.queueCapacity + settings.maxInFlight)
    private val completionContext = ThreadLocal<Boolean>()
    private val completionExecutor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("aeron-order-completion-", 0).factory())
    private var available = false

    @Volatile
    private var closing = false
    private val timeoutNs = TimeUnit.MILLISECONDS.toNanos(settings.resultTimeoutMs)
    private val worker: Thread

    init {
        Files.createDirectory(settings.runRoot)
        worker =
            Thread(::run, "aeron-order-gateway").apply {
                isDaemon = true
                start()
            }
        if (!ready.await(settings.startupTimeoutMs + 1_000, TimeUnit.MILLISECONDS)) {
            close()
            error("Aeron HTTP gateway 연결 준비 시간이 초과됐습니다")
        }
        startupFailure.get()?.let {
            close()
            throw IllegalStateException("Aeron HTTP gateway 연결을 준비하지 못했습니다", it)
        }
    }

    override fun submit(submission: TradingOrderSubmission): CompletableFuture<TradingAdmissionResult> {
        val enteredAt = System.nanoTime()
        val key = key(submission)
        var acquiredAt = enteredAt
        var outcome = GatewayMetrics.Submission.NEW
        val result =
            lock.withLock {
                acquiredAt = System.nanoTime()
                requests[key]?.let { previous ->
                    return@withLock if (previous.submission == submission) {
                        outcome = GatewayMetrics.Submission.REUSED
                        previous.result
                    } else {
                        outcome = GatewayMetrics.Submission.CONFLICT
                        completed(TradingAdmissionResult.Rejected("REQUEST_IN_FLIGHT_CONTENT_CONFLICT"))
                    }
                }
                if (closing || !available) {
                    outcome = GatewayMetrics.Submission.UNAVAILABLE
                    return@withLock completed(TradingAdmissionResult.NotSubmitted("GATEWAY_UNAVAILABLE"))
                }
                if (queuedCount.get() >= settings.queueCapacity) {
                    outcome = GatewayMetrics.Submission.QUEUE_FULL
                    return@withLock completed(TradingAdmissionResult.NotSubmitted("QUEUE_FULL", overloaded = true))
                }
                // 원본과 큐 자리를 함께 예약한다. 변환 중에도 같은 키는 같은 future를 공유한다.
                val durations = if (metrics.observesRequests) LongArray(GatewayMetrics.Phase.entries.size) { -1L } else null
                durations?.set(GatewayMetrics.Phase.SUBMIT_LOCK.ordinal, acquiredAt - enteredAt)
                val pending = Pending(submission, System.nanoTime(), durations)
                requests[key] = pending
                queuedCount.incrementAndGet()
                // consumer가 볼 수 있게 게시하기 전에 계측에도 예약한다.
                metrics.adjustSizes(queued = 1, reservedKeys = 1)
                queue.add(pending)
                pending.result
            }
        metrics.duration(GatewayMetrics.Phase.SUBMIT_LOCK, acquiredAt - enteredAt)
        metrics.submission(outcome)
        return result
    }

    private fun run() {
        var transport: GatewayOrderTransport? = null
        val idle = TradingAeronRuntime.idleStrategy()
        try {
            transport = openTransport()
            lock.withLock { available = !closing }
            ready.countDown()
            while (!closing) {
                try {
                    // submit 직렬화가 큐의 시각 순서를 보존한다. 응답이 뒤집혀도 남은 두 head 중 가장 오래된 요청을 찾는다.
                    val queuedAt = queue.peek()?.enqueuedAt
                    val inFlightAt = inFlight.values.firstOrNull()?.enqueuedAt
                    val oldestAt =
                        when {
                            queuedAt == null -> inFlightAt
                            inFlightAt == null -> queuedAt
                            else -> minOf(queuedAt, inFlightAt)
                        }
                    val expired = oldestAt?.let { System.nanoTime() - it >= timeoutNs } ?: false
                    if (expired) {
                        // 대기 종료는 취소가 아니다. 이전 session을 닫기 전에는 같은 키를 다른 원본에 재사용하지 않는다.
                        disconnect(TradingAdmissionResult.Unknown("ADMISSION_TIMEOUT", timedOut = true))
                        transport?.close()
                        transport = null
                        clearDisconnected()
                        if (closing) break
                        transport = reconnect() ?: break
                        lock.withLock { available = !closing }
                    }
                    val current = checkNotNull(transport)
                    var work = drainResponses(current)
                    work += sendQueued(current)
                    idle.idle(work)
                } catch (error: Exception) {
                    logger.warn("Aeron 주문 연결 오류로 대기 결과를 미확인 처리하고 연결을 교체합니다", error)
                    disconnect(TradingAdmissionResult.Unknown("CONNECTION_LOST"))
                    transport?.close()
                    transport = null
                    clearDisconnected()
                    if (closing) break
                    transport = reconnect() ?: break
                    lock.withLock { available = !closing }
                }
            }
        } catch (error: Throwable) {
            logger.error("Aeron HTTP gateway 연결 소유 스레드가 중단됐습니다", error)
            startupFailure.compareAndSet(null, error)
        } finally {
            lock.withLock {
                closing = true
                available = false
            }
            ready.countDown()
            disconnect(TradingAdmissionResult.Unknown("GATEWAY_CLOSED"))
            try {
                transport?.close()
            } finally {
                clearDisconnected()
                // 미종료 callback이 남으면 Gauge를 유지해 진행 중인 완료를 숨기지 않는다.
                if (stopCompletions()) metrics.close()
            }
        }
    }

    /** 최초 시작 실패와 달리 교체 연결 실패는 새 요청을 막은 채 종료 요청까지 재시도한다. */
    private fun reconnect(): GatewayOrderTransport? {
        val retryIdle = SleepingMillisIdleStrategy(100)
        while (!closing) {
            try {
                return openTransport()
            } catch (error: Exception) {
                logger.warn("Aeron 교체 연결을 열지 못해 신규 요청을 차단한 채 재시도합니다", error)
                retryIdle.idle()
            }
        }
        return null
    }

    /** poll 한 번의 제한보다 응답이 많아도 전송·기한 확인에 돌아올 기회를 남긴다. */
    private fun drainResponses(transport: GatewayOrderTransport): Int {
        val startedAt = pollNanoTime()
        var work = 0
        while (!closing) {
            val fragments = transport.poll(::receive)
            work += fragments
            // poll 자체의 한 배치는 나누지 않는다. 경계는 각 poll이 돌아온 뒤 확인한다.
            if (fragments == 0 || work >= 64 || pollNanoTime() - startedAt >= 1_000_000) break
        }
        return work
    }

    private fun sendQueued(transport: GatewayOrderTransport): Int {
        var count = 0
        // 응답 poll과 기한 확인이 새 요청의 연속 유입 때문에 밀리지 않게 한 번의 전송량을 제한한다.
        repeat(64) {
            var selectedNow = false
            if (closing || inFlight.size >= settings.maxInFlight) return count
            val next = queue.peek() ?: return count
            if (next.selectedAt == null) {
                next.selectedAt = System.nanoTime()
                selectedNow = true
            }
            if (selectedNow) recordDuration(next, GatewayMetrics.Phase.QUEUE, checkNotNull(next.selectedAt) - next.enqueuedAt)
            val bytes =
                next.bytes ?: try {
                    val encodeStartedAt = System.nanoTime()
                    try {
                        encode(next.submission).also { next.bytes = it }
                    } finally {
                        recordDuration(next, GatewayMetrics.Phase.ENCODE, System.nanoTime() - encodeStartedAt)
                    }
                } catch (_: AdmissionMessageException) {
                    startCompletion(next)
                    check(queue.poll() === next)
                    val removed = requests.remove(key(next.submission), next)
                    check(removed)
                    metrics.adjustSizes(queued = -1, reservedKeys = -1)
                    queuedCount.decrementAndGet()
                    complete(next, TradingAdmissionResult.InvalidInput("INVALID_COMMAND"))
                    count++
                    return@repeat
                }
            if (closing) return count
            // 큐에서 아직 제거하지 않아 offer가 진행되는 동안에도 bounded 대기 자리를 차지한다.
            // close와 경합한 offer의 결과는 worker 종료에서 Unknown으로 끝난다. 대기 종료는 취소가 아니다.
            if (next.firstOfferAt == null) next.firstOfferAt = System.nanoTime()
            val position =
                try {
                    transport.offer(bytes)
                } catch (error: Exception) {
                    metrics.offerException()
                    throw error
                }
            val offerReturnedAt = System.nanoTime()
            metrics.offer(position)
            if (position == Publication.BACK_PRESSURED || position == Publication.ADMIN_ACTION) return count
            check(position > 0) { "Aeron 주문을 제출할 수 없습니다" }
            next.offeredAt = offerReturnedAt
            recordDuration(next, GatewayMetrics.Phase.OFFER_WAIT, offerReturnedAt - checkNotNull(next.firstOfferAt))
            check(queue.poll() === next)
            next.offered = true
            inFlight[key(next.submission)] = next
            metrics.adjustSizes(queued = -1, inFlight = 1)
            // 계측의 이동을 마친 뒤 producer에 큐 자리를 반환한다.
            queuedCount.decrementAndGet()
            count++
        }
        return count
    }

    private fun encode(submission: TradingOrderSubmission): ByteArray {
        // 최초 요청 키의 판단은 노드가 맡는다. 미등록 마켓도 주문 원본을 바꾸지 않는다.
        val context = markets[submission.order.marketId] ?: markets.all().first()
        return codec.encode(
            AdmissionCommandPayload(
                submission.requestId,
                submission.order,
                context.market,
                context.feePolicySnapshot,
                "HTTP-${UUID.randomUUID()}",
                Instant.now(),
            ),
        )
    }

    private fun receive(response: AdmissionResultPayload) {
        val key = RequestKey(response.userId, response.requestId)
        val pending = requests[key] ?: return
        check(
            pending.offered &&
                pending.submission.order.marketId.value == response.marketId &&
                pending.submission.order.orderId.value == response.orderId,
        ) { "Aeron 접수 응답이 전송한 주문과 다릅니다" }
        val validatedAt = System.nanoTime()
        startCompletion(pending)
        check(inFlight.remove(key) === pending)
        check(requests.remove(key, pending))
        metrics.adjustSizes(inFlight = -1, reservedKeys = -1)
        recordDuration(pending, GatewayMetrics.Phase.RESPONSE_WAIT, validatedAt - checkNotNull(pending.offeredAt))
        val result =
            when (response) {
                is AdmissionResultPayload.Accepted -> TradingAdmissionResult.Accepted(response.sequence)
                is AdmissionResultPayload.Rejected -> TradingAdmissionResult.Rejected(response.reason.name, response.insufficientBalance)
            }
        complete(pending, result)
    }

    /** Pending의 종료는 외부에서 취소할 수 있는 future 상태와 별개다. worker만 한 번 기록한다. */
    private fun complete(
        pending: Pending,
        result: TradingAdmissionResult,
    ) {
        if (pending.terminalRecorded) return
        startCompletion(pending)
        pending.terminalRecorded = true
        val readyAt = System.nanoTime()
        pending.resultReadyAt = readyAt
        recordDuration(pending, GatewayMetrics.Phase.RESULT_READY, readyAt - pending.enqueuedAt)
        metrics.result(
            when (result) {
                is TradingAdmissionResult.Accepted -> {
                    GatewayMetrics.Result.ACCEPTED
                }

                is TradingAdmissionResult.Rejected -> {
                    GatewayMetrics.Result.REJECTED
                }

                is TradingAdmissionResult.InvalidInput -> {
                    GatewayMetrics.Result.INVALID
                }

                is TradingAdmissionResult.Unknown -> {
                    when {
                        result.timedOut -> GatewayMetrics.Result.UNKNOWN_TIMEOUT
                        result.reason == "GATEWAY_CLOSED" -> GatewayMetrics.Result.UNKNOWN_CLOSED
                        else -> GatewayMetrics.Result.UNKNOWN_CONNECTION
                    }
                }

                is TradingAdmissionResult.NotSubmitted -> {
                    error("예약된 Pending은 미제출 결과로 완료하지 않습니다")
                }
            },
        )
        // 완료 callback만 넘긴다. 슬롯이 없으면 기존 동기 처리로 결과를 보존한다.
        if (!completionPermits.tryAcquire()) {
            completeFuture(pending, result)
            return
        }
        try {
            completionExecutor.execute {
                try {
                    completeFuture(pending, result)
                } finally {
                    completionPermits.release()
                }
            }
        } catch (_: RejectedExecutionException) {
            completionPermits.release()
            completeFuture(pending, result)
        }
    }

    private fun completeFuture(
        pending: Pending,
        result: TradingAdmissionResult,
    ) {
        completionContext.set(true)
        try {
            val completionStartedAt = System.nanoTime()
            try {
                pending.result.complete(result)
            } finally {
                val completedAt = System.nanoTime()
                recordDuration(pending, GatewayMetrics.Phase.FUTURE_COMPLETION, completedAt - completionStartedAt)
                pending.phaseDurations?.let { durations ->
                    metrics.requestCompleted(
                        GatewayRequestTiming(
                            pending.submission.order.userId.value,
                            pending.submission.requestId,
                            pending.submission.order.orderId.value,
                            pending.enqueuedAt,
                            pending.resultReadyAt,
                            completedAt,
                            GatewayMetrics.Phase.entries
                                .filter { durations[it.ordinal] >= 0 }
                                .associate { it.tag to durations[it.ordinal] },
                            admissionSequence = (result as? TradingAdmissionResult.Accepted)?.sequence,
                        ),
                    )
                }
            }
        } finally {
            metrics.completionFinished()
            completionContext.remove()
        }
    }

    /** worker의 마지막 완료 제출 뒤 호출한다. daemon 작업 때문에 종료를 무한히 기다리지 않는다. */
    private fun stopCompletions(): Boolean {
        completionExecutor.shutdown()
        try {
            if (!completionExecutor.awaitTermination(settings.startupTimeoutMs + 1_000, TimeUnit.MILLISECONDS)) {
                val failure = IllegalStateException("Aeron 주문 완료 callback의 종료 대기 시간이 초과됐습니다")
                shutdownFailure.compareAndSet(null, failure)
                completionExecutor.shutdownNow()
                completionExecutor.awaitTermination(250, TimeUnit.MILLISECONDS)
                logger.warn(failure.message)
            }
        } catch (error: InterruptedException) {
            shutdownFailure.compareAndSet(null, error)
            completionExecutor.shutdownNow()
            Thread.currentThread().interrupt()
            logger.warn("Aeron 주문 완료 callback의 종료 대기가 중단됐습니다", error)
        }
        return completionExecutor.isTerminated
    }

    private fun recordDuration(
        pending: Pending,
        phase: GatewayMetrics.Phase,
        elapsedNs: Long,
    ) {
        pending.phaseDurations?.set(phase.ordinal, elapsedNs)
        metrics.duration(phase, elapsedNs)
    }

    private fun startCompletion(pending: Pending) {
        if (!pending.completionTracked) {
            pending.completionTracked = true
            metrics.completionStarted()
        }
    }

    private fun disconnect(result: TradingAdmissionResult.Unknown) {
        val pending =
            lock.withLock {
                available = false
                requests.values.toList()
            }
        pending.forEach { complete(it, result) }
    }

    private fun clearDisconnected() {
        lock.withLock {
            requests.clear()
            queue.clear()
            inFlight.clear()
            queuedCount.set(0)
            metrics.sizes(0, 0, 0)
        }
    }

    override fun close() {
        lock.withLock {
            closing = true
            available = false
        }
        // 완료 callback이 close를 불러도 worker의 executor drain과 서로 기다리지 않는다.
        if (Thread.currentThread() !== worker && completionContext.get() != true) {
            worker.join(settings.startupTimeoutMs + 2_000)
            check(!worker.isAlive) { "Aeron HTTP gateway 연결 소유 스레드가 종료되지 않았습니다" }
            shutdownFailure.get()?.let { throw IllegalStateException("Aeron HTTP gateway 완료 처리를 종료하지 못했습니다", it) }
        }
    }

    private fun key(submission: TradingOrderSubmission): RequestKey = RequestKey(submission.order.userId.value, submission.requestId)

    private fun completed(result: TradingAdmissionResult): CompletableFuture<TradingAdmissionResult> =
        CompletableFuture.completedFuture(result)
}
