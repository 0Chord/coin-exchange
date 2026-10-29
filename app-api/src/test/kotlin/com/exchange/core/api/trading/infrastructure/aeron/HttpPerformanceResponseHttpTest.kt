package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(20)
class HttpPerformanceResponseHttpTest {
    @Test
    fun `실제 HTTP 응답의 UTF8 본문과 상태는 ofString과 같고 소비 시각을 기록한다`() {
        val original = "{\"message\":\"주문 승인 € 😀\"}\n"
        val bytes = original.toByteArray(StandardCharsets.UTF_8)
        val plain = receive(HttpResponse.BodyHandlers.ofString()) { socket -> writeResponse(socket, bytes) }
        val trace = HttpPerformanceResponseTrace()
        val startedNs = System.nanoTime()
        val traced = receive(trace.handler()) { socket -> writeResponse(socket, bytes) }
        val finishedNs = System.nanoTime()

        assertEquals(original, plain.body())
        assertEquals(plain.body(), traced.body())
        assertEquals(202, traced.statusCode())
        assertEquals(plain.statusCode(), traced.statusCode())
        assertEquals(plain.headers().map(), traced.headers().map())
        assertSuccessfulTrace(trace, startedNs, finishedNs, hasBody = true)
    }

    @Test
    fun `실제 빈 HTTP 응답도 ofString과 같고 없는 본문 수신 시각을 만들지 않는다`() {
        val plain = receive(HttpResponse.BodyHandlers.ofString()) { socket -> writeResponse(socket, byteArrayOf()) }
        val trace = HttpPerformanceResponseTrace()
        val startedNs = System.nanoTime()
        val traced = receive(trace.handler()) { socket -> writeResponse(socket, byteArrayOf()) }
        val finishedNs = System.nanoTime()

        assertEquals("", plain.body())
        assertEquals(plain.body(), traced.body())
        assertEquals(plain.statusCode(), traced.statusCode())
        assertSuccessfulTrace(trace, startedNs, finishedNs, hasBody = false)
    }

    @Test
    fun `선언한 길이보다 짧게 닫힌 실제 HTTP 본문은 ofString처럼 실패한다`() {
        val partial = "partial".toByteArray(StandardCharsets.UTF_8)
        val plainFailure =
            receiveFailure(HttpResponse.BodyHandlers.ofString()) { socket ->
                writeResponse(socket, partial, partial.size + 100)
            }
        val trace = HttpPerformanceResponseTrace()
        val startedNs = System.nanoTime()
        val tracedFailure =
            receiveFailure(trace.handler()) { socket ->
                writeResponse(socket, partial, partial.size + 100)
            }
        val finishedNs = System.nanoTime()

        assertIs<IOException>(plainFailure)
        assertIs<IOException>(tracedFailure)
        assertTime(trace.headersEnteredNs, startedNs, finishedNs)
        assertTime(trace.getBodyEnteredNs, assertNotNull(trace.headersEnteredNs), finishedNs)
        assertTime(trace.firstNextNs, assertNotNull(trace.headersEnteredNs), finishedNs)
        assertTime(trace.lastNextNs, assertNotNull(trace.firstNextNs), finishedNs)
        assertNull(trace.onCompleteNs)
        assertNull(trace.bodyCompletedNs)
    }

    @Test
    fun `응답 헤더 전 실제 요청 timeout은 ofString처럼 실패하고 응답 시각을 만들지 않는다`() {
        listOf<HttpPerformanceResponseTrace?>(null, HttpPerformanceResponseTrace()).forEach { trace ->
            val release = CountDownLatch(1)
            OneResponseServer { release.await(5, TimeUnit.SECONDS) }.use { server ->
                withClient { client ->
                    val request =
                        HttpRequest
                            .newBuilder(server.uri)
                            .timeout(Duration.ofSeconds(1))
                            .GET()
                            .build()
                    val future = client.sendAsync(request, trace?.handler() ?: HttpResponse.BodyHandlers.ofString())
                    try {
                        assertTrue(server.requestRead.await(5, TimeUnit.SECONDS), "server did not receive the request")
                        assertIs<HttpTimeoutException>(failure(future))
                        trace?.let(::assertNoResponseTimes)
                    } finally {
                        release.countDown()
                        future.cancel(true)
                    }
                }
                server.awaitServed()
            }
        }
    }

    @Test
    fun `본문 일부를 받은 실제 HTTP 요청을 취소해도 성공 완료 시각을 만들지 않는다`() {
        listOf<HttpPerformanceResponseTrace?>(null, HttpPerformanceResponseTrace()).forEach { trace ->
            val release = CountDownLatch(1)
            val plainHeadersReceived = CountDownLatch(1)
            // The control returns the original ofString subscriber unchanged, including JDK trust/scheduling.
            val plain = HttpResponse.BodyHandlers.ofString()
            val handler =
                trace?.handler() ?: HttpResponse.BodyHandler { info ->
                    plain.apply(info).also { plainHeadersReceived.countDown() }
                }
            OneResponseServer { socket ->
                writeResponse(socket, byteArrayOf('a'.code.toByte()), declaredLength = 100)
                release.await(5, TimeUnit.SECONDS)
            }.use { server ->
                withClient { client ->
                    val startedNs = System.nanoTime()
                    val future = client.sendAsync(request(server.uri), handler)
                    try {
                        if (trace == null) {
                            assertTrue(plainHeadersReceived.await(5, TimeUnit.SECONDS), "plain handler did not receive headers")
                        } else {
                            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                            while (trace.firstNextNs == null && System.nanoTime() < deadlineNs && !future.isDone) {
                                Thread.sleep(1)
                            }
                        }
                        val observedNs = System.nanoTime()
                        trace?.let { assertTime(it.firstNextNs, startedNs, observedNs) }
                        assertFalse(future.isDone, "incomplete body must not complete the request")
                        assertTrue(future.cancel(true))
                        val failure = assertFailsWith<Exception> { future.get(5, TimeUnit.SECONDS) }
                        // MinimalFuture cancels its exchange first. That can complete the dependent
                        // future exceptionally before super.cancel marks this future isCancelled.
                        assertTrue(
                            generateSequence<Throwable>(failure) { it.cause }.any { it is CancellationException },
                            failure.toString(),
                        )
                        assertTrue(future.isDone)
                        assertTrue(future.isCompletedExceptionally)
                        trace?.let {
                            assertTime(it.headersEnteredNs, startedNs, observedNs)
                            assertNull(it.onCompleteNs)
                            assertNull(it.bodyCompletedNs)
                        }
                    } finally {
                        future.cancel(true)
                        release.countDown()
                    }
                }
                server.awaitServed()
            }
        }
    }

    private fun receive(
        handler: HttpResponse.BodyHandler<String>,
        response: (Socket) -> Unit,
    ): HttpResponse<String> =
        OneResponseServer(response).use { server ->
            val result = withClient { it.sendAsync(request(server.uri), handler).get(5, TimeUnit.SECONDS) }
            server.awaitServed()
            result
        }

    private fun receiveFailure(
        handler: HttpResponse.BodyHandler<String>,
        response: (Socket) -> Unit,
    ): Throwable =
        OneResponseServer(response).use { server ->
            val result = withClient { failure(it.sendAsync(request(server.uri), handler)) }
            server.awaitServed()
            result
        }

    private fun request(uri: URI): HttpRequest =
        HttpRequest
            .newBuilder(uri)
            .timeout(Duration.ofSeconds(4))
            .GET()
            .build()

    private fun failure(future: CompletableFuture<*>): Throwable {
        var cause = assertNotNull(assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }.cause)
        while (cause is CompletionException || cause is ExecutionException) cause = assertNotNull(cause.cause)
        return cause
    }

    private fun assertSuccessfulTrace(
        trace: HttpPerformanceResponseTrace,
        startedNs: Long,
        finishedNs: Long,
        hasBody: Boolean,
    ) {
        val headersNs = assertTime(trace.headersEnteredNs, startedNs, finishedNs)
        assertTime(trace.getBodyEnteredNs, headersNs, finishedNs)
        val completeNs = assertTime(trace.onCompleteNs, headersNs, finishedNs)
        assertTime(trace.bodyCompletedNs, completeNs, finishedNs)
        if (hasBody) {
            val firstNs = assertTime(trace.firstNextNs, headersNs, completeNs)
            assertTime(trace.lastNextNs, firstNs, completeNs)
        } else {
            assertNull(trace.firstNextNs)
            assertNull(trace.lastNextNs)
        }
    }

    private fun assertTime(
        value: Long?,
        fromNs: Long,
        toNs: Long,
    ): Long = assertNotNull(value).also { assertTrue(it in fromNs..toNs, "$it outside $fromNs..$toNs") }

    private fun assertNoResponseTimes(trace: HttpPerformanceResponseTrace) {
        assertNull(trace.headersEnteredNs)
        assertNull(trace.getBodyEnteredNs)
        assertNull(trace.firstNextNs)
        assertNull(trace.lastNextNs)
        assertNull(trace.onCompleteNs)
        assertNull(trace.bodyCompletedNs)
    }

    private fun writeResponse(
        socket: Socket,
        body: ByteArray,
        declaredLength: Int = body.size,
    ) {
        val output = socket.getOutputStream()
        output.write(
            (
                "HTTP/1.1 202 Accepted\r\n" +
                    "Content-Type: application/json; charset=UTF-8\r\n" +
                    "Content-Length: $declaredLength\r\n" +
                    "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII),
        )
        output.flush()
        if (body.isNotEmpty()) {
            output.write(body, 0, 1)
            output.flush()
            output.write(body, 1, body.size - 1)
            output.flush()
        }
    }

    private fun <T> withClient(action: (HttpClient) -> T): T {
        val client =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build()
        try {
            return action(client)
        } finally {
            client.shutdownNow()
            assertTrue(client.awaitTermination(Duration.ofSeconds(5)), "HTTP client did not terminate")
        }
    }

    private class OneResponseServer(
        response: (Socket) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val socket = AtomicReference<Socket?>()
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "response-http-test-server").apply { isDaemon = true } }
        val requestRead = CountDownLatch(1)
        val uri: URI = URI("http://127.0.0.1:${server.localPort}/response")
        private val served =
            executor.submit {
                server.accept().use { accepted ->
                    socket.set(accepted)
                    if (server.isClosed) accepted.close()
                    accepted.soTimeout = 4000
                    val input = accepted.getInputStream()
                    var suffix = 0
                    var headerBytes = 0
                    while (suffix != 0x0d0a0d0a) {
                        check(headerBytes++ < 16_384) { "request headers exceeded the fixture limit" }
                        val next = input.read()
                        check(next >= 0) { "request closed before its headers completed" }
                        suffix = (suffix shl 8) or next
                    }
                    requestRead.countDown()
                    response(accepted)
                }
            }

        fun awaitServed() {
            served.get(5, TimeUnit.SECONDS)
        }

        override fun close() {
            server.close()
            socket.get()?.close()
            served.cancel(true)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "response server did not terminate")
        }
    }
}
