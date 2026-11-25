package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.BoundedAcknowledge
import com.exchange.core.api.trading.infrastructure.codec.generated.BoundedAdmissionReceipt
import com.exchange.core.api.trading.infrastructure.codec.generated.BoundedExecute
import com.exchange.core.api.trading.infrastructure.codec.generated.BoundedOrderCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.BoundedQuarantine
import com.exchange.core.api.trading.infrastructure.codec.generated.BoundedRotateEpoch
import com.exchange.core.api.trading.infrastructure.codec.generated.DevelopmentBalanceCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.InsufficientBalance
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReceipt
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.ExecuteAdmission
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.google.protobuf.ByteString
import com.google.protobuf.Message
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import com.exchange.core.api.trading.infrastructure.codec.generated.BoundedTradingCommand as WireCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.BoundedTradingReply as WireResponse
import com.exchange.core.api.trading.infrastructure.codec.generated.OrderType as WireOrderType
import com.exchange.core.api.trading.infrastructure.codec.generated.Side as WireSide
import com.exchange.core.api.trading.infrastructure.codec.generated.TimeInForce as WireTimeInForce

/** 버전 3은 명시적으로 선택한다. 기존 버전 2 요청을 새 요청 token에 다시 대응시키지 않는다. */
sealed interface BoundedTradingCommand {
    data class Initialize(
        val manifest: ByteArray,
    ) : BoundedTradingCommand

    data class Admit(
        val token: RequestToken,
        val intent: OrderIntent,
    ) : BoundedTradingCommand

    data class Acknowledge(
        val userId: UserId,
        val channel: Int,
        val epoch: Long,
        val through: Long,
    ) : BoundedTradingCommand

    data class Rotate(
        val userId: UserId,
        val channel: Int,
        val expectedEpoch: Long,
    ) : BoundedTradingCommand

    data class Execution(
        val command: ExecuteAdmission,
    ) : BoundedTradingCommand

    data class Funding(
        val command: DevelopmentBalanceCommandPayload,
    ) : BoundedTradingCommand

    data class Quarantine(
        val incidentPosition: Long,
        val reason: String,
    ) : BoundedTradingCommand

    data object Progress : BoundedTradingCommand
}

data class BoundedCommandEnvelope(
    val correlationId: Long,
    val command: BoundedTradingCommand,
)

data class BoundedMarketProgress(
    val marketId: String,
    val lastAssigned: Long,
    val executedThrough: Long,
)

data class BoundedTradingReply(
    val correlationId: Long,
    val code: String,
    val appliedLogPosition: Long,
    val admission: AdmissionReceipt? = null,
    val epoch: Long? = null,
    val retiredThrough: Long? = null,
    val markets: List<BoundedMarketProgress> = emptyList(),
    val lifecycle: String? = null,
    val lastAdmissionSequence: Long? = null,
    val funding: com.exchange.core.ledger.OpeningBalanceResult? = null,
    val lineage: ClusterLineage? = null,
)

class BoundedTradingCodec {
    fun encode(
        correlationId: Long,
        command: BoundedTradingCommand,
    ): ByteArray {
        val builder = WireCommand.newBuilder().setCorrelationId(correlationId)
        when (command) {
            is BoundedTradingCommand.Initialize -> {
                require(command.manifest.size in 1..MAX_MANIFEST_BYTES) { "bounded initialization manifest exceeds byte limit" }
                builder.initializeManifest = ByteString.copyFrom(command.manifest)
            }

            is BoundedTradingCommand.Admit -> {
                val t = command.token
                val i = command.intent
                builder.admission =
                    BoundedOrderCommand
                        .newBuilder()
                        .setUserId(text(t.userId.value))
                        .setChannel(t.channel)
                        .setEpoch(t.epoch)
                        .setSequence(t.sequence)
                        .setMarketId(text(i.marketId.value))
                        .setConfigurationVersion(i.expectedConfigurationVersion)
                        .setSide(WireSide.valueOf(i.side.name))
                        .setOrderType(WireOrderType.valueOf(i.orderType.name))
                        .setTimeInForce(WireTimeInForce.valueOf(i.timeInForce.name))
                        .setPrice(i.price.value)
                        .setQuantity(i.quantity.value)
                        .build()
            }

            is BoundedTradingCommand.Acknowledge -> {
                builder.acknowledge =
                    BoundedAcknowledge
                        .newBuilder()
                        .setUserId(
                            text(command.userId.value),
                        ).setChannel(command.channel)
                        .setEpoch(command.epoch)
                        .setThrough(command.through)
                        .build()
            }

            is BoundedTradingCommand.Rotate -> {
                builder.rotateEpoch =
                    BoundedRotateEpoch
                        .newBuilder()
                        .setUserId(text(command.userId.value))
                        .setChannel(command.channel)
                        .setExpectedEpoch(command.expectedEpoch)
                        .build()
            }

            is BoundedTradingCommand.Execution -> {
                val c = command.command
                builder.execution =
                    BoundedExecute
                        .newBuilder()
                        .setLineageHigh(c.lineage.high)
                        .setLineageLow(c.lineage.low)
                        .setMarketId(
                            text(c.marketId.value),
                        ).setMarketSequence(c.marketAdmissionSequence)
                        .setAdmissionSequence(c.admissionSequence)
                        .build()
            }

            is BoundedTradingCommand.Funding -> {
                val c = command.command
                builder.funding =
                    DevelopmentBalanceCommand
                        .newBuilder()
                        .setPreparationId(text(c.opening.preparationId))
                        .setUserId(text(c.opening.userId.value))
                        .setAssetId(text(c.opening.assetId.value))
                        .setAmount(c.opening.amount.value)
                        .setLedgerTransactionId(text(c.ledgerTransactionId))
                        .setOccurredAtEpochSecond(c.occurredAt.epochSecond)
                        .setOccurredAtNano(c.occurredAt.nano)
                        .build()
            }

            is BoundedTradingCommand.Quarantine -> {
                builder.quarantine =
                    BoundedQuarantine
                        .newBuilder()
                        .setIncidentPosition(command.incidentPosition)
                        .setReason(text(command.reason))
                        .build()
            }

            BoundedTradingCommand.Progress -> {
                builder.progress = true
            }
        }
        return frame(COMMAND_KIND, builder.build())
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): BoundedCommandEnvelope =
        AdmissionMessageFormat.readValues {
            validate(bytes, offset, length, COMMAND_KIND)
            val wire = WireCommand.parser().parseFrom(bytes, offset + HEADER_BYTES, length - HEADER_BYTES)
            known(wire)
            require(wire.hasCorrelationId()) { "missing correlation" }
            val command: BoundedTradingCommand =
                when (wire.bodyCase) {
                    WireCommand.BodyCase.INITIALIZE_MANIFEST -> {
                        BoundedTradingCommand.Initialize(
                            wire.initializeManifest.toByteArray().also {
                                require(it.size in 1..MAX_MANIFEST_BYTES) { "bounded initialization manifest exceeds byte limit" }
                            },
                        )
                    }

                    WireCommand.BodyCase.ADMISSION -> {
                        val w = wire.admission
                        known(w)
                        require(
                            w.hasUserId() && w.hasChannel() && w.hasEpoch() && w.hasSequence() && w.hasMarketId() &&
                                w.hasConfigurationVersion() && w.hasSide() && w.hasOrderType() && w.hasTimeInForce() && w.hasPrice() &&
                                w.hasQuantity(),
                        )
                        BoundedTradingCommand.Admit(
                            RequestToken(UserId(text(w.userId)), w.channel, w.epoch, w.sequence),
                            OrderIntent(
                                MarketId(text(w.marketId)),
                                w.configurationVersion,
                                Side.valueOf(w.side.name),
                                OrderType.valueOf(w.orderType.name),
                                TimeInForce.valueOf(w.timeInForce.name),
                                Price(w.price),
                                Quantity(w.quantity),
                            ),
                        )
                    }

                    WireCommand.BodyCase.ACKNOWLEDGE -> {
                        val w = wire.acknowledge
                        known(w)
                        require(w.hasUserId() && w.hasChannel() && w.hasEpoch() && w.hasThrough())
                        BoundedTradingCommand.Acknowledge(UserId(text(w.userId)), w.channel, w.epoch, w.through)
                    }

                    WireCommand.BodyCase.ROTATE_EPOCH -> {
                        val w = wire.rotateEpoch
                        known(w)
                        require(w.hasUserId() && w.hasChannel() && w.hasExpectedEpoch())
                        BoundedTradingCommand.Rotate(UserId(text(w.userId)), w.channel, w.expectedEpoch)
                    }

                    WireCommand.BodyCase.EXECUTION -> {
                        val w = wire.execution
                        known(w)
                        require(
                            w.hasLineageHigh() && w.hasLineageLow() && w.hasMarketId() && w.hasMarketSequence() && w.hasAdmissionSequence(),
                        )
                        BoundedTradingCommand.Execution(
                            ExecuteAdmission(
                                ClusterLineage(w.lineageHigh, w.lineageLow),
                                MarketId(text(w.marketId)),
                                w.marketSequence,
                                w.admissionSequence,
                            ),
                        )
                    }

                    WireCommand.BodyCase.FUNDING -> {
                        val w = wire.funding
                        known(w)
                        require(
                            w.hasPreparationId() && w.hasUserId() && w.hasAssetId() && w.hasAmount() && w.hasLedgerTransactionId() &&
                                w.hasOccurredAtEpochSecond() &&
                                w.hasOccurredAtNano(),
                        )
                        require(w.occurredAtNano in 0..999_999_999)
                        BoundedTradingCommand.Funding(
                            DevelopmentBalanceCommandPayload(
                                OpeningBalance(text(w.preparationId), UserId(text(w.userId)), AssetId(text(w.assetId)), Amount(w.amount)),
                                text(w.ledgerTransactionId),
                                Instant.ofEpochSecond(w.occurredAtEpochSecond, w.occurredAtNano.toLong()),
                            ),
                        )
                    }

                    WireCommand.BodyCase.PROGRESS -> {
                        require(wire.progress)
                        BoundedTradingCommand.Progress
                    }

                    WireCommand.BodyCase.QUARANTINE -> {
                        val w = wire.quarantine
                        known(w)
                        require(w.hasIncidentPosition() && w.hasReason())
                        BoundedTradingCommand.Quarantine(w.incidentPosition, text(w.reason))
                    }

                    else -> {
                        throw AdmissionMessageException("bounded command kind missing")
                    }
                }
            require(wire.correlationId >= 0)
            BoundedCommandEnvelope(wire.correlationId, command)
        }

    fun encode(response: BoundedTradingReply): ByteArray {
        val b =
            WireResponse
                .newBuilder()
                .setCorrelationId(
                    response.correlationId,
                ).setCode(text(response.code))
                .setAppliedLogPosition(response.appliedLogPosition)
        response.admission?.let { r ->
            val a = BoundedAdmissionReceipt.newBuilder().setCode(r.code.name)
            r.orderId?.let { a.orderId = text(it.value) }
            r.admissionSequence?.let { a.admissionSequence = it }
            r.marketAdmissionSequence?.let { a.marketSequence = it }
            r.reason?.let { a.reason = it.name }
            r.insufficientBalance?.let { v ->
                a.insufficientBalance =
                    InsufficientBalance
                        .newBuilder()
                        .setAssetId(text(v.assetId.value))
                        .setAvailable(v.available.value)
                        .setRequired(v.required.value)
                        .setShortfall(v.shortfall.value)
                        .build()
            }
            b.admission = a.build()
        }
        response.epoch?.let { b.epoch = it }
        response.retiredThrough?.let { b.retiredThrough = it }
        response.lifecycle?.let { b.lifecycle = text(it) }
        response.lastAdmissionSequence?.let { b.lastAdmissionSequence = it }
        response.lineage?.let {
            b.lineageHigh = it.high
            b.lineageLow = it.low
        }
        response.funding?.let { r ->
            b.funding =
                com.exchange.core.api.trading.infrastructure.codec.generated.DevelopmentBalancePrepared
                    .newBuilder()
                    .setPreparationId(
                        text(r.opening.preparationId),
                    ).setUserId(text(r.opening.userId.value))
                    .setAssetId(text(r.opening.assetId.value))
                    .setAmount(
                        r.opening.amount.value,
                    ).setLedgerTransactionId(text(r.ledgerTransactionId))
                    .setOccurredAtEpochSecond(r.occurredAt.epochSecond)
                    .setOccurredAtNano(r.occurredAt.nano)
                    .setAlreadyPrepared(r.alreadyPrepared)
                    .build()
        }
        response.markets.forEach { p ->
            b.addMarkets(
                com.exchange.core.api.trading.infrastructure.codec.generated.BoundedMarketProgress
                    .newBuilder()
                    .setMarketId(text(p.marketId))
                    .setLastAssigned(p.lastAssigned)
                    .setExecutedThrough(p.executedThrough),
            )
        }
        return frame(RESPONSE_KIND, b.build())
    }

    fun decodeResponse(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): BoundedTradingReply =
        AdmissionMessageFormat.readValues {
            validate(bytes, offset, length, RESPONSE_KIND)
            val w = WireResponse.parser().parseFrom(bytes, offset + HEADER_BYTES, length - HEADER_BYTES)
            known(w)
            require(w.hasCorrelationId() && w.hasCode() && w.hasAppliedLogPosition())
            require(w.hasLineageHigh() == w.hasLineageLow()) { "incomplete response lineage" }
            val receipt =
                if (w.hasAdmission()) {
                    val a = w.admission
                    known(a)
                    require(a.hasCode())
                    val shortage =
                        if (a.hasInsufficientBalance()) {
                            val v = a.insufficientBalance
                            known(v)
                            require(v.hasAssetId() && v.hasAvailable() && v.hasRequired() && v.hasShortfall())
                            OrderAdmissionResult.InsufficientBalance(
                                AssetId(text(v.assetId)),
                                Amount(v.available),
                                Amount(v.required),
                                Amount(v.shortfall),
                            )
                        } else {
                            null
                        }
                    AdmissionReceipt(
                        AdmissionCode.valueOf(a.code),
                        if (a.hasOrderId()) OrderId(text(a.orderId)) else null,
                        if (a.hasAdmissionSequence()) a.admissionSequence else null,
                        if (a.hasMarketSequence()) a.marketSequence else null,
                        shortage,
                        if (a.hasReason()) OrderAdmissionResult.Reason.valueOf(a.reason) else null,
                    )
                } else {
                    null
                }
            val funding =
                if (w.hasFunding()) {
                    val f = w.funding
                    known(f)
                    require(
                        f.hasPreparationId() && f.hasUserId() && f.hasAssetId() && f.hasAmount() && f.hasLedgerTransactionId() &&
                            f.hasOccurredAtEpochSecond() &&
                            f.hasOccurredAtNano() &&
                            f.hasAlreadyPrepared(),
                    )
                    require(f.occurredAtNano in 0..999_999_999)
                    com.exchange.core.ledger.OpeningBalanceResult(
                        OpeningBalance(text(f.preparationId), UserId(text(f.userId)), AssetId(text(f.assetId)), Amount(f.amount)),
                        text(f.ledgerTransactionId),
                        Instant.ofEpochSecond(f.occurredAtEpochSecond, f.occurredAtNano.toLong()),
                        f.alreadyPrepared,
                    )
                } else {
                    null
                }
            BoundedTradingReply(
                w.correlationId,
                text(w.code),
                w.appliedLogPosition,
                receipt,
                if (w.hasEpoch()) w.epoch else null,
                if (w.hasRetiredThrough()) w.retiredThrough else null,
                w.marketsList.map { p ->
                    known(p)
                    require(p.hasMarketId() && p.hasLastAssigned() && p.hasExecutedThrough())
                    BoundedMarketProgress(text(p.marketId), p.lastAssigned, p.executedThrough)
                },
                if (w.hasLifecycle()) text(w.lifecycle) else null,
                if (w.hasLastAdmissionSequence()) w.lastAdmissionSequence else null,
                funding,
                if (w.hasLineageHigh() && w.hasLineageLow()) ClusterLineage(w.lineageHigh, w.lineageLow) else null,
            )
        }

    private fun frame(
        kind: Int,
        message: Message,
    ): ByteArray {
        val size = message.serializedSize
        require(size in 1..MAX_BODY_BYTES)
        return ByteArray(HEADER_BYTES + size).also { bytes ->
            ByteBuffer
                .wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(size.toShort())
                .putShort(kind.toShort())
                .putShort(87)
                .putShort(3)
            val output =
                com.google.protobuf.CodedOutputStream
                    .newInstance(bytes, HEADER_BYTES, size)
            message.writeTo(output)
            output.checkNoSpaceLeft()
        }
    }

    private fun validate(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        kind: Int,
    ) {
        require(offset >= 0 && length in HEADER_BYTES + 1..MAX_MESSAGE_BYTES && offset <= bytes.size - length)
        val b = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        require(
            (b.short.toInt() and 65535) == length - HEADER_BYTES && (b.short.toInt() and 65535) == kind &&
                (b.short.toInt() and 65535) == 87 && (b.short.toInt() and 65535) == 3,
        ) { "unsupported bounded header" }
    }

    private fun known(message: Message) = AdmissionMessageFormat.verifyKnownFields(message)

    private fun text(value: String): String = AdmissionMessageFormat.text(value)

    companion object {
        const val HEADER_BYTES = 8
        const val MAX_BODY_BYTES = 4_096 - HEADER_BYTES
        const val MAX_MESSAGE_BYTES = HEADER_BYTES + MAX_BODY_BYTES
        const val MAX_MANIFEST_BYTES = 3_072
        private const val COMMAND_KIND = 20
        private const val RESPONSE_KIND = 21
    }
}
