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
    private var stoppedService: String? = null
    private var recoveryOverride: Path? = null
    private var probeSequence = 0L
    private val lifecycle = linkedMapOf<String, Any?>()
    private val timeline = linkedMapOf<String, Long>()
    private val eventOrder = linkedMapOf<String, Int>()
    private val gates = mutableListOf<Map<String, Any?>>()
    private var gateInstallation = ""
    private var electedBtcLeader = ""

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
                    project.resolve("src/test/docker/aeron-order-delivery/Dockerfile").toString(),
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
            await(MultiMatchingStage.INITIALIZED)
            scenarioBody(this)
            verifyLiveNodes(collectMemory = true)
        } catch (failure: Throwable) {
            issues += "$phase: ${failure.javaClass.simpleName}: ${failure.message}"
        } finally {
            cleanup()
            writeReport()
        }
        assertTrue(issues.isEmpty() && resourcesRemoved, "실제9노드 검증 실패: $issues · 보고서 $reports")
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
                    val fee = if (scenario == "race") 0 else 10_000
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
            val state = node.path("State")
            (runtimes.getValue(service) as ObjectNode).set("inspection", node)
            if (service == stoppedService) {
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
                checkpoints += mapOf("phase" to stage.name, "elapsedMs" to elapsedMs(), "states" to states, "originals" to originals)
                return states
            }
            Thread.sleep(100)
        }
        error("$stage 수렴 실패: ${failures.take(12)}")
    }

    fun tool(vararg args: String): JsonNode {
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
                "client",
                TOOL,
            ) + args,
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

    /** 기존 정상 수명은 유지하고, 이 시험에서 명시한 죽은 BTC 한 개만 현재 사본에서 뺀다. */
    fun awaitFailover(
        stage: MultiMatchingFailoverStage,
        originals: Map<String, JsonNode>,
        higherThanTerm: Long? = null,
    ): Map<String, JsonNode> {
        check(leaderFailover)
        phase = stage.name
        val until = minOf(workDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
        var failures = emptyList<String>()
        var probe: Long? = null
        while (System.nanoTime() < until) {
            budgets()
            val states = SERVICES.filter { it != stoppedService }.associateWith { read(control.resolve("state-$it.json")) }
            latestStates = states
            failures = MultiMatchingFailoverConvergence.violations(stage, states, originals) +
                currentFailures(states, probe, higherThanTerm)
            if (failures.isEmpty()) {
                if (probe == null) {
                    probe = 1_000_000_000_000L + ++probeSequence
                    tool("probe", "all", probe.toString())
                    continue
                }
                verifyLiveNodes()
                checkpoints += mapOf("phase" to stage.name, "elapsedMs" to elapsedMs(), "states" to states, "originals" to originals)
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
            if (owner == "btc" && higherThanTerm != null && group.any { it.path("leadershipTermId").asLong(-1) <= higherThanTerm }) {
                failures += "BTC 생존 과반의 더 높은 임기 미관측"
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
            for (service in SERVICES.filter { it != stoppedService }) {
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
        val expected = if (gate.getValue("gate") == "btc-dispatch") gate.getValue("blockedService").toString() else electedBtcLeader
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

    /** 종료 직전 새 probe의 실제 리더만 죽이고, 과거 LEADER 파일을 이후의 현재 사본에서 제외한다. */
    fun killBtcLeader(states: Map<String, JsonNode>): Long {
        check(leaderFailover && stoppedService == null)
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
        val btc = states.filterKeys { it.startsWith("btc") }
        val target = btc.entries.single { it.value.path("clusterRole").asString() == "LEADER" }.key
        val term = btc.getValue(target).path("leadershipTermId").asLong()
        lifecycle["target"] = target
        lifecycle["initial"] = boundRuntimes(states)
        mark("killSent")
        command(listOf("docker", "kill", "--signal", "KILL", containers.getValue(target)), 10_000)
        check(command(listOf("docker", "wait", containers.getValue(target)), 10_000).stdout.trim() == "137")
        val inspected = mapper.readTree(command(listOf("docker", "inspect", containers.getValue(target)), 10_000).stdout).path(0)
        val state = inspected.path("State")
        check(!state.path("Running").asBoolean() && state.path("ExitCode").asInt() == 137 && !state.path("OOMKilled").asBoolean())
        stoppedService = target
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
        electedBtcLeader = states.entries.single { it.key.startsWith("btc") && it.value.path("clusterRole").asString() == "LEADER" }.key
    }

    private fun boundRuntimes(states: Map<String, JsonNode>) =
        states.mapValues { (service, state) ->
            (runtimes.getValue(service).deepCopy() as ObjectNode).apply { set("state", state) }
        }

    /** 같은 Archive를 그대로 다시 열되, 이 노드 한 개의 복구 플래그만 바꾼다. */
    fun rejoinBtcLeader() {
        val target = checkNotNull(stoppedService)
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
        val recoveryFile = reports.resolve("recovery.override.yml")
        Files.writeString(recoveryFile, "services:\n  $target:\n    environment:\n      FUNDING_RECOVER_EXISTING: \"true\"\n")
        recoveryOverride = recoveryFile
        Files.delete(control.resolve("runtime-$target.json"))
        compose(listOf("up", "-d", "--no-deps", "--force-recreate", target), TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()))
        while (!Files.isRegularFile(control.resolve("runtime-$target.json"))) {
            check(System.nanoTime() < until) { "구 BTC 리더의 새 runtime 미관측" }
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
        stoppedService = null
        val afterFiles = recoveryFiles(target)
        check(beforeFiles.lineSequence().first() == afterFiles.lineSequence().first()) { "재합류 복구 identity 원문 변경" }
        lifecycle["recoveryAfter"] = mapOf("service" to target, "volume" to volume, "files" to afterFiles)
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
        val failures = MultiMatchingFailoverConvergence.lifecycleViolations(evidence)
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
        return command(
            listOf(
                "docker",
                "run",
                "--rm",
                "--name",
                "$runId-records",
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
    }

    fun sameBusiness(
        before: Map<String, JsonNode>,
        after: Map<String, JsonNode>,
    ) {
        for (service in SERVICES) {
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
            if (leaderFailover) {
                val total =
                    recordsHelper("du -sk /records/*")
                        .lineSequence()
                        .filter(String::isNotBlank)
                        .sumOf { it.trim().split(Regex("\\s+"))[0].toLong() * 1024 }
                recordsPeak = maxOf(recordsPeak, total)
                check(total <= 2 * GIB) { "죽은 볼륨 포함9개 기록 합계2GiB 초과: $total" }
                return
            }
            val result = compose(listOf("exec", "-T", "funds0", "du", "-sk", "/records"), 5_000)
            // 각 named volume은 컨테이너별로 확인한다. 첫 볼륨을9배한 값으로 대신하지 않는다.
            var total =
                result.stdout
                    .trim()
                    .split(Regex("\\s+"))[0]
                    .toLong() * 1024
            for (service in SERVICES.drop(1)) {
                total +=
                    compose(listOf("exec", "-T", service, "du", "-sk", "/records"), 5_000)
                        .stdout
                        .trim()
                        .split(Regex("\\s+"))[0]
                        .toLong() * 1024
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
            ) + (recoveryOverride?.let { listOf("--file", it.toString()) } ?: emptyList()) + args,
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
                "case" to if (leaderFailover) gates.firstOrNull()?.get("gate") ?: "leader-failover" else scenario,
                "success" to (issues.isEmpty() && resourcesRemoved),
                "issues" to issues,
                "baseCommit" to
                    if (leaderFailover) "9001e14a5afe96f91aeb461861052dd62c57ef9a" else "e3656d88e1cd1a10fe670472ca5744a23eb037f9",
                "headCommit" to headCommit,
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
                    if (leaderFailover) {
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

    private fun currentSourceHashes() = SOURCES.associateWith { sha(Files.readAllBytes(project.resolve(it))) }

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
