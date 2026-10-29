package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpPerformanceCheckpointFilesTest {
    @TempDir
    lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    @Test
    fun `기존 파일 reader도 reference의 member 누락과 null을 영번으로 보충하지 않는다`() {
        val published = HttpPerformanceCheckpointFiles.publish(directory, checkpoint())
        for (missing in listOf(true, false)) {
            val reference = mapper.valueToTree<tools.jackson.databind.JsonNode>(published) as tools.jackson.databind.node.ObjectNode
            if (missing) reference.remove("memberId") else reference.putNull("memberId")
            assertFailsWith<IllegalArgumentException> {
                HttpPerformanceCheckpointFiles.read(directory, reference, "checkpoint-test", 0, "final")
            }
        }
    }

    @Test
    fun `기존 reader의 본문 member도 지문이 맞는다는 이유로 누락 null 문자열 소수를 영번으로 해석하지 않는다`() {
        val published = HttpPerformanceCheckpointFiles.publish(directory, checkpoint())
        val file = directory.resolve(published.getValue("artifactFile") as String)
        for (member in listOf(null, "null", "\"0\"", "0.0")) {
            val body = mapper.valueToTree<tools.jackson.databind.JsonNode>(checkpoint()) as tools.jackson.databind.node.ObjectNode
            if (member == null) body.remove("memberId") else body.set("memberId", mapper.readTree(member))
            Files.writeString(file, mapper.writeValueAsString(body))
            val hash =
                java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(Files.readAllBytes(file)),
                )
            val reference =
                mapper.valueToTree<tools.jackson.databind.JsonNode>(
                    published + mapOf("artifactBytes" to Files.size(file), "artifactSha256" to hash),
                )
            assertFailsWith<IllegalArgumentException>("body memberId=$member") {
                HttpPerformanceCheckpointFiles.read(directory, reference, "checkpoint-test", 0, "final")
            }
        }
    }

    @Test
    fun `체크포인트 이력은 큰 전체 상태를 다시 보관하지 않고 위치와 파일 지문만 남긴다`() {
        val full =
            mapper.valueToTree<tools.jackson.databind.JsonNode>(
                checkpoint() + mapOf("applied" to mapOf("position" to 256), "payload" to "x".repeat(8 * 1024 * 1024)),
            )
        val reference =
            mapper.valueToTree<tools.jackson.databind.JsonNode>(
                mapOf("artifactFile" to "node0-checkpoint-final.json", "artifactBytes" to 8_388_608, "artifactSha256" to "a".repeat(64)),
            )
        val history = httpPerformanceCheckpointHistory("final", mapOf(0 to full), mapOf(0 to reference))
        val json = mapper.writeValueAsString(history)
        assertTrue(json.length < 1024)
        val small = mapper.readTree(json).path("nodes").path("0")
        assertEquals(256L, small.path("position").asLong())
        assertEquals("control/node0-checkpoint-final.json", small.path("artifactFile").asString())
        assertEquals("a".repeat(64), small.path("artifactSha256").asString())
        assertTrue(small.path("payload").isMissingNode)
    }

    @Test
    fun `Docker 청크보다 큰 전체 상태도 로그에는 작은 참조만 남기고 원문은 파일에서 온전히 읽는다`() {
        val payload = "x".repeat(8 * 1024 * 1024)
        val reference = HttpPerformanceCheckpointFiles.publish(directory, checkpoint() + ("payload" to payload))
        val output = "FUNDING_EVENT ${mapper.writeValueAsString(reference)}\n"
        assertTrue(output.length < 1024)
        assertTrue(Files.size(directory.resolve(reference.getValue("artifactFile") as String)) > 8 * 1024 * 1024)
        val issues = mutableListOf<FundingVerificationIssue>()
        val event = readFundingEvents(output, issues).single()
        assertTrue(issues.isEmpty())
        assertEquals(
            payload,
            HttpPerformanceCheckpointFiles.read(directory, event, "checkpoint-test", 0, "final").path("payload").asString(),
        )
    }

    @Test
    fun `직렬화가 끝나기 전에는 최종 파일과 참조가 공개되지 않는다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val future =
            CompletableFuture.supplyAsync {
                HttpPerformanceCheckpointFiles.publish(directory, checkpoint() + ("state" to PausedState(entered, release)))
            }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(Files.exists(directory.resolve("node0-checkpoint-final.json")))
            assertFalse(future.isDone)
        } finally {
            release.countDown()
        }
        val reference = mapper.valueToTree<tools.jackson.databind.JsonNode>(future.get(5, TimeUnit.SECONDS))
        val full = HttpPerformanceCheckpointFiles.read(directory, reference, "checkpoint-test", 0, "final")
        assertEquals("complete", full.path("state").path("payload").asString())
        Files.list(directory).use { files -> assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") }) }
    }

    @Test
    fun `다른 노드 참조와 파일 변조를 거절하고 기존 원문을 덮어쓰지 않는다`() {
        val reference = HttpPerformanceCheckpointFiles.publish(directory, checkpoint())
        assertFailsWith<IllegalArgumentException> { HttpPerformanceCheckpointFiles.publish(directory, checkpoint()) }
        val event = mapper.valueToTree<tools.jackson.databind.JsonNode>(reference)
        assertFailsWith<IllegalArgumentException> { HttpPerformanceCheckpointFiles.read(directory, event, "checkpoint-test", 1, "final") }
        val file = directory.resolve(reference.getValue("artifactFile") as String)
        val bytes = Files.readAllBytes(file)
        bytes[bytes.lastIndex] = ' '.code.toByte()
        Files.write(file, bytes)
        assertFailsWith<IllegalArgumentException> { HttpPerformanceCheckpointFiles.read(directory, event, "checkpoint-test", 0, "final") }
    }

    @Test
    fun `종료 표식 목록도 별도 파일에 보존해 큰 stdout 종료 기록을 만들지 않는다`() {
        val stopped =
            mapOf(
                "kind" to "STOPPED",
                "runId" to "checkpoint-test",
                "memberId" to 0,
                "closed" to true,
                "failure" to null,
                "markerSummary" to
                    mapOf(
                        "sentCount" to 8192,
                        "sentMarkers" to List(8192) { mapOf("admissionSequence" to it + 1) },
                        "senderLocalSpans" to List(8192) { mapOf("admissionSequence" to it + 1, "queueWaitNs" to it) },
                    ),
            )
        val reference = HttpPerformanceCheckpointFiles.publishStopped(directory, stopped)
        assertTrue(mapper.writeValueAsString(reference).length < 1024)
        val full = mapper.readTree(Files.readString(directory.resolve("node0-stopped.json")))
        assertEquals(8192, full.path("markerSummary").path("sentMarkers").size())
        assertEquals(8192, full.path("markerSummary").path("senderLocalSpans").size())
    }

    private fun checkpoint(): Map<String, Any?> =
        mapOf("kind" to "PERFORMANCE_CHECKPOINT", "runId" to "checkpoint-test", "memberId" to 0, "label" to "final")

    class PausedState(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) {
        val payload: String
            get() {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return "complete"
            }
    }
}
