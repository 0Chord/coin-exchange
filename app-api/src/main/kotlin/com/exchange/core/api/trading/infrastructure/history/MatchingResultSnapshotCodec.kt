package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingNotExecutedReason
import com.exchange.core.trading.MatchingOrderEffect
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.TradingMarketContext
import java.io.DataInputStream
import java.io.DataOutputStream

/** 매칭 v1의 승인·전체 결과 바이트를 두 역할의 snapshot에서 같은 순서로 사용한다. */
internal class MatchingResultSnapshotCodec {
    private val reservations = TradingHistoryCodec(MAX_RESERVATION_BYTES)

    fun writeResult(
        output: DataOutputStream,
        value: MatchingResultEnvelope,
    ) = output.result(value)

    fun readResult(
        input: DataInputStream,
        stream: FundsDispatchStream,
        context: TradingMarketContext,
        maximumEvents: Int,
    ): MatchingResultEnvelope = input.result(stream, context, maximumEvents)

    fun writeDispatch(
        output: DataOutputStream,
        value: FundsOrderDispatch,
    ) = output.dispatch(value)

    fun readDispatch(
        input: DataInputStream,
        streamFor: (MarketId) -> FundsDispatchStream,
        contextFor: (MarketId) -> TradingMarketContext,
    ): FundsOrderDispatch = input.dispatch(streamFor, contextFor)

    private fun DataOutputStream.result(value: MatchingResultEnvelope) {
        writeLong(value.resultSequence)
        dispatch(value.input)
        writeInt(value.outcome.ordinal)
        writeInt(value.reason?.ordinal ?: -1)
        writeLong(value.engineSequenceBefore)
        writeLong(value.engineSequenceAfter)
        writeInt(value.events.size)
        for (event in value.events) {
            when (event) {
                is TradeExecuted -> {
                    writeInt(1)
                    writeLong(event.engineSequence)
                    text(event.makerOrderId.value)
                    text(event.takerOrderId.value)
                    text(event.makerUserId.value)
                    text(event.takerUserId.value)
                    writeInt(event.side.ordinal)
                    writeLong(event.price.value)
                    writeLong(event.quantity.value)
                }

                is OrderEnteredBook -> {
                    writeInt(2)
                    writeLong(event.engineSequence)
                    text(event.orderId.value)
                    text(event.userId.value)
                    writeInt(event.side.ordinal)
                    writeLong(event.price.value)
                    writeLong(event.remainingQuantity.value)
                }

                else -> {
                    throw IllegalArgumentException("matching intake cannot snapshot cancellation events")
                }
            }
        }
        writeInt(value.effects.size)
        for (effect in value.effects) {
            dispatch(effect.origin)
            writeLong(effect.beforeQuantity.value)
            writeLong(effect.afterQuantity.value)
            writeInt(effect.disposition.ordinal)
        }
    }

    private fun DataInputStream.result(
        stream: FundsDispatchStream,
        context: TradingMarketContext,
        maximumEvents: Int,
    ): MatchingResultEnvelope {
        val market = stream.marketId

        fun expectedStream(actual: MarketId): FundsDispatchStream {
            require(actual == market) { "result snapshot dispatch market differs" }
            return stream
        }
        val sequence = readLong()
        val original = dispatch(::expectedStream) { context }
        val outcome = enum<MatchingOutcome>()
        val reasonCode = readInt()
        val reason =
            if (reasonCode ==
                -1
            ) {
                null
            } else {
                requireNotNull(MatchingNotExecutedReason.entries.getOrNull(reasonCode)) { "invalid matching rejection reason" }
            }
        val before = readLong()
        val after = readLong()
        val events =
            List(count(maximumEvents)) {
                when (readInt()) {
                    1 -> {
                        TradeExecuted(
                            market,
                            readLong(),
                            OrderId(text()),
                            OrderId(text()),
                            UserId(text()),
                            UserId(text()),
                            enum(),
                            Price(readLong()),
                            Quantity(readLong()),
                        )
                    }

                    2 -> {
                        OrderEnteredBook(
                            market,
                            readLong(),
                            OrderId(text()),
                            UserId(text()),
                            enum(),
                            Price(readLong()),
                            Quantity(readLong()),
                        )
                    }

                    else -> {
                        throw IllegalArgumentException("invalid matching event type")
                    }
                }
            }
        val effects =
            List(count(Math.addExact(maximumEvents, 1))) {
                MatchingOrderEffect(dispatch(::expectedStream) { context }, Quantity(readLong()), Quantity(readLong()), enum())
            }
        return MatchingResultEnvelope(
            MatchingResultStream(stream.matchingLineage, stream.fundsLineage, market, stream.ownershipEpoch, stream.configurationVersion),
            sequence,
            original,
            outcome,
            reason,
            before,
            after,
            events,
            effects,
        )
    }

    private fun DataOutputStream.dispatch(value: FundsOrderDispatch) {
        text(value.stream.marketId.value)
        writeLong(value.dispatchSequence)
        writeLong(value.admissionSequence)
        writeLong(value.marketAdmissionSequence)
        val order = value.order
        text(order.marketId.value)
        text(order.orderId.value)
        text(order.userId.value)
        writeInt(order.side.ordinal)
        writeInt(order.orderType.ordinal)
        writeInt(order.timeInForce.ordinal)
        writeLong(order.price.value)
        writeLong(order.quantity.value)
        blob(reservations.encodeReservation(value.reservation), MAX_RESERVATION_BYTES)
    }

    private fun DataInputStream.dispatch(
        streamFor: (MarketId) -> FundsDispatchStream,
        contextFor: (MarketId) -> TradingMarketContext,
    ): FundsOrderDispatch {
        val market = MarketId(text())
        val stream = streamFor(market)
        val dispatch = readLong()
        val admission = readLong()
        val marketAdmission = readLong()
        val order =
            OrderAdmissionInput(
                MarketId(text()),
                OrderId(text()),
                UserId(text()),
                enum(),
                enum(),
                enum(),
                Price(readLong()),
                Quantity(readLong()),
            )
        val reservation = reservations.decodeReservation(blob(MAX_RESERVATION_BYTES))
        return FundsOrderDispatch(stream, dispatch, admission, marketAdmission, order, reservation, contextFor(market))
    }

    private inline fun <reified E : Enum<E>> DataInputStream.enum(): E =
        enumValues<E>().getOrNull(readInt())
            ?: throw IllegalArgumentException("invalid matching snapshot enum")

    private fun DataOutputStream.text(value: String) = blob(value.toByteArray(Charsets.UTF_8), MAX_TEXT_BYTES)

    private fun DataInputStream.text(): String {
        val bytes = blob(MAX_TEXT_BYTES)
        return bytes.toString(Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) }
    }

    private fun DataOutputStream.blob(
        bytes: ByteArray,
        maximum: Int,
    ) {
        require(bytes.size in 1..maximum)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.blob(maximum: Int): ByteArray {
        val length = readInt()
        require(length in 1..maximum) { "matching snapshot value length exceeds its bound" }
        return ByteArray(length).also(::readFully)
    }

    private fun DataInputStream.count(maximum: Int): Int =
        readInt().also {
            require(it in 0..minOf(maximum, MAX_ROWS)) { "matching snapshot row count exceeds its bound" }
        }

    companion object {
        private const val MAX_TEXT_BYTES = 1_024
        private const val MAX_RESERVATION_BYTES = 64 * 1_024
        private const val MAX_ROWS = 1_000_000
    }
}
