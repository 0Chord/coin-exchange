package com.exchange.core.api.trading.infrastructure.aeron

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.distribution.pause.PauseDetector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayMetricsTest {
    @Test
    fun `같은 JVM의 ns 차분을 각 고정 단계에 기록하고 누적하지 않은 단계는 표본이 없다`() {
        SimpleMeterRegistry().withRegistry { registry ->
            GatewayMetrics(registry).use { metrics ->
                val expected = listOf(2L, 10L, 3L, 16L, 20L, 52L, 7L)
                GatewayMetrics.Phase.entries
                    .zip(expected)
                    .forEach { (phase, ns) -> metrics.duration(phase, ns) }
                GatewayMetrics.Phase.entries.zip(expected).forEach { (phase, ns) ->
                    val timer = checkNotNull(registry.find(GatewayMetrics.DURATION).tag("phase", phase.tag).timer())
                    assertEquals(1L, timer.count())
                    assertEquals(ns.toDouble(), timer.totalTime(TimeUnit.NANOSECONDS))
                }
                assertTrue(metrics.isOperational)
                assertEquals(31, registry.meters.size, "고정 Timer 7 + Counter 19 + Gauge 5")
                assertTrue(registry.meters.all { meter -> meter.id.tags.all { it.key in setOf("phase", "outcome", "status") } })
            }
        }
    }

    @Test
    fun `Gauge는 강한 현재값을 사용하고 닫은 뒤 같은 registry에 새 상태를 등록한다`() {
        SimpleMeterRegistry().withRegistry { registry ->
            GatewayMetrics(registry).use { metrics ->
                metrics.sizes(3, 2, 5)
                metrics.completionStarted()
                assertEquals(3.0, registry.get("${GatewayMetrics.PREFIX}.queued").gauge().value())
                assertEquals(2.0, registry.get("${GatewayMetrics.PREFIX}.inflight").gauge().value())
                assertEquals(5.0, registry.get("${GatewayMetrics.PREFIX}.reserved_keys").gauge().value())
                assertEquals(1.0, registry.get("${GatewayMetrics.PREFIX}.active_completions").gauge().value())
                metrics.completionFinished()
            }
            assertNull(registry.find("${GatewayMetrics.PREFIX}.queued").gauge())
            GatewayMetrics(registry).use { replacement ->
                replacement.sizes(1, 0, 1)
                assertTrue(replacement.isOperational)
                assertEquals(1.0, registry.get("${GatewayMetrics.PREFIX}.queued").gauge().value())
                assertEquals(0.0, registry.get("${GatewayMetrics.PREFIX}.active_completions").gauge().value())
            }
        }
    }

    @Test
    fun `동시에 두 소유자가 생겨도 이전 Gauge를 새 Gateway의 값인 것처럼 재사용하지 않는다`() {
        SimpleMeterRegistry().withRegistry { registry ->
            GatewayMetrics(registry).use { first ->
                first.sizes(2, 0, 2)
                GatewayMetrics(registry).use { second ->
                    assertFalse(second.isOperational)
                    second.sizes(100, 0, 100)
                    assertEquals(2.0, registry.get("${GatewayMetrics.PREFIX}.queued").gauge().value())
                }
                assertNotNull(registry.find("${GatewayMetrics.PREFIX}.queued").gauge())
            }
        }
    }

    @Test
    fun `등록 예외나 DENY NOOP은 거래 예외가 아니라 비정상 계측으로 남는다`() {
        SimpleMeterRegistry().withRegistry { registry ->
            registry.config().meterFilter(
                object : MeterFilter {
                    override fun map(id: Meter.Id): Meter.Id = throw IllegalStateException("registry unavailable")
                },
            )
            GatewayMetrics(registry).use { metrics ->
                assertFalse(metrics.isOperational)
                metrics.duration(GatewayMetrics.Phase.QUEUE, 3)
                metrics.submission(GatewayMetrics.Submission.NEW)
            }
        }
        SimpleMeterRegistry().withRegistry { registry ->
            registry.config().meterFilter(MeterFilter.deny())
            GatewayMetrics(registry).use { metrics -> assertFalse(metrics.isOperational) }
        }
    }

    @Test
    fun `Timer 기록 예외 뒤 반복 기록을 중단하고 유한한 degraded 상태를 남긴다`() {
        ThrowingTimerRegistry().withRegistry { registry ->
            GatewayMetrics(registry).use { metrics ->
                metrics.duration(GatewayMetrics.Phase.QUEUE, 10)
                metrics.duration(GatewayMetrics.Phase.QUEUE, 20)
                metrics.submission(GatewayMetrics.Submission.NEW)
                assertFalse(metrics.isOperational)
                assertEquals(1, registry.attempts)
                assertEquals(1.0, registry.get("${GatewayMetrics.PREFIX}.degraded").gauge().value())
                assertEquals(
                    0.0,
                    registry
                        .get(GatewayMetrics.SUBMISSIONS)
                        .tag("outcome", "new")
                        .counter()
                        .count(),
                )
            }
        }
    }

    private class ThrowingTimerRegistry : SimpleMeterRegistry() {
        var attempts = 0

        override fun newTimer(
            id: Meter.Id,
            distributionStatisticConfig: DistributionStatisticConfig,
            pauseDetector: PauseDetector,
        ): Timer {
            val timer = super.newTimer(id, distributionStatisticConfig, pauseDetector)
            return object : Timer by timer {
                override fun record(
                    amount: Long,
                    unit: TimeUnit,
                ) {
                    attempts++
                    throw IllegalStateException("record unavailable")
                }
            }
        }
    }

    private inline fun <T : SimpleMeterRegistry, R> T.withRegistry(block: (T) -> R): R =
        try {
            block(this)
        } finally {
            close()
        }
}
