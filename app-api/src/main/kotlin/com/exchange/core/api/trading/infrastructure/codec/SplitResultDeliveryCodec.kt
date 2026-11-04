package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDispatch
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitFundsSettledAck
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMatchingEffect
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMatchingEvent
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMatchingOutcome
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMatchingResult
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitNotExecutedReason
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitOrderCancelRejected
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitOrderCancelled
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitOrderDisposition
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitOrderEnteredBook
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitResultCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitResultDirection
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitResultReply
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitResultStream
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitSettlementProgress
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitTradeExecuted
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.Side
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingNotExecutedReason
import com.exchange.core.trading.MatchingOrderDisposition
import com.exchange.core.trading.MatchingOrderEffect
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingServiceRole
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Message
import com.google.protobuf.WireFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.exchange.core.api.trading.infrastructure.codec.generated.Side as WireSide

internal sealed interface SplitResultDeliveryCommand {
    data object DescribePeer : SplitResultDeliveryCommand

    data class Apply(
        val result: MatchingResultEnvelope,
    ) : SplitResultDeliveryCommand

    data class Progress(
        val stream: MatchingResultStream,
        val context: TradingMarketContext,
    ) : SplitResultDeliveryCommand

    data class RecordAck(
        val ack: MatchingFundsSettledAck,
    ) : SplitResultDeliveryCommand
}

internal data class SplitResultDeliveryEnvelope(
    val correlationId: Long,
    val command: SplitResultDeliveryCommand,
)

internal data class SplitResultDeliveryReply(
    val correlationId: Long,
    val code: String,
    val role: TradingServiceRole,
    val lineage: ClusterLineage,
    val topologySha256: String,
    val appliedLogPosition: Long,
    val ack: MatchingFundsSettledAck? = null,
    val expectedSequence: Long? = null,
    val resultIssued: Long? = null,
    val lifecycle: TradingLifecycle? = null,
)

/** 결과는 schema89의 독립 프레임 하나로 보내며 ID·원본·정책을 외부 설정에서 보충하지 않는다. */
internal class SplitResultDeliveryCodec {
    private val fields = SplitOrderDeliveryCodec()

    fun encode(
        correlationId: Long,
        command: SplitResultDeliveryCommand,
    ): ByteArray {
        require(correlationId >= 0)
        val builder = SplitResultCommand.newBuilder().setCorrelationId(correlationId)
        when (command) {
            SplitResultDeliveryCommand.DescribePeer -> {
                builder.describePeer = true
            }

            is SplitResultDeliveryCommand.Apply -> {
                builder.apply = result(command.result)
            }

            is SplitResultDeliveryCommand.Progress -> {
                requireContext(command.stream, command.context)
                builder.progress =
                    SplitSettlementProgress
                        .newBuilder()
                        .setStream(
                            stream(command.stream),
                        ).setContext(fields.context(command.context))
                        .build()
            }

            is SplitResultDeliveryCommand.RecordAck -> {
                builder.recordAck = ack(command.ack)
            }
        }
        return frame(COMMAND_KIND, builder.build())
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): SplitResultDeliveryEnvelope =
        AdmissionMessageFormat.readValues {
            validate(bytes, offset, length, COMMAND_KIND, SplitResultCommand.getDescriptor())
            val wire = SplitResultCommand.parser().parseFrom(bytes, offset + HEADER_BYTES, length - HEADER_BYTES)
            require(wire.hasCorrelationId() && wire.correlationId >= 0)
            val command =
                when (wire.bodyCase) {
                    SplitResultCommand.BodyCase.DESCRIBE_PEER -> {
                        require(wire.describePeer)
                        SplitResultDeliveryCommand.DescribePeer
                    }

                    SplitResultCommand.BodyCase.APPLY -> {
                        SplitResultDeliveryCommand.Apply(result(wire.apply))
                    }

                    SplitResultCommand.BodyCase.PROGRESS -> {
                        complete(wire.progress)
                        val stream = stream(wire.progress.stream)
                        val context = fields.context(wire.progress.context)
                        requireContext(stream, context)
                        SplitResultDeliveryCommand.Progress(stream, context)
                    }

                    SplitResultCommand.BodyCase.RECORD_ACK -> {
                        SplitResultDeliveryCommand.RecordAck(ack(wire.recordAck))
                    }

                    else -> {
                        throw IllegalArgumentException("missing result command body")
                    }
                }
            SplitResultDeliveryEnvelope(wire.correlationId, command)
        }

    fun encodeReply(reply: SplitResultDeliveryReply): ByteArray {
        require(reply.correlationId >= 0 && reply.appliedLogPosition >= 0)
        val builder =
            SplitResultReply
                .newBuilder()
                .setCorrelationId(reply.correlationId)
                .setCode(text(reply.code))
                .setRole(fields.role(reply.role))
                .setLineage(fields.lineage(reply.lineage))
                .setTopologySha256(fingerprint(reply.topologySha256))
                .setAppliedLogPosition(reply.appliedLogPosition)
        reply.ack?.let { builder.ack = ack(it) }
        reply.expectedSequence?.let { builder.expectedSequence = it.also { require(it > 0) } }
        reply.resultIssued?.let { builder.resultIssued = it.also { require(it >= 0) } }
        reply.lifecycle?.let { builder.lifecycle = it.name }
        return frame(REPLY_KIND, builder.build())
    }

    fun decodeReply(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): SplitResultDeliveryReply =
        AdmissionMessageFormat.readValues {
            validate(bytes, offset, length, REPLY_KIND, SplitResultReply.getDescriptor())
            val wire = SplitResultReply.parser().parseFrom(bytes, offset + HEADER_BYTES, length - HEADER_BYTES)
            complete(wire, setOf("ack", "expected_sequence", "result_issued", "lifecycle"))
            require(wire.correlationId >= 0 && wire.appliedLogPosition >= 0)
            SplitResultDeliveryReply(
                wire.correlationId,
                text(wire.code),
                fields.role(wire.role),
                fields.lineage(wire.lineage),
                fingerprint(wire.topologySha256),
                wire.appliedLogPosition,
                if (wire.hasAck()) ack(wire.ack) else null,
                if (wire.hasExpectedSequence()) wire.expectedSequence.also { require(it > 0) } else null,
                if (wire.hasResultIssued()) wire.resultIssued.also { require(it >= 0) } else null,
                if (wire.hasLifecycle()) TradingLifecycle.valueOf(wire.lifecycle) else null,
            )
        }

    private fun result(value: MatchingResultEnvelope): SplitMatchingResult {
        requireResult(value)
        val builder =
            SplitMatchingResult
                .newBuilder()
                .setFormatVersion(value.formatVersion)
                .setStream(stream(value.stream))
                .setResultSequence(value.resultSequence)
                .setInput(fields.dispatch(value.input))
                .setOutcome(SplitMatchingOutcome.valueOf("SPLIT_${value.outcome.name}"))
                .setEngineSequenceBefore(value.engineSequenceBefore)
                .setEngineSequenceAfter(value.engineSequenceAfter)
                .setEventCount(value.eventCount)
        value.reason?.let { builder.reason = SplitNotExecutedReason.valueOf("SPLIT_${it.name}") }
        value.events.forEach { builder.addEvents(event(it)) }
        value.effects.forEach { builder.addEffects(effect(it)) }
        return builder.build()
    }

    private fun result(wire: SplitMatchingResult): MatchingResultEnvelope {
        complete(wire, setOf("reason", "events", "effects"))
        require(wire.formatVersion == 2 && wire.eventCount == wire.eventsCount)
        val result =
            MatchingResultEnvelope(
                stream(wire.stream),
                wire.resultSequence,
                fields.dispatch(wire.input),
                MatchingOutcome.valueOf(wire.outcome.name.removePrefix("SPLIT_")),
                if (wire.hasReason()) MatchingNotExecutedReason.valueOf(wire.reason.name.removePrefix("SPLIT_")) else null,
                wire.engineSequenceBefore,
                wire.engineSequenceAfter,
                wire.eventsList.map(::event),
                wire.effectsList.map(::effect),
            )
        requireResult(result)
        return result
    }

    private fun stream(value: MatchingResultStream): SplitResultStream =
        SplitResultStream
            .newBuilder()
            .setMatchingLineage(fields.lineage(value.matchingLineage))
            .setFundsLineage(fields.lineage(value.fundsLineage))
            .setMarketId(text(value.marketId.value))
            .setDirection(SplitResultDirection.SPLIT_MATCHING_TO_FUNDS)
            .setOwnershipEpoch(value.ownershipEpoch)
            .setConfigurationVersion(value.configurationVersion)
            .build()

    private fun stream(wire: SplitResultStream): MatchingResultStream {
        complete(wire)
        require(wire.direction == SplitResultDirection.SPLIT_MATCHING_TO_FUNDS)
        return MatchingResultStream(
            fields.lineage(wire.matchingLineage),
            fields.lineage(wire.fundsLineage),
            MarketId(text(wire.marketId)),
            wire.ownershipEpoch,
            wire.configurationVersion,
        )
    }

    private fun ack(value: MatchingFundsSettledAck): SplitFundsSettledAck {
        require(value.through >= 0)
        requireContext(value.stream, value.context)
        return SplitFundsSettledAck
            .newBuilder()
            .setStream(stream(value.stream))
            .setThrough(value.through)
            .setContext(fields.context(value.context))
            .build()
    }

    private fun ack(wire: SplitFundsSettledAck): MatchingFundsSettledAck {
        complete(wire)
        return MatchingFundsSettledAck(stream(wire.stream), wire.through, fields.context(wire.context)).also {
            require(it.through >= 0)
            requireContext(it.stream, it.context)
        }
    }

    private fun event(value: MatchingEvent): SplitMatchingEvent {
        val builder = SplitMatchingEvent.newBuilder()
        when (value) {
            is TradeExecuted -> {
                builder.trade =
                    SplitTradeExecuted
                        .newBuilder()
                        .setMarketId(text(value.marketId.value))
                        .setEngineSequence(value.engineSequence)
                        .setMakerOrderId(text(value.makerOrderId.value))
                        .setTakerOrderId(text(value.takerOrderId.value))
                        .setMakerUserId(text(value.makerUserId.value))
                        .setTakerUserId(text(value.takerUserId.value))
                        .setSide(WireSide.valueOf(value.side.name))
                        .setPrice(value.price.value)
                        .setQuantity(value.quantity.value)
                        .build()
            }

            is OrderEnteredBook -> {
                builder.entered =
                    SplitOrderEnteredBook
                        .newBuilder()
                        .setMarketId(text(value.marketId.value))
                        .setEngineSequence(value.engineSequence)
                        .setOrderId(text(value.orderId.value))
                        .setUserId(text(value.userId.value))
                        .setSide(WireSide.valueOf(value.side.name))
                        .setPrice(value.price.value)
                        .setRemainingQuantity(value.remainingQuantity.value)
                        .build()
            }

            is OrderCancelled -> {
                builder.cancelled =
                    SplitOrderCancelled
                        .newBuilder()
                        .setMarketId(text(value.marketId.value))
                        .setEngineSequence(value.engineSequence)
                        .setOrderId(text(value.orderId.value))
                        .setUserId(text(value.userId.value))
                        .setRemainingQuantity(value.remainingQuantity.value)
                        .build()
            }

            is OrderCancelRejected -> {
                builder.cancelRejected =
                    SplitOrderCancelRejected
                        .newBuilder()
                        .setMarketId(text(value.marketId.value))
                        .setEngineSequence(value.engineSequence)
                        .setOrderId(text(value.orderId.value))
                        .setUserId(text(value.userId.value))
                        .setReason(text(value.reason))
                        .build()
            }
        }
        return builder.build()
    }

    private fun event(wire: SplitMatchingEvent): MatchingEvent =
        when (wire.bodyCase) {
            SplitMatchingEvent.BodyCase.TRADE -> {
                wire.trade.let {
                    complete(it)
                    TradeExecuted(
                        MarketId(text(it.marketId)),
                        it.engineSequence,
                        OrderId(text(it.makerOrderId)),
                        OrderId(text(it.takerOrderId)),
                        UserId(text(it.makerUserId)),
                        UserId(text(it.takerUserId)),
                        Side.valueOf(it.side.name),
                        Price(it.price),
                        Quantity(it.quantity),
                    )
                }
            }

            SplitMatchingEvent.BodyCase.ENTERED -> {
                wire.entered.let {
                    complete(it)
                    OrderEnteredBook(
                        MarketId(text(it.marketId)),
                        it.engineSequence,
                        OrderId(text(it.orderId)),
                        UserId(text(it.userId)),
                        Side.valueOf(it.side.name),
                        Price(it.price),
                        Quantity(it.remainingQuantity),
                    )
                }
            }

            SplitMatchingEvent.BodyCase.CANCELLED -> {
                wire.cancelled.let {
                    complete(it)
                    OrderCancelled(
                        MarketId(text(it.marketId)),
                        it.engineSequence,
                        OrderId(text(it.orderId)),
                        UserId(text(it.userId)),
                        Quantity(it.remainingQuantity),
                    )
                }
            }

            SplitMatchingEvent.BodyCase.CANCEL_REJECTED -> {
                wire.cancelRejected.let {
                    complete(it)
                    OrderCancelRejected(
                        MarketId(text(it.marketId)),
                        it.engineSequence,
                        OrderId(text(it.orderId)),
                        UserId(text(it.userId)),
                        text(it.reason),
                    )
                }
            }

            else -> {
                throw IllegalArgumentException("missing result event body")
            }
        }

    private fun effect(value: MatchingOrderEffect): SplitMatchingEffect =
        SplitMatchingEffect
            .newBuilder()
            .setOrigin(
                fields.dispatch(value.origin),
            ).setBeforeQuantity(value.beforeQuantity.value)
            .setAfterQuantity(value.afterQuantity.value)
            .setDisposition(
                when (value.disposition) {
                    MatchingOrderDisposition.RESTING -> SplitOrderDisposition.SPLIT_RESTING
                    MatchingOrderDisposition.TERMINAL -> SplitOrderDisposition.SPLIT_TERMINAL
                    MatchingOrderDisposition.NOT_EXECUTED -> SplitOrderDisposition.SPLIT_ORDER_NOT_EXECUTED
                    MatchingOrderDisposition.CANCELLED -> SplitOrderDisposition.SPLIT_ORDER_CANCELLED
                },
            ).build()

    private fun effect(wire: SplitMatchingEffect): MatchingOrderEffect {
        complete(wire)
        return MatchingOrderEffect(
            fields.dispatch(wire.origin),
            Quantity(wire.beforeQuantity),
            Quantity(wire.afterQuantity),
            when (wire.disposition) {
                SplitOrderDisposition.SPLIT_RESTING -> MatchingOrderDisposition.RESTING
                SplitOrderDisposition.SPLIT_TERMINAL -> MatchingOrderDisposition.TERMINAL
                SplitOrderDisposition.SPLIT_ORDER_NOT_EXECUTED -> MatchingOrderDisposition.NOT_EXECUTED
                SplitOrderDisposition.SPLIT_ORDER_CANCELLED -> MatchingOrderDisposition.CANCELLED
                else -> throw IllegalArgumentException("unknown result disposition")
            },
        )
    }

    private fun requireResult(value: MatchingResultEnvelope) {
        require(value.resultSequence > 0 && value.resultSequence == value.input.dispatchSequence)
        require(value.formatVersion == 2 && value.eventCount <= MAX_RESULT_EVENTS && value.effects.size in 0..MAX_RESULT_EVENTS + 1)
        require(value.engineSequenceBefore >= 0 && value.engineSequenceAfter >= value.engineSequenceBefore)
        requireContext(value.stream, value.input.context)
        val dispatchStream =
            FundsDispatchStream(
                value.stream.fundsLineage,
                value.stream.matchingLineage,
                value.stream.marketId,
                value.stream.ownershipEpoch,
                value.stream.configurationVersion,
            )

        fun original(input: FundsOrderDispatch) {
            require(input.stream == dispatchStream && input.context == value.input.context) { "result original stream or context differs" }
            require(input.order.marketId == dispatchStream.marketId && input.reservation.marketId == dispatchStream.marketId)
            require(input.order.orderId == input.reservation.orderId && input.order.userId == input.reservation.userId)
            require(input.order.side == input.reservation.side && input.context.feePolicySnapshot == input.reservation.feePolicySnapshot)
        }
        original(value.input)
        if (!value.input.isCancellation) {
            require(value.effects.isNotEmpty() && value.effects.first().origin == value.input) { "result taker origin differs" }
        }
        require(
            value.effects
                .map { it.orderId }
                .distinct()
                .size == value.effects.size,
        )
        value.effects.forEach {
            original(it.origin)
            require(it.beforeQuantity.value > 0 && it.beforeQuantity <= it.origin.order.quantity)
            require(it.afterQuantity.value >= 0 && it.afterQuantity <= it.beforeQuantity)
        }
        value.events.forEach {
            require(it.marketId == value.stream.marketId && it.engineSequence > 0)
            when (it) {
                is TradeExecuted -> require(it.quantity.value > 0)
                is OrderEnteredBook -> require(it.remainingQuantity.value > 0)
                is OrderCancelled -> require(it.remainingQuantity.value > 0)
                is OrderCancelRejected -> require(it.reason == "order not found")
            }
        }
        when (value.outcome) {
            MatchingOutcome.NOT_EXECUTED -> {
                require(!value.input.isCancellation)
                require(value.reason != null && value.events.isEmpty() && value.effects.size == 1)
                require(value.engineSequenceAfter == value.engineSequenceBefore)
                require(value.effects.first().disposition == MatchingOrderDisposition.NOT_EXECUTED)
            }

            MatchingOutcome.MATCHED -> {
                require(!value.input.isCancellation)
                require(value.reason == null && value.eventCount > 0)
                // 외부 원문의 범위 오류가 ArithmeticException으로 서비스 소유자 실패 경계를 넘지 않게 한다.
                require(value.eventCount.toLong() <= Long.MAX_VALUE - value.engineSequenceBefore) { "result engine frontier overflows" }
                require(value.engineSequenceAfter == value.engineSequenceBefore + value.eventCount.toLong())
                require(value.events.all { it is TradeExecuted || it is OrderEnteredBook })
                require(value.effects.none { it.origin.isCancellation || it.disposition == MatchingOrderDisposition.CANCELLED })
                require(value.effects.size == value.events.count { it is TradeExecuted } + 1)
                value.events.forEachIndexed { index, event ->
                    // index+1은 위에서 검증한 eventCount 이하여서 같은 범위 안에 있다.
                    require(event.engineSequence == value.engineSequenceBefore + index.toLong() + 1)
                }
            }

            MatchingOutcome.CANCELLED, MatchingOutcome.CANCEL_REJECTED -> {
                val cancellation = requireNotNull(value.input.cancel) { "missing cancellation input" }
                require(cancellation.requestToken.userId == value.input.order.userId)
                require(value.reason == null && value.events.size == 1)
                require(value.engineSequenceBefore < Long.MAX_VALUE && value.engineSequenceAfter == value.engineSequenceBefore + 1)
                val event = value.events.single()
                require(event.engineSequence == value.engineSequenceAfter)
                if (value.outcome == MatchingOutcome.CANCELLED) {
                    require(event is OrderCancelled && value.effects.size == 1)
                    require(event.orderId == value.input.order.orderId && event.userId == cancellation.requestToken.userId)
                    val effect = value.effects.single()
                    val origin = effect.origin
                    require(!origin.isCancellation && origin.dispatchSequence < value.input.dispatchSequence)
                    require(
                        origin.admissionSequence == value.input.admissionSequence &&
                            origin.marketAdmissionSequence == value.input.marketAdmissionSequence,
                    )
                    require(origin.order == value.input.order && origin.reservation == value.input.reservation)
                    require(effect.beforeQuantity == event.remainingQuantity && effect.afterQuantity.value == 0L)
                    require(effect.disposition == MatchingOrderDisposition.CANCELLED)
                } else {
                    require(event is OrderCancelRejected && value.effects.isEmpty())
                    require(event.orderId == value.input.order.orderId && event.userId == cancellation.requestToken.userId)
                    require(event.reason == "order not found")
                }
            }
        }
    }

    private fun requireContext(
        stream: MatchingResultStream,
        context: TradingMarketContext,
    ) {
        require(stream.marketId == context.market.marketId) { "result market context differs" }
    }

    private fun complete(
        message: Message,
        optional: Set<String> = emptySet(),
    ) {
        require(message.descriptorForType.fields.all { it.name in optional || (!it.isRepeated && message.hasField(it)) }) {
            "missing result ${message.descriptorForType.name} field"
        }
    }

    private fun text(value: String): String = AdmissionMessageFormat.text(value).also { require(it.isNotBlank() && '\u0000' !in it) }

    private fun fingerprint(value: String): String = value.also { require(it.matches(SHA256)) }

    private fun frame(
        kind: Int,
        message: Message,
    ): ByteArray {
        val size = message.serializedSize
        require(size in 1..MAX_MESSAGE_BYTES - HEADER_BYTES) { "result message exceeds frame limit" }
        val bytes = ByteArray(HEADER_BYTES + size)
        ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(SCHEMA.toShort())
            .putShort(VERSION.toShort())
            .putShort(kind.toShort())
            .putShort(0)
            .putInt(size)
        val output = CodedOutputStream.newInstance(bytes, HEADER_BYTES, size)
        message.writeTo(output)
        output.checkNoSpaceLeft()
        // 동일한 중복·문자열·반복 상한을 송신 시에도 적용한다.
        scan(bytes, HEADER_BYTES, size, message.descriptorForType)
        return bytes
    }

    private fun validate(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        kind: Int,
        descriptor: Descriptor,
    ) {
        require(offset >= 0 && length in HEADER_BYTES + 1..MAX_MESSAGE_BYTES && offset <= bytes.size - length)
        val header = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        require(
            (header.short.toInt() and 65535) == SCHEMA && (header.short.toInt() and 65535) == VERSION &&
                (header.short.toInt() and 65535) == kind && header.short.toInt() == 0 && header.int == length - HEADER_BYTES,
        ) { "unsupported result header" }
        scan(bytes, offset + HEADER_BYTES, length - HEADER_BYTES, descriptor)
    }

    /** 생성 parser가 목록이나 긴 문자열을 할당하기 전에 크기·중복·타입을 제한한다. */
    private fun scan(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        descriptor: Descriptor,
    ) {
        val input = CodedInputStream.newInstance(bytes, offset, length)
        input.setSizeLimit(MAX_MESSAGE_BYTES)

        fun visit(
            type: Descriptor,
            depth: Int,
        ) {
            require(depth <= 16)
            val counts = IntArray(type.fields.size)
            val oneofs = BooleanArray(type.oneofs.size)
            while (!input.isAtEnd) {
                val tag = input.readTag()
                require(tag != 0)
                val field = requireNotNull(type.findFieldByNumber(WireFormat.getTagFieldNumber(tag))) { "unknown result field" }
                counts[field.index]++
                if (field.isRepeated) {
                    val maximum = if (field.name == "effects") MAX_RESULT_EVENTS + 1 else MAX_RESULT_EVENTS
                    require(counts[field.index] <= maximum) { "result repeated field exceeds bound" }
                } else {
                    require(counts[field.index] == 1) { "duplicate result field" }
                    field.containingOneof?.let {
                        require(!oneofs[it.index]) { "duplicate result oneof" }
                        oneofs[it.index] = true
                    }
                }
                val wireType = WireFormat.getTagWireType(tag)
                when (field.javaType) {
                    JavaType.MESSAGE -> {
                        require(wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED)
                        val size = input.readRawVarint32()
                        require(size in 1..MAX_MESSAGE_BYTES)
                        val old = input.pushLimit(size)
                        visit(field.messageType, depth + 1)
                        require(input.bytesUntilLimit == 0)
                        input.popLimit(old)
                    }

                    JavaType.STRING -> {
                        require(wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED)
                        val size = input.readRawVarint32()
                        require(size in 1..AdmissionMessageFormat.MAX_IDENTIFIER_LENGTH)
                        input.skipRawBytes(size)
                    }

                    JavaType.ENUM -> {
                        require(wireType == WireFormat.WIRETYPE_VARINT)
                        val value = input.readEnum()
                        require(value != 0 && field.enumType.findValueByNumber(value) != null) { "unknown result enum" }
                    }

                    JavaType.LONG -> {
                        if (field.type == FieldDescriptor.Type.FIXED64) {
                            require(wireType == WireFormat.WIRETYPE_FIXED64)
                            input.readFixed64()
                        } else {
                            require(wireType == WireFormat.WIRETYPE_VARINT)
                            input.readInt64()
                        }
                    }

                    JavaType.INT, JavaType.BOOLEAN -> {
                        require(wireType == WireFormat.WIRETYPE_VARINT)
                        val value = input.readInt64()
                        if (field.javaType == JavaType.BOOLEAN) {
                            require(value == 0L || value == 1L)
                        } else {
                            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                        }
                    }

                    else -> {
                        throw IllegalArgumentException("unsupported result field type")
                    }
                }
            }
        }
        visit(descriptor, 0)
    }

    companion object {
        const val HEADER_BYTES = 12
        const val MAX_MESSAGE_BYTES = 2 * 1_024 * 1_024 - 32
        const val MAX_BODY_BYTES = MAX_MESSAGE_BYTES - HEADER_BYTES
        const val MAX_RESULT_EVENTS = 16_384
        private const val SCHEMA = 89
        private const val VERSION = 2
        private const val COMMAND_KIND = 1
        private const val REPLY_KIND = 2
        private val SHA256 = Regex("[0-9a-f]{64}")

        /**
         * 논리 charge는 모든 원본을 각각 센다. 문자열 본문은 wire에서도 한 번씩이며,
         * 원본별 varint/정책/중첩 초과분256, effect32, event32, root/header1024를 더한다.
         * 기본1MiB/1024사건은1,377,824바이트다. 원본 최대크기를 다시 곱하지 않는다.
         */
        fun maximumResultFrameBytes(
            maxLogicalBytes: Long,
            maxEvents: Int,
        ): Long {
            require(maxLogicalBytes > 0 && maxEvents in 1..MAX_RESULT_EVENTS)
            verifySizingContract()
            val effects = Math.addExact(maxEvents.toLong(), 1)
            var bytes = Math.addExact(maxLogicalBytes, Math.multiplyExact(256L, Math.addExact(1, effects)))
            bytes = Math.addExact(bytes, Math.multiplyExact(32L, effects))
            bytes = Math.addExact(bytes, Math.multiplyExact(32L, maxEvents.toLong()))
            return Math.addExact(bytes, 1_024L)
        }

        fun upperBound(
            maxLogicalBytes: Long,
            maxEvents: Int,
        ): Long = maximumResultFrameBytes(maxLogicalBytes, maxEvents)

        /** descriptor가 바뀌면 기존 charge와의 상계 증명을 조용히 재사용하지 않는다. */
        fun verifySizingContract() {
            fun maximum(message: Message): Message {
                val builder = message.toBuilder()
                for (field in message.descriptorForType.fields) {
                    check(!field.isRepeated)
                    val value: Any =
                        when (field.javaType) {
                            JavaType.INT -> {
                                -1
                            }

                            JavaType.LONG -> {
                                -1L
                            }

                            JavaType.STRING -> {
                                "x".repeat(
                                    if (field.name ==
                                        "cancel_id"
                                    ) {
                                        40
                                    } else {
                                        AdmissionMessageFormat.MAX_IDENTIFIER_LENGTH
                                    },
                                )
                            }

                            JavaType.ENUM -> {
                                field.enumType.values.maxBy { it.number }
                            }

                            JavaType.MESSAGE -> {
                                maximum(builder.newBuilderForField(field).build())
                            }

                            else -> {
                                error("unsupported result sizing field ${field.name}")
                            }
                        }
                    builder.setField(field, value)
                }
                return builder.build()
            }

            fun strings(type: Descriptor): Int =
                type.fields.sumOf {
                    when (it.javaType) {
                        JavaType.STRING -> 1
                        JavaType.MESSAGE -> strings(it.messageType)
                        else -> 0
                    }
                }
            val dispatch = maximum(SplitDispatch.getDefaultInstance())
            // 취소 ID는 고정40byte, 요청자는 최대256byte다. 논리 취소 charge80에는 ID와 고정 slot이 포함된다.
            // wire의 문자열13개 중12개가 최대256byte이며, 원본 reserve ID는 논리 charge에서 한 번 더 센다.
            check(strings(dispatch.descriptorForType) == 13)
            check(dispatch.serializedSize - (12 * 256 + 40) - (293 + 40) <= 256)
            val trade = maximum(SplitTradeExecuted.getDefaultInstance()) as SplitTradeExecuted
            val entered = maximum(SplitOrderEnteredBook.getDefaultInstance()) as SplitOrderEnteredBook
            check(strings(trade.descriptorForType) == 5 && strings(entered.descriptorForType) == 3)
            // 사건의 oneof와 반복 wrapper는 최대 각각4바이트로 함께 센다.
            check(trade.serializedSize - 5 * 256 + 8 - (36 + 5 * 4) <= 32)
            check(entered.serializedSize - 3 * 256 + 8 - (32 + 3 * 4) <= 32)
            val cancelled = maximum(SplitOrderCancelled.getDefaultInstance())
            val cancelRejected = maximum(SplitOrderCancelRejected.getDefaultInstance())
            check(strings(cancelled.descriptorForType) == 3 && strings(cancelRejected.descriptorForType) == 4)
            check(cancelled.serializedSize - 3 * 256 + 8 - (20 + 3 * 4) <= 32)
            check(cancelRejected.serializedSize - 4 * 256 + 8 - (12 + 4 * 4) <= 32)
            val effect = maximum(SplitMatchingEffect.getDefaultInstance())
            check(effect.serializedSize - dispatch.serializedSize + 4 - 20 <= 32)
            val rootFields = SplitMatchingResult.getDescriptor().fields
            check(
                rootFields.map { it.name } ==
                    listOf(
                        "format_version",
                        "stream",
                        "result_sequence",
                        "input",
                        "outcome",
                        "reason",
                        "engine_sequence_before",
                        "engine_sequence_after",
                        "event_count",
                        "events",
                        "effects",
                    ),
            )
            check(rootFields.all { it.number < 16 })
            val stream = maximum(SplitResultStream.getDefaultInstance())
            // root는 최대 stream(태그·길이 포함), 스칼라8개, input wrapper, command/correlation/header로도1024 미만이다.
            check(stream.serializedSize + 4 + 8 * 11 + 4 + 4 + 11 + HEADER_BYTES <= 1_024)
        }
    }
}
