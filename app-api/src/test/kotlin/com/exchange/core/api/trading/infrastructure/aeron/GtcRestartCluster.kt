package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.support.OrderQueryTestDatabase
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingServiceRole
import io.aeron.Aeron
import io.aeron.AeronCounters
import io.aeron.archive.ArchiveMarkFile
import io.aeron.cluster.ClusterTool
import io.aeron.cluster.RecordingLog
import io.aeron.cluster.service.ClusterCounters
import io.aeron.cluster.service.ClusterMarkFile
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.concurrent.status.CountersReader
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit

/** 같은 기록 경로에서 별도 WAS·자금·매칭 JVM을 교체하는 시험 준비. */
internal class GtcRestartCluster(
    private val directory: Path,
    private val database: OrderQueryTestDatabase,
) : AutoCloseable {
    private val lineage = ClusterLineage(41, 42)
    private val testSettings = SplitTradingHttpTestSettings(directory, lineage)
    private val basePort = testSettings.availablePorts()
    private val json = JsonMapper.builder().build()
    private val manifest = directory.resolve("manifest.bin")
    private val funds = Node(TradingServiceRole.FUNDS)
    private val matching = Node(TradingServiceRole.MATCHING)
    private val nodes = listOf(funds, matching)
    private val snapshots = mutableMapOf<TradingServiceRole, Snapshot>()
    private val recoveryEvidence = mutableListOf<String>()
    private var lastHttpResponse = "HTTP 응답을 받기 전입니다"
    private var controlDriver: MediaDriver? = null
    private var was: OrderRecoveryWas? = null

    fun start(): String {
        val fees = MakerTakerFeeRates(makerFeeRate = FeeRate(10_000), takerFeeRate = FeeRate(20_000))
        val definition = testSettings.manifest(fees, maxFundingAccounts = 8)
        Files.write(manifest, BoundedTradingStateCodec().encodeManifest(definition))
        nodes.forEach { it.start(recover = false) }

        controlDriver =
            MediaDriver.launch(
                MediaDriver
                    .Context()
                    .aeronDirectoryName(directory.resolve("control-driver").toString())
                    .dirDeleteOnShutdown(true)
                    .threadingMode(ThreadingMode.SHARED),
            )
        val initialize = SplitOrderDeliveryCommand.InitializeRole(funds.settings.configuration.topologySha256)
        for (node in nodes) {
            node.connect().use { client -> check(client.request(initialize).code == "INITIALIZED") }
        }
        funds.connect().use { client ->
            val balances = listOf(Triple("buyer", "USDT", 1_000L), Triple("seller", "BTC", 3L), Triple("later-buyer", "USDT", 300L))
            for ((user, asset, amount) in balances) {
                val opening = OpeningBalance("http-opening-$user", UserId(user), AssetId(asset), Amount(amount))
                val payload = DevelopmentBalanceCommandPayload(opening, "http-opening-ledger-$user", Instant.EPOCH)
                val reply = client.request(SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Funding(payload)))
                check(reply.code == "PREPARED") { "초기 자금 지급에 실패했습니다 ($user): ${reply.code}" }
            }
        }
        return startWas(0)
    }

    fun snapshot() {
        for (node in nodes) {
            val output = ByteArrayOutputStream()
            val taken =
                PrintStream(output).use {
                    ClusterTool.snapshot(
                        node.settings.transport.clusterDirectory
                            .toFile(),
                        it,
                    )
                }
            check(taken) { "${node.role} 스냅샷 요청 실패: $output" }
            snapshots[node.role] = node.latestSnapshot()
        }
    }

    fun progress(): BoundedTradingReply =
        funds.connect().use { client ->
            val reply = client.request(SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Progress))
            check(reply.code == "PROGRESS") { "자금 정산 순번 조회 실패: ${reply.code}" }
            checkNotNull(reply.control)
        }

    fun restart(expectedThrough: Long): String {
        val firstWas = checkNotNull(was)
        val oldPids = nodes.map { it.pid } + firstWas.pid
        firstWas.stopGracefully(mapOf("BTC-USDT" to expectedThrough))
        recoveryEvidence.add("WAS PID ${firstWas.pid} 종료 완료, 자금 정산 순번 $expectedThrough")

        val endPositions = nodes.associate { it.role to it.confirmedPosition() }
        for (node in nodes) {
            check(endPositions.getValue(node.role) > snapshots.getValue(node.role).position) {
                "${node.role} 스냅샷 뒤에 복원할 기록이 없습니다"
            }
        }

        recoveryEvidence.add("스냅샷 위치 $snapshots, 종료 전 확정·적용 위치 $endPositions")
        nodes.forEach { it.stopGracefully() }
        check(oldPids.none { ProcessHandle.of(it).isPresent }) { "이전 서버가 아직 살아 있습니다: $oldPids" }

        for (node in nodes) {
            node.start(recover = true)
            val snapshot = snapshots.getValue(node.role)
            check(node.latestSnapshot() == snapshot) { "${node.role}이 최초 스냅샷을 유지하지 않았습니다" }
            val restored = node.confirmedPosition()
            val previous = endPositions.getValue(node.role)
            recoveryEvidence.add("${node.role} PID ${node.pid}, 종료 전 $previous → 복원 뒤 $restored")
            check(restored >= previous) { "${node.role} 확정 위치가 복원되기 전보다 작습니다: $restored < $previous" }
        }
        val url = startWas(1)
        val newPids = nodes.map { it.pid } + checkNotNull(was).pid
        check(newPids.none(oldPids::contains)) { "서버 PID가 교체되지 않았습니다: $oldPids → $newPids" }
        recoveryEvidence.add("정상 종료 뒤 프로세스 교체 $oldPids → $newPids")
        println(recoveryEvidence.joinToString("\n"))
        return url
    }

    private fun startWas(generation: Int): String {
        val settingsFiles =
            nodes.map { node ->
                val file = directory.resolve("${node.role.name.lowercase()}.properties")
                val properties = Properties()
                properties.putAll(node.environment)
                Files.newOutputStream(file).use { properties.store(it, null) }
                file
            }
        val values =
            database.properties +
                mapOf(
                    "exchange.order-query.supported-markets" to "BTC-USDT",
                    "exchange.order-query.source" to "archive",
                    "exchange.order-query.consumer-id" to "gtc-restart",
                    "exchange.order-query.archive-node-configs" to settingsFiles.joinToString(","),
                    "exchange.order-query.order-book-enabled" to "true",
                    "exchange.order-query.require-recovery-ready" to "false",
                    "TRADING_GATEWAY_PROTECTION" to "local",
                    "TRADING_GATEWAY_RUN_ROOT" to directory.resolve("gateway-$generation").toString(),
                    "TRADING_GATEWAY_INGRESS_ENDPOINTS" to funds.settings.fundsIngressEndpoints,
                    "TRADING_GATEWAY_EGRESS_ENDPOINT" to "127.0.0.1:${basePort + 32 + generation}",
                    "server.port" to "0",
                    "server.tomcat.threads.min-spare" to "1",
                    "spring.main.banner-mode" to "off",
                    "logging.level.root" to "ERROR",
                    "logging.level.${BoundedAeronGateway::class.java.name}" to "INFO",
                )
        val arguments = values.map { (key, value) -> "--$key=$value" }.toTypedArray()
        val started = OrderRecoveryWas.start(arguments, directory.resolve("was-$generation.log"))
        was = started
        return started.url
    }

    fun recordHttp(response: HttpResponse<String>) {
        val request = response.request()
        lastHttpResponse = "${request.method()} ${request.uri().rawPath} → ${response.statusCode()}: ${response.body().takeLast(3_000)}"
    }

    fun failureEvidence(): String =
        buildString {
            recoveryEvidence.forEach(::appendLine)
            appendLine("마지막 HTTP 응답 $lastHttpResponse")
            val ledger =
                runCatching {
                    database.jdbc().queryForObject("select max(sequence) from order_query.funds_ledger_batch", Long::class.java)
                }.getOrElse { it.message }
            appendLine("PostgreSQL 원장 순번 $ledger")
            Files.list(directory).use { files ->
                files.filter { it.fileName.toString().endsWith(".log") }.sorted().forEach { log ->
                    val output = Files.readAllBytes(log).toString(Charsets.UTF_8)
                    appendLine("${log.fileName}\n${output.takeLast(3_000)}")
                }
            }
        }

    override fun close() {
        var firstFailure: Throwable? = null
        val closeActions = listOf({ was?.close() }, { funds.cleanup() }, { matching.cleanup() }, { controlDriver?.close() })
        for (close in closeActions) {
            try {
                close()
            } catch (error: Throwable) {
                val previous = firstFailure
                if (previous == null) firstFailure = error else previous.addSuppressed(error)
            }
        }
        firstFailure?.let { throw it }
    }

    private data class Snapshot(
        val recordingId: Long,
        val position: Long,
    )

    private inner class Node(
        val role: TradingServiceRole,
    ) {
        val environment = testSettings.settingsEnvironment(manifest, role, basePort, archiveQuery = true)
        val settings by lazy { splitTradingSettings(environment) }
        private var process: Process? = null
        private lateinit var log: Path
        val pid: Long get() = checkNotNull(process).pid()

        fun start(recover: Boolean) {
            log = directory.resolve("${role.name.lowercase()}-recover-$recover.log")
            val command = tradingProcessCommand("com.exchange.core.api.trading.infrastructure.aeron.DevelopmentSplitTradingMainKt")
            val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
            val childEnvironment = builder.environment()
            childEnvironment.keys.removeIf {
                it.startsWith(
                    "FUNDING_",
                ) || it.startsWith("SPLIT_") || it.startsWith("EXCHANGE_ORDER_QUERY_")
            }
            childEnvironment.putAll(environment)
            childEnvironment["FUNDING_RECOVER_EXISTING"] = recover.toString()
            val started = builder.start()
            process = started
            val event = awaitStart(started)
            check(event.path("pid").longValue() == started.pid())
            check(event.path("role").asString() == role.name)
            check(event.path("storageLevels").values().map(JsonNode::intValue) == listOf(2, 2, 2))
            check(event.path("tradingReady").asBoolean() == recover) { "역할의 복원 상태가 다릅니다: $event" }
        }

        private fun awaitStart(started: Process): JsonNode {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65)
            while (started.isAlive && System.nanoTime() < deadline) {
                val output = Files.readAllBytes(log).toString(Charsets.UTF_8)
                val ready =
                    output.lineSequence().firstOrNull { line ->
                        line.startsWith("FUNDING_EVENT ") && line.contains("SPLIT_RUNTIME_READY") && line.endsWith('}')
                    }
                if (ready != null) {
                    return json.readTree(ready.removePrefix("FUNDING_EVENT "))
                }
                Thread.sleep(50)
            }
            error("$role 기동 실패 (PID ${started.pid()}, $log)\n${Files.readString(log).takeLast(12_000)}")
        }

        fun stopGracefully() {
            val running = checkNotNull(process)
            running.stopTradingProcess(log)
            val transport = settings.transport
            val clusterFiles = listOf(ClusterMarkFile.FILENAME, ClusterMarkFile.markFilenameForService(transport.serviceId))
            for (markName in clusterFiles) {
                ClusterMarkFile(transport.clusterDirectory.toFile(), markName, System::currentTimeMillis, 0, null).use { marker ->
                    checkTerminated(markName, marker.decoder().pid(), marker.activityTimestampVolatile(), running.pid())
                }
            }
            val archiveDirectory = transport.archiveDirectory.toFile()
            ArchiveMarkFile(archiveDirectory, ArchiveMarkFile.FILENAME, System::currentTimeMillis, 0, null).use { marker ->
                checkTerminated(ArchiveMarkFile.FILENAME, marker.decoder().pid(), marker.activityTimestampVolatile(), running.pid())
            }
        }

        private fun checkTerminated(
            markName: String,
            markerPid: Long,
            activity: Long,
            stoppedPid: Long,
        ) {
            check(markerPid == stoppedPid && activity == Aeron.NULL_VALUE.toLong()) {
                "$role $markName 정상 종료 기록이 없습니다 (PID $markerPid / $stoppedPid, timestamp $activity)"
            }
            recoveryEvidence.add("$role PID $stoppedPid 종료 기록 $markName")
        }

        fun cleanup() = process?.cleanupTradingProcess()

        fun connect(): SplitOrderDeliveryClient {
            val ingress = if (role == TradingServiceRole.FUNDS) settings.fundsIngressEndpoints else settings.matchingIngressEndpoints
            val endpoint =
                SplitOrderDeliveryEndpoint(
                    driverDirectory = checkNotNull(controlDriver).aeronDirectoryName(),
                    ingressEndpoints = ingress,
                    egressEndpoint = "127.0.0.1:0",
                    clusterId = settings.clusterId,
                    inputContractVersion = 2,
                )
            return SplitOrderDeliveryClient(endpoint, 10_000)
        }

        fun latestSnapshot(): Snapshot =
            RecordingLog(settings.transport.clusterDirectory.toFile(), false).use { recordingLog ->
                val serviceId = settings.transport.serviceId
                val service = checkNotNull(recordingLog.getLatestSnapshot(serviceId)) { "$role 서비스 스냅샷 누락" }
                val consensus = checkNotNull(recordingLog.getLatestSnapshot(-1)) { "$role 합의 스냅샷 누락" }
                check(service.logPosition == consensus.logPosition && service.logPosition > 0)
                Snapshot(service.recordingId, service.logPosition)
            }

        fun confirmedPosition(): Long {
            val context = Aeron.Context().aeronDirectoryName(settings.transport.driverDirectory)
            return Aeron.connect(context).use { aeron ->
                val counters = aeron.countersReader()
                val committedId = ClusterCounters.find(counters, AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID, settings.clusterId)
                val label =
                    splitArchiveAppliedLabel(settings.clusterId, settings.transport.memberId, settings.configuration.resultTopologySha256)
                var appliedId = CountersReader.NULL_COUNTER_ID
                counters.forEach { id, type, _, name ->
                    if (type == SPLIT_ARCHIVE_APPLIED_TYPE_ID && name == label) appliedId = id
                }
                check(committedId != CountersReader.NULL_COUNTER_ID && appliedId != CountersReader.NULL_COUNTER_ID) {
                    "$role 확정·적용 위치를 읽을 수 없습니다"
                }
                minOf(counters.getCounterValue(committedId), counters.getCounterValue(appliedId))
            }
        }
    }
}

internal fun verifyGtcRestart(
    directory: Path,
    verify: (OrderQueryTestDatabase, GtcRestartCluster, HttpClient) -> Unit,
) {
    OrderQueryTestDatabase(socketTimeoutSeconds = 2).use { database ->
        GtcRestartCluster(directory, database).use { cluster ->
            HttpClient.newHttpClient().use { client ->
                try {
                    verify(database, cluster, client)
                } catch (failure: Throwable) {
                    val evidence = runCatching(cluster::failureEvidence).getOrElse { "기록 수집 오류: ${it.message}" }
                    failure.addSuppressed(AssertionError(evidence))
                    throw failure
                }
            }
        }
    }
}
