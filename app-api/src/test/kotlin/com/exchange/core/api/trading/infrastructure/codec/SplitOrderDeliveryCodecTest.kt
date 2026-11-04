package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDirection
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitRole
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.CancelCode
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.CancelReceipt
import com.exchange.core.trading.CancelReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.ExecuteAdmission
import com.exchange.core.trading.FundsCancelDispatch
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingServiceRole
import com.google.protobuf.Message
import com.google.protobuf.UnknownFieldSet
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.exchange.core.api.trading.infrastructure.codec.generated.Side as WireSide

class SplitOrderDeliveryCodecTest {
    private val codec = SplitOrderDeliveryCodec()
    private val source = ClusterLineage(1, 2)
    private val matching = ClusterLineage(3, 4)
    private val topology = "a".repeat(64)
    private val policy =
        TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.VVIP, 17, MakerTakerFeeRates(FeeRate(7_000), FeeRate(15_000)))
    private val context =
        TradingMarketContext(MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), AssetId("USDT"), 0, 2, 3), policy, 7)
    private val calculator = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))

    @Test
    fun `typed dispatch preserves both lineages original order full reservation and fixed context`() {
        for (side in Side.entries) {
            val input = dispatch(side)
            val bytes = codec.encode(91, SplitOrderDeliveryCommand.Apply(input))
            val wire = command(bytes)
            assertEquals(2, wire.apply.formatVersion)
            assertEquals(SplitDirection.SPLIT_FUNDS_TO_MATCHING, wire.apply.stream.direction)
            assertEquals(17L, wire.apply.reservation.feePolicy.scheduleVersion)
            assertEquals(7_000L, wire.apply.context.feePolicy.makerPpm)
            assertEquals(15_000L, wire.apply.context.feePolicy.takerPpm)
            assertEquals(SplitOrderDeliveryEnvelope(91, SplitOrderDeliveryCommand.Apply(input)), codec.decode(bytes))
            assertEquals(if (side == Side.BUY) 609L else 6L, input.reservation.reservedAmount.value)
        }
    }

    @Test
    fun `reservation optional released amount and nonzero remainder are preserved without snapshots`() {
        val input = dispatch()
        val partial = input.reservation.applyFill(Quantity(3), Amount(300), Amount(5), FeeRemainder(123_456))
        val released = partial.release()
        val settled = input.reservation.applyFill(Quantity(6), Amount(600), Amount(9), FeeRemainder(999_999))
        for (reservation in listOf(input.reservation, partial, released, settled)) {
            val original = input.copy(reservation = reservation)
            val bytes = codec.encode(1, SplitOrderDeliveryCommand.Apply(original))
            assertEquals(reservation.releasedAmount != null, command(bytes).apply.reservation.hasReleasedAmount())
            assertEquals(SplitOrderDeliveryCommand.Apply(original), codec.decode(bytes).command)
        }
        assertEquals(304L, released.releasedAmount?.value)
        assertEquals(123_456L, released.feeRemainder.numerator)
    }

    @Test
    fun `peer initialization bounded development control and cumulative ack roundtrip`() {
        val input = dispatch()
        val commands =
            listOf(
                SplitOrderDeliveryCommand.DescribePeer,
                SplitOrderDeliveryCommand.InitializeRole(topology),
                SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Progress),
                SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(UserId("U1"), 0, 0)),
                SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(UserId("U1"), 0, 1)),
                SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Acknowledge(UserId("U1"), 0, 1, 0)),
                SplitOrderDeliveryCommand.Control(
                    BoundedTradingCommand.Funding(
                        DevelopmentBalanceCommandPayload(
                            OpeningBalance("P1", UserId("U1"), AssetId("USDT"), Amount(1_000)),
                            "L1",
                            Instant.ofEpochSecond(123, 456),
                        ),
                    ),
                ),
                SplitOrderDeliveryCommand.Control(
                    BoundedTradingCommand.Admit(
                        RequestToken(UserId("U1"), 0, 1, 1),
                        OrderIntent(context.market.marketId, 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(6)),
                    ),
                ),
                SplitOrderDeliveryCommand.RecordAck(FundsMatchingAppliedAck(input.stream, 0, input.context)),
                SplitOrderDeliveryCommand.RecordAck(FundsMatchingAppliedAck(input.stream, Long.MAX_VALUE, input.context)),
            )
        for (value in commands) assertEquals(SplitOrderDeliveryEnvelope(0, value), codec.decode(codec.encode(0, value)))
        val manifest = byteArrayOf(1, 2, 3)
        val initialize = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Initialize(manifest))
        val decoded = codec.decode(codec.encode(1, initialize)).command as SplitOrderDeliveryCommand.Control
        assertContentEquals(manifest, (decoded.command as BoundedTradingCommand.Initialize).manifest)
    }

    @Test
    fun `small replies preserve committed position ack gap control and uninitialized peer status`() {
        val input = dispatch()
        val replies =
            listOf(
                reply("DESCRIBED").copy(lifecycle = TradingLifecycle.UNINITIALIZED.name, appliedLogPosition = 0),
                reply("APPLIED").copy(ack = FundsMatchingAppliedAck(input.stream, 1, input.context)),
                reply("GAP").copy(expectedSequence = Long.MAX_VALUE),
                reply("ACKNOWLEDGED").copy(role = TradingServiceRole.FUNDS, lineage = source),
                reply("ACCEPTED").copy(control = BoundedTradingReply(91, "ACCEPTED", 8192, lifecycle = "RUNNING")),
            )
        for (value in replies) assertEquals(value, codec.decodeReply(codec.encodeReply(value)))
        assertTrue(codec.encodeReply(replies[1]).size < codec.encode(91, SplitOrderDeliveryCommand.Apply(input)).size)
    }

    @Test
    fun `frame has separate little endian schema and accepts only the explicit offset slice`() {
        val bytes = codec.encode(91, SplitOrderDeliveryCommand.Apply(dispatch()))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(bytes.size - 8, header.short.toInt() and 65535)
        assertEquals(1, header.short.toInt())
        assertEquals(88, header.short.toInt())
        assertEquals(2, header.short.toInt())
        val sliced = ByteArray(5) + bytes + ByteArray(11)
        assertEquals(codec.decode(bytes), codec.decode(sliced, 5, bytes.size))
        val reply = codec.encodeReply(reply("DESCRIBED"))
        assertEquals(
            2,
            ByteBuffer
                .wrap(reply)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort(2)
                .toInt(),
        )
        assertEquals(codec.decodeReply(reply), codec.decodeReply(ByteArray(7) + reply, 7))
        val bounded = BoundedTradingCodec().encode(91, BoundedTradingCommand.Progress)
        assertFailsWith<AdmissionMessageException> { codec.decode(bounded) }
        assertFailsWith<AdmissionMessageException> { BoundedTradingCodec().decode(bytes) }
        assertFailsWith<AdmissionMessageException> { codec.decode(reply) }
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(bytes) }
    }

    @Test
    fun `truncation concatenation incorrect headers lengths offsets and oversized frames fail before a command`() {
        val bytes = codec.encode(91, SplitOrderDeliveryCommand.Apply(dispatch()))
        for (length in listOf(0, 7, 8, bytes.size - 1)) {
            assertFailsWith<AdmissionMessageException> { codec.decode(bytes.copyOf(length)) }
        }
        for (index in listOf(0, 2, 4, 6)) {
            assertFailsWith<AdmissionMessageException> { codec.decode(bytes.copyOf().also { it[index] = 99 }) }
        }
        assertFailsWith<AdmissionMessageException> { codec.decode(bytes + bytes) }
        assertFailsWith<AdmissionMessageException> { codec.decode(bytes, -1, bytes.size) }
        assertFailsWith<AdmissionMessageException> { codec.decode(bytes, 1, bytes.size) }
        assertFailsWith<AdmissionMessageException> { codec.decode(bytes, Int.MAX_VALUE, Int.MAX_VALUE) }
        assertEquals(32_768, SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES)
        assertFailsWith<AdmissionMessageException> { codec.decode(ByteArray(32_769)) }
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(ByteArray(32_769)) }
    }

    @Test
    fun `missing command body correlation and every required nested dispatch field are rejected`() {
        val valid = command(codec.encode(91, SplitOrderDeliveryCommand.Apply(dispatch())))
        assertFailsWith<AdmissionMessageException> { codec.decode(frame(1, valid.toBuilder().clearCorrelationId().build())) }
        assertFailsWith<AdmissionMessageException> { codec.decode(frame(1, valid.toBuilder().clearBody().build())) }
        for ((path, incomplete) in missingRequired(valid.apply)) {
            val bytes =
                frame(
                    1,
                    valid
                        .toBuilder()
                        .setApply(
                            incomplete as com.exchange.core.api.trading.infrastructure.codec.generated.SplitDispatch,
                        ).build(),
                )
            assertFailsWith<AdmissionMessageException>(path) { codec.decode(bytes) }
        }
        val ack = command(codec.encode(1, SplitOrderDeliveryCommand.RecordAck(FundsMatchingAppliedAck(dispatch().stream, 1, context))))
        for ((path, incomplete) in missingRequired(ack.recordAck)) {
            val bytes =
                frame(
                    1,
                    ack
                        .toBuilder()
                        .setRecordAck(
                            incomplete as com.exchange.core.api.trading.infrastructure.codec.generated.SplitAppliedAck,
                        ).build(),
                )
            assertFailsWith<AdmissionMessageException>(path) { codec.decode(bytes) }
        }
    }

    @Test
    fun `required reply identity fields remain required even when their numeric value is zero`() {
        val valid = response(codec.encodeReply(reply("DESCRIBED")))
        for ((path, incomplete) in missingRequired(valid, setOf("ack", "expected_sequence", "control", "lifecycle"))) {
            assertFailsWith<AdmissionMessageException>(path) { codec.decodeReply(frame(2, incomplete)) }
        }
        assertEquals(
            0L,
            codec.decodeReply(codec.encodeReply(reply("DESCRIBED").copy(correlationId = 0, appliedLogPosition = 0))).correlationId,
        )
    }

    @Test
    fun `unknown fields and enum values are rejected at every typed nesting level`() {
        val valid = command(codec.encode(91, SplitOrderDeliveryCommand.Apply(dispatch())))
        for ((path, damaged) in unknownFields(valid)) {
            assertFailsWith<AdmissionMessageException>(path) { codec.decode(frame(1, damaged)) }
        }
        val unknownDirection =
            valid.apply.stream
                .toBuilder()
                .setDirectionValue(99)
                .build()
        val unknownSide =
            valid.apply.order
                .toBuilder()
                .setSideValue(99)
                .build()
        val unspecifiedSide =
            valid.apply.order
                .toBuilder()
                .setSide(WireSide.SIDE_UNSPECIFIED)
                .build()
        val unknownFee =
            valid.apply.context.feePolicy
                .toBuilder()
                .setFeeTierValue(99)
                .build()
        val unknownStatus =
            valid.apply.reservation
                .toBuilder()
                .setStatusValue(99)
                .build()
        val inputs =
            listOf(
                valid.apply
                    .toBuilder()
                    .setStream(unknownDirection)
                    .build(),
                valid.apply
                    .toBuilder()
                    .setOrder(unknownSide)
                    .build(),
                valid.apply
                    .toBuilder()
                    .setOrder(unspecifiedSide)
                    .build(),
                valid.apply
                    .toBuilder()
                    .setContext(
                        valid.apply.context
                            .toBuilder()
                            .setFeePolicy(unknownFee),
                    ).build(),
                valid.apply
                    .toBuilder()
                    .setReservation(unknownStatus)
                    .build(),
            )
        for (input in inputs) {
            assertFailsWith<AdmissionMessageException> {
                codec.decode(
                    frame(1, valid.toBuilder().setApply(input).build()),
                )
            }
        }
        val reply = response(codec.encodeReply(reply("DESCRIBED")))
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(frame(2, reply.toBuilder().setRoleValue(99).build())) }
        assertFailsWith<AdmissionMessageException> {
            codec.decodeReply(frame(2, reply.toBuilder().setRole(SplitRole.SPLIT_ROLE_UNSPECIFIED).build()))
        }
    }

    @Test
    fun `invalid domain constructor combinations and numeric ranges are not normalized`() {
        val valid = command(codec.encode(91, SplitOrderDeliveryCommand.Apply(dispatch())))
        val input = valid.apply
        val invalid =
            listOf(
                input.toBuilder().setFormatVersion(1),
                input.toBuilder().setDispatchSequence(0),
                input.toBuilder().setAdmissionSequence(-1),
                input.toBuilder().setMarketAdmissionSequence(0),
                input.toBuilder().setStream(input.stream.toBuilder().setOwnershipEpoch(0)),
                input.toBuilder().setStream(input.stream.toBuilder().setConfigurationVersion(-1)),
                input.toBuilder().setStream(input.stream.toBuilder().setMatchingLineage(input.stream.fundsLineage)),
                input.toBuilder().setOrder(input.order.toBuilder().setPrice(0)),
                input.toBuilder().setOrder(input.order.toBuilder().setQuantity(0)),
                input.toBuilder().setContext(input.context.toBuilder().setOrdinal(-1)),
                input.toBuilder().setContext(input.context.toBuilder().setBaseAssetScale(19)),
                input.toBuilder().setContext(input.context.toBuilder().setQuoteAssetId(input.context.baseAssetId)),
                input.toBuilder().setContext(
                    input.context.toBuilder().setFeePolicy(
                        input.context.feePolicy
                            .toBuilder()
                            .setMakerPpm(1_000_001),
                    ),
                ),
                input.toBuilder().setContext(
                    input.context.toBuilder().setFeePolicy(
                        input.context.feePolicy
                            .toBuilder()
                            .setScheduleVersion(0),
                    ),
                ),
                input.toBuilder().setReservation(input.reservation.toBuilder().setRemainingQuantity(7)),
                input.toBuilder().setReservation(input.reservation.toBuilder().setRemainingAmount(0)),
                input.toBuilder().setReservation(input.reservation.toBuilder().setRemainingFeeReserveAmount(10)),
                input.toBuilder().setReservation(input.reservation.toBuilder().setFeeRemainder(1_000_000)),
                input.toBuilder().setReservation(input.reservation.toBuilder().setReleasedAmount(1)),
            )
        for ((index, builder) in invalid.withIndex()) {
            assertFailsWith<AdmissionMessageException>(
                "invalid input $index",
            ) { codec.decode(frame(1, valid.toBuilder().setApply(builder).build())) }
        }
        assertFailsWith<IllegalArgumentException> { codec.encode(-1, SplitOrderDeliveryCommand.DescribePeer) }
        assertFailsWith<IllegalArgumentException> {
            codec.encode(1, SplitOrderDeliveryCommand.RecordAck(FundsMatchingAppliedAck(dispatch().stream, -1, context)))
        }
        val ack = command(codec.encode(1, SplitOrderDeliveryCommand.RecordAck(FundsMatchingAppliedAck(dispatch().stream, 1, context))))
        assertFailsWith<AdmissionMessageException> {
            codec.decode(frame(1, ack.toBuilder().setRecordAck(ack.recordAck.toBuilder().setThrough(-1)).build()))
        }
        val reply = response(codec.encodeReply(reply("GAP")))
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(frame(2, reply.toBuilder().setExpectedSequence(0).build())) }
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(frame(2, reply.toBuilder().setAppliedLogPosition(-1).build())) }
    }

    @Test
    fun `utf8 byte limit accepts exactly 256 bytes and refuses one more malformed text blank and nul`() {
        val input = dispatch()
        val maximum = "한".repeat(85) + "a"
        assertEquals(256, maximum.toByteArray(Charsets.UTF_8).size)
        val request = SplitOrderDeliveryCommand.Apply(input.copy(order = input.order.copy(userId = UserId(maximum))))
        assertEquals(request, codec.decode(codec.encode(1, request)).command)
        val wire = command(codec.encode(1, request))
        for (invalid in listOf(maximum + "b", " ", "bad\u0000id")) {
            val damaged =
                wire.apply.toBuilder().setOrder(
                    wire.apply.order
                        .toBuilder()
                        .setUserId(invalid),
                )
            assertFailsWith<AdmissionMessageException> { codec.decode(frame(1, wire.toBuilder().setApply(damaged).build())) }
        }
        for (invalid in listOf(maximum + "b", "bad\u0000id", "\uD800")) {
            val bad = input.copy(order = input.order.copy(userId = UserId(invalid)))
            assertFailsWith<IllegalArgumentException> { codec.encode(1, SplitOrderDeliveryCommand.Apply(bad)) }
        }
        val malformedUtf8 = codec.encode(1, SplitOrderDeliveryCommand.Apply(input))
        val userOffset =
            (8 until malformedUtf8.lastIndex).first {
                malformedUtf8[it] == 'U'.code.toByte() &&
                    malformedUtf8[it + 1] == '1'.code.toByte()
            }
        malformedUtf8[userOffset] = 0xFF.toByte()
        assertFailsWith<AdmissionMessageException> { codec.decode(malformedUtf8) }
        assertFailsWith<IllegalArgumentException> { codec.encode(1, SplitOrderDeliveryCommand.InitializeRole("A".repeat(64))) }
        assertFailsWith<IllegalArgumentException> { codec.encodeReply(reply("DESCRIBED").copy(topologySha256 = "a".repeat(63))) }
    }

    @Test
    fun `maximum normal identifiers long sequences and lineages produce a fragmented but bounded frame`() {
        val market = MarketDefinition(MarketId("m".repeat(256)), AssetId("b".repeat(256)), AssetId("q".repeat(256)), 0)
        val fixed = TradingMarketContext(market, policy.copy(scheduleVersion = Long.MAX_VALUE), Int.MAX_VALUE)
        val order =
            OrderAdmissionInput(
                market.marketId,
                TradingIdentifiers.order(ClusterLineage(Long.MIN_VALUE, Long.MAX_VALUE), Long.MAX_VALUE),
                UserId("한".repeat(85) + "a"),
                Side.BUY,
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(100),
                Quantity(6),
            )
        val reservation =
            OrderReservation.create(
                order.marketId,
                order.orderId,
                order.userId,
                order.side,
                order.price,
                order.quantity,
                calculator.calculate(market, order.side, order.price, order.quantity, fixed.feePolicySnapshot),
                fixed.feePolicySnapshot,
            )
        val input =
            FundsOrderDispatch(
                FundsDispatchStream(
                    ClusterLineage(Long.MIN_VALUE, Long.MAX_VALUE),
                    ClusterLineage(-1, -2),
                    market.marketId,
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                ),
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                order,
                reservation,
                fixed,
            )
        val bytes = codec.encode(Long.MAX_VALUE, SplitOrderDeliveryCommand.Apply(input))
        assertTrue(bytes.size > 1376, "maximum identifiers exceed one MTU payload")
        val maximumFrame = SplitOrderDeliveryCodec.maximumDispatchFrameBytes()
        assertTrue(bytes.size <= maximumFrame)
        assertTrue(maximumFrame <= SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES)
        assertEquals(SplitOrderDeliveryEnvelope(Long.MAX_VALUE, SplitOrderDeliveryCommand.Apply(input)), codec.decode(bytes))
    }

    @Test
    fun `integrated execution quarantine and mismatched nested correlations cannot enter split control`() {
        val excluded =
            listOf(
                BoundedTradingCommand.Execution(ExecuteAdmission(source, context.market.marketId, 1, 1)),
                BoundedTradingCommand.Quarantine(1, "STATE_MISMATCH"),
            )
        for (control in excluded) {
            assertFailsWith<IllegalArgumentException> { codec.encode(91, SplitOrderDeliveryCommand.Control(control)) }
            val bytes = BoundedTradingCodec().encode(91, control)
            val nested =
                com.exchange.core.api.trading.infrastructure.codec.generated.BoundedTradingCommand.parser().parseFrom(
                    bytes,
                    8,
                    bytes.size - 8,
                )
            val wire =
                SplitDeliveryCommand
                    .newBuilder()
                    .setCorrelationId(91)
                    .setControl(nested)
                    .build()
            assertFailsWith<AdmissionMessageException> { codec.decode(frame(1, wire)) }
        }
        val valid = command(codec.encode(91, SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Progress)))
        assertFailsWith<AdmissionMessageException> {
            codec.decode(frame(1, valid.toBuilder().setControl(valid.control.toBuilder().setCorrelationId(92)).build()))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.encodeReply(
                reply("ACCEPTED").copy(control = BoundedTradingReply(92, "ACCEPTED", 1)),
            )
        }
        assertFailsWith<IllegalArgumentException> { codec.encodeReply(reply("DESCRIBED").copy(role = TradingServiceRole.INTEGRATED)) }
    }

    @Test
    fun `v2 cancel preserves separate request identity target approval and first receipt`() {
        val original = dispatch()
        val token = RequestToken(original.order.userId, 1, 2, 9)
        val identity = TradingIdentifiers.cancel(source, context.ordinal, 4)
        val cancel = original.copy(dispatchSequence = 4, cancel = FundsCancelDispatch(identity, token))
        val intent = CancelIntent(context.market.marketId, original.order.orderId, 19)
        for (command in listOf(SplitOrderDeliveryCommand.Cancel(token, intent), SplitOrderDeliveryCommand.Apply(cancel))) {
            assertEquals(command, codec.decode(codec.encode(91, command)).command)
        }
        val first = CancelReceipt(CancelCode.ACCEPTED, identity, original.order.orderId, 4)
        for (cancelReply in listOf(
            CancelReply.Recorded(first, true),
            CancelReply.Recorded(first, false),
            CancelReply.Recorded(
                CancelReceipt(CancelCode.REJECTED, targetOrderId = original.order.orderId, reason = "CANCEL_PENDING"),
                true,
            ),
            CancelReply.Refused("REQUEST_CONTENT_CONFLICT"),
        )) {
            val expected = reply("ACCEPTED").copy(cancel = cancelReply)
            assertEquals(expected, codec.decodeReply(codec.encodeReply(expected)))
        }
        val wire = command(codec.encode(91, SplitOrderDeliveryCommand.Apply(cancel)))
        for ((path, damaged) in unknownFields(wire)) {
            assertFailsWith<AdmissionMessageException>(path) { codec.decode(frame(1, damaged)) }
        }
        for ((path, missing) in missingRequired(wire)) {
            if (path.endsWith(".cancellation")) continue
            assertFailsWith<AdmissionMessageException>(path) { codec.decode(frame(1, missing)) }
        }
        val legacy = codec.encode(91, SplitOrderDeliveryCommand.Apply(original))
        ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN).putShort(6, 1)
        assertFailsWith<AdmissionMessageException> { codec.decode(legacy) }
    }

    private fun dispatch(side: Side = Side.BUY): FundsOrderDispatch {
        val order =
            OrderAdmissionInput(
                context.market.marketId,
                TradingIdentifiers.order(source, 5),
                UserId("U1"),
                side,
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(100),
                Quantity(6),
            )
        val reservation =
            OrderReservation.create(
                order.marketId,
                order.orderId,
                order.userId,
                order.side,
                order.price,
                order.quantity,
                calculator.calculate(context.market, side, order.price, order.quantity, policy),
                policy,
            )
        return FundsOrderDispatch(FundsDispatchStream(source, matching, order.marketId, 13, 19), 3, 5, 3, order, reservation, context)
    }

    private fun reply(code: String): SplitOrderDeliveryReply =
        SplitOrderDeliveryReply(91, code, TradingServiceRole.MATCHING, matching, topology, 8192, lifecycle = TradingLifecycle.RUNNING.name)

    private fun command(bytes: ByteArray): SplitDeliveryCommand = SplitDeliveryCommand.parser().parseFrom(bytes, 8, bytes.size - 8)

    private fun response(bytes: ByteArray): SplitDeliveryReply = SplitDeliveryReply.parser().parseFrom(bytes, 8, bytes.size - 8)

    private fun frame(
        kind: Int,
        message: Message,
    ): ByteArray {
        val body = message.toByteArray()
        val bytes = ByteArray(8 + body.size)
        ByteBuffer
            .wrap(
                bytes,
            ).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(body.size.toShort())
            .putShort(kind.toShort())
            .putShort(88)
            .putShort(2)
        body.copyInto(bytes, 8)
        return bytes
    }

    private fun missingRequired(
        message: Message,
        optional: Set<String> = emptySet(),
    ): List<Pair<String, Message>> {
        val result = mutableListOf<Pair<String, Message>>()
        for ((field, value) in message.allFields) {
            if (field.name !in optional && field.name != "released_amount") {
                result += "${message.descriptorForType.name}.${field.name}" to message.toBuilder().clearField(field).build()
            }
            if (value is Message) {
                for ((path, nested) in missingRequired(value)) result += path to message.toBuilder().setField(field, nested).build()
            }
        }
        return result
    }

    private fun unknownFields(message: Message): List<Pair<String, Message>> {
        val unknown =
            UnknownFieldSet
                .newBuilder()
                .addField(
                    127,
                    UnknownFieldSet.Field
                        .newBuilder()
                        .addVarint(1)
                        .build(),
                ).build()
        val result = mutableListOf(message.descriptorForType.name to message.toBuilder().setUnknownFields(unknown).build())
        for ((field, value) in message.allFields) {
            if (value is Message) {
                for ((path, nested) in unknownFields(value)) result += path to message.toBuilder().setField(field, nested).build()
            }
        }
        return result
    }
}
