package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.trading.TradingHistoryRetention
import com.exchange.core.trading.TradingState
import io.aeron.Image
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.agrona.DirectBuffer
import org.agrona.concurrent.NanoClock
import org.agrona.concurrent.SystemNanoClock
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

internal const val HTTP_PERFORMANCE_CALLBACK_CAPACITY = 8192

/** Reads existing meters only; no registration, business-state access or per-request tracing. */
internal fun httpPerformanceOperationalMetrics(registry: MeterRegistry): Map<String, Any?> {
    val timers = mutableListOf<Map<String, Any?>>()
    val counters = mutableListOf<Map<String, Any?>>()
    for (meter in registry.meters) {
        val identity = mapOf("name" to meter.id.name, "tags" to meter.id.tags.associate { it.key to it.value })
        when (meter) {
            is Timer -> {
                timers.add(
                    identity +
                        mapOf(
                            "count" to meter.count(),
                            "totalNs" to meter.totalTime(TimeUnit.NANOSECONDS),
                            "maxNs" to meter.max(TimeUnit.NANOSECONDS),
                        ),
                )
            }

            is Counter -> {
                counters.add(identity + ("count" to meter.count()))
            }
        }
    }
    val degraded = registry.find("${TradingNodeMetrics.PREFIX}.degraded").gauge()?.value()
    val observationFailures = registry.find("${TradingNodeMetrics.PREFIX}.observation_failures").counter()?.count()
    return mapOf(
        "timers" to timers,
        "counters" to counters,
        "telemetryOperational" to degraded?.let { it == 0.0 },
        "observationFailures" to observationFailures,
    )
}

/**
 * Coordinator writes control/node-metrics.request as before or after, then waits for all three
 * node{member}-metrics-{label}.json files. A label is captured once, on this control thread.
 * before must be acknowledged before GO; after is requested only after the cohort drains.
 */
internal class HttpPerformanceNodeMetricsControl(
    private val directory: Path,
    private val runId: String,
    private val memberId: Int,
    private val registry: () -> MeterRegistry,
) {
    constructor(directory: Path, runId: String, memberId: Int, registry: MeterRegistry) :
        this(directory, runId, memberId, { registry })

    private val captured = mutableSetOf<String>()
    var latest: Map<String, Any?>? = null
        private set

    fun poll(): Map<String, Any?>? {
        val request = directory.resolve("node-metrics.request")
        if (!Files.isRegularFile(request)) return null
        val label = Files.readString(request).trim()
        require(label == "before" || label == "after") { "metrics label must be before or after" }
        if (label in captured) return null
        val identity = mapOf("kind" to "NODE_METRICS", "runId" to runId, "memberId" to memberId, "label" to label)
        val snapshot =
            try {
                val memory = ManagementFactory.getMemoryMXBean().heapMemoryUsage
                val metrics = httpPerformanceOperationalMetrics(registry())
                val observationFailures = (metrics["observationFailures"] as? Number)?.toDouble()
                val invalidReason =
                    when {
                        metrics["telemetryOperational"] != true -> "operational telemetry is missing or degraded"
                        observationFailures == null -> "observation failure counter is missing"
                        !observationFailures.isFinite() -> "observation failure counter is not finite"
                        observationFailures != 0.0 -> "observation failure counter is nonzero: $observationFailures"
                        else -> null
                    }
                identity + metrics +
                    mapOf(
                        "valid" to (invalidReason == null),
                        "failure" to invalidReason,
                        "gc" to
                            ManagementFactory.getGarbageCollectorMXBeans().map {
                                mapOf("name" to it.name, "count" to it.collectionCount, "timeMs" to it.collectionTime)
                            },
                        "heapUsedBytes" to memory.used,
                        "heapCommittedBytes" to memory.committed,
                    )
            } catch (failure: Exception) {
                identity + mapOf("valid" to false, "failure" to failure.toString())
            }
        val reference = HttpPerformanceCheckpointFiles.publishMetrics(directory, memberId, label, snapshot)
        captured.add(label)
        latest = snapshot
        return identity + reference + ("valid" to snapshot["valid"])
    }
}

/** Missing/null/coerced scalars must never impersonate the valid member zero. */
internal fun httpPerformanceCheckpointMemberMatches(
    value: JsonNode,
    expected: Int,
): Boolean = value.isIntegralNumber && value.canConvertToInt() && value.asInt() == expected

/** 같은 노드 callback 안의 차분만 보존한다. 큰 업무 snapshot과 절대 나노시각은 보관하지 않는다. */
internal data class HttpPerformanceCallbackTiming(
    val kind: String,
    val userId: String,
    val requestId: String,
    val admissionSequence: Long?,
    val position: Long,
    val productCallbackNs: Long,
    var observerNs: Long = 0,
    val admissionToExecutionGapNs: Long? = null,
    val phases: Map<String, Long> = emptyMap(),
    /** callback에서는 primitive 배열만 보존하고 Map은 checkpoint에서 만든다. */
    val phaseDurationsNs: LongArray = LongArray(0),
    val executionOfferAttempts: Int? = null,
    val executionBackPressured: Int? = null,
    val executionAdminActions: Int? = null,
    val executionOfferPosition: Long? = null,
    val replyAttempts: Int? = null,
    val replyBackPressured: Int? = null,
    val replyAdminActions: Int? = null,
    val replyOfferPosition: Long? = null,
    val replyStatus: String? = null,
    val callbackStartedNs: Long? = null,
    val callbackCompletedNs: Long? = null,
)

internal data class HttpPerformanceCallbackDurationSummary(
    val count: Int,
    val totalNs: Long,
    val p50Ns: Long?,
    val p95Ns: Long?,
    val p99Ns: Long?,
    val maxNs: Long?,
)

private fun callbackDurationSummary(values: List<Long>): HttpPerformanceCallbackDurationSummary {
    val sorted = values.sorted()

    fun percentile(fraction: Double): Long? = sorted.takeIf { it.isNotEmpty() }?.get(ceil(sorted.size * fraction).toInt() - 1)
    return HttpPerformanceCallbackDurationSummary(
        sorted.size,
        sorted.sum(),
        percentile(0.50),
        percentile(0.95),
        percentile(0.99),
        sorted.lastOrNull(),
    )
}

/** 큰 JSON은 main에서 완성한 뒤 공개한다. stdout에는 Docker 청크 크기보다 작은 참조만 남긴다. */
internal object HttpPerformanceCheckpointFiles {
    private val mapper = jacksonObjectMapper()

    fun publish(
        directory: Path,
        checkpoint: Map<String, Any?>,
    ): Map<String, Any?> {
        val member = checkpoint.getValue("memberId") as Int
        val label = checkpoint.getValue("label") as String
        require(member in 0..2 && label.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        val reference = write(directory, "node$member-checkpoint-$label.json", checkpoint)
        return mapOf(
            "kind" to "PERFORMANCE_CHECKPOINT",
            "runId" to checkpoint.getValue("runId"),
            "memberId" to member,
            "label" to label,
        ) + reference
    }

    fun publish(
        directory: Path,
        capture: HttpPerformanceCheckpointCapture,
        markerSummary: Map<String, Any?>,
        role: String,
        runtimeCounters: Any? = null,
    ): Map<String, Any?> {
        val member = capture.memberId
        val label = capture.label
        require(member in 0..2 && label.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        val reference =
            writeStream(directory, "node$member-checkpoint-$label.json") { output ->
                HttpPerformanceCheckpointStreaming.write(output, capture, markerSummary, role, runtimeCounters)
            }
        return mapOf(
            "kind" to "PERFORMANCE_CHECKPOINT",
            "runId" to capture.runId,
            "memberId" to member,
            "label" to label,
        ) + reference
    }

    fun publishStopped(
        directory: Path,
        stopped: Map<String, Any?>,
    ): Map<String, Any?> {
        val member = stopped.getValue("memberId") as Int
        require(member in 0..2)
        val summary = stopped.getValue("markerSummary") as Map<*, *>
        val failures = summary["failures"] as? List<*> ?: emptyList<Any?>()
        // Shutdown can discover a force/close failure after the last successful checkpoint.
        val recorded = stopped + ("failure" to (stopped["failure"] ?: failures.firstOrNull()?.toString()))
        val reference = write(directory, "node$member-stopped.json", recorded)
        val compact =
            (summary - "sentMarkers" - "undeliveredMarkers" - "senderLocalSpans" - "failures") +
                mapOf("failures" to failures.take(1), "failureCount" to failures.size)
        return recorded + ("markerSummary" to compact) + reference
    }

    fun read(
        directory: Path,
        reference: JsonNode,
        runId: String,
        member: Int,
        label: String,
    ): JsonNode {
        require(member in 0..2 && label.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        require(reference.path("kind").asString() == "PERFORMANCE_CHECKPOINT")
        require(reference.path("runId").asString() == runId && httpPerformanceCheckpointMemberMatches(reference.path("memberId"), member))
        require(reference.path("label").asString() == label)
        val expectedName = "node$member-checkpoint-$label.json"
        require(reference.path("artifactFile").asString() == expectedName) { "체크포인트 파일 식별자가 다릅니다" }
        val file = directory.resolve(expectedName)
        require(Files.isRegularFile(file) && Files.size(file) == reference.path("artifactBytes").asLong()) { "체크포인트 파일 크기가 다릅니다" }
        require(sha256(file) == reference.path("artifactSha256").asString()) { "체크포인트 파일 지문이 다릅니다" }
        return Files.newInputStream(file).use { mapper.readTree(it) }.also { full ->
            require(full.path("kind").asString() == "PERFORMANCE_CHECKPOINT")
            require(
                full.path("runId").asString() == runId && httpPerformanceCheckpointMemberMatches(full.path("memberId"), member) &&
                    full.path("label").asString() == label,
            )
        }
    }

    fun publishMetrics(
        directory: Path,
        member: Int,
        label: String,
        metrics: Map<String, Any?>,
    ): Map<String, Any?> {
        require(member in 0..2 && (label == "before" || label == "after"))
        return write(directory, "node$member-metrics-$label.json", metrics)
    }

    private fun write(
        directory: Path,
        name: String,
        value: Map<String, Any?>,
    ): Map<String, Any?> = writeStream(directory, name) { output -> mapper.writeValue(output, value) }

    private fun writeStream(
        directory: Path,
        name: String,
        writeContent: (OutputStream) -> Unit,
    ): Map<String, Any?> {
        val target = directory.resolve(name)
        require(!Files.exists(target)) { "기존 체크포인트 파일을 덮어쓰지 않습니다: $name" }
        val temporary = Files.createTempFile(directory, ".$name-", ".tmp")
        try {
            Files.newOutputStream(temporary).use(writeContent)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return mapOf("artifactFile" to name, "artifactBytes" to Files.size(target), "artifactSha256" to sha256(target))
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}

/** callback에서는 이 명령의 불변 결과와 완료를 읽는다. 누적 상태는 체크포인트에서만 복사한다. */
internal class HttpPerformanceCollector(
    private val runId: String,
    private val memberId: Int,
    private val send: (HttpPerformanceCompletion) -> Unit,
    private val nanoTime: NanoClock,
    private val callbackTimingCapacity: Int,
    private val detailedTracing: Boolean = true,
    private val markerPlan: HttpPerformanceMarkerPlan? = null,
    private val requestJfr: HttpPerformanceJfr? = null,
    identityEvidencePath: Path? = null,
    identityArchiveLimits: HttpPerformanceIdentityArchiveLimits = HttpPerformanceIdentityArchiveLimits(),
) : AutoCloseable {
    constructor(
        runId: String,
        memberId: Int,
        send: (HttpPerformanceCompletion) -> Unit,
    ) : this(runId, memberId, send, SystemNanoClock.INSTANCE, HTTP_PERFORMANCE_CALLBACK_CAPACITY)

    private val identityCapacity = markerPlan?.identitiesPerMember ?: HTTP_PERFORMANCE_MARKER_CAPACITY
    private val history =
        HttpPerformanceCollectorHistory(
            identityCapacity,
            identityEvidencePath?.let { HttpPerformanceIdentityArchive(it, identityArchiveLimits) },
        )
    private val callbackTimings = ArrayList<HttpPerformanceCallbackTiming>()
    private val pendingAdmissionEndNs = mutableMapOf<Long, Long>()
    private var latestNodeTiming: TradingNodeCallbackTelemetry? = null
    private var timingCheckpointLabel: String? = null
    private val failure = AtomicReference<String?>()
    private val checkpointRequest = AtomicReference<String?>()
    private val checkpointResult = AtomicReference<HttpPerformanceCheckpointCapture?>()
    private val roleChanges = AtomicLong()
    private val currentRole = AtomicReference("UNKNOWN")
    private val recordRetention = identityEvidencePath != null
    private var lastRetentionNs = 0L

    @Volatile
    var tradingHistoryRetention: TradingHistoryRetention? = null
        private set
    internal val retainedIdentityCount: Int get() = history.retainedCount
    val error: String? get() = failure.get() ?: history.error
    val roleChangeCount: Long get() = roleChanges.get()
    val role: String get() = currentRole.get()

    init {
        require(callbackTimingCapacity in 1..HTTP_PERFORMANCE_CALLBACK_CAPACITY)
        require(markerPlan == null || markerPlan.runId == runId) { "완료 표식 계획과 collector 실행이 다릅니다" }
    }

    fun requestCheckpoint(label: String) = checkpointRequest.set(label)

    fun pollCheckpoint(): HttpPerformanceCheckpointCapture? = checkpointResult.getAndSet(null)

    fun decorate(service: DevelopmentFundingAdapter): ClusteredService {
        if (detailedTracing) service.attachTelemetry(TradingNodeTelemetry { latestNodeTiming = it }, nanoTime)
        return object : ClusteredService by service {
            override fun onStart(
                cluster: Cluster,
                snapshotImage: Image?,
            ) {
                service.onStart(cluster, snapshotImage)
                currentRole.compareAndSet("UNKNOWN", cluster.role().name)
            }

            override fun onSessionMessage(
                session: ClientSession?,
                timestamp: Long,
                buffer: DirectBuffer,
                offset: Int,
                length: Int,
                header: Header,
            ) {
                drainIdentityHistory()
                val previous = service.observation
                latestNodeTiming = null
                val startNs = if (detailedTracing) nanoTime.nanoTime() else 0L
                val jfrEvent = requestJfr?.let { observeRequestJfr { it.begin() } }
                var productFailed = false
                try {
                    service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                } catch (error: Throwable) {
                    productFailed = true
                    throw error
                } finally {
                    if (jfrEvent != null) observeRequestJfr { requestJfr?.end(jfrEvent) }
                    // 제품 callback의 업무 적용·응답 재시도 뒤 observer를 따로 잰다.
                    val productCompletedNs = if (detailedTracing) nanoTime.nanoTime() else 0L
                    if (!service.telemetryOperational) {
                        failure.compareAndSet(null, "노드 계측 장애: failures=${service.telemetryFailureCount}")
                    }
                    val observation = service.observation?.takeIf { it !== previous }
                    val firstExecution =
                        observeSafely("최초 실행 완료 여부 조회") {
                            observation?.executionSequence?.let { !history.isCompleted(it) } == true
                        } ?: false
                    observation?.let(::observe)
                    if (jfrEvent != null) {
                        observeRequestJfr {
                            requestJfr?.commit(
                                jfrEvent,
                                observation,
                                observation?.executionSequence?.let(history::get),
                                header.position(),
                                role,
                                productFailed,
                            )
                        }
                    }
                    if (detailedTracing) {
                        recordCallbackTiming(
                            observation,
                            productCompletedNs - startNs,
                            productCompletedNs,
                            startNs,
                            firstExecution,
                            latestNodeTiming,
                        )
                    }
                    drainIdentityHistory()
                }
            }

            override fun onRoleChange(newRole: Cluster.Role) {
                service.onRoleChange(newRole)
                currentRole.set(newRole.name)
                roleChanges.incrementAndGet()
            }

            override fun doBackgroundWork(nowNs: Long): Int {
                val work = service.doBackgroundWork(nowNs) + drainIdentityHistory()
                if (recordRetention && nowNs - lastRetentionNs >= TimeUnit.SECONDS.toNanos(1)) {
                    lastRetentionNs = nowNs
                    observeSafely("업무 이력 보관량 수집") {
                        if (!isVerificationTradingBlocked(service)) tradingHistoryRetention = captureVerificationRetention(service)
                    }
                }
                val label = checkpointRequest.getAndSet(null) ?: return work
                try {
                    val observation = service.captureObservation().join()
                    val snapshot = observation?.snapshot ?: service.captureSnapshotOnServiceThread()
                    checkpointResult.set(
                        captureHttpPerformanceCheckpoint(
                            runId,
                            memberId,
                            checkNotNull(config),
                            service,
                            label,
                            observation,
                            snapshot,
                            history.completedCount,
                            callbackTimingCheckpoint(label),
                            error,
                            roleChangeCount,
                        ),
                    )
                    // raw 복사본은 이 체크포인트에 남긴다. 다음 구간은 새 유한 버퍼로 기록한다.
                    callbackTimings.clear()
                    timingCheckpointLabel = label
                } catch (error: Throwable) {
                    failure.compareAndSet(null, "체크포인트 수집 실패: ${error.javaClass.simpleName}: ${error.message}")
                }
                return work + 1
            }
        }
    }

    internal fun drainIdentityHistory(): Int =
        try {
            history.drainAcknowledgements()
        } catch (error: Throwable) {
            failure.compareAndSet(null, "Accepted 원문 배출 실패: ${error.javaClass.simpleName}: ${error.message}")
            0
        }

    override fun close() {
        try {
            history.close()
        } catch (error: Throwable) {
            failure.compareAndSet(null, "Accepted 원문 종료 실패: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    // 이 설정은 경로·마켓 정보만 담고 DB 자격증명을 담지 않는다.
    private var config: DevelopmentFundingSettings? = null

    private inline fun <T> observeSafely(
        operation: String,
        action: () -> T,
    ): T? =
        try {
            action()
        } catch (error: Throwable) {
            // 관찰용 디스크 조회나 진단 실패는 제품 callback의 결과·예외를 바꾸지 않는다.
            failure.compareAndSet(null, "$operation 실패: ${error.javaClass.simpleName}: ${error.message}")
            null
        }

    private inline fun <T> observeRequestJfr(action: () -> T): T? =
        try {
            action()
        } catch (error: Throwable) {
            failure.compareAndSet(null, "요청 JFR 관찰 실패: ${error.javaClass.simpleName}: ${error.message}")
            null
        }

    fun configure(value: DevelopmentFundingSettings) {
        config = value
    }

    private fun recordCallbackTiming(
        observation: FundingObservation?,
        productCallbackNs: Long,
        observerStartedNs: Long,
        callbackStartedNs: Long,
        firstExecution: Boolean,
        nodeTiming: TradingNodeCallbackTelemetry?,
    ) {
        try {
            require(productCallbackNs >= 0) { "제품 callback의 노드 내부 시계 차분이 음수입니다" }
            val applied = requireNotNull(observation) { "callback의 새 업무 관찰이 없습니다" }
            val admission = applied.admissionResult
            val executionSequence = applied.executionSequence
            val opening =
                when (val funding = applied.result) {
                    is DevelopmentBalanceResultPayload.Prepared -> funding.result.opening
                    is DevelopmentBalanceResultPayload.Rejected -> funding.opening
                    null -> null
                }
            val timing =
                when {
                    admission != null -> {
                        val accepted = admission as? AdmissionResultPayload.Accepted
                        if (accepted != null && !history.isCompleted(accepted.sequence) && accepted.sequence !in pendingAdmissionEndNs) {
                            require(pendingAdmissionEndNs.size < callbackTimingCapacity) {
                                "Admission 종료시각 버퍼 초과: capacity=$callbackTimingCapacity"
                            }
                            pendingAdmissionEndNs[accepted.sequence] = observerStartedNs
                        }
                        HttpPerformanceCallbackTiming(
                            "ADMISSION",
                            admission.userId,
                            admission.requestId,
                            accepted?.sequence,
                            applied.position,
                            productCallbackNs,
                        )
                    }

                    executionSequence != null -> {
                        val identity = requireNotNull(history[executionSequence]) { "실행 callback의 원래 HTTP 요청 식별자가 없습니다" }
                        val gapNs =
                            if (firstExecution) {
                                val admissionEndNs =
                                    requireNotNull(pendingAdmissionEndNs.remove(executionSequence)) {
                                        "최초 실행 callback의 원래 Admission 종료시각이 없습니다"
                                    }
                                (callbackStartedNs - admissionEndNs).also {
                                    require(it >= 0) { "Admission과 실행 사이의 노드 내부 시계 차분이 음수입니다" }
                                }
                            } else {
                                null
                            }
                        HttpPerformanceCallbackTiming(
                            "EXECUTION",
                            identity.userId,
                            identity.requestId,
                            executionSequence,
                            applied.position,
                            productCallbackNs,
                            admissionToExecutionGapNs = gapNs,
                        )
                    }

                    opening != null -> {
                        HttpPerformanceCallbackTiming(
                            "FUNDING",
                            opening.userId.value,
                            opening.preparationId,
                            null,
                            applied.position,
                            productCallbackNs,
                        )
                    }

                    else -> {
                        error("callback 입력 종류를 업무 관찰에서 확인할 수 없습니다")
                    }
                }
            val phases = requireNotNull(nodeTiming) { "노드 내부 callback 계측이 없습니다" }
            require(phases.logPosition == timing.position && phases.timingErrors == 0) { "노드 내부 계측 위치 또는 시계가 잘못됐습니다" }
            phases.userId?.let { require(it == timing.userId) { "노드 내부 계측 사용자 식별자가 다릅니다" } }
            phases.requestId?.let { require(it == timing.requestId) { "노드 내부 계측 요청 식별자가 다릅니다" } }
            require(phases.admissionSequence == timing.admissionSequence) { "노드 내부 계측 접수 순번이 다릅니다" }
            val enriched =
                timing.copy(
                    phaseDurationsNs = phases.phaseDurationsNs,
                    executionOfferAttempts =
                        phases.executionOfferAttempts.takeIf {
                            phases.phaseDurationsNs[TradingNodePhase.EXECUTION_OFFER.ordinal] >=
                                0
                        },
                    executionBackPressured =
                        phases.executionBackPressured.takeIf {
                            phases.phaseDurationsNs[TradingNodePhase.EXECUTION_OFFER.ordinal] >=
                                0
                        },
                    executionAdminActions =
                        phases.executionAdminActions.takeIf {
                            phases.phaseDurationsNs[TradingNodePhase.EXECUTION_OFFER.ordinal] >=
                                0
                        },
                    executionOfferPosition = phases.executionOfferPosition,
                    replyAttempts = phases.replyAttempts.takeIf { phases.phaseDurationsNs[TradingNodePhase.REPLY_OFFER.ordinal] >= 0 },
                    replyBackPressured =
                        phases.replyBackPressured.takeIf {
                            phases.phaseDurationsNs[TradingNodePhase.REPLY_OFFER.ordinal] >= 0
                        },
                    replyAdminActions =
                        phases.replyAdminActions.takeIf {
                            phases.phaseDurationsNs[TradingNodePhase.REPLY_OFFER.ordinal] >= 0
                        },
                    replyOfferPosition = phases.replyOfferPosition,
                    replyStatus = phases.replyStatus,
                    callbackStartedNs = phases.callbackStartedNs,
                    callbackCompletedNs = phases.callbackCompletedNs,
                )
            require(callbackTimings.size < callbackTimingCapacity) { "callback 시간 버퍼 초과: capacity=$callbackTimingCapacity" }
            callbackTimings.add(enriched)
            enriched.observerNs = nanoTime.nanoTime() - observerStartedNs
            require(enriched.observerNs >= 0) { "observer의 노드 내부 시계 차분이 음수입니다" }
        } catch (error: Throwable) {
            // 계측 한도를 넘어도 실제 callback 결과와 서비스 생명주기는 보존한다.
            failure.compareAndSet(null, "callback 시간 수집 실패: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun callbackTimingCheckpoint(label: String): Map<String, Any?> {
        fun summaries(samples: List<HttpPerformanceCallbackTiming>): Map<String, Any?> =
            listOf("FUNDING", "ADMISSION", "EXECUTION").associateWith { kind ->
                val matching = samples.filter { it.kind == kind }
                mapOf(
                    "productCallback" to callbackDurationSummary(matching.map { it.productCallbackNs }),
                    "observer" to callbackDurationSummary(matching.map { it.observerNs }),
                    "admissionToExecutionGap" to callbackDurationSummary(matching.mapNotNull { it.admissionToExecutionGapNs }),
                    "phases" to
                        TradingNodePhase.entries.associate { phase ->
                            phase.tag to
                                callbackDurationSummary(
                                    matching.mapNotNull {
                                        it.phaseDurationsNs.getOrNull(phase.ordinal)?.takeIf { elapsed ->
                                            elapsed >=
                                                0
                                        }
                                    },
                                )
                        },
                )
            }
        return mapOf(
            "clock" to "node-local System.nanoTime differences only",
            "rawTimePointScope" to
                "callbackStartedNs/callbackCompletedNs join only this node JVM's runtimeCounters.originNs + frame.elapsedNs; " +
                "never subtract client/peer JVM clocks",
            "detailedTracing" to detailedTracing,
            "scope" to "callbacks since previous checkpoint",
            "afterCheckpoint" to timingCheckpointLabel,
            "throughCheckpoint" to label,
            "productCallbackScope" to
                "onSessionMessage including business apply and reply attempts; full snapshot only at explicit checkpoint",
            "observerScope" to "performance observer and bounded timing append after product callback",
            "admissionToExecutionGapScope" to
                "first Admission product callback end to first Execution product callback start on this node; " +
                "includes observer, intervening callbacks and scheduling; internal submission already starts inside Admission, " +
                "so this is not total consensus latency",
            "capacity" to callbackTimingCapacity,
            "pendingAdmissionTimingCount" to pendingAdmissionEndNs.size,
            "all" to summaries(callbackTimings),
            "measuredBuy" to summaries(callbackTimings.filter { it.userId == "BUYER" && it.requestId.startsWith("M-") }),
            "raw" to
                callbackTimings.map { row ->
                    row.copy(
                        phases =
                            buildMap {
                                TradingNodePhase.entries.forEach { phase ->
                                    row.phaseDurationsNs
                                        .getOrNull(phase.ordinal)
                                        ?.takeIf { it >= 0 }
                                        ?.let { put(phase.tag, it) }
                                }
                            },
                    )
                },
        )
    }

    internal fun observe(observation: FundingObservation) {
        try {
            observation.failure?.let { error("업무 서비스 실패: $it") }
            require(observation.malformedMessages == 0L) { "잘못된 엔진 메시지가 관측됐습니다" }
            (observation.admissionResult as? AdmissionResultPayload.Accepted)?.let(history::record)
            val sequence = observation.executionSequence ?: return
            if (history.isCompleted(sequence)) return
            val identity = requireNotNull(history[sequence]) { "실행 순번의 원래 HTTP 요청 식별자가 없습니다" }
            // 제품이 반환한 이 실행의 불변 완료를 읽는다. 누적 전체 상태는 체크포인트에서만 복사한다.
            val completion = requireNotNull(observation.executionCompletion) { "실행 완료 상태가 없습니다" }
            require(completion.admission.sequence == sequence) { "실행 순번과 완료 상태가 다릅니다" }
            require(completion.admission.candidate.order.orderId.value == identity.orderId) { "완료 주문 식별자가 다릅니다" }
            require(completion.admission.candidate.order.userId.value == identity.userId) { "완료 사용자 식별자가 다릅니다" }
            val marker =
                HttpPerformanceCompletion(
                    runId,
                    memberId,
                    requireNotNull(observation.appliedRole),
                    identity.userId,
                    identity.requestId,
                    identity.orderId,
                    sequence,
                    observation.position,
                    completion.matchingResult.events
                        .count { it is TradeExecuted }
                        .toLong(),
                    completion.settlement.settlements.size
                        .toLong(),
                )
            history.markCompleted(sequence)
            send(marker)
        } catch (error: Throwable) {
            // 계측은 보고서를 무효로 만들 수 있지만 실제 주문을 거절하거나 중단하지 않는다.
            failure.compareAndSet(null, "완료 표식 수집 실패: ${error.javaClass.simpleName}: ${error.message}")
        }
    }
}

private fun performanceCheckpointTradingEvidence(
    service: DevelopmentFundingAdapter,
    snapshot: TradingState.Snapshot,
): Map<String, Any?> =
    tradingVerificationEvidence(service, snapshot) +
        mapOf(
            "matchingResultCount" to snapshot.matchingResults.size,
            "matchingResults" to
                snapshot.matchingResults.map { result ->
                    val command = result.command as SubmitOrderCommand
                    mapOf(
                        "commandId" to result.commandId,
                        "marketId" to result.command.marketId.value,
                        "orderId" to command.orderId.value,
                        "userId" to command.userId.value,
                        "expectedEventCount" to result.expectedEventCount,
                        "firstEngineSequence" to result.firstEngineSequence,
                        "lastEngineSequence" to result.lastEngineSequence,
                        "events" to
                            result.events.map { event ->
                                mapOf("marketId" to event.marketId.value, "engineSequence" to event.engineSequence) +
                                    when (event) {
                                        is TradeExecuted -> {
                                            mapOf(
                                                "kind" to "TRADE_EXECUTED",
                                                "makerOrderId" to event.makerOrderId.value,
                                                "takerOrderId" to event.takerOrderId.value,
                                                "price" to event.price.value,
                                                "quantity" to event.quantity.value,
                                            )
                                        }

                                        is OrderEnteredBook -> {
                                            mapOf(
                                                "kind" to "ORDER_ENTERED_BOOK",
                                                "orderId" to event.orderId.value,
                                                "remainingQuantity" to event.remainingQuantity.value,
                                            )
                                        }

                                        else -> {
                                            error("예상하지 않은 매칭 사건: $event")
                                        }
                                    }
                            },
                    )
                },
        )

/** 테스트 전용 진입점이다. 측정 밖에서 명시적으로 요청한 체크포인트만 전체 상태를 수집한다. */
fun main(args: Array<String>) {
    require(args.contentEquals(arrayOf("--cluster-env"))) { "성능 노드 사용법: --cluster-env" }
    val config =
        developmentClusterSettings().copy(
            archiveFileSyncLevel = (System.getenv("PERFORMANCE_ARCHIVE_SYNC_LEVEL") ?: "2").toInt(),
        )
    val endpoint = requireNotNull(System.getenv("PERFORMANCE_MARKER_ENDPOINT"))
    val control = Path.of(requireNotNull(System.getenv("PERFORMANCE_CONTROL_DIRECTORY")))
    val markerIdlePolicy =
        HttpPerformanceMarkerIdlePolicy.valueOf(System.getenv("PERFORMANCE_MARKER_IDLE_POLICY") ?: "SLEEP_1MS")
    val detailedTracing = (System.getenv("PERFORMANCE_DETAILED_TRACING") ?: "true").toBooleanStrict()
    val requestJfr =
        if ((System.getenv("PERFORMANCE_REQUEST_JFR") ?: "false").toBooleanStrict()) {
            HttpPerformanceJfr(requireNotNull(config.runId), config.memberId)
        } else {
            null
        }
    val markerPlan = httpPerformanceMarkerPlanFromEnvironment(requireNotNull(config.runId))
    val evidenceTimeBudget = httpPerformanceNodeEvidenceTimeBudget(System.getenv("PERFORMANCE_EVIDENCE_TIME_BUDGET"), markerPlan.orders)
    val sender =
        HttpPerformanceMarkerSender(
            config.runRoot,
            endpoint,
            idlePolicy = markerIdlePolicy,
            detailedTracing = detailedTracing,
            markerPlan = markerPlan,
            requestJfr = requestJfr,
        )
    val collector =
        HttpPerformanceCollector(
            requireNotNull(config.runId),
            config.memberId,
            sender::offer,
            SystemNanoClock.INSTANCE,
            HTTP_PERFORMANCE_CALLBACK_CAPACITY,
            detailedTracing,
            markerPlan,
            requestJfr,
            identityEvidencePath = control.resolve("node${config.memberId}-collector-identities.bin"),
        )
    collector.configure(config)
    var runtimeCounters: HttpPerformanceRuntimeCounters? = null
    var runtimeCounterFailure: String? = null
    var metricsControl: HttpPerformanceNodeMetricsControl? = null
    val closed = AtomicReference<DevelopmentFundingNode?>()
    val finished = CountDownLatch(1)
    val shutdown =
        Thread({
            closed.get()?.close()
            finished.await(evidenceTimeBudget.nodeShutdownReportWaitMs, TimeUnit.MILLISECONDS)
        }, "http-performance-node-shutdown")
    try {
        fundingEvent(
            mapOf(
                "kind" to "PERFORMANCE_STARTING",
                "runId" to config.runId,
                "markerPlan" to markerPlan.fields(),
                "memberId" to config.memberId,
                "advertisedHost" to config.advertisedHost,
                "advertisedAddresses" to InetAddress.getAllByName(config.advertisedHost).map { it.hostAddress },
                "members" to config.members,
            ),
        )
        DevelopmentFundingNode.start(config, collector::decorate).use { node ->
            if (System.getenv("PERFORMANCE_COUNTER_SAMPLING") == "true") {
                runtimeCounters = HttpPerformanceRuntimeCounters.start(config.driverDirectory)
            }
            sender.start()
            closed.set(node)
            Runtime.getRuntime().addShutdownHook(shutdown)
            fundingEvent(
                fundingReadyFields(config, node) +
                    mapOf(
                        "performance" to true,
                        "markerCapacity" to sender.capacity,
                        "markerTransport" to "Aeron UDP",
                        "markerIdlePolicy" to markerIdlePolicy.name,
                        "markerIdleStrategy" to markerIdlePolicy.description,
                        "detailedTracing" to detailedTracing,
                        "postLoadEvidenceTimeBudget" to evidenceTimeBudget.fields(),
                        "idle" to "BackoffIdleStrategy(10,5,1000ns,100000ns)",
                        "javaVersion" to System.getProperty("java.version"),
                        "javaVm" to System.getProperty("java.vm.name"),
                        "architecture" to System.getProperty("os.arch"),
                        "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
                        "jvmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments,
                    ),
            )
            // Opt-in is sampled once at startup. The default path has no diagnostic poll or MBean call.
            val nodeHeapDiagnostics = HttpPerformanceNodeHeapControl.open(control, requireNotNull(config.runId), config.memberId)
            metricsControl = HttpPerformanceNodeMetricsControl(control, requireNotNull(config.runId), config.memberId) { node.metrics }
            var lastRequest: String? = null
            var lastHealthNs = 0L
            var pendingCheckpoint: HttpPerformanceCheckpointCapture? = null
            var checkpointDeadline = 0L
            do {
                val now = System.nanoTime()
                if (now - lastHealthNs >= TimeUnit.SECONDS.toNanos(1)) {
                    fundingEvent(
                        mapOf(
                            "kind" to "PERFORMANCE_HEALTH",
                            "runId" to config.runId,
                            "memberId" to config.memberId,
                            "role" to collector.role,
                            "roleChangeCount" to collector.roleChangeCount,
                            "position" to node.observation?.position,
                            "collectorFailure" to collector.error,
                            "tradingHistoryRetention" to collector.tradingHistoryRetention,
                            "failure" to node.error?.toString(),
                        ),
                    )
                    lastHealthNs = now
                }
                val requestPath = control.resolve("checkpoint.request")
                if (Files.isRegularFile(requestPath)) {
                    val request = Files.readString(requestPath).trim()
                    if (request.isNotEmpty() && request != lastRequest) {
                        require(request.matches(Regex("[a-zA-Z0-9-]{1,100}")))
                        collector.requestCheckpoint(request)
                        lastRequest = request
                    }
                }
                collector.pollCheckpoint()?.let {
                    pendingCheckpoint = it
                    checkpointDeadline = now + TimeUnit.SECONDS.toNanos(10)
                }
                pendingCheckpoint?.let { checkpoint ->
                    if (sender.isDrained || now >= checkpointDeadline) {
                        fundingEvent(
                            HttpPerformanceCheckpointFiles.publish(
                                control,
                                checkpoint,
                                sender.summary(),
                                collector.role,
                                runtimeCounters?.snapshot(),
                            ),
                        )
                        pendingCheckpoint = null
                    }
                }
                // Runs on the existing test control thread, never inside a service callback.
                nodeHeapDiagnostics?.poll()?.let(::fundingEvent)
                metricsControl?.poll()?.let(::fundingEvent)
            } while (!node.awaitClosed(25))
            node.error?.let { throw IllegalStateException("성능 노드의 업무 서비스가 중단됐습니다", it) }
        }
    } catch (error: Throwable) {
        fundingEvent(mapOf("kind" to "ERROR", "runId" to config.runId, "memberId" to config.memberId, "failure" to error.toString()))
        throw error
    } finally {
        try {
            runtimeCounters?.let { counters ->
                counters.close()
                Files.newOutputStream(control.resolve("node${config.memberId}-runtime-counters.json")).use { output ->
                    jacksonObjectMapper().writeValue(output, counters.snapshot())
                }
            }
        } catch (failure: Throwable) {
            // 진단 보존 실패가 실제 노드·송신기 종료를 건너뛰거나 성공 보고가 되지 않게 한다.
            runtimeCounterFailure = "runtime counter 종료·보존 실패: ${failure.javaClass.simpleName}: ${failure.message}"
        }
        try {
            closed.get()?.close()
        } finally {
            // Business callbacks have stopped before the sole owner drains/closes its evidence.
            collector.close()
        }
        sender.close()
        val stopped =
            mapOf(
                "kind" to "STOPPED",
                "runId" to config.runId,
                "memberId" to config.memberId,
                "closed" to (closed.get()?.isClosed == true),
                "failure" to (closed.get()?.error?.toString() ?: collector.error ?: runtimeCounterFailure),
                "markerSummary" to sender.summary(),
                "operationalMetrics" to metricsControl?.latest,
            )
        val stoppedReference =
            runCatching { HttpPerformanceCheckpointFiles.publishStopped(control, stopped) }.getOrElse {
                stopped - "markerSummary" + ("failure" to "종료 관찰 파일 보존 실패: ${it.javaClass.simpleName}: ${it.message}")
            }
        fundingEvent(stoppedReference)
        finished.countDown()
        try {
            Runtime.getRuntime().removeShutdownHook(shutdown)
        } catch (_: IllegalStateException) {
            // 종료 hook은 생산자를 닫고 main의 관찰 배출·보고 완료를 기다린다.
        }
    }
}
