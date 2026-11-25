package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.trading.ClusterLineage
import io.aeron.Publication
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** 한 BTC 원문만 실제 offer 직전에 보류한다. 연결·poll·keepAlive·peer 확인은 실제 Aeron에 그대로 맡긴다. */
internal class MultiMatchingFailureControl(
    private val runId: String,
    private val boot: String,
    private val service: String,
    private val directory: Path,
    private val orderFactory: SplitOrderDeliveryConnectionFactory = AeronSplitOrderDeliveryConnectionFactory,
    private val resultFactory: SplitResultDeliveryConnectionFactory = AeronSplitResultDeliveryConnectionFactory,
) {
    private val json = JsonMapper.builder().build()
    private val orders = SplitOrderDeliveryCodec(2)
    private val results = SplitResultDeliveryCodec(2)
    private var installedGate: Gate? = null
    private var currentGate: Gate? = null
    private var blocked: Map<String, Any?>? = null
    private var lastBlocked: Map<String, Any?>? = null
    private var offered: Map<String, Any?>? = null
    private var reply: Map<String, Any?>? = null
    private var confirmed = false

    private data class Gate(
        val installationId: String,
        val gate: String,
        val sequence: Long,
        val enabled: Boolean,
    ) {
        fun observation(): Map<String, Any?> =
            mapOf("installationId" to installationId, "gate" to gate, "sequence" to sequence, "enabled" to enabled)
    }

    private class Connection(
        val id: String = UUID.randomUUID().toString(),
        var described: Map<String, Any?>? = null,
        var sent: Map<String, Any?>? = null,
    )

    fun orderConnections(): SplitOrderDeliveryConnectionFactory =
        SplitOrderDeliveryConnectionFactory { endpoint, timeout, onReply ->
            val observed = Connection()
            val attempt =
                orderFactory.begin(endpoint, timeout) { received ->
                    if (received.code == "DESCRIBED") {
                        observed.described =
                            mapOf(
                                "code" to received.code,
                                "correlationId" to received.correlationId,
                                "appliedLogPosition" to received.appliedLogPosition,
                            )
                    }
                    recordReply(observed, received.correlationId, received.code, received.ack?.through, received.appliedLogPosition)
                    onReply(received)
                }
            object : SplitOrderDeliveryConnectAttempt {
                override fun poll(): SplitOrderDeliveryConnection? {
                    val connection = attempt.poll() ?: return null
                    return object : SplitOrderDeliveryConnection by connection {
                        override fun offer(bytes: ByteArray): Long {
                            val envelope = orders.decode(bytes)
                            val command = envelope.command
                            val input = (command as? SplitOrderDeliveryCommand.Apply)?.input
                            val gate = selected("btc-dispatch", input?.stream?.marketId?.value, input?.dispatchSequence)
                            if (gate == null || input == null) return connection.offer(bytes)
                            val row =
                                observation(
                                    gate,
                                    observed,
                                    endpoint,
                                    envelope.correlationId,
                                    input.order.orderId.value,
                                    null,
                                    input.stream.fundsLineage,
                                    input.stream.matchingLineage,
                                    input.stream.ownershipEpoch,
                                    input.stream.configurationVersion,
                                    orders.encode(0, command),
                                    bytes,
                                )
                            if (hold(gate, row, bytes)) return Publication.BACK_PRESSURED
                            return connection.offer(bytes).also { accepted(gate, observed, row, it) }
                        }
                    }
                }

                override fun close() = attempt.close()
            }
        }

    fun resultConnections(): SplitResultDeliveryConnectionFactory =
        SplitResultDeliveryConnectionFactory { endpoint, timeout, onReply ->
            val observed = Connection()
            val attempt =
                resultFactory.begin(endpoint, timeout) { received ->
                    if (received.code == "DESCRIBED") {
                        observed.described =
                            mapOf(
                                "code" to received.code,
                                "correlationId" to received.correlationId,
                                "appliedLogPosition" to received.appliedLogPosition,
                            )
                    }
                    recordReply(observed, received.correlationId, received.code, received.ack?.through, received.appliedLogPosition)
                    onReply(received)
                }
            object : SplitResultDeliveryConnectAttempt {
                override fun poll(): SplitResultDeliveryConnection? {
                    val connection = attempt.poll() ?: return null
                    return object : SplitResultDeliveryConnection by connection {
                        override fun offer(bytes: ByteArray): Long {
                            val envelope = results.decode(bytes)
                            val command = envelope.command
                            val result = (command as? SplitResultDeliveryCommand.Apply)?.result
                            val gate = selected("btc-result", result?.stream?.marketId?.value, result?.resultSequence)
                            if (gate == null || result == null) return connection.offer(bytes)
                            val row =
                                observation(
                                    gate,
                                    observed,
                                    endpoint,
                                    envelope.correlationId,
                                    result.input.order.orderId.value,
                                    result.resultId,
                                    result.stream.fundsLineage,
                                    result.stream.matchingLineage,
                                    result.stream.ownershipEpoch,
                                    result.stream.configurationVersion,
                                    results.encode(0, command),
                                    bytes,
                                )
                            if (hold(gate, row, bytes)) return Publication.BACK_PRESSURED
                            return connection.offer(bytes).also { accepted(gate, observed, row, it) }
                        }
                    }
                }

                override fun close() = attempt.close()
            }
        }

    private fun selected(
        wanted: String,
        market: String?,
        sequence: Long?,
    ): Gate? {
        if (market != "BTC-USDT") return null
        val gate =
            installedGate ?: run {
                val file = directory.resolve("failover-control.json")
                if (!Files.exists(file)) return null
                val bytes = Files.readAllBytes(file)
                check(bytes.size <= 4 * 1024) { "장애 제어 파일 한도를 넘었습니다" }
                val value = json.readTree(bytes)
                val installed =
                    Gate(
                        value.path("installationId").asString(),
                        value.path("gate").asString(),
                        value.path("sequence").asLong(),
                        value.path("enabled").asBoolean(),
                    )
                check(installed.installationId.isNotBlank() && installed.installationId.length <= 128 && value.path("enabled").isBoolean)
                check(installed.enabled) { "장애 제어는 보류 상태로 한 번만 설치합니다" }
                check(
                    (installed.gate == "btc-dispatch" && installed.sequence == 1L) ||
                        (installed.gate == "btc-result" && installed.sequence == 2L),
                )
                installed.also { installedGate = it }
            }
        // 설정은 불변이다. 해제는 별도 빈 파일의 존재만으로 읽어 공유 파일 교체와 파싱이 겹치지 않게 한다.
        val effective = gate.copy(enabled = !Files.exists(directory.resolve("failover-release-${gate.installationId}")))
        return effective.takeIf { it.gate == wanted && it.sequence == sequence }
    }

    private fun observation(
        gate: Gate,
        connection: Connection,
        endpoint: SplitOrderDeliveryEndpoint,
        correlationId: Long,
        orderId: String,
        resultId: String?,
        fundsLineage: ClusterLineage,
        matchingLineage: ClusterLineage,
        ownershipEpoch: Long,
        configurationVersion: Long,
        canonical: ByteArray,
        wire: ByteArray,
    ): Map<String, Any?> =
        gate.observation() +
            mapOf(
                "marketId" to "BTC-USDT",
                "orderId" to orderId,
                "resultId" to resultId,
                "payloadSha256" to sha(canonical),
                "wireSha256" to sha(wire),
                "wireBytes" to wire.size,
                "stream" to
                    mapOf(
                        "direction" to if (gate.gate == "btc-dispatch") "FUNDS_TO_MATCHING" else "MATCHING_TO_FUNDS",
                        "fundsLineageHigh" to fundsLineage.high,
                        "fundsLineageLow" to fundsLineage.low,
                        "matchingLineageHigh" to matchingLineage.high,
                        "matchingLineageLow" to matchingLineage.low,
                        "ownershipEpoch" to ownershipEpoch,
                        "configurationVersion" to configurationVersion,
                    ),
                "bootId" to boot,
                "connectionId" to connection.id,
                "endpointClusterId" to endpoint.clusterId,
                "correlationId" to correlationId,
                "described" to connection.described,
            )

    @Synchronized
    private fun hold(
        gate: Gate,
        row: Map<String, Any?>,
        bytes: ByteArray,
    ): Boolean {
        check(
            currentGate == null ||
                (
                    currentGate?.installationId == gate.installationId && currentGate?.gate == gate.gate &&
                        currentGate?.sequence == gate.sequence
                ),
        ) { "실행 중 보류 대상을 바꾸지 않습니다" }
        currentGate = gate
        check(blocked == null || blocked?.get("payloadSha256") == row["payloadSha256"]) { "보류한 BTC 원문이 바뀌었습니다" }
        if (!gate.enabled) return false
        if (blocked == null) {
            blocked = row
            writeBoundedHttpArtifact(directory.resolve("held-$service-${gate.gate}-${gate.sequence}.bin"), bytes, true)
        }
        if (lastBlocked?.get("connectionId") != row["connectionId"]) {
            lastBlocked = row
            publish()
        }
        return true
    }

    @Synchronized
    private fun accepted(
        gate: Gate,
        connection: Connection,
        row: Map<String, Any?>,
        position: Long,
    ) {
        if (position <= 0 || confirmed) return
        currentGate = gate
        connection.sent = row + ("positiveOfferPosition" to position)
        offered = connection.sent
        publish()
    }

    @Synchronized
    private fun recordReply(
        connection: Connection,
        correlationId: Long,
        code: String,
        through: Long?,
        appliedLogPosition: Long,
    ) {
        if (confirmed) return
        val sent = connection.sent ?: return
        if (sent["correlationId"] != correlationId) return
        // 재시도 중 상관값이 바뀌어도 최종 성공은 실제 offer와 그 응답 한 쌍으로 남긴다.
        offered = sent
        reply =
            mapOf(
                "bootId" to boot,
                "connectionId" to connection.id,
                "correlationId" to correlationId,
                "code" to code,
                "through" to through,
                "appliedLogPosition" to appliedLogPosition,
                "payloadSha256" to sent["payloadSha256"],
            )
        confirmed = code in setOf("APPLIED", "DUPLICATE") && through != null && through >= checkNotNull(currentGate).sequence
        publish()
    }

    private fun publish() {
        val bytes =
            json.writeValueAsBytes(
                mapOf(
                    "runId" to runId,
                    "service" to service,
                    "bootId" to boot,
                    "gate" to currentGate?.observation(),
                    "blocked" to blocked,
                    "lastBlocked" to lastBlocked,
                    "offered" to offered,
                    "reply" to reply,
                ),
            )
        check(bytes.size <= 256 * 1024) { "장애 작업 관찰 한도를 넘었습니다" }
        writeBoundedHttpArtifact(directory.resolve("worker-$service.json"), bytes, true)
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
