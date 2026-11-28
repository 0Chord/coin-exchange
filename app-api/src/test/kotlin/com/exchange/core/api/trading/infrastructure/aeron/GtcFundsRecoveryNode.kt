package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.MatchingResultReadStatus
import com.exchange.core.trading.TradingServiceRole
import io.aeron.Publication
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 기존 자금·매칭 노드에 시험용 결과 전송 제어와 요청 시 상태 관찰을 연결한다. */
fun main(args: Array<String>) {
    val control = GtcFundsResultControl(Path.of(args.single()))
    val settings = splitTradingSettings()
    require(!settings.configuration.transferBatchEnabled)
    val matching = settings.role == TradingServiceRole.MATCHING
    val inputVersion = settings.configuration.inputContractVersion
    val resultConnections = if (matching) control.connections(inputVersion) else AeronSplitResultDeliveryConnectionFactory
    lateinit var service: SplitTradingClusterAdapter
    DevelopmentSplitTradingNode
        .start(
            settings,
            orderConnectionFactory = AeronSplitOrderDeliveryConnectionFactory,
            resultConnectionFactory = resultConnections,
            serviceDecorator = { adapter ->
                service = adapter
                if (matching) adapter else control.observeFunds(adapter, inputVersion)
            },
        ).use { node ->
            Runtime.getRuntime().addShutdownHook(Thread(node::close, "test-gtc-recovery-shutdown"))
            fundingEvent(
                mapOf(
                    "kind" to "SPLIT_RUNTIME_READY",
                    "role" to settings.role.name,
                    "pid" to ProcessHandle.current().pid(),
                    "storageLevels" to node.storageLevels,
                    "tradingReady" to node.tradingReady,
                ),
            )
            while (!node.isClosed && node.error == null) {
                if (matching) control.observeMatchingResults(service, settings.configuration)
                Thread.sleep(50)
            }
            node.error?.let { throw IllegalStateException("정산 복구 시험의 ${settings.role} JVM 실행 실패", it) }
        }
}

internal class GtcFundsResultControl(
    private val directory: Path,
) {
    private val json = JsonMapper.builder().build()
    private val gate = directory.resolve("funds-result-gate.json")
    private val request = directory.resolve("matching-result-state-request.json")
    private val observed = directory.resolve("matching-result-state.json")
    private val fundsStateRequest = directory.resolve("funds-state-request.json")
    private val fundsStateObserved = directory.resolve("funds-state.json")
    private var observationNumber = 0L

    fun configure(
        through: Long,
        withholdAcknowledgementsFrom: Long?,
    ) {
        require(through >= 0 && (withholdAcknowledgementsFrom == null || withholdAcknowledgementsFrom > 0))
        writeSplitTestJson(gate, mapOf("allowThrough" to through, "withholdFrom" to withholdAcknowledgementsFrom))
    }

    fun awaitWithheldAcknowledgement(through: Long): Long {
        val reply = awaitEvidence(withheldAcknowledgement(through), "자금 확인 응답 $through 보류") { true }
        return reply.path("appliedLogPosition").longValue().also { check(it > 0L) }
    }

    fun awaitMatchingResultState(
        issued: Long,
        fundsSettledThrough: Long,
    ) {
        require(issued >= fundsSettledThrough && fundsSettledThrough >= 0)
        val number = ++observationNumber
        writeSplitTestJson(request, mapOf("number" to number, "issued" to issued, "fundsSettledThrough" to fundsSettledThrough))
        awaitEvidence(observed, "매칭 확정 $issued, 정산 확인 $fundsSettledThrough") { state ->
            state.path("number").longValue() == number && state.path("matches").booleanValue()
        }
    }

    fun fundsState(
        pid: Long,
        progress: () -> BoundedTradingReply,
    ): JsonNode {
        val number = ++observationNumber
        writeSplitTestJson(fundsStateRequest, mapOf("number" to number, "pid" to pid))
        val position = progress().appliedLogPosition
        return awaitEvidence(fundsStateObserved, "자금 JVM $pid 로그 $position 상태 조회") { state ->
            state.path("number").longValue() == number &&
                state.path("pid").longValue() == pid &&
                state.path("position").longValue() == position
        }
    }

    fun observeFunds(
        service: SplitTradingClusterAdapter,
        inputContractVersion: Int,
    ): ClusteredService {
        val codec = SplitOrderDeliveryCodec(inputContractVersion)
        val funds = service.javaClass.getDeclaredField("funds")
        funds.isAccessible = true
        val pid = ProcessHandle.current().pid()
        return object : ClusteredService by service {
            override fun onSessionMessage(
                session: ClientSession?,
                timestamp: Long,
                buffer: DirectBuffer,
                offset: Int,
                length: Int,
                header: Header,
            ) {
                service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                if (session == null || !service.tradingReady) return
                val request = read(fundsStateRequest) ?: return
                if (request.path("pid").longValue() != pid) return
                // 이 요청을 깨우는 제어 명령의 callback에서만 자금 소유 상태를 복사한다.
                if (length < SplitOrderDeliveryCodec.HEADER_BYTES) return
                val schema = buffer.getShort(offset + 4, ByteOrder.LITTLE_ENDIAN).toInt()
                if (schema != 88) return
                val bytes = ByteArray(length)
                buffer.getBytes(offset, bytes)
                val control = codec.decode(bytes).command as? SplitOrderDeliveryCommand.Control
                if (control?.command != BoundedTradingCommand.Progress) return

                val owner = funds.get(service) as BoundedFundingState
                val state = checkNotNull(owner.recoveryAccounts(offset = 0, maxRows = 64))
                check(state.nextOffset == null) { "자금 복구 시험의 최대 잔고·예약 행을 넘었습니다" }
                val balances =
                    state.balances.map { balance ->
                        mapOf(
                            "userId" to balance.userId.value,
                            "assetId" to balance.assetId.value,
                            "available" to balance.available.value,
                            "hold" to balance.hold.value,
                        )
                    }
                val activeReservations = state.reservations.filter { it.status == OrderReservationStatus.ACTIVE }
                val reservations =
                    activeReservations.map { reservation ->
                        mapOf(
                            "orderId" to reservation.orderId.value,
                            "remainingAmount" to reservation.remainingAmount.value,
                            "remainingQuantity" to reservation.remainingQuantity.value,
                        )
                    }
                Files.delete(fundsStateRequest)
                writeSplitTestJson(
                    fundsStateObserved,
                    mapOf(
                        "number" to request.path("number").longValue(),
                        "pid" to pid,
                        "position" to service.appliedLogPosition,
                        "balances" to balances,
                        "reservations" to reservations,
                    ),
                )
            }

            override fun doBackgroundWork(nowNs: Long): Int = service.doBackgroundWork(nowNs)
        }
    }

    fun connections(inputContractVersion: Int): SplitResultDeliveryConnectionFactory {
        val codec = SplitResultDeliveryCodec(inputContractVersion)
        return SplitResultDeliveryConnectionFactory { endpoint, timeout, onReply ->
            val factory = AeronSplitResultDeliveryConnectionFactory
            if (endpoint.clusterId != SplitTradingNodeSettings.FUNDS_CLUSTER_ID) {
                factory.begin(endpoint, timeout, onReply)
            } else {
                val attempt =
                    factory.begin(endpoint, timeout) { reply ->
                        val ack = reply.ack
                        val limit = read(gate)?.path("withholdFrom")
                        if (ack != null && limit?.isIntegralNumber == true && ack.through >= limit.longValue()) {
                            // 누적 Progress도 같은 순번을 확인할 수 있으므로 함께 전달하지 않는다.
                            val marker = withheldAcknowledgement(ack.through)
                            if (reply.code == "APPLIED" && !Files.exists(marker)) {
                                writeSplitTestJson(marker, mapOf("appliedLogPosition" to reply.appliedLogPosition))
                            }
                        } else {
                            onReply(reply)
                        }
                    }
                object : SplitResultDeliveryConnectAttempt by attempt {
                    override fun poll(): SplitResultDeliveryConnection? {
                        val actual = attempt.poll() ?: return null
                        return object : SplitResultDeliveryConnection by actual {
                            override fun offer(bytes: ByteArray): Long {
                                val command = codec.decode(bytes).command
                                check(command !is SplitResultDeliveryCommand.ApplyBatch) { "시험 결과는 한 건씩 전달해야 합니다" }
                                if (command is SplitResultDeliveryCommand.Apply) {
                                    val through = read(gate)?.path("allowThrough")?.longValue() ?: Long.MAX_VALUE
                                    if (command.result.resultSequence > through) return Publication.BACK_PRESSURED
                                }
                                return actual.offer(bytes)
                            }
                        }
                    }
                }
            }
        }
    }

    fun observeMatchingResults(
        service: SplitTradingClusterAdapter,
        configuration: SplitTradingConfiguration,
    ) {
        val query = read(request) ?: return
        val generation = service.activeResultGeneration() ?: return
        val issued = query.path("issued").longValue()
        val settled = query.path("fundsSettledThrough").longValue()
        val market =
            configuration.matchingConfiguration.markets
                .single()
                .market.marketId
        val stream = configuration.matchingConfiguration.resultStream(market)
        val limits = configuration.resultDeliveryLimits
        val pending = service.readResults(generation, stream, settled + 1, limits.maxReadEntries, limits.maxReadBytes)
        val state = pending.get(5, TimeUnit.SECONDS)
        val retained = state.entries.map { it.resultSequence }
        val matches =
            state.status == MatchingResultReadStatus.BATCH &&
                state.resultIssued == issued &&
                state.fundsSettledThrough == settled &&
                retained == (settled + 1..issued).toList()
        if (matches) Files.delete(request)
        writeSplitTestJson(
            observed,
            mapOf(
                "number" to query.path("number").longValue(),
                "status" to state.status.name,
                "issued" to state.resultIssued,
                "fundsSettledThrough" to state.fundsSettledThrough,
                "retained" to retained,
                "matches" to matches,
            ),
        )
    }

    private fun withheldAcknowledgement(through: Long): Path = directory.resolve("withheld-funds-ack-$through.json")

    private fun read(path: Path): JsonNode? = if (Files.exists(path)) json.readTree(Files.readString(path)) else null

    private fun awaitEvidence(
        path: Path,
        label: String,
        matches: (JsonNode) -> Boolean,
    ): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var last: JsonNode? = null
        while (System.nanoTime() < deadline) {
            val evidence = read(path)
            if (evidence != null && matches(evidence)) return evidence
            last = evidence
            Thread.sleep(25)
        }
        error("$label 확인 실패. 마지막 관찰 $last, 전송 설정 ${read(gate)}")
    }
}
