package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingServiceRole
import io.aeron.driver.media.UdpChannel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SplitOrderDeliveryClientTest {
    private val endpoint = SplitOrderDeliveryEndpoint("/existing-node-driver", "0=localhost:9000", "localhost:0", 198)

    @Test
    fun `개발 요청은 같은 correlation 응답을 poll한 뒤 반환하고 client 자원만 닫는다`() {
        val factory = Factory()
        SplitOrderDeliveryClient(endpoint, factory = factory).use { client ->
            assertEquals("DESCRIBED", client.request(SplitOrderDeliveryCommand.DescribePeer).code)
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
        SplitOrderDeliveryClient(endpoint, timeoutMs = 250, factory = factory).use { client ->
            val error = assertFailsWith<SplitOrderDeliveryUnconfirmedException> { client.request(SplitOrderDeliveryCommand.DescribePeer) }
            assertTrue(error.transportAccepted)
            assertEquals(1, factory.connection.offers)
        }
        assertTrue(factory.connection.closed)
    }

    @Test
    fun `연결 시도 만료는 미완료 시도만 닫고 새 Driver를 만들지 않는다`() {
        val factory = Factory().also { it.connect = false }
        assertFailsWith<IllegalStateException> { SplitOrderDeliveryClient(endpoint, timeoutMs = 5, factory = factory) }
        assertTrue(factory.attemptClosed)
        assertEquals(endpoint.driverDirectory, factory.openedEndpoint?.driverDirectory)
        assertEquals(0, factory.connection.offers)
    }

    @Test
    fun `연결 시도 자원 정리가 실패하면 이미 연 client도 닫는다`() {
        val factory = Factory().also { it.failAttemptClose = true }
        assertFailsWith<IllegalStateException> { SplitOrderDeliveryClient(endpoint, factory = factory) }
        assertTrue(factory.attemptClosed)
        assertTrue(factory.connection.closed)
    }

    @Test
    fun `동적 응답 포트의 관찰 사본은 같은 tag로 실제 client 소켓을 공유하고 다른 연결과 분리된다`() {
        val dynamic = "aeron:udp?endpoint=127.0.0.1:0"
        assertNotEquals(UdpChannel.parse(dynamic).canonicalForm(), UdpChannel.parse(dynamic).canonicalForm())
        val shared = observedSplitEgressChannel(dynamic, 73)
        assertEquals(UdpChannel.parse(shared).canonicalForm(), UdpChannel.parse(shared).canonicalForm())
        assertNotEquals(UdpChannel.parse(shared).canonicalForm(), UdpChannel.parse(observedSplitEgressChannel(dynamic, 74)).canonicalForm())
        assertTrue(shared.contains("endpoint=127.0.0.1:0"))
    }

    @Test
    fun `관찰 연결은 실제 Aeron context가 없으면 시도를 닫고 실패한다`() {
        val delegate = Factory()
        val factory = ObservedAeronSplitOrderDeliveryConnectionFactory({}, delegate)

        val error = assertFailsWith<IllegalStateException> { factory.begin(endpoint, 1) {} }

        assertEquals("split observation requires the actual Aeron context", error.message)
        assertTrue(delegate.attemptClosed)
        assertEquals(0, delegate.connection.offers)
    }

    private class Factory : SplitOrderDeliveryConnectionFactory {
        var connect = true
        var reply = true
        var attemptClosed = false
        var failAttemptClose = false
        var openedEndpoint: SplitOrderDeliveryEndpoint? = null
        lateinit var receive: (SplitOrderDeliveryReply) -> Unit
        val connection = Connection()

        override fun begin(
            endpoint: SplitOrderDeliveryEndpoint,
            timeoutNs: Long,
            onReply: (SplitOrderDeliveryReply) -> Unit,
        ): SplitOrderDeliveryConnectAttempt {
            openedEndpoint = endpoint
            receive = onReply
            return object : SplitOrderDeliveryConnectAttempt {
                override fun poll(): SplitOrderDeliveryConnection? = if (connect) connection else null

                override fun close() {
                    attemptClosed = true
                    check(!failAttemptClose) { "connection attempt cleanup failed" }
                }
            }
        }

        inner class Connection : SplitOrderDeliveryConnection {
            val codec = SplitOrderDeliveryCodec()
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
                    SplitOrderDeliveryReply(
                        id,
                        "DESCRIBED",
                        TradingServiceRole.FUNDS,
                        ClusterLineage(1, 2),
                        "0".repeat(64),
                        1,
                        lifecycle = "RUNNING",
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
