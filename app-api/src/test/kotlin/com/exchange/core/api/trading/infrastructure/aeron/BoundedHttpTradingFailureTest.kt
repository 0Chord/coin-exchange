package com.exchange.core.api.trading.infrastructure.aeron

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.lang.reflect.InvocationTargetException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 통합 실행기의 실패 진단을 검사한다. 제품의 timeout 동작을 검증하는 테스트가 아니다. */
class BoundedHttpTradingFailureTest {
    private val mapper = jacksonObjectMapper()
    private val path = "/api/trading/v3/markets/BTC-USDT/orders"
    private val body =
        linkedMapOf(
            "userId" to "BUYER",
            "channel" to 0,
            "epoch" to 1L,
            "sequence" to 1L,
            "expectedConfigurationVersion" to 1L,
            "side" to "BUY",
            "orderType" to "LIMIT",
            "timeInForce" to "GTC",
            "price" to 10_000L,
            "quantity" to 100_000_000L,
        )

    @Test
    @Timeout(10)
    fun `응답을 기다리다 시간이 초과되어도 전송한 단계 경로 본문과 토큰을 보존한다`() {
        withVerification { runner ->
            DelayedResponseServer().use { server ->
                server.start()
                setField(runner, "port", server.port)
                setField(runner, "startupDeadline", System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750))

                val failure = assertIs<HttpTimeoutException>(postFailure(runner, "REPLAY"))

                assertTrue(server.requestReceived.await(1, TimeUnit.SECONDS), "서버가 요청 본문을 받지 못했습니다")
                server.assertHealthy()
                assertEquals(
                    mapper.readTree(mapper.writeValueAsString(body)),
                    mapper.readTree(server.receivedBody.get()),
                )
                assertFailureRecord(runner, "REPLAY", failure)
            }
        }
    }

    @Test
    @Timeout(10)
    fun `연결이 거절되어도 동일한 요청 진단과 결과 미확정을 기록한다`() {
        // 임의의 다른 서비스를 호출하지 않도록 이번에 확보했다가 닫은 루프백 포트만 사용한다.
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        withVerification { runner ->
            setField(runner, "port", closedPort)
            setField(runner, "startupDeadline", System.nanoTime() + TimeUnit.SECONDS.toNanos(1))

            val failure = assertIs<ConnectException>(postFailure(runner, "BUY"))

            assertFailureRecord(runner, "BUY", failure)
        }
    }

    private fun postFailure(
        runner: Any,
        phase: String,
    ): Throwable {
        val post =
            runner.javaClass
                .getDeclaredMethod(
                    "post",
                    String::class.java,
                    Any::class.java,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                ).apply { isAccessible = true }
        val wrapped = assertFailsWith<InvocationTargetException> { post.invoke(runner, path, body, 202, phase) }
        return requireNotNull(wrapped.cause)
    }

    private fun assertFailureRecord(
        runner: Any,
        phase: String,
        failure: Throwable,
    ) {
        val record = field<List<Map<String, Any?>>>(runner, "requests").single()
        assertEquals(phase, record["phase"])
        assertEquals(path, record["path"])
        assertEquals(body, record["request"])
        val recordedBody = assertIs<Map<*, *>>(record["request"])
        listOf("userId", "channel", "epoch", "sequence").forEach { tokenField ->
            assertEquals(body.getValue(tokenField), recordedBody[tokenField], "원래 요청 토큰 누락: $tokenField")
        }
        assertEquals("OUTCOME_UNKNOWN", record["outcome"])
        assertEquals(failure.toString(), record["transportFailure"])
        assertTrue(record.getValue("transportFailure").toString().contains(failure.javaClass.name))
        assertTrue(field<Map<String, Any?>>(runner, "responses").isEmpty(), "실패한 전송을 HTTP 응답으로 기록했습니다")
    }

    private fun withVerification(action: (Any) -> Unit) {
        val runner =
            Class
                .forName("com.exchange.core.api.trading.infrastructure.aeron.BoundedHttpTradingVerification")
                .getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
        try {
            action(runner)
        } finally {
            // runner.close()는 Docker 정리도 실행한다. 이 테스트가 만든 HTTP client와 빈 폴더만 직접 정리한다.
            field<HttpClient>(runner, "http").close()
            Files.deleteIfExists(field<Path>(runner, "control"))
            Files.deleteIfExists(field<Path>(runner, "report"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(
        target: Any,
        name: String,
    ): T =
        target.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(target) as T

    private fun setField(
        target: Any,
        name: String,
        value: Any,
    ) {
        target.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(target, value)
    }

    private class DelayedResponseServer : AutoCloseable {
        val requestReceived = CountDownLatch(1)
        val receivedBody = AtomicReference<String>()
        private val started = CountDownLatch(1)
        private val releaseResponse = CountDownLatch(1)
        private val failure = AtomicReference<Throwable>()
        private val executor =
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "bounded-http-diagnostic-server") }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    receivedBody.set(exchange.requestBody.use { String(it.readAllBytes(), Charsets.UTF_8) })
                    requestReceived.countDown()
                    // timeout이 돌아온 뒤 테스트가 해제할 때까지 응답 헤더조차 보내지 않는다.
                    check(releaseResponse.await(5, TimeUnit.SECONDS)) { "지연 응답 서버 해제 누락" }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    exchange.close()
                }
            }
        }

        fun start() {
            executor.execute {
                try {
                    server.start()
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    started.countDown()
                }
            }
            check(started.await(2, TimeUnit.SECONDS)) { "지연 응답 서버 기동 실패" }
            assertHealthy()
        }

        fun assertHealthy() {
            failure.get()?.let { throw AssertionError("지연 응답 서버 실패", it) }
        }

        override fun close() {
            releaseResponse.countDown()
            server.stop(0)
            executor.shutdown()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                check(executor.awaitTermination(2, TimeUnit.SECONDS)) { "지연 응답 서버 스레드 종료 실패" }
            }
            assertHealthy()
        }
    }
}
