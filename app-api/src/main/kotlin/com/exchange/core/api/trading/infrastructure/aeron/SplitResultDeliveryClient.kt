package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingServiceRole
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import org.agrona.concurrent.UnsafeBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal interface SplitResultDeliveryConnection : AutoCloseable {
    val isClosed: Boolean

    fun offer(bytes: ByteArray): Long

    fun poll(): Int

    fun keepAlive(): Boolean
}

internal interface SplitResultDeliveryConnectAttempt : AutoCloseable {
    fun poll(): SplitResultDeliveryConnection?
}

/** 실제 연결과 장애를 관측하는 시험 연결은 같은 생명주기 계약을 사용한다. */
internal fun interface SplitResultDeliveryConnectionFactory {
    fun begin(
        endpoint: SplitOrderDeliveryEndpoint,
        timeoutNs: Long,
        onReply: (SplitResultDeliveryReply) -> Unit,
    ): SplitResultDeliveryConnectAttempt
}

internal object AeronSplitResultDeliveryConnectionFactory : SplitResultDeliveryConnectionFactory {
    override fun begin(
        endpoint: SplitOrderDeliveryEndpoint,
        timeoutNs: Long,
        onReply: (SplitResultDeliveryReply) -> Unit,
    ): SplitResultDeliveryConnectAttempt {
        val failure = AtomicReference<Throwable?>()
        val codec = SplitResultDeliveryCodec(endpoint.inputContractVersion)
        val context =
            AeronCluster
                .Context()
                .aeronDirectoryName(endpoint.driverDirectory)
                .ingressChannel("aeron:udp?term-length=${TradingAeronRuntime.TERM_LENGTH_BYTES}")
                .ingressEndpoints(endpoint.ingressEndpoints)
                .egressChannel("aeron:udp?endpoint=${endpoint.egressEndpoint}")
                .messageTimeoutNs(timeoutNs)
                .errorHandler { failure.compareAndSet(null, it) }
                // AeronCluster.pollEgress가 단편을 조립한 뒤 이 listener를 호출한다.
                .egressListener { _, _, buffer, offset, length, _ ->
                    require(length in SplitResultDeliveryCodec.HEADER_BYTES..SplitResultDeliveryCodec.MAX_MESSAGE_BYTES) {
                        "invalid result reply length"
                    }
                    val bytes = ByteArray(length)
                    buffer.getBytes(offset, bytes)
                    onReply(codec.decodeReply(bytes))
                }
        val attempt =
            try {
                AeronCluster.asyncConnect(context)
            } catch (error: Throwable) {
                context.close()
                throw error
            }
        return object : SplitResultDeliveryConnectAttempt {
            override fun poll(): SplitResultDeliveryConnection? {
                failure.get()?.let { throw IllegalStateException("split connection failed", it) }
                val cluster = attempt.poll() ?: return null
                // session header를 포함한 정상 최대 frame이 실제 publication에 들어가야 한다.
                try {
                    require(
                        cluster.ingressPublication().maxMessageLength() >=
                            SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + AeronCluster.SESSION_HEADER_LENGTH,
                    ) {
                        "result ingress maximum message length is too small"
                    }
                } catch (error: Throwable) {
                    cluster.close()
                    throw error
                }
                return object : SplitResultDeliveryConnection {
                    override val isClosed: Boolean get() = cluster.isClosed

                    override fun offer(bytes: ByteArray): Long {
                        require(bytes.size <= SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
                        return cluster.offer(UnsafeBuffer(bytes), 0, bytes.size)
                    }

                    override fun poll(): Int {
                        failure.get()?.let { throw IllegalStateException("split transport failed", it) }
                        return cluster.pollEgress()
                    }

                    override fun keepAlive(): Boolean = cluster.sendKeepAlive()

                    override fun close() = cluster.close()
                }
            }

            override fun close() = attempt.close()
        }
    }
}

/** 두 상대와의 연결·규격 확인·바이트 송신만 담당한다. 결과 순서와 정산 ACK의 의미는 Agent가 판단한다. */
internal class MatchingResultDeliveryTransport(
    private val configuration: SplitTradingConfiguration,
    fundsEndpoint: SplitOrderDeliveryEndpoint,
    matchingEndpoint: SplitOrderDeliveryEndpoint,
    private val limits: MatchingResultDeliveryLimits,
    private val connectionFactory: SplitResultDeliveryConnectionFactory,
    private val isCurrentGeneration: () -> Boolean,
    private val onFundsReply: (SplitResultDeliveryReply) -> Unit,
    private val onMatchingReply: (SplitResultDeliveryReply) -> Unit,
    private val onReconnectRequired: (Long, String) -> Unit,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    private class Peer(
        val endpoint: SplitOrderDeliveryEndpoint,
        val role: TradingServiceRole,
        val lineage: ClusterLineage,
    ) {
        var attempt: SplitResultDeliveryConnectAttempt? = null
        var connection: SplitResultDeliveryConnection? = null
        var openedAt = 0L
        var retryAt: Long? = null
        var lastKeepAlive = 0L
        var description: Frame? = null
        var verified = false
        var epoch = 0L
    }

    data class Frame(
        val correlation: Long,
        val bytes: ByteArray,
        val createdAt: Long,
        var accepted: Boolean = false,
    )

    private val codec = SplitResultDeliveryCodec(configuration.inputContractVersion)
    private val funds = Peer(fundsEndpoint, TradingServiceRole.FUNDS, configuration.fundsLineage)
    private val matching = Peer(matchingEndpoint, TradingServiceRole.MATCHING, configuration.matchingLineage)
    private var nextCorrelation = 1L

    val ready: Boolean get() = funds.verified && matching.verified

    fun offerFunds(
        frame: Frame,
        now: Long,
    ): Int = offer(funds, frame, now)

    fun offerMatching(
        frame: Frame,
        now: Long,
    ): Int = offer(matching, frame, now)

    fun retryAfter(deadline: Long?) {
        funds.retryAt = deadline
        matching.retryAt = deadline
    }

    fun poll(now: Long): Int {
        var work = 0
        for (peer in listOf(funds, matching)) {
            if (!isCurrentGeneration()) break
            try {
                if (peer.connection == null && peer.attempt == null && peer.retryAt?.let { now - it >= 0 } != false) {
                    val epoch = ++peer.epoch
                    peer.openedAt = now
                    peer.attempt =
                        connectionFactory.begin(peer.endpoint, limits.attemptTimeoutNs) { reply ->
                            if (peer.epoch == epoch && isCurrentGeneration()) {
                                receive(peer, reply)
                            }
                        }
                    work++
                }
                val attempt = peer.attempt
                if (attempt != null) {
                    val connection = attempt.poll()
                    if (connection != null) {
                        peer.connection = connection
                        peer.attempt = null
                        attempt.close()
                        peer.lastKeepAlive = now
                        peer.description = newFrame(SplitResultDeliveryCommand.DescribePeer, now)
                        work++
                    } else if (now - peer.openedAt >= limits.attemptTimeoutNs) {
                        onReconnectRequired(now, "split result peer connection attempt timed out")
                        return work + 1
                    }
                }
                val connection = peer.connection ?: continue
                if (connection.isClosed) {
                    onReconnectRequired(now, "split result peer connection closed")
                    return work + 1
                }
                work += connection.poll()
                if (!isCurrentGeneration() || peer.connection !== connection) return work
                if (now - peer.lastKeepAlive >= limits.keepAliveNs) {
                    connection.keepAlive()
                    peer.lastKeepAlive = now
                    work++
                }
                val description = peer.description
                if (!peer.verified && description != null) {
                    if (now - description.createdAt >= limits.attemptTimeoutNs) {
                        onReconnectRequired(now, "split result peer description timed out")
                        return work + 1
                    }
                    if (!description.accepted) work += offer(peer, description, now)
                }
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Exception) {
                onReconnectRequired(now, "split result transport unavailable: ${error.message}")
                return work + 1
            }
        }
        return work
    }

    private fun receive(
        peer: Peer,
        reply: SplitResultDeliveryReply,
    ) {
        require(reply.role == peer.role && reply.lineage == peer.lineage && reply.topologySha256 == configuration.resultTopologySha256) {
            "split result peer role, lineage or topology does not match"
        }
        val description = peer.description
        if (!peer.verified && description?.correlation == reply.correlationId) {
            require(reply.code == "DESCRIBED") { "unexpected split result peer description: ${reply.code}" }
            if (reply.lifecycle != TradingLifecycle.RUNNING) {
                onReconnectRequired(nanoTime(), "split result peer is not initialized: ${reply.lifecycle}")
                return
            }
            peer.verified = true
            peer.description = null
            return
        }
        if (!peer.verified) return
        if (peer === matching) onMatchingReply(reply) else onFundsReply(reply)
    }

    private fun offer(
        peer: Peer,
        frame: Frame,
        now: Long,
    ): Int {
        val result = peer.connection?.offer(frame.bytes) ?: return 0
        when {
            result > 0 -> {
                frame.accepted = true
            }

            result == Publication.NOT_CONNECTED || result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED -> {
                onReconnectRequired(now, "split result publication unavailable: $result")
            }

            result != Publication.BACK_PRESSURED && result != Publication.ADMIN_ACTION -> {
                throw IllegalStateException("unexpected split result publication result: $result")
            }
        }
        return if (result > 0) 1 else 0
    }

    fun newFrame(
        command: SplitResultDeliveryCommand,
        now: Long,
    ): Frame {
        check(nextCorrelation < Long.MAX_VALUE) { "split result correlation sequence exhausted" }
        val correlation = nextCorrelation++
        return Frame(correlation, codec.encode(correlation, command), now)
    }

    override fun close() {
        var failure: Throwable? = null
        for (peer in listOf(funds, matching)) {
            peer.epoch++
            for (resource in listOf(peer.attempt, peer.connection)) {
                try {
                    resource?.close()
                } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error
                    if (previous != null && previous !== error) previous.addSuppressed(error)
                }
            }
            peer.attempt = null
            peer.connection = null
            peer.description = null
            peer.verified = false
        }
        failure?.let { throw it }
    }
}

internal class SplitResultDeliveryUnconfirmedException(
    val transportAccepted: Boolean,
) : IllegalStateException("split request result is unconfirmed; retain the same business identity")

/** 개발 제어용 단일 소유자 client. relay는 이 동기 요청 대신 비동기 factory를 사용한다. */
internal class DevelopmentSplitResultDeliveryClient(
    endpoint: SplitOrderDeliveryEndpoint,
    private val timeoutMs: Long = 3_000,
    factory: SplitResultDeliveryConnectionFactory = AeronSplitResultDeliveryConnectionFactory,
    private val onReply: (SplitResultDeliveryReply) -> Unit = {},
) : AutoCloseable {
    private val codec = SplitResultDeliveryCodec(endpoint.inputContractVersion)
    private val connection: SplitResultDeliveryConnection
    private var expected: Long? = null
    private var response: SplitResultDeliveryReply? = null
    private var nextCorrelation = 1L
    private var lastKeepAlive = System.nanoTime()

    init {
        require(timeoutMs in 1..60_000)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val attempt =
            factory.begin(endpoint, TimeUnit.MILLISECONDS.toNanos(timeoutMs)) { reply ->
                if (reply.correlationId == expected) response = reply
                onReply(reply)
            }
        var connected: SplitResultDeliveryConnection? = null
        var primaryFailure: Throwable? = null
        try {
            val idle = TradingAeronRuntime.idleStrategy()
            while (System.nanoTime() - deadline < 0 && !Thread.currentThread().isInterrupted) {
                connected = attempt.poll()
                if (connected != null) break
                idle.idle()
            }
            connection = checkNotNull(connected) { "split connection attempt timed out" }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                attempt.close()
            } catch (cleanup: Throwable) {
                try {
                    connected?.close()
                } catch (closingClient: Throwable) {
                    if (cleanup !== closingClient) cleanup.addSuppressed(closingClient)
                }
                val original = primaryFailure
                if (original == null) throw cleanup
                if (original !== cleanup) original.addSuppressed(cleanup)
            }
        }
    }

    fun offer(
        correlationId: Long,
        command: SplitResultDeliveryCommand,
    ): Long = connection.offer(codec.encode(correlationId, command))

    fun poll(): Int {
        val work = connection.poll()
        val now = System.nanoTime()
        if (now - lastKeepAlive >= TimeUnit.MILLISECONDS.toNanos(500)) {
            connection.keepAlive()
            lastKeepAlive = now
        }
        return work
    }

    fun request(command: SplitResultDeliveryCommand): SplitResultDeliveryReply {
        check(expected == null) { "finish the previous split request first" }
        val correlation = nextCorrelation.also { nextCorrelation = Math.addExact(it, 1) }
        val bytes = codec.encode(correlation, command)
        expected = correlation
        response = null
        var accepted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val idle = TradingAeronRuntime.idleStrategy()
        try {
            while (System.nanoTime() - deadline < 0 && !connection.isClosed && !Thread.currentThread().isInterrupted) {
                if (!accepted) {
                    val result = connection.offer(bytes)
                    if (result > 0) {
                        accepted = true
                    } else if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                        break
                    }
                }
                idle.idle(poll())
                response?.let { return it }
            }
            throw SplitResultDeliveryUnconfirmedException(accepted)
        } finally {
            expected = null
            response = null
        }
    }

    override fun close() = connection.close()
}
