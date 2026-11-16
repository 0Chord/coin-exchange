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
import java.nio.file.Files
import java.nio.file.Path
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
private class MultiMatchingRun(
    private val scenario: String,
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
        mapOf(
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
            check(state.path("Running").asBoolean() && !state.path("OOMKilled").asBoolean() && state.path("Pid").asLong() > 0) {
                "$service 현재 컨테이너가 정지/OOM 상태입니다"
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
            listOf("run", "-d", "--no-deps", "--name", name, "--env", "FUNDING_HOST=$name", "client", TOOL) + args,
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
        diagnosticPeak = maxOf(diagnosticPeak, bytes(reports))
        check(diagnosticPeak <= 16 * MIB) { "진단 합계16MiB 초과: $diagnosticPeak" }
        if (containers.isNotEmpty()) {
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
            ) +
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
        diagnosticPeak = maxOf(diagnosticPeak, bytes(reports))
        val report =
            mapOf(
                "runId" to runId,
                "case" to scenario,
                "success" to (issues.isEmpty() && resourcesRemoved),
                "issues" to issues,
                "baseCommit" to "e3656d88e1cd1a10fe670472ca5744a23eb037f9",
                "headCommit" to headCommit,
                "sourceSha256" to sourceHashes,
                "imageId" to imageId,
                "manifestSha256" to manifestSha,
                "provenance" to provenance,
                "memoryObservations" to memoryObservations,
                "runtimes" to runtimes,
                "checkpoints" to checkpoints,
                "lastStates" to latestStates,
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
                    listOf("리더 전환/과반 장애", "장기 부하/성능", "실제5마켓3+2 배치", "미회수 payload에 대한 부정 ACK 격리"),
            )
        val bytes = mapper.writeValueAsBytes(report)
        check(bytes.size <= 4 * MIB) { "결과 보고서4MiB 초과" }
        Files.write(reports.resolve("result.json"), bytes)
        diagnosticPeak = maxOf(diagnosticPeak, bytes(reports))
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
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MultiMatchingConvergence.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MarketConfigurationDatabase.kt",
                "src/test/kotlin/com/exchange/core/api/trading/infrastructure/aeron/VerificationCommands.kt",
                "src/test/docker/aeron-multi-matching/compose.yml",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingNode.kt",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/DevelopmentSplitTradingMain.kt",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/FundsOrderDeliveryAgent.kt",
                "src/main/kotlin/com/exchange/core/api/trading/infrastructure/aeron/MatchingResultDeliveryAgent.kt",
            )

        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun bytes(path: Path): Long =
            Files.walk(path).use { files -> files.filter(Files::isRegularFile).mapToLong(Files::size).sum() }
    }
}
