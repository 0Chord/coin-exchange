package com.exchange.core.api.trading.infrastructure.aeron

import io.micrometer.core.instrument.config.MeterFilter
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpPerformanceGatewayTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `제어 파일의 끝이 덜 보이면 실행하지 않고 완성된 해당 실행 프레임만 받는다`() {
        assertNull(httpPerformanceWindowCommand("close:run-", "run-1"))
        assertNull(httpPerformanceWindowCommand("close:run-1", "run-1"))
        assertEquals("close" to "run-1", httpPerformanceWindowCommand("close:run-1\n", "run-1"))
        assertEquals("open" to "run-1", httpPerformanceWindowCommand("open:run-1\n", "run-1"))
        assertFailsWith<IllegalArgumentException> { httpPerformanceWindowCommand("close:other\n", "run-1") }
        assertFailsWith<IllegalArgumentException> { httpPerformanceWindowCommand("invalid:run-1\n", "run-1") }
    }

    @Test
    fun `예열을 제외한 익명 표본과 실제 SimpleTimer 차분을 함께 남긴다`() {
        HttpPerformanceGatewayRegistry().use { registry ->
            GatewayMetrics(registry).use { metrics ->
                metrics.duration(GatewayMetrics.Phase.QUEUE, 99)
                registry.openWindow("measured")
                metrics.duration(GatewayMetrics.Phase.QUEUE, 10)
                metrics.duration(GatewayMetrics.Phase.QUEUE, 20)
                metrics.submission(GatewayMetrics.Submission.NEW)
                metrics.result(GatewayMetrics.Result.ACCEPTED)

                val result = tree(registry.closeWindow("measured"))
                val queue = result.path("timers").path("queue")
                assertEquals("CLOSED", result.path("status").asString())
                assertEquals(2L, queue.path("count").asLong())
                assertEquals(2L, queue.path("capturedCount").asLong())
                assertEquals(30.0, queue.path("totalNs").asDouble())
                assertEquals(listOf(10L, 20L), queue.path("rawNs").toList().map { it.asLong() })
                assertEquals(10L, queue.path("p50Ns").asLong())
                assertEquals(20L, queue.path("p99Ns").asLong())
                assertEquals(
                    1.0,
                    result
                        .path("counters")
                        .path("submissions|outcome=new")
                        .path("delta")
                        .asDouble(),
                )
                val actual = registry.find("$HTTP_GATEWAY_METRIC_PREFIX.duration").tag("phase", "queue").timer()!!
                assertEquals(3L, actual.count())
                assertEquals(129.0, actual.totalTime(TimeUnit.NANOSECONDS))
                assertTrue(result.path("errors").isEmpty)
                assertTrue(registry.meters.all { meter -> meter.id.tags.all { it.key in setOf("phase", "outcome", "status") } })
            }
        }
    }

    @Test
    fun `상세 기록을 꺼도 실제 미터와 창의 횟수 합계는 유지한다`() {
        HttpPerformanceGatewayRegistry(detailedTracing = false).use { registry ->
            GatewayMetrics(registry).use { metrics ->
                registry.openWindow("aggregate-only")
                metrics.duration(GatewayMetrics.Phase.ENCODE, 123)
                registry.onRequestCompleted(timing("aggregate-only"))

                val result = tree(registry.closeWindow("aggregate-only"))
                val encode = result.path("timers").path("encode")
                assertEquals("CLOSED", result.path("status").asString())
                assertEquals(1L, encode.path("count").asLong())
                assertEquals(123.0, encode.path("totalNs").asDouble())
                assertTrue(encode.path("rawNs").isEmpty)
                assertTrue(encode.path("p50Ns").isNull)
                assertTrue(result.path("requestTimings").isEmpty)
                assertEquals(0L, result.path("requestTimingCount").asLong())
            }
        }
    }

    @Test
    fun `상한 초과는 실제 미터 전달을 보존하고 진단 결과를 무효로 만든다`() {
        HttpPerformanceGatewayRegistry(sampleCapacity = 2, frameCapacity = 1).use { registry ->
            GatewayMetrics(registry).use { metrics ->
                registry.openWindow("bounded")
                repeat(3) { metrics.duration(GatewayMetrics.Phase.QUEUE, 10) }
                registry.captureGaugeFrame()
                registry.captureGaugeFrame()

                val result = tree(registry.closeWindow("bounded"))
                assertEquals("ERROR", result.path("status").asString())
                assertEquals(
                    3L,
                    result
                        .path("timers")
                        .path("queue")
                        .path("count")
                        .asLong(),
                )
                assertEquals(
                    2,
                    result
                        .path("timers")
                        .path("queue")
                        .path("rawNs")
                        .size(),
                )
                assertEquals(1, result.path("gaugeFrames").size())
                assertEquals(2L, result.path("overflowCount").asLong())
                assertEquals<Set<String>>(
                    setOf("TIMER_SAMPLE_OVERFLOW:queue", "GAUGE_FRAME_OVERFLOW"),
                    result
                        .path("errors")
                        .toList()
                        .map { it.asString() }
                        .toSet(),
                )
            }
        }
    }

    @Test
    fun `키가 비어도 마지막 future 완료 호출이 끝나기 전에 창을 닫지 않는다`() {
        HttpPerformanceGatewayRegistry().use { registry ->
            GatewayMetrics(registry).use { metrics ->
                registry.openWindow("completion-fence")
                metrics.completionStarted()
                val closed = CompletableFuture.supplyAsync { registry.closeWindow("completion-fence") }
                try {
                    assertFailsWith<TimeoutException> { closed.get(50, TimeUnit.MILLISECONDS) }
                    metrics.duration(GatewayMetrics.Phase.FUTURE_COMPLETION, 777)
                } finally {
                    metrics.completionFinished()
                }

                val result = tree(closed.get(5, TimeUnit.SECONDS))
                assertEquals("CLOSED", result.path("status").asString())
                assertEquals(0, result.path("recordingInProgress").asInt())
                assertEquals(
                    1L,
                    result
                        .path("timers")
                        .path("future_completion")
                        .path("count")
                        .asLong(),
                )
                assertEquals<List<Long>>(
                    listOf(777L),
                    result
                        .path("timers")
                        .path("future_completion")
                        .path("rawNs")
                        .toList()
                        .map { it.asLong() },
                )
            }
        }
    }

    @Test
    fun `대기 요청이나 비정상 미터가 있으면 예열 배출을 통과시키지 않는다`() {
        HttpPerformanceGatewayRegistry().use { registry ->
            GatewayMetrics(registry).use { metrics ->
                metrics.sizes(1, 0, 1)
                assertFailsWith<IllegalStateException> { registry.openWindow("pending") }
                metrics.sizes(0, 0, 0)
                registry.openWindow("drained")
                assertEquals("CLOSED", tree(registry.closeWindow("drained")).path("status").asString())
            }
        }
        HttpPerformanceGatewayRegistry().use { registry ->
            registry.config().meterFilter(MeterFilter.denyNameStartsWith("$HTTP_GATEWAY_METRIC_PREFIX.duration"))
            GatewayMetrics(registry).use {
                assertFailsWith<IllegalStateException> { registry.openWindow("noop") }
            }
        }
    }

    @Test
    fun `나노시계 원점이 음수여도 게이지 프레임에는 같은 시계의 차분을 쓴다`() {
        var now = -1000L
        HttpPerformanceGatewayRegistry(nanoTime = { now }).use { registry ->
            GatewayMetrics(registry).use {
                registry.openWindow("negative-origin")
                now = -750
                registry.captureGaugeFrame()
                val result = tree(registry.closeWindow("negative-origin"))
                assertEquals(
                    250L,
                    result
                        .path("gaugeFrames")
                        .single()
                        .path("elapsedNs")
                        .asLong(),
                )
                assertTrue(result.path("errors").isEmpty)
            }
        }
    }

    @Test
    fun `요청 식별자와 실제 구간은 열린 상세 창에만 저장하며 없는 구간을 영으로 만들지 않는다`() {
        HttpPerformanceGatewayRegistry().use { registry ->
            GatewayMetrics(registry).use {
                registry.onRequestCompleted(timing("warmup"))
                registry.openWindow("request-rows")
                val durations = mutableMapOf("queue" to 25L)
                registry.onRequestCompleted(timing("M-1", durations))
                durations["queue"] = 999

                val result = tree(registry.closeWindow("request-rows"))
                registry.onRequestCompleted(timing("after-close"))
                val captured = result.path("requestTimings").single()
                assertEquals("CLOSED", result.path("status").asString())
                assertEquals(1L, result.path("requestTimingCount").asLong())
                assertEquals("BUYER", captured.path("userId").asString())
                assertEquals("M-1", captured.path("requestId").asString())
                assertEquals("M-1", captured.path("orderId").asString())
                assertEquals(25L, captured.path("phaseDurationsNs").path("queue").asLong())
                assertEquals(1, captured.path("phaseDurationsNs").size())
                assertEquals(-100L, captured.path("enqueuedNs").asLong())
                assertEquals(-50L, captured.path("resultReadyNs").asLong())
                assertEquals(-25L, captured.path("completedNs").asLong())
                assertTrue(registry.meters.all { meter -> meter.id.tags.all { tag -> tag.key in setOf("phase", "outcome", "status") } })
            }
        }
    }

    @Test
    fun `요청 표본 상한 2000건을 넘으면 실제 미터를 유지하고 초과를 명시한다`() {
        HttpPerformanceGatewayRegistry().use { registry ->
            GatewayMetrics(registry).use { metrics ->
                registry.openWindow("request-capacity")
                repeat(2001) { registry.onRequestCompleted(timing("M-$it")) }
                metrics.duration(GatewayMetrics.Phase.QUEUE, 17)

                val result = tree(registry.closeWindow("request-capacity"))
                assertEquals("ERROR", result.path("status").asString())
                assertEquals(2000, result.path("requestTimings").size())
                assertEquals(2001L, result.path("requestTimingCount").asLong())
                assertEquals(1L, result.path("overflowCount").asLong())
                assertEquals<List<String>>(listOf("REQUEST_TIMING_OVERFLOW"), result.path("errors").toList().map { it.asString() })
                assertEquals(
                    1L,
                    result
                        .path("timers")
                        .path("queue")
                        .path("count")
                        .asLong(),
                )
            }
        }
    }

    @Test
    fun `잘못된 요청 구간은 원자료를 남기며 정상 창으로 보고하지 않는다`() {
        HttpPerformanceGatewayRegistry().use { registry ->
            GatewayMetrics(registry).use {
                registry.openWindow("invalid-request")
                registry.onRequestCompleted(timing("M-1", mapOf("queue" to -1)))

                val result = tree(registry.closeWindow("invalid-request"))
                assertEquals("ERROR", result.path("status").asString())
                assertEquals<List<String>>(listOf("INVALID_REQUEST_TIMING"), result.path("errors").toList().map { it.asString() })
                assertEquals(
                    -1L,
                    result
                        .path("requestTimings")
                        .single()
                        .path("phaseDurationsNs")
                        .path("queue")
                        .asLong(),
                )
            }
        }
    }

    @Test
    fun `타이머와 요청 기록은 제어 잠금을 소유한 동안에도 끝난다`() {
        HttpPerformanceGatewayRegistry().use { registry ->
            GatewayMetrics(registry).use { metrics ->
                registry.openWindow("no-request-monitor")
                val controlLock =
                    registry.javaClass
                        .getDeclaredField("observationLock")
                        .apply { isAccessible = true }
                        .get(registry)
                synchronized(controlLock) {
                    CompletableFuture
                        .runAsync {
                            metrics.duration(GatewayMetrics.Phase.QUEUE, 11)
                            registry.onRequestCompleted(timing("M-1"))
                        }.get(5, TimeUnit.SECONDS)
                }

                val result = tree(registry.closeWindow("no-request-monitor"))
                assertEquals("CLOSED", result.path("status").asString())
                assertEquals(
                    1L,
                    result
                        .path("timers")
                        .path("queue")
                        .path("capturedCount")
                        .asLong(),
                )
                assertEquals(1, result.path("requestTimings").size())
            }
        }
    }

    @Test
    fun `동시에 기록한 실제 미터 표본과 요청 행을 마지막 완료 뒤 모두 배출한다`() {
        HttpPerformanceGatewayRegistry().use { registry ->
            GatewayMetrics(registry).use { metrics ->
                val executor = Executors.newFixedThreadPool(8)
                registry.openWindow("concurrent")
                metrics.completionStarted()
                var completionFinished = false
                val closed = CompletableFuture.supplyAsync { registry.closeWindow("concurrent") }
                try {
                    val writers =
                        (0 until 8).map { worker ->
                            CompletableFuture.runAsync({
                                repeat(100) { sample ->
                                    val id = worker * 100 + sample
                                    metrics.duration(GatewayMetrics.Phase.QUEUE, 10)
                                    registry.onRequestCompleted(timing("M-$id", mapOf("queue" to 10L)))
                                }
                            }, executor)
                        }
                    writers.forEach { it.get(5, TimeUnit.SECONDS) }
                    metrics.completionFinished()
                    completionFinished = true

                    val result = tree(closed.get(5, TimeUnit.SECONDS))
                    val queue = result.path("timers").path("queue")
                    assertEquals("CLOSED", result.path("status").asString())
                    assertEquals(800L, queue.path("count").asLong())
                    assertEquals(800L, queue.path("capturedCount").asLong())
                    assertEquals(8000.0, queue.path("totalNs").asDouble())
                    assertEquals<List<Long>>(List(800) { 10L }, queue.path("rawNs").toList().map { it.asLong() })
                    assertEquals(800L, result.path("requestTimingCount").asLong())
                    assertEquals<Set<String>>(
                        (0 until 800).map { "M-$it" }.toSet(),
                        result
                            .path("requestTimings")
                            .toList()
                            .map { it.path("requestId").asString() }
                            .toSet(),
                    )
                    assertEquals(0, result.path("recordingInProgress").asInt())
                    assertTrue(result.path("errors").isEmpty)
                } finally {
                    if (!completionFinished) metrics.completionFinished()
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `창 닫기는 이미 시작한 요청 기록이 끝날 때까지 기다린다`() {
        HttpPerformanceGatewayRegistry().use { registry ->
            GatewayMetrics(registry).use {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val actual = mapOf("queue" to 10L)
                val blocked =
                    object : Map<String, Long> by actual {
                        override val entries: Set<Map.Entry<String, Long>>
                            get() {
                                entered.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                                return actual.entries
                            }
                    }
                registry.openWindow("request-fence")
                val recording = CompletableFuture.runAsync { registry.onRequestCompleted(timing("M-1", blocked)) }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val closed = CompletableFuture.supplyAsync { registry.closeWindow("request-fence") }
                    assertFailsWith<TimeoutException> { closed.get(50, TimeUnit.MILLISECONDS) }
                    release.countDown()
                    recording.get(5, TimeUnit.SECONDS)

                    val result = tree(closed.get(5, TimeUnit.SECONDS))
                    assertEquals("CLOSED", result.path("status").asString())
                    assertEquals(0, result.path("recordingInProgress").asInt())
                    assertEquals(
                        "M-1",
                        result
                            .path("requestTimings")
                            .single()
                            .path("requestId")
                            .asString(),
                    )
                } finally {
                    release.countDown()
                    recording.get(5, TimeUnit.SECONDS)
                }
            }
        }
    }

    @Test
    fun `배출 시간 초과는 미완성 값을 만들지 않고 늦은 기록을 다음 창에서 격리한다`() {
        HttpPerformanceGatewayRegistry().use { registry ->
            GatewayMetrics(registry).use {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val actual = mapOf("queue" to 10L)
                val blocked =
                    object : Map<String, Long> by actual {
                        override val entries: Set<Map.Entry<String, Long>>
                            get() {
                                entered.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                                return actual.entries
                            }
                    }
                registry.openWindow("timed-out-request")
                val recording = CompletableFuture.runAsync { registry.onRequestCompleted(timing("old", blocked)) }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val timedOut = tree(registry.closeWindow("timed-out-request", timeoutMs = 1))
                    assertEquals("ERROR", timedOut.path("status").asString())
                    assertEquals(1, timedOut.path("recordingInProgress").asInt())
                    assertTrue(timedOut.path("requestTimings").isEmpty)
                    assertEquals<List<String>>(
                        listOf("GATEWAY_WINDOW_DRAIN_TIMEOUT"),
                        timedOut.path("errors").toList().map { it.asString() },
                    )

                    registry.openWindow("next-request")
                    registry.onRequestCompleted(timing("new"))
                    release.countDown()
                    recording.get(5, TimeUnit.SECONDS)

                    val next = tree(registry.closeWindow("next-request"))
                    assertEquals("CLOSED", next.path("status").asString())
                    assertEquals(1L, next.path("requestTimingCount").asLong())
                    assertEquals(
                        "new",
                        next
                            .path("requestTimings")
                            .single()
                            .path("requestId")
                            .asString(),
                    )
                } finally {
                    release.countDown()
                    recording.get(5, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun timing(
        requestId: String,
        phases: Map<String, Long> = mapOf("queue" to 25L),
    ) = GatewayRequestTiming("BUYER", requestId, requestId, -100, -50, -25, phases)

    private fun tree(value: Map<String, Any?>): JsonNode = mapper.valueToTree(value)
}
