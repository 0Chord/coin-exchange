package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(20)
class HttpPerformanceClusterNodeOnlyTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `node-only 생성 계획에는 세 노드만 있고 기본 계획에는 Gateway가 남는다`() {
        assertEquals(listOf("node0", "node1", "node2"), httpPerformanceClusterServices(true))
        assertEquals(listOf("node0", "node1", "node2", "gateway"), httpPerformanceClusterServices(false))
    }

    @Test
    fun `직접 client는 서비스 DNS를 유지하고 제어 경로를 하나의 volume 인자로 전달한다`() {
        val control = temporary.resolve("control with spaces")
        val arguments = httpPerformanceCombinedClientStartArguments("direct-test", control)
        assertEquals(
            listOf(
                "run",
                "-d",
                "--name",
                "direct-test-direct",
                "--use-aliases",
                "--entrypoint",
                "sleep",
                "--volume",
                "$control:/direct-control",
                "--env",
                "FUNDING_HOST=client",
                "client",
                "1800",
            ),
            arguments,
        )
        assertEquals(
            listOf("docker", "update", "--cpus=2", "--memory=2g", "--memory-swap=2g", "owned-container-id"),
            httpPerformanceCombinedClientResourceArguments("owned-container-id"),
        )
        val inspection = httpPerformanceCombinedClientInspectArguments("owned-container-id")
        assertEquals(listOf("docker", "inspect", "--format"), inspection.take(3))
        assertEquals("owned-container-id", inspection.last())
        assertTrue(".HostConfig" in inspection[3])
        assertTrue(".State" in inspection[3])
        assertFalse(".Config" in inspection[3])
        assertFalse(".Env" in inspection[3])
    }

    @Test
    fun `docker exec는 지정한 컨테이너와 기존 Java 진입 옵션을 사용하고 main 인자를 분리한다`() {
        val arguments =
            httpPerformanceCombinedClientExecArguments("owned-container", "DirectCoreClient", listOf("100000", "/direct-control/raw file"))
        assertEquals(listOf("docker", "exec", "owned-container", "java"), arguments.take(4))
        assertTrue("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED" in arguments)
        assertTrue("--add-opens=java.base/java.util.zip=ALL-UNNAMED" in arguments)
        assertEquals("/app/test-classes:/app/classes:/app/lib/*", arguments[arguments.indexOf("-cp") + 1])
        assertEquals(listOf("DirectCoreClient", "100000", "/direct-control/raw file"), arguments.takeLast(3))
        assertFailsWith<IllegalArgumentException> { httpPerformanceCombinedClientExecArguments("owned", "-cp", emptyList()) }
        assertFailsWith<IllegalArgumentException> { httpPerformanceCombinedClientExecArguments("owned", "Main", listOf("bad\u0000arg")) }
    }

    @Test
    fun `node-only는 Gateway 계측과 HTTP 경계 진단을 생성 전에 거절한다`() {
        assertFailsWith<IllegalArgumentException> { cluster(nodeOnly = true, stageDiagnostics = true) }
        assertFailsWith<IllegalArgumentException> { cluster(nodeOnly = true, stageDiagnostics = true, httpBoundaryOrders = 2) }
        assertFalse(Files.exists(temporary.resolve("reports/control")))
    }

    @Test
    fun `remote marker는 node-only의 실행 중 client alias와 유효 port만 사용하며 원문을 오류에 싣지 않는다`() {
        assertFailsWith<IllegalArgumentException> { cluster(remote = "client:19800") }
        for (endpoint in listOf("https://user:password@client:19800", "other:19800", "client:0", "client:65536")) {
            val failure = assertFailsWith<IllegalArgumentException> { cluster(nodeOnly = true, remote = endpoint) }
            assertFalse(failure.message.orEmpty().contains(endpoint))
            assertFalse(failure.message.orEmpty().contains("password"))
        }
        assertFalse(Files.exists(temporary.resolve("reports/control")))
    }

    @Test
    fun `remote marker manifest는 유휴 host 수신기를 완료 증거로 표시하지 않는다`() {
        cluster(nodeOnly = true, remote = "client:19800").use { cluster ->
            val manifest = cluster.manifest()
            assertEquals(true, manifest["nodeOnly"])
            assertEquals(false, manifest["gatewayPresent"])
            assertNull(manifest["gateway"])
            assertNull(manifest["gatewayConfiguredLimits"])
            val marker = manifest["markerObserver"] as Map<*, *>
            assertEquals("REMOTE_COMBINED_DIRECT_CLIENT", marker["mode"])
            assertEquals(true, marker["externalMarkerProofRequired"])
            assertEquals("running idle; not used as completion evidence", marker["hostReceiver"])
            assertFalse(manifest.toString().contains("client:19800"))
            assertFailsWith<IllegalStateException> { cluster.runCombinedDirectClient("Main", emptyList(), 1000) }
        }
    }

    @Test
    fun `기본 cluster는 기존 host marker 수신기와 Gateway 설정을 유지한다`() {
        cluster().use { cluster ->
            val manifest = cluster.manifest()
            assertEquals(false, manifest["nodeOnly"])
            assertEquals(true, manifest["gatewayPresent"])
            assertEquals(HTTP_PERFORMANCE_MARKET_DOCUMENT, manifest["marketDocument"])
            assertEquals(768, manifest["nodeMaxHeapMb"])
            assertEquals("768m", manifest["nodeMaxHeapEnvironment"])
            assertEquals(
                mapOf("queueCapacity" to 1024, "maxInFlight" to 256, "resultTimeoutMs" to 5000),
                manifest["gatewayConfiguredLimits"],
            )
            val marker = manifest["markerObserver"] as Map<*, *>
            assertTrue(marker["senderEndpoint"].toString().startsWith("host.docker.internal:"))
            assertFailsWith<IllegalStateException> { cluster.runCombinedDirectClient("Main", emptyList(), 1000) }
        }
    }

    @Test
    fun `세 실제 마켓 원문과 명시한 노드 heap을 환경 및 manifest에 유지한다`() {
        val document =
            listOf("BTC", "SOL", "SUI").joinToString(prefix = "[", postfix = "]") { asset ->
                HTTP_PERFORMANCE_MARKET_DOCUMENT.removeSurrounding("[", "]").replace("BTC", asset)
            }
        cluster(nodeOnly = true, marketDocument = document, nodeMaxHeapMb = 1536).use { cluster ->
            val manifest = cluster.manifest()
            assertEquals(document, manifest["marketDocument"])
            assertEquals(1536, manifest["nodeMaxHeapMb"])
            assertEquals("1536m", manifest["nodeMaxHeapEnvironment"])
        }
    }

    @Test
    fun `비어 있는 마켓 원문과 허용 범위 밖 heap은 생성 전에 거절한다`() {
        assertFailsWith<IllegalArgumentException> { cluster(marketDocument = " ") }
        assertFailsWith<IllegalArgumentException> { cluster(nodeMaxHeapMb = 767) }
        assertFailsWith<IllegalArgumentException> { cluster(nodeMaxHeapMb = 1537) }
        assertFalse(Files.exists(temporary.resolve("reports/control")))
    }

    private fun cluster(
        nodeOnly: Boolean = false,
        remote: String? = null,
        stageDiagnostics: Boolean = false,
        httpBoundaryOrders: Int? = null,
        marketDocument: String = HTTP_PERFORMANCE_MARKET_DOCUMENT,
        nodeMaxHeapMb: Int = 768,
    ): HttpPerformanceCluster =
        HttpPerformanceCluster(
            project = temporary.resolve("app-api"),
            distribution = temporary.resolve("distribution"),
            runId = "direct-test",
            reports = temporary.resolve("reports"),
            stageDiagnostics = stageDiagnostics,
            httpBoundaryOrders = httpBoundaryOrders,
            markerPlan = HttpPerformanceMarkerPlan("direct-test", 1, 2),
            nodeOnly = nodeOnly,
            remoteMarkerEndpoint = remote,
            marketDocument = marketDocument,
            nodeMaxHeapMb = nodeMaxHeapMb,
            onMarker = { _, _ -> },
        )
}
