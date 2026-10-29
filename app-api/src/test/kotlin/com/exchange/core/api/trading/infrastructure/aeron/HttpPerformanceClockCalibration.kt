package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal const val HTTP_PERFORMANCE_CLOCK_PROBE_HEADER = "X-Performance-Clock-Probe"
internal const val HTTP_PERFORMANCE_SERVER_NANO_HEADER = "X-Performance-Server-Nano"

internal enum class HttpPerformanceClockPhase { BEFORE, AFTER }

internal data class HttpPerformanceClockInterval(
    val lowerNs: Long,
    val upperNs: Long,
) {
    val widthNs: Long get() = Math.subtractExact(upperNs, lowerNs)

    /** A calibrated server point is an interval in the client's clock, never an exact timestamp. */
    fun clientBounds(serverNs: Long): HttpPerformanceClockInterval =
        HttpPerformanceClockInterval(Math.subtractExact(serverNs, upperNs), Math.subtractExact(serverNs, lowerNs))
}

internal data class HttpPerformanceClockProbe(
    val phase: HttpPerformanceClockPhase,
    val index: Int,
    val clientSendNs: Long,
    val clientBodyReceivedNs: Long,
    val serverNs: Long?,
    val httpStatus: Int?,
    val serverHeaderValues: List<String>,
    val offsetInterval: HttpPerformanceClockInterval?,
    val errors: List<String>,
)

/** At most 16 idle probes, outside the measured cohort. No symmetric network-delay assumption. */
internal class HttpPerformanceClockCalibration(
    private val runId: String,
    private val healthUri: URI,
    private val probesPerPhase: Int = 6,
    private val nanoTime: () -> Long = System::nanoTime,
    private val submit: (HttpRequest) -> CompletableFuture<HttpResponse<String>>,
) {
    private val mapper = jacksonObjectMapper()
    private val probes = ArrayList<HttpPerformanceClockProbe>(probesPerPhase * 2)
    private val captured = HashSet<HttpPerformanceClockPhase>()
    private val controlErrors = linkedSetOf<String>()

    init {
        require(probesPerPhase in 4..8)
        require(runId.matches(Regex("[a-zA-Z0-9-]{1,70}")))
        require(healthUri.path == "/actuator/health")
    }

    fun capture(phase: HttpPerformanceClockPhase) {
        if (!captured.add(phase)) {
            controlErrors.add("DUPLICATE_PHASE:$phase")
            return
        }
        repeat(probesPerPhase) { index ->
            if (Thread.currentThread().isInterrupted) {
                controlErrors.add("INTERRUPTED_PHASE:$phase")
                return
            }
            probes.add(probe(phase, index))
        }
    }

    private fun probe(
        phase: HttpPerformanceClockPhase,
        index: Int,
    ): HttpPerformanceClockProbe {
        val request =
            HttpRequest
                .newBuilder(healthUri)
                .timeout(Duration.ofSeconds(2))
                .header(HTTP_PERFORMANCE_CLOCK_PROBE_HEADER, runId)
                .GET()
                .build()
        val issues = mutableListOf<String>()
        var status: Int? = null
        var headerValues = emptyList<String>()
        var serverNs: Long? = null
        var response: HttpResponse<String>? = null
        var future: CompletableFuture<HttpResponse<String>>? = null
        val t1 = nanoTime()
        try {
            future = submit(request)
            // The response future uses ofString: successful completion includes the full body.
            response = future.get(3, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            future?.cancel(true)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            val cause = generateSequence<Throwable>(failure) { it.cause }.last()
            issues.add("HTTP_FAILURE:${cause.javaClass.simpleName}")
        }
        val t4 = nanoTime()
        response?.let {
            status = it.statusCode()
            if (status != 200) issues.add("HTTP_STATUS:$status")
            val values = it.headers().allValues(HTTP_PERFORMANCE_SERVER_NANO_HEADER)
            headerValues = values.take(8).map { value -> value.take(128) }
            if (values.size != 1 || values.any { value -> value.length > 128 }) issues.add("SERVER_HEADER_CARDINALITY_OR_SIZE")
            serverNs = values.singleOrNull()?.toLongOrNull()
            if (serverNs == null) issues.add("SERVER_NANO_MISSING_OR_INVALID")
            val healthUp =
                runCatching {
                    it.body().length <= 4096 && mapper.readTree(it.body()).path("status").asString() == "UP"
                }.getOrDefault(false)
            if (!healthUp) issues.add("HEALTH_NOT_UP")
        }
        val interval =
            try {
                if (Math.subtractExact(t4, t1) < 0) issues.add("NEGATIVE_CLIENT_ROUND_TRIP")
                serverNs?.takeIf { issues.isEmpty() }?.let { server ->
                    HttpPerformanceClockInterval(Math.subtractExact(server, t4), Math.subtractExact(server, t1)).also { it.widthNs }
                }
            } catch (_: ArithmeticException) {
                issues.add("CLOCK_ARITHMETIC_OVERFLOW")
                null
            }
        return HttpPerformanceClockProbe(phase, index, t1, t4, serverNs, status, headerValues, interval, issues)
    }

    fun snapshot(): Map<String, Any?> {
        val errors = controlErrors.toMutableSet()
        HttpPerformanceClockPhase.entries.forEach { phase ->
            if (probes.count { it.phase == phase } != probesPerPhase) errors.add("INCOMPLETE_PHASE:$phase")
        }
        probes.forEach { probe -> probe.errors.forEach { errors.add("${probe.phase}:${probe.index}:$it") } }

        fun intersection(values: List<HttpPerformanceClockProbe>): HttpPerformanceClockInterval? {
            if (values.isEmpty() || values.any { it.offsetInterval == null }) return null
            val lower = values.maxOf { checkNotNull(it.offsetInterval).lowerNs }
            val upper = values.minOf { checkNotNull(it.offsetInterval).upperNs }
            return if (lower <= upper) HttpPerformanceClockInterval(lower, upper) else null
        }
        val phases =
            HttpPerformanceClockPhase.entries.associate { phase ->
                phase.name to intersection(probes.filter { it.phase == phase })
            }
        phases.filterValues { it == null }.keys.forEach { errors.add("NO_PHASE_INTERSECTION:$it") }
        val complete = HttpPerformanceClockPhase.entries.all { phase -> probes.count { it.phase == phase } == probesPerPhase }
        val common = if (complete && phases.values.all { it != null }) intersection(probes) else null
        if (common == null) errors.add("NO_COMMON_OFFSET_INTERSECTION")
        return mapOf(
            "runId" to runId,
            "status" to if (errors.isEmpty()) "VALID" else "ERROR",
            "errors" to errors.toList(),
            "probeUri" to healthUri.toString(),
            "probesPerPhase" to probesPerPhase,
            "maximumProbes" to probesPerPhase * 2,
            "outsideMeasuredTPS" to true,
            "clockModel" to
                "One constant serverMinusClient monotonic-clock offset over the bracketing interval. " +
                "The intersection is conditional on that model; clock-rate drift inside the cohort is not directly measured.",
            "intervalRule" to "Each successful probe bounds serverMinusClient by [serverNs-clientBodyReceivedNs, serverNs-clientSendNs].",
            "translationRule" to "For a common offset [lowerNs,upperNs], server point s maps to client [s-upperNs,s-lowerNs].",
            "phaseOffsetIntervals" to phases,
            "commonOffsetInterval" to common,
            "probes" to probes.toList(),
        )
    }
}
