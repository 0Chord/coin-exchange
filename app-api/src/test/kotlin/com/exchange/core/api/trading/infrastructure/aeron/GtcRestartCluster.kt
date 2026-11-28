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
import io.aeron.CommonContext
import io.aeron.archive.ArchiveMarkFile
import io.aeron.archive.client.AeronArchive
import io.aeron.archive.status.RecordingPos
import io.aeron.cluster.ClusterTool
import io.aeron.cluster.RecordingLog
import io.aeron.cluster.service.ClusterCounters
import io.aeron.cluster.service.ClusterMarkFile
import io.aeron.cluster.service.ClusteredServiceContainer
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
    private val matchingCrashDispatch: Long?,
    controlFundsResults: Boolean,
) : AutoCloseable {
    private val lineage = ClusterLineage(41, 42)
    private val testSettings = SplitTradingHttpTestSettings(directory, lineage)
    private val basePort = testSettings.availablePorts()
    private val json = JsonMapper.builder().build()
    private val manifest = directory.resolve("manifest.bin")
    private val fundsResultControl = if (controlFundsResults) GtcFundsResultControl(directory) else null
    private val funds = Node(TradingServiceRole.FUNDS)
    private val matching = Node(TradingServiceRole.MATCHING)
    private val nodes = listOf(funds, matching)
    private val snapshots = mutableMapOf<TradingServiceRole, Snapshot>()
    private val recoveryEvidence = mutableListOf<String>()
    private var lastHttpResponse = "HTTP 응답을 받기 전입니다"
    private var controlDriver: MediaDriver? = null
    private var was: OrderRecoveryWas? = null
    val wasPid: Long get() = checkNotNull(was).pid

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

    fun fundsState(): JsonNode = checkNotNull(fundsResultControl).fundsState(funds.pid, ::progress)

    fun allowFundsResultsThrough(
        through: Long,
        withholdAcknowledgementsFrom: Long? = null,
    ) = checkNotNull(fundsResultControl).configure(through, withholdAcknowledgementsFrom)

    fun awaitWithheldFundsAcknowledgement(through: Long): Long {
        val position = checkNotNull(fundsResultControl).awaitWithheldAcknowledgement(through)
        recoveryEvidence.add("매칭에 전달하지 않은 자금 확인 순번 $through, 자금 적용 위치 $position")
        return position
    }

    fun awaitMatchingResultState(
        issued: Long,
        fundsSettledThrough: Long,
    ) {
        checkNotNull(fundsResultControl).awaitMatchingResultState(issued, fundsSettledThrough)
        recoveryEvidence.add("매칭 결과 확정 순번 $issued, 정산 확인 순번 $fundsSettledThrough")
    }

    fun crashAndRecoverFunds() {
        val originalPid = funds.pid
        val unaffectedPids = listOf(matching.pid, wasPid)
        val snapshot = snapshots[TradingServiceRole.FUNDS]
        val before = funds.logBoundary()
        val businessPosition = minOf(before.committed, before.applied, before.recorded)
        check(businessPosition > 0) { "복원할 자금 로그가 없습니다: $before" }

        val exitCode = funds.kill()
        recoveryEvidence.add("자금 PID $originalPid 강제 종료, 종료 코드 $exitCode, 자금 로그 $before")
        check(exitCode == 137) { "자금 JVM이 SIGKILL로 종료되지 않았습니다" }
        funds.awaitInactiveRuntime()
        funds.start(recover = true)

        val restored = funds.logBoundary()
        recoveryEvidence.add("자금 PID ${funds.pid} 복원, 자금 로그 $restored, 유지된 매칭·WAS PID $unaffectedPids")
        check(funds.pid != originalPid)
        check(unaffectedPids.all { ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) }) {
            "자금 복구 동안 매칭과 WAS가 유지되지 않았습니다"
        }
        val originalLogRestored = restored.recordingId == before.recordingId
        val restoredPosition = minOf(restored.committed, restored.applied, restored.recorded)
        check(originalLogRestored && restoredPosition >= businessPosition) {
            "자금 Archive가 원래 확정 위치를 복원하지 않았습니다: $before → $restored"
        }
        if (snapshot != null) check(funds.latestSnapshot() == snapshot) { "자금 스냅샷 원본이 바뀌었습니다" }
        println(recoveryEvidence.takeLast(2).joinToString("\n"))
    }

    fun assertWasRemainedRunning(originalPid: Long) {
        check(wasPid == originalPid && ProcessHandle.of(originalPid).map(ProcessHandle::isAlive).orElse(false)) {
            "후속 HTTP 요청을 처리한 WAS PID가 바뀌었습니다. 최초 $originalPid, 현재 $wasPid"
        }
        println("자금 복구와 후속 HTTP 요청 완료 뒤 같은 WAS PID $originalPid 유지")
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

    fun crashAndRecoverMatching(orderId: String) {
        val snapshot = snapshots.getValue(TradingServiceRole.MATCHING)
        val originalPid = matching.pid
        val fundsPid = funds.pid
        val wasPid = checkNotNull(was).pid
        val paused = matching.awaitCrashMarker(recover = false, orderId = orderId)
        val position = paused.path("position").longValue()
        val boundary = matching.logBoundary()
        recoveryEvidence.add("매칭 적용 직전 $paused, 스냅샷 $snapshot, 로그 $boundary")
        check(snapshot.position < position && boundary.applied < position) { "대상 주문은 스냅샷 뒤 미적용 상태여야 합니다" }
        check(boundary.committed >= position && boundary.recorded >= position) { "대상 주문이 매칭 로그에 확정·기록되지 않았습니다" }

        val exitCode = matching.kill()
        recoveryEvidence.add("매칭 PID $originalPid 강제 종료, 종료 코드 $exitCode")
        check(exitCode == 137) { "매칭 JVM이 SIGKILL로 종료되지 않았습니다" }
        matching.awaitInactiveRuntime()
        matching.start(recover = true)

        val replayed = matching.awaitCrashMarker(recover = true, orderId = orderId)
        val restored = matching.logBoundary()
        recoveryEvidence.add("매칭 복원 $replayed, 로그 $restored, 유지된 자금 PID $fundsPid·WAS PID $wasPid")
        check(matching.pid != originalPid)
        check(listOf(fundsPid, wasPid).all { ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) }) {
            "매칭 복구 동안 자금과 WAS가 유지되지 않았습니다"
        }
        check(matching.latestSnapshot() == snapshot && restored.recordingId == boundary.recordingId) {
            "매칭 스냅샷이나 로그 원본이 바뀌었습니다"
        }
        check(replayed.path("position").longValue() == position && restored.applied >= position) {
            "대상 주문의 원래 로그 위치($position)에서 복원을 확인하지 못했습니다"
        }
        check(replayed.path("resultSequence").longValue() == matchingCrashDispatch) {
            "복원된 체결 결과의 순번이 다릅니다: $replayed"
        }
        println(recoveryEvidence.joinToString("\n"))
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

    private data class LogPositions(
        val committed: Long,
        val applied: Long,
    )

    private data class LogBoundary(
        val committed: Long,
        val applied: Long,
        val recordingId: Long,
        val recorded: Long,
    )

    private inner class Node(
        val role: TradingServiceRole,
    ) {
        val environment =
            testSettings.settingsEnvironment(manifest, role, basePort, archiveQuery = true) +
                if (fundsResultControl != null) mapOf("SPLIT_TRANSFER_BATCH_ENABLED" to "false") else emptyMap()
        val settings by lazy { splitTradingSettings(environment) }
        private var process: Process? = null
        private lateinit var log: Path
        private var crashArmed = false
        private var generation = 0
        val pid: Long get() = checkNotNull(process).pid()

        fun start(recover: Boolean) {
            log = directory.resolve("${role.name.lowercase()}-${generation++}-recover-$recover.log")
            val command =
                if (role == TradingServiceRole.MATCHING && matchingCrashDispatch != null) {
                    tradingProcessCommand(
                        "com.exchange.core.api.trading.infrastructure.aeron.MatchingCommitCrashNodeKt",
                        classpathProperty = "fundingVerificationRuntimeClasspath",
                    ) + listOf(crashMarker(recover).toString(), matchingCrashDispatch.toString())
                } else if (fundsResultControl != null) {
                    tradingProcessCommand(
                        "com.exchange.core.api.trading.infrastructure.aeron.GtcFundsRecoveryNodeKt",
                        classpathProperty = "fundingVerificationRuntimeClasspath",
                    ) + directory.toString()
                } else {
                    tradingProcessCommand("com.exchange.core.api.trading.infrastructure.aeron.DevelopmentSplitTradingMainKt")
                }
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
            crashArmed = role == TradingServiceRole.MATCHING && matchingCrashDispatch != null && !recover
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

        fun cleanup() {
            val running = process ?: return
            if (running.isAlive && crashArmed) {
                kill()
            } else {
                running.cleanupTradingProcess()
            }
        }

        private fun crashMarker(recover: Boolean): Path = directory.resolve("matching-commit-recover-$recover.json")

        fun awaitCrashMarker(
            recover: Boolean,
            orderId: String,
        ): JsonNode {
            val markerPath = crashMarker(recover)
            val running = checkNotNull(process)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (running.isAlive && System.nanoTime() < deadline) {
                if (Files.isRegularFile(markerPath)) {
                    val marker = json.readTree(Files.readString(markerPath))
                    val stage = if (recover) "APPLIED" else "BEFORE_APPLY"
                    val clusterRole = if (recover) "FOLLOWER" else "LEADER"
                    check(
                        marker.path("pid").longValue() == running.pid() && marker.path("stage").asString() == stage &&
                            marker.path("clusterRole").asString() == clusterRole &&
                            marker.path("marketId").asString() == "BTC-USDT" && marker.path("orderId").asString() == orderId &&
                            marker.path("dispatchSequence").longValue() == matchingCrashDispatch &&
                            marker.path("position").longValue() > 0L,
                    ) { "대상 주문의 매칭 위치 표식이 다릅니다: $marker" }
                    return marker
                }
                Thread.sleep(25)
            }
            error("매칭 PID ${running.pid()}의 표식을 관찰하지 못했습니다: $markerPath")
        }

        fun kill(): Int {
            val running = checkNotNull(process)
            check(running.isAlive) { "강제 종료 요청 전에 $role JVM이 종료됐습니다" }
            running.destroyForcibly()
            check(running.waitFor(5, TimeUnit.SECONDS)) { "강제 종료한 $role JVM을 회수하지 못했습니다" }
            return running.exitValue()
        }

        fun awaitInactiveRuntime() {
            val transport = settings.transport
            val driver = Path.of(transport.driverDirectory).toFile()
            val driverTimeoutMs = MediaDriver.Context().driverTimeoutMs()
            // 각 구성요소가 별도로 heartbeat를 쓰므로 Driver 만료만으로 재기동하면 경합이 생긴다.
            val clusterTimeoutMs = ClusteredServiceContainer.Configuration.LIVENESS_TIMEOUT_MS
            val clusterFiles = listOf(ClusterMarkFile.FILENAME, ClusterMarkFile.markFilenameForService(transport.serviceId))
            val clusterActivity =
                clusterFiles.maxOf { markName ->
                    ClusterMarkFile(transport.clusterDirectory.toFile(), markName, System::currentTimeMillis, 0, null).use { mark ->
                        mark.activityTimestampVolatile()
                    }
                }
            val archiveActivity =
                ArchiveMarkFile(
                    transport.archiveDirectory.toFile(),
                    ArchiveMarkFile.FILENAME,
                    System::currentTimeMillis,
                    0,
                    null,
                ).use { mark ->
                    mark.activityTimestampVolatile()
                }
            val archiveTimeoutMs = TimeUnit.SECONDS.toMillis(10) // Aeron 1.53.3 Archive liveness timeout
            val marksExpireAt = maxOf(clusterActivity + clusterTimeoutMs, archiveActivity + archiveTimeoutMs)
            val timeoutMs = maxOf(driverTimeoutMs, clusterTimeoutMs, archiveTimeoutMs)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs * 2)
            while (System.currentTimeMillis() <= marksExpireAt || CommonContext.isDriverActive(driver, driverTimeoutMs) {}) {
                check(System.nanoTime() < deadline) { "강제 종료한 $role 노드의 heartbeat가 만료되지 않았습니다" }
                Thread.sleep(50)
            }
        }

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
                val positions = logPositions(aeron.countersReader())
                minOf(positions.committed, positions.applied)
            }
        }

        fun logBoundary(): LogBoundary {
            val transport = settings.transport
            val context = transport.archiveClient().messageTimeoutNs(TimeUnit.SECONDS.toNanos(3))
            return AeronArchive.connect(context).use { archive ->
                val reader = archive.context().aeron().countersReader()
                val termId = ClusterCounters.find(reader, AeronCounters.CLUSTER_LEADERSHIP_TERM_ID_TYPE_ID, settings.clusterId)
                check(termId != CountersReader.NULL_COUNTER_ID) { "$role 임기를 읽을 수 없습니다" }
                val term = reader.getCounterValue(termId)
                val recordingId =
                    RecordingLog(transport.clusterDirectory.toFile(), false).use { log ->
                        log.getTermEntry(term).recordingId
                    }
                val recordingCounter = RecordingPos.findCounterIdByRecording(reader, recordingId, settings.clusterId.toLong())
                check(RecordingPos.isActive(reader, recordingCounter, recordingId)) { "$role 임기의 Archive 기록이 활성 상태가 아닙니다" }
                val recorded = minOf(reader.getCounterValue(recordingCounter), archive.getMaxRecordedPosition(recordingId))
                val positions = logPositions(reader)
                check(reader.getCounterValue(termId) == term) { "$role 로그 위치를 읽는 동안 임기가 바뀌었습니다" }
                LogBoundary(positions.committed, positions.applied, recordingId, recorded)
            }
        }

        private fun logPositions(counters: CountersReader): LogPositions {
            val committedId = ClusterCounters.find(counters, AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID, settings.clusterId)
            val label =
                splitArchiveAppliedLabel(settings.clusterId, settings.transport.memberId, settings.configuration.resultTopologySha256)
            var appliedId = CountersReader.NULL_COUNTER_ID
            counters.forEach { id, type, _, name ->
                if (type == SPLIT_ARCHIVE_APPLIED_TYPE_ID && name == label) {
                    appliedId = id
                }
            }
            check(committedId != CountersReader.NULL_COUNTER_ID && appliedId != CountersReader.NULL_COUNTER_ID) {
                "$role 확정·적용 위치를 읽을 수 없습니다"
            }
            return LogPositions(counters.getCounterValue(committedId), counters.getCounterValue(appliedId))
        }
    }
}

internal fun <T> verifyGtcRestart(
    directory: Path,
    matchingCrashDispatch: Long? = null,
    controlFundsResults: Boolean = false,
    verify: (OrderQueryTestDatabase, GtcRestartCluster, HttpClient) -> T,
): T =
    OrderQueryTestDatabase(socketTimeoutSeconds = 2).use { database ->
        GtcRestartCluster(directory, database, matchingCrashDispatch, controlFundsResults).use { cluster ->
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
