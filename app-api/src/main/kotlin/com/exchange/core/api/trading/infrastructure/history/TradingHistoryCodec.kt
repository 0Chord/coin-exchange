package com.exchange.core.api.trading.infrastructure.history

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
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.matching.CancelOrderCommand
import com.exchange.core.matching.MatchingCommand
import com.exchange.core.matching.MatchingCommandResult
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementPlan
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionCandidate
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.OrderSettlementCandidate
import com.exchange.core.trading.SettlementExecution
import com.exchange.core.trading.TradingHistoryValues
import com.exchange.core.trading.TradingState
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Objects
import java.util.zip.CRC32C

/** Versioned, bounded values; JVM serialization and constructor bypass are deliberately absent. */
internal class TradingHistoryCodec(
    private val maximumValueBytes: Int,
) {
    init {
        require(maximumValueBytes in 1_024..64 * 1_024 * 1_024)
    }

    fun encodeRequest(value: OrderAdmissionState.RequestRecord): ByteArray = encode(1) { request(value) }

    fun decodeRequest(bytes: ByteArray): OrderAdmissionState.RequestRecord = decode(bytes, 1) { request() }

    fun encodeAdmission(value: OrderAdmissionState.Record): ByteArray = encode(2) { record(value) }

    fun decodeAdmission(bytes: ByteArray): OrderAdmissionState.Record = decode(bytes, 2) { record() }

    fun encodeCompletion(value: TradingState.Completion): ByteArray = encode(3) { completion(value) }

    fun decodeCompletion(bytes: ByteArray): TradingState.Completion = decode(bytes, 3) { completion() }

    fun encodePreparation(value: OrderAdmissionState.PreparationRecord): ByteArray = encode(4) { preparation(value) }

    fun decodePreparation(bytes: ByteArray): OrderAdmissionState.PreparationRecord = decode(bytes, 4) { preparation() }

    fun encodeReservation(value: OrderReservation): ByteArray = encode(5) { reservation(value) }

    fun decodeReservation(bytes: ByteArray): OrderReservation = decode(bytes, 5) { reservation() }

    fun encodeTransaction(value: LedgerTransaction): ByteArray = encode(6) { transaction(value) }

    fun decodeTransaction(bytes: ByteArray): LedgerTransaction = decode(bytes, 6) { transaction() }

    private fun encode(
        kind: Int,
        write: Writer.() -> Unit,
    ): ByteArray {
        val frame = FrameBuffer(maximumValueBytes)
        Writer(DataOutputStream(frame)).write()
        return frame.finish(kind)
    }

    /** Each encode owns its buffer; reserve the header and copy only the completed frame. */
    private class FrameBuffer(
        maximumValueBytes: Int,
    ) : OutputStream() {
        private val maximumBodyEnd = maximumValueBytes - Int.SIZE_BYTES
        private var bytes = ByteArray(minOf(maximumBodyEnd, 1_024))
        private var position = HEADER_BYTES

        override fun write(value: Int) {
            reserve(1)
            bytes[position++] = value.toByte()
        }

        override fun write(
            value: ByteArray,
            offset: Int,
            length: Int,
        ) {
            Objects.checkFromIndexSize(offset, length, value.size)
            reserve(length)
            value.copyInto(bytes, position, offset, offset + length)
            position += length
        }

        private fun reserve(length: Int) {
            check(length <= maximumBodyEnd - position) { "history value exceeds its byte limit" }
            val required = position + length
            if (required > bytes.size) {
                bytes = bytes.copyOf(minOf(maximumBodyEnd, maxOf(required, bytes.size * 2)))
            }
        }

        fun finish(kind: Int): ByteArray {
            val bodyLength = position - HEADER_BYTES
            val checksum = CRC32C().apply { update(bytes, HEADER_BYTES, bodyLength) }.value.toInt()
            val frame = bytes.copyOf(position + Int.SIZE_BYTES)
            putInt(frame, 0, MAGIC)
            putInt(frame, 4, VERSION)
            putInt(frame, 8, kind)
            putInt(frame, 12, bodyLength)
            putInt(frame, position, checksum)
            return frame
        }

        private fun putInt(
            target: ByteArray,
            offset: Int,
            value: Int,
        ) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        }
    }

    private fun <T> decode(
        bytes: ByteArray,
        kind: Int,
        read: Reader.() -> T,
    ): T {
        check(bytes.size in FRAME_BYTES..maximumValueBytes) { "invalid history value length" }
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            check(input.readInt() == MAGIC && input.readInt() == VERSION && input.readInt() == kind) { "unsupported history frame" }
            val length = input.readInt()
            check(length == bytes.size - FRAME_BYTES) { "invalid history payload length" }
            val crc = CRC32C().apply { update(bytes, HEADER_BYTES, length) }.value.toInt()
            input.skipNBytes(length.toLong())
            check(input.readInt() == crc) { "history payload checksum mismatch" }
            val body = ByteArrayInputStream(bytes, HEADER_BYTES, length)
            val result = Reader(DataInputStream(body)).read()
            check(body.available() == 0) { "history payload has trailing fields" }
            return result
        } catch (error: Exception) {
            throw IllegalStateException("invalid trading history value", error)
        }
    }

    private class Writer(
        val out: DataOutputStream,
    ) {
        private val textBuffer = ByteArray(1_024)

        fun text(value: String) {
            out.writeInt(value.length)
            // Preserve every UTF-16 code unit, including domain inputs containing unpaired surrogates.
            // Bulk writes also check the value bound once per chunk, not once per encoded byte.
            var offset = 0
            while (offset < value.length) {
                val count = minOf(value.length - offset, textBuffer.size / Char.SIZE_BYTES)
                for (index in 0 until count) {
                    val code = value[offset + index].code
                    textBuffer[index * 2] = (code ushr 8).toByte()
                    textBuffer[index * 2 + 1] = code.toByte()
                }
                out.write(textBuffer, 0, count * Char.SIZE_BYTES)
                offset += count
            }
        }

        fun enum(value: Enum<*>) = text(value.name)

        fun time(value: Instant) {
            out.writeLong(value.epochSecond)
            out.writeInt(value.nano)
        }

        fun <T> list(
            values: List<T>,
            write: (T) -> Unit,
        ) {
            out.writeInt(values.size)
            values.forEach(write)
        }

        fun amount(value: Amount) = out.writeLong(value.value)

        fun balance(value: Balance) {
            text(value.userId.value)
            text(value.assetId.value)
            amount(value.available)
            amount(value.hold)
        }

        fun policy(value: TradingFeePolicySnapshot) {
            enum(value.productType)
            enum(value.feeTier)
            out.writeLong(value.scheduleVersion)
            out.writeLong(value.feeRates.makerFeeRate.partsPerMillion)
            out.writeLong(value.feeRates.takerFeeRate.partsPerMillion)
        }

        fun market(value: MarketDefinition) {
            text(value.marketId.value)
            text(value.baseAssetId.value)
            text(value.quoteAssetId.value)
            out.writeInt(value.baseAssetScale)
            out.writeLong(value.priceTick)
            out.writeLong(value.quantityStep)
        }

        fun order(value: OrderAdmissionInput) {
            text(value.marketId.value)
            text(value.orderId.value)
            text(value.userId.value)
            enum(value.side)
            enum(value.orderType)
            enum(value.timeInForce)
            out.writeLong(value.price.value)
            out.writeLong(value.quantity.value)
        }

        fun reservation(value: OrderReservation) {
            text(value.marketId.value)
            text(value.orderId.value)
            text(value.userId.value)
            enum(value.side)
            text(value.assetId.value)
            out.writeLong(value.limitPrice.value)
            out.writeLong(value.initialQuantity.value)
            out.writeLong(value.remainingQuantity.value)
            amount(value.reservedAmount)
            amount(value.remainingAmount)
            policy(value.feePolicySnapshot)
            amount(value.initialFeeReserveAmount)
            amount(value.remainingFeeReserveAmount)
            enum(value.status)
            out.writeLong(value.feeRemainder.numerator)
            out.writeBoolean(value.releasedAmount != null)
            value.releasedAmount?.let(::amount)
        }

        fun transaction(value: LedgerTransaction) {
            text(value.ledgerTransactionId)
            text(value.sourceEventId)
            enum(value.transactionType)
            time(value.occurredAt)
            list(value.postings) {
                text(it.accountId)
                text(it.assetId.value)
                enum(it.side)
                amount(it.amount)
            }
        }

        fun candidate(value: OrderAdmissionCandidate) {
            order(value.order)
            market(value.market)
            policy(value.feePolicySnapshot)
            balance(value.previousBalance)
            balance(value.nextBalance)
            reservation(value.reservation)
            transaction(value.reserveTransaction)
        }

        fun record(value: OrderAdmissionState.Record) {
            out.writeLong(value.sequence)
            candidate(value.candidate)
        }

        fun result(value: OrderAdmissionState.Result) {
            when (value) {
                is OrderAdmissionState.Result.Accepted -> {
                    out.writeByte(1)
                    record(value.record)
                }

                is OrderAdmissionState.Result.Rejected -> {
                    out.writeByte(2)
                    enum(value.reason)
                    out.writeBoolean(value.insufficientBalance != null)
                    value.insufficientBalance?.let {
                        text(it.assetId.value)
                        amount(it.available)
                        amount(it.required)
                        amount(it.shortfall)
                    }
                }
            }
        }

        fun request(value: OrderAdmissionState.RequestRecord) {
            text(value.requestId)
            order(value.order)
            market(value.market)
            policy(value.feePolicySnapshot)
            text(value.ledgerTransactionId)
            time(value.occurredAt)
            result(value.result)
        }

        fun preparation(value: OrderAdmissionState.PreparationRecord) {
            val original = value.result
            text(original.opening.preparationId)
            text(original.opening.userId.value)
            text(original.opening.assetId.value)
            amount(original.opening.amount)
            text(original.ledgerTransactionId)
            time(original.occurredAt)
            out.writeBoolean(original.alreadyPrepared)
            transaction(value.transaction)
        }

        fun execution(value: SettlementExecution) {
            text(value.marketId.value)
            out.writeLong(value.engineSequence)
            text(value.makerOrderId.value)
            text(value.takerOrderId.value)
            text(value.makerUserId.value)
            text(value.takerUserId.value)
            enum(value.side)
            out.writeLong(value.price.value)
            out.writeLong(value.quantity.value)
            text(value.ledgerTransactionId)
            time(value.occurredAt)
        }

        fun plan(value: OrderFillSettlementPlan) {
            reservation(value.updatedReservation)
            amount(value.reservedAmountToReduce)
            amount(value.holdAmountToConsume)
            amount(value.holdAmountToRelease)
            text(value.creditAssetId.value)
            amount(value.creditAmount)
            text(value.feeAssetId.value)
            amount(value.actualFeeAmount)
        }

        fun settlement(value: OrderSettlementCandidate) {
            order(value.order)
            market(value.market)
            list(value.settlements) {
                execution(it.execution)
                plan(it.makerPlan)
                plan(it.takerPlan)
                transaction(it.transaction)
            }
            list(value.balanceChanges) {
                balance(it.previous)
                balance(it.next)
            }
            list(value.reservationChanges) {
                reservation(it.previous)
                reservation(it.next)
            }
        }

        fun command(value: MatchingCommand) {
            when (value) {
                is SubmitOrderCommand -> {
                    out.writeByte(1)
                    order(
                        OrderAdmissionInput(
                            value.marketId,
                            value.orderId,
                            value.userId,
                            value.side,
                            value.orderType,
                            value.timeInForce,
                            value.price,
                            value.quantity,
                        ),
                    )
                }

                is CancelOrderCommand -> {
                    out.writeByte(2)
                    text(value.marketId.value)
                    text(value.orderId.value)
                    text(value.userId.value)
                }
            }
        }

        fun event(value: MatchingEvent) {
            out.writeByte(
                when (value) {
                    is TradeExecuted -> 1
                    is OrderEnteredBook -> 2
                    is OrderCancelled -> 3
                    is OrderCancelRejected -> 4
                },
            )
            text(value.marketId.value)
            out.writeLong(value.engineSequence)
            when (value) {
                is TradeExecuted -> {
                    text(value.makerOrderId.value)
                    text(value.takerOrderId.value)
                    text(value.makerUserId.value)
                    text(value.takerUserId.value)
                    enum(value.side)
                    out.writeLong(value.price.value)
                    out.writeLong(value.quantity.value)
                }

                is OrderEnteredBook -> {
                    text(value.orderId.value)
                    text(value.userId.value)
                    enum(value.side)
                    out.writeLong(value.price.value)
                    out.writeLong(value.remainingQuantity.value)
                }

                is OrderCancelled -> {
                    text(value.orderId.value)
                    text(value.userId.value)
                    out.writeLong(value.remainingQuantity.value)
                }

                is OrderCancelRejected -> {
                    text(value.orderId.value)
                    text(value.userId.value)
                    text(value.reason)
                }
            }
        }

        fun matching(value: MatchingCommandResult) {
            text(value.commandId)
            command(value.command)
            out.writeInt(value.expectedEventCount)
            out.writeLong(value.firstEngineSequence)
            out.writeLong(value.lastEngineSequence)
            list(value.events, ::event)
        }

        fun completion(value: TradingState.Completion) {
            record(value.admission)
            text(value.executionId)
            time(value.occurredAt)
            matching(value.matchingResult)
            settlement(value.settlement)
        }
    }

    private class Reader(
        val input: DataInputStream,
    ) {
        fun text(): String {
            val count = input.readInt()
            check(count >= 0 && count <= input.available() / Char.SIZE_BYTES) { "invalid history string length" }
            return CharArray(count) { input.readChar() }.concatToString()
        }

        inline fun <reified T : Enum<T>> enum(): T = enumValueOf(text())

        fun time(): Instant {
            val seconds = input.readLong()
            val nanos = input.readInt()
            check(nanos in 0..999_999_999) { "invalid history instant nanos" }
            return Instant.ofEpochSecond(seconds, nanos.toLong())
        }

        fun flag(): Boolean =
            when (input.readUnsignedByte()) {
                0 -> false
                1 -> true
                else -> error("invalid history flag")
            }

        fun <T> list(read: () -> T): List<T> {
            val count = input.readInt()
            check(count >= 0 && count <= input.available()) { "invalid history list length" }
            val values = ArrayList<T>(minOf(count, 16))
            repeat(count) { values.add(read()) }
            return values
        }

        fun amount() = Amount(input.readLong())

        fun balance() = Balance(UserId(text()), AssetId(text()), amount(), amount())

        fun policy() =
            TradingFeePolicySnapshot(
                enum<FeeProductType>(),
                enum<FeeTier>(),
                input.readLong(),
                MakerTakerFeeRates(FeeRate(input.readLong()), FeeRate(input.readLong())),
            )

        fun market() =
            MarketDefinition(MarketId(text()), AssetId(text()), AssetId(text()), input.readInt(), input.readLong(), input.readLong())

        fun order() =
            OrderAdmissionInput(
                MarketId(text()),
                OrderId(text()),
                UserId(text()),
                enum<Side>(),
                enum<OrderType>(),
                enum<TimeInForce>(),
                Price(input.readLong()),
                Quantity(input.readLong()),
            )

        fun reservation() =
            OrderReservation(
                MarketId(text()),
                OrderId(text()),
                UserId(text()),
                enum<Side>(),
                AssetId(text()),
                Price(input.readLong()),
                Quantity(input.readLong()),
                Quantity(input.readLong()),
                amount(),
                amount(),
                policy(),
                amount(),
                amount(),
                enum<OrderReservationStatus>(),
                FeeRemainder(input.readLong()),
                if (flag()) amount() else null,
            )

        fun transaction() =
            LedgerTransaction(
                text(),
                text(),
                enum<LedgerTransactionType>(),
                time(),
                list {
                    LedgerPosting(text(), AssetId(text()), enum<LedgerPostingSide>(), amount())
                },
            )

        fun candidate() =
            TradingHistoryValues.admissionCandidate(order(), market(), policy(), balance(), balance(), reservation(), transaction())

        fun record() = TradingHistoryValues.admissionRecord(input.readLong(), candidate())

        fun result(): OrderAdmissionState.Result =
            when (input.readUnsignedByte()) {
                1 -> {
                    OrderAdmissionState.Result.Accepted(record())
                }

                2 -> {
                    OrderAdmissionState.Result.Rejected(
                        enum<OrderAdmissionResult.Reason>(),
                        if (flag()) OrderAdmissionResult.InsufficientBalance(AssetId(text()), amount(), amount(), amount()) else null,
                    )
                }

                else -> {
                    error("unknown admission result")
                }
            }

        fun request() = TradingHistoryValues.requestRecord(text(), order(), market(), policy(), text(), time(), result())

        fun preparation(): OrderAdmissionState.PreparationRecord {
            val result = OpeningBalanceResult(OpeningBalance(text(), UserId(text()), AssetId(text()), amount()), text(), time(), flag())
            return TradingHistoryValues.preparationRecord(result, transaction())
        }

        fun execution() =
            SettlementExecution(
                MarketId(text()),
                input.readLong(),
                OrderId(text()),
                OrderId(text()),
                UserId(text()),
                UserId(text()),
                enum<Side>(),
                Price(input.readLong()),
                Quantity(input.readLong()),
                text(),
                time(),
            )

        fun plan() =
            OrderFillSettlementPlan(reservation(), amount(), amount(), amount(), AssetId(text()), amount(), AssetId(text()), amount())

        fun settlement() =
            TradingHistoryValues.settlementCandidate(
                order(),
                market(),
                list { OrderSettlementCandidate.Settlement(execution(), plan(), plan(), transaction()) },
                list { OrderSettlementCandidate.BalanceChange(balance(), balance()) },
                list { OrderSettlementCandidate.ReservationChange(reservation(), reservation()) },
            )

        fun command(): MatchingCommand =
            when (input.readUnsignedByte()) {
                1 -> {
                    order().let {
                        SubmitOrderCommand(
                            it.marketId,
                            it.orderId,
                            it.userId,
                            it.side,
                            it.orderType,
                            it.timeInForce,
                            it.price,
                            it.quantity,
                        )
                    }
                }

                2 -> {
                    CancelOrderCommand(MarketId(text()), OrderId(text()), UserId(text()))
                }

                else -> {
                    error("unknown matching command")
                }
            }

        fun event(): MatchingEvent {
            val kind = input.readUnsignedByte()
            val market = MarketId(text())
            val sequence = input.readLong()
            return when (kind) {
                1 -> {
                    TradeExecuted(
                        market,
                        sequence,
                        OrderId(text()),
                        OrderId(text()),
                        UserId(text()),
                        UserId(text()),
                        enum<Side>(),
                        Price(input.readLong()),
                        Quantity(input.readLong()),
                    )
                }

                2 -> {
                    OrderEnteredBook(
                        market,
                        sequence,
                        OrderId(text()),
                        UserId(text()),
                        enum<Side>(),
                        Price(input.readLong()),
                        Quantity(input.readLong()),
                    )
                }

                3 -> {
                    OrderCancelled(market, sequence, OrderId(text()), UserId(text()), Quantity(input.readLong()))
                }

                4 -> {
                    OrderCancelRejected(market, sequence, OrderId(text()), UserId(text()), text())
                }

                else -> {
                    error("unknown matching event")
                }
            }
        }

        fun matching() =
            TradingHistoryValues.matchingResult(text(), command(), input.readInt(), input.readLong(), input.readLong(), list(::event))

        fun completion() = TradingHistoryValues.completion(record(), text(), time(), matching(), settlement())
    }

    private companion object {
        const val MAGIC = 0x54484831
        const val VERSION = 1
        const val HEADER_BYTES = 16
        const val FRAME_BYTES = HEADER_BYTES + Int.SIZE_BYTES
    }
}
