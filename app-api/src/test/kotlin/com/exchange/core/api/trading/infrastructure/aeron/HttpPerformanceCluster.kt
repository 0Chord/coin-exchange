package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

internal const val HTTP_PERFORMANCE_MARKET_DOCUMENT =
    """[{"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,"priceTick":1,"quantityStep":100000000,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,"makerPartsPerMillion":1000,"takerPartsPerMillion":2000}}]"""

internal fun httpPerformanceClusterServices(nodeOnly: Boolean): List<String> =
    listOf("node0", "node1", "node2") + if (nodeOnly) emptyList() else listOf("gateway")

internal fun httpPerformanceCombinedClientStartArguments(
    runId: String,
    control: Path,
): List<String> =
    listOf(
        "run",
        "-d",
        "--name",
        "$runId-direct",
        "--use-aliases",
        "--entrypoint",
        "sleep",
        "--volume",
        "$control:/direct-control",
        "--env",
        "FUNDING_HOST=client",
        "client",
        "1800",
    )

internal fun httpPerformanceCombinedClientExecArguments(
    container: String,
    mainClass: String,
    args: List<String>,
): List<String> {
    require(mainClass.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) { "잘못된 직접 client main class" }
    require(args.none { '\u0000' in it }) { "직접 client 인자에 NUL 문자를 사용할 수 없습니다" }
    return listOf(
        "docker",
        "exec",
        container,
        "java",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
        "-cp",
        "/app/test-classes:/app/classes:/app/lib/*",
        mainClass,
    ) + args
}

internal fun httpPerformanceCombinedClientResourceArguments(container: String): List<String> =
    listOf("docker", "update", "--cpus=2", "--memory=2g", "--memory-swap=2g", container)

internal fun httpPerformanceCombinedClientInspectArguments(container: String): List<String> =
    listOf(
        "docker",
        "inspect",
        "--format",
        """{"Id":{{json .Id}},"Image":{{json .Image}},"State":{{json .State}},"HostConfig":{{json .HostConfig}},"NetworkSettings":{{json .NetworkSettings}}}""",
        container,
    )

/** 원문 tree를 이력에 붙잡지 않고 이미 보존한 노드 파일의 식별·위치·지문만 보관한다. */
internal fun httpPerformanceCheckpointHistory(
    label: String,
    checkpoints: Map<Int, JsonNode>,
    references: Map<Int, JsonNode>,
): Map<String, Any?> =
    mapOf(
        "label" to label,
        "checkpointFile" to "checkpoint-$label.json",
        "nodes" to
            checkpoints.mapValues { (member, checkpoint) ->
                val reference = references.getValue(member)
                mapOf(
                    "position" to checkpoint.path("applied").path("position").asLong(),
                    "label" to checkpoint.path("label").asString(),
                    "artifactFile" to "control/${reference.path("artifactFile").asString()}",
                    "artifactBytes" to reference.path("artifactBytes").asLong(),
                    "artifactSha256" to reference.path("artifactSha256").asString(),
                )
            },
    )

/** 이번 실행에서 새로 만든 자원만 소유한다. 일반 제품 실행에는 관찰 API나 gate를 추가하지 않는다. */
internal class HttpPerformanceCluster(
    private val project: Path,
    private val distribution: Path,
    val runId: String,
    val reports: Path,
    private val sampleRuntimeCounters: Boolean = false,
    private val stageDiagnostics: Boolean = false,
    private val detailedTracing: Boolean = true,
    private val markerIdlePolicy: HttpPerformanceMarkerIdlePolicy = HttpPerformanceMarkerIdlePolicy.SLEEP_1MS,
    private val cpuPeriodUs: Int? = null,
    private val gatewayCpuCount: Int = 2,
    private val httpBoundaryOrders: Int? = null,
    private val preserveNodeRecordings: Boolean = false,
    private val nodeHeapDiagnostics: Boolean = false,
    private val evidenceTimeBudget: HttpPerformanceEvidenceTimeBudget = HttpPerformanceEvidenceTimeBudget.STANDARD,
    private val markerPlan: HttpPerformanceMarkerPlan,
    private val nodeOnly: Boolean = false,
    private val remoteMarkerEndpoint: String? = null,
    private val marketDocument: String = HTTP_PERFORMANCE_MARKET_DOCUMENT,
    private val nodeMaxHeapMb: Int = 768,
    onMarker: (HttpPerformanceCompletion, Long) -> Unit,
) : AutoCloseable {
    private val mapper = jacksonObjectMapper()
    private val archiveSyncLevel = System.getProperty("performance.archiveSyncLevel", "2").toInt().also { require(it in 0..2) }
    private val control = reports.resolve("control")
    private val image = "exchange-http-performance:$runId"
    private val environment =
        mutableMapOf(
            "FUNDING_IMAGE" to image,
            "FUNDING_RUN_ID" to runId,
            "PERFORMANCE_CONTROL_HOST_DIRECTORY" to control.toAbsolutePath().toString(),
            "PERFORMANCE_COUNTER_SAMPLING" to sampleRuntimeCounters.toString(),
            "PERFORMANCE_DETAILED_TRACING" to detailedTracing.toString(),
            "PERFORMANCE_HTTP_BOUNDARIES" to (httpBoundaryOrders != null).toString(),
            "PERFORMANCE_HTTP_BOUNDARY_ORDERS" to (httpBoundaryOrders ?: 0).toString(),
            "PERFORMANCE_MARKER_IDLE_POLICY" to markerIdlePolicy.name,
            "PERFORMANCE_MARKER_PLAN_WARMUP" to markerPlan.warmup.toString(),
            "PERFORMANCE_MARKER_PLAN_ORDERS" to markerPlan.orders.toString(),
            "PERFORMANCE_EVIDENCE_TIME_BUDGET" to evidenceTimeBudget.name,
            "PERFORMANCE_NODE_MAX_HEAP" to "${nodeMaxHeapMb}m",
            "PERFORMANCE_ARCHIVE_SYNC_LEVEL" to archiveSyncLevel.toString(),
            "PERFORMANCE_GATEWAY_MAIN" to
                if (stageDiagnostics) {
                    "com.exchange.core.api.trading.infrastructure.aeron.HttpPerformanceGatewayKt"
                } else {
                    "com.exchange.core.TradingGatewayApplicationKt"
                },
        )
    private val commands = VerificationCommands(project, reports, environment)
    private val password = "performance142-${UUID.randomUUID()}"
    private val events = linkedMapOf<Int, List<JsonNode>>()
    private val nodes = linkedMapOf<Int, String>()
    private val nodeInspections = linkedMapOf<Int, JsonNode>()
    private val checkpointHistory = mutableListOf<Map<String, Any?>>()
    private val infrastructureErrors = mutableListOf<String>()
    private val jvmProfiles = linkedMapOf<String, JvmProfile>()
    private val jvmProfileErrors = mutableListOf<String>()
    private val clients = mutableListOf<String>()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val receiver: HttpPerformanceMarkerReceiver
    private var database: MarketConfigurationDatabase? = null
    private var network: String? = null
    private var composeAttempted = false
    private var imageBuildAttempted = false
    private var gateway: String? = null
    private var gatewayInspection: JsonNode? = null
    private var combinedDirectClient: String? = null
    private var combinedDirectClientInspection: JsonNode? = null
    private var dockerResources: JsonNode? = null
    private var httpBase: URI? = null
    private var expectedConfiguration: ExpectedMarketConfiguration? = null
    private var roleBaseline: Map<Int, Pair<String, Long>>? = null
    private var closed = false
    private var gatewayWindow: JsonNode? = null
    private var archiveRetention: HttpPerformanceArchiveRetentionResult? = null
    private var remainingRecordingVolumes: List<String>? = null
    private val nodeHeapGate = if (nodeHeapDiagnostics) HttpPerformanceNodeHeapGate() else null
    private val nodeHeapHistory = mutableListOf<Map<String, Any?>>()

    init {
        require(marketDocument.isNotBlank()) { "마켓 설정 원문이 비어 있습니다" }
        require(nodeMaxHeapMb in 768..1536) { "노드 최대 heap은 768..1536 MiB 범위여야 합니다" }
        require(!nodeOnly || (!stageDiagnostics && httpBoundaryOrders == null)) { "node-only 실행은 HTTP/Gateway 진단을 사용할 수 없습니다" }
        require(remoteMarkerEndpoint == null || nodeOnly) { "원격 완료 표식 수신기는 node-only 실행에서만 사용합니다" }
        require(
            remoteMarkerEndpoint == null ||
                (
                    remoteMarkerEndpoint.matches(Regex("client:[1-9][0-9]{0,4}")) &&
                        remoteMarkerEndpoint.substringAfter(':').toInt() <= 65_535
                ),
        ) { "원격 완료 표식 수신기는 client alias와 유효한 port를 사용해야 합니다" }
        require(httpBoundaryOrders == null || (stageDiagnostics && httpBoundaryOrders in 1..2000))
        require(project.isAbsolute && distribution.isAbsolute && reports.isAbsolute)
        require(runId.matches(Regex("[a-z0-9-]{1,64}")))
        require(markerPlan.runId == runId) { "완료 표식 계획과 cluster 실행이 다릅니다" }
        evidenceTimeBudget.requireForOrders(markerPlan.orders)
        require(cpuPeriodUs == null || cpuPeriodUs in setOf(10_000, 100_000))
        require(gatewayCpuCount in setOf(2, 8) && (gatewayCpuCount == 2 || cpuPeriodUs != null))
        if (cpuPeriodUs != null) {
            environment["PERFORMANCE_CPU_PERIOD_US"] = cpuPeriodUs.toString()
            environment["PERFORMANCE_CPU_QUOTA_US"] = (cpuPeriodUs * gatewayCpuCount).toString()
        }
        Files.createDirectories(control)
        if (nodeHeapDiagnostics) HttpPerformanceNodeHeapControl.enable(control, runId)
        receiver =
            HttpPerformanceMarkerReceiver(
                runId,
                reports.resolve("observer"),
                onMarker,
                idlePolicy = markerIdlePolicy,
                markerPlan = markerPlan,
            )
        environment["PERFORMANCE_MARKER_ENDPOINT"] = remoteMarkerEndpoint ?: "host.docker.internal:${receiver.port}"
    }

    val orderUri: URI get() = checkNotNull(httpBase).resolve("/api/trading/markets/BTC-USDT/orders")

    fun start() {
        must(command(listOf("docker", "version"), 15_000), "Docker 준비")
        must(compose(listOf("version"), 15_000), "Compose 준비")
        for (arguments in listOf(
            listOf("docker", "ps", "--all", "--quiet", "--filter", "label=com.docker.compose.project=$runId"),
            listOf("docker", "volume", "ls", "--quiet", "--filter", "label=com.docker.compose.project=$runId"),
        )) {
            val existing = command(arguments, 15_000)
            must(existing, "같은 실행 ID의 기존 자원 검사")
            check(existing.stdout.isBlank()) { "같은 실행 ID의 기존 Docker 자원이 있습니다. 재사용하거나 삭제하지 않습니다: $runId" }
        }
        val resources =
            command(
                listOf(
                    "docker",
                    "info",
                    "--format",
                    """{"serverVersion":{{json .ServerVersion}},"cpus":{{json .NCPU}},"memoryBytes":{{json .MemTotal}},"architecture":{{json .Architecture}},"os":{{json .OperatingSystem}},"storageDriver":{{json .Driver}},"dockerRoot":{{json .DockerRootDir}}}""",
                ),
                15_000,
            )
        must(resources, "Docker VM 자원 검사")
        dockerResources = mapper.readTree(resources.stdout)
        check(Files.isDirectory(distribution.resolve("lib"))) { "성능 배포 산출물이 없습니다: $distribution" }
        val existingImage = command(listOf("docker", "image", "ls", "--quiet", "--filter", "reference=$image"), 15_000)
        must(existingImage, "같은 실행 ID의 기존 이미지 검사")
        check(existingImage.stdout.isBlank()) { "같은 실행 ID의 기존 이미지는 재사용하거나 삭제하지 않습니다: $image" }
        imageBuildAttempted = true
        must(
            command(
                listOf(
                    "docker",
                    "build",
                    "-f",
                    project.resolve("src/test/docker/aeron-funding/Dockerfile").toString(),
                    "-t",
                    image,
                    distribution.toString(),
                ),
                300_000,
            ),
            "성능 이미지 준비",
        )
        val db = MarketConfigurationDatabase(password)
        database = db
        val settings = db.register(marketDocument)
        environment.putAll(settings)
        environment["FUNDING_MARKET_DB_URL"] = db.containerJdbcUrl
        expectedConfiguration =
            ExpectedMarketConfiguration(
                settings.getValue("FUNDING_MARKET_CONFIG_VERSION").toLong(),
                settings.getValue("FUNDING_MARKET_CONFIG_SHA256"),
            )
        composeAttempted = true
        must(compose(listOf("create") + httpPerformanceClusterServices(nodeOnly), 30_000), "독립 거래 실행 컨테이너 생성")
        for (member in 0..2) nodes[member] = containerId("node$member")
        if (!nodeOnly) gateway = containerId("gateway")
        for ((member, id) in nodes) nodeInspections[member] = inspect(id)
        val fundingNetworks =
            nodeInspections.values.map { node ->
                node
                    .path("NetworkSettings")
                    .path("Networks")
                    .propertyNames()
                    .toSet()
            }
        check(fundingNetworks.all { it == setOf("${runId}_funding", "${runId}_performance-observer") }) { "전용 거래·관찰 네트워크 구성이 다릅니다" }
        network = "${runId}_funding"
        must(
            command(listOf("docker", "network", "connect", "--alias", "market-db", checkNotNull(network), db.containerId), 15_000),
            "마켓 DB 연결",
        )
        if (nodeOnly) {
            val name = "$runId-direct"
            clients.add(name)
            must(compose(httpPerformanceCombinedClientStartArguments(runId, control), 30_000), "직접 client DNS·제어 디렉터리 준비")
            val initial = inspectCombinedDirectClient(name)
            val id = initial.path("Id").asString().also { check(it.matches(Regex("[a-f0-9]{64}"))) }
            must(command(httpPerformanceCombinedClientResourceArguments(id), 15_000), "직접 client CPU·메모리 제한 설정")
            val direct = inspectCombinedDirectClient(id)
            check(direct.path("State").path("Running").asBoolean()) { "직접 client 준비 컨테이너가 실행되지 않았습니다" }
            check(direct.path("HostConfig").path("NanoCpus").asLong() == 2_000_000_000L) { "직접 client CPU 제한이 다릅니다" }
            check(direct.path("HostConfig").path("Memory").asLong() == 2L * 1024 * 1024 * 1024) { "직접 client 메모리 제한이 다릅니다" }
            check(direct.path("HostConfig").path("MemorySwap").asLong() == 2L * 1024 * 1024 * 1024) { "직접 client 메모리·스왑 제한이 다릅니다" }
            combinedDirectClient = id
            combinedDirectClientInspection = direct
            writeJson("combined-direct-client-inspection.json", combinedDirectClientInspection)
        }
        must(compose(listOf("up", "-d", "node0", "node1", "node2"), 60_000), "세 성능 노드 기동")
        try {
            await("세 노드 동일 설정·sync=$archiveSyncLevel·리더 준비", 60_000) {
                collectNodes()
                ready()
            }
        } catch (error: Throwable) {
            nodes.values.forEach { id -> command(listOf("docker", "exec", id, "jcmd", "1", "Thread.print"), 10_000) }
            throw error
        }
        assertIsolation()
        if (nodeOnly) {
            assertHealthy()
            return
        }
        must(compose(listOf("up", "-d", "gateway"), 30_000), "제품 HTTP gateway 기동")
        val inspected = inspect(checkNotNull(gateway))
        gatewayInspection = inspected
        val binding =
            inspected
                .path("NetworkSettings")
                .path("Ports")
                .path("8080/tcp")
                .toList()
                .single()
        check(binding.path("HostIp").asString() == "127.0.0.1") { "HTTP 포트가 loopback에만 게시되지 않았습니다" }
        val port = binding.path("HostPort").asString().toInt()
        check(port in 1..65_535)
        httpBase = URI.create("http://127.0.0.1:$port")
        await("제품 HTTP 서버 준비", 60_000) {
            runCatching {
                http
                    .send(
                        HttpRequest
                            .newBuilder(checkNotNull(httpBase).resolve("/actuator/health"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.discarding(),
                    ).statusCode() == 200
            }.getOrDefault(false)
        }
        check(inspected.path("Image") == nodeInspections.values.first().path("Image")) { "gateway와 거래 노드의 실제 이미지가 다릅니다" }
        check(inspected.path("Id").asString() !in nodes.values)
        assertHealthy()
    }

    /** The caller owns the small stdout summary and writes raw evidence under /direct-control. */
    fun runCombinedDirectClient(
        mainClass: String,
        args: List<String>,
        timeoutMs: Long,
    ): VerificationCommandResult {
        check(nodeOnly && !closed) { "직접 결합 client는 실행 중인 node-only cluster에서만 사용할 수 있습니다" }
        require(timeoutMs in 1..1_800_000) { "직접 결합 client 실행 기한이 범위를 벗어났습니다" }
        val container = checkNotNull(combinedDirectClient) { "직접 client 준비 컨테이너를 먼저 시작해야 합니다" }
        assertHealthy()
        return command(httpPerformanceCombinedClientExecArguments(container, mainClass, args), timeoutMs)
    }

    fun fund(
        id: String,
        user: String,
        asset: String,
        amount: Long,
    ) {
        require(amount > 0)
        val name = "$runId-fund-${clients.size}"
        clients.add(name)
        val result =
            compose(
                listOf(
                    "run",
                    "--name",
                    name,
                    "--env",
                    "FUNDING_HOST=$name",
                    "client",
                    "com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingClientKt",
                    "--cluster-env",
                    id,
                    user,
                    asset,
                    amount.toString(),
                    "OPEN-$id",
                    "2026-10-26T00:00:00Z",
                ),
                100_000,
            )
        must(result, "$id 개발용 엔진 시작 자금 준비")
        val parseIssues = mutableListOf<FundingVerificationIssue>()
        val results = readFundingEvents(result.stdout, parseIssues).filter { it.path("kind").asString() == "RESULT_RECEIVED" }
        check(parseIssues.isEmpty() && results.size == 1) { "$id 자금 준비 응답을 확인하지 못했습니다" }
        val prepared = results.single().path("result")
        check(
            prepared.path("preparationId").asString() == id && prepared.path("userId").asString() == user &&
                prepared.path("assetId").asString() == asset && prepared.path("amount").asLong() == amount &&
                prepared.path("reason").isMissingNode,
        ) { "$id 엔진 시작 자금 준비가 거절됐거나 다른 요청 결과입니다" }
    }

    /** 측정 BUY만 별도 client JVM에서 직접 보낸다. 실패 종료여도 존재하는 raw 결과를 반환한다. */
    fun runDirectLoad(
        rate: Int,
        count: Int,
        prefix: String,
        user: String,
        maxInFlight: Int,
    ): JsonNode {
        val workload =
            parseDirectAeronCapacityClientArguments(arrayOf(rate.toString(), count.toString(), prefix, user, maxInFlight.toString()))
        val name = "$runId-direct-${clients.size}"
        clients.add(name)
        val result =
            compose(
                listOf(
                    "run",
                    "--name",
                    name,
                    "--env",
                    "FUNDING_HOST=$name",
                    "--env",
                    "FUNDING_RUN_ROOT=/tmp/$name",
                    "--env",
                    "JAVA_TOOL_OPTIONS=-Xms256m -Xmx2g",
                    "client",
                    "com.exchange.core.api.trading.infrastructure.aeron.DirectAeronCapacityClientKt",
                    rate.toString(),
                    count.toString(),
                    prefix,
                    user,
                    maxInFlight.toString(),
                ),
                TimeUnit.NANOSECONDS.toMillis(workload.windowNs) + 150_000,
            )
        var reportFailure: String? = null
        val report =
            try {
                readDirectAeronCapacityClientReport(result.stdout).also {
                    check(it.path("runId").asString() == runId) { "직접 Aeron client 실행 ID 불일치" }
                }
            } catch (failure: Exception) {
                reportFailure = failure.stackTraceToString()
                null
            }
        return mapper.valueToTree(
            mapOf(
                "containerName" to name,
                "processSucceeded" to result.succeeded,
                "exitCode" to result.exitCode,
                "timedOut" to result.timedOut,
                "processFailure" to if (result.succeeded) null else result.failure(),
                "reportFailure" to reportFailure,
                "report" to report,
            ),
        )
    }

    /** Long mode keeps raw JSONL on an owned host bind before any container cleanup. */
    fun runDirectLoadSustained(
        rate: Int,
        seconds: Int,
        prefix: String,
        user: String,
        maxInFlight: Int,
    ): JsonNode {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("direct sustained bridge interrupted before start")
        val workload =
            parseDirectAeronCapacityClientArguments(
                arrayOf("--sustained", "--rate=$rate", "--seconds=$seconds", prefix, user, maxInFlight.toString()),
            )
        val name = "$runId-direct-${clients.size}"
        clients.add(name)
        val rawDirectory = Files.createDirectory(control.resolve("direct-$name")).toAbsolutePath().normalize()
        val rawFile = rawDirectory.resolve(DIRECT_AERON_SUSTAINED_RAW_NAME)
        val relativeRaw =
            reports
                .toAbsolutePath()
                .normalize()
                .relativize(rawFile)
                .toString()
        val evidenceFile = "$name-raw-evidence.json"
        writeJson(
            evidenceFile,
            mapOf("containerName" to name, "runId" to runId, "rawArtifactFile" to relativeRaw, "status" to "PLANNED", "verified" to false),
        )
        val commandStartedNs = System.nanoTime()
        val result =
            compose(
                directAeronSustainedComposeArguments(name, rawDirectory, workload),
                TimeUnit.NANOSECONDS.toMillis(workload.windowNs) + 150_000,
            )
        val commandReturnedNs = System.nanoTime()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("direct sustained bridge interrupted after command")
        var reportFailure: String? = null
        val report =
            try {
                DirectAeronSustainedRawFiles.readReport(result.stdout, runId, workload)
            } catch (failure: Exception) {
                reportFailure = failure.stackTraceToString()
                null
            }
        var rawFailure: String? = null
        val rawEvidence =
            if (report == null) {
                rawFailure = "client summary unavailable; owned raw candidate is preserved"
                null
            } else {
                try {
                    val reference = DirectAeronSustainedRawFiles.resolve(rawDirectory, report, runId, workload)
                    DirectAeronSustainedRawFiles.scan(reference, workload).fields(reports)
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw failure
                } catch (failure: Exception) {
                    rawFailure = failure.stackTraceToString()
                    null
                }
            }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("direct sustained bridge interrupted after evidence")
        val candidatePresent = Files.isRegularFile(rawFile, LinkOption.NOFOLLOW_LINKS)
        val wrapper =
            mapOf(
                "containerName" to name,
                "runId" to runId,
                "processSucceeded" to result.succeeded,
                "exitCode" to result.exitCode,
                "timedOut" to result.timedOut,
                "processFailure" to if (result.succeeded) null else result.failure(),
                "reportFailure" to reportFailure,
                "rawFailure" to rawFailure,
                "rawArtifactFile" to relativeRaw,
                "rawArtifactPresent" to candidatePresent,
                "rawArtifactBytesObserved" to if (candidatePresent) Files.size(rawFile) else null,
                "rawEvidence" to rawEvidence,
                "report" to report,
                "parentClientCommandStartedNs" to commandStartedNs,
                "parentClientCommandReturnedNs" to commandReturnedNs,
                "parentCommandClock" to
                    "parent System.nanoTime only; includes launch/client window/drain/raw output; never subtract client clock",
            )
        writeJson(evidenceFile, wrapper)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("direct sustained bridge interrupted before return")
        return mapper.valueToTree(wrapper)
    }

    /** 측정 시작 전 또는 부하·배출이 끝난 뒤에만 전체 상태를 수집한다. */
    fun checkpoint(
        label: String,
        timeoutMs: Long = 30_000,
        verifyObservation: Boolean = true,
    ): Map<Int, JsonNode> {
        require(label.matches(Regex("[a-zA-Z0-9-]{1,70}")))
        require(timeoutMs in 1..120_000)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var attempt = 0
        var result = emptyMap<Int, JsonNode>()
        while (true) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            check(remainingMs > 0) { "$label 세 노드의 후속 적용 위치 수렴 제한 시간 초과" }
            result = captureCheckpoint("$label-${UUID.randomUUID().toString().take(8)}", remainingMs)
            writeJson("checkpoint-$label-attempt-${attempt++}.json", result)
            if (verifyObservation) {
                result.forEach { (member, checkpoint) ->
                    check(checkpoint.path("collectorFailure").isNull) { "node$member: ${checkpoint.path("collectorFailure")}" }
                    val summary = checkpoint.path("markerSummary")
                    check(
                        summary.path("queueOverflow").asLong() == 0L && summary.path("failures").isEmpty,
                    ) { "node$member 완료 표식 송신 오류: $summary" }
                }
            }
            val positions = result.values.map { it.path("applied").path("position").asLong() }
            if (positions.distinct().size == 1) {
                val states = result.values.map { it.path("applied").path("state") to it.path("trading") }
                check(states.distinct().size == 1) { "$label: 같은 적용 위치에서 세 노드의 잔고·예약·원장·요청·완료·매칭·주문장이 다릅니다" }
                // 수집 직후 다음 실행이 진행됐으면 세 노드가 같은 후속 위치에 도달한 뒤 다시 수집한다.
                if (!verifyObservation ||
                    result.values.all {
                        it.path("markerSummary").path("sentCount").asInt() == it.path("emittedMarkerCount").asInt()
                    }
                ) {
                    break
                }
            }
            Thread.sleep(250)
        }
        writeJson("checkpoint-$label.json", result)
        if (!verifyObservation) {
            checkpointHistory.add(
                mapOf(
                    "label" to label,
                    "observationVerification" to false,
                    "purpose" to "financial state preserved after invalid observation",
                ),
            )
            return result
        }
        for ((member, checkpoint) in result) {
            roleBaseline?.get(member)?.let { baseline ->
                check(baseline == (checkpoint.path("role").asString() to checkpoint.path("roleChangeCount").asLong())) {
                    "측정 중 node$member 리더십 또는 역할 변경"
                }
            }
            check(checkpoint.path("collectorFailure").isNull) { "node$member: ${checkpoint.path("collectorFailure")}" }
            val summary = checkpoint.path("markerSummary")
            check(summary.path("queueOverflow").asLong() == 0L && summary.path("failures").isEmpty) { "node$member 완료 표식 송신 오류: $summary" }
            check(summary.path("offered").asLong() == summary.path("sentCount").asLong()) { "node$member 완료 표식 배출 미완료" }
            check(summary.path("sentCount").asInt() == checkpoint.path("emittedMarkerCount").asInt()) { "node$member 표식 생성·송신 개수 불일치" }
        }
        if (remoteMarkerEndpoint != null) {
            val references =
                result.mapValues { (member, checkpoint) ->
                    events.getValue(member).last {
                        it.path("kind").asString() == "PERFORMANCE_CHECKPOINT" && it.path("label") == checkpoint.path("label")
                    }
                }
            checkpointHistory.add(
                httpPerformanceCheckpointHistory(label, result, references) +
                    mapOf("observationVerification" to false, "nodeObservationVerification" to true, "externalMarkerProofRequired" to true),
            )
            assertHealthy()
            return result
        }
        try {
            await("$label 송신·수신 표식 대조", 10_000) { markerDifferences(result).isEmpty() || receiver.errors().isNotEmpty() }
        } catch (error: IllegalStateException) {
            infrastructureErrors.addAll(markerDifferences(result))
            throw IllegalStateException("$label: ${observerErrors().joinToString("; ")}", error)
        }
        val markerErrors = observerErrors() + markerDifferences(result)
        check(markerErrors.isEmpty()) { markerErrors.joinToString("; ") }
        val references =
            result.mapValues { (member, checkpoint) ->
                events.getValue(member).last {
                    it.path("kind").asString() == "PERFORMANCE_CHECKPOINT" && it.path("label") == checkpoint.path("label")
                }
            }
        checkpointHistory.add(httpPerformanceCheckpointHistory(label, result, references) + ("markerErrors" to markerErrors))
        assertHealthy()
        return result
    }

    val checkpointCaptureTimeoutCeilingMs: Long get() = evidenceTimeBudget.checkpointCaptureMaxMs

    /** Parallel file-reference API; the existing tree checkpoint path remains available as an oracle. */
    fun checkpointFiles(
        label: String,
        timeoutMs: Long = 30_000,
        verifyObservation: Boolean = true,
    ): Map<Int, HttpPerformanceCheckpointFileReference> {
        evidenceTimeBudget.requireCheckpointTimeout(timeoutMs)
        val captured =
            collectHttpPerformanceCheckpointFiles(
                label,
                timeoutMs,
                verifyObservation,
                capture = ::captureCheckpointFiles,
                preserve = { name, value -> writeJson(name, value) },
                roleBaseline = roleBaseline,
                timeBudget = evidenceTimeBudget,
            )
        if (!verifyObservation) {
            checkpointHistory.add(
                httpPerformanceCheckpointFileWrapper(label, captured.references, captured.observations, false) +
                    mapOf(
                        "checkpointFile" to "checkpoint-files-$label.json",
                        "purpose" to "financial state preserved after invalid observation",
                    ),
            )
            return captured.references
        }
        if (remoteMarkerEndpoint != null) {
            checkpointHistory.add(
                httpPerformanceCheckpointFileWrapper(label, captured.references, captured.observations, false) +
                    mapOf(
                        "checkpointFile" to "checkpoint-files-$label.json",
                        "nodeObservationVerification" to true,
                        "externalMarkerProofRequired" to true,
                    ),
            )
            assertHealthy()
            return captured.references
        }
        val markerFiles = readHttpPerformanceSentMarkerFiles(captured.references, markerPlan.identitiesPerMember)
        try {
            await("$label 송신·수신 표식 대조", 10_000) { markerDifferencesFiles(markerFiles).isEmpty() || receiver.errors().isNotEmpty() }
        } catch (error: IllegalStateException) {
            infrastructureErrors.addAll(markerDifferencesFiles(markerFiles))
            throw IllegalStateException("$label: ${observerErrors().joinToString("; ")}", error)
        }
        val markerErrors = observerErrors() + markerDifferencesFiles(markerFiles)
        check(markerErrors.isEmpty()) { markerErrors.joinToString("; ") }
        checkpointHistory.add(
            httpPerformanceCheckpointFileWrapper(label, captured.references, captured.observations, true) +
                mapOf("checkpointFile" to "checkpoint-files-$label.json", "markerErrors" to markerErrors),
        )
        assertHealthy()
        return captured.references
    }

    private fun captureCheckpointFiles(
        token: String,
        timeoutMs: Long,
    ): Map<Int, HttpPerformanceCheckpointFileReference> {
        val temporary = control.resolve("checkpoint.tmp")
        Files.writeString(temporary, token)
        Files.move(temporary, control.resolve("checkpoint.request"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val result = linkedMapOf<Int, HttpPerformanceCheckpointFileReference>()
        val captureAvailable = {
            collectNodes()
            for (member in 0..2) {
                if (member !in result) {
                    events[member]
                        .orEmpty()
                        .lastOrNull {
                            it.path("kind").asString() == "PERFORMANCE_CHECKPOINT" && it.path("label").asString() == token
                        }?.let { reference ->
                            result[member] = HttpPerformanceCheckpointReader.resolve(control, reference, runId, member, token)
                        }
                }
            }
            result.size == 3
        }
        if (evidenceTimeBudget == HttpPerformanceEvidenceTimeBudget.STANDARD) {
            await("$token 세 노드 후속 체크포인트", timeoutMs, captureAvailable)
        } else {
            awaitHttpPerformanceCheckpointFileCapture("$token 세 노드 후속 체크포인트", timeoutMs, reports.toString(), predicate = captureAvailable)
        }
        return result
    }

    private fun captureCheckpoint(
        token: String,
        timeoutMs: Long,
    ): Map<Int, JsonNode> {
        val temporary = control.resolve("checkpoint.tmp")
        Files.writeString(temporary, token)
        Files.move(temporary, control.resolve("checkpoint.request"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val result = linkedMapOf<Int, JsonNode>()
        await("$token 세 노드 후속 체크포인트", timeoutMs) {
            collectNodes()
            for (member in 0..2) {
                if (member !in result) {
                    events[member]
                        .orEmpty()
                        .lastOrNull {
                            it.path("kind").asString() == "PERFORMANCE_CHECKPOINT" && it.path("label").asString() == token
                        }?.let { reference ->
                            result[member] = HttpPerformanceCheckpointFiles.read(control, reference, runId, member, token)
                        }
                }
            }
            result.size == 3
        }
        return result
    }

    /** Explicit intrusive run only: caller must have completed preparation and its financial checkpoint. */
    fun captureNodeHeapAfterPreparation(timeoutMs: Long = 30_000): Map<String, Any?> {
        val gate = checkNotNull(nodeHeapGate) { "nodeHeapDiagnostics opt-in is required" }
        return captureNodeHeap(gate.preparationAttempt(), timeoutMs)
    }

    /** Caller must have stopped submitting and completed its existing bounded marker drain first. */
    fun captureNodeHeapAfterLoadDrain(timeoutMs: Long = 30_000): Map<String, Any?> {
        val gate = checkNotNull(nodeHeapGate) { "nodeHeapDiagnostics opt-in is required" }
        val phase = gate.drainedAttempt()
        return captureNodeHeap(phase, timeoutMs)
    }

    private fun captureNodeHeap(
        phase: HttpPerformanceNodeHeapPhase,
        timeoutMs: Long,
    ): Map<String, Any?> {
        check(!closed && nodes.size == 3)
        require(timeoutMs in 1..60_000)
        val result = captureHttpPerformanceNodeHeapResponses(control, runId, phase, timeoutMs, checkNotNull(nodeHeapGate))
        var verified = result["status"] == "VERIFIED"
        try {
            Files
                .newOutputStream(
                    reports.resolve("node-heap-${phase.name.lowercase()}.json"),
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE,
                ).use { mapper.writeValue(it, result) }
        } catch (error: Exception) {
            verified = false
            result["status"] = "UNVERIFIED"
            result["failures"] =
                (result["failures"] as List<*>) + "host report persistence: ${error.javaClass.simpleName}: ${error.message?.take(2048)}"
        }
        if (!verified) nodeHeapGate?.markUnverified()
        nodeHeapHistory.add(result.toMap())
        return result
    }

    /** 준비 중 선거가 끝난 역할을 고정한다. 이후 역할 callback은 정상 기준선 실행을 무효로 한다. */
    fun beginMeasurement() {
        collectNodes()
        assertHealthy()
        nodeHeapGate?.let { gate ->
            gate.beginMeasurement()
            HttpPerformanceNodeHeapControl.window(control, runId, "MEASUREMENT")
        }
        roleBaseline =
            (0..2).associateWith { member -> health(member).let { it.path("role").asString() to it.path("roleChangeCount").asLong() } }
        if (stageDiagnostics) gatewayWindowCommand("open")
        if (cpuPeriodUs != null) captureCpuEvidence("before")
    }

    /** 부하 전후에만 실행한다. 명시한 CPU 예산과 실제 제한 주기·throttling 카운터를 읽는다. */
    fun captureCpuEvidence(label: String) {
        require(label == "before" || label == "after")
        val evidence =
            (
                nodes.mapKeys { (member, _) -> "node$member" } +
                    gateway?.let { mapOf("gateway" to it) }.orEmpty() +
                    combinedDirectClient?.let { mapOf("directClient" to it) }.orEmpty()
            ).mapValues { (service, id) ->
                val started = Instant.now().toString()
                val result = command(listOf("docker", "exec", id, "cat", "/sys/fs/cgroup/cpu.max", "/sys/fs/cgroup/cpu.stat"), 10_000)
                must(result, "$service CPU 제한·통계 확인")
                val lines = result.stdout.trim().lines()
                val maximum = lines.first().trim().split(Regex("\\s+"))
                val expectedCpuCount = if (service == "gateway") gatewayCpuCount else 2
                check(maximum.size == 2 && maximum[0].toLongOrNull() == maximum[1].toLong() * expectedCpuCount) {
                    "$service 실제 CPU 시간 예산이 $expectedCpuCount CPU가 아닙니다"
                }
                val expectedPeriod = if (service == "gateway") cpuPeriodUs else 100_000
                check(maximum[1].toInt() == expectedPeriod) { "$service 실제 CPU 제한 주기가 다릅니다" }
                val counters =
                    lines.drop(1).associate { line ->
                        val fields = line.trim().split(Regex("\\s+"))
                        check(fields.size == 2)
                        fields[0] to fields[1].toLong()
                    }
                mapOf(
                    "cpuMax" to lines.first(),
                    "stat" to counters,
                    "sampleStartedAt" to started,
                    "sampleFinishedAt" to Instant.now().toString(),
                )
            }
        writeJson("cpu-$label.json", evidence)
    }

    fun endMeasurement(): JsonNode {
        check(stageDiagnostics)
        return gatewayWindowCommand("close") {
            gatewayWindow = it
            writeJson("gateway-stages.json", it)
            if (httpBoundaryOrders != null) writeJson("gateway-http-boundaries.json", it.path("httpBoundaries"))
        }
    }

    fun httpBoundaryEvidence(): JsonNode? = gatewayWindow?.path("httpBoundaries")?.takeUnless { it.isMissingNode || it.isNull }

    private fun gatewayWindowCommand(
        action: String,
        preserveEvidence: (JsonNode) -> Unit = {},
    ): JsonNode {
        check(action == "open" || action == "close")
        val temporary = control.resolve("gateway-window.tmp")
        Files.writeString(temporary, "$action:$runId\n")
        Files.move(
            temporary,
            control.resolve("gateway-window.request"),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        val response = control.resolve("gateway-window-$runId-$action.json")
        await("Gateway 측정 창 $action 확인", 20_000) { Files.isRegularFile(response) }
        check(Files.size(response) in 1..8_388_608) { "Gateway 계측 파일 크기 오류" }
        val result = Files.newInputStream(response).use { mapper.readTree(it) }
        return consumeHttpPerformanceGatewayWindow(result, runId, action, detailedTracing, preserveEvidence)
    }

    /** 준비가 끝난 별도 진단 실행에서만 켠다. 프로파일을 켠 처리량은 기준선 처리량으로 해석하지 않는다. */
    fun startJvmProfiles(
        maxSizeMb: Int? = null,
        maxAgeSeconds: Int? = null,
    ) {
        require(maxSizeMb == null || maxSizeMb in 32..250)
        require(maxAgeSeconds == null || maxAgeSeconds in 60..300)
        check(!closed && jvmProfiles.isEmpty()) { "JVM 프로파일은 실행당 한 번만 시작할 수 있습니다" }
        check(nodes.size == 3 && gateway != null) { "네 JVM 준비 후에만 프로파일을 시작할 수 있습니다" }
        Files.createDirectories(reports.resolve("profiles"))
        val targets = nodes.mapKeys { (member, _) -> "node$member" } + ("gateway" to checkNotNull(gateway))
        for ((service, container) in targets) {
            val directory = if (service == "gateway") "/tmp" else "/records"
            jvmProfiles[service] =
                JvmProfile(
                    container,
                    "performance-$runId-$service",
                    "$directory/performance-$runId-$service.jfr",
                    "profiles/$service.jfr",
                )
        }
        val failures = mutableListOf<String>()
        for ((service, profile) in jvmProfiles) {
            runCatching {
                jvmProfileCommand(
                    profile,
                    "resources-before",
                    listOf("docker", "exec", profile.container, "cat", "/sys/fs/cgroup/cpu.stat", "/sys/fs/cgroup/memory.events"),
                )
                val result =
                    jvmProfileCommand(
                        profile,
                        "start",
                        listOf(
                            "docker",
                            "exec",
                            profile.container,
                            "jcmd",
                            "1",
                            "JFR.start",
                            "name=${profile.recordingName}",
                            "settings=profile",
                            "dumponexit=false",
                            "disk=true",
                            "jdk.InitialSystemProperty#enabled=false",
                            "jdk.InitialEnvironmentVariable#enabled=false",
                            "jdk.ThreadSleep#enabled=false",
                            "jdk.FileForce#threshold=0ms",
                            "jdk.JavaMonitorEnter#threshold=1ms",
                            "jdk.JavaMonitorWait#threshold=1ms",
                            "jdk.ThreadPark#threshold=1ms",
                            "jdk.FileWrite#threshold=0ms",
                            "jdk.FileWrite#throttle=off",
                            "jdk.SocketRead#enabled=true",
                            "jdk.SocketRead#threshold=0ms",
                            "jdk.SocketRead#throttle=off",
                            "jdk.SocketRead#stackTrace=false",
                            "jdk.SocketWrite#enabled=true",
                            "jdk.SocketWrite#threshold=0ms",
                            "jdk.SocketWrite#throttle=off",
                            "jdk.SocketWrite#stackTrace=false",
                        ) +
                            listOfNotNull(
                                maxSizeMb?.let { "maxsize=${it}m" },
                                maxAgeSeconds?.let { "maxage=${it}s" },
                            ),
                    )
                must(result, "$service JFR 시작")
                check(result.stdout.contains("Started recording")) { "$service JFR 시작 응답 미확인" }
                profile.started = true
                must(
                    jvmProfileCommand(
                        profile,
                        "settings",
                        listOf(
                            "docker",
                            "exec",
                            profile.container,
                            "jcmd",
                            "1",
                            "JFR.check",
                            "name=${profile.recordingName}",
                            "verbose=true",
                        ),
                    ),
                    "$service JFR 설정 확인",
                )
            }.onFailure { failures.add("$service JFR 시작: ${it.javaClass.simpleName}: ${it.message}") }
        }
        jvmProfileErrors.addAll(failures)
        check(failures.isEmpty()) { "JVM 프로파일 시작 실패: $failures. 보고서: $reports" }
    }

    /** 측정 직후 호출한다. 일부 중지·복사가 실패해도 전부 시도하고 close에서 실패 항목을 다시 시도한다. */
    fun stopJvmProfiles() {
        val failures = mutableListOf<String>()
        val stoppedNow = mutableSetOf<String>()
        for ((service, profile) in jvmProfiles) {
            if (!profile.stopped) {
                stoppedNow.add(service)
                runCatching {
                    val result =
                        jvmProfileCommand(
                            profile,
                            "stop",
                            listOf(
                                "docker",
                                "exec",
                                profile.container,
                                "jcmd",
                                "1",
                                "JFR.stop",
                                "name=${profile.recordingName}",
                                "filename=${profile.containerFile}",
                            ),
                        )
                    must(result, "$service JFR 중지")
                    check(result.stdout.contains("Stopped recording")) { "$service JFR 중지 응답 미확인" }
                    profile.stopped = true
                    jvmProfileCommand(
                        profile,
                        "resources-after",
                        listOf("docker", "exec", profile.container, "cat", "/sys/fs/cgroup/cpu.stat", "/sys/fs/cgroup/memory.events"),
                    )
                }.onFailure { failures.add("$service JFR 중지: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
        for ((service, profile) in jvmProfiles) {
            if (!profile.preserved || service in stoppedNow) {
                runCatching {
                    val artifact = reports.resolve(profile.artifactFile)
                    must(
                        jvmProfileCommand(
                            profile,
                            "copy",
                            listOf("docker", "cp", "${profile.container}:${profile.containerFile}", artifact.toString()),
                        ),
                        "$service JFR 보존",
                    )
                    check(Files.isRegularFile(artifact) && Files.size(artifact) > 0) { "$service JFR 파일이 비어 있거나 없습니다" }
                    profile.preserved = true
                }.onFailure { failures.add("$service JFR 보존: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
        jvmProfileErrors.addAll(failures)
        check(failures.isEmpty()) { "JVM 프로파일 중지·보존 실패: $failures. 보고서: $reports" }
    }

    private fun jvmProfileCommand(
        profile: JvmProfile,
        stage: String,
        arguments: List<String>,
    ): VerificationCommandResult {
        val index = commands.records.size
        val startedAt = Instant.now().toString()
        try {
            return command(arguments, 30_000)
        } finally {
            commands.records.getOrNull(index)?.let {
                profile.commands.add(
                    mapOf(
                        "stage" to stage,
                        "startedAt" to startedAt,
                        "finishedAt" to Instant.now().toString(),
                        "command" to it,
                    ),
                )
            }
        }
    }

    private class JvmProfile(
        val container: String,
        val recordingName: String,
        val containerFile: String,
        val artifactFile: String,
    ) {
        var started = false
        var stopped = false
        var preserved = false
        val commands = mutableListOf<Map<String, Any?>>()
    }

    fun markers(): List<HttpPerformanceCompletion> = receiver.markers()

    fun observerErrors(): List<String> = receiver.errors() + infrastructureErrors.toList()

    fun assertHealthy() {
        for ((member, id) in nodes) {
            val inspected = inspect(id)
            check(inspected.path("State").path("Running").asBoolean() && !inspected.path("State").path("OOMKilled").asBoolean()) {
                "측정 중 node$member 종료 또는 메모리 부족"
            }
            check(
                inspected.path("State").path("StartedAt") == nodeInspections[member]?.path("State")?.path("StartedAt"),
            ) { "측정 중 node$member JVM 재시작" }
        }
        gateway?.let { check(inspect(it).path("State").path("Running").asBoolean()) { "HTTP gateway 종료" } }
        collectNodes()
        check(ready()) { "실제 역할·설정·동기화 조건이 바뀌었습니다" }
        roleBaseline?.forEach { (member, previous) ->
            val current = health(member)
            check(previous == (current.path("role").asString() to current.path("roleChangeCount").asLong())) {
                "측정 중 node$member 리더십 또는 역할 변경"
            }
        }
        check(observerErrors().isEmpty()) { observerErrors().joinToString("; ") }
    }

    fun manifest(): Map<String, Any?> =
        mapOf(
            "runId" to runId,
            "orderUri" to httpBase?.resolve("/api/trading/markets/BTC-USDT/orders")?.toString(),
            "marketDocument" to marketDocument,
            "nodeMaxHeapMb" to nodeMaxHeapMb,
            "nodeMaxHeapEnvironment" to environment.getValue("PERFORMANCE_NODE_MAX_HEAP"),
            "marketConfiguration" to expectedConfiguration,
            "nodes" to
                (0..2).associateWith { member ->
                    mapOf(
                        "ready" to events[member].orEmpty().firstOrNull { it.path("kind").asString() == "READY" },
                        "inspection" to nodeInspections[member],
                    )
                },
            "gateway" to gatewayInspection,
            "combinedDirectClient" to combinedDirectClientInspection,
            "nodeOnly" to nodeOnly,
            "gatewayPresent" to !nodeOnly,
            "gatewayConfiguredLimits" to
                if (nodeOnly) null else mapOf("queueCapacity" to 1024, "maxInFlight" to 256, "resultTimeoutMs" to 5000),
            "postLoadEvidenceTimeBudget" to evidenceTimeBudget.fields(),
            "dockerVmResources" to dockerResources,
            "markerObserver" to
                if (remoteMarkerEndpoint == null) {
                    receiver.manifest()
                } else {
                    mapOf(
                        "mode" to "REMOTE_COMBINED_DIRECT_CLIENT",
                        "hostReceiver" to "running idle; not used as completion evidence",
                        "externalMarkerProofRequired" to true,
                        "clock" to "remote combined direct client JVM; never subtract host receiver clock",
                    )
                },
            "storageGuarantee" to "Archive fileSyncLevel=$archiveSyncLevel, catalogFileSyncLevel=2, Consensus fileSyncLevel=2",
            "hostProcessors" to Runtime.getRuntime().availableProcessors(),
            "hostOs" to System.getProperty("os.name"),
            "hostArchitecture" to System.getProperty("os.arch"),
            "hostJava" to System.getProperty("java.version"),
            "volumeBacking" to "Docker named volumes on this host; inspect Docker VM backing storage separately",
            "independentDisasterDomains" to false,
            "runtimeCounterSamplingEnabled" to sampleRuntimeCounters,
            "gatewayStageDiagnostics" to stageDiagnostics,
            "gatewayCpuPeriodExperimentUs" to if (nodeOnly) null else cpuPeriodUs,
            "gatewayCpuBudgetExperiment" to if (nodeOnly) null else gatewayCpuCount,
            "gatewayWindow" to
                gatewayWindow?.let {
                    mapOf(
                        "status" to it.path("status").asString(),
                        "detailedTracing" to it.path("detailedTracing").asBoolean(),
                        "errors" to it.path("errors"),
                    )
                },
            "metricsCollection" to
                if (stageDiagnostics) {
                    "Operational Gateway metrics plus bounded test detail; profiler state recorded separately"
                } else if (jvmProfiles.isEmpty()) {
                    "No CPU/GC/disk sampler in the measured window; no bottleneck attribution"
                } else {
                    "JFR settings=profile on three nodes and gateway; diagnostic run includes profiler overhead and is not a throughput baseline"
                },
            "jvmProfiles" to
                mapOf(
                    "settings" to "profile",
                    "recordings" to
                        jvmProfiles.mapValues { (_, profile) ->
                            mapOf(
                                "container" to profile.container,
                                "recordingName" to profile.recordingName,
                                "containerFile" to profile.containerFile,
                                "artifactFile" to profile.artifactFile,
                                "started" to profile.started,
                                "stopped" to profile.stopped,
                                "preserved" to profile.preserved,
                                "commands" to profile.commands.toList(),
                            )
                        },
                    "errors" to jvmProfileErrors.toList(),
                ),
            "nodeHeapDiagnostics" to
                mapOf(
                    "requested" to nodeHeapDiagnostics,
                    "intrusive" to nodeHeapDiagnostics,
                    "baselineComparable" to !nodeHeapDiagnostics,
                    "maximumCapturesPerMember" to 2,
                    "maximumRawBytesPerCapture" to HTTP_PERFORMANCE_NODE_HEAP_MAX_BYTES,
                    "history" to nodeHeapHistory.toList(),
                    "scope" to "reported class shallow bytes only; native/owner graph and live coverage not proven",
                ),
            "archiveRetention" to
                mapOf(
                    "requested" to preserveNodeRecordings,
                    "status" to
                        when {
                            !preserveNodeRecordings -> "NOT_REQUESTED"
                            !composeAttempted -> "NOT_STARTED"
                            else -> archiveRetention?.status ?: "UNCONFIRMED"
                        },
                    "phase" to "after-node-stop-before-compose-down",
                    "result" to archiveRetention,
                    "retainedVolumes" to archiveRetention?.retainedVolumes.orEmpty(),
                    "remainingVolumesAfterComposeCleanup" to remainingRecordingVolumes,
                    "defaultCleanup" to "explicit opt-in absent: existing run-owned volumes are removed",
                ),
        )

    private fun markerDifferences(checkpoints: Map<Int, JsonNode>): List<String> =
        buildList {
            val received = markers()
            for ((member, checkpoint) in checkpoints) {
                val sent =
                    checkpoint
                        .path("markerSummary")
                        .path("sentMarkers")
                        .toList()
                        .map {
                            mapper.treeToValue(it, HttpPerformanceCompletion::class.java)
                        }.toSet()
                val appliedPosition = checkpoint.path("applied").path("position").asLong()
                val observed = received.filter { it.memberId == member && it.logPosition <= appliedPosition }.toSet()
                if (sent !=
                    observed
                ) {
                    add(
                        "node$member 완료 표식 송수신 불일치: sent=${sent.size}, received=${observed.size}, missing=${(sent - observed).size}, extra=${(observed - sent).size}",
                    )
                }
            }
        }

    private fun markerDifferencesFiles(checkpoints: Map<Int, HttpPerformanceSentMarkerFileEvidence>): List<String> =
        httpPerformanceMarkerDifferencesFiles(checkpoints, markers())

    private fun ready(): Boolean {
        val all =
            (0..2).map { member ->
                events[member].orEmpty().filter { it.path("kind").asString() == "READY" }.singleOrNull()
                    ?: return false
            }
        val expected = expectedConfiguration ?: return false
        if (all.any {
                it
                    .path("settings")
                    .path("storageLevels")
                    .toList()
                    .map { value -> value.asInt() } != listOf(archiveSyncLevel, 2, 2) ||
                    it.path("settings").path("marketConfigVersion").asLong() != expected.version ||
                    it.path("settings").path("marketConfigSha256").asString() != expected.sha256
            }
        ) {
            return false
        }
        if (all.map { it.path("settings").path("markets") }.distinct().size != 1) return false
        val roles = (0..2).map { health(it).path("role").asString() }.sorted()
        return roles == listOf("FOLLOWER", "FOLLOWER", "LEADER")
    }

    private fun health(member: Int): JsonNode =
        events[member].orEmpty().lastOrNull { it.path("kind").asString() == "PERFORMANCE_HEALTH" } ?: mapper.missingNode()

    private fun assertIsolation() {
        check(nodes.values.distinct().size == 3)
        val inspections = nodes.mapValues { inspect(it.value) }
        check(
            inspections.values
                .map { it.path("Image").asString() }
                .distinct()
                .size == 1,
        )
        val volumes =
            inspections.values.map {
                it
                    .path("Mounts")
                    .single { mount ->
                        mount.path("Destination").asString() == "/records"
                    }.path("Name")
                    .asString()
            }
        check(volumes.all { it.startsWith("${runId}_") } && volumes.distinct().size == 3) { "세 노드 Archive 기록 볼륨이 격리되지 않았습니다" }
        inspections.values.forEach {
            check(it.path("State").path("Running").asBoolean())
            check(
                it
                    .path(
                        "HostConfig",
                    ).path("NetworkMode")
                    .asString() != "host" && it.path("HostConfig").path("IpcMode").asString() != "host",
            )
        }
        nodeInspections.putAll(inspections)
    }

    private fun collectNodes() {
        for ((member, id) in nodes) {
            val logs = command(listOf("docker", "logs", id), 10_000)
            must(logs, "node$member 관찰 로그")
            val issues = mutableListOf<FundingVerificationIssue>()
            events[member] = readFundingEvents(logs.stdout, issues)
            check(issues.isEmpty()) { issues.toString() }
            check(
                events[member].orEmpty().none {
                    it.path("kind").asString() == "ERROR" ||
                        (!it.path("failure").isMissingNode && !it.path("failure").isNull) ||
                        (!it.path("collectorFailure").isMissingNode && !it.path("collectorFailure").isNull)
                },
            ) {
                "node$member 업무 또는 관찰 오류"
            }
        }
    }

    private fun inspect(id: String): JsonNode {
        val result =
            command(
                listOf(
                    "docker",
                    "inspect",
                    "--format",
                    """[{"Id":{{json .Id}},"Image":{{json .Image}},"State":{{json .State}},"HostConfig":{"NetworkMode":{{json .HostConfig.NetworkMode}},"IpcMode":{{json .HostConfig.IpcMode}},"Memory":{{json .HostConfig.Memory}},"NanoCpus":{{json .HostConfig.NanoCpus}},"ShmSize":{{json .HostConfig.ShmSize}}},"Mounts":{{json .Mounts}},"NetworkSettings":{{json .NetworkSettings}}}]""",
                    id,
                ),
                10_000,
            )
        must(result, "컨테이너 검사")
        return mapper.readTree(result.stdout).path(0)
    }

    /** No new container is created for preservation. Absence is established from this run's Compose project. */
    private fun preserveStoppedNodeRecordings(): HttpPerformanceArchiveRetentionResult {
        val discoveryFailures = mutableListOf<String>()
        val ownedVolumes =
            try {
                val listed =
                    command(listOf("docker", "volume", "ls", "--quiet", "--filter", "label=com.docker.compose.project=$runId"), 15_000)
                must(listed, "실행 전용 기록 볼륨 조회")
                listed.stdout
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .map { it.trim() }
                    .toList()
            } catch (failure: Exception) {
                discoveryFailures.add("owned volume discovery: ${failure.javaClass.simpleName}: ${failure.message}")
                null
            }
        val candidates =
            (0..2).map { member ->
                var id: String? = nodes[member]
                try {
                    val listed = compose(listOf("ps", "--all", "--quiet", "node$member"), 15_000)
                    must(listed, "node$member 보존 대상 조회")
                    val ids =
                        listed.stdout
                            .lineSequence()
                            .filter { it.isNotBlank() }
                            .map { it.trim() }
                            .toList()
                    check(ids.size <= 1) { "node$member container count differs from fixed Compose plan" }
                    val current = ids.singleOrNull()
                    check(id == null || id == current) { "node$member container identity changed" }
                    id = current
                    val inspected =
                        id?.let { container ->
                            val result =
                                command(
                                    listOf("docker", "inspect", "--format", HTTP_PERFORMANCE_RECORDING_INSPECT_FORMAT, container),
                                    10_000,
                                )
                            must(result, "node$member 원본 보존 경로 검사")
                            mapper.readTree(result.stdout)
                        }
                    HttpPerformanceRecordingCandidate(
                        member,
                        id,
                        inspected,
                        events[member].orEmpty().filter { it.path("kind").asString() == "READY" },
                    )
                } catch (failure: Exception) {
                    HttpPerformanceRecordingCandidate(
                        member,
                        id,
                        null,
                        emptyList(),
                        "${failure.javaClass.simpleName}: ${failure.message}",
                    )
                }
            }
        return preserveHttpPerformanceNodeRecordings(reports, runId, candidates, ownedVolumes, discoveryFailures) { source, path, target ->
            // No -L, no '/.', and a previously nonexistent destination: copied symlinks are rejected by inventory.
            must(
                command(listOf("docker", "cp", "${source.containerId}:$path", target.toString()), 300_000),
                "node${source.memberId} $path 원본 복사",
            )
        }
    }

    private fun containerId(service: String): String {
        val result = compose(listOf("ps", "--all", "--quiet", service), 15_000)
        must(result, "$service 식별")
        return result.stdout
            .lineSequence()
            .filter { it.isNotBlank() }
            .single()
    }

    private fun inspectCombinedDirectClient(container: String): JsonNode {
        // Select fields before Docker writes stdout; inherited DB credentials never enter this command's logs.
        val result = command(httpPerformanceCombinedClientInspectArguments(container), 15_000)
        must(result, "직접 client 실행·자원 정보 확인")
        return mapper.readTree(result.stdout)
    }

    private fun await(
        description: String,
        timeoutMs: Long,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        do {
            if (predicate()) return
            Thread.sleep(250)
        } while (System.nanoTime() < deadline)
        error("$description 제한 시간 초과. 보고서: $reports")
    }

    private fun compose(
        arguments: List<String>,
        timeoutMs: Long,
    ): VerificationCommandResult =
        command(
            listOf("docker", "compose", "--project-name", runId) +
                (
                    listOf("compose.yml", "http-gateway.compose.yml", "http-performance.compose.yml") +
                        if (cpuPeriodUs == null) emptyList() else listOf("http-cpu-period.compose.yml")
                ).flatMap {
                    listOf("--file", project.resolve("src/test/docker/aeron-funding/$it").toString())
                } + arguments,
            timeoutMs,
        )

    private fun command(
        arguments: List<String>,
        timeoutMs: Long,
    ) = commands.start(arguments).await(timeoutMs)

    private fun must(
        result: VerificationCommandResult,
        description: String,
    ) {
        check(result.succeeded) { "$description: ${result.failure()}. 보고서: $reports" }
    }

    private fun writeJson(
        name: String,
        value: Any?,
    ) {
        val temporary = Files.createTempFile(reports, ".$name-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { mapper.writerWithDefaultPrettyPrinter().writeValue(it, value) }
            Files.move(temporary, reports.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        nodeHeapGate?.close()
        val cleanup = mutableListOf<String>()
        // An opted-in run must establish success before the destructive --volumes flag can be used.
        var keepRecordingVolumes = preserveNodeRecordings

        fun safely(
            stage: String,
            action: () -> Unit,
        ) {
            runCatching(action).onFailure { cleanup.add("$stage: ${it.message}") }
        }
        safely("실행 중 CLI 종료") { cleanup.addAll(commands.closeRunning().map { it.message }) }
        safely("JVM 프로파일 중지·보존") { stopJvmProfiles() }
        if (composeAttempted) {
            if (gateway != null) {
                safely("gateway 종료") { must(compose(listOf("stop", "--timeout", "15", "gateway"), 20_000), "gateway 종료") }
            }
            safely("노드 종료") {
                must(
                    compose(
                        listOf("stop", "--timeout", evidenceTimeBudget.nodeStopGraceSeconds.toString(), "node0", "node1", "node2"),
                        evidenceTimeBudget.nodeStopCommandTimeoutMs,
                    ),
                    "노드 종료",
                )
            }
            safely("최종 로그") { collectNodes() }
            nodes.forEach { (member, id) ->
                safely("node$member 종료 증거") {
                    val inspected = inspect(id)
                    val stopped = events[member].orEmpty().lastOrNull { it.path("kind").asString() == "STOPPED" }
                    check(!inspected.path("State").path("Running").asBoolean())
                    check(
                        inspected.path("State").path("ExitCode").asInt() in setOf(0, 143) &&
                            !inspected.path("State").path("OOMKilled").asBoolean(),
                    )
                    check(
                        stopped != null && stopped.path("closed").asBoolean() && stopped.path("failure").isNull,
                    ) { "node$member 정상 종료 관찰 미확인" }
                    writeJson("node$member-final.json", mapOf("inspection" to inspected, "events" to events[member]))
                }
            }
            if (preserveNodeRecordings) {
                safely("노드 Archive·cluster 원본 보존") {
                    val retained = preserveStoppedNodeRecordings()
                    archiveRetention = retained
                    keepRecordingVolumes = !retained.volumeDeletionAllowed
                    cleanup.addAll(retained.failures.map { "노드 원본 보존: $it" })
                }
            }
            gateway?.let { id ->
                safely("gateway 로그") {
                    val logs = command(listOf("docker", "logs", id), 10_000)
                    Files.writeString(reports.resolve("gateway.stdout.log"), logs.stdout)
                    Files.writeString(reports.resolve("gateway.stderr.log"), logs.stderr)
                }
            }
            clients.forEach { name -> safely("$name 제거") { must(command(listOf("docker", "rm", "--force", name), 15_000), "준비 클라이언트 제거") } }
            database?.let { db ->
                network?.let { net ->
                    safely(
                        "DB 연결 해제",
                    ) { must(command(listOf("docker", "network", "disconnect", "--force", net, db.containerId), 15_000), "DB 연결 해제") }
                }
            }
            // 보존 불확실 시 --volumes를 빼 이번 실행의 원본 볼륨을 모두 남긴다.
            // 그 외에는 시작 시 기존 자원이 없음을 확인한 이번 실행 전용 볼륨만 제거한다.
            safely(
                "실행 전용 Compose 정리",
            ) { must(compose(httpPerformanceComposeDownArguments(keepRecordingVolumes), 30_000), "실행 전용 자원 정리") }
            if (preserveNodeRecordings) {
                safely("정리 뒤 남은 기록 볼륨 확인") {
                    val remaining =
                        command(listOf("docker", "volume", "ls", "--quiet", "--filter", "label=com.docker.compose.project=$runId"), 15_000)
                    must(remaining, "남은 실행 전용 기록 볼륨 조회")
                    remainingRecordingVolumes =
                        remaining.stdout
                            .lineSequence()
                            .filter { it.isNotBlank() }
                            .map { it.trim() }
                            .sorted()
                            .toList()
                    check(keepRecordingVolumes || remainingRecordingVolumes.orEmpty().isEmpty()) { "Compose volume cleanup incomplete" }
                    check(
                        !keepRecordingVolumes ||
                            archiveRetention?.retainedVolumes.orEmpty().all { it in remainingRecordingVolumes.orEmpty() },
                    ) {
                        "an expected retained recording volume is missing after Compose cleanup"
                    }
                }
            }
        }
        safely("테스트 DB 종료") { database?.close() }
        if (imageBuildAttempted) {
            safely("실행 전용 이미지 태그 정리") {
                val remaining = command(listOf("docker", "image", "ls", "--quiet", "--filter", "reference=$image"), 15_000)
                must(remaining, "실행 전용 이미지 확인")
                if (remaining.stdout.isNotBlank()) must(command(listOf("docker", "image", "rm", image), 30_000), "실행 전용 이미지 제거")
            }
        }
        safely("HTTP health client 종료") { http.close() }
        safely("표식 수신기 종료") { receiver.close() }
        var reportWritten = false
        try {
            writeJson(
                "cluster.json",
                mapOf(
                    "manifest" to manifest(),
                    "checkpoints" to checkpointHistory,
                    "markers" to markers(),
                    "observerErrors" to observerErrors(),
                    "cleanupFailures" to cleanup,
                    "recordingVolumesDeletionSkipped" to keepRecordingVolumes,
                    "archiveRetention" to archiveRetention,
                    "remainingRecordingVolumes" to remainingRecordingVolumes,
                    "commands" to commands.records,
                ),
            )
            reportWritten = true
        } finally {
            writeJson(
                "cluster-status.json",
                mapOf(
                    "runId" to runId,
                    "clusterReportWritten" to reportWritten,
                    "recordingVolumesDeletionSkipped" to keepRecordingVolumes,
                    "archiveRetention" to archiveRetention,
                    "remainingRecordingVolumes" to remainingRecordingVolumes,
                    "cleanupFailures" to cleanup,
                    "observerErrors" to observerErrors(),
                    "checkpoints" to checkpointHistory,
                ),
            )
        }
        check(credentialLeakFiles(reports, password).isEmpty()) { "성능 보고서에 DB 비밀번호가 남았습니다" }
        check(cleanup.isEmpty()) { "성능 실행 자원 정리 실패: $cleanup. 보고서: $reports" }
    }
}
