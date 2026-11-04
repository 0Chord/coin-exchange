package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingServiceRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SplitResultDeliveryClientTest {
    private val endpoint = SplitOrderDeliveryEndpoint("/existing-node-driver", "0=localhost:9000", "localhost:0", 198)

    @Test
    fun `개발 요청은 같은 correlation 응답을 poll한 뒤 반환하고 client 자원만 닫는다`() {
        val factory = Factory()
        DevelopmentSplitResultDeliveryClient(endpoint, factory = factory).use { client ->
            assertEquals("DESCRIBED", client.request(SplitResultDeliveryCommand.DescribePeer).code)
            assertEquals(1, factory.connection.offers)
            assertTrue(factory.connection.polls > 0)
            assertEquals(endpoint, factory.openedEndpoint)
            assertTrue(factory.attemptClosed)
        }
        assertTrue(factory.connection.closed)
    }

    @Test
    fun `전송 수용 뒤 응답이 없으면 완료를 꾸미지 않고 미확인으로 반환한다`() {
        val factory = Factory().also { it.reply = false }
        DevelopmentSplitResultDeliveryClient(endpoint, timeoutMs = 250, factory = factory).use { client ->
            val error = assertFailsWith<SplitResultDeliveryUnconfirmedException> { client.request(SplitResultDeliveryCommand.DescribePeer) }
            assertTrue(error.transportAccepted)
            assertEquals(1, factory.connection.offers)
        }
        assertTrue(factory.connection.closed)
    }

    @Test
    fun `연결 시도 만료는 미완료 시도만 닫고 새 Driver를 만들지 않는다`() {
        val factory = Factory().also { it.connect = false }
        assertFailsWith<IllegalStateException> { DevelopmentSplitResultDeliveryClient(endpoint, timeoutMs = 5, factory = factory) }
        assertTrue(factory.attemptClosed)
        assertEquals(endpoint.driverDirectory, factory.openedEndpoint?.driverDirectory)
        assertEquals(0, factory.connection.offers)
    }

    @Test
    fun `연결 시도 자원 정리가 실패하면 이미 연 client도 닫는다`() {
        val factory = Factory().also { it.failAttemptClose = true }
        assertFailsWith<IllegalStateException> { DevelopmentSplitResultDeliveryClient(endpoint, factory = factory) }
        assertTrue(factory.attemptClosed)
        assertTrue(factory.connection.closed)
    }

    private class Factory : SplitResultDeliveryConnectionFactory {
        var connect = true
        var reply = true
        var attemptClosed = false
        var failAttemptClose = false
        var openedEndpoint: SplitOrderDeliveryEndpoint? = null
        lateinit var receive: (SplitResultDeliveryReply) -> Unit
        val connection = Connection()

        override fun begin(
            endpoint: SplitOrderDeliveryEndpoint,
            timeoutNs: Long,
            onReply: (SplitResultDeliveryReply) -> Unit,
        ): SplitResultDeliveryConnectAttempt {
            openedEndpoint = endpoint
            receive = onReply
            return object : SplitResultDeliveryConnectAttempt {
                override fun poll(): SplitResultDeliveryConnection? = if (connect) connection else null

                override fun close() {
                    attemptClosed = true
                    check(!failAttemptClose) { "connection attempt cleanup failed" }
                }
            }
        }

        inner class Connection : SplitResultDeliveryConnection {
            val codec = SplitResultDeliveryCodec()
            var offers = 0
            var polls = 0
            var closed = false
            var correlation: Long? = null
            override val isClosed get() = closed

            override fun offer(bytes: ByteArray): Long {
                offers++
                correlation = codec.decode(bytes).correlationId
                return 1
            }

            override fun poll(): Int {
                polls++
                val id = correlation ?: return 0
                if (!reply) return 0
                correlation = null
                val response =
                    SplitResultDeliveryReply(
                        id,
                        "DESCRIBED",
                        TradingServiceRole.FUNDS,
                        ClusterLineage(1, 2),
                        "0".repeat(64),
                        1,
                        lifecycle = TradingLifecycle.RUNNING,
                    )
                receive(response.copy(correlationId = id + 1))
                receive(response)
                return 2
            }

            override fun keepAlive() = true

            override fun close() {
                closed = true
            }
        }
    }
}
