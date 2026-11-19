package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCodec
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCommand
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceReply
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import org.agrona.concurrent.UnsafeBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class OrderQuerySourceUnconfirmedException(
    val transportAccepted: Boolean,
) : IllegalStateException("query source request result is unconfirmed")

/** WAS의 전용 수집 스레드가 소유한다. 연결만 열며 공유 Driver의 수명은 변경하지 않는다. */
internal class OrderQuerySourceClient(
    endpoint: SplitOrderDeliveryEndpoint,
    private val timeoutMs: Long = 3_000,
    private val onSessionChanged: () -> Unit = {},
) : AutoCloseable {
    private val codec = OrderQuerySourceCodec(endpoint.inputContractVersion)
    private val failure = AtomicReference<Throwable?>()
    private val cluster: AeronCluster
    private var expected: Long? = null
    private var response: OrderQuerySourceReply? = null
    private var nextCorrelation = 1L
    private var lastKeepAlive = System.nanoTime()

    val isClosed: Boolean get() = cluster.isClosed
    val leadershipTermId: Long get() = cluster.leadershipTermId()

    init {
        require(timeoutMs in 1..60_000)
        val timeoutNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val context =
            AeronCluster
                .Context()
                .aeronDirectoryName(endpoint.driverDirectory)
                .ingressChannel("aeron:udp?term-length=${TradingAeronRuntime.TERM_LENGTH_BYTES}")
                .ingressEndpoints(endpoint.ingressEndpoints)
                .egressChannel("aeron:udp?endpoint=${endpoint.egressEndpoint}")
                .messageTimeoutNs(timeoutNs)
                .errorHandler { failure.compareAndSet(null, it) }
                // pollEgress 조립 이후에만 bounded 원문을 해독한다.
                .egressListener { _, _, buffer, offset, length, _ ->
                    require(length in 12..SplitResultDeliveryCodec.MAX_MESSAGE_BYTES) { "invalid query source reply length" }
                    val bytes = ByteArray(length)
                    buffer.getBytes(offset, bytes)
                    val reply = codec.decodeReply(bytes)
                    if (reply.correlationId == expected && response == null) response = reply
                }
        val attempt =
            try {
                AeronCluster.asyncConnect(context)
            } catch (error: Throwable) {
                closeAfterFailure(AutoCloseable { context.close() }, error)
                throw error
            }
        var connected: AeronCluster? = null
        var primaryFailure: Throwable? = null
        try {
            val deadline = System.nanoTime() + timeoutNs
            val idle = TradingAeronRuntime.idleStrategy()
            while (System.nanoTime() - deadline < 0) {
                requireNotInterrupted()
                requireTransportHealthy()
                connected = attempt.poll()
                if (connected != null) break
                idle.idle()
            }
            requireNotInterrupted()
            cluster = checkNotNull(connected) { "query source connection attempt timed out" }
            require(
                cluster.ingressPublication().maxMessageLength() >=
                    SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + AeronCluster.SESSION_HEADER_LENGTH,
            ) { "query source ingress maximum message length is too small" }
            requireTransportHealthy()
        } catch (error: Throwable) {
            primaryFailure = error
            connected?.let { closeAfterFailure(it, error) }
            throw error
        } finally {
            try {
                attempt.close()
            } catch (cleanup: Throwable) {
                val original = primaryFailure
                if (original == null) {
                    connected?.let { closeAfterFailure(it, cleanup) }
                    throw cleanup
                }
                if (original !== cleanup) original.addSuppressed(cleanup)
            }
        }
    }

    /** 두 요청 사이에도 호출해 session을 유지한다. */
    fun poll(): Int {
        try {
            requireNotInterrupted()
            requireTransportHealthy()
            requireLiveSession()
            val previousTerm = cluster.leadershipTermId()
            val work = cluster.pollEgress()
            requireLiveSession()
            if (previousTerm != cluster.leadershipTermId()) onSessionChanged()
            requireTransportHealthy()
            val now = System.nanoTime()
            if (now - lastKeepAlive >= KEEP_ALIVE_NS) {
                cluster.sendKeepAlive()
                requireLiveSession()
                lastKeepAlive = now
            }
            return work
        } catch (failure: Exception) {
            onSessionChanged()
            throw failure
        }
    }

    private fun requireLiveSession() {
        // Aeron은 새 리더를 기다리는 동안 세션을 닫기 전에 기존 ingress를 닫는다.
        check(!cluster.isClosed && !cluster.ingressPublication().isClosed) { "query source connection closed" }
    }

    fun request(command: OrderQuerySourceCommand): OrderQuerySourceReply {
        check(expected == null) { "finish the previous query source request first" }
        requireNotInterrupted()
        requireTransportHealthy()
        check(nextCorrelation < Long.MAX_VALUE) { "query source correlation sequence exhausted" }
        val correlation = nextCorrelation++
        val bytes = codec.encode(correlation, command)
        require(bytes.size <= SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
        expected = correlation
        response = null
        var accepted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val idle = TradingAeronRuntime.idleStrategy()
        try {
            while (System.nanoTime() - deadline < 0) {
                requireNotInterrupted()
                requireTransportHealthy()
                if (cluster.isClosed) break
                if (!accepted) {
                    when (val offered = cluster.offer(UnsafeBuffer(bytes), 0, bytes.size)) {
                        Publication.BACK_PRESSURED, Publication.ADMIN_ACTION -> {
                            Unit
                        }

                        Publication.NOT_CONNECTED, Publication.CLOSED, Publication.MAX_POSITION_EXCEEDED -> {
                            break
                        }

                        else -> {
                            check(offered > 0) { "unexpected query source publication result: $offered" }
                            accepted = true
                        }
                    }
                }
                idle.idle(poll())
                response?.let { return it }
            }
            requireNotInterrupted()
            throw OrderQuerySourceUnconfirmedException(accepted)
        } finally {
            expected = null
            response = null
        }
    }

    private fun requireTransportHealthy() {
        failure.get()?.let { throw IllegalStateException("query source transport failed", it) }
    }

    private fun requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("query source operation interrupted")
    }

    override fun close() = cluster.close()

    private fun closeAfterFailure(
        resource: AutoCloseable,
        primary: Throwable,
    ) {
        try {
            resource.close()
        } catch (cleanup: Throwable) {
            if (primary !== cleanup) primary.addSuppressed(cleanup)
        }
    }

    companion object {
        private val KEEP_ALIVE_NS = TimeUnit.MILLISECONDS.toNanos(500)
    }
}
