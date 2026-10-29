package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실행 계획과 두 JVM 시계의 분리만 검사한다. Docker·Aeron을 기동하지 않는다. */
class DirectAeronCapacityBaselineTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `node original retention is false by default and explicit sustained opt-in only`() {
        assertFalse(parseDirectAeronCapacityOptions(arrayOf("--smoke")).preserveNodeRecordings)
        assertFalse(parseDirectAeronCapacityOptions(arrayOf("--probe")).preserveNodeRecordings)
        val ordinary = parseDirectAeronCapacityOptions(arrayOf("--sustained", "--rate=1000", "--seconds=60"))
        assertFalse(ordinary.preserveNodeRecordings)
        val retained = parseDirectAeronCapacityOptions(arrayOf("--sustained", "--rate=1000", "--seconds=60", "--preserve-node-recordings"))
        assertTrue(retained.preserveNodeRecordings)
        assertEquals(ordinary.copy(preserveNodeRecordings = true), retained)
        assertEquals(ordinary.orders, retained.orders)
        assertEquals(ordinary.warmup, retained.warmup)
        assertEquals(4096, retained.maxInFlight)
    }

    @Test
    fun `diagnostic profiles are explicit sustained only and preserve ordinary workload`() {
        val ordinary = parseDirectAeronCapacityOptions(arrayOf("--sustained", "--rate=5000", "--seconds=60"))
        val diagnostic =
            parseDirectAeronCapacityOptions(
                arrayOf("--sustained", "--rate=5000", "--seconds=60", "--diagnostic-profiles"),
            )
        assertFalse(ordinary.diagnosticProfiles)
        assertEquals(ordinary.copy(diagnosticProfiles = true), diagnostic)
        for (flags in listOf(
            arrayOf("--diagnostic-profiles", "--preserve-node-recordings"),
            arrayOf("--preserve-node-recordings", "--diagnostic-profiles"),
        )) {
            assertEquals(
                diagnostic.copy(preserveNodeRecordings = true),
                parseDirectAeronCapacityOptions(arrayOf("--sustained", "--rate=5000", "--seconds=60") + flags),
            )
        }
        for (args in listOf(
            arrayOf("--smoke", "--diagnostic-profiles"),
            arrayOf("--probe", "--diagnostic-profiles"),
            arrayOf("--sustained", "--rate=5000", "--seconds=60", "--diagnostic-profiles", "--diagnostic-profiles"),
        )) {
            assertFailsWith<IllegalArgumentException> { parseDirectAeronCapacityOptions(args) }
        }
        assertFailsWith<IllegalArgumentException> { DirectAeronCapacityOptions(smoke = false, diagnosticProfiles = true) }
    }

    @Test
    fun `burst misplaced duplicate and unknown original retention flags are rejected`() {
        for (args in listOf(
            arrayOf("--smoke", "--preserve-node-recordings"),
            arrayOf("--probe", "--preserve-node-recordings"),
            arrayOf("--sustained", "--preserve-node-recordings", "--rate=1000", "--seconds=60"),
            arrayOf("--sustained", "--rate=1000", "--seconds=60", "--preserve-node-recordings", "--preserve-node-recordings"),
            arrayOf("--sustained", "--rate=1000", "--seconds=60", "--preserve-node-recording"),
        )) {
            assertFailsWith<IllegalArgumentException> { parseDirectAeronCapacityOptions(args) }
        }
        assertFailsWith<IllegalArgumentException> { DirectAeronCapacityOptions(smoke = false, preserveNodeRecordings = true) }
    }

    @Test
    fun `장기 marker raw와 exact index는 shifted 도착창 뒤에도 전량 증거를 유지한다`() {
        val file = directory.resolve("markers.jsonl")
        val observer = DirectAeronSustainedMarkerObservations("run1", 2, file)
        val first = -100L
        for (member in 0..2) observer.observe(observation(member, 0, first + member).marker, first + member)
        for (member in 0..2) {
            val at = first + 60_000_000_000L + member
            observer.observe(observation(member, 1, at).marker, at)
        }
        val cutoff = first + 60_000_000_003L
        assertTrue(observer.complete(booleanArrayOf(true, true), cutoff))
        observer.close()
        val summary = observer.summary(cutoff, longArrayOf(10, 11))
        assertEquals(emptyList<String>(), summary["errors"])
        assertEquals(6, summary["rawRows"])
        val members = summary["members"] as Map<*, *>
        for (member in 0..2) {
            val row = members[member] as Map<*, *>
            assertEquals(2, row["receivedWithinDrain"])
            assertEquals(1, row["arrivalWindowInside"])
            assertEquals(1, row["arrivalWindowAfter"])
        }
        assertEquals(6, Files.readAllLines(file).size)
        assertEquals(true, observer.rawArtifact()["complete"])
    }

    @Test
    fun `장기 drain은 확실한 미실행만 제외하며 부분 완료를 전량 성공으로 바꾸지 않는다`() {
        val observer = DirectAeronSustainedMarkerObservations("run1", 2, directory.resolve("markers.jsonl"))
        (0..2).forEach { member -> observer.observe(observation(member, 0, member.toLong()).marker, member.toLong()) }
        assertTrue(observer.complete(booleanArrayOf(true, false), 10))
        assertFalse(observer.complete(booleanArrayOf(true, true), 10))
        observer.close()
        assertTrue((observer.summary(10, longArrayOf(10, 0))["errors"] as List<*>).isNotEmpty())
        assertFalse(directAeronSustainedFinancialCohortComplete(mapOf("actualMeasuredAccepted" to 1L, "actualMeasuredSettled" to 1L), 2))
        assertFalse(directAeronSustainedFinancialCohortComplete(mapOf("actualMeasuredAccepted" to 2L, "actualMeasuredSettled" to 1L), 2))
        assertFalse(directAeronSustainedFinancialCohortComplete(null, 2))
        assertTrue(directAeronSustainedFinancialCohortComplete(mapOf("actualMeasuredAccepted" to 2L, "actualMeasuredSettled" to 2L), 2))
    }

    @Test
    fun `장기 늦은 marker와 Long 경계는 raw에 남아도 원래 drain cutoff를 넘기지 못한다`() {
        val first = Long.MAX_VALUE - 20
        val deadline = first + 30
        val observer = DirectAeronSustainedMarkerObservations("run1", 1, directory.resolve("markers.jsonl"))
        observer.observe(observation(0, 0, first).marker, first)
        observer.observe(observation(1, 0, first + 10).marker, first + 10)
        var now = first + 15
        val cutoff =
            awaitDirectAeronSustainedMarkers(observer, booleanArrayOf(true), deadline, { now }) {
                now = deadline + 1
                observer.observe(observation(2, 0, now).marker, now)
            }
        assertEquals(deadline, cutoff)
        assertFalse(observer.complete(booleanArrayOf(true), cutoff))
        observer.close()
        assertEquals(1, observer.summary(cutoff, longArrayOf(10))["receivedAfterDrain"])
        assertEquals(3, Files.readAllLines(directory.resolve("markers.jsonl")).size)
    }

    @Test
    fun `장기 부모 시계의 실제 역행은 payload 전량이 같아도 유효 관측으로 표시하지 않는다`() {
        val observer = DirectAeronSustainedMarkerObservations("run1", 1, directory.resolve("markers.jsonl"))
        observer.observe(observation(0, 0, 10).marker, 10)
        observer.observe(observation(1, 0, 9).marker, 9)
        observer.observe(observation(2, 0, 11).marker, 11)
        observer.close()
        assertTrue((observer.summary(20, longArrayOf(10))["errors"] as List<*>).contains("PARENT_MARKER_CLOCK_REGRESSION"))
    }

    @Test
    fun `장기 marker 충돌과 초과는 원문을 남기고 index를 새 payload로 덮어쓰지 않는다`() {
        val observer = DirectAeronSustainedMarkerObservations("run1", 2, directory.resolve("markers.jsonl"))
        (0..2).forEach { member -> observer.observe(observation(member, 0, member.toLong()).marker, member.toLong()) }
        observer.observe(observation(0, 0, 3).marker.copy(admissionSequence = 99), 3)
        observer.observe(observation(0, 1, 4).marker, 4)
        observer.observe(observation(1, 1, 5).marker, 5)
        observer.observe(observation(2, 1, 6).marker, 6)
        observer.close()
        val summary = observer.summary(10, longArrayOf(10, 11))
        assertEquals(6, summary["rawRows"])
        assertTrue((summary["errors"] as List<*>).any { it.toString().startsWith("DUPLICATE_REQUEST_MARKER:") })
        assertTrue((summary["errors"] as List<*>).any { it.toString().startsWith("MARKER_OBSERVATION_OVERFLOW:") })
        assertTrue(summary["firstOverflow"] is DirectAeronMarkerObservation)
        assertFalse(observer.complete(booleanArrayOf(true, true), 10))
    }

    @Test
    fun `장기 client raw를 행별로 읽어 known missing과 unknown 및 정확한 ACK 순번을 분리한다`() {
        val fixture = sustainedRawFixture()
        val reference = DirectAeronSustainedRawFiles.resolve(directory, fixture.second, "direct-test", fixture.first.workload)
        val evidence = readDirectAeronSustainedClientEvidence(fixture.second, reference, fixture.first.workload)
        assertEquals(10L, evidence.acceptedSequences[0])
        assertEquals(0L, evidence.acceptedSequences[1])
        assertTrue(evidence.possibleExecution[0])
        assertFalse(evidence.possibleExecution[1])
        assertFalse(evidence.possibleExecution[2])
        assertTrue(evidence.possibleExecution[3])
        assertTrue(evidence.possibleExecution[4])
        assertEquals(1, evidence.metrics["notSent"])
        assertEquals(2, evidence.metrics["resultUnknown"])
        assertEquals(1, evidence.metrics["offerUnknown"])
        val rtt = evidence.metrics["ackRtt"] as Map<*, *>
        assertEquals(56, rtt["sampleCount"])
        assertEquals(4, rtt["cohortWithoutAcceptedLatencyCount"])
        assertEquals(3L, rtt["p99Ns"])
        assertEquals(false, evidence.metrics["fullCohortAcceptedWithoutErrors"])
    }

    @Test
    fun `장기 raw의 늦은 EOF 손상은 provisional index를 성공 결과로 반환하지 않는다`() {
        val fixture = sustainedRawFixture()
        val reference = DirectAeronSustainedRawFiles.resolve(directory, fixture.second, "direct-test", fixture.first.workload)
        val bytes = Files.readAllBytes(reference.file)
        bytes[bytes.lastIndex] = ' '.code.toByte()
        Files.write(reference.file, bytes)
        assertFailsWith<IllegalArgumentException> {
            readDirectAeronSustainedClientEvidence(
                fixture.second,
                reference,
                fixture.first.workload,
            )
        }
    }

    @Test
    fun `대기가 deadline을 넘어 깨어나도 늦은 표식을 drain 안의 완료로 바꾸지 않는다`() {
        val observer = DirectAeronMarkerObservations("run1", 1)
        observer.observe(observation(0, 0, -20).marker, -20)
        observer.observe(observation(1, 0, -10).marker, -10)
        var now = 0L

        val cutoff =
            awaitDirectAeronMarkers(observer, setOf("M-0"), deadlineNs = 60, nanoTime = { now }) {
                observer.observe(observation(2, 0, 62).marker, 62)
                now = 65
            }

        assertEquals(60L, cutoff)
        assertTrue(observer.complete())
        assertFalse(observer.complete(setOf("M-0"), cutoff))
        val summary = directAeronMarkerSummary(observer.snapshot(), 1, cutoff, mapOf("M-0" to 10L))
        assertEquals(1, summary["receivedAfterDrain"])
        assertTrue((summary["errors"] as List<*>).isNotEmpty())
    }

    @Test
    fun `deadline 전 표식은 늦게 깨어나도 보존하고 nanoTime의 Long 경계를 안전하게 지난다`() {
        val observer = DirectAeronMarkerObservations("run1", 1)
        val start = Long.MAX_VALUE - 30
        var now = start
        val deadline = start + 60

        val cutoff =
            awaitDirectAeronMarkers(observer, setOf("M-0"), deadlineNs = deadline, nanoTime = { now }) {
                for (member in 0..2) observer.observe(observation(member, 0, start + 50 + member).marker, start + 50 + member)
                now = start + 65
            }

        assertEquals(deadline, cutoff)
        assertTrue(observer.complete(setOf("M-0"), cutoff))
        assertEquals(0, directAeronMarkerSummary(observer.snapshot(), 1, cutoff, mapOf("M-0" to 10L))["receivedAfterDrain"])
    }

    @Test
    fun `deadline 뒤 pause가 예외로 끝나도 먼저 보존한 cutoff는 늦은 표식을 소급하지 않는다`() {
        for (start in listOf(0L, Long.MAX_VALUE - 30)) {
            for (originalFailure in listOf(InterruptedException("drain interrupted"), IllegalStateException("drain pause failed"))) {
                val observer = DirectAeronMarkerObservations("run1", 1)
                observer.observe(observation(0, 0, start - 20).marker, start - 20)
                observer.observe(observation(1, 0, start - 10).marker, start - 10)
                var now = start
                val deadlineNs = start + 60
                // The caller owns this fallback before entering the helper, as in the real run's finally path.
                var cutoffNs = deadlineNs

                val failure =
                    assertFailsWith<Exception> {
                        cutoffNs =
                            awaitDirectAeronMarkers(observer, setOf("M-0"), deadlineNs, nanoTime = { now }) {
                                observer.observe(observation(2, 0, start + 62).marker, start + 62)
                                now = start + 65
                                throw originalFailure
                            }
                    }

                assertSame(originalFailure, failure)
                assertEquals(deadlineNs, cutoffNs)
                assertTrue(now - cutoffNs > 0)
                assertTrue(observer.complete())
                assertFalse(observer.complete(setOf("M-0"), cutoffNs))
                val summary = directAeronMarkerSummary(observer.snapshot(), 1, cutoffNs, mapOf("M-0" to 10L))
                assertEquals(deadlineNs, summary["cutoffNs"])
                assertEquals(1, summary["receivedAfterDrain"])
                assertTrue((summary["errors"] as List<*>).isNotEmpty())
            }
        }
    }

    @Test
    fun `두 표식의 수신 간격은 Long 경계를 넘어도 절대값 정렬로 뒤집지 않는다`() {
        val raw =
            (0..2).flatMap { member ->
                listOf(observation(member, 0, Long.MAX_VALUE - 5), observation(member, 1, Long.MIN_VALUE + 4))
            }
        val summary = directAeronMarkerSummary(raw, 2, Long.MIN_VALUE + 9, mapOf("M-0" to 10L, "M-1" to 11L))

        assertEquals(emptyList<String>(), summary["errors"])
        val members = summary["members"] as Map<*, *>
        for (member in 0..2) {
            val node = members[member] as Map<*, *>
            assertEquals(Long.MAX_VALUE - 5, node["firstReceivedNs"])
            assertEquals(Long.MIN_VALUE + 4, node["lastReceivedNs"])
            assertEquals(10L, node["interarrivalIntervalNs"])
            assertEquals(100_000_000.0, node["interarrivalRate"])
        }
    }

    @Test
    fun `기존 root CLI의 작은 smoke와 고정 N 네 rate probe 계획은 유지한다`() {
        val smoke = parseDirectAeronCapacityOptions(arrayOf("--smoke"))
        val probe = parseDirectAeronCapacityOptions(arrayOf("--probe"))

        assertEquals(1, smoke.warmup)
        assertEquals(2, smoke.orders)
        assertEquals(listOf(10), smoke.rates)
        assertEquals(200, probe.warmup)
        assertEquals(2000, probe.orders)
        assertEquals(listOf(1000, 5000, 10_000, 20_000), probe.rates)
        assertEquals(4096, probe.maxInFlight)
        assertFailsWith<IllegalArgumentException> { parseDirectAeronCapacityOptions(emptyArray()) }
        assertFailsWith<IllegalArgumentException> { parseDirectAeronCapacityOptions(arrayOf("--sustained")) }
        assertFailsWith<IllegalArgumentException> { parseDirectAeronCapacityOptions(arrayOf("--smoke", "--probe")) }
    }

    @Test
    fun `명시적 sustained만 N을 rate 곱하기 고정 60초로 계획하고 최대 600k까지만 연다`() {
        val first = parseDirectAeronCapacityOptions(arrayOf("--sustained", "--rate=1000", "--seconds=60"))
        val goal = parseDirectAeronCapacityOptions(arrayOf("--sustained", "--rate=10000", "--seconds=60"))
        assertTrue(first.sustained)
        assertEquals(200, first.warmup)
        assertEquals(60_000, first.orders)
        assertEquals(listOf(1000), first.rates)
        assertEquals(600_000, goal.orders)
        assertEquals(listOf(10_000), goal.rates)
        assertEquals(4096, first.maxInFlight)
        assertEquals(4096, goal.maxInFlight)
        listOf(
            arrayOf("--sustained"),
            arrayOf("--sustained", "--rate=1000"),
            arrayOf("--sustained", "--rate=1000", "--seconds=59"),
            arrayOf("--sustained", "--rate=10001", "--seconds=60"),
            arrayOf("--sustained", "--rate=0", "--seconds=60"),
            arrayOf("--sustained", "--rate=2147483647", "--seconds=60"),
            arrayOf("--sustained", "--rate=1000", "--rate=1000"),
            arrayOf("--sustained", "--rate=1000", "--seconds=60", "--probe"),
        ).forEach { args -> assertFailsWith<IllegalArgumentException> { parseDirectAeronCapacityOptions(args) } }
        assertFailsWith<IllegalArgumentException> { DirectAeronCapacityOptions(true, 1000, 60) }
        assertFailsWith<IllegalArgumentException> { DirectAeronCapacityOptions(false, sustainedRate = 1000) }
    }

    @Test
    fun `ACK 분포와 실제 시작률은 음수여도 같은 client 시계의 차분으로 계산한다`() {
        val result = clientResult(accepted = 2)

        val metrics = directAeronClientMetrics(result, 10, 2, 4096)
        val latency = metrics.getValue("ackRtt") as Map<*, *>

        assertEquals(10.0, metrics["actualStartedWindowRate"])
        assertEquals(10.0, metrics["transportAcceptedWindowRate"])
        assertEquals(10.0, metrics["acceptedAckWindowRate"])
        assertEquals(2, latency["sampleCount"])
        assertEquals(0, latency["cohortWithoutAcceptedLatencyCount"])
        assertEquals(0, latency["resultUnknownCount"])
        assertEquals(4_000_000L, latency["p99Ns"])
        assertTrue((metrics["scope"] as String).contains("no execution completion latency"))
    }

    @Test
    fun `미확인 ACK를 0 지연으로 채우지 않고 조건부 표본과 고정 cohort의 누락 수를 함께 남긴다`() {
        val metrics = directAeronClientMetrics(clientResult(accepted = 1), 10, 2, 4096)
        val latency = metrics.getValue("ackRtt") as Map<*, *>

        assertEquals(1, latency["sampleCount"])
        assertEquals(2, latency["cohortSize"])
        assertEquals(1, latency["cohortWithoutAcceptedLatencyCount"])
        assertEquals(1, latency["resultUnknownCount"])
        assertEquals(4_000_000L, latency["p99Ns"])
        assertTrue((latency["scope"] as String).contains("conditional"))
        assertEquals(5.0, metrics["acceptedAckWindowRate"])
    }

    @Test
    fun `확실한 미발송은 ACK 지연 cohort의 누락이지만 결과 미확인으로 세지 않는다`() {
        val metrics = directAeronClientMetrics(clientResult(accepted = 1, knownNotSent = true), 10, 2, 4096)
        val latency = metrics.getValue("ackRtt") as Map<*, *>

        assertEquals(1, latency["sampleCount"])
        assertEquals(1, latency["cohortWithoutAcceptedLatencyCount"])
        assertEquals(1, latency["notSentCount"])
        assertEquals(0, latency["rejectedCount"])
        assertEquals(0, latency["offerUnknownCount"])
        assertEquals(0, latency["resultUnknownCount"])
        assertEquals(1, metrics["notSent"])
        assertEquals(0, metrics["offerUnknown"])
        assertEquals(0, metrics["resultUnknown"])
        assertFalse(latency.containsKey("unknownCount"))
        assertEquals(4_000_000L, latency["p99Ns"])
        assertTrue((latency["scope"] as String).contains("conditional"))
    }

    @Test
    fun `알려진 부분 승인 세 사본이면 drain은 끝낼 수 있지만 전체 N 완료 판정은 유지한다`() {
        val observer = DirectAeronMarkerObservations("run1", 2)
        (0..2).forEach { member -> observer.observe(observation(member, 0, 1000L + member * 100).marker, 1000L + member * 100) }

        assertTrue(observer.complete(setOf("M-0")))
        assertFalse(observer.complete())
        val fullCohort = directAeronMarkerSummary(observer.snapshot(), 2, 2000, mapOf("M-0" to 10L))
        assertTrue((fullCohort.getValue("errors") as List<*>).isNotEmpty())
    }

    @Test
    fun `불확인 ID는 실제 세 노드 표식이 모두 올 때까지 drain 완료로 보지 않는다`() {
        val observer = DirectAeronMarkerObservations("run1", 2)
        (0..2).forEach { member -> observer.observe(observation(member, 0, 1000L + member * 100).marker, 1000L + member * 100) }
        val possibleIds = setOf("M-0", "M-1")

        assertFalse(observer.complete(possibleIds))
        observer.observe(observation(0, 1, 3000).marker, 3000)
        observer.observe(observation(1, 1, 3100).marker, 3100)
        assertFalse(observer.complete(possibleIds))
        observer.observe(observation(2, 1, 3200).marker, 3200)
        assertTrue(observer.complete(possibleIds))
        assertTrue(observer.complete())
    }

    @Test
    fun `부모는 세 노드 payload와 접수 순번을 대조하고 자기 수신 시각의 도착 간격만 보고한다`() {
        val raw =
            (0..2).flatMap { member ->
                listOf(
                    observation(member, 0, 1000L + member * 100),
                    observation(
                        member,
                        1,
                        3000L + member * 100,
                    ),
                )
            }

        val summary = directAeronMarkerSummary(raw, 2, 4000, mapOf("M-0" to 10L, "M-1" to 11L))
        val members = summary.getValue("members") as Map<*, *>
        val node0 = members[0] as Map<*, *>

        assertEquals(emptyList<String>(), summary["errors"])
        assertEquals(2, node0["receivedWithinDrain"])
        assertEquals(2000L, node0["interarrivalIntervalNs"])
        assertEquals(500_000.0, node0["interarrivalRate"])
        assertTrue((summary["clock"] as String).contains("never subtract client clock"))
        assertFalse(summary.containsKey("completionLatencyNs"))
    }

    @Test
    fun `drain 뒤 표식이나 ACK와 다른 실행 순번으로 세 사본 완료를 소급해 채우지 않는다`() {
        val raw =
            (0..2).flatMap { member ->
                listOf(
                    observation(member, 0, 1000L + member * 100),
                    observation(
                        member,
                        1,
                        3000L + member * 100,
                    ),
                )
            }

        val afterDrain = directAeronMarkerSummary(raw, 2, 3100, mapOf("M-0" to 10L, "M-1" to 11L))
        val wrongSequence = directAeronMarkerSummary(raw, 2, 4000, mapOf("M-0" to 99L, "M-1" to 11L))

        assertEquals(1, afterDrain["receivedAfterDrain"])
        assertTrue((afterDrain.getValue("errors") as List<*>).isNotEmpty())
        assertTrue((wrongSequence.getValue("errors") as List<*>).isNotEmpty())
    }

    @Test
    fun `부모 raw 상한과 원본 검사는 예상 밖 측정 요청을 성공 개수에 넣지 않는다`() {
        val observer = DirectAeronMarkerObservations("run1", 1)
        observer.observe(observation(0, 0, 100).marker, 100)
        observer.observe(observation(1, 0, 200).marker, 200)
        observer.observe(observation(2, 0, 300).marker, 300)
        assertTrue(observer.complete())
        observer.observe(observation(0, 0, 400).marker.copy(admissionSequence = 99), 400)
        assertEquals(3, observer.snapshot().size)
        assertTrue(observer.errors().any { it.startsWith("MARKER_OBSERVATION_OVERFLOW:") })
        val invalid = DirectAeronMarkerObservations("run1", 1)
        invalid.observe(observation(0, 1, 500).marker, 500)
        assertFalse(invalid.complete())
        assertTrue(invalid.errors().any { it.startsWith("INVALID_MEASURED_MARKER:") })
    }

    /** Parser fixture, not an engine/financial throughput result. */
    private fun sustainedRawFixture(): Pair<DirectAeronCapacityResult, JsonNode> {
        val workload = DirectAeronCapacityWorkload(1, 60, "M-", Side.BUY, "BUYER", 4096, sustainedSeconds = 60)
        val first = Long.MAX_VALUE - 30_000_000_000L
        val base = admissionCommand("M-0", "M-0")
        val samples =
            List(60) { index ->
                val id = "M-$index"
                val command =
                    base.copy(
                        requestId = id,
                        order =
                            base.order.copy(
                                orderId = OrderId(id),
                                userId = UserId("BUYER"),
                                side = Side.BUY,
                                price = Price(10_000),
                                quantity = Quantity(100_000_000),
                            ),
                    )
                val outcome =
                    when (index) {
                        1 -> DirectAeronCapacityOutcome.NOT_SENT
                        2 -> DirectAeronCapacityOutcome.REJECTED
                        3 -> DirectAeronCapacityOutcome.OFFER_UNKNOWN
                        4 -> DirectAeronCapacityOutcome.RESULT_UNKNOWN
                        else -> DirectAeronCapacityOutcome.ACCEPTED
                    }
                val due = first + index * 1_000_000_000L
                val started = outcome != DirectAeronCapacityOutcome.NOT_SENT
                val transported = started && outcome != DirectAeronCapacityOutcome.OFFER_UNKNOWN
                val acknowledged = outcome == DirectAeronCapacityOutcome.ACCEPTED || outcome == DirectAeronCapacityOutcome.REJECTED
                val response =
                    when (outcome) {
                        DirectAeronCapacityOutcome.ACCEPTED -> {
                            AdmissionResultPayload.Accepted("BUYER", id, "BTC-USDT", id, 10L + index)
                        }

                        DirectAeronCapacityOutcome.REJECTED -> {
                            AdmissionResultPayload.Rejected(
                                "BUYER",
                                id,
                                "BTC-USDT",
                                id,
                                AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE,
                            )
                        }

                        else -> {
                            null
                        }
                    }
                DirectAeronCapacitySample(
                    command,
                    if (started) AdmissionCommandCodec().encode(command) else null,
                    due,
                    if (started) due else null,
                    if (started) due + 1_000_000 else null,
                    if (started) due + 1 else null,
                    if (transported) due + 2 else null,
                    if (transported) 17 else null,
                    if (transported) 17 else null,
                    if (started) 1 else 0,
                    if (acknowledged) due + 3 else null,
                    response,
                    outcome,
                    null,
                )
            }
        val result =
            DirectAeronCapacityResult(
                workload,
                first,
                first + workload.windowNs,
                first + workload.windowNs + 100,
                60,
                59,
                1,
                58,
                1,
                2,
                56,
                1,
                1,
                0,
                null,
                null,
                emptyList(),
                0,
                samples,
            )
        val raw = writeDirectAeronSustainedSamples(directory.resolve("direct-client-samples.jsonl"), "direct-test", samples)
        val report =
            jacksonObjectMapper().valueToTree<JsonNode>(
                mapOf(
                    "schemaVersion" to 2,
                    "kind" to "DIRECT_AERON_ADMISSION_SUSTAINED",
                    "runId" to "direct-test",
                    "workload" to workload,
                    "startupFailure" to null,
                    "loadStarted" to true,
                    "succeeded" to false,
                    "rawArtifact" to raw,
                    "rawFailure" to null,
                    "result" to result.copy(samples = emptyList()),
                ),
            )
        return result to report
    }

    private fun observation(
        member: Int,
        order: Int,
        receivedNs: Long,
    ): DirectAeronMarkerObservation =
        DirectAeronMarkerObservation(
            HttpPerformanceCompletion(
                "run1",
                member,
                if (member ==
                    0
                ) {
                    "LEADER"
                } else {
                    "FOLLOWER"
                },
                "BUYER",
                "M-$order",
                "M-$order",
                10L + order,
                500L + order,
                1,
                1,
            ),
            receivedNs,
        )

    private fun clientResult(
        accepted: Int,
        knownNotSent: Boolean = false,
    ): JsonNode {
        val firstDue = -9_000_000_000_000L
        val samples =
            List(2) { index ->
                mapOf(
                    "command" to mapOf("requestId" to "M-$index"),
                    "startNs" to if (index >= accepted && knownNotSent) null else firstDue + index * 100_000_000L + 1_000_000,
                    "transportAcceptedNs" to if (index >= accepted && knownNotSent) null else firstDue + index * 100_000_000L + 2_000_000,
                    "ackNs" to if (index < accepted) firstDue + index * 100_000_000L + 5_000_000 else null,
                    "outcome" to
                        if (index < accepted) {
                            "ACCEPTED"
                        } else if (knownNotSent) {
                            "NOT_SENT"
                        } else {
                            "RESULT_UNKNOWN"
                        },
                )
            }
        return jacksonObjectMapper().valueToTree(
            mapOf(
                "workload" to mapOf("rate" to 10, "count" to 2, "maxInFlight" to 4096),
                "planned" to 2,
                "started" to if (knownNotSent) accepted else 2,
                "transportAccepted" to if (knownNotSent) accepted else 2,
                "accepted" to accepted,
                "rejected" to 0,
                "notSent" to if (knownNotSent) 2 - accepted else 0,
                "offerUnknown" to 0,
                "resultUnknown" to if (knownNotSent) 0 else 2 - accepted,
                "firstDueNs" to firstDue,
                "windowEndNs" to (firstDue + 200_000_000),
                "finishedNs" to (firstDue + 210_000_000),
                "peakInFlight" to 2,
                "samples" to samples,
            ),
        )
    }
}
