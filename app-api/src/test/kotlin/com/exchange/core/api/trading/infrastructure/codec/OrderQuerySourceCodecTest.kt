package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingOrderDisposition
import com.exchange.core.trading.MatchingOrderEffect
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingServiceRole
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderQuerySourceCodecTest {
    private val codec = OrderQuerySourceCodec()
    private val funds = ClusterLineage(Long.MIN_VALUE, Long.MAX_VALUE)
    private val matching = ClusterLineage(3, 4)
    private val consumer = "주문-조회"
    private val policy =
        TradingFeePolicySnapshot(
            FeeProductType.SPOT,
            FeeTier.VVIP,
            17,
            MakerTakerFeeRates(FeeRate(7_000), FeeRate(15_000)),
        )
    private val context =
        TradingMarketContext(
            MarketDefinition(MarketId("코인-원화"), AssetId("코인"), AssetId("원화"), 0, 1, 1),
            policy,
            7,
        )
    private val stream = MatchingResultStream(matching, funds, context.market.marketId, 13, 19)

    @Test
    fun `조회 제어 명령은 전역과 마켓 범위 및 누적 확인의 영 값을 보존한다`() {
        val commands =
            listOf(
                OrderQuerySourceCommand.Describe,
                OrderQuerySourceCommand.Read(consumer, null, 1),
                OrderQuerySourceCommand.Read(consumer, context.market.marketId.value, Long.MAX_VALUE),
                OrderQuerySourceCommand.Acknowledge(consumer, null, 0),
                OrderQuerySourceCommand.Acknowledge(consumer, context.market.marketId.value, Long.MAX_VALUE),
            )
        for (command in commands) {
            val bytes = codec.encode(Long.MAX_VALUE, command)
            assertEquals(OrderQuerySourceEnvelope(Long.MAX_VALUE, command), codec.decode(bytes))
            val padded = ByteArray(7) + bytes + ByteArray(3)
            assertTrue(OrderQuerySourceCodec.isQueryFrame(UnsafeBuffer(padded), 7, bytes.size))
            assertFalse(OrderQuerySourceCodec.isQueryFrame(UnsafeBuffer(padded), 7, 3))
        }
    }

    @Test
    fun `두 역할의 원본 정체성 정책 및 발급 회수 정산 위치를 보존한다`() {
        for (role in listOf(TradingServiceRole.FUNDS, TradingServiceRole.MATCHING)) {
            val expected = metadata(role)
            val restored = codec.decodeReply(codec.encodeReply(expected))
            assertEquals(expected, restored)
            assertEquals(context, restored.contexts.getValue(context.market.marketId.value))
            assertEquals(stream, restored.streams.getValue(context.market.marketId.value))
            assertEquals(3L, restored.progress.last().financialThrough)
            assertNull(restored.admission)
            assertNull(restored.result)
        }
        for (consumerId in listOf("", "-")) {
            val reply = metadata().copy(consumerId = consumerId)
            assertEquals(reply, codec.decodeReply(codec.encodeReply(reply)))
        }
    }

    @Test
    fun `기존 역할의 최대 128마켓 배치표와 진행 위치를 제어 응답 상한 안에 보존한다`() {
        val contexts =
            (0 until 128).associate { i ->
                val marketId = MarketId("M$i-QUOTE")
                marketId.value to context.copy(market = context.market.copy(marketId = marketId), ordinal = i)
            }
        val streams = contexts.mapValues { (_, value) -> stream.copy(marketId = value.market.marketId) }
        val progress = listOf(OrderQuerySourceProgress(null, 0, 0)) + contexts.keys.map { OrderQuerySourceProgress(it, 0, 0) }
        val expected = metadata().copy(contexts = contexts, streams = streams, progress = progress)
        val bytes = codec.encodeReply(expected)
        assertTrue(bytes.size <= OrderQuerySourceCodec.MAX_CONTROL_BYTES)
        assertEquals(expected, codec.decodeReply(bytes))
    }

    @Test
    fun `빈 원본도 입력 계약을 알리고 미지원 버전을 준비 상태로 정규화하지 않는다`() {
        for (version in listOf(1, 2)) {
            val reply = metadata().copy(inputContractVersion = version)
            assertEquals(reply, codec.decodeReply(codec.encodeReply(reply)))
        }
        assertFailsWith<IllegalArgumentException> { codec.encodeReply(metadata().copy(inputContractVersion = 3)) }
        val damaged = codec.encodeReply(metadata())
        ByteBuffer.wrap(damaged).putInt(damaged.size - 4, 3)
        assertFailsWith<IllegalArgumentException> { codec.decodeReply(damaged) }
    }

    @Test
    fun `접수와 전체 결과 응답은 기존 단일 원문 frame의 bytes와 정책을 그대로 유지한다`() {
        for (version in listOf(1, 2)) {
            val query = OrderQuerySourceCodec(version)
            val input = input(version)
            val originalAdmission = SplitOrderDeliveryCodec(version).encode(57, SplitOrderDeliveryCommand.Apply(input))
            assertContentEquals(originalAdmission, query.admissionBytes(57, input))
            assertContentEquals(originalAdmission, query.encodeReply(OrderQuerySourceReply(57, "FACT", admission = input)))
            val decodedAdmission = query.decodeReply(originalAdmission)
            assertEquals(57L, decodedAdmission.correlationId)
            assertEquals("FACT", decodedAdmission.code)
            assertEquals(input, decodedAdmission.admission)
            assertNull(decodedAdmission.result)

            val result = result(input)
            val originalResult = SplitResultDeliveryCodec(version).encode(58, SplitResultDeliveryCommand.Apply(result))
            assertContentEquals(originalResult, query.resultBytes(58, result))
            assertContentEquals(originalResult, query.encodeReply(OrderQuerySourceReply(58, "FACT", result = result)))
            val decodedResult = query.decodeReply(originalResult)
            assertEquals(58L, decodedResult.correlationId)
            assertEquals("FACT", decodedResult.code)
            assertEquals(result, decodedResult.result)
            assertEquals(result.effects, decodedResult.result!!.effects)
            assertNull(decodedResult.admission)
        }
    }

    @Test
    fun `최대 결과 frame도 작은 제어 응답 한도와 독립적으로 원문 그대로 운반한다`() {
        val maximum = SplitResultDeliveryBoundaryFixture.frame(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES, inputContractVersion = 2)
        assertTrue(maximum.bytes.size > OrderQuerySourceCodec.MAX_CONTROL_BYTES)
        assertContentEquals(maximum.bytes, codec.resultBytes(maximum.correlationId, maximum.result))
        assertContentEquals(maximum.bytes, codec.encodeReply(OrderQuerySourceReply(maximum.correlationId, "FACT", result = maximum.result)))
        val restored = codec.decodeReply(maximum.bytes)
        assertEquals(maximum.correlationId, restored.correlationId)
        assertEquals(maximum.result, restored.result)
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(maximum.bytes.copyOf(maximum.bytes.size - 1)) }
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(maximum.bytes + 0) }
    }

    @Test
    fun `제어와 메타데이터의 모든 잘림과 잘못된 schema 버전 종류 및 뒤따른 bytes를 거절한다`() {
        val command = codec.encode(77, OrderQuerySourceCommand.Read(consumer, context.market.marketId.value, 1))
        val reply = codec.encodeReply(metadata())
        for (length in command.indices) {
            assertFailsWith<IllegalArgumentException>("command prefix $length") { codec.decode(command.copyOf(length)) }
        }
        for (length in reply.indices) {
            assertFailsWith<IllegalArgumentException>("reply prefix $length") { codec.decodeReply(reply.copyOf(length)) }
        }
        for (index in listOf(1, 2, 3)) {
            val damagedCommand = command.copyOf().also { it[index] = (it[index].toInt() + 1).toByte() }
            val damagedReply = reply.copyOf().also { it[index] = (it[index].toInt() + 1).toByte() }
            assertFailsWith<IllegalArgumentException> { codec.decode(damagedCommand) }
            assertFailsWith<IllegalArgumentException> { codec.decodeReply(damagedReply) }
        }
        val unknownCommand = codec.encode(77, OrderQuerySourceCommand.Describe).also { it[12] = 99 }
        assertFailsWith<IllegalArgumentException> { codec.decode(unknownCommand) }
        assertFailsWith<IllegalArgumentException> {
            codec.decodeReply(SplitOrderDeliveryCodec(2).encode(77, SplitOrderDeliveryCommand.DescribePeer))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decodeReply(SplitResultDeliveryCodec(2).encode(77, SplitResultDeliveryCommand.DescribePeer))
        }
        assertFailsWith<IllegalArgumentException> { codec.decode(command + 0) }
        assertFailsWith<IllegalArgumentException> { codec.decodeReply(reply + 0) }
        assertFailsWith<IllegalArgumentException> { codec.decode(ByteArray(OrderQuerySourceCodec.MAX_CONTROL_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> { codec.decodeReply(ByteArray(OrderQuerySourceCodec.MAX_CONTROL_BYTES + 1)) }
    }

    @Test
    fun `입력과 응답의 음수 미래 위치 및 과다 마켓 수를 정규화하지 않고 거절한다`() {
        val read = codec.encode(77, OrderQuerySourceCommand.Read(consumer, null, 1))
        val ack = codec.encode(77, OrderQuerySourceCommand.Acknowledge(consumer, null, 1))
        assertFailsWith<IllegalArgumentException> { codec.decode(replaceLong(read, 4, 0)) }
        assertFailsWith<IllegalArgumentException> { codec.decode(replaceLong(read, read.size - 8, 0)) }
        assertFailsWith<IllegalArgumentException> { codec.decode(replaceLong(ack, ack.size - 8, -1)) }
        assertFailsWith<IllegalArgumentException> { codec.encode(0, OrderQuerySourceCommand.Describe) }
        assertFailsWith<IllegalArgumentException> { codec.encode(77, OrderQuerySourceCommand.Read(consumer, null, 0)) }
        assertFailsWith<IllegalArgumentException> { codec.encode(77, OrderQuerySourceCommand.Acknowledge(consumer, null, -1)) }
        assertFailsWith<IllegalArgumentException> { codec.encodeReply(metadata().copy(correlationId = 0)) }
        assertFailsWith<IllegalArgumentException> {
            codec.encodeReply(metadata().copy(progress = listOf(OrderQuerySourceProgress(null, 1, 2))))
        }
        val metadata = codec.encodeReply(metadata(TradingServiceRole.MATCHING))
        for (damaged in listOf(
            replaceLong(metadata, 4, 0),
            replaceLong(metadata, metadata.size - 28, -1),
            replaceLong(metadata, metadata.size - 20, 6),
            replaceLong(metadata, metadata.size - 12, -1),
        )) {
            assertFailsWith<IllegalArgumentException> { codec.decodeReply(damaged) }
        }
        val tooManyMarkets = metadata.copyOf()
        ByteBuffer.wrap(tooManyMarkets).putInt(streamCountOffset(metadata), OrderQuerySourceCodec.MAX_MARKETS + 1)
        assertFailsWith<IllegalArgumentException> { codec.decodeReply(tooManyMarkets) }
    }

    @Test
    fun `소비자 식별자는 정확한 UTF8 byte 경계와 다국어 내용을 보존한다`() {
        val maximum = "한".repeat(85) + "a"
        assertEquals(256, maximum.toByteArray(Charsets.UTF_8).size)
        val command = OrderQuerySourceCommand.Read(maximum, context.market.marketId.value, 1)
        assertEquals(command, codec.decode(codec.encode(77, command)).command)
        assertFailsWith<IllegalArgumentException> { codec.encode(77, command.copy(consumerId = maximum + "b")) }
        assertFailsWith<IllegalArgumentException> { codec.decode(readFrame(maximum + "b")) }
        assertFailsWith<IllegalArgumentException> { codec.decode(readFrame(" ")) }
        val malformed = readFrame("abc")
        // header·correlation·종류·문자열 길이 뒤 첫 byte는 UTF8 시작 byte여야 한다.
        malformed[15] = 0xFF.toByte()
        assertFailsWith<IllegalArgumentException> { codec.decode(malformed) }
    }

    private fun metadata(role: TradingServiceRole = TradingServiceRole.FUNDS): OrderQuerySourceReply =
        OrderQuerySourceReply(
            77,
            "DESCRIBED",
            role,
            if (role == TradingServiceRole.FUNDS) funds else matching,
            "a".repeat(64),
            consumer,
            mapOf(context.market.marketId.value to stream),
            mapOf(context.market.marketId.value to context),
            if (role == TradingServiceRole.FUNDS) {
                listOf(OrderQuerySourceProgress(null, 5, 2), OrderQuerySourceProgress(context.market.marketId.value, 0, 0, 3))
            } else {
                listOf(OrderQuerySourceProgress(context.market.marketId.value, 5, 2, 3))
            },
        )

    private fun input(version: Int): FundsOrderDispatch {
        val order =
            OrderAdmissionInput(
                context.market.marketId,
                TradingIdentifiers.order(funds, 5),
                UserId("구매자"),
                Side.BUY,
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(100),
                Quantity(3),
            )
        val calculator = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))
        val reservation =
            OrderReservation.create(
                order.marketId,
                order.orderId,
                order.userId,
                order.side,
                order.price,
                order.quantity,
                calculator.calculate(context.market, order.side, order.price, order.quantity, policy),
                policy,
            )
        return FundsOrderDispatch(
            FundsDispatchStream(funds, matching, order.marketId, 13, 19),
            3,
            5,
            3,
            order,
            reservation,
            context,
            inputContractVersion = version,
        )
    }

    private fun result(input: FundsOrderDispatch): MatchingResultEnvelope =
        MatchingResultEnvelope(
            stream,
            3,
            input,
            MatchingOutcome.MATCHED,
            null,
            7,
            8,
            listOf(OrderEnteredBook(input.order.marketId, 8, input.order.orderId, input.order.userId, Side.BUY, Price(100), Quantity(3))),
            listOf(MatchingOrderEffect(input, Quantity(3), Quantity(3), MatchingOrderDisposition.RESTING)),
        )

    private fun replaceLong(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ): ByteArray = bytes.copyOf().also { ByteBuffer.wrap(it).putLong(offset, value) }

    private fun streamCountOffset(bytes: ByteArray): Int =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            it.skipNBytes(12)
            it.readUTF()
            it.readUTF()
            it.skipNBytes(16)
            it.readUTF()
            if (it.readBoolean()) it.readUTF()
            bytes.size - it.available()
        }

    private fun readFrame(consumerId: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeShort(OrderQuerySourceCodec.SCHEMA)
            it.writeByte(1)
            it.writeByte(1)
            it.writeLong(77)
            it.writeByte(1)
            it.writeUTF(consumerId)
            it.writeBoolean(false)
            it.writeLong(1)
        }
        return bytes.toByteArray()
    }
}
