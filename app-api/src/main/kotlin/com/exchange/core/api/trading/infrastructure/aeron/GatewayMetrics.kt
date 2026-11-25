package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.Publication
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.noop.NoopCounter
import io.micrometer.core.instrument.noop.NoopGauge
import io.micrometer.core.instrument.noop.NoopTimer
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 같은 Gateway JVM의 시각과 고정 구간만 전달한다. 식별자는 metric tag로 쓰지 않는다. */
internal data class GatewayRequestTiming(
    val userId: String,
    val requestId: String,
    val orderId: String,
    val enqueuedNs: Long,
    val resultReadyNs: Long,
    val completedNs: Long,
    val phaseDurationsNs: Map<String, Long>,
    val admissionSequence: Long? = null,
)

internal fun interface GatewayRequestTimingObserver {
    fun onRequestCompleted(timing: GatewayRequestTiming)
}

/** 고정된 운영 지표만 기록한다. 요청 식별자와 업무 시계/수명은 소유하지 않는다. */
internal class GatewayMetrics(
    private val registry: MeterRegistry? = null,
    private val requestObserver: GatewayRequestTimingObserver? = null,
) : AutoCloseable {
    val observesRequests: Boolean get() = requestObserver != null

    fun requestCompleted(timing: GatewayRequestTiming) {
        val observer = requestObserver ?: return
        safely { observer.onRequestCompleted(timing) }
    }

    enum class Phase(
        val tag: String,
    ) {
        SUBMIT_LOCK("submit_lock"),
        QUEUE("queue"),
        ENCODE("encode"),
        OFFER_WAIT("offer_wait"),
        RESPONSE_WAIT("response_wait"),
        RESULT_READY("result_ready"),
        FUTURE_COMPLETION("future_completion"),
    }

    enum class Submission(
        val tag: String,
    ) {
        NEW("new"),
        REUSED("reused"),
        CONFLICT("conflict"),
        UNAVAILABLE("unavailable"),
        QUEUE_FULL("queue_full"),
    }

    enum class Result(
        val tag: String,
    ) {
        ACCEPTED("accepted"),
        REJECTED("rejected"),
        INVALID("invalid"),
        UNKNOWN_TIMEOUT("unknown_timeout"),
        UNKNOWN_CONNECTION("unknown_connection"),
        UNKNOWN_CLOSED("unknown_closed"),
    }

    private enum class Offer(
        val tag: String,
    ) {
        ACCEPTED("accepted"),
        BACK_PRESSURED("back_pressured"),
        ADMIN_ACTION("admin_action"),
        NOT_CONNECTED("not_connected"),
        CLOSED("closed"),
        MAX_POSITION_EXCEEDED("max_position_exceeded"),
        OTHER("other"),
        EXCEPTION("exception"),
    }

    private val logger = LoggerFactory.getLogger(GatewayMetrics::class.java)
    private val failed = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val queued = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val reservedKeys = AtomicInteger()
    private val activeCompletions = AtomicInteger()
    private val degraded = AtomicInteger()
    private val gauges = mutableListOf<Gauge>()
    private var timers: List<Timer> = emptyList()
    private var submissions: List<Counter> = emptyList()
    private var results: List<Counter> = emptyList()
    private var offers: List<Counter> = emptyList()

    val isOperational: Boolean
        get() = registry != null && !failed.get() && !closed.get()

    init {
        if (registry != null) {
            safely {
                // 같은 registry에서 이전 인스턴스를 가리키는 Gauge를 조용히 재사용하지 않는다.
                val gaugeStates =
                    listOf(
                        "queued" to queued,
                        "inflight" to inFlight,
                        "reserved_keys" to reservedKeys,
                        "active_completions" to activeCompletions,
                        "degraded" to degraded,
                    )
                check(gaugeStates.none { (suffix, _) -> registry.find("$PREFIX.$suffix").gauge() != null }) {
                    "Gateway Gauge가 이미 등록되어 있습니다"
                }
                gaugeStates.forEach { (suffix, state) ->
                    val gauge = Gauge.builder("$PREFIX.$suffix", state) { it.get().toDouble() }.register(registry)
                    gauges += gauge
                    check(gauge !is NoopGauge) { "Gateway Gauge가 비활성화되어 있습니다" }
                }
                timers = Phase.entries.map { Timer.builder(DURATION).tag("phase", it.tag).register(registry) }
                submissions = Submission.entries.map { Counter.builder(SUBMISSIONS).tag("outcome", it.tag).register(registry) }
                results = Result.entries.map { Counter.builder(RESULTS).tag("outcome", it.tag).register(registry) }
                offers = Offer.entries.map { Counter.builder(OFFERS).tag("status", it.tag).register(registry) }
                check(timers.none { it is NoopTimer } && (submissions + results + offers).none { it is NoopCounter }) {
                    "Gateway Timer/Counter가 비활성화되어 있습니다"
                }
            }
        }
    }

    /** 호출자는 System.nanoTime의 같은 원점에서 구한 경과 시간만 전달한다. */
    fun duration(
        phase: Phase,
        elapsedNs: Long,
    ) {
        safely { timers[phase.ordinal].record(elapsedNs, TimeUnit.NANOSECONDS) }
    }

    fun submission(outcome: Submission) {
        safely { submissions[outcome.ordinal].increment() }
    }

    fun result(outcome: Result) {
        safely { results[outcome.ordinal].increment() }
    }

    fun offer(position: Long) {
        val outcome =
            when {
                position > 0 -> Offer.ACCEPTED
                position == Publication.BACK_PRESSURED -> Offer.BACK_PRESSURED
                position == Publication.ADMIN_ACTION -> Offer.ADMIN_ACTION
                position == Publication.NOT_CONNECTED -> Offer.NOT_CONNECTED
                position == Publication.CLOSED -> Offer.CLOSED
                position == Publication.MAX_POSITION_EXCEEDED -> Offer.MAX_POSITION_EXCEEDED
                else -> Offer.OTHER
            }
        safely { offers[outcome.ordinal].increment() }
    }

    fun offerException() {
        safely { offers[Offer.EXCEPTION.ordinal].increment() }
    }

    /** 연결 교체로 신규 제출을 막은 경계에서만 절대값을 재설정한다. */
    fun sizes(
        queued: Int,
        inFlight: Int,
        reservedKeys: Int,
    ) {
        this.queued.set(queued)
        this.inFlight.set(inFlight)
        this.reservedKeys.set(reservedKeys)
    }

    /** producer와 consumer의 증감을 합쳐 오래된 전체 snapshot이 현재값을 덮어쓰지 않게 한다. */
    fun adjustSizes(
        queued: Int = 0,
        inFlight: Int = 0,
        reservedKeys: Int = 0,
    ) {
        if (queued != 0) this.queued.addAndGet(queued)
        if (inFlight != 0) this.inFlight.addAndGet(inFlight)
        if (reservedKeys != 0) this.reservedKeys.addAndGet(reservedKeys)
    }

    fun completionStarted() {
        activeCompletions.incrementAndGet()
    }

    fun completionFinished() {
        activeCompletions.decrementAndGet()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Gauge의 이전 상태 참조를 해제한다. Timer/Counter는 같은 registry의 누적값을 유지한다.
        gauges.forEach { gauge ->
            try {
                registry?.remove(gauge)
            } catch (error: RuntimeException) {
                fail(error)
            }
        }
    }

    private inline fun safely(record: () -> Unit) {
        if (registry == null || failed.get() || closed.get()) return
        try {
            record()
        } catch (error: RuntimeException) {
            fail(error)
        }
    }

    private fun fail(error: RuntimeException) {
        degraded.set(1)
        if (failed.compareAndSet(false, true)) {
            logger.warn("Gateway 운영 계측을 중단합니다. 주문 전송과 결과 처리는 계속됩니다", error)
        }
    }

    companion object {
        const val PREFIX = "exchange.trading.gateway"
        const val DURATION = "$PREFIX.duration"
        const val SUBMISSIONS = "$PREFIX.submissions"
        const val RESULTS = "$PREFIX.results"
        const val OFFERS = "$PREFIX.offers"
    }
}
