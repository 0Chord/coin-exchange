package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.archive.status.RecordingPos
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun haBoundedProbeManifest(): TradingClusterManifest =
    TradingClusterManifest(
        ClusterLineage(0x484150524F4245, 142),
        "bounded-ha-probe-v1",
        BoundedExecutionIdentity.artifactSha256,
        BoundedExecutionIdentity.schemaSha256,
        1,
        TradingEngineProbeWorkload.markets.mapIndexed {
            index,
            market,
            ->
            TradingMarketContext(market, TradingEngineProbeWorkload.policy, index)
        },
        TradingResourceLimits(),
    )

fun main(args: Array<String>) {
    require(args.isEmpty())
    val original = haBoundedProbeManifest()
    val manifest =
        if (System.getenv("HA_PROBE_IDENTITY_MISMATCH") == "true") {
            TradingClusterManifest(
                ClusterLineage(original.lineage.high xor 1L, original.lineage.low),
                original.algorithmId,
                original.executionArtifactSha256,
                original.schemaSha256,
                original.configurationVersion,
                original.markets,
                original.limits,
            )
        } else {
            original
        }
    val settings =
        haTradingProbeSettings().copy(
            boundedManifest = manifest,
            trustedBoundedControl = System.getenv("FUNDING_TRUSTED_BOUNDED_CONTROL")?.toBooleanStrict() ?: true,
        )
    runHaTradingProbeNode(settings, snapshot = { it.requestSnapshot() }, faultDecorator = { decorated, service ->
        if (System.getenv("HA_PROBE_FAULTS") == "true") boundedFaultDecorator(settings.memberId, decorated, service) else decorated
    }, appliedResult = { it.compactExecution != null }) { node ->
        val state = node.captureRecoveryState()
        // 현재 상태만 비교하도록 전송·생명주기 위치를 0으로 정규화한다.
        val bytes = ByteArrayOutputStream().also { BoundedTradingStateCodec().write(state, 0, it) }.toByteArray()
        mapOf(
            "stateSha256" to haProbeSha(bytes),
            "stateBytes" to bytes.size,
            "lifecycle" to state.lifecycle.name,
            "lastAdmissionSequence" to state.lastAdmissionSequence,
            "liveOrders" to state.liveOrders.size,
            "pending" to state.markets.sumOf { it.pendingAdmissionSequences.size },
            "retainedReceipts" to state.inbox.channels.sumOf { it.entries.size },
            "receiptFloors" to
                state.inbox.channels.map {
                    listOf(it.userId.value, it.channel, it.epoch, it.retiredThrough, it.processedThrough)
                },
            "liveMakers" to
                state.liveOrders.map {
                    listOf(
                        it.order.userId.value,
                        it.order.marketId.value,
                        it.order.side.name,
                        it.phase.name,
                        it.order.quantity.value,
                        it.reservation.remainingQuantity.value,
                        it.reservation.remainingAmount.value,
                    )
                },
            "feeRevenue" to (state.feeRevenueByAsset[TradingEngineProbeWorkload.quote]?.toString() ?: "0"),
            "balances" to state.balances.map { listOf(it.userId.value, it.assetId.value, it.available.value, it.hold.value) },
        )
    }
}

/** 명시적인 장애 테스트 프로필에서만 생성하며 두 성능 프로필에서는 생성하지 않는다. */
private fun boundedFaultDecorator(
    member: Int,
    delegate: ClusteredService,
    service: DevelopmentFundingAdapter,
): ClusteredService {
    val control = Path.of(requireNotNull(System.getenv("HA_PROBE_CONTROL")))
    val codec = BoundedTradingCodec()
    return object : ClusteredService by delegate {
        private lateinit var cluster: Cluster
        private var admissionBeingApplied: BoundedTradingCommand.Admit? = null
        private val executionOfferDelegations = mutableMapOf<com.exchange.core.trading.ExecuteAdmission, Int>()

        override fun onStart(
            cluster: Cluster,
            snapshotImage: Image?,
        ) {
            this.cluster = cluster
            val ingressBarrier =
                object : Cluster by cluster {
                    override fun offer(
                        buffer: DirectBuffer,
                        offset: Int,
                        length: Int,
                    ): Long {
                        val bytes = ByteArray(length).also { buffer.getBytes(offset, it) }
                        val execution = codec.decode(bytes).command as? BoundedTradingCommand.Execution
                        val admission = admissionBeingApplied
                        val arm = control.resolve("pause-before-execution-offer-$member")
                        if (admission != null && Files.exists(arm) &&
                            Files.readString(arm).trim() == "${admission.token.userId.value}:${admission.token.sequence}"
                        ) {
                            check(execution != null) { "admission submitted an unexpected internal message" }
                            val delegated = executionOfferDelegations[execution.command] ?: 0
                            check(delegated == 0) { "the target Execute was already forwarded before the barrier" }
                            val current = service.captureRecoveryState().join()
                            val receipt =
                                current.inbox.channels
                                    .single {
                                        it.userId == admission.token.userId &&
                                            it.channel == admission.token.channel
                                    }.entries
                                    .single { it.sequence == admission.token.sequence }
                                    .receipt
                            val pending = current.liveOrders.single { it.admissionSequence == receipt.admissionSequence }
                            check(pending.phase == com.exchange.core.trading.LiveOrderPhase.PENDING)
                            check(
                                execution.command.admissionSequence == pending.admissionSequence &&
                                    execution.command.marketAdmissionSequence == pending.marketAdmissionSequence &&
                                    execution.command.marketId == pending.order.marketId &&
                                    execution.command.lineage == current.manifest.lineage,
                            )
                            val progress = current.markets.single { it.marketId == pending.order.marketId }
                            val market =
                                current.manifest.markets
                                    .single { it.market.marketId == pending.order.marketId }
                                    .market
                            val balance =
                                current.balances.single {
                                    it.userId == admission.token.userId && it.assetId == market.quoteAssetId
                                }
                            val stateBytes = ByteArrayOutputStream().also { BoundedTradingStateCodec().write(current, 0, it) }.toByteArray()
                            writeFaultEvidence(
                                control,
                                "paused-before-execution-offer-$member",
                                mapOf(
                                    "kind" to "ADMISSION_COMMITTED_EXECUTION_NOT_OFFERED",
                                    "member" to member,
                                    "position" to cluster.logPosition(),
                                    "role" to cluster.role().name,
                                    "executionOfferDelegations" to delegated,
                                    "executionBodySha256" to haProbeSha(bytes),
                                    "stateSha256" to haProbeSha(stateBytes),
                                    "pending" to current.markets.sumOf { it.pendingAdmissionSequences.size },
                                    "marketExecutedThrough" to progress.executedThrough,
                                    "buyerAvailable" to balance.available.value,
                                    "buyerHold" to balance.hold.value,
                                    "feeRevenue" to (current.feeRevenueByAsset[market.quoteAssetId]?.toString() ?: "0"),
                                    "receipt" to
                                        mapOf(
                                            "code" to receipt.code.name,
                                            "orderId" to receipt.orderId?.value,
                                            "admissionSequence" to receipt.admissionSequence,
                                            "marketAdmissionSequence" to receipt.marketAdmissionSequence,
                                        ),
                                ),
                            )
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                            while (Files.exists(arm)) {
                                check(System.nanoTime() - deadline < 0) { "fault controller did not release the pre-offer barrier" }
                                // 실제 Aeron 유휴 작업을 유지하며 양수 offer 결과를 꾸며내지 않는다.
                                cluster.idleStrategy().idle()
                            }
                        }
                        execution?.let { executionOfferDelegations[it.command] = (executionOfferDelegations[it.command] ?: 0) + 1 }
                        return cluster.offer(buffer, offset, length)
                    }
                }
            delegate.onStart(ingressBarrier, snapshotImage)
        }

        override fun doBackgroundWork(nowNs: Long): Int = delegate.doBackgroundWork(nowNs)

        override fun onSessionMessage(
            session: ClientSession?,
            timestamp: Long,
            buffer: DirectBuffer,
            offset: Int,
            length: Int,
            header: Header,
        ) {
            val arm = control.resolve("pause-before-execution-offer-$member")
            val replyArm = control.resolve("pause-before-admission-reply-$member")
            admissionBeingApplied =
                if (session != null && (Files.exists(arm) || Files.exists(replyArm))) {
                    runCatching {
                        codec
                            .decode(
                                ByteArray(length).also { buffer.getBytes(offset, it) },
                            ).command as? BoundedTradingCommand.Admit
                    }.getOrNull()
                } else {
                    null
                }
            val admission = admissionBeingApplied
            val guardedSession =
                if (session != null && admission != null && Files.exists(replyArm)) {
                    object : ClientSession by session {
                        private var replyDelegations = 0

                        override fun offer(
                            buffer: DirectBuffer,
                            offset: Int,
                            length: Int,
                        ): Long {
                            if (cluster.role() == Cluster.Role.LEADER && Files.exists(replyArm) &&
                                Files.readString(replyArm).trim() == "${admission.token.userId.value}:${admission.token.sequence}"
                            ) {
                                check(replyDelegations == 0) { "the target admission reply was already forwarded" }
                                val response = codec.decodeResponse(ByteArray(length).also { buffer.getBytes(offset, it) })
                                val receipt = checkNotNull(response.admission)
                                check(receipt.code == com.exchange.core.trading.AdmissionCode.ACCEPTED)
                                val submitted =
                                    executionOfferDelegations.entries.single {
                                        it.key.admissionSequence == receipt.admissionSequence
                                    }
                                check(submitted.value > 0) { "Execute must have been offered before the admission response" }
                                writeFaultEvidence(
                                    control,
                                    "paused-before-admission-reply-$member",
                                    mapOf(
                                        "kind" to "EXECUTION_OFFERED_ADMISSION_REPLY_NOT_OFFERED",
                                        "member" to member,
                                        "admissionPosition" to cluster.logPosition(),
                                        "admissionReplyDelegations" to replyDelegations,
                                        "executionOfferDelegations" to submitted.value,
                                        "receipt" to
                                            mapOf(
                                                "code" to receipt.code.name,
                                                "orderId" to receipt.orderId?.value,
                                                "admissionSequence" to receipt.admissionSequence,
                                                "marketAdmissionSequence" to receipt.marketAdmissionSequence,
                                            ),
                                    ),
                                )
                                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                                while (Files.exists(replyArm)) {
                                    check(
                                        System.nanoTime() - deadline < 0,
                                    ) { "fault controller did not kill the process before admission reply" }
                                    cluster.idleStrategy().idle()
                                }
                            }
                            replyDelegations++
                            return session.offer(buffer, offset, length)
                        }
                    }
                } else {
                    session
                }
            try {
                delegate.onSessionMessage(guardedSession, timestamp, buffer, offset, length, header)
            } finally {
                admissionBeingApplied = null
            }
        }

        override fun onTakeSnapshot(snapshotPublication: ExclusivePublication) {
            val arm = control.resolve("pause-snapshot-$member")
            if (!Files.exists(arm) || cluster.role() != Cluster.Role.LEADER) {
                delegate.onTakeSnapshot(snapshotPublication)
                return
            }
            val state = service.captureRecoveryState().join()
            val bytes = ByteArrayOutputStream().also { BoundedTradingStateCodec().write(state, cluster.logPosition(), it) }.toByteArray()
            val partial = minOf(128, bytes.size - 1)
            check(partial > 0)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var position: Long
            do {
                position = snapshotPublication.offer(UnsafeBuffer(bytes), 0, partial)
                check(System.nanoTime() - deadline < 0) { "partial snapshot offer timed out" }
                if (position <= 0) {
                    java.util.concurrent.locks.LockSupport
                        .parkNanos(100_000)
                }
            } while (position <= 0)
            val counters = cluster.aeron().countersReader()
            var recorded = -1L
            while (recorded < position) {
                val counter = RecordingPos.findCounterIdBySession(counters, snapshotPublication.sessionId())
                if (counter >= 0) recorded = counters.getCounterValue(counter)
                check(System.nanoTime() - deadline < 0) { "Archive did not record the partial snapshot" }
                if (recorded < position) {
                    java.util.concurrent.locks.LockSupport
                        .parkNanos(100_000)
                }
            }
            signalAndAwaitKill(
                control,
                arm,
                "paused-snapshot-$member",
                mapOf(
                    "kind" to "PARTIAL_SNAPSHOT_RECORDED",
                    "member" to member,
                    "partialBytes" to partial,
                    "completeBodyBytes" to bytes.size,
                    "publicationPosition" to position,
                    "recordedThrough" to recorded,
                ),
            )
            error("partial snapshot fault must terminate the process before completing the callback")
        }
    }
}

private fun signalAndAwaitKill(
    control: Path,
    arm: Path,
    name: String,
    fields: Map<String, Any?>,
) {
    writeFaultEvidence(control, name, fields)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (Files.exists(arm) && System.nanoTime() - deadline < 0) {
        java.util.concurrent.locks.LockSupport
            .parkNanos(1_000_000)
    }
    check(!Files.exists(arm)) { "fault controller did not kill the paused process" }
}

private fun writeFaultEvidence(
    control: Path,
    name: String,
    fields: Map<String, Any?>,
) {
    val temporary = control.resolve("$name.tmp")
    Files.writeString(
        temporary,
        tools.jackson.module.kotlin
            .jacksonObjectMapper()
            .writeValueAsString(fields),
    )
    Files.move(temporary, control.resolve("$name.json"), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
}
