package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.Side
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.SettledTrade
import com.exchange.core.trading.SettlementFact
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant

/** 확정한 분개와 수수료를 다시 계산하지 않고 스냅샷·조회 전송에서 읽고 쓴다. */
internal object SettlementFactCodec {
    fun encode(fact: SettlementFact): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { write(it, fact) }
        return bytes.toByteArray().also { require(it.size.toLong() <= fact.canonicalBytes) }
    }

    fun decode(bytes: ByteArray): SettlementFact {
        require(bytes.size.toLong() in 1..SettlementFact.MAX_CANONICAL_BYTES)
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val fact = read(input)
                require(input.read() == -1 && bytes.size.toLong() <= fact.canonicalBytes)
                fact
            }
        } catch (failure: IOException) {
            throw IllegalArgumentException("invalid settlement fact", failure)
        }
    }

    fun write(
        output: DataOutputStream,
        fact: SettlementFact,
    ) {
        val stream = fact.stream
        output.lineage(stream.matchingLineage)
        output.lineage(stream.fundsLineage)
        output.text(stream.marketId.value)
        output.writeLong(stream.ownershipEpoch)
        output.writeLong(stream.configurationVersion)
        output.writeLong(fact.resultSequence)
        output.writeInt(fact.fills.size)
        for (fill in fact.fills) writeFill(output, fill)
    }

    fun read(input: DataInputStream): SettlementFact {
        val stream =
            MatchingResultStream(
                matchingLineage = input.lineage(),
                fundsLineage = input.lineage(),
                marketId = MarketId(input.text()),
                ownershipEpoch = input.readLong(),
                configurationVersion = input.readLong(),
            )
        val sequence = input.readLong()
        val count = input.readInt()
        require(count in 0..SettlementFact.MAX_CANONICAL_BYTES / MIN_FILL_BYTES)
        return SettlementFact(stream, sequence, List(count) { readFill(input, stream.marketId) })
    }

    private fun writeFill(
        output: DataOutputStream,
        fill: SettledTrade,
    ) {
        val trade = fill.trade
        val transaction = fill.transaction
        require(transaction.transactionType == LedgerTransactionType.SETTLEMENT)
        output.writeLong(trade.engineSequence)
        output.text(trade.makerOrderId.value)
        output.text(trade.takerOrderId.value)
        output.text(trade.makerUserId.value)
        output.text(trade.takerUserId.value)
        output.writeByte(if (trade.side == Side.BUY) 0 else 1)
        output.writeLong(trade.price.value)
        output.writeLong(trade.quantity.value)
        output.writeLong(fill.makerFee.value)
        output.writeLong(fill.takerFee.value)
        output.text(transaction.ledgerTransactionId)
        output.text(transaction.sourceEventId)
        output.writeLong(transaction.occurredAt.epochSecond)
        output.writeInt(transaction.occurredAt.nano)
        require(transaction.postings.size in 1..MAX_POSTINGS)
        output.writeByte(transaction.postings.size)
        for (posting in transaction.postings) {
            output.text(posting.accountId)
            output.text(posting.assetId.value)
            output.writeByte(if (posting.side == LedgerPostingSide.DEBIT) 0 else 1)
            output.writeLong(posting.amount.value)
        }
    }

    private fun readFill(
        input: DataInputStream,
        market: MarketId,
    ): SettledTrade {
        val trade =
            TradeExecuted(
                marketId = market,
                engineSequence = input.readLong(),
                makerOrderId = OrderId(input.text()),
                takerOrderId = OrderId(input.text()),
                makerUserId = UserId(input.text()),
                takerUserId = UserId(input.text()),
                side = if (input.readDirection() == 0) Side.BUY else Side.SELL,
                price = Price(input.readLong()),
                quantity = Quantity(input.readLong()),
            )
        val makerFee = Amount(input.readLong())
        val takerFee = Amount(input.readLong())
        val transactionId = input.text()
        val sourceEventId = input.text()
        val seconds = input.readLong()
        val nanos = input.readInt()
        require(nanos in 0..999_999_999)
        require(seconds in Instant.MIN.epochSecond..Instant.MAX.epochSecond)
        val postingCount = input.readUnsignedByte()
        require(postingCount in 1..MAX_POSTINGS)
        val postings =
            List(postingCount) {
                LedgerPosting(
                    accountId = input.text(),
                    assetId = AssetId(input.text()),
                    side = if (input.readDirection() == 0) LedgerPostingSide.DEBIT else LedgerPostingSide.CREDIT,
                    amount = Amount(input.readLong()),
                )
            }
        val transaction =
            LedgerTransaction(
                ledgerTransactionId = transactionId,
                sourceEventId = sourceEventId,
                transactionType = LedgerTransactionType.SETTLEMENT,
                occurredAt = Instant.ofEpochSecond(seconds, nanos.toLong()),
                postings = postings,
            )
        return SettledTrade(trade, transaction, makerFee, takerFee)
    }

    private fun DataInputStream.readDirection(): Int = readUnsignedByte().also { require(it in 0..1) }

    private fun DataOutputStream.lineage(lineage: ClusterLineage) {
        writeLong(lineage.high)
        writeLong(lineage.low)
    }

    private fun DataInputStream.lineage() = ClusterLineage(readLong(), readLong())

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_TEXT_BYTES)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(): String {
        val size = readUnsignedShort()
        require(size in 1..MAX_TEXT_BYTES)
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8
            .newDecoder()
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private const val MAX_TEXT_BYTES = 1_024
    private const val MAX_POSTINGS = 10
    private const val MIN_FILL_BYTES = 64L
}
