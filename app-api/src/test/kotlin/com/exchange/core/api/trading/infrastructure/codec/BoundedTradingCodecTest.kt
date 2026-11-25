package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
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
import com.exchange.core.trading.ExecuteAdmission
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedTradingCodecTest {
    private val codec = BoundedTradingCodec()
    private val token = RequestToken(UserId("U1"), 0, 1, 7)
    private val intent = OrderIntent(MarketId("BTC-USDT"), 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(200_000_000))

    @Test
    fun `version3 preserves request token intent and internal execution identity`() {
        val commands =
            listOf(
                BoundedTradingCommand.Admit(token, intent),
                BoundedTradingCommand.Execution(ExecuteAdmission(ClusterLineage(1, 2), intent.marketId, 3, 9)),
                BoundedTradingCommand.Acknowledge(token.userId, 0, 1, 6),
                BoundedTradingCommand.Rotate(token.userId, 0, 1),
                BoundedTradingCommand.Quarantine(100, "STATE_MISMATCH"),
                BoundedTradingCommand.Progress,
            )
        for (command in commands) assertEquals(BoundedCommandEnvelope(17, command), codec.decode(codec.encode(17, command)))
    }

    @Test
    fun `legacy header truncated and concatenated messages do not enter bounded protocol`() {
        val bytes = codec.encode(17, BoundedTradingCommand.Admit(token, intent))
        assertFailsWith<AdmissionMessageException> { codec.decode(bytes.copyOf().also { it[6] = 2 }) }
        assertFailsWith<AdmissionMessageException> { codec.decode(bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<AdmissionMessageException> { codec.decode(bytes + bytes) }
    }

    @Test
    fun `small accepted and rejected receipts roundtrip without a candidate graph`() {
        val accepted = AdmissionReceipt(AdmissionCode.ACCEPTED, OrderId("O2example"), 4, 2)
        val rejected =
            AdmissionReceipt(
                AdmissionCode.REJECTED,
                reason = OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE,
                insufficientBalance = OrderAdmissionResult.InsufficientBalance(AssetId("USDT"), Amount(200), Amount(300), Amount(100)),
            )
        for (receipt in listOf(accepted, rejected)) {
            val response = BoundedTradingReply(17, receipt.code.name, 640, receipt)
            assertEquals(response, codec.decodeResponse(codec.encode(response)))
        }
    }

    @Test
    fun `4096 byte frame and 3072 byte initialization limits reject limit plus one`() {
        assertEquals(4096, BoundedTradingCodec.MAX_MESSAGE_BYTES)
        assertEquals(3072, BoundedTradingCodec.MAX_MANIFEST_BYTES)
        val maximum = ByteArray(BoundedTradingCodec.MAX_MANIFEST_BYTES) { 1 }
        val decoded = codec.decode(codec.encode(1, BoundedTradingCommand.Initialize(maximum)))
        kotlin.test.assertContentEquals(maximum, (decoded.command as BoundedTradingCommand.Initialize).manifest)
        assertFailsWith<IllegalArgumentException> { codec.encode(1, BoundedTradingCommand.Initialize(maximum + byteArrayOf(1))) }
        val oversized =
            com.exchange.core.api.trading.infrastructure.codec.generated.BoundedTradingCommand
                .newBuilder()
                .setCorrelationId(
                    1,
                ).setInitializeManifest(
                    com.google.protobuf.ByteString
                        .copyFrom(maximum + byteArrayOf(1)),
                ).build()
                .toByteArray()
        val framed = ByteArray(8 + oversized.size)
        java.nio.ByteBuffer
            .wrap(
                framed,
            ).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort(oversized.size.toShort())
            .putShort(20)
            .putShort(87)
            .putShort(3)
        oversized.copyInto(framed, 8)
        assertFailsWith<AdmissionMessageException> { codec.decode(framed) }
        assertFailsWith<AdmissionMessageException> { codec.decode(ByteArray(BoundedTradingCodec.MAX_MESSAGE_BYTES + 1)) }
        assertFailsWith<AdmissionMessageException> { codec.decodeResponse(ByteArray(BoundedTradingCodec.MAX_MESSAGE_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> {
            com.exchange.core.api.trading.infrastructure.history
                .BoundedTradingStateCodec()
                .decodeManifest(maximum + byteArrayOf(1))
        }
    }
}
