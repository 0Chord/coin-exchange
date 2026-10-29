package com.exchange.core.api.trading.infrastructure.aeron

import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.valves.ValveBase
import org.apache.tomcat.util.modeler.Registry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor
import org.springframework.web.context.request.async.WebAsyncUtils
import org.springframework.web.servlet.AsyncHandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.JsonNode
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

internal const val HTTP_PERFORMANCE_MVC_ATTRIBUTE = "exchange.http-performance.mvc-span"
private const val HTTP_PERFORMANCE_IGNORED_ATTRIBUTE = "exchange.http-performance.ignored"
internal const val HTTP_PERFORMANCE_SERVER_CLOCK_PROBE_HEADER = "X-Performance-Clock-Probe"
internal const val HTTP_PERFORMANCE_SERVER_CLOCK_NANO_HEADER = "X-Performance-Server-Nano"

/** Mutable fields are protected only by this request's monitor, never by a cohort-wide request-path monitor. */
internal class HttpPerformanceMvcHandle(
    val index: Int,
) {
    var sealed = false
    var enteredNs: Long? = null
    var containerEnteredNs: Long? = null
    var containerReturnedNs: Long? = null
    var containerRedispatchEnteredNs: Long? = null
    var containerRedispatchReturnedNs: Long? = null
    var containerResponseStatus: Int? = null
    var asyncProcessingStartedNs: Long? = null
    var asyncResultReadyNs: Long? = null
    var mvcInitialAsyncExitNs: Long? = null
    var mvcRedispatchEnteredNs: Long? = null
    var mvcPostHandleNs: Long? = null
    var mvcAfterCompletionNs: Long? = null
    var dispatches = 0
    var containerDispatches = 0
    var asyncStarts = 0
    var terminalCallbacks = 0
    var errorSignals = 0
    var timeoutSignals = 0
    var completedNs: Long? = null
    var completionKind: String? = null
    var responseStatus: Int? = null
    val socketDispatches = mutableListOf<HttpPerformanceSocketDispatch>()
    val errors = linkedSetOf<String>()
}

internal data class HttpPerformanceContainerDispatch(
    val handle: HttpPerformanceMvcHandle,
    val initial: Boolean,
)

/** One process/one measured cohort. Export local offsets and durations, never cross-JVM clock arithmetic. */
internal class HttpPerformanceMvcBoundaries(
    private val runId: String,
    private val orders: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) : AsyncHandlerInterceptor {
    // Only open/close use this monitor. Application requests never acquire it.
    private val controlLock = Any()
    private val spans = AtomicReferenceArray<HttpPerformanceMvcHandle?>(orders.also { require(it in 1..2000) })
    private val closedEmpty = HttpPerformanceMvcHandle(-1).apply { sealed = true }
    private val errors = ConcurrentSkipListSet<String>()
    private val ignoredHeaders = AtomicLong()
    private val duplicateRequests = AtomicLong()
    private val observerFailures = AtomicLong()

    @Volatile
    private var opened = false

    @Volatile
    private var closed = false
    private var connectorsAtOpen: Map<String, Any?>? = null
    private var socketTracingExpected = false

    fun expectSocketTracing() {
        socketTracingExpected = true
    }

    /** Available before open and after close; calibration does not create a measured request span. */
    fun clockProbe(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        observeSafely {
            val enteredAt = nanoTime()
            val probes = request.getHeaders(HTTP_PERFORMANCE_SERVER_CLOCK_PROBE_HEADER)
            if (probes.hasMoreElements() && probes.nextElement() == runId && !probes.hasMoreElements()) {
                response.setHeader(HTTP_PERFORMANCE_SERVER_CLOCK_NANO_HEADER, enteredAt.toString())
            }
        }
    }

    init {
        require(runId.matches(Regex("[a-zA-Z0-9-]{1,70}")))
    }

    fun open(connectors: Map<String, Any?>): Map<String, Any?> =
        synchronized(controlLock) {
            check(!opened && !closed) { "HTTP boundary diagnostic supports one cohort only" }
            connectorsAtOpen = connectors
            opened = true
            mapOf("enabled" to true, "runId" to runId, "capacity" to orders, "status" to "OPEN", "errors" to emptyList<String>())
        }

    /** Context Valve entry is after connector parsing/executor dispatch and before the web filter chain. */
    fun containerEntered(request: HttpServletRequest): HttpPerformanceContainerDispatch? {
        var dispatch: HttpPerformanceContainerDispatch? = null
        observeSafely {
            val enteredAt = nanoTime()
            val span = requestHandle(request) ?: return@observeSafely
            val initial = request.dispatcherType == DispatcherType.REQUEST
            synchronized(span) {
                if (closed || span.sealed) return@observeSafely
                HttpPerformanceSocketContext.bind(span)
                span.containerDispatches++
                if (initial) {
                    if (span.containerEnteredNs == null) span.containerEnteredNs = enteredAt
                } else if (span.containerRedispatchEnteredNs == null) {
                    span.containerRedispatchEnteredNs = enteredAt
                }
                dispatch = HttpPerformanceContainerDispatch(span, initial)
            }
        }
        return dispatch
    }

    fun containerReturned(
        dispatch: HttpPerformanceContainerDispatch?,
        response: HttpServletResponse,
    ) {
        if (dispatch == null) return
        observeSafely {
            val returnedAt = nanoTime()
            val span = dispatch.handle
            synchronized(span) {
                if (closed || span.sealed) return@observeSafely
                if (dispatch.initial) {
                    if (span.containerReturnedNs == null) span.containerReturnedNs = returnedAt
                } else if (span.containerRedispatchReturnedNs == null) {
                    span.containerRedispatchReturnedNs = returnedAt
                }
                span.containerResponseStatus = response.status
            }
        }
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        observeSafely {
            // Capture before identity/header work and before even this request's monitor.
            val enteredAt = nanoTime()
            val span = requestHandle(request) ?: return@observeSafely
            var registerDeferredObserver = false
            synchronized(span) {
                if (closed || span.sealed) return@observeSafely
                HttpPerformanceSocketContext.bind(span)
                if (span.enteredNs == null) {
                    if (request.dispatcherType != DispatcherType.REQUEST) {
                        span.errors.add("REDISPATCH_WITHOUT_INITIAL_MVC_ENTRY")
                        return@observeSafely
                    }
                    span.enteredNs = enteredAt
                    span.dispatches = 1
                    registerDeferredObserver = true
                } else {
                    span.dispatches++
                    if (request.dispatcherType == DispatcherType.REQUEST) {
                        span.errors.add("REPEATED_REQUEST_DISPATCH")
                    } else if (span.mvcRedispatchEnteredNs == null) {
                        span.mvcRedispatchEnteredNs = enteredAt
                    }
                }
            }
            if (registerDeferredObserver) {
                // Runs before controller argument decoding/validation, not at controller invocation.
                WebAsyncUtils.getAsyncManager(request).registerDeferredResultInterceptor(
                    HTTP_PERFORMANCE_MVC_ATTRIBUTE,
                    deferredObserver(span, response),
                )
            }
        }
        return true
    }

    override fun afterConcurrentHandlingStarted(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ) {
        observeRequest(request) { span, now ->
            if (span.mvcInitialAsyncExitNs == null) span.mvcInitialAsyncExitNs = now
            if (span.asyncStarts == 0) span.errors.add("ASYNC_WITHOUT_DEFERRED_RESULT_OBSERVER")
        }
    }

    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?,
    ) {
        observeRequest(request) { span, now -> if (span.mvcPostHandleNs == null) span.mvcPostHandleNs = now }
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        observeRequest(request) { span, now ->
            if (span.mvcAfterCompletionNs == null) span.mvcAfterCompletionNs = now
            if (ex != null) span.errorSignals++
            if (span.asyncStarts == 0) finish(span, "SYNC_MVC_AFTER_COMPLETION", response.status, now)
        }
    }

    internal fun deferredObserver(
        span: HttpPerformanceMvcHandle,
        response: HttpServletResponse,
    ): DeferredResultProcessingInterceptor =
        object : DeferredResultProcessingInterceptor {
            override fun <T : Any> beforeConcurrentHandling(
                request: NativeWebRequest,
                deferredResult: DeferredResult<T>,
            ) {
                observeHandle(span) { now ->
                    span.asyncStarts++
                    if (span.asyncProcessingStartedNs == null) span.asyncProcessingStartedNs = now
                }
            }

            override fun <T : Any> postProcess(
                request: NativeWebRequest,
                deferredResult: DeferredResult<T>,
                concurrentResult: Any?,
            ) {
                // Spring invokes this just before its concurrent-result handler dispatches the request.
                observeHandle(span) { now -> if (span.asyncResultReadyNs == null) span.asyncResultReadyNs = now }
            }

            override fun <T : Any> handleTimeout(
                request: NativeWebRequest,
                deferredResult: DeferredResult<T>,
            ): Boolean {
                observeHandle(span) { span.timeoutSignals++ }
                return true
            }

            override fun <T : Any> handleError(
                request: NativeWebRequest,
                deferredResult: DeferredResult<T>,
                throwable: Throwable,
            ): Boolean {
                observeHandle(span) { span.errorSignals++ }
                return true
            }

            override fun <T : Any> afterCompletion(
                request: NativeWebRequest,
                deferredResult: DeferredResult<T>,
            ) {
                observeHandle(span) { now -> finish(span, "ASYNC_WEB_REQUEST_COMPLETION", response.status, now) }
            }
        }

    private fun requestHandle(request: HttpServletRequest): HttpPerformanceMvcHandle? {
        if (!opened || closed || request.getAttribute(HTTP_PERFORMANCE_IGNORED_ATTRIBUTE) == true) return null
        val previous = request.getAttribute(HTTP_PERFORMANCE_MVC_ATTRIBUTE) as? HttpPerformanceMvcHandle
        if (previous != null) {
            if (isCurrent(previous)) return previous
            errors.add("FOREIGN_REQUEST_ATTRIBUTE")
            return null
        }
        if (request.method != "POST" ||
            request.requestURI.removePrefix(request.contextPath) != "/api/trading/markets/BTC-USDT/orders"
        ) {
            return null
        }

        fun singleIdentityHeader(name: String): String? {
            val values = request.getHeaders(name)
            if (!values.hasMoreElements()) return null
            val first = values.nextElement()
            if (values.hasMoreElements()) {
                errors.add("DUPLICATE_IDENTITY_HEADER:$name")
                return null
            }
            return first
        }
        val requestId = singleIdentityHeader(HTTP_PERFORMANCE_REQUEST_HEADER)
        val requestRunId = singleIdentityHeader(HTTP_PERFORMANCE_RUN_HEADER)
        val index = httpPerformanceMeasuredIndex(requestId, orders)
        if (requestRunId != runId || index == null) {
            ignoredHeaders.incrementAndGet()
            request.setAttribute(HTTP_PERFORMANCE_IGNORED_ATTRIBUTE, true)
            return null
        }
        if (request.dispatcherType != DispatcherType.REQUEST) {
            errors.add("REDISPATCH_WITHOUT_INITIAL_MVC_ENTRY")
            request.setAttribute(HTTP_PERFORMANCE_IGNORED_ATTRIBUTE, true)
            return null
        }
        val span = HttpPerformanceMvcHandle(index)
        if (!spans.compareAndSet(index, null, span)) {
            if (!closed) {
                duplicateRequests.incrementAndGet()
                errors.add("DUPLICATE_MEASURED_REQUEST")
            }
            request.setAttribute(HTTP_PERFORMANCE_IGNORED_ATTRIBUTE, true)
            return null
        }
        request.setAttribute(HTTP_PERFORMANCE_MVC_ATTRIBUTE, span)
        return span
    }

    private fun isCurrent(span: HttpPerformanceMvcHandle): Boolean = span.index in 0 until orders && spans.get(span.index) === span

    private inline fun observeRequest(
        request: HttpServletRequest,
        action: (HttpPerformanceMvcHandle, Long) -> Unit,
    ) {
        observeSafely {
            val now = nanoTime()
            val span = request.getAttribute(HTTP_PERFORMANCE_MVC_ATTRIBUTE) as? HttpPerformanceMvcHandle ?: return@observeSafely
            if (!isCurrent(span)) {
                if (!closed) errors.add("FOREIGN_REQUEST_ATTRIBUTE")
                return@observeSafely
            }
            synchronized(span) {
                if (!closed && !span.sealed) {
                    HttpPerformanceSocketContext.bind(span)
                    action(span, now)
                }
            }
        }
    }

    private inline fun observeHandle(
        span: HttpPerformanceMvcHandle,
        action: (Long) -> Unit,
    ) {
        observeSafely {
            val now = nanoTime()
            synchronized(span) {
                if (!closed && !span.sealed) {
                    HttpPerformanceSocketContext.bind(span)
                    action(now)
                }
            }
        }
    }

    private fun finish(
        span: HttpPerformanceMvcHandle,
        kind: String,
        status: Int,
        completed: Long,
    ) {
        span.terminalCallbacks++
        if (span.completedNs != null) {
            span.errors.add("DUPLICATE_TERMINAL_CALLBACK")
            return
        }
        if (span.enteredNs?.let { completed - it < 0 } == true) span.errors.add("NEGATIVE_MVC_DURATION")
        span.completedNs = completed
        span.completionKind = kind
        span.responseStatus = status
    }

    internal fun completedCount(): Int =
        (0 until orders).count { index -> spans.get(index)?.let { span -> synchronized(span) { span.completedNs != null } } == true }

    fun close(connectors: Map<String, Any?>): Map<String, Any?> =
        synchronized(controlLock) {
            check(opened && !closed) { "HTTP boundary diagnostic is not open" }
            closed = true
            val captured = mutableListOf<Map<String, Any?>>()
            val containerOnly = mutableListOf<Map<String, Any?>>()
            val containerIds = mutableSetOf<Int>()
            val capturedErrors = errors.toMutableSet()
            for (index in 0 until orders) {
                // Seal an absent slot too: a request racing close can never install a span behind the snapshot.
                if (spans.compareAndSet(index, null, closedEmpty)) continue
                val span = checkNotNull(spans.get(index))
                synchronized(span) {
                    span.sealed = true
                    if (socketTracingExpected && span.containerEnteredNs != null) {
                        if (span.socketDispatches.isEmpty()) span.errors.add("MISSING_SOCKET_DISPATCH_TRACE")
                        if (span.socketDispatches.any { trace ->
                                synchronized(trace) {
                                    trace.socketProcessingStartedNs == null || trace.handlerEnteredNs == null ||
                                        (trace.dispatch && (trace.submittedNs == null || trace.workerStartedNs == null))
                                }
                            }
                        ) {
                            span.errors.add("INCOMPLETE_SOCKET_DISPATCH_TRACE")
                        }
                        if (span.completedNs != null &&
                            span.socketDispatches.none { trace -> synchronized(trace) { trace.nativeWriteCalls > 0 } }
                        ) {
                            span.errors.add("MISSING_NATIVE_RESPONSE_WRITE")
                        }
                    }
                    capturedErrors.addAll(span.errors)
                    if (span.containerEnteredNs != null) containerIds.add(index)
                    if (span.enteredNs != null) {
                        captured.add(snapshot(span))
                    } else if (span.containerEnteredNs != null) {
                        containerOnly.add(
                            mapOf(
                                "requestId" to "M-$index",
                                "containerDurationNs" to span.containerReturnedNs?.minus(checkNotNull(span.containerEnteredNs)),
                                "containerResponseStatus" to span.containerResponseStatus,
                                "gatewayLocalPoints" to localPoints(span),
                            ),
                        )
                    }
                }
            }
            val incomplete = captured.filter { it["mvcDurationNs"] == null }.map { it["requestId"] }
            if (incomplete.isNotEmpty()) capturedErrors.add("INCOMPLETE_MVC_SPANS_AT_CUTOFF")
            if (connectorsAtOpen?.get("status") != "CAPTURED" || connectors["status"] != "CAPTURED") {
                capturedErrors.add("CONNECTOR_CAPTURE_UNAVAILABLE")
            }
            mapOf(
                "enabled" to true,
                "runId" to runId,
                "capacity" to orders,
                "status" to if (capturedErrors.isEmpty() && observerFailures.get() == 0L) "CLOSED" else "ERROR",
                "clock" to "gateway System.nanoTime local differences only",
                "boundary" to "first MVC preHandle -> AsyncWebRequest completion; sync -> MVC afterCompletion",
                "containerBoundary" to
                    "Tomcat Context Valve entry/return; excludes socket parse, accept and executor queue before the Valve",
                "phaseOffsetOrigin" to "first MVC preHandle entry before observer identity validation; same gateway JVM only",
                "transportAckClaim" to false,
                "socketTracingExpected" to socketTracingExpected,
                "socketBoundary" to
                    "gateway-local Tomcat event admission/submit/worker/socket-lock/handler/register; only events bound at Valve or async callbacks; earlier partial-header events and accept/poller/OS queues excluded",
                "nativeWriteBoundary" to
                    "plain HTTP NioChannel OS-write calls scoped to a dispatch, which may service multiple requests; excludes TLS and off-dispatch writes; no client-receive or transport-ACK claim; completion/write order may vary",
                "coverage" to "the existing CompletableFuture order endpoint; native Servlet async and Callable are unsupported",
                "enteredCount" to captured.size,
                "completedCount" to captured.count { it["mvcDurationNs"] != null },
                "containerEnteredCount" to containerIds.size,
                "containerMissingRequestIds" to (0 until orders).filter { it !in containerIds }.map { "M-$it" },
                "containerOnlySpans" to containerOnly,
                "ignoredHeaderCount" to ignoredHeaders.get(),
                "duplicateRequestCount" to duplicateRequests.get(),
                "observerFailureCount" to observerFailures.get(),
                "errors" to capturedErrors.sorted(),
                "missingRequestIds" to (0 until orders).filter { index -> captured.none { it["requestId"] == "M-$index" } }.map { "M-$it" },
                "incompleteRequestIds" to incomplete,
                "connectorsAtOpen" to connectorsAtOpen,
                "connectorsAtClose" to connectors,
                "spans" to captured,
            )
        }

    private fun snapshot(span: HttpPerformanceMvcHandle): Map<String, Any?> {
        val entered = checkNotNull(span.enteredNs)

        fun offset(value: Long?): Long? = value?.minus(entered)

        fun duration(
            start: Long?,
            end: Long?,
        ): Long? = start?.let { first -> end?.minus(first) }
        return mapOf(
            "requestId" to "M-${span.index}",
            "mvcDurationNs" to offset(span.completedNs),
            "completionKind" to span.completionKind,
            "responseStatus" to span.responseStatus,
            "dispatchCount" to span.dispatches,
            "asyncStartCount" to span.asyncStarts,
            "terminalCallbackCount" to span.terminalCallbacks,
            "errorSignalCount" to span.errorSignals,
            "timeoutSignalCount" to span.timeoutSignals,
            "containerDispatchCount" to span.containerDispatches,
            "containerResponseStatus" to span.containerResponseStatus,
            "containerToMvcNs" to duration(span.containerEnteredNs, span.enteredNs),
            "mvcToAsyncResultReadyNs" to offset(span.asyncResultReadyNs),
            "asyncResultReadyToRedispatchNs" to duration(span.asyncResultReadyNs, span.mvcRedispatchEnteredNs),
            "redispatchToCompletionNs" to duration(span.mvcRedispatchEnteredNs, span.completedNs),
            "gatewayLocalPoints" to localPoints(span),
            "socketDispatches" to span.socketDispatches.map { it.snapshot() },
            "phaseOffsetsNs" to
                mapOf(
                    "containerEntry" to offset(span.containerEnteredNs),
                    "containerReturn" to offset(span.containerReturnedNs),
                    "containerRedispatchEntry" to offset(span.containerRedispatchEnteredNs),
                    "containerRedispatchReturn" to offset(span.containerRedispatchReturnedNs),
                    "mvcEntry" to 0L,
                    "asyncProcessingStarted" to offset(span.asyncProcessingStartedNs),
                    "asyncResultReady" to offset(span.asyncResultReadyNs),
                    "mvcInitialAsyncExit" to offset(span.mvcInitialAsyncExitNs),
                    "mvcRedispatchEntry" to offset(span.mvcRedispatchEnteredNs),
                    "mvcPostHandle" to offset(span.mvcPostHandleNs),
                    "mvcAfterCompletion" to offset(span.mvcAfterCompletionNs),
                    "asyncWebRequestCompletion" to if (span.asyncStarts > 0) offset(span.completedNs) else null,
                ),
        )
    }

    /** Raw points are joinable only with another trace explicitly originating in this same gateway JVM. */
    private fun localPoints(span: HttpPerformanceMvcHandle): Map<String, Any?> =
        mapOf(
            "clock" to "gateway JVM System.nanoTime only",
            "tomcatEnteredNs" to span.containerEnteredNs,
            "tomcatReturnedNs" to span.containerReturnedNs,
            "tomcatRedispatchEnteredNs" to span.containerRedispatchEnteredNs,
            "tomcatRedispatchReturnedNs" to span.containerRedispatchReturnedNs,
            "mvcEnteredNs" to span.enteredNs,
            "asyncProcessingStartedNs" to span.asyncProcessingStartedNs,
            "asyncResultReadyNs" to span.asyncResultReadyNs,
            "mvcInitialAsyncExitNs" to span.mvcInitialAsyncExitNs,
            "mvcRedispatchEnteredNs" to span.mvcRedispatchEnteredNs,
            "mvcPostHandleNs" to span.mvcPostHandleNs,
            "mvcAfterCompletionNs" to span.mvcAfterCompletionNs,
            "completedNs" to span.completedNs,
        )

    private inline fun observeSafely(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            if (!closed) observerFailures.incrementAndGet()
        }
    }
}

internal class HttpPerformanceContextValve(
    private val boundaries: HttpPerformanceMvcBoundaries,
) : ValveBase(true) {
    override fun invoke(
        request: Request,
        response: Response,
    ) {
        boundaries.clockProbe(request, response)
        val dispatch = boundaries.containerEntered(request)
        try {
            next.invoke(request, response)
        } finally {
            boundaries.containerReturned(dispatch, response)
        }
    }
}

internal fun httpPerformanceBoundaryTomcatCustomizer(
    boundaries: HttpPerformanceMvcBoundaries,
): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
    WebServerFactoryCustomizer { factory ->
        boundaries.expectSocketTracing()
        factory.setProtocol(HttpPerformanceSocketProtocol::class.java.name)
        factory.addContextValves(HttpPerformanceContextValve(boundaries))
    }

internal fun httpPerformanceBoundaryMvcConfigurer(boundaries: HttpPerformanceMvcBoundaries): WebMvcConfigurer =
    object : WebMvcConfigurer {
        override fun addInterceptors(registry: InterceptorRegistry) {
            registry.addInterceptor(boundaries).addPathPatterns("/api/trading/markets/BTC-USDT/orders")
        }
    }

@Configuration(proxyBeanMethods = false)
@Profile("http-performance")
@ConditionalOnProperty(name = ["performance.http-boundaries.enabled"], havingValue = "true")
internal class HttpPerformanceHttpBoundaryConfiguration {
    @Bean
    fun performanceMvcBoundaries(): HttpPerformanceMvcBoundaries =
        HttpPerformanceMvcBoundaries(
            requireNotNull(System.getenv("FUNDING_RUN_ID")),
            requireNotNull(System.getenv("PERFORMANCE_HTTP_BOUNDARY_ORDERS")).toInt(),
        )

    @Bean
    fun performanceBoundaryMvcConfigurer(boundaries: HttpPerformanceMvcBoundaries): WebMvcConfigurer =
        httpPerformanceBoundaryMvcConfigurer(boundaries)

    @Bean
    fun performanceBoundaryTomcatCustomizer(
        boundaries: HttpPerformanceMvcBoundaries,
    ): WebServerFactoryCustomizer<TomcatServletWebServerFactory> = httpPerformanceBoundaryTomcatCustomizer(boundaries)
}

/** Reflective getters read the actual running connector; unavailable values remain explicit, never defaults. */
internal fun captureHttpPerformanceTomcat(context: ConfigurableApplicationContext): Map<String, Any?> {
    val errors = mutableListOf<String>()

    fun call(
        target: Any,
        method: String,
    ): Any? = target.javaClass.getMethod(method).invoke(target)

    fun field(
        target: Any?,
        method: String,
        required: Boolean = true,
    ): Map<String, Any?> =
        try {
            val value = call(checkNotNull(target), method)
            check(value == null || value is Number || value is Boolean || value is String) { "NON_SCALAR_VALUE" }
            mapOf("available" to true, "value" to value)
        } catch (failure: Exception) {
            if (required) errors.add("$method:${failure.javaClass.simpleName}")
            mapOf("available" to false, "error" to failure.javaClass.simpleName)
        }
    val connectors =
        try {
            val server = checkNotNull((context as? ServletWebServerApplicationContext)?.webServer)
            val tomcat = checkNotNull(call(server, "getTomcat"))
            val service = checkNotNull(call(tomcat, "getService"))
            val actual = call(service, "findConnectors") as Array<*>
            check(actual.size in 1..16) { "CONNECTOR_COUNT_OUT_OF_BOUNDS" }
            actual.map { entry ->
                val connector = checkNotNull(entry)
                val protocol = checkNotNull(call(connector, "getProtocolHandler"))
                val executor = call(protocol, "getExecutor")
                mapOf(
                    "connectorClass" to connector.javaClass.name,
                    "protocolClass" to protocol.javaClass.name,
                    "executorClass" to executor?.javaClass?.name,
                    "port" to field(connector, "getPort"),
                    "localPort" to field(connector, "getLocalPort"),
                    "protocol" to field(connector, "getProtocol"),
                    "keepAliveTimeoutMs" to field(protocol, "getKeepAliveTimeout"),
                    "maxKeepAliveRequests" to field(protocol, "getMaxKeepAliveRequests"),
                    "connectionTimeoutMs" to field(protocol, "getConnectionTimeout"),
                    "acceptCount" to field(protocol, "getAcceptCount"),
                    "acceptCountMeaning" to "configured Tomcat accept backlog; effective OS backlog is not measured",
                    "maxConnections" to field(protocol, "getMaxConnections"),
                    "connectionCount" to field(protocol, "getConnectionCount"),
                    "maxThreads" to field(protocol, "getMaxThreads"),
                    "processorCache" to field(protocol, "getProcessorCache"),
                    "minSpareThreads" to field(protocol, "getMinSpareThreads"),
                    "executorPoolSize" to field(executor, "getPoolSize", required = false),
                    "executorActiveCount" to field(executor, "getActiveCount", required = false),
                    "executorCorePoolSize" to field(executor, "getCorePoolSize", required = false),
                    "executorMaximumPoolSize" to field(executor, "getMaximumPoolSize", required = false),
                )
            }
        } catch (failure: Exception) {
            errors.add("CONNECTOR_DISCOVERY:${failure.javaClass.simpleName}")
            emptyList()
        }
    return mapOf(
        "status" to if (errors.isEmpty()) "CAPTURED" else "PARTIAL",
        "errors" to errors.toList(),
        "connectors" to connectors,
        "tomcatRegistryClass" to if (connectors.isEmpty()) null else Registry.getRegistry(null).javaClass.name,
        "linuxTcp" to captureHttpPerformanceLinuxTcp(),
    )
}

/** Join identities only. Absolute nanoTime values from different JVMs are deliberately absent. */
internal fun joinHttpPerformanceHttpBoundaries(
    runId: String,
    requests: List<HttpPerformanceRequestSample>,
    server: JsonNode?,
): Map<String, Any?> {
    val errors = linkedSetOf<String>()
    if (server == null || server.path("runId").asString() != runId) errors.add("MISSING_OR_WRONG_SERVER_RUN")
    val rows = server?.path("spans")?.toList().orEmpty()
    if (server != null && (
            !server.path("spans").isArray || server.path("capacity").asInt() != requests.size ||
                server.path("enteredCount").asInt() != rows.size
        )
    ) {
        errors.add("SERVER_BOUNDARY_SHAPE_MISMATCH")
    }
    val byId = rows.associateBy { it.path("requestId").asString() }
    if (byId.size != rows.size) errors.add("DUPLICATE_SERVER_REQUEST_ID")
    val ids = requests.map { it.requestId }.toSet()
    if (ids.size != requests.size || byId.keys.any { it !in ids }) errors.add("RAW_REQUEST_ID_MISMATCH")
    if (server != null && server
            .path("missingRequestIds")
            .toList()
            .map { it.asString() }
            .toSet() != ids - byId.keys
    ) {
        errors.add("SERVER_MISSING_ID_MISMATCH")
    }
    if (server != null && (server.path("status").asString() != "CLOSED" || !server.path("errors").isEmpty)) {
        errors.add("SERVER_BOUNDARY_INSTRUMENTATION_ERROR")
    }
    val joined =
        requests.map { client ->
            val span = byId[client.requestId]
            if (client.httpOutcome == HttpPerformanceOutcome.ACCEPTED &&
                (
                    span == null || !span.path("mvcDurationNs").isIntegralNumber || span.path("mvcDurationNs").asLong() < 0 ||
                        !span.path("terminalCallbackCount").isIntegralNumber || span.path("terminalCallbackCount").asLong() != 1L ||
                        !span.path("responseStatus").isIntegralNumber || span.path("responseStatus").asLong() != 202L
                )
            ) {
                errors.add("ACCEPTED_WITHOUT_MATCHING_COMPLETED_MVC_SPAN")
            }
            mapOf(
                "requestId" to client.requestId,
                "clientHttpOutcome" to client.httpOutcome,
                "clientHttpDetail" to client.httpDetail,
                "clientHttpLocalDurationNs" to client.startNs?.let { start -> client.httpResultNs?.minus(start) },
                "serverMvcLocalDurationNs" to span?.path("mvcDurationNs")?.takeUnless { it.isNull || it.isMissingNode }?.asLong(),
                "serverCompletionKind" to span?.path("completionKind")?.takeUnless { it.isNull || it.isMissingNode }?.asString(),
                "serverResponseStatus" to span?.path("responseStatus")?.takeUnless { it.isNull || it.isMissingNode }?.asInt(),
                "serverEntered" to (span != null),
                "serverContainerToMvcNs" to span?.path("containerToMvcNs")?.takeIf { it.isIntegralNumber }?.asLong(),
                "serverAsyncReadyToRedispatchNs" to span?.path("asyncResultReadyToRedispatchNs")?.takeIf { it.isIntegralNumber }?.asLong(),
                "serverRedispatchToCompletionNs" to span?.path("redispatchToCompletionNs")?.takeIf { it.isIntegralNumber }?.asLong(),
                "serverGatewayLocalPoints" to span?.path("gatewayLocalPoints")?.takeIf { it.isObject },
                "serverPhaseOffsetsNs" to span?.path("phaseOffsetsNs")?.takeIf { it.isObject },
            )
        }
    return mapOf(
        "runId" to runId,
        "clockRule" to "Join by run/request identifiers; compare separately measured local durations. Never subtract JVM clock origins.",
        "errors" to errors.toList(),
        "requests" to joined,
    )
}
