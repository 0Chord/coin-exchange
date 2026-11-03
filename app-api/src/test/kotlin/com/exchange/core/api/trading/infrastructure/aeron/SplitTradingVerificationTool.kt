package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingServiceRole
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.Publication
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private val splitTestJson = JsonMapper.builder().build()
private val splitControl = Path.of(System.getenv("FUNDING_CONTROL_DIRECTORY") ?: "/control")

// 실제 전송에서도 MTU를 넘도록 허용 경계인 64 codepoint·256 UTF-8 byte를 사용한다.
private val splitBuyer = UserId("😀".repeat(64))

/** 명시한 실제 서비스 호출만 관찰한다. 제품 callback에는 시험용 관찰 큐를 추가하지 않는다. */
private class SplitVerificationService(
    private val delegate: SplitTradingClusterAdapter,
    private val settings: SplitTradingNodeSettings,
    private val bootId: String,
) : ClusteredService by delegate {
    private lateinit var cluster: Cluster
    private var snapshots = 0
    private var snapshotPosition = 0L
    private var restoredSnapshot = false

    override fun onStart(
        cluster: Cluster,
        snapshotImage: Image?,
    ) {
        this.cluster = cluster
        restoredSnapshot = snapshotImage != null
        delegate.onStart(cluster, snapshotImage)
        capture()
    }

    override fun onSessionMessage(
        session: ClientSession?,
        timestamp: Long,
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
        header: Header,
    ) {
        delegate.onSessionMessage(session, timestamp, buffer, offset, length, header)
        capture()
    }

    override fun onTakeSnapshot(snapshotPublication: ExclusivePublication) {
        delegate.onTakeSnapshot(snapshotPublication)
        snapshots++
        snapshotPosition = cluster.logPosition()
        capture()
    }

    override fun onRoleChange(newRole: Cluster.Role) {
        delegate.onRoleChange(newRole)
        capture()
    }

    // Java default callback은 Kotlin의 인터페이스 위임만으로 전달되지 않는다.
    override fun doBackgroundWork(nowNs: Long): Int = delegate.doBackgroundWork(nowNs)

    private fun capture() {
        val values =
            linkedMapOf<String, Any?>(
                "role" to settings.role.name,
                "memberId" to settings.transport.memberId,
                "pid" to ProcessHandle.current().pid(),
                "bootId" to bootId,
                "initialized" to delegate.tradingReady,
                "logPosition" to cluster.logPosition(),
                "clusterRole" to cluster.role().name,
                "snapshotCount" to snapshots,
                "lastSnapshotPosition" to snapshotPosition,
                "restoredSnapshot" to restoredSnapshot,
            )
        if (delegate.lifecycle == TradingLifecycle.RUNNING) {
            // 시험 wrapper는 같은 서비스 callback 안에서만 소유 상태를 읽는다.
            val field = delegate.javaClass.getDeclaredField(if (settings.role == TradingServiceRole.FUNDS) "funds" else "matching")
            field.isAccessible = true
            if (settings.role == TradingServiceRole.FUNDS) {
                val saved = (field.get(delegate) as BoundedFundingState).fundsRecoveryState()
                val balance = saved.financial.balances.singleOrNull { it.userId == splitBuyer && it.assetId == AssetId("USDT") }
                val dispatch = saved.dispatch.streams.single()
                val progress = saved.financial.markets.single()
                values["funds"] =
                    mapOf(
                        "available" to (balance?.available?.value ?: 0),
                        "hold" to (balance?.hold?.value ?: 0),
                        "dispatchEntries" to saved.dispatch.dispatchEntries,
                        "activeOrders" to saved.dispatch.activeOrders,
                        "pendingOrders" to progress.pendingAdmissionSequences.size,
                        "matchingAppliedThrough" to dispatch.matchingAppliedThrough,
                        "issued" to dispatch.issued,
                        "executedThrough" to progress.executedThrough,
                        "resultThrough" to checkNotNull(saved.results).streams.single().resultThrough,
                    )
            } else {
                val saved = (field.get(delegate) as ReservedMatchingState).recoveryState()
                val market = saved.matching.markets.singleOrNull()
                val stream = saved.streams.single()
                values["matching"] =
                    mapOf(
                        "bookOrders" to
                            (market?.orders ?: emptyList()).map {
                                mapOf(
                                    "orderId" to it.orderId.value,
                                    "remainingQuantity" to it.remainingQuantity.value,
                                )
                            },
                        "engineSequence" to (market?.lastEngineSequence ?: 0L),
                        "appliedThrough" to stream.appliedThrough,
                        "resultIssued" to stream.resultIssued,
                        "resultEntries" to saved.resultEntries,
                        "fundsSettledThrough" to stream.fundsSettledThrough,
                    )
            }
        }
        writeSplitTestJson(splitControl.resolve("state-${settings.role.name.lowercase()}${settings.transport.memberId}.json"), values)
    }
}

private fun writeSplitTestJson(
    path: Path,
    value: Any,
) {
    val temporary = Files.createTempFile(path.parent, ".${path.fileName}", ".tmp")
    try {
        Files.writeString(temporary, splitTestJson.writeValueAsString(value))
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/** 유실은 시험 소스에서만 만든다. 실제 Aeron 연결과 승인 원본은 그대로 전달한다. */
private fun verificationConnections(bootId: String): SplitOrderDeliveryConnectionFactory =
    SplitOrderDeliveryConnectionFactory { endpoint, timeout, onReply ->
        val attempt =
            AeronSplitOrderDeliveryConnectionFactory.begin(endpoint, timeout) { reply ->
                if (endpoint.clusterId == SplitTradingNodeSettings.MATCHING_CLUSTER_ID && reply.ack != null) {
                    writeSplitTestJson(
                        splitControl.resolve("received-matching-ack.json"),
                        mapOf(
                            "bootId" to bootId,
                            "code" to reply.code,
                            "correlationId" to reply.correlationId,
                            "through" to reply.ack.through,
                            "topologySha256" to reply.topologySha256,
                        ),
                    )
                    if (Files.exists(splitControl.resolve("hold-matching-acks"))) return@begin
                }
                onReply(reply)
            }
        object : SplitOrderDeliveryConnectAttempt {
            override fun poll(): SplitOrderDeliveryConnection? {
                val connection = attempt.poll() ?: return null
                return object : SplitOrderDeliveryConnection by connection {
                    override fun offer(bytes: ByteArray): Long {
                        val command = SplitOrderDeliveryCodec().decode(bytes).command
                        if (command is SplitOrderDeliveryCommand.Apply && command.input.dispatchSequence <= 16) {
                            Files.write(splitControl.resolve("dispatch-${command.input.dispatchSequence}.bin"), bytes)
                        }
                        if (command is SplitOrderDeliveryCommand.RecordAck && Files.exists(splitControl.resolve("hold-source-records"))) {
                            return Publication.BACK_PRESSURED
                        }
                        return connection.offer(bytes)
                    }
                }
            }

            override fun close() = attempt.close()
        }
    }

private fun splitVerificationNode() {
    val settings = splitTradingSettings()
    val bootId = UUID.randomUUID().toString()
    val workerFailure = AtomicReference<Throwable?>()
    DevelopmentSplitTradingNode
        .start(settings, { SplitVerificationService(it, settings, bootId) }) { source ->
            FundsOrderDeliveryAgent(
                settings.configuration,
                source,
                settings.endpoint(false),
                settings.endpoint(true),
                connectionFactory = verificationConnections(bootId),
                onFailure = { workerFailure.compareAndSet(null, it) },
            )
        }.use { node ->
            Runtime.getRuntime().addShutdownHook(Thread(node::close))
            val config = settings.transport
            writeSplitTestJson(
                splitControl.resolve("runtime-${settings.role.name.lowercase()}${config.memberId}.json"),
                mapOf(
                    "role" to settings.role.name,
                    "memberId" to config.memberId,
                    "pid" to ProcessHandle.current().pid(),
                    "bootId" to bootId,
                    "storageLevels" to node.storageLevels,
                    "clusterId" to settings.clusterId,
                    "members" to config.members,
                    "clusterRole" to node.role.name,
                    "runRoot" to config.runRoot.toString(),
                    "driverDirectory" to config.driverDirectory,
                    "archiveDirectory" to config.archiveDirectory.toString(),
                    "clusterDirectory" to config.clusterDirectory.toString(),
                    "artifactSha256" to BoundedExecutionIdentity.artifactSha256,
                    "schemaSha256" to BoundedExecutionIdentity.schemaSha256,
                    "topologySha256" to settings.configuration.topologySha256,
                    "jdkVersion" to System.getProperty("java.version"),
                    "termLengthBytes" to TradingAeronRuntime.TERM_LENGTH_BYTES,
                    "mtuLength" to 1408,
                ),
            )
            val request = splitControl.resolve("snapshot-${settings.role.name.lowercase()}")
            var previous = if (Files.exists(request)) Files.readString(request) else null
            while (!node.isClosed && node.error == null) {
                workerFailure.get()?.let { throw IllegalStateException("실제 전송 작업 실패", it) }
                if (Files.exists(request) && node.role == Cluster.Role.LEADER && node.tradingReady) {
                    val token = Files.readString(request)
                    if (token != previous && node.requestSnapshot()) previous = token
                }
                Thread.sleep(10)
            }
            node.error?.let { throw IllegalStateException("실제 분리 노드 실패", it) }
        }
}

private fun setupSplitVerification() {
    val context =
        TradingMarketContext(
            MarketDefinition(MarketId("🪙".repeat(64)), AssetId("X"), AssetId("USDT"), 0),
            TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(0), FeeRate(0))),
            0,
        )
    val manifest =
        TradingClusterManifest(
            ClusterLineage(168, 1),
            "split-order-delivery-v1",
            BoundedExecutionIdentity.artifactSha256,
            BoundedExecutionIdentity.schemaSha256,
            1,
            listOf(context),
            TradingResourceLimits(
                maxAccounts = 8,
                channelsPerAccount = 1,
                maxChannels = 8,
                requestWindow = 16,
                maxInboxEntries = 128,
                maxActiveOrders = 32,
                maxPendingOrders = 32,
                maxMarketActiveOrders = 32,
                maxMarketPendingOrders = 32,
                maxBalanceKeys = 32,
                maxPreparations = 16,
                maxMarkets = 1,
                maxAssets = 2,
            ),
        )
    Files.write(splitControl.resolve("manifest.bin"), BoundedTradingStateCodec().encodeManifest(manifest))
}

private fun splitVerificationCommand(args: Array<String>) {
    val env = System.getenv()
    val manifest = BoundedTradingStateCodec().decodeManifest(Files.readAllBytes(Path.of(env["SPLIT_MANIFEST"] ?: "/control/manifest.bin")))
    val configuration = splitTradingConfiguration(manifest, env)
    MediaDriver
        .launch(
            MediaDriver
                .Context()
                .aeronDirectoryName("/tmp/split-client-${UUID.randomUUID()}")
                .dirDeleteOnStart(false)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.SHARED),
        ).use { driver ->
            fun endpoint(matching: Boolean) =
                SplitOrderDeliveryEndpoint(
                    driver.aeronDirectoryName(),
                    requireNotNull(env[if (matching) "SPLIT_MATCHING_INGRESS" else "SPLIT_FUNDS_INGRESS"]),
                    "${env["FUNDING_HOST"] ?: "client"}:0",
                    if (matching) SplitTradingNodeSettings.MATCHING_CLUSTER_ID else SplitTradingNodeSettings.FUNDS_CLUSTER_ID,
                )
            SplitOrderDeliveryClient(endpoint(false), 10_000).use { funds ->
                when (args[0]) {
                    "prepare" -> {
                        SplitOrderDeliveryClient(endpoint(true), 10_000).use { matching ->
                            for (client in listOf(funds, matching)) {
                                check(
                                    client.request(SplitOrderDeliveryCommand.InitializeRole(configuration.topologySha256)).code in
                                        setOf("INITIALIZED", "ALREADY_INITIALIZED"),
                                )
                            }
                        }
                        val funded =
                            funds.request(
                                SplitOrderDeliveryCommand.Control(
                                    BoundedTradingCommand.Funding(
                                        DevelopmentBalanceCommandPayload(
                                            OpeningBalance("split168-opening", splitBuyer, AssetId("USDT"), Amount(1000)),
                                            "split168-ledger",
                                            Instant.parse("2026-11-03T00:00:00Z"),
                                        ),
                                    ),
                                ),
                            )
                        check(funded.code == "PREPARED") { funded.toString() }
                        val rotated = funds.request(SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(splitBuyer, 0, 0)))
                        check(rotated.control?.epoch == 1L) { rotated.toString() }
                    }

                    "admit" -> {
                        val reply =
                            funds.request(
                                SplitOrderDeliveryCommand.Control(
                                    BoundedTradingCommand.Admit(
                                        RequestToken(splitBuyer, 0, 1, args[1].toLong()),
                                        OrderIntent(
                                            manifest.markets
                                                .single()
                                                .market.marketId,
                                            manifest.configurationVersion,
                                            Side.BUY,
                                            OrderType.LIMIT,
                                            TimeInForce.GTC,
                                            Price(100),
                                            Quantity(args[2].toLong()),
                                        ),
                                    ),
                                ),
                            )
                        check(reply.code == "ACCEPTED") { reply.toString() }
                        println(
                            splitTestJson.writeValueAsString(
                                mapOf(
                                    "code" to reply.code,
                                    "sequence" to reply.control?.admission?.marketAdmissionSequence,
                                ),
                            ),
                        )
                    }

                    "progress" -> {
                        val reply = funds.request(SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Progress))
                        check(reply.code == "PROGRESS") { reply.toString() }
                        val control = checkNotNull(reply.control)
                        println(
                            splitTestJson.writeValueAsString(
                                mapOf(
                                    "code" to reply.code,
                                    "lifecycle" to control.lifecycle,
                                    "lastAdmissionSequence" to control.lastAdmissionSequence,
                                    "appliedLogPosition" to reply.appliedLogPosition,
                                    "markets" to
                                        control.markets.map {
                                            mapOf(
                                                "marketId" to it.marketId,
                                                "lastAssigned" to it.lastAssigned,
                                                "executedThrough" to it.executedThrough,
                                            )
                                        },
                                ),
                            ),
                        )
                    }

                    "replay" -> {
                        SplitOrderDeliveryClient(endpoint(true), 10_000).use { matching ->
                            val original =
                                SplitOrderDeliveryCodec()
                                    .decode(
                                        Files.readAllBytes(splitControl.resolve("dispatch-${args[1]}.bin")),
                                    ).command
                            val reply = matching.request(original)
                            check(reply.code in setOf("DUPLICATE", "PRUNED")) { reply.toString() }
                            println(splitTestJson.writeValueAsString(mapOf("code" to reply.code, "through" to reply.ack?.through)))
                        }
                    }

                    else -> {
                        error("알 수 없는 시험 제어 명령")
                    }
                }
            }
        }
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "setup" -> setupSplitVerification()
        "node" -> splitVerificationNode()
        "snapshot" -> Files.writeString(splitControl.resolve("snapshot-${args[1]}"), UUID.randomUUID().toString())
        else -> splitVerificationCommand(args)
    }
}
