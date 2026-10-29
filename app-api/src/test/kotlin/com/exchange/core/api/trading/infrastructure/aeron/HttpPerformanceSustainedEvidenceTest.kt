package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tiny same-clock artifacts and full sample/marker values only; no real runtime/performance result. */
class HttpPerformanceSustainedEvidenceTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private var serial = 0
    private val end = 3_000_000L
    private val cutoff = 13_000_000L

    @Test
    fun `전량raw를닫고EOF bytes SHA를확인한뒤같은clock의창내외정산을분리한다`() {
        val prepared = prepare(late = true)
        val summary = prepared.measurements.finish(cutoff, prepared.markers)
        val raw = publish(prepared)
        assertTrue(raw.complete)
        assertEquals(3, raw.recordsWritten)
        val ref = requireNotNull(raw.reference)
        assertEquals(Files.size(ref.file), ref.bytes)
        assertEquals(sha(ref.file), ref.sha256)
        val window = requireNotNull(raw.window)
        assertEquals(3, window.startsInsideWindow)
        assertEquals(3, window.acceptedAcksInsideWindow)
        assertEquals(2, window.leaderCompletionsInsideWindow)
        assertEquals(1, window.leaderCompletionsAtOrAfterWindow)
        assertEquals(0, window.leaderCompletionsAfterCutoff)
        assertTrue(window.windowElapsed)
        val evidence = httpPerformanceSustainedCohortEvidence(3, summary, finance(), raw, emptyList())
        assertEquals(true, evidence["cohortComplete"])
        assertEquals(false, evidence["fixedWindowTargetMet"])
        assertEquals(3, evidence["acceptedAcksInsideFixedWindow"])
        assertEquals(2L, evidence["leaderSettlementsInsideFixedWindow"])
        assertEquals(1000.0, evidence["acceptedAcksPerFixedWindowSecond"])
        val lines = Files.readAllLines(ref.file)
        assertEquals(3, lines.size)
        val actual = lines.map { mapper.readTree(it).path("sample") }
        val expectedWireSamples = prepared.measurements.requests().map { mapper.readTree(mapper.writeValueAsString(it)) }
        assertEquals(expectedWireSamples, actual)
    }

    @Test
    fun `unknown 미송신 부분금융 cleanup오류는raw가완전해도cohort성공이아니다`() {
        for (mode in listOf("unknown", "unsent", "finance", "cleanup")) {
            val prepared = prepare(unknown = mode == "unknown", unsent = mode == "unsent")
            val summary = prepared.measurements.finish(cutoff, prepared.markers)
            val raw = publish(prepared)
            assertTrue(raw.complete)
            val financial = if (mode == "finance") mapOf("actualMeasuredAccepted" to 3L, "actualMeasuredSettled" to 2L) else finance()
            val evidence =
                httpPerformanceSustainedCohortEvidence(
                    3,
                    summary,
                    financial,
                    raw,
                    if (mode ==
                        "cleanup"
                    ) {
                        listOf("cleanup failed")
                    } else {
                        emptyList()
                    },
                )
            assertEquals(false, evidence["cohortComplete"], mode)
        }
    }

    @Test
    fun `같은길이raw변조도SHA에서거절하고LF 중복필드 전체필드누락도실패한다`() {
        val prepared = prepare()
        prepared.measurements.finish(cutoff, prepared.markers)
        val raw = publish(prepared)
        val ref = requireNotNull(raw.reference)
        val original = Files.readString(ref.file)
        Files.writeString(ref.file, original.replace("\"OK\"", "\"NO\""))
        val failure = assertFailsWith<IllegalStateException> { HttpPerformanceSustainedRawFiles.scan(ref, 0, end, cutoff, end) }
        assertTrue(failure.message.orEmpty().contains("SHA"))
        val versions =
            listOf(
                original.dropLast(1),
                original.replace("\"index\":0", "\"index\":0,\"index\":0"),
                original
                    .lineSequence()
                    .filter { it.isNotEmpty() }
                    .mapIndexed { index, line ->
                        if (index == 0) {
                            val row = mapper.readTree(line) as ObjectNode
                            (row.path("sample") as ObjectNode).remove("httpDetail")
                            mapper.writeValueAsString(row)
                        } else {
                            line
                        }
                    }.joinToString("\n", postfix = "\n"),
            )
        for (version in versions) {
            Files.writeString(ref.file, version)
            val changed = ref.copy(bytes = Files.size(ref.file), sha256 = sha(ref.file))
            assertFailsWith<Exception> { HttpPerformanceSustainedRawFiles.scan(changed, 0, end, cutoff, end) }
            assertTrue(Files.isRegularFile(ref.file))
        }
    }

    @Test
    fun `writer와visitor실패는candidate를보존하되완전한reference를반환하지않는다`() {
        val prepared = prepare()
        prepared.measurements.finish(cutoff, prepared.markers)
        val file = freshFile()
        Files.createDirectory(file)
        val directoryFailure =
            HttpPerformanceSustainedRawFiles.publish(file, "http-raw", 3, 0, end, cutoff, end) {
                error("writer must fail before visitor")
            }
        assertFalse(directoryFailure.complete)
        assertNull(directoryFailure.reference)
        assertTrue(Files.isDirectory(file))
        val partial =
            HttpPerformanceSustainedRawFiles.publish(freshFile(), "http-raw", 3, 0, end, cutoff, end) { visit ->
                var first = true
                prepared.measurements.forEachSustainedRequestObservation {
                    if (first) {
                        visit(it)
                        first = false
                    }
                }
                throw IOException("raw source failed after first row")
            }
        assertFalse(partial.complete)
        assertEquals(1, partial.recordsWritten)
        assertNull(partial.reference)
        assertTrue(partial.failure.orEmpty().contains("raw source failed after first row"))
        assertTrue(Files.isRegularFile(partial.file))
        assertEquals(3, prepared.measurements.requests().size)
    }

    @Test
    fun `마지막raw행interrupt와누락된raw는완료reference나capacity성공으로바뀌지않는다`() {
        val prepared = prepare()
        val summary = prepared.measurements.finish(cutoff, prepared.markers)
        val previous = Thread.interrupted()
        try {
            val raw =
                HttpPerformanceSustainedRawFiles.publish(freshFile(), "http-raw", 3, 0, end, cutoff, end) { visit ->
                    prepared.measurements.forEachSustainedRequestObservation {
                        visit(it)
                        if (it.sample.requestId == "M-2") Thread.currentThread().interrupt()
                    }
                }
            assertFalse(raw.complete)
            assertNull(raw.reference)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (previous) Thread.currentThread().interrupt()
        }
        assertEquals(false, httpPerformanceSustainedCohortEvidence(3, summary, finance(), null, emptyList())["cohortComplete"])
    }

    @Test
    fun `세file marker원문은행별exact대조하고마지막file EOF SHA실패는전량방문뒤에도성공이아니다`() {
        val original = prepare()
        val expected = original.measurements.finish(cutoff, original.markers)
        val prepared = prepare()
        val refs = markerFiles(prepared.markers)
        val actual =
            prepared.measurements.finishWithSentMarkerVisitor(
                cutoff,
            ) { visit -> visitHttpPerformanceMeasuredSentMarkerFiles(refs, 3, visit) }
        assertEquals(expected, actual)
        val failed = prepare()
        val bad = refs.toMutableMap().also { it[2] = it.getValue(2).copy(sha256 = "0".repeat(64)) }
        var provisional = 0
        assertFailsWith<IllegalArgumentException> {
            failed.measurements.finishWithSentMarkerVisitor(cutoff) { visit ->
                visitHttpPerformanceMeasuredSentMarkerFiles(bad, 3) {
                    provisional++
                    visit(it)
                }
            }
        }
        assertTrue(provisional > 0)
        assertFalse(failed.measurements.finish(cutoff, null).completionLatencyValid)
        assertTrue(refs.values.all { Files.isRegularFile(it.file) })
    }

    @Test
    fun `file marker의문자열member와서로다른적용위치는typedcoercion이나계수로통과하지않는다`() {
        for (kind in listOf("member", "position")) {
            val prepared = prepare()
            val refs = markerFiles(prepared.markers).toMutableMap()
            val original = refs.getValue(2)
            val value = mapper.readTree(Files.readString(original.file)) as ObjectNode
            if (kind == "member") {
                ((value.at("/markerSummary/sentMarkers/0")) as ObjectNode).put("memberId", "2")
            } else {
                (value.path("applied") as ObjectNode).put("position", 4097)
            }
            Files.writeString(original.file, mapper.writeValueAsString(value))
            refs[2] = original.copy(bytes = Files.size(original.file), sha256 = sha(original.file))
            assertFailsWith<IllegalStateException>(kind) {
                prepared.measurements.finishWithSentMarkerVisitor(
                    cutoff,
                ) { visit -> visitHttpPerformanceMeasuredSentMarkerFiles(refs, 3, visit) }
            }
            assertTrue(Files.isRegularFile(original.file))
        }
    }

    @Test
    fun `leader와ACK59점9초뒤follower가원래cutoff1ns후면금융성공이어도cohort와창목표는실패한다`() {
        val seconds60 = 60_000_000_000L
        val originalCutoff = 120_000_000_000L
        val prepared = oneOrderOnSixtySecondWindow(originalCutoff + 1)
        val summary = prepared.measurements.finish(originalCutoff, prepared.markers)
        assertTrue(summary.completionLatencyValid)
        assertEquals(1, summary.completed)
        assertEquals(1, summary.completionWindowCount)
        assertEquals(1, summary.acceptedResponseWindowCount)
        val raw =
            HttpPerformanceSustainedRawFiles.publish(
                freshFile(),
                "http-raw",
                1,
                0,
                seconds60,
                originalCutoff,
                originalCutoff,
                prepared.measurements::forEachSustainedRequestObservation,
            )
        assertTrue(raw.complete)
        val evidence =
            httpPerformanceSustainedCohortEvidence(
                1,
                summary,
                mapOf(
                    "actualMeasuredAccepted" to 1L,
                    "actualMeasuredSettled" to 1L,
                ),
                raw,
                emptyList(),
            )
        assertEquals(true, evidence["fullFinancialCohort"])
        assertEquals(true, evidence["httpAndLeaderCompletionCohort"])
        assertEquals(false, evidence["threeCopyObservationCohort"])
        assertEquals(false, evidence["cohortComplete"])
        assertEquals(false, evidence["fixedWindowTargetMet"])
        assertEquals(1, requireNotNull(raw.window).allCopiesAfterCutoff)
        assertEquals(0, requireNotNull(raw.window).allCopiesByCutoff)
        val row = mapper.readTree(Files.readString(requireNotNull(raw.reference).file))
        assertEquals(2, row.path("schemaVersion").asInt())
        assertEquals(59_900_000_000L, row.at("/sample/doneNs").asLong())
        assertEquals(originalCutoff + 1, row.at("/completionCopies/member2ReceivedNs").asLong())
        assertEquals(originalCutoff + 1, row.at("/completionCopies/allCopiesDoneNs").asLong())
        val report =
            httpPerformanceReport(
                listOf(
                    HttpPerformanceRunResult(
                        "http-raw",
                        1000,
                        1,
                        directory.toString(),
                        summary,
                        null,
                        listOf("3copy cutoff"),
                        sustainedEvidence = evidence,
                    ),
                ),
            )
        assertTrue(report.contains("리더 완료"))
        assertTrue(report.contains("세 노드 전체 완료"))
        assertTrue(report.contains("http-client-samples.jsonl schema2"))
    }

    @Test
    fun `follower가창밖1ns이지만cutoff안이면cohort만성공하며경계값은반열린창과포함cutoff를지킨다`() {
        val seconds60 = 60_000_000_000L
        val originalCutoff = 120_000_000_000L
        for (followerNs in listOf(seconds60 - 1, seconds60, seconds60 + 1, originalCutoff)) {
            val prepared = oneOrderOnSixtySecondWindow(followerNs)
            val summary = prepared.measurements.finish(originalCutoff, prepared.markers)
            val raw =
                HttpPerformanceSustainedRawFiles.publish(
                    freshFile(),
                    "http-raw",
                    1,
                    0,
                    seconds60,
                    originalCutoff,
                    maxOf(seconds60, followerNs),
                    prepared.measurements::forEachSustainedRequestObservation,
                )
            assertTrue(raw.complete)
            val evidence =
                httpPerformanceSustainedCohortEvidence(
                    1,
                    summary,
                    mapOf(
                        "actualMeasuredAccepted" to 1L,
                        "actualMeasuredSettled" to 1L,
                    ),
                    raw,
                    emptyList(),
                )
            assertEquals(true, evidence["cohortComplete"], followerNs.toString())
            assertEquals(followerNs < seconds60, evidence["fixedWindowTargetMet"], followerNs.toString())
            assertEquals(1, requireNotNull(raw.window).allCopiesByCutoff)
            assertEquals(0, requireNotNull(raw.window).allCopiesAfterCutoff)
            assertEquals(1, evidence["leaderCompletionsInsideFixedWindow"])
            assertEquals(if (followerNs < seconds60) 1 else 0, evidence["threeCopyCompletionsInsideFixedWindow"])
        }
    }

    @Test
    fun `누락follower는리더와부분송신목록이exact여도세노드원시시각을null로보존하고성공하지않는다`() {
        val prepared = oneOrderOnSixtySecondWindow(null)
        val summary = prepared.measurements.finish(120_000_000_000L, prepared.markers)
        assertTrue(summary.completionLatencyValid)
        val raw =
            HttpPerformanceSustainedRawFiles.publish(
                freshFile(),
                "http-raw",
                1,
                0,
                60_000_000_000L,
                120_000_000_000L,
                120_000_000_000L,
                prepared.measurements::forEachSustainedRequestObservation,
            )
        assertTrue(raw.complete)
        assertEquals(1, requireNotNull(raw.window).allCopiesMissing)
        val evidence =
            httpPerformanceSustainedCohortEvidence(
                1,
                summary,
                mapOf(
                    "actualMeasuredAccepted" to 1L,
                    "actualMeasuredSettled" to 1L,
                ),
                raw,
                emptyList(),
            )
        assertEquals(false, evidence["cohortComplete"])
        val row = mapper.readTree(Files.readString(requireNotNull(raw.reference).file))
        assertTrue(row.at("/completionCopies/member2ReceivedNs").isNull)
        assertTrue(row.at("/completionCopies/allCopiesDoneNs").isNull)
    }

    @Test
    fun `세노드raw의필드누락coercion과가짜max는해시를다시계산해도거절한다`() {
        val prepared = prepare()
        prepared.measurements.finish(cutoff, prepared.markers)
        val raw = publish(prepared)
        val reference = requireNotNull(raw.reference)
        val original = Files.readString(reference.file)
        for (kind in listOf("missing", "string", "max", "extra")) {
            val changed =
                original
                    .lineSequence()
                    .filter { it.isNotEmpty() }
                    .mapIndexed { index, line ->
                        if (index != 0) {
                            line
                        } else {
                            val row = mapper.readTree(line) as ObjectNode
                            val copies = row.path("completionCopies") as ObjectNode
                            when (kind) {
                                "missing" -> copies.remove("member2ReceivedNs")
                                "string" -> copies.put("member2ReceivedNs", "30")
                                "max" -> copies.put("allCopiesDoneNs", 29)
                                "extra" -> copies.put("member3ReceivedNs", 30)
                            }
                            mapper.writeValueAsString(row)
                        }
                    }.joinToString("\n", postfix = "\n")
            Files.writeString(reference.file, changed)
            val changedReference = reference.copy(bytes = Files.size(reference.file), sha256 = sha(reference.file))
            assertFailsWith<IllegalStateException>(kind) { HttpPerformanceSustainedRawFiles.scan(changedReference, 0, end, cutoff, end) }
        }
    }

    @Test
    fun `세노드전체완료시각은Longwrap뒤음수절대시각도origin차분으로max를검증한다`() {
        val first = Long.MAX_VALUE - 15
        val windowEnd = first + 1_000_000
        val originalCutoff = windowEnd + 10_000_000
        val measurements =
            HttpPerformanceMeasurements("http-raw", first, windowEnd, listOf(PlannedHttpRequest("BUYER", "M-0", "M-0", first)))
        measurements.requestStarted("BUYER", "M-0", first + 1)
        measurements.httpCompleted("BUYER", "M-0", first + 5, HttpPerformanceOutcome.ACCEPTED, 1)
        val markers =
            (0..2).map {
                HttpPerformanceCompletion(
                    "http-raw",
                    it,
                    if (it ==
                        0
                    ) {
                        "LEADER"
                    } else {
                        "FOLLOWER"
                    },
                    "BUYER",
                    "M-0",
                    "M-0",
                    1,
                    256,
                    1,
                    1,
                )
            }
        for (member in 0..2) measurements.completionObserved(markers[member], first + (member + 1) * 10)
        val summary = measurements.finish(originalCutoff, markers)
        val raw =
            HttpPerformanceSustainedRawFiles.publish(
                freshFile(),
                "http-raw",
                1,
                first,
                windowEnd,
                originalCutoff,
                windowEnd,
                measurements::forEachSustainedRequestObservation,
            )
        assertTrue(raw.complete)
        assertEquals(1, requireNotNull(raw.window).allCopiesInsideWindow)
        val row = mapper.readTree(Files.readString(requireNotNull(raw.reference).file))
        assertEquals(first + 30, row.at("/completionCopies/allCopiesDoneNs").asLong())
        assertTrue(row.at("/completionCopies/allCopiesDoneNs").asLong() < 0)
        assertEquals(
            true,
            httpPerformanceSustainedCohortEvidence(
                1,
                summary,
                mapOf(
                    "actualMeasuredAccepted" to 1L,
                    "actualMeasuredSettled" to 1L,
                ),
                raw,
                emptyList(),
            )["fixedWindowTargetMet"],
        )
        assertFalse(
            HttpPerformanceSustainedRawFiles
                .publish(
                    freshFile(),
                    "http-raw",
                    1,
                    0,
                    60_000_000_001L,
                    120_000_000_001L,
                    60_000_000_001L,
                    measurements::forEachSustainedRequestObservation,
                ).complete,
        )
    }

    private fun oneOrderOnSixtySecondWindow(follower2Ns: Long?): Prepared {
        val measurements = HttpPerformanceMeasurements("http-raw", 0, 60_000_000_000L, listOf(PlannedHttpRequest("BUYER", "M-0", "M-0", 0)))
        measurements.requestStarted("BUYER", "M-0", 1)
        measurements.httpCompleted("BUYER", "M-0", 59_900_000_000L, HttpPerformanceOutcome.ACCEPTED, 1)
        val markers =
            (0..2).filter { it != 2 || follower2Ns != null }.map {
                HttpPerformanceCompletion("http-raw", it, if (it == 0) "LEADER" else "FOLLOWER", "BUYER", "M-0", "M-0", 1, 256, 1, 1)
            }
        for (marker in markers) {
            measurements.completionObserved(
                marker,
                if (marker.memberId ==
                    2
                ) {
                    requireNotNull(follower2Ns)
                } else {
                    59_900_000_000L
                },
            )
        }
        return Prepared(measurements, markers)
    }

    private data class Prepared(
        val measurements: HttpPerformanceMeasurements,
        val markers: List<HttpPerformanceCompletion>,
    )

    private fun prepare(
        late: Boolean = false,
        unknown: Boolean = false,
        unsent: Boolean = false,
    ): Prepared {
        val plans = (0..2).map { PlannedHttpRequest("BUYER", "M-$it", "M-$it", it * 1_000_000L) }
        val measurements = HttpPerformanceMeasurements("http-raw", 0, end, plans)
        val markers = mutableListOf<HttpPerformanceCompletion>()
        for (index in 0..2) {
            if (unsent && index == 2) {
                measurements.requestNotSent("BUYER", "M-$index", HttpPerformanceMeasurements.GENERATOR_SATURATED, index * 1_000_000L)
                continue
            }
            measurements.requestStarted("BUYER", "M-$index", index * 1_000_000L + 10)
            measurements.httpCompleted(
                "BUYER",
                "M-$index",
                index * 1_000_000L + 20,
                if (unknown && index == 1) HttpPerformanceOutcome.HTTP_UNKNOWN else HttpPerformanceOutcome.ACCEPTED,
                if (unknown && index == 1) null else index + 1L,
                "OK",
                responseReceived = !(unknown && index == 1),
            )
            for (member in 0..2) {
                val marker =
                    HttpPerformanceCompletion(
                        "http-raw",
                        member,
                        if (member ==
                            0
                        ) {
                            "LEADER"
                        } else {
                            "FOLLOWER"
                        },
                        "BUYER",
                        "M-$index",
                        "M-$index",
                        index + 1L,
                        256L * (index + 1),
                        1,
                        1,
                    )
                markers.add(marker)
                measurements.completionObserved(marker, if (late && index == 2) end + 1 else index * 1_000_000L + 30)
            }
        }
        return Prepared(measurements, markers)
    }

    private fun publish(value: Prepared) =
        HttpPerformanceSustainedRawFiles.publish(
            freshFile(),
            "http-raw",
            3,
            0,
            end,
            cutoff,
            end,
            value.measurements::forEachSustainedRequestObservation,
        )

    private fun freshFile(): Path =
        Files.createDirectories(directory.resolve("raw-${serial++}")).resolve(HTTP_SUSTAINED_RAW_NAME).toAbsolutePath()

    private fun finance(): Map<String, Any> = mapOf("actualMeasuredAccepted" to 3L, "actualMeasuredSettled" to 3L)

    private fun markerFiles(markers: List<HttpPerformanceCompletion>): Map<Int, HttpPerformanceCheckpointFileReference> {
        val target = Files.createDirectories(directory.resolve("markers-${serial++}"))
        return (0..2).associateWith { member ->
            val file = target.resolve("node$member-checkpoint-final.json").toAbsolutePath()
            mapper.writeValue(
                file.toFile(),
                mapOf(
                    "kind" to "PERFORMANCE_CHECKPOINT",
                    "runId" to "http-raw",
                    "memberId" to member,
                    "label" to "final",
                    "applied" to mapOf("position" to 4096, "state" to emptyMap<String, Any>()),
                    "trading" to emptyMap<String, Any>(),
                    "markerSummary" to mapOf("sentMarkers" to markers.filter { it.memberId == member }),
                ),
            )
            HttpPerformanceCheckpointFileReference(file, "http-raw", member, "final", Files.size(file), sha(file))
        }
    }

    private fun sha(file: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
}
