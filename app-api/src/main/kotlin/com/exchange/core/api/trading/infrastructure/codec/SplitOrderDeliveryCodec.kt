package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.SplitAppliedAck
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDirection
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDispatch
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDispatchStream
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitFeePolicy
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitLineage
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMarketContext
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitOrderInput
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitReservation
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitReservationStatus
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitRole
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingServiceRole
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Descriptors.EnumValueDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Message
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.exchange.core.api.trading.infrastructure.codec.generated.BoundedTradingCommand as WireControl
import com.exchange.core.api.trading.infrastructure.codec.generated.BoundedTradingReply as WireControlReply
import com.exchange.core.api.trading.infrastructure.codec.generated.FeeTier as WireFeeTier
import com.exchange.core.api.trading.infrastructure.codec.generated.OrderType as WireOrderType
import com.exchange.core.api.trading.infrastructure.codec.generated.ProductType as WireProductType
import com.exchange.core.api.trading.infrastructure.codec.generated.Side as WireSide
import com.exchange.core.api.trading.infrastructure.codec.generated.TimeInForce as WireTimeInForce

internal sealed interface SplitOrderDeliveryCommand {
    data object DescribePeer : SplitOrderDeliveryCommand

    data class InitializeRole(
        val topologySha256: String,
    ) : SplitOrderDeliveryCommand

    data class Control(
        val command: BoundedTradingCommand,
    ) : SplitOrderDeliveryCommand

    data class Apply(
        val input: FundsOrderDispatch,
    ) : SplitOrderDeliveryCommand

    data class RecordAck(
        val ack: FundsMatchingAppliedAck,
    ) : SplitOrderDeliveryCommand
}

internal data class SplitOrderDeliveryEnvelope(
    val correlationId: Long,
    val command: SplitOrderDeliveryCommand,
)

internal data class SplitOrderDeliveryReply(
    val correlationId: Long,
    val code: String,
    val role: TradingServiceRole,
    val lineage: ClusterLineage,
    val topologySha256: String,
    val appliedLogPosition: Long,
    val ack: FundsMatchingAppliedAck? = null,
    val expectedSequence: Long? = null,
    val control: BoundedTradingReply? = null,
    val lifecycle: String? = null,
)

/** schema88/version1은 별도 프레임을 사용하며 기존 통합 v2/v3 계약을 바꾸지 않는다. */
internal class SplitOrderDeliveryCodec {
    private val bounded = BoundedTradingCodec()

    fun encode(
        correlationId: Long,
        command: SplitOrderDeliveryCommand,
    ): ByteArray {
        require(correlationId >= 0) { "negative split correlation" }
        val builder = SplitDeliveryCommand.newBuilder().setCorrelationId(correlationId)
        when (command) {
            SplitOrderDeliveryCommand.DescribePeer -> {
                builder.describePeer = true
            }

            is SplitOrderDeliveryCommand.InitializeRole -> {
                builder.initializeTopologySha256 = fingerprint(command.topologySha256)
            }

            is SplitOrderDeliveryCommand.Control -> {
                allowedControl(command.command)
                val bytes = bounded.encode(correlationId, command.command)
                // 저빈도 제어 명령은 기존 타입 검증을 재사용한다.
                bounded.decode(bytes)
                builder.control = WireControl.parser().parseFrom(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES).also(::known)
            }

            is SplitOrderDeliveryCommand.Apply -> {
                builder.apply = dispatch(command.input)
            }

            is SplitOrderDeliveryCommand.RecordAck -> {
                builder.recordAck = ack(command.ack)
            }
        }
        return frame(COMMAND_KIND, builder.build())
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): SplitOrderDeliveryEnvelope =
        AdmissionMessageFormat.readValues {
            validate(bytes, offset, length, COMMAND_KIND)
            val wire = SplitDeliveryCommand.parser().parseFrom(bytes, offset + HEADER_BYTES, length - HEADER_BYTES)
            known(wire)
            require(wire.hasCorrelationId() && wire.correlationId >= 0) { "missing or negative split correlation" }
            val command =
                when (wire.bodyCase) {
                    SplitDeliveryCommand.BodyCase.DESCRIBE_PEER -> {
                        require(wire.describePeer)
                        SplitOrderDeliveryCommand.DescribePeer
                    }

                    SplitDeliveryCommand.BodyCase.INITIALIZE_TOPOLOGY_SHA256 -> {
                        SplitOrderDeliveryCommand.InitializeRole(fingerprint(wire.initializeTopologySha256))
                    }

                    SplitDeliveryCommand.BodyCase.CONTROL -> {
                        val control = bounded.decode(controlFrame(20, wire.control))
                        require(control.correlationId == wire.correlationId) { "nested control correlation differs" }
                        allowedControl(control.command)
                        SplitOrderDeliveryCommand.Control(control.command)
                    }

                    SplitDeliveryCommand.BodyCase.APPLY -> {
                        SplitOrderDeliveryCommand.Apply(dispatch(wire.apply))
                    }

                    SplitDeliveryCommand.BodyCase.RECORD_ACK -> {
                        SplitOrderDeliveryCommand.RecordAck(ack(wire.recordAck))
                    }

                    else -> {
                        throw AdmissionMessageException("split command body missing")
                    }
                }
            SplitOrderDeliveryEnvelope(wire.correlationId, command)
        }

    fun encodeReply(reply: SplitOrderDeliveryReply): ByteArray {
        require(reply.correlationId >= 0 && reply.appliedLogPosition >= 0)
        val builder =
            SplitDeliveryReply
                .newBuilder()
                .setCorrelationId(reply.correlationId)
                .setCode(text(reply.code))
                .setRole(role(reply.role))
                .setLineage(lineage(reply.lineage))
                .setTopologySha256(fingerprint(reply.topologySha256))
                .setAppliedLogPosition(reply.appliedLogPosition)
        reply.ack?.let { builder.ack = ack(it) }
        reply.expectedSequence?.let {
            require(it > 0)
            builder.expectedSequence = it
        }
        reply.control?.let {
            require(it.correlationId == reply.correlationId) { "nested control correlation differs" }
            require(it.markets.size <= MAX_MARKETS)
            val bytes = bounded.encode(it)
            bounded.decodeResponse(bytes)
            builder.control = WireControlReply.parser().parseFrom(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES).also(::known)
        }
        reply.lifecycle?.let { builder.lifecycle = TradingLifecycle.valueOf(it).name }
        return frame(REPLY_KIND, builder.build())
    }

    fun decodeReply(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): SplitOrderDeliveryReply =
        AdmissionMessageFormat.readValues {
            validate(bytes, offset, length, REPLY_KIND)
            val wire = SplitDeliveryReply.parser().parseFrom(bytes, offset + HEADER_BYTES, length - HEADER_BYTES)
            known(wire)
            complete(wire, setOf("ack", "expected_sequence", "control", "lifecycle"))
            require(wire.correlationId >= 0 && wire.appliedLogPosition >= 0)
            val control =
                if (wire.hasControl()) {
                    require(wire.control.marketsCount <= MAX_MARKETS)
                    bounded.decodeResponse(controlFrame(21, wire.control)).also {
                        require(it.correlationId == wire.correlationId) { "nested control correlation differs" }
                    }
                } else {
                    null
                }
            SplitOrderDeliveryReply(
                wire.correlationId,
                text(wire.code),
                role(wire.role),
                lineage(wire.lineage),
                fingerprint(wire.topologySha256),
                wire.appliedLogPosition,
                if (wire.hasAck()) ack(wire.ack) else null,
                if (wire.hasExpectedSequence()) wire.expectedSequence.also { require(it > 0) } else null,
                control,
                if (wire.hasLifecycle()) TradingLifecycle.valueOf(wire.lifecycle).name else null,
            )
        }

    internal fun dispatch(input: FundsOrderDispatch): SplitDispatch {
        require(input.dispatchSequence > 0 && input.admissionSequence > 0 && input.marketAdmissionSequence > 0)
        return SplitDispatch
            .newBuilder()
            .setFormatVersion(input.formatVersion)
            .setStream(stream(input.stream))
            .setDispatchSequence(input.dispatchSequence)
            .setAdmissionSequence(input.admissionSequence)
            .setMarketAdmissionSequence(input.marketAdmissionSequence)
            .setOrder(order(input.order))
            .setReservation(reservation(input.reservation))
            .setContext(context(input.context))
            .build()
    }

    internal fun dispatch(wire: SplitDispatch): FundsOrderDispatch {
        complete(wire)
        require(wire.formatVersion == 1)
        require(wire.dispatchSequence > 0 && wire.admissionSequence > 0 && wire.marketAdmissionSequence > 0)
        return FundsOrderDispatch(
            stream(wire.stream),
            wire.dispatchSequence,
            wire.admissionSequence,
            wire.marketAdmissionSequence,
            order(wire.order),
            reservation(wire.reservation),
            context(wire.context),
        )
    }

    private fun stream(value: FundsDispatchStream): SplitDispatchStream =
        SplitDispatchStream
            .newBuilder()
            .setFundsLineage(lineage(value.fundsLineage))
            .setMatchingLineage(lineage(value.matchingLineage))
            .setMarketId(text(value.marketId.value))
            .setDirection(SplitDirection.SPLIT_FUNDS_TO_MATCHING)
            .setOwnershipEpoch(value.ownershipEpoch)
            .setConfigurationVersion(value.configurationVersion)
            .build()

    private fun stream(wire: SplitDispatchStream): FundsDispatchStream {
        complete(wire)
        require(wire.direction == SplitDirection.SPLIT_FUNDS_TO_MATCHING)
        return FundsDispatchStream(
            lineage(wire.fundsLineage),
            lineage(wire.matchingLineage),
            MarketId(text(wire.marketId)),
            wire.ownershipEpoch,
            wire.configurationVersion,
        )
    }

    private fun order(value: OrderAdmissionInput): SplitOrderInput {
        require(value.quantity.value > 0)
        return SplitOrderInput
            .newBuilder()
            .setMarketId(text(value.marketId.value))
            .setOrderId(text(value.orderId.value))
            .setUserId(text(value.userId.value))
            .setSide(WireSide.valueOf(value.side.name))
            .setOrderType(WireOrderType.valueOf(value.orderType.name))
            .setTimeInForce(WireTimeInForce.valueOf(value.timeInForce.name))
            .setPrice(value.price.value)
            .setQuantity(value.quantity.value)
            .build()
    }

    private fun order(wire: SplitOrderInput): OrderAdmissionInput {
        complete(wire)
        require(wire.quantity > 0)
        return OrderAdmissionInput(
            MarketId(text(wire.marketId)),
            OrderId(text(wire.orderId)),
            UserId(text(wire.userId)),
            Side.valueOf(wire.side.name),
            OrderType.valueOf(wire.orderType.name),
            TimeInForce.valueOf(wire.timeInForce.name),
            Price(wire.price),
            Quantity(wire.quantity),
        )
    }

    private fun fee(value: TradingFeePolicySnapshot): SplitFeePolicy =
        SplitFeePolicy
            .newBuilder()
            .setProductType(WireProductType.valueOf(value.productType.name))
            .setFeeTier(WireFeeTier.valueOf(value.feeTier.name))
            .setScheduleVersion(value.scheduleVersion)
            .setMakerPpm(value.feeRates.makerFeeRate.partsPerMillion)
            .setTakerPpm(value.feeRates.takerFeeRate.partsPerMillion)
            .build()

    private fun fee(wire: SplitFeePolicy): TradingFeePolicySnapshot {
        complete(wire)
        return TradingFeePolicySnapshot(
            FeeProductType.valueOf(wire.productType.name),
            FeeTier.valueOf(wire.feeTier.name),
            wire.scheduleVersion,
            MakerTakerFeeRates(FeeRate(wire.makerPpm), FeeRate(wire.takerPpm)),
        )
    }

    internal fun context(value: TradingMarketContext): SplitMarketContext {
        require(value.ordinal >= 0)
        val market = value.market
        return SplitMarketContext
            .newBuilder()
            .setMarketId(text(market.marketId.value))
            .setBaseAssetId(text(market.baseAssetId.value))
            .setQuoteAssetId(text(market.quoteAssetId.value))
            .setBaseAssetScale(market.baseAssetScale)
            .setPriceTick(market.priceTick)
            .setQuantityStep(market.quantityStep)
            .setFeePolicy(fee(value.feePolicySnapshot))
            .setOrdinal(value.ordinal)
            .build()
    }

    internal fun context(wire: SplitMarketContext): TradingMarketContext {
        complete(wire)
        require(wire.ordinal >= 0)
        return TradingMarketContext(
            MarketDefinition(
                MarketId(text(wire.marketId)),
                AssetId(text(wire.baseAssetId)),
                AssetId(text(wire.quoteAssetId)),
                wire.baseAssetScale,
                wire.priceTick,
                wire.quantityStep,
            ),
            fee(wire.feePolicy),
            wire.ordinal,
        )
    }

    private fun reservation(value: OrderReservation): SplitReservation {
        val builder =
            SplitReservation
                .newBuilder()
                .setMarketId(text(value.marketId.value))
                .setOrderId(text(value.orderId.value))
                .setUserId(text(value.userId.value))
                .setSide(WireSide.valueOf(value.side.name))
                .setAssetId(text(value.assetId.value))
                .setLimitPrice(value.limitPrice.value)
                .setInitialQuantity(value.initialQuantity.value)
                .setRemainingQuantity(value.remainingQuantity.value)
                .setReservedAmount(value.reservedAmount.value)
                .setRemainingAmount(value.remainingAmount.value)
                .setFeePolicy(fee(value.feePolicySnapshot))
                .setInitialFeeReserveAmount(value.initialFeeReserveAmount.value)
                .setRemainingFeeReserveAmount(value.remainingFeeReserveAmount.value)
                .setStatus(SplitReservationStatus.valueOf("SPLIT_${value.status.name}"))
                .setFeeRemainder(value.feeRemainder.numerator)
        value.releasedAmount?.let { builder.releasedAmount = it.value }
        return builder.build()
    }

    private fun reservation(wire: SplitReservation): OrderReservation {
        complete(wire, setOf("released_amount"))
        return OrderReservation(
            MarketId(text(wire.marketId)),
            OrderId(text(wire.orderId)),
            UserId(text(wire.userId)),
            Side.valueOf(wire.side.name),
            AssetId(text(wire.assetId)),
            Price(wire.limitPrice),
            Quantity(wire.initialQuantity),
            Quantity(wire.remainingQuantity),
            Amount(wire.reservedAmount),
            Amount(wire.remainingAmount),
            fee(wire.feePolicy),
            Amount(wire.initialFeeReserveAmount),
            Amount(wire.remainingFeeReserveAmount),
            OrderReservationStatus.valueOf(wire.status.name.removePrefix("SPLIT_")),
            FeeRemainder(wire.feeRemainder),
            if (wire.hasReleasedAmount()) Amount(wire.releasedAmount) else null,
        )
    }

    private fun ack(value: FundsMatchingAppliedAck): SplitAppliedAck {
        require(value.through >= 0)
        return SplitAppliedAck
            .newBuilder()
            .setStream(stream(value.stream))
            .setThrough(value.through)
            .setContext(context(value.context))
            .build()
    }

    private fun ack(wire: SplitAppliedAck): FundsMatchingAppliedAck {
        complete(wire)
        require(wire.through >= 0)
        return FundsMatchingAppliedAck(stream(wire.stream), wire.through, context(wire.context))
    }

    internal fun lineage(value: ClusterLineage): SplitLineage =
        SplitLineage
            .newBuilder()
            .setHigh(value.high)
            .setLow(value.low)
            .build()

    internal fun lineage(wire: SplitLineage): ClusterLineage {
        complete(wire)
        return ClusterLineage(wire.high, wire.low)
    }

    internal fun role(value: TradingServiceRole): SplitRole =
        when (value) {
            TradingServiceRole.FUNDS -> SplitRole.SPLIT_FUNDS
            TradingServiceRole.MATCHING -> SplitRole.SPLIT_MATCHING
            else -> throw IllegalArgumentException("integrated role cannot enter split wire")
        }

    internal fun role(wire: SplitRole): TradingServiceRole =
        when (wire) {
            SplitRole.SPLIT_FUNDS -> TradingServiceRole.FUNDS
            SplitRole.SPLIT_MATCHING -> TradingServiceRole.MATCHING
            else -> throw IllegalArgumentException("unknown split role")
        }

    private fun allowedControl(command: BoundedTradingCommand) {
        require(command !is BoundedTradingCommand.Execution && command !is BoundedTradingCommand.Quarantine) {
            "integrated execution and quarantine cannot enter split wire"
        }
        when (command) {
            is BoundedTradingCommand.Acknowledge -> {
                text(command.userId.value)
                require(command.channel >= 0 && command.epoch > 0 && command.through >= 0)
            }

            is BoundedTradingCommand.Rotate -> {
                text(command.userId.value)
                require(command.channel >= 0 && command.expectedEpoch >= 0)
            }

            else -> {
                Unit
            }
        }
    }

    private fun fingerprint(value: String): String = value.also { require(it.matches(SHA256)) { "invalid topology SHA-256" } }

    private fun text(value: String): String {
        require(value.isNotBlank() && '\u0000' !in value)
        return AdmissionMessageFormat.text(value)
    }

    private fun complete(
        message: Message,
        optional: Set<String> = emptySet(),
    ) {
        require(message.descriptorForType.fields.all { it.name in optional || message.hasField(it) }) {
            "missing split ${message.descriptorForType.name} field"
        }
    }

    private fun known(message: Message) {
        AdmissionMessageFormat.verifyKnownFields(message)
        for ((_, value) in message.allFields) {
            fun check(field: Any) {
                when (field) {
                    is Message -> known(field)
                    is EnumValueDescriptor -> require(field.index >= 0) { "unknown split enum" }
                    is String -> text(field)
                }
            }
            if (value is List<*>) value.forEach { check(checkNotNull(it)) } else check(value)
        }
    }

    private fun frame(
        kind: Int,
        message: Message,
        schema: Int = SCHEMA,
        version: Int = VERSION,
        maxBytes: Int = MAX_MESSAGE_BYTES,
    ): ByteArray {
        val size = message.serializedSize
        require(size in 1..maxBytes - HEADER_BYTES) { "split message exceeds frame limit" }
        return ByteArray(HEADER_BYTES + size).also { bytes ->
            ByteBuffer
                .wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(size.toShort())
                .putShort(kind.toShort())
                .putShort(schema.toShort())
                .putShort(version.toShort())
            val output = CodedOutputStream.newInstance(bytes, HEADER_BYTES, size)
            message.writeTo(output)
            output.checkNoSpaceLeft()
        }
    }

    private fun controlFrame(
        kind: Int,
        message: Message,
    ): ByteArray = frame(kind, message, 87, 3, BoundedTradingCodec.MAX_MESSAGE_BYTES)

    private fun validate(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        kind: Int,
    ) {
        require(offset >= 0 && length in HEADER_BYTES + 1..MAX_MESSAGE_BYTES && offset <= bytes.size - length) {
            "invalid split frame bounds"
        }
        val header = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        require(
            (header.short.toInt() and 65535) == length - HEADER_BYTES && (header.short.toInt() and 65535) == kind &&
                (header.short.toInt() and 65535) == SCHEMA && (header.short.toInt() and 65535) == VERSION,
        ) { "unsupported split header" }
    }

    companion object {
        const val HEADER_BYTES = 8
        const val MAX_MESSAGE_BYTES = 32_768
        const val MAX_BODY_BYTES = MAX_MESSAGE_BYTES - HEADER_BYTES
        private const val COMMAND_KIND = 1
        private const val REPLY_KIND = 2
        private const val SCHEMA = 88
        private const val VERSION = 1
        private const val MAX_MARKETS = 128
        private val SHA256 = Regex("[0-9a-f]{64}")

        /**
         * 크기 계산용 상위 경계다. 문자열은 각각 UTF-8 256바이트로 둔다.
         * 정수는 가장 긴 인코딩으로 두고 선택적인 예약 반환 필드도 포함한다.
         * 이 합성 값은 디코딩이나 업무 상태에 넣지 않는다.
         */
        fun maximumDispatchFrameBytes(): Int {
            fun maximum(message: Message): Message {
                val builder = message.toBuilder()
                for (field in message.descriptorForType.fields) {
                    check(!field.isRepeated)
                    val value: Any =
                        when (field.javaType) {
                            JavaType.INT -> -1
                            JavaType.LONG -> -1L
                            JavaType.STRING -> "x".repeat(AdmissionMessageFormat.MAX_IDENTIFIER_LENGTH)
                            JavaType.ENUM -> field.enumType.values.maxBy { it.number }
                            JavaType.MESSAGE -> maximum(builder.newBuilderForField(field).build())
                            else -> error("unsupported dispatch sizing field ${field.name}")
                        }
                    builder.setField(field, value)
                }
                return builder.build()
            }
            val dispatch = maximum(SplitDispatch.getDefaultInstance()) as SplitDispatch
            return HEADER_BYTES +
                SplitDeliveryCommand
                    .newBuilder()
                    .setCorrelationId(-1L)
                    .setApply(dispatch)
                    .build()
                    .serializedSize
        }
    }
}
