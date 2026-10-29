package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.config.TradingGatewayHttpWorkerConfig
import com.exchange.core.api.trading.api.HttpContractTradingGateway
import com.exchange.core.api.trading.api.TradingOrderHttpConfig
import com.exchange.core.api.trading.application.TradingAdmissionResult
import jakarta.servlet.DispatcherType
import org.apache.tomcat.util.threads.ThreadPoolExecutor
import org.apache.tomcat.util.threads.VirtualThreadExecutor
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.tomcat.TomcatWebServer
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.util.Enumeration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpPerformanceHttpBoundariesTest {
    private val connector = mapOf<String, Any?>("status" to "CAPTURED", "errors" to emptyList<String>())
    private val mapper = jacksonObjectMapper()

    @Test
    fun `상태 확인 시계 probe는 관측 창 전후에도 진입 시각만 헤더로 반환한다`() {
        var now = 100L
        val observer = HttpPerformanceMvcBoundaries("run-1", 1) { now }

        fun probe(expected: Long) {
            val request =
                object : MockHttpServletRequest("GET", "/actuator/health") {
                    override fun getHeaders(name: String): Enumeration<String> {
                        now += 50L
                        return super.getHeaders(name)
                    }
                }.apply { addHeader(HTTP_PERFORMANCE_SERVER_CLOCK_PROBE_HEADER, "run-1") }
            val response =
                MockHttpServletResponse().apply {
                    status = 200
                    writer.write("{\"status\":\"UP\"}")
                }
            observer.clockProbe(request, response)
            assertEquals(expected.toString(), response.getHeader(HTTP_PERFORMANCE_SERVER_CLOCK_NANO_HEADER))
            assertEquals(200, response.status)
            assertEquals("{\"status\":\"UP\"}", response.contentAsString)
            assertNull(request.getAttribute(HTTP_PERFORMANCE_MVC_ATTRIBUTE))
        }
        probe(100L)
        observer.open(connector)
        now = 200L
        probe(200L)
        observer.close(connector)
        now = 300L
        probe(300L)
        assertEquals(0, observer.completedCount())
    }

    @Test
    fun `다른 실행이나 중복 시계 probe 헤더는 서버 시각을 반환하지 않는다`() {
        val observer = HttpPerformanceMvcBoundaries("run-1", 1) { 100L }
        for (values in listOf(emptyList(), listOf("other-run"), listOf("run-1", "run-1"))) {
            val request =
                MockHttpServletRequest("GET", "/actuator/health").apply {
                    values.forEach { addHeader(HTTP_PERFORMANCE_SERVER_CLOCK_PROBE_HEADER, it) }
                }
            val response = MockHttpServletResponse()
            observer.clockProbe(request, response)
            assertNull(response.getHeader(HTTP_PERFORMANCE_SERVER_CLOCK_NANO_HEADER))
        }
    }

    @Test
    fun `식별 헤더가 중복되면 첫 값을 정상 관측으로 쓰지 않고 원래 응답은 유지한다`() {
        for (header in listOf(HTTP_PERFORMANCE_RUN_HEADER, HTTP_PERFORMANCE_REQUEST_HEADER)) {
            val observer = HttpPerformanceMvcBoundaries("run-1", 1) { 100L }
            observer.open(connector)
            val request = request("M-0").apply { addHeader(header, if (header == HTTP_PERFORMANCE_RUN_HEADER) "other-run" else "M-1") }
            val response = MockHttpServletResponse().apply { status = 202 }
            assertTrue(observer.preHandle(request, response, this))
            observer.afterCompletion(request, response, this, null)

            val report = tree(observer.close(connector))
            assertEquals("ERROR", report.path("status").asString())
            assertEquals(0, report.path("enteredCount").asInt())
            assertEquals(listOf("M-0"), report.path("missingRequestIds").toList().map { it.asString() })
            assertTrue(report.path("errors").toList().any { it.asString() == "DUPLICATE_IDENTITY_HEADER:$header" })
            assertEquals(202, response.status)
            assertEquals("", response.contentAsString)
        }
    }

    @Test
    fun `Gateway timer 오류여도 같은 실행의 HTTP 경계는 먼저 보존하고 오류는 계속 전달한다`() {
        val result =
            mapper.readTree(
                """{"runId":"run-1","token":"run-1","action":"close","status":"ERROR","detailedTracing":true,"errors":["TIMER_DRAIN"],"httpBoundaries":{"runId":"run-1","status":"CLOSED","spans":[]}}""",
            )
        var captured: JsonNode? = null
        val failure =
            assertFailsWith<IllegalStateException> {
                consumeHttpPerformanceGatewayWindow(result, "run-1", "close", true) { captured = it }
            }
        assertEquals(result, captured)
        assertEquals("CLOSED", captured?.path("httpBoundaries")?.path("status")?.asString())
        assertTrue(failure.message.orEmpty().contains("Gateway 계측 창 오류"))
        captured = null
        assertFailsWith<IllegalStateException> {
            consumeHttpPerformanceGatewayWindow(result, "other-run", "close", true) { captured = it }
        }
        assertNull(captured)
        val normal =
            mapper.readTree(
                """{"runId":"run-1","token":"run-1","action":"close","status":"CLOSED","detailedTracing":true,"errors":[]}""",
            )
        assertEquals(normal, consumeHttpPerformanceGatewayWindow(normal, "run-1", "close", true) { captured = it })
        assertEquals(normal, captured)
    }

    @Test
    fun `명시하지 않은 평소 조립에는 서버 진단 bean이 생기지 않고 N 상한은 할당 전에 검사한다`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.setActiveProfiles("http-performance")
            context.register(HttpPerformanceHttpBoundaryConfiguration::class.java)
            context.refresh()
            assertTrue(context.getBeansOfType(HttpPerformanceMvcBoundaries::class.java).isEmpty())
            assertTrue(context.getBeansOfType(WebMvcConfigurer::class.java).isEmpty())
            val unavailable = captureHttpPerformanceTomcat(context)
            assertEquals("PARTIAL", unavailable["status"])
            assertFalse((unavailable["errors"] as List<*>).isEmpty())
        }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceMvcBoundaries("run-1", Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceHttpFailures("run-1", Int.MAX_VALUE) }
    }

    @Test
    fun `빠른 async 완료와 재dispatch는 같은 첫 진입과 한 종료를 유지한다`() {
        var now = -100L
        val observer = HttpPerformanceMvcBoundaries("run-1", 2) { now }
        observer.open(connector)
        val request = request("M-0")
        val response = MockHttpServletResponse().apply { status = 202 }
        assertTrue(observer.preHandle(request, response, this))
        val handle = request.getAttribute(HTTP_PERFORMANCE_MVC_ATTRIBUTE) as HttpPerformanceMvcHandle
        val callbacks = observer.deferredObserver(handle, response)
        val web = ServletWebRequest(request, response)
        val result = DeferredResult<String>()
        callbacks.beforeConcurrentHandling(web, result)
        now = -50
        // Completion may race ahead of afterConcurrentHandlingStarted or the ASYNC redispatch.
        callbacks.afterCompletion(web, result)
        observer.afterConcurrentHandlingStarted(request, response, this)
        request.dispatcherType = DispatcherType.ASYNC
        observer.preHandle(request, response, this)
        observer.afterCompletion(request, response, this, null)
        val report = tree(observer.close(connector))
        val span = report.path("spans").single()
        assertEquals(50L, span.path("mvcDurationNs").asLong())
        assertEquals(2, span.path("dispatchCount").asInt())
        assertEquals(1, span.path("terminalCallbackCount").asInt())
        assertEquals("ASYNC_WEB_REQUEST_COMPLETION", span.path("completionKind").asString())
        assertEquals(listOf("M-1"), report.path("missingRequestIds").toList().map { it.asString() })
        assertTrue(report.path("errors").isEmpty)
        assertEquals(202, response.status)
        assertFalse(span.has("enteredNs"))
        assertFalse(span.has("completedNs"))
    }

    @Test
    fun `timeout error는 응답을 바꾸지 않고 완료 전에 별도 신호로 남는다`() {
        var now = 10L
        val observer = HttpPerformanceMvcBoundaries("run-1", 1) { now }
        observer.open(connector)
        val request = request("M-0")
        val response = MockHttpServletResponse().apply { status = 503 }
        observer.preHandle(request, response, this)
        val handle = request.getAttribute(HTTP_PERFORMANCE_MVC_ATTRIBUTE) as HttpPerformanceMvcHandle
        val callbacks = observer.deferredObserver(handle, response)
        val web = ServletWebRequest(request, response)
        val result = DeferredResult<String>()
        callbacks.beforeConcurrentHandling(web, result)
        assertTrue(callbacks.handleTimeout(web, result))
        assertTrue(callbacks.handleError(web, result, IllegalStateException("original")))
        assertFalse(result.hasResult())
        assertEquals(0, observer.completedCount())
        // A further DeferredResult async cycle retains the same first MVC entry.
        callbacks.beforeConcurrentHandling(web, result)
        now = 110
        callbacks.afterCompletion(web, result)
        callbacks.afterCompletion(web, result)
        val report = tree(observer.close(connector))
        val span = report.path("spans").single()
        assertEquals(100L, span.path("mvcDurationNs").asLong())
        assertEquals(2, span.path("asyncStartCount").asInt())
        assertEquals(1, span.path("timeoutSignalCount").asInt())
        assertEquals(1, span.path("errorSignalCount").asInt())
        assertEquals(2, span.path("terminalCallbackCount").asInt())
        assertEquals(1, report.path("completedCount").asInt())
        assertTrue(
            report
                .path("errors")
                .toList()
                .map { it.asString() }
                .contains("DUPLICATE_TERMINAL_CALLBACK"),
        )
        assertEquals("ERROR", report.path("status").asString())
        assertEquals(503, response.status)
        assertEquals("", response.contentAsString)
    }

    @Test
    fun `상한 밖 헤더와 중복 REQUEST를 보관하지 않고 미완료도 성공으로 바꾸지 않는다`() {
        val observer = HttpPerformanceMvcBoundaries("run-1", 2) { 100L }
        observer.open(connector)
        val response = MockHttpServletResponse()
        val initial = request("M-0")
        observer.preHandle(initial, response, this)
        observer.preHandle(request("M-0"), response, this)
        for (invalid in listOf("M-2", "M-01", "M-999999999999999999", "W-0")) {
            observer.preHandle(request(invalid), response, this)
        }
        observer.preHandle(request("M-1", "other-run"), response, this)
        val unknownRedispatch = request("M-1").apply { dispatcherType = DispatcherType.ASYNC }
        observer.preHandle(unknownRedispatch, response, this)
        val report = tree(observer.close(connector))
        assertEquals(1, report.path("spans").size())
        assertEquals(1, report.path("duplicateRequestCount").asInt())
        assertEquals(5, report.path("ignoredHeaderCount").asInt())
        assertEquals(listOf("M-0"), report.path("incompleteRequestIds").toList().map { it.asString() })
        assertEquals(listOf("M-1"), report.path("missingRequestIds").toList().map { it.asString() })
        assertEquals("ERROR", report.path("status").asString())
        assertNull(initial.getAttribute("any-original-attribute"))
        observer.afterCompletion(initial, response, this, null)
        assertEquals(0, observer.completedCount()) // The immutable cutoff cannot be retroactively repaired.
    }

    @Test
    fun `관측 예외와 원래 handler 예외는 HTTP 응답을 덮어쓰지 않는다`() {
        var calls = 0
        val observer =
            HttpPerformanceMvcBoundaries("run-1", 1) {
                if (++calls == 1) throw IllegalStateException("diagnostic clock")
                calls.toLong()
            }
        observer.open(connector)
        val request = request("M-0")
        val response = MockHttpServletResponse().apply { status = 422 }
        request.setAttribute("original", "kept")
        val original = IllegalArgumentException("handler failed")
        assertTrue(observer.preHandle(request, response, this))
        observer.afterCompletion(request, response, this, original)
        val report = tree(observer.close(connector))
        assertEquals(1, report.path("observerFailureCount").asInt())
        assertEquals("ERROR", report.path("status").asString())
        assertEquals("kept", request.getAttribute("original"))
        assertEquals(422, response.status)
        assertEquals("handler failed", original.message)
        assertEquals("", response.contentAsString)
    }

    @Test
    fun `close와 완료가 경합해도 종료 수와 cutoff 상태가 모순되지 않는다`() {
        val observer = HttpPerformanceMvcBoundaries("run-1", 1)
        observer.open(connector)
        val request = request("M-0")
        val response = MockHttpServletResponse().apply { status = 202 }
        observer.preHandle(request, response, this)
        val handle = request.getAttribute(HTTP_PERFORMANCE_MVC_ATTRIBUTE) as HttpPerformanceMvcHandle
        val callbacks = observer.deferredObserver(handle, response)
        val web = ServletWebRequest(request, response)
        val result = DeferredResult<String>()
        callbacks.beforeConcurrentHandling(web, result)
        val gate = CountDownLatch(1)
        val completing =
            CompletableFuture.runAsync {
                gate.await()
                callbacks.afterCompletion(web, result)
            }
        val closing =
            CompletableFuture.supplyAsync {
                gate.await()
                observer.close(connector)
            }
        gate.countDown()
        completing.get(3, TimeUnit.SECONDS)
        val report = tree(closing.get(3, TimeUnit.SECONDS))
        val span = report.path("spans").single()
        val completed = report.path("completedCount").asInt()
        assertTrue(completed in 0..1)
        assertEquals(completed, span.path("terminalCallbackCount").asInt())
        assertEquals(completed == 0, span.path("mvcDurationNs").isNull)
        assertEquals(if (completed == 1) "CLOSED" else "ERROR", report.path("status").asString())
    }

    @Test
    fun `요청 원본과 서버는 식별자로만 연결하고 두 로컬 시간은 각각 보존한다`() {
        val observer = HttpPerformanceMvcBoundaries("run-1", 2) { 100L }
        observer.open(connector)
        val request = request("M-0")
        val response = MockHttpServletResponse().apply { status = 202 }
        observer.preHandle(request, response, this)
        observer.afterCompletion(request, response, this, null)
        val server = tree(observer.close(connector))
        val planned = listOf(PlannedHttpRequest("BUYER", "M-0", "M-0", -10), PlannedHttpRequest("BUYER", "M-1", "M-1", 0))
        val measurements = HttpPerformanceMeasurements("run-1", -10, 10, planned)
        measurements.requestStarted("BUYER", "M-0", -5)
        measurements.httpCompleted("BUYER", "M-0", 25, HttpPerformanceOutcome.ACCEPTED, 1)
        measurements.requestStarted("BUYER", "M-1", 1)
        measurements.httpCompleted(
            "BUYER",
            "M-1",
            40,
            HttpPerformanceOutcome.HTTP_UNKNOWN,
            detail = "EOFException",
            responseReceived = false,
        )
        val joined = tree(joinHttpPerformanceHttpBoundaries("run-1", measurements.requests(), server))
        assertTrue(joined.path("errors").isEmpty)
        val rows = joined.path("requests")
        assertEquals(30L, rows.first().path("clientHttpLocalDurationNs").asLong())
        assertEquals(0L, rows.first().path("serverMvcLocalDurationNs").asLong())
        assertEquals(false, rows.last().path("serverEntered").asBoolean())
        assertTrue(rows.last().path("serverMvcLocalDurationNs").isNull)
        assertFalse(rows.first().has("crossJvmDurationNs"))
        val invalid = tree(joinHttpPerformanceHttpBoundaries("wrong-run", measurements.requests(), server))
        assertFalse(invalid.path("errors").isEmpty)
        val missingDuration =
            mapper.readTree(
                """{"runId":"run-1","status":"CLOSED","capacity":2,"enteredCount":1,"errors":[],"spans":[{"requestId":"M-0","responseStatus":202,"terminalCallbackCount":1}]}""",
            )
        val rejected = tree(joinHttpPerformanceHttpBoundaries("run-1", measurements.requests(), missingDuration))
        assertTrue(
            rejected
                .path("errors")
                .toList()
                .map { it.asString() }
                .contains("ACCEPTED_WITHOUT_MATCHING_COMPLETED_MVC_SPAN"),
        )
    }

    @Test
    fun `실제 MVC의 지연 future 즉시 future 동기 400에서도 원래 응답과 단일 완료를 보존한다`() {
        // This existing HTTP fixture stubs only the gateway. It does not claim financial or Aeron validation.
        SpringApplicationBuilder(
            TradingOrderHttpConfig::class.java,
            HttpBoundaryContractConfiguration::class.java,
            TradingGatewayHttpWorkerConfig::class.java,
        ).profiles("aeron-gateway")
            .run("--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")
            .use { context ->
                val observer = context.getBean(HttpPerformanceMvcBoundaries::class.java)
                val connectors = captureHttpPerformanceTomcat(context)
                assertEquals("CAPTURED", connectors["status"], connectors.toString())
                val captured = tree(connectors).path("connectors").single()
                assertEquals(HttpPerformanceSocketProtocol::class.java.name, captured.path("protocolClass").asString())
                assertEquals(VirtualThreadExecutor::class.java.name, captured.path("executorClass").asString())
                assertFalse(captured.path("executorClass").asString().contains("ObservedExecutor"))
                val tomcat = (context as ServletWebServerApplicationContext).webServer as TomcatWebServer
                val executor =
                    assertIs<VirtualThreadExecutor>(
                        tomcat.tomcat.service
                            .findConnectors()
                            .single()
                            .protocolHandler.executor,
                    )
                val virtualTask = CompletableFuture<Thread>()
                executor.execute { virtualTask.complete(Thread.currentThread()) }
                val virtualWorker = virtualTask.get(3, TimeUnit.SECONDS)
                assertTrue(virtualWorker.isVirtual)
                virtualWorker.join(1_000)
                assertFalse(virtualWorker.isAlive)
                assertEquals(1024, captured.path("acceptCount").path("value").asInt())
                for (field in listOf(
                    "keepAliveTimeoutMs",
                    "maxKeepAliveRequests",
                    "acceptCount",
                    "maxConnections",
                    "maxThreads",
                    "minSpareThreads",
                )) {
                    assertTrue(captured.path(field).path("available").asBoolean(), field)
                    assertTrue(captured.path(field).path("value").isNumber, field)
                }
                observer.open(connectors)
                val gateway = context.getBean(HttpContractTradingGateway::class.java)
                val port = requireNotNull((context as ServletWebServerApplicationContext).webServer).port
                val uri = URI("http://127.0.0.1:$port/api/trading/markets/BTC-USDT/orders")
                HttpClient.newHttpClient().use { client ->
                    gateway.result = CompletableFuture()
                    gateway.submitted = CountDownLatch(1)
                    val pending =
                        client.sendAsync(
                            performanceHttpRequest(
                                uri,
                                mapper.writeValueAsString(performanceOrder("BUYER", "BUY", "M-0")),
                                HttpPerformanceHttpIdentity("run-1", "M-0"),
                            ),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    try {
                        val submitted = gateway.submitted.await(3, TimeUnit.SECONDS)
                        if (!submitted) {
                            val responseEvidence =
                                if (pending.isDone) {
                                    runCatching { "${pending.join().statusCode()}:${pending.join().body()}" }
                                        .fold({ it }, { it.stackTraceToString() })
                                } else {
                                    "HTTP future still pending"
                                }
                            assertTrue(submitted, "Gateway submission not observed: $responseEvidence")
                        }
                        assertFalse(pending.isDone)
                        assertEquals(0, observer.completedCount())
                    } finally {
                        gateway.result.complete(TradingAdmissionResult.Accepted(71))
                    }
                    val delayed = pending.get(3, TimeUnit.SECONDS)
                    assertEquals(202, delayed.statusCode())
                    assertEquals(71L, mapper.readTree(delayed.body()).path("sequence").asLong())
                    gateway.result = CompletableFuture.completedFuture(TradingAdmissionResult.Accepted(17))
                    val immediate =
                        client.send(
                            performanceHttpRequest(
                                uri,
                                mapper.writeValueAsString(performanceOrder("BUYER", "BUY", "M-1")),
                                HttpPerformanceHttpIdentity("run-1", "M-1"),
                            ),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    assertEquals(202, immediate.statusCode())
                    val body = mapper.readTree(immediate.body())
                    assertEquals("M-1", body.path("requestId").asString())
                    assertEquals("M-1", body.path("orderId").asString())
                    assertEquals("BUYER", body.path("userId").asString())
                    val malformed =
                        client.send(
                            performanceHttpRequest(uri, "{", HttpPerformanceHttpIdentity("run-1", "M-2")),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    assertEquals(400, malformed.statusCode())
                    assertEquals("INVALID_INPUT", mapper.readTree(malformed.body()).path("status").asString())
                    val ordinary =
                        client.send(
                            performanceHttpRequest(uri, mapper.writeValueAsString(performanceOrder("BUYER", "BUY", "W-0"))),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    assertEquals(202, ordinary.statusCode())
                    assertEquals(listOf("M-0", "M-1", "W-0"), gateway.submissions.map { it.requestId })
                    assertEquals(
                        100_000_000L,
                        gateway.submissions
                            .first()
                            .order.quantity.value,
                    )
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (observer.completedCount() != 3 && System.nanoTime() - deadline < 0) Thread.sleep(1)
                    val report = tree(observer.close(captureHttpPerformanceTomcat(context)))
                    assertEquals("CLOSED", report.path("status").asString(), report.toString())
                    assertEquals(3, report.path("completedCount").asInt())
                    assertEquals(3, report.path("containerEnteredCount").asInt())
                    assertTrue(report.path("containerOnlySpans").isEmpty)
                    assertTrue(report.path("spans").all { it.path("terminalCallbackCount").asInt() == 1 })
                    assertTrue(report.path("spans").all { it.path("containerToMvcNs").isIntegralNumber })
                    assertTrue(report.path("spans").all { it.path("containerToMvcNs").asLong() >= 0L })
                    assertTrue(report.path("socketTracingExpected").asBoolean())
                    for (span in report.path("spans")) {
                        val events = span.path("socketDispatches").toList()
                        assertTrue(events.isNotEmpty(), span.toString())
                        assertEquals(1, events.map { it.path("connectionId").asString() }.distinct().size)
                        val initial = events.first()
                        val valveEntered = span.path("gatewayLocalPoints").path("tomcatEnteredNs").asLong()
                        for ((earlier, later) in listOf(
                            "admittedNs" to "submittedNs",
                            "submittedNs" to "workerStartedNs",
                            "workerStartedNs" to "socketProcessingStartedNs",
                            "socketProcessingStartedNs" to "handlerEnteredNs",
                        )) {
                            assertTrue(initial.path(earlier).isIntegralNumber, initial.toString())
                            assertTrue(initial.path(later).isIntegralNumber, initial.toString())
                            assertTrue(initial.path(later).asLong() >= initial.path(earlier).asLong(), initial.toString())
                        }
                        assertTrue(valveEntered >= initial.path("handlerEnteredNs").asLong())
                        assertTrue(events.sumOf { it.path("nativeWriteBytes").asLong() } > 0L, span.toString())
                        assertTrue(events.all { it.path("nativeWriteFailures").asInt() == 0 })
                    }
                    assertEquals(
                        listOf(
                            "ASYNC_WEB_REQUEST_COMPLETION",
                            "ASYNC_WEB_REQUEST_COMPLETION",
                            "SYNC_MVC_AFTER_COMPLETION",
                        ),
                        report.path("spans").toList().map {
                            it.path("completionKind").asString()
                        },
                    )
                    assertTrue(report.path("missingRequestIds").isEmpty)
                }
            }
    }

    @Test
    fun `HTTP 가상 스레드를 끄면 설정한 최대 크기로 기본 풀이 미리 시작되고 context와 함께 종료한다`() {
        lateinit var native: ThreadPoolExecutor
        SpringApplicationBuilder(
            TradingOrderHttpConfig::class.java,
            HttpBoundaryContractConfiguration::class.java,
            TradingGatewayHttpWorkerConfig::class.java,
        ).profiles("aeron-gateway")
            .run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--trading.aeron.gateway.http-virtual-threads=false",
                // Exercise the production min-spare fallback with four workers instead of 200 test threads.
                "--server.tomcat.threads.max=4",
            ).use { context ->
                assertFalse(context.containsBean("tradingGatewayHttpVirtualThreads"))
                val server = (context as ServletWebServerApplicationContext).webServer as TomcatWebServer
                val protocol =
                    server.tomcat.service
                        .findConnectors()
                        .single()
                        .protocolHandler
                native = assertIs<ThreadPoolExecutor>(protocol.executor)
                assertEquals(4, native.maximumPoolSize)
                assertEquals(4, native.corePoolSize)
                assertEquals(4, native.poolSize)
                assertFalse(native.isShutdown)
                val captured = tree(captureHttpPerformanceTomcat(context)).path("connectors").single()
                assertEquals(ThreadPoolExecutor::class.java.name, captured.path("executorClass").asString())
                assertEquals(4, captured.path("maxThreads").path("value").asInt())
                assertEquals(4, captured.path("minSpareThreads").path("value").asInt())
                val gateway = context.getBean(HttpContractTradingGateway::class.java)
                gateway.result = CompletableFuture.completedFuture(TradingAdmissionResult.Accepted(29))
                val uri = URI("http://127.0.0.1:${server.port}/api/trading/markets/BTC-USDT/orders")
                HttpClient.newHttpClient().use { client ->
                    val response =
                        client.send(
                            performanceHttpRequest(uri, mapper.writeValueAsString(performanceOrder("BUYER", "BUY", "W-0"))),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    assertEquals(202, response.statusCode())
                    val body = mapper.readTree(response.body())
                    assertEquals("W-0", body.path("requestId").asString())
                    assertEquals(29L, body.path("sequence").asLong())
                }
            }
        assertTrue(native.isShutdown)
        assertTrue(native.awaitTermination(3, TimeUnit.SECONDS))
        assertTrue(native.isTerminated)
    }

    @Test
    fun `다른 요청의 느린 관측은 독립 요청의 MVC 진입과 완료를 붙잡지 않는다`() {
        val clockEntered = CountDownLatch(1)
        val releaseClock = CountDownLatch(1)
        val calls = AtomicInteger()
        val observer =
            HttpPerformanceMvcBoundaries("run-1", 2) {
                val call = calls.incrementAndGet()
                if (call == 1) {
                    clockEntered.countDown()
                    check(releaseClock.await(3, TimeUnit.SECONDS))
                }
                call * 10L
            }
        observer.open(connector)
        val firstRequest = request("M-0")
        val firstResponse = MockHttpServletResponse().apply { status = 202 }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Boolean> { observer.preHandle(firstRequest, firstResponse, this) }
            assertTrue(clockEntered.await(3, TimeUnit.SECONDS))
            val independent =
                pool.submit {
                    val request = request("M-1")
                    val response = MockHttpServletResponse().apply { status = 202 }
                    assertTrue(observer.preHandle(request, response, this))
                    observer.afterCompletion(request, response, this, null)
                }
            // This must finish while the first request's observer clock is still deliberately blocked.
            independent.get(3, TimeUnit.SECONDS)
            assertEquals(1, observer.completedCount())
            releaseClock.countDown()
            assertTrue(first.get(3, TimeUnit.SECONDS))
            observer.afterCompletion(firstRequest, firstResponse, this, null)
            val report = tree(observer.close(connector))
            assertEquals("CLOSED", report.path("status").asString())
            assertEquals(2, report.path("completedCount").asInt())
        } finally {
            releaseClock.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `MVC 시작 시각에 식별 헤더 관측 시간도 포함한다`() {
        var now = 10L
        val observer = HttpPerformanceMvcBoundaries("run-1", 1) { now }
        observer.open(connector)
        val request =
            object : MockHttpServletRequest("POST", "/api/trading/markets/BTC-USDT/orders") {
                override fun getHeaders(name: String): Enumeration<String> {
                    now = 50
                    return super.getHeaders(name)
                }
            }.apply {
                addHeader(HTTP_PERFORMANCE_RUN_HEADER, "run-1")
                addHeader(HTTP_PERFORMANCE_REQUEST_HEADER, "M-0")
            }
        val response = MockHttpServletResponse().apply { status = 202 }
        observer.preHandle(request, response, this)
        assertEquals(50L, now)
        now = 100
        observer.afterCompletion(request, response, this, null)
        val span = tree(observer.close(connector)).path("spans").single()
        assertEquals(90L, span.path("mvcDurationNs").asLong())
        assertEquals(10L, span.path("gatewayLocalPoints").path("mvcEnteredNs").asLong())
    }

    @Test
    fun `MVC 시작은 식별 헤더 관측 전에 찍고 컨테이너 async 각 경계를 같은 요청에 보존한다`() {
        var now = 100L
        val observer = HttpPerformanceMvcBoundaries("run-1", 1) { now }
        observer.open(connector)
        val request =
            object : MockHttpServletRequest("POST", "/api/trading/markets/BTC-USDT/orders") {
                override fun getHeaders(name: String): Enumeration<String> {
                    now += 5
                    return super.getHeaders(name)
                }
            }.apply {
                addHeader(HTTP_PERFORMANCE_RUN_HEADER, "run-1")
                addHeader(HTTP_PERFORMANCE_REQUEST_HEADER, "M-0")
            }
        val response = MockHttpServletResponse().apply { status = 202 }
        val initialDispatch = observer.containerEntered(request)
        assertEquals(110L, now) // Identity work advances the clock after the container entry was captured.
        now = 120
        observer.preHandle(request, response, this)
        val handle = request.getAttribute(HTTP_PERFORMANCE_MVC_ATTRIBUTE) as HttpPerformanceMvcHandle
        val callbacks = observer.deferredObserver(handle, response)
        val web = ServletWebRequest(request, response)
        val result = DeferredResult<String>()
        now = 130
        callbacks.beforeConcurrentHandling(web, result)
        now = 160
        callbacks.postProcess(web, result, "original result")
        now = 170
        observer.afterConcurrentHandlingStarted(request, response, this)
        now = 180
        observer.containerReturned(initialDispatch, response)
        request.dispatcherType = DispatcherType.ASYNC
        now = 200
        val redispatch = observer.containerEntered(request)
        now = 220
        observer.preHandle(request, response, this)
        now = 230
        observer.postHandle(request, response, this, null)
        now = 240
        observer.afterCompletion(request, response, this, null)
        now = 250
        callbacks.afterCompletion(web, result)
        now = 260
        observer.containerReturned(redispatch, response)
        val report = tree(observer.close(connector))
        val span = report.path("spans").single()
        assertEquals("CLOSED", report.path("status").asString())
        assertEquals(130L, span.path("mvcDurationNs").asLong())
        assertEquals(20L, span.path("containerToMvcNs").asLong())
        assertEquals(40L, span.path("mvcToAsyncResultReadyNs").asLong())
        assertEquals(60L, span.path("asyncResultReadyToRedispatchNs").asLong())
        assertEquals(30L, span.path("redispatchToCompletionNs").asLong())
        assertEquals(2, span.path("containerDispatchCount").asInt())
        val offsets = span.path("phaseOffsetsNs")
        assertEquals(-20L, offsets.path("containerEntry").asLong())
        assertEquals(60L, offsets.path("containerReturn").asLong())
        assertEquals(10L, offsets.path("asyncProcessingStarted").asLong())
        assertEquals(100L, offsets.path("mvcRedispatchEntry").asLong())
        assertEquals(130L, offsets.path("asyncWebRequestCompletion").asLong())
        val raw = span.path("gatewayLocalPoints")
        assertEquals("gateway JVM System.nanoTime only", raw.path("clock").asString())
        assertEquals(100L, raw.path("tomcatEnteredNs").asLong())
        assertEquals(120L, raw.path("mvcEnteredNs").asLong())
        assertEquals(160L, raw.path("asyncResultReadyNs").asLong())
        assertEquals(250L, raw.path("completedNs").asLong())
        assertFalse(result.hasResult()) // Observation did not supply or replace the original DeferredResult.
        assertEquals(202, response.status)
    }

    @Test
    fun `컨테이너 이후 MVC 전 중단한 요청도 별도 근거를 남기고 close 뒤 새 요청은 들어오지 않는다`() {
        var now = 10L
        val observer = HttpPerformanceMvcBoundaries("run-1", 2) { now }
        observer.open(connector)
        val request = request("M-0")
        val response = MockHttpServletResponse().apply { status = 400 }
        val dispatch = observer.containerEntered(request)
        now = 30
        observer.containerReturned(dispatch, response)
        val report = tree(observer.close(connector))
        assertEquals(0, report.path("enteredCount").asInt())
        assertEquals(1, report.path("containerEnteredCount").asInt())
        val containerOnly = report.path("containerOnlySpans").single()
        assertEquals("M-0", containerOnly.path("requestId").asString())
        assertEquals(20L, containerOnly.path("containerDurationNs").asLong())
        assertEquals(400, containerOnly.path("containerResponseStatus").asInt())
        assertEquals(listOf("M-1"), report.path("containerMissingRequestIds").toList().map { it.asString() })
        val late = request("M-1")
        observer.containerEntered(late)
        observer.preHandle(late, response, this)
        observer.afterCompletion(request, response, this, null)
        assertNull(late.getAttribute(HTTP_PERFORMANCE_MVC_ATTRIBUTE))
        assertEquals(0, observer.completedCount())
    }

    private fun request(
        id: String,
        run: String = "run-1",
    ): MockHttpServletRequest =
        MockHttpServletRequest("POST", "/api/trading/markets/BTC-USDT/orders").apply {
            addHeader(HTTP_PERFORMANCE_RUN_HEADER, run)
            addHeader(HTTP_PERFORMANCE_REQUEST_HEADER, id)
        }

    private fun tree(value: Map<String, Any?>): JsonNode = mapper.readTree(mapper.writeValueAsBytes(value))
}

@TestConfiguration(proxyBeanMethods = false)
internal class HttpBoundaryContractConfiguration {
    @Bean
    fun boundaryRecorder(): HttpPerformanceMvcBoundaries = HttpPerformanceMvcBoundaries("run-1", 3)

    @Bean
    fun boundaryConfigurer(observer: HttpPerformanceMvcBoundaries): WebMvcConfigurer = httpPerformanceBoundaryMvcConfigurer(observer)

    @Bean
    fun boundaryTomcatCustomizer(observer: HttpPerformanceMvcBoundaries): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        httpPerformanceBoundaryTomcatCustomizer(observer)
}
