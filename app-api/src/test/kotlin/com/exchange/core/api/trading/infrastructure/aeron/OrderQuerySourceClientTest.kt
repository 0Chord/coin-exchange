package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderQuerySourceClientTest {
    @Test
    fun `임기가 그대로여도 응답을 읽다가 송신 경로가 닫히면 복구 판정을 무효화한다`() {
        val cluster = mock(AeronCluster::class.java)
        val publication = mock(Publication::class.java)
        val attempt = mock(AeronCluster.AsyncConnect::class.java)
        var ingressClosed = false
        var sessionChanges = 0
        `when`(attempt.poll()).thenReturn(cluster)
        `when`(cluster.ingressPublication()).thenReturn(publication)
        `when`(cluster.leadershipTermId()).thenReturn(7L)
        `when`(publication.isClosed).thenAnswer { ingressClosed }
        `when`(publication.maxMessageLength()).thenReturn(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + AeronCluster.SESSION_HEADER_LENGTH)
        `when`(cluster.pollEgress()).thenAnswer {
            ingressClosed = true
            0
        }

        val endpoint = SplitOrderDeliveryEndpoint("/unused-query-source-test", "0=127.0.0.1:9000", "127.0.0.1:0", 0)
        mockStatic(AeronCluster::class.java).use { factory ->
            val openCluster =
                factory.`when`<AeronCluster.AsyncConnect> {
                    AeronCluster.asyncConnect(any(AeronCluster.Context::class.java))
                }
            openCluster.thenReturn(attempt)
            OrderQuerySourceClient(endpoint, onSessionChanged = { sessionChanges++ }).use { client ->
                assertFalse(publication.isClosed)

                assertFailsWith<IllegalStateException> { client.poll() }

                assertTrue(publication.isClosed)
                assertFalse(cluster.isClosed)
                assertEquals(7L, cluster.leadershipTermId())
                assertEquals(1, sessionChanges)
            }
        }
    }
}
