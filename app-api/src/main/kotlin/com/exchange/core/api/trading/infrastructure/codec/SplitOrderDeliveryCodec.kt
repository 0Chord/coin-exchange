package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.SplitAppliedAck
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitCancelCode
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitCancelCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitCancelIntent
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitCancelReceipt
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitCancellation
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDirection
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDispatch
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitDispatchStream
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitFeePolicy
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitInputKind
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitLineage
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMarketContext
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitOrderInput
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitRequestToken
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
import com.exchange.core.trading.CancelCode
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.CancelReceipt
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsCancelDispatch
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingServiceRole
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.EnumValueDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Message
import com.google.protobuf.WireFormat
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

    data class Cancel(
        val token: RequestToken,
        val intent: CancelIntent,
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
    val cancellation: CancelReceipt? = null,
)

/** 고정한 입력 계약에 맞는 schema88 프레임만 읽고 쓴다. 기존 통합 v2/v3 계약은 유지한다. */
internal class SplitOrderDeliveryCodec(
    private val inputContractVersion: Int = 1,
) {
    private val bounded = BoundedTradingCodec()

    init {
        require(inputContractVersion in 1..2) { "unsupported split input contract" }
    }

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

            is SplitOrderDeliveryCommand.Cancel -> {
                require(inputContractVersion == 2) { "cancellation requires wire contract 2" }
                builder.cancel =
                    SplitCancelCommand
                        .newBuilder()
                        .setToken(token(command.token))
                        .setIntent(
                            SplitCancelIntent
                                .newBuilder()
                                .setMarketId(text(command.intent.marketId.value))
                                .setTargetOrderId(text(command.intent.targetOrderId.value))
                                .setExpectedConfigurationVersion(command.intent.expectedConfigurationVersion),
                        ).build()
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
            scanContractFields(bytes, offset + HEADER_BYTES, length - HEADER_BYTES, SplitDeliveryCommand.getDescriptor())
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

                    SplitDeliveryCommand.BodyCase.CANCEL -> {
                        require(inputContractVersion == 2)
                        complete(wire.cancel)
                        complete(wire.cancel.intent)
                        SplitOrderDeliveryCommand.Cancel(
                            token(wire.cancel.token),
                            CancelIntent(
                                MarketId(text(wire.cancel.intent.marketId)),
                                OrderId(text(wire.cancel.intent.targetOrderId)),
                                wire.cancel.intent.expectedConfigurationVersion,
                            ),
                        )
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
        reply.cancellation?.let {
            require(inputContractVersion == 2) { "cancellation receipt requires wire contract 2" }
            builder.cancellation = receipt(it)
        }
        return frame(REPLY_KIND, builder.build())
    }

    fun decodeReply(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): SplitOrderDeliveryReply =
        AdmissionMessageFormat.readValues {
            validate(bytes, offset, length, REPLY_KIND)
            scanContractFields(bytes, offset + HEADER_BYTES, length - HEADER_BYTES, SplitDeliveryReply.getDescriptor())
            val wire = SplitDeliveryReply.parser().parseFrom(bytes, offset + HEADER_BYTES, length - HEADER_BYTES)
            known(wire)
            complete(wire, setOf("ack", "expected_sequence", "control", "lifecycle", "cancellation"))
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
                if (wire.hasCancellation()) receipt(wire.cancellation) else null,
            )
        }

    internal fun dispatch(input: FundsOrderDispatch): SplitDispatch {
        require(input.formatVersion == inputContractVersion) { "split input contract differs" }
        require(input.dispatchSequence > 0 && input.admissionSequence > 0 && input.marketAdmissionSequence > 0)
        val builder =
            SplitDispatch
                .newBuilder()
                .setFormatVersion(input.formatVersion)
                .setStream(stream(input.stream))
                .setDispatchSequence(input.dispatchSequence)
                .setAdmissionSequence(input.admissionSequence)
                .setMarketAdmissionSequence(input.marketAdmissionSequence)
                .setOrder(order(input.order))
                .setReservation(reservation(input.reservation))
                .setContext(context(input.context))
        if (inputContractVersion == 1) {
            require(!input.isCancellation) { "cancellation requires wire contract 2" }
        } else {
            requireCancellation(input)
            builder.inputKind = if (input.isCancellation) SplitInputKind.SPLIT_CANCEL else SplitInputKind.SPLIT_NEW
            input.cancel?.let { cancel ->
                builder.cancellation =
                    SplitCancellation
                        .newBuilder()
                        .setCancelId(text(cancel.cancelId))
                        .setRequestToken(token(cancel.requestToken))
                        .setCancelSequence(cancel.cancelSequence)
                        .setTargetInputSequence(cancel.targetInputSequence)
                        .build()
            }
        }
        return builder.build()
    }

    internal fun dispatch(wire: SplitDispatch): FundsOrderDispatch {
        complete(wire, setOf("input_kind", "cancellation"))
        require(wire.formatVersion == inputContractVersion) { "split input contract differs" }
        require(wire.dispatchSequence > 0 && wire.admissionSequence > 0 && wire.marketAdmissionSequence > 0)
        if (inputContractVersion == 1) {
            require(wire.inputKindValue == 0 && !wire.hasCancellation()) { "new input fields require wire contract 2" }
        } else {
            require(wire.inputKind == SplitInputKind.SPLIT_NEW || wire.inputKind == SplitInputKind.SPLIT_CANCEL)
            require(wire.hasCancellation() == (wire.inputKind == SplitInputKind.SPLIT_CANCEL)) { "split input kind differs" }
        }
        val cancel =
            if (wire.hasCancellation()) {
                complete(wire.cancellation)
                wire.cancellation.let {
                    FundsCancelDispatch(
                        text(it.cancelId),
                        token(it.requestToken),
                        it.cancelSequence,
                        it.targetInputSequence,
                    )
                }
            } else {
                null
            }
        return FundsOrderDispatch(
            stream(wire.stream),
            wire.dispatchSequence,
            wire.admissionSequence,
            wire.marketAdmissionSequence,
            order(wire.order),
            reservation(wire.reservation),
            context(wire.context),
            cancel,
            inputContractVersion,
        ).also { if (inputContractVersion == 2) requireCancellation(it) }
    }

    private fun requireCancellation(input: FundsOrderDispatch) {
        require(input.marketAdmissionSequence <= input.dispatchSequence && input.marketAdmissionSequence <= input.admissionSequence)
        input.cancel?.let {
            require(it.cancelSequence > 0 && it.cancelId == TradingIdentifiers.cancel(input.stream.fundsLineage, it.cancelSequence)) {
                "split cancellation identity differs"
            }
            require(it.requestToken.userId == input.order.userId) { "split cancellation requester differs" }
            require(it.targetInputSequence >= input.marketAdmissionSequence && it.targetInputSequence < input.dispatchSequence) {
                "split cancellation must follow its target input"
            }
        }
    }

    private fun token(value: RequestToken): SplitRequestToken =
        SplitRequestToken
            .newBuilder()
            .setUserId(text(value.userId.value))
            .setChannel(value.channel)
            .setEpoch(value.epoch)
            .setSequence(value.sequence)
            .build()

    private fun token(wire: SplitRequestToken): RequestToken {
        complete(wire)
        return RequestToken(UserId(text(wire.userId)), wire.channel, wire.epoch, wire.sequence)
    }

    private fun receipt(value: CancelReceipt): SplitCancelReceipt {
        val builder =
            SplitCancelReceipt
                .newBuilder()
                .setCode(
                    if (value.code ==
                        CancelCode.ACCEPTED
                    ) {
                        SplitCancelCode.SPLIT_CANCEL_ACCEPTED
                    } else {
                        SplitCancelCode.SPLIT_CANCEL_REJECTED
                    },
                )
        value.cancelId?.let { builder.cancelId = text(it) }
        value.targetOrderId?.let { builder.targetOrderId = text(it.value) }
        value.inputSequence?.let { builder.inputSequence = it }
        value.reason?.let { builder.reason = text(it) }
        return builder.build()
    }

    private fun receipt(wire: SplitCancelReceipt): CancelReceipt {
        require(inputContractVersion == 2)
        complete(wire, setOf("cancel_id", "target_order_id", "input_sequence", "reason"))
        val code =
            when (wire.code) {
                SplitCancelCode.SPLIT_CANCEL_ACCEPTED -> CancelCode.ACCEPTED
                SplitCancelCode.SPLIT_CANCEL_REJECTED -> CancelCode.REJECTED
                else -> throw IllegalArgumentException("unknown cancellation receipt code")
            }
        return CancelReceipt(
            code,
            if (wire.hasCancelId()) text(wire.cancelId) else null,
            if (wire.hasTargetOrderId()) OrderId(text(wire.targetOrderId)) else null,
            if (wire.hasInputSequence()) wire.inputSequence else null,
            if (wire.hasReason()) text(wire.reason) else null,
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
        version: Int = inputContractVersion,
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
                (header.short.toInt() and 65535) == SCHEMA && (header.short.toInt() and 65535) == inputContractVersion,
        ) { "unsupported split header" }
    }

    /** v1에 추가 필드를 숨기거나 v2의 int32·oneof를 parser가 잘라 읽기 전에 거절한다. */
    private fun scanContractFields(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        descriptor: Descriptor,
    ) {
        val input = CodedInputStream.newInstance(bytes, offset, length)

        fun visit(type: Descriptor) {
            val seen = BooleanArray(type.fields.size)
            val oneofs = BooleanArray(type.oneofs.size)
            while (!input.isAtEnd) {
                val tag = input.readTag()
                require(tag != 0)
                val field = requireNotNull(type.findFieldByNumber(WireFormat.getTagFieldNumber(tag))) { "unknown split field" }
                val newer =
                    when (type.name) {
                        "SplitDeliveryCommand" -> field.name == "cancel"
                        "SplitDeliveryReply" -> field.name == "cancellation"
                        "SplitDispatch" -> field.number >= 9
                        else -> false
                    }
                require(inputContractVersion == 2 || !newer) { "new fields require split wire contract 2" }
                if (inputContractVersion == 2 && !field.isRepeated) {
                    require(!seen[field.index]) { "duplicate split field" }
                    seen[field.index] = true
                    field.containingOneof?.let {
                        require(!oneofs[it.index]) { "duplicate split oneof" }
                        oneofs[it.index] = true
                    }
                }
                val wireType = WireFormat.getTagWireType(tag)
                when {
                    field.javaType == JavaType.MESSAGE && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                        val size = input.readRawVarint32()
                        require(size in 0..MAX_MESSAGE_BYTES)
                        val old = input.pushLimit(size)
                        visit(field.messageType)
                        require(input.bytesUntilLimit == 0)
                        input.popLimit(old)
                    }

                    inputContractVersion == 2 && field.javaType in setOf(JavaType.INT, JavaType.ENUM, JavaType.BOOLEAN) -> {
                        require(wireType == WireFormat.WIRETYPE_VARINT)
                        val value = input.readInt64()
                        if (field.javaType == JavaType.BOOLEAN) {
                            require(value == 0L || value == 1L) { "invalid split boolean" }
                        } else {
                            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "split int32 overflows" }
                        }
                    }

                    else -> {
                        require(input.skipField(tag))
                    }
                }
            }
        }
        visit(descriptor)
    }

    companion object {
        const val HEADER_BYTES = 8
        const val MAX_MESSAGE_BYTES = 32_768
        const val MAX_BODY_BYTES = MAX_MESSAGE_BYTES - HEADER_BYTES
        private const val COMMAND_KIND = 1
        private const val REPLY_KIND = 2
        private const val SCHEMA = 88
        private const val MAX_MARKETS = 128
        private val SHA256 = Regex("[0-9a-f]{64}")

        /**
         * 크기 계산용 상위 경계다. 문자열은 각각 UTF-8 256바이트로 둔다.
         * 정수는 가장 긴 인코딩으로 두고 선택적인 예약 반환 필드도 포함한다.
         * 이 합성 값은 디코딩이나 업무 상태에 넣지 않는다.
         */
        fun maximumDispatchFrameBytes(inputContractVersion: Int = 1): Int {
            require(inputContractVersion in 1..2)

            fun maximum(message: Message): Message {
                val builder = message.toBuilder()
                for (field in message.descriptorForType.fields) {
                    if (inputContractVersion == 1 && message is SplitDispatch && field.number >= 9) continue
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
