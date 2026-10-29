package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLSession
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pure HTTP-future/checkpoint fixtures: no HTTP server, Aeron, profile or load is started. */
class HttpPerformanceConnectionWarmupTest {
    private val mapper = jacksonObjectMapper()
    private val uri = URI("http://fixture.invalid/api/trading/markets/BTC-USDT/orders")

    @Test
    fun `모든 유한 요청을 기다리기 전에 제출하고 같은 W0 원문과 최초 순번을 확인한다`() {
        val requests = mutableListOf<HttpRequest>()
        val pending = mutableListOf<CompletableFuture<HttpResponse<String>>>()
        var time = -20L
        val result =
            warmHttpPerformanceConnections(
                uri,
                107,
                count = 3,
                nanoTime = { time.also { time += 10 } },
                awaitAll = { all ->
                    assertEquals(3, requests.size)
                    assertTrue(pending.none { it.isDone })
                    pending.forEach { it.complete(reply()) }
                    all.join()
                },
                send = { request ->
                    requests.add(request)
                    CompletableFuture<HttpResponse<String>>().also(pending::add)
                },
            )

        assertTrue(result.succeeded)
        assertEquals(3, result.attempts.size)
        assertEquals(10L, result.elapsedNs)
        requests.forEach { request ->
            assertSame(requests.first(), request)
            assertEquals(uri, request.uri())
            assertEquals("POST", request.method())
            assertTrue(request.headers().firstValue(HTTP_PERFORMANCE_RUN_HEADER).isEmpty)
            assertTrue(request.headers().firstValue(HTTP_PERFORMANCE_REQUEST_HEADER).isEmpty)
        }
        assertEquals(
            mapper.readTree(mapper.writeValueAsBytes(performanceOrder("BUYER", "BUY", "W-0"))),
            mapper.readTree(body(requests.first())),
        )
        assertEquals(listOf(0, 1, 2), result.attempts.map { it.index })
        assertEquals(3, result.summary()["verifiedResponses"])
    }

    @Test
    fun `HTTP 상태와 모든 응답 식별자 및 최초 순번을 각각 검증하고 전체 시도를 남긴다`() {
        val mutations =
            listOf(
                mapOf("status" to "REJECTED"),
                mapOf("userId" to "OTHER"),
                mapOf("requestId" to "W-1"),
                mapOf("marketId" to "OTHER"),
                mapOf("orderId" to "OTHER"),
                mapOf("sequence" to 108L),
                mapOf("sequence" to BigInteger("18446744073709551723")),
            )
        var index = 0
        val result =
            warmHttpPerformanceConnections(uri, 107, count = mutations.size + 1) {
                val current = index++
                CompletableFuture.completedFuture(
                    if (current <
                        mutations.size
                    ) {
                        reply(changes = mutations[current])
                    } else {
                        reply(status = 503)
                    },
                )
            }

        assertFalse(result.succeeded)
        assertEquals(8, result.failedAttempts)
        assertEquals(8, index)
        assertTrue(result.attempts.all { it.responseKnown && it.validationFailures.isNotEmpty() })
        assertEquals(listOf("FIRST_SEQUENCE_MISMATCH"), result.attempts[5].validationFailures)
        assertEquals(listOf("FIRST_SEQUENCE_MISMATCH"), result.attempts[6].validationFailures)
        assertEquals(listOf("HTTP_STATUS_NOT_202"), result.attempts[7].validationFailures)
        assertTrue(result.attempts.all { it.responseBody != null })
    }

    @Test
    fun `동기 제출 예외와 비동기 EOF 원인 및 잘못된 JSON도 후속 응답을 버리지 않고 남긴다`() {
        var calls = 0
        val result =
            warmHttpPerformanceConnections(uri, 107, count = 4) {
                when (calls++) {
                    0 -> throw IOException("submit failed")
                    1 -> CompletableFuture.failedFuture(CompletionException("headers had no bytes", EOFException("EOF fixture")))
                    2 -> CompletableFuture.completedFuture(reply(raw = "not-json"))
                    else -> CompletableFuture.completedFuture(reply())
                }
            }

        assertFalse(result.succeeded)
        assertEquals(4, calls)
        assertEquals(4, result.attempts.size)
        assertEquals(3, result.failedAttempts)
        assertFalse(result.attempts[0].submittedToHttpClient)
        assertTrue(result.attempts[0].exception!!.contains("submit failed"))
        assertTrue(result.attempts[1].exception!!.contains("EOFException: EOF fixture"))
        assertTrue(result.attempts[1].exception!!.contains("headers had no bytes"))
        assertEquals("not-json", result.attempts[2].responseBody)
        assertEquals(listOf("RESPONSE_JSON_INVALID"), result.attempts[2].validationFailures)
        assertTrue(result.attempts[3].verified)
    }

    @Test
    fun `준비 제한시간의 미확인 응답은 취소를 시도해도 성공으로 바꾸지 않는다`() {
        val pending = CompletableFuture<HttpResponse<String>>()
        var calls = 0
        val result =
            warmHttpPerformanceConnections(
                uri,
                107,
                count = 2,
                awaitAll = { throw TimeoutException("fixture preparation deadline") },
                send = { if (calls++ == 0) CompletableFuture.completedFuture(reply()) else pending },
            )

        assertFalse(result.succeeded)
        assertEquals(2, calls)
        assertEquals(2, result.attempts.size)
        assertTrue(result.waitFailure!!.contains("fixture preparation deadline"))
        assertTrue(result.attempts[0].verified)
        assertFalse(result.attempts[1].responseKnown)
        assertTrue(pending.isCancelled)
        assertEquals(1, result.summary()["unknownResponses"])
        assertFalse(pending.complete(reply()))
        assertFalse(result.succeeded)
    }

    @Test
    fun `최대 천 시도의 오류 수와 응답 및 예외의 잘림을 유한하게 보존한다`() {
        var calls = 0
        val result =
            warmHttpPerformanceConnections(uri, 107) {
                when (calls++) {
                    0 -> CompletableFuture.completedFuture(reply(raw = "x".repeat(10_000)))
                    1 -> throw IOException("x".repeat(10_000))
                    else -> CompletableFuture.completedFuture(reply())
                }
            }

        assertEquals(1000, calls)
        assertEquals(1000, result.attempts.size)
        assertFalse(result.succeeded)
        assertEquals(2, result.failedAttempts)
        assertTrue(result.attempts[0].responseBodyTruncated)
        assertEquals(10_000, result.attempts[0].responseBodyChars)
        assertEquals(4096, result.attempts[0].responseBody!!.length)
        assertTrue(result.attempts[1].exceptionTruncated)
        assertEquals(4096, result.attempts[1].exception!!.length)
        assertEquals(2, result.summary()["truncatedAttemptEvidence"])
    }

    @Test
    fun `잘못된 warmup 건수나 최초 순번은 HTTP 제출 전에 거절한다`() {
        var calls = 0
        for ((count, sequence) in listOf(0 to 107L, 1001 to 107L, 1 to 0L)) {
            assertFailsWith<IllegalArgumentException> {
                warmHttpPerformanceConnections(uri, sequence, count = count) {
                    calls++
                    CompletableFuture.completedFuture(reply())
                }
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `interrupt와 wrap 시계 뒤에도 실패 raw는 직렬화 가능하고 음수 차분은 성공이 아니다`() {
        val previouslyInterrupted = Thread.interrupted()
        try {
            val pending = CompletableFuture<HttpResponse<String>>()
            var clockCalls = 0
            val interrupted =
                warmHttpPerformanceConnections(
                    uri,
                    107,
                    count = 1,
                    awaitAll = { throw InterruptedException("fixture interrupted wait") },
                    nanoTime = { if (clockCalls++ == 0) Long.MAX_VALUE - 5 else Long.MIN_VALUE + 4 },
                    send = { pending },
                )
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(interrupted.succeeded)
            assertEquals(10L, interrupted.elapsedNs)
            val saved = mapper.readTree(mapper.writeValueAsString(interrupted))
            assertTrue(saved.path("waitFailure").asString().contains("fixture interrupted wait"))
            assertEquals(1, saved.path("attempts").size())
            assertFalse(
                saved
                    .path("attempts")
                    .path(0)
                    .path("responseKnown")
                    .asBoolean(),
            )
            Thread.interrupted()
            var calls = 0
            val invalidClock =
                warmHttpPerformanceConnections(
                    uri,
                    107,
                    count = 1,
                    nanoTime = { if (calls++ == 0) 0L else -1L },
                    send = { CompletableFuture.completedFuture(reply()) },
                )
            assertFalse(invalidClock.succeeded)
            assertEquals(-1L, invalidClock.elapsedNs)
            assertEquals(false, invalidClock.summary()["clockDifferenceValid"])
        } finally {
            Thread.interrupted()
            if (previouslyInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun `최초 W0 순번은 세 사본의 실제 record에서 읽고 누락 중복 불일치를 거절한다`() {
        assertEquals(107L, httpPerformanceWarmConnectionSequence(checkpoints()))
        assertFailsWith<IllegalStateException> { httpPerformanceWarmConnectionSequence(checkpoints() - 2) }
        assertFailsWith<IllegalStateException> { httpPerformanceWarmConnectionSequence(checkpoints(sequenceByMember = mapOf(2 to 108L))) }
        assertFailsWith<IllegalStateException> { httpPerformanceWarmConnectionSequence(checkpoints(duplicateOriginal = true)) }
        assertFailsWith<IllegalStateException> {
            httpPerformanceWarmConnectionSequence(
                checkpoints(orderChanges = mapOf("price" to 9999L)),
            )
        }
    }

    @Test
    fun `중복 로그 적용 위치만 증가할 수 있고 금융 이력과 최초 context 변경은 거절한다`() {
        val before = checkpoints(position = 1000)
        checkHttpPerformanceWarmConnectionState(before, checkpoints(position = 2000))
        assertFailsWith<IllegalStateException> {
            checkHttpPerformanceWarmConnectionState(before, checkpoints(position = 2000, context = "changed-original-context"))
        }
        assertFailsWith<IllegalStateException> {
            checkHttpPerformanceWarmConnectionState(before, checkpoints(position = 2000, tradingCount = 1401))
        }
        assertFailsWith<IllegalStateException> { checkHttpPerformanceWarmConnectionState(before, checkpoints(position = 999)) }
    }

    private fun reply(
        status: Int = 202,
        changes: Map<String, Any> = emptyMap(),
        raw: String? = null,
    ): HttpResponse<String> {
        val text =
            raw ?: mapper.writeValueAsString(
                mapOf(
                    "status" to "ACCEPTED",
                    "userId" to "BUYER",
                    "requestId" to "W-0",
                    "marketId" to "BTC-USDT",
                    "orderId" to "W-0",
                    "sequence" to 107L,
                ) +
                    changes,
            )
        return object : HttpResponse<String> {
            override fun statusCode(): Int = status

            override fun request(): HttpRequest = performanceHttpRequest(uri, "fixture")

            override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

            override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

            override fun body(): String = text

            override fun sslSession(): Optional<SSLSession> = Optional.empty()

            override fun uri(): URI = this@HttpPerformanceConnectionWarmupTest.uri

            override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        }
    }

    private fun checkpoints(
        position: Long = 1000,
        sequenceByMember: Map<Int, Long> = emptyMap(),
        duplicateOriginal: Boolean = false,
        orderChanges: Map<String, Any> = emptyMap(),
        context: String = "original-context",
        tradingCount: Int = 1400,
    ): Map<Int, JsonNode> =
        (0..2).associateWith { member ->
            val original =
                mapOf(
                    "requestId" to "W-0",
                    "order" to (performanceOrder("BUYER", "BUY", "W-0") - "requestId" + ("marketId" to "BTC-USDT") + orderChanges),
                    "result" to mapOf("outcome" to "ACCEPTED", "record" to mapOf("sequence" to (sequenceByMember[member] ?: 107L))),
                    "ledgerTransactionId" to context,
                )
            mapper.valueToTree<JsonNode>(
                mapOf(
                    "applied" to
                        mapOf(
                            "position" to position,
                            "state" to
                                mapOf(
                                    "lastSequence" to 1400,
                                    "requests" to if (duplicateOriginal) listOf(original, original) else listOf(original),
                                    "transactions" to listOf("original-ledger"),
                                ),
                        ),
                    "trading" to mapOf("matchingResultCount" to tradingCount, "completions" to listOf("original-completion")),
                ),
            )
        }

    private fun body(request: HttpRequest): String {
        val bytes = ByteArrayOutputStream()
        val complete = CompletableFuture<String>()
        request.bodyPublisher().orElseThrow().subscribe(
            object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)

                override fun onNext(item: ByteBuffer) {
                    val part = ByteArray(item.remaining())
                    item.get(part)
                    bytes.write(part)
                }

                override fun onError(throwable: Throwable) {
                    complete.completeExceptionally(throwable)
                }

                override fun onComplete() {
                    complete.complete(bytes.toString(StandardCharsets.UTF_8))
                }
            },
        )
        return complete.join()
    }
}
