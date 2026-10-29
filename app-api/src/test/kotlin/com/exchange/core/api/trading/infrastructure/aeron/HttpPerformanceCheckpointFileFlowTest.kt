package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.core.JsonToken
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Small file-workflow fixtures; no Docker, Aeron driver, financial verdict, or performance run. */
class HttpPerformanceCheckpointFileFlowTest {
    @TempDir
    lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private val baseline = (0..2).associateWith { role(it) to 0L }

    @Test
    fun `명시적큰cohort파일수집은600초를받되재시도에는같은deadline의잔여시간만준다`() {
        var now = 0L
        var captures = 0
        val timeouts = mutableListOf<Long>()
        val result =
            collectHttpPerformanceCheckpointFiles(
                "large-final",
                600_000,
                true,
                capture = { token, timeout ->
                    timeouts.add(timeout)
                    captures++
                    publishAll(token, if (captures == 1) listOf(100, 99, 100) else listOf(100, 100, 100))
                },
                preserve = { _, _ -> },
                roleBaseline = baseline,
                nanoTime = { now },
                pause = { now += TimeUnit.MILLISECONDS.toNanos(120_000) },
                suffix = { "a$captures" },
                timeBudget = HttpPerformanceEvidenceTimeBudget.LARGE_SUSTAINED,
            )
        assertEquals(listOf(600_000L, 480_000L), timeouts)
        assertEquals(setOf(0, 1, 2), result.references.keys)
    }

    @Test
    fun `기본file수집은120초초과를거절하며확장capture의실패도그대로전파한다`() {
        assertFailsWith<IllegalArgumentException> {
            collectHttpPerformanceCheckpointFiles("default", 120_001, true, { _, _ -> error("must not capture") }, { _, _ -> })
        }
        val original = IllegalStateException("final-token 세 노드 후속 체크포인트 제한 시간 초과")
        val actual =
            assertFailsWith<IllegalStateException> {
                collectHttpPerformanceCheckpointFiles(
                    "large-final",
                    600_000,
                    true,
                    capture = { _, _ -> throw original },
                    preserve = { _, _ -> },
                    timeBudget = HttpPerformanceEvidenceTimeBudget.LARGE_SUSTAINED,
                )
            }
        assertTrue(actual === original)
    }

    @Test
    fun `다른 position은 원래 deadline 안에서 재캡처하고 같은 position에서 전체사본을 대조한다`() {
        var now = 0L
        var captures = 0
        var comparisons = 0
        val timeouts = mutableListOf<Long>()
        val preserved = linkedMapOf<String, Map<String, Any?>>()
        val result =
            collectHttpPerformanceCheckpointFiles(
                "ready",
                1_000,
                true,
                capture = { token, timeout ->
                    timeouts.add(timeout)
                    captures++
                    publishAll(token, if (captures == 1) listOf(100, 99, 100) else listOf(100, 100, 100))
                },
                preserve = { name, value -> preserved[name] = value },
                roleBaseline = baseline,
                compareCopies = {
                    comparisons++
                    checkHttpPerformanceCheckpointCopiesFiles(it)
                },
                nanoTime = { now },
                pause = { now += TimeUnit.MILLISECONDS.toNanos(250) },
                suffix = { "a$captures" },
            )

        assertEquals(2, captures)
        assertEquals(1, comparisons)
        assertEquals(listOf(1_000L, 750L), timeouts)
        assertEquals(setOf(0, 1, 2), result.references.keys)
        assertEquals(
            setOf("checkpoint-files-ready-attempt-0.json", "checkpoint-files-ready-attempt-1.json", "checkpoint-files-ready.json"),
            preserved.keys,
        )
        val wrapper = mapper.valueToTree<JsonNode>(preserved.getValue("checkpoint-files-ready.json"))
        assertEquals("PERFORMANCE_CHECKPOINT_REFERENCES_V1", wrapper.path("schema").asString())
        assertTrue(
            wrapper
                .path("nodes")
                .path("0")
                .path("state")
                .isMissingNode,
        )
        assertTrue(
            wrapper
                .path("nodes")
                .path("0")
                .path("artifactBytes")
                .asLong() > 0,
        )
        assertTrue(mapper.writeValueAsString(wrapper).length < 4_096)
        result.references.values.forEach { assertTrue(Files.isRegularFile(it.file)) }
    }

    @Test
    fun `다른 position이 계속되면 capture마다 시간을 새로 주지 않고 원래 deadline에서 실패한다`() {
        var now = Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(100)
        val timeouts = mutableListOf<Long>()
        var attempt = 0
        assertFailsWith<IllegalStateException> {
            collectHttpPerformanceCheckpointFiles(
                "timeout",
                400,
                true,
                capture = { token, timeout ->
                    timeouts.add(timeout)
                    publishAll(token, listOf(100, 99, 100))
                },
                preserve = { _, _ -> },
                nanoTime = { now },
                pause = { now += TimeUnit.MILLISECONDS.toNanos(250) },
                suffix = { "a${attempt++}" },
            )
        }
        assertEquals(listOf(400L, 150L), timeouts)
    }

    @Test
    fun `같은 position이어도 송신이 생성 count를 따라오기 전에는 재캡처한다`() {
        var now = 0L
        var attempt = 0
        val result =
            collectHttpPerformanceCheckpointFiles(
                "drain",
                1_000,
                true,
                capture = { token, _ ->
                    attempt++
                    publishAll(token) { member, fields ->
                        fields["emittedMarkerCount"] = 2
                        sender(fields)["offered"] = 2
                        if (attempt == 2) {
                            sender(fields)["sentCount"] = 2
                            sender(fields)["sentMarkers"] =
                                listOf(marker(member), marker(member).copy(admissionSequence = 2, requestId = "M-1", orderId = "M-1"))
                        }
                    }
                },
                preserve = { _, _ -> },
                roleBaseline = baseline,
                nanoTime = { now },
                pause = { now += TimeUnit.MILLISECONDS.toNanos(250) },
                suffix = { "a$attempt" },
            )
        assertEquals(2, attempt)
        assertTrue(result.observations.values.all { it.sent == 2L && it.emitted == 2L })
    }

    @Test
    fun `같은 position의 비금액 context 한 필드 차이도 전체동일성 실패이고 attempt참조를 남긴다`() {
        val preserved = linkedMapOf<String, Map<String, Any?>>()
        val failure =
            assertFailsWith<IllegalStateException> {
                collectHttpPerformanceCheckpointFiles(
                    "different",
                    1_000,
                    true,
                    capture = { token, _ ->
                        publishAll(token) { member, fields ->
                            state(fields)["context"] =
                                if (member == 1) "changed" else "original"
                        }
                    },
                    preserve = { name, value -> preserved[name] = value },
                    suffix = { "first" },
                )
            }
        assertTrue(failure.message.orEmpty().contains("전체 업무 상태"))
        assertEquals(setOf("checkpoint-files-different-attempt-0.json"), preserved.keys)
        assertEquals(3, (preserved.values.single().getValue("nodes") as Map<*, *>).size)
        Files.list(directory).use { paths -> assertEquals(3L, paths.count()) }
    }

    @Test
    fun `기존 collector 역할 queue 오류와 drain 원문 count 검사를 모두 유지한다`() {
        val corruptions: List<(MutableMap<String, Any?>) -> Unit> =
            listOf(
                { it["collectorFailure"] = "deliberate collector failure" },
                { it.remove("collectorFailure") },
                { sender(it)["queueOverflow"] = 1 },
                { sender(it)["queueOverflow"] = "0" },
                { sender(it)["historyOverflow"] = 1 },
                { sender(it)["failures"] = listOf("deliberate sender failure") },
                { sender(it).remove("failures") },
                { sender(it).remove("sentCount") },
                { it["role"] = "FOLLOWER" },
                { it["roleChangeCount"] = 1 },
                { sender(it)["offered"] = 2 },
                { sender(it)["sentMarkers"] = emptyList<Any>() },
            )
        for ((index, corrupt) in corruptions.withIndex()) {
            val fields = fields(0, "bad$index", 100)
            corrupt(fields)
            val reference = publish(fields)
            val observation = readHttpPerformanceCheckpointFileObservation(reference)
            assertFailsWith<IllegalStateException>("corruption $index") { observation.checkFinal(baseline.getValue(0)) }
            assertTrue(Files.isRegularFile(reference.file))
        }
    }

    @Test
    fun `관찰 실패 뒤 false경로는 원본과 사본대조를 보존하지만 관찰을 성공으로 바꾸지 않는다`() {
        val errors = mutableListOf<String>()
        val preserved = linkedMapOf<String, Map<String, Any?>>()
        val corrupt: (Int, MutableMap<String, Any?>) -> Unit = { _, fields ->
            fields["collectorFailure"] = "original observation failure"
            sender(fields)["queueOverflow"] = 1
            sender(fields)["failures"] = listOf("sender failure")
            fields["role"] = "UNKNOWN"
        }
        try {
            collectHttpPerformanceCheckpointFiles("observed", 1_000, true, { token, _ ->
                publishAll(
                    token,
                    mutate = corrupt,
                )
            }, { name, value ->
                preserved[name] =
                    value
            }, suffix = { "first" })
        } catch (failure: IllegalStateException) {
            errors.add(checkNotNull(failure.message))
        }
        val result =
            collectHttpPerformanceCheckpointFiles("financial", 1_000, false, { token, _ ->
                publishAll(
                    token,
                    mutate = corrupt,
                )
            }, { name, value ->
                preserved[name] =
                    value
            }, suffix = { "first" })

        assertTrue(errors.isNotEmpty())
        assertEquals(3, result.references.size)
        assertTrue(result.observations.values.all { it.failureCount == 1L && it.collectorFailureToken != JsonToken.VALUE_NULL })
        assertEquals(false, preserved.getValue("checkpoint-files-financial.json")["observationVerificationRequested"])
        result.references.values.forEach { assertTrue(Files.isRegularFile(it.file)) }
    }

    @Test
    fun `parse 실패도 세 원본 참조 wrapper를 남기고 파일을 지우지 않는다`() {
        val preserved = linkedMapOf<String, Map<String, Any?>>()
        var captured = emptyMap<Int, HttpPerformanceCheckpointFileReference>()
        assertFailsWith<Exception> {
            collectHttpPerformanceCheckpointFiles(
                "corrupt",
                1_000,
                true,
                capture = { token, _ ->
                    publishAll(token).also {
                        captured = it
                        Files.writeString(it.getValue(1).file, " {}", StandardOpenOption.APPEND)
                    }
                },
                preserve = { name, value -> preserved[name] = value },
                suffix = { "first" },
            )
        }
        assertEquals(3, (preserved.values.single().getValue("nodes") as Map<*, *>).size)
        assertTrue(captured.values.all { Files.isRegularFile(it.file) })
    }

    @Test
    fun `송수신은 모든 typed payload로 대조하고 미래position은 기존 fence대로 제외한다`() {
        val references = publishAll("markers")
        val sent = readHttpPerformanceSentMarkerFiles(references, 2)
        val received = (0..2).map(::marker)
        val future = marker(0).copy(admissionSequence = 2, logPosition = 101, requestId = "M-1", orderId = "M-1")
        assertTrue(httpPerformanceMarkerDifferencesFiles(sent, received + future).isEmpty())
        assertTrue(httpPerformanceMarkerDifferencesFiles(sent, received + received.first()).isEmpty(), "동일 duplicate의 기존 Set 의미를 유지합니다")

        val changed = received.map { if (it.memberId == 0) it.copy(settlementCount = 0) else it }
        val mismatch = httpPerformanceMarkerDifferencesFiles(sent, changed)
        assertEquals(1, mismatch.size)
        assertTrue(mismatch.single().contains("missing=1, extra=1"))
        assertTrue(httpPerformanceMarkerDifferencesFiles(sent, received.dropLast(1)).single().contains("missing=1"))
        assertTrue(httpPerformanceMarkerDifferencesFiles(sent, received + future.copy(logPosition = 99)).single().contains("extra=1"))
    }

    @Test
    fun `marker 원문의 member run과 계획 상한은 parser성공 뒤에도 엄격히 검사한다`() {
        val wrong =
            publishAll("wrongmarker") { member, fields ->
                if (member ==
                    0
                ) {
                    sender(fields)["sentMarkers"] = listOf(marker(0).copy(runId = "other-run"))
                }
            }
        assertFailsWith<IllegalStateException> { readHttpPerformanceSentMarkerFiles(wrong, 2) }
        val over =
            publishAll("overmarker") { member, fields ->
                sender(fields)["sentMarkers"] =
                    listOf(marker(member), marker(member).copy(admissionSequence = 2))
            }
        assertFailsWith<IllegalStateException> { readHttpPerformanceSentMarkerFiles(over, 1) }
        assertTrue((wrong.values + over.values).all { Files.isRegularFile(it.file) })
    }

    @Test
    fun `최종 wrapper callback의 interrupt도 성공반환으로 바꾸지 않고 원본을 보존한다`() {
        var captured = emptyMap<Int, HttpPerformanceCheckpointFileReference>()
        try {
            assertFailsWith<InterruptedException> {
                collectHttpPerformanceCheckpointFiles(
                    "interrupted",
                    1_000,
                    true,
                    capture = { token, _ -> publishAll(token).also { captured = it } },
                    preserve = { name, _ -> if (name == "checkpoint-files-interrupted.json") Thread.currentThread().interrupt() },
                    suffix = { "first" },
                )
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertTrue(captured.values.all { Files.isRegularFile(it.file) })
    }

    @Test
    fun `세 노드 중 하나라도 빠진 참조를 전체동일성 성공으로 넘기지 않는다`() {
        var compared = false
        assertFailsWith<IllegalStateException> {
            collectHttpPerformanceCheckpointFiles(
                "missing",
                1_000,
                true,
                capture = { token, _ -> publishAll(token).filterKeys { it != 2 } },
                preserve = { _, _ -> },
                compareCopies = {
                    compared = true
                    error("must not compare partial copies")
                },
                suffix = { "first" },
            )
        }
        assertFalse(compared)
        Files.list(directory).use { paths -> assertEquals(3L, paths.count()) }
    }

    private fun role(member: Int): String = if (member == 0) "LEADER" else "FOLLOWER"

    private fun marker(member: Int): HttpPerformanceCompletion =
        HttpPerformanceCompletion("flow-test", member, role(member), "BUYER", "M-0", "M-0", 1, 100, 1, 1)

    private fun fields(
        member: Int,
        label: String,
        position: Long,
    ): MutableMap<String, Any?> =
        linkedMapOf(
            "kind" to "PERFORMANCE_CHECKPOINT",
            "runId" to "flow-test",
            "memberId" to member,
            "label" to label,
            "applied" to
                mutableMapOf(
                    "position" to position,
                    "state" to
                        mutableMapOf<String, Any?>(
                            "balances" to emptyList<Any>(),
                            "preparationCount" to 0,
                            "pendingOrderCount" to 0,
                            "requestRecordCount" to 0,
                            "lastSequence" to 0,
                            "preparations" to emptyList<Any>(),
                            "pendingOrders" to emptyList<Any>(),
                            "requests" to emptyList<Any>(),
                            "transactions" to emptyList<Any>(),
                        ),
                ),
            "trading" to
                mutableMapOf(
                    "currentReservations" to emptyList<Any>(),
                    "books" to emptyList<Any>(),
                    "completions" to emptyList<Any>(),
                    "matchingResultCount" to 0,
                    "matchingResults" to emptyList<Any>(),
                ),
            "collectorFailure" to null,
            "emittedMarkerCount" to 1,
            "role" to role(member),
            "roleChangeCount" to 0,
            "markerSummary" to
                mutableMapOf(
                    "offered" to 1,
                    "sentCount" to 1,
                    "queueOverflow" to 0,
                    "historyOverflow" to 0,
                    "failures" to emptyList<String>(),
                    "sentMarkers" to listOf(marker(member)),
                ),
        )

    private fun publish(fields: Map<String, Any?>): HttpPerformanceCheckpointFileReference {
        val event = mapper.valueToTree<JsonNode>(HttpPerformanceCheckpointFiles.publish(directory, fields))
        return HttpPerformanceCheckpointReader.resolve(
            directory,
            event,
            "flow-test",
            fields.getValue("memberId") as Int,
            fields.getValue("label") as String,
        )
    }

    private fun publishAll(
        label: String,
        positions: List<Long> = listOf(100, 100, 100),
        mutate: (Int, MutableMap<String, Any?>) -> Unit = { _, _ -> },
    ): Map<Int, HttpPerformanceCheckpointFileReference> =
        (0..2).associateWith { member -> publish(fields(member, label, positions[member]).apply { mutate(member, this) }) }

    @Suppress("UNCHECKED_CAST")
    private fun sender(fields: MutableMap<String, Any?>): MutableMap<String, Any?> =
        fields.getValue("markerSummary") as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun state(fields: MutableMap<String, Any?>): MutableMap<String, Any?> =
        (fields.getValue("applied") as Map<String, Any?>).getValue("state") as MutableMap<String, Any?>
}
