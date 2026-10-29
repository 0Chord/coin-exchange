package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.TradingGatewayApplication
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.distribution.pause.PauseDetector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.LongAdder
import kotlin.math.ceil

internal const val HTTP_GATEWAY_METRIC_PREFIX = "exchange.trading.gateway"

/** Keep correctly identified evidence before rejecting an invalid observation window. */
internal fun consumeHttpPerformanceGatewayWindow(
    result: JsonNode,
    runId: String,
    action: String,
    detailedTracing: Boolean,
    preserveEvidence: (JsonNode) -> Unit,
): JsonNode {
    require(action == "open" || action == "close")
    check(
        result.path("runId").asString() == runId && result.path("token").asString() == runId &&
            result.path("action").asString() == action,
    ) { "Gateway 계측 파일 식별 오류" }
    preserveEvidence(result)
    check(result.path("status").asString() == if (action == "open") "OPEN" else "CLOSED") {
        "Gateway 계측 창 오류: ${result.path("errors") }"
    }
    check(result.path("errors").isEmpty && result.path("detailedTracing").asBoolean() == detailedTracing) {
        "Gateway 계측 모드/오류 불일치"
    }
    return result
}

internal val HTTP_GATEWAY_TIMER_PHASES =
    listOf("submit_lock", "queue", "encode", "offer_wait", "response_wait", "result_ready", "future_completion")
internal val HTTP_GATEWAY_GAUGES = listOf("queued", "inflight", "reserved_keys", "active_completions", "degraded")
private val gatewayCounterTags =
    mapOf(
        "submissions" to ("outcome" to listOf("new", "reused", "conflict", "unavailable", "queue_full")),
        "results" to ("outcome" to listOf("accepted", "rejected", "invalid", "unknown_timeout", "unknown_connection", "unknown_closed")),
        "offers" to
            (
                "status" to
                    listOf(
                        "accepted",
                        "back_pressured",
                        "admin_action",
                        "not_connected",
                        "closed",
                        "max_position_exceeded",
                        "other",
                        "exception",
                    )
            ),
    )

/** 실제 SimpleTimer에 전달하면서 테스트 창 안의 구간·요청 원자료를 유한하게 보존한다. */
internal class HttpPerformanceGatewayRegistry(
    private val detailedTracing: Boolean = true,
    private val sampleCapacity: Int = 8192,
    private val frameCapacity: Int = 4096,
    private val nanoTime: () -> Long = System::nanoTime,
) : SimpleMeterRegistry(),
    Closeable,
    GatewayRequestTimingObserver {
    private class Phase(
        capacity: Int,
    ) {
        val count = LongAdder()
        val totalNs = LongAdder()
        val rawCount = AtomicLong()
        val raw = AtomicLongArray(capacity)
        val ready = AtomicIntegerArray(capacity)
    }

    private class Errors {
        private val values = AtomicReferenceArray<String>(64)
        private val overflow = AtomicBoolean()

        fun add(error: String) {
            for (index in 0 until values.length()) {
                val existing = values.get(index)
                if (existing == error) return
                if (existing == null && values.compareAndSet(index, null, error)) return
                if (values.get(index) == error) return
            }
            overflow.set(true)
        }

        fun toList(): List<String> =
            (0 until values.length()).mapNotNull { values.get(it) } +
                if (overflow.get()) listOf("ERROR_DETAIL_OVERFLOW") else emptyList()
    }

    private class Window(
        val token: String,
        val openedNs: Long,
        val before: Map<String, Any>,
        sampleCapacity: Int,
        frameCapacity: Int,
        requestCapacity: Int,
    ) {
        val phases = HTTP_GATEWAY_TIMER_PHASES.associateWith { Phase(sampleCapacity) }
        val frames = AtomicReferenceArray<Map<String, Any>>(frameCapacity)
        val frameCount = AtomicLong()
        val requestTimings = AtomicReferenceArray<GatewayRequestTiming>(requestCapacity)
        val requestTimingCount = AtomicLong()
        val errors = Errors()

        // The sign bit seals admission; the remaining bits retain in-progress writers even after a timeout.
        val recording = AtomicInteger()
        val overflowCount = LongAdder()
    }

    // Control operations may serialize; request/timer admission uses the window's atomic close fence.
    private val observationLock = Any()
    private val window = AtomicReference<Window?>()

    init {
        require(sampleCapacity in 1..8192 && frameCapacity in 1..4096)
    }

    override fun newTimer(
        id: Meter.Id,
        distributionStatisticConfig: DistributionStatisticConfig,
        pauseDetector: PauseDetector,
    ): Timer {
        val actual = super.newTimer(id, distributionStatisticConfig, pauseDetector)
        if (id.name != "$HTTP_GATEWAY_METRIC_PREFIX.duration") return actual
        val phase = id.getTag("phase")
        return object : Timer by actual {
            override fun record(
                amount: Long,
                unit: TimeUnit,
            ) {
                val observing = acquireWindow()
                try {
                    actual.record(amount, unit)
                    if (observing != null) {
                        val value = observing.phases[phase]
                        if (value == null || amount < 0) {
                            observing.errors.add("INVALID_TIMER_SAMPLE:$phase")
                        } else {
                            val elapsed = unit.toNanos(amount)
                            value.count.increment()
                            value.totalNs.add(elapsed)
                            if (detailedTracing) {
                                val index = value.rawCount.getAndIncrement()
                                if (index < sampleCapacity) {
                                    value.raw.set(index.toInt(), elapsed)
                                    value.ready.set(index.toInt(), 1)
                                } else {
                                    observing.overflowCount.increment()
                                    observing.errors.add("TIMER_SAMPLE_OVERFLOW:$phase")
                                }
                            }
                        }
                    }
                } finally {
                    observing?.recording?.decrementAndGet()
                }
            }
        }
    }

    fun openWindow(token: String): Map<String, Any?> =
        synchronized(observationLock) {
            requireToken(token)
            check(window.get() == null) { "Gateway 관찰 창이 이미 열려 있습니다" }
            val gauges = readGauges()
            requireQuiescent(gauges)
            val before = actualMetrics()
            window.set(
                Window(
                    token,
                    nanoTime(),
                    before,
                    if (detailedTracing) sampleCapacity else 0,
                    frameCapacity,
                    if (detailedTracing) 2000 else 0,
                ),
            )
            response(token, "open", "OPEN", emptyList()) + mapOf("boundaryGauges" to gauges, "metricsBefore" to before)
        }

    fun captureGaugeFrame() {
        val active = acquireWindow() ?: return
        try {
            val elapsed = nanoTime() - active.openedNs
            if (elapsed < 0) active.errors.add("NEGATIVE_GAUGE_ELAPSED")
            val gauges = readGauges()
            val index = active.frameCount.getAndIncrement()
            if (index >= frameCapacity) {
                active.overflowCount.increment()
                active.errors.add("GAUGE_FRAME_OVERFLOW")
            } else {
                active.frames.set(index.toInt(), mapOf("elapsedNs" to elapsed, "values" to gauges))
            }
        } finally {
            active.recording.decrementAndGet()
        }
    }

    override fun onRequestCompleted(timing: GatewayRequestTiming) {
        if (!detailedTracing) return
        val active = acquireWindow() ?: return
        try {
            if (
                timing.resultReadyNs - timing.enqueuedNs < 0 || timing.completedNs - timing.resultReadyNs < 0 ||
                timing.phaseDurationsNs.any { (phase, elapsed) -> phase !in HTTP_GATEWAY_TIMER_PHASES || elapsed < 0 }
            ) {
                active.errors.add("INVALID_REQUEST_TIMING")
            }
            val captured = timing.copy(phaseDurationsNs = timing.phaseDurationsNs.toMap())
            val index = active.requestTimingCount.getAndIncrement()
            if (index >= active.requestTimings.length()) {
                active.overflowCount.increment()
                active.errors.add("REQUEST_TIMING_OVERFLOW")
            } else {
                active.requestTimings.set(index.toInt(), captured)
            }
        } finally {
            active.recording.decrementAndGet()
        }
    }

    private fun acquireWindow(): Window? {
        val active = window.get() ?: return null
        while (true) {
            val count = active.recording.get()
            if (count < 0) return null
            if (active.recording.compareAndSet(count, count + 1)) return active
        }
    }

    /** active_completions는 제품의 최종 Timer.record가 돌아온 뒤 0이 된다. */
    fun closeWindow(
        token: String,
        timeoutMs: Long = 10_000,
    ): Map<String, Any?> {
        requireToken(token)
        require(timeoutMs in 1..10_000)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val result =
                synchronized(observationLock) {
                    val active = checkNotNull(window.get()) { "Gateway 관찰 창이 열려 있지 않습니다" }
                    check(active.token == token) { "Gateway 관찰 창 식별자가 다릅니다" }
                    val gauges = readGauges()
                    val drained = isQuiescent(gauges) && active.recording.compareAndSet(0, Int.MIN_VALUE)
                    if (drained || System.nanoTime() - deadline >= 0) {
                        if (!drained) {
                            active.recording.getAndUpdate { it or Int.MIN_VALUE }
                            active.errors.add("GATEWAY_WINDOW_DRAIN_TIMEOUT")
                        }
                        window.set(null)
                        val after = actualMetrics()
                        finish(active, gauges, after)
                    } else {
                        null
                    }
                }
            if (result != null) return result
            Thread.sleep(5)
        }
    }

    private fun finish(
        active: Window,
        gauges: Map<String, Double>,
        after: Map<String, Any>,
    ): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val beforeTimers = active.before.getValue("timers") as Map<String, Map<String, Number>>

        @Suppress("UNCHECKED_CAST")
        val afterTimers = after.getValue("timers") as Map<String, Map<String, Number>>
        val timers =
            active.phases.mapValues { (phase, captured) ->
                val before = beforeTimers.getValue(phase)
                val current = afterTimers.getValue(phase)
                val count = current.getValue("count").toLong() - before.getValue("count").toLong()
                val total = current.getValue("totalNs").toDouble() - before.getValue("totalNs").toDouble()
                val capturedCount = captured.count.sum()
                if (count != capturedCount || kotlin.math.abs(total - captured.totalNs.sum().toDouble()) > 0.5) {
                    active.errors.add("TIMER_RECORD_MISMATCH:$phase")
                }
                val raw =
                    (0 until minOf(captured.rawCount.get(), captured.raw.length().toLong()).toInt()).mapNotNull { index ->
                        if (captured.ready.get(index) == 1) captured.raw.get(index) else null
                    }
                val sorted = raw.sorted()

                fun percentile(fraction: Double): Long? = sorted.takeIf { it.isNotEmpty() }?.get(ceil(sorted.size * fraction).toInt() - 1)
                mapOf(
                    "count" to count,
                    "totalNs" to total,
                    "capturedCount" to capturedCount,
                    "p50Ns" to percentile(0.5),
                    "p95Ns" to percentile(0.95),
                    "p99Ns" to percentile(0.99),
                    "maxNs" to sorted.lastOrNull(),
                    "rawNs" to raw,
                )
            }

        @Suppress("UNCHECKED_CAST")
        val beforeCounters = active.before.getValue("counters") as Map<String, Double>

        @Suppress("UNCHECKED_CAST")
        val afterCounters = after.getValue("counters") as Map<String, Double>
        val counters =
            beforeCounters.mapValues { (name, before) ->
                val current = afterCounters.getValue(name)
                mapOf("before" to before, "after" to current, "delta" to current - before)
            }
        val frames = (0 until minOf(active.frameCount.get(), active.frames.length().toLong()).toInt()).mapNotNull { active.frames.get(it) }
        val requestTimings =
            (0 until minOf(active.requestTimingCount.get(), active.requestTimings.length().toLong()).toInt())
                .mapNotNull { active.requestTimings.get(it) }
        val errors = active.errors.toList()
        val status = if (errors.isEmpty()) "CLOSED" else "ERROR"
        return response(active.token, "close", status, errors) +
            mapOf(
                "boundaryGauges" to gauges,
                "metricsBefore" to active.before,
                "metricsAfter" to after,
                "timers" to timers,
                "counters" to counters,
                "gaugeFrames" to frames,
                "requestTimings" to requestTimings,
                "requestTimingCount" to active.requestTimingCount.get(),
                "recordingInProgress" to (active.recording.get() and Int.MAX_VALUE),
                "overflowCount" to active.overflowCount.sum(),
            )
    }

    private fun actualMetrics(): Map<String, Any> {
        val timers =
            HTTP_GATEWAY_TIMER_PHASES.associateWith { phase ->
                val timer =
                    checkNotNull(find("$HTTP_GATEWAY_METRIC_PREFIX.duration").tag("phase", phase).timer()) { "Gateway Timer 누락: $phase" }
                check(!timer.javaClass.simpleName.startsWith("Noop")) { "Gateway Timer가 NOOP입니다: $phase" }
                mapOf("count" to timer.count(), "totalNs" to timer.totalTime(TimeUnit.NANOSECONDS))
            }
        val counters =
            gatewayCounterTags
                .flatMap { (name, definition) ->
                    val (tag, values) = definition
                    values.map { value ->
                        val counter =
                            checkNotNull(
                                find("$HTTP_GATEWAY_METRIC_PREFIX.$name").tag(tag, value).counter(),
                            ) { "Gateway Counter 누락: $name/$value" }
                        check(!counter.javaClass.simpleName.startsWith("Noop")) { "Gateway Counter가 NOOP입니다: $name/$value" }
                        "$name|$tag=$value" to counter.count()
                    }
                }.toMap()
        return mapOf("timers" to timers, "counters" to counters)
    }

    private fun readGauges(): Map<String, Double> =
        HTTP_GATEWAY_GAUGES.associateWith { name ->
            val value = checkNotNull(find("$HTTP_GATEWAY_METRIC_PREFIX.$name").gauge()) { "Gateway Gauge 누락: $name" }.value()
            check(value.isFinite() && value >= 0) { "Gateway Gauge 값이 잘못됐습니다: $name=$value" }
            check(name != "degraded" || value == 0.0) { "Gateway 운영 계측이 비정상입니다" }
            value
        }

    private fun isQuiescent(gauges: Map<String, Double>): Boolean = gauges.values.all { it == 0.0 }

    private fun requireQuiescent(gauges: Map<String, Double>) = check(isQuiescent(gauges)) { "Gateway에 아직 대기·완료 호출이 있습니다: $gauges" }

    private fun response(
        token: String,
        action: String,
        status: String,
        errors: List<String>,
    ): Map<String, Any?> =
        mapOf(
            "kind" to "GATEWAY_WINDOW",
            "token" to token,
            "action" to action,
            "status" to status,
            "detailedTracing" to detailedTracing,
            "errors" to errors,
        )

    private fun requireToken(token: String) = require(token.matches(Regex("[a-zA-Z0-9-]{1,70}"))) { "Gateway 관찰 창 식별자가 잘못됐습니다" }
}

@Configuration(proxyBeanMethods = false)
@Profile("http-performance")
internal class HttpPerformanceGatewayConfiguration {
    @Bean
    @Primary
    fun performanceGatewayRegistry(): HttpPerformanceGatewayRegistry =
        HttpPerformanceGatewayRegistry(System.getenv("PERFORMANCE_DETAILED_TRACING") != "false")
}

/** 공유 파일에서 끝까지 읽은 제어 프레임만 받는다. 미완성 프레임은 다음 poll에서 다시 읽는다. */
internal fun httpPerformanceWindowCommand(
    text: String,
    runId: String,
): Pair<String, String>? {
    if (!text.endsWith('\n')) return null
    val document = text.removeSuffix("\n")
    require(document.matches(Regex("(open|close):[a-zA-Z0-9-]{1,70}"))) { "Gateway 관찰 명령이 잘못됐습니다" }
    val (action, token) = document.split(':')
    require(token == runId) { "다른 실행의 Gateway 관찰 명령을 받지 않습니다" }
    return action to token
}

/** 파일 제어·원시 표본은 테스트 배포물에만 있고 제품 HTTP 입구는 그대로 사용한다. */
fun main(args: Array<String>) {
    val control = Path.of(requireNotNull(System.getenv("PERFORMANCE_CONTROL_DIRECTORY")))
    val runId = requireNotNull(System.getenv("FUNDING_RUN_ID"))
    val mapper = jacksonObjectMapper()
    Files.createDirectories(control)
    SpringApplicationBuilder(
        TradingGatewayApplication::class.java,
        com.exchange.core.api.config.TradingGatewayHttpWorkerConfig::class.java,
        HttpPerformanceGatewayConfiguration::class.java,
        HttpPerformanceHttpBoundaryConfiguration::class.java,
    ).profiles("aeron-gateway", "http-performance")
        .properties(
            mapOf(
                "performance.http-boundaries.enabled" to (System.getenv("PERFORMANCE_HTTP_BOUNDARIES") == "true"),
                "trading.aeron.gateway.http-virtual-threads" to (System.getenv("TRADING_GATEWAY_HTTP_VIRTUAL_THREADS") == "true"),
            ),
        ).run(*args)
        .use { context ->
            val registry = context.getBean(HttpPerformanceGatewayRegistry::class.java)
            val boundaries = context.getBeanProvider(HttpPerformanceMvcBoundaries::class.java).getIfAvailable()
            var previous: String? = null
            var lastFrameNs = System.nanoTime()
            while (context.isActive) {
                val request = control.resolve("gateway-window.request")
                if (Files.isRegularFile(request)) {
                    val text = Files.readString(request)
                    val command = httpPerformanceWindowCommand(text, runId)
                    if (command != null && text != previous) {
                        val (action, token) = command
                        val result =
                            try {
                                if (action == "open") registry.openWindow(token) else registry.closeWindow(token)
                            } catch (failure: Exception) {
                                mapOf(
                                    "kind" to "GATEWAY_WINDOW",
                                    "token" to token,
                                    "action" to action,
                                    "status" to "ERROR",
                                    "errors" to listOf("${failure.javaClass.simpleName}: ${failure.message}"),
                                )
                            } + ("runId" to runId)
                        // Preserve boundary evidence even if the independent Gateway timer window reports a failure.
                        val withBoundaries =
                            if (boundaries == null) {
                                result
                            } else {
                                val captured =
                                    try {
                                        val connectors = captureHttpPerformanceTomcat(context)
                                        if (action == "open") boundaries.open(connectors) else boundaries.close(connectors)
                                    } catch (failure: Exception) {
                                        mapOf(
                                            "enabled" to true,
                                            "runId" to runId,
                                            "status" to "ERROR",
                                            "errors" to listOf(failure.javaClass.simpleName),
                                        )
                                    }
                                result + ("httpBoundaries" to captured)
                            }
                        val target = control.resolve("gateway-window-$token-$action.json")
                        check(!Files.exists(target)) { "기존 Gateway 관찰 결과를 덮어쓰지 않습니다" }
                        val temporary = Files.createTempFile(control, ".gateway-window-", ".tmp")
                        try {
                            Files.newOutputStream(temporary).use { mapper.writeValue(it, withBoundaries) }
                            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                        } finally {
                            Files.deleteIfExists(temporary)
                        }
                        previous = text
                    }
                }
                val now = System.nanoTime()
                if (now - lastFrameNs >= TimeUnit.MILLISECONDS.toNanos(250)) {
                    registry.captureGaugeFrame()
                    lastFrameNs = now
                }
                Thread.sleep(25)
            }
        }
}
