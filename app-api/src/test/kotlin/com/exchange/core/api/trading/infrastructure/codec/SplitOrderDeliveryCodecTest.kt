package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDirection
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitInputKind
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
    fun `새 계약은 신규와 취소의 공통 순서와 최초 대상 원본을 바이트로 보존한다`() {
        val cancellationCodec = SplitOrderDeliveryCodec(inputContractVersion = 2)
        val original = dispatch().copy(inputContractVersion = 2)
        val cancellation = cancellation(original)
        val following = original.copy(dispatchSequence = 5, marketAdmissionSequence = 4, admissionSequence = 6)
        for (input in listOf(original, cancellation, following)) {
            val bytes = cancellationCodec.encode(91, SplitOrderDeliveryCommand.Apply(input))
            assertEquals(
                2,
                ByteBuffer
                    .wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(6)
                    .toInt(),
            )
            assertEquals(
                if (input.isCancellation) SplitInputKind.SPLIT_CANCEL else SplitInputKind.SPLIT_NEW,
                command(bytes).apply.inputKind,
            )
            assertEquals(SplitOrderDeliveryEnvelope(91, SplitOrderDeliveryCommand.Apply(input)), cancellationCodec.decode(bytes))
        }
        assertEquals(3L, cancellation.cancel?.targetInputSequence)
        assertEquals(3L, cancellation.marketAdmissionSequence)
        assertEquals(4L, cancellation.dispatchSequence)
        assertEquals(original.order, cancellation.order)
        assertEquals(original.reservation, cancellation.reservation)
    }

    @Test
    fun `기존 계약은 새 원본을 조용히 신규 주문으로 직렬화하지 않는다`() {
        val original = dispatch().copy(inputContractVersion = 2)
        for (input in listOf(original, cancellation(original))) {
            assertFailsWith<IllegalArgumentException> { codec.encode(91, SplitOrderDeliveryCommand.Apply(input)) }
        }
    }

    @Test
    fun `기존 peer 조회 프레임 바이트는 새 계약을 추가해도 그대로다`() {
        assertContentEquals(
            byteArrayOf(4, 0, 1, 0, 88, 0, 1, 0, 8, 0, 80, 1),
            codec.encode(0, SplitOrderDeliveryCommand.DescribePeer),
        )
    }

    @Test
    fun `새 계약의 취소 접수는 요청 토큰 대상과 최초 응답을 운반한다`() {
        val cancellationCodec = SplitOrderDeliveryCodec(inputContractVersion = 2)
        val input = cancellation(dispatch().copy(inputContractVersion = 2))
        val cancel = requireNotNull(input.cancel)
        val request =
            SplitOrderDeliveryCommand.Cancel(
                cancel.requestToken,
                CancelIntent(input.stream.marketId, input.order.orderId, input.stream.configurationVersion),
            )
        assertEquals(SplitOrderDeliveryEnvelope(91, request), cancellationCodec.decode(cancellationCodec.encode(91, request)))
        val accepted = CancelReceipt(CancelCode.ACCEPTED, cancel.cancelId, input.order.orderId, input.dispatchSequence)
        val rejected = CancelReceipt(CancelCode.REJECTED, targetOrderId = input.order.orderId, reason = "CANCEL_PENDING")
        for (receipt in listOf(accepted, rejected)) {
            val response = reply(receipt.code.name).copy(cancellation = receipt)
            assertEquals(response, cancellationCodec.decodeReply(cancellationCodec.encodeReply(response)))
        }
        assertFailsWith<IllegalArgumentException> { codec.encode(91, request) }
        assertFailsWith<IllegalArgumentException> { codec.encodeReply(reply("ACCEPTED").copy(cancellation = accepted)) }
    }

    @Test
    fun `계약 버전이 다른 명령과 응답은 헤더를 바꾸어도 섞을 수 없다`() {
        val v2 = SplitOrderDeliveryCodec(2)
        val input = cancellation(dispatch().copy(inputContractVersion = 2))
        val bytes = v2.encode(91, SplitOrderDeliveryCommand.Apply(input))
        val legacy = codec.encode(91, SplitOrderDeliveryCommand.Apply(dispatch()))
        assertFailsWith<AdmissionMessageException> { codec.decode(bytes) }
        assertFailsWith<AdmissionMessageException> { v2.decode(legacy) }
        assertFailsWith<AdmissionMessageException> { codec.decode(frame(1, command(bytes), version = 1)) }
        assertFailsWith<AdmissionMessageException> { v2.decode(frame(1, command(legacy), version = 2)) }
        val reply = reply("ACCEPTED")
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(v2.encodeReply(reply)) }
        assertFailsWith<AdmissionMessageException> { v2.decodeReply(codec.encodeReply(reply)) }
        assertFailsWith<IllegalArgumentException> { v2.encode(91, SplitOrderDeliveryCommand.Apply(dispatch())) }
        assertFailsWith<IllegalArgumentException> { SplitOrderDeliveryCodec(0) }
        assertFailsWith<IllegalArgumentException> { SplitOrderDeliveryCodec(3) }
    }

    @Test
    fun `신규 전용 reader는 기본 enum 값으로 숨긴 새 필드와 새 접수 응답도 거절한다`() {
        val legacy = command(codec.encode(91, SplitOrderDeliveryCommand.Apply(dispatch())))
        val applyBody = legacy.apply.toByteArray() + byteArrayOf(72, 0)
        val body = byteArrayOf(8, 91, 106) + varint(applyBody.size.toLong()) + applyBody
        assertFailsWith<AdmissionMessageException> { codec.decode(frame(1, body, 1)) }
        val receipt = CancelReceipt(CancelCode.REJECTED, reason = "CANCEL_PENDING")
        val response = response(SplitOrderDeliveryCodec(2).encodeReply(reply("REJECTED").copy(cancellation = receipt)))
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(frame(2, response, 1)) }
    }

    @Test
    fun `취소 종류 식별자 요청자 대상 순서 변조와 필수 토큰 누락은 거절한다`() {
        val v2 = SplitOrderDeliveryCodec(2)
        val valid = command(v2.encode(91, SplitOrderDeliveryCommand.Apply(cancellation(dispatch().copy(inputContractVersion = 2)))))
        val input = valid.apply
        val cancel = input.cancellation
        val invalid =
            listOf(
                input.toBuilder().clearInputKind(),
                input.toBuilder().setInputKindValue(99),
                input.toBuilder().setInputKind(SplitInputKind.SPLIT_NEW),
                input.toBuilder().clearCancellation(),
                input.toBuilder().setCancellation(cancel.toBuilder().setCancelId(TradingIdentifiers.cancel(source, 8))),
                input.toBuilder().setCancellation(cancel.toBuilder().setCancelSequence(0)),
                input.toBuilder().setCancellation(cancel.toBuilder().setTargetInputSequence(0)),
                input.toBuilder().setCancellation(cancel.toBuilder().setTargetInputSequence(input.dispatchSequence)),
                input.toBuilder().setCancellation(cancel.toBuilder().setRequestToken(cancel.requestToken.toBuilder().setUserId("other"))),
                input.toBuilder().setCancellation(cancel.toBuilder().setRequestToken(cancel.requestToken.toBuilder().setChannel(-1))),
                input.toBuilder().setCancellation(cancel.toBuilder().setRequestToken(cancel.requestToken.toBuilder().setEpoch(0))),
                input.toBuilder().setCancellation(cancel.toBuilder().setRequestToken(cancel.requestToken.toBuilder().setSequence(0))),
            )
        for ((index, damaged) in invalid.withIndex()) {
            assertFailsWith<AdmissionMessageException>("변조 $index") { v2.decode(frame(1, valid.toBuilder().setApply(damaged).build(), 2)) }
        }
        for ((path, incomplete) in missingRequired(cancel)) {
            val damaged =
                input.toBuilder().setCancellation(
                    incomplete as com.exchange.core.api.trading.infrastructure.codec.generated.SplitCancellation,
                )
            assertFailsWith<AdmissionMessageException>(path) { v2.decode(frame(1, valid.toBuilder().setApply(damaged).build(), 2)) }
        }
        for ((path, damaged) in unknownFields(valid)) {
            assertFailsWith<AdmissionMessageException>(path) { v2.decode(frame(1, damaged, 2)) }
        }
    }

    @Test
    fun `취소 접수의 잘린 int32와 중복 필드는 parser의 정상 값으로 바뀌지 않는다`() {
        val v2 = SplitOrderDeliveryCodec(2)

        fun request(channel: ByteArray): ByteArray {
            val token = byteArrayOf(10, 2, 85, 49, 16) + channel + byteArrayOf(24, 1, 32, 1)
            val intent = byteArrayOf(10, 1, 109, 18, 1, 111, 24, 1)
            val cancel = byteArrayOf(10) + varint(token.size.toLong()) + token + byteArrayOf(18) + varint(intent.size.toLong()) + intent
            return byteArrayOf(8, 91, 122) + varint(cancel.size.toLong()) + cancel
        }
        val maximum = request(varint(Int.MAX_VALUE.toLong()))
        val decoded = v2.decode(frame(1, maximum, 2)).command as SplitOrderDeliveryCommand.Cancel
        assertEquals(Int.MAX_VALUE, decoded.token.channel)
        for (overflow in listOf(Int.MAX_VALUE.toLong() + 1, 1L shl 32, (1L shl 32) + 1)) {
            assertFailsWith<AdmissionMessageException> { v2.decode(frame(1, request(varint(overflow)), 2)) }
        }
        val valid = command(v2.encode(91, SplitOrderDeliveryCommand.Apply(dispatch().copy(inputContractVersion = 2))))
        val kindOverflow =
            valid.apply
                .toBuilder()
                .clearInputKind()
                .build()
                .toByteArray() + byteArrayOf(72) + varint((1L shl 32) + 1)
        val body = byteArrayOf(8, 91, 106) + varint(kindOverflow.size.toLong()) + kindOverflow
        assertFailsWith<AdmissionMessageException> { v2.decode(frame(1, body, 2)) }
        assertFailsWith<AdmissionMessageException> { v2.decode(frame(1, byteArrayOf(8, 91, 80, 2), 2)) }
        assertFailsWith<AdmissionMessageException> { v2.decode(frame(1, maximum + byteArrayOf(8, 91), 2)) }
    }

    @Test
    fun `취소 최초 응답은 성공과 거절의 필드 조합을 바꾸거나 누락할 수 없다`() {
        val v2 = SplitOrderDeliveryCodec(2)
        val accepted = CancelReceipt(CancelCode.ACCEPTED, TradingIdentifiers.cancel(source, 7), dispatch().order.orderId, 4)
        val valid = response(v2.encodeReply(reply("ACCEPTED").copy(cancellation = accepted)))
        val receipt = valid.cancellation
        val invalid =
            listOf(
                receipt.toBuilder().clearCode(),
                receipt.toBuilder().setCodeValue(99),
                receipt.toBuilder().clearCancelId(),
                receipt.toBuilder().clearTargetOrderId(),
                receipt.toBuilder().clearInputSequence(),
                receipt.toBuilder().setInputSequence(0),
                receipt.toBuilder().setReason("CANCEL_PENDING"),
                receipt.toBuilder().setCode(
                    com.exchange.core.api.trading.infrastructure.codec.generated.SplitCancelCode.SPLIT_CANCEL_REJECTED,
                ),
            )
        for ((index, damaged) in invalid.withIndex()) {
            assertFailsWith<AdmissionMessageException>("응답 변조 $index") {
                v2.decodeReply(frame(2, valid.toBuilder().setCancellation(damaged).build(), 2))
            }
        }
        for ((path, damaged) in unknownFields(valid)) {
            assertFailsWith<AdmissionMessageException>(path) { v2.decodeReply(frame(2, damaged, 2)) }
        }
    }

    @Test
    fun `typed dispatch preserves both lineages original order full reservation and fixed context`() {
        for (side in Side.entries) {
            val input = dispatch(side)
            val bytes = codec.encode(91, SplitOrderDeliveryCommand.Apply(input))
            val wire = command(bytes)
            assertEquals(1, wire.apply.formatVersion)
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
        assertEquals(1, header.short.toInt())
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
                input.toBuilder().setFormatVersion(2),
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

        val v2 = SplitOrderDeliveryCodec(2)
        val original =
            input.copy(
                dispatchSequence = Long.MAX_VALUE - 1,
                marketAdmissionSequence = Long.MAX_VALUE - 2,
                inputContractVersion = 2,
            )
        val cancel =
            cancellation(original).copy(
                cancel =
                    FundsCancelDispatch(
                        TradingIdentifiers.cancel(original.stream.fundsLineage, Long.MAX_VALUE),
                        RequestToken(original.order.userId, Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                        Long.MAX_VALUE,
                        original.dispatchSequence,
                    ),
            )
        val cancelledBytes = v2.encode(Long.MAX_VALUE, SplitOrderDeliveryCommand.Apply(cancel))
        val cancelMaximumFrame = SplitOrderDeliveryCodec.maximumDispatchFrameBytes(2)
        assertTrue(cancelledBytes.size > bytes.size)
        assertTrue(cancelledBytes.size <= cancelMaximumFrame)
        assertTrue(cancelMaximumFrame <= SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES)
        assertEquals(SplitOrderDeliveryEnvelope(Long.MAX_VALUE, SplitOrderDeliveryCommand.Apply(cancel)), v2.decode(cancelledBytes))
        assertEquals(v2.decode(cancelledBytes), v2.decode(ByteArray(9) + cancelledBytes + ByteArray(7), 9, cancelledBytes.size))
        assertFailsWith<AdmissionMessageException> { v2.decode(cancelledBytes.copyOf(cancelledBytes.size - 1)) }
        assertFailsWith<AdmissionMessageException> { v2.decode(cancelledBytes + cancelledBytes) }
        assertFailsWith<AdmissionMessageException> { v2.decode(ByteArray(SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES + 1)) }
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

    private fun cancellation(original: FundsOrderDispatch): FundsOrderDispatch =
        original.copy(
            dispatchSequence = original.dispatchSequence + 1,
            cancel =
                FundsCancelDispatch(
                    TradingIdentifiers.cancel(original.stream.fundsLineage, 7),
                    RequestToken(original.order.userId, 2, 11, 13),
                    7,
                    original.dispatchSequence,
                ),
        )

    private fun command(bytes: ByteArray): SplitDeliveryCommand = SplitDeliveryCommand.parser().parseFrom(bytes, 8, bytes.size - 8)

    private fun response(bytes: ByteArray): SplitDeliveryReply = SplitDeliveryReply.parser().parseFrom(bytes, 8, bytes.size - 8)

    private fun frame(
        kind: Int,
        message: Message,
        version: Int = 1,
    ): ByteArray = frame(kind, message.toByteArray(), version)

    private fun frame(
        kind: Int,
        body: ByteArray,
        version: Int,
    ): ByteArray {
        val bytes = ByteArray(8 + body.size)
        ByteBuffer
            .wrap(
                bytes,
            ).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(body.size.toShort())
            .putShort(kind.toShort())
            .putShort(88)
            .putShort(version.toShort())
        body.copyInto(bytes, 8)
        return bytes
    }

    private fun varint(value: Long): ByteArray {
        require(value >= 0)
        val bytes = mutableListOf<Byte>()
        var remainder = value
        while (remainder > 127) {
            bytes += ((remainder and 127) or 128).toByte()
            remainder = remainder ushr 7
        }
        bytes += remainder.toByte()
        return bytes.toByteArray()
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
