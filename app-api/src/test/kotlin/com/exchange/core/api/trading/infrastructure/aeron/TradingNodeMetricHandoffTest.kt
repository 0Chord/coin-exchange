package com.exchange.core.api.trading.infrastructure.aeron

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.distribution.pause.PauseDetector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TradingNodeMetricHandoffTest {
    @Test
    fun `bounded mode skips a saturated optional metric without waiting for its exporter`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val registry =
            object : SimpleMeterRegistry() {
                override fun newTimer(
                    id: Meter.Id,
                    distributionStatisticConfig: DistributionStatisticConfig,
                    pauseDetector: PauseDetector,
                ): Timer {
                    val actual = super.newTimer(id, distributionStatisticConfig, pauseDetector)
                    return object : Timer by actual {
                        override fun record(
                            amount: Long,
                            unit: TimeUnit,
                        ) {
                            if (calls.incrementAndGet() == 1) {
                                entered.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                            }
                            actual.record(amount, unit)
                        }
                    }
                }
            }
        registry.withRegistry {
            TradingNodeMetrics(registry, asynchronous = true, queueCapacity = 1, dropOnSaturation = true).use { metrics ->
                try {
                    var now = 0L
                    val capture = TradingNodeCallbackCapture { now }

                    fun record() {
                        capture.reset()
                        capture.kind = TradingNodeCallbackKind.ADMISSION
                        now++
                        capture.complete()
                        metrics.recordCompleted(capture, 128, "LEADER", "OFFERED")
                    }
                    record()
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    // The worker is still inside the first timer and cannot consume another slot.
                    repeat(3) { record() }
                    assertTrue(registry.get("${TradingNodeMetrics.PREFIX}.dropped_samples").gauge().value() >= 1.0)
                    assertFalse(metrics.isOperational)
                    assertEquals(1L, release.count, "financial owner returned before the exporter was released")
                } finally {
                    release.countDown()
                }
                metrics.awaitRecorded()
            }
        }
    }

    @Test
    fun `비동기 기록도 0ns 표본과 counter 및 capture 불변성을 보존한다`() {
        SimpleMeterRegistry().withRegistry { registry ->
            TradingNodeMetrics(registry, asynchronous = true, queueCapacity = 16).use { metrics ->
                var now = 0L
                val capture = TradingNodeCallbackCapture { now }
                capture.kind = TradingNodeCallbackKind.ADMISSION
                capture.executionOfferAttempts = 3
                capture.replyAttempts = 2
                capture.begin(TradingNodePhase.DECODE)
                capture.end(TradingNodePhase.DECODE)
                now = 7
                capture.complete()
                metrics.recordCompleted(capture, 128, "LEADER", "OFFERED")
                capture.reset()
                capture.kind = TradingNodeCallbackKind.EXECUTION
                now = 12
                capture.begin(TradingNodePhase.EXECUTION_TOTAL)
                now = 23
                capture.end(TradingNodePhase.EXECUTION_TOTAL)
                capture.complete()
                metrics.recordCompleted(capture, 256, "FOLLOWER", "EXECUTED")
                capture.reset()
                metrics.awaitRecorded()

                val decode = registry.get(TradingNodeMetrics.DURATION).tag("phase", "decode").timer()
                assertEquals(1L, decode.count())
                assertEquals(0.0, decode.totalTime(TimeUnit.NANOSECONDS))
                val execution = registry.get(TradingNodeMetrics.DURATION).tag("phase", "execution_total").timer()
                assertEquals(1L, execution.count())
                assertEquals(11.0, execution.totalTime(TimeUnit.NANOSECONDS))
                assertEquals(11.0, execution.max(TimeUnit.NANOSECONDS))
                assertEquals(
                    2L,
                    registry
                        .get(TradingNodeMetrics.DURATION)
                        .tag("phase", "callback")
                        .timer()
                        .count(),
                )
                assertEquals(
                    0L,
                    registry
                        .get(TradingNodeMetrics.DURATION)
                        .tag("phase", "funding_apply")
                        .timer()
                        .count(),
                )
                assertEquals(
                    1.0,
                    registry
                        .get(TradingNodeMetrics.CALLBACKS)
                        .tag("kind", "admission")
                        .counter()
                        .count(),
                )
                assertEquals(
                    1.0,
                    registry
                        .get(TradingNodeMetrics.CALLBACKS)
                        .tag("kind", "execution")
                        .counter()
                        .count(),
                )
                assertEquals(
                    3.0,
                    registry
                        .get("${TradingNodeMetrics.PREFIX}.offer_attempts")
                        .tag("target", "execution")
                        .counter()
                        .count(),
                )
                assertEquals(
                    2.0,
                    registry
                        .get("${TradingNodeMetrics.PREFIX}.offer_attempts")
                        .tag("target", "reply")
                        .counter()
                        .count(),
                )
                assertTrue(metrics.isOperational)
                assertEquals(0L, metrics.failureCount)
            }
        }
    }

    @Test
    fun `닫기는 모든 수락 표본을 기록한 뒤 worker를 종료한다`() {
        val failures = AtomicInteger()
        val recorded = AtomicInteger()
        val queue =
            TradingNodeMetricHandoff(4096, { _, _, _, _, _ -> recorded.incrementAndGet() }, { failures.incrementAndGet() }, { throw it })
        val durations = LongArray(TradingNodePhase.entries.size) { -1 }
        repeat(2000) { queue.offer(TradingNodeCallbackKind.ADMISSION, durations, 1, 1, 0) }
        queue.close()
        assertEquals(2000, recorded.get())
        assertEquals(0, failures.get())
        queue.close()
        assertFalse(queue.offer(TradingNodeCallbackKind.ADMISSION, durations, 1, 1, 0))
        assertEquals(2000, recorded.get())
        assertEquals(0, failures.get())
    }

    @Test
    fun `가득 찬 버퍼는 기다리거나 덮어쓰지 않고 동기 fallback 필요를 반환한다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failures = AtomicInteger()
        val recorded = AtomicInteger()
        val queue =
            TradingNodeMetricHandoff(1, { _, values, _, _, _ ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                assertEquals(11L, values[TradingNodePhase.CALLBACK.ordinal])
                recorded.incrementAndGet()
            }, { failures.incrementAndGet() }, { throw it })
        try {
            val values = LongArray(TradingNodePhase.entries.size) { -1 }
            values[TradingNodePhase.CALLBACK.ordinal] = 11
            assertTrue(queue.offer(TradingNodeCallbackKind.ADMISSION, values, 0, 0, 0))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            values[TradingNodePhase.CALLBACK.ordinal] = 22
            assertFalse(queue.offer(TradingNodeCallbackKind.EXECUTION, values, 0, 0, 0))
            assertEquals(0, failures.get())
            release.countDown()
            queue.awaitRecorded()
        } finally {
            release.countDown()
            queue.close()
        }
        assertEquals(1, recorded.get())
    }

    @Test
    fun `2의 거듭제곱이 아닌 버퍼를 여러 번 돌아도 수락한 값과 순서를 보존한다`() {
        val failures = AtomicInteger()
        val recorded = mutableListOf<Long>()
        val expected = (1L..18L).toList()
        TradingNodeMetricHandoff(3, { _, values, _, _, _ ->
            recorded.add(values[TradingNodePhase.CALLBACK.ordinal])
        }, { failures.incrementAndGet() }, { throw it }).use { queue ->
            val values = LongArray(TradingNodePhase.entries.size) { -1 }
            repeat(9) { batch ->
                repeat(2) { offset ->
                    values[TradingNodePhase.CALLBACK.ordinal] = batch * 2L + offset + 1
                    assertTrue(queue.offer(TradingNodeCallbackKind.ADMISSION, values, 0, 0, 0))
                    values[TradingNodePhase.CALLBACK.ordinal] = -1
                }
                queue.awaitRecorded()
            }
        }
        assertEquals(expected, recorded)
        assertEquals(0, failures.get())
    }

    @Test
    fun `가득 차서 거절한 offer 뒤에도 슬롯을 재사용하고 수락한 표본만 drain한다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failures = AtomicInteger()
        val recorded = mutableListOf<Long>()
        val accepted = mutableListOf<Long>()
        val queue =
            TradingNodeMetricHandoff(3, { _, values, _, _, _ ->
                if (recorded.isEmpty()) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                recorded.add(values[TradingNodePhase.CALLBACK.ordinal])
            }, { failures.incrementAndGet() }, { throw it })
        try {
            val values = LongArray(TradingNodePhase.entries.size) { -1 }

            fun offer(value: Long): Boolean {
                values[TradingNodePhase.CALLBACK.ordinal] = value
                val offered = queue.offer(TradingNodeCallbackKind.ADMISSION, values, 0, 0, 0)
                values[TradingNodePhase.CALLBACK.ordinal] = -1
                if (offered) accepted.add(value)
                return offered
            }

            assertTrue(offer(1))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(offer(2))
            assertTrue(offer(3))
            assertFalse(offer(99))
            release.countDown()
            queue.awaitRecorded()
            for (value in 4L..7L) {
                assertTrue(offer(value))
                queue.awaitRecorded()
            }
        } finally {
            release.countDown()
            queue.close()
        }
        assertEquals((1L..7L).toList(), accepted)
        assertEquals(accepted.size, recorded.size)
        assertEquals(accepted, recorded)
        assertEquals(0, failures.get())
    }

    @Test
    fun `비동기 시계 실패도 degraded와 실패 횟수에 남는다`() {
        SimpleMeterRegistry().withRegistry { registry ->
            TradingNodeMetrics(registry, asynchronous = true).use { metrics ->
                var unavailable = true
                val capture =
                    TradingNodeCallbackCapture {
                        if (unavailable) throw IllegalStateException("clock unavailable")
                        1L
                    }
                capture.complete()
                metrics.recordCompleted(capture, 128, "LEADER", "OFFERED")
                unavailable = false
                capture.reset()
                metrics.awaitRecorded()
                assertFalse(metrics.isOperational)
                assertEquals(1L, metrics.failureCount)
                assertEquals(1.0, registry.get("${TradingNodeMetrics.PREFIX}.observation_failures").counter().count())
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["normal", "clock", "counter"])
    fun `full fallback의 오류도 앞서 수락한 정상 표본을 생략하지 않는다`(failure: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val registry =
            object : SimpleMeterRegistry() {
                override fun newTimer(
                    id: Meter.Id,
                    distributionStatisticConfig: DistributionStatisticConfig,
                    pauseDetector: PauseDetector,
                ): Timer {
                    val actual = super.newTimer(id, distributionStatisticConfig, pauseDetector)
                    return object : Timer by actual {
                        override fun record(
                            amount: Long,
                            unit: TimeUnit,
                        ) {
                            if (calls.incrementAndGet() == 1) {
                                entered.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                            }
                            actual.record(amount, unit)
                        }
                    }
                }

                override fun newCounter(id: Meter.Id): io.micrometer.core.instrument.Counter {
                    val actual = super.newCounter(id)
                    return object : io.micrometer.core.instrument.Counter by actual {
                        override fun increment(amount: Double) {
                            if (failure == "counter" && id.name.endsWith("handoff_fallbacks")) error("counter failure")
                            actual.increment(amount)
                        }
                    }
                }
            }
        registry.withRegistry {
            TradingNodeMetrics(registry, asynchronous = true, queueCapacity = 2).use { metrics ->
                try {
                    var now = 0L
                    var clockFailed = false
                    val capture =
                        TradingNodeCallbackCapture {
                            check(!clockFailed) { "clock failure" }
                            now
                        }
                    capture.kind = TradingNodeCallbackKind.ADMISSION
                    now = 11
                    capture.complete()
                    metrics.recordCompleted(capture, 128, "LEADER", "OFFERED")
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    capture.reset()
                    capture.kind = TradingNodeCallbackKind.ADMISSION
                    now = 22
                    capture.complete()
                    metrics.recordCompleted(capture, 256, "LEADER", "OFFERED")
                    capture.reset()
                    capture.kind = TradingNodeCallbackKind.EXECUTION
                    now = 34
                    clockFailed = failure == "clock"
                    capture.complete()
                    val owner = Thread.currentThread()
                    val releaser =
                        Thread {
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                            while (owner.stackTrace.none { it.methodName == "awaitRecorded" }) {
                                check(System.nanoTime() < deadline)
                                Thread.yield()
                            }
                            release.countDown()
                        }
                    releaser.start()
                    metrics.recordCompleted(capture, 384, "FOLLOWER", "EXECUTED")
                    releaser.join(5000)
                    assertFalse(releaser.isAlive)
                    metrics.awaitRecorded()
                    val normal = failure == "normal"
                    val callback = registry.get(TradingNodeMetrics.DURATION).tag("phase", "callback").timer()
                    assertEquals(if (normal) 3L else 2L, callback.count())
                    assertEquals(if (normal) 34.0 else 22.0, callback.totalTime(TimeUnit.NANOSECONDS))
                    assertEquals(if (normal) 12.0 else 11.0, callback.max(TimeUnit.NANOSECONDS))
                    assertEquals(
                        2.0,
                        registry
                            .get(TradingNodeMetrics.CALLBACKS)
                            .tag("kind", "admission")
                            .counter()
                            .count(),
                    )
                    assertEquals(normal, metrics.isOperational)
                    assertEquals(if (normal) 0L else 1L, metrics.failureCount)
                    if (failure != "counter") {
                        assertEquals(1.0, registry.get("${TradingNodeMetrics.PREFIX}.handoff_fallbacks").counter().count())
                    }
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun `fallback drain 기한 초과도 recorder의 관측 실패로 남는다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val registry =
            object : SimpleMeterRegistry() {
                override fun newTimer(
                    id: Meter.Id,
                    distributionStatisticConfig: DistributionStatisticConfig,
                    pauseDetector: PauseDetector,
                ): Timer {
                    val actual = super.newTimer(id, distributionStatisticConfig, pauseDetector)
                    return object : Timer by actual {
                        override fun record(
                            amount: Long,
                            unit: TimeUnit,
                        ) {
                            if (calls.incrementAndGet() == 1) {
                                entered.countDown()
                                check(release.await(15, TimeUnit.SECONDS))
                            }
                            actual.record(amount, unit)
                        }
                    }
                }
            }
        registry.withRegistry {
            TradingNodeMetrics(registry, asynchronous = true, queueCapacity = 1).use { metrics ->
                try {
                    val capture = TradingNodeCallbackCapture { 0 }
                    capture.complete()
                    metrics.recordCompleted(capture, 128, "LEADER", "OFFERED")
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertFailsWith<IllegalStateException> { metrics.recordCompleted(capture, 256, "LEADER", "OFFERED") }
                    assertFalse(metrics.isOperational)
                    assertEquals(1L, metrics.failureCount)
                    assertEquals(1.0, registry.get("${TradingNodeMetrics.PREFIX}.observation_failures").counter().count())
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun `worker Error는 다음 명령을 기다리지 않고 소유 노드에 즉시 통지한다`() {
        val original = AssertionError("timer fatal")
        val notified = CountDownLatch(1)
        var observed: Error? = null
        val registry =
            object : SimpleMeterRegistry() {
                override fun newTimer(
                    id: Meter.Id,
                    distributionStatisticConfig: DistributionStatisticConfig,
                    pauseDetector: PauseDetector,
                ): Timer {
                    val actual = super.newTimer(id, distributionStatisticConfig, pauseDetector)
                    return object : Timer by actual {
                        override fun record(
                            amount: Long,
                            unit: TimeUnit,
                        ): Unit = throw original
                    }
                }
            }
        registry.withRegistry {
            val metrics =
                TradingNodeMetrics(registry, asynchronous = true, onFatal = {
                    observed = it
                    notified.countDown()
                })
            val capture = TradingNodeCallbackCapture { 0 }
            capture.complete()
            metrics.recordCompleted(capture, 128, "LEADER", "OFFERED")
            assertTrue(notified.await(5, TimeUnit.SECONDS))
            assertSame(original, observed)
            assertFalse(metrics.isOperational)
            assertSame(original, assertFailsWith<AssertionError> { metrics.awaitRecorded() })
            assertSame(original, assertFailsWith<AssertionError> { metrics.close() })
        }
    }

    @Test
    fun `발행과 닫기가 겹쳐도 수락한 표본은 전부 drain한다`() {
        repeat(64) {
            val accepted = AtomicInteger()
            val recorded = AtomicInteger()
            val started = CountDownLatch(1)
            val queue =
                TradingNodeMetricHandoff(64, { _, _, _, _, _ -> recorded.incrementAndGet() }, { error("metric failure") }, { throw it })
            val writer =
                Thread {
                    val values = LongArray(TradingNodePhase.entries.size) { -1 }
                    repeat(2000) { index ->
                        if (queue.offer(TradingNodeCallbackKind.ADMISSION, values, 0, 0, 0)) accepted.incrementAndGet()
                        if (index == 100) started.countDown()
                    }
                }
            try {
                writer.start()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                queue.close()
                writer.join(5000)
                assertFalse(writer.isAlive)
                assertEquals(accepted.get(), recorded.get())
            } finally {
                writer.join(5000)
                queue.close()
            }
        }
    }
}

private inline fun <R> SimpleMeterRegistry.withRegistry(action: (SimpleMeterRegistry) -> R): R =
    try {
        action(this)
    } finally {
        close()
    }
