package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReceipt
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingServiceRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 유한 client 분류의 경계를 검사한다. 실제 세션·redirect 적합성은 9노드 시험이 검증한다. */
class FundsQuorumClientTest {
    private val endpoint = SplitOrderDeliveryEndpoint("unused", "0=localhost:9000", "localhost:0", 198, 2)
    private val command =
        SplitOrderDeliveryCommand.Control(
            BoundedTradingCommand.Admit(
                RequestToken(UserId("buyer"), 0, 1, 2),
                OrderIntent(MarketId("BTC-USDT"), 7, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(1)),
            ),
        )

    @Test
    fun `offer 수용 뒤 응답이 없으면 승인으로 바꾸지 않고 같은 원문과 token을 보관한다`() {
        val factory = Factory(reply = false)
        val trace = FundsQuorumClientTrace(factory)
        SplitOrderDeliveryClient(endpoint, 20, factory = trace.factory()).use { client ->
            val result = trace.request(client, "pb", command)
            assertEquals("SENT_NO_REPLY", result["classification"])
            assertEquals(true, result["transportAccepted"])
            assertEquals(73L, result["positiveOfferPosition"])
            assertTrue(result["requestSha256"].toString().matches(Regex("[a-f0-9]{64}")))
            assertTrue(result["wireSha256"].toString().matches(Regex("[a-f0-9]{64}")))
            assertNull(result["receipt"])
            assertEquals(1, factory.offers)
        }
    }

    @Test
    fun `실제 응답은 최초 receipt와 위치를 보관하고 중복전송의 원문 지문은 그대로다`() {
        val factory = Factory(reply = true)
        val trace = FundsQuorumClientTrace(factory)
        SplitOrderDeliveryClient(endpoint, 100, factory = trace.factory()).use { client ->
            val first = trace.request(client, "pb", command)
            val second = trace.request(client, "pb", command)
            assertEquals("REPLIED", first["classification"])
            assertEquals("ACCEPTED", first["code"])
            assertEquals(256L, first["appliedLogPosition"])
            assertEquals(first["receipt"], second["receipt"])
            assertEquals(first["requestSha256"], second["requestSha256"])
            assertFalse(first["wireSha256"] == second["wireSha256"])
        }
    }

    @Test
    fun `연결 미확인은 P 업무 전송이나 금융 거절을 주장하지 않는다`() {
        val trace = FundsQuorumClientTrace()
        val result = trace.notConnected("pb", command, IllegalStateException("connect timed out"))
        assertEquals("CONNECT_UNCONFIRMED", result["classification"])
        assertEquals(false, result["connected"])
        assertEquals(false, result["transportAccepted"])
        assertNull(result["wireSha256"])
        assertNull(result["receipt"])
    }

    private class Factory(
        private val reply: Boolean,
    ) : SplitOrderDeliveryConnectionFactory {
        var offers = 0
        private val codec = SplitOrderDeliveryCodec(2)

        override fun begin(
            endpoint: SplitOrderDeliveryEndpoint,
            timeoutNs: Long,
            onReply: (SplitOrderDeliveryReply) -> Unit,
        ): SplitOrderDeliveryConnectAttempt =
            object : SplitOrderDeliveryConnectAttempt {
                override fun poll(): SplitOrderDeliveryConnection =
                    object : SplitOrderDeliveryConnection {
                        private var correlation: Long? = null
                        override val isClosed = false

                        override fun offer(bytes: ByteArray): Long {
                            offers++
                            correlation = codec.decode(bytes).correlationId
                            return 73
                        }

                        override fun poll(): Int {
                            if (!reply) return 0
                            val id = correlation ?: return 0
                            correlation = null
                            val receipt = AdmissionReceipt(AdmissionCode.ACCEPTED, OrderId("first-order"), 2, 2)
                            onReply(
                                SplitOrderDeliveryReply(
                                    id,
                                    "ACCEPTED",
                                    TradingServiceRole.FUNDS,
                                    ClusterLineage(1, 2),
                                    "0".repeat(64),
                                    256,
                                    control = BoundedTradingReply(id, "ACCEPTED", 256, admission = receipt),
                                ),
                            )
                            return 1
                        }

                        override fun keepAlive() = true

                        override fun close() = Unit
                    }

                override fun close() = Unit
            }
    }
}
