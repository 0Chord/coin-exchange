package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import io.aeron.ChannelUri
import io.aeron.FragmentAssembler
import io.aeron.cluster.codecs.MessageHeaderDecoder
import io.aeron.cluster.codecs.NewLeaderEventDecoder
import io.aeron.cluster.codecs.SessionEventDecoder
import java.util.UUID

/** 정상 factory가 연 실제 연결의 egress 사본과 현재 route만 시험에서 읽는다. */
internal class ObservedAeronSplitOrderDeliveryConnectionFactory(
    private val observer: (Map<String, Any?>) -> Unit,
    private val delegate: SplitOrderDeliveryConnectionFactory = AeronSplitOrderDeliveryConnectionFactory,
) : SplitOrderDeliveryConnectionFactory {
    override fun begin(
        endpoint: SplitOrderDeliveryEndpoint,
        timeoutNs: Long,
        onReply: (SplitOrderDeliveryReply) -> Unit,
    ): SplitOrderDeliveryConnectAttempt {
        var observeCurrentRoute: (() -> Unit)? = null
        val attempt =
            delegate.begin(endpoint, timeoutNs) { reply ->
                // pollEgress 안에서 응답을 소비하기 전에 해당 응답의 실제 route를 먼저 기록한다.
                checkNotNull(observeCurrentRoute) { "split reply arrived before the observed transport connected" }.invoke()
                onReply(reply)
            }
        val tap =
            try {
                val context = checkNotNull(attempt.aeronContext) { "split observation requires the actual Aeron context" }
                // 첫 poll 전 두 subscription에 같은 tag를 준다. :0을 두 번 열면 서로 다른 UDP 소켓이 된다.
                // 새 Driver마다 다시 시작하는 correlation ID 대신 연결별 UUID를 쓴다.
                val tag = UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE
                context.egressChannel(observedSplitEgressChannel(context.egressChannel(), tag))
                context.aeron().addSubscription(context.egressChannel(), context.egressStreamId())
            } catch (error: Throwable) {
                closeAfterFailure(error, attempt)
                throw error
            }
        // AsyncConnect는 REDIRECT를 listener에 전달하지 않으므로 같은 실제 egress의 사본만 읽는다.
        val events =
            FragmentAssembler { buffer, offset, _, _ ->
                val header = MessageHeaderDecoder().wrap(buffer, offset)
                val bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH
                when (header.templateId()) {
                    SessionEventDecoder.TEMPLATE_ID -> {
                        val event = SessionEventDecoder().wrap(buffer, bodyOffset, header.blockLength(), header.version())
                        observer(
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
                        observer(
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

        fun pollTap() = tap.poll(events, 10)

        return object : SplitOrderDeliveryConnectAttempt {
            private var connected = false

            override fun poll(): SplitOrderDeliveryConnection? {
                var connection: SplitOrderDeliveryConnection? = null
                try {
                    pollTap()
                    connection = attempt.poll() ?: return null
                    val cluster = checkNotNull(connection.aeronCluster) { "split observation requires the actual Aeron connection" }
                    var previousRoute: Map<String, Any?>? = null

                    fun observeRoute() {
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
                    observeRoute()
                    val transport = connection
                    connected = true
                    return object : SplitOrderDeliveryConnection by transport {
                        override fun offer(bytes: ByteArray): Long {
                            observeRoute()
                            return transport.offer(bytes)
                        }

                        override fun poll(): Int =
                            try {
                                val work = transport.poll()
                                pollTap()
                                observeRoute()
                                work
                            } catch (error: Throwable) {
                                closeAfterFailure(error, transport)
                                closeAfterFailure(error, tap)
                                throw error
                            }

                        override fun close() {
                            try {
                                transport.close()
                            } finally {
                                tap.close()
                            }
                        }
                    }
                } catch (error: Throwable) {
                    connection?.let { closeAfterFailure(error, it) }
                    closeAfterFailure(error, attempt)
                    closeAfterFailure(error, tap)
                    throw error
                }
            }

            override fun close() {
                try {
                    attempt.close()
                } finally {
                    if (!connected) tap.close()
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

private fun closeAfterFailure(
    error: Throwable,
    resource: AutoCloseable,
) {
    try {
        resource.close()
    } catch (cleanup: Throwable) {
        if (cleanup !== error) error.addSuppressed(cleanup)
    }
}
