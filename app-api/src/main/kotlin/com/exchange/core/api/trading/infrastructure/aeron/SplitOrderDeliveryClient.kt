package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.RequestToken
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import org.agrona.concurrent.UnsafeBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Driver는 노드가 소유한다. 이 연결이 별도의 Driver나 기록 경로를 만들지 않는다. */
internal data class SplitOrderDeliveryEndpoint(
    val driverDirectory: String,
    val ingressEndpoints: String,
    val egressEndpoint: String,
    val clusterId: Int,
) {
    init {
        require(driverDirectory.isNotBlank() && ingressEndpoints.isNotBlank() && egressEndpoint.isNotBlank())
        require(clusterId >= 0)
    }
}

internal interface SplitOrderDeliveryConnection : AutoCloseable {
    val isClosed: Boolean

    fun offer(bytes: ByteArray): Long

    fun poll(): Int

    fun keepAlive(): Boolean
}

internal interface SplitOrderDeliveryConnectAttempt : AutoCloseable {
    fun poll(): SplitOrderDeliveryConnection?
}

/** 실제 연결과 장애를 관측하는 시험 연결은 같은 생명주기 계약을 사용한다. */
internal fun interface SplitOrderDeliveryConnectionFactory {
    fun begin(
        endpoint: SplitOrderDeliveryEndpoint,
        timeoutNs: Long,
        onReply: (SplitOrderDeliveryReply) -> Unit,
    ): SplitOrderDeliveryConnectAttempt
}

internal object AeronSplitOrderDeliveryConnectionFactory : SplitOrderDeliveryConnectionFactory {
    override fun begin(
        endpoint: SplitOrderDeliveryEndpoint,
        timeoutNs: Long,
        onReply: (SplitOrderDeliveryReply) -> Unit,
    ): SplitOrderDeliveryConnectAttempt {
        val failure = AtomicReference<Throwable?>()
        val codec = SplitOrderDeliveryCodec()
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
                    require(length in SplitOrderDeliveryCodec.HEADER_BYTES..SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES) {
                        "invalid split reply length"
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
        return object : SplitOrderDeliveryConnectAttempt {
            override fun poll(): SplitOrderDeliveryConnection? {
                failure.get()?.let { throw IllegalStateException("split connection failed", it) }
                val cluster = attempt.poll() ?: return null
                // session header를 포함한 정상 최대 frame이 실제 publication에 들어가야 한다.
                try {
                    require(
                        cluster.ingressPublication().maxMessageLength() >=
                            SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES + AeronCluster.SESSION_HEADER_LENGTH,
                    ) {
                        "split ingress maximum message length is too small"
                    }
                } catch (error: Throwable) {
                    cluster.close()
                    throw error
                }
                return object : SplitOrderDeliveryConnection {
                    override val isClosed: Boolean get() = cluster.isClosed

                    override fun offer(bytes: ByteArray): Long {
                        require(bytes.size <= SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES)
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

internal class SplitOrderDeliveryUnconfirmedException(
    val transportAccepted: Boolean,
) : IllegalStateException("split request result is unconfirmed; retain the same business identity")

/** 개발 제어용 단일 소유자 client. relay는 이 동기 요청 대신 비동기 factory를 사용한다. */
internal class SplitOrderDeliveryClient(
    endpoint: SplitOrderDeliveryEndpoint,
    private val timeoutMs: Long = 3_000,
    private val onReply: (SplitOrderDeliveryReply) -> Unit = {},
    factory: SplitOrderDeliveryConnectionFactory = AeronSplitOrderDeliveryConnectionFactory,
) : AutoCloseable {
    private val codec = SplitOrderDeliveryCodec()
    private val connection: SplitOrderDeliveryConnection
    private var expected: Long? = null
    private var response: SplitOrderDeliveryReply? = null
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
        var connected: SplitOrderDeliveryConnection? = null
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
        command: SplitOrderDeliveryCommand,
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

    fun request(command: SplitOrderDeliveryCommand): SplitOrderDeliveryReply {
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
            throw SplitOrderDeliveryUnconfirmedException(accepted)
        } finally {
            expected = null
            response = null
        }
    }

    fun cancel(
        token: RequestToken,
        intent: CancelIntent,
    ): SplitOrderDeliveryReply = request(SplitOrderDeliveryCommand.Cancel(token, intent))

    override fun close() = connection.close()
}
