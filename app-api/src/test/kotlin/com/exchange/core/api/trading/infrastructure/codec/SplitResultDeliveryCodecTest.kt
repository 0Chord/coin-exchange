package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMatchingEffect
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMatchingEvent
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMatchingOutcome
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitOrderDisposition
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitResultCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitResultReply
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitRole
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
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsCancelDispatch
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingIntakeConfiguration
import com.exchange.core.trading.MatchingIntakeStatus
import com.exchange.core.trading.MatchingNotExecutedReason
import com.exchange.core.trading.MatchingOrderDisposition
import com.exchange.core.trading.MatchingOrderEffect
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import com.google.protobuf.Message
import com.google.protobuf.UnknownFieldSet
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SplitResultDeliveryCodecTest {
    private val codec = SplitResultDeliveryCodec()
    private val cancellationCodec = SplitResultDeliveryCodec(inputContractVersion = 2)
    private val funds = ClusterLineage(1, 2)
    private val matching = ClusterLineage(3, 4)
    private val policy =
        TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.VVIP, 17, MakerTakerFeeRates(FeeRate(7_000), FeeRate(15_000)))
    private val context = TradingMarketContext(MarketDefinition(MarketId("X-Q"), AssetId("X"), AssetId("Q"), 0), policy, 7)
    private val stream = MatchingResultStream(matching, funds, context.market.marketId, 13, 19)
    private val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))

    @Test
    fun `명시한 취소 결과는 취소 원문 대상 최초 원본과 빈 효과 거절을 보존한다`() {
        for (rejected in listOf(false, true)) {
            val value = cancellationResult(rejected)
            val bytes = cancellationCodec.encode(91, SplitResultDeliveryCommand.Apply(value))
            assertEquals(
                2,
                ByteBuffer
                    .wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(2)
                    .toInt(),
            )
            val restored = (cancellationCodec.decode(bytes).command as SplitResultDeliveryCommand.Apply).result
            assertEquals(value, restored)
            assertEquals(value.input.cancel, restored.input.cancel)
            assertEquals(if (rejected) emptyList() else listOf(input(1).copy(inputContractVersion = 2)), restored.effects.map { it.origin })
            assertContentEquals(bytes, cancellationCodec.encode(91, SplitResultDeliveryCommand.Apply(restored)))
        }
    }

    @Test
    fun `v1 고정 최소 bytes와 enum 번호를 유지하고 두 profile의 원문과 확인을 섞지 않는다`() {
        val legacy = byteArrayOf(89, 0, 1, 0, 1, 0, 0, 0, 4, 0, 0, 0, 8, 1, 80, 1)
        assertContentEquals(legacy, codec.encode(1, SplitResultDeliveryCommand.DescribePeer))
        assertContentEquals(legacy.copyOf().also { it[2] = 2 }, cancellationCodec.encode(1, SplitResultDeliveryCommand.DescribePeer))
        assertEquals(1, SplitMatchingOutcome.SPLIT_MATCHED.number)
        assertEquals(2, SplitMatchingOutcome.SPLIT_NOT_EXECUTED.number)
        assertEquals(3, SplitOrderDisposition.SPLIT_ORDER_NOT_EXECUTED.number)
        assertFailsWith<IllegalArgumentException> { SplitResultDeliveryCodec(0) }
        assertFailsWith<IllegalArgumentException> { SplitResultDeliveryCodec(3) }
        val ack = MatchingFundsSettledAck(stream, Long.MAX_VALUE, context)
        val commands =
            listOf(
                SplitResultDeliveryCommand.DescribePeer,
                SplitResultDeliveryCommand.Progress(stream, context),
                SplitResultDeliveryCommand.RecordAck(ack),
            )
        for (command in commands) {
            val old = codec.encode(91, command)
            val current = cancellationCodec.encode(91, command)
            assertEquals(SplitResultDeliveryEnvelope(91, command), cancellationCodec.decode(current))
            assertFailsWith<AdmissionMessageException> { codec.decode(current) }
            assertFailsWith<AdmissionMessageException> { cancellationCodec.decode(old) }
        }
        val reply = reply().copy(ack = ack, expectedSequence = Long.MAX_VALUE, resultIssued = Long.MAX_VALUE)
        val oldReply = codec.encodeReply(reply)
        val currentReply = cancellationCodec.encodeReply(reply)
        assertEquals(reply, cancellationCodec.decodeReply(currentReply))
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(currentReply) }
        assertFailsWith<AdmissionMessageException> { cancellationCodec.decodeReply(oldReply) }
    }

    @Test
    fun `v2 신규와 취소 전체 결과는 같은 profile의 입력과 효과 원본만 운반한다`() {
        val old = result(2)
        val current = versionTwo(old)
        assertEquals(
            current,
            (
                cancellationCodec.decode(cancellationCodec.encode(1, SplitResultDeliveryCommand.Apply(current))).command as
                    SplitResultDeliveryCommand.Apply
            ).result,
        )
        assertFailsWith<IllegalArgumentException> { codec.encode(1, SplitResultDeliveryCommand.Apply(current)) }
        assertFailsWith<IllegalArgumentException> { codec.encode(1, SplitResultDeliveryCommand.Apply(cancellationResult())) }
        assertFailsWith<IllegalArgumentException> { cancellationCodec.encode(1, SplitResultDeliveryCommand.Apply(old)) }
        val wire = command(cancellationCodec.encode(1, SplitResultDeliveryCommand.Apply(current)))
        for (bad in listOf(
            wire.apply.toBuilder().setFormatVersion(1),
            wire.apply.toBuilder().setInput(
                wire.apply.input
                    .toBuilder()
                    .setFormatVersion(1),
            ),
            wire.apply.toBuilder().setEffects(
                0,
                wire.apply
                    .getEffects(0)
                    .toBuilder()
                    .setOrigin(
                        wire.apply
                            .getEffects(0)
                            .origin
                            .toBuilder()
                            .setFormatVersion(1),
                    ),
            ),
        )) {
            assertFailsWith<AdmissionMessageException> { cancellationCodec.decode(frame(1, wire.toBuilder().setApply(bad).build(), 2)) }
        }
    }

    @Test
    fun `취소 사건과 최초 원본의 누락 중첩 unknown 변조를 wire 경계에서 거절한다`() {
        for (rejected in listOf(false, true)) {
            val wire = command(cancellationCodec.encode(1, SplitResultDeliveryCommand.Apply(cancellationResult(rejected))))
            for (unknown in listOf(false, true)) {
                for ((path, invalid) in mutations(wire, unknown)) {
                    assertFailsWith<AdmissionMessageException>(path) { cancellationCodec.decode(frame(1, invalid, 2)) }
                }
            }
            val value = wire.apply
            val invalid =
                listOf(
                    value.toBuilder().setOutcome(SplitMatchingOutcome.SPLIT_MATCHED),
                    value.toBuilder().setEngineSequenceBefore(Long.MAX_VALUE).setEngineSequenceAfter(Long.MAX_VALUE),
                    value.toBuilder().setEventCount(2),
                    value.toBuilder().clearEvents(),
                    value.toBuilder().setInput(value.input.toBuilder().clearCancellation()),
                )
            for (bad in invalid) {
                assertFailsWith<AdmissionMessageException> { cancellationCodec.decode(frame(1, wire.toBuilder().setApply(bad).build(), 2)) }
            }
        }
        val success = command(cancellationCodec.encode(1, SplitResultDeliveryCommand.Apply(cancellationResult())))
        val value = success.apply
        val overflowOutcome =
            UnknownFieldSet
                .newBuilder()
                .addField(
                    5,
                    UnknownFieldSet.Field
                        .newBuilder()
                        .addVarint((1L shl 32) + SplitMatchingOutcome.SPLIT_CANCELLED.number)
                        .build(),
                ).build()
        val overflow = value.toBuilder().clearOutcome().setUnknownFields(overflowOutcome)
        val overflowError =
            assertFailsWith<AdmissionMessageException> {
                cancellationCodec.decode(frame(1, success.toBuilder().setApply(overflow).build(), 2))
            }
        assertEquals("result enum exceeds int32", overflowError.cause?.message)
        for (bad in listOf(
            value.toBuilder().clearEffects(),
            value.toBuilder().setEffects(0, value.getEffects(0).toBuilder().setAfterQuantity(1)),
            value.toBuilder().setEffects(0, value.getEffects(0).toBuilder().setDisposition(SplitOrderDisposition.SPLIT_TERMINAL)),
            value.toBuilder().setEvents(
                0,
                value.getEvents(0).toBuilder().setCancelled(
                    value
                        .getEvents(0)
                        .cancelled
                        .toBuilder()
                        .setRemainingQuantity(1),
                ),
            ),
            value.toBuilder().setEvents(
                0,
                value.getEvents(0).toBuilder().setCancelled(
                    value
                        .getEvents(0)
                        .cancelled
                        .toBuilder()
                        .setOrderId("other"),
                ),
            ),
        )) {
            assertFailsWith<AdmissionMessageException> { cancellationCodec.decode(frame(1, success.toBuilder().setApply(bad).build(), 2)) }
        }
        val exactEngineEnd =
            value
                .toBuilder()
                .setEngineSequenceBefore(Long.MAX_VALUE - 1)
                .setEngineSequenceAfter(Long.MAX_VALUE)
                .setEvents(
                    0,
                    value.getEvents(0).toBuilder().setCancelled(
                        value
                            .getEvents(0)
                            .cancelled
                            .toBuilder()
                            .setEngineSequence(Long.MAX_VALUE),
                    ),
                )
        val exactBytes = frame(1, success.toBuilder().setApply(exactEngineEnd).build(), 2)
        val exact = (cancellationCodec.decode(exactBytes).command as SplitResultDeliveryCommand.Apply).result
        assertEquals(Long.MAX_VALUE, exact.engineSequenceAfter)
        assertContentEquals(exactBytes, cancellationCodec.encode(1, SplitResultDeliveryCommand.Apply(exact)))
        val rejection = command(cancellationCodec.encode(1, SplitResultDeliveryCommand.Apply(cancellationResult(true))))
        val event = rejection.apply.getEvents(0)
        val missingReason = event.toBuilder().setCancelRejected(event.cancelRejected.toBuilder().clearReason())
        assertFailsWith<AdmissionMessageException> {
            cancellationCodec.decode(
                frame(1, rejection.toBuilder().setApply(rejection.apply.toBuilder().setEvents(0, missingReason)).build(), 2),
            )
        }
        assertFailsWith<AdmissionMessageException> {
            cancellationCodec.decode(
                frame(
                    1,
                    rejection
                        .toBuilder()
                        .setApply(
                            rejection.apply.toBuilder().setEvents(
                                0,
                                event.toBuilder().setCancelRejected(event.cancelRejected.toBuilder().setReason("order owner mismatch")),
                            ),
                        ).build(),
                    2,
                ),
            )
        }
    }

    @Test
    fun `v2 잘림 길이 예약 비트와 포화 사건 효과를 생성 parser 전에 거절한다`() {
        val bytes = cancellationCodec.encode(1, SplitResultDeliveryCommand.Apply(cancellationResult()))
        for (length in listOf(0, 11, bytes.size - 1)) {
            assertFailsWith<AdmissionMessageException> { cancellationCodec.decode(bytes.copyOf(length)) }
        }
        for (index in listOf(0, 2, 4, 6, 8)) {
            val damaged = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFailsWith<AdmissionMessageException> { cancellationCodec.decode(damaged) }
        }
        assertFailsWith<AdmissionMessageException> { cancellationCodec.decode(bytes + 0) }
        val wire = command(bytes)
        val incompleteEffect = SplitMatchingEffect.newBuilder().setBeforeQuantity(1).build()
        for (bad in listOf(
            wire.apply.toBuilder().clearEvents().addAllEvents(
                List(SplitResultDeliveryCodec.MAX_RESULT_EVENTS + 1) { wire.apply.getEvents(0) },
            ),
            wire.apply
                .toBuilder()
                .clearEffects()
                .addAllEffects(List(SplitResultDeliveryCodec.MAX_RESULT_EVENTS + 2) { incompleteEffect }),
        )) {
            val overflowing = frame(1, wire.toBuilder().setApply(bad).build(), 2)
            assertTrue(overflowing.size <= SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
            val error = assertFailsWith<AdmissionMessageException> { cancellationCodec.decode(overflowing) }
            assertEquals("result repeated field exceeds bound", error.cause?.message)
        }
        val padded = ByteArray(bytes.size + 8).also { bytes.copyInto(it, 4) }
        assertEquals(cancellationCodec.decode(bytes), cancellationCodec.decode(padded, 4, bytes.size))
    }

    @Test
    fun `v2 descriptor 상계가 신규 취소성공과 빈 효과 거절의 실제 charge를 수용한다`() {
        SplitResultDeliveryCodec.verifySizingContract(inputContractVersion = 2)
        val bound = SplitResultDeliveryCodec.maximumResultFrameBytes(1_048_576, 1_024, inputContractVersion = 2)
        assertEquals(1_377_824L, bound)
        assertTrue(bound < SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
        assertEquals(bound, SplitResultDeliveryCodec.upperBound(1_048_576, 1_024, inputContractVersion = 2))
        assertFailsWith<ArithmeticException> { SplitResultDeliveryCodec.maximumResultFrameBytes(Long.MAX_VALUE, 1_024, 2) }
        assertFailsWith<IllegalArgumentException> { SplitResultDeliveryCodec.maximumResultFrameBytes(1_048_576, 1_024, 3) }
        for (value in listOf(versionTwo(result(50)), cancellationResult(), cancellationResult(true))) {
            assertTrue(
                cancellationCodec.encode(1, SplitResultDeliveryCommand.Apply(value)).size <=
                    SplitResultDeliveryCodec.maximumResultFrameBytes(value.canonicalBytes, maxOf(1, value.eventCount), 2),
            )
        }
    }

    @Test
    fun `v2 정확한 최대 frame을 수용하고 한바이트 초과의 송수신을 거절한다`() {
        val maximum = SplitResultDeliveryBoundaryFixture.frame(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES, inputContractVersion = 2)
        assertEquals(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES, maximum.bytes.size)
        val restored = cancellationCodec.decode(maximum.bytes)
        assertEquals(maximum.result, (restored.command as SplitResultDeliveryCommand.Apply).result)
        assertContentEquals(
            maximum.bytes,
            cancellationCodec.encode(maximum.correlationId, SplitResultDeliveryCommand.Apply(maximum.result)),
        )
        val oversized = SplitResultDeliveryBoundaryFixture.frame(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + 1, inputContractVersion = 2)
        assertEquals(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + 1, oversized.bytes.size)
        assertFailsWith<AdmissionMessageException> { cancellationCodec.decode(oversized.bytes) }
        assertFailsWith<IllegalArgumentException> {
            cancellationCodec.encode(oversized.correlationId, SplitResultDeliveryCommand.Apply(oversized.result))
        }
    }

    @Test
    fun `전체 체결 대기 사건과 각 maker 최초 원본을 보존한다`() {
        val value = result(1)
        val bytes = codec.encode(91, SplitResultDeliveryCommand.Apply(value))
        assertEquals(
            89,
            ByteBuffer
                .wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt(),
        )
        assertEquals(bytes.size - 12, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(8))
        val restored = (codec.decode(bytes).command as SplitResultDeliveryCommand.Apply).result
        assertEquals(value, restored)
        assertEquals(value.resultId, restored.resultId)
        assertNotEquals(restored.input, restored.effects[1].origin)
        assertEquals(listOf(Quantity(2), Quantity(1)), restored.effects.map { it.beforeQuantity })
        assertContentEquals(bytes, codec.encode(91, SplitResultDeliveryCommand.Apply(restored)))
        assertTrue(
            command(bytes)
                .apply.descriptorForType.fields
                .none { it.name == "result_id" },
        )
    }

    @Test
    fun `조회 확인 최종 거절과 typed 응답을 별도 명령으로 왕복한다`() {
        val input = input(3)
        val rejected =
            MatchingResultEnvelope(
                stream,
                3,
                input,
                MatchingOutcome.NOT_EXECUTED,
                MatchingNotExecutedReason.ACTIVE_ORDER_LIMIT,
                5,
                5,
                emptyList(),
                listOf(MatchingOrderEffect(input, input.order.quantity, input.order.quantity, MatchingOrderDisposition.NOT_EXECUTED)),
            )
        val ack = MatchingFundsSettledAck(stream, Long.MAX_VALUE, context)
        for (command in listOf(
            SplitResultDeliveryCommand.DescribePeer,
            SplitResultDeliveryCommand.Apply(rejected),
            SplitResultDeliveryCommand.Progress(stream, context),
            SplitResultDeliveryCommand.RecordAck(ack),
        )) {
            assertEquals(SplitResultDeliveryEnvelope(91, command), codec.decode(codec.encode(91, command)))
        }
        val reply = reply().copy(ack = ack, expectedSequence = Long.MAX_VALUE, resultIssued = Long.MAX_VALUE)
        assertEquals(reply, codec.decodeReply(codec.encodeReply(reply)))
        val absent = reply.copy(ack = null, expectedSequence = null, resultIssued = null, lifecycle = null)
        assertEquals(absent, codec.decodeReply(codec.encodeReply(absent)))
    }

    @Test
    fun `최대 UTF8 lineage 순번과 파생 ID256초과를 수용한다`() {
        val longFunds = ClusterLineage(Long.MIN_VALUE, Long.MAX_VALUE)
        val longMatching = ClusterLineage(-1, -2)
        val maximum = "한".repeat(85) + "a"
        val fixed =
            TradingMarketContext(
                MarketDefinition(MarketId(maximum), AssetId("b".repeat(256)), AssetId("q".repeat(256)), 0),
                policy.copy(scheduleVersion = Long.MAX_VALUE),
                Int.MAX_VALUE,
            )
        val input = input(Long.MAX_VALUE, user = maximum, fixed = fixed, source = longFunds, destination = longMatching)
        val resultStream = MatchingResultStream(longMatching, longFunds, fixed.market.marketId, Long.MAX_VALUE, Long.MAX_VALUE)
        val original = input.copy(stream = input.stream.copy(ownershipEpoch = Long.MAX_VALUE, configurationVersion = Long.MAX_VALUE))
        val result =
            MatchingResultEnvelope(
                resultStream,
                Long.MAX_VALUE,
                original,
                MatchingOutcome.NOT_EXECUTED,
                MatchingNotExecutedReason.RESULT_TOO_LARGE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                emptyList(),
                listOf(
                    MatchingOrderEffect(original, original.order.quantity, original.order.quantity, MatchingOrderDisposition.NOT_EXECUTED),
                ),
            )
        assertTrue(result.resultId.toByteArray().size > 256)
        assertEquals(
            result,
            (
                codec.decode(codec.encode(Long.MAX_VALUE, SplitResultDeliveryCommand.Apply(result))).command as
                    SplitResultDeliveryCommand.Apply
            ).result,
        )
    }

    @Test
    fun `descriptor와 실제 논리 charge를 대조한 상계가 기본한도 전체를 수용한다`() {
        SplitResultDeliveryCodec.verifySizingContract()
        assertEquals(1_377_824L, SplitResultDeliveryCodec.maximumResultFrameBytes(1_048_576, 1_024))
        assertTrue(SplitResultDeliveryCodec.maximumResultFrameBytes(1_048_576, 1_024) < SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
        assertFailsWith<ArithmeticException> { SplitResultDeliveryCodec.maximumResultFrameBytes(Long.MAX_VALUE, 1_024) }
        assertFailsWith<IllegalArgumentException> { SplitResultDeliveryCodec.maximumResultFrameBytes(1_048_576, 16_385) }
        val original = input(7, user = "한".repeat(85) + "a")
        val order = original.order
        val market = original.context.market
        // charge의 원본ID 추가1개를 포함한12문자열과 고정293바이트를 도메인 실제 값에 대조한다.
        val strings =
            listOf(
                order.marketId.value,
                order.orderId.value,
                order.marketId.value,
                order.orderId.value,
                order.userId.value,
                order.marketId.value,
                order.orderId.value,
                order.userId.value,
                market.quoteAssetId.value,
                market.marketId.value,
                market.baseAssetId.value,
                market.quoteAssetId.value,
            )
        assertEquals(293L + strings.sumOf { it.toByteArray().size.toLong() }, original.dispatchCanonicalBytes)
        val value = result(50)
        assertTrue(
            codec.encode(91, SplitResultDeliveryCommand.Apply(value)).size <=
                SplitResultDeliveryCodec.maximumResultFrameBytes(value.canonicalBytes, value.eventCount),
        )
    }

    @Test
    fun `실제 매칭 owner가 기본 단일 논리상한에 가까운 정상 결과를 만들고32KiB를 넘어 전달한다`() {
        val fixed = context.copy(feePolicySnapshot = policy.copy(feeRates = MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)))
        val topology =
            TradingRoleTopology(
                TradingClusterManifest(
                    funds,
                    "result-wire-test",
                    "0".repeat(64),
                    "1".repeat(64),
                    19,
                    listOf(fixed),
                    TradingResourceLimits(),
                ),
                listOf(TradingMarketAssignment(fixed.market.marketId, matching, 13)),
            )
        val configuration =
            MatchingIntakeConfiguration(
                topology,
                TradingRoleDeclaration(TradingServiceRole.MATCHING, matching, 19, listOf(fixed), mapOf(fixed.market.marketId to 13L)),
            )
        val maximumUser = "한".repeat(85) + "a"
        var n = 1
        var upper = 1_023
        while (n < upper) {
            val middle = (n + upper + 1) / 2
            if (result(middle, maximumUser, fixed).canonicalBytes <= 1_048_576) n = middle else upper = middle - 1
        }
        val owner = ReservedMatchingState(configuration).also { it.initialize() }
        for (index in 1..n) {
            val applied = owner.apply(input(index.toLong(), Side.SELL, 1, maximumUser, fixed))
            assertEquals(MatchingIntakeStatus.APPLIED, applied.status)
            owner.acknowledgeFundsSettled(MatchingFundsSettledAck(stream, index.toLong(), fixed))
        }
        val reply = owner.apply(input(n + 1L, Side.BUY, n + 1L, maximumUser, fixed))
        assertEquals(MatchingIntakeStatus.APPLIED, reply.status)
        val value = assertNotNull(reply.result)
        assertTrue(value.canonicalBytes in 1_040_000..1_048_576)
        val bytes = codec.encode(1, SplitResultDeliveryCommand.Apply(value))
        assertTrue(bytes.size > 32_768)
        assertTrue(bytes.size <= SplitResultDeliveryCodec.maximumResultFrameBytes(1_048_576, 1_024))
        assertEquals(value, (codec.decode(bytes).command as SplitResultDeliveryCommand.Apply).result)
    }

    @Test
    fun `필수 원문 필드 누락과 모든 중첩 unknown field는 성공하지 않는다`() {
        val wire = command(codec.encode(1, SplitResultDeliveryCommand.Apply(result(2))))
        for ((path, missing) in mutations(wire, unknown = false)) {
            assertFailsWith<AdmissionMessageException>(path) { codec.decode(frame(1, missing)) }
        }
        for ((path, unknown) in mutations(wire, unknown = true)) {
            assertFailsWith<AdmissionMessageException>(path) { codec.decode(frame(1, unknown)) }
        }
    }

    @Test
    fun `count 방향 enum context와 순번 모순은 거절한다`() {
        val wire = command(codec.encode(1, SplitResultDeliveryCommand.Apply(result(2))))
        val value = wire.apply
        val invalid =
            listOf(
                value.toBuilder().setFormatVersion(2),
                value.toBuilder().setResultSequence(0),
                value.toBuilder().setResultSequence(100),
                value.toBuilder().setEventCount(value.eventCount + 1),
                value.toBuilder().setOutcomeValue(0),
                value.toBuilder().setOutcomeValue(99),
                value.toBuilder().setEngineSequenceBefore(-1),
                value.toBuilder().setStream(value.stream.toBuilder().setDirectionValue(99)),
                value.toBuilder().setStream(value.stream.toBuilder().clearFundsLineage()),
                value.toBuilder().setInput(
                    value.input.toBuilder().setContext(
                        value.input.context
                            .toBuilder()
                            .setOrdinal(8),
                    ),
                ),
                value.toBuilder().setEffects(1, value.getEffects(1).toBuilder().setDispositionValue(99)),
                value.toBuilder().setEffects(1, value.getEffects(1).toBuilder().setOrigin(value.input)),
            )
        for ((index, bad) in invalid.withIndex()) {
            assertFailsWith<AdmissionMessageException>("invalid result $index") {
                codec.decode(frame(1, wire.toBuilder().setApply(bad).build()))
            }
        }
        val ack = command(codec.encode(1, SplitResultDeliveryCommand.RecordAck(MatchingFundsSettledAck(stream, 1, context))))
        assertFailsWith<AdmissionMessageException> {
            codec.decode(frame(1, ack.toBuilder().setRecordAck(ack.recordAck.toBuilder().setThrough(-1)).build()))
        }
        val response = response(codec.encodeReply(reply()))
        for (bad in listOf(
            response.toBuilder().setRole(SplitRole.SPLIT_ROLE_UNSPECIFIED),
            response.toBuilder().setExpectedSequence(0),
            response.toBuilder().setResultIssued(-1),
            response.toBuilder().setLifecycle("UNRECOGNIZED"),
            response.toBuilder().setTopologySha256("g".repeat(64)),
        )) {
            assertFailsWith<AdmissionMessageException> { codec.decodeReply(frame(2, bad.build())) }
        }
    }

    @Test
    fun `engine frontier overflow is malformed while exact Long MAX frontier is preserved`() {
        val original = command(codec.encode(1, SplitResultDeliveryCommand.Apply(result(1))))
        val result = original.apply
        val overflow = result.toBuilder().setEngineSequenceBefore(Long.MAX_VALUE).setEngineSequenceAfter(Long.MAX_VALUE)
        assertFailsWith<AdmissionMessageException> {
            codec.decode(frame(1, original.toBuilder().setApply(overflow).build()))
        }
        val exact =
            result
                .toBuilder()
                .setEngineSequenceBefore(Long.MAX_VALUE - 2)
                .setEngineSequenceAfter(Long.MAX_VALUE)
                .setEvents(
                    0,
                    result.getEvents(0).toBuilder().setTrade(
                        result
                            .getEvents(0)
                            .trade
                            .toBuilder()
                            .setEngineSequence(Long.MAX_VALUE - 1),
                    ),
                ).setEvents(
                    1,
                    result.getEvents(1).toBuilder().setEntered(
                        result
                            .getEvents(1)
                            .entered
                            .toBuilder()
                            .setEngineSequence(Long.MAX_VALUE),
                    ),
                ).build()
        val bytes = frame(1, original.toBuilder().setApply(exact).build())
        val restored = (codec.decode(bytes).command as SplitResultDeliveryCommand.Apply).result
        assertEquals(Long.MAX_VALUE, restored.engineSequenceAfter)
        assertEquals(listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE), restored.events.map { it.engineSequence })
        assertContentEquals(bytes, codec.encode(1, SplitResultDeliveryCommand.Apply(restored)))
    }

    @Test
    fun `exact maximum frame is accepted and one more byte is rejected for encode and decode`() {
        val maximum = SplitResultDeliveryBoundaryFixture.frame(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
        assertEquals(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES, maximum.bytes.size)
        val decoded = codec.decode(maximum.bytes)
        assertEquals(maximum.correlationId, decoded.correlationId)
        assertEquals(maximum.result, (decoded.command as SplitResultDeliveryCommand.Apply).result)
        assertContentEquals(maximum.bytes, codec.encode(maximum.correlationId, SplitResultDeliveryCommand.Apply(maximum.result)))

        val oversized = SplitResultDeliveryBoundaryFixture.frame(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + 1)
        assertEquals(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + 1, oversized.bytes.size)
        assertFailsWith<AdmissionMessageException> { codec.decode(oversized.bytes) }
        assertFailsWith<IllegalArgumentException> {
            codec.encode(oversized.correlationId, SplitResultDeliveryCommand.Apply(oversized.result))
        }
    }

    @Test
    fun `잘린 header 길이 범위 예약 비트와 구형schema를 거절한다`() {
        val bytes = codec.encode(1, SplitResultDeliveryCommand.DescribePeer)
        for (index in listOf(0, 2, 4, 6, 8)) {
            val damaged = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFailsWith<AdmissionMessageException> { codec.decode(damaged) }
        }
        assertFailsWith<AdmissionMessageException> { codec.decode(bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<AdmissionMessageException> { codec.decode(bytes + 0) }
        assertFailsWith<AdmissionMessageException> { codec.decode(bytes, -1) }
        assertFailsWith<AdmissionMessageException> { codec.decode(ByteArray(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + 1)) }
        val padded = ByteArray(bytes.size + 8).also { bytes.copyInto(it, 4) }
        assertEquals(codec.decode(bytes), codec.decode(padded, 4, bytes.size))
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(bytes) }
    }

    @Test
    fun `중복과 많은 빈 repeated 원문을 생성 parser 할당 전에 거절한다`() {
        val bytes = codec.encode(1, SplitResultDeliveryCommand.DescribePeer)
        val body = bytes.copyOfRange(12, bytes.size)
        assertFailsWith<AdmissionMessageException> { codec.decode(frameBody(1, body + body)) }
        val entered = command(codec.encode(1, SplitResultDeliveryCommand.Apply(result(2)))).apply.getEvents(2).entered
        val repeated =
            SplitResultCommand
                .newBuilder()
                .setCorrelationId(1)
                .setApply(
                    command(codec.encode(1, SplitResultDeliveryCommand.Apply(result(2))))
                        .apply
                        .toBuilder()
                        .clearEvents()
                        .addAllEvents(
                            List(SplitResultDeliveryCodec.MAX_RESULT_EVENTS + 1) {
                                SplitMatchingEvent
                                    .newBuilder()
                                    .setEntered(
                                        entered,
                                    ).build()
                            },
                        ),
                ).build()
        assertFailsWith<AdmissionMessageException> { codec.decode(frame(1, repeated)) }
        val response = response(codec.encodeReply(reply()))
        assertFailsWith<AdmissionMessageException> { codec.decodeReply(frame(2, response.toBuilder().setCode("x".repeat(257)).build())) }
    }

    private fun result(
        makers: Int,
        user: String = "buyer",
        fixed: TradingMarketContext = context,
    ): MatchingResultEnvelope {
        val taker = input(makers + 1L, Side.BUY, makers + 1L, user, fixed)
        val origins = (1..makers).map { input(it.toLong(), Side.SELL, 1, user, fixed) }
        val events =
            origins.mapIndexed { index, maker ->
                TradeExecuted(
                    fixed.market.marketId,
                    makers + index + 1L,
                    maker.order.orderId,
                    taker.order.orderId,
                    maker.order.userId,
                    taker.order.userId,
                    Side.BUY,
                    maker.order.price,
                    Quantity(1),
                )
            } +
                OrderEnteredBook(
                    fixed.market.marketId,
                    2L * makers + 1,
                    taker.order.orderId,
                    taker.order.userId,
                    Side.BUY,
                    Price(100),
                    Quantity(1),
                )
        return MatchingResultEnvelope(
            stream,
            makers + 1L,
            taker,
            MatchingOutcome.MATCHED,
            null,
            makers.toLong(),
            2L * makers + 1,
            events,
            listOf(MatchingOrderEffect(taker, taker.order.quantity, Quantity(1), MatchingOrderDisposition.RESTING)) +
                origins.map { MatchingOrderEffect(it, Quantity(1), Quantity.ZERO, MatchingOrderDisposition.TERMINAL) },
        )
    }

    private fun cancellationResult(rejected: Boolean = false): MatchingResultEnvelope {
        val origin = input(1).copy(inputContractVersion = 2)
        val cancellation =
            origin.copy(
                dispatchSequence = 2,
                cancel = FundsCancelDispatch(TradingIdentifiers.cancel(funds, 7), RequestToken(origin.order.userId, 3, 11, 5), 7, 1),
            )
        return MatchingResultEnvelope(
            stream,
            2,
            cancellation,
            if (rejected) MatchingOutcome.CANCEL_REJECTED else MatchingOutcome.CANCELLED,
            null,
            1,
            2,
            listOf(
                if (rejected) {
                    OrderCancelRejected(context.market.marketId, 2, origin.order.orderId, origin.order.userId, "order not found")
                } else {
                    OrderCancelled(context.market.marketId, 2, origin.order.orderId, origin.order.userId, Quantity(2))
                },
            ),
            if (rejected) {
                emptyList()
            } else {
                listOf(
                    MatchingOrderEffect(origin, Quantity(2), Quantity.ZERO, MatchingOrderDisposition.CANCELLED),
                )
            },
        )
    }

    private fun versionTwo(value: MatchingResultEnvelope): MatchingResultEnvelope =
        MatchingResultEnvelope(
            value.stream,
            value.resultSequence,
            value.input.copy(inputContractVersion = 2),
            value.outcome,
            value.reason,
            value.engineSequenceBefore,
            value.engineSequenceAfter,
            value.events,
            value.effects.map { it.copy(origin = it.origin.copy(inputContractVersion = 2)) },
        )

    private fun input(
        sequence: Long,
        side: Side = Side.BUY,
        quantity: Long = 2,
        user: String = "buyer",
        fixed: TradingMarketContext = context,
        source: ClusterLineage = funds,
        destination: ClusterLineage = matching,
    ): FundsOrderDispatch {
        val order =
            OrderAdmissionInput(
                fixed.market.marketId,
                TradingIdentifiers.order(source, sequence),
                UserId(user),
                side,
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(100),
                Quantity(quantity),
            )
        val reservation =
            OrderReservation.create(
                order.marketId,
                order.orderId,
                order.userId,
                side,
                order.price,
                order.quantity,
                reservations.calculate(fixed.market, side, order.price, order.quantity, fixed.feePolicySnapshot),
                fixed.feePolicySnapshot,
            )
        return FundsOrderDispatch(
            FundsDispatchStream(source, destination, order.marketId, 13, 19),
            sequence,
            sequence,
            sequence,
            order,
            reservation,
            fixed,
        )
    }

    private fun reply() =
        SplitResultDeliveryReply(
            91,
            "APPLIED",
            TradingServiceRole.FUNDS,
            funds,
            "a".repeat(64),
            8192,
            lifecycle = TradingLifecycle.RUNNING,
        )

    private fun command(bytes: ByteArray): SplitResultCommand = SplitResultCommand.parser().parseFrom(bytes, 12, bytes.size - 12)

    private fun response(bytes: ByteArray): SplitResultReply = SplitResultReply.parser().parseFrom(bytes, 12, bytes.size - 12)

    private fun frame(
        kind: Int,
        message: Message,
        version: Int = 1,
    ): ByteArray = frameBody(kind, message.toByteArray(), version)

    private fun frameBody(
        kind: Int,
        body: ByteArray,
        version: Int = 1,
    ): ByteArray =
        ByteArray(12 + body.size).also {
            ByteBuffer
                .wrap(it)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(89)
                .putShort(version.toShort())
                .putShort(kind.toShort())
                .putShort(0)
                .putInt(body.size)
            body.copyInto(it, 12)
        }

    private fun mutations(
        message: Message,
        unknown: Boolean,
    ): List<Pair<String, Message>> {
        val result = mutableListOf<Pair<String, Message>>()
        val unknownFields =
            UnknownFieldSet
                .newBuilder()
                .addField(
                    127,
                    UnknownFieldSet.Field
                        .newBuilder()
                        .addVarint(1)
                        .build(),
                ).build()
        if (unknown) result += message.descriptorForType.name to message.toBuilder().setUnknownFields(unknownFields).build()
        for ((field, value) in message.allFields) {
            if (!unknown && field.name !in setOf("reason", "released_amount")) {
                result += field.fullName to message.toBuilder().clearField(field).build()
            }
            if (value is Message) {
                for ((path, nested) in mutations(value, unknown)) result += path to message.toBuilder().setField(field, nested).build()
            } else if (value is List<*>) {
                value.forEachIndexed { index, row ->
                    if (row is Message) {
                        for ((path, nested) in mutations(row, unknown)) {
                            result += path to message.toBuilder().setRepeatedField(field, index, nested).build()
                        }
                    }
                }
            }
        }
        return result
    }
}
