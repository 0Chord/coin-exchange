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

/** Intrusive memory diagnosis only. Full GC and checkpoints exclude this run from timing baselines. */
class DockerNodeHeapDiagnosticTest {
    @Test
    fun `실제 세 노드의 부하 전후 histogram 원문과 금융 상태를 함께 보존한다`() {
        val count = System.getenv("PERFORMANCE_HEAP_DIAGNOSTIC_ORDERS")?.toInt() ?: 2
        require(count in setOf(2, 20_000)) { "진단 회차는 명시한 2건 또는 20000건만 허용합니다" }
        val warmup = if (count == 2) 1 else 200
        val total = count + warmup
        val project = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
        val distribution = Path.of(requireNotNull(System.getProperty("fundingDistributionDirectory")))
        val runId = "heap${System.currentTimeMillis()}${UUID.randomUUID().toString().take(8)}"
        val reports = project.resolve("build/reports/http-performance/node-heap-diagnostic/$runId")
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
                markerPlan = HttpPerformanceMarkerPlan(runId, warmup, count),
                nodeHeapDiagnostics = true,
                onMarker = { _, _ -> },
            )
        try {
            cluster.start()
            cluster.fund("P-BUYER", "BUYER", "USDT", total * 10_020L + 1_000)
            cluster.fund("P-SELLER", "SELLER", "BTC", total * 100_000_000L)
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
                prepareHttpPerformanceOrders(http, cluster.orderUri, "SELLER", "SELL", "S-", total)
                awaitFiles(cluster, "sellers", total.toLong())
                prepareHttpPerformanceOrders(http, cluster.orderUri, "BUYER", "BUY", "W-", warmup)
                awaitFiles(cluster, "warmup", (total + warmup).toLong())
            }
            assertEquals("VERIFIED", cluster.captureNodeHeapAfterPreparation()["status"])
            cluster.beginMeasurement()
            if (count == 2) {
                val execution = cluster.runDirectLoad(10, count, "M-", "BUYER", 4096)
                Files.writeString(reports.resolve("direct-client.json"), mapper.writeValueAsString(execution))
                assertTrue(execution.path("processSucceeded").asBoolean(), execution.toString())
                assertTrue(execution.path("report").path("succeeded").asBoolean())
            } else {
                // The short Direct generator intentionally caps N at 2000. Keep that guard unchanged.
                // This is a retained-state diagnosis: use the existing bounded HTTP helper, not a TPS claim.
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
                    prepareHttpPerformanceOrders(http, cluster.orderUri, "BUYER", "BUY", "M-", count)
                }
                Files.writeString(
                    reports.resolve("diagnostic-load.json"),
                    mapper.writeValueAsString(
                        mapOf(
                            "orders" to count,
                            "transport" to "existing bounded HTTP helper",
                            "capacityClaim" to false,
                        ),
                    ),
                )
            }
            // This existing checkpoint API also verifies every emitted/received marker before returning.
            val final = awaitFiles(cluster, "final", 2L * total)
            val finance = checkHttpPerformanceStateFiles(final, warmup, count)
            assertEquals(count.toLong(), (finance.getValue("actualMeasuredSettled") as Number).toLong())
            assertEquals(total.toLong(), (finance.getValue("actualTotalSettlements") as Number).toLong())
            assertEquals(30L * total, (finance.getValue("expectedFeeRevenue") as Number).toLong())
            assertEquals(3L * total + 2, (finance.getValue("ledgerTransactions") as Number).toLong())
            Files.writeString(reports.resolve("financial-check.json"), mapper.writeValueAsString(finance))
            assertEquals("VERIFIED", cluster.captureNodeHeapAfterLoadDrain()["status"])
            assertTrue(cluster.observerErrors().isEmpty())
        } finally {
            try {
                cluster.close()
            } finally {
                println("NODE_HEAP_DIAGNOSTIC reports=$reports count=$count baselineComparable=false")
            }
        }
        val preserved = Files.newInputStream(reports.resolve("cluster.json")).use { mapper.readTree(it) }
        assertTrue(preserved.path("observerErrors").isEmpty)
        assertTrue(preserved.path("cleanupFailures").isEmpty)
        val recording = Files.newInputStream(reports.resolve("node-recordings/manifest.json")).use { mapper.readTree(it) }
        assertEquals("VERIFIED", recording.path("status").asString())
        assertTrue(recording.path("volumeDeletionAllowed").asBoolean())
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
                    readHttpPerformanceCheckpointFileObservation(it).scan.rowCounts["/trading/completions"] == completions
                }
            ) {
                return references
            }
            Thread.sleep(50)
        } while (deadline - System.nanoTime() > 0)
        error("$label 실제 세 노드 완료 $completions 확인 시간 초과")
    }
}
