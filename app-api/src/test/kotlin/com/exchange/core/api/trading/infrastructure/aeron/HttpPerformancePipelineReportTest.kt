package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpPerformancePipelineReportTest {
    @TempDir
    lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private val run = "pipeline-test"

    @Test
    fun `응답 헤더 본문 완료와 HTTP callback 사이 지연은 같은 부하 JVM에서 계산한다`() {
        fixture()
        addResponsePoints()
        val errors = writeHttpPerformancePipelineReport(directory, run, 2)
        assertTrue(errors.isEmpty(), errors.toString())
        val client = report().path("rows").first().path("client")
        assertEquals(290L, client.path("bodyPublicationToResponseHeadersNs").asLong())
        assertEquals(50L, client.path("responseHeadersToFirstBodyNextNs").asLong())
        assertEquals(10L, client.path("responseBodyFirstToLastNextNs").asLong())
        assertEquals(10L, client.path("responseBodyLastNextToOnCompleteNs").asLong())
        assertEquals(70L, client.path("responseHeadersToOnCompleteNs").asLong())
        assertEquals(10L, client.path("responseHeadersToGetBodyNs").asLong())
        assertEquals(10L, client.path("responseOnCompleteToBodyCompletedNs").asLong())
        assertEquals(220L, client.path("responseBodyCompletedToCallbackNs").asLong())
        assertEquals(
            2,
            report()
                .path("stageSummaries")
                .path("client.response_body_completed_to_callback")
                .path("sampleCount")
                .asInt(),
        )
    }

    @Test
    fun `이전 응답 계측이 없는 원문은 선택 구간을 0으로 생성하지 않는다`() {
        fixture()
        assertTrue(writeHttpPerformancePipelineReport(directory, run, 2).isEmpty())
        assertTrue(
            report()
                .path("rows")
                .first()
                .path("client")
                .path("responseBodyCompletedToCallbackNs")
                .isNull,
        )
        assertFalse(report().path("stageSummaries").has("client.response_body_completed_to_callback"))
    }

    @Test
    fun `빈 응답에는 onNext가 없어도 되며 부분 응답 계측과 음수 callback 차이는 오류다`() {
        fixture()
        addResponsePoints()
        mutate("client-stages.json") { root ->
            root.path("samples").forEach { row ->
                val sample = row as tools.jackson.databind.node.ObjectNode
                sample.putNull("responseBodyFirstNextNs")
                sample.putNull("responseBodyLastNextNs")
            }
        }
        assertTrue(writeHttpPerformancePipelineReport(directory, run, 2).isEmpty())
        mutate("client-stages.json") { root ->
            val first = root.path("samples").first() as tools.jackson.databind.node.ObjectNode
            first.remove("responseBodyOnCompleteNs")
            first.put("responseBodyCompletedNs", first.path("callbackEnteredNs").asLong() + 1)
        }
        val errors = writeHttpPerformancePipelineReport(directory, run, 2)
        assertTrue(errors.contains("MISSING_OR_NONINTEGER:client:M-0:responseBodyOnCompleteNs"), errors.toString())
        assertTrue(errors.contains("NEGATIVE_DURATION:client.response_body_completed_to_callback:M-0"), errors.toString())
    }

    private fun addResponsePoints() {
        mutate("client-stages.json") { root ->
            root.path("samples").forEachIndexed { index, row ->
                val sample = row as tools.jackson.databind.node.ObjectNode
                val start = -1000L + index * 10_000
                sample.put("responseHeadersEnteredNs", start + 300)
                sample.put("responseBodyFirstNextNs", start + 350)
                sample.put("responseBodyLastNextNs", start + 360)
                sample.put("responseBodyOnCompleteNs", start + 370)
                sample.put("responseBodyCompletedNs", start + 380)
                sample.put("responseBodyGetBodyEnteredNs", start + 310)
            }
        }
    }

    @Test
    fun `요청과 순번으로 연결한 구간을 먼저 계산한 뒤 분위수를 구하고 JVM 원점은 섞지 않는다`() {
        fixture()
        val errors = writeHttpPerformancePipelineReport(directory, run, 2)
        assertTrue(errors.isEmpty(), errors.toString())
        val report = report()
        assertEquals("VALID", report.path("status").asString())
        assertEquals(2, report.path("coverage").path("clientHttp").asInt())
        val rows = report.path("rows").toList()
        assertEquals(listOf(10L, 290L), rows.map { it.path("gatewayRoundTripMinusLeaderCallbackNs").asLong() })
        val summary = report.path("comparisonSummaries").path("gatewayRoundTripMinusLeaderCallbackNs")
        assertEquals(10L, summary.path("p50Ns").asLong())
        assertEquals(290L, summary.path("p99Ns").asLong())
        assertEquals(
            5L,
            rows
                .first()
                .path("client")
                .path("startToBodyPublicationNs")
                .asLong(),
        )
        assertEquals(
            10L,
            rows
                .first()
                .path("server")
                .path("containerToMvcNs")
                .asLong(),
        )
        assertEquals(3, rows.first().path("nodes").size())
        assertEquals(
            2,
            report
                .path("stageSummaries")
                .path("node2.execution.matching_candidate")
                .path("sampleCount")
                .asInt(),
        )
        assertTrue(
            report
                .path("archiveRecorderCounters")
                .path("2")
                .path("cohortBracket")
                .path("complete")
                .asBoolean(),
        )
        assertTrue(Files.readString(directory.resolve("pipeline-stages.md")).contains("phases overlap"))
    }

    @Test
    fun `Gateway 응답이 leader callback 종료보다 빠른 음수 비교도 정상이며 지연 분위수에 섞지 않는다`() {
        fixture()
        mutate("checkpoint-final.json") { root ->
            val admission =
                root
                    .path("0")
                    .path("callbackTimings")
                    .path("raw")
                    .first() as tools.jackson.databind.node.ObjectNode
            admission.put("productCallbackNs", 190)
            admission.put("callbackCompletedNs", admission.path("callbackStartedNs").asLong() + 190)
        }
        val errors = writeHttpPerformancePipelineReport(directory, run, 2)
        assertTrue(errors.isEmpty(), errors.toString())
        val result = report()
        assertEquals("VALID", result.path("status").asString())
        assertEquals(
            -90L,
            result
                .path("rows")
                .first()
                .path("gatewayRoundTripMinusLeaderCallbackNs")
                .asLong(),
        )
        val comparison = result.path("comparisonSummaries").path("gatewayRoundTripMinusLeaderCallbackNs")
        assertEquals(-90L, comparison.path("p50Ns").asLong())
        assertEquals(1, comparison.path("negativeCount").asInt())
        assertTrue(comparison.path("signed").asBoolean())
        assertFalse(result.path("stageSummaries").has("gatewayRoundTripMinusLeaderCallbackNs"))
        assertFalse(result.path("stageSummaries").properties().any { "unattributed" in it.key })
    }

    @Test
    fun `기록 commit service 위치는 실제 label과 stream 식별로 연결하고 다른 subscription은 제외한다`() {
        fixture()
        val errors = writeHttpPerformancePipelineReport(directory, run, 2)
        assertTrue(errors.isEmpty(), errors.toString())
        val positions = report().path("positionEvidence").path("0")
        val identity = positions.path("identity")
        assertEquals("RESOLVED_BY_LABEL", identity.path("status").asString())
        assertEquals(7L, identity.path("recordingId").asLong())
        assertEquals(3, identity.path("recordingDefinitionIndex").asInt())
        assertEquals(4, identity.path("commitDefinitionIndex").asInt())
        assertEquals(5, identity.path("serviceDefinitionIndex").asInt())
        assertEquals(
            setOf(3, 4, 5, 7),
            positions
                .path("definitions")
                .toList()
                .map { it.path("definitionIndex").asInt() }
                .toSet(),
        )
        assertEquals(
            setOf(3, 4, 5, 7),
            positions
                .path("frames")
                .first()
                .path("values")
                .toList()
                .map { it.path("definitionIndex").asInt() }
                .toSet(),
        )
        assertEquals(
            100L,
            positions
                .path("callbackPositions")
                .path("ADMISSION")
                .path("firstPosition")
                .asLong(),
        )
        assertEquals(
            201L,
            positions
                .path("callbackPositions")
                .path("EXECUTION")
                .path("lastPosition")
                .asLong(),
        )
    }

    @Test
    fun `같은 stream의 recording 후보가 둘이면 원문 위치는 남기고 하나를 임의로 고르지 않는다`() {
        fixture()
        addPositionCounters(ambiguousRecording = true)
        val errors = writeHttpPerformancePipelineReport(directory, run, 2)
        assertTrue(errors.contains("POSITION_IDENTITY_UNRESOLVED:node0"), errors.toString())
        assertEquals("ERROR", report().path("status").asString())
        val positions = report().path("positionEvidence").path("0")
        assertEquals("UNRESOLVED", positions.path("identity").path("status").asString())
        assertEquals(2, positions.path("identity").path("recordingCandidateCount").asInt())
        assertTrue(positions.path("identity").path("recordingDefinitionIndex").isNull)
        assertTrue(positions.path("identity").path("recordingId").isNull)
        assertEquals(5, positions.path("definitions").size())
    }

    @Test
    fun `필수 위치 counter의 definition만 있고 관측값이 없으면 성공으로 판정하지 않는다`() {
        fixture()
        mutate("checkpoint-final.json") { root ->
            root.path("0").path("runtimeCounters").path("frames").forEach { frame ->
                (frame.path("values") as tools.jackson.databind.node.ArrayNode).remove(4)
            }
        }
        val errors = writeHttpPerformancePipelineReport(directory, run, 2)
        assertTrue(errors.contains("POSITION_COUNTER_VALUES_MISSING:node0"), errors.toString())
        assertEquals("ERROR", report().path("status").asString())
    }

    @Test
    fun `코호트 이전 counter frame이 없으면 원문을 보존하고 ERROR로 남긴다`() {
        fixture()
        mutate("checkpoint-final.json") { root ->
            (root.path("0").path("runtimeCounters").path("frames") as tools.jackson.databind.node.ArrayNode).remove(0)
        }
        val errors = writeHttpPerformancePipelineReport(directory, run, 2)
        assertTrue(errors.contains("ARCHIVE_COHORT_BEFORE_FRAME_MISSING:node0"), errors.toString())
        val result = report()
        assertEquals("ERROR", result.path("status").asString())
        val bracket = result.path("archiveRecorderCounters").path("0").path("cohortBracket")
        assertFalse(bracket.path("complete").asBoolean())
        assertTrue(bracket.path("beforeFrame").isNull)
        assertTrue(bracket.path("afterFrame").isObject)
    }

    @Test
    fun `코호트 이후 counter frame이 없으면 원문을 보존하고 ERROR로 남긴다`() {
        fixture()
        mutate("checkpoint-final.json") { root ->
            (root.path("0").path("runtimeCounters").path("frames") as tools.jackson.databind.node.ArrayNode).remove(1)
        }
        val errors = writeHttpPerformancePipelineReport(directory, run, 2)
        assertTrue(errors.contains("ARCHIVE_COHORT_AFTER_FRAME_MISSING:node0"), errors.toString())
        val result = report()
        assertEquals("ERROR", result.path("status").asString())
        val bracket = result.path("archiveRecorderCounters").path("0").path("cohortBracket")
        assertFalse(bracket.path("complete").asBoolean())
        assertTrue(bracket.path("beforeFrame").isObject)
        assertTrue(bracket.path("afterFrame").isNull)
    }

    @Test
    fun `body 관측 누락 음수 구간 순번 불일치와 중복 노드 표본을 정상 0으로 채우지 않는다`() {
        fixture()
        mutate("client-stages.json") { root ->
            val first = root.path("samples").first() as tools.jackson.databind.node.ObjectNode
            first.remove("bodyPublishCompletedNs")
            first.put("lockAcquiredNs", first.path("callbackEnteredNs").asLong() - 1)
        }
        mutate("gateway-stages.json") { root ->
            (root.path("requestTimings").first() as tools.jackson.databind.node.ObjectNode).put("admissionSequence", 999)
        }
        mutate("checkpoint-final.json") { root ->
            val raw = root.path("2").path("callbackTimings").path("raw") as tools.jackson.databind.node.ArrayNode
            raw.add(raw.first())
        }
        val errors = writeHttpPerformancePipelineReport(directory, run, 2)
        assertTrue(errors.any { it.contains("bodyPublishCompletedNs") })
        assertTrue(errors.any { it.startsWith("NEGATIVE_DURATION:client.observation_lock") })
        assertTrue(errors.any { it == "GATEWAY_IDENTITY_OR_SEQUENCE:M-0" })
        assertTrue(errors.any { it == "DUPLICATE_IDENTITY:node2.ADMISSION:M-0" })
        assertEquals("ERROR", report().path("status").asString())
        assertTrue(
            report()
                .path("rows")
                .first()
                .path("client")
                .path("bodyPublicationNs")
                .isNull,
        )
        assertTrue(
            report()
                .path("rows")
                .first()
                .path("gatewayRoundTripMinusLeaderCallbackNs")
                .isNull,
        )
        assertTrue(
            report()
                .path("rows")
                .first()
                .path("nodes")
                .path("2")
                .path("ADMISSION")
                .path("productCallbackNs")
                .isNull,
        )
    }

    @Test
    fun `없는 자료와 알려진 실패는 빈 성공이 되지 않고 애매한 리더의 잔여도 계산하지 않는다`() {
        val missing = writeHttpPerformancePipelineReport(directory, run, 2)
        assertFalse(missing.isEmpty())
        assertEquals("ERROR", report().path("status").asString())
        fixture()
        mutate("requests.jsonl", jsonLines = true) { row ->
            (row as tools.jackson.databind.node.ObjectNode).put("httpOutcome", "HTTP_TIMEOUT")
        }
        mutate("checkpoint-final.json") { root ->
            (root.path("1") as tools.jackson.databind.node.ObjectNode).put("role", "LEADER")
        }
        val errors = writeHttpPerformancePipelineReport(directory, run, 2)
        assertTrue(errors.any { it.startsWith("REQUEST_NOT_ACCEPTED_AND_SETTLED") })
        assertTrue(errors.contains("LEADER_ROLE_COVERAGE"))
        assertTrue(report().path("rows").all { it.path("gatewayRoundTripMinusLeaderCallbackNs").isNull })
    }

    private fun fixture() {
        val requests =
            (0..1).map { index ->
                val start = -1000L + index * 10_000
                mapOf(
                    "userId" to "BUYER",
                    "requestId" to "M-$index",
                    "orderId" to "M-$index",
                    "admissionSequence" to index + 1,
                    "startNs" to start,
                    "httpResultNs" to start + 610,
                    "doneNs" to start + 550,
                    "httpOutcome" to "ACCEPTED",
                    "tradeCount" to 1,
                    "settlementCount" to 1,
                )
            }
        Files.writeString(
            directory.resolve("requests.jsonl"),
            requests.joinToString("\n", postfix = "\n") { mapper.writeValueAsString(it) },
        )
        save(
            "client-stages.json",
            mapOf(
                "enabled" to true,
                "overflow" to 0,
                "errors" to emptyList<String>(),
                "samples" to
                    (0..1).map { index ->
                        val start = -1000L + index * 10_000
                        mapOf(
                            "kind" to "HTTP",
                            "userId" to "BUYER",
                            "requestId" to "M-$index",
                            "callbackEnteredNs" to start + 600,
                            "lockAcquiredNs" to start + 605,
                            "interpretationFinishedNs" to start + 610,
                            "bodyPublishStartedNs" to start + 5,
                            "bodyPublishCompletedNs" to start + 10,
                            "bodySubscriptions" to 1,
                        )
                    },
            ),
        )
        val server =
            mapOf(
                "runId" to run,
                "errors" to emptyList<String>(),
                "status" to "CLOSED",
                "enteredCount" to 2,
                "completedCount" to 2,
                "observerFailureCount" to 0,
                "spans" to
                    (0..1).map { index ->
                        val start = 90_000_000L + index * 1000
                        mapOf(
                            "requestId" to "M-$index",
                            "gatewayLocalPoints" to
                                mapOf(
                                    "clock" to "gateway JVM System.nanoTime only",
                                    "tomcatEnteredNs" to start,
                                    "mvcEnteredNs" to start + 10,
                                    "asyncResultReadyNs" to start + 405,
                                    "mvcRedispatchEnteredNs" to start + 450,
                                    "completedNs" to start + 500,
                                ),
                        )
                    },
            )
        save(
            "gateway-stages.json",
            mapOf(
                "runId" to run,
                "errors" to emptyList<String>(),
                "status" to "CLOSED",
                "overflowCount" to 0,
                "recordingInProgress" to 0,
                "httpBoundaries" to server,
                "requestTimings" to
                    (0..1).map { index ->
                        val start = 90_000_000L + index * 1000
                        mapOf(
                            "userId" to "BUYER",
                            "requestId" to "M-$index",
                            "orderId" to "M-$index",
                            "admissionSequence" to index + 1,
                            "enqueuedNs" to start + 20,
                            "resultReadyNs" to start + 400,
                            "completedNs" to start + 410,
                            "phaseDurationsNs" to
                                HTTP_GATEWAY_TIMER_PHASES.associateWith {
                                    if (it == "response_wait") {
                                        if (index == 0) {
                                            100L
                                        } else {
                                            300L
                                        }
                                    } else {
                                        1L
                                    }
                                },
                        )
                    },
            ),
        )
        save(
            "checkpoint-final.json",
            (0..2).associate { member ->
                val nodeOrigin = 1_000_000_000L + member * 1_000_000_000_000L
                member.toString() to
                    mapOf(
                        "runId" to run,
                        "memberId" to member,
                        "role" to if (member == 0) "LEADER" else "FOLLOWER",
                        "collectorFailure" to null,
                        "callbackTimings" to
                            mapOf(
                                "raw" to
                                    (0..1).flatMap { index ->
                                        listOf("ADMISSION", "EXECUTION").map { kind ->
                                            val required =
                                                if (kind ==
                                                    "ADMISSION"
                                                ) {
                                                    listOf(
                                                        "callback",
                                                        "decode",
                                                        "admission_context",
                                                        "admission_apply",
                                                        "execution_encode",
                                                        "execution_offer",
                                                        "reply_encode",
                                                        "reply_offer",
                                                    )
                                                } else {
                                                    listOf(
                                                        "callback",
                                                        "decode",
                                                        "execution_total",
                                                        "matching_candidate",
                                                        "settlement_input_validation",
                                                        "settlement_candidate",
                                                        "result_validation",
                                                        "execution_apply",
                                                        "completion",
                                                    )
                                                }
                                            mapOf(
                                                "kind" to kind,
                                                "userId" to "BUYER",
                                                "requestId" to "M-$index",
                                                "admissionSequence" to index + 1,
                                                "position" to 100 + index + if (kind == "EXECUTION") 100 else 0,
                                                "productCallbackNs" to if (index == 0) 90L else 10L,
                                                "observerNs" to 1L,
                                                "callbackStartedNs" to nodeOrigin + index * 1000 + if (kind == "EXECUTION") 500 else 0,
                                                "callbackCompletedNs" to
                                                    nodeOrigin + index * 1000 + (if (kind == "EXECUTION") 500 else 0) +
                                                    (if (index == 0) 90L else 10L),
                                                "admissionToExecutionGapNs" to if (kind == "EXECUTION") 10L else null,
                                                "phases" to required.associateWith { 1L },
                                                "replyStatus" to if (member == 0) "OFFERED" else "NOT_LEADER",
                                            )
                                        }
                                    },
                            ),
                        "runtimeCounters" to
                            mapOf(
                                "originNs" to nodeOrigin - 100,
                                "definitions" to listOf(105, 106, 107).map { mapOf("typeId" to it) },
                                "frames" to
                                    listOf(0, 1).map { n ->
                                        mapOf(
                                            "elapsedNs" to n * 2000L,
                                            "readDurationNs" to 1L,
                                            "values" to listOf(0, 1, 2).map { mapOf("definitionIndex" to it, "value" to n * 100L + it) },
                                        )
                                    },
                                "errors" to emptyList<String>(),
                                "overflowCount" to 0,
                            ),
                    )
            },
        )
        (0..2).forEach { member -> addPositionCounters(ambiguousRecording = false, member = member) }
    }

    private fun save(
        name: String,
        value: Any,
    ) {
        Files.writeString(directory.resolve(name), mapper.writeValueAsString(value))
    }

    private fun addPositionCounters(
        ambiguousRecording: Boolean,
        member: Int = 0,
    ) {
        mutate("checkpoint-final.json") { root ->
            val runtime = root.path(member.toString()).path("runtimeCounters")
            val definitions = runtime.path("definitions") as tools.jackson.databind.node.ArrayNode
            val firstIndex = definitions.size()
            val added =
                if (ambiguousRecording) {
                    listOf(100 to "rec-pos: 8 42 100 aeron:ipc?alias=log - archiveId=97")
                } else {
                    listOf(
                        100 to "rec-pos: 7 42 100 aeron:ipc?alias=log - archiveId=97",
                        203 to "Cluster commit-pos: - clusterId=97",
                        4 to "sub-pos: 99 42 100 aeron:ipc?alias=log-sc-0 @ 0",
                        4 to "sub-pos: 98 50 101 aeron:ipc?alias=unrelated @ 0",
                        203 to "Cluster commit-pos: - clusterId=98",
                    )
                }
            added.forEach { (type, label) -> definitions.add(mapper.createObjectNode().put("typeId", type).put("label", label)) }
            runtime.path("frames").forEach { frame ->
                val values = frame.path("values") as tools.jackson.databind.node.ArrayNode
                added.indices.forEach { index ->
                    values.add(
                        mapper.createObjectNode().put("definitionIndex", index + firstIndex).put(
                            "value",
                            500L + index,
                        ),
                    )
                }
            }
        }
    }

    private fun mutate(
        name: String,
        jsonLines: Boolean = false,
        change: (JsonNode) -> Unit,
    ) {
        val path = directory.resolve(name)
        if (jsonLines) {
            val rows = Files.readAllLines(path).map { mapper.readTree(it).also(change) }
            Files.writeString(path, rows.joinToString("\n", postfix = "\n") { mapper.writeValueAsString(it) })
        } else {
            val row = mapper.readTree(Files.readString(path)).also(change)
            save(name, row)
        }
    }

    private fun report(): JsonNode = mapper.readTree(Files.readString(directory.resolve("pipeline-stages.json")))
}
