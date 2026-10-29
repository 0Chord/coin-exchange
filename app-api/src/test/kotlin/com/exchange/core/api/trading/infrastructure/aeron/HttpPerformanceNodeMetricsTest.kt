package com.exchange.core.api.trading.infrastructure.aeron

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class HttpPerformanceNodeMetricsTest {
    private inline fun <R> SimpleMeterRegistry.use(block: (SimpleMeterRegistry) -> R): R =
        try {
            block(this)
        } finally {
            close()
        }

    @TempDir
    lateinit var directory: Path

    @Test
    fun `기존 timer와 counter를 새 meter 등록 없이 정확하게 보존한다`() {
        SimpleMeterRegistry().use { registry ->
            val timer = Timer.builder("trading.node.duration").tag("phase", "execution_offer").register(registry)
            val counter = Counter.builder("trading.node.offers").tag("result", "back_pressured").register(registry)
            timer.record(11, TimeUnit.NANOSECONDS)
            timer.record(22, TimeUnit.NANOSECONDS)
            counter.increment(3.0)
            val snapshot = httpPerformanceOperationalMetrics(registry)
            val timers = snapshot.getValue("timers") as List<*>
            val counters = snapshot.getValue("counters") as List<*>
            val duration = timers.single() as Map<*, *>
            val attempts = counters.single() as Map<*, *>
            assertEquals(2, registry.meters.size)
            assertEquals("trading.node.duration", duration["name"])
            assertEquals(mapOf("phase" to "execution_offer"), duration["tags"])
            assertEquals(2L, duration["count"])
            assertEquals(33.0, duration["totalNs"])
            assertEquals(22.0, duration["maxNs"])
            assertEquals(3.0, attempts["count"])
            assertEquals(mapOf("result" to "back_pressured"), attempts["tags"])
        }
    }

    @Test
    fun `필수 운영 지표가 없으면 성공 스냅샷으로 인정하지 않는다`() {
        SimpleMeterRegistry().use { registry ->
            val control = HttpPerformanceNodeMetricsControl(directory, "missing-meters", 0, registry)
            Files.writeString(directory.resolve("node-metrics.request"), "before")
            assertEquals(false, control.poll()?.get("valid"))
            val stored = jacksonObjectMapper().readTree(Files.readString(directory.resolve("node0-metrics-before.json")))
            assertFalse(stored.path("valid").asBoolean())
            assertTrue(stored.path("failure").asString().contains("missing or degraded"))
        }
    }

    @Test
    fun `운영 지표가 활성화되어도 관측 실패가 있으면 무효로 저장한다`() {
        SimpleMeterRegistry().use { registry ->
            TradingNodeMetrics(registry)
            registry.get("${TradingNodeMetrics.PREFIX}.observation_failures").counter().increment(2.0)
            val control = HttpPerformanceNodeMetricsControl(directory, "failed-observation", 2, registry)
            Files.writeString(directory.resolve("node-metrics.request"), "after")
            assertEquals(false, control.poll()?.get("valid"))
            val stored = jacksonObjectMapper().readTree(Files.readString(directory.resolve("node2-metrics-after.json")))
            assertFalse(stored.path("valid").asBoolean())
            assertTrue(stored.path("telemetryOperational").asBoolean())
            assertEquals(2.0, stored.path("observationFailures").asDouble())
            assertTrue(stored.path("failure").asString().contains("nonzero"))
        }
    }

    @Test
    fun `명시적 요청만 한 번씩 저장하고 before 파일을 덮어쓰지 않는다`() {
        SimpleMeterRegistry().use { registry ->
            TradingNodeMetrics(registry)
            val counter = registry.counter("trading.node.callbacks")
            val control = HttpPerformanceNodeMetricsControl(directory, "metrics-test", 1, registry)
            assertNull(control.poll())
            assertFalse(Files.exists(directory.resolve("node1-metrics-before.json")))
            counter.increment(2.0)
            Files.writeString(directory.resolve("node-metrics.request"), "before")
            assertEquals(true, control.poll()?.get("valid"))
            val beforeFile = directory.resolve("node1-metrics-before.json")
            val before = Files.readString(beforeFile)
            counter.increment(3.0)
            assertNull(control.poll())
            assertEquals(before, Files.readString(beforeFile))
            Files.writeString(directory.resolve("node-metrics.request"), "after")
            assertEquals(true, control.poll()?.get("valid"))
            val after = jacksonObjectMapper().readTree(Files.readString(directory.resolve("node1-metrics-after.json")))
            assertEquals("metrics-test", after.path("runId").asString())
            assertEquals(1, after.path("memberId").asInt())
            val callbacks = after.path("counters").first { it.path("name").asString() == "trading.node.callbacks" }
            assertEquals(5.0, callbacks.path("count").asDouble())
            assertTrue(after.path("heapUsedBytes").asLong() > 0)
            Files.writeString(directory.resolve("node-metrics.request"), "before")
            assertNull(control.poll())
            assertEquals(before, Files.readString(beforeFile))
        }
    }
}
