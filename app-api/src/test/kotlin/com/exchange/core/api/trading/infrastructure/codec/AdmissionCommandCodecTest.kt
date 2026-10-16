package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AdmissionCommandCodecTest {
    private val codec = AdmissionCommandCodec()

    @Test
    fun `접수 명령은 Protobuf 전송 계약 버전 2로 인코딩한다`() {
        val encoded = codec.encode(exampleCommand())
        val version =
            java.nio.ByteBuffer
                .wrap(encoded)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .getShort(6)
                .toInt() and 0xFFFF
        assertEquals(2, version, "SBE 버전 1 바이트를 새 Protobuf 메시지로 보내면 안 됩니다")
    }

    @Test
    fun `주문과 판단 정보를 Protobuf로 보냈다가 같은 값으로 읽는다`() {
        val command = exampleCommand()
        val encoded = codec.encode(command)
        assertEquals(command, codec.decode(encoded))
        assertContentEquals(encoded, codec.encode(command))
    }

    @Test
    fun `스키마에서 독립적으로 정한 바이트와 양방향으로 대조한다`() {
        // 헤더의 본문 길이는 65, 종류 1·스키마 87·버전 2다.
        // 태그=(필드번호<<3)|wireType. 필드 6의 명시한 0도 30 00으로 남긴다.
        // 초 1234567890=d2 85 d8 cc 04, 나노초 123456789=95 9a ef 3a (varint).
        val expected =
            java.util.HexFormat.of().parseHex(
                "4100010057000200080110011801206428033000380140014807500a581460d285d8cc0468959aef3a" +
                    "720252317a01758201016d8a01016f9201016d9a010142a2010151aa01024c31",
            )
        assertContentEquals(expected, codec.encode(exampleCommand()))
        assertEquals(exampleCommand(), codec.decode(expected))
    }

    private fun exampleCommand() =
        AdmissionCommandPayload(
            requestId = "R1",
            order =
                OrderAdmissionInput(
                    MarketId("m"),
                    OrderId("o"),
                    UserId("u"),
                    Side.BUY,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    Price(100),
                    Quantity(3),
                ),
            market = MarketDefinition(MarketId("m"), AssetId("B"), AssetId("Q"), 0),
            feePolicySnapshot =
                TradingFeePolicySnapshot(
                    FeeProductType.SPOT,
                    FeeTier.NORMAL,
                    7,
                    MakerTakerFeeRates(FeeRate(10), FeeRate(20)),
                ),
            ledgerTransactionId = "L1",
            occurredAt = Instant.ofEpochSecond(1_234_567_890, 123_456_789),
        )
}
