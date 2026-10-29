package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

internal data class DirectAeronCapacityOptions(
    val smoke: Boolean,
    val sustainedRate: Int? = null,
    val sustainedSeconds: Int? = null,
    val preserveNodeRecordings: Boolean = false,
    val diagnosticProfiles: Boolean = false,
) {
    init {
        require(!preserveNodeRecordings || sustainedRate != null) { "노드 원본 보존은 명시적 sustained 실행에서만 허용합니다" }
        require(!diagnosticProfiles || sustainedRate != null) { "프로파일은 명시적 sustained 진단 실행에서만 허용합니다" }
        require((sustainedRate == null) == (sustainedSeconds == null))
        if (sustainedRate != null) {
            require(!smoke)
            directAeronSustainedCount(sustainedRate, checkNotNull(sustainedSeconds))
        }
    }

    val sustained: Boolean get() = sustainedRate != null
    val warmup: Int get() = if (smoke) 1 else 200
    val orders: Int
        get() =
            if (sustained) {
                directAeronSustainedCount(checkNotNull(sustainedRate), checkNotNull(sustainedSeconds))
            } else if (smoke) {
                2
            } else {
                2000
            }
    val rates: List<Int>
        get() = sustainedRate?.let { listOf(it) } ?: if (smoke) listOf(10) else listOf(1000, 5000, 10_000, 20_000)
    val maxInFlight: Int get() = 4096
}

internal fun parseDirectAeronCapacityOptions(args: Array<String>): DirectAeronCapacityOptions {
    if (args.firstOrNull() == "--sustained") {
        val flags = args.drop(3)
        require(
            args.size in 3..5 && args[1].startsWith("--rate=") && args[2].startsWith("--seconds=") &&
                flags.size == flags.distinct().size &&
                flags.all { it in setOf("--preserve-node-recordings", "--diagnostic-profiles") },
        ) {
            "장기 직접 Aeron: --sustained --rate=1000 --seconds=60 [--preserve-node-recordings] [--diagnostic-profiles]"
        }
        return DirectAeronCapacityOptions(
            smoke = false,
            sustainedRate = requireNotNull(args[1].removePrefix("--rate=").toIntOrNull()),
            sustainedSeconds = requireNotNull(args[2].removePrefix("--seconds=").toIntOrNull()),
            preserveNodeRecordings = "--preserve-node-recordings" in flags,
            diagnosticProfiles = "--diagnostic-profiles" in flags,
        )
    }
    require(args.size == 1 && args.single() in setOf("--smoke", "--probe")) { "직접 Aeron 접수 burst: --smoke 또는 --probe" }
    return DirectAeronCapacityOptions(smoke = args.single() == "--smoke")
}

internal data class DirectAeronMarkerObservation(
    val marker: HttpPerformanceCompletion,
    val receivedNs: Long,
)

/** 이미 중복을 제거하는 수신기 뒤에서 측정 BUY만 최대 3N개 보존한다. */
internal class DirectAeronMarkerObservations(
    private val runId: String,
    private val orders: Int,
) {
    private val values = mutableListOf<DirectAeronMarkerObservation>()
    private val keys = mutableSetOf<Pair<Int, String>>()
    private val errors = linkedSetOf<String>()
    private var overflow = 0L

    init {
        require(orders in 1..2000)
    }

    @Synchronized
    fun observe(
        marker: HttpPerformanceCompletion,
        receivedNs: Long,
    ) {
        if (marker.userId != "BUYER" || !marker.requestId.startsWith("M-")) return
        if (marker.runId != runId || marker.memberId !in 0..2 || marker.orderId != marker.requestId ||
            marker.requestId.removePrefix("M-").toIntOrNull() !in 0 until orders
        ) {
            if (errors.size < 2000) errors.add("INVALID_MEASURED_MARKER:${marker.memberId}/${marker.requestId}") else overflow++
            return
        }
        if (values.size >= orders * 3) {
            overflow++
            return
        }
        values.add(DirectAeronMarkerObservation(marker, receivedNs))
        if (!keys.add(marker.memberId to marker.requestId)) errors.add("DUPLICATE_REQUEST_MARKER:${marker.memberId}/${marker.requestId}")
    }

    @Synchronized
    fun complete(): Boolean = keys.size == orders * 3

    @Synchronized
    fun complete(requestIds: Set<String>): Boolean = requestIds.all { id -> (0..2).all { member -> (member to id) in keys } }

    @Synchronized
    fun complete(
        requestIds: Set<String>,
        cutoffNs: Long,
    ): Boolean {
        val within = values.filter { cutoffNs - it.receivedNs >= 0 }.map { it.marker.memberId to it.marker.requestId }.toSet()
        return requestIds.all { id -> (0..2).all { member -> (member to id) in within } }
    }

    @Synchronized
    fun snapshot(): List<DirectAeronMarkerObservation> = values.toList()

    @Synchronized
    fun errors(): List<String> = errors.toList() + if (overflow > 0) listOf("MARKER_OBSERVATION_OVERFLOW:$overflow") else emptyList()
}

/** The caller preserves its absolute deadline before this interruptible wait; scheduling cannot extend the cutoff. */
internal fun awaitDirectAeronMarkers(
    observations: DirectAeronMarkerObservations,
    requestIds: Set<String>,
    deadlineNs: Long,
    nanoTime: () -> Long = System::nanoTime,
    pause: () -> Unit = { Thread.sleep(10) },
): Long {
    while (true) {
        val now = nanoTime()
        val expired = now - deadlineNs >= 0
        val cutoff = if (expired) deadlineNs else now
        if (observations.complete(requestIds, cutoff) || expired) return cutoff
        pause()
    }
}

private fun JsonNode.optionalLong(field: String): Long? = path(field).takeUnless { it.isMissingNode || it.isNull }?.asLong()

/** client의 같은 시계로만 ACK와 예정 창 안의 실제 전송률을 계산한다. */
internal fun directAeronClientMetrics(
    result: JsonNode,
    rate: Int,
    orders: Int,
    maxInFlight: Int,
): Map<String, Any?> {
    check(!result.isMissingNode && !result.isNull) { "직접 client 결과 누락" }
    check(result.path("planned").asInt() == orders && result.path("workload").path("rate").asInt() == rate)
    check(result.path("workload").path("count").asInt() == orders && result.path("workload").path("maxInFlight").asInt() == maxInFlight)
    val firstDue = checkNotNull(result.optionalLong("firstDueNs"))
    val windowEnd = checkNotNull(result.optionalLong("windowEndNs"))
    val windowNs = windowEnd - firstDue
    check(windowNs == TimeUnit.SECONDS.toNanos(orders.toLong()) / rate && windowNs > 0)
    val samples = result.path("samples").toList()
    check(
        samples.size == orders &&
            samples.map { it.path("command").path("requestId").asString() }.toSet() == (0 until orders).map { "M-$it" }.toSet(),
    )
    check(result.path("peakInFlight").asInt() in 0..maxInFlight)

    fun inWindow(ns: Long?): Boolean = ns != null && ns - firstDue >= 0 && windowEnd - ns > 0

    val latencies =
        samples
            .mapNotNull { sample ->
                if (sample.path("outcome").asString() != "ACCEPTED") {
                    null
                } else {
                    val start = checkNotNull(sample.optionalLong("startNs"))
                    val accepted = checkNotNull(sample.optionalLong("transportAcceptedNs"))
                    val ack = checkNotNull(sample.optionalLong("ackNs"))
                    check(accepted - start >= 0 && ack - accepted >= 0)
                    ack - start
                }
            }.sorted()

    fun percentile(fraction: Double): Long? = latencies.takeIf { it.isNotEmpty() }?.get(ceil(latencies.size * fraction).toInt() - 1)

    return mapOf(
        "clock" to "client JVM System.nanoTime only",
        "scope" to "admission ACK and actual client sends; no execution completion latency",
        "offeredPerSecond" to rate,
        "plannedWindowNs" to windowNs,
        "started" to result.path("started").asInt(),
        "transportAccepted" to result.path("transportAccepted").asInt(),
        "accepted" to result.path("accepted").asInt(),
        "rejected" to result.path("rejected").asInt(),
        "notSent" to result.path("notSent").asInt(),
        "offerUnknown" to result.path("offerUnknown").asInt(),
        "resultUnknown" to result.path("resultUnknown").asInt(),
        "resultUnknownIncludesOfferUnknown" to true,
        "finishedRelativeToWindowEndNs" to (checkNotNull(result.optionalLong("finishedNs")) - windowEnd),
        "actualStartedWindowRate" to (samples.count { inWindow(it.optionalLong("startNs")) } * 1_000_000_000.0 / windowNs),
        "transportAcceptedWindowRate" to (samples.count { inWindow(it.optionalLong("transportAcceptedNs")) } * 1_000_000_000.0 / windowNs),
        "acceptedAckWindowRate" to
            (
                samples.count { it.path("outcome").asString() == "ACCEPTED" && inWindow(it.optionalLong("ackNs")) } * 1_000_000_000.0 /
                    windowNs
            ),
        "ackRtt" to
            mapOf(
                "sampleCount" to latencies.size,
                "cohortSize" to orders,
                "cohortWithoutAcceptedLatencyCount" to (orders - latencies.size),
                "notSentCount" to result.path("notSent").asInt(),
                "rejectedCount" to result.path("rejected").asInt(),
                "offerUnknownCount" to result.path("offerUnknown").asInt(),
                "resultUnknownCount" to result.path("resultUnknown").asInt(),
                "scope" to
                    "conditional accepted ACK latency; cohort without accepted latency includes known not-sent/rejected and unknown results",
                "p50Ns" to percentile(0.50),
                "p95Ns" to percentile(0.95),
                "p99Ns" to percentile(0.99),
            ),
    )
}

/** 부모 수신 시각끼리의 간격만 잰다. client 시각을 인자로 받거나 빼지 않는다. */
internal fun directAeronMarkerSummary(
    observations: List<DirectAeronMarkerObservation>,
    orders: Int,
    cutoffNs: Long,
    acceptedSequences: Map<String, Long>,
): Map<String, Any?> {
    val within = observations.filter { cutoffNs - it.receivedNs >= 0 }
    val errors = mutableListOf<String>()
    val members =
        (0..2).associateWith { member ->
            val group = within.filter { it.marker.memberId == member }.sortedBy { it.receivedNs - cutoffNs }
            val expectedIds = (0 until orders).map { "M-$it" }.toSet()
            if (group.map { it.marker.requestId }.toSet() != expectedIds ||
                group.size != orders
            ) {
                errors.add("node$member 완료 표식 전량 미확인:${group.size}/$orders")
            }
            group.forEach { observation ->
                val marker = observation.marker
                if (acceptedSequences[marker.requestId] != marker.admissionSequence || marker.tradeCount != 1L ||
                    marker.settlementCount != 1L
                ) {
                    errors.add("node$member 접수 ACK·실행 표식 불일치:${marker.requestId}")
                }
            }
            val interval = if (group.size >= 2) group.last().receivedNs - group.first().receivedNs else null
            mapOf(
                "receivedWithinDrain" to group.size,
                "firstReceivedNs" to group.firstOrNull()?.receivedNs,
                "lastReceivedNs" to group.lastOrNull()?.receivedNs,
                "interarrivalIntervalNs" to interval,
                "interarrivalRate" to interval?.takeIf { it > 0 }?.let { (group.size - 1) * 1_000_000_000.0 / it },
            )
        }
    within.groupBy { it.marker.requestId }.forEach { (requestId, group) ->
        if (group.size != 3 || group.map { it.marker.memberId }.toSet() != setOf(0, 1, 2) ||
            group.map { it.marker.admissionSequence }.distinct().size != 1 || group.map { it.marker.logPosition }.distinct().size != 1 ||
            group.count { it.marker.role == "LEADER" } != 1 || group.count { it.marker.role == "FOLLOWER" } != 2
        ) {
            errors.add("세 사본 실행 표식 불일치:$requestId")
        }
    }
    return mapOf(
        "clock" to "parent observer JVM System.nanoTime only; never subtract client clock",
        "scope" to "completion-marker arrival intervals including marker transport; not sustainable settlement TPS",
        "cutoffNs" to cutoffNs,
        "receivedAfterDrain" to (observations.size - within.size),
        "members" to members,
        "errors" to errors,
    )
}

internal data class DirectAeronCapacityRunResult(
    val runId: String,
    val offeredRate: Int,
    val planned: Int,
    val plannedWindowNs: Long,
    val reportDirectory: String,
    val clientMetrics: Map<String, Any?>?,
    val markerSummary: Map<String, Any?>?,
    val stateCheck: Map<String, Any>?,
    val invalidReasons: List<String>,
    val mode: String = "BURST_PROBE",
)

internal data class DirectAeronSustainedClientEvidence(
    val acceptedSequences: LongArray,
    val possibleExecution: BooleanArray,
    val metrics: Map<String, Any?>,
)

/** Long-mode marker raw is journaled; primitive exact indexes replace a 3N object List and Pair Set. */
internal class DirectAeronSustainedMarkerObservations(
    private val runId: String,
    private val orders: Int,
    private val file: Path,
) : AutoCloseable {
    private val boundedOrders =
        orders.also {
            require(it in 1..DIRECT_AERON_SUSTAINED_MAX_ORDERS && file.isAbsolute)
            require(runId.matches(Regex("[a-z0-9-]{1,64}")))
        }
    private val capacity = Math.multiplyExact(boundedOrders, 3)
    private val sequence = Array(3) { LongArray(orders) }
    private val position = Array(3) { LongArray(orders) }
    private val received = Array(3) { LongArray(orders) }
    private val role = Array(3) { ByteArray(orders) }
    private val mapper = jacksonObjectMapper()
    private val writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    private val failures = mutableListOf<String>()
    private var failureOverflow = 0L
    private var rows = 0
    private var observedRows = 0
    private var rawOverflow = 0L
    private var firstReceivedNs: Long? = null
    private var lastObservedNs: Long? = null
    private var expected: BooleanArray? = null
    private var expectedCopies = 0
    private var expectedReceived = 0
    private var writerFailure: String? = null
    private var firstOverflow: DirectAeronMarkerObservation? = null
    private var closed = false

    private fun error(reason: String) {
        if (failures.size < 2000) failures.add(reason.take(16_384)) else failureOverflow++
    }

    @Synchronized
    fun observe(
        marker: HttpPerformanceCompletion,
        receivedNs: Long,
    ) {
        if (marker.userId != "BUYER" || !marker.requestId.startsWith("M-")) return
        val observation = DirectAeronMarkerObservation(marker, receivedNs)
        if (observedRows == capacity) {
            rawOverflow++
            if (firstOverflow == null) firstOverflow = observation
            error("MARKER_OBSERVATION_OVERFLOW:${marker.memberId}/${marker.requestId}")
            return
        }
        observedRows++
        if (writerFailure == null) {
            try {
                val line = mapper.writeValueAsString(observation)
                require(line.length <= 16_384) { "단일 marker raw 행 초과" }
                writer.appendLine(line)
                rows++
            } catch (failure: Exception) {
                writerFailure = failure.stackTraceToString()
                error("MARKER_JOURNAL_FAILURE:$writerFailure")
            }
        }
        val index = marker.requestId.removePrefix("M-").toIntOrNull()
        if (marker.runId != runId || marker.memberId !in 0..2 || index == null || index !in 0 until orders ||
            marker.requestId != "M-$index" || marker.orderId != marker.requestId || marker.admissionSequence <= 0 ||
            marker.logPosition < 0 || (marker.role != "LEADER" && marker.role != "FOLLOWER")
        ) {
            error("INVALID_MEASURED_MARKER:${marker.memberId}/${marker.requestId}")
            return
        }
        if (marker.tradeCount != 1L ||
            marker.settlementCount != 1L
        ) {
            error("INVALID_TRADE_SETTLEMENT_COUNT:${marker.memberId}/${marker.requestId}")
        }
        lastObservedNs?.let { if (receivedNs - it < 0) error("PARENT_MARKER_CLOCK_REGRESSION") }
        lastObservedNs = receivedNs
        val member = marker.memberId
        if (sequence[member][index] != 0L) {
            error("DUPLICATE_REQUEST_MARKER:$member/${marker.requestId}")
            return
        }
        if (firstReceivedNs == null) firstReceivedNs = receivedNs
        sequence[member][index] = marker.admissionSequence
        position[member][index] = marker.logPosition
        received[member][index] = receivedNs
        role[member][index] = if (marker.role == "LEADER") 1.toByte() else 2.toByte()
        if (expected?.get(index) == true) expectedReceived++
    }

    @Synchronized
    fun complete(
        possible: BooleanArray,
        cutoffNs: Long,
    ): Boolean {
        require(possible.size == orders)
        if (expected !== possible) {
            expected = possible
            expectedCopies = 0
            expectedReceived = 0
            for (index in 0 until orders) {
                if (possible[index]) {
                    for (member in 0..2) {
                        expectedCopies++
                        if (sequence[member][index] != 0L) expectedReceived++
                    }
                }
            }
        }
        if (expectedReceived != expectedCopies) return false
        for (index in 0 until orders) {
            if (possible[index]) {
                for (member in 0..2) {
                    if (sequence[member][index] == 0L || cutoffNs - received[member][index] < 0) return false
                }
            }
        }
        return true
    }

    @Synchronized
    fun summary(
        cutoffNs: Long,
        acceptedSequences: LongArray,
    ): Map<String, Any?> {
        require(acceptedSequences.size == orders)
        val counts = IntArray(3)
        val afterDrain = IntArray(3)
        val arrivalInside = IntArray(3)
        val arrivalAfter = IntArray(3)
        val first = arrayOfNulls<Long>(3)
        val last = arrayOfNulls<Long>(3)
        val anchor = firstReceivedNs
        for (index in 0 until orders) {
            var copies = 0
            var leaders = 0
            var followers = 0
            var firstSequence = 0L
            var firstPosition = 0L
            var mismatch = false
            for (member in 0..2) {
                val current = sequence[member][index]
                if (current == 0L) continue
                val time = received[member][index]
                if (anchor != null) {
                    val elapsed = time - anchor
                    if (elapsed >= 0 && elapsed < TimeUnit.SECONDS.toNanos(60)) arrivalInside[member]++ else arrivalAfter[member]++
                }
                if (cutoffNs - time < 0) {
                    afterDrain[member]++
                    continue
                }
                counts[member]++
                if (first[member] == null || time - checkNotNull(first[member]) < 0) first[member] = time
                if (last[member] == null || time - checkNotNull(last[member]) > 0) last[member] = time
                if (acceptedSequences[index] != current) error("ACK_MARKER_SEQUENCE_MISMATCH:$member/M-$index")
                if (copies == 0) {
                    firstSequence = current
                    firstPosition = position[member][index]
                } else if (firstSequence != current || firstPosition != position[member][index]) {
                    mismatch = true
                }
                if (role[member][index] == 1.toByte()) leaders++ else followers++
                copies++
            }
            if (copies != 0 && (copies != 3 || leaders != 1 || followers != 2 || mismatch)) error("THREE_COPY_MARKER_MISMATCH:M-$index")
        }
        for (member in 0..2) if (counts[member] != orders) error("node$member 완료 표식 전량 미확인:${counts[member]}/$orders")
        return mapOf(
            "clock" to "parent observer JVM System.nanoTime only; never subtract client clock",
            "scope" to "completion-marker arrivals including transport; first-arrival window is shifted and not the client load window",
            "cutoffNs" to cutoffNs,
            "arrivalWindowAnchorNs" to anchor,
            "arrivalWindowNs" to TimeUnit.SECONDS.toNanos(60),
            "arrivalWindowScope" to
                "first valid M marker through +60s; counts include arrivals after drain and are not a full client-window success condition",
            "rawRows" to rows,
            "observedRowsWithinCapacity" to observedRows,
            "rawOverflow" to rawOverflow,
            "firstOverflow" to firstOverflow,
            "journalFailure" to writerFailure,
            "receivedAfterDrain" to afterDrain.sum(),
            "members" to
                (0..2).associateWith { member ->
                    val interval = first[member]?.let { start -> checkNotNull(last[member]) - start }
                    mapOf(
                        "receivedWithinDrain" to counts[member],
                        "receivedAfterDrain" to afterDrain[member],
                        "firstReceivedNs" to first[member],
                        "lastReceivedNs" to last[member],
                        "interarrivalIntervalNs" to interval,
                        "interarrivalRate" to interval?.takeIf { it > 0 }?.let { (counts[member] - 1) * 1_000_000_000.0 / it },
                        "arrivalWindowInside" to arrivalInside[member],
                        "arrivalWindowAfter" to arrivalAfter[member],
                    )
                },
            "errors" to
                (failures.toList() + if (failureOverflow == 0L) emptyList() else listOf("MARKER_ERROR_DETAIL_OVERFLOW:$failureOverflow")),
        )
    }

    @Synchronized
    fun rawArtifact(): Map<String, Any?> {
        check(closed)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return mapOf(
            "artifactFile" to file.fileName.toString(),
            "records" to rows,
            "bytes" to Files.size(file),
            "sha256" to HexFormat.of().formatHex(digest.digest()),
            "complete" to (writerFailure == null && rawOverflow == 0L),
            "failure" to writerFailure,
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            writer.close()
        } catch (failure: Exception) {
            writerFailure = failure.stackTraceToString()
            error("MARKER_JOURNAL_CLOSE_FAILURE:$writerFailure")
            throw failure
        }
    }
}

internal fun awaitDirectAeronSustainedMarkers(
    observations: DirectAeronSustainedMarkerObservations,
    possible: BooleanArray,
    deadlineNs: Long,
    nanoTime: () -> Long = System::nanoTime,
    pause: () -> Unit = { Thread.sleep(10) },
): Long {
    while (true) {
        val now = nanoTime()
        val expired = now - deadlineNs >= 0
        val cutoff = if (expired) deadlineNs else now
        if (observations.complete(possible, cutoff) || expired) return cutoff
        pause()
    }
}

/** Retains primitive indexes and exact latency values, never the cohort of parsed sample trees. */
internal fun readDirectAeronSustainedClientEvidence(
    report: JsonNode,
    reference: DirectAeronSustainedRawReference,
    workload: DirectAeronCapacityWorkload,
): DirectAeronSustainedClientEvidence {
    require(workload.sustained)
    val sequences = LongArray(workload.count)
    val possible = BooleanArray(workload.count) { true }
    if (reference.startupZeroRows) {
        DirectAeronSustainedRawFiles.scan(reference, workload)
        possible.fill(false)
        return DirectAeronSustainedClientEvidence(
            sequences,
            possible,
            mapOf("planned" to workload.count, "startupBeforeLoad" to true, "started" to 0, "transportAccepted" to 0, "accepted" to 0),
        )
    }
    val result = report.path("result")

    fun integer(field: String): Int = result.path(field).also { check(it.isIntegralNumber && it.canConvertToInt()) }.asInt()

    fun time(
        row: JsonNode,
        field: String,
    ): Long? =
        row.path(field).let { value ->
            check(!value.isMissingNode) { "client sample 시각 누락:$field" }
            if (value.isNull) null else value.also { check(it.isIntegralNumber && it.canConvertToLong()) }.asLong()
        }
    check(integer("planned") == workload.count)
    check(result.path("workload").path("rate").asInt() == workload.rate)
    check(result.path("workload").path("count").asInt() == workload.count)
    check(result.path("workload").path("maxInFlight").asInt() == workload.maxInFlight)
    check(result.path("workload").path("sustainedSeconds").asInt() == workload.sustainedSeconds)
    val first = checkNotNull(time(result, "firstDueNs"))
    val end = checkNotNull(time(result, "windowEndNs"))
    val finished = checkNotNull(time(result, "finishedNs"))
    check(end - first == workload.windowNs && finished - first >= 0)
    val latency = LongArray(workload.count)
    var latencyCount = 0
    var started = 0
    var offered = 0
    var accepted = 0
    var rejected = 0
    var notSent = 0
    var offerUnknown = 0
    var resultUnknown = 0
    var startedInside = 0
    var startedAfter = 0
    var offerInside = 0
    var offerAfter = 0
    var ackInside = 0
    var ackAfter = 0
    var observedAckInside = 0
    var observedAckAfter = 0
    var maximumScheduleDelayNs = 0L

    fun inside(ns: Long?): Boolean = ns != null && ns - first >= 0 && end - ns > 0

    fun after(ns: Long?): Boolean = ns != null && ns - end >= 0
    DirectAeronSustainedRawFiles.scan(reference, workload) { index, sample ->
        val id = "M-$index"
        check(time(sample, "dueNs") == first + index.toLong() * 1_000_000_000L / workload.rate)
        val start = time(sample, "startNs")
        val positive = time(sample, "transportAcceptedNs")
        val ack = time(sample, "ackNs")
        if (start != null) {
            started++
            check(start - checkNotNull(time(sample, "dueNs")) >= 0)
            maximumScheduleDelayNs = maxOf(maximumScheduleDelayNs, start - checkNotNull(time(sample, "dueNs")))
        }
        if (positive != null) {
            offered++
            val offerPosition = sample.path("transportPosition")
            check(
                positive - checkNotNull(start) >= 0 && offerPosition.isIntegralNumber && offerPosition.canConvertToLong() &&
                    offerPosition.asLong() > 0,
            )
        }
        if (ack != null) check(ack - checkNotNull(positive) >= 0)
        if (inside(start)) startedInside++
        if (after(start)) startedAfter++
        if (inside(positive)) offerInside++
        if (after(positive)) offerAfter++
        if (inside(ack)) observedAckInside++
        if (after(ack)) observedAckAfter++
        val outcome = DirectAeronCapacityOutcome.valueOf(sample.path("outcome").asString())
        val response = sample.path("response")
        if (outcome == DirectAeronCapacityOutcome.ACCEPTED || outcome == DirectAeronCapacityOutcome.REJECTED) {
            check(ack != null && positive != null)
            check(response.path("userId").asString() == "BUYER" && response.path("requestId").asString() == id)
            check(response.path("orderId").asString() == id && response.path("marketId").asString() == "BTC-USDT")
            check(checkNotNull(time(sample, "deadlineNs")) - ack > 0) { "deadline 뒤 ACK가 성공 outcome으로 바뀌었습니다:$id" }
        }
        when (outcome) {
            DirectAeronCapacityOutcome.ACCEPTED -> {
                accepted++
                check(response.path("sequence").isIntegralNumber && response.path("sequence").canConvertToLong())
                sequences[index] = response.path("sequence").asLong().also { check(it > 0) }
                latency[latencyCount++] = checkNotNull(ack) - checkNotNull(start)
                if (inside(ack)) ackInside++
                if (after(ack)) ackAfter++
            }

            DirectAeronCapacityOutcome.REJECTED -> {
                rejected++
                check(!response.path("reason").isNull && !response.path("reason").isMissingNode)
                possible[index] = false
            }

            DirectAeronCapacityOutcome.NOT_SENT -> {
                notSent++
                check(directAeronSampleProvesNotSent(sample)) { "미발송 표기와 원문 offer/ACK가 모순입니다:$id" }
                possible[index] = false
            }

            DirectAeronCapacityOutcome.OFFER_UNKNOWN -> {
                offerUnknown++
                resultUnknown++
            }

            DirectAeronCapacityOutcome.RESULT_UNKNOWN -> {
                resultUnknown++
            }
        }
    }
    check(integer("started") == started && integer("transportAccepted") == offered)
    check(integer("accepted") == accepted && integer("rejected") == rejected && integer("notSent") == notSent)
    check(integer("offerUnknown") == offerUnknown && integer("resultUnknown") == resultUnknown)
    check(integer("peakInFlight") in 0..workload.maxInFlight)
    check(accepted + rejected + notSent + resultUnknown == workload.count)
    check(result.path("errors").isArray && result.path("errorOverflow").isIntegralNumber && result.path("errorOverflow").canConvertToLong())
    check(result.path("errorOverflow").asLong() >= 0)
    val fullAccepted =
        accepted == workload.count && offered == workload.count && started == workload.count &&
            rejected == 0 && notSent == 0 && offerUnknown == 0 && resultUnknown == 0 && result.path("errors").isEmpty &&
            result.path("errorOverflow").asLong() == 0L && result.path("loadFailure").isNull && result.path("closeFailure").isNull
    java.util.Arrays.sort(latency, 0, latencyCount)

    fun percentile(fraction: Double): Long? = if (latencyCount == 0) null else latency[ceil(latencyCount * fraction).toInt() - 1]
    return DirectAeronSustainedClientEvidence(
        sequences,
        possible,
        mapOf(
            "clock" to "client JVM System.nanoTime only",
            "scope" to "60-second paced admission; ACK drain shown separately; no completion latency",
            "offeredPerSecond" to workload.rate,
            "plannedWindowNs" to workload.windowNs,
            "windowElapsed" to (finished - first >= workload.windowNs),
            "finishedRelativeToWindowEndNs" to (finished - end),
            "started" to started,
            "transportAccepted" to offered,
            "accepted" to accepted,
            "rejected" to rejected,
            "notSent" to notSent,
            "offerUnknown" to offerUnknown,
            "resultUnknown" to resultUnknown,
            "resultUnknownIncludesOfferUnknown" to true,
            "startedInsideWindow" to startedInside,
            "startedAfterWindow" to startedAfter,
            "positiveOfferInsideWindow" to offerInside,
            "positiveOfferAfterWindow" to offerAfter,
            "acceptedAckInsideWindow" to ackInside,
            "acceptedAckAfterWindow" to ackAfter,
            "observedAckInsideWindow" to observedAckInside,
            "observedAckAfterWindow" to observedAckAfter,
            "actualStartedWindowRate" to (startedInside * 1_000_000_000.0 / workload.windowNs),
            "transportAcceptedWindowRate" to (offerInside * 1_000_000_000.0 / workload.windowNs),
            "acceptedAckWindowRate" to (ackInside * 1_000_000_000.0 / workload.windowNs),
            "maximumScheduleDelayNs" to maximumScheduleDelayNs,
            "fullCohortAcceptedWithoutErrors" to fullAccepted,
            "allPlannedSendsInsideWindow" to (startedInside == workload.count && offerInside == workload.count),
            "allAcceptedAckInsideWindow" to (ackInside == workload.count),
            "ackRtt" to
                mapOf(
                    "sampleCount" to latencyCount,
                    "cohortSize" to workload.count,
                    "cohortWithoutAcceptedLatencyCount" to (workload.count - latencyCount),
                    "notSentCount" to notSent,
                    "rejectedCount" to rejected,
                    "offerUnknownCount" to offerUnknown,
                    "resultUnknownCount" to resultUnknown,
                    "scope" to "conditional accepted ACK RTT; known missing and unknown outcomes remain separate",
                    "p50Ns" to percentile(.50),
                    "p95Ns" to percentile(.95),
                    "p99Ns" to percentile(.99),
                ),
        ),
    )
}

internal fun runDirectAeronCapacityBaseline(
    options: DirectAeronCapacityOptions,
    rate: Int,
    reportRoot: Path,
): DirectAeronCapacityRunResult {
    if (options.sustained) return runDirectAeronSustainedBaseline(options, rate, reportRoot)
    require(rate in options.rates)
    val project = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
    val distribution = Path.of(requireNotNull(System.getProperty("fundingDistributionDirectory")))
    val runId = "direct${System.currentTimeMillis()}${UUID.randomUUID().toString().take(8)}"
    val reports = reportRoot.resolve(runId)
    Files.createDirectories(reports)
    val mapper = jacksonObjectMapper()
    val errors = mutableListOf<String>()
    val observations = DirectAeronMarkerObservations(runId, options.orders)
    val manifest =
        linkedMapOf<String, Any?>(
            "runId" to runId,
            "startedAt" to Instant.now().toString(),
            "kind" to "DIRECT_AERON_BURST_PROBE",
            "options" to options,
            "rate" to rate,
            "plannedOrders" to options.orders,
            "plannedWindowNs" to (TimeUnit.SECONDS.toNanos(options.orders.toLong()) / rate),
            "durationFormula" to "N / offered rate; 60-second drain is not a 60-second load",
            "sustainedThroughputClaim" to false,
            "completionLatencyCalculated" to false,
            "measurementPath" to "dedicated client container -> AeronCluster -> three trading nodes; HTTP and Gateway bypassed",
            "preparationPath" to "existing HTTP SELL preparation and BUY warmup",
            "idleGatewayPresent" to true,
            "idleGatewayEffect" to
                "Gateway remains in the environment and consumes baseline resources, but receives no measured BUY requests",
            "clientHeapOptions" to "-Xms256m -Xmx2g; client CPU quota follows the existing Compose client service",
            "generatorBudgetExperiment" to
                mapOf(
                    "previousMaxInFlight" to 1024,
                    "maxInFlight" to options.maxInFlight,
                    "maximumCohortSize" to 2000,
                    "scope" to "generator pending budget only; product Gateway, CPU, heap, codec, idle and business logic unchanged",
                ),
            "clientClock" to "separate client JVM System.nanoTime",
            "markerClock" to "parent observer JVM System.nanoTime; no cross-JVM subtraction",
            "rawArtifacts" to listOf("direct-client.json", "marker-observations.jsonl", "cluster.json", "cluster-status.json"),
        )
    var cluster: HttpPerformanceCluster? = null
    var http: HttpClient? = null
    var started = false
    var finalAttempted = false
    var clientExecution: JsonNode? = null
    var clientMetrics: Map<String, Any?>? = null
    var markerSummary: Map<String, Any?>? = null
    var stateCheck: Map<String, Any>? = null
    var cutoffNs: Long? = null
    var sourceBefore: Map<String, Any>? = null
    var distributionBefore: String? = null

    fun finalEvidence() {
        finalAttempted = true
        val current = checkNotNull(cluster)
        val (_, verified) =
            collectHttpPerformanceFinalEvidence(
                errors,
                closeObservation = {},
                capture = {
                    try {
                        current.checkpoint("final", 60_000)
                    } catch (failure: Exception) {
                        errors.add("최종 관찰 대조: ${failure.javaClass.simpleName}: ${failure.message}")
                        current.checkpoint("financial-final", 60_000, verifyObservation = false)
                    }
                },
                verify = { checkHttpPerformanceState(it, options.warmup, options.orders) },
                health = current::assertHealthy,
            )
        stateCheck = verified
        if (verified != null &&
            (verified["actualMeasuredAccepted"] != options.orders.toLong() || verified["actualMeasuredSettled"] != options.orders.toLong())
        ) {
            errors.add("최종 금융 상태에서 예정 BUY 전량의 승인·정산 미확인")
        }
    }

    try {
        sourceBefore = performanceSourceFingerprint(project, allowProductChanges = true)
        manifest["source"] = sourceBefore
        distributionBefore = performanceDistributionFingerprint(distribution)
        manifest["distributionSha256"] = distributionBefore
        val current =
            HttpPerformanceCluster(
                project,
                distribution,
                runId,
                reports,
                detailedTracing = false,
                markerPlan = HttpPerformanceMarkerPlan(runId, options.warmup, options.orders),
                onMarker = observations::observe,
            )
        cluster = current
        current.start()
        started = true
        val client =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build()
        http = client
        val prepared = options.warmup + options.orders
        current.fund("P-BUYER", "BUYER", "USDT", prepared.toLong() * 10_020 + 1_000)
        current.fund("P-SELLER", "SELLER", "BTC", prepared.toLong() * 100_000_000)
        prepareHttpPerformanceOrders(client, current.orderUri, "SELLER", "SELL", "S-", prepared)
        val sellers = awaitPerformanceCheckpoint(current, "sellers", prepared.toLong())
        check(sellers.values.all { it.path("trading").path("books").sumOf { book -> book.path("orders").size() } == prepared })
        prepareHttpPerformanceOrders(client, current.orderUri, "BUYER", "BUY", "W-", options.warmup)
        awaitPerformanceCheckpoint(current, "warmup", prepared.toLong() + options.warmup)
        current.beginMeasurement()
        manifest["parentDirectCommandStartedNs"] = System.nanoTime()
        val execution = current.runDirectLoad(rate, options.orders, "M-", "BUYER", options.maxInFlight)
        if (options.diagnosticProfiles) {
            // Includes the bridge raw scan after the client has ended; excludes financial checkpoints.
            attemptHttpPerformanceEvidence(errors, "장기 진단 프로파일 종료") { current.stopJvmProfiles() }
        }
        clientExecution = execution
        val report = execution.path("report")
        if (!execution.path("processSucceeded").asBoolean()) errors.add("직접 client 실패 종료: ${execution.path("processFailure").asString()}")
        if (!execution.path("reportFailure").isNull) errors.add("직접 client 결과 복원 실패: ${execution.path("reportFailure").asString()}")
        if (!report.path("succeeded").asBoolean()) errors.add("직접 client에서 예정 전량 ACK·무오류 조건 미충족")
        clientMetrics =
            attemptHttpPerformanceEvidence(errors, "client 접수 지표 검증") {
                directAeronClientMetrics(report.path("result"), rate, options.orders, options.maxInFlight)
            }
        val possibleExecutionIds = directAeronPossibleExecutionIds(report, options.orders)
        manifest["parentDrainExpectedRequestIds"] = possibleExecutionIds.sorted()
        manifest["parentDrainExpectedCopies"] = possibleExecutionIds.size * 3
        manifest["parentDrainBasis"] =
            "Accepted and uncertain IDs retain three-copy observation; proven NOT_SENT/REJECTED excluded only from waiting"
        if (possibleExecutionIds.isEmpty()) {
            manifest["parentDrainSkippedReason"] =
                "client proved no possible execution; full-N success and financial checks remain unchanged"
        }
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        // pause may throw after the deadline. Preserve the boundary before calling it so finally cannot use a later now.
        cutoffNs = deadlineNs
        manifest["parentDrainDeadlineNs"] = deadlineNs
        val cutoff = awaitDirectAeronMarkers(observations, possibleExecutionIds, deadlineNs)
        cutoffNs = cutoff
        if (!observations.complete(possibleExecutionIds, cutoff)) errors.add("부모 관측 drain 경계 안에서 실행 가능 요청의 세 노드 표식 미확인")
        if (!observations.complete((0 until options.orders).map { "M-$it" }.toSet(), cutoff)) {
            errors.add("부모 관측 drain 경계 안에서 세 노드의 예정 BUY 표식 전량 미확인")
        }
        finalEvidence()
    } catch (failure: Exception) {
        errors.add("직접 Aeron 실행: ${failure.stackTraceToString()}")
    } finally {
        if (cutoffNs == null) cutoffNs = System.nanoTime()
        if (started && !finalAttempted) attemptHttpPerformanceEvidence(errors, "실패 뒤 최종 금융 상태 시도", ::finalEvidence)
        attemptHttpPerformanceEvidence(errors, "준비 HTTP client 종료") { http?.close() }
        attemptHttpPerformanceEvidence(errors, "직접 Aeron 클러스터 종료") { cluster?.close() }
        val raw = observations.snapshot()
        errors.addAll(observations.errors())
        val sequences =
            clientExecution
                ?.path("report")
                ?.path("result")
                ?.path("samples")
                ?.filter { it.path("outcome").asString() == "ACCEPTED" }
                ?.associate { it.path("command").path("requestId").asString() to it.path("response").path("sequence").asLong() }
                .orEmpty()
        val observedSummary = directAeronMarkerSummary(raw, options.orders, checkNotNull(cutoffNs), sequences)
        markerSummary = observedSummary
        @Suppress("UNCHECKED_CAST")
        errors.addAll(observedSummary.getValue("errors") as List<String>)
        attemptHttpPerformanceEvidence(errors, "직접 client raw 보존") {
            Files.writeString(
                reports.resolve("direct-client.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(clientExecution),
            )
        }
        attemptHttpPerformanceEvidence(errors, "부모 표식 raw 보존") {
            Files.newBufferedWriter(reports.resolve("marker-observations.jsonl")).use { writer ->
                raw.forEach { writer.appendLine(mapper.writeValueAsString(it)) }
            }
        }
        manifest["endedAt"] = Instant.now().toString()
        manifest["finalEvidenceAttempted"] = finalAttempted
        manifest["parentMarkerCutoffNs"] = cutoffNs
        manifest["cluster"] = cluster?.manifest()
        manifest["clientMetrics"] = clientMetrics
        manifest["markerSummary"] = markerSummary
        manifest["stateCheck"] = stateCheck
        if (sourceBefore != null && distributionBefore != null) {
            checkHttpPerformancePostFingerprints(
                manifest,
                errors,
                checkNotNull(sourceBefore),
                distributionBefore,
                readSource = { performanceSourceFingerprint(project, allowProductChanges = true) },
                readDistribution = { performanceDistributionFingerprint(distribution) },
            )
        }
        manifest["invalidReasons"] = errors.distinct()
        attemptHttpPerformanceEvidence(errors, "직접 Aeron manifest 보존") {
            Files.writeString(reports.resolve("manifest.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest))
        }
    }
    val result =
        DirectAeronCapacityRunResult(
            runId,
            rate,
            options.orders,
            TimeUnit.SECONDS.toNanos(options.orders.toLong()) / rate,
            reports.toString(),
            clientMetrics,
            markerSummary,
            stateCheck,
            errors.distinct(),
        )
    attemptHttpPerformanceEvidence(errors, "직접 Aeron summary 보존") {
        Files.writeString(reports.resolve("summary.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))
    }
    return result.copy(invalidReasons = errors.distinct())
}

/** One additional EOF/SHA metadata pass per node after Cluster's exact copy checks, not another copy comparison. */
internal fun awaitDirectAeronPreparationFiles(
    cluster: HttpPerformanceCluster,
    label: String,
    completions: Long,
    bookOrders: Long,
    timeoutMs: Long = 120_000,
): Map<Int, HttpPerformanceCheckpointScan> {
    require(timeoutMs in 1..cluster.checkpointCaptureTimeoutCeilingMs)
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var attempt = 0
    while (true) {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        check(remaining > 0) { "$label 준비 완료 $completions 확인 기한 초과" }
        val references = cluster.checkpointFiles("$label-${attempt++}", remaining.coerceAtMost(timeoutMs))
        val scans = references.mapValues { (_, reference) -> readHttpPerformanceCheckpointFileObservation(reference).scan }
        if (scans.values.all { it.rowCounts["/trading/completions"] == completions && it.bookOrderCount == bookOrders }) return scans
        Thread.sleep(50)
    }
}

internal fun directAeronSustainedFinancialCohortComplete(
    state: Map<String, Any>?,
    orders: Int,
): Boolean = state != null && state["actualMeasuredAccepted"] == orders.toLong() && state["actualMeasuredSettled"] == orders.toLong()

internal fun runDirectAeronSustainedBaseline(
    options: DirectAeronCapacityOptions,
    rate: Int,
    reportRoot: Path,
): DirectAeronCapacityRunResult {
    require(options.sustained && rate in options.rates)
    val evidenceTimeBudget = directAeronEvidenceTimeBudget(options)
    val workload =
        parseDirectAeronCapacityClientArguments(
            arrayOf("--sustained", "--rate=$rate", "--seconds=${options.sustainedSeconds}", "M-", "BUYER", "4096"),
        )
    val project = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
    val distribution = Path.of(requireNotNull(System.getProperty("fundingDistributionDirectory")))
    val runId = "direct${System.currentTimeMillis()}${UUID.randomUUID().toString().take(8)}"
    val reports = reportRoot.resolve(runId)
    Files.createDirectories(reports)
    val mapper = jacksonObjectMapper()
    val errors = mutableListOf<String>()
    val manifest =
        linkedMapOf<String, Any?>(
            "runId" to runId,
            "startedAt" to Instant.now().toString(),
            "kind" to
                if (options.diagnosticProfiles) "DIRECT_AERON_SUSTAINED_DIAGNOSTIC" else "DIRECT_AERON_SUSTAINED_60S_PACED_ADMISSION",
            "diagnosticScope" to
                if (options.diagnosticProfiles) {
                    "JFR plus CnC sampling; profiler overhead included; never an unprofiled capacity result"
                } else {
                    null
                },
            "options" to options,
            "postLoadEvidenceTimeBudget" to evidenceTimeBudget.fields(),
            "rate" to rate,
            "plannedOrders" to options.orders,
            "plannedWindowNs" to workload.windowNs,
            "completionLatencyCalculated" to false,
            "maximumSustainableThroughputClaim" to false,
            "measurementPath" to "dedicated client -> AeronCluster -> three nodes; HTTP and Gateway bypassed",
            "preparationPath" to "existing HTTP SELL(N+W) and BUY(W); may take many tens of seconds or longer, excluded from measured rate",
            "idleGatewayPresent" to true,
            "detailedTracing" to false,
            "resources" to "unchanged Compose CPU/heap and sync 2/2/2; client -Xms256m -Xmx2g; generator pending4096",
            "clientClock" to "client JVM nanoTime firstDue/windowEnd, starts/offers/ACK inside and after kept separately",
            "markerClock" to "parent JVM nanoTime; first M arrival anchors a shifted 60s arrival observation only",
            "rawMarkerWriter" to
                "synchronous bounded-row JSONL on observer thread; its I/O cost is included in observation, no zero-overhead claim",
            "rawRetention" to "full per-ID client JSONL and per-copy parent marker JSONL plus exact finite indexes; no heap-fit guarantee",
            "successCriterion" to
                "all planned starts/positive offers inside client 60s; all ACK and three-copy/financial checks by their separate drain cutoffs",
            "clientAckInsideWindow" to "separate count/flag; drain ACKs are preserved and never added to the client-window ACK rate",
            "remainingCloseCost" to
                "existing Cluster.close retains a typed full receiver marker sorted List for cluster.json; no global zero-copy claim",
        )
    var cluster: HttpPerformanceCluster? = null
    var http: HttpClient? = null
    var observations: DirectAeronSustainedMarkerObservations? = null
    var started = false
    var finalAttempted = false
    var clientExecution: JsonNode? = null
    var clientMetrics: Map<String, Any?>? = null
    var markerSummary: Map<String, Any?>? = null
    var stateCheck: Map<String, Any>? = null
    var cutoffNs: Long? = null
    var sequences = LongArray(options.orders)
    var sourceBefore: Map<String, Any>? = null
    var distributionBefore: String? = null

    fun finalEvidence() {
        finalAttempted = true
        val current = checkNotNull(cluster)
        if (options.diagnosticProfiles) {
            attemptHttpPerformanceEvidence(errors, "금융 수집 전 진단 프로파일 종료") { current.stopJvmProfiles() }
        }
        val (_, verified) =
            collectHttpPerformanceFinalEvidence(
                errors,
                closeObservation = {},
                capture = {
                    try {
                        current.checkpointFiles("final", evidenceTimeBudget.checkpointCaptureMaxMs)
                    } catch (failure: Exception) {
                        errors.add("최종 관찰 files 대조: ${failure.javaClass.simpleName}: ${failure.message}")
                        current.checkpointFiles("financial-final", evidenceTimeBudget.checkpointCaptureMaxMs, verifyObservation = false)
                    }
                },
                verify = { checkHttpPerformanceStateFiles(it, options.warmup, options.orders, maxOrders = 600_000) },
                health = current::assertHealthy,
            )
        stateCheck = verified
        if (!directAeronSustainedFinancialCohortComplete(verified, options.orders)) {
            errors.add("최종 금융 files 상태에서 예정 BUY 전량의 승인·정산 미확인")
        }
    }
    try {
        sourceBefore = performanceSourceFingerprint(project, allowProductChanges = true)
        manifest["source"] = sourceBefore
        distributionBefore = performanceDistributionFingerprint(distribution)
        manifest["distributionSha256"] = distributionBefore
        // This explicit count ceiling is not a resource-fit or sustained-capacity guarantee.
        HttpPerformanceFinancialBounds(options.warmup, options.orders, maximumOrders = 600_000)
        manifest["financialMaximumOrders"] = 600_000
        manifest["financialBoundsPreflightPassed"] = true
        val observed = DirectAeronSustainedMarkerObservations(runId, options.orders, reports.resolve("marker-observations.jsonl"))
        observations = observed
        val current =
            HttpPerformanceCluster(
                project,
                distribution,
                runId,
                reports,
                sampleRuntimeCounters = options.diagnosticProfiles,
                detailedTracing = false,
                preserveNodeRecordings = options.preserveNodeRecordings,
                evidenceTimeBudget = evidenceTimeBudget,
                markerPlan = HttpPerformanceMarkerPlan(runId, options.warmup, options.orders),
                onMarker = observed::observe,
            )
        cluster = current
        current.start()
        started = true
        val client =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build()
        http = client
        val prepared = Math.addExact(options.warmup, options.orders)
        manifest["preparationStartedAt"] = Instant.now().toString()
        manifest["preparationHttpBatchSize"] = mapOf("sellers" to 256, "warmupBuy" to 200)
        current.fund("P-BUYER", "BUYER", "USDT", Math.addExact(Math.multiplyExact(prepared.toLong(), 10_020L), 1_000))
        current.fund("P-SELLER", "SELLER", "BTC", Math.multiplyExact(prepared.toLong(), 100_000_000L))
        prepareHttpPerformanceOrders(client, current.orderUri, "SELLER", "SELL", "S-", prepared, batchSize = 256)
        awaitDirectAeronPreparationFiles(
            current,
            "sellers",
            prepared.toLong(),
            prepared.toLong(),
            evidenceTimeBudget.preparationObservationMs,
        )
        prepareHttpPerformanceOrders(client, current.orderUri, "BUYER", "BUY", "W-", options.warmup, batchSize = 200)
        awaitDirectAeronPreparationFiles(
            current,
            "warmup",
            (prepared + options.warmup).toLong(),
            options.orders.toLong(),
            evidenceTimeBudget.preparationObservationMs,
        )
        manifest["preparationEndedAt"] = Instant.now().toString()
        if (options.diagnosticProfiles) current.startJvmProfiles(maxSizeMb = 64, maxAgeSeconds = 180)
        current.beginMeasurement()
        manifest["parentDirectCommandStartedNs"] = System.nanoTime()
        val execution =
            try {
                current.runDirectLoadSustained(rate, checkNotNull(options.sustainedSeconds), "M-", "BUYER", 4096)
            } catch (failure: Exception) {
                errors.add("장기 client bridge 예외; 가능한 실행 ID를 보수적으로 drain:${failure.stackTraceToString()}")
                mapper.valueToTree<JsonNode>(
                    mapOf(
                        "processSucceeded" to false,
                        "processFailure" to failure.toString(),
                        "reportFailure" to null,
                        "rawFailure" to null,
                        "report" to null,
                    ),
                )
            }
        if (options.diagnosticProfiles) {
            // Includes the bridge raw scan after the client has ended; excludes financial checkpoints.
            attemptHttpPerformanceEvidence(errors, "장기 진단 프로파일 종료") { current.stopJvmProfiles() }
        }
        clientExecution = execution
        val report = execution.path("report")
        if (!execution.path("processSucceeded").asBoolean()) errors.add("장기 client 실패 종료:${execution.path("processFailure").asString()}")
        if (!execution.path("reportFailure").isNull) errors.add("장기 client summary 복원 실패:${execution.path("reportFailure").asString()}")
        if (!execution.path("rawFailure").isNull) errors.add("장기 client raw 검사 실패:${execution.path("rawFailure").asString()}")
        if (!report.path("succeeded").asBoolean()) errors.add("장기 client 예정 전량 ACK·창 안 전송·무오류 조건 미충족")
        var possible = BooleanArray(options.orders) { true }
        val evidence =
            attemptHttpPerformanceEvidence(errors, "장기 client raw/지표/index 검사") {
                val raw = execution.path("rawEvidence")
                check(raw.path("verified").asBoolean()) { "부모 raw EOF/SHA 검사 미완료" }
                val rawFile = reports.resolve(raw.path("artifactFile").asString()).toAbsolutePath().normalize()
                check(rawFile.startsWith(reports.toAbsolutePath().normalize().resolve("control"))) { "실행 소유 밖 client raw 경로" }
                val directory = rawFile.parent
                val reference = DirectAeronSustainedRawFiles.resolve(directory, report, runId, workload)
                readDirectAeronSustainedClientEvidence(report, reference, workload)
            }
        if (evidence != null) {
            sequences = evidence.acceptedSequences
            possible = evidence.possibleExecution
            clientMetrics = evidence.metrics
            if (evidence.metrics["allPlannedSendsInsideWindow"] != true || evidence.metrics["windowElapsed"] != true) {
                errors.add("같은 client 시계에서 60초 경과·예정 N건의 창 안 시작/전송 미확인")
            }
            if (evidence.metrics["fullCohortAcceptedWithoutErrors"] != true) errors.add("장기 raw에서 예정 N건 전량 ACK·무오류 미확인")
        } else if (directAeronClientHadNoAcceptedOffer(report, options.orders)) {
            possible.fill(false)
        }
        manifest["parentDrainExpectedCopies"] = possible.count { it } * 3
        manifest["parentDrainBasis"] =
            "exact per-ID raw outcomes; only proven NOT_SENT/REJECTED excluded; unknown/invalid raw retains all possible IDs"
        if (possible.none { it }) {
            manifest["parentDrainSkippedReason"] =
                "no possible execution proven; full-N and financial success remain required"
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        cutoffNs = deadline
        manifest["parentDrainDeadlineNs"] = deadline
        cutoffNs = awaitDirectAeronSustainedMarkers(observed, possible, deadline)
        if (!observed.complete(possible, checkNotNull(cutoffNs))) errors.add("장기 parent drain 안 실행 가능 ID의 세 사본 표식 미확인")
        if (!observed.complete(
                BooleanArray(options.orders) { true },
                checkNotNull(cutoffNs),
            )
        ) {
            errors.add("장기 parent drain 안 예정 N건 세 사본 표식 전량 미확인")
        }
        finalEvidence()
    } catch (failure: Exception) {
        errors.add("장기 직접 Aeron 실행:${failure.stackTraceToString()}")
    } finally {
        if (cutoffNs == null) cutoffNs = System.nanoTime()
        if (started && !finalAttempted) attemptHttpPerformanceEvidence(errors, "장기 실패 뒤 최종 금융 files 시도", ::finalEvidence)
        attemptHttpPerformanceEvidence(errors, "준비 HTTP client 종료") { http?.close() }
        attemptHttpPerformanceEvidence(errors, "장기 직접 클러스터 종료") { cluster?.close() }
        attemptHttpPerformanceEvidence(errors, "장기 marker raw 종료") { observations?.close() }
        val observed = observations
        if (observed != null) {
            markerSummary =
                attemptHttpPerformanceEvidence(errors, "장기 marker exact 대조") { observed.summary(checkNotNull(cutoffNs), sequences) }
            @Suppress("UNCHECKED_CAST")
            errors.addAll((markerSummary?.get("errors") as? List<String>).orEmpty())
            manifest["markerRawArtifact"] = attemptHttpPerformanceEvidence(errors, "장기 marker raw 지문") { observed.rawArtifact() }
        }
        attemptHttpPerformanceEvidence(errors, "장기 client summary 보존") {
            Files.newOutputStream(reports.resolve("direct-client-report.json")).use {
                mapper.writerWithDefaultPrettyPrinter().writeValue(it, clientExecution)
            }
        }
        manifest["endedAt"] = Instant.now().toString()
        manifest["finalEvidenceAttempted"] = finalAttempted
        manifest["parentMarkerCutoffNs"] = cutoffNs
        manifest["cluster"] = attemptHttpPerformanceEvidence(errors, "장기 cluster manifest 수집") { cluster?.manifest() }
        manifest["clientMetrics"] = clientMetrics
        manifest["markerSummary"] = markerSummary
        manifest["stateCheck"] = stateCheck
        if (sourceBefore != null && distributionBefore != null) {
            checkHttpPerformancePostFingerprints(
                manifest,
                errors,
                checkNotNull(sourceBefore),
                distributionBefore,
                readSource = { performanceSourceFingerprint(project, allowProductChanges = true) },
                readDistribution = { performanceDistributionFingerprint(distribution) },
            )
        }
        manifest["invalidReasons"] = errors.distinct()
        attemptHttpPerformanceEvidence(errors, "장기 manifest 보존") {
            Files.newOutputStream(reports.resolve("manifest.json")).use { mapper.writerWithDefaultPrettyPrinter().writeValue(it, manifest) }
        }
    }
    val result =
        DirectAeronCapacityRunResult(
            runId,
            rate,
            options.orders,
            workload.windowNs,
            reports.toString(),
            clientMetrics,
            markerSummary,
            stateCheck,
            errors.distinct(),
            if (options.diagnosticProfiles) "SUSTAINED_DIAGNOSTIC_NOT_CAPACITY" else "SUSTAINED_60S_PACED_ADMISSION",
        )
    attemptHttpPerformanceEvidence(errors, "장기 summary 보존") {
        Files.newOutputStream(reports.resolve("summary.json")).use { mapper.writerWithDefaultPrettyPrinter().writeValue(it, result) }
    }
    return result.copy(invalidReasons = errors.distinct())
}

internal fun directAeronCapacityReport(results: List<DirectAeronCapacityRunResult>): String =
    buildString {
        appendLine("# 직접 Aeron 접수 burst 탐색")
        appendLine()
        appendLine("각 회차의 예정 창은 N/rate다. 60초 drain을 포함해 기록하지만 60초 지속 부하나 최대 TPS를 입증하지 않는다.")
        appendLine("측정 BUY는 별도 client 컨테이너에서 Aeron으로 직접 보낸다. HTTP SELL 준비·BUY 예열과 idle Gateway가 환경에 남는다.")
        appendLine("ACK 지연·실제 전송률은 client JVM 시계다. 완료 표식 도착 간격은 부모 JVM 시계이며 두 시계를 빼거나 완료 지연으로 표시하지 않는다.")
        appendLine()
        appendLine("| 예정 BUY/s | 예정 N | 예정 창 ms | 접수 ACK | client 창 안 실제 시작/s | client 창 안 전송 수용/s | 금융 정산 | 판정 |")
        appendLine("|---:|---:|---:|---:|---:|---:|---:|---|")
        results.forEach { result ->
            appendLine(
                "| ${result.offeredRate} | ${result.planned} | ${result.plannedWindowNs / 1_000_000.0} | " +
                    "${result.clientMetrics?.get("accepted")} | ${result.clientMetrics?.get("actualStartedWindowRate")} | " +
                    "${result.clientMetrics?.get("transportAcceptedWindowRate")} | ${result.stateCheck?.get("actualMeasuredSettled")} | " +
                    "${if (result.invalidReasons.isEmpty()) "유효 burst" else "무효 (${result.invalidReasons.size}개; summary.json 참조)"} |",
            )
        }
        appendLine()
        appendLine(
            "client ACK 분위수·세 노드 표식별 도착 간격·drain 뒤 도착은 실행별 summary에 분리했다. 원문 client 결과와 부모 표식 JSONL, 세 사본 금융 checkpoint와 종료 증거를 보존한다.",
        )
    }

internal fun directAeronSustainedCapacityReport(results: List<DirectAeronCapacityRunResult>): String =
    buildString {
        appendLine("# 직접 Aeron 고정 60초 paced 접수")
        appendLine()
        appendLine("SELL 준비·BUY 예열은 측정 전에 별도 HTTP로 수행하며 수십 초 이상 걸릴 수 있다. 측정 BUY는 직접 Aeron 경로다.")
        appendLine("client 자기 시계의 60초 창 안 시작·positive offer·ACK와 창 뒤 ACK drain을 구분한다. drain 뒤 금융 전량은 창 안 완료 TPS와 다르다.")
        appendLine("부모 첫 M 표식 이후60초는 shifted arrival 관측창이다. client 창과 시각을 빼거나 같은 경계로 해석하지 않는다.")
        appendLine()
        appendLine("| 예정/s | N | client 창 안 offer | 창 안 ACK | 창 뒤 ACK | 최종 금융 정산 | 판정 |")
        appendLine("|---:|---:|---:|---:|---:|---:|---|")
        results.forEach { result ->
            appendLine(
                "| ${result.offeredRate} | ${result.planned} | ${result.clientMetrics?.get("positiveOfferInsideWindow")} | " +
                    "${result.clientMetrics?.get("acceptedAckInsideWindow")} | ${result.clientMetrics?.get("acceptedAckAfterWindow")} | " +
                    "${result.stateCheck?.get(
                        "actualMeasuredSettled",
                    )} | ${if (result.invalidReasons.isEmpty()) "조건 충족" else "무효; summary 참조"} |",
            )
        }
        appendLine()
        appendLine("한 회차로 최대 TPS나 N600k 자원 적합성을 보장하지 않는다. raw JSONL·파일 SHA·세 사본 전체 금융 검사와 실패 근거는 실행별 보고서에 남긴다.")
    }

fun main(args: Array<String>) {
    val options = parseDirectAeronCapacityOptions(args)
    val project = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
    val reports =
        project.resolve(
            "build/reports/direct-aeron-capacity/${if (options.sustained) {
                "sustained"
            } else if (options.smoke) {
                "smoke"
            } else {
                "probe"
            }}-${System.currentTimeMillis()}",
        )
    Files.createDirectories(reports)
    val mapper = jacksonObjectMapper()
    val results = mutableListOf<DirectAeronCapacityRunResult>()
    val reportErrors = mutableListOf<String>()
    val label = if (options.sustained) "DIRECT_AERON_SUSTAINED" else "DIRECT_AERON_BURST"
    for (rate in options.rates) {
        println("$label rate=$rate warmup=${options.warmup} orders=${options.orders}")
        val result = runDirectAeronCapacityBaseline(options, rate, reports)
        results.add(result)
        attemptHttpPerformanceEvidence(reportErrors, "직접 Aeron matrix 보존") {
            Files.writeString(reports.resolve("matrix.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results))
        }
        println("$label run=${result.runId} invalid=${result.invalidReasons}")
        if (result.clientMetrics == null) break
    }
    attemptHttpPerformanceEvidence(reportErrors, "직접 Aeron report 보존") {
        Files.writeString(
            reports.resolve("report.md"),
            if (options.sustained) directAeronSustainedCapacityReport(results) else directAeronCapacityReport(results),
        )
    }
    println("$label reports=$reports")
    check(results.size == options.rates.size && results.all { it.invalidReasons.isEmpty() } && reportErrors.isEmpty()) {
        "$label 실행에 무효 회차 또는 보존 오류가 있습니다: $reports $reportErrors"
    }
}
