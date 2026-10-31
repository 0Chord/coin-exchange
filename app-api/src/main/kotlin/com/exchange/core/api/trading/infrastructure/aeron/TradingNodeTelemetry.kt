package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.trading.TradingExecutionObserver
import com.exchange.core.trading.TradingExecutionPhase
import io.aeron.Publication
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.noop.NoopCounter
import io.micrometer.core.instrument.noop.NoopGauge
import io.micrometer.core.instrument.noop.NoopTimer
import org.agrona.concurrent.NanoClock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal enum class TradingNodePhase(
    val tag: String,
) {
    CALLBACK("callback"),
    DECODE("decode"),
    FUNDING_APPLY("funding_apply"),
    ADMISSION_CONTEXT("admission_context"),
    ADMISSION_APPLY("admission_apply"),
    EXECUTION_ENCODE("execution_encode"),
    EXECUTION_OFFER("execution_offer"),
    REPLY_ENCODE("reply_encode"),
    REPLY_OFFER("reply_offer"),
    EXECUTION_TOTAL("execution_total"),
    MATCHING_CANDIDATE("matching_candidate"),
    SETTLEMENT_INPUT_VALIDATION("settlement_input_validation"),
    SETTLEMENT_CANDIDATE("settlement_candidate"),
    RESULT_VALIDATION("result_validation"),
    EXECUTION_APPLY("execution_apply"),
    COMPLETION("completion"),
}

internal enum class TradingNodeCallbackKind { UNKNOWN, FUNDING, ADMISSION, EXECUTION, MALFORMED }

/** 이 record의 모든 시간은 한 노드 JVM 안의 경과다. publication position과 cluster log position은 별개다. */
internal data class TradingNodeCallbackTelemetry(
    val kind: TradingNodeCallbackKind,
    val userId: String?,
    val requestId: String?,
    val admissionSequence: Long?,
    val logPosition: Long,
    val role: String,
    val phaseDurationsNs: LongArray,
    val executionOfferAttempts: Int,
    val executionBackPressured: Int,
    val executionAdminActions: Int,
    val executionOfferPosition: Long?,
    val replyAttempts: Int,
    val replyBackPressured: Int,
    val replyAdminActions: Int,
    val replyOfferPosition: Long?,
    val replyStatus: String?,
    val failed: Boolean,
    val timingErrors: Int,
    /** 같은 노드 CnC frame(originNs + elapsedNs)과만 연결한다. JVM 간 지연 계산용이 아니다. */
    val callbackStartedNs: Long? = null,
    val callbackCompletedNs: Long? = null,
)

/** 단일 서비스 스레드의 선택적 관측자다. 구현은 업무 상태를 변경하거나 동기 I/O를 해서는 안 된다. */
internal fun interface TradingNodeTelemetry {
    fun record(value: TradingNodeCallbackTelemetry)

    val isOperational: Boolean get() = true
    val failureCount: Long get() = 0

    companion object {
        val NONE = TradingNodeTelemetry { }

        fun compose(
            first: TradingNodeTelemetry,
            second: TradingNodeTelemetry,
        ): TradingNodeTelemetry =
            object : TradingNodeTelemetry, CompletedCaptureSink {
                private var recordingFailures = 0L
                override val isOperational: Boolean get() = recordingFailures == 0L && first.isOperational && second.isOperational
                override val failureCount: Long get() = recordingFailures + first.failureCount + second.failureCount

                override fun recordCompleted(
                    capture: TradingNodeCallbackCapture,
                    position: Long,
                    role: String,
                    replyStatus: String?,
                ) {
                    try {
                        first.recordCompleted(capture, position, role, replyStatus)
                    } catch (_: Exception) {
                        recordingFailures++
                    }
                    try {
                        second.recordCompleted(capture, position, role, replyStatus)
                    } catch (_: Exception) {
                        recordingFailures++
                    }
                }

                override fun record(value: TradingNodeCallbackTelemetry) {
                    // 제품과 다음 observer를 유지하되 실패를 유효한 계측으로 숨기지 않는다.
                    try {
                        first.record(value)
                    } catch (_: Exception) {
                        recordingFailures++
                    }
                    try {
                        second.record(value)
                    } catch (_: Exception) {
                        recordingFailures++
                    }
                }
            }
    }
}

/** 완료된 capture는 호출 중에만 빌려 쓴다. 외부 observer에는 독립된 record를 전달한다. */
private interface CompletedCaptureSink {
    fun recordCompleted(
        capture: TradingNodeCallbackCapture,
        position: Long,
        role: String,
        replyStatus: String?,
    )
}

internal fun TradingNodeTelemetry.recordCompleted(
    capture: TradingNodeCallbackCapture,
    position: Long,
    role: String,
    replyStatus: String?,
) {
    if (this is CompletedCaptureSink) {
        recordCompleted(capture, position, role, replyStatus)
    } else {
        record(capture.finish(position, role, replyStatus))
    }
}

/** 고정된 phase만 태그로 사용한다. 요청 ID·sequence·position을 운영 metric label로 보관하지 않는다. */
internal class TradingNodeMetrics(
    registry: MeterRegistry,
    asynchronous: Boolean = false,
    queueCapacity: Int = 4096,
    private val dropOnSaturation: Boolean = false,
    onFatal: (Error) -> Unit = {},
) : TradingNodeTelemetry,
    CompletedCaptureSink,
    AutoCloseable {
    private val degraded = AtomicInteger()
    private val recordingFailures = AtomicLong()
    private val droppedSamples = AtomicLong()
    private var timers: List<Timer> = emptyList()
    private var callbacks: Map<TradingNodeCallbackKind, Counter> = emptyMap()
    private var failures: Counter? = null
    private var executionAttempts: Counter? = null
    private var replyAttempts: Counter? = null
    private var handoffFallbacks: Counter? = null

    init {
        try {
            val gauge = Gauge.builder("$PREFIX.degraded", degraded) { it.get().toDouble() }.register(registry)
            Gauge.builder("$PREFIX.dropped_samples", droppedSamples) { it.get().toDouble() }.register(registry)
            failures = Counter.builder("$PREFIX.observation_failures").register(registry)
            timers =
                TradingNodePhase.entries.map { phase ->
                    Timer.builder(DURATION).tag("phase", phase.tag).register(registry)
                }
            callbacks =
                TradingNodeCallbackKind.entries.associateWith { kind ->
                    Counter.builder(CALLBACKS).tag("kind", kind.name.lowercase()).register(registry)
                }
            executionAttempts = Counter.builder("$PREFIX.offer_attempts").tag("target", "execution").register(registry)
            replyAttempts = Counter.builder("$PREFIX.offer_attempts").tag("target", "reply").register(registry)
            handoffFallbacks = Counter.builder("$PREFIX.handoff_fallbacks").register(registry)
            check(
                gauge !is NoopGauge && timers.none { it is NoopTimer } &&
                    (
                        callbacks.values +
                            listOfNotNull(
                                failures,
                                executionAttempts,
                                replyAttempts,
                                handoffFallbacks,
                            )
                    ).none { it is NoopCounter },
            ) {
                "노드 운영 지표가 비활성화되어 있습니다"
            }
        } catch (_: RuntimeException) {
            markFailure()
        }
    }

    private val pendingFatal = AtomicReference<Error?>()
    private val handoff =
        if (asynchronous) {
            TradingNodeMetricHandoff(queueCapacity, ::recordDurations, ::markFailure) { error ->
                pendingFatal.compareAndSet(null, error)
                onFatal(error)
            }
        } else {
            null
        }

    override val isOperational: Boolean get() = degraded.get() == 0
    override val failureCount: Long get() = recordingFailures.get()

    /** 운영 지표 내보내기를 위한 읽기 장벽이다. 측정 대상 처리가 모두 끝난 뒤 호출한다. */
    fun awaitRecorded() {
        pendingFatal.get()?.let { throw it }
        try {
            handoff?.awaitRecorded()
        } catch (failure: Throwable) {
            pendingFatal.get()?.let { throw it }
            throw failure
        }
        pendingFatal.get()?.let { throw it }
    }

    override fun close() {
        try {
            handoff?.close()
        } catch (failure: Throwable) {
            pendingFatal.get()?.let { throw it }
            throw failure
        }
        pendingFatal.get()?.let { throw it }
    }

    override fun record(value: TradingNodeCallbackTelemetry) {
        awaitRecorded()
        recordDurations(value.kind, value.phaseDurationsNs, value.executionOfferAttempts, value.replyAttempts, value.timingErrors)
    }

    override fun recordCompleted(
        capture: TradingNodeCallbackCapture,
        position: Long,
        role: String,
        replyStatus: String?,
    ) {
        pendingFatal.get()?.let { throw it }
        val queue = handoff
        if (queue == null) {
            recordDurations(
                capture.kind,
                capture.durationsForMetrics(),
                capture.executionOfferAttempts,
                capture.replyAttempts,
                capture.timingErrors,
            )
        } else {
            val queued =
                queue.offer(
                    capture.kind,
                    capture.durationsForMetrics(),
                    capture.executionOfferAttempts,
                    capture.replyAttempts,
                    capture.timingErrors,
                )
            if (!queued) {
                if (dropOnSaturation) {
                    // 선택적 모니터링 때문에 금융 서비스가 느린 exporter를 기다리며 멈추어서는 안 된다.
                    // 손실 Gauge를 명시해 수집한 표본을 누락 없는 증거로 오해하지 않게 한다.
                    droppedSamples.incrementAndGet()
                    degraded.set(1)
                    return
                }
                // Micrometer meter는 스레드 안전하므로 모니터링 데이터를 버리지 않고 모든 표본을 기록한다.
                // 뒤에 발생한 지표 기록 실패가 이미 접수한 앞부분의 처리를 앞서서는 안 된다.
                try {
                    awaitRecorded()
                } catch (failure: Exception) {
                    markFailure()
                    throw failure
                }
                try {
                    handoffFallbacks?.increment()
                } catch (_: RuntimeException) {
                    markFailure()
                }
                recordDurations(
                    capture.kind,
                    capture.durationsForMetrics(),
                    capture.executionOfferAttempts,
                    capture.replyAttempts,
                    capture.timingErrors,
                )
            }
        }
    }

    private fun recordDurations(
        kind: TradingNodeCallbackKind,
        durationsNs: LongArray,
        executionOfferAttempts: Int,
        replyOfferAttempts: Int,
        timingErrors: Int,
    ) {
        try {
            // 큐 포화로 생긴 손실은 degraded에 남기되 이미 수락했거나 이후 도착한 정상 표본은 기록한다.
            // 실제 시계·meter 기록 실패가 있을 때만 기존처럼 후속 기록을 중단한다.
            if (recordingFailures.get() != 0L) return
            var ordinal = 0
            while (ordinal < durationsNs.size) {
                val duration = durationsNs[ordinal]
                if (duration >= 0) timers[ordinal].record(duration, TimeUnit.NANOSECONDS)
                ordinal++
            }
            callbacks.getValue(kind).increment()
            executionAttempts?.increment(executionOfferAttempts.toDouble())
            replyAttempts?.increment(replyOfferAttempts.toDouble())
            if (timingErrors > 0) {
                recordingFailures.addAndGet(timingErrors.toLong())
                failures?.increment(timingErrors.toDouble())
                degraded.set(1)
            }
        } catch (_: RuntimeException) {
            markFailure()
        }
    }

    private fun markFailure() {
        recordingFailures.incrementAndGet()
        degraded.set(1)
        try {
            failures?.increment()
        } catch (_: RuntimeException) {
        }
    }

    companion object {
        const val PREFIX = "exchange.trading.node"
        const val DURATION = "$PREFIX.duration"
        const val CALLBACKS = "$PREFIX.callbacks"
    }
}

/**
 * 서비스 작성자 한 개가 기본 타입 값으로 채운 슬롯을 게시하고, worker 한 개가 원래 meter를 호출한다.
 * 용량은 고정한다. 전달 큐가 가득 찼거나 닫혔으면 false를 반환해 producer가 같은 표본을 동기로 기록한다.
 * 요청 식별자·금융 상태·callback 객체는 보관하지 않는다.
 */
internal class TradingNodeMetricHandoff(
    private val capacity: Int,
    private val record: (TradingNodeCallbackKind, LongArray, Int, Int, Int) -> Unit,
    private val failed: () -> Unit,
    private val fatal: (Error) -> Unit,
) : AutoCloseable {
    private class Slot {
        var kind = TradingNodeCallbackKind.UNKNOWN
        val durations = LongArray(TradingNodePhase.entries.size)
        var executionAttempts = 0
        var replyAttempts = 0
        var timingErrors = 0
    }

    private val slots: Array<Slot>
    private val published = AtomicLong()
    private val consumed = AtomicLong()
    private val closing = AtomicBoolean()
    private val terminated = AtomicBoolean()
    private val producer = AtomicReference<Thread?>()
    private val producerActive = AtomicInteger()

    // 바인딩한 producer만 이 값을 쓴다. 원자적 게시 구간은 다른 스레드에서도 계속 볼 수 있다.
    private var producerPosition = 0L
    private var producerSlotIndex = 0
    private val worker: Thread

    init {
        require(capacity in 1..65_536)
        slots = Array(capacity) { Slot() }
        worker = Thread(::consume, "trading-node-metrics").apply { isDaemon = true }
        worker.start()
    }

    fun offer(
        kind: TradingNodeCallbackKind,
        durations: LongArray,
        executionAttempts: Int,
        replyAttempts: Int,
        timingErrors: Int,
    ): Boolean {
        val current = Thread.currentThread()
        var owner = producer.get()
        if (owner == null) {
            producer.compareAndSet(null, current)
            owner = producer.get()
        }
        if (owner !== current || !producerActive.compareAndSet(0, 1)) {
            return false
        }
        try {
            if (closing.get() || terminated.get()) return false
            val next = producerPosition
            if (next - consumed.get() >= capacity) return false
            val slot = slots[producerSlotIndex]
            durations.copyInto(slot.durations)
            slot.kind = kind
            slot.executionAttempts = executionAttempts
            slot.replyAttempts = replyAttempts
            slot.timingErrors = timingErrors
            published.lazySet(next + 1)
            producerPosition = next + 1
            producerSlotIndex++
            if (producerSlotIndex == capacity) producerSlotIndex = 0
            // 이미 실행 중인 consumer는 시스템 호출 없이 published를 관측한다.
            if (next == consumed.get()) LockSupport.unpark(worker)
            return true
        } finally {
            producerActive.set(0)
        }
    }

    fun awaitRecorded() {
        val target = published.get()
        val started = System.nanoTime()
        while (consumed.get() < target) {
            check(!terminated.get() && System.nanoTime() - started < WAIT_NS) { "노드 메트릭 기록을 완료하지 못했습니다" }
            LockSupport.unpark(worker)
            LockSupport.parkNanos(10_000L)
        }
    }

    private fun consume() {
        var next = 0L
        var slotIndex = 0
        try {
            while (true) {
                if (next < published.get()) {
                    val slot = slots[slotIndex]
                    record(slot.kind, slot.durations, slot.executionAttempts, slot.replyAttempts, slot.timingErrors)
                    next++
                    slotIndex++
                    if (slotIndex == capacity) slotIndex = 0
                    consumed.lazySet(next)
                } else {
                    // 위에서 빈 상태를 읽은 뒤 마지막 producer가 게시할 수 있다. 닫기를 통해 새 게시를 막고
                    // 이미 진입한 작성자까지 빠져나간 다음에만 게시된 앞부분을 다시 읽는다.
                    if (closing.get() && producerActive.get() == 0 && consumed.get() == published.get()) return
                    LockSupport.parkNanos(100_000L)
                }
            }
        } catch (error: Error) {
            try {
                failed()
            } catch (markingFailure: Throwable) {
                if (markingFailure !== error) error.addSuppressed(markingFailure)
            }
            fatal(error)
        } catch (_: Exception) {
            failed()
        } finally {
            terminated.set(true)
        }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        LockSupport.unpark(worker)
        worker.join(TimeUnit.NANOSECONDS.toMillis(WAIT_NS))
        check(!worker.isAlive && consumed.get() == published.get()) { "노드 메트릭 worker 종료 또는 drain을 완료하지 못했습니다" }
    }

    private companion object {
        val WAIT_NS = TimeUnit.SECONDS.toNanos(5)
    }
}

/** 서비스 스레드에서 재사용한다. 보관하는 observer에만 독립된 record와 duration 배열을 만든다. */
internal class TradingNodeCallbackCapture(
    private val nanoTime: NanoClock,
) : TradingExecutionObserver {
    private val starts = LongArray(TradingNodePhase.entries.size)
    private val active = BooleanArray(TradingNodePhase.entries.size)
    private val durations = LongArray(TradingNodePhase.entries.size) { -1 }
    var kind = TradingNodeCallbackKind.UNKNOWN
    var userId: String? = null
    var requestId: String? = null
    var sequence: Long? = null
    var executionOfferAttempts = 0
    var executionBackPressured = 0
    var executionAdminActions = 0
    var executionOfferPosition: Long? = null
    var replyAttempts = 0
    var replyBackPressured = 0
    var replyAdminActions = 0
    var replyOfferPosition: Long? = null
    var failed = false
    var timingErrors = 0
        private set
    private var clockReadable = true
    private var callbackStartedNs = 0L
    private var callbackCompletedNs = 0L
    private var callbackStartReadable = false
    private var callbackEndReadable = false
    private var completed = false

    init {
        begin(TradingNodePhase.CALLBACK)
    }

    fun reset() {
        active.fill(false)
        durations.fill(-1)
        kind = TradingNodeCallbackKind.UNKNOWN
        userId = null
        requestId = null
        sequence = null
        executionOfferAttempts = 0
        executionBackPressured = 0
        executionAdminActions = 0
        executionOfferPosition = null
        replyAttempts = 0
        replyBackPressured = 0
        replyAdminActions = 0
        replyOfferPosition = null
        failed = false
        timingErrors = 0
        clockReadable = true
        callbackStartReadable = false
        callbackEndReadable = false
        completed = false
        begin(TradingNodePhase.CALLBACK)
    }

    fun complete() {
        if (completed) return
        end(TradingNodePhase.CALLBACK)
        completed = true
    }

    internal fun durationsForMetrics(): LongArray = durations

    fun begin(phase: TradingNodePhase) {
        val now = readClock()
        if (!clockReadable) return
        starts[phase.ordinal] = now
        active[phase.ordinal] = true
        if (phase == TradingNodePhase.CALLBACK) {
            callbackStartedNs = now
            callbackStartReadable = true
        }
    }

    fun end(phase: TradingNodePhase) {
        if (!active[phase.ordinal]) return
        val start = starts[phase.ordinal]
        active[phase.ordinal] = false
        val end = readClock()
        if (!clockReadable) return
        if (phase == TradingNodePhase.CALLBACK) {
            callbackCompletedNs = end
            callbackEndReadable = true
        }
        val elapsed = end - start
        if (elapsed < 0) {
            timingErrors++
        } else {
            durations[phase.ordinal] = if (durations[phase.ordinal] < 0) elapsed else durations[phase.ordinal] + elapsed
        }
    }

    fun executionOffer(position: Long) {
        when (position) {
            Publication.BACK_PRESSURED -> executionBackPressured++
            Publication.ADMIN_ACTION -> executionAdminActions++
            else -> if (position > 0) executionOfferPosition = position
        }
    }

    fun replyOffer(position: Long) {
        when (position) {
            Publication.BACK_PRESSURED -> replyBackPressured++
            Publication.ADMIN_ACTION -> replyAdminActions++
            else -> if (position > 0) replyOfferPosition = position
        }
    }

    override fun onPhaseStarted(
        admissionSequence: Long,
        phase: TradingExecutionPhase,
    ) {
        if (sequence != admissionSequence) timingErrors++
        begin(phase.nodePhase())
    }

    override fun onPhaseCompleted(
        admissionSequence: Long,
        phase: TradingExecutionPhase,
        failure: Throwable?,
    ) {
        end(phase.nodePhase())
    }

    fun finish(
        position: Long,
        role: String,
        replyStatus: String?,
    ): TradingNodeCallbackTelemetry {
        complete()
        return TradingNodeCallbackTelemetry(
            kind,
            userId,
            requestId,
            sequence,
            position,
            role,
            durations.copyOf(),
            executionOfferAttempts,
            executionBackPressured,
            executionAdminActions,
            executionOfferPosition,
            replyAttempts,
            replyBackPressured,
            replyAdminActions,
            replyOfferPosition,
            replyStatus,
            failed,
            timingErrors,
            if (callbackStartReadable) callbackStartedNs else null,
            if (callbackEndReadable) callbackCompletedNs else null,
        )
    }

    private fun readClock(): Long {
        clockReadable = true
        return try {
            nanoTime.nanoTime()
        } catch (_: Exception) {
            clockReadable = false
            timingErrors++
            0
        }
    }

    private fun TradingExecutionPhase.nodePhase(): TradingNodePhase =
        when (this) {
            TradingExecutionPhase.MATCHING_CANDIDATE -> TradingNodePhase.MATCHING_CANDIDATE
            TradingExecutionPhase.SETTLEMENT_INPUT_VALIDATION -> TradingNodePhase.SETTLEMENT_INPUT_VALIDATION
            TradingExecutionPhase.SETTLEMENT_CANDIDATE -> TradingNodePhase.SETTLEMENT_CANDIDATE
            TradingExecutionPhase.RESULT_VALIDATION -> TradingNodePhase.RESULT_VALIDATION
            TradingExecutionPhase.APPLY -> TradingNodePhase.EXECUTION_APPLY
            TradingExecutionPhase.COMPLETION -> TradingNodePhase.COMPLETION
        }
}
