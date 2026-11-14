package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryBoundaryFixture
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryReply
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
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.MatchingResultEnvelope
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val resultJson = JsonMapper.builder().build()
private val resultControl = Path.of(System.getenv("FUNDING_CONTROL_DIRECTORY") ?: "/control")
private val resultBuyer = UserId("😀".repeat(64))
private val resultSeller = UserId("🦊".repeat(64))
private val resultFixture = System.getenv("SPLIT_RESULT_FIXTURE") ?: "baseline"
private val resultRecoveryFixture = resultFixture in setOf("recovery", "recovery-cap")
private val resultInputContract = System.getenv("SPLIT_INPUT_CONTRACT_VERSION")?.toInt() ?: 1
private val resultOrderCodec = SplitOrderDeliveryCodec(resultInputContract)
private val resultDeliveryCodec = SplitResultDeliveryCodec(resultInputContract)

/** compose의 선택적 시험 한도가 비어 있으면 기존 제품 기본값을 그대로 사용한다. */
private fun resultVerificationEnvironment(): Map<String, String> =
    System.getenv().filter { (name, value) ->
        name !in setOf("SPLIT_MAX_FUNDS_DISPATCH_BYTES", "SPLIT_MAX_FUNDS_ACTIVE_BYTES") ||
            value.isNotBlank()
    }

/** 상태 복사와 파일 관찰은 시험 wrapper가 서비스 소유 스레드 안에서만 수행한다. */
private class ResultVerificationService(
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

    // Java default callback은 Kotlin 인터페이스 위임만으로 전달되지 않는다.
    override fun doBackgroundWork(nowNs: Long): Int = delegate.doBackgroundWork(nowNs)

    private fun capture() {
        val values =
            linkedMapOf<String, Any?>(
                "role" to settings.role.name,
                "memberId" to settings.transport.memberId,
                "pid" to ProcessHandle.current().pid(),
                "bootId" to bootId,
                "initialized" to delegate.tradingReady,
                "lifecycle" to delegate.lifecycle.name,
                "logPosition" to cluster.logPosition(),
                "clusterRole" to cluster.role().name,
                "snapshotCount" to snapshots,
                "lastSnapshotPosition" to snapshotPosition,
                "restoredSnapshot" to restoredSnapshot,
            )
        if (delegate.lifecycle == TradingLifecycle.RUNNING) {
            val field = delegate.javaClass.getDeclaredField(if (settings.role == TradingServiceRole.FUNDS) "funds" else "matching")
            field.isAccessible = true
            if (settings.role == TradingServiceRole.FUNDS) {
                val saved = (field.get(delegate) as BoundedFundingState).fundsRecoveryState()
                val results = checkNotNull(saved.results)

                fun balance(
                    user: UserId,
                    asset: String,
                    hold: Boolean = false,
                ): Long {
                    val found = saved.financial.balances.singleOrNull { it.userId == user && it.assetId == AssetId(asset) }
                    return if (hold) found?.hold?.value ?: 0L else found?.available?.value ?: 0L
                }
                values["funds"] =
                    linkedMapOf(
                        "buyerQ" to balance(resultBuyer, "USDT"),
                        "buyerQHold" to balance(resultBuyer, "USDT", true),
                        "buyerX" to balance(resultBuyer, "X"),
                        "buyerXHold" to balance(resultBuyer, "X", true),
                        "sellerQ" to balance(resultSeller, "USDT"),
                        "sellerQHold" to balance(resultSeller, "USDT", true),
                        "sellerX" to balance(resultSeller, "X"),
                        "sellerXHold" to balance(resultSeller, "X", true),
                        "feeQ" to (saved.financial.feeRevenueByAsset[AssetId("USDT")]?.longValueExact() ?: 0L),
                        "dispatchEntries" to saved.dispatch.dispatchEntries,
                        "dispatchBytes" to saved.dispatch.dispatchBytes,
                        "activeOrders" to saved.dispatch.activeOrders,
                        "activeBytes" to saved.dispatch.activeBytes,
                        "liveOrders" to saved.financial.liveOrders.size,
                        "remainingQuantity" to saved.financial.liveOrders.sumOf { it.reservation.remainingQuantity.value },
                        "pendingOrders" to
                            saved.financial.markets
                                .single()
                                .pendingAdmissionSequences.size,
                        "matchingAppliedThrough" to
                            saved.dispatch.streams
                                .single()
                                .matchingAppliedThrough,
                        "issued" to
                            saved.dispatch.streams
                                .single()
                                .issued,
                        "admissionIssued" to saved.financial.lastAdmissionSequence,
                        "cancellationIssued" to saved.lastCancellationSequence,
                        "pendingCancellations" to saved.cancellations.size,
                        "executedThrough" to
                            saved.financial.markets
                                .single()
                                .executedThrough,
                        "resultThrough" to results.streams.single().resultThrough,
                        "retiredThrough" to results.streams.single().retiredThrough,
                        "receiptEntries" to results.receipts.size,
                        "settlementTrades" to results.receipts.sumOf { it.result.events.count { event -> event is TradeExecuted } },
                        "receipts" to
                            results.receipts.takeLast(8).map {
                                mapOf(
                                    "sequence" to it.result.resultSequence,
                                    "appliedAt" to it.appliedAt.toString(),
                                    "eventCount" to it.result.eventCount,
                                    "effectCount" to it.result.effects.size,
                                    "logicalBytes" to it.result.canonicalBytes,
                                )
                            },
                    ).apply {
                        if (resultRecoveryFixture) {
                            val channels = saved.financial.inbox.channels
                            put("inboxEntries", channels.sumOf { it.entries.size + it.cancelEntries.size })
                            put("inboxOrderEntries", channels.sumOf { it.entries.size })
                            put("inboxCancelEntries", channels.sumOf { it.cancelEntries.size })
                            put(
                                "inboxChannels",
                                channels.sortedBy { it.userId.value }.take(8).map { channel ->
                                    mapOf(
                                        "userId" to channel.userId.value,
                                        "channel" to channel.channel,
                                        "epoch" to channel.epoch,
                                        "processedThrough" to channel.processedThrough,
                                        "retiredThrough" to channel.retiredThrough,
                                        "orders" to
                                            channel.entries.sortedBy { it.sequence }.take(8).map {
                                                mapOf(
                                                    "requestSequence" to it.sequence,
                                                    "code" to it.receipt.code.name,
                                                    "orderId" to it.receipt.orderId?.value,
                                                    "admissionSequence" to it.receipt.admissionSequence,
                                                )
                                            },
                                        "cancellations" to
                                            channel.cancelEntries.sortedBy { it.sequence }.take(8).map {
                                                mapOf(
                                                    "requestSequence" to it.sequence,
                                                    "code" to it.receipt.code.name,
                                                    "cancelId" to it.receipt.cancelId,
                                                    "targetOrderId" to it.receipt.targetOrderId?.value,
                                                    "inputSequence" to it.receipt.inputSequence,
                                                )
                                            },
                                    )
                                },
                            )
                            put(
                                "liveReservations",
                                saved.financial.liveOrders.sortedBy { it.admissionSequence }.take(8).map {
                                    mapOf(
                                        "orderId" to it.order.orderId.value,
                                        "userId" to it.order.userId.value,
                                        "admissionSequence" to it.admissionSequence,
                                        "phase" to it.phase.name,
                                        "quantity" to it.reservation.remainingQuantity.value,
                                        "remainingAmount" to it.reservation.remainingAmount.value,
                                        "remainingFeeReserve" to it.reservation.remainingFeeReserveAmount.value,
                                        "feeRemainder" to it.reservation.feeRemainder.numerator,
                                    )
                                },
                            )
                            put(
                                "cancellationDetails",
                                saved.cancellations.sortedBy { it.input.dispatchSequence }.take(8).map {
                                    mapOf(
                                        "cancelId" to checkNotNull(it.input.cancel).cancelId,
                                        "targetOrderId" to it.input.order.orderId.value,
                                        "inputSequence" to it.input.dispatchSequence,
                                        "terminalResultSequence" to it.terminalResultSequence,
                                    )
                                },
                            )
                            put("resultDetails", results.receipts.takeLast(8).map { resultMeaning(it.result) })
                        }
                    }
            } else {
                val saved = (field.get(delegate) as ReservedMatchingState).recoveryState()
                val market = saved.matching.markets.singleOrNull()
                val stream = saved.streams.single()
                if ((resultFixture == "cancellation" || resultRecoveryFixture) && cluster.role() == Cluster.Role.LEADER) {
                    for (result in stream.outstanding.filter { it.resultSequence <= 8 }) {
                        writeBoundedHttpArtifact(
                            resultControl.resolve("retained-result-${result.resultSequence}.bin"),
                            resultDeliveryCodec.encode(1, SplitResultDeliveryCommand.Apply(result)),
                            true,
                        )
                    }
                }
                values["matching"] =
                    mapOf<String, Any?>(
                        "bookOrders" to (market?.orders ?: emptyList()).size,
                        "bookQuantity" to (market?.orders ?: emptyList()).sumOf { it.remainingQuantity.value },
                        "engineSequence" to (market?.lastEngineSequence ?: 0L),
                        "appliedThrough" to stream.appliedThrough,
                        "resultIssued" to stream.resultIssued,
                        "resultEntries" to saved.resultEntries,
                        "resultBytes" to saved.resultBytes,
                        "activeOrders" to saved.activeOrders,
                        "activeBytes" to saved.activeBytes,
                        "fundsSettledThrough" to stream.fundsSettledThrough,
                    ).toMutableMap().apply {
                        if (resultRecoveryFixture) {
                            put(
                                "bookDetails",
                                market?.orders.orEmpty().take(8).map {
                                    mapOf(
                                        "orderId" to it.orderId.value,
                                        "userId" to it.userId.value,
                                        "side" to it.side.name,
                                        "price" to it.price.value,
                                        "quantity" to it.remainingQuantity.value,
                                    )
                                },
                            )
                            put("resultDetails", stream.outstanding.take(8).map(::resultMeaning))
                        }
                    }
            }
        }
        writeSplitTestJson(resultControl.resolve("state-${settings.role.name.lowercase()}${settings.transport.memberId}.json"), values)
    }
}

/** 복합 시험의 다섯 결과만 의미 필드로 관찰하며 제품 이력 저장소로 사용하지 않는다. */
private fun resultMeaning(result: MatchingResultEnvelope): Map<String, Any?> {
    check(result.events.size <= 8 && result.effects.size <= 8)
    return mapOf(
        "sequence" to result.resultSequence,
        "resultId" to result.resultId,
        "inputSequence" to result.input.dispatchSequence,
        "orderId" to result.input.order.orderId.value,
        "cancelId" to result.input.cancel?.cancelId,
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

/** 유실·보류는 시험 전송 wrapper에만 있고 제품 상태·응답 처리 경계는 그대로다. */
private fun resultOrderConnections(bootId: String): SplitOrderDeliveryConnectionFactory =
    SplitOrderDeliveryConnectionFactory { endpoint, timeout, onReply ->
        val attempt =
            AeronSplitOrderDeliveryConnectionFactory.begin(endpoint, timeout) { reply ->
                if (endpoint.clusterId == SplitTradingNodeSettings.MATCHING_CLUSTER_ID && reply.ack != null) {
                    writeBoundedHttpArtifact(
                        resultControl.resolve("original-order-ack.bin"),
                        resultOrderCodec.encode(1, SplitOrderDeliveryCommand.RecordAck(reply.ack)),
                        true,
                    )
                    writeSplitTestJson(
                        resultControl.resolve("received-matching-ack.json"),
                        mapOf(
                            "bootId" to bootId,
                            "code" to reply.code,
                            "through" to reply.ack.through,
                            "topologySha256" to reply.topologySha256,
                        ),
                    )
                    if (Files.exists(resultControl.resolve("hold-matching-acks"))) return@begin
                }
                if (reply.code ==
                    "BUSY"
                ) {
                    writeSplitTestJson(resultControl.resolve("received-order-busy.json"), mapOf("bootId" to bootId, "code" to reply.code))
                }
                onReply(reply)
            }
        object : SplitOrderDeliveryConnectAttempt {
            override fun poll(): SplitOrderDeliveryConnection? {
                val connection = attempt.poll() ?: return null
                return object : SplitOrderDeliveryConnection by connection {
                    override fun offer(bytes: ByteArray): Long {
                        val command = resultOrderCodec.decode(bytes).command
                        if (command is SplitOrderDeliveryCommand.Apply && command.input.dispatchSequence <= 8) {
                            writeBoundedHttpArtifact(resultControl.resolve("dispatch-${command.input.dispatchSequence}.bin"), bytes, true)
                        }
                        return connection.offer(bytes)
                    }
                }
            }

            override fun close() = attempt.close()
        }
    }

/** 실제 결과 client는 전달 스레드만 닫는다. 요청 파일은 재시작 후 다시 소비하지 않는다. */
private class ResultConnectionObservation(
    private val bootId: String,
) {
    private val request = resultControl.resolve("close-result-connection")
    private var seenToken = if (Files.exists(request)) Files.readString(request) else null
    private var connectionId: String? = null
    private var closedConnectionId: String? = null
    private var describedConnectionId: String? = null
    private var consumedCloseToken: String? = null
    private var connectionAttempts = 0
    private var connectedCount = 0
    private var describedCount = 0
    private var closeCount = 0

    fun opening(): String {
        connectionId = UUID.randomUUID().toString()
        connectionAttempts++
        publish("OPENING")
        return checkNotNull(connectionId)
    }

    fun connected(id: String) {
        check(id == connectionId)
        connectedCount++
        publish("CONNECTED")
    }

    fun described(id: String) {
        check(id == connectionId)
        describedConnectionId = id
        describedCount++
        publish("DESCRIBED")
    }

    fun closeRequested(
        connection: SplitResultDeliveryConnection,
        id: String,
    ): Boolean {
        if (!Files.exists(request)) return false
        val token = Files.readString(request)
        if (token == seenToken) return false
        check(token.isNotBlank() && token.length <= 128)
        seenToken = token
        connection.close()
        check(connection.isClosed) { "시험이 요청한 실제 결과 연결이 닫히지 않았습니다" }
        consumedCloseToken = token
        closedConnectionId = id
        closeCount++
        publish("CLOSED")
        return true
    }

    private fun publish(state: String) {
        writeSplitTestJson(
            resultControl.resolve("result-connection.json"),
            mapOf(
                "bootId" to bootId,
                "connectionId" to connectionId,
                "closedConnectionId" to closedConnectionId,
                "describedConnectionId" to describedConnectionId,
                "connectionAttempts" to connectionAttempts,
                "connectedCount" to connectedCount,
                "describedCount" to describedCount,
                "consumedCloseToken" to consumedCloseToken,
                "closeCount" to closeCount,
                "state" to state,
            ),
        )
    }
}

private fun resultConnections(bootId: String): SplitResultDeliveryConnectionFactory {
    val observation = if (resultRecoveryFixture) ResultConnectionObservation(bootId) else null
    return SplitResultDeliveryConnectionFactory { endpoint, timeout, onReply ->
        val observed = observation.takeIf { endpoint.clusterId == SplitTradingNodeSettings.FUNDS_CLUSTER_ID }
        val connectionId = observed?.opening()
        val attempt =
            AeronSplitResultDeliveryConnectionFactory.begin(endpoint, timeout) { reply ->
                if (reply.ack != null) {
                    val name = if (endpoint.clusterId == SplitTradingNodeSettings.FUNDS_CLUSTER_ID) "funds" else "record"
                    writeSplitTestJson(
                        resultControl.resolve("received-$name-result-ack.json"),
                        mapOf(
                            "bootId" to bootId,
                            "code" to reply.code,
                            "through" to reply.ack.through,
                            "appliedLogPosition" to reply.appliedLogPosition,
                            "topologySha256" to reply.topologySha256,
                        ),
                    )
                    if (Files.exists(resultControl.resolve("hold-$name-result-replies"))) return@begin
                }
                onReply(reply)
                if (reply.code == "DESCRIBED" && reply.lifecycle == TradingLifecycle.RUNNING) {
                    connectionId?.let { observed?.described(it) }
                }
            }
        object : SplitResultDeliveryConnectAttempt {
            override fun poll(): SplitResultDeliveryConnection? {
                val connection = attempt.poll() ?: return null
                connectionId?.let { observed?.connected(it) }
                return object : SplitResultDeliveryConnection by connection {
                    override fun poll(): Int {
                        if (connectionId != null && observed?.closeRequested(connection, connectionId) == true) return 0
                        return connection.poll()
                    }

                    override fun offer(bytes: ByteArray): Long {
                        if (connectionId != null && observed?.closeRequested(connection, connectionId) == true) return Publication.CLOSED
                        when (val command = resultDeliveryCodec.decode(bytes).command) {
                            is SplitResultDeliveryCommand.Apply -> {
                                if (command.result.resultSequence <= 8 || command.result.eventCount > 8) {
                                    val path = resultControl.resolve("result-${command.result.resultSequence}.bin")
                                    writeBoundedHttpArtifact(path, bytes, true)
                                }
                                if (Files.exists(resultControl.resolve("hold-results"))) return Publication.BACK_PRESSURED
                            }

                            is SplitResultDeliveryCommand.RecordAck -> {
                                writeBoundedHttpArtifact(resultControl.resolve("settled-ack.bin"), bytes, true)
                                if (Files.exists(resultControl.resolve("hold-result-records"))) return Publication.BACK_PRESSURED
                            }

                            else -> {
                                Unit
                            }
                        }
                        return connection.offer(bytes)
                    }
                }
            }

            override fun close() = attempt.close()
        }
    }
}

private fun resultVerificationNode() {
    val settings = splitTradingSettings(resultVerificationEnvironment())
    val bootId = UUID.randomUUID().toString()
    val workerFailure = AtomicReference<Throwable?>()
    DevelopmentSplitTradingNode
        .start(
            settings,
            serviceDecorator = { ResultVerificationService(it, settings, bootId) },
            deliveryFactory = { source ->
                FundsOrderDeliveryAgent(
                    settings.configuration,
                    source,
                    settings.endpoint(false),
                    settings.endpoint(true),
                    connectionFactory = resultOrderConnections(bootId),
                    onFailure = { workerFailure.compareAndSet(null, it) },
                )
            },
            resultDeliveryFactory = { source ->
                MatchingResultDeliveryAgent(
                    settings.configuration,
                    source,
                    settings.endpoint(false),
                    settings.endpoint(true),
                    connectionFactory = resultConnections(bootId),
                    onFailure = { workerFailure.compareAndSet(null, it) },
                )
            },
        ).use { node ->
            Runtime.getRuntime().addShutdownHook(Thread(node::close))
            val config = settings.transport
            val dispatchLimits = settings.configuration.dispatchLimits
            val dispatchMarket = dispatchLimits.marketLimits.values.single()
            val matchingLimits = settings.configuration.matchingLimits
            val matchingMarket = matchingLimits.marketLimits.values.single()
            writeSplitTestJson(
                resultControl.resolve("runtime-${settings.role.name.lowercase()}${config.memberId}.json"),
                mapOf(
                    "role" to settings.role.name,
                    "memberId" to config.memberId,
                    "pid" to ProcessHandle.current().pid(),
                    "bootId" to bootId,
                    "storageLevels" to node.storageLevels,
                    "clusterId" to settings.clusterId,
                    "members" to config.members,
                    "runRoot" to config.runRoot.toString(),
                    "driverDirectory" to config.driverDirectory,
                    "archiveDirectory" to config.archiveDirectory.toString(),
                    "clusterDirectory" to config.clusterDirectory.toString(),
                    "artifactSha256" to BoundedExecutionIdentity.artifactSha256,
                    "schemaSha256" to BoundedExecutionIdentity.schemaSha256,
                    "topologySha256" to settings.configuration.resultTopologySha256,
                    "mode" to settings.mode,
                    "fixture" to resultFixture,
                    "inputContractVersion" to settings.configuration.inputContractVersion,
                    "maxPendingOrders" to settings.configuration.topology.fundsManifest.limits.maxPendingOrders,
                    "maxMarketPendingOrders" to settings.configuration.topology.fundsManifest.limits.maxMarketPendingOrders,
                    "maxMatchingResultEntries" to settings.configuration.matchingLimits.maxResultEntries,
                    "maxMarketMatchingResultEntries" to
                        settings.configuration.matchingLimits.marketLimits.values
                            .single()
                            .maxResultEntries,
                    "effectiveFundsDispatchBytes" to minOf(dispatchLimits.maxDispatchBytes, dispatchMarket.maxDispatchBytes),
                    "effectiveFundsActiveBytes" to minOf(dispatchLimits.maxActiveBytes, dispatchMarket.maxActiveBytes),
                    "effectiveMatchingResultBytes" to minOf(matchingLimits.maxResultBytes, matchingMarket.maxResultBytes),
                    "effectiveMatchingActiveBytes" to minOf(matchingLimits.maxActiveBytes, matchingMarket.maxActiveBytes),
                    "jdkVersion" to System.getProperty("java.version"),
                    "termLengthBytes" to TradingAeronRuntime.TERM_LENGTH_BYTES,
                    "mtuLength" to 1408,
                ),
            )
            val request = resultControl.resolve("snapshot-${settings.role.name.lowercase()}")
            var previous = if (Files.exists(request)) Files.readString(request) else null
            while (!node.isClosed && node.error == null) {
                workerFailure.get()?.let { throw IllegalStateException("실제 결과 전송 작업 실패", it) }
                if (Files.exists(request) && node.role == Cluster.Role.LEADER && node.tradingReady) {
                    val token = Files.readString(request)
                    if (token != previous && node.requestSnapshot()) previous = token
                }
                Thread.sleep(10)
            }
            node.error?.let { throw IllegalStateException("실제 결과 정산 노드 실패", it) }
        }
}

private fun setupResultVerification() {
    val context =
        TradingMarketContext(
            MarketDefinition(MarketId("🪙".repeat(64)), AssetId("X"), AssetId("USDT"), 0),
            TradingFeePolicySnapshot(
                FeeProductType.SPOT,
                FeeTier.NORMAL,
                1,
                MakerTakerFeeRates(
                    FeeRate(if (resultFixture == "fee" || resultRecoveryFixture) 10_000 else 0),
                    FeeRate(
                        if (resultRecoveryFixture) {
                            10_000
                        } else if (resultFixture == "fee") {
                            20_000
                        } else {
                            0
                        },
                    ),
                ),
            ),
            0,
        )
    val manifest =
        TradingClusterManifest(
            ClusterLineage(169, 1),
            "split-result-settlement-v1",
            BoundedExecutionIdentity.artifactSha256,
            BoundedExecutionIdentity.schemaSha256,
            1,
            listOf(context),
            TradingResourceLimits(
                maxAccounts = 8,
                channelsPerAccount = 1,
                maxChannels = 8,
                requestWindow = 1024,
                maxInboxEntries = 2048,
                maxActiveOrders = 1024,
                maxPendingOrders = if (resultFixture == "recovery-cap") 1 else 1024,
                maxMarketActiveOrders = 1024,
                maxMarketPendingOrders = if (resultFixture == "recovery-cap") 1 else 1024,
                maxBalanceKeys = 32,
                maxPreparations = 16,
                maxMarkets = 1,
                maxAssets = 2,
            ),
        )
    Files.write(resultControl.resolve("manifest.bin"), BoundedTradingStateCodec().encodeManifest(manifest))
}

/** 논리 수용과 별개인 native publication의 경계를 실제 연결에서만 검사한다. */
private fun verifyNativeResultFrameBoundary(endpoint: SplitOrderDeliveryEndpoint) {
    val maximum = SplitResultDeliveryBoundaryFixture.frame(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES, endpoint.inputContractVersion)
    val overflow = SplitResultDeliveryBoundaryFixture.frame(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + 1, endpoint.inputContractVersion)
    check(maximum.bytes.size == SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
    check(overflow.bytes.size == maximum.bytes.size + 1)
    val decoded = SplitResultDeliveryCodec(endpoint.inputContractVersion).decode(maximum.bytes)
    check(decoded.correlationId == maximum.correlationId)
    check((decoded.command as SplitResultDeliveryCommand.Apply).result == maximum.result)
    var reply: SplitResultDeliveryReply? = null
    val timeout = TimeUnit.SECONDS.toNanos(10)
    val idle = TradingAeronRuntime.idleStrategy()
    AeronSplitResultDeliveryConnectionFactory
        .begin(endpoint, timeout) {
            if (it.correlationId == maximum.correlationId) reply = it
        }.use { attempt ->
            val connectStarted = System.nanoTime()
            var connected: SplitResultDeliveryConnection? = null
            while (connected == null && System.nanoTime() - connectStarted < timeout) {
                connected = attempt.poll()
                if (connected == null) idle.idle()
            }
            checkNotNull(connected) { "native boundary connection timed out" }.use { connection ->
                var offered = 0L
                val requestStarted = System.nanoTime()
                var lastKeepAlive = requestStarted
                while ((offered <= 0 || reply == null) && System.nanoTime() - requestStarted < timeout && !connection.isClosed) {
                    if (offered <= 0) offered = connection.offer(maximum.bytes)
                    idle.idle(connection.poll())
                    val now = System.nanoTime()
                    if (now - lastKeepAlive >= TimeUnit.MILLISECONDS.toNanos(500)) {
                        connection.keepAlive()
                        lastKeepAlive = now
                    }
                }
                check(offered > 0) { "maximum native frame was not accepted: $offered" }
                val received = checkNotNull(reply) { "maximum native frame callback timed out" }
                check(received.role == TradingServiceRole.FUNDS && received.ack == null)
                check(received.code in setOf("INVALID_STREAM", "INVALID_RESULT")) { received.toString() }
                check(received.appliedLogPosition > 0)
                var rejected = false
                try {
                    connection.offer(overflow.bytes)
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                check(rejected) { "native connection accepted a frame one byte over its configured maximum" }
                val evidence =
                    mapOf(
                        "frameBytes" to maximum.bytes.size,
                        "correlationId" to maximum.correlationId,
                        "positiveOfferPosition" to offered,
                        "code" to received.code,
                        "responseAppliedLogPosition" to received.appliedLogPosition,
                        "logicalBytes" to maximum.result.canonicalBytes,
                        "maxPlusOneBytes" to overflow.bytes.size,
                        "maxPlusOneRejected" to rejected,
                    )
                writeSplitTestJson(resultControl.resolve("frame-boundary.json"), evidence)
                println(resultJson.writeValueAsString(evidence))
            }
        }
}

private fun resultVerificationCommand(args: Array<String>) {
    val env = resultVerificationEnvironment()
    val manifest = BoundedTradingStateCodec().decodeManifest(Files.readAllBytes(Path.of(env["SPLIT_MANIFEST"] ?: "/control/manifest.bin")))
    val configuration = splitTradingConfiguration(manifest, env)
    MediaDriver
        .launch(
            MediaDriver
                .Context()
                .aeronDirectoryName("/tmp/result-client-${UUID.randomUUID()}")
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
                    configuration.inputContractVersion,
                )
            SplitOrderDeliveryClient(endpoint(false), 10_000).use { funds ->
                fun fund(
                    user: UserId,
                    asset: String,
                    amount: Long,
                    id: String,
                ) {
                    check(
                        funds
                            .request(
                                SplitOrderDeliveryCommand.Control(
                                    BoundedTradingCommand.Funding(
                                        DevelopmentBalanceCommandPayload(
                                            OpeningBalance(id, user, AssetId(asset), Amount(amount)),
                                            "result169-ledger",
                                            Instant.parse("2026-11-04T00:00:00Z"),
                                        ),
                                    ),
                                ),
                            ).code ==
                            "PREPARED",
                    )
                }

                fun admit(
                    who: String,
                    sequence: Long,
                    price: Long,
                    quantity: Long,
                    side: Side = if (who == "seller") Side.SELL else Side.BUY,
                    requireAccepted: Boolean = true,
                ): SplitOrderDeliveryReply {
                    val reply =
                        funds.request(
                            SplitOrderDeliveryCommand.Control(
                                BoundedTradingCommand.Admit(
                                    RequestToken(if (who == "seller") resultSeller else resultBuyer, 0, 1, sequence),
                                    OrderIntent(
                                        manifest.markets
                                            .single()
                                            .market.marketId,
                                        manifest.configurationVersion,
                                        side,
                                        OrderType.LIMIT,
                                        TimeInForce.GTC,
                                        Price(price),
                                        Quantity(quantity),
                                    ),
                                ),
                            ),
                        )
                    if (requireAccepted) check(reply.code == "ACCEPTED") { reply.toString() }
                    return reply
                }
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
                        val sellerQuantity =
                            when (resultFixture) {
                                "partial", "cap", "active" -> 2L
                                "cancellation", "recovery", "recovery-cap" -> 3L
                                "large" -> 1024L
                                else -> 1L
                            }
                        fund(resultBuyer, "USDT", if (resultFixture == "large") 1_000_000 else 1_000, "result169-buyer")
                        fund(resultSeller, "X", sellerQuantity, "result169-seller")
                        if (resultRecoveryFixture) fund(resultSeller, "USDT", 100, "result134-seller-quote")
                        for (user in listOf(resultBuyer, resultSeller)) {
                            val rotated = funds.request(SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(user, 0, 0)))
                            check(rotated.control?.epoch == 1L)
                        }
                    }

                    "admit" -> {
                        val side = args.getOrNull(5)?.let(Side::valueOf) ?: if (args[1] == "seller") Side.SELL else Side.BUY
                        admit(args[1], args[2].toLong(), args[3].toLong(), args[4].toLong(), side)
                    }

                    "probe-capacity" -> {
                        check(resultRecoveryFixture)
                        val reply = admit("buyer", 3, 1, 1, Side.BUY, requireAccepted = false)
                        println(
                            resultJson.writeValueAsString(
                                mapOf(
                                    "code" to reply.code,
                                    "reason" to (
                                        reply.control
                                            ?.admission
                                            ?.reason
                                            ?.name ?: reply.code
                                    ),
                                    "appliedLogPosition" to reply.appliedLogPosition,
                                ),
                            ),
                        )
                    }

                    "cancel" -> {
                        val original = resultOrderCodec.decode(Files.readAllBytes(resultControl.resolve("dispatch-${args[3]}.bin"))).command
                        check(original is SplitOrderDeliveryCommand.Apply && !original.input.isCancellation)
                        val reply =
                            funds.request(
                                SplitOrderDeliveryCommand.Cancel(
                                    RequestToken(if (args[1] == "seller") resultSeller else resultBuyer, 0, 1, args[2].toLong()),
                                    CancelIntent(
                                        original.input.stream.marketId,
                                        original.input.order.orderId,
                                        manifest.configurationVersion,
                                    ),
                                ),
                            )
                        check(reply.code == "ACCEPTED") { reply.toString() }
                        val receipt = checkNotNull(reply.cancellation)
                        println(
                            resultJson.writeValueAsString(
                                mapOf(
                                    "code" to reply.code,
                                    "cancelId" to receipt.cancelId,
                                    "targetOrderId" to receipt.targetOrderId?.value,
                                    "inputSequence" to receipt.inputSequence,
                                ),
                            ),
                        )
                    }

                    "deliver-result" -> {
                        val original =
                            resultDeliveryCodec
                                .decode(
                                    Files.readAllBytes(resultControl.resolve("retained-result-${args[1]}.bin")),
                                ).command
                        DevelopmentSplitResultDeliveryClient(endpoint(false), 10_000).use { client ->
                            val reply = client.request(original)
                            check(reply.code in setOf("APPLIED", "GAP", "DUPLICATE", "PRUNED")) { reply.toString() }
                            println(
                                resultJson.writeValueAsString(
                                    mapOf(
                                        "code" to reply.code,
                                        "through" to reply.ack?.through,
                                        "expectedSequence" to reply.expectedSequence,
                                    ),
                                ),
                            )
                        }
                    }

                    "makers" -> {
                        for (sequence in 1..args[1].toInt()) admit("seller", sequence.toLong(), 100, 1)
                    }

                    "frame-boundary" -> {
                        verifyNativeResultFrameBoundary(endpoint(false))
                    }

                    "replay-result", "replay-ack" -> {
                        val ack = args[0] == "replay-ack"
                        val path = if (ack) resultControl.resolve("settled-ack.bin") else resultControl.resolve("result-${args[1]}.bin")
                        val original = resultDeliveryCodec.decode(Files.readAllBytes(path)).command
                        DevelopmentSplitResultDeliveryClient(endpoint(ack), 10_000).use { client ->
                            val reply = client.request(original)
                            val accepted = if (ack) setOf("ACKNOWLEDGED") else setOf("DUPLICATE", "PRUNED")
                            check(reply.code in accepted) { reply.toString() }
                            println(
                                resultJson.writeValueAsString(
                                    mapOf(
                                        "code" to reply.code,
                                        "through" to reply.ack?.through,
                                        "appliedLogPosition" to reply.appliedLogPosition,
                                    ),
                                ),
                            )
                        }
                    }

                    "replay-order-ack" -> {
                        val bytes = Files.readAllBytes(resultControl.resolve("original-order-ack.bin"))
                        val command = resultOrderCodec.decode(bytes).command
                        check(funds.request(command).code == "ACKNOWLEDGED")
                    }

                    "replay-order" -> {
                        SplitOrderDeliveryClient(endpoint(true), 10_000).use { matching ->
                            val reply =
                                matching.request(
                                    resultOrderCodec
                                        .decode(
                                            Files.readAllBytes(resultControl.resolve("dispatch-${args[1]}.bin")),
                                        ).command,
                                )
                            check(reply.code in setOf("DUPLICATE", "PRUNED"))
                        }
                    }

                    else -> {
                        error("알 수 없는 결과 정산 시험 명령")
                    }
                }
            }
        }
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "setup" -> setupResultVerification()
        "node" -> resultVerificationNode()
        "snapshot" -> Files.writeString(resultControl.resolve("snapshot-${args[1]}"), UUID.randomUUID().toString())
        else -> resultVerificationCommand(args)
    }
}
