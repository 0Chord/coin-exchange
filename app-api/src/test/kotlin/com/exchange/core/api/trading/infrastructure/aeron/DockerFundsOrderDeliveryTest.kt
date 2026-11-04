package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** 독립 JVM 3+3개를 실제 실행한다. Docker에 연결할 수 없으면 검증 실패다. */
class DockerFundsOrderDeliveryTest {
    @Test
    fun `reserved orders pipeline across two real clusters and survive log and snapshot tail restart`() {
        val verification = DockerFundsOrderDeliveryVerification()
        assertTrue(verification.run(), "${verification.failures.joinToString("\n")}\n보고서: ${verification.reports}")
    }
}

internal class DockerFundsOrderDeliveryVerification(
    private val resultFixture: String? = null,
    private val resultScenario: ((DockerFundsOrderDeliveryVerification) -> Unit)? = null,
) {
    internal val mapper = jacksonObjectMapper()
    private val project = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
    private val distribution = Path.of(requireNotNull(System.getProperty("fundingDistributionDirectory")))
    private val runId = "delivery${System.currentTimeMillis()}${UUID.randomUUID().toString().take(8)}"
    val reports: Path =
        project.resolve(
            "build/reports/aeron-funding/${if (resultScenario == null) "order-delivery" else "result-settlement"}/$runId",
        )
    internal val control = reports.resolve("control")
    private val image = "exchange-order-delivery:$runId"
    private val composeFile = project.resolve("src/test/docker/aeron-order-delivery/compose.yml")
    private val environment =
        mutableMapOf(
            "SPLIT_IMAGE" to image,
            "SPLIT_RUN_ID" to runId,
            "SPLIT_CONTROL_DIRECTORY" to control.toString(),
            "SPLIT_RECOVER_EXISTING" to "false",
            "SPLIT_RESULT_FIXTURE" to (resultFixture ?: "baseline"),
            "SPLIT_MAX_RESULT_ENTRIES" to (if (resultFixture == "cap") "1" else "4096"),
            "SPLIT_MAX_MATCHING_ACTIVE" to (if (resultFixture == "active") "1" else "8192"),
        )
    private val commands = VerificationCommands(project, reports, environment)
    val failures = mutableListOf<String>()
    internal val checkpoints = mutableListOf<Map<String, Any?>>()
    private val containers = linkedMapOf<String, String>()
    private val inspections = linkedMapOf<String, JsonNode>()
    private var originalVolumes = emptyMap<String, String>()
    private var frozenImageId = ""
    private var frozenManifestSha256 = ""
    private var headCommit = ""
    private var hostFreeDiskBytes: Long? = null
    private var dockerMemoryBytes: Long? = null
    private var recordsBytes: Long = 0
    private var reportBytes: Long = 0
    private var composeAttempted = false
    private var imageBuilt = false
    private var cleaned = false
    private var clientSequence = 0
    private var cleanupMode = false
    private var stage = "preflight"
    private val started = System.nanoTime()
    private val overallDeadline = started + TimeUnit.MINUTES.toNanos(8)
    private val workDeadline = overallDeadline - TimeUnit.SECONDS.toNanos(45)

    init {
        Files.createDirectories(control)
    }

    fun run(): Boolean {
        try {
            preflight()
            stage = "frozen image and manifest"
            check(Files.isDirectory(distribution.resolve("test-classes")) && Files.isDirectory(distribution.resolve("lib"))) {
                "stageFundingVerificationRuntime 배포 산출물이 없습니다: $distribution"
            }
            must(
                command(
                    listOf(
                        "docker",
                        "build",
                        "-f",
                        project.resolve("src/test/docker/aeron-order-delivery/Dockerfile").toString(),
                        "-t",
                        image,
                        distribution.toString(),
                    ),
                    180_000,
                ),
                "동결 실행 이미지 준비",
            )
            imageBuilt = true
            frozenImageId =
                must(command(listOf("docker", "image", "inspect", "--format", "{{.Id}}", image), 10_000), "실행 이미지 식별").stdout.trim()
            check(frozenImageId.startsWith("sha256:"))
            composeAttempted = true
            tool("setup")
            frozenManifestSha256 = sha256(control.resolve("manifest.bin"))
            startNodes("initial", emptyMap())
            tool("prepare")
            awaitStates("initialized roles", 60_000) { states -> states.values.all { it.path("initialized").asBoolean() } }

            if (resultScenario != null) {
                stage = "result settlement ${resultFixture ?: "baseline"}"
                resultScenario.invoke(this)
            } else {
                stage = "pipeline while first matching ACK is lost"
                Files.writeString(control.resolve("hold-matching-acks"), UUID.randomUUID().toString())
                tool("admit", "1", "6")
                awaitExpected("G3 first matching application with missing ACK", 1, 6, 1, 0)
                recordFragmentedDispatchEvidence()
                verifyObservationReadableByDifferentUser()
                tool("admit", "2", "2")
                awaitExpected("G4 second matching log application before ACK1", 2, 8, 2, 0)
                Files.delete(control.resolve("hold-matching-acks"))
                awaitExpected("G3 G4 source committed cumulative ACK2", 2, 8, 0, 2)
                val duplicate = mapper.readTree(tool("replay", "2").stdout.lineSequence().last { it.isNotBlank() })
                check(duplicate.path("code").asString() == "DUPLICATE" && number(duplicate, "through") == 2L)
                awaitExpected("G5 original dispatch2 sent again without another matching effect", 2, 8, 0, 2)

                stage = "full log restart after committed source ACK"
                check(readStates().values.all { number(it, "snapshotCount") == 0L }) { "전체 로그 복구 전에 snapshot이 생겼습니다" }
                restart("G18 full log recovery", 2, 8, 0, 2, false)

                stage = "snapshots at input2 followed by input3 in each log tail"
                tool("snapshot", "funds")
                tool("snapshot", "matching")
                val atSnapshot =
                    awaitStates("both role snapshots at input2", 60_000) { states ->
                        expected(states, 2, 8, 0, 2) &&
                            states.values.all { number(it, "snapshotCount") >= 1 && number(it, "lastSnapshotPosition") > 0 }
                    }
                Files.writeString(control.resolve("hold-source-records"), UUID.randomUUID().toString())
                Files.deleteIfExists(control.resolve("received-matching-ack.json"))
                tool("admit", "3", "1")
                val beforeRestart = awaitExpected("G6 source retains input3 after matching ACK reception", 3, 9, 1, 2)
                awaitFile(control.resolve("received-matching-ack.json"), 30_000)
                validateMatchingAck(3, beforeRestart)
                Files.copy(control.resolve("received-matching-ack.json"), reports.resolve("matching-ack-before-source-record.json"))
                for (service in SERVICES) {
                    check(
                        number(beforeRestart.getValue(service), "logPosition") >
                            number(atSnapshot.getValue(service), "lastSnapshotPosition"),
                    ) {
                        "$service input3이 snapshot 이후의 자기 로그 tail에 없습니다"
                    }
                }
                Files.delete(control.resolve("received-matching-ack.json"))
                restart("G6 G19 snapshot2 plus log tail3 with source obligation", 3, 9, 1, 2, true)
                awaitFile(control.resolve("received-matching-ack.json"), 30_000)
                validateMatchingAck(3, readStates(), setOf("DUPLICATE", "PRUNED"))
                Files.copy(control.resolve("received-matching-ack.json"), reports.resolve("matching-ack-after-recovery.json"))
                Files.delete(control.resolve("hold-source-records"))
                awaitExpected("G6 G19 replayed input3 ACK committed without matching again", 3, 9, 0, 3)

                stage = "source ACK3 restored from the tail after snapshot2"
                restart("G20 funds snapshot2 plus committed ACK3 log tail", 3, 9, 0, 3, true)

                stage = "G16 funds admission progress and snapshot while matching is stopped"
                verifyFundsProgressWhileMatchingStopped()
                tool("admit", "4", "1")
                awaitExpected("G5 repeated user request keeps original identity and one effect", 4, 10, 0, 4)
            }
            check(sha256(control.resolve("manifest.bin")) == frozenManifestSha256) { "복구 도중 manifest가 변경됐습니다" }
            checkStorageBudget()
        } catch (failure: Throwable) {
            failures += "$stage: ${failure.javaClass.simpleName}: ${failure.message}"
        } finally {
            close()
            writeReport()
        }
        return failures.isEmpty() && cleaned
    }

    private fun preflight() {
        headCommit = must(command(listOf("git", "rev-parse", "HEAD"), 5_000), "검증 HEAD 확인").stdout.trim()
        hostFreeDiskBytes = Files.getFileStore(project).usableSpace
        check(checkNotNull(hostFreeDiskBytes) >= 8L * GIB) { "호스트 기록 디스크 여유 8GiB 필요: $hostFreeDiskBytes bytes" }
        must(command(listOf("docker", "version"), 15_000), "Docker 준비")
        must(compose(listOf("version"), 15_000), "Compose 준비")
        val memory = must(command(listOf("docker", "info", "--format", "{{.MemTotal}}"), 15_000), "Docker 메모리 확인").stdout.trim().toLong()
        dockerMemoryBytes = memory
        check(memory >= 6L * GIB) { "6노드와 client를 위한 Docker 메모리 설정 6GiB 필요: $memory bytes" }
    }

    private fun startNodes(
        label: String,
        oldBootIds: Map<String, String>,
    ) {
        val startupDeadline = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(90))
        must(compose(listOf("up", "-d", "--force-recreate") + SERVICES, remaining(startupDeadline)), "6개 독립 노드 기동")
        for (service in SERVICES) {
            val id = must(compose(listOf("ps", "-q", service), minOf(10_000, remaining(startupDeadline))), "$service 컨테이너 식별").stdout.trim()
            check(id.matches(Regex("[0-9a-f]{12,64}"))) { "$service 실제 컨테이너가 없습니다: $id" }
            containers[service] = id
        }
        awaitStates("$label all six current boot states and completed runtimes", remaining(startupDeadline)) { states ->
            states.all { (service, state) ->
                number(state, "pid") > 0 && state.path("bootId").asString().isNotBlank() &&
                    (oldBootIds[service] == null || oldBootIds[service] != state.path("bootId").asString()) &&
                    runtimeReadyFor(service, state)
            }
        }
        inspectIsolation(label)
        checkStorageBudget()
    }

    private fun restart(
        label: String,
        issued: Long,
        totalQuantity: Long,
        dispatches: Long,
        ackThrough: Long,
        restoredSnapshot: Boolean,
    ) {
        val before = readStates()
        val boots = before.mapValues { (_, state) -> state.path("bootId").asString() }
        collectLogs("before-restart-${checkpoints.size}")
        must(compose(listOf("stop", "--timeout", "15") + SERVICES, 30_000), "기록을 유지한 6노드 종료")
        for ((service, id) in containers) {
            val state = inspect(id).path("State")
            check(!state.path("Running").asBoolean() && !state.path("OOMKilled").asBoolean()) { "$service 종료 실패 또는 OOM" }
            check(state.path("ExitCode").asInt() in setOf(0, 143)) { "$service 비정상 종료: $state" }
            Files.deleteIfExists(control.resolve("state-$service.json"))
            Files.deleteIfExists(control.resolve("runtime-$service.json"))
        }
        if (Files.exists(control.resolve("hold-source-records"))) Files.deleteIfExists(control.resolve("received-matching-ack.json"))
        environment["SPLIT_RECOVER_EXISTING"] = "true"
        startNodes(label, boots)
        val restored = awaitExpected(label, issued, totalQuantity, dispatches, ackThrough)
        check(restored.values.all { it.path("restoredSnapshot").asBoolean() == restoredSnapshot }) {
            "전체 로그 복구와 snapshot+tail 복구의 실제 snapshotImage 사용이 다릅니다"
        }
        check(sha256(control.resolve("manifest.bin")) == frozenManifestSha256)
    }

    internal fun restartResults(
        label: String,
        restoredSnapshot: Boolean,
        expectation: (Map<String, JsonNode>) -> Boolean,
    ): Map<String, JsonNode> {
        val before = readStates()
        val boots = before.mapValues { (_, state) -> state.path("bootId").asString() }
        val receipts = before.filterKeys { it.startsWith("funds") }.mapValues { (_, state) -> state.path("funds").path("receipts") }
        collectLogs("before-result-restart-${checkpoints.size}")
        must(compose(listOf("stop", "--timeout", "15") + SERVICES, 30_000), "같은 기록의 6노드 종료")
        for ((service, id) in containers) {
            val state = inspect(id).path("State")
            check(!state.path("Running").asBoolean() && !state.path("OOMKilled").asBoolean())
            check(state.path("ExitCode").asInt() in setOf(0, 143)) { "$service 비정상 종료: $state" }
            Files.deleteIfExists(control.resolve("state-$service.json"))
            Files.deleteIfExists(control.resolve("runtime-$service.json"))
        }
        environment["SPLIT_RECOVER_EXISTING"] = "true"
        startNodes(label, boots)
        return awaitStates(label, 45_000, expectation).also { states ->
            check(states.values.all { it.path("restoredSnapshot").asBoolean() == restoredSnapshot })
            check(receipts.all { (name, expected) -> states.getValue(name).path("funds").path("receipts") == expected }) {
                "재시작으로 최초 정산 시각이나 완결 사건·효과가 바뀌었습니다"
            }
            check(sha256(control.resolve("manifest.bin")) == frozenManifestSha256)
            checkStorageBudget()
        }
    }

    private fun verifyFundsProgressWhileMatchingStopped() {
        val matchingServices = SERVICES.filter { it.startsWith("matching") }
        val fundsServices = SERVICES.filter { it.startsWith("funds") }
        val before = readStates()
        val oldMatchingBoots = before.filterKeys { it.startsWith("matching") }.mapValues { (_, state) -> state.path("bootId").asString() }
        val fundsBoots = before.filterKeys { it.startsWith("funds") }.mapValues { (_, state) -> state.path("bootId").asString() }
        val fundsContainers = containers.filterKeys { it.startsWith("funds") }.toMap()
        must(compose(listOf("stop", "--timeout", "15") + matchingServices, 30_000), "매칭 3노드만 실제 종료")
        val stopped = matchingServices.associateWith { inspect(containers.getValue(it)) }
        check(stopped.values.all { !it.path("State").path("Running").asBoolean() && !it.path("State").path("OOMKilled").asBoolean() })
        check(stopped.values.all { it.path("State").path("ExitCode").asInt() in setOf(0, 143) })
        check(fundsServices.all { inspect(containers.getValue(it)).path("State").path("Running").asBoolean() })
        checkpoints +=
            mapOf(
                "case" to "G16 matching processes stopped while funds processes remain running",
                "matchingContainers" to stopped,
                "fundsBootIds" to fundsBoots,
            )

        tool("admit", "4", "1")
        val offline =
            awaitStates("G16 offline matching retains input4 while all three funds copies admit", 45_000) { states ->
                expected(states.filterKeys { it.startsWith("funds") }, 4, 10, 1, 3) &&
                    expected(states.filterKeys { it.startsWith("matching") }, 3, 9, 0, 3) &&
                    fundsBoots.all { (service, boot) -> states.getValue(service).path("bootId").asString() == boot }
            }
        val progress = mapper.readTree(tool("progress").stdout.lineSequence().last { it.isNotBlank() })
        check(progress.path("code").asString() == "PROGRESS" && progress.path("lifecycle").asString() == "RUNNING")
        check(number(progress, "lastAdmissionSequence") == 4L)
        val market = progress.path("markets").toList().single()
        check(number(market, "lastAssigned") == 4L && number(market, "executedThrough") == 0L)
        check(number(progress, "appliedLogPosition") > 0)
        checkpoints += mapOf("case" to "G16 actual funds ingress progress reply while matching is stopped", "reply" to progress)
        tool("snapshot", "funds")
        awaitStates("G16 funds snapshots complete while matching is still stopped", 45_000) { states ->
            expected(states.filterKeys { it.startsWith("funds") }, 4, 10, 1, 3) &&
                fundsServices.all { service ->
                    val state = states.getValue(service)
                    state.path("bootId").asString() == fundsBoots.getValue(service) &&
                        number(state, "snapshotCount") > number(offline.getValue(service), "snapshotCount") &&
                        number(state, "lastSnapshotPosition") > number(offline.getValue(service), "logPosition")
                }
        }
        check(matchingServices.all { !inspect(containers.getValue(it)).path("State").path("Running").asBoolean() })
        for (service in matchingServices) {
            Files.deleteIfExists(control.resolve("state-$service.json"))
            Files.deleteIfExists(control.resolve("runtime-$service.json"))
        }
        val startupDeadline = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(90))
        must(
            compose(listOf("up", "-d", "--no-deps", "--force-recreate") + matchingServices, remaining(startupDeadline)),
            "같은 기록의 매칭 3노드만 복구",
        )
        for (service in matchingServices) {
            val id =
                must(
                    compose(listOf("ps", "-q", service), minOf(10_000, remaining(startupDeadline))),
                    "$service 복구 컨테이너 식별",
                ).stdout.trim()
            check(id.matches(Regex("[0-9a-f]{12,64}")))
            containers[service] = id
        }
        awaitStates("G16 matching new boots recover while funds boots remain unchanged", remaining(startupDeadline)) { states ->
            fundsBoots.all { (service, boot) -> states.getValue(service).path("bootId").asString() == boot } &&
                oldMatchingBoots.all { (service, boot) ->
                    val state = states.getValue(service)
                    state.path("bootId").asString() != boot && runtimeReadyFor(service, state)
                }
        }
        for (service in fundsServices) {
            val id = must(compose(listOf("ps", "-q", service), 10_000), "$service 계속 실행한 컨테이너 확인").stdout.trim()
            check(id == fundsContainers.getValue(service)) { "매칭 복구 중 자금 컨테이너를 재생성했습니다" }
        }
        inspectIsolation("G16 matching-only recovery")
        awaitExpected("G16 G19 only input4 advances matching after recovery and source ACK4 commits", 4, 10, 0, 4)
    }

    private fun awaitExpected(
        label: String,
        issued: Long,
        totalQuantity: Long,
        dispatches: Long,
        ackThrough: Long,
    ): Map<String, JsonNode> =
        awaitStates(label, 45_000) { expected(it, issued, totalQuantity, dispatches, ackThrough) }.also { checkStorageBudget() }

    private fun expected(
        states: Map<String, JsonNode>,
        issued: Long,
        totalQuantity: Long,
        dispatches: Long,
        ackThrough: Long,
    ): Boolean =
        states.all { (service, state) ->
            if (!state.path("initialized").asBoolean()) {
                false
            } else if (service.startsWith("funds")) {
                val funds = state.path("funds")
                number(funds, "available") == 1_000L - totalQuantity * 100 && number(funds, "hold") == totalQuantity * 100 &&
                    number(funds, "dispatchEntries") == dispatches && number(funds, "activeOrders") == issued &&
                    number(funds, "pendingOrders") == issued && number(funds, "matchingAppliedThrough") == ackThrough &&
                    number(funds, "issued") == issued && number(funds, "executedThrough") == 0L && number(funds, "resultThrough") == 0L
            } else {
                val matching = state.path("matching")
                matching.path("bookOrders").isArray && matching.path("bookOrders").size().toLong() == issued &&
                    matching.path("bookOrders").toList().sumOf { number(it, "remainingQuantity") } == totalQuantity &&
                    number(matching, "engineSequence") == issued && number(matching, "appliedThrough") == issued &&
                    number(matching, "resultIssued") == issued && number(matching, "resultEntries") == issued &&
                    number(matching, "fundsSettledThrough") == 0L
            }
        }

    internal fun awaitStates(
        label: String,
        timeoutMs: Long,
        condition: (Map<String, JsonNode>) -> Boolean,
    ): Map<String, JsonNode> {
        val deadline = minOf(workDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs))
        var latest = emptyMap<String, JsonNode>()
        while (System.nanoTime() < deadline) {
            checkReportBudget()
            val observed = runCatching { readStates() }.getOrNull()
            if (observed != null) {
                latest = observed
                if (condition(observed)) {
                    checkpoints += mapOf("case" to label, "elapsedMs" to elapsedMs(), "states" to observed)
                    return observed
                }
            }
            Thread.sleep(100)
        }
        checkpoints += mapOf("case" to label, "elapsedMs" to elapsedMs(), "incomplete" to true, "states" to latest)
        error("$label: 전체 6사본 기대값 대기 상한 초과")
    }

    internal fun readStates(): Map<String, JsonNode> =
        SERVICES.associateWith { service ->
            val file = control.resolve("state-$service.json")
            check(Files.isRegularFile(file) && Files.size(file) in 1..MAX_OBSERVATION_BYTES)
            mapper.readTree(Files.readString(file)).also {
                check(it.path("role").asString() == if (service.startsWith("funds")) "FUNDS" else "MATCHING")
                check(number(it, "memberId") == service.last().digitToInt().toLong())
            }
        }

    /** onStart의 상태 게시가 노드의 전체 런타임 기동보다 먼저 끝날 수 있다. */
    private fun runtimeReadyFor(
        service: String,
        state: JsonNode,
    ): Boolean =
        runCatching {
            val path = control.resolve("runtime-$service.json")
            if (!Files.isRegularFile(path) || Files.size(path) !in 1..MAX_OBSERVATION_BYTES) return@runCatching false
            val runtime = mapper.readTree(Files.readString(path))
            runtime.path("bootId").asString() == state.path("bootId").asString() &&
                number(runtime, "pid") == number(state, "pid") &&
                runtime.path("role").asString() == state.path("role").asString() &&
                number(runtime, "memberId") == number(state, "memberId")
        }.getOrDefault(false)

    private fun inspectIsolation(label: String) {
        inspections.clear()
        for ((service, id) in containers) inspections[service] = inspect(id)
        check(containers.values.toSet().size == 6) { "6개 독립 컨테이너가 아닙니다" }
        check(inspections.values.all { it.path("Image").asString() == frozenImageId }) { "동결 이미지와 노드 실행물이 다릅니다" }
        val networks =
            inspections.values.map {
                it
                    .path("NetworkSettings")
                    .path("Networks")
                    .propertyNames()
                    .toSet()
            }
        check(networks.all { it.size == 1 } && networks.distinct().size == 1) { "전용 bridge 하나를 공유해야 합니다" }
        val network = networks.first().single()
        val networkData =
            mapper
                .readTree(
                    must(command(listOf("docker", "network", "inspect", network), 10_000), "전용 network 검사").stdout,
                ).path(0)
        check(networkData.path("Internal").asBoolean() && networkData.path("Driver").asString() == "bridge")
        val volumes =
            inspections.mapValues { (_, node) ->
                node
                    .path("Mounts")
                    .single { it.path("Destination").asString() == "/records" }
                    .also {
                        check(it.path("Type").asString() == "volume")
                    }.path("Name")
                    .asString()
            }
        check(volumes.values.toSet().size == 6) { "역할·사본별 기록 볼륨을 공유했습니다" }
        if (originalVolumes.isEmpty()) originalVolumes = volumes else check(volumes == originalVolumes) { "재시작에서 기록 볼륨이 바뀌었습니다" }
        val runtimes = linkedMapOf<String, JsonNode>()
        for ((service, node) in inspections) {
            val host = node.path("HostConfig")
            check(
                number(host, "Memory") == 768L * MIB && number(host, "ShmSize") == 256L * MIB && number(host, "NanoCpus") == 1_000_000_000L,
            )
            check(host.path("IpcMode").asString() !in setOf("host", "container") && host.path("PidMode").asString() != "host")
            check(host.path("PortBindings").isNull || host.path("PortBindings").size() == 0) { "공개 host 포트가 있습니다" }
            check(!node.path("State").path("OOMKilled").asBoolean())
            val runtimePath = control.resolve("runtime-$service.json")
            check(Files.size(runtimePath) in 1..MAX_OBSERVATION_BYTES)
            val runtime = mapper.readTree(Files.readString(runtimePath))
            check(runtime.path("storageLevels").toList().map { it.asInt() } == listOf(2, 2, 2)) { "$service 실제 sync 2/2/2가 아닙니다" }
            runtimes[service] = runtime
        }
        check(
            runtimes.values
                .map { it.path("runRoot").asString() }
                .toSet()
                .size == 6,
        )
        check(
            runtimes.values
                .map { it.path("driverDirectory").asString() }
                .toSet()
                .size == 6,
        )
        for ((service, runtime) in runtimes) {
            check(runtime.path("runRoot").asString() == "/records/$runId-$service")
            check(runtime.path("jdkVersion").asString().startsWith("25"))
        }
        for (field in listOf("artifactSha256", "schemaSha256", "topologySha256")) {
            val actual = runtimes.values.map { it.path(field).asString() }.toSet()
            check(actual.size == 1 && actual.single().matches(Regex("[0-9a-f]{64}"))) { "6노드 고정 실행 식별이 다릅니다: $field" }
        }
        checkpoints +=
            mapOf(
                "case" to "$label actual isolation and execution",
                "containers" to inspections.toMap(),
                "runtime" to runtimes,
                "network" to networkData,
            )
    }

    private fun checkStorageBudget() {
        var total = 0L
        for ((service, id) in containers) {
            val size =
                must(
                    command(listOf("docker", "exec", id, "du", "-sk", "/records"), 10_000),
                    "$service 기록 용량",
                ).stdout.trim().split(Regex("\\s+"))[0].toLong()
            total = Math.addExact(total, Math.multiplyExact(size, 1024L))
        }
        recordsBytes = maxOf(recordsBytes, total)
        check(total <= 2L * GIB) { "run 기록 2GiB 상한 초과: $total" }
        checkReportBudget()
    }

    internal fun verifyObservationReadableByDifferentUser() {
        val id = containers.getValue("funds0")
        val reader = "65534:65534"
        val prefix = listOf("docker", "exec", "--user", reader, id)
        val uid = must(command(prefix + listOf("id", "-u"), 10_000), "관찰 파일을 읽을 다른 Linux UID 확인").stdout.trim()
        check(uid == "65534")
        awaitFile(control.resolve("received-matching-ack.json"), 30_000)
        val names = listOf("runtime-funds1.json", "state-funds1.json", "received-matching-ack.json")
        val metadata = linkedMapOf<String, String>()
        for (name in names) {
            val path = "/control/$name"
            val attributes =
                must(command(listOf("docker", "exec", id, "stat", "-c", "%a:%u:%g", path), 10_000), "$name 실제 Linux 권한").stdout.trim()
            val fields = attributes.split(':')
            check(fields.size == 3 && fields[0] == "644" && fields[1] != uid) { "$name 공유 읽기 권한이 없습니다: $attributes" }
            val json = must(command(prefix + listOf("cat", path), 10_000), "$name 다른 UID의 실제 JSON 읽기").stdout
            check(mapper.readTree(json).isObject) { "$name 완성된 JSON이 아닙니다" }
            metadata[name] = attributes
        }
        checkpoints +=
            mapOf(
                "case" to "CI174 different Linux UID reads runtime state and matching ACK observations",
                "readerUid" to uid,
                "fileModeOwnerGroup" to metadata,
                "readableFiles" to names,
            )
    }

    private fun recordFragmentedDispatchEvidence() {
        val bytes = Files.readAllBytes(control.resolve("dispatch-1.bin"))
        val command = SplitOrderDeliveryCodec().decode(bytes).command as SplitOrderDeliveryCommand.Apply
        check(command.input.dispatchSequence == 1L && command.input.order.quantity.value == 6L)
        val userBytes =
            command.input.order.userId.value
                .toByteArray(Charsets.UTF_8)
                .size
        val marketBytes =
            command.input.order.marketId.value
                .toByteArray(Charsets.UTF_8)
                .size
        check(userBytes == 256 && marketBytes == 256)
        val mtu = number(mapper.readTree(Files.readString(control.resolve("runtime-funds0.json"))), "mtuLength")
        val sessionHeaderBytes = 32L
        val dataHeaderBytes = 32L
        check(bytes.size <= SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES)
        check(bytes.size + sessionHeaderBytes > mtu) { "실제 적용된 입력1의 전송 프레임이 MTU를 넘지 않습니다" }
        val fragmentPayloadBytes = mtu - dataHeaderBytes
        check(fragmentPayloadBytes > 0)
        checkpoints +=
            mapOf(
                "case" to "G15 actual matching application of a frame exceeding MTU",
                "sourceDispatchFrame" to "control/dispatch-1.bin",
                "applicationFrameBytes" to bytes.size,
                "aeronSessionHeaderBytes" to sessionHeaderBytes,
                "aeronDataHeaderBytes" to dataHeaderBytes,
                "mtuLength" to mtu,
                "fragmentPayloadBytes" to fragmentPayloadBytes,
                "expectedFragmentsAtConfiguredMtu" to (bytes.size + sessionHeaderBytes + fragmentPayloadBytes - 1) / fragmentPayloadBytes,
                "userUtf8Bytes" to userBytes,
                "marketUtf8Bytes" to marketBytes,
                "canonicalDispatchBytes" to command.input.dispatchCanonicalBytes,
                "matchingAppliedCopies" to readStates().filterKeys { it.startsWith("matching") },
            )
    }

    private fun checkReportBudget() {
        var size = 0L
        Files.walkFileTree(
            reports,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (attributes.isRegularFile) size = Math.addExact(size, attributes.size())
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    error: IOException,
                ): FileVisitResult {
                    // 관찰 writer가 이름을 바꾸며 없앤 임시 파일만 허용한다. 권한 오류 등은 그대로 실패시킨다.
                    if (error !is NoSuchFileException) throw error
                    return FileVisitResult.CONTINUE
                }
            },
        )
        reportBytes = maxOf(reportBytes, size)
        check(size <= 16L * MIB) { "진단물 16MiB 상한 초과: $size" }
    }

    internal fun awaitFile(
        path: Path,
        timeoutMs: Long,
    ) {
        val deadline = minOf(workDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs))
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path) && Files.size(path) in 1..MAX_OBSERVATION_BYTES) return
            Thread.sleep(100)
        }
        error("test wrapper의 matching ACK 수신 기록이 없습니다: $path")
    }

    private fun validateMatchingAck(
        through: Long,
        states: Map<String, JsonNode>,
        allowedCodes: Set<String> = setOf("APPLIED", "DUPLICATE", "PRUNED"),
    ) {
        val received = mapper.readTree(Files.readString(control.resolve("received-matching-ack.json")))
        check(received.path("code").asString() in allowedCodes && number(received, "through") == through)
        check(
            received.path("bootId").asString() in states.filterKeys { it.startsWith("funds") }.values.map { it.path("bootId").asString() },
        ) {
            "과거 source worker 세대의 ACK를 현재 수신으로 읽었습니다"
        }
        check(received.path("topologySha256").asString().matches(Regex("[0-9a-f]{64}")))
    }

    internal fun tool(vararg arguments: String): VerificationCommandResult {
        val name = "$runId-client-${clientSequence++}"
        return must(
            compose(
                listOf(
                    "run",
                    "--rm",
                    "--no-deps",
                    "--name",
                    name,
                    "--env",
                    "FUNDING_HOST=$name",
                    "client",
                    if (resultScenario ==
                        null
                    ) {
                        TOOL
                    } else {
                        RESULT_TOOL
                    },
                ) +
                    arguments,
                60_000,
            ),
            "실제 Aeron client ${arguments.joinToString(" ")}",
        )
    }

    private fun collectLogs(prefix: String) {
        for ((service, id) in containers) {
            val logs = command(listOf("docker", "logs", "--tail", "100", id), 5_000)
            Files.writeString(reports.resolve("$prefix-$service.log"), (logs.stdout + logs.stderr).takeLast(128 * 1024))
        }
    }

    private fun inspect(id: String): JsonNode {
        val format =
            """[{"Id":{{json .Id}},"Image":{{json .Image}},"State":{{json .State}},"HostConfig":{"Memory":{{json .HostConfig.Memory}},"NanoCpus":{{json .HostConfig.NanoCpus}},"ShmSize":{{json .HostConfig.ShmSize}},"PidMode":{{json .HostConfig.PidMode}},"IpcMode":{{json .HostConfig.IpcMode}},"PortBindings":{{json .HostConfig.PortBindings}}},"Mounts":{{json .Mounts}},"NetworkSettings":{{json .NetworkSettings}}}]"""
        return mapper.readTree(must(command(listOf("docker", "inspect", "--format", format, id), 5_000), "$id 실제 자원 검사").stdout).path(0)
    }

    private fun compose(
        arguments: List<String>,
        timeoutMs: Long,
    ): VerificationCommandResult =
        command(
            listOf("docker", "compose", "--project-name", runId, "--file", composeFile.toString()) +
                (
                    if (resultScenario ==
                        null
                    ) {
                        emptyList()
                    } else {
                        listOf("--file", project.resolve("src/test/docker/aeron-result-settlement/compose.yml").toString())
                    }
                ) +
                arguments,
            timeoutMs,
        )

    private fun command(
        arguments: List<String>,
        timeoutMs: Long,
    ): VerificationCommandResult {
        val available = TimeUnit.NANOSECONDS.toMillis((if (cleanupMode) overallDeadline else workDeadline) - System.nanoTime())
        check(available > 0) { "전체 8분 실행 상한 초과" }
        val running = commands.start(arguments)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(minOf(timeoutMs, available))
        while (running.isAlive && System.nanoTime() < deadline) {
            try {
                checkReportBudget()
            } catch (failure: Throwable) {
                running.stop()
                for (path in listOf(running.stdoutPath, running.stderrPath)) {
                    if (Files.exists(path) && Files.size(path) > MIB) {
                        Files.newByteChannel(path, java.nio.file.StandardOpenOption.WRITE).use { it.truncate(MIB) }
                    }
                }
                throw failure
            }
            Thread.sleep(50)
        }
        return running.await(1)
    }

    private fun must(
        result: VerificationCommandResult,
        label: String,
    ): VerificationCommandResult = result.also { check(it.succeeded) { "$label: ${it.failure()}" } }

    private fun close() {
        cleanupMode = true
        failures += commands.closeRunning().map { it.message }
        if (composeAttempted) {
            runCatching { collectLogs("final") }.onFailure { failures += "진단 수집: ${it.message}" }
            runCatching {
                must(compose(listOf("down", "--volumes", "--remove-orphans", "--timeout", "10"), 25_000), "run 소유 컨테이너·볼륨·network 정리")
                val remaining =
                    must(
                        command(listOf("docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=$runId"), 5_000),
                        "컨테이너 정리 확인",
                    ).stdout.trim()
                check(remaining.isEmpty())
                val volumes =
                    must(
                        command(listOf("docker", "volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$runId"), 5_000),
                        "기록 볼륨 정리 확인",
                    ).stdout.trim()
                check(volumes.isEmpty())
                val networks =
                    must(
                        command(listOf("docker", "network", "ls", "-q", "--filter", "label=com.docker.compose.project=$runId"), 5_000),
                        "network 정리 확인",
                    ).stdout.trim()
                check(networks.isEmpty())
                cleaned = true
            }.onFailure { failures += "자원 정리: ${it.message}" }
        } else {
            cleaned = true
        }
        if (imageBuilt) {
            runCatching { must(command(listOf("docker", "image", "rm", image), 10_000), "run 전용 임시 이미지 정리") }.onFailure {
                failures +=
                    "이미지 정리: ${it.message}"
            }
        }
    }

    private fun writeReport() {
        val fingerprints =
            listOf(
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingNode.kt",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitTradingClusterAdapter.kt",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsOrderDeliveryAgent.kt",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitOrderDeliveryClient.kt",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitOrderDeliveryCodec.kt",
                "src/main/proto/split_order_delivery.proto",
            ).plus(
                if (resultScenario == null) {
                    emptyList()
                } else {
                    listOf(
                        "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgent.kt",
                        "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitResultDeliveryClient.kt",
                        "src/main/kotlin/com/exchange/core/api/trading/infrastructure/codec/SplitResultDeliveryCodec.kt",
                        "src/main/proto/split_result_delivery.proto",
                        "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultVerificationTool.kt",
                        "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMatchingResultDeliveryTest.kt",
                    )
                },
            ).associateWith { path -> runCatching { sha256(project.resolve(path)) }.getOrNull() }
        val report =
            linkedMapOf(
                "runId" to runId,
                "createdAt" to Instant.now().toString(),
                "success" to (failures.isEmpty() && cleaned),
                "baseCommit" to
                    (
                        if (resultScenario ==
                            null
                        ) {
                            "8d2572bfc98748f44569f5fecbaa8a7c47e87285"
                        } else {
                            "33e464fef96327bc19100c8a098aae9b7eafd0ff"
                        }
                    ),
                "fixture" to resultFixture,
                "headCommit" to headCommit,
                "productSourceSha256" to fingerprints,
                "image" to image,
                "frozenImageId" to frozenImageId,
                "manifestSha256" to frozenManifestSha256,
                "hostFreeDiskBytesBeforeStart" to hostFreeDiskBytes,
                "dockerMemoryBytes" to dockerMemoryBytes,
                "recordBytesHighWater" to recordsBytes,
                "diagnosticBytesHighWater" to reportBytes,
                "limits" to
                    mapOf(
                        "totalMs" to 480_000,
                        "startupMs" to 90_000,
                        "heapBytes" to 256L * MIB,
                        "containerBytes" to 768L * MIB,
                        "recordBytes" to 2L * GIB,
                        "diagnosticBytes" to 16L * MIB,
                    ),
                "elapsedMs" to elapsedMs(),
                "recordVolumesDeleted" to cleaned,
                "runResourcesRemoved" to cleaned,
                "checkpoints" to checkpoints,
                "issues" to failures,
                "commands" to commands.records,
                "unverified" to
                    (
                        (if (resultScenario == null) listOf("#169 결과 역전달·정산") else emptyList()) +
                            listOf("장애 과반·복합 복원·장기 부하", "악의적 peer 인증·위조 방어")
                    ),
            )
        val encoded = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        check(encoded.toByteArray(Charsets.UTF_8).size <= 4L * MIB) { "결과 보고서 단일 파일 4MiB 상한 초과" }
        if (resultScenario ==
            null
        ) {
            Files.writeString(reports.resolve("result.json"), encoded)
        } else {
            writeSplitTestJson(reports.resolve("result.json"), report)
        }
        checkReportBudget()
    }

    internal fun number(
        node: JsonNode,
        field: String,
    ): Long {
        check(node.has(field) && node.path(field).isIntegralNumber) { "수치 관측 필드 누락: $field" }
        return node.path(field).asLong()
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
            "%02x".format(it)
        }

    private fun elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun remaining(deadline: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).also {
            check(it > 0) { "기동 90초 상한 초과" }
        }

    companion object {
        private val SERVICES = listOf("funds0", "funds1", "funds2", "matching0", "matching1", "matching2")
        private const val RESULT_TOOL = "com.exchange.core.api.trading.infrastructure.aeron.MatchingResultVerificationToolKt"
        private const val TOOL = "com.exchange.core.api.trading.infrastructure.aeron.SplitTradingVerificationToolKt"
        private const val MIB = 1_024L * 1_024
        private const val GIB = 1_024L * MIB
        private const val MAX_OBSERVATION_BYTES = 256L * 1_024
    }
}
