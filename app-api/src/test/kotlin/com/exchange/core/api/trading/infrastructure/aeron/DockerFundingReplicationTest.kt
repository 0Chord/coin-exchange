package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** 하나의 이미지·한 P1을 실제 Docker UDP 클러스터로 검증한다. Docker 불가는 실패다. */
class DockerFundingReplicationTest {
    @Test
    fun `한 번 보낸 P1의 자금과 OPENING이 세 독립 컨테이너의 같은 위치에 복제된다`() {
        val verification = DockerFundingVerification()
        val result = verification.run()
        assertTrue(
            result.succeeded,
            "${result.issues.joinToString("\n") { "${it.category}: ${it.message}" }}\n보고서: ${verification.reportDirectory}",
        )
    }
}

private class DockerFundingVerification {
    private val mapper = jacksonObjectMapper()
    private val projectDirectory = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
    private val distributionDirectory = Path.of(requireNotNull(System.getProperty("fundingDistributionDirectory")))
    private val runId = "funding${System.currentTimeMillis()}${UUID.randomUUID().toString().take(8)}"
    val reportDirectory: Path = projectDirectory.resolve("build/reports/aeron-funding/$runId")
    private val composeFile = projectDirectory.resolve("src/test/docker/aeron-funding/compose.yml")
    private val dockerfile = projectDirectory.resolve("src/test/docker/aeron-funding/Dockerfile")
    private val image = "exchange-funding:$runId"
    private val environment = mapOf("FUNDING_IMAGE" to image, "FUNDING_RUN_ID" to runId)
    private val issues = mutableListOf<FundingVerificationIssue>()
    private val commands = mutableListOf<Map<String, Any?>>()
    private val timings = linkedMapOf<String, Long>()
    private val nodeIds = linkedMapOf<Int, String>()
    private val events = linkedMapOf<Int, List<JsonNode>>()
    private val inspections = linkedMapOf<Int, JsonNode>()
    private var clientEvents = emptyList<JsonNode>()
    private var clientCreated = false
    private var inputAttempted = false
    private var composeAttempted = false
    private var cleanStop = false
    private var comparison = FundingReplicationComparison(null, emptyList())
    private var stage = "준비"

    fun run(): FundingReplicationComparison {
        Files.createDirectories(reportDirectory)
        try {
            timed("image") {
                must(command(listOf("docker", "version"), 15_000), "Docker 준비")
                must(compose(listOf("version"), 15_000), "Compose 준비")
                check(Files.isDirectory(distributionDirectory.resolve("lib"))) { "선행 Gradle 작업의 개발 배포 산출물이 없습니다: $distributionDirectory" }
                must(
                    command(listOf("docker", "build", "-f", dockerfile.toString(), "-t", image, distributionDirectory.toString()), 300_000),
                    "이미지 준비",
                )
                must(command(listOf("docker", "image", "inspect", image), 15_000), "이미지 식별")
            }
            timed("startup") {
                stage = "기동"
                val deadline = deadline(60_000)
                composeAttempted = true
                must(compose(listOf("up", "-d", "node0", "node1", "node2"), remaining(deadline)), "세 노드 동시 기동")
                findNodeIds(deadline)
                while (true) {
                    collectNodes(deadline, requireRunning = true)
                    val readiness = compareFundingReadiness(runId, events)
                    if (readiness.isEmpty()) break
                    check(System.nanoTime() < deadline) { readiness.joinToString("; ") { it.message } }
                    if (events.values.flatten().any { it.path("kind").asString() == "ERROR" }) error("노드 오류로 준비를 완료하지 못했습니다")
                    Thread.sleep(100)
                }
                validateIsolation()
                check(issues.isEmpty()) { "컨테이너 격리·설정 확인 실패" }
            }
            timed("client") {
                stage = "클라이언트"
                inputAttempted = true
                clientCreated = true
                val response = compose(listOf("run", "--use-aliases", "--name", "$runId-client", "client"), 20_000)
                Files.writeString(reportDirectory.resolve("client.stdout.log"), response.stdout)
                Files.writeString(reportDirectory.resolve("client.stderr.log"), response.stderr)
                clientEvents = readFundingEvents(response.stdout, issues)
                if (!response.succeeded) issues.add(FundingVerificationIssue("UNVERIFIABLE", "클라이언트 응답 미확인: ${response.failure()}"))
            }
            timed("observations") {
                stage = "관찰"
                val deadline = deadline(15_000)
                while (true) {
                    collectNodes(deadline, requireRunning = true)
                    comparison = compareFundingReplication(runId, events, clientEvents)
                    issues.addAll(comparison.issues.filter { it.category == "MISMATCH" })
                    // 누락은 상한까지 기다린다. 이미 관찰한 업무 차이는 최종 결과에 그대로 남긴다.
                    if (hasThreeFreshObservations() || events.values.flatten().any { it.path("kind").asString() == "ERROR" }) break
                    if (System.nanoTime() >= deadline) break
                    Thread.sleep(100)
                }
                issues.addAll(comparison.issues)
            }
        } catch (error: Exception) {
            issues.add(
                FundingVerificationIssue(
                    if (inputAttempted) "UNVERIFIABLE" else "SETUP",
                    "$stage 실패: ${error.javaClass.simpleName}: ${error.message}",
                ),
            )
        } finally {
            preserveAndClose()
            if (inputAttempted) {
                // 수집/정리 실패가 기존의 업무 불일치나 클라이언트 미확인을 지우지 않는다.
                comparison = compareFundingReplication(runId, events, clientEvents, collectionIssues = issues)
            } else {
                comparison = FundingReplicationComparison(null, issues.distinct())
            }
            writeReport()
        }
        return comparison
    }

    private fun findNodeIds(
        deadline: Long,
        required: Boolean = true,
    ) {
        nodeIds.putAll(
            discoverFundingNodeIds(issues) { member ->
                val result = compose(listOf("ps", "--all", "--quiet", "node$member"), remaining(deadline))
                must(result, "node$member 컨테이너 식별")
                val ids =
                    result.stdout
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .toList()
                check(ids.size == 1) { "node$member 컨테이너가 한 개가 아닙니다: $ids" }
                ids.single()
            },
        )
        if (required) check(nodeIds.size == 3) { "세 노드 식별을 완료하지 못했습니다" }
    }

    private fun collectNodes(
        deadline: Long,
        requireRunning: Boolean,
    ) {
        for ((member, id) in nodeIds) {
            try {
                val logs = command(listOf("docker", "logs", id), remaining(deadline))
                if (!logs.succeeded) issues.add(FundingVerificationIssue("COLLECTION", "node$member 로그 수집 실패: ${logs.failure()}"))
                Files.writeString(reportDirectory.resolve("node$member.stdout.log"), logs.stdout)
                Files.writeString(reportDirectory.resolve("node$member.stderr.log"), logs.stderr)
                val parsed = readFundingEvents(logs.stdout, issues)
                events[member] = mergeFundingObservations(events[member].orEmpty(), parsed)
                val inspected = command(listOf("docker", "inspect", id), remaining(deadline))
                if (inspected.succeeded) {
                    val data = mapper.readTree(inspected.stdout).path(0)
                    inspections[member] = data
                    Files.writeString(reportDirectory.resolve("node$member.inspect.json"), inspected.stdout)
                    if (requireRunning && !data.path("State").path("Running").asBoolean()) {
                        error("node$member 예상 밖 종료: ${data.path("State")}")
                    }
                } else {
                    issues.add(FundingVerificationIssue("COLLECTION", "node$member 컨테이너 조회 실패: ${inspected.failure()}"))
                }
            } catch (error: Exception) {
                issues.add(FundingVerificationIssue("COLLECTION", "node$member 근거 수집 실패: ${error.message}"))
            }
        }
    }

    private fun validateIsolation() {
        if (nodeIds.values.toSet().size != 3 || inspections.size != 3) {
            issues.add(FundingVerificationIssue("SETUP", "서로 다른 세 컨테이너의 식별 근거가 부족합니다"))
            return
        }
        if (inspections.values
                .map { it.path("Image").asString() }
                .toSet()
                .size != 1
        ) {
            issues.add(FundingVerificationIssue("SETUP", "세 컨테이너가 같은 실제 이미지를 사용하지 않습니다"))
        }
        val networks =
            inspections.values.map {
                it
                    .path("NetworkSettings")
                    .path("Networks")
                    .propertyNames()
                    .toSet()
            }
        if (networks.any { it.size != 1 } || networks.toSet().size != 1) {
            issues.add(FundingVerificationIssue("SETUP", "세 노드가 같은 전용 bridge 하나에 있지 않습니다: $networks"))
        }
        val mounts = inspections.mapValues { (_, node) -> node.path("Mounts").filter { it.path("Destination").asString() == "/records" } }
        val volumes = mounts.values.flatten().map { it.path("Name").asString() }
        if (mounts.values.any { it.size != 1 } || volumes.toSet().size != 3 || volumes.any { it.isBlank() }) {
            issues.add(FundingVerificationIssue("SETUP", "세 노드의 별도 기록 볼륨이 아닙니다: $volumes"))
        }
        for ((member, node) in inspections) {
            if (node.path("HostConfig").path("NetworkMode").asString() == "host" ||
                node.path("HostConfig").path("IpcMode").asString() == "host"
            ) {
                issues.add(FundingVerificationIssue("SETUP", "node$member 호스트 network/IPC로 격리를 우회했습니다"))
            }
        }
    }

    private fun hasThreeFreshObservations(): Boolean {
        val leader =
            events.values.flatten().lastOrNull {
                it.path("kind").asString() == "APPLIED" && it.path("role").asString() == "LEADER" &&
                    it.path("runId").asString() == runId
            }
        val position = leader?.path("position")?.asLong() ?: return false
        return position > 0 &&
            (0..2).all { member ->
                events[member].orEmpty().any {
                    it.path("kind").asString() == "APPLIED" && it.path("runId").asString() == runId &&
                        it.path("preparationId").asString() == "P1" && it.path("position").asLong() == position
                }
            }
    }

    private fun preserveAndClose() {
        if (!composeAttempted) return
        try {
            timed("final-observations") {
                val deadline = deadline(15_000)
                if (nodeIds.size < 3) findNodeIds(deadline, required = false)
                collectNodes(deadline, requireRunning = inputAttempted)
                if (clientCreated) {
                    val client = command(listOf("docker", "inspect", "$runId-client"), remaining(deadline))
                    if (client.succeeded) {
                        Files.writeString(reportDirectory.resolve("client.inspect.json"), client.stdout)
                        val data = mapper.readTree(client.stdout).path(0)
                        val node = inspections[0]
                        if (node != null && (
                                data.path("Image") != node.path("Image") ||
                                    data
                                        .path("NetworkSettings")
                                        .path("Networks")
                                        .propertyNames()
                                        .toSet() !=
                                    node
                                        .path("NetworkSettings")
                                        .path("Networks")
                                        .propertyNames()
                                        .toSet()
                            )
                        ) {
                            issues.add(FundingVerificationIssue("SETUP", "클라이언트가 같은 실제 이미지·전용 bridge에 있지 않습니다"))
                        }
                    } else {
                        issues.add(FundingVerificationIssue("COLLECTION", "클라이언트 식별 수집 실패: ${client.failure()}"))
                    }
                }
            }
        } catch (error: Exception) {
            issues.add(FundingVerificationIssue("COLLECTION", "종료 전 관찰 수집 실패: ${error.message}"))
        }
        try {
            timed("stop") {
                val deadline = deadline(15_000)
                val stopped = compose(listOf("stop", "--timeout", "10", "node0", "node1", "node2"), remaining(deadline))
                cleanStop = stopped.succeeded
                if (!cleanStop) issues.add(FundingVerificationIssue("CLEANUP", "정상 종료 확인 실패: ${stopped.failure()}"))
                collectNodes(deadline, requireRunning = false)
                for ((member, id) in nodeIds) {
                    val stoppedEvent =
                        events[member].orEmpty().lastOrNull {
                            it.path("kind").asString() == "STOPPED" && it.path("runId").asString() == runId &&
                                it.path("memberId").asInt() == member
                        }
                    if (stoppedEvent == null || !stoppedEvent.path("closed").asBoolean() || stoppedEvent.hasNonNull("failure")) {
                        cleanStop = false
                        issues.add(FundingVerificationIssue("CLEANUP", "node$member 기록 파일을 닫은 STOPPED 근거가 없습니다: $stoppedEvent"))
                    }
                    val inspected = inspections[member]
                    if (inspected != null) {
                        Files.writeString(reportDirectory.resolve("node$member.stopped.inspect.json"), inspected.toPrettyString())
                        val state = inspected.path("State")
                        val exitCode = state.path("ExitCode").asInt()
                        if (state.path("Running").asBoolean() || state.path("OOMKilled").asBoolean() || exitCode !in listOf(0, 143)) {
                            cleanStop = false
                            issues.add(FundingVerificationIssue("CLEANUP", "node$member 정상 JVM 종료가 아닙니다: $state"))
                        }
                    } else {
                        cleanStop = false
                        issues.add(FundingVerificationIssue("CLEANUP", "node$member 종료 상태가 없습니다: $id"))
                    }
                }
            }
        } catch (error: Exception) {
            cleanStop = false
            issues.add(FundingVerificationIssue("CLEANUP", "정상 종료 근거 수집 실패: ${error.message}"))
        }
        try {
            timed("records") {
                val deadline = deadline(15_000)
                for ((member, id) in nodeIds) {
                    val target = reportDirectory.resolve("node$member-records")
                    Files.createDirectories(target)
                    for (directory in listOf("archive", "cluster")) {
                        val copied =
                            command(listOf("docker", "cp", "$id:/records/$runId/$directory", target.toString()), remaining(deadline))
                        if (!copied.succeeded) {
                            issues.add(
                                FundingVerificationIssue("COLLECTION", "node$member $directory 미생성 또는 복사 실패: ${copied.failure()}"),
                            )
                        }
                    }
                }
            }
        } catch (error: Exception) {
            issues.add(FundingVerificationIssue("COLLECTION", "기록 수집 실패: ${error.message}"))
        } finally {
            timed("cleanup") {
                val removed = compose(listOf("down", "--remove-orphans", "--timeout", "1"), 15_000)
                if (!removed.succeeded) issues.add(FundingVerificationIssue("CLEANUP", "컨테이너·네트워크 정리 실패: ${removed.failure()}"))
            }
        }
    }

    private fun writeReport() {
        val report =
            linkedMapOf(
                "runId" to runId,
                "createdAt" to Instant.now().toString(),
                "success" to comparison.succeeded,
                "position" to comparison.position,
                "inputAttempted" to inputAttempted,
                "transportAccepted" to clientEvents.any { it.path("kind").asString() == "TRANSPORT_ACCEPTED" },
                "resultReceived" to clientEvents.any { it.path("kind").asString() == "RESULT_RECEIVED" },
                "cleanStop" to cleanStop,
                "recordVolumesDeleted" to false,
                "limitsMs" to
                    mapOf(
                        "image" to 300_000,
                        "startup" to 60_000,
                        "clientProcess" to 20_000,
                        "clientResult" to 5_000,
                        "observations" to 15_000,
                        "stop" to 15_000,
                        "records" to 15_000,
                        "cleanup" to 15_000,
                    ),
                "durationMs" to timings,
                "issues" to comparison.issues,
                "commands" to commands,
                "observations" to events,
                "clientEvents" to clientEvents,
            )
        Files.writeString(reportDirectory.resolve("result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        Files.writeString(
            reportDirectory.resolve("summary.txt"),
            buildString {
                appendLine("${if (comparison.succeeded) "PASS" else "FAIL"} $runId; appliedPosition=${comparison.position}")
                appendLine("P1 한 건, 세 독립 Docker JVM. 복구·장애 전환·성능 검증은 포함하지 않습니다.")
                appendLine("입력 시도=$inputAttempted; 정상 종료 확인=$cleanStop; 기록 볼륨 삭제는 요청하지 않았습니다.")
                comparison.issues.forEach { appendLine("${it.category}: ${it.message}") }
                appendLine("durationMs=$timings")
            },
        )
    }

    private fun compose(
        arguments: List<String>,
        timeoutMs: Long,
    ): CommandResult =
        command(listOf("docker", "compose", "--project-name", runId, "--file", composeFile.toString()) + arguments, timeoutMs)

    private fun command(
        arguments: List<String>,
        timeoutMs: Long,
    ): CommandResult {
        val index = commands.size
        val stdout = reportDirectory.resolve("command-$index.stdout.log")
        val stderr = reportDirectory.resolve("command-$index.stderr.log")
        val started = System.nanoTime()
        var exit: Int? = null
        var failure: String? = null
        var timedOut = false
        try {
            val builder =
                ProcessBuilder(
                    arguments,
                ).directory(projectDirectory.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
            builder.environment().putAll(environment)
            val process = builder.start()
            if (!process.waitFor(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
                timedOut = true
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            } else {
                exit = process.exitValue()
            }
        } catch (error: Exception) {
            failure = "${error.javaClass.simpleName}: ${error.message}"
        }
        val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        commands.add(
            mapOf(
                "arguments" to arguments,
                "exitCode" to exit,
                "timedOut" to timedOut,
                "failure" to failure,
                "durationMs" to duration,
            ),
        )
        return CommandResult(
            exit,
            timedOut,
            failure,
            if (Files.exists(stdout)) Files.readString(stdout) else "",
            if (Files.exists(stderr)) Files.readString(stderr) else "",
        )
    }

    private fun must(
        result: CommandResult,
        action: String,
    ) = check(result.succeeded) { "$action: ${result.failure()}" }

    private fun timed(
        name: String,
        action: () -> Unit,
    ) {
        val start = System.nanoTime()
        try {
            action()
        } finally {
            timings[name] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        }
    }

    private fun deadline(milliseconds: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)

    private fun remaining(deadline: Long): Long {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        check(remaining > 0) { "단계 대기 상한을 초과했습니다" }
        return remaining
    }

    private data class CommandResult(
        val exitCode: Int?,
        val timedOut: Boolean,
        val error: String?,
        val stdout: String,
        val stderr: String,
    ) {
        val succeeded: Boolean get() = exitCode == 0 && !timedOut && error == null

        fun failure(): String = "exit=$exitCode, timedOut=$timedOut, error=$error, stderr=${stderr.takeLast(4000)}"
    }
}
