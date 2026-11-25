package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.trading.TradingServiceRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.DatagramSocket
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 실제 main과 start의 거절을 검사한다. 단독 역할의 서버나 주문 처리 대역을 만들지 않는다. */
class TradingRoleStartupTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `역할 미지정과 명시한 통합 역할은 같은 기존 실행 설정을 선택한다`() {
        val environment = environment(directory.resolve("integrated"))
        val defaults = developmentClusterSettings(environment)
        val explicit = developmentClusterSettings(environment + ("TRADING_SERVICE_ROLE" to "INTEGRATED"))

        assertEquals(TradingServiceRole.INTEGRATED, defaults.serviceRole)
        assertEquals(TradingServiceRole.INTEGRATED, explicit.serviceRole)
        assertEquals(defaults.runRoot, explicit.runRoot)
        assertEquals(defaults.members, explicit.members)
        assertEquals(defaults.markets.all(), explicit.markets.all())
        assertEquals(
            DevelopmentFundingRun.configurationIdentitySha256(defaults),
            DevelopmentFundingRun.configurationIdentitySha256(explicit),
        )
        assertEquals(DevelopmentMarketContext.BTC_USDT, defaults.markets.single())
    }

    @Test
    fun `환경 설정은 단독 역할과 잘못된 역할을 manifest나 RDB보다 먼저 거절한다`() {
        listOf("FUNDS", "MATCHING", "", "funds", "UNKNOWN").forEachIndexed { index, raw ->
            val root = directory.resolve("settings-$index")
            val environment = invalidExternalSettings(root) + ("TRADING_SERVICE_ROLE" to raw)

            if (raw == "FUNDS" || raw == "MATCHING") {
                val failure = assertFailsWith<IllegalStateException> { developmentClusterSettings(environment) }
                assertEquals("SPLIT_ROLE_NOT_ENABLED: $raw", failure.message)
            } else {
                val failure = assertFailsWith<IllegalArgumentException> { developmentClusterSettings(environment) }
                assertEquals("INVALID_SERVICE_ROLE", failure.message)
            }
            assertFalse(Files.exists(root))
        }
    }

    @Test
    fun `직접 시작도 노드 생성과 새 실행 루트 및 포트 접근 전에 단독 역할을 거절한다`() {
        val workers = metricsWorkers()
        DatagramSocket(0).use { occupied ->
            listOf(TradingServiceRole.FUNDS, TradingServiceRole.MATCHING).forEach { role ->
                val root = directory.resolve("new-${role.name}")
                val config =
                    DevelopmentFundingSettings(
                        root,
                        basePort = occupied.localPort.coerceAtMost(65528),
                        serviceRole = role,
                    )

                val failure = assertFailsWith<IllegalStateException> { DevelopmentFundingNode.start(config) }

                assertEquals("SPLIT_ROLE_NOT_ENABLED: ${role.name}", failure.message)
                assertFalse(Files.exists(root))
                assertEquals(workers, metricsWorkers(), "거절된 시작이 지표 worker를 남기면 안 됩니다")
            }
        }
    }

    @Test
    fun `직접 단독 역할 시작은 잠긴 기존 기록과 sentinel을 건드리지 않는다`() {
        listOf(TradingServiceRole.FUNDS, TradingServiceRole.MATCHING).forEach { role ->
            val root = Files.createDirectory(directory.resolve("existing-${role.name}"))
            val sentinel = root.resolve("sentinel.bin")
            val content = byteArrayOf(7, 3, 1, 9)
            Files.write(sentinel, content)
            val lockFile = root.resolve("recovery.lock")
            FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
                channel.lock().use {
                    val before = Files.list(root).use { it.toList().toSet() }
                    val config = DevelopmentFundingSettings(root, recoverExisting = true, serviceRole = role)

                    val failure = assertFailsWith<IllegalStateException> { DevelopmentFundingNode.start(config) }

                    assertEquals("SPLIT_ROLE_NOT_ENABLED: ${role.name}", failure.message)
                    assertEquals(before, Files.list(root).use { it.toList().toSet() })
                    assertContentEquals(content, Files.readAllBytes(sentinel))
                }
            }
        }
    }

    @Test
    @Timeout(60)
    fun `실제 main의 일반 CLI와 cluster env는 역할 오류를 먼저 내고 READY나 기록을 만들지 않는다`() {
        listOf(false, true).forEach { structured ->
            listOf("FUNDS", "MATCHING", "", "UNKNOWN").forEachIndexed { index, raw ->
                val runRoot = directory.resolve("main-$structured-$index")
                val reports = Files.createDirectory(directory.resolve("logs-$structured-$index"))
                val runner =
                    VerificationCommands(
                        directory,
                        reports,
                        invalidExternalSettings(runRoot) + ("TRADING_SERVICE_ROLE" to raw),
                    )
                try {
                    val result =
                        runner
                            .start(
                                listOf(
                                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                                    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
                                    "-cp",
                                    assertNotNull(System.getProperty("developmentFundingRuntimeClasspath")),
                                    "com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingNodeKt",
                                ) + if (structured) listOf("--cluster-env") else listOf(runRoot.toString()),
                            ).await(5_000)
                    val output = result.stdout + result.stderr
                    val expected =
                        if (raw == "FUNDS" || raw == "MATCHING") "SPLIT_ROLE_NOT_ENABLED: $raw" else "INVALID_SERVICE_ROLE"

                    assertFalse(result.timedOut, result.failure())
                    assertEquals(1, result.exitCode, result.failure())
                    assertTrue(output.contains(expected), output)
                    assertFalse(output.contains("READY"), output)
                    assertFalse(Files.exists(runRoot))
                } finally {
                    assertTrue(runner.closeRunning().isEmpty(), "거절된 main 프로세스를 종료해야 합니다")
                }
            }
        }
    }

    private fun environment(root: Path): Map<String, String> =
        mapOf(
            "FUNDING_RUN_ROOT" to root.toString(),
            "FUNDING_RUN_ID" to "role-startup",
            "FUNDING_HOST" to "127.0.0.1",
            "FUNDING_MEMBERS" to "0,127.0.0.1:19700,127.0.0.1:19701,127.0.0.1:19702,127.0.0.1:19703,127.0.0.1:19704",
        )

    // 파일 읽기와 DB 설정 해석까지 진행하면 역할 거절 대신 다른 오류가 드러난다.
    private fun invalidExternalSettings(root: Path): Map<String, String> =
        environment(root) +
            mapOf(
                "FUNDING_BOUNDED_MANIFEST" to directory.resolve("missing-manifest.bin").toString(),
                "FUNDING_MARKET_DB_URL" to "invalid-database-url",
            )

    private fun metricsWorkers(): Set<Long> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.isAlive && it.name == "trading-node-metrics" }
            .map { it.threadId() }
            .toSet()
}
