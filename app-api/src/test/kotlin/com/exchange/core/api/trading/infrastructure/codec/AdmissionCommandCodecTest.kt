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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionCommand as WireAdmissionCommand

class AdmissionCommandCodecTest {
    private val codec = AdmissionCommandCodec()

    @Test
    fun `식별자 검증은 ASCII와 다중 바이트 UTF8의 256 바이트 경계를 유지한다`() {
        listOf("R1", "x".repeat(256), "한".repeat(85) + "x", "😀".repeat(64)).forEach { value ->
            assertEquals(value, AdmissionMessageFormat.text(value))
        }
        listOf("x".repeat(257), "한".repeat(86), "😀".repeat(65)).forEach { value ->
            assertFailsWith<AdmissionMessageException> { AdmissionMessageFormat.text(value) }
        }
    }

    @Test
    fun `ASCII 접두사 뒤의 잘못된 surrogate도 엄격하게 거절한다`() {
        listOf("\uD800", "R1\uDC00", "x".repeat(255) + "\uD800").forEach { value ->
            assertFailsWith<AdmissionMessageException> { AdmissionMessageFormat.text(value) }
        }
    }

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
        // 헤더의 본문 길이는 71, 종류 1·스키마 87·버전 2다.
        // 태그=(필드번호<<3)|wireType. 필드 6의 명시한 0도 30 00으로 남긴다.
        // 초 1234567890=d2 85 d8 cc 04, 나노초 123456789=95 9a ef 3a (varint).
        // 필드 22·23은 각각 b0 01·b8 01 뒤 단위 1을 명시한다.
        val expected =
            java.util.HexFormat.of().parseHex(
                "4700010057000200080110011801206428033000380140014807500a581460d285d8cc0468959aef3a" +
                    "720252317a01758201016d8a01016f9201016d9a010142a2010151aa01024c31b00101b80101",
            )
        assertContentEquals(expected, codec.encode(exampleCommand()))
        assertEquals(exampleCommand(), codec.decode(expected))
    }

    @Test
    fun `단위 필드가 없던 고정 v2 명령은 기존 기본 단위로 읽는다`() {
        // 단위가 추가되기 전의 65바이트 본문을 변경하지 않고 유지한다.
        val previous =
            java.util.HexFormat.of().parseHex(
                "4100010057000200080110011801206428033000380140014807500a581460d285d8cc0468959aef3a" +
                    "720252317a01758201016d8a01016f9201016d9a010142a2010151aa01024c31",
            )

        val decoded = codec.decode(previous)

        assertEquals(exampleCommand(), decoded)
        assertEquals(1L, decoded.market.priceTick)
        assertEquals(1L, decoded.market.quantityStep)
    }

    @Test
    fun `시장별 단위는 기본값으로 바꾸지 않고 두 필드에 명시해 보존한다`() {
        val command =
            exampleCommand().let {
                it.copy(market = it.market.copy(baseAssetScale = 8, priceTick = 100, quantityStep = 1_000_000))
            }

        val bytes = codec.encode(command)
        val wire = WireAdmissionCommand.parseFrom(bytes.copyOfRange(8, bytes.size))

        assertTrue(wire.hasPriceTick())
        assertTrue(wire.hasQuantityStep())
        assertEquals(100L, wire.priceTick)
        assertEquals(1_000_000L, wire.quantityStep)
        assertEquals(command, codec.decode(bytes))
    }

    @Test
    fun `단위 필드 둘 다 없는 v2 명령은 scale별로 정한 기존 단위를 복원한다`() {
        listOf(0 to 1L, 8 to 100_000_000L, 18 to 1_000_000_000_000_000_000L).forEach { (scale, step) ->
            val wire =
                wireCommandBuilder()
                    .setBaseAssetScale(scale)
                    .clearPriceTick()
                    .clearQuantityStep()
                    .build()

            val decoded = codec.decode(protobufFrame(1, wire.toByteArray()))

            assertEquals(scale, decoded.market.baseAssetScale)
            assertEquals(1L, decoded.market.priceTick)
            assertEquals(step, decoded.market.quantityStep)
        }
    }

    @Test
    fun `Long 곱 범위를 넘는 호환 단위도 손실 없이 왕복한다`() {
        val original = exampleCommand()
        listOf(
            original.market.copy(priceTick = Long.MAX_VALUE, quantityStep = Long.MAX_VALUE),
            original.market.copy(baseAssetScale = 18, priceTick = Long.MAX_VALUE, quantityStep = 1_000_000_000_000_000_000L),
        ).forEach { market ->
            val command = original.copy(market = market)

            assertEquals(command, codec.decode(codec.encode(command)))
        }
    }

    @Test
    fun `동일한 여섯 마켓 값만 재사용하고 정책과 요청은 별도로 읽는다`() {
        val original = exampleCommand().let { it.copy(market = it.market.copy(priceTick = 10, quantityStep = 10)) }
        val first = codec.decode(codec.encode(original))
        val changed =
            original.copy(
                requestId = "R2",
                feePolicySnapshot = original.feePolicySnapshot.copy(scheduleVersion = 8),
            )
        val repeated = codec.decode(codec.encode(changed))
        assertSame(first.market, repeated.market)
        assertEquals(changed, repeated)
        val variations =
            listOf(
                original.market.copy(marketId = MarketId("other-market")),
                original.market.copy(baseAssetId = AssetId("other-base")),
                original.market.copy(quoteAssetId = AssetId("other-quote")),
                original.market.copy(baseAssetScale = 1),
                original.market.copy(priceTick = 20),
                original.market.copy(quantityStep = 20),
            )
        variations.forEach { market ->
            val command = original.copy(market = market)
            val decoded = codec.decode(codec.encode(command))
            assertNotSame(first.market, decoded.market)
            assertEquals(command, decoded)
        }
    }

    @Test
    fun `캐시가 준비되어도 잘못된 마켓 단위를 정상 정의로 치환하지 않는다`() {
        codec.decode(protobufFrame(1, wireCommandBuilder().build().toByteArray()))
        val invalidDefinitions =
            listOf(
                wireCommandBuilder().setPriceTick(0).setQuantityStep(1),
                wireCommandBuilder().setPriceTick(1).setQuantityStep(0),
                wireCommandBuilder().setBaseAssetScale(8).setPriceTick(1).setQuantityStep(1),
                wireCommandBuilder().setBaseAssetScale(19).clearPriceTick().clearQuantityStep(),
                wireCommandBuilder().setQuoteAssetId("BTC"),
            )
        invalidDefinitions.forEach { wire ->
            assertFailsWith<AdmissionMessageException> {
                codec.decode(protobufFrame(1, wire.build().toByteArray()))
            }
        }
        assertEquals(exampleCommand(), codec.decode(codec.encode(exampleCommand())))
    }

    @Test
    fun `이전 v2 기본 단위와 명시된 동일 단위는 같은 검증된 값을 재사용한다`() {
        val wire =
            wireCommandBuilder()
                .setBaseAssetScale(8)
                .clearPriceTick()
                .clearQuantityStep()
                .build()
        val legacy = codec.decode(protobufFrame(1, wire.toByteArray()))
        val explicit = codec.decode(codec.encode(legacy))
        assertEquals(100_000_000L, legacy.market.quantityStep)
        assertSame(legacy.market, explicit.market)
        val altered = legacy.copy(market = legacy.market.copy(priceTick = 100, quantityStep = 1_000_000))
        val decoded = codec.decode(codec.encode(altered))
        assertNotSame(legacy.market, decoded.market)
        assertEquals(altered, decoded)
    }

    @Test
    fun `처음 열여섯 정의만 보관하고 나머지도 원본 값으로 읽는다`() {
        val first = codec.decode(codec.encode(exampleCommand()))
        (1 until 16).forEach { index ->
            val command = exampleCommand().let { it.copy(market = it.market.copy(marketId = MarketId("market-$index"))) }
            assertEquals(command, codec.decode(codec.encode(command)))
        }
        val overflow = exampleCommand().let { it.copy(market = it.market.copy(marketId = MarketId("uncached-market"))) }
        val a = codec.decode(codec.encode(overflow))
        val b = codec.decode(codec.encode(overflow))
        assertEquals(overflow, a)
        assertEquals(overflow, b)
        assertNotSame(a.market, b.market)
        assertSame(first.market, codec.decode(codec.encode(exampleCommand())).market)
    }

    @Test
    fun `동시 복원은 서로 다른 마켓의 값을 섞거나 가변 목록을 공유하지 않는다`() {
        val commands =
            (0 until 20).map { index ->
                exampleCommand().let { it.copy(market = it.market.copy(marketId = MarketId("market-$index"))) }
            }
        val frames = commands.map(codec::encode)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val jobs =
                (0 until 4).map { worker ->
                    workers.submit(
                        Callable {
                            repeat(40) { iteration ->
                                val index = (iteration + worker) % commands.size
                                assertEquals(commands[index], codec.decode(frames[index]))
                            }
                        },
                    )
                }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
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
