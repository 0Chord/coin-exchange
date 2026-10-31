package com.exchange.core.api.trading.infrastructure.aeron

import io.micrometer.core.instrument.MeterRegistry
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 미지원·비활성·실패 카운터는 미측정 상태를 유지한다. 0은 실제로 측정한 값이다. */
internal fun haProbeCounter(
    supported: Boolean,
    enabled: Boolean,
    read: () -> Long,
): Map<String, Any?> =
    when {
        !supported -> {
            mapOf("status" to "unsupported", "value" to null)
        }

        !enabled -> {
            mapOf("status" to "disabled", "value" to null)
        }

        else -> {
            try {
                val value = read()
                if (value < 0) mapOf("status" to "unavailable", "value" to null) else mapOf("status" to "available", "value" to value)
            } catch (failure: Exception) {
                mapOf("status" to "unavailable", "value" to null, "reason" to failure.javaClass.simpleName)
            }
        }
    }

/** 측정 구간 밖의 크기가 제한된 CAPTURE 요청은 테스트 데코레이터의 서비스 스레드만 처리한다. */
internal class HaProbeServiceBoundary {
    private val pending = ArrayDeque<CompletableFuture<Map<String, Any?>>>()

    @Volatile private var hasPending = false

    fun capture(timeoutMs: Long): Map<String, Any?> {
        val result = CompletableFuture<Map<String, Any?>>()
        synchronized(pending) {
            check(pending.size < 4) { "probe service boundary capture capacity exceeded" }
            pending.addLast(result)
            hasPending = true
        }
        return try {
            result.get(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            result.cancel(false)
        }
    }

    fun completePending(): Int {
        if (!hasPending) return 0
        val waiting =
            synchronized(pending) {
                pending.toList().also {
                    pending.clear()
                    // 생산자는 이 배출이 끝난 뒤 같은 잠금 안에서 true 플래그를 공개한다.
                    hasPending = false
                }
            }
        val active = waiting.filterNot { it.isCancelled }
        if (active.isEmpty()) return waiting.size
        val bean = ManagementFactory.getThreadMXBean()
        val allocated = bean as? com.sun.management.ThreadMXBean
        val thread = Thread.currentThread()
        val cpuSupported = bean.isThreadCpuTimeSupported
        val allocationSupported = allocated?.isThreadAllocatedMemorySupported == true
        val counters =
            mapOf(
                "scope" to
                    "same-node service thread checkpoint; includes bounded final-state CAPTURE and boundary-control costs; not pure domain CPU",
                "sampledNs" to System.nanoTime(),
                "clock" to "node-local System.nanoTime; never subtract another JVM clock",
                "threadId" to thread.id,
                "cpuNs" to haProbeCounter(cpuSupported, cpuSupported && bean.isThreadCpuTimeEnabled) { bean.currentThreadCpuTime },
                "allocatedBytes" to
                    haProbeCounter(allocationSupported, allocationSupported && allocated?.isThreadAllocatedMemoryEnabled == true) {
                        checkNotNull(allocated).getThreadAllocatedBytes(thread.id)
                    },
            )
        active.forEach { if (!it.isCancelled) it.complete(counters) }
        return waiting.size
    }
}

/** 요청 묶음이 모두 처리된 뒤 기존 운영 지표를 읽는다. 지표를 등록하거나 초기화하지 않는다. */
internal fun haTradingProbeMetrics(registry: () -> MeterRegistry): Map<String, Any?> =
    try {
        val meters = registry()
        val problems = mutableListOf<String>()
        val prefix = TradingNodeMetrics.PREFIX

        fun number(
            name: String,
            value: Double?,
        ): Double? {
            if (value == null || !value.isFinite() || value < 0) problems.add("missing or invalid $name")
            return value
        }

        val phases =
            TradingNodePhase.entries.map { phase ->
                val timer = meters.find(TradingNodeMetrics.DURATION).tag("phase", phase.tag).timer()
                val count = timer?.count()
                if (count == null || count < 0) problems.add("missing or invalid duration count:${phase.tag}")
                mapOf(
                    "phase" to phase.tag,
                    "count" to count,
                    "totalNs" to number("duration total:${phase.tag}", timer?.totalTime(TimeUnit.NANOSECONDS)),
                    "maxNs" to number("duration max:${phase.tag}", timer?.max(TimeUnit.NANOSECONDS)),
                )
            }
        val attempts =
            listOf("execution", "reply").associateWith { target ->
                number(
                    "offer attempts:$target",
                    meters
                        .find("$prefix.offer_attempts")
                        .tag("target", target)
                        .counter()
                        ?.count(),
                )
            }
        val callbacks =
            TradingNodeCallbackKind.entries.associate { kind ->
                val tag = kind.name.lowercase()
                tag to
                    number(
                        "callbacks:$tag",
                        meters
                            .find(TradingNodeMetrics.CALLBACKS)
                            .tag("kind", tag)
                            .counter()
                            ?.count(),
                    )
            }
        val degraded = number("degraded", meters.find("$prefix.degraded").gauge()?.value())
        val dropped = number("dropped_samples", meters.find("$prefix.dropped_samples").gauge()?.value())
        val failures = number("observation_failures", meters.find("$prefix.observation_failures").counter()?.count())
        val fallbacks = number("handoff_fallbacks", meters.find("$prefix.handoff_fallbacks").counter()?.count())
        if (degraded != null && degraded != 0.0) problems.add("operational telemetry degraded")
        if (dropped != null && dropped != 0.0) problems.add("operational telemetry samples dropped")
        if (failures != null && failures != 0.0) problems.add("operational telemetry recording failures")
        mapOf(
            "valid" to problems.isEmpty(),
            "failure" to problems.takeIf { it.isNotEmpty() }?.joinToString("; "),
            "scope" to "cumulative operational meters; external observation, outside financial state",
            "maxSemantics" to "Micrometer timer window maximum; do not subtract checkpoints",
            "phases" to phases,
            "offerAttempts" to attempts,
            "callbacks" to callbacks,
            "droppedSamples" to dropped,
            "degraded" to degraded,
            "observationFailures" to failures,
            "handoffFallbacks" to fallbacks,
        )
    } catch (failure: Exception) {
        mapOf("valid" to false, "failure" to failure.toString())
    }
