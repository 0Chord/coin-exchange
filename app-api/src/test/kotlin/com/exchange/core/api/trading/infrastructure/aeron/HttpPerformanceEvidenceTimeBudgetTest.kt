package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Policy and cooperative-clock tests only: no Docker, child JVM, sleep, workload or large file. */
class HttpPerformanceEvidenceTimeBudgetTest {
    @Test
    fun `burst와120k까지의sustained는기존예산이고초과한명시cohort만확장한다`() {
        val standard = HttpPerformanceEvidenceTimeBudget.STANDARD
        assertEquals(standard, directAeronEvidenceTimeBudget(DirectAeronCapacityOptions(smoke = true)))
        assertEquals(standard, directAeronEvidenceTimeBudget(DirectAeronCapacityOptions(smoke = false)))
        for (rate in listOf(1, 1000, 2000)) {
            assertEquals(standard, directAeronEvidenceTimeBudget(DirectAeronCapacityOptions(false, rate, 60)))
        }
        for (rate in listOf(2001, 3000, 5000, 10000)) {
            val budget = directAeronEvidenceTimeBudget(DirectAeronCapacityOptions(false, rate, 60))
            assertEquals(HttpPerformanceEvidenceTimeBudget.LARGE_SUSTAINED, budget)
            assertEquals(600_000L, budget.checkpointCaptureMaxMs)
            assertEquals(600_000L, budget.preparationObservationMs)
        }
        assertEquals(120_000L, standard.checkpointCaptureMaxMs)
        assertEquals(120_000L, standard.preparationObservationMs)
        assertEquals(10_000L, standard.nodeShutdownReportWaitMs)
        assertEquals(15, standard.nodeStopGraceSeconds)
        assertEquals(20_000L, standard.nodeStopCommandTimeoutMs)
    }

    @Test
    fun `기본과확장예산모두유한하고작은node계획에확장프로필을주입하지못한다`() {
        HttpPerformanceEvidenceTimeBudget.STANDARD.requireCheckpointTimeout(120_000)
        assertFailsWith<IllegalArgumentException> { HttpPerformanceEvidenceTimeBudget.STANDARD.requireCheckpointTimeout(120_001) }
        val large = HttpPerformanceEvidenceTimeBudget.LARGE_SUSTAINED
        large.requireCheckpointTimeout(600_000)
        for (timeout in listOf(Long.MIN_VALUE, -1L, 0L, 600_001L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { large.requireCheckpointTimeout(timeout) }
        }
        for (orders in listOf(Int.MIN_VALUE, 0, 2000, 60_000, 120_000, 600_001, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { httpPerformanceNodeEvidenceTimeBudget("LARGE_SUSTAINED", orders) }
        }
        assertEquals(large, httpPerformanceNodeEvidenceTimeBudget("LARGE_SUSTAINED", 180_000))
        assertEquals(HttpPerformanceEvidenceTimeBudget.STANDARD, httpPerformanceNodeEvidenceTimeBudget(null, 60_000))
        assertFailsWith<IllegalArgumentException> { httpPerformanceNodeEvidenceTimeBudget("6000000", 180_000) }
        val graceMs = TimeUnit.SECONDS.toMillis(large.nodeStopGraceSeconds.toLong())
        assertTrue(large.nodeShutdownReportWaitMs < graceMs && graceMs < large.nodeStopCommandTimeoutMs)
    }

    @Test
    fun `capture가끝나지않으면같은600초deadline에서실제timeout으로실패한다`() {
        var now = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1)
        var polls = 0
        val failure =
            assertFailsWith<IllegalStateException> {
                awaitHttpPerformanceCheckpointFileCapture(
                    "final-token 세 노드 후속 체크포인트",
                    600_000,
                    "/small-fixture-report",
                    predicate = {
                        polls++
                        false
                    },
                    nanoTime = { now },
                    pause = { now += TimeUnit.SECONDS.toNanos(200) },
                )
            }
        assertEquals(3, polls)
        assertTrue(failure.message.orEmpty().contains("final-token 세 노드 후속 체크포인트 제한 시간 초과"))
    }

    @Test
    fun `늦게끝난logs poll도600초를넘은capture성공으로바꾸지않는다`() {
        var now = 0L
        assertFailsWith<IllegalStateException> {
            awaitHttpPerformanceCheckpointFileCapture(
                "financial-final-token 세 노드 후속 체크포인트",
                600_000,
                "/small-fixture-report",
                predicate = {
                    now += TimeUnit.MILLISECONDS.toNanos(600_001)
                    true
                },
                nanoTime = { now },
                pause = { error("late successful predicate must fail immediately") },
            )
        }
    }

    @Test
    fun `capture I O 오류와interrupt를성공이나재시도로바꾸지않는다`() {
        val original = IOException("docker logs failed")
        assertSame(
            original,
            assertFailsWith<IOException> {
                awaitHttpPerformanceCheckpointFileCapture("final", 600_000, "/small-fixture-report", predicate = { throw original })
            },
        )
        val wasInterrupted = Thread.interrupted()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedException> {
                awaitHttpPerformanceCheckpointFileCapture("final", 600_000, "/small-fixture-report", predicate = { true })
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }
}
