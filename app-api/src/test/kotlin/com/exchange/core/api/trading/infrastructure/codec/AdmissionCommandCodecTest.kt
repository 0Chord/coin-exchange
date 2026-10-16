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
    fun `주문과 판단 정보를 SBE로 보냈다가 같은 값으로 읽는다`() {
        val command = exampleCommand()
        val encoded = codec.encode(command)
        assertEquals(command, codec.decode(encoded))
        assertContentEquals(encoded, codec.encode(command))
    }

    @Test
    fun `스키마에서 독립적으로 정한 바이트와 양방향으로 대조한다`() {
        // 8바이트 헤더, 50바이트 고정 필드, 길이와 UTF-8 문자열 8개를 명세 순서로 적었다.
        val expected =
            java.util.HexFormat.of().parseHex(
                "3200010057000100010101640000000000000003000000000000000001010700000000000000" +
                    "0a00000014000000d20296490000000015cd5b070200523101007501006d01006f01006d01004201005102004c31",
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
