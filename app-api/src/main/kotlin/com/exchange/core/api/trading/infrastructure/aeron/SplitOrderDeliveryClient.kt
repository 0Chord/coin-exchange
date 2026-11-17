package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import io.aeron.ChannelUri
import io.aeron.FragmentAssembler
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.codecs.MessageHeaderDecoder
import io.aeron.cluster.codecs.NewLeaderEventDecoder
import io.aeron.cluster.codecs.SessionEventDecoder
import org.agrona.concurrent.UnsafeBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Driver는 노드가 소유한다. 이 연결이 별도의 Driver나 기록 경로를 만들지 않는다. */
internal data class SplitOrderDeliveryEndpoint(
    val driverDirectory: String,
    val ingressEndpoints: String,
    val egressEndpoint: String,
    val clusterId: Int,
    val inputContractVersion: Int = 1,
) {
    init {
        require(driverDirectory.isNotBlank() && ingressEndpoints.isNotBlank() && egressEndpoint.isNotBlank())
        require(clusterId >= 0 && inputContractVersion in 1..2)
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
    ): SplitOrderDeliveryConnectAttempt = beginObserved(endpoint, timeoutNs, onReply, null)

    /** 정상 연결·redirect를 그대로 실행하며 선택한 시험에서만 실제 경로를 읽는다. */
    internal fun observed(observer: (Map<String, Any?>) -> Unit): SplitOrderDeliveryConnectionFactory =
        SplitOrderDeliveryConnectionFactory { endpoint, timeoutNs, onReply -> beginObserved(endpoint, timeoutNs, onReply, observer) }

    private fun beginObserved(
        endpoint: SplitOrderDeliveryEndpoint,
        timeoutNs: Long,
        onReply: (SplitOrderDeliveryReply) -> Unit,
        observer: ((Map<String, Any?>) -> Unit)?,
    ): SplitOrderDeliveryConnectAttempt {
        val failure = AtomicReference<Throwable?>()
        val codec = SplitOrderDeliveryCodec(endpoint.inputContractVersion)
        var observeCurrentRoute: (() -> Unit)? = null
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
                    observeCurrentRoute?.invoke()
                    onReply(codec.decodeReply(bytes))
                }
        val attempt =
            try {
                AeronCluster.asyncConnect(context)
            } catch (error: Throwable) {
                context.close()
                throw error
            }
        // AsyncConnect는 REDIRECT를 listener에 전달하지 않으므로 같은 실제 egress의 사본만 읽는다.
        val tap =
            try {
                observer?.let {
                    // 첫 poll 전 두 subscription에 같은 tag를 준다. :0을 두 번 열면 서로 다른 UDP 소켓이 된다.
                    // tag는 응답 채널을 통해 서버에도 전달된다. 새 Driver마다 다시 시작하는 correlation ID를 쓰지 않는다.
                    val tag = UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE
                    context.egressChannel(observedSplitEgressChannel(context.egressChannel(), tag))
                    context.aeron().addSubscription(context.egressChannel(), context.egressStreamId())
                }
            } catch (error: Throwable) {
                attempt.close()
                throw error
            }
        val events =
            observer?.let { observe ->
                FragmentAssembler { buffer, offset, _, _ ->
                    val header = MessageHeaderDecoder().wrap(buffer, offset)
                    val bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH
                    when (header.templateId()) {
                        SessionEventDecoder.TEMPLATE_ID -> {
                            val event = SessionEventDecoder().wrap(buffer, bodyOffset, header.blockLength(), header.version())
                            observe(
                                mapOf(
                                    "kind" to "SESSION_EVENT",
                                    "correlationId" to event.correlationId(),
                                    "clusterSessionId" to event.clusterSessionId(),
                                    "leadershipTermId" to event.leadershipTermId(),
                                    "leaderMemberId" to event.leaderMemberId(),
                                    "code" to event.code().name,
                                    "detail" to event.detail().also { check(it.length <= 4096) },
                                ),
                            )
                        }

                        NewLeaderEventDecoder.TEMPLATE_ID -> {
                            val event = NewLeaderEventDecoder().wrap(buffer, bodyOffset, header.blockLength(), header.version())
                            observe(
                                mapOf(
                                    "kind" to "NEW_LEADER_EVENT",
                                    "clusterSessionId" to event.clusterSessionId(),
                                    "leadershipTermId" to event.leadershipTermId(),
                                    "leaderMemberId" to event.leaderMemberId(),
                                    "ingressEndpoints" to event.ingressEndpoints().also { check(it.length <= 4096) },
                                ),
                            )
                        }
                    }
                }
            }

        fun pollTap() {
            if (tap != null && events != null) tap.poll(events, 10)
        }
        return object : SplitOrderDeliveryConnectAttempt {
            private var connected = false

            override fun poll(): SplitOrderDeliveryConnection? {
                pollTap()
                failure.get()?.let { throw IllegalStateException("split connection failed", it) }
                val cluster = attempt.poll() ?: return null
                var previousRoute: Map<String, Any?>? = null

                fun observeRoute() {
                    if (observer == null) return
                    val publication = cluster.ingressPublication()
                    val route =
                        mapOf(
                            "kind" to "ROUTE",
                            "clusterSessionId" to cluster.clusterSessionId(),
                            "leadershipTermId" to cluster.leadershipTermId(),
                            "leaderMemberId" to cluster.leaderMemberId(),
                            "ingressChannel" to publication.channel(),
                            "ingressEndpoint" to ChannelUri.parse(publication.channel()).get("endpoint"),
                            "ingressRegistrationId" to publication.registrationId(),
                            "ingressSessionId" to publication.sessionId(),
                            "ingressStreamId" to publication.streamId(),
                            "egressChannel" to cluster.egressSubscription().tryResolveChannelEndpointPort(),
                        )
                    if (route != previousRoute) observer(route).also { previousRoute = route }
                }
                observeCurrentRoute = ::observeRoute
                // session header를 포함한 정상 최대 frame이 실제 publication에 들어가야 한다.
                try {
                    if (tap != null && observer != null) {
                        val actual = cluster.egressSubscription().tryResolveChannelEndpointPort()
                        val tapped = tap.tryResolveChannelEndpointPort()
                        require(
                            actual != null && tapped != null &&
                                ChannelUri.parse(actual).get("endpoint") == ChannelUri.parse(tapped).get("endpoint"),
                        ) {
                            "split observer egress endpoint differs from the actual session"
                        }
                        observer(
                            mapOf(
                                "kind" to "EGRESS_TAP_BOUND",
                                "egressChannel" to actual,
                                "tapChannel" to tapped,
                                "egressRegistrationId" to cluster.egressSubscription().registrationId(),
                                "tapRegistrationId" to tap.registrationId(),
                                "egressStreamId" to cluster.egressSubscription().streamId(),
                            ),
                        )
                    }
                    observeRoute()
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
                connected = true
                return object : SplitOrderDeliveryConnection {
                    override val isClosed: Boolean get() = cluster.isClosed

                    override fun offer(bytes: ByteArray): Long {
                        require(bytes.size <= SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES)
                        observeRoute()
                        return cluster.offer(UnsafeBuffer(bytes), 0, bytes.size)
                    }

                    override fun poll(): Int {
                        failure.get()?.let { throw IllegalStateException("split transport failed", it) }
                        val work = cluster.pollEgress()
                        pollTap()
                        observeRoute()
                        return work
                    }

                    override fun keepAlive(): Boolean = cluster.sendKeepAlive()

                    override fun close() {
                        try {
                            cluster.close()
                        } finally {
                            tap?.close()
                        }
                    }
                }
            }

            override fun close() {
                try {
                    attempt.close()
                } finally {
                    if (!connected) tap?.close()
                }
            }
        }
    }
}

/** 관찰 사본과 실제 client가 같은 Driver의 같은 UDP endpoint를 공유하게 한다. */
internal fun observedSplitEgressChannel(
    channel: String,
    tag: Long,
): String {
    require(tag >= 0)
    return ChannelUri.parse(channel).apply { put("tags", tag.toString()) }.toString()
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
    private val codec = SplitOrderDeliveryCodec(endpoint.inputContractVersion)
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

    override fun close() = connection.close()
}
