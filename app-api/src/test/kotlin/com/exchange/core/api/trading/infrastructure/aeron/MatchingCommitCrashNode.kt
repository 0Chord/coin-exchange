package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.TradingServiceRole
import io.aeron.Image
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** 실제 매칭 노드에서 확정된 대상 주문 한 건의 적용 전후를 관찰한다. */
fun main(args: Array<String>) {
    require(args.size == 2) { "관찰 파일 경로와 대상 전달 순번이 필요합니다" }
    val marker = Path.of(args[0])
    require(marker.isAbsolute && !Files.exists(marker))
    val targetSequence = args[1].toLong()
    require(targetSequence > 0)
    val settings = splitTradingSettings()
    require(settings.role == TradingServiceRole.MATCHING)
    val probe =
        MatchingCommitCrashProbe(
            marketId = settings.transport.market.marketId.value,
            targetSequence = targetSequence,
            codec = SplitOrderDeliveryCodec(settings.configuration.inputContractVersion),
            marker = marker,
            recover = settings.transport.recoverExisting,
            maxResultBytes = settings.configuration.resultDeliveryLimits.maxReadBytes,
        )

    DevelopmentSplitTradingNode.start(settings, serviceDecorator = probe::decorate).use { node ->
        Runtime.getRuntime().addShutdownHook(Thread(node::close, "test-matching-commit-shutdown"))
        fundingEvent(
            mapOf(
                "kind" to "SPLIT_RUNTIME_READY",
                "role" to settings.role.name,
                "pid" to ProcessHandle.current().pid(),
                "storageLevels" to node.storageLevels,
                "tradingReady" to node.tradingReady,
            ),
        )
        while (!node.isClosed && node.error == null) Thread.sleep(100)
        node.error?.let { throw IllegalStateException("매칭 시험 노드 실행 실패", it) }
    }
}

private class MatchingCommitCrashProbe(
    private val marketId: String,
    private val targetSequence: Long,
    private val codec: SplitOrderDeliveryCodec,
    private val marker: Path,
    private val recover: Boolean,
    private val maxResultBytes: Long,
) {
    private val testProcess = ProcessHandle.current().parent().orElseThrow()

    fun decorate(service: SplitTradingClusterAdapter): ClusteredService =
        object : ClusteredService by service {
            private lateinit var cluster: Cluster
            private var replayedOrder: ReplayedOrder? = null
            private var verifiedReplay = false

            override fun onStart(
                cluster: Cluster,
                snapshotImage: Image?,
            ) {
                this.cluster = cluster
                service.onStart(cluster, snapshotImage)
            }

            override fun onSessionMessage(
                session: ClientSession?,
                timestamp: Long,
                buffer: DirectBuffer,
                offset: Int,
                length: Int,
                header: Header,
            ) {
                val target = if (replayedOrder == null) targetOrder(buffer, offset, length) else null
                if (target == null) {
                    service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                    return
                }

                val position = cluster.logPosition()
                check(position > service.appliedLogPosition && service.tradingReady)
                if (!recover) {
                    writeMarker("BEFORE_APPLY", target, position, cluster.role())
                    // 테스트가 강제 종료를 담당한다. 테스트 프로세스가 사라져도 이 주문을 적용하지 않는다.
                    testProcess.onExit().join()
                    error("강제 종료를 담당한 테스트 프로세스가 사라졌습니다")
                }

                val role = cluster.role()
                check(role == Cluster.Role.FOLLOWER) { "복구 로그에서 대상 주문을 찾기 전에 신규 전달을 받았습니다" }
                service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                check(service.failure == null && service.tradingReady && service.appliedLogPosition == position)
                replayedOrder = ReplayedOrder(target, position)
            }

            override fun onRoleChange(newRole: Cluster.Role) {
                service.onRoleChange(newRole)
                if (!recover || verifiedReplay || newRole != Cluster.Role.LEADER) return

                // 복구 직후의 승격 콜백에서 읽으면 새 전달이 결과를 만들기 전에 확인할 수 있다.
                val replayed = checkNotNull(replayedOrder) { "리더가 되기 전에 대상 주문을 재생하지 못했습니다" }
                val resultSequence = verifyReplayedResult(service, replayed.input)
                writeMarker("APPLIED", replayed.input, replayed.position, Cluster.Role.FOLLOWER, resultSequence)
                verifiedReplay = true
            }

            override fun doBackgroundWork(nowNs: Long): Int = service.doBackgroundWork(nowNs)
        }

    private fun verifyReplayedResult(
        service: SplitTradingClusterAdapter,
        target: FundsOrderDispatch,
    ): Long {
        val dispatch = target.stream
        val stream =
            MatchingResultStream(
                matchingLineage = dispatch.matchingLineage,
                fundsLineage = dispatch.fundsLineage,
                marketId = dispatch.marketId,
                ownershipEpoch = dispatch.ownershipEpoch,
                configurationVersion = dispatch.configurationVersion,
            )
        val generation = checkNotNull(service.activeResultGeneration())
        val pending = service.readResults(generation, stream, fromSequence = targetSequence, maxEntries = 1, maxBytes = maxResultBytes)
        service.doBackgroundWork(System.nanoTime())
        check(pending.isDone)
        val result = checkNotNull(pending.join().entries.singleOrNull()) { "재생된 체결 결과가 없습니다" }
        val trade = result.events.filterIsInstance<TradeExecuted>().single()
        check(result.resultSequence == targetSequence && result.input == target && trade.takerOrderId == target.order.orderId)
        return result.resultSequence
    }

    private fun targetOrder(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
    ): FundsOrderDispatch? {
        if (length < SplitOrderDeliveryCodec.HEADER_BYTES || buffer.getShort(offset + 4, ByteOrder.LITTLE_ENDIAN).toInt() != 88) {
            return null
        }
        val bytes = ByteArray(length)
        buffer.getBytes(offset, bytes)
        return when (val command = codec.decode(bytes).command) {
            is SplitOrderDeliveryCommand.Apply -> command.input.takeIf(::matchesTarget)
            is SplitOrderDeliveryCommand.ApplyBatch -> command.inputs.singleOrNull(::matchesTarget)
            else -> null
        }
    }

    private fun matchesTarget(input: FundsOrderDispatch): Boolean =
        input.stream.marketId.value == marketId && input.dispatchSequence == targetSequence && !input.isCancellation

    private fun writeMarker(
        stage: String,
        target: FundsOrderDispatch,
        position: Long,
        role: Cluster.Role,
        resultSequence: Long? = null,
    ) {
        writeBoundedHttpJson(
            marker,
            mapOf(
                "stage" to stage,
                "pid" to ProcessHandle.current().pid(),
                "marketId" to marketId,
                "orderId" to target.order.orderId.value,
                "dispatchSequence" to target.dispatchSequence,
                "position" to position,
                "clusterRole" to role.name,
                "resultSequence" to resultSequence,
            ),
        )
    }

    private data class ReplayedOrder(
        val input: FundsOrderDispatch,
        val position: Long,
    )
}
