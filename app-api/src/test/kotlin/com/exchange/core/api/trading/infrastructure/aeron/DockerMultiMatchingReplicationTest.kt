package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 자원 생성 전에 적용하는 B의 고정 예산이다. 작은 프로필로 자동 우회하지 않는다. */
internal object MultiMatchingRunBudget {
    fun requireCapacity(
        dockerMemoryBytes: Long,
        hostFreeDiskBytes: Long,
    ) {
        check(dockerMemoryBytes >= 9L * 1024 * 1024 * 1024) { "Docker 실제 메모리9GiB 필요: $dockerMemoryBytes" }
        check(hostFreeDiskBytes >= 20L * 1024 * 1024 * 1024) { "호스트 보고서 여유20GiB 필요: $hostFreeDiskBytes" }
    }

    /** atomic 게시로 사라진 임시 파일만 이미 회수된 것으로 처리하고, 관찰 당시의 크기는 모두 센다. */
    fun diagnosticBytes(path: Path): Long {
        val root = path.toAbsolutePath().normalize()
        var total = 0L
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (attributes.isRegularFile) total += attributes.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    failure: IOException,
                ): FileVisitResult {
                    val name = file.fileName?.toString().orEmpty()
                    if (failure is NoSuchFileException && file != root && file.startsWith(root) &&
                        name.startsWith(".") && name.endsWith(".tmp")
                    ) {
                        return FileVisitResult.CONTINUE
                    }
                    throw failure
                }
            },
        )
        return total
    }
}

/** 두 정책은 각기 새 RDB 입력·9개 JVM·기록에서 검증한다. 부하/장애 승계 시험이 아니다. */
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DockerMultiMatchingReplicationTest {
    @Test
    @Order(1)
    fun `B01 실제 두 담당의 동시 주문은 같은 자금을 한 번만 예약한다`() =
        MultiMatchingRun("race").verify { run ->
            val response = run.tool("race")
            val ready = response.path("readiness")
            check(ready.properties().map { it.key }.toSet() == setOf("btc", "sui"))
            check(ready.path("btc").path("threadId").asLong() != ready.path("sui").path("threadId").asLong())
            check(listOf("btc", "sui").all { ready.path(it).path("readyNs").asLong() in 1..response.path("goNs").asLong() })
            val originals = linkedMapOf("race-btc" to response.path("btc"), "race-sui" to response.path("sui"))
            val before = run.await(MultiMatchingStage.RACE, originals)
            for ((market, quantity) in listOf("btc" to "6", "sui" to "5")) {
                assertEquals(originals.getValue("race-$market"), run.tool("admit", "buyer", market, "1", quantity, "BUY"))
            }
            check(run.tool("admit", "buyer", "btc", "1", "7", "BUY").path("code").asString() == "REQUEST_CONTENT_CONFLICT")
            run.sameBusiness(before, run.await(MultiMatchingStage.RACE, originals))
        }

    @Test
    @Order(2)
    fun `B02 실제 두 담당의 체결 수수료와 잔량 취소는 공통 자금에서 수렴한다`() =
        MultiMatchingRun("settlement").verify { run ->
            val originals = linkedMapOf<String, JsonNode>()
            originals["buy-btc"] = run.tool("admit", "buyer", "btc", "1", "2", "BUY")
            originals["buy-sui"] = run.tool("admit", "buyer", "sui", "1", "3", "BUY")
            run.await(MultiMatchingStage.RESERVED, originals)
            originals["sell-btc"] = run.tool("admit", "seller", "btc", "1", "1", "SELL")
            originals["sell-sui"] = run.tool("admit", "seller", "sui", "1", "1", "SELL")
            run.await(MultiMatchingStage.TRADED, originals)
            originals["cancel-btc"] =
                run.tool(
                    "cancel",
                    "btc",
                    "2",
                    originals
                        .getValue("buy-btc")
                        .path("receipt")
                        .path("orderId")
                        .asString(),
                )
            run.await(MultiMatchingStage.BTC_CANCELLED, originals)
            originals["cancel-sui"] =
                run.tool(
                    "cancel",
                    "sui",
                    "2",
                    originals
                        .getValue("buy-sui")
                        .path("receipt")
                        .path("orderId")
                        .asString(),
                )
            val before = run.await(MultiMatchingStage.COMPLETE, originals)
            for ((actor, market, quantity) in listOf(
                Triple("buyer", "btc", "2"),
                Triple("buyer", "sui", "3"),
                Triple("seller", "btc", "1"),
                Triple("seller", "sui", "1"),
            )) {
                val name = if (actor == "buyer") "buy" else "sell"
                assertEquals(
                    originals.getValue("$name-$market"),
                    run.tool(
                        "admit",
                        actor,
                        market,
                        "1",
                        quantity,
                        if (actor ==
                            "buyer"
                        ) {
                            "BUY"
                        } else {
                            "SELL"
                        },
                    ),
                )
            }
            for (market in listOf("btc", "sui")) {
                assertEquals(
                    originals.getValue("cancel-$market"),
                    run.tool(
                        "cancel",
                        market,
                        "2",
                        originals
                            .getValue("buy-$market")
                            .path("receipt")
                            .path("orderId")
                            .asString(),
                    ),
                )
                for (sequence in 1..3) {
                    check(run.tool("replay-result", market, sequence.toString()).path("code").asString() in setOf("DUPLICATE", "PRUNED"))
                }
                check(run.tool("replay-ack", market).path("code").asString() == "ACKNOWLEDGED")
            }
            run.sameBusiness(before, run.await(MultiMatchingStage.COMPLETE, originals))
        }
}

/** B에 필요한 실행 수명만 소유한다. 기존 6노드 실행기와 상태/기한을 공유하지 않는다. */
internal class MultiMatchingRun(
    private val scenario: String,
    private val leaderFailover: Boolean = false,
    private val failoverOwner: String = "btc",
    internal val quorumCase: String? = null,
) {
    private val mapper = jacksonObjectMapper()
    private val project = Path.of(checkNotNull(System.getProperty("fundingProjectDirectory")))
    private val distribution = Path.of(checkNotNull(System.getProperty("fundingDistributionDirectory")))
    private val runId = "multi${System.currentTimeMillis()}${UUID.randomUUID().toString().take(8)}"
    private val reports = project.resolve("build/reports/aeron-funding/multi-matching/$runId")
    private val control = reports.resolve("control")
    private val image = "exchange-multi-matching:$runId"
    private val scratch = "$runId-space"
    private val environment =
        mutableMapOf(
            "SPLIT_NETWORK_PREFIX" to "172.29.${Math.floorMod(runId.hashCode(), 250) + 1}",
            "SPLIT_IMAGE" to image,
            "SPLIT_RUN_ID" to runId,
            "SPLIT_CONTROL_DIRECTORY" to control.toString(),
            "SPLIT_MULTI_CASE" to scenario,
        )
    private val commands = VerificationCommands(project, reports, environment)
    private val started = System.nanoTime()
    private val deadline = started + TimeUnit.SECONDS.toNanos(480)
    private val workDeadline = deadline - TimeUnit.SECONDS.toNanos(90)
    private val issues = mutableListOf<String>()
    private val checkpoints = mutableListOf<Map<String, Any?>>()
    private val runtimes = linkedMapOf<String, JsonNode>()
    private val containers = linkedMapOf<String, String>()
    private val memoryObservations = linkedMapOf<String, Map<String, Long?>>()
    private var phase = "preflight"
    private var imageId = ""
    private var manifestSha = ""
    private var composeAttempted = false
    private var imageAttempted = false
    private var headCommit = ""
    private var resourcesRemoved = false
    private var recordsPeak = 0L
    private var diagnosticPeak = 0L
    private var provenance = emptyMap<String, Any?>()
    private var dockerMemory = 0L
    private var freeBefore = 0L
    private var latestStates = emptyMap<String, JsonNode>()
    private var sourceHashes = emptyMap<String, String>()
    private var temporaryDataPeak = 0L
    private val stoppedServices = linkedSetOf<String>()
    private val stoppedService: String? get() = stoppedServices.singleOrNull()
    private val recoveredServices = linkedSetOf<String>()
    private var activeQuorumClient: Pair<JsonNode, Long>? = null
    private var quorumNetworkControl: FundsQuorumNetworkControl? = null
    private val quorumEvidence = linkedMapOf<String, Any?>()
    private var quorumCleaning = false
    private var quorumNetworkDeadline = Long.MAX_VALUE
    private var activeQuorumScope: String? = null
    private var activeQuorumNonce: String? = null
    private var recoveryOverride: Path? = null
    private var probeSequence = 0L
    private val lifecycle = linkedMapOf<String, Any?>()
    private val timeline = linkedMapOf<String, Long>()
    private val eventOrder = linkedMapOf<String, Int>()
    private val gates = mutableListOf<Map<String, Any?>>()
    private var gateInstallation = ""
    private var electedLeader = ""

    fun verify(scenarioBody: (MultiMatchingRun) -> Unit) {
        Files.createDirectories(control)
        try {
            preflight()
            phase = "동결 이미지"
            imageAttempted = true
            command(
                listOf(
                    "docker",
                    "build",
                    "-f",
                    project
                        .resolve(
                            if (quorumCase ==
                                null
                            ) {
                                "src/test/docker/aeron-order-delivery/Dockerfile"
                            } else {
                                "src/test/docker/aeron-multi-matching/quorum.Dockerfile"
                            },
                        ).toString(),
                    "-t",
                    image,
                    distribution.toString(),
                ),
                180_000,
            )
            imageId = command(listOf("docker", "image", "inspect", "--format", "{{.Id}}", image), 10_000).stdout.trim()
            verifyDockerSpace()
            phase = "RDB 출처와 DB 종료"
            prepareManifest()
            freezeManifestInImage()
            phase = "9노드 기동"
            composeAttempted = true
            compose(listOf("up", "-d", "--no-deps") + SERVICES, 90_000)
            freezeRuntimes()
            tool("prepare")
            if (quorumCase == null) await(MultiMatchingStage.INITIALIZED) else awaitQuorum(FundsQuorumStage.INITIALIZED, emptyMap())
            scenarioBody(this)
            verifyLiveNodes(collectMemory = true)
        } catch (failure: Throwable) {
            issues += "$phase: ${failure.javaClass.simpleName}: ${failure.message}"
            Files.writeString(reports.resolve("failure.txt"), failure.stackTraceToString().take(16 * 1024))
        } finally {
            cleanup()
            writeReport()
        }
        assertTrue(issues.isEmpty() && resourcesRemoved, "실제9노드 검증 실패: $issues · 보고서 $reports")
    }

    fun quorumLive(): Set<String> = SERVICES.toSet() - stoppedServices

    fun quorumMark(
        name: String,
        evidence: Any,
    ) {
        check(quorumCase != null)
        phase = name
        quorumEvidence[name] = mapOf("elapsedMs" to elapsedMs(), "evidence" to evidence)
    }

    fun quorumLeader(states: Map<String, JsonNode>): String =
        states.entries.single { it.key.startsWith("funds") && it.value.path("clusterRole").asString() == "LEADER" }.key

    fun quorumRecordInitial(states: Map<String, JsonNode>) {
        lifecycle["initial"] = boundRuntimes(states)
        quorumMark("initial", boundRuntimes(states))
        // 선거 전 실제 log recording의 identity를 확보해야 선거 중 정상 해제와 미관측을 구분할 수 있다.
        val witness = quorumWitness()
        for ((service, row) in witness) {
            check(row.path("node").path("appendState").asString() == "ACTIVE") { "$service 장애 전 log recording 미확인" }
            check(row.path("node").path("role") == states.getValue(service).path("clusterRole")) { "$service 장애 대상 선정 뒤 역할 변경" }
            check(row.path("node").path("leadershipTermId") == states.getValue(service).path("leadershipTermId")) {
                "$service 장애 대상 선정 뒤 임기 변경"
            }
        }
        quorumMark("initial-witness", witness)
    }

    /** 네트워크 작업은 이미 실행 중인 run 소유 namespace에서만 실행한다. */
    fun quorumNetwork(): FundsQuorumNetworkControl {
        check(quorumCase != null)
        return quorumNetworkControl ?: FundsQuorumNetworkControl(
            runId,
            (SERVICES + "client").associateWith { service ->
                val suffix =
                    when {
                        service == "client" -> 40
                        service.startsWith("funds") -> 10 + service.last().digitToInt()
                        service.startsWith("btc") -> 20 + service.last().digitToInt()
                        else -> 30 + service.last().digitToInt()
                    }
                "${environment.getValue("SPLIT_NETWORK_PREFIX")}.$suffix"
            },
            { service, arguments ->
                val id = if (service == "client") "$runId-client" else containers.getValue(service)
                val left =
                    if (quorumNetworkDeadline == Long.MAX_VALUE) {
                        15_000L
                    } else {
                        TimeUnit.NANOSECONDS.toMillis(quorumNetworkDeadline - System.nanoTime())
                    }
                check(left > 0) { "네트워크 규칙 작업 전체15초 기한 초과" }
                command(listOf("docker", "exec", id) + arguments, minOf(15_000, left), quorumCleaning).stdout
            },
        ).also { quorumNetworkControl = it }
    }

    /** 설치/해제와 도달 확인 전체가 같은 15초 예산을 쓴다. 명령마다 기한을 다시 늘리지 않는다. */
    private fun <T> quorumNetworkStep(action: () -> T): T {
        check(quorumNetworkDeadline == Long.MAX_VALUE)
        val startedAt = System.nanoTime()
        quorumNetworkDeadline = startedAt + TimeUnit.SECONDS.toNanos(15)
        try {
            val result = action()
            check(System.nanoTime() < quorumNetworkDeadline) { "네트워크 규칙 작업 전체15초 기한 초과" }
            return result
        } finally {
            quorumEvidence["network-step-${quorumEvidence.size}"] =
                mapOf("elapsedMs" to elapsedMs(), "durationMs" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
            quorumNetworkDeadline = Long.MAX_VALUE
        }
    }

    fun quorumPartition(pairs: Set<FundsPeerPair>) {
        val active = quorumLive() + if (activeQuorumClient == null) emptySet() else setOf("client")
        quorumMark("network-${quorumEvidence.size}", quorumNetworkStep { quorumNetwork().partition(pairs, active) })
    }

    /** controller가 확인한 실제 종료 집합만 생존 사본에서 뺀다. */
    fun quorumKill(services: Set<String>) {
        check(quorumCase != null && activeQuorumClient == null)
        check(services.isNotEmpty() && services.all { it.startsWith("funds") && it in quorumLive() })
        val rows = linkedMapOf<String, Any>()
        for (service in services) {
            val id = containers.getValue(service)
            val before = runtimes.getValue(service).deepCopy()
            command(listOf("docker", "kill", "--signal", "KILL", id), 10_000)
            check(command(listOf("docker", "wait", id), 10_000).stdout.trim() == "137")
            val actual = mapper.readTree(command(listOf("docker", "inspect", id), 10_000).stdout).path(0)
            check(!actual.path("State").path("Running").asBoolean())
            check(actual.path("State").path("ExitCode").asInt() == 137 && !actual.path("State").path("OOMKilled").asBoolean())
            stoppedServices.add(service)
            rows[service] = mapOf("before" to before, "terminated" to actual)
        }
        verifyLiveNodes()
        quorumMark("killed", rows)
    }

    /** 새 로컬 nonce는 금융 callback을 생성하지 않고 현재 프로세스의 공개 관찰만 읽는다. */
    fun quorumWitness(services: Set<String> = quorumLive()): Map<String, JsonNode> {
        check(services.isNotEmpty() && quorumLive().containsAll(services))
        var nonce = UUID.randomUUID().toString()
        for (service in services) writeBoundedHttpJson(control.resolve("witness-request-$service.json"), mapOf("nonce" to nonce))
        val until = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
        while (System.nanoTime() < until) {
            val rows =
                services.associateWith { service ->
                    val file = control.resolve("witness-$service.json")
                    if (Files.isRegularFile(file)) read(file) else mapper.createObjectNode()
                }
            if (rows.all { (service, row) ->
                    row.path("nonce").asString() == nonce && row.path("bootId") == runtimes.getValue(service).path("bootId")
                }
            ) {
                for ((service, row) in rows) {
                    val observationFailure = row.path("observationFailure")
                    check(observationFailure.isMissingNode || observationFailure.isNull) {
                        "$service witness 관찰 실패: $observationFailure"
                    }
                }
                if (rows.values.any {
                        !it.path("node").path("counterReadStable").asBoolean() ||
                            !it.path("serviceObservation").path("ready").asBoolean()
                    }
                ) {
                    nonce = UUID.randomUUID().toString()
                    for (service in services) {
                        writeBoundedHttpJson(
                            control.resolve("witness-request-$service.json"),
                            mapOf("nonce" to nonce),
                        )
                    }
                    continue
                }
                for ((service, row) in rows) {
                    val runtime = runtimes.getValue(service)
                    check(row.path("runId").asString() == runId && row.path("service").asString() == service)
                    check(row.path("memberId") == runtime.path("memberId"))
                    val node = row.path("node")
                    check(node.path("driverActive").asBoolean() && !node.path("driverClosed").asBoolean())
                    check(node.path("driverHeartbeatAgeMs").asLong(-1) in 0..5_000)
                    check(node.path("counterReadStable").asBoolean() && node.path("commitPosition").asLong(-1) >= 0)
                    val nodeFailures = fundsQuorumNodeObservationViolations(node)
                    check(nodeFailures.isEmpty()) { "$service 현재 노드 관찰 오류: $nodeFailures" }
                    val observation = row.path("serviceObservation")
                    check(observation.path("ready").asBoolean()) { "$service 서비스 관찰이 반쪽 상태입니다" }
                    check(observation.path("snapshot").path("bootId") == runtime.path("bootId"))
                }
                return rows
            }
            Thread.sleep(10)
        }
        error("새 nonce의 현재 witness 누락: $services")
    }

    fun awaitQuorum(
        stage: FundsQuorumStage,
        originals: Map<String, JsonNode>,
        expected: Set<String> = quorumLive(),
        local: Boolean = false,
        higherThanTerm: Long? = null,
    ): Map<String, JsonNode> {
        check(quorumCase != null && quorumLive().containsAll(expected))
        phase = "$quorumCase-${stage.name}"
        val until = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
        var failures = emptyList<String>()
        var correlation: Long? = null
        while (System.nanoTime() < until) {
            budgets()
            val states =
                if (local) {
                    quorumWitness(expected).mapValues { it.value.path("serviceObservation").path("snapshot") }
                } else {
                    expected.associateWith { read(control.resolve("state-$it.json")) }
                }
            latestStates = states
            failures = FundsQuorumConvergence.violations(stage, states, originals, expected)
            if (!local) {
                for ((service, state) in states) {
                    if (correlation != null &&
                        state.path("probe").path("correlationId").asLong() != correlation
                    ) {
                        failures += "$service 새 복제 probe 없음"
                    }
                    if (state.path("bootId") != runtimes.getValue(service).path("bootId")) failures += "$service 현재 boot 불일치"
                }
                val funds = states.filterKeys { it.startsWith("funds") }
                val terms = funds.values.map { it.path("leadershipTermId").asLong(-1) }
                val leaders = funds.values.map { it.path("leaderMemberId").asInt(-1) }
                if (funds.size >= 2 && (
                        terms.distinct().size != 1 || terms.any { it < 0 } || leaders.distinct().size != 1 ||
                            funds.values.count { it.path("clusterRole").asString() == "LEADER" } != 1 ||
                            leaders.any { leader -> funds.keys.none { it.last().digitToInt() == leader } } ||
                            (higherThanTerm != null && terms.any { it <= higherThanTerm })
                    )
                ) {
                    failures += "자금 과반의 현재 선출 미확인"
                }
            }
            if (failures.isEmpty()) {
                if (!local && correlation == null) {
                    check(activeQuorumClient == null)
                    correlation = 1_000_000_000_000L + ++probeSequence
                    tool("probe", "all", correlation.toString())
                    continue
                }
                verifyLiveNodes()
                checkpoints += mapOf("phase" to phase, "states" to states, "originals" to originals.toMap(), "elapsedMs" to elapsedMs())
                return states
            }
            Thread.sleep(100)
        }
        error("$stage 과반 검증 수렴 실패: ${failures.take(12)}")
    }

    /** client의 첫 Java 연결 전에 scope를 설치한다. 별도 client/helper는 동시에 띄우지 않는다. */
    fun beginQuorumClient(
        scope: String,
        baseline: Boolean,
        allowed: Set<String>,
    ) {
        check(quorumCase != null && activeQuorumClient == null && scope.matches(Regex("[a-z0-9-]{1,40}")))
        val launch = "/control/quorum-launch-$scope"
        val script =
            "while [ ! -f $launch ]; do sleep 0.01; done; " +
                "exec java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED " +
                "--add-opens=java.base/java.util.zip=ALL-UNNAMED " +
                "-cp '/app/test-classes:/app/classes:/app/lib/*' $TOOL " +
                "quorum-client $scope ${if (baseline) "baseline" else "pending"}"
        val args = listOf("quorum-client", scope, if (baseline) "baseline" else "pending")
        activeQuorumScope = scope
        activeQuorumClient = startClient(args, script)
        quorumMark("scope-$scope", quorumNetworkStep { quorumNetwork().scope("client", allowed, quorumLive() + "client") })
        Files.writeString(control.resolve("quorum-launch-$scope"), "launch")
    }

    fun awaitQuorumClient(phases: Set<String>): JsonNode {
        val scope = checkNotNull(activeQuorumScope)
        val until = minOf(checkNotNull(activeQuorumClient).second, System.nanoTime() + TimeUnit.SECONDS.toNanos(35))
        while (System.nanoTime() < until) {
            val file = control.resolve("quorum-client-$scope.json")
            if (Files.isRegularFile(file)) {
                val row = read(file)
                check(row.path("scope").asString() == scope)
                if (row.path("phase").asString() in phases) return row
            }
            Thread.sleep(10)
        }
        error("$scope client 단계 미관측: $phases")
    }

    fun submitQuorumPending(): JsonNode {
        val scope = checkNotNull(activeQuorumScope)
        val nonce = UUID.randomUUID().toString()
        activeQuorumNonce = nonce
        writeBoundedHttpJson(control.resolve("quorum-start-$scope.json"), mapOf("nonce" to nonce))
        val result = awaitQuorumClient(setOf("COMPLETE"))
        check(result.path("startNonce").asString() == nonce && result.path("attempts").size() == 2)
        check(
            result
                .path("attempts")
                .toList()
                .map { it.path("request").asString() }
                .toSet() == setOf("pb", "ps"),
        )
        quorumMark("attempts-$scope", result)
        return result
    }

    /** client의 실제 publication/session과 서비스가 만든 최초 receipt를 같은 요청에 결속한다. */
    fun quorumDecisions(
        rows: List<JsonNode>,
        allowed: Set<String>,
        higherThanTerm: Long? = null,
    ) {
        for (row in rows) {
            check(row.path("classification").asString() == "REPLIED" && row.path("code").asString() == "ACCEPTED")
            val route = row.path("routeAtReply")
            val member = route.path("leaderMemberId").asInt(-1)
            val service = "funds$member"
            check(service in allowed && route.path("clusterSessionId").asLong(-1) >= 0)
            val term = route.path("leadershipTermId").asLong(-1)
            check(term >= 0 && (higherThanTerm == null || term > higherThanTerm))
            check(route.path("ingressEndpoint").asString() == "${environment.getValue("SPLIT_NETWORK_PREFIX")}.${10 + member}:19700") {
                "실제 ingress publication과 승인한 member의 주소가 다릅니다: $route"
            }
            val label = row.path("request").asString()
            check(label in setOf("b0", "pb", "ps"))
            val file = if (label == "b0") "admission-$service.json" else "admission-$service-$label.json"
            val committed = read(control.resolve(file))
            check(committed.path("bootId") == runtimes.getValue(service).path("bootId") && committed.path("runId").asString() == runId)
            check(committed.path("memberId").asInt() == member && committed.path("leaderMemberId").asInt() == member)
            check(committed.path("leadershipTermId").asLong() == term && committed.path("clusterRole").asString() == "LEADER")
            for (field in listOf("requestSha256", "wireSha256", "correlationId", "receipt")) {
                check(committed.path(field) == row.path(field)) {
                    "$label client·서비스의 $field 결속 불일치"
                }
            }
            check(committed.path("logPosition").asLong() == row.path("appliedLogPosition").asLong())
            quorumMark("decision-$label", mapOf("client" to row, "service" to committed))
        }
    }

    fun finishQuorumClient(): JsonNode {
        val scope = checkNotNull(activeQuorumScope)
        val client = checkNotNull(activeQuorumClient)
        quorumMark("scope-released-$scope", quorumNetworkStep { quorumNetwork().releaseScope(activeServices = quorumLive() + "client") })
        writeBoundedHttpJson(control.resolve("quorum-finish-$scope.json"), mapOf("nonce" to checkNotNull(activeQuorumNonce)))
        val result = finishClient(listOf("quorum-client", scope), client.first, client.second)
        activeQuorumClient = null
        activeQuorumScope = null
        activeQuorumNonce = null
        budgets()
        return result
    }

    /** 과반이 P를 완료한 뒤에도 고립된 구 리더의 늦은 적용이 없었는지 해제 직전에 확인한다. */
    fun quorumMinorityStillProtected(
        service: String,
        originalB0: JsonNode,
    ) {
        check(activeQuorumClient == null && service.startsWith("funds"))
        val selected = setOf(service)
        val rows = quorumWitness(selected)
        val states = rows.mapValues { it.value.path("serviceObservation").path("snapshot") }
        val failures = FundsQuorumConvergence.violations(FundsQuorumStage.PROTECTED, states, mapOf("B0" to originalB0), selected)
        check(failures.isEmpty()) { "과반 진행 뒤 minority 보호 상태 변경: $failures" }
        check(
            rows
                .getValue(service)
                .path("serviceObservation")
                .path("callbacks")
                .size() == 0,
        ) { "$service 고립 중 늦은 P callback 진입" }
        val network = quorumNetwork().inspect(quorumLive())
        verifyLiveNodes()
        quorumMark("minority-after-majority", mapOf("witness" to rows, "network" to network))
    }

    fun observeNoQuorum(
        funds: Set<String>,
        originals: Map<String, JsonNode>,
    ) {
        check(activeQuorumClient == null && funds.isNotEmpty() && funds.all { it.startsWith("funds") })
        check(workDeadline - System.nanoTime() > TimeUnit.SECONDS.toNanos(10)) { "no-quorum 연속 관찰10초의 남은 기한이 없습니다" }
        val selected = funds + SERVICES.filter { !it.startsWith("funds") }
        val samples = mutableListOf<Map<String, JsonNode>>()
        val networkSamples = mutableListOf<FundsQuorumNetworkEvidence>()
        val until = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(10))
        do {
            val rows = quorumWitness(selected)
            val states = rows.mapValues { it.value.path("serviceObservation").path("snapshot") }
            val failures = FundsQuorumConvergence.violations(FundsQuorumStage.PROTECTED, states, originals, selected)
            check(failures.isEmpty()) { "과반 없는 영역의 보호 상태 변경: ${failures.take(10)}" }
            for (service in funds) {
                check(
                    rows
                        .getValue(service)
                        .path("serviceObservation")
                        .path("callbacks")
                        .size() == 0,
                ) {
                    "$service 과반 없이 P callback 진입"
                }
            }
            samples += rows
            // witness만 살아 있고 peer 규칙이 풀린 자료를 무과반의 성공 근거로 쓰지 않는다.
            networkSamples += quorumNetwork().inspect(quorumLive())
            verifyLiveNodes()
            if (System.nanoTime() < until) Thread.sleep(5_000)
        } while (System.nanoTime() < until || samples.size < 3)
        for (service in selected) {
            check(
                samples
                    .last()
                    .getValue(service)
                    .path("serviceObservation")
                    .path("backgroundWorkCount")
                    .asLong() >
                    samples
                        .first()
                        .getValue(service)
                        .path("serviceObservation")
                        .path("backgroundWorkCount")
                        .asLong(),
            ) { "$service 서비스 스레드 진행 없음" }
        }
        quorumMark("no-quorum-${quorumEvidence.size}", mapOf("samples" to samples, "network" to networkSamples))
        budgets()
    }

    private fun preflight() {
        sourceHashes = currentSourceHashes()
        headCommit = command(listOf("git", "rev-parse", "HEAD"), 5_000).stdout.trim()
        freeBefore = Files.getFileStore(project).usableSpace
        dockerMemory = command(listOf("docker", "info", "--format", "{{.MemTotal}}"), 15_000).stdout.trim().toLong()
        MultiMatchingRunBudget.requireCapacity(dockerMemory, freeBefore)
        val others =
            command(
                listOf("docker", "ps", "--format", "{{.Image}}"),
                10_000,
            ).stdout.lineSequence().filter(String::isNotBlank).toList()
        check(others.size <= 1 && others.all { it.startsWith("testcontainers/ryuk:") }) { "다른 실행 컨테이너의 예산을 확인할 수 없습니다: $others" }
        check(Files.isDirectory(distribution.resolve("test-classes"))) { "검증 배포 경로가 없습니다" }
        selectNetworkPrefix()
    }

    private fun selectNetworkPrefix() {
        val ids = command(listOf("docker", "network", "ls", "-q"), 5_000).stdout.split(Regex("\\s+")).filter(String::isNotBlank)
        val ranges =
            if (ids.isEmpty()) {
                emptyList()
            } else {
                mapper.readTree(command(listOf("docker", "network", "inspect") + ids, 10_000).stdout).flatMap { network ->
                    network.path("IPAM").path("Config").mapNotNull { item ->
                        val cidr = item.path("Subnet").asString().split('/')
                        if (cidr.size != 2 || ':' in cidr[0]) {
                            null
                        } else {
                            val address = cidr[0].split('.').fold(0L) { value, part -> (value shl 8) + part.toLong() }
                            val mask = (0xffff_ffffL shl (32 - cidr[1].toInt())) and 0xffff_ffffL
                            (address and mask)..((address and mask) or (mask xor 0xffff_ffffL))
                        }
                    }
                }
            }
        val prefix =
            (24..31)
                .asSequence()
                .flatMap { second -> (1..250).asSequence().map { third -> "172.$second.$third" } }
                .first { candidate ->
                    val address = candidate.split('.').fold(0L) { value, part -> (value shl 8) + part.toLong() } shl 8
                    ranges.none { address <= it.last && address + 255 >= it.first }
                }
        environment["SPLIT_NETWORK_PREFIX"] = prefix
        checkpoints += mapOf("phase" to "network", "stableNodeSubnet" to "$prefix.0/24")
    }

    private fun verifyDockerSpace() {
        command(listOf("docker", "volume", "create", "--label", "com.exchange.multi.run=$runId", scratch), 10_000)
        val result =
            command(
                listOf(
                    "docker",
                    "run",
                    "--rm",
                    "--network",
                    "none",
                    "--memory",
                    "768m",
                    "--cpus",
                    "1",
                    "--shm-size",
                    "256m",
                    "--pids-limit",
                    "128",
                    "--label",
                    "com.exchange.multi.run=$runId",
                    "--mount",
                    "type=volume,src=$scratch,dst=/records",
                    "--entrypoint",
                    "df",
                    image,
                    "-Pk",
                    "/records",
                ),
                15_000,
            )
        val available =
            result.stdout
                .trim()
                .lineSequence()
                .last()
                .trim()
                .split(Regex("\\s+"))[3]
                .toLong() * 1024
        check(available >= 12 * GIB) { "Docker 기록 파일시스템 여유12GiB 필요: $available" }
        command(listOf("docker", "volume", "rm", scratch), 10_000)
        checkpoints += mapOf("phase" to "space", "dockerFreeBytes" to available)
    }

    private fun prepareManifest() {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "$runId-db").apply { isDaemon = true } }
        val future =
            executor.submit<Map<String, Any?>> {
                var dbId = ""
                var result: Map<String, Any?> = emptyMap()
                MarketConfigurationDatabase(verificationRunId = runId).use { database ->
                    dbId = database.containerId
                    val inspection = mapper.readTree(command(listOf("docker", "inspect", dbId), 10_000).stdout).path(0)
                    val host = inspection.path("HostConfig")
                    check(
                        host.path("Memory").asLong() == 512 * MIB && host.path("NanoCpus").asLong() == 500_000_000L &&
                            host.path("PidsLimit").asLong() == 128L,
                    )
                    val fee = if (scenario in setOf("race", "quorum")) 0 else 10_000
                    val document =
                        mapper.writeValueAsString(
                            listOf("BTC", "SUI").map { asset ->
                                mapOf(
                                    "marketId" to "$asset-USDT",
                                    "baseAssetId" to asset,
                                    "quoteAssetId" to "USDT",
                                    "baseQuantityScale" to 0,
                                    "priceTick" to 1,
                                    "quantityStep" to 1,
                                    "feePolicy" to
                                        mapOf(
                                            "productType" to "SPOT",
                                            "feeTier" to "NORMAL",
                                            "scheduleVersion" to 1,
                                            "makerPartsPerMillion" to fee,
                                            "takerPartsPerMillion" to fee,
                                        ),
                                )
                            },
                        )
                    val dbEnvironment = database.register(document)
                    val registry = readDevelopmentMarkets(dbEnvironment)
                    check(registry.all().map { it.market.marketId.value } == listOf("BTC-USDT", "SUI-USDT"))
                    check(registry.contentSha256 == sha(document.toByteArray()))
                    check(
                        registry.all().all {
                            it.feePolicySnapshot.feeRates.makerFeeRate.partsPerMillion == fee.toLong() &&
                                it.feePolicySnapshot.feeRates.takerFeeRate.partsPerMillion == fee.toLong()
                        },
                    )
                    val invalid =
                        runCatching {
                            readDevelopmentMarkets(
                                dbEnvironment + ("FUNDING_MARKET_CONFIG_SHA256" to "0".repeat(64)),
                            )
                        }.exceptionOrNull()
                    check(invalid is IllegalArgumentException && invalid.message.orEmpty().contains("FUNDING_MARKET_CONFIG_SHA256")) {
                        "잘못된 RDB 원문 지문이 거절되지 않았습니다"
                    }
                    val manifest =
                        TradingClusterManifest(
                            ClusterLineage(135, 1),
                            "split-multi-native-v1",
                            BoundedExecutionIdentity.artifactSha256,
                            BoundedExecutionIdentity.schemaSha256,
                            checkNotNull(registry.configurationVersion),
                            registry.all().mapIndexed {
                                ordinal,
                                context,
                                ->
                                TradingMarketContext(context.market, context.feePolicySnapshot, ordinal)
                            },
                            TradingResourceLimits(),
                        )
                    val temporary =
                        command(listOf("docker", "exec", dbId, "du", "-sk", "/var/lib/postgresql/data", "/tmp"), 5_000)
                            .stdout
                            .lineSequence()
                            .filter(String::isNotBlank)
                            .sumOf { it.trim().split(Regex("\\s+"))[0].toLong() * 1024 }
                    temporaryDataPeak = maxOf(temporaryDataPeak, temporary)
                    check(temporary <= 512 * MIB) { "DB 임시 데이터512MiB 초과: $temporary" }
                    val bytes = BoundedTradingStateCodec().encodeManifest(manifest)
                    Files.write(control.resolve("manifest.bin"), bytes)
                    manifestSha = sha(bytes)
                    result =
                        mapOf(
                            "configurationVersion" to registry.configurationVersion,
                            "rawSha256" to registry.contentSha256,
                            "manifestSha256" to manifestSha,
                            "markets" to registry.all().map { it.market.marketId.value },
                            "feePartsPerMillion" to fee,
                            "databaseContainerId" to dbId,
                            "databaseImageId" to inspection.path("Image").asString(),
                            "invalidHashRejected" to true,
                        )
                }
                val remaining =
                    command(
                        listOf("docker", "ps", "-aq", "--filter", "label=com.exchange.multi.run=$runId"),
                        5_000,
                    ).stdout.trim()
                check(remaining.isEmpty()) { "9노드 전 DB가 정리되지 않았습니다: $dbId" }
                result + ("databaseStoppedBeforeNodes" to true)
            }
        try {
            provenance = future.get(remainingMs(60_000), TimeUnit.MILLISECONDS)
        } finally {
            future.cancel(true)
            executor.shutdownNow()
            check(executor.awaitTermination(2, TimeUnit.SECONDS)) { "DB 준비 스레드가 종료되지 않았습니다" }
        }
    }

    private fun freezeManifestInImage() {
        val codec = BoundedTradingStateCodec()
        val originalBytes = Files.readAllBytes(control.resolve("manifest.bin"))
        val before = codec.decodeManifest(originalBytes)
        val output =
            command(
                listOf(
                    "docker",
                    "run",
                    "--rm",
                    "--network",
                    "none",
                    "--memory",
                    "768m",
                    "--cpus",
                    "1",
                    "--shm-size",
                    "256m",
                    "--pids-limit",
                    "128",
                    "--label",
                    "com.exchange.multi.run=$runId",
                    "--env",
                    "JAVA_TOOL_OPTIONS=-Xms64m -Xmx256m -XX:+UseG1GC",
                    "--env",
                    "SPLIT_MANIFEST=/control/manifest.bin",
                    "--mount",
                    "type=bind,src=$control,dst=/control",
                    image,
                    TOOL,
                    "freeze-manifest",
                ),
                30_000,
            )
        val identity = mapper.readTree(output.stdout.lineSequence().last { it.trim().startsWith("{") })
        val bytes = Files.readAllBytes(control.resolve("manifest.bin"))
        val after = codec.decodeManifest(bytes)
        check(identity.path("code").asString() == "FROZEN")
        check(identity.path("sourceManifestSha256").asString() == sha(originalBytes))
        check(identity.path("manifestSha256").asString() == sha(bytes))
        check(Files.readAllBytes(control.resolve("manifest-rdb-input.bin")).contentEquals(originalBytes))
        check(before.schemaSha256 == after.schemaSha256)
        check(
            before.lineage == after.lineage && before.algorithmId == after.algorithmId &&
                before.configurationVersion == after.configurationVersion && before.markets == after.markets &&
                before.limits == after.limits,
        )
        check(
            after.executionArtifactSha256 == identity.path("artifactSha256").asString() &&
                after.schemaSha256 == identity.path("schemaSha256").asString(),
        )
        manifestSha = sha(bytes)
        provenance = provenance + mapOf("manifestSha256" to manifestSha, "imageExecutionIdentity" to identity)
    }

    private fun freezeRuntimes() {
        val until = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(90))
        while (true) {
            check(System.nanoTime() < until) { "9개 현재 runtime 관찰이 준비되지 않았습니다" }
            val files = SERVICES.map { control.resolve("runtime-$it.json") }
            if (files.all(Files::isRegularFile)) {
                for (service in SERVICES) {
                    val id = compose(listOf("ps", "-q", service), 10_000).stdout.trim()
                    check(id.isNotEmpty())
                    val node = mapper.readTree(command(listOf("docker", "inspect", id), 10_000).stdout).path(0)
                    val host = node.path("HostConfig")
                    check(node.path("State").path("Running").asBoolean() && !node.path("State").path("OOMKilled").asBoolean())
                    check(node.path("Image").asString() == imageId)
                    check(
                        host.path("Memory").asLong() == 768 * MIB && host.path("ShmSize").asLong() == 256 * MIB &&
                            host.path("NanoCpus").asLong() == 1_000_000_000L &&
                            host.path("PidsLimit").asLong() == 128L,
                    )
                    check(host.path("PortBindings").isNull || host.path("PortBindings").size() == 0)
                    check(host.path("IpcMode").asString() != "host" && host.path("PidMode").asString() != "host")
                    val runtime = read(control.resolve("runtime-$service.json")) as ObjectNode
                    check(runtime.path("storageLevels").toList().map { it.asInt() } == listOf(2, 2, 2))
                    check(runtime.path("manifestSha256").asString() == manifestSha)
                    runtime.put("imageId", imageId)
                    runtime.put("containerId", id)
                    runtime.set("inspection", node)
                    runtimes[service] = runtime
                    containers[service] = id
                }
                val volumes =
                    runtimes.values.map { runtime ->
                        runtime
                            .path("inspection")
                            .path("Mounts")
                            .single {
                                it.path("Destination").asString() ==
                                    "/records"
                            }.path("Name")
                            .asString()
                    }
                check(volumes.distinct().size == 9)
                check(
                    runtimes.values.all {
                        it
                            .path("inspection")
                            .path("NetworkSettings")
                            .path("Networks")
                            .size() == 1
                    },
                )
                val network =
                    runtimes.values
                        .map {
                            it
                                .path("inspection")
                                .path("NetworkSettings")
                                .path("Networks")
                                .properties()
                                .single()
                                .key
                        }.distinct()
                        .single()
                val networkData = mapper.readTree(command(listOf("docker", "network", "inspect", network), 10_000).stdout).path(0)
                check(networkData.path("Internal").asBoolean() && networkData.path("Driver").asString() == "bridge")
                return
            }
            Thread.sleep(100)
        }
    }

    private fun verifyLiveNodes(collectMemory: Boolean = false) {
        check(containers.size == 9)
        val nodes = mapper.readTree(command(listOf("docker", "inspect") + containers.values, 10_000).stdout)
        check(nodes.size() == 9)
        for (node in nodes) {
            val service =
                node
                    .path("Config")
                    .path("Labels")
                    .path("com.docker.compose.service")
                    .asString()
            check(node.path("Id").asString() == containers.getValue(service))
            if (quorumCase != null) {
                val host = node.path("HostConfig")
                val expectedCaps = if (service.startsWith("funds")) setOf("NET_ADMIN") else emptySet()
                check(
                    host
                        .path("CapAdd")
                        .toList()
                        .map { it.asString().removePrefix("CAP_") }
                        .toSet() == expectedCaps,
                )
                check(!host.path("Privileged").asBoolean() && host.path("NetworkMode").asString() != "host")
            }
            val state = node.path("State")
            (runtimes.getValue(service) as ObjectNode).set("inspection", node)
            if (service in stoppedServices) {
                check(
                    !state.path("Running").asBoolean() && !state.path("OOMKilled").asBoolean() &&
                        state.path("ExitCode").asInt() == 137 && state.path("Pid").asLong() == 0L,
                ) {
                    "$service 의도한 SIGKILL 종료 상태가 아닙니다"
                }
                continue
            }
            check(state.path("Running").asBoolean() && !state.path("OOMKilled").asBoolean() && state.path("Pid").asLong() > 0) {
                "$service 현재 컨테이너가 정지/OOM 상태입니다"
            }
            check(read(control.resolve("runtime-$service.json")).path("bootId") == runtimes.getValue(service).path("bootId")) {
                "$service 살아 있는 노드의 boot가 바뀌었습니다"
            }
            check(node.path("RestartCount").asLong() == 0L) { "$service 예기치 않은 재시작입니다" }
            if (collectMemory) {
                val script =
                    listOf("memory.peak", "memory.current", "memory.swap.peak", "memory.swap.current").joinToString("; ") { file ->
                        "if [ -r /sys/fs/cgroup/$file ]; then printf '$file='; cat /sys/fs/cgroup/$file; fi"
                    }
                val output = compose(listOf("exec", "-T", service, "sh", "-c", script), 5_000).stdout
                val values =
                    output
                        .lineSequence()
                        .mapNotNull { line ->
                            val parts = line.split('=', limit = 2)
                            if (parts.size == 2) parts[0] to parts[1].trim().toLongOrNull() else null
                        }.toMap()
                memoryObservations[service] =
                    listOf("memory.peak", "memory.current", "memory.swap.peak", "memory.swap.current")
                        .associateWith { values[it] }
            }
        }
    }

    fun await(
        stage: MultiMatchingStage,
        originals: Map<String, JsonNode> = emptyMap(),
    ): Map<String, JsonNode> {
        phase = stage.name
        val until = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
        var failures = emptyList<String>()
        while (System.nanoTime() < until) {
            budgets()
            val states = SERVICES.associateWith { read(control.resolve("state-$it.json")) }
            latestStates = states
            failures = MultiMatchingConvergence.failures(states, runtimes, stage, originals)
            if (failures.isEmpty()) {
                verifyLiveNodes()
                checkpoints +=
                    mapOf("phase" to stage.name, "elapsedMs" to elapsedMs(), "states" to states, "originals" to originals.toMap())
                return states
            }
            Thread.sleep(100)
        }
        error("$stage 수렴 실패: ${failures.take(12)}")
    }

    fun tool(vararg args: String): JsonNode {
        val (inspection, deadline) = startClient(args.toList())
        return finishClient(args.toList(), inspection, deadline)
    }

    private fun startClient(
        args: List<String>,
        launchScript: String? = null,
    ): Pair<JsonNode, Long> {
        // 종료 뒤에도 inspect를 남겨 client의 실제 한도를 확인하고, 이 호출이 직접 회수한다.
        val name = "$runId-client"
        val clientDeadline = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(60))

        fun left() = TimeUnit.NANOSECONDS.toMillis(clientDeadline - System.nanoTime()).also { check(it > 0) }
        compose(
            listOf(
                "run",
                "-d",
                "--no-deps",
                "--name",
                name,
                "--env",
                "FUNDING_HOST=${environment.getValue("SPLIT_NETWORK_PREFIX")}.40",
            ) + (if (launchScript == null) listOf("client", TOOL) + args else listOf("--entrypoint", "sh", "client", "-c", launchScript)),
            left(),
        )
        val inspection = mapper.readTree(command(listOf("docker", "inspect", name), minOf(5_000, left())).stdout).path(0)
        val host = inspection.path("HostConfig")
        check(
            host.path("Memory").asLong() == 768 * MIB && host.path("ShmSize").asLong() == 256 * MIB &&
                host.path("NanoCpus").asLong() == 1_000_000_000L && host.path("PidsLimit").asLong() == 128L,
        )
        check(host.path("Tmpfs").path("/tmp").asString() == "size=536870912,mode=1777")
        check(inspection.path("Image").asString() == imageId)
        if (quorumCase != null) {
            check(
                host
                    .path("CapAdd")
                    .toList()
                    .map { it.asString().removePrefix("CAP_") }
                    .toSet() == setOf("NET_ADMIN"),
            )
            check(!host.path("Privileged").asBoolean() && host.path("NetworkMode").asString() != "host")
            val networks = inspection.path("NetworkSettings").path("Networks")
            val nodeNetworks =
                runtimes
                    .getValue("funds0")
                    .path("inspection")
                    .path("NetworkSettings")
                    .path("Networks")
            check(
                networks.isObject && networks.size() == 1 &&
                    networks.properties().map { it.key } == nodeNetworks.properties().map { it.key },
            )
        }
        return inspection to clientDeadline
    }

    private fun finishClient(
        args: List<String>,
        inspection: JsonNode,
        clientDeadline: Long,
    ): JsonNode {
        val name = "$runId-client"

        fun left() = TimeUnit.NANOSECONDS.toMillis(clientDeadline - System.nanoTime()).also { check(it > 0) }
        val exit = command(listOf("docker", "wait", name), left()).stdout.trim()
        val result = command(listOf("docker", "logs", name), minOf(5_000, left()))
        check(exit == "0") { "client ${args.toList()} 종료 코드=$exit: ${result.stderr.takeLast(1600)}" }
        check(
            !mapper
                .readTree(command(listOf("docker", "inspect", name), minOf(5_000, left())).stdout)
                .path(0)
                .path("State")
                .path("OOMKilled")
                .asBoolean(),
        )
        command(listOf("docker", "rm", "-v", name), minOf(5_000, left()))
        checkpoints += mapOf("phase" to "client", "arguments" to args.toList(), "inspection" to inspection)
        budgets()
        return mapper.readTree(result.stdout.lineSequence().last { it.trim().startsWith("{") })
    }

    /** 실제 최초 응답 관측 뒤 다른 업무 요청 없이 사전에 확인한 자금 리더를 종료한다. */
    fun admitThenKill(
        initial: Map<String, JsonNode>,
        hidden: Boolean,
    ): Pair<JsonNode, Long> {
        check(leaderFailover && failoverOwner == "funds")
        phase = if (hidden) "실제 접수 응답 숨김" else "실제 접수 응답 직후 종료"
        val args = listOf("admit-observed", if (hidden) "hidden" else "normal")
        val (inspection, clientDeadline) = startClient(args)
        val file = control.resolve("first-admission.json")
        val until = minOf(clientDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
        while (!Files.isRegularFile(file)) {
            check(System.nanoTime() < until) { "실제 최초 접수 응답 관측 기한 초과" }
            Thread.sleep(20)
        }
        val observed = read(file)
        lifecycle["admission"] = observed
        mark("acceptedObserved")
        val term = killLeader(initial)
        val completed = finishClient(args, inspection, clientDeadline)
        lifecycle["admissionCompleted"] = completed
        check(completed.path("reply") == observed.path("reply")) { "최초 실응답과 client 종료 근거 불일치" }
        check(completed.path("code").asString() == if (hidden) "UNCONFIRMED" else "ACCEPTED")
        return observed.path("reply") to term
    }

    /** 의도한 단일 노드 종료만 제외하고 현재 부팅·임기의 실제 복제 관찰을 요구한다. */
    fun awaitFailover(
        stage: MultiMatchingFailoverStage,
        originals: Map<String, JsonNode>,
        higherThanTerm: Long? = null,
    ): Map<String, JsonNode> =
        awaitCurrent(stage.name, originals, higherThanTerm) { states ->
            MultiMatchingFailoverConvergence.violations(stage, states, originals)
        }

    fun awaitFunds(
        stage: FundsFailoverStage,
        originals: Map<String, JsonNode> = emptyMap(),
        higherThanTerm: Long? = null,
    ): Map<String, JsonNode> =
        awaitCurrent(stage.name, originals, higherThanTerm) { states ->
            FundsFailoverConvergence.violations(stage, states, originals)
        }

    private fun awaitCurrent(
        stage: String,
        originals: Map<String, JsonNode>,
        higherThanTerm: Long?,
        violations: (Map<String, JsonNode>) -> List<String>,
    ): Map<String, JsonNode> {
        check(leaderFailover)
        phase = stage
        val until = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
        var failures = emptyList<String>()
        var probe: Long? = null
        while (System.nanoTime() < until) {
            budgets()
            val states = SERVICES.filter { it !in stoppedServices }.associateWith { read(control.resolve("state-$it.json")) }
            latestStates = states
            failures = violations(states) + currentFailures(states, probe, higherThanTerm)
            if (failures.isEmpty()) {
                if (probe == null) {
                    probe = 1_000_000_000_000L + ++probeSequence
                    tool("probe", "all", probe.toString())
                    continue
                }
                verifyLiveNodes()
                checkpoints += mapOf("phase" to stage, "elapsedMs" to elapsedMs(), "states" to states, "originals" to originals.toMap())
                return states
            }
            Thread.sleep(100)
        }
        error("$stage 현재 사본 수렴 실패: ${failures.take(12)}")
    }

    private fun currentFailures(
        states: Map<String, JsonNode>,
        probe: Long?,
        higherThanTerm: Long?,
    ): List<String> {
        val failures = mutableListOf<String>()
        for ((service, state) in states) {
            val runtime = runtimes.getValue(service)
            for (field in listOf(
                "runId",
                "service",
                "bootId",
                "memberId",
                "role",
                "clusterId",
                "lineageHigh",
                "lineageLow",
                "manifestSha256",
                "topologySha256",
                "resultTopologySha256",
                "pid",
            )) {
                if (state.path(field).isMissingNode ||
                    state.path(field) != runtime.path(field)
                ) {
                    failures += "$service.$field 현재 runtime 불일치"
                }
            }
            if (!state.path("initialized").asBoolean() || state.path("lifecycle").asString() != "RUNNING") {
                failures += "$service 업무 준비 미완료"
            }
            if (probe != null) {
                val observed = state.path("probe")
                if (observed.path("correlationId").asLong() != probe || observed.path("bootId") != runtime.path("bootId") ||
                    observed.path("leadershipTermId") != state.path("leadershipTermId") ||
                    observed.path("leaderMemberId") != state.path("leaderMemberId") ||
                    observed.path("logPosition").asLong(-1) !in 0..state.path("logPosition").asLong()
                ) {
                    failures += "$service 새 복제 probe 미관측"
                }
            }
        }
        for (owner in listOf("funds", "btc", "sui")) {
            val group = states.filterKeys { it.startsWith(owner) }.values
            val terms = group.map { it.path("leadershipTermId") }.distinct()
            val leaders = group.map { it.path("leaderMemberId") }.distinct()
            if (terms.size != 1 || terms.single().isNull || !terms.single().isIntegralNumber || terms.single().asLong() < 0 ||
                leaders.size != 1 || !leaders.single().isIntegralNumber ||
                group.count { it.path("clusterRole").asString() == "LEADER" } != 1 ||
                group.any { it.path("clusterRole").asString() !in setOf("LEADER", "FOLLOWER") } ||
                group.none { it.path("memberId") == leaders.singleOrNull() && it.path("clusterRole").asString() == "LEADER" }
            ) {
                failures += "$owner 현재 임기/리더 합의 미완료"
            }
            if (owner == failoverOwner && higherThanTerm != null &&
                group.any { it.path("leadershipTermId").asLong(-1) <= higherThanTerm }
            ) {
                failures += "$owner 생존 과반의 더 높은 임기 미관측"
            }
        }
        return failures
    }

    fun installGate(
        gate: String,
        sequence: Long,
    ) {
        check(leaderFailover && gate in setOf("btc-dispatch", "btc-result"))
        gateInstallation = "$runId-$gate-$sequence"
        val value = mapOf("gate" to gate, "sequence" to sequence, "enabled" to true, "installationId" to gateInstallation)
        writeControl(value)
        gates += value + ("installedAt" to elapsedMs())
    }

    private fun writeControl(value: Map<String, Any?>) {
        val target = control.resolve("failover-control.json")
        check(!Files.exists(target)) { "BTC 보류 설정은 실행마다 한 번만 설치합니다" }
        val temporary = control.resolve("failover-control.tmp")
        Files.write(temporary, mapper.writeValueAsBytes(value), java.nio.file.StandardOpenOption.CREATE_NEW)
        Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    fun awaitBlocked(): JsonNode {
        val until = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
        while (System.nanoTime() < until) {
            for (service in SERVICES.filter { it !in stoppedServices }) {
                val file = control.resolve("worker-$service.json")
                if (!Files.isRegularFile(file)) continue
                val worker = read(file)
                val blocked = worker.path("blocked")
                if (worker.path("bootId") == runtimes.getValue(service).path("bootId") &&
                    blocked.path("installationId").asString() == gateInstallation
                ) {
                    check(blocked.path("wireBytes").asLong() > 0 && blocked.path("payloadSha256").asString().matches(Regex("[0-9a-f]{64}")))
                    val gate = gates.last()
                    val bytes =
                        Files.readAllBytes(
                            control.resolve("held-$service-${gate.getValue("gate")}-${gate.getValue("sequence")}.bin"),
                        )
                    check(sha(bytes) == blocked.path("wireSha256").asString()) { "보류 원문과 worker 지문 불일치" }
                    gates[gates.lastIndex] = gate +
                        mapOf(
                            "blockedAt" to elapsedMs(),
                            "blockedService" to service,
                            "blocked" to blocked,
                            "heldWireSha256" to sha(bytes),
                            "heldWireBytes" to bytes.size,
                        )
                    return blocked
                }
            }
            Thread.sleep(100)
        }
        error("실제 전송 직전 BTC 보류를 관측하지 못했습니다")
    }

    /** 보류 해제 뒤 같은 원문이 실제 worker에서 양수 offer와 실제 응답까지 이어졌는지 결속한다. */
    fun awaitReleased() {
        val gate = gates.last()
        val expected = if (gate.getValue("gate") == "btc-dispatch") gate.getValue("blockedService").toString() else electedLeader
        check(expected.isNotEmpty())
        val until = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
        while (System.nanoTime() < until) {
            val workerFile = control.resolve("worker-$expected.json")
            if (!Files.isRegularFile(workerFile)) {
                Thread.sleep(100)
                continue
            }
            val worker = read(workerFile)
            val offered = worker.path("offered")
            val reply = worker.path("reply")
            val original = gate.getValue("blocked") as JsonNode
            if (offered.path("installationId").asString() == gateInstallation &&
                offered.path("positiveOfferPosition").asLong() > 0 &&
                offered.path("bootId") == runtimes.getValue(expected).path("bootId") &&
                reply.path("bootId") == offered.path("bootId") && reply.path("connectionId") == offered.path("connectionId") &&
                reply.path("correlationId") == offered.path("correlationId") &&
                reply.path("through").asLong() >= original.path("sequence").asLong() &&
                reply.path("payloadSha256") == original.path("payloadSha256")
            ) {
                check(offered.path("payloadSha256") == original.path("payloadSha256")) { "재개 worker 원문 변경" }
                gates[gates.lastIndex] = gate +
                    mapOf(
                        "offered" to offered,
                        "reply" to reply,
                        "offeredService" to expected,
                        "offeredObservedAt" to elapsedMs(),
                    )
                return
            }
            Thread.sleep(100)
        }
        error("BTC 실제 worker의 원문 전송·응답 재개 근거를 찾지 못했습니다: $expected")
    }

    fun mark(name: String) {
        timeline[name] = elapsedMs()
        eventOrder[name] = eventOrder.size + 1
    }

    fun releaseGate() {
        val gate = gates.last()
        Files.createFile(control.resolve("failover-release-$gateInstallation"))
        mark("gateReleased")
        gates[gates.lastIndex] = gate + ("releaseAt" to timeline.getValue("gateReleased"))
    }

    /** 요청 전 확인한 실제 리더만 종료하고 과거 LEADER 파일을 이후 현재 사본에서 제외한다. */
    fun killBtcLeader(states: Map<String, JsonNode>): Long = killLeader(states)

    private fun killLeader(states: Map<String, JsonNode>): Long {
        check(leaderFailover && stoppedServices.isEmpty())
        check(
            currentFailures(
                states,
                states.values
                    .first()
                    .path("probe")
                    .path("correlationId")
                    .asLong(),
                null,
            ).isEmpty(),
        )
        val group = states.filterKeys { it.startsWith(failoverOwner) }
        val target = group.entries.single { it.value.path("clusterRole").asString() == "LEADER" }.key
        val term = group.getValue(target).path("leadershipTermId").asLong()
        lifecycle["target"] = target
        lifecycle["initial"] = boundRuntimes(states)
        mark("killSent")
        command(listOf("docker", "kill", "--signal", "KILL", containers.getValue(target)), 10_000)
        check(command(listOf("docker", "wait", containers.getValue(target)), 10_000).stdout.trim() == "137")
        val inspected = mapper.readTree(command(listOf("docker", "inspect", containers.getValue(target)), 10_000).stdout).path(0)
        val state = inspected.path("State")
        check(!state.path("Running").asBoolean() && state.path("ExitCode").asInt() == 137 && !state.path("OOMKilled").asBoolean())
        stoppedServices.add(target)
        mark("killConfirmed")
        lifecycle["stopped"] =
            mapOf(
                "service" to target,
                "containerId" to containers.getValue(target),
                "running" to false,
                "exitCode" to 137,
                "oomKilled" to false,
                "inspection" to inspected,
            )
        verifyLiveNodes()
        return term
    }

    fun recordElection(states: Map<String, JsonNode>) {
        check(stoppedService != null)
        mark("electionObserved")
        lifecycle["elected"] = boundRuntimes(states)
        electedLeader =
            states.entries.single { it.key.startsWith(failoverOwner) && it.value.path("clusterRole").asString() == "LEADER" }.key
    }

    private fun boundRuntimes(states: Map<String, JsonNode>) =
        states.mapValues { (service, state) ->
            (runtimes.getValue(service).deepCopy() as ObjectNode).apply { set("state", state) }
        }

    /** 같은 Archive를 그대로 다시 열되, 이 노드 한 개의 복구 플래그만 바꾼다. */
    fun rejoinBtcLeader() = rejoinLeader()

    fun rejoinLeader() = rejoinService(checkNotNull(stoppedService))

    /** 종료한 대상별로 원래 기록을 다시 열며 생존한 다른 노드는 재생성하지 않는다. */
    fun rejoinService(target: String) {
        check(target in stoppedServices)
        val until = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(90))
        val previous = runtimes.getValue(target)
        val volume = recordVolume(previous)
        val beforeFiles = recoveryFiles(target)
        lifecycle["recoveryBefore"] =
            mapOf(
                "service" to target,
                "volume" to volume,
                "files" to beforeFiles,
                "runRoot" to previous.path("runRoot"),
                "archiveDirectory" to previous.path("archiveDirectory"),
                "clusterDirectory" to previous.path("clusterDirectory"),
            )
        if (quorumCase != null) quorumMark("recovery-before-$target", checkNotNull(lifecycle["recoveryBefore"]))
        val recoveryFile = reports.resolve("recovery.override.yml")
        recoveredServices.add(target)
        Files.writeString(
            recoveryFile,
            "services:\n" + recoveredServices.joinToString("") { "  $it:\n    environment:\n      FUNDING_RECOVER_EXISTING: \"true\"\n" },
        )
        recoveryOverride = recoveryFile
        Files.delete(control.resolve("runtime-$target.json"))
        compose(listOf("up", "-d", "--no-deps", "--force-recreate", target), TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()))
        while (!Files.isRegularFile(control.resolve("runtime-$target.json"))) {
            check(System.nanoTime() < until) { "구 $failoverOwner 리더의 새 runtime 미관측" }
            Thread.sleep(100)
        }
        val runtime = read(control.resolve("runtime-$target.json")) as ObjectNode
        val id = compose(listOf("ps", "-q", target), 10_000).stdout.trim()
        check(id.isNotEmpty() && id != containers.getValue(target))
        val inspected = mapper.readTree(command(listOf("docker", "inspect", id), 10_000).stdout).path(0)
        check(inspected.path("Image").asString() == imageId)
        for (field in listOf("Memory", "ShmSize", "NanoCpus", "PidsLimit", "IpcMode", "PidMode", "PortBindings")) {
            check(inspected.path("HostConfig").path(field) == previous.path("inspection").path("HostConfig").path(field)) {
                "$target 재합류 자원/격리 한도 변경: $field"
            }
        }
        check(runtime.path("storageLevels") == previous.path("storageLevels"))
        val initial = checkNotNull((lifecycle["initial"] as? Map<*, *>)?.get(target) as? JsonNode)
        val originalNetwork =
            initial
                .path("inspection")
                .path("NetworkSettings")
                .path("Networks")
                .properties()
                .single()
                .value
        val rejoinedNetwork =
            inspected
                .path("NetworkSettings")
                .path("Networks")
                .properties()
                .single()
                .value
        val addresses =
            listOf(
                originalNetwork.path("IPAddress").asString(),
                originalNetwork.path("IPAMConfig").path("IPv4Address").asString(),
                rejoinedNetwork.path("IPAddress").asString(),
                rejoinedNetwork.path("IPAMConfig").path("IPv4Address").asString(),
            )
        check(addresses.all(String::isNotBlank) && addresses.distinct().size == 1) { "$target 재합류 고정 endpoint 변경: $addresses" }
        runtime.put("imageId", imageId)
        runtime.put("containerId", id)
        runtime.set("inspection", inspected)
        check(runtime.path("bootId") != previous.path("bootId") && runtime.path("recoverExisting").asBoolean())
        for (field in listOf(
            "runId",
            "service",
            "memberId",
            "clusterId",
            "lineageHigh",
            "lineageLow",
            "manifestSha256",
            "topologySha256",
            "resultTopologySha256",
            "members",
            "runRoot",
            "archiveDirectory",
            "clusterDirectory",
        )) {
            check(runtime.path(field) == previous.path(field)) { "$target 재합류 identity/경로 변경: $field" }
        }
        check(recordVolume(runtime) == volume)
        runtimes[target] = runtime
        containers[target] = id
        stoppedServices.remove(target)
        if (quorumCase != null &&
            quorumNetworkControl != null
        ) {
            quorumMark("rejoined-network-$target", quorumNetworkStep { quorumNetwork().rejoined(target, quorumLive()) })
        }
        val afterFiles = recoveryFiles(target)
        check(beforeFiles.lineSequence().first() == afterFiles.lineSequence().first()) { "재합류 복구 identity 원문 변경" }
        lifecycle["recoveryAfter"] = mapOf("service" to target, "volume" to volume, "files" to afterFiles)
        if (quorumCase != null) quorumMark("recovery-after-$target", checkNotNull(lifecycle["recoveryAfter"]))
        verifyLiveNodes()
    }

    fun recordRejoined(states: Map<String, JsonNode>) {
        mark("rejoined")
        lifecycle["rejoined"] = boundRuntimes(states)
    }

    fun verifyFailoverEvidence() {
        lifecycle["timeline"] = timeline
        lifecycle["eventOrder"] = eventOrder
        lifecycle["gates"] = gates
        val evidence = mapper.readTree(mapper.writeValueAsBytes(lifecycle))
        val failures =
            if (failoverOwner == "funds") {
                FundsFailoverConvergence.lifecycleViolations(evidence)
            } else {
                MultiMatchingFailoverConvergence.lifecycleViolations(evidence)
            }
        check(failures.isEmpty()) { "장애 수명 근거 실패: $failures" }
    }

    private fun recordVolume(runtime: JsonNode): String =
        runtime
            .path("inspection")
            .path("Mounts")
            .single { it.path("Destination").asString() == "/records" }
            .path("Name")
            .asString()

    private fun recoveryFiles(service: String): String {
        val runtime = runtimes.getValue(service)
        val root = runtime.path("runRoot").asString().replace("/records/", "/records/$service/")
        val archive = runtime.path("archiveDirectory").asString().replace("/records/", "/records/$service/")
        val cluster = runtime.path("clusterDirectory").asString().replace("/records/", "/records/$service/")
        check(listOf(root, archive, cluster).all { it.matches(Regex("[a-zA-Z0-9/_.-]+")) })
        return recordsHelper(
            "sha256sum $root/split-recovery-identity.bin && stat -c '%n %s' $archive/archive.catalog $cluster/recording.log",
        ).also { output ->
            check(
                output.lineSequence().filter(String::isNotBlank).count() == 3 &&
                    output
                        .lineSequence()
                        .filter(String::isNotBlank)
                        .drop(1)
                        .all { it.substringAfterLast(' ').toLong() > 0 },
            )
        }
    }

    private fun recordsHelper(script: String): String {
        val mounts =
            SERVICES.flatMap { service ->
                listOf("--mount", "type=volume,src=${recordVolume(runtimes.getValue(service))},dst=/records/$service,readonly")
            }
        val helperName = "$runId-records"
        return try {
            command(
                listOf(
                    "docker",
                    "run",
                    "--rm",
                    "--name",
                    helperName,
                    "--network",
                    "none",
                    "--read-only",
                    "--memory",
                    "768m",
                    "--cpus",
                    "1",
                    "--shm-size",
                    "256m",
                    "--pids-limit",
                    "128",
                    "--label",
                    "com.exchange.multi.run=$runId",
                    "--entrypoint",
                    "sh",
                ) + mounts + listOf(image, "-c", script),
                15_000,
            ).stdout
        } catch (failure: Exception) {
            // Docker CLI만 종료돼도 컨테이너는 남을 수 있다. 이 실행의 보조 컨테이너부터 회수한다.
            try {
                command(listOf("docker", "rm", "-f", "-v", helperName), 10_000, true)
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun sameBusiness(
        before: Map<String, JsonNode>,
        after: Map<String, JsonNode>,
    ) {
        assertEquals(before.keys, after.keys, "중복 대조 사본 집합 변경")
        for (service in before.keys) {
            for (field in listOf(
                "funds",
                "matching",
                "raceRows",
            )) {
                assertEquals(before.getValue(service).path(field), after.getValue(service).path(field), "$service $field 중복 효과")
            }
        }
    }

    private fun read(file: Path): JsonNode {
        check(Files.isRegularFile(file) && Files.size(file) in 1..(256 * 1024)) { "관찰 누락/크기 위반: ${file.fileName}" }
        return mapper.readTree(Files.readString(file))
    }

    private fun budgets() {
        diagnosticPeak = maxOf(diagnosticPeak, MultiMatchingRunBudget.diagnosticBytes(reports))
        check(diagnosticPeak <= 16 * MIB) { "진단 합계16MiB 초과: $diagnosticPeak" }
        if (containers.isNotEmpty()) {
            if (activeQuorumClient != null) return // 장수 client와 별도 volume helper의 동시 실행을 금지한다.
            // 중단된 사본의 볼륨만 별도 접근이 필요하다. 모두 살아 있으면 기존 노드에서 직접 잰다.
            if (stoppedServices.isNotEmpty()) {
                val total =
                    recordsHelper("du -sk /records/*")
                        .lineSequence()
                        .filter(String::isNotBlank)
                        .sumOf { it.trim().split(Regex("\\s+"))[0].toLong() * 1024 }
                recordsPeak = maxOf(recordsPeak, total)
                check(total <= 2 * GIB) { "죽은 볼륨 포함9개 기록 합계2GiB 초과: $total" }
                return
            }
            // 이미 확인한 컨테이너에서 볼륨별 명령을 먼저 시작하고 하나의 기한 안에서 모두 회수한다.
            val until = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
            val measurements =
                SERVICES.associateWith { service ->
                    check(!Thread.currentThread().isInterrupted && System.nanoTime() < until) {
                        "기록 용량 측정 시작 기한 초과: $service"
                    }
                    val args = listOf("docker", "exec", containers.getValue(service), "du", "-sk", "/records")
                    commands.start(args, outputLimitBytes = MIB)
                }
            var total = 0L
            for ((service, measurement) in measurements) {
                val left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
                check(left > 0) { "기록 용량 측정 기한 초과: $service" }
                val result = measurement.await(left)
                check(result.succeeded) { "$service 기록 용량 측정 실패: ${result.failure()}" }
                val sizeAndPath = result.stdout.trim().split(Regex("\\s+"))
                val sizeKiB = sizeAndPath.first().toLongOrNull()
                check(sizeAndPath.size == 2 && sizeAndPath[1] == "/records" && sizeKiB != null && sizeKiB >= 0) {
                    "$service 기록 용량 출력 오류: ${result.stdout.take(200)}"
                }
                total = Math.addExact(total, Math.multiplyExact(sizeKiB, 1024))
            }
            recordsPeak = maxOf(recordsPeak, total)
            check(total <= 2 * GIB) { "9개 기록 합계2GiB 초과: $total" }
        }
    }

    private fun compose(
        args: List<String>,
        timeout: Long,
        cleaning: Boolean = false,
    ): VerificationCommandResult =
        command(
            listOf(
                "docker",
                "compose",
                "--project-name",
                runId,
                "--file",
                project.resolve("src/test/docker/aeron-multi-matching/compose.yml").toString(),
            ) +
                (
                    if (quorumCase !=
                        null
                    ) {
                        listOf("--file", project.resolve("src/test/docker/aeron-multi-matching/quorum.compose.yml").toString())
                    } else {
                        emptyList()
                    }
                ) +
                (recoveryOverride?.let { listOf("--file", it.toString()) } ?: emptyList()) +
                args,
            timeout,
            cleaning,
        )

    private fun command(
        args: List<String>,
        timeout: Long,
        cleaning: Boolean = false,
    ): VerificationCommandResult {
        val allowed = remainingMs(timeout, cleaning)
        check(!Thread.currentThread().isInterrupted) { "중단된 실행은 새 CLI를 시작하지 않습니다" }
        val outputLimit = if ("logs" in args) 128L * 1024 else MIB
        val result = commands.start(args, outputLimitBytes = outputLimit).await(allowed)
        check(result.succeeded) { "${args.take(5)} 실패 exit=${result.exitCode} timeout=${result.timedOut}: ${result.stderr.takeLast(1200)}" }
        return result
    }

    private fun remainingMs(
        maximum: Long,
        cleaning: Boolean = false,
    ): Long {
        val left = TimeUnit.NANOSECONDS.toMillis((if (cleaning) deadline else workDeadline) - System.nanoTime())
        check(left > 0) { "전체 ${if (cleaning) "정리" else "업무"} 기한 초과" }
        return minOf(maximum, left)
    }

    private fun cleanup() {
        fun attempt(block: () -> Unit) {
            runCatching(block).exceptionOrNull()?.let { issues += "정리: ${it.javaClass.simpleName}: ${it.message}" }
        }
        quorumCleaning = true
        attempt {
            try {
                if (quorumNetworkControl != null) quorumNetworkStep { quorumNetworkControl?.close() }
            } finally {
                quorumEvidence["networkRemovals"] = quorumNetworkControl?.removals()
            }
            check(quorumNetworkControl?.removals()?.none { it.error != null } != false) { "네트워크 규칙 회수 실패" }
        }
        commands.closeRunning().forEach { issues += "정리: $it" }
        if (composeAttempted) {
            for (service in SERVICES) {
                attempt {
                    val result = compose(listOf("logs", "--no-color", "--tail", "80", service), 2_000, true)
                    val log = result.stdout.take(128 * 1024)
                    Files.writeString(reports.resolve("$service.log"), log)
                }
            }
            attempt { compose(listOf("down", "--volumes", "--remove-orphans", "--timeout", "10"), 25_000, true) }
        }
        attempt {
            val ids =
                command(
                    listOf("docker", "ps", "-aq", "--filter", "label=com.exchange.multi.run=$runId"),
                    5_000,
                    true,
                ).stdout.split(Regex("\\s+")).filter(String::isNotBlank)
            if (ids.isNotEmpty()) command(listOf("docker", "rm", "-f", "-v") + ids, 10_000, true)
        }
        attempt {
            val ids =
                command(
                    listOf("docker", "volume", "ls", "-q", "--filter", "label=com.exchange.multi.run=$runId"),
                    5_000,
                    true,
                ).stdout.split(Regex("\\s+")).filter(String::isNotBlank)
            if (ids.isNotEmpty()) command(listOf("docker", "volume", "rm") + ids, 10_000, true)
        }
        // custom client를 회수하기 전 compose down이 남긴 이 run의 빈 network도 마지막에 회수한다.
        attempt {
            val networks =
                command(
                    listOf("docker", "network", "ls", "-q", "--filter", "label=com.docker.compose.project=$runId"),
                    5_000,
                    true,
                ).stdout.split(Regex("\\s+")).filter(String::isNotBlank)
            if (networks.isNotEmpty()) command(listOf("docker", "network", "rm") + networks, 10_000, true)
        }
        if (imageAttempted) {
            attempt {
                if (command(
                        listOf("docker", "image", "ls", "-q", image),
                        5_000,
                        true,
                    ).stdout.isNotBlank()
                ) {
                    command(listOf("docker", "image", "rm", image), 10_000, true)
                }
            }
        }
        attempt {
            val containersLeft =
                command(
                    listOf("docker", "ps", "-aq", "--filter", "label=com.exchange.multi.run=$runId"),
                    5_000,
                    true,
                ).stdout.trim()
            val volumesLeft =
                command(
                    listOf("docker", "volume", "ls", "-q", "--filter", "label=com.exchange.multi.run=$runId"),
                    5_000,
                    true,
                ).stdout.trim()
            val networksLeft =
                command(
                    listOf("docker", "network", "ls", "-q", "--filter", "label=com.docker.compose.project=$runId"),
                    5_000,
                    true,
                ).stdout.trim()
            resourcesRemoved = containersLeft.isEmpty() && volumesLeft.isEmpty() && networksLeft.isEmpty()
            check(resourcesRemoved) { "run 소유 자원이 남았습니다" }
        }
    }

    private fun writeReport() {
        if (sourceHashes.isNotEmpty() && currentSourceHashes() != sourceHashes) issues += "실행 중 소스가 변경되었습니다"
        diagnosticPeak = maxOf(diagnosticPeak, MultiMatchingRunBudget.diagnosticBytes(reports))
        val report =
            mapOf(
                "runId" to runId,
                "case" to
                    if (quorumCase != null) {
                        quorumCase
                    } else if (leaderFailover && failoverOwner == "funds") {
                        if ((lifecycle["admission"] as? JsonNode)?.path("hidden")?.asBoolean() ==
                            true
                        ) {
                            "funds-hidden-reply"
                        } else {
                            "funds-after-reply"
                        }
                    } else if (leaderFailover) {
                        gates.firstOrNull()?.get("gate") ?: "leader-failover"
                    } else {
                        scenario
                    },
                "success" to (issues.isEmpty() && resourcesRemoved),
                "issues" to issues,
                "baseCommit" to
                    if (quorumCase !=
                        null
                    ) {
                        "3067b0c2dfb3cf261c1a13c74718c016ea19d6e7"
                    } else if (leaderFailover && failoverOwner == "funds") {
                        "26d2013cca94abcdc38a1a55c2d048a68ee7fc3b"
                    } else if (leaderFailover) {
                        "9001e14a5afe96f91aeb461861052dd62c57ef9a"
                    } else {
                        "e3656d88e1cd1a10fe670472ca5744a23eb037f9"
                    },
                "headCommit" to headCommit,
                "quorumEvidence" to quorumEvidence,
                "sourceSha256" to sourceHashes,
                "imageId" to imageId,
                "manifestSha256" to manifestSha,
                "provenance" to provenance,
                "memoryObservations" to memoryObservations,
                "runtimes" to runtimes,
                "checkpoints" to checkpoints,
                "lastStates" to latestStates,
                "failoverEvidence" to (lifecycle + mapOf("timeline" to timeline, "eventOrder" to eventOrder, "gates" to gates)),
                "elapsedMs" to elapsedMs(),
                "dockerMemoryBytes" to dockerMemory,
                "hostFreeDiskBefore" to freeBefore,
                "hostFreeDiskAfter" to Files.getFileStore(project).usableSpace,
                "recordsBytesPeak" to recordsPeak,
                "diagnosticBytesPeak" to diagnosticPeak,
                "databaseDataBytesObserved" to temporaryDataPeak,
                "clientTemporaryDataBytesPeak" to null,
                "clientTemporaryDataEnforcedLimit" to 512 * MIB,
                "recordVolumesDeleted" to resourcesRemoved,
                "runResourcesRemoved" to resourcesRemoved,
                "limits" to
                    mapOf(
                        "totalMs" to 480_000,
                        "cleanupMs" to 90_000,
                        "containerBytes" to 768 * MIB,
                        "heapBytes" to 256 * MIB,
                        "recordsBytes" to 2 * GIB,
                        "diagnosticBytes" to 16 * MIB,
                        "temporaryDataBytes" to 512 * MIB,
                    ),
                "commands" to commands.records,
                "unverified" to
                    if (quorumCase != null) {
                        listOf("다른 Q 사례는 각자의 별도 실행으로 확인", "전체 볼륨 유실", "서버/AZ 전체 장애", "장기 부하/성능")
                    } else if (leaderFailover && failoverOwner == "funds") {
                        listOf("BTC 리더 종료/장애 격리", "과반 상실/네트워크 분할", "전체 볼륨 유실", "장기 부하/성능")
                    } else if (leaderFailover) {
                        listOf("자금 리더 종료", "과반 상실/네트워크 분할", "전체 볼륨 유실", "장기 부하/성능")
                    } else {
                        listOf("리더 전환/과반 장애", "장기 부하/성능", "실제5마켓3+2 배치", "미회수 payload에 대한 부정 ACK 격리")
                    },
            )
        val bytes = mapper.writeValueAsBytes(report)
        check(bytes.size <= 4 * MIB) { "결과 보고서4MiB 초과" }
        Files.write(reports.resolve("result.json"), bytes)
        diagnosticPeak = maxOf(diagnosticPeak, MultiMatchingRunBudget.diagnosticBytes(reports))
        if (diagnosticPeak > 16 * MIB) issues += "정리/보고서 포함 진단 합계16MiB 초과: $diagnosticPeak"
        // 최종 파일까지 포함한 판정으로 성공 여부를 다시 기록한다.
        val finalReport = report + mapOf("diagnosticBytesPeak" to diagnosticPeak, "success" to (issues.isEmpty() && resourcesRemoved))
        Files.write(reports.resolve("result.json"), mapper.writeValueAsBytes(finalReport))
    }

    private fun currentSourceHashes() =
        (
            SOURCES +
                if (quorumCase == null) {
                    emptyList()
                } else {
                    listOf(
                        "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumObservation.kt",
                        "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumRuntimeObservation.kt",
                        "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/AeronSplitOrderDeliveryObservation.kt",
                        "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumClient.kt",
                        "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumNetworkControl.kt",
                        "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumConvergence.kt",
                        "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsQuorumScenario.kt",
                        "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsQuorumLossTest.kt",
                        "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerFundsQuorumPartitionTest.kt",
                        "src/test/docker/aeron-multi-matching/quorum.Dockerfile",
                        "src/test/docker/aeron-multi-matching/quorum.compose.yml",
                        "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/SplitOrderDeliveryClient.kt",
                        "../.github/ci-test-groups.json",
                        "../gradle.properties",
                    )
                }
        ).associateWith { sha(Files.readAllBytes(project.resolve(it))) }

    private fun elapsedMs() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    companion object {
        private const val MIB = 1024L * 1024
        private const val GIB = 1024L * MIB
        private const val TOOL = "com.exchange.core.api.trading.infrastructure.aeron.MultiMatchingVerificationToolKt"
        private val SERVICES = listOf("funds0", "funds1", "funds2", "btc0", "btc1", "btc2", "sui0", "sui1", "sui2")
        private val SOURCES =
            listOf(
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DockerMultiMatchingReplicationTest.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMatchingVerificationTool.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMatchingFailureControl.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMatchingConvergence.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMatchingLeaderFailoverTest.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMatchingFailoverConvergence.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsLeaderFailoverTest.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsFailoverConvergence.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsAdmissionReplyControl.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MarketConfigurationDatabase.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/VerificationCommands.kt",
                "src/test/docker/aeron-multi-matching/compose.yml",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingNode.kt",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingMain.kt",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsOrderDeliveryAgent.kt",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgent.kt",
            )

        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
