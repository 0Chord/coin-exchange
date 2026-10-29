package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal data class HttpPerformanceOptions(
    val warmup: Int = 200,
    val orders: Int = 2000,
    val rates: List<Int> = listOf(50, 200, 1000),
    val repetitions: Int = 3,
    val maxInFlight: Int = 1024,
    val drainSeconds: Long = 60,
    val diagnosticProfiles: Boolean = false,
    val optimizedProduct: Boolean = false,
    val stageDiagnostics: Boolean = false,
    val detailedTracing: Boolean = true,
    val markerIdlePolicy: HttpPerformanceMarkerIdlePolicy = HttpPerformanceMarkerIdlePolicy.SLEEP_1MS,
    val gatewayCpuPeriodUs: Int? = null,
    val gatewayCpuCount: Int = 2,
    val capacityProbe: Boolean = false,
    val httpBoundaryDiagnostics: Boolean = false,
    val httpWarmConnectionsDiagnostic: Boolean = false,
    val sustainedSeconds: Int? = null,
    val preserveNodeRecordings: Boolean = false,
) {
    val sustained: Boolean get() = sustainedSeconds != null

    init {
        require(!httpBoundaryDiagnostics || (stageDiagnostics && optimizedProduct && !capacityProbe))
        require(
            !httpWarmConnectionsDiagnostic ||
                (
                    httpBoundaryDiagnostics && stageDiagnostics && optimizedProduct && diagnosticProfiles && detailedTracing &&
                        !capacityProbe && warmup in setOf(200, 2000) && orders == 1000 && rates == listOf(5000) && repetitions == 1 &&
                        maxInFlight == 1024 && drainSeconds == 60L && gatewayCpuPeriodUs == null && gatewayCpuCount == 2 &&
                        markerIdlePolicy == HttpPerformanceMarkerIdlePolicy.SLEEP_1MS
                ),
        ) { "HTTP connection warmup은 기존 boundary 진단의 자원·입력을 유지하는 별도 진단 flag에서만 허용합니다" }
        require(!preserveNodeRecordings || sustained) { "노드 원본 보존은 명시적 HTTP sustained 실행에서만 허용합니다" }
        if (sustained) {
            require(sustainedSeconds == 60 && warmup == 200 && orders == 60_000 && rates == listOf(1000) && repetitions == 1)
            require(maxInFlight == 1024 && drainSeconds == 60L && optimizedProduct && !capacityProbe)
            require(
                !diagnosticProfiles && !stageDiagnostics && !detailedTracing && !httpBoundaryDiagnostics && !httpWarmConnectionsDiagnostic,
            )
            require(gatewayCpuPeriodUs == null && gatewayCpuCount == 2 && markerIdlePolicy == HttpPerformanceMarkerIdlePolicy.SLEEP_1MS)
        } else {
            require(warmup in 1..2000 && orders in 1..2000)
        }
        val maximumRate = if (capacityProbe) 20_000 else 10_000
        require(rates.isNotEmpty() && rates.all { it in 1..maximumRate } && rates.distinct().size == rates.size)
        require(repetitions in 1..3 && maxInFlight in 1..1024 && drainSeconds == 60L)
        require(gatewayCpuPeriodUs == null || gatewayCpuPeriodUs in setOf(10_000, 100_000))
        require(gatewayCpuCount in setOf(2, 8) && (gatewayCpuCount == 2 || gatewayCpuPeriodUs != null))
        require(
            !capacityProbe ||
                (
                    warmup == 200 && orders == 2000 && repetitions == 1 && maxInFlight == 1024 &&
                        !diagnosticProfiles && !stageDiagnostics && !detailedTracing &&
                        markerIdlePolicy == HttpPerformanceMarkerIdlePolicy.SLEEP_1MS &&
                        gatewayCpuPeriodUs == null && gatewayCpuCount == 2
                ),
        ) { "capacity probe는 기존 자원과 유한한 2000건의 무프로파일 burst만 허용합니다" }
    }
}

internal fun parseHttpPerformanceOptions(args: Array<String>): HttpPerformanceOptions {
    if (args.firstOrNull() == "--sustained") {
        require(
            args.size in 3..4 && args[1] == "--rate=1000" && args[2] == "--seconds=60" &&
                (args.size == 3 || args[3] == "--preserve-node-recordings"),
        ) {
            "HTTP 장기: --sustained --rate=1000 --seconds=60 [--preserve-node-recordings]"
        }
        return HttpPerformanceOptions(
            orders = 60_000,
            rates = listOf(1000),
            repetitions = 1,
            optimizedProduct = true,
            detailedTracing = false,
            sustainedSeconds = 60,
            preserveNodeRecordings = args.size == 4,
        )
    }
    val flag = args.singleOrNull()
    val optimized =
        flag in
            setOf(
                "--optimized",
                "--optimized-probe",
                "--optimized-diagnostic",
                "--optimized-smoke",
                "--http-overload-diagnostic",
                "--http-boundary-diagnostic",
                "--http-boundary-smoke",
                "--http-warm-connections-diagnostic",
            )
    val smoke = flag in setOf("--smoke", "--optimized-smoke")
    val diagnostic = flag in setOf("--diagnostic", "--optimized-diagnostic")
    require(args.isEmpty() || smoke || diagnostic || optimized || flag == "--capacity-probe") {
        "기준선: 인자 없음/--smoke/--diagnostic, 개선 코드: --optimized/--optimized-probe/--optimized-diagnostic/--optimized-smoke, 짧은 burst: --capacity-probe"
    }
    return when {
        flag == "--http-boundary-smoke" -> {
            HttpPerformanceOptions(1, 2, listOf(10), 1, optimizedProduct = true, stageDiagnostics = true, httpBoundaryDiagnostics = true)
        }

        flag in setOf("--http-overload-diagnostic", "--http-boundary-diagnostic", "--http-warm-connections-diagnostic") -> {
            // A single cohort fits the unchanged 1024-request generator budget; this is a profiled diagnosis, not capacity proof.
            HttpPerformanceOptions(
                orders = 1000,
                rates = listOf(5000),
                repetitions = 1,
                diagnosticProfiles = true,
                optimizedProduct = true,
                stageDiagnostics = true,
                httpBoundaryDiagnostics = flag in setOf("--http-boundary-diagnostic", "--http-warm-connections-diagnostic"),
                httpWarmConnectionsDiagnostic = flag == "--http-warm-connections-diagnostic",
            )
        }

        flag == "--capacity-probe" -> {
            HttpPerformanceOptions(
                rates = listOf(1000, 5000, 10_000, 20_000),
                repetitions = 1,
                optimizedProduct = true,
                detailedTracing = false,
                capacityProbe = true,
            )
        }

        smoke -> {
            HttpPerformanceOptions(1, 2, listOf(10), 1, optimizedProduct = optimized)
        }

        diagnostic || flag == "--optimized-probe" -> {
            HttpPerformanceOptions(
                rates = listOf(50, 1000),
                repetitions = 1,
                diagnosticProfiles = diagnostic,
                optimizedProduct = optimized,
            )
        }

        else -> {
            HttpPerformanceOptions(optimizedProduct = optimized)
        }
    }
}

internal fun httpPerformanceWindowNs(
    options: HttpPerformanceOptions,
    rate: Int,
): Long {
    require(rate in options.rates) { "실행 rate는 검증한 예정 rate 중 하나여야 합니다" }
    return TimeUnit.SECONDS.toNanos(options.orders.toLong()) / rate
}

internal fun plannedHttpPerformanceRequests(
    options: HttpPerformanceOptions,
    rate: Int,
    firstDueNs: Long,
): List<PlannedHttpRequest> {
    httpPerformanceWindowNs(options, rate)
    return List(options.orders) { i ->
        PlannedHttpRequest("BUYER", "M-$i", "M-$i", firstDueNs + i.toLong() * 1_000_000_000L / rate)
    }
}

internal data class HttpPerformanceRunResult(
    val runId: String,
    val offeredRate: Int,
    val repetition: Int,
    val reportDirectory: String,
    val summary: HttpPerformanceSummary?,
    val stateCheck: Map<String, Any>?,
    val invalidReasons: List<String>,
    val capacityProbe: Boolean = false,
    val plannedWindowNs: Long? = null,
    val sustainedEvidence: Map<String, Any?>? = null,
)

/** 기존 HTTP 입구에 고정 예정 시각으로 주문하고 같은 JVM 시계로 접수와 실행 관측을 측정한다. */
internal fun runHttpPerformanceBaseline(
    options: HttpPerformanceOptions,
    rate: Int,
    repetition: Int,
    reportRoot: Path,
): HttpPerformanceRunResult {
    val windowNs = httpPerformanceWindowNs(options, rate)
    val project = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
    val distribution = Path.of(requireNotNull(System.getProperty("fundingDistributionDirectory")))
    val runId = "perf${System.currentTimeMillis()}${UUID.randomUUID().toString().take(8)}"
    val reports = reportRoot.resolve(runId)
    Files.createDirectories(reports)
    val mapper = jacksonObjectMapper()
    val active = AtomicReference<HttpPerformanceLoad?>()
    val errors = mutableListOf<String>()
    var summary: HttpPerformanceSummary? = null
    var stateCheck: Map<String, Any>? = null
    var measurements: HttpPerformanceMeasurements? = null
    var cutoff: Long? = null
    var fixedFirstDue: Long? = null
    var fixedWindowEnd: Long? = null
    var loopStoppedNs: Long? = null
    var rawArtifact: HttpPerformanceSustainedRawArtifact? = null
    var sustainedEvidence: Map<String, Any?>? = null
    var restoreInterrupt = false
    var httpClosed = false
    var serverHttpBoundaries: JsonNode? = null
    var connectionWarmup: HttpPerformanceConnectionWarmupReport? = null
    val source = performanceSourceFingerprint(project, options.optimizedProduct)
    val verifyFingerprints = options.stageDiagnostics || options.capacityProbe || options.sustained
    val distributionFingerprint = if (verifyFingerprints) performanceDistributionFingerprint(distribution) else null
    val clientStages = HttpPerformanceClientStages(options.stageDiagnostics && options.detailedTracing)
    val httpFailures = if (options.httpBoundaryDiagnostics) HttpPerformanceHttpFailures(runId, options.orders) else null
    val cluster =
        HttpPerformanceCluster(
            project,
            distribution,
            runId,
            reports,
            sampleRuntimeCounters = options.diagnosticProfiles || options.stageDiagnostics,
            stageDiagnostics = options.stageDiagnostics,
            detailedTracing = options.detailedTracing,
            markerIdlePolicy = options.markerIdlePolicy,
            cpuPeriodUs = options.gatewayCpuPeriodUs,
            gatewayCpuCount = options.gatewayCpuCount,
            httpBoundaryOrders = options.orders.takeIf { options.httpBoundaryDiagnostics },
            preserveNodeRecordings = options.preserveNodeRecordings,
            markerPlan = HttpPerformanceMarkerPlan(runId, options.warmup, options.orders),
        ) { marker, receivedNs ->
            active.get()?.observe(marker, receivedNs)
        }
    val manifest =
        linkedMapOf<String, Any?>(
            "runId" to runId,
            "startedAt" to Instant.now().toString(),
            "options" to options,
            "rate" to rate,
            "repetition" to repetition,
            "source" to source,
            "distributionSha256" to distributionFingerprint,
            "input" to
                mapOf(
                    "market" to "BTC-USDT",
                    "price" to 10_000,
                    "quantity" to 100_000_000,
                    "makerPpm" to 1000,
                    "takerPpm" to 2000,
                    "preparationOrders" to options.warmup + options.orders,
                ),
            "clock" to "부하 JVM 하나의 System.nanoTime 차분",
            "httpTimeoutMs" to 6000,
            "completionDeadlineMs" to 10_000,
            "completionQueueCapacity" to 8192,
            "httpVersion" to "HTTP/1.1",
            "automaticRetries" to false,
        )
    if (options.capacityProbe) {
        manifest["capacityProbe"] =
            mapOf(
                "kind" to "BOUNDED_HTTP_BURST",
                "plannedOrders" to options.orders,
                "offeredPerSecond" to rate,
                "plannedWindowNs" to windowNs,
                "durationFormula" to "N / offered rate",
                "sustainedThroughputClaim" to false,
            )
    }
    if (options.sustained) {
        manifest["sustained"] =
            mapOf(
                "kind" to "HTTP_SUSTAINED_60S_FIXED_COHORT",
                "schemaVersion" to 2,
                "plannedOrders" to options.orders,
                "clock" to "same HTTP client and marker receiver parent JVM System.nanoTime; original fixed due origin",
                "rawArtifactFile" to HTTP_SUSTAINED_RAW_NAME,
                "maximumSustainableThroughputClaim" to false,
                "windowAndDrain" to (
                    "all starts inside 60s; leader latency separate from all-three-member receives; " +
                        "cohort requires all three by original cutoff, target requires all three inside original window; " +
                        "fixed additional drain<=60s"
                ),
                "resources" to "unchanged CPU/heap/sync/HTTP timeout/HTTP1.1; inflight1024; no detailed tracing or profiler",
                "rawScope" to (
                    "all planned legacy leader sample fields plus member0/1/2 first receivedNs and allCopiesDoneNs; " +
                        "not full wire response bytes"
                ),
                "startBoundary" to
                    "long: after HTTP body/request construction immediately before sendAsync; body preparation remains in due-to-start delay; legacy modes retain their original pre-construction start",
                "preserveNodeRecordings" to options.preserveNodeRecordings,
            )
    }
    if (options.httpWarmConnectionsDiagnostic) {
        manifest["httpWarmConnectionsDiagnostic"] =
            linkedMapOf<String, Any?>(
                "phase" to "PLANNED",
                "additionalHttpRequests" to HTTP_PERFORMANCE_CONNECTION_WARMUP_REQUESTS,
                "concurrentSubmissionBound" to HTTP_PERFORMANCE_CONNECTION_WARMUP_REQUESTS,
                "requestId" to "W-0",
                "orderId" to "W-0",
                "userId" to "BUYER",
                "sameHttpClientInstance" to true,
                "includedInMeasuredTPS" to false,
                "additionalPreparationOrders" to 0,
                "additionalWarmupBuys" to 0,
                "actualConnectionCount" to "separate connector observation; requested calls do not guarantee a connection count",
                "additionalLogInput" to
                    "HTTP retransmissions may produce extra admission log entries; concurrent identical keys can share a pending result",
                "context" to
                    "same HTTP URI/body and market/fee configuration; Gateway generates transport ledger/time normally; stored first context is checked unchanged",
                "hypothesis" to "cold pool/connection expansion diagnostic; not an established cause",
            )
    }
    val http =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build()
    var clockCalibration: HttpPerformanceClockCalibration? = null

    fun closeHttpClient() {
        if (!httpClosed) {
            http.close()
            httpClosed = true
        }
    }
    var clusterStarted = false
    var finalEvidenceAttempted = false
    var measurementStarted = false
    var profileStartAttempted = false

    fun collectFinalEvidence(): Map<Int, JsonNode>? {
        finalEvidenceAttempted = true
        val (captured, verified) =
            collectHttpPerformanceFinalEvidence(
                errors,
                closeObservation = {
                    if (options.stageDiagnostics && (!options.httpWarmConnectionsDiagnostic || measurementStarted)) {
                        attemptHttpPerformanceEvidence(errors, "Gateway 관찰 종료") {
                            val stages =
                                try {
                                    cluster.endMeasurement()
                                } finally {
                                    if (options.httpBoundaryDiagnostics) serverHttpBoundaries = cluster.httpBoundaryEvidence()
                                }
                            for (phase in HTTP_GATEWAY_TIMER_PHASES) {
                                check(
                                    stages
                                        .path("timers")
                                        .path(phase)
                                        .path("count")
                                        .asLong() == options.orders.toLong(),
                                ) {
                                    "Gateway $phase 측정 표본 수 불일치"
                                }
                            }
                        }
                    }
                    if (options.diagnosticProfiles) {
                        if (!options.httpWarmConnectionsDiagnostic || measurementStarted) {
                            attemptHttpPerformanceEvidence(
                                errors,
                                "진단 follower 완료",
                            ) { awaitDiagnosticFollowerCompletions(cluster, options.orders) }
                        }
                        if (!options.httpWarmConnectionsDiagnostic || profileStartAttempted) {
                            attemptHttpPerformanceEvidence(errors, "진단 프로파일 종료") { cluster.stopJvmProfiles() }
                        }
                    }
                },
                capture = {
                    try {
                        cluster.checkpoint("final", 60_000)
                    } catch (failure: Exception) {
                        errors.add("최종 관찰 대조: ${failure.javaClass.simpleName}: ${failure.message}")
                        // 관찰 오류를 성공으로 바꾸지 않는다. 같은 위치의 금융 상태 대조는 별도로 보존한다.
                        cluster.checkpoint("financial-final", 60_000, verifyObservation = false)
                    }
                },
                verify = { checkHttpPerformanceState(it, options.warmup, options.orders) },
                health = cluster::assertHealthy,
            )
        stateCheck = verified
        return captured
    }

    fun collectFinalFileEvidence(): Map<Int, HttpPerformanceCheckpointFileReference>? {
        if (Thread.interrupted()) {
            restoreInterrupt = true
            errors.add("HTTP 장기 최종 증거 수집 전 interrupt; 원래 실패를 유지하고 종료 증거를 시도합니다")
        }
        finalEvidenceAttempted = true
        val (captured, verified) =
            collectHttpPerformanceFinalEvidence(
                errors,
                closeObservation = {},
                capture = {
                    try {
                        cluster.checkpointFiles("final", 120_000)
                    } catch (failure: Exception) {
                        errors.add("최종 관찰 files 대조: ${failure.javaClass.simpleName}: ${failure.message}")
                        if (failure is InterruptedException || Thread.currentThread().isInterrupted) {
                            restoreInterrupt = true
                            Thread.interrupted()
                        }
                        cluster.checkpointFiles("financial-final", 120_000, verifyObservation = false)
                    }
                },
                verify = { checkHttpPerformanceStateFiles(it, options.warmup, options.orders) },
                health = cluster::assertHealthy,
            )
        stateCheck = verified
        return captured
    }
    try {
        cluster.start()
        clusterStarted = true
        if (options.httpBoundaryDiagnostics) {
            manifest["clockCalibration"] = mapOf("artifactFile" to "clock-calibration.json", "outsideMeasuredTPS" to true)
            clockCalibration =
                HttpPerformanceClockCalibration(runId, cluster.orderUri.resolve("/actuator/health")) { request ->
                    http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                }
        }
        val m = options.warmup.toLong() + options.orders
        cluster.fund("P-BUYER", "BUYER", "USDT", m * 10_020 + 1_000)
        cluster.fund("P-SELLER", "SELLER", "BTC", m * 100_000_000)
        prepareHttpPerformanceOrders(http, cluster.orderUri, "SELLER", "SELL", "S-", m.toInt())
        if (options.sustained) {
            HttpPerformanceFinancialBounds(options.warmup, options.orders)
            awaitDirectAeronPreparationFiles(cluster, "sellers", m, m)
            prepareHttpPerformanceOrders(http, cluster.orderUri, "BUYER", "BUY", "W-", options.warmup)
            val before = awaitDirectAeronPreparationFiles(cluster, "warmup", m + options.warmup, options.orders.toLong())
            manifest["beforeStateSizes"] = before.getValue(0).rowCounts
        } else {
            val sellers = awaitPerformanceCheckpoint(cluster, "sellers", m)
            check(
                sellers.values.all {
                    it
                        .path("trading")
                        .path("books")
                        .sumOf { book -> book.path("orders").size() }
                        .toLong() == m
                },
            )
            prepareHttpPerformanceOrders(http, cluster.orderUri, "BUYER", "BUY", "W-", options.warmup)
            var before = awaitPerformanceCheckpoint(cluster, "warmup", m + options.warmup)
            if (options.httpWarmConnectionsDiagnostic) {
                @Suppress("UNCHECKED_CAST")
                val warmupManifest = manifest.getValue("httpWarmConnectionsDiagnostic") as MutableMap<String, Any?>
                val sequence = httpPerformanceWarmConnectionSequence(before)
                warmupManifest["expectedFirstSequence"] = sequence
                warmupManifest["financialBefore"] = checkHttpPerformanceState(before, options.warmup, options.orders)
                warmupManifest["phase"] = "SUBMITTING"
                val warmed =
                    warmHttpPerformanceConnections(cluster.orderUri, sequence) { request ->
                        // Reuse the exact HttpClient used for SELL/W preparation and the measured load.
                        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    }
                connectionWarmup = warmed
                warmupManifest.putAll(warmed.summary())
                warmupManifest["phase"] = "RESPONSES_COLLECTED"
                warmupManifest["rawArtifact"] = "http-warm-connections.json"
                val after = awaitPerformanceCheckpoint(cluster, "warmup-connections", m + options.warmup)
                checkHttpPerformanceWarmConnectionState(before, after)
                warmupManifest["financialStateCheck"] = checkHttpPerformanceState(after, options.warmup, options.orders)
                warmupManifest["financialStateUnchanged"] = true
                warmupManifest["phase"] = if (warmed.succeeded) "VERIFIED" else "FAILED"
                check(warmed.succeeded) { "HTTP connection warmup 준비 실패: ${warmed.failedAttempts}/${warmed.planned}; 측정 요청을 시작하지 않습니다" }
                before = after
            }
            manifest["beforeStateSizes"] = performanceStateSizes(before.getValue(0))
        }
        if (options.diagnosticProfiles) {
            profileStartAttempted = true
            cluster.startJvmProfiles()
            Thread.sleep(1_000)
        }
        clockCalibration?.capture(HttpPerformanceClockPhase.BEFORE)
        cluster.beginMeasurement()
        measurementStarted = true
        val firstDue = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        val windowEnd = firstDue + windowNs
        fixedFirstDue = firstDue
        fixedWindowEnd = windowEnd
        val planned = plannedHttpPerformanceRequests(options, rate, firstDue)
        val recorder = HttpPerformanceMeasurements(runId, firstDue, windowEnd, planned)
        measurements = recorder
        val load =
            HttpPerformanceLoad(
                http,
                cluster.orderUri,
                planned,
                recorder,
                options.maxInFlight,
                clientStages,
                boundaryRunId = runId.takeIf { options.httpBoundaryDiagnostics },
                failures = httpFailures,
                enforceWindow = options.sustained,
            )
        active.set(load)
        val drainNs = TimeUnit.SECONDS.toNanos(options.drainSeconds)
        // Preserve the absolute original cutoff before any long-mode interruptible wait.
        if (options.sustained) cutoff = windowEnd + drainNs
        val drainEnd =
            try {
                load.sendAndDrain(windowEnd, drainNs)
            } finally {
                if (options.sustained) loopStoppedNs = System.nanoTime()
            }
        cutoff = drainEnd
        if (options.gatewayCpuPeriodUs !=
            null
        ) {
            attemptHttpPerformanceEvidence(errors, "측정 후 CPU 통계") { cluster.captureCpuEvidence("after") }
        }
        // 사후 상태는 빠진 지연 표본을 채우는 데 쓰지 않고 돈과 실제 송신 표식을 대조하는 데 쓴다.
        if (options.sustained) {
            attemptHttpPerformanceEvidence(errors, "HTTP 장기 응답 종료") { closeHttpClient() }
            val after = checkNotNull(collectFinalFileEvidence()) { "최종 금융 files 상태를 수집하지 못했습니다" }
            val expectedCallbacks = (stateCheck?.get("actualMeasuredSettled") as? Long)?.let { Math.multiplyExact(it, 3L) }
            if (expectedCallbacks != null) {
                attemptHttpPerformanceEvidence(errors, "최종 측정 표식 callback 배출") {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (recorder.observedRequestMemberCount().toLong() < expectedCallbacks &&
                        System.nanoTime() - deadline < 0
                    ) {
                        Thread.sleep(1)
                    }
                    check(recorder.observedRequestMemberCount().toLong() >= expectedCallbacks) { "금융 완료에 해당하는 측정 표식 callback 누락" }
                }
            }
            summary =
                load.finishWithSentMarkerVisitor(drainEnd) { visit ->
                    visitHttpPerformanceMeasuredSentMarkerFiles(
                        after,
                        HttpPerformanceMarkerPlan(runId, options.warmup, options.orders).identitiesPerMember,
                        visit,
                    )
                }
            active.set(null)
            if (!summary.completionLatencyValid) errors.addAll(summary.instrumentationErrors)
            manifest["afterStateSizes"] = readHttpPerformanceCheckpointFileObservation(after.getValue(0)).scan.rowCounts
            manifest["fixedWindow"] =
                mapOf("firstDueNs" to firstDue, "windowEndNs" to windowEnd, "drainEndNs" to drainEnd, "loopStoppedNs" to loopStoppedNs)
            manifest["cluster"] = cluster.manifest()
        } else {
            val after = checkNotNull(collectFinalEvidence()) { "최종 금융 상태를 수집하지 못했습니다" }
            if (options.diagnosticProfiles || options.stageDiagnostics) {
                check(
                    after.values.all {
                        val counters = it.path("runtimeCounters")
                        !counters.isMissingNode && !counters.isNull && counters.path("errors").size() == 0 &&
                            counters.path("overflowCount").asLong() == 0L && counters.path("definitionOverflowCount").asLong() == 0L
                    },
                ) { "runtime counter 관찰이 누락되거나 한도를 초과했습니다" }
            }
            val sent =
                after.values
                    .flatMap { it.path("markerSummary").path("sentMarkers").toList() }
                    .map { mapper.treeToValue(it, HttpPerformanceCompletion::class.java) }
                    .filter { it.userId == "BUYER" && it.requestId.startsWith("M-") }
            if (options.stageDiagnostics && options.detailedTracing) {
                val traceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (clientStages.count("MARKER") < options.orders * 3 && System.nanoTime() - traceDeadline < 0) Thread.sleep(1)
            }
            summary = load.finish(drainEnd, sent)
            active.set(null)
            if (!summary.completionLatencyValid) errors.addAll(summary.instrumentationErrors)
            if (options.stageDiagnostics) {
                val trace = clientStages.snapshot()
                check(trace["overflow"] == 0L && (trace["errors"] as List<*>).isEmpty()) { "부하 발생기 상세 기록 오류" }
                if (options.detailedTracing) {
                    val samples = trace["samples"] as List<*>
                    check(
                        samples.count { (it as HttpPerformanceClientStage).kind == "HTTP" } == options.orders,
                    ) { "HTTP callback 상세 기록 누락" }
                    check(
                        samples.count { (it as HttpPerformanceClientStage).kind == "MARKER" } == options.orders * 3,
                    ) { "세 노드 표식 상세 기록 누락" }
                }
                after.forEach { (member, node) ->
                    val marker = node.path("markerSummary")
                    check(marker.path("markerIdlePolicy").asString() == options.markerIdlePolicy.name) { "node$member 실제 관측 idle이 다릅니다" }
                    check(marker.path("detailedTracing").asBoolean() == options.detailedTracing) { "node$member 상세 계측 모드가 다릅니다" }
                    check(marker.path("senderLocalSpanOverflow").asLong() == 0L) { "node$member 표식 구간 기록 초과" }
                    if (options.detailedTracing) {
                        check(
                            marker.path("senderLocalSpans").count {
                                it.path("userId").asString() == "BUYER" &&
                                    it.path("requestId").asString().startsWith("M-")
                            } ==
                                options.orders,
                        ) {
                            "node$member 측정 표식의 송신 구간 누락"
                        }
                    }
                }
            }
            manifest["afterStateSizes"] = performanceStateSizes(after.getValue(0))
            manifest["fixedWindow"] = mapOf("firstDueNs" to firstDue, "windowEndNs" to windowEnd, "drainEndNs" to drainEnd)
            manifest["cluster"] = cluster.manifest()
            if (summary.businessRejected > 0) errors.add("고정된 정상 입력에서 예상 밖 업무 거절")
            if (rate <= 50 && (summary.accepted != options.orders || summary.settlementCount != options.orders.toLong())) {
                errors.add("낮은 부하 정상 기준선에서 예정 요청 전체의 승인·정산을 확인하지 못했습니다")
            }
        }
        clockCalibration?.capture(HttpPerformanceClockPhase.AFTER)
    } catch (failure: Throwable) {
        if (options.sustained && (failure is InterruptedException || Thread.currentThread().isInterrupted)) {
            restoreInterrupt = true
            Thread.interrupted()
        }
        if (options.httpWarmConnectionsDiagnostic && !measurementStarted) {
            @Suppress("UNCHECKED_CAST")
            val warmupManifest = manifest.getValue("httpWarmConnectionsDiagnostic") as MutableMap<String, Any?>
            warmupManifest["phase"] = "PREPARATION_FAILED"
            warmupManifest["preparationFailure"] = "${failure.javaClass.simpleName}: ${failure.message}".take(4096)
        }
        errors.add("${failure.javaClass.simpleName}: ${failure.message}")
        if (clusterStarted && !finalEvidenceAttempted) {
            if (options.sustained) {
                attemptHttpPerformanceEvidence(errors, "중단 HTTP 응답 종료") { closeHttpClient() }
                collectFinalFileEvidence()
            } else {
                collectFinalEvidence()
            }
        }
        active.getAndSet(null)?.let { load ->
            if (summary == null) {
                runCatching { load.finish(cutoff ?: System.nanoTime(), null) }
                    .onSuccess { summary = it }
                    .onFailure { errors.add("중단된 계측 집계: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    } finally {
        if (options.sustained && Thread.interrupted()) restoreInterrupt = true
        // 원시 표본 저장이 실패해도 시작한 클러스터의 종료는 반드시 시도한다.
        finishHttpPerformanceRun(
            errors = errors,
            closeHttp = ::closeHttpClient,
            preserveRawSamples = {
                clockCalibration?.let { calibration ->
                    val evidence = calibration.snapshot()
                    Files.writeString(reports.resolve("clock-calibration.json"), mapper.writeValueAsString(evidence))
                    manifest["clockCalibration"] =
                        mapOf(
                            "artifactFile" to "clock-calibration.json",
                            "status" to evidence["status"],
                            "commonOffsetInterval" to evidence["commonOffsetInterval"],
                            "outsideMeasuredTPS" to true,
                        )
                    @Suppress("UNCHECKED_CAST")
                    errors.addAll((evidence["errors"] as List<String>).map { "HTTP clock calibration: $it" })
                }
                connectionWarmup?.let { warmed ->
                    attemptHttpPerformanceEvidence(errors, "connection warmup raw 보존") {
                        Files.newOutputStream(reports.resolve("http-warm-connections.json")).use { output ->
                            mapper.writeValue(output, warmed)
                        }
                    }
                }
                if (options.stageDiagnostics) {
                    Files.writeString(
                        reports.resolve("client-stages.json"),
                        mapper.writeValueAsString(clientStages.snapshot()),
                    )
                }
                if (httpFailures != null) {
                    val failureEvidence = httpFailures.snapshot()
                    Files.writeString(reports.resolve("client-http-failures.json"), mapper.writeValueAsString(failureEvidence))
                    if (listOf("duplicateCount", "invalidIdentityCount", "captureFailureCount", "capturePendingCount").any {
                            (failureEvidence[it] as Long) !=
                                0L
                        }
                    ) {
                        errors.add("HTTP boundary diagnostic: HTTP_FAILURE_CAPTURE_ERROR")
                    }
                }
                if (options.sustained) {
                    val recorder = measurements
                    if (recorder == null || fixedFirstDue == null || fixedWindowEnd == null || cutoff == null) {
                        manifest["sustainedRaw"] =
                            mapOf(
                                "artifactFile" to HTTP_SUSTAINED_RAW_NAME,
                                "complete" to false,
                                "missingReason" to "MEASUREMENT_RECORDER_OR_WINDOW_UNAVAILABLE",
                            )
                        errors.add("HTTP 장기 raw 예정 표본/창 누락")
                    } else {
                        try {
                            rawArtifact =
                                HttpPerformanceSustainedRawFiles.publish(
                                    reports.resolve(HTTP_SUSTAINED_RAW_NAME).toAbsolutePath(),
                                    runId,
                                    options.orders,
                                    checkNotNull(fixedFirstDue),
                                    checkNotNull(fixedWindowEnd),
                                    checkNotNull(cutoff),
                                    loopStoppedNs,
                                    recorder::forEachSustainedRequestObservation,
                                )
                            manifest["sustainedRaw"] = rawArtifact?.fields()
                            if (rawArtifact?.complete != true) errors.add("HTTP 장기 raw 불완전: ${rawArtifact?.failure}")
                        } finally {
                            if (Thread.interrupted()) restoreInterrupt = true
                        }
                    }
                } else {
                    Files.newBufferedWriter(reports.resolve("requests.jsonl")).use { writer ->
                        measurements?.requests()?.forEach { writer.appendLine(mapper.writeValueAsString(it)) }
                    }
                }
                if (options.httpBoundaryDiagnostics && (!options.httpWarmConnectionsDiagnostic || measurementStarted)) {
                    val joined = joinHttpPerformanceHttpBoundaries(runId, measurements?.requests().orEmpty(), serverHttpBoundaries)
                    Files.writeString(reports.resolve("http-boundary-join.json"), mapper.writeValueAsString(joined))
                    @Suppress("UNCHECKED_CAST")
                    val boundaryErrors = joined["errors"] as List<String>
                    errors.addAll(boundaryErrors.map { "HTTP boundary diagnostic: $it" })
                    if (options.detailedTracing && measurementStarted) {
                        attemptHttpPerformanceEvidence(errors, "요청별 전체 구간 연결") {
                            errors.addAll(writeHttpPerformancePipelineReport(reports, runId, options.orders))
                        }
                    }
                }
            },
            closeCluster = cluster::close,
        )
        manifest["endedAt"] = Instant.now().toString()
        manifest["finalEvidenceAttempted"] = finalEvidenceAttempted
        if (options.httpWarmConnectionsDiagnostic) manifest["measurementStarted"] = measurementStarted
        manifest["cluster"] = cluster.manifest()
        if (verifyFingerprints) {
            checkHttpPerformancePostFingerprints(
                manifest,
                errors,
                source,
                distributionFingerprint,
                readSource = { performanceSourceFingerprint(project, options.optimizedProduct) },
                readDistribution = { performanceDistributionFingerprint(distribution) },
            )
        }
        if (options.sustained) {
            sustainedEvidence = httpPerformanceSustainedCohortEvidence(options.orders, summary, stateCheck, rawArtifact, errors)
            if (sustainedEvidence?.get("cohortComplete") != true) errors.add("HTTP 장기 예정 cohort 전량·금융·관찰·raw·cleanup 검증 미완료")
            manifest["sustainedEvidence"] = sustainedEvidence
        }
        try {
            Files.writeString(reports.resolve("manifest.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest))
        } catch (failure: Throwable) {
            if (restoreInterrupt) Thread.currentThread().interrupt()
            throw failure
        }
    }
    val result =
        HttpPerformanceRunResult(
            runId,
            rate,
            repetition,
            reports.toString(),
            summary,
            stateCheck,
            errors.distinct(),
            options.capacityProbe,
            windowNs,
            sustainedEvidence,
        )
    try {
        Files.writeString(reports.resolve("summary.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))
        return result
    } finally {
        if (restoreInterrupt) Thread.currentThread().interrupt()
    }
}

/** 리더 지연 집계는 이미 닫았다. 사후 큰 checkpoint가 따라오는 follower를 방해하지 않게 기다린다. */
private fun awaitDiagnosticFollowerCompletions(
    cluster: HttpPerformanceCluster,
    orders: Int,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    do {
        check(cluster.observerErrors().isEmpty()) { "진단 완료 표식 관측 오류" }
        val counts =
            cluster
                .markers()
                .filter { it.userId == "BUYER" && it.requestId.startsWith("M-") }
                .groupBy { it.memberId }
                .mapValues { (_, markers) -> markers.map { it.requestId }.toSet().size }
        if ((0..2).all { counts[it] == orders }) return
        Thread.sleep(50)
    } while (System.nanoTime() - deadline < 0)
    error("세 노드의 측정 주문 완료를 사후 checkpoint 전에 확인하지 못했습니다")
}

internal class HttpPerformanceLoad(
    private val http: HttpClient,
    private val uri: URI,
    private val planned: List<PlannedHttpRequest>,
    private val measurements: HttpPerformanceMeasurements,
    private val maxInFlight: Int,
    private val stages: HttpPerformanceClientStages,
    private val boundaryRunId: String? = null,
    private val failures: HttpPerformanceHttpFailures? = null,
    private val enforceWindow: Boolean = false,
    private val nanoTime: () -> Long = System::nanoTime,
    private val parkNanos: (Long) -> Unit = LockSupport::parkNanos,
    private val submit: (
        (
            HttpRequest,
        ) -> CompletableFuture<HttpResponse<String>>
    )? = null,
) {
    private val mapper = jacksonObjectMapper()
    private val lock = Any()
    private val pending = AtomicInteger()
    private val httpFinished = AtomicInteger()
    private val knownNoExecution = AtomicInteger()
    private val observed = ConcurrentHashMap.newKeySet<String>()
    private var finished = false

    fun observe(
        marker: HttpPerformanceCompletion,
        receivedNs: Long,
    ) {
        synchronized(lock) {
            val acquiredNs = if (stages.enabled) nanoTime() else 0L
            if (!finished && marker.userId == "BUYER" && marker.requestId.startsWith("M-")) {
                measurements.completionObserved(marker, receivedNs)
                if (stages.enabled) {
                    stages.record(
                        HttpPerformanceClientStage(
                            "MARKER",
                            marker.userId,
                            marker.requestId,
                            marker.memberId,
                            receivedNs,
                            acquiredNs,
                            null,
                        ),
                    )
                }
                if (marker.role == "LEADER") observed.add(marker.requestId)
            }
        }
    }

    fun sendAndDrain(
        windowEndNs: Long,
        drainNs: Long,
    ): Long {
        var nextPlan = 0
        try {
            for ((index, plan) in planned.withIndex()) {
                nextPlan = index
                if (enforceWindow) waitForHttpPerformanceDue(plan.dueNs, nanoTime, parkNanos) else waitUntil(plan.dueNs)
                if (enforceWindow && nanoTime() - windowEndNs >= 0) {
                    measurements.requestNotSent(plan.userId, plan.requestId, "WINDOW_ENDED", nanoTime())
                    httpFinished.incrementAndGet()
                    knownNoExecution.incrementAndGet()
                    nextPlan = index + 1
                    continue
                }
                if (pending.incrementAndGet() > maxInFlight) {
                    pending.decrementAndGet()
                    measurements.requestNotSent(plan.userId, plan.requestId, "GENERATOR_SATURATED", nanoTime())
                    httpFinished.incrementAndGet()
                    knownNoExecution.incrementAndGet()
                    nextPlan = index + 1
                    continue
                }
                var requestRecorded = false
                var submittedAt: Long? = null
                if (!enforceWindow) {
                    measurements.requestStarted(plan.userId, plan.requestId, nanoTime())
                    requestRecorded = true
                    nextPlan = index + 1
                }
                try {
                    val bodyTrace = if (stages.enabled) HttpPerformanceBodyTrace(nanoTime) else null
                    val responseTrace = if (stages.enabled) HttpPerformanceResponseTrace(nanoTime) else null
                    val request =
                        performanceHttpRequest(
                            uri,
                            mapper.writeValueAsString(performanceOrder(plan.userId, "BUY", plan.requestId)),
                            boundaryRunId?.let { HttpPerformanceHttpIdentity(it, plan.requestId) },
                            bodyTrace,
                        )
                    if (enforceWindow) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("HTTP sustained submit interrupted")
                        val startedAt = nanoTime()
                        if (startedAt - windowEndNs >= 0) {
                            pending.decrementAndGet()
                            measurements.requestNotSent(plan.userId, plan.requestId, "WINDOW_ENDED", startedAt)
                            httpFinished.incrementAndGet()
                            knownNoExecution.incrementAndGet()
                            nextPlan = index + 1
                            continue
                        }
                        // Capture immediately before the API call; do not hold the observation lock before submitting.
                        submittedAt = startedAt
                    }
                    val future =
                        submit?.invoke(request)
                            ?: http.sendAsync(request, responseTrace?.handler() ?: HttpResponse.BodyHandlers.ofString())
                    if (enforceWindow) {
                        measurements.requestStarted(plan.userId, plan.requestId, checkNotNull(submittedAt))
                        requestRecorded = true
                        nextPlan = index + 1
                    }
                    future.whenComplete { response, error ->
                        val enteredNs = if (stages.enabled) nanoTime() else 0L
                        var captureFailure = false
                        synchronized(lock) {
                            val acquiredNs = if (stages.enabled) nanoTime() else 0L
                            try {
                                if (!finished) {
                                    val parsedNs = recordHttp(plan, response, error)
                                    captureFailure = error != null
                                    if (stages.enabled) {
                                        stages.record(
                                            HttpPerformanceClientStage(
                                                "HTTP",
                                                plan.userId,
                                                plan.requestId,
                                                null,
                                                enteredNs,
                                                acquiredNs,
                                                parsedNs,
                                                bodyTrace?.startedNs,
                                                bodyTrace?.completedNs,
                                                bodyTrace?.subscriptions,
                                                responseTrace?.headersEnteredNs,
                                                responseTrace?.firstNextNs,
                                                responseTrace?.lastNextNs,
                                                responseTrace?.onCompleteNs,
                                                responseTrace?.bodyCompletedNs,
                                                responseTrace?.getBodyEnteredNs,
                                            ),
                                        )
                                    }
                                }
                            } finally {
                                pending.decrementAndGet()
                                httpFinished.incrementAndGet()
                            }
                        }
                        if (captureFailure) failures?.record(plan.requestId, checkNotNull(error))
                    }
                } catch (error: Exception) {
                    if (enforceWindow && !requestRecorded && submittedAt != null) {
                        // A synchronous submit failure is conservatively an attempted HTTP request with unknown execution.
                        measurements.requestStarted(plan.userId, plan.requestId, checkNotNull(submittedAt))
                        requestRecorded = true
                        nextPlan = index + 1
                    }
                    if (enforceWindow && !requestRecorded) {
                        pending.decrementAndGet()
                        val interrupted = error is InterruptedException || Thread.currentThread().isInterrupted
                        measurements.requestAbandoned(
                            plan.userId,
                            plan.requestId,
                            if (interrupted) "INTERRUPTED" else "LOAD_ABORTED",
                            nanoTime(),
                        )
                        httpFinished.incrementAndGet()
                        knownNoExecution.incrementAndGet()
                        nextPlan = index + 1
                        if (interrupted) Thread.currentThread().interrupt()
                        throw error
                    }
                    val enteredNs = if (stages.enabled) nanoTime() else 0L
                    synchronized(lock) {
                        val acquiredNs = if (stages.enabled) nanoTime() else 0L
                        recordHttp(plan, null, error)
                        if (stages.enabled) {
                            stages.record(
                                HttpPerformanceClientStage("HTTP", plan.userId, plan.requestId, null, enteredNs, acquiredNs, null),
                            )
                        }
                    }
                    pending.decrementAndGet()
                    httpFinished.incrementAndGet()
                    failures?.record(plan.requestId, error)
                    if (enforceWindow && error is InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw error
                    }
                }
            }
        } catch (failure: Throwable) {
            if (enforceWindow) {
                val interrupted = failure is InterruptedException || Thread.currentThread().isInterrupted
                val now = nanoTime()
                for (index in nextPlan until planned.size) {
                    val plan = planned[index]
                    measurements.requestAbandoned(plan.userId, plan.requestId, if (interrupted) "INTERRUPTED" else "LOAD_ABORTED", now)
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
            throw failure
        }
        val deadline = windowEndNs + drainNs
        while (true) {
            if (enforceWindow && Thread.currentThread().isInterrupted) throw InterruptedException("HTTP sustained drain interrupted")
            val now = nanoTime()
            if (now - deadline >= 0) return deadline
            val completionGoal = if (enforceWindow) Math.multiplyExact(planned.size, 3) else planned.size
            val observedForDrain = if (enforceWindow) measurements.observedRequestMemberCount() else observed.size
            val knownNoExecutionForDrain = if (enforceWindow) Math.multiplyExact(knownNoExecution.get(), 3) else knownNoExecution.get()
            if (now - windowEndNs >= 0 && httpFinished.get() == planned.size &&
                observedForDrain + knownNoExecutionForDrain >= completionGoal
            ) {
                // 완료 여부를 확인하는 사이 마지막 표식이 도착할 수 있으므로 판정 후 시각으로 닫는다.
                val stoppedAt = nanoTime()
                return if (stoppedAt - deadline >= 0) deadline else stoppedAt
            }
            parkNanos(1_000_000)
        }
    }

    fun finish(
        cutoff: Long,
        sent: List<HttpPerformanceCompletion>?,
    ): HttpPerformanceSummary =
        synchronized(lock) {
            finished = true
            measurements.finish(cutoff, sent)
        }

    fun finishWithSentMarkerVisitor(
        cutoff: Long,
        visitSent: ((HttpPerformanceCompletion) -> Unit) -> Unit,
    ): HttpPerformanceSummary =
        synchronized(lock) {
            finished = true
            measurements.finishWithSentMarkerVisitor(cutoff, visitSent)
        }

    private fun recordHttp(
        plan: PlannedHttpRequest,
        response: HttpResponse<String>?,
        error: Throwable?,
    ): Long? {
        if (error != null) {
            failures?.expect(plan.requestId)
            val cause = generateSequence(error) { it.cause }.last()
            measurements.httpCompleted(
                plan.userId,
                plan.requestId,
                nanoTime(),
                if (cause is HttpTimeoutException) HttpPerformanceOutcome.HTTP_TIMEOUT else HttpPerformanceOutcome.HTTP_UNKNOWN,
                detail = cause.javaClass.simpleName,
                responseReceived = false,
            )
            return null
        }
        checkNotNull(response)
        try {
            val body = mapper.readTree(response.body())
            check(
                body.path("userId").asString() == plan.userId && body.path("requestId").asString() == plan.requestId &&
                    body.path("orderId").asString() == plan.orderId && body.path("marketId").asString() == "BTC-USDT",
            ) { "HTTP 원본 식별자 불일치" }
            val status = body.path("status").asString()
            val outcome =
                when {
                    response.statusCode() == 202 && status == "ACCEPTED" -> HttpPerformanceOutcome.ACCEPTED
                    response.statusCode() in listOf(409, 422) && status == "REJECTED" -> HttpPerformanceOutcome.BUSINESS_REJECTED
                    status == "OUTCOME_UNKNOWN" -> HttpPerformanceOutcome.HTTP_UNKNOWN
                    else -> HttpPerformanceOutcome.HTTP_ERROR
                }
            if (status == "NOT_SUBMITTED" || outcome == HttpPerformanceOutcome.BUSINESS_REJECTED) knownNoExecution.incrementAndGet()
            val parsedNs = if (stages.enabled) nanoTime() else null
            measurements.httpCompleted(
                plan.userId,
                plan.requestId,
                nanoTime(),
                outcome,
                if (outcome == HttpPerformanceOutcome.ACCEPTED) body.path("sequence").asLong() else null,
                "${response.statusCode()}:$status:${body.path("reason").asString()}",
            )
            return parsedNs
        } catch (failure: Exception) {
            measurements.recordInstrumentationError("HTTP 응답 해석 실패: ${plan.requestId}:$failure")
            measurements.httpCompleted(
                plan.userId,
                plan.requestId,
                nanoTime(),
                HttpPerformanceOutcome.HTTP_ERROR,
                detail = response.statusCode().toString(),
            )
            return null
        }
    }
}

/** Same-JVM subtraction is wrap-safe; cancellation must never become a late catch-up submission. */
internal fun waitForHttpPerformanceDue(
    dueNs: Long,
    nanoTime: () -> Long,
    parkNanos: (Long) -> Unit,
) {
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("HTTP sustained due wait interrupted")
        val remaining = dueNs - nanoTime()
        if (remaining <= 0) return
        parkNanos(minOf(remaining, 1_000_000L))
    }
}

private fun waitUntil(dueNs: Long) {
    while (true) {
        val remaining = dueNs - System.nanoTime()
        if (remaining <= 0) return
        LockSupport.parkNanos(minOf(remaining, 1_000_000))
    }
}

internal fun performanceOrder(
    user: String,
    side: String,
    id: String,
): Map<String, Any> =
    linkedMapOf(
        "requestId" to id,
        "orderId" to id,
        "userId" to user,
        "side" to side,
        "orderType" to "LIMIT",
        "timeInForce" to "GTC",
        "price" to 10_000L,
        "quantity" to 100_000_000L,
    )

internal fun performanceHttpRequest(
    uri: URI,
    body: String,
    diagnosticIdentity: HttpPerformanceHttpIdentity? = null,
    bodyTrace: HttpPerformanceBodyTrace? = null,
): HttpRequest =
    HttpRequest
        .newBuilder(uri)
        .timeout(Duration.ofSeconds(6))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .apply {
            if (diagnosticIdentity != null) {
                header(HTTP_PERFORMANCE_RUN_HEADER, diagnosticIdentity.runId)
                header(HTTP_PERFORMANCE_REQUEST_HEADER, diagnosticIdentity.requestId)
            }
        }.POST(HttpRequest.BodyPublishers.ofString(body).let { bodyTrace?.wrap(it) ?: it })
        .build()

internal fun prepareHttpPerformanceOrders(
    http: HttpClient,
    uri: URI,
    user: String,
    side: String,
    prefix: String,
    count: Int,
    batchSize: Int = 32,
) {
    require(batchSize in 1..256)
    val mapper = jacksonObjectMapper()
    (0 until count).chunked(batchSize).forEach { batch ->
        val responses =
            batch.map { index ->
                http.sendAsync(
                    performanceHttpRequest(uri, mapper.writeValueAsString(performanceOrder(user, side, "$prefix$index"))),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
        CompletableFuture.allOf(*responses.toTypedArray()).get(30, TimeUnit.SECONDS)
        responses.forEach { response -> check(response.join().statusCode() == 202) { "측정 밖 준비 주문 실패: ${response.join().body()}" } }
    }
}

internal fun awaitPerformanceCheckpoint(
    cluster: HttpPerformanceCluster,
    label: String,
    count: Long,
): Map<Int, JsonNode> {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    var index = 0
    do {
        val checkpoint = cluster.checkpoint("$label-${index++}")
        if (checkpoint.values.all {
                it
                    .path("trading")
                    .path("completions")
                    .size()
                    .toLong() == count
            }
        ) {
            return checkpoint
        }
        Thread.sleep(50)
    } while (System.nanoTime() - deadline < 0)
    error("$label 실행 완료 $count 확인 기한 초과")
}

private fun performanceStateSizes(checkpoint: JsonNode): Map<String, Int> =
    mapOf(
        "requests" to
            checkpoint
                .path("applied")
                .path("state")
                .path("requests")
                .size(),
        "transactions" to
            checkpoint
                .path("applied")
                .path("state")
                .path("transactions")
                .size(),
        "completions" to checkpoint.path("trading").path("completions").size(),
        "reservations" to checkpoint.path("trading").path("currentReservations").size(),
    )

/** 사후 지문 조회만 보호한다. 조회 오류는 실행을 무효로 하되 이미 수집한 증거의 저장은 계속한다. */
internal fun checkHttpPerformancePostFingerprints(
    manifest: MutableMap<String, Any?>,
    errors: MutableList<String>,
    sourceBefore: Map<String, Any>,
    distributionBefore: String?,
    readSource: () -> Map<String, Any>,
    readDistribution: () -> String,
) {
    try {
        val sourceAfter = readSource()
        manifest["sourceUnchanged"] = sourceBefore == sourceAfter
        if (sourceBefore != sourceAfter) errors.add("실행 중 소스 지문 변경")
    } catch (failure: Exception) {
        manifest["sourceUnchanged"] = null
        val reason = "사후 소스 지문 조회 실패: ${failure.javaClass.simpleName}: ${failure.message}"
        manifest["sourceVerificationError"] = reason
        errors.add(reason)
    }
    try {
        val distributionAfter = readDistribution()
        manifest["distributionUnchanged"] = distributionBefore == distributionAfter
        if (distributionBefore != distributionAfter) errors.add("실행 중 배포 산출물 변경")
    } catch (failure: Exception) {
        manifest["distributionUnchanged"] = null
        val reason = "사후 배포 산출물 지문 조회 실패: ${failure.javaClass.simpleName}: ${failure.message}"
        manifest["distributionVerificationError"] = reason
        errors.add(reason)
    }
}

internal fun performanceSourceFingerprint(
    project: Path,
    allowProductChanges: Boolean,
): Map<String, Any> {
    fun git(vararg args: String): String =
        ProcessBuilder(listOf("git") + args).directory(project.parent.toFile()).start().let { p ->
            val text = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0)
            text.trim()
        }
    val repository = project.parent
    val changed =
        (git("diff", "--name-only", "HEAD").lines() + git("ls-files", "--others", "--exclude-standard").lines())
            .filter {
                it.isNotBlank()
            }.distinct()
            .sorted()
    check(allowProductChanges || changed.none { "/src/main/" in it }) { "제품 소스 변경 없이 기준선을 측정해야 합니다" }
    val digests =
        changed.associateWith { file ->
            val path = repository.resolve(file)
            if (Files.exists(path)) {
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
                    "%02x".format(it)
                }
            } else {
                "DELETED"
            }
        }
    return mapOf(
        "commit" to git("rev-parse", "HEAD"),
        "branch" to git("branch", "--show-current"),
        "productChangesAllowed" to allowProductChanges,
        "changedFileSha256" to digests,
    )
}

/** 실제 Docker 이미지에 넣는 class/resource/library의 지문이다. 측정 구간 밖에서만 읽는다. */
internal fun performanceDistributionFingerprint(directory: Path): String {
    val tree = MessageDigest.getInstance("SHA-256")
    Files.walk(directory).use { files ->
        files.filter(Files::isRegularFile).sorted().forEach { file ->
            tree.update(directory.relativize(file).toString().toByteArray(Charsets.UTF_8))
            tree.update(0.toByte())
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            tree.update(digest.digest())
        }
    }
    return java.util.HexFormat
        .of()
        .formatHex(tree.digest())
}

fun main(args: Array<String>) {
    val flag = args.singleOrNull()
    if (flag in
        setOf(
            "--stage-diagnostic-smoke",
            "--stage-diagnostic",
            "--stage-probe",
            "--stage-profile",
            "--cpu-period-probe",
            "--cpu-period-comparison",
            "--cpu-budget-probe",
            "--cpu-budget-comparison",
        )
    ) {
        runStageDiagnosticMatrix(flag!!)
        return
    }
    val options = parseHttpPerformanceOptions(args)
    val optimized = options.optimizedProduct
    val diagnostic = options.diagnosticProfiles
    val project = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
    val mode =
        if (options.sustained) {
            "sustained"
        } else if (options.capacityProbe) {
            "capacity-probe"
        } else if (optimized) {
            flag!!.removePrefix("--")
        } else if (diagnostic) {
            "diagnostic"
        } else {
            "baseline"
        }
    val reports = project.resolve("build/reports/http-performance/$mode-${System.currentTimeMillis()}")
    Files.createDirectories(reports)
    val results = mutableListOf<HttpPerformanceRunResult>()
    matrix@ for (rate in options.rates) {
        for (repetition in 1..options.repetitions) {
            val burstLabel =
                if (options.capacityProbe) {
                    " kind=BOUNDED_HTTP_BURST plannedWindowNs=${httpPerformanceWindowNs(
                        options,
                        rate,
                    )}"
                } else {
                    ""
                }
            println("PERFORMANCE run rate=$rate repetition=$repetition warmup=${options.warmup} measured=${options.orders}$burstLabel")
            val result = runHttpPerformanceBaseline(options, rate, repetition, reports)
            results.add(result)
            println(
                "PERFORMANCE result run=${result.runId} accepted=${result.summary?.accepted} settled=${result.summary?.settlementCount} invalid=${result.invalidReasons}",
            )
            Files.writeString(
                reports.resolve("matrix.json"),
                jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results),
            )
            // 기동·준비 실패는 수치가 없는 무효 실행이다. 같은 준비 실패를 아홉 번 반복하지 않는다.
            if (result.summary == null) break@matrix
        }
    }
    Files.writeString(reports.resolve("report.md"), httpPerformanceReport(results, diagnostic, optimized))
    println("PERFORMANCE reports=$reports")
    check(
        results.all {
            it.invalidReasons.isEmpty() && it.summary?.completionLatencyValid == true &&
                (!options.sustained || it.sustainedEvidence?.get("cohortComplete") == true)
        },
    ) { "무효 실행이 있습니다. 표본과 무효 사유를 확인하세요: $reports" }
}

/** 비교 순서를 고정해 같은 바이너리에서 관측 경로와 상세 기록의 비용만 바꾼다. */
private fun runStageDiagnosticMatrix(flag: String) {
    val project = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
    val reports = project.resolve("build/reports/http-performance/stages-${System.currentTimeMillis()}")
    Files.createDirectories(reports)
    val smoke = flag == "--stage-diagnostic-smoke"
    val probe = flag == "--stage-probe"
    val profile = flag == "--stage-profile"
    val cpuProbe = flag == "--cpu-period-probe"
    val cpuComparison = flag == "--cpu-period-comparison"
    val cpuBudgetProbe = flag == "--cpu-budget-probe"
    val cpuBudgetComparison = flag == "--cpu-budget-comparison"
    val base =
        HttpPerformanceOptions(
            warmup = if (smoke) 1 else 200,
            orders = if (smoke) 2 else 2000,
            optimizedProduct = true,
            stageDiagnostics = true,
            diagnosticProfiles = profile,
        )
    val cases =
        buildList {
            if (cpuBudgetProbe || cpuBudgetComparison) {
                for (repetition in 1..if (cpuBudgetProbe) 1 else 3) {
                    val budgets = if (repetition % 2 == 1) listOf(2, 8) else listOf(8, 2)
                    budgets.forEach { budget ->
                        add(Triple(1000, repetition, base.copy(gatewayCpuPeriodUs = 100_000, gatewayCpuCount = budget)))
                    }
                }
            } else if (cpuProbe || cpuComparison) {
                for (repetition in 1..if (cpuProbe) 1 else 3) {
                    val periods = if (repetition % 2 == 1) listOf(100_000, 10_000) else listOf(10_000, 100_000)
                    periods.forEach { period -> add(Triple(1000, repetition, base.copy(gatewayCpuPeriodUs = period))) }
                }
            } else if (smoke || probe || profile) {
                add(Triple(if (smoke) 10 else 1000, 1, base.copy(markerIdlePolicy = HttpPerformanceMarkerIdlePolicy.SLEEP_1MS)))
                if (!profile) add(Triple(if (smoke) 10 else 1000, 1, base.copy(markerIdlePolicy = HttpPerformanceMarkerIdlePolicy.BACKOFF)))
            } else {
                for (repetition in 1..3) {
                    val policies =
                        if (repetition % 2 ==
                            1
                        ) {
                            HttpPerformanceMarkerIdlePolicy.entries
                        } else {
                            HttpPerformanceMarkerIdlePolicy.entries.reversed()
                        }
                    policies.forEach { policy -> add(Triple(1000, repetition, base.copy(markerIdlePolicy = policy))) }
                }
                HttpPerformanceMarkerIdlePolicy.entries.forEach { policy -> add(Triple(50, 1, base.copy(markerIdlePolicy = policy))) }
                add(Triple(1000, 4, base.copy(markerIdlePolicy = HttpPerformanceMarkerIdlePolicy.BACKOFF, detailedTracing = false)))
                add(Triple(1000, 5, base.copy(markerIdlePolicy = HttpPerformanceMarkerIdlePolicy.BACKOFF, detailedTracing = true)))
            }
        }
    val results = mutableListOf<HttpPerformanceRunResult>()
    val runOptions = linkedMapOf<String, HttpPerformanceOptions>()
    for ((rate, repetition, options) in cases) {
        println(
            "STAGE_PERFORMANCE rate=$rate repetition=$repetition markerIdle=${options.markerIdlePolicy} details=${options.detailedTracing} gatewayCpuPeriodUs=${options.gatewayCpuPeriodUs} gatewayCpuCount=${options.gatewayCpuCount}",
        )
        val result = runHttpPerformanceBaseline(options.copy(rates = listOf(rate), repetitions = 1), rate, repetition, reports)
        results.add(result)
        runOptions[result.runId] = options
        Files.writeString(
            reports.resolve("matrix.json"),
            jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results),
        )
        println(
            "STAGE_PERFORMANCE run=${result.runId} accepted=${result.summary?.accepted} completed=${result.summary?.completed} invalid=${result.invalidReasons}",
        )
        if (result.summary == null) break
    }
    Files.writeString(reports.resolve("report.md"), httpStagePerformanceReport(results, profile, runOptions))
    println("STAGE_PERFORMANCE reports=$reports")
    check(results.size == cases.size && results.all { it.invalidReasons.isEmpty() && it.summary?.completionLatencyValid == true }) {
        "구간 진단 실행이 무효입니다: $reports"
    }
}

internal fun httpStagePerformanceReport(
    results: List<HttpPerformanceRunResult>,
    profile: Boolean,
    runOptions: Map<String, HttpPerformanceOptions> = emptyMap(),
): String = httpPerformanceReport(results, diagnostic = profile, optimized = true, stageOptions = runOptions)

internal fun httpPerformanceReport(
    results: List<HttpPerformanceRunResult>,
    diagnostic: Boolean = false,
    optimized: Boolean = false,
    stageOptions: Map<String, HttpPerformanceOptions>? = null,
): String =
    buildString {
        require(stageOptions == null || results.all { it.runId in stageOptions }) { "실행 조건 없이 구간 진단을 보고하지 않습니다" }
        val sustained = results.any { it.sustainedEvidence != null }
        require(!sustained || results.all { it.sustainedEvidence != null }) { "장기 HTTP 실행을 다른 모드와 합치지 않습니다" }
        val capacityProbe = results.any { it.capacityProbe }
        require(!capacityProbe || results.all { it.capacityProbe }) { "짧은 burst를 다른 측정 모드와 합쳐 보고하지 않습니다" }
        val cpuExperiment = stageOptions?.values?.any { it.gatewayCpuPeriodUs != null } == true
        appendLine(
            if (sustained) {
                "# HTTP → Aeron 고정 60초 cohort"
            } else if (capacityProbe) {
                "# HTTP → Aeron 짧은 부하 burst 탐색"
            } else if (diagnostic) {
                "# HTTP → Aeron 원인 진단 (JFR 포함)"
            } else if (cpuExperiment) {
                "# HTTP → Aeron CPU 자원 변경 진단"
            } else if (stageOptions != null) {
                "# HTTP → Aeron 구간·관측 경로 비교"
            } else if (optimized) {
                "# HTTP → Aeron 개선 코드 측정"
            } else {
                "# HTTP → Aeron 성능 기준선"
            },
        )
        if (sustained) {
            appendLine(
                "1k/s·60초 예정 전송. leader 완료 지연과 세 노드 전체 수신을 구분한다. " +
                    "cohort는 세 노드가 원래 cutoff 안, 창 목표는 세 노드가 원래 60초 안에 모두 관측되어야 한다. " +
                    "최대 TPS나 heap 적합성을 입증하지 않는다.",
            )
            results.forEach {
                appendLine(
                    "${it.runId}: cohortComplete=${it.sustainedEvidence?.get(
                        "cohortComplete",
                    )}, fixedWindowTargetMet=${it.sustainedEvidence?.get("fixedWindowTargetMet")}",
                )
                appendLine(
                    "창 안 ACK=${it.sustainedEvidence?.get("acceptedAcksInsideFixedWindow")}, " +
                        "세 노드 전체 완료=${it.sustainedEvidence?.get("threeCopyCompletionsInsideFixedWindow")}, " +
                        "세 노드 전체 완료/60s=${it.sustainedEvidence?.get("threeCopyCompletionsPerFixedWindowSecond")}",
                )
            }
        }
        if (stageOptions != null) {
            appendLine("운영 Gateway 계측과 테스트 상세 기록을 구분한다. 실행 조건과 소스 지문은 각 manifest에 보존한다.")
        } else if (optimized && !capacityProbe) {
            appendLine("개선한 제품 코드의 지문을 manifest에 남겼다. 변경 전 기준선은 별도로 보존한다.")
        }
        if (capacityProbe) {
            appendLine("각 회차는 2,000건의 짧은 burst다. 예정 창은 N/rate이며 60초 drain은 측정 지속시간이 아니다. 지속 처리량이나 최대 TPS를 입증하지 않는다.")
            appendLine(
                results.joinToString(" · ") { result ->
                    val seconds = result.plannedWindowNs?.let { "%.3f".format(java.util.Locale.ROOT, it / 1_000_000_000.0) } ?: "산출 불가"
                    "${result.offeredRate} BUY/s: 예정 창 ${seconds}s"
                },
            )
            appendLine("상세 추적과 JFR을 끄고 기존 CPU·heap·동기화·완료 표식·세 노드 금융 검사를 유지했다. 실행 전후 소스와 배포 산출물 지문을 대조한다.")
        }
        if (cpuExperiment) {
            appendLine("CPU 예산·제한 주기를 바꾼 진단이다. 같은 자원의 코드 개선 수치가 아니며 기본 실행 설정을 변경하지 않는다. 실제 cpu.max·cpu.stat를 별도 파일로 검사한다.")
        }
        if (diagnostic) appendLine("JFR·callback 계측 비용을 포함한 원인 조사다. 프로파일 없는 성능 기준선을 대체하지 않는다.")
        appendLine()
        appendLine("로컬 Docker 세 노드·동기화 2. 완료 지연은 실제 실행 뒤 테스트 표식 전달 비용을 포함한다. 성공 표본 분위수와 고정 예정 요청의 미관측률을 함께 읽는다.")
        appendLine()
        val conditionHeading = if (stageOptions == null) "" else " 실행 조건 |"
        val conditionSeparator = if (stageOptions == null) "" else "---|"
        val completionHeading = if (sustained) "리더 완료" else "완료"
        val settlementHeading = if (sustained) "리더 창 안 정산 관측/s" else "창 안 정산 관측/s"
        val settledCohortHeading = if (sustained) "리더 정산/예정" else "정산/예정"
        appendLine(
            "| 예정 BUY/s | 반복 |$conditionHeading 승인/예정 | $settledCohortHeading | 접수 p50/p95/p99 ms | $completionHeading p50/p95/p99 ms | $settlementHeading | $completionHeading 미관측률 | 판정 |",
        )
        appendLine("|---:|---:|$conditionSeparator---:|---:|---|---|---:|---:|---|")

        fun millis(value: Long?) = value?.let { "%.3f".format(java.util.Locale.ROOT, it / 1_000_000.0) } ?: "산출 불가"

        fun latency(value: HttpPerformanceLatencySummary?) =
            if (value ==
                null
            ) {
                "산출 불가"
            } else {
                "${millis(value.p50Ns)}/${millis(value.p95Ns)}/${millis(value.p99Ns)}" +
                    if (value.p99Censored) " (전체 p99 미확정)" else ""
            }
        for (result in results) {
            val s = result.summary
            val validity = if (result.invalidReasons.isEmpty()) "유효" else result.invalidReasons.joinToString("; ")
            val condition =
                stageOptions?.getValue(result.runId)?.let {
                    " 표식 ${it.markerIdlePolicy} · 상세 ${it.detailedTracing} · Gateway ${it.gatewayCpuCount} CPU / ${it.gatewayCpuPeriodUs ?: 100_000}µs |"
                } ?: ""
            appendLine(
                "| ${result.offeredRate} | ${result.repetition} |$condition ${s?.accepted}/${s?.planned} | " +
                    "${s?.settlementCount}/${s?.planned} | ${latency(s?.ackRtt)} | ${latency(s?.settlementLatency)} | " +
                    "${s?.settlementsObservedPerSecond} | ${s?.completionUnknownRate} | $validity |",
            )
        }
        appendLine()
        appendLine(
            "완료 미관측률은 미발송·업무 거절을 포함한 고정 예정 요청 기준이다. " +
                if (diagnostic) {
                    "JFR과 노드 내부 callback 차분을 별도로 분석한다. samples·대기·GC·디스크 이벤트를 동일한 CPU 비율로 합하지 않는다. " +
                        "각 실행의 manifest·requests.jsonl·summary.json·세 노드 checkpoint·JFR를 보존한다."
                } else if (stageOptions != null) {
                    "Gateway의 직접 구간과 노드·표식·부하 JVM의 로컬 구간을 각각 분석한다. 다른 구간의 분위수를 합산하지 않는다. " +
                        "프로파일 없는 이 실행으로 CPU·GC·디스크의 단일 원인을 확정하지 않는다. manifest·원시 표본·세 노드 checkpoint를 보존한다."
                } else if (sustained) {
                    "표의 완료 지연·정산/s는 리더 관측이다. 세 노드 전체 수신의 창 안/밖 및 cutoff 판정은 " +
                        "sustainedEvidence와 http-client-samples.jsonl schema2에 별도 보존한다. " +
                        "각 실행의 manifest·http-client-samples.jsonl·summary.json·세 노드 checkpoint를 보존한다."
                } else {
                    "CPU/GC/디스크의 원인별 계측은 하지 않아 특정 병목을 확정하지 않는다. " +
                        "각 실행의 manifest·requests.jsonl·summary.json·세 노드 checkpoint를 보존한다."
                },
        )
    }

/** 원시 표본 저장 실패가 이미 시작한 클러스터의 종료를 건너뛰지 않도록 수명 경계를 고정한다. */
internal fun finishHttpPerformanceRun(
    errors: MutableList<String>,
    closeHttp: () -> Unit,
    preserveRawSamples: () -> Unit,
    closeCluster: () -> Unit,
) {
    try {
        runCatching(closeHttp).onFailure { failure ->
            errors.add("HTTP 종료: ${failure.javaClass.simpleName}: ${failure.message}")
        }
        runCatching(preserveRawSamples).onFailure { failure ->
            errors.add("원시 표본 저장: ${failure.javaClass.simpleName}: ${failure.message}")
        }
    } finally {
        runCatching(closeCluster).onFailure { failure ->
            errors.add("클러스터 정리: ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }
}
