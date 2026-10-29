package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

internal const val HTTP_SUSTAINED_RAW_NAME = "http-client-samples.jsonl"
private const val HTTP_RAW_MAX_LINE_BYTES = 65_536
private const val HTTP_SUSTAINED_MAX_WINDOW_NS = 60_000_000_000L

internal data class HttpPerformanceSustainedRawReference(
    val file: Path,
    val runId: String,
    val records: Int,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(file.isAbsolute && file.fileName.toString() == HTTP_SUSTAINED_RAW_NAME)
        require(runId.matches(Regex("[a-zA-Z0-9-]{1,70}")) && records in 1..60_000)
        require(bytes > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/** These counts share the HTTP load/receiver JVM clock. They never use a node clock or shifted marker anchor. */
internal data class HttpPerformanceSustainedWindowEvidence(
    val records: Int,
    val firstDueNs: Long,
    val windowEndNs: Long,
    val cutoffNs: Long,
    val loopStoppedNs: Long?,
    val startsInsideWindow: Int,
    val startsOutsideWindow: Int,
    val acceptedAcksInsideWindow: Int,
    val acceptedAcksAtOrAfterWindow: Int,
    val acceptedAcksAfterCutoff: Int,
    val leaderCompletionsInsideWindow: Int,
    val leaderCompletionsAtOrAfterWindow: Int,
    val leaderCompletionsAfterCutoff: Int,
    val unsent: Int,
    val unknownHttp: Int,
    val member0Observed: Int,
    val member1Observed: Int,
    val member2Observed: Int,
    val allCopiesObserved: Int,
    val allCopiesMissing: Int,
    val allCopiesInsideWindow: Int,
    val allCopiesAtOrAfterWindow: Int,
    val allCopiesByCutoff: Int,
    val allCopiesAfterCutoff: Int,
) {
    val windowElapsed: Boolean get() = loopStoppedNs?.let { it - firstDueNs >= windowEndNs - firstDueNs } == true
}

internal data class HttpPerformanceSustainedRawArtifact(
    val file: Path,
    val runId: String,
    val recordsWritten: Int,
    val bytesObserved: Long?,
    val reference: HttpPerformanceSustainedRawReference?,
    val window: HttpPerformanceSustainedWindowEvidence?,
    val failure: String?,
) {
    val complete: Boolean get() = failure == null && reference != null && window?.records == reference.records

    fun fields(): Map<String, Any?> =
        mapOf(
            "kind" to "HTTP_CAPACITY_SAMPLES_JSONL",
            "schemaVersion" to 2,
            "artifactFile" to file.fileName.toString(),
            "recordsWritten" to recordsWritten,
            "bytesObserved" to bytesObserved,
            "records" to reference?.records,
            "bytes" to reference?.bytes,
            "sha256" to reference?.sha256,
            "runId" to runId,
            "complete" to complete,
            "failure" to failure,
            "window" to window,
            "scope" to (
                "all planned legacy leader samples plus first receive times for members 0/1/2 and wrap-safe allCopiesDoneNs; " +
                    "not full wire response bytes or a financial verdict"
            ),
        )
}

/** Test-source file writer/reader. Exact rows remain on disk; only one current parsed row is retained. */
internal object HttpPerformanceSustainedRawFiles {
    private val mapper =
        JsonMapper
            .builder(
                JsonFactory
                    .builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                        StreamReadConstraints
                            .builder()
                            .maxNestingDepth(
                                32,
                            ).maxNameLength(256)
                            .maxStringLength(HTTP_RAW_MAX_LINE_BYTES)
                            .maxNumberLength(128)
                            .build(),
                    ).build(),
            ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
    private val sampleFields =
        setOf(
            "userId",
            "requestId",
            "orderId",
            "dueNs",
            "startNs",
            "ackNs",
            "httpResultNs",
            "httpOutcome",
            "httpDetail",
            "httpTimeliness",
            "admissionSequence",
            "doneNs",
            "completionMemberId",
            "tradeCount",
            "settlementCount",
            "notSentReason",
            "notSentNs",
            "generatorDelayNs",
            "ackRttNs",
            "completionLatencyNs",
            "scheduledAckLatencyNs",
            "scheduledCompletionLatencyNs",
            "completionTimeliness",
        )
    private val nullableLongs =
        listOf(
            "startNs",
            "ackNs",
            "httpResultNs",
            "admissionSequence",
            "doneNs",
            "notSentNs",
            "generatorDelayNs",
            "ackRttNs",
            "completionLatencyNs",
            "scheduledAckLatencyNs",
            "scheduledCompletionLatencyNs",
        )

    fun publish(
        file: Path,
        runId: String,
        planned: Int,
        firstDueNs: Long,
        windowEndNs: Long,
        cutoffNs: Long,
        loopStoppedNs: Long?,
        visitSamples: ((HttpPerformanceSustainedRequestObservation) -> Unit) -> Int,
    ): HttpPerformanceSustainedRawArtifact {
        var written = 0
        try {
            require(file.isAbsolute && file.fileName.toString() == HTTP_SUSTAINED_RAW_NAME && planned in 1..60_000)
            require(windowEndNs - firstDueNs in planned.toLong() * 1_000_000L..HTTP_SUSTAINED_MAX_WINDOW_NS)
            require(cutoffNs - windowEndNs in 0L..HTTP_SUSTAINED_MAX_WINDOW_NS)
            interrupted()
            val writerMapper = jacksonObjectMapper()
            Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
                val count =
                    visitSamples { sample ->
                        interrupted()
                        check(written < planned) {
                            "HTTP raw 예정 수보다 많은 visitor 행"
                        }
                        val line =
                            writerMapper.writeValueAsString(
                                mapOf(
                                    "kind" to "HTTP_CAPACITY_SAMPLE",
                                    "schemaVersion" to 2,
                                    "runId" to runId,
                                    "index" to written,
                                    "sample" to sample.sample,
                                    "completionCopies" to sample.completionCopies,
                                ),
                            )
                        require(line.toByteArray(StandardCharsets.UTF_8).size <= HTTP_RAW_MAX_LINE_BYTES) {
                            "HTTP raw 단일 행 상한 초과"
                        }
                        writer.appendLine(line)
                        written = Math.incrementExact(written)
                    }
                check(count == planned && written == planned) {
                    "HTTP raw 예정 전체 행 누락: $written/$planned, visitor=$count"
                }
            }
            interrupted()
            val reference = HttpPerformanceSustainedRawReference(file, runId, planned, Files.size(file), sha(file))
            val window = scan(reference, firstDueNs, windowEndNs, cutoffNs, loopStoppedNs)
            return HttpPerformanceSustainedRawArtifact(file, runId, written, reference.bytes, reference, window, null)
        } catch (failure: Exception) {
            val bytes = runCatching { if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.size(file) else null }.getOrNull()
            return HttpPerformanceSustainedRawArtifact(file, runId, written, bytes, null, null, failure.stackTraceToString())
        }
    }

    fun scan(
        reference: HttpPerformanceSustainedRawReference,
        firstDueNs: Long,
        windowEndNs: Long,
        cutoffNs: Long,
        loopStoppedNs: Long?,
    ): HttpPerformanceSustainedWindowEvidence {
        interrupted()
        require(windowEndNs - firstDueNs in reference.records.toLong() * 1_000_000L..HTTP_SUSTAINED_MAX_WINDOW_NS)
        require(cutoffNs - windowEndNs in 0L..HTTP_SUSTAINED_MAX_WINDOW_NS)
        require(Files.isRegularFile(reference.file, LinkOption.NOFOLLOW_LINKS) && Files.size(reference.file) == reference.bytes)
        val counter = WindowCounter(firstDueNs, windowEndNs, cutoffNs, loopStoppedNs)
        val digest = MessageDigest.getInstance("SHA-256")
        val pending = ByteArrayOutputStream(4096)
        var bytes = 0L
        var records = 0
        Files.newInputStream(reference.file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                interrupted()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                bytes = Math.addExact(bytes, count.toLong())
                digest.update(buffer, 0, count)
                var start = 0
                for (index in 0 until count) {
                    if (buffer[index] == '\n'.code.toByte()) {
                        append(pending, buffer, start, index - start)
                        check(pending.size() > 0 && records < reference.records) {
                            "HTTP raw 빈 행 또는 추가 행"
                        }
                        val decoder =
                            StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(
                                    CodingErrorAction.REPORT,
                                ).onUnmappableCharacter(CodingErrorAction.REPORT)
                        val row = mapper.readTree(decoder.decode(ByteBuffer.wrap(pending.toByteArray())).toString())
                        check(
                            row.isObject &&
                                row.properties().map { it.key }.toSet() ==
                                setOf("kind", "schemaVersion", "runId", "index", "sample", "completionCopies"),
                        ) {
                            "HTTP raw envelope 필드 불일치"
                        }
                        check(text(row, "kind") == "HTTP_CAPACITY_SAMPLE" && number(row, "schemaVersion") == 2L)
                        check(text(row, "runId") == reference.runId && number(row, "index") == records.toLong()) {
                            "HTTP raw run/index 불일치"
                        }
                        val sample = row.path("sample")
                        check(sample.isObject && sample.properties().map { it.key }.toSet() == sampleFields) {
                            "HTTP raw 전체 sample 필드 누락/변경"
                        }
                        check(
                            text(sample, "userId") == "BUYER" && text(sample, "requestId") == "M-$records" &&
                                text(sample, "orderId") == "M-$records",
                        )
                        check(number(sample, "dueNs") == firstDueNs + records.toLong() * 1_000_000L) {
                            "HTTP raw 고정 예정 시각 불일치"
                        }
                        nullableLongs.forEach { nullableNumber(sample, it) }
                        for (field in listOf("httpDetail", "notSentReason")) check(sample.path(field).isNull || sample.path(field).isString)
                        check(
                            sample.path("httpOutcome").isNull ||
                                (
                                    sample.path("httpOutcome").isString &&
                                        sample.path("httpOutcome").asString() in HttpPerformanceOutcome.entries.map { it.name }
                                ),
                        )
                        check(text(sample, "httpTimeliness") in setOf("WITHIN_DRAIN", "AFTER_DRAIN", "NOT_STARTED", "UNKNOWN"))
                        check(text(sample, "completionTimeliness") in setOf("ON_TIME", "LATE", "AFTER_DRAIN", "UNKNOWN"))
                        nullableNumber(sample, "completionMemberId")?.let { check(it in 0L..2L) }
                        check(number(sample, "tradeCount") >= 0 && number(sample, "settlementCount") >= 0)
                        val copyNode = row.path("completionCopies")
                        check(
                            copyNode.isObject &&
                                copyNode.properties().map { it.key }.toSet() ==
                                setOf("member0ReceivedNs", "member1ReceivedNs", "member2ReceivedNs", "allCopiesDoneNs"),
                        ) {
                            "HTTP raw 세 노드 수신 시각 필드 누락/변경"
                        }
                        val copies =
                            httpPerformanceCompletionCopiesSample(
                                firstDueNs,
                                nullableNumber(copyNode, "member0ReceivedNs"),
                                nullableNumber(copyNode, "member1ReceivedNs"),
                                nullableNumber(copyNode, "member2ReceivedNs"),
                            )
                        check(nullableNumber(copyNode, "allCopiesDoneNs") == copies.allCopiesDoneNs) {
                            "HTTP raw 세 노드 전체 완료 시각 불일치"
                        }
                        val leaderDone = nullableNumber(sample, "doneNs")
                        val leaderMember = nullableNumber(sample, "completionMemberId")
                        if (leaderDone == null) {
                            check(leaderMember == null)
                        } else {
                            val leaderCopy =
                                when (leaderMember) {
                                    0L -> copies.member0ReceivedNs
                                    1L -> copies.member1ReceivedNs
                                    2L -> copies.member2ReceivedNs
                                    else -> null
                                }
                            check(leaderCopy == leaderDone) {
                                "HTTP raw 리더/노드 수신 시각 불일치"
                            }
                        }
                        counter.row(sample, copies)
                        records = Math.incrementExact(records)
                        pending.reset()
                        start = index + 1
                    }
                }
                append(pending, buffer, start, count - start)
            }
        }
        interrupted()
        check(pending.size() == 0 && records == reference.records && bytes == reference.bytes) {
            "HTTP raw LF/EOF/행 수/bytes 불일치"
        }
        check(HexFormat.of().formatHex(digest.digest()) == reference.sha256) {
            "HTTP raw SHA-256 불일치"
        }
        interrupted()
        return counter.finish(records)
    }

    private class WindowCounter(
        val first: Long,
        val end: Long,
        val cutoff: Long,
        val stopped: Long?,
    ) {
        var inside = 0
        var outside = 0
        var ackInside = 0
        var ackAfter = 0
        var ackAfterCutoff = 0
        var doneInside = 0
        var doneAfter = 0
        var doneAfterCutoff = 0
        var unsent = 0
        var unknown = 0
        var member0 = 0
        var member1 = 0
        var member2 = 0
        var copiesObserved = 0
        var copiesMissing = 0
        var copiesInside = 0
        var copiesAfterWindow = 0
        var copiesByCutoff = 0
        var copiesAfterCutoff = 0

        fun row(
            value: JsonNode,
            copies: HttpPerformanceCompletionCopiesSample,
        ) {
            val start = HttpPerformanceSustainedRawFiles.nullableNumber(value, "startNs")
            if (start == null) {
                unsent++
            } else if (inWindow(start)) {
                inside++
            } else {
                outside++
            }
            val outcome = value.path("httpOutcome")
            if (start != null && (outcome.isNull || outcome.asString() in setOf("HTTP_UNKNOWN", "HTTP_TIMEOUT"))) unknown++
            if (outcome.isString && outcome.asString() == "ACCEPTED") {
                HttpPerformanceSustainedRawFiles.nullableNumber(value, "ackNs")?.let {
                    if (inWindow(it)) {
                        ackInside++
                    } else if (it - end >= 0) {
                        ackAfter++
                    }
                    if (it - cutoff > 0) ackAfterCutoff++
                }
            }
            HttpPerformanceSustainedRawFiles.nullableNumber(value, "doneNs")?.let {
                if (inWindow(it)) {
                    doneInside++
                } else if (it - end >= 0) {
                    doneAfter++
                }
                if (it - cutoff > 0) doneAfterCutoff++
            }
            if (copies.member0ReceivedNs != null) member0++
            if (copies.member1ReceivedNs != null) member1++
            if (copies.member2ReceivedNs != null) member2++
            val allDone = copies.allCopiesDoneNs
            if (allDone == null) {
                copiesMissing++
            } else {
                copiesObserved++
                val times = listOfNotNull(copies.member0ReceivedNs, copies.member1ReceivedNs, copies.member2ReceivedNs)
                if (times.all(::inWindow)) copiesInside++
                if (allDone - end >= 0) copiesAfterWindow++
                if (times.all { it - first >= 0 && cutoff - it >= 0 }) copiesByCutoff++
                if (allDone - cutoff > 0) copiesAfterCutoff++
            }
        }

        private fun inWindow(ns: Long): Boolean = ns - first >= 0 && end - ns > 0

        fun finish(records: Int) =
            HttpPerformanceSustainedWindowEvidence(
                records,
                first,
                end,
                cutoff,
                stopped,
                inside,
                outside,
                ackInside,
                ackAfter,
                ackAfterCutoff,
                doneInside,
                doneAfter,
                doneAfterCutoff,
                unsent,
                unknown,
                member0,
                member1,
                member2,
                copiesObserved,
                copiesMissing,
                copiesInside,
                copiesAfterWindow,
                copiesByCutoff,
                copiesAfterCutoff,
            )
    }

    private fun append(
        output: ByteArrayOutputStream,
        bytes: ByteArray,
        offset: Int,
        count: Int,
    ) {
        check(count <= HTTP_RAW_MAX_LINE_BYTES - output.size()) {
            "HTTP raw 단일 행 상한 초과"
        }
        output.write(bytes, offset, count)
    }

    private fun text(
        value: JsonNode,
        field: String,
    ): String =
        value
            .path(field)
            .also {
                check(it.isString) { "HTTP raw 문자열 누락: $field" }
            }.asString()

    private fun number(
        value: JsonNode,
        field: String,
    ): Long =
        value
            .path(field)
            .also {
                check(it.isIntegralNumber && it.canConvertToLong()) { "HTTP raw 정수 누락: $field" }
            }.asLong()

    private fun nullableNumber(
        value: JsonNode,
        field: String,
    ): Long? = value.path(field).let { if (it.isNull) null else number(value, field) }

    private fun sha(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                interrupted()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        interrupted()
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("HTTP sustained raw interrupted")
    }
}

/** Capacity validity is stronger than well-instrumented partial observations. Window attainment stays separate. */
internal fun httpPerformanceSustainedCohortEvidence(
    planned: Int,
    summary: HttpPerformanceSummary?,
    financial: Map<String, Any>?,
    raw: HttpPerformanceSustainedRawArtifact?,
    errors: List<String>,
): Map<String, Any?> {
    val window = raw?.window
    val measured =
        summary != null && summary.planned == planned && summary.started == planned && summary.unsent == 0 &&
            summary.accepted == planned && summary.businessRejected == 0 && summary.httpErrors == 0 && summary.httpUnknown == 0 &&
            summary.httpTimeouts == 0 && summary.httpUnfinished == 0 && summary.httpAfterDrain == 0 && summary.completed == planned &&
            summary.completionUnknown == 0 && summary.tradeCount == planned.toLong() && summary.settlementCount == planned.toLong() &&
            summary.completionLatencyValid
    val finance =
        financial?.get("actualMeasuredAccepted") == planned.toLong() && financial?.get("actualMeasuredSettled") == planned.toLong()
    val copies =
        window != null && window.allCopiesObserved == planned && window.allCopiesMissing == 0 && window.allCopiesByCutoff == planned &&
            window.allCopiesAfterCutoff == 0
    val complete =
        planned in 1..60_000 && errors.isEmpty() && measured && finance && copies && raw?.complete == true &&
            raw.reference?.records == planned &&
            window != null && window.records == planned && summary?.measurementWindowNs == window.windowEndNs - window.firstDueNs &&
            window.windowElapsed && window.startsInsideWindow == planned && window.startsOutsideWindow == 0 && window.unsent == 0
    val fixedWindowSeconds = summary?.measurementWindowNs?.toDouble()?.div(1_000_000_000.0)
    return mapOf(
        "kind" to "HTTP_SUSTAINED_60S_FIXED_COHORT",
        "schemaVersion" to 2,
        "cohortComplete" to complete,
        "fixedWindowTargetMet" to
            (
                complete && window?.allCopiesInsideWindow == planned && summary != null && summary.acceptedResponseWindowCount == planned &&
                    summary.completionWindowCount == planned &&
                    summary.settlementWindowCount == planned.toLong()
            ),
        "fixedWindowSeconds" to fixedWindowSeconds,
        "acceptedAcksInsideFixedWindow" to summary?.acceptedResponseWindowCount,
        "leaderCompletionsInsideFixedWindow" to summary?.completionWindowCount,
        "leaderSettlementsInsideFixedWindow" to summary?.settlementWindowCount,
        "acceptedAcksPerFixedWindowSecond" to fixedWindowSeconds?.takeIf { it > 0 }?.let { summary?.acceptedResponseWindowCount?.div(it) },
        "leaderCompletionsPerFixedWindowSecond" to fixedWindowSeconds?.takeIf { it > 0 }?.let { summary?.completionWindowCount?.div(it) },
        "leaderSettlementsPerFixedWindowSecond" to fixedWindowSeconds?.takeIf { it > 0 }?.let { summary?.settlementWindowCount?.div(it) },
        "threeCopyCompletionsInsideFixedWindow" to window?.allCopiesInsideWindow,
        "threeCopyCompletionsPerFixedWindowSecond" to fixedWindowSeconds?.takeIf { it > 0 }?.let { window?.allCopiesInsideWindow?.div(it) },
        "rawComplete" to (raw?.complete == true),
        "fullFinancialCohort" to finance,
        "httpAndLeaderCompletionCohort" to measured,
        "threeCopyObservationCohort" to copies,
        "window" to window,
        "clock" to "same HTTP load and marker receiver JVM System.nanoTime; original fixed due origin",
        "scope" to (
            "legacy latency/counts are leader observations; cohort requires all starts inside window, " +
                "ACK and all three member receives by original cutoff; fixed-window target additionally requires " +
                "all three member receives inside the original window"
        ),
        "maximumSustainableThroughputClaim" to false,
        "heapFitClaim" to false,
    )
}

/** Stream full typed marker rows, keeping financial trees out of the HTTP runner. Every scan must finish EOF/SHA. */
internal fun visitHttpPerformanceMeasuredSentMarkerFiles(
    references: Map<Int, HttpPerformanceCheckpointFileReference>,
    maximumMarkersPerMember: Int,
    visit: (HttpPerformanceCompletion) -> Unit,
) {
    check(references.keys == setOf(0, 1, 2))
    check(
        references.values
            .map { it.runId to it.label }
            .distinct()
            .size == 1,
    )
    require(maximumMarkersPerMember > 0)
    var position: Long? = null
    val mapper = jacksonObjectMapper()
    for (member in 0..2) {
        val reference = references.getValue(member)
        check(reference.memberId == member)
        var count = 0L
        val scan =
            HttpPerformanceCheckpointReader.scanRows(reference, onRow = { _, row ->
                count = Math.incrementExact(count)
                check(count <= maximumMarkersPerMember)
                check(row.isObject && httpPerformanceCheckpointMemberMatches(row.path("memberId"), member))
                for (field in listOf("runId", "role", "userId", "requestId", "orderId")) check(row.path(field).isString)
                for (field in listOf("admissionSequence", "logPosition", "tradeCount", "settlementCount")) {
                    check(row.path(field).isIntegralNumber && row.path(field).canConvertToLong())
                }
                val marker = mapper.treeToValue(row, HttpPerformanceCompletion::class.java)
                check(marker.runId == reference.runId && marker.memberId == member)
                if (marker.userId == "BUYER" && marker.requestId.startsWith("M-")) visit(marker)
            }, includeRow = { it.startsWith("/markerSummary/sentMarkers/") }, includeMarkerRows = true)
        check(scan.rowCounts["/markerSummary/sentMarkers"] == count)
        val current = checkNotNull(scan.appliedPosition)
        check(position == null || current == position) {
            "HTTP marker 원문 적용 위치 불일치"
        }
        position = current
    }
    if (Thread.currentThread().isInterrupted) throw InterruptedException("HTTP sent marker scan interrupted")
}
