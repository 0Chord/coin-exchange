package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HaTradingProbeMetricsTest {
    @Test
    fun `기한이 지난 CAPTURE는 다음 drain에서 회수하고 빈 fast path로 돌아간다`() {
        val boundary = HaProbeServiceBoundary()
        assertFailsWith<TimeoutException> { boundary.capture(1) }
        assertEquals(1, boundary.completePending())
        repeat(8) { assertEquals(0, boundary.completePending()) }
    }

    @Test
    fun `CAPTURE 발행과 drain이 겹쳐도 drain 뒤의 새 요청 flag를 잃지 않는다`() {
        val boundary = HaProbeServiceBoundary()
        repeat(8) {
            val start = CountDownLatch(1)
            val ready = CountDownLatch(2)
            val results = List(2) { CompletableFuture<Map<String, Any?>>() }
            val requesters =
                results.map { result ->
                    Thread {
                        try {
                            ready.countDown()
                            check(start.await(2, TimeUnit.SECONDS))
                            result.complete(boundary.capture(2_000))
                        } catch (failure: Throwable) {
                            result.completeExceptionally(failure)
                        }
                    }.also { it.start() }
                }
            try {
                check(ready.await(2, TimeUnit.SECONDS))
                start.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (results.any { !it.isDone } && System.nanoTime() - deadline < 0) {
                    boundary.completePending()
                    Thread.yield()
                }
                results.forEach { assertEquals(Thread.currentThread().id, it.get(2, TimeUnit.SECONDS)["threadId"]) }
            } finally {
                start.countDown()
                requesters.forEach { it.join(2_000) }
            }
            assertFalse(requesters.any { it.isAlive })
            assertEquals(0, boundary.completePending())
        }
    }

    @Test
    fun `CAPTURE counter는 요청 thread가 아닌 서비스를 수행한 thread 경계에서 읽는다`() {
        val boundary = HaProbeServiceBoundary()
        val result = CompletableFuture<Map<String, Any?>>()
        val requester =
            Thread {
                try {
                    result.complete(boundary.capture(2_000))
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        requester.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!result.isDone && System.nanoTime() - deadline < 0) {
                boundary.completePending()
                Thread.yield()
            }
            assertEquals(Thread.currentThread().id, result.get(2, TimeUnit.SECONDS)["threadId"])
        } finally {
            requester.join(2_000)
        }
        assertFalse(requester.isAlive)
        assertEquals(0, boundary.completePending())
    }

    @Test
    fun `지원하지 않거나 비활성인 CPU 할당 counter는 0으로 바꾸지 않는다`() {
        val unsupported = haProbeCounter(false, false) { error("must not read unsupported counter") }
        val disabled = haProbeCounter(true, false) { error("must not read disabled counter") }
        assertEquals("unsupported", unsupported["status"])
        assertEquals("disabled", disabled["status"])
        assertNull(unsupported["value"])
        assertNull(disabled["value"])
        assertEquals(mapOf("status" to "available", "value" to 0L), haProbeCounter(true, true) { 0 })
    }

    @Test
    fun `지원 counter의 음수와 읽기 실패도 명시적인 unavailable이다`() {
        val negative = haProbeCounter(true, true) { -1 }
        val failed = haProbeCounter(true, true) { throw UnsupportedOperationException("counter unavailable") }
        assertEquals("unavailable", negative["status"])
        assertEquals("unavailable", failed["status"])
        assertNull(negative["value"])
        assertNull(failed["value"])
    }
}
