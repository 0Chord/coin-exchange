package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerVerificationCleanupTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `A 최종 출력 파일을 쓸 수 없어도 B 수집과 노드 종료 정리 보고서 작성을 이어간다`() {
        val runner = verification()
        val runId = field<String>(runner, "runId")
        val original = FundingVerificationIssue("MISMATCH", "앞서 확인한 예약액 불일치")
        field<MutableList<FundingVerificationIssue>>(runner, "issues").add(original)
        field<MutableList<String>>(runner, "clients").addAll(listOf("$runId-client-a", "$runId-client-b"))
        setField(runner, "composeAttempted", true)
        setField(runner, "openingPosition", 128L)
        val blockedOutput = directory.resolve("a-client.final.stdout.log")
        Files.createDirectory(blockedOutput)

        // 무작위의 미존재 이미지에서 준비를 끝내 실제 컨테이너는 생성하지 않는다.
        // 종료 대상 이름만 준비해 run()의 finally와 실제 파일 쓰기 실패를 통과시킨다.
        val result = runner.run()

        assertFalse(result.succeeded)
        assertTrue(original in result.issues)
        assertTrue(
            result.issues.any { it.category == "COLLECTION" && it.message.contains("A 최종 클라이언트 출력 수집 실패") },
            result.issues.toString(),
        )
        assertTrue(Files.isDirectory(blockedOutput))
        assertTrue(Files.isRegularFile(directory.resolve("b-client.final.stdout.log")))
        assertTrue(Files.isRegularFile(directory.resolve("b-client.final.stderr.log")))

        val commands = field<VerificationCommands>(runner, "commandProcesses").records.map { it.getValue("arguments") as List<*> }
        val aLogs = commands.indexOfFirst { "logs" in it && "$runId-client-a" in it }
        val bLogs = commands.indexOfFirst { "logs" in it && "$runId-client-b" in it }
        val nodeStop = commands.indexOfFirst { "stop" in it && listOf("node0", "node1", "node2").all(it::contains) }
        val composeDown = commands.indexOfFirst { "compose" in it && "down" in it }
        assertTrue(aLogs >= 0 && bLogs > aLogs, "A 실패 뒤 B logs 시도가 없습니다: $commands")
        assertTrue(nodeStop > bLogs, "B 수집 뒤 노드 stop 시도가 없습니다: $commands")
        assertTrue(composeDown > nodeStop, "노드 stop 뒤 compose down 시도가 없습니다: $commands")
        assertTrue(commands.none { "up" in it || "run" in it || "build" in it || "pull" in it })

        val report = jacksonObjectMapper().readTree(Files.readString(directory.resolve("result.json")))
        assertFalse(report.path("success").asBoolean())
        assertTrue(
            report.path("issues").any {
                it.path("category").asString() == "MISMATCH" &&
                    it.path("message").asString() == original.message
            },
        )
        assertTrue(
            report.path("issues").any {
                it.path("category").asString() == "COLLECTION" &&
                    it.path("message").asString().contains("A 최종 클라이언트 출력 수집 실패")
            },
        )
        assertTrue(Files.readString(directory.resolve("summary.txt")).contains(original.message))
    }

    @Test
    fun `동시 단계의 상한과 확인한 GO 대기를 기록하고 실패한 단계의 소요 시간도 남긴다`() {
        val runner = verification()
        val events = field<MutableMap<String, List<JsonNode>>>(runner, "pairClientEvents")
        events["A"] = listOf(jacksonObjectMapper().readTree("""{"kind":"READY","gateTimeoutMs":60000}"""))
        // B의 READY는 관찰하지 않았다. 임의의 GO 상한을 대신 채우면 안 된다.
        val failedAction: () -> Unit = { error("controlled-response-failure") }
        val timed = runner.javaClass.getDeclaredMethod("timed", String::class.java, Function0::class.java).apply { isAccessible = true }
        val error = assertFailsWith<InvocationTargetException> { timed.invoke(runner, "concurrent-responses", failedAction) }
        assertEquals("controlled-response-failure", error.cause?.message)

        runner.javaClass
            .getDeclaredMethod("writeReport")
            .apply { isAccessible = true }
            .invoke(runner)

        val report = jacksonObjectMapper().readTree(Files.readString(directory.resolve("result.json")))
        val limits = report.path("limitsMs")
        assertEquals(90_000L, limits.path("concurrentReady").asLong())
        assertEquals(20_000L, limits.path("concurrentResponses").asLong())
        assertEquals(15_000L, limits.path("concurrentObservations").asLong())
        assertEquals(60_000L, limits.path("concurrentClientGoById").path("A").asLong())
        assertTrue(limits.path("concurrentClientGoById").path("B").isNull)
        val duration = report.path("durationMs").path("concurrent-responses")
        assertTrue(duration.isIntegralNumber && duration.asLong() >= 0L)
    }

    private fun verification(): DockerFundingVerification {
        val runner =
            DockerFundingVerification(sharedImage = "exchange-cleanup-missing-${UUID.randomUUID()}:unused", concurrentOrders = true)
        val project = field<Path>(runner, "projectDirectory")
        val runId = field<String>(runner, "runId")
        val distribution = Files.createDirectories(directory.resolve("distribution"))
        Files.createDirectories(distribution.resolve("lib"))
        setField(runner, "reportDirectory", directory)
        setField(runner, "distributionDirectory", distribution)
        setField(
            runner,
            "commandProcesses",
            VerificationCommands(
                project,
                directory,
                mapOf(
                    "FUNDING_IMAGE" to runner.image,
                    "FUNDING_RUN_ID" to runId,
                    "FUNDING_CONTROL_DIRECTORY" to directory.toString(),
                ),
            ),
        )
        return runner
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(
        target: Any,
        name: String,
    ): T =
        target.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(target) as T

    private fun setField(
        target: Any,
        name: String,
        value: Any,
    ) {
        target.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(target, value)
    }
}
