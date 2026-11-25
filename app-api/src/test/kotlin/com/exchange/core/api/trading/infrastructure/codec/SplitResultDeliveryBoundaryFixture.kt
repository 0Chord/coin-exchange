package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMatchingEffect
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMatchingEvent
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMatchingOutcome
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMatchingResult
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitOrderDisposition
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitOrderEnteredBook
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitResultCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitResultDirection
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitResultStream
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitTradeExecuted
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
import com.exchange.core.matching.TradeExecuted
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
import com.google.protobuf.CodedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.exchange.core.api.trading.infrastructure.codec.generated.Side as WireSide

/** 기본 논리 한도와 별개로 정확한 전송 경계를 검사하는 전체 원문이다. 제품 수용 한도를 바꾸지 않는다. */
internal object SplitResultDeliveryBoundaryFixture {
    data class Frame(
        val correlationId: Long,
        val result: MatchingResultEnvelope,
        val bytes: ByteArray,
    )

    private val funds = ClusterLineage(1, 2)
    private val matching = ClusterLineage(3, 4)
    private val fees =
        TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
    private val context = TradingMarketContext(MarketDefinition(MarketId("boundary-X-Q"), AssetId("X"), AssetId("Q"), 0), fees, 0)
    private val stream = MatchingResultStream(matching, funds, context.market.marketId, 1, 1)
    private val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))
    private val fieldsByVersion = mapOf(1 to SplitOrderDeliveryCodec(), 2 to SplitOrderDeliveryCodec(inputContractVersion = 2))

    fun frame(
        targetFrameBytes: Int,
        inputContractVersion: Int = 1,
    ): Frame {
        require(inputContractVersion in 1..2)
        require(targetFrameBytes in 1_000_000..SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + 1)
        var low = 2
        var high = SplitResultDeliveryCodec.MAX_RESULT_EVENTS - 1
        while (low < high) {
            val middle = (low + high) / 2
            val size = command(1, wire(result(middle, inputContractVersion))).serializedSize + SplitResultDeliveryCodec.HEADER_BYTES
            if (size < targetFrameBytes) low = middle + 1 else high = middle
        }
        val original = result(low, inputContractVersion)
        val wire = wire(original)
        val firstChoices = choices(original, wire, 0)
        val secondChoices = choices(original, wire, 1).associateBy { it.removedBytes }
        // correlation의 varint 폭도1~9바이트 범위에서 고른다. 업무 순번은 그대로다.
        for (width in 1..9) {
            val correlation = if (width == 1) 1L else 1L shl (7 * (width - 1))
            for (lengthWidth in 1..4) {
                val targetResultBytes =
                    targetFrameBytes - SplitResultDeliveryCodec.HEADER_BYTES -
                        CodedOutputStream.computeInt64Size(1, correlation) - CodedOutputStream.computeTagSize(11) - lengthWidth
                if (CodedOutputStream.computeUInt32SizeNoTag(targetResultBytes) != lengthWidth) continue
                val removed = wire.serializedSize - targetResultBytes
                for (first in firstChoices) {
                    val second = secondChoices[removed - first.removedBytes] ?: continue
                    val fitted =
                        wire
                            .toBuilder()
                            .setEvents(0, first.wireEvent)
                            .setEffects(1, first.wireEffect)
                            .setEvents(1, second.wireEvent)
                            .setEffects(2, second.wireEffect)
                            .build()
                    val root = command(correlation, fitted)
                    check(root.serializedSize + SplitResultDeliveryCodec.HEADER_BYTES == targetFrameBytes)
                    val events =
                        original.events.toMutableList().also {
                            it[0] = first.event
                            it[1] = second.event
                        }
                    val effects =
                        original.effects.toMutableList().also {
                            it[1] = it[1].copy(origin = first.origin)
                            it[2] = it[2].copy(origin = second.origin)
                        }
                    val result =
                        MatchingResultEnvelope(
                            stream,
                            original.resultSequence,
                            original.input,
                            MatchingOutcome.MATCHED,
                            null,
                            original.engineSequenceBefore,
                            original.engineSequenceAfter,
                            events,
                            effects,
                        )
                    val bytes = ByteArray(targetFrameBytes)
                    ByteBuffer
                        .wrap(bytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(89)
                        .putShort(inputContractVersion.toShort())
                        .putShort(1)
                        .putShort(0)
                        .putInt(root.serializedSize)
                    root.toByteArray().copyInto(bytes, SplitResultDeliveryCodec.HEADER_BYTES)
                    return Frame(correlation, result, bytes)
                }
            }
        }
        error("cannot fit exact result frame boundary $targetFrameBytes")
    }

    private data class Choice(
        val origin: FundsOrderDispatch,
        val event: TradeExecuted,
        val wireEvent: SplitMatchingEvent,
        val wireEffect: SplitMatchingEffect,
        val removedBytes: Int,
    )

    private fun choices(
        result: MatchingResultEnvelope,
        wire: SplitMatchingResult,
        index: Int,
    ): List<Choice> {
        val originalEffect = result.effects[index + 1]
        val event = result.events[index] as TradeExecuted
        val originalBytes =
            CodedOutputStream.computeMessageSize(10, wire.getEvents(index)) +
                CodedOutputStream.computeMessageSize(11, wire.getEffects(index + 1))
        return (1..256).map { length ->
            val user = UserId("u".repeat(length))
            val origin =
                originalEffect.origin.copy(
                    order = originalEffect.origin.order.copy(userId = user),
                    reservation = originalEffect.origin.reservation.copy(userId = user),
                )
            val changedEvent = event.copy(makerUserId = user)
            val wireEvent = trade(changedEvent)
            val wireEffect = effect(originalEffect.copy(origin = origin))
            val changedBytes = CodedOutputStream.computeMessageSize(10, wireEvent) + CodedOutputStream.computeMessageSize(11, wireEffect)
            Choice(origin, changedEvent, wireEvent, wireEffect, originalBytes - changedBytes)
        }
    }

    private fun command(
        correlationId: Long,
        result: SplitMatchingResult,
    ): SplitResultCommand =
        SplitResultCommand
            .newBuilder()
            .setCorrelationId(correlationId)
            .setApply(result)
            .build()

    private fun result(
        makers: Int,
        inputContractVersion: Int,
    ): MatchingResultEnvelope {
        val taker = input(makers + 1L, Side.BUY, makers + 1L, inputContractVersion)
        val origins = (1..makers).map { input(it.toLong(), Side.SELL, 1, inputContractVersion) }
        val events =
            origins.mapIndexed { index, maker ->
                TradeExecuted(
                    stream.marketId,
                    makers + index + 1L,
                    maker.order.orderId,
                    taker.order.orderId,
                    maker.order.userId,
                    taker.order.userId,
                    Side.BUY,
                    Price(100),
                    Quantity(1),
                )
            } +
                OrderEnteredBook(
                    stream.marketId,
                    2L * makers + 1,
                    taker.order.orderId,
                    taker.order.userId,
                    Side.BUY,
                    Price(100),
                    Quantity(1),
                )
        val effects =
            listOf(MatchingOrderEffect(taker, taker.order.quantity, Quantity(1), MatchingOrderDisposition.RESTING)) +
                origins.map { MatchingOrderEffect(it, Quantity(1), Quantity.ZERO, MatchingOrderDisposition.TERMINAL) }
        return MatchingResultEnvelope(
            stream,
            makers + 1L,
            taker,
            MatchingOutcome.MATCHED,
            null,
            makers.toLong(),
            2L * makers + 1,
            events,
            effects,
        )
    }

    private fun input(
        sequence: Long,
        side: Side,
        quantity: Long,
        inputContractVersion: Int,
    ): FundsOrderDispatch {
        val order =
            OrderAdmissionInput(
                stream.marketId,
                TradingIdentifiers.order(funds, sequence),
                UserId("u".repeat(256)),
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
                reservations.calculate(context.market, side, order.price, order.quantity, fees),
                fees,
            )
        return FundsOrderDispatch(
            FundsDispatchStream(funds, matching, stream.marketId, 1, 1),
            sequence,
            sequence,
            sequence,
            order,
            reservation,
            context,
            inputContractVersion = inputContractVersion,
        )
    }

    private fun wire(result: MatchingResultEnvelope): SplitMatchingResult {
        val fields = fieldsByVersion.getValue(result.formatVersion)
        val builder =
            SplitMatchingResult
                .newBuilder()
                .setFormatVersion(result.formatVersion)
                .setStream(
                    SplitResultStream
                        .newBuilder()
                        .setMatchingLineage(fields.lineage(matching))
                        .setFundsLineage(fields.lineage(funds))
                        .setMarketId(stream.marketId.value)
                        .setDirection(SplitResultDirection.SPLIT_MATCHING_TO_FUNDS)
                        .setOwnershipEpoch(1)
                        .setConfigurationVersion(1),
                ).setResultSequence(result.resultSequence)
                .setInput(fields.dispatch(result.input))
                .setOutcome(SplitMatchingOutcome.SPLIT_MATCHED)
                .setEngineSequenceBefore(result.engineSequenceBefore)
                .setEngineSequenceAfter(result.engineSequenceAfter)
                .setEventCount(result.eventCount)
        result.events.forEach { event ->
            builder.addEvents(
                when (event) {
                    is TradeExecuted -> {
                        trade(event)
                    }

                    is OrderEnteredBook -> {
                        SplitMatchingEvent
                            .newBuilder()
                            .setEntered(
                                SplitOrderEnteredBook
                                    .newBuilder()
                                    .setMarketId(event.marketId.value)
                                    .setEngineSequence(event.engineSequence)
                                    .setOrderId(event.orderId.value)
                                    .setUserId(event.userId.value)
                                    .setSide(WireSide.BUY)
                                    .setPrice(event.price.value)
                                    .setRemainingQuantity(event.remainingQuantity.value),
                            ).build()
                    }

                    else -> {
                        error("unexpected fixture event")
                    }
                },
            )
        }
        result.effects.forEach { builder.addEffects(effect(it)) }
        return builder.build()
    }

    private fun trade(event: TradeExecuted): SplitMatchingEvent =
        SplitMatchingEvent
            .newBuilder()
            .setTrade(
                SplitTradeExecuted
                    .newBuilder()
                    .setMarketId(event.marketId.value)
                    .setEngineSequence(event.engineSequence)
                    .setMakerOrderId(event.makerOrderId.value)
                    .setTakerOrderId(event.takerOrderId.value)
                    .setMakerUserId(event.makerUserId.value)
                    .setTakerUserId(event.takerUserId.value)
                    .setSide(WireSide.BUY)
                    .setPrice(event.price.value)
                    .setQuantity(event.quantity.value),
            ).build()

    private fun effect(effect: MatchingOrderEffect): SplitMatchingEffect =
        SplitMatchingEffect
            .newBuilder()
            .setOrigin(
                fieldsByVersion.getValue(effect.origin.inputContractVersion).dispatch(effect.origin),
            ).setBeforeQuantity(effect.beforeQuantity.value)
            .setAfterQuantity(effect.afterQuantity.value)
            .setDisposition(
                if (effect.disposition == MatchingOrderDisposition.RESTING) {
                    SplitOrderDisposition.SPLIT_RESTING
                } else {
                    SplitOrderDisposition.SPLIT_TERMINAL
                },
            ).build()
}
