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

internal class DockerFundingVerification(
    private val scenario: OrderReplicationScenario? = null,
    sharedImage: String? = null,
    private val concurrentScenario: ConcurrentOrderReplicationScenario? = null,
) {
    init {
        require(scenario == null || concurrentScenario == null) { "순차·동시 사례를 함께 선택할 수 없습니다" }
    }

    private val concurrentOrders = concurrentScenario != null
    private val concurrentResponseTimeoutMs = 20_000L
    private val concurrentObservationTimeoutMs = 15_000L
    private val mapper = jacksonObjectMapper()
    private val projectDirectory = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
    private val distributionDirectory = Path.of(requireNotNull(System.getProperty("fundingDistributionDirectory")))
    private val runId = "funding${System.currentTimeMillis()}${UUID.randomUUID().toString().take(8)}"
    val reportDirectory: Path = projectDirectory.resolve("build/reports/aeron-funding/$runId")
    private val composeFile = projectDirectory.resolve("src/test/docker/aeron-funding/compose.yml")
    private val dockerfile = projectDirectory.resolve("src/test/docker/aeron-funding/Dockerfile")
    val image = sharedImage ?: "exchange-funding:$runId"
    private val buildImage = sharedImage == null
    private val controlDirectory = reportDirectory.resolve("control")
    private val environment =
        mutableMapOf(
            "FUNDING_IMAGE" to image,
            "FUNDING_RUN_ID" to runId,
            "FUNDING_CONTROL_DIRECTORY" to controlDirectory.toString(),
        )
    private val usesMarketDatabase =
        scenario == OrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS ||
            concurrentScenario == ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS
    private val databasePassword = "issue116-${UUID.randomUUID()}"
    private var marketDatabase: MarketConfigurationDatabase? = null
    private var marketNetwork: String? = null
    private var expectedConfiguration: ExpectedMarketConfiguration? = null
    private val issues = mutableListOf<FundingVerificationIssue>()
    private val commandProcesses = VerificationCommands(projectDirectory, reportDirectory, environment)
    private val timings = linkedMapOf<String, Long>()
    private val nodeIds = linkedMapOf<Int, String>()
    private val events = linkedMapOf<Int, List<JsonNode>>()
    private val inspections = linkedMapOf<Int, JsonNode>()
    private var clientEvents = emptyList<JsonNode>()
    private val clients = mutableListOf<String>()
    private val stepReports = mutableListOf<Map<String, Any?>>()

    private data class OrderCheckpoint(
        val step: OrderReplicationStep,
        val previousPosition: Long,
        val position: Long?,
        val responses: List<JsonNode>,
    )

    private val attemptedSteps = mutableListOf<OrderCheckpoint>()
    private var openingPosition: Long? = null
    private var pairPositions: Map<String, Long>? = null
    private val pairClientEvents = linkedMapOf<String, List<JsonNode>>()
    private var pairAttempted = false
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
                if (buildImage) {
                    must(
                        command(
                            listOf("docker", "build", "-f", dockerfile.toString(), "-t", image, distributionDirectory.toString()),
                            300_000,
                        ),
                        "이미지 준비",
                    )
                }
                must(command(listOf("docker", "image", "inspect", image), 15_000), "이미지 식별")
            }
            if (usesMarketDatabase) {
                timed("market-database") {
                    stage = "설정 DB 준비"
                    val database = MarketConfigurationDatabase(databasePassword)
                    marketDatabase = database
                    val settings = database.register(MULTI_MARKET_DOCUMENT)
                    environment.putAll(settings)
                    environment["FUNDING_MARKET_DB_URL"] = database.containerJdbcUrl
                    expectedConfiguration =
                        ExpectedMarketConfiguration(
                            settings.getValue("FUNDING_MARKET_CONFIG_VERSION").toLong(),
                            settings.getValue("FUNDING_MARKET_CONFIG_SHA256"),
                        )
                }
            }
            timed("startup") {
                stage = "기동"
                val deadline = deadline(60_000)
                composeAttempted = true
                if (usesMarketDatabase) connectMarketDatabase(deadline)
                must(compose(listOf("up", "-d", "node0", "node1", "node2"), remaining(deadline)), "세 노드 동시 기동")
                findNodeIds(deadline)
                while (true) {
                    collectNodes(deadline, requireRunning = true)
                    val readiness =
                        if (usesMarketDatabase) {
                            compareFundingReadiness(runId, events, expectedMultiMarketSettings(), checkNotNull(expectedConfiguration))
                        } else {
                            compareFundingReadiness(runId, events)
                        }
                    if (readiness.isEmpty()) break
                    check(System.nanoTime() < deadline) { readiness.joinToString("; ") { it.message } }
                    if (events.values.flatten().any { it.path("kind").asString() == "ERROR" }) error("노드 오류로 준비를 완료하지 못했습니다")
                    Thread.sleep(100)
                }
                validateIsolation()
                check(issues.isEmpty()) { "컨테이너 격리·설정 확인 실패" }
            }
            when {
                concurrentOrders -> runConcurrentInputs()
                scenario != null -> runOrderInputs(scenario.steps())
                else -> runFundingInput()
            }
        } catch (error: Exception) {
            issues.add(
                FundingVerificationIssue(
                    if (inputAttempted && !(concurrentOrders && openingPosition != null && !pairAttempted)) "UNVERIFIABLE" else "SETUP",
                    "$stage 실패: ${error.javaClass.simpleName}: ${error.message}",
                ),
            )
        } finally {
            issues.addAll(commandProcesses.closeRunning())
            try {
                preserveAndClose()
            } finally {
                closeMarketDatabase()
            }
            recheckOrderInputs()
            recheckConcurrentInputs()
            // 이미 확인한 차이는 마지막 부분 수집이나 정리 실패로 덮어쓰지 않는다.
            comparison =
                if (inputAttempted && scenario == null && !concurrentOrders) {
                    compareFundingReplication(runId, events, clientEvents, collectionIssues = issues)
                } else {
                    FundingReplicationComparison(comparison.position, issues.distinct())
                }
            writeReport()
            if (usesMarketDatabase) {
                val leaked = credentialLeakFiles(reportDirectory, databasePassword)
                check(leaked.isEmpty()) { "설정 DB 비밀번호가 보고서에 포함됐습니다: ${leaked.map(reportDirectory::relativize)}" }
            }
        }
        return comparison
    }

    /** JVM을 시작하기 전에 외부 테스트 DB를 이번 실행의 전용 bridge에 연결한다. */
    private fun connectMarketDatabase(deadline: Long) {
        must(compose(listOf("create", "node0", "node1", "node2"), remaining(deadline)), "세 노드 생성")
        findNodeIds(deadline)
        val inspection = command(dockerContainerInspection(nodeIds.getValue(0)), remaining(deadline))
        must(inspection, "설정 DB 연결용 네트워크 확인")
        val networks =
            mapper
                .readTree(inspection.stdout)
                .path(0)
                .path("NetworkSettings")
                .path("Networks")
                .propertyNames()
                .toList()
        check(networks.size == 1) { "설정 DB를 연결할 전용 bridge가 하나가 아닙니다" }
        val network = networks.single()
        marketNetwork = network // 명령이 중간에 실패해도 정리 대상은 기억한다.
        must(
            command(
                listOf("docker", "network", "connect", "--alias", "market-db", network, checkNotNull(marketDatabase).containerId),
                remaining(deadline),
            ),
            "설정 DB 네트워크 연결",
        )
    }

    private fun closeMarketDatabase() {
        val database = marketDatabase ?: return
        try {
            marketNetwork?.let { network ->
                val result = command(listOf("docker", "network", "disconnect", "--force", network, database.containerId), 10_000)
                if (!result.succeeded) issues.add(FundingVerificationIssue("CLEANUP", "설정 DB 네트워크 분리 실패: ${result.failure()}"))
            }
        } catch (error: Exception) {
            issues.add(FundingVerificationIssue("CLEANUP", "설정 DB 네트워크 정리 실패: ${error.javaClass.simpleName}"))
        } finally {
            try {
                database.close()
            } catch (error: Exception) {
                issues.add(FundingVerificationIssue("CLEANUP", "설정 DB 종료 실패: ${error.javaClass.simpleName}"))
            } finally {
                marketDatabase = null
            }
        }
    }

    private fun runFundingInput() {
        timed("client") {
            stage = "클라이언트"
            inputAttempted = true
            val response = runClient("client", emptyList())
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
                if (hasThreeFreshObservations() || events.values.flatten().any { it.path("kind").asString() == "ERROR" }) break
                if (System.nanoTime() >= deadline) break
                Thread.sleep(100)
            }
            issues.addAll(comparison.issues)
        }
    }

    private fun runOrderInputs(steps: List<OrderReplicationStep>) {
        var previousPosition = 0L
        for (step in steps) {
            stage = "${scenario?.name ?: "CONCURRENT"}/${step.name}"
            inputAttempted = true
            val observationCursors = events.mapValues { it.value.size }
            val response = runClient(step.name.lowercase(), listOf(step.mainClass, "--cluster-env") + step.arguments)
            val stepClientEvents = readFundingEvents(response.stdout, issues)
            clientEvents = clientEvents + stepClientEvents
            if (!response.succeeded) issues.add(FundingVerificationIssue("UNVERIFIABLE", "$stage 응답 미확인: ${response.failure()}"))
            timed("$stage-observations") {
                val deadline = deadline(15_000)
                while (true) {
                    collectNodes(deadline, requireRunning = true)
                    comparison = compareOrderReplication(runId, step, events, stepClientEvents, previousPosition)
                    issues.addAll(comparison.issues.filter { it.category == "MISMATCH" })
                    if (hasOrderObservations(step, comparison.position, previousPosition) ||
                        events.values.flatten().any { it.path("kind").asString() == "ERROR" }
                    ) {
                        break
                    }
                    if (System.nanoTime() >= deadline) break
                    Thread.sleep(100)
                }
                issues.addAll(comparison.issues)
            }
            attemptedSteps.add(OrderCheckpoint(step, previousPosition, comparison.position, stepClientEvents))
            recheckOrderInputs()
            stepReports.add(
                mapOf(
                    "step" to step.name,
                    "inputKind" to step.inputKind,
                    "arguments" to step.arguments,
                    "observationCursors" to observationCursors,
                    "previousPosition" to previousPosition,
                    "position" to comparison.position,
                    "expectedResult" to step.expectedResult,
                    "expectedState" to step.expectedState,
                    "clientEvents" to stepClientEvents,
                    "issues" to comparison.issues,
                ),
            )
            // 응답과 세 사본이 확인되지 않았으면 다음 입력을 보내지 않는다.
            if (issues.isNotEmpty()) break
            previousPosition = checkNotNull(comparison.position)
        }
    }

    private fun runConcurrentInputs() {
        val selected = checkNotNull(concurrentScenario)
        runOrderInputs(listOf(selected.openingStep))
        if (issues.isNotEmpty()) return
        openingPosition = checkNotNull(comparison.position)
        stage = "CONCURRENT/연결 준비"
        Files.createDirectory(controlDirectory)
        val running = linkedMapOf<String, RunningVerificationCommand>()
        val barrier = ConcurrentClientBarrier(controlDirectory, runId, selected)
        val readyDeadline = deadline(CONCURRENT_READY_TIMEOUT_MS)
        for ((id, arguments) in selected.clientArguments) {
            val suffix = id.lowercase()
            val name = "$runId-client-$suffix"
            clients.add(name)
            running[id] =
                commandProcesses.start(
                    composeArguments(
                        listOf(
                            "run",
                            "--use-aliases",
                            "--name",
                            name,
                            "client-$suffix",
                            "com.exchange.core.api.trading.infrastructure.aeron.ConcurrentOrderClientKt",
                            "--cluster-env",
                        ) +
                            arguments,
                    ),
                )
            check(!running.getValue(id).failedToStart) { "$id 클라이언트 기동 실패" }
        }
        timed("concurrent-ready") {
            while (true) {
                for ((id, process) in running) {
                    // 실행 중 마지막 미완성 JSON 행은 다음 수집 때 읽는다.
                    val output = if (Files.exists(process.stdoutPath)) Files.readString(process.stdoutPath) else ""
                    pairClientEvents[id] = readFundingEvents(output.substringBeforeLast('\n', ""), issues)
                }
                val readiness = compareConcurrentClientReadiness(runId, pairClientEvents, selected)
                if (readiness.isEmpty()) break
                check(running.values.all { it.isAlive }) { "GO 전 클라이언트 종료: $readiness" }
                check(System.nanoTime() < readyDeadline) { "두 연결 준비 상한: $readiness" }
                Thread.sleep(20)
            }
        }
        validateConcurrentClients()
        check(issues.isEmpty()) { "두 클라이언트 격리·출력 검증 실패" }
        val gateIssues = barrier.release(pairClientEvents)
        issues.addAll(gateIssues)
        check(gateIssues.isEmpty()) { "공통 출발 조건 미충족" }
        pairAttempted = true
        stage = "CONCURRENT/동시 전송"
        // 두 프로세스는 이미 출발했다. A의 응답은 B의 시작 조건이 아니다.
        timed("concurrent-responses") {
            val responseDeadline = deadline(concurrentResponseTimeoutMs)
            for ((id, process) in running) {
                val result = process.await(remaining(responseDeadline))
                Files.writeString(reportDirectory.resolve("${id.lowercase()}-client.stdout.log"), result.stdout)
                Files.writeString(reportDirectory.resolve("${id.lowercase()}-client.stderr.log"), result.stderr)
                pairClientEvents[id] = readFundingEvents(result.stdout, issues)
                if (!result.succeeded) issues.add(FundingVerificationIssue("UNVERIFIABLE", "$id 결과 미확인: ${result.failure()}"))
            }
        }
        clientEvents = clientEvents + pairClientEvents.values.flatten()
        issues.addAll(compareConcurrentClientProgress(runId, pairClientEvents, checkNotNull(concurrentScenario)))
        stage = "CONCURRENT/두 위치의 세 사본"
        timed("concurrent-observations") {
            val observationDeadline = deadline(concurrentObservationTimeoutMs)
            while (true) {
                collectNodes(observationDeadline, requireRunning = true)
                val pair =
                    compareConcurrentOrderReplication(
                        runId,
                        events,
                        pairClientEvents,
                        checkNotNull(openingPosition),
                        pinnedPositions = pairPositions,
                        scenario = checkNotNull(concurrentScenario),
                    )
                if (pair.positions.size == 2 && pair.positions.values
                        .distinct()
                        .size == 2 &&
                    pairPositions == null
                ) {
                    pairPositions = pair.positions
                }
                issues.addAll(pair.issues.filter { it.category == "MISMATCH" })
                comparison = FundingReplicationComparison(pair.position, pair.issues)
                if (pair.orderedSteps.size == 2 &&
                    pair.orderedSteps.all { hasOrderObservations(it, pair.positions[it.name], checkNotNull(openingPosition)) }
                ) {
                    issues.addAll(pair.issues)
                    stepReports.add(
                        mapOf(
                            "step" to "CONCURRENT_${selected.name}",
                            "openingPosition" to openingPosition,
                            "positions" to pair.positions,
                            "expectedSteps" to pair.orderedSteps,
                            "clientEvents" to pairClientEvents,
                            "issues" to pair.issues,
                        ),
                    )
                    break
                }
                if (System.nanoTime() >= observationDeadline || events.values.flatten().any { it.path("kind").asString() == "ERROR" }) {
                    issues.addAll(pair.issues)
                    break
                }
                Thread.sleep(100)
            }
        }
    }

    private fun validateConcurrentClients() {
        val ids = mutableListOf<String>()
        for (clientId in listOf("A", "B")) {
            val name = "$runId-client-${clientId.lowercase()}"
            val result = command(dockerContainerInspection(name), CONCURRENT_CLIENT_INSPECTION_TIMEOUT_MS)
            must(result, "$clientId 실제 클라이언트 식별")
            Files.writeString(reportDirectory.resolve("$name.ready.inspect.json"), result.stdout)
            val client = mapper.readTree(result.stdout).path(0)
            ids.add(client.path("Id").asString())
            val node = inspections.getValue(0)
            check(client.path("State").path("Running").asBoolean()) { "$clientId GO 전 JVM이 종료됐습니다" }
            check(client.path("Image") == node.path("Image")) { "$clientId 실제 이미지가 노드와 다릅니다" }
            check(
                client
                    .path("NetworkSettings")
                    .path("Networks")
                    .propertyNames()
                    .toSet() ==
                    node
                        .path("NetworkSettings")
                        .path("Networks")
                        .propertyNames()
                        .toSet(),
            ) { "$clientId 전용 bridge 불일치" }
            check(
                client.path("HostConfig").path("NetworkMode").asString() != "host" &&
                    client.path("HostConfig").path("IpcMode").asString() != "host",
            ) { "$clientId 호스트 공유로 격리를 우회했습니다" }
        }
        check(ids.distinct().size == 2 && ids.none { it in nodeIds.values || it.isBlank() }) { "독립된 두 클라이언트 컨테이너가 아닙니다" }
    }

    private fun recheckConcurrentInputs() {
        if (!pairAttempted) return
        issues.addAll(compareConcurrentClientProgress(runId, pairClientEvents, checkNotNull(concurrentScenario)))
        val pair =
            compareConcurrentOrderReplication(
                runId,
                events,
                pairClientEvents,
                checkNotNull(openingPosition),
                pinnedPositions = pairPositions,
                scenario = checkNotNull(concurrentScenario),
            )
        issues.addAll(pair.issues)
        // P1만 검증하거나 A/B만 검증하면 계획 밖 입력을 놓칠 수 있으므로 최종 전체 기록을 대조한다.
        val checkpoints =
            listOf(checkNotNull(concurrentScenario).openingStep to openingPosition) +
                pair.orderedSteps.map { it to pairPositions?.get(it.name) }
        if (checkpoints.size != 3) {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "P1과 두 동시 주문의 최종 checkpoint 미확인"))
        }
        val coverageIssues = compareOrderReplicationCoverage(runId, events, checkpoints)
        issues.addAll(coverageIssues)
        stepReports.add(mapOf("step" to "FINAL_COVERAGE", "checkpoints" to checkpoints, "issues" to coverageIssues))
        comparison = FundingReplicationComparison(pair.position, issues.distinct())
    }

    private fun collectConcurrentClientOutputs() {
        if (!concurrentOrders || openingPosition == null) return
        for (id in listOf("A", "B")) {
            val name = "$runId-client-${id.lowercase()}"
            if (name !in clients) continue
            try {
                val logs = command(listOf("docker", "logs", name), 5_000)
                if (!logs.succeeded) issues.add(FundingVerificationIssue("COLLECTION", "$id 최종 클라이언트 출력 미확인: ${logs.failure()}"))
                Files.writeString(reportDirectory.resolve("${id.lowercase()}-client.final.stdout.log"), logs.stdout)
                Files.writeString(reportDirectory.resolve("${id.lowercase()}-client.final.stderr.log"), logs.stderr)
                val parsed = readFundingEvents(logs.stdout, issues)
                val previous = pairClientEvents[id].orEmpty()
                if (parsed.take(previous.size) !=
                    previous
                ) {
                    issues.add(FundingVerificationIssue("COLLECTION", "$id 기존 출력이 최종 로그의 접두부와 다릅니다"))
                }
                if (parsed.size >= previous.size) pairClientEvents[id] = parsed
            } catch (error: Exception) {
                issues.add(FundingVerificationIssue("COLLECTION", "$id 최종 클라이언트 출력 수집 실패: ${error.message}"))
            }
        }
    }

    private fun recheckOrderInputs() {
        for ((step, previousPosition, position, responses) in attemptedSteps) {
            issues.addAll(
                compareOrderReplication(runId, step, events, responses, previousPosition, pinnedPosition = position).issues,
            )
        }
        if (scenario != null) {
            issues.addAll(compareOrderReplicationCoverage(runId, events, attemptedSteps.map { it.step to it.position }))
        }
    }

    private fun hasOrderObservations(
        step: OrderReplicationStep,
        position: Long?,
        previousPosition: Long,
    ): Boolean =
        position != null && position > previousPosition &&
            (0..2).all { member ->
                events[member].orEmpty().any { event ->
                    event.path("kind").asString() == "APPLIED" && event.path("runId").asString() == runId &&
                        observedApplicationPosition(event) == position &&
                        step.identity.all { (key, value) -> event.path(key).asString() == value }
                }
            }

    private fun runClient(
        suffix: String,
        arguments: List<String>,
    ): VerificationCommandResult {
        val name = "$runId-client-$suffix"
        clients.add(name) // Compose 명령이 중간에 실패해도 실제 JVM을 추적한다.
        val result = compose(listOf("run", "--use-aliases", "--name", name, "client") + arguments, 100_000)
        Files.writeString(reportDirectory.resolve("$suffix-client.stdout.log"), result.stdout)
        Files.writeString(reportDirectory.resolve("$suffix-client.stderr.log"), result.stderr)
        return result
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
                val inspected = command(dockerContainerInspection(id), remaining(deadline))
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
        val position = leader?.let(::observedApplicationPosition) ?: return false
        return position > 0 &&
            (0..2).all { member ->
                events[member].orEmpty().any {
                    it.path("kind").asString() == "APPLIED" && it.path("runId").asString() == runId &&
                        it.path("preparationId").asString() == "P1" && observedApplicationPosition(it) == position
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
                for (name in clients) {
                    val client = command(dockerContainerInspection(name), remaining(deadline))
                    if (client.succeeded) {
                        Files.writeString(reportDirectory.resolve("$name.inspect.json"), client.stdout)
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
        // CLI 타임아웃이 컨테이너의 JVM을 취소하지는 않는다. 쓰기 주체를 먼저 닫는다.
        for (name in clients) {
            try {
                val inspection = command(dockerContainerInspection(name), 5_000)
                must(inspection, "$name 종료 전 식별")
                val before = mapper.readTree(inspection.stdout).path(0).path("State")
                if (before.path("Running").asBoolean()) {
                    issues.add(FundingVerificationIssue("UNVERIFIABLE", "$name client가 계속 실행 중이어서 중단합니다"))
                    must(command(listOf("docker", "stop", "--time", "10", name), 15_000), "$name 중단")
                } else if (before.path("ExitCode").asInt() != 0 || before.path("OOMKilled").asBoolean()) {
                    issues.add(FundingVerificationIssue("UNVERIFIABLE", "$name client 비정상 종료: $before"))
                }
                val closed = command(dockerContainerInspection(name), 5_000)
                must(closed, "$name 종료 확인")
                Files.writeString(reportDirectory.resolve("$name.stopped.inspect.json"), closed.stdout)
                check(
                    !mapper
                        .readTree(closed.stdout)
                        .path(0)
                        .path("State")
                        .path("Running")
                        .asBoolean(),
                ) { "$name JVM 종료 미확인" }
            } catch (error: Exception) {
                issues.add(FundingVerificationIssue("CLEANUP", "$name client 종료 확인 실패: ${error.message}"))
            }
        }
        collectConcurrentClientOutputs()
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
                    if (stoppedEvent == null || !stoppedEvent.path("closed").asBoolean() ||
                        !stoppedEvent.path("observationDrained").asBoolean() ||
                        stoppedEvent.hasNonNull("failure")
                    ) {
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
                closeMarketDatabase()
                val removed = compose(listOf("down", "--remove-orphans", "--timeout", "1"), 15_000)
                if (!removed.succeeded) issues.add(FundingVerificationIssue("CLEANUP", "컨테이너·네트워크 정리 실패: ${removed.failure()}"))
            }
        }
    }

    private fun writeReport() {
        val report =
            linkedMapOf(
                "runId" to runId,
                "scenario" to (scenario?.name ?: concurrentScenario?.let { "CONCURRENT_${it.name}" } ?: "FUNDING"),
                "image" to image,
                "imageBuilt" to buildImage,
                "marketConfiguration" to expectedConfiguration,
                "marketConfigurationFixture" to if (usesMarketDatabase) "MULTI_MARKET_DOCUMENT" else null,
                "expectedMarkets" to if (usesMarketDatabase) expectedMultiMarketSettings() else null,
                "steps" to stepReports,
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
                        "clientProcess" to 100_000,
                        "clientConnect" to 60_000,
                        "clientSend" to 5_000,
                        "clientResult" to 5_000,
                        "observations" to 15_000,
                        "stop" to 15_000,
                        "records" to 15_000,
                        "cleanup" to 15_000,
                        "concurrentReady" to if (concurrentOrders) CONCURRENT_READY_TIMEOUT_MS else null,
                        "concurrentClientInspect" to if (concurrentOrders) CONCURRENT_CLIENT_INSPECTION_TIMEOUT_MS else null,
                        "concurrentResponses" to if (concurrentOrders) concurrentResponseTimeoutMs else null,
                        "concurrentObservations" to if (concurrentOrders) concurrentObservationTimeoutMs else null,
                        // READY 전 실패하면 실제 클라이언트 설정을 관찰하지 못했으므로 null이다.
                        "concurrentClientGoById" to
                            if (concurrentOrders) {
                                listOf("A", "B").associateWith { id ->
                                    pairClientEvents[id]
                                        ?.firstOrNull { it.path("kind").asString() == "READY" }
                                        ?.get("gateTimeoutMs")
                                        ?.asLong()
                                }
                            } else {
                                null
                            },
                    ),
                "durationMs" to timings,
                "issues" to comparison.issues,
                "commands" to commandProcesses.records,
                "concurrentPositions" to pairPositions,
                "concurrentClientEvents" to pairClientEvents,
                "observations" to events,
                "clientEvents" to clientEvents,
            )
        Files.writeString(reportDirectory.resolve("result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        Files.writeString(
            reportDirectory.resolve("summary.txt"),
            buildString {
                appendLine("${if (comparison.succeeded) "PASS" else "FAIL"} $runId; appliedPosition=${comparison.position}")
                appendLine(
                    "${scenario?.name ?: if (concurrentOrders) "두 클라이언트 동시 주문" else "P1 한 건"}, 세 독립 Docker JVM. 복구·장애 전환·성능 검증은 포함하지 않습니다.",
                )
                appendLine("입력 시도=$inputAttempted; 정상 종료 확인=$cleanStop; 기록 볼륨 삭제는 요청하지 않았습니다.")
                comparison.issues.forEach { appendLine("${it.category}: ${it.message}") }
                appendLine("durationMs=$timings")
            },
        )
    }

    private fun compose(
        arguments: List<String>,
        timeoutMs: Long,
    ): VerificationCommandResult = command(composeArguments(arguments), timeoutMs)

    private fun composeArguments(arguments: List<String>) =
        listOf("docker", "compose", "--project-name", runId, "--file", composeFile.toString()) + arguments

    private fun command(
        arguments: List<String>,
        timeoutMs: Long,
    ): VerificationCommandResult = commandProcesses.start(arguments).await(timeoutMs)

    private fun must(
        result: VerificationCommandResult,
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
}
