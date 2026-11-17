package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** B0를 처리한 실제 세션을 controller의 장애 주입 뒤 P 두 건까지 유지한다. */
internal fun runFundsQuorumClient(
    scope: String,
    baseline: Boolean,
    configurationVersion: Long,
    control: Path,
    endpoint: SplitOrderDeliveryEndpoint,
    probe: (String, Long, () -> Unit) -> Map<String, Any?>,
): Map<String, Any?> {
    require(scope.matches(Regex("[a-z0-9-]{1,40}")))
    val json = JsonMapper.builder().build()
    val file = control.resolve("quorum-client-$scope.json")
    val start = control.resolve("quorum-start-$scope.json")
    val finish = control.resolve("quorum-finish-$scope.json")
    val overallDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    val trace = FundsQuorumClientTrace()
    val common = mapOf("scope" to scope, "mode" to if (baseline) "baseline" else "pending", "pid" to ProcessHandle.current().pid())
    var baselineEvidence: Map<String, Any?>? = null
    var probeEvidence: Map<String, Map<String, Any?>>? = null
    var probeCorrelation: Long? = null
    var startNonce: String? = null

    fun publish(
        phase: String,
        attempts: List<Map<String, Any?>> = emptyList(),
    ): Map<String, Any?> {
        val result =
            common +
                mapOf(
                    "phase" to phase,
                    "startNonce" to startNonce,
                    "baseline" to baselineEvidence,
                    "probeCorrelationId" to probeCorrelation,
                    "probes" to probeEvidence,
                    "attempts" to attempts,
                    "transport" to trace.events.toList(),
                )
        trace.requireHealthy()
        writeBoundedHttpJson(file, result)
        return result
    }

    fun command(
        label: String,
        sequence: Long,
        quantity: Long,
    ): SplitOrderDeliveryCommand =
        SplitOrderDeliveryCommand.Control(
            BoundedTradingCommand.Admit(
                RequestToken(UserId("buyer"), if (label == "btc") 0 else 1, 1, sequence),
                OrderIntent(
                    MarketId(if (label == "btc") "BTC-USDT" else "SUI-USDT"),
                    configurationVersion,
                    Side.BUY,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    Price(100),
                    Quantity(quantity),
                ),
            ),
        )

    fun readStart() {
        if (!Files.isRegularFile(start)) return
        check(Files.size(start) <= 2048) { "P 시작 신호 한도를 넘었습니다" }
        val nonce = json.readTree(Files.readAllBytes(start)).path("nonce").asString()
        require(nonce.matches(Regex("[A-Za-z0-9_-]{8,96}")))
        startNonce = nonce
    }

    fun awaitFinish(client: SplitOrderDeliveryClient?) {
        var pollable = client != null
        while (System.nanoTime() - overallDeadline < 0 && !Thread.currentThread().isInterrupted) {
            if (Files.isRegularFile(finish)) {
                check(Files.size(finish) <= 2048) { "client 회수 신호 한도를 넘었습니다" }
                val nonce = json.readTree(Files.readAllBytes(finish)).path("nonce").asString()
                check(nonce == startNonce) { "client 회수 신호가 현재 P 시작 nonce와 다릅니다" }
                return
            }
            if (pollable) {
                try {
                    checkNotNull(client).poll()
                } catch (_: Exception) {
                    pollable = false
                }
            }
            Thread.sleep(1)
        }
        error("scope 규칙 회수 뒤 client 종료 신호 기한을 넘었습니다")
    }
    val pending = listOf("pb" to command("btc", 2, 1), "ps" to command("sui", 1, 1))
    publish("CONNECTING")
    val connection =
        try {
            SplitOrderDeliveryClient(endpoint, 10_000, factory = trace.factory())
        } catch (error: Exception) {
            trace.requireHealthy()
            if (baseline) throw error
            publish("CONNECT_UNCONFIRMED")
            val deadline = minOf(overallDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
            while (startNonce == null && System.nanoTime() - deadline < 0 && !Thread.currentThread().isInterrupted) {
                readStart()
                Thread.sleep(1)
            }
            check(startNonce != null) { "연결 미확인 scope의 P 시작 신호 기한을 넘었습니다" }
            val completed =
                publish(
                    "COMPLETE",
                    pending.map { (label, input) ->
                        trace.notConnected(label, input, error)
                    },
                )
            awaitFinish(null)
            return completed
        }
    connection.use {
        if (baseline) {
            baselineEvidence = trace.request(connection, "b0", command("btc", 1, 6))
            check(baselineEvidence["classification"] == "REPLIED" && baselineEvidence["code"] == "ACCEPTED") { "B0가 실제 세션에서 승인되지 않았습니다" }
            // 같은 helper/JVM에서만 정상 복제 probe를 직렬 발행한다. B0의 funds 세션은 닫지 않는다.
            probeCorrelation = Math.addExact(1_000_000_000_000L, System.currentTimeMillis())
            probeEvidence = listOf("funds", "btc", "sui").associateWith { probe(it, checkNotNull(probeCorrelation)) { connection.poll() } }
            publish("BASELINE_READY")
        } else {
            publish("CONNECTED")
        }
        val deadline = minOf(overallDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
        var transportFailure: Exception? = null
        while (startNonce == null && System.nanoTime() - deadline < 0 && !Thread.currentThread().isInterrupted) {
            readStart()
            if (transportFailure == null) {
                try {
                    connection.poll()
                } catch (error: Exception) {
                    // 선거로 세션이 닫혀도 controller 신호 전 P를 보내거나 새 세션으로 바꾸지 않는다.
                    transportFailure = error
                    publish("SESSION_UNCONFIRMED")
                }
            }
            Thread.sleep(1)
        }
        check(startNonce != null) { "장애 성립 뒤 P 시작 신호를 기다리다 기한을 넘었습니다" }
        val attempts =
            pending.map { (label, input) ->
                trace.request(connection, label, input)
            }
        publish("COMPLETE", attempts)
        awaitFinish(connection)
        return publish("COMPLETE", attempts)
    }
}

/** 전송 수용은 금융 확정으로 바꾸지 않고 실제 route와 응답을 별도로 결속한다. */
internal class FundsQuorumClientTrace(
    private val delegate: SplitOrderDeliveryConnectionFactory? = null,
) {
    private val codec = SplitOrderDeliveryCodec(2)
    val events = mutableListOf<Map<String, Any?>>()
    private var route: Map<String, Any?>? = null
    private var offer: Map<String, Any?> = emptyMap()
    private var requestLabel: String? = null
    private var replyRoute: Map<String, Any?>? = null
    private var accepted = false
    private var overflow = false

    private fun record(event: Map<String, Any?>) {
        if (events.size >= 128) {
            overflow = true
            error("quorum client transport 관찰 한도를 넘었습니다")
        }
        val observed = event + mapOf("index" to events.size + 1, "request" to requestLabel)
        events.add(observed)
        if (event["kind"] == "ROUTE") route = observed
    }

    fun requireHealthy() {
        check(!overflow) { "quorum client transport 관찰 누락입니다" }
    }

    fun factory(): SplitOrderDeliveryConnectionFactory =
        SplitOrderDeliveryConnectionFactory { endpoint, timeout, onReply ->
            val factory = delegate ?: AeronSplitOrderDeliveryConnectionFactory.observed(::record)
            val attempt =
                factory.begin(endpoint, timeout) { reply ->
                    replyRoute = route
                    record(
                        mapOf(
                            "kind" to "BUSINESS_REPLY",
                            "correlationId" to reply.correlationId,
                            "appliedLogPosition" to reply.appliedLogPosition,
                            "reply" to multiReply(reply),
                            "route" to route,
                        ),
                    )
                    onReply(reply)
                }
            object : SplitOrderDeliveryConnectAttempt {
                override fun poll(): SplitOrderDeliveryConnection? {
                    val connection = attempt.poll() ?: return null
                    return object : SplitOrderDeliveryConnection by connection {
                        override fun offer(bytes: ByteArray): Long {
                            val envelope = codec.decode(bytes)
                            val position = connection.offer(bytes)
                            offer =
                                mapOf(
                                    "correlationId" to envelope.correlationId,
                                    "wireSha256" to sha(bytes),
                                    "routeAtSend" to route,
                                    "lastOfferPosition" to position,
                                )
                            if (position > 0) {
                                accepted = true
                                offer = offer + ("positiveOfferPosition" to position)
                                record(mapOf("kind" to "TRANSPORT_ACCEPTED", "request" to requestLabel) + offer)
                            }
                            return position
                        }
                    }
                }

                override fun close() = attempt.close()
            }
        }

    fun notConnected(
        label: String,
        command: SplitOrderDeliveryCommand,
        error: Exception,
    ): Map<String, Any?> =
        requestIdentity(label, command) +
            mapOf("classification" to "CONNECT_UNCONFIRMED", "connected" to false, "transportAccepted" to false, "error" to describe(error))

    fun request(
        client: SplitOrderDeliveryClient,
        label: String,
        command: SplitOrderDeliveryCommand,
    ): Map<String, Any?> {
        requestLabel = label
        offer = emptyMap()
        replyRoute = null
        accepted = false
        val identity = requestIdentity(label, command)
        return try {
            val reply: SplitOrderDeliveryReply = client.request(command)
            identity + offer + multiReply(reply) +
                mapOf(
                    "classification" to "REPLIED",
                    "connected" to true,
                    "transportAccepted" to accepted,
                    "appliedLogPosition" to reply.appliedLogPosition,
                    "routeAtReply" to replyRoute,
                )
        } catch (error: SplitOrderDeliveryUnconfirmedException) {
            identity + offer +
                mapOf(
                    "classification" to if (error.transportAccepted) "SENT_NO_REPLY" else "CONNECTED_NOT_SENT",
                    "connected" to true,
                    "transportAccepted" to error.transportAccepted,
                )
        } catch (error: Exception) {
            check(!overflow) { "quorum client transport 관찰 누락입니다" }
            identity + offer +
                mapOf(
                    "classification" to "TRANSPORT_FAILURE",
                    "connected" to true,
                    "transportAccepted" to accepted,
                    "error" to describe(error),
                )
        } finally {
            requestLabel = null
        }
    }

    private fun requestIdentity(
        label: String,
        command: SplitOrderDeliveryCommand,
    ): Map<String, Any?> {
        val admission = (command as SplitOrderDeliveryCommand.Control).command as BoundedTradingCommand.Admit
        return mapOf(
            "request" to label,
            "requestSha256" to sha(codec.encode(0, command)),
            "token" to
                mapOf(
                    "userId" to admission.token.userId.value,
                    "channel" to admission.token.channel,
                    "epoch" to admission.token.epoch,
                    "sequence" to admission.token.sequence,
                ),
            "marketId" to admission.intent.marketId.value,
            "quantity" to admission.intent.quantity.value,
        )
    }

    private fun describe(error: Exception): String = "${error.javaClass.simpleName}: ${error.message}".take(1024)

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
