package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionReceipt
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.CancelReceipt
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingServiceRole
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val multiJson = JsonMapper.builder().build()
private val multiControl = Path.of(System.getenv("FUNDING_CONTROL_DIRECTORY") ?: "/control")
private val multiOrderCodec = SplitOrderDeliveryCodec(2)
private val multiResultCodec = SplitResultDeliveryCodec(2)
private val multiBuyer = UserId("buyer")
private val multiSeller = UserId("seller")

private fun multiLabel(marketId: String): String =
    when (marketId) {
        "BTC-USDT" -> "btc"
        "SUI-USDT" -> "sui"
        else -> error("다중 매칭 시험에 없는 마켓입니다: $marketId")
    }

private fun multiMarket(label: String): MarketId =
    MarketId(
        when (label) {
            "btc" -> "BTC-USDT"
            "sui" -> "SUI-USDT"
            else -> error("다중 매칭 시험의 담당은 btc/sui입니다")
        },
    )

private fun multiAdmission(receipt: AdmissionReceipt): Map<String, Any?> =
    mapOf(
        "code" to receipt.code.name,
        "orderId" to receipt.orderId?.value,
        "admissionSequence" to receipt.admissionSequence,
        "marketAdmissionSequence" to receipt.marketAdmissionSequence,
        "reason" to receipt.reason?.name,
        "insufficientBalance" to
            receipt.insufficientBalance?.let {
                mapOf(
                    "assetId" to it.assetId.value,
                    "required" to it.required.value,
                    "available" to it.available.value,
                    "shortfall" to it.shortfall.value,
                )
            },
    )

private fun multiCancellation(receipt: CancelReceipt): Map<String, Any?> =
    mapOf(
        "code" to receipt.code.name,
        "cancelId" to receipt.cancelId,
        "targetOrderId" to receipt.targetOrderId?.value,
        "inputSequence" to receipt.inputSequence,
        "reason" to receipt.reason,
    )

private fun multiReply(reply: SplitOrderDeliveryReply): Map<String, Any?> =
    mapOf(
        "code" to reply.code,
        "receipt" to (reply.control?.admission?.let(::multiAdmission) ?: reply.cancellation?.let(::multiCancellation)),
    )

/** 원문 대신 시험에 필요한 결과 의미만 유한하게 관찰한다. 금액 정답은 이 함수가 만들지 않는다. */
private fun multiResult(result: MatchingResultEnvelope): Map<String, Any?> {
    check(result.events.size <= 8 && result.effects.size <= 8)
    return mapOf(
        "marketId" to result.stream.marketId.value,
        "resultSequence" to result.resultSequence,
        "resultId" to result.resultId,
        "orderId" to result.input.order.orderId.value,
        "cancelId" to result.input.cancel?.cancelId,
        "inputSequence" to result.input.dispatchSequence,
        "outcome" to result.outcome.name,
        "engineBefore" to result.engineSequenceBefore,
        "engineAfter" to result.engineSequenceAfter,
        "events" to
            result.events.map { event ->
                mapOf<String, Any?>("engineSequence" to event.engineSequence) +
                    when (event) {
                        is TradeExecuted -> {
                            mapOf(
                                "kind" to "TRADE",
                                "makerOrderId" to event.makerOrderId.value,
                                "takerOrderId" to event.takerOrderId.value,
                                "quantity" to event.quantity.value,
                                "price" to event.price.value,
                            )
                        }

                        is OrderEnteredBook -> {
                            mapOf(
                                "kind" to "RESTING",
                                "orderId" to event.orderId.value,
                                "quantity" to event.remainingQuantity.value,
                                "price" to event.price.value,
                            )
                        }

                        is OrderCancelled -> {
                            mapOf(
                                "kind" to "CANCELLED",
                                "orderId" to event.orderId.value,
                                "quantity" to event.remainingQuantity.value,
                            )
                        }

                        is OrderCancelRejected -> {
                            mapOf(
                                "kind" to "CANCEL_REJECTED",
                                "orderId" to event.orderId.value,
                                "reason" to event.reason,
                            )
                        }
                    }
            },
        "effects" to
            result.effects.map {
                mapOf(
                    "orderId" to it.orderId.value,
                    "originInputSequence" to it.origin.dispatchSequence,
                    "beforeQuantity" to it.beforeQuantity.value,
                    "afterQuantity" to it.afterQuantity.value,
                    "disposition" to it.disposition.name,
                )
            },
    )
}

/** 관찰은 서비스 소유 스레드에서 복사한다. 전송·정산 경로는 실제 노드의 기본 worker를 쓴다. */
private class MultiMatchingService(
    private val delegate: SplitTradingClusterAdapter,
    private val settings: SplitTradingNodeSettings,
    private val boot: String,
    private val service: String,
    private val manifestSha: String,
) : ClusteredService by delegate {
    private lateinit var cluster: Cluster
    private val raceRows = linkedMapOf<String, Map<String, Any?>>()
    private val retained = linkedMapOf<String, MatchingResultEnvelope>()
    private val field =
        delegate.javaClass
            .getDeclaredField(if (settings.role == TradingServiceRole.FUNDS) "funds" else "matching")
            .also { it.isAccessible = true }

    override fun onStart(
        cluster: Cluster,
        snapshotImage: Image?,
    ) {
        this.cluster = cluster
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
        val bytes = ByteArray(length).also { buffer.getBytes(offset, it) }
        val order =
            if (length >= 8 && (buffer.getShort(offset + 4, ByteOrder.LITTLE_ENDIAN).toInt() and 65535) == 88) {
                multiOrderCodec.decode(bytes).command
            } else {
                null
            }
        val result =
            if (length >= 12 && (buffer.getShort(offset, ByteOrder.LITTLE_ENDIAN).toInt() and 65535) == 89) {
                multiResultCodec.decode(bytes).command
            } else {
                null
            }
        delegate.onSessionMessage(session, timestamp, buffer, offset, length, header)
        if (settings.role == TradingServiceRole.FUNDS && order is SplitOrderDeliveryCommand.Control &&
            order.command is BoundedTradingCommand.Admit && delegate.lifecycle == TradingLifecycle.RUNNING
        ) {
            val admitted = order.command as BoundedTradingCommand.Admit
            val token = admitted.token
            if (token.userId == multiBuyer && token.sequence == 1L && token.channel in 0..1 &&
                admitted.intent.marketId.value !in raceRows
            ) {
                val state = (field.get(delegate) as BoundedFundingState).fundsRecoveryState()
                val receipt =
                    state.financial.inbox.channels
                        .single {
                            it.userId == token.userId && it.channel == token.channel && it.epoch == token.epoch
                        }.entries
                        .single { it.sequence == token.sequence }
                        .receipt
                check(raceRows.size < 2)
                raceRows[admitted.intent.marketId.value] =
                    mapOf(
                        "marketId" to admitted.intent.marketId.value,
                        "channel" to token.channel,
                        "epoch" to token.epoch,
                        "sequence" to token.sequence,
                        "position" to cluster.logPosition(),
                        "code" to receipt.code.name,
                        "orderId" to receipt.orderId?.value,
                    )
            }
        }
        if (settings.role == TradingServiceRole.MATCHING && result is SplitResultDeliveryCommand.RecordAck &&
            cluster.role() == Cluster.Role.LEADER && delegate.lifecycle == TradingLifecycle.RUNNING
        ) {
            val owner = field.get(delegate) as ReservedMatchingState
            val state = owner.recoveryState()
            if (state.streams.any {
                    owner.configuration.resultStream(it.stream.marketId) == result.ack.stream &&
                        it.fundsSettledThrough >= result.ack.through
                }
            ) {
                writeBoundedHttpArtifact(multiControl.resolve("${multiLabel(result.ack.stream.marketId.value)}/ack.bin"), bytes, true)
            }
        }
        capture()
    }

    override fun onTakeSnapshot(snapshotPublication: ExclusivePublication) {
        delegate.onTakeSnapshot(snapshotPublication)
        capture()
    }

    override fun onRoleChange(newRole: Cluster.Role) {
        delegate.onRoleChange(newRole)
        capture()
    }

    override fun doBackgroundWork(nowNs: Long): Int = delegate.doBackgroundWork(nowNs)

    private fun capture() {
        val values =
            multiIdentity(settings, boot, service, manifestSha).toMutableMap().apply {
                put("initialized", delegate.tradingReady)
                put("lifecycle", delegate.lifecycle.name)
                put("logPosition", cluster.logPosition())
                put("clusterRole", cluster.role().name)
                put("raceRows", raceRows.values.toList())
            }
        if (delegate.lifecycle == TradingLifecycle.RUNNING) {
            if (settings.role == TradingServiceRole.FUNDS) {
                values["funds"] = funds()
            } else {
                values["matching"] = matching()
            }
        }
        val bytes = multiJson.writeValueAsBytes(values)
        check(bytes.size <= 256 * 1024) { "9사본 관찰 파일 한도를 넘었습니다" }
        writeBoundedHttpArtifact(multiControl.resolve("state-$service.json"), bytes, true)
    }

    private fun funds(): Map<String, Any?> {
        val saved = (field.get(delegate) as BoundedFundingState).fundsRecoveryState()
        val results = checkNotNull(saved.results)
        val receipts =
            saved.financial.inbox.channels.flatMap { channel ->
                channel.entries.map { entry ->
                    mapOf(
                        "userId" to channel.userId.value,
                        "channel" to channel.channel,
                        "epoch" to channel.epoch,
                        "sequence" to entry.sequence,
                        "kind" to "ORDER",
                        "marketId" to entry.intent.marketId.value,
                        "receipt" to multiAdmission(entry.receipt),
                    )
                } +
                    channel.cancelEntries.map { entry ->
                        mapOf(
                            "userId" to channel.userId.value,
                            "channel" to channel.channel,
                            "epoch" to channel.epoch,
                            "sequence" to entry.sequence,
                            "kind" to "CANCEL",
                            "marketId" to entry.intent.marketId.value,
                            "receipt" to multiCancellation(entry.receipt),
                        )
                    }
            }
        check(receipts.size <= 6 && results.receipts.size <= 6)
        return mapOf(
            "balances" to
                saved.financial.balances.sortedWith(compareBy({ it.userId.value }, { it.assetId.value })).map {
                    mapOf(
                        "userId" to it.userId.value,
                        "assetId" to it.assetId.value,
                        "available" to it.available.value,
                        "hold" to it.hold.value,
                    )
                },
            "fees" to
                saved.financial.feeRevenueByAsset.entries
                    .associate { it.key.value to it.value.toString() },
            "lastAdmissionSequence" to saved.financial.lastAdmissionSequence,
            "lastCancellationSequence" to saved.lastCancellationSequence,
            "dispatch" to
                saved.dispatch.streams.associate {
                    it.stream.marketId.value to
                        mapOf(
                            "issued" to it.issued,
                            "matchingAppliedThrough" to it.matchingAppliedThrough,
                            "dispatchEntries" to it.outstanding.size,
                            "dispatchBytes" to it.dispatchBytes,
                            "activeOrders" to it.activeOrders,
                            "activeBytes" to it.activeBytes,
                        )
                },
            "resultProgress" to
                results.streams.associate {
                    it.stream.marketId.value to
                        mapOf(
                            "resultThrough" to it.resultThrough,
                            "settledEngineSequence" to it.settledEngineSequence,
                            "retiredThrough" to it.retiredThrough,
                        )
                },
            "dispatchEntries" to saved.dispatch.dispatchEntries,
            "dispatchBytes" to saved.dispatch.dispatchBytes,
            "activeOrders" to saved.dispatch.activeOrders,
            "activeBytes" to saved.dispatch.activeBytes,
            "pendingOrders" to saved.financial.markets.sumOf { it.pendingAdmissionSequences.size },
            "liveOrders" to saved.financial.liveOrders.size,
            "receipts" to
                receipts.sortedWith(compareBy({ it["userId"].toString() }, { it["channel"].toString() }, { it["sequence"].toString() })),
            "resultDetails" to results.receipts.map { multiResult(it.result) + ("appliedAt" to it.appliedAt.toString()) },
        )
    }

    private fun matching(): Map<String, Any?> {
        val saved = (field.get(delegate) as ReservedMatchingState).recoveryState()
        for (stream in saved.streams) {
            for (result in stream.outstanding) {
                val previous = retained.putIfAbsent(result.resultId, result)
                check(previous == null || previous == result) { "최초 매칭 결과가 바뀌었습니다" }
                check(retained.size <= 3) { "시험 관찰 결과 상한을 넘었습니다" }
                if (cluster.role() == Cluster.Role.LEADER && previous == null) {
                    writeBoundedHttpArtifact(
                        multiControl.resolve("${multiLabel(result.stream.marketId.value)}/result-${result.resultSequence}.bin"),
                        multiResultCodec.encode(1, SplitResultDeliveryCommand.Apply(result)),
                        true,
                    )
                }
            }
        }
        return mapOf(
            "markets" to
                saved.streams.associate { stream ->
                    val book = saved.matching.markets.singleOrNull { it.marketId == stream.stream.marketId }
                    stream.stream.marketId.value to
                        mapOf(
                            "orders" to
                                (book?.orders ?: emptyList()).map {
                                    mapOf(
                                        "orderId" to it.orderId.value,
                                        "userId" to it.userId.value,
                                        "side" to it.side.name,
                                        "price" to it.price.value,
                                        "originalQuantity" to it.originalQuantity.value,
                                        "remainingQuantity" to it.remainingQuantity.value,
                                    )
                                },
                            "engineSequence" to (book?.lastEngineSequence ?: 0L),
                            "appliedThrough" to stream.appliedThrough,
                            "resultIssued" to stream.resultIssued,
                            "fundsSettledThrough" to stream.fundsSettledThrough,
                        )
                },
            "resultEntries" to saved.resultEntries,
            "resultBytes" to saved.resultBytes,
            "activeOrders" to saved.activeOrders,
            "activeBytes" to saved.activeBytes,
            "resultDetails" to retained.values.map(::multiResult),
        )
    }
}

private fun multiIdentity(
    settings: SplitTradingNodeSettings,
    boot: String,
    service: String,
    manifestSha: String,
): Map<String, Any?> {
    val config = settings.configuration
    val lineage = if (settings.role == TradingServiceRole.FUNDS) config.fundsLineage else config.matchingLineage
    val markets =
        if (settings.role ==
            TradingServiceRole.FUNDS
        ) {
            config.topology.fundsManifest.markets
        } else {
            config.matchingDeclaration.markets
        }
    return mapOf(
        "runId" to settings.transport.runId,
        "service" to service,
        "bootId" to boot,
        "role" to settings.role.name,
        "memberId" to settings.transport.memberId,
        "clusterId" to settings.clusterId,
        "lineageHigh" to lineage.high,
        "lineageLow" to lineage.low,
        "markets" to markets.map { it.market.marketId.value },
        "configurationVersion" to config.topology.fundsManifest.configurationVersion,
        "manifestSha256" to manifestSha,
        "topologySha256" to config.topologySha256,
        "resultTopologySha256" to config.resultTopologySha256,
        "pid" to ProcessHandle.current().pid(),
    )
}

private fun multiNode() {
    val settings = splitTradingSettings()
    val service = requireNotNull(System.getenv("SPLIT_MULTI_SERVICE"))
    val boot = UUID.randomUUID().toString()
    val sha =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(Path.of(requireNotNull(System.getenv("SPLIT_MANIFEST")))))
            .joinToString("") { "%02x".format(it) }
    DevelopmentSplitTradingNode
        .start(settings, serviceDecorator = { MultiMatchingService(it, settings, boot, service, sha) })
        .use { node ->
            Runtime.getRuntime().addShutdownHook(Thread(node::close))
            val transport = settings.transport
            writeSplitTestJson(
                multiControl.resolve("runtime-$service.json"),
                multiIdentity(settings, boot, service, sha) +
                    mapOf(
                        "storageLevels" to node.storageLevels,
                        "members" to transport.members,
                        "driverDirectory" to transport.driverDirectory,
                        "runRoot" to transport.runRoot.toString(),
                        "artifactSha256" to BoundedExecutionIdentity.artifactSha256,
                        "schemaSha256" to BoundedExecutionIdentity.schemaSha256,
                        "inputContractVersion" to settings.configuration.inputContractVersion,
                        "mode" to settings.mode,
                        "jdkVersion" to System.getProperty("java.version"),
                    ),
            )
            while (!node.isClosed && node.error == null) Thread.sleep(10)
            node.error?.let { throw IllegalStateException("다중 매칭 노드 실패", it) }
        }
}

/** RDB 조회로 만든 정책·한도는 유지하고 실제 실행 이미지의 identity에만 결속한다. */
private fun freezeMultiManifest() {
    val path = Path.of(requireNotNull(System.getenv("SPLIT_MANIFEST")))
    val sourceBytes = Files.readAllBytes(path)
    val codec = BoundedTradingStateCodec()
    val source = codec.decodeManifest(sourceBytes)
    check(source.schemaSha256 == BoundedExecutionIdentity.schemaSha256) { "RDB 준비 자료와 실행 이미지의 schema가 다릅니다" }
    val bound =
        TradingClusterManifest(
            source.lineage,
            source.algorithmId,
            BoundedExecutionIdentity.artifactSha256,
            source.schemaSha256,
            source.configurationVersion,
            source.markets,
            source.limits,
        )
    val bytes = codec.encodeManifest(bound)

    fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    writeBoundedHttpArtifact(multiControl.resolve("manifest-rdb-input.bin"), sourceBytes, false)
    writeBoundedHttpArtifact(path, bytes, true)
    println(
        multiJson.writeValueAsString(
            mapOf(
                "code" to "FROZEN",
                "sourceManifestSha256" to sha(sourceBytes),
                "manifestSha256" to sha(bytes),
                "artifactSha256" to bound.executionArtifactSha256,
                "schemaSha256" to bound.schemaSha256,
                "runtimeVersion" to Runtime.version().toString(),
                "runtimeVendor" to System.getProperty("java.vendor"),
            ),
        ),
    )
}

private fun multiCommand(args: Array<String>) {
    val env = System.getenv()
    val manifest = BoundedTradingStateCodec().decodeManifest(Files.readAllBytes(Path.of(requireNotNull(env["SPLIT_MANIFEST"]))))
    val configuration = splitTradingConfiguration(manifest, env)
    MediaDriver
        .launch(
            MediaDriver
                .Context()
                .aeronDirectoryName("/tmp/multi-client-${UUID.randomUUID()}")
                .dirDeleteOnStart(false)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.SHARED),
        ).use { driver ->
            fun endpoint(label: String? = null): SplitOrderDeliveryEndpoint {
                val index =
                    label?.let { wanted ->
                        (0 until 2).single {
                            env["SPLIT_MATCHING_${it}_MARKETS"]?.split(',')?.map(String::trim)?.contains(multiMarket(wanted).value) ==
                                true
                        }
                    }
                return SplitOrderDeliveryEndpoint(
                    driver.aeronDirectoryName(),
                    requireNotNull(env[if (index == null) "SPLIT_FUNDS_INGRESS" else "SPLIT_MATCHING_${index}_INGRESS"]),
                    "${env["FUNDING_HOST"] ?: "client"}:0",
                    if (index ==
                        null
                    ) {
                        198
                    } else {
                        requireNotNull(env["SPLIT_MATCHING_${index}_CLUSTER_ID"]).toInt()
                    },
                    2,
                )
            }

            fun client(label: String? = null) = SplitOrderDeliveryClient(endpoint(label), 10_000)

            fun order(
                who: String,
                label: String,
                sequence: Long,
                quantity: Long,
                side: Side,
            ): SplitOrderDeliveryCommand {
                require(who in setOf("buyer", "seller"))
                return SplitOrderDeliveryCommand.Control(
                    BoundedTradingCommand.Admit(
                        RequestToken(if (who == "buyer") multiBuyer else multiSeller, if (label == "btc") 0 else 1, 1, sequence),
                        OrderIntent(
                            multiMarket(label),
                            manifest.configurationVersion,
                            side,
                            OrderType.LIMIT,
                            TimeInForce.GTC,
                            Price(100),
                            Quantity(quantity),
                        ),
                    ),
                )
            }
            val output: Any =
                when (args[0]) {
                    "prepare" -> {
                        for (label in listOf(null, "btc", "sui")) {
                            client(label).use {
                                check(
                                    it.request(SplitOrderDeliveryCommand.InitializeRole(configuration.topologySha256)).code in
                                        setOf("INITIALIZED", "ALREADY_INITIALIZED"),
                                )
                            }
                        }
                        client().use { funds ->
                            val openings = mutableListOf(Triple(multiBuyer, "USDT", 1_000L))
                            if (env["SPLIT_MULTI_CASE"] ==
                                "settlement"
                            ) {
                                openings.addAll(listOf(Triple(multiSeller, "BTC", 1L), Triple(multiSeller, "SUI", 1L)))
                            }
                            for ((user, asset, amount) in openings) {
                                check(
                                    funds
                                        .request(
                                            SplitOrderDeliveryCommand.Control(
                                                BoundedTradingCommand.Funding(
                                                    DevelopmentBalanceCommandPayload(
                                                        OpeningBalance(
                                                            "multi135-${user.value}-$asset",
                                                            user,
                                                            AssetId(asset),
                                                            Amount(amount),
                                                        ),
                                                        "multi135-ledger",
                                                        Instant.parse("2026-11-16T00:00:00Z"),
                                                    ),
                                                ),
                                            ),
                                        ).code == "PREPARED",
                                )
                            }
                            for (user in openings.map { it.first }.distinct()) {
                                for (channel in 0..1) {
                                    check(
                                        funds
                                            .request(
                                                SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(user, channel, 0)),
                                            ).control
                                            ?.epoch ==
                                            1L,
                                    )
                                }
                            }
                        }
                        mapOf("code" to "PREPARED")
                    }

                    "race" -> {
                        val ready = CountDownLatch(2)
                        val go = CountDownLatch(1)
                        val readiness = ConcurrentHashMap<String, Map<String, Long>>()
                        val executor = Executors.newFixedThreadPool(2)
                        try {
                            val futures =
                                listOf("btc", "sui").associateWith { label ->
                                    executor.submit(
                                        Callable {
                                            var signalled = false
                                            try {
                                                client().use { funds ->
                                                    readiness[label] =
                                                        mapOf(
                                                            "threadId" to Thread.currentThread().threadId(),
                                                            "readyNs" to System.nanoTime(),
                                                        )
                                                    ready.countDown().also { signalled = true }
                                                    check(go.await(15, TimeUnit.SECONDS)) { "경쟁 입력 GO 대기 초과" }
                                                    multiReply(
                                                        funds.request(
                                                            order(
                                                                "buyer",
                                                                label,
                                                                1,
                                                                if (label ==
                                                                    "btc"
                                                                ) {
                                                                    6
                                                                } else {
                                                                    5
                                                                },
                                                                Side.BUY,
                                                            ),
                                                        ),
                                                    )
                                                }
                                            } finally {
                                                if (!signalled) ready.countDown()
                                            }
                                        },
                                    )
                                }
                            check(ready.await(15, TimeUnit.SECONDS)) { "두 독립 연결 준비 대기 초과" }
                            check(readiness.keys == setOf("btc", "sui")) { "두 독립 연결이 준비되지 않았습니다" }
                            val goNs = System.nanoTime()
                            go.countDown()
                            val replies = futures.mapValues { it.value.get(15, TimeUnit.SECONDS) }
                            mapOf(
                                "btc" to replies.getValue("btc"),
                                "sui" to replies.getValue("sui"),
                                "readiness" to readiness.toSortedMap(),
                                "goNs" to goNs,
                            )
                        } finally {
                            go.countDown()
                            executor.shutdownNow()
                            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "경쟁 입력 client 스레드 정리 실패" }
                        }
                    }

                    "admit" -> {
                        client().use {
                            multiReply(
                                it.request(
                                    order(
                                        args[1],
                                        args[2],
                                        args[3].toLong(),
                                        args[4].toLong(),
                                        args.getOrNull(5)?.let(Side::valueOf) ?: if (args[1] == "seller") Side.SELL else Side.BUY,
                                    ),
                                ),
                            )
                        }
                    }

                    "cancel" -> {
                        client().use {
                            multiReply(
                                it.request(
                                    SplitOrderDeliveryCommand.Cancel(
                                        RequestToken(multiBuyer, if (args[1] == "btc") 0 else 1, 1, args[2].toLong()),
                                        CancelIntent(multiMarket(args[1]), OrderId(args[3]), manifest.configurationVersion),
                                    ),
                                ),
                            )
                        }
                    }

                    "replay-result", "replay-ack" -> {
                        val ack = args[0] == "replay-ack"
                        val file = multiControl.resolve("${args[1]}/${if (ack) "ack.bin" else "result-${args[2]}.bin"}")
                        val command = multiResultCodec.decode(Files.readAllBytes(file)).command
                        check(if (ack) command is SplitResultDeliveryCommand.RecordAck else command is SplitResultDeliveryCommand.Apply)
                        DevelopmentSplitResultDeliveryClient(endpoint(if (ack) args[1] else null), 10_000).use {
                            val reply = it.request(command)
                            mapOf("code" to reply.code, "through" to reply.ack?.through)
                        }
                    }

                    else -> {
                        error("지원하지 않는 다중 매칭 시험 명령입니다")
                    }
                }
            println(multiJson.writeValueAsString(output))
        }
}

fun main(args: Array<String>) {
    require(args.isNotEmpty())
    listOf("btc", "sui").forEach { Files.createDirectories(multiControl.resolve(it)) }
    when (args[0]) {
        "node" -> multiNode()
        "freeze-manifest" -> freezeMultiManifest()
        else -> multiCommand(args)
    }
}
