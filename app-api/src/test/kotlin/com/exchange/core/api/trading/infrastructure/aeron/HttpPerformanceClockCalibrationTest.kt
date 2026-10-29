package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLSession
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpPerformanceClockCalibrationTest {
    private val uri = URI("http://fixture.invalid/actuator/health")

    @Test
    fun `전후 probe의 왕복 구간 교집합으로 offset을 제한하고 서버 시각을 client 구간으로 옮긴다`() {
        val times = (0 until 8).flatMap { listOf(-100L + it * 100, -100L + it * 100 + if (it == 7) 4 else 20) }.iterator()
        var index = 0
        val requests = mutableListOf<HttpRequest>()
        val calibration =
            HttpPerformanceClockCalibration("clock-test", uri, probesPerPhase = 4, nanoTime = times::next) { request ->
                requests.add(request)
                val server = 910L + index * 100 - if (index++ == 7) 8 else 0
                CompletableFuture.completedFuture(reply(request, header = listOf(server.toString())))
            }
        calibration.capture(HttpPerformanceClockPhase.BEFORE)
        assertNull(calibration.snapshot()["commonOffsetInterval"])
        calibration.capture(HttpPerformanceClockPhase.AFTER)
        val report = calibration.snapshot()
        assertEquals("VALID", report["status"])
        assertTrue((report["errors"] as List<*>).isEmpty())
        val common = report["commonOffsetInterval"] as HttpPerformanceClockInterval
        assertEquals(HttpPerformanceClockInterval(998, 1002), common)
        assertEquals(4L, common.widthNs)
        assertEquals(HttpPerformanceClockInterval(1998, 2002), common.clientBounds(3000))
        val probes = report["probes"] as List<*>
        assertEquals(8, probes.size)
        assertEquals(-100L, (probes.first() as HttpPerformanceClockProbe).clientSendNs)
        requests.forEach { request ->
            assertEquals(uri, request.uri())
            assertEquals("GET", request.method())
            assertEquals(listOf("clock-test"), request.headers().allValues(HTTP_PERFORMANCE_CLOCK_PROBE_HEADER))
        }
        calibration.capture(HttpPerformanceClockPhase.AFTER)
        assertEquals(8, (calibration.snapshot()["probes"] as List<*>).size)
        assertTrue((calibration.snapshot()["errors"] as List<*>).contains("DUPLICATE_PHASE:AFTER"))
    }

    @Test
    fun `전후 offset 구간이 겹치지 않으면 공통 시각 대응을 만들지 않는다`() {
        var tick = 0L
        var index = 0
        val calibration =
            HttpPerformanceClockCalibration("clock-test", uri, probesPerPhase = 4, nanoTime = { tick.also { tick += 10 } }) { request ->
                val clientStart = (index * 20).toLong()
                val offset = if (index++ < 4) 1000 else 2000
                CompletableFuture.completedFuture(reply(request, header = listOf((clientStart + offset + 5).toString())))
            }
        calibration.capture(HttpPerformanceClockPhase.BEFORE)
        calibration.capture(HttpPerformanceClockPhase.AFTER)
        val report = calibration.snapshot()
        assertEquals("ERROR", report["status"])
        assertNull(report["commonOffsetInterval"])
        assertTrue((report["errors"] as List<*>).contains("NO_COMMON_OFFSET_INTERSECTION"))
    }

    @Test
    fun `상태 health header 실패와 HTTP 실패는 원시 probe를 보존하고 진단을 무효로 표시한다`() {
        var tick = 0L
        var index = 0
        val calibration =
            HttpPerformanceClockCalibration("clock-test", uri, probesPerPhase = 4, nanoTime = { tick.also { tick += 10 } }) { request ->
                when (index++) {
                    0 -> CompletableFuture.completedFuture(reply(request, status = 503))
                    1 -> CompletableFuture.completedFuture(reply(request, body = "{\"status\":\"DOWN\"}"))
                    2 -> CompletableFuture.completedFuture(reply(request, header = emptyList()))
                    3 -> CompletableFuture.completedFuture(reply(request, header = listOf("1", "2")))
                    4 -> CompletableFuture.completedFuture(reply(request, header = listOf("invalid")))
                    else -> CompletableFuture.failedFuture(TimeoutException("bounded fixture failure"))
                }
            }
        calibration.capture(HttpPerformanceClockPhase.BEFORE)
        calibration.capture(HttpPerformanceClockPhase.AFTER)
        val report = calibration.snapshot()
        val probes = report["probes"] as List<*>
        assertEquals(8, probes.size)
        assertTrue(probes.all { (it as HttpPerformanceClockProbe).offsetInterval == null && it.clientBodyReceivedNs > it.clientSendNs })
        assertEquals("ERROR", report["status"])
        assertNull(report["commonOffsetInterval"])
        val errors = report["errors"] as List<*>
        assertTrue(errors.any { it.toString().contains("HTTP_STATUS:503") })
        assertTrue(errors.any { it.toString().contains("HEALTH_NOT_UP") })
        assertTrue(errors.any { it.toString().contains("SERVER_NANO_MISSING_OR_INVALID") })
        assertTrue(errors.any { it.toString().contains("SERVER_HEADER_CARDINALITY_OR_SIZE") })
        assertTrue(errors.any { it.toString().contains("HTTP_FAILURE:TimeoutException") })
    }

    @Test
    fun `한 phase만 있거나 overflow가 나면 검증 가능한 공통 offset으로 판정하지 않는다`() {
        val clock = listOf(Long.MIN_VALUE, Long.MAX_VALUE).iterator()
        var calls = 0
        val calibration =
            HttpPerformanceClockCalibration("clock-test", uri, probesPerPhase = 4, nanoTime = {
                if (clock.hasNext()) clock.next() else (calls++).toLong()
            }) { request -> CompletableFuture.completedFuture(reply(request)) }
        calibration.capture(HttpPerformanceClockPhase.BEFORE)
        val report = calibration.snapshot()
        assertEquals("ERROR", report["status"])
        assertTrue((report["errors"] as List<*>).any { it.toString().contains("CLOCK_ARITHMETIC_OVERFLOW") })
        assertTrue((report["errors"] as List<*>).contains("INCOMPLETE_PHASE:AFTER"))
        assertNull(report["commonOffsetInterval"])
    }

    private fun reply(
        request: HttpRequest,
        status: Int = 200,
        body: String = "{\"status\":\"UP\"}",
        header: List<String> = listOf("1000"),
    ): HttpResponse<String> =
        object : HttpResponse<String> {
            override fun statusCode(): Int = status

            override fun request(): HttpRequest = request

            override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

            override fun headers(): HttpHeaders = HttpHeaders.of(mapOf(HTTP_PERFORMANCE_SERVER_NANO_HEADER to header)) { _, _ -> true }

            override fun body(): String = body

            override fun sslSession(): Optional<SSLSession> = Optional.empty()

            override fun uri(): URI = request.uri()

            override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        }
}
