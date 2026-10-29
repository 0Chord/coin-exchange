package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletionException
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpPerformanceClientStagesTest {
    @Test
    fun `응답 원문의 UTF8 조각을 그대로 해석하고 body 완료와 후속 getBody 호출을 구분한다`() {
        val clock = AtomicLong()
        val trace = HttpPerformanceResponseTrace { clock.addAndGet(10) }
        val subscriber = trace.handler().apply(responseInfo())
        var demand = 0L
        subscriber.onSubscribe(
            object : Flow.Subscription {
                override fun request(n: Long) {
                    demand = n
                }

                override fun cancel() = error("must not cancel")
            },
        )
        val original = "한글 response"
        val bytes = original.toByteArray(StandardCharsets.UTF_8)
        // Split inside the first multibyte character to exercise the unchanged JDK decoder.
        subscriber.onNext(listOf(ByteBuffer.wrap(bytes.copyOfRange(0, 1))))
        subscriber.onNext(listOf(ByteBuffer.wrap(bytes.copyOfRange(1, bytes.size))))
        subscriber.onComplete()
        assertEquals(Long.MAX_VALUE, demand)
        assertEquals(10L, trace.headersEnteredNs)
        assertEquals(20L, trace.firstNextNs)
        assertEquals(30L, trace.lastNextNs)
        assertEquals(40L, trace.onCompleteNs)
        // The delegate completes its future inside onComplete, before the wrapper's getBody call.
        assertEquals(50L, trace.bodyCompletedNs)
        assertNull(trace.getBodyEnteredNs)
        assertEquals(original, subscriber.body.toCompletableFuture().join())
        assertEquals(60L, trace.getBodyEnteredNs)
    }

    @Test
    fun `빈 응답 본문은 onNext 시각을 만들지 않고 정상 완료한다`() {
        val clock = AtomicLong()
        val trace = HttpPerformanceResponseTrace { clock.addAndGet(10) }
        val subscriber = trace.handler().apply(responseInfo())
        subscriber.onSubscribe(noOpSubscription())
        val body = subscriber.body
        subscriber.onComplete()
        assertEquals("", body.toCompletableFuture().join())
        assertNull(trace.firstNextNs)
        assertNull(trace.lastNextNs)
        assertEquals(30L, trace.onCompleteNs)
        assertEquals(40L, trace.bodyCompletedNs)
    }

    @Test
    fun `응답 본문 실패를 전달하고 성공 완료 시각으로 채우지 않는다`() {
        val trace = HttpPerformanceResponseTrace { 42 }
        val subscriber = trace.handler().apply(responseInfo())
        subscriber.onSubscribe(noOpSubscription())
        val body = subscriber.body
        val failure = IllegalStateException("response failed")
        subscriber.onError(failure)
        assertSame(failure, assertFailsWith<CompletionException> { body.toCompletableFuture().join() }.cause)
        assertNull(trace.onCompleteNs)
        assertNull(trace.bodyCompletedNs)
    }

    @Test
    fun `동일 JVM의 callback 잠금 대기와 해석을 분리하고 음수 절대시각도 허용한다`() {
        val stages = HttpPerformanceClientStages(true)
        val http = HttpPerformanceClientStage("HTTP", "buyer", "R1", null, -10, 10, 25)
        val marker = HttpPerformanceClientStage("MARKER", "buyer", "R1", 0, -20, -15, null)
        stages.record(http)
        stages.record(marker)
        assertEquals(20L, http.observationLockWaitNs)
        assertEquals(15L, http.interpretationNs)
        assertEquals(5L, marker.observationLockWaitNs)
        assertTrue((stages.snapshot()["errors"] as List<*>).isEmpty())
    }

    @Test
    fun `중복과 음수 구간은 오류이며 초과 표본과 identity도 유한하게 유지한다`() {
        val stages = HttpPerformanceClientStages(true, 1)
        stages.record(HttpPerformanceClientStage("HTTP", "u", "r", null, 3, 2, null))
        stages.record(HttpPerformanceClientStage("HTTP", "u", "r", null, 3, 3, null))
        repeat(100) { stages.record(HttpPerformanceClientStage("HTTP", "u", "new-$it", null, 3, 4, null)) }
        val snapshot = stages.snapshot()
        assertEquals(1, (snapshot["samples"] as List<*>).size)
        assertEquals(100L, snapshot["overflow"])
        assertEquals(setOf("CLIENT_STAGE_NEGATIVE_DURATION", "CLIENT_STAGE_DUPLICATE"), (snapshot["errors"] as List<*>).toSet())
    }

    @Test
    fun `상세 기록 off는 원시 표본을 보관하지 않는다`() {
        val stages = HttpPerformanceClientStages(false)
        stages.record(HttpPerformanceClientStage("HTTP", "u", "r", null, 3, 2, null))
        assertTrue((stages.snapshot()["samples"] as List<*>).isEmpty())
        assertTrue((stages.snapshot()["errors"] as List<*>).isEmpty())
    }

    private fun responseInfo(): HttpResponse.ResponseInfo =
        object : HttpResponse.ResponseInfo {
            override fun statusCode(): Int = 202

            override fun headers(): HttpHeaders =
                HttpHeaders.of(mapOf("Content-Type" to listOf("application/json; charset=UTF-8"))) { _, _ -> true }

            override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        }

    private fun noOpSubscription(): Flow.Subscription =
        object : Flow.Subscription {
            override fun request(n: Long) = Unit

            override fun cancel() = Unit
        }
}
