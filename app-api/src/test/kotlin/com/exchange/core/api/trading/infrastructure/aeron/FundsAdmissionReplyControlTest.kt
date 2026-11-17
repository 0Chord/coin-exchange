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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** callback을 숨기는 위치를 검증한다. 실제 9노드의 확정·장애 검증은 별도 시험이 맡는다. */
class FundsAdmissionReplyControlTest {
    private val endpoint = SplitOrderDeliveryEndpoint("unused", "0=localhost:9000", "localhost:0", 198, 2)
    private val command =
        SplitOrderDeliveryCommand.Control(
            BoundedTradingCommand.Admit(
                RequestToken(UserId("buyer"), 0, 1, 1),
                OrderIntent(MarketId("BTC-USDT"), 7, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(6)),
            ),
        )

    @Test
    fun `정상 응답은 내부 callback을 통과하고 request 반환 뒤에만 관찰 완료를 기록한다`() {
        val factory = Factory()
        val observed = mutableListOf<Map<String, Any?>>()
        val control = FundsAdmissionReplyControl(command, false, factory, observed::add)
        val callbacks = mutableListOf<SplitOrderDeliveryReply>()
        SplitOrderDeliveryClient(endpoint, onReply = callbacks::add, factory = control.factory()).use { client ->
            val returned = client.request(command)
            assertTrue(observed.isEmpty())
            val evidence = control.completed(returned)
            assertEquals("ACCEPTED", evidence["code"])
            assertEquals(true, evidence["forwarded"])
            assertEquals(true, evidence["requestReturned"])
            assertEquals(false, evidence["unconfirmed"])
            assertEquals(1, observed.size)
            assertEquals(2, callbacks.size) // 다른 correlation은 그대로 전달하되 성공 근거로 세지 않는다.
            assertEquals(returned, callbacks.last())
        }
        assertEquals(1, factory.offers)
        assertTrue(factory.attemptClosed && factory.connectionClosed)
    }

    @Test
    fun `숨긴 실제 응답은 내부 설정에 도달하지 않아 client가 실제 미확인 예외로 끝난다`() {
        val factory = Factory()
        var observed: Map<String, Any?>? = null
        val callbacks = mutableListOf<SplitOrderDeliveryReply>()
        val control = FundsAdmissionReplyControl(command, true, factory) { observed = it }
        SplitOrderDeliveryClient(endpoint, 100, onReply = callbacks::add, factory = control.factory()).use { client ->
            val failure = assertFailsWith<SplitOrderDeliveryUnconfirmedException> { client.request(command) }
            assertTrue(failure.transportAccepted)
            val first = assertNotNull(observed)
            assertEquals("ACCEPTED_OBSERVED", first["code"])
            assertEquals(false, first["forwarded"])
            assertEquals(false, first["requestReturned"])
            assertEquals(false, first["unconfirmed"])
            assertEquals(1, callbacks.size)
            assertTrue(callbacks.single().correlationId != first["correlationId"])
            val final = control.unconfirmed(failure)
            assertEquals("UNCONFIRMED", final["code"])
            assertEquals(true, final["unconfirmed"])
            for (field in listOf("reply", "wireSha256", "requestSha256", "appliedLogPosition", "positiveOfferPosition")) {
                assertEquals(first[field], final[field], field)
            }
            assertEquals(1, factory.offers) // 수용 뒤 응답이 없어도 업무 요청을 새로 보내지 않는다.
        }
        assertTrue(factory.attemptClosed && factory.connectionClosed)
    }

    @Test
    fun `응답을 전혀 받지 못한 timeout을 실제 응답 숨김으로 꾸미지 않는다`() {
        val factory = Factory(sendReply = false)
        var observed = false
        val control = FundsAdmissionReplyControl(command, true, factory) { observed = true }
        SplitOrderDeliveryClient(endpoint, 100, factory = control.factory()).use { client ->
            val failure = assertFailsWith<SplitOrderDeliveryUnconfirmedException> { client.request(command) }
            assertTrue(failure.transportAccepted)
            assertFailsWith<IllegalStateException> { control.unconfirmed(failure) }
            assertFalse(observed)
        }
    }

    @Test
    fun `전용 연결에 다른 업무 원문을 보내면 delegate offer 전에 거절한다`() {
        val factory = Factory()
        val control = FundsAdmissionReplyControl(command, true, factory) {}
        SplitOrderDeliveryClient(endpoint, factory = control.factory()).use { client ->
            assertFailsWith<IllegalStateException> { client.request(SplitOrderDeliveryCommand.DescribePeer) }
            assertEquals(0, factory.offers)
        }
    }

    private class Factory(
        private val sendReply: Boolean = true,
    ) : SplitOrderDeliveryConnectionFactory {
        var offers = 0
        var attemptClosed = false
        var connectionClosed = false
        private var correlation: Long? = null
        private lateinit var callback: (SplitOrderDeliveryReply) -> Unit
        private val codec = SplitOrderDeliveryCodec(2)

        override fun begin(
            endpoint: SplitOrderDeliveryEndpoint,
            timeoutNs: Long,
            onReply: (SplitOrderDeliveryReply) -> Unit,
        ): SplitOrderDeliveryConnectAttempt {
            callback = onReply
            return object : SplitOrderDeliveryConnectAttempt {
                override fun poll(): SplitOrderDeliveryConnection =
                    object : SplitOrderDeliveryConnection {
                        override val isClosed get() = connectionClosed

                        override fun offer(bytes: ByteArray): Long {
                            offers++
                            correlation = codec.decode(bytes).correlationId
                            return 73
                        }

                        override fun poll(): Int {
                            if (!sendReply) return 0
                            val id = correlation ?: return 0
                            correlation = null
                            val receipt = AdmissionReceipt(AdmissionCode.ACCEPTED, OrderId("original-order"), 1, 1)
                            val reply =
                                SplitOrderDeliveryReply(
                                    id,
                                    "ACCEPTED",
                                    TradingServiceRole.FUNDS,
                                    ClusterLineage(1, 2),
                                    "0".repeat(64),
                                    256,
                                    control = BoundedTradingReply(id, "ACCEPTED", 256, admission = receipt),
                                )
                            callback(reply.copy(correlationId = id + 10))
                            callback(reply)
                            return 2
                        }

                        override fun keepAlive() = true

                        override fun close() {
                            connectionClosed = true
                        }
                    }

                override fun close() {
                    attemptClosed = true
                }
            }
        }
    }
}
