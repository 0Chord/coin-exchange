package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Small real-node integration and differential oracle; this is not a capacity measurement. */
class DockerCheckpointFileFlowTest {
    @Test
    fun `실제 세 노드의 파일 참조와 표식 배출을 연결하고 전체 금융 판정을 기존 검사와 대조한다`() {
        val project = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
        val distribution = Path.of(requireNotNull(System.getProperty("fundingDistributionDirectory")))
        val runId = "files${System.currentTimeMillis()}${UUID.randomUUID().toString().take(8)}"
        val reports = project.resolve("build/reports/http-performance/checkpoint-files-integration/$runId")
        Files.createDirectories(reports)
        val mapper = jacksonObjectMapper()
        val cluster =
            HttpPerformanceCluster(
                project,
                distribution,
                runId,
                reports,
                detailedTracing = false,
                preserveNodeRecordings = true,
                markerPlan = HttpPerformanceMarkerPlan(runId, 1, 2),
                onMarker = { _, _ -> },
            )
        try {
            cluster.start()
            cluster.fund("P-BUYER", "BUYER", "USDT", 3 * 10_020L + 1_000)
            cluster.fund("P-SELLER", "SELLER", "BTC", 3 * 100_000_000L)
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
                prepareHttpPerformanceOrders(http, cluster.orderUri, "SELLER", "SELL", "S-", 3)
                val sellers = awaitFiles(cluster, "sellers", 3)
                assertTrue(sellers.values.all { readHttpPerformanceCheckpointFileObservation(it).scan.bookOrderCount == 3L })
                prepareHttpPerformanceOrders(http, cluster.orderUri, "BUYER", "BUY", "W-", 1)
                awaitFiles(cluster, "warmup", 4)
            }
            cluster.beginMeasurement()
            val execution = cluster.runDirectLoad(10, 2, "M-", "BUYER", 4096)
            Files.writeString(reports.resolve("direct-client.json"), mapper.writeValueAsString(execution))
            assertTrue(execution.path("processSucceeded").asBoolean(), execution.toString())
            assertTrue(execution.path("report").path("succeeded").asBoolean())
            val references = awaitFiles(cluster, "final", 6)
            val streamed = checkHttpPerformanceStateFiles(references, 1, 2)
            // Small integration only: the exact same original files feed the pre-existing full-tree oracle.
            val trees = references.mapValues { (_, ref) -> Files.newInputStream(ref.file).use { mapper.readTree(it) } }
            val legacy = checkHttpPerformanceState(trees, 1, 2)
            assertEquals(legacy.keys, streamed.keys)
            for (key in legacy.keys) {
                val expected = legacy.getValue(key)
                val actual = streamed.getValue(key)
                if (expected is Number &&
                    actual is Number
                ) {
                    assertEquals(expected.toLong(), actual.toLong(), key)
                } else {
                    assertEquals(expected, actual, key)
                }
            }
            assertEquals(2L, (streamed.getValue("actualMeasuredAccepted") as Number).toLong())
            assertEquals(2L, (streamed.getValue("actualMeasuredSettled") as Number).toLong())
            assertEquals(90L, (streamed.getValue("expectedFeeRevenue") as Number).toLong())
            assertEquals(11L, (streamed.getValue("ledgerTransactions") as Number).toLong())
            assertTrue(cluster.observerErrors().isEmpty())
            Files.writeString(reports.resolve("financial-check.json"), mapper.writeValueAsString(streamed))
            assertTrue(references.values.all { Files.isRegularFile(it.file) })
        } finally {
            try {
                cluster.close()
            } finally {
                println("CHECKPOINT_FILES_INTEGRATION reports=$reports")
            }
        }
        val preserved = Files.newInputStream(reports.resolve("cluster.json")).use { mapper.readTree(it) }
        assertEquals(runId, preserved.path("manifest").path("runId").asString())
        assertTrue(preserved.path("commands").isArray && !preserved.path("commands").isEmpty)
        assertTrue(preserved.path("checkpoints").isArray && !preserved.path("checkpoints").isEmpty)
        assertTrue(preserved.path("markers").isArray && !preserved.path("markers").isEmpty)
        assertTrue(preserved.path("observerErrors").isArray && preserved.path("observerErrors").isEmpty)
        assertTrue(preserved.path("cleanupFailures").isArray && preserved.path("cleanupFailures").isEmpty)
        assertTrue(!preserved.path("recordingVolumesDeletionSkipped").asBoolean())
        assertTrue(preserved.path("remainingRecordingVolumes").isArray && preserved.path("remainingRecordingVolumes").isEmpty)
        val recordings = Files.newInputStream(reports.resolve("node-recordings/manifest.json")).use { mapper.readTree(it) }
        assertEquals(runId, recordings.path("runId").asString())
        assertEquals("VERIFIED", recordings.path("status").asString())
        assertTrue(recordings.path("volumeDeletionAllowed").asBoolean())
        assertTrue(recordings.path("failures").isEmpty)
        assertEquals(3, recordings.path("members").size())
        for (member in recordings.path("members")) {
            assertTrue(member.path("readyObserved").asBoolean())
            assertEquals(2, member.path("copies").size())
            for (copy in member.path("copies")) {
                assertTrue(copy.path("verified").asBoolean())
                assertTrue(copy.path("files").asLong() > 0)
                assertTrue(copy.path("bytes").asLong() > 0)
                assertTrue(Files.isDirectory(reports.resolve(copy.path("artifactDirectory").asString())))
            }
        }
        assertTrue(recordings.path("inventory").path("records").asLong() > 0)
    }

    private fun awaitFiles(
        cluster: HttpPerformanceCluster,
        label: String,
        completions: Long,
    ): Map<Int, HttpPerformanceCheckpointFileReference> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var attempt = 0
        do {
            val references = cluster.checkpointFiles("$label-${attempt++}")
            if (references.values.all {
                    readHttpPerformanceCheckpointFileObservation(it).scan.rowCounts["/trading/completions"] ==
                        completions
                }
            ) {
                return references
            }
            Thread.sleep(50)
        } while (deadline - System.nanoTime() > 0)
        error("$label 실제 세 노드 완료 $completions 확인 시간 초과")
    }
}
