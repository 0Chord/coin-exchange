package com.exchange.core.api.trading.infrastructure.aeron

import java.io.EOFException
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpPerformanceHttpFailuresTest {
    @Test
    fun `진단을 명시한 요청에만 두 식별 헤더를 붙이고 본문 경로와 timeout은 유지한다`() {
        val uri = URI("http://localhost/api/trading/markets/BTC-USDT/orders")
        val ordinary = performanceHttpRequest(uri, "unchanged-body")
        val diagnostic = performanceHttpRequest(uri, "unchanged-body", HttpPerformanceHttpIdentity("run-1", "M-0"))
        assertTrue(ordinary.headers().firstValue(HTTP_PERFORMANCE_RUN_HEADER).isEmpty)
        assertTrue(ordinary.headers().firstValue(HTTP_PERFORMANCE_REQUEST_HEADER).isEmpty)
        assertEquals("run-1", diagnostic.headers().firstValue(HTTP_PERFORMANCE_RUN_HEADER).orElseThrow())
        assertEquals("M-0", diagnostic.headers().firstValue(HTTP_PERFORMANCE_REQUEST_HEADER).orElseThrow())
        assertEquals(ordinary.uri(), diagnostic.uri())
        assertEquals("POST", diagnostic.method())
        assertEquals(Duration.ofSeconds(6), diagnostic.timeout().orElseThrow())
        assertEquals(ordinary.headers().firstValue("Content-Type"), diagnostic.headers().firstValue("Content-Type"))
        assertEquals(ordinary.bodyPublisher().orElseThrow().contentLength(), diagnostic.bodyPublisher().orElseThrow().contentLength())
        assertFalse(parseHttpPerformanceOptions(arrayOf("--http-overload-diagnostic")).httpBoundaryDiagnostics)
        assertFalse(parseHttpPerformanceOptions(arrayOf("--optimized-smoke")).httpBoundaryDiagnostics)
        val options = parseHttpPerformanceOptions(arrayOf("--http-boundary-diagnostic"))
        assertTrue(options.httpBoundaryDiagnostics && options.diagnosticProfiles && options.stageDiagnostics)
        assertEquals(listOf(5000), options.rates)
        assertEquals(1000, options.orders)
    }

    @Test
    fun `EOF의 wrapper 메시지 cause suppressed stack을 원래 예외와 함께 보존한다`() {
        val eof = EOFException("closed while parsing headers")
        val failure = CompletionException("header parser received no bytes", eof)
        failure.addSuppressed(IllegalStateException("suppressed detail"))
        val recorder = HttpPerformanceHttpFailures("run-1", 2)
        recorder.record("M-0", failure)
        val entry = entries(recorder).single()
        val text = entry["stackTrace"] as String
        assertTrue(text.contains("CompletionException: header parser received no bytes"))
        assertTrue(text.contains("Caused by: java.io.EOFException: closed while parsing headers"))
        assertTrue(text.contains("Suppressed: java.lang.IllegalStateException: suppressed detail"))
        assertTrue(text.contains("HttpPerformanceHttpFailuresTest"))
        assertEquals(EOFException::class.java.name, entry["deepestTypeWithinChainLimit"])
        assertEquals(false, entry["truncated"])
        assertEquals("closed while parsing headers", eof.message)
        assertEquals(eof, failure.cause)
    }

    @Test
    fun `N과 건별 및 전체 문자 상한을 지키고 초과 잘림 중복을 숨기지 않는다`() {
        val recorder = HttpPerformanceHttpFailures("run-1", 2, maxCharactersPerFailure = 100, maxTotalCharacters = 150)
        val huge = IllegalStateException("x".repeat(10_000))
        recorder.record("M-0", huge)
        recorder.record("M-0", huge)
        recorder.record("M-1", huge)
        recorder.record("M-2", huge)
        recorder.record("W-0", huge)
        val snapshot = recorder.snapshot()
        val rows = entries(recorder)
        assertEquals(2, rows.size)
        assertEquals(listOf(100, 50), rows.map { (it["stackTrace"] as String).length })
        assertTrue(rows.all { it["truncated"] == true && (it["discardedCharacters"] as Long) > 0 })
        assertEquals(150, snapshot["retainedCharacters"])
        assertEquals(1L, snapshot["duplicateCount"])
        assertEquals(2L, snapshot["invalidIdentityCount"])
        assertEquals(0L, snapshot["captureFailureCount"])
        assertNull(httpPerformanceMeasuredIndex("M-01", 2))
        assertNull(httpPerformanceMeasuredIndex("M--1", 2))
        assertNull(httpPerformanceMeasuredIndex("M-2000", 2000))
    }

    @Test
    fun `예외 원인의 순환도 유한하게 보존하고 탐색 잘림을 표시한다`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        first.initCause(second)
        second.initCause(first)
        val recorder = HttpPerformanceHttpFailures("run-1", 1)
        recorder.record("M-0", first)
        val entry = entries(recorder).single()
        assertEquals(true, entry["causeInspectionTruncated"])
        assertTrue((entry["stackTrace"] as String).contains("CIRCULAR REFERENCE"))
        assertEquals(0L, recorder.snapshot()["captureFailureCount"])
    }

    @Test
    fun `결과는 기록됐으나 stack 기록이 아직 끝나지 않았으면 누락을 명시한다`() {
        val recorder = HttpPerformanceHttpFailures("run-1", 1)
        recorder.expect("M-0")
        assertEquals(1L, recorder.snapshot()["capturePendingCount"])
        assertEquals(listOf("M-0"), recorder.snapshot()["capturePendingRequestIds"])
        recorder.record("M-0", EOFException("original"))
        assertEquals(0L, recorder.snapshot()["capturePendingCount"])
        assertEquals(1, entries(recorder).size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun entries(recorder: HttpPerformanceHttpFailures): List<Map<String, Any?>> =
        recorder.snapshot()["failures"] as List<Map<String, Any?>>
}
