package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.order.Side
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectAeronCapacityClientTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `장기 raw는 원본 sample별 JSONL과 닫힌 파일의 bytes SHA를 보존하며 덮어쓰지 않는다`() {
        val path = directory.resolve("direct-client-samples.jsonl")
        val sample = successfulResult().samples.single().copy(transportPosition = Long.MAX_VALUE - 31)
        val reference = writeDirectAeronSustainedSamples(path, "direct-test", listOf(sample))
        val bytes = Files.readAllBytes(path)
        val raw = jacksonObjectMapper().readTree(Files.readString(path).trimEnd())
        assertEquals("DIRECT_AERON_CAPACITY_SAMPLE", raw.path("kind").asString())
        assertEquals("direct-test", raw.path("runId").asString())
        assertEquals(0, raw.path("index").asInt())
        val expectedWire = jacksonObjectMapper().readTree(jacksonObjectMapper().writeValueAsBytes(sample))
        assertEquals(expectedWire, raw.path("sample"))
        assertEquals(Long.MAX_VALUE - 31, raw.path("sample").path("transportPosition").asLong())
        assertEquals(1, reference["records"])
        assertEquals(bytes.size.toLong(), reference["bytes"])
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), reference["sha256"])
        assertEquals(true, reference["complete"])
        assertTrue(bytes.last() == '\n'.code.toByte())
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { writeDirectAeronSustainedSamples(path, "direct-test", listOf(sample)) }
        assertEquals(raw, jacksonObjectMapper().readTree(Files.readString(path).trimEnd()))
    }

    @Test
    fun `기동 전 실패의 0행 raw는 가짜 N개 command를 만들지 않고 빈 파일 지문으로 남긴다`() {
        val path = directory.resolve("direct-client-samples.jsonl")
        val reference = writeDirectAeronSustainedSamples(path, "direct-test", emptyList())
        assertEquals(0, reference["records"])
        assertEquals(0L, reference["bytes"])
        assertEquals(0L, Files.size(path))
        assertEquals(true, reference["complete"])
    }

    @Test
    fun `장기 summary에서 줄인 오류 상세는 별도 JSONL에 전체 문자열로 보존한다`() {
        val complete = "error-detail".repeat(1000)
        val result = successfulResult().copy(errors = listOf(complete), loadFailure = "load-failure")
        val path = directory.resolve("direct-client-errors.jsonl")
        val reference = writeDirectAeronSustainedErrors(path, "direct-test", result, "startup-failure", "raw-failure")
        val rows = Files.readAllLines(path).map { jacksonObjectMapper().readTree(it) }
        assertEquals(4, reference["records"])
        assertEquals(listOf("STARTUP_FAILURE", "LOAD_FAILURE", "RAW_FAILURE", "LOAD_ERROR"), rows.map { it.path("kind").asString() })
        assertEquals(complete, rows.last().path("message").asString())
        assertEquals(true, reference["complete"])
    }

    @Test
    fun `장기 client CLI만 60k와 600k 고정 업무 계획을 허용한다`() {
        val first = parseDirectAeronCapacityClientArguments(arrayOf("--sustained", "--rate=1000", "--seconds=60", "M-", "BUYER", "4096"))
        assertEquals(60_000, first.count)
        assertEquals(60_000_000_000L, first.windowNs)
        assertEquals(60, first.sustainedSeconds)
        assertEquals(4096, first.maxInFlight)
        assertEquals(10_000L, first.price)
        assertEquals(100_000_000L, first.quantity)
        val goal = parseDirectAeronCapacityClientArguments(arrayOf("--sustained", "--rate=10000", "--seconds=60", "M-", "BUYER", "4096"))
        assertEquals(600_000, goal.count)
        listOf(
            arrayOf("--sustained", "--rate=1000", "--seconds=60", "M-", "BUYER", "4095"),
            arrayOf("--sustained", "--rate=1000", "--seconds=60", "S-", "BUYER", "4096"),
            arrayOf("--sustained", "--rate=1000", "--seconds=60", "M-", "SELLER", "4096"),
            arrayOf("--sustained", "--rate=10000", "--seconds=61", "M-", "BUYER", "4096"),
            arrayOf("--sustained", "--rate=10001", "--seconds=60", "M-", "BUYER", "4096"),
        ).forEach { args -> assertFailsWith<IllegalArgumentException> { parseDirectAeronCapacityClientArguments(args) } }
        assertFailsWith<IllegalArgumentException> {
            DirectAeronCapacityWorkload(1000, 60_000, "M-", Side.BUY, "BUYER", 4096)
        }
    }

    @Test
    fun `client CLI는 직접 BUY의 rate count 원본과 유한한 pending 상한만 받는다`() {
        val workload = parseDirectAeronCapacityClientArguments(arrayOf("20000", "2000", "M-", "BUYER", "4096"))

        assertEquals(20_000, workload.rate)
        assertEquals(2000, workload.count)
        assertEquals("M-", workload.prefix)
        assertEquals("BUYER", workload.userId)
        assertEquals(Side.BUY, workload.side)
        assertEquals(4096, workload.maxInFlight)
        assertEquals(1024, parseDirectAeronCapacityClientArguments(arrayOf("1000", "2000", "M-", "BUYER", "1024")).maxInFlight)
        assertEquals(10_000L, workload.price)
        assertEquals(100_000_000L, workload.quantity)
        assertFailsWith<IllegalArgumentException> { parseDirectAeronCapacityClientArguments(emptyArray()) }
        assertFailsWith<IllegalArgumentException> {
            parseDirectAeronCapacityClientArguments(
                arrayOf("20001", "2000", "M-", "BUYER", "1024"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseDirectAeronCapacityClientArguments(
                arrayOf("1000", "2001", "M-", "BUYER", "1024"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseDirectAeronCapacityClientArguments(
                arrayOf("1000", "2000", "M-", "BUYER", "4097"),
            )
        }
    }

    @Test
    fun `성공은 실제 원본에 맞는 ACK 전량과 무오류를 요구하며 양수 offer만으로 통과하지 않는다`() {
        val result = successfulResult()

        assertTrue(directAeronCapacityClientSucceeded(result))
        assertFalse(directAeronCapacityClientSucceeded(result.copy(accepted = 0, resultUnknown = 1)))
        assertFalse(directAeronCapacityClientSucceeded(result.copy(notSent = 1)))
        assertFalse(directAeronCapacityClientSucceeded(result.copy(rejected = 1)))
        assertFalse(directAeronCapacityClientSucceeded(result.copy(errors = listOf("observer error"))))
        assertFalse(directAeronCapacityClientSucceeded(result.copy(closeFailure = "close failed")))
        val wrong = (result.samples.single().response as AdmissionResultPayload.Accepted).copy(orderId = "OTHER")
        assertFalse(directAeronCapacityClientSucceeded(result.copy(samples = listOf(result.samples.single().copy(response = wrong)))))
    }

    @Test
    fun `원시 결과 JSON은 로그와 구분해 한 건만 복원하고 중복 및 다른 schema를 거절한다`() {
        val mapper = jacksonObjectMapper()
        val record =
            mapper.writeValueAsString(
                mapOf("schemaVersion" to 1, "kind" to "DIRECT_AERON_ADMISSION_BURST", "runId" to "run1", "result" to successfulResult()),
            )
        val line = DIRECT_AERON_CAPACITY_RESULT_PREFIX + record

        val parsed = readDirectAeronCapacityClientReport("ordinary log\n$line\n")

        assertEquals("run1", parsed.path("runId").asString())
        assertEquals(1, parsed.path("result").path("samples").size())
        assertEquals(
            "M-0",
            parsed
                .path("result")
                .path("samples")
                .path(0)
                .path("command")
                .path("requestId")
                .asString(),
        )
        assertFailsWith<IllegalArgumentException> { readDirectAeronCapacityClientReport("ordinary log") }
        assertFailsWith<IllegalArgumentException> { readDirectAeronCapacityClientReport("$line\n$line") }
        assertFailsWith<IllegalArgumentException> {
            readDirectAeronCapacityClientReport(
                DIRECT_AERON_CAPACITY_RESULT_PREFIX + "{\"schemaVersion\":2,\"kind\":\"DIRECT_AERON_ADMISSION_BURST\"}",
            )
        }
    }

    @Test
    fun `0전송 drain 생략은 load 진입 전 실패나 전부 미발송일 때만 허용한다`() {
        val mapper = jacksonObjectMapper()

        fun tree(value: Any): JsonNode = mapper.valueToTree(value)

        assertTrue(
            directAeronClientHadNoAcceptedOffer(
                tree(
                    mapOf(
                        "startupFailure" to "configuration failed",
                        "loadStarted" to false,
                        "result" to null,
                    ),
                ),
                2,
            ),
        )
        assertFalse(
            directAeronClientHadNoAcceptedOffer(
                tree(
                    mapOf(
                        "startupFailure" to "after load entry",
                        "loadStarted" to true,
                        "result" to null,
                    ),
                ),
                2,
            ),
        )
        assertFalse(directAeronClientHadNoAcceptedOffer(tree(mapOf("startupFailure" to "missing phase evidence", "result" to null)), 2))
        val noOffers =
            mapOf(
                "planned" to 2,
                "notSent" to 2,
                "transportAccepted" to 0,
                "offerUnknown" to 0,
                "resultUnknown" to 0,
                "samples" to
                    List(2) { mapOf("outcome" to "NOT_SENT", "transportAcceptedNs" to null, "transportPosition" to null, "ackNs" to null) },
            )
        assertTrue(directAeronClientHadNoAcceptedOffer(tree(mapOf("result" to noOffers)), 2))
        assertFalse(directAeronClientHadNoAcceptedOffer(tree(mapOf("result" to (noOffers + ("offerUnknown" to 1)))), 2))
        assertFalse(directAeronClientHadNoAcceptedOffer(tree(mapOf("result" to (noOffers + ("transportAccepted" to 1)))), 2))
    }

    @Test
    fun `부분 결과가 모두 알려졌으면 확실한 미발송과 거절을 뺀 승인 ID만 drain 대상으로 둔다`() {
        val report = executionReport(listOf("ACCEPTED", "NOT_SENT", "REJECTED"))

        assertEquals(setOf("M-0"), directAeronPossibleExecutionIds(report, 3))
    }

    @Test
    fun `offer와 결과 불확인 ID는 가능한 실행으로 남겨 부모의 제한 대기를 유지한다`() {
        val report = executionReport(listOf("ACCEPTED", "NOT_SENT", "REJECTED", "OFFER_UNKNOWN", "RESULT_UNKNOWN"))

        assertEquals(setOf("M-0", "M-3", "M-4"), directAeronPossibleExecutionIds(report, 5))
    }

    @Test
    fun `불완전하거나 모순인 결과는 예정 ID를 대기 대상에서 제외하지 않는다`() {
        val missing = jacksonObjectMapper().valueToTree<JsonNode>(mapOf("loadStarted" to true, "result" to null))
        assertEquals(setOf("M-0", "M-1"), directAeronPossibleExecutionIds(missing, 2))
        val known = executionReport(listOf("ACCEPTED", "NOT_SENT"))
        val contradictory =
            jacksonObjectMapper().valueToTree<JsonNode>(
                mapOf(
                    "result" to
                        mapOf(
                            "planned" to 2,
                            "accepted" to 1,
                            "notSent" to 0,
                            "rejected" to 0,
                            "offerUnknown" to 1,
                            "resultUnknown" to 1,
                            "samples" to known.path("result").path("samples"),
                        ),
                ),
            )
        assertEquals(setOf("M-0", "M-1"), directAeronPossibleExecutionIds(contradictory, 2))
    }

    @Test
    fun `미발송 표기와 양수 offer 원문이 모순이면 해당 ID를 가능한 실행으로 유지한다`() {
        val report =
            jacksonObjectMapper().valueToTree<JsonNode>(
                mapOf(
                    "result" to
                        mapOf(
                            "planned" to 1,
                            "notSent" to 1,
                            "transportAccepted" to 0,
                            "accepted" to 0,
                            "rejected" to 0,
                            "offerUnknown" to 0,
                            "resultUnknown" to 0,
                            "samples" to
                                listOf(
                                    mapOf(
                                        "command" to mapOf("requestId" to "M-0"),
                                        "outcome" to "NOT_SENT",
                                        "lastOfferResult" to 17,
                                        "transportAcceptedNs" to null,
                                        "transportPosition" to null,
                                        "ackNs" to null,
                                    ),
                                ),
                        ),
                ),
            )

        assertFalse(directAeronClientHadNoAcceptedOffer(report, 1))
        assertEquals(setOf("M-0"), directAeronPossibleExecutionIds(report, 1))
    }

    private fun executionReport(outcomes: List<String>): JsonNode {
        val samples =
            outcomes.mapIndexed { index, outcome ->
                val acknowledged = outcome == "ACCEPTED" || outcome == "REJECTED"
                val transported = outcome != "NOT_SENT" && outcome != "OFFER_UNKNOWN"
                val response =
                    if (acknowledged) {
                        mapOf(
                            "userId" to "BUYER",
                            "requestId" to "M-$index",
                            "marketId" to "BTC-USDT",
                            "orderId" to "M-$index",
                            "reason" to if (outcome == "REJECTED") "DUPLICATE_ORDER" else null,
                        )
                    } else {
                        null
                    }
                mapOf(
                    "command" to mapOf("requestId" to "M-$index"),
                    "outcome" to outcome,
                    "transportAcceptedNs" to if (transported) 10L else null,
                    "transportPosition" to if (transported) 17L else null,
                    "lastOfferResult" to if (transported) 17L else null,
                    "ackNs" to if (acknowledged) 20L else null,
                    "response" to response,
                )
            }
        return jacksonObjectMapper().valueToTree(
            mapOf(
                "result" to
                    mapOf(
                        "planned" to outcomes.size,
                        "accepted" to outcomes.count { it == "ACCEPTED" },
                        "rejected" to outcomes.count { it == "REJECTED" },
                        "notSent" to outcomes.count { it == "NOT_SENT" },
                        "transportAccepted" to outcomes.count { it != "NOT_SENT" && it != "OFFER_UNKNOWN" },
                        "offerUnknown" to outcomes.count { it == "OFFER_UNKNOWN" },
                        "resultUnknown" to outcomes.count { it == "OFFER_UNKNOWN" || it == "RESULT_UNKNOWN" },
                        "samples" to samples,
                    ),
            ),
        )
    }

    private fun successfulResult(): DirectAeronCapacityResult {
        val command = admissionCommand("M-0", "M-0")
        val workload = DirectAeronCapacityWorkload(20_000, 1, "M-", Side.BUY, command.order.userId.value)
        val sample =
            DirectAeronCapacitySample(
                command,
                AdmissionCommandCodec().encode(command),
                0,
                0,
                1_000_000,
                10,
                20,
                17,
                17,
                1,
                30,
                AdmissionResultPayload.Accepted(command.order.userId.value, "M-0", "BTC-USDT", "M-0", 1),
                DirectAeronCapacityOutcome.ACCEPTED,
                null,
            )
        return DirectAeronCapacityResult(
            workload = workload,
            firstDueNs = 0,
            windowEndNs = workload.windowNs,
            finishedNs = workload.windowNs,
            planned = 1,
            started = 1,
            notSent = 0,
            transportAccepted = 1,
            offerUnknown = 0,
            resultUnknown = 0,
            accepted = 1,
            rejected = 0,
            peakInFlight = 1,
            duplicateResponses = 0,
            loadFailure = null,
            closeFailure = null,
            errors = emptyList(),
            errorOverflow = 0,
            samples = listOf(sample),
        )
    }
}
