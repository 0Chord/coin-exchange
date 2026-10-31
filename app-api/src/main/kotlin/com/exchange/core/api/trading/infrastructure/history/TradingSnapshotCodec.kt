package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.order.Side
import com.exchange.core.trading.TradingBalanceKey
import com.exchange.core.trading.TradingCheckpoint
import com.exchange.core.trading.TradingState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * 복구에 필요한 내용을 자체적으로 담는 형식이다. 활성 상태는 객체로 디코딩하고, 과거 이력은
 * 행마다 새 인덱스에 스트리밍한다. Snapshot 이후에 바뀐 로컬 이력 DB를 참조하지 않는다.
 */
internal class TradingSnapshotCodec(
    private val history: RocksDbTradingHistoryStore,
    private val identitySha256: String,
) {
    private val values = TradingHistoryCodec(MAXIMUM_VALUE_BYTES)

    init {
        require(identitySha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun write(
        state: TradingState,
        malformedMessages: Long,
        target: OutputStream,
    ) {
        require(malformedMessages >= 0)
        val checkpoint = state.checkpoint()
        val digest = MessageDigest.getInstance("SHA-256")
        val checked = DigestOutputStream(target, digest)
        val out = DataOutputStream(checked)
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeUTF(identitySha256)
        out.writeLong(malformedMessages)
        out.writeLong(checkpoint.lastAdmissionSequence)
        out.writeLong(checkpoint.historyOrdinal)
        out.rows(checkpoint.balances) {
            out.writeUTF(it.userId.value)
            out.writeUTF(it.assetId.value)
            out.writeLong(it.available.value)
            out.writeLong(it.hold.value)
        }
        out.rows(checkpoint.usedBalances) {
            out.writeUTF(it.userId.value)
            out.writeUTF(it.assetId.value)
        }
        out.rows(checkpoint.admissions) { out.blob(values.encodeAdmission(it)) }
        out.rows(checkpoint.reservations) { out.blob(values.encodeReservation(it)) }
        out.rows(checkpoint.pendingAdmissionSequences, out::writeLong)
        out.rows(checkpoint.completions) { out.blob(values.encodeReferencedCompletion(it)) }
        out.rows(checkpoint.matching.markets) { market ->
            out.writeUTF(market.marketId.value)
            out.writeLong(market.lastEngineSequence)
            out.rows(market.orders) { order ->
                out.writeUTF(order.orderId.value)
                out.writeUTF(order.userId.value)
                out.writeUTF(order.side.name)
                out.writeLong(order.price.value)
                out.writeLong(order.originalQuantity.value)
                out.writeLong(order.remainingQuantity.value)
            }
        }
        var rows = 0L
        val ordinal =
            history.exportSnapshot { key, value ->
                out.writeByte(1)
                out.blob(key)
                out.blob(value)
                rows = Math.addExact(rows, 1)
            }
        check(ordinal == checkpoint.historyOrdinal) { "history changed during snapshot" }
        out.writeByte(0)
        out.writeLong(rows)
        out.flush()
        checked.on(false)
        out.write(digest.digest())
        out.writeInt(END_MAGIC)
        out.flush()
    }

    fun read(
        state: TradingState,
        source: InputStream,
    ): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val checked = DigestInputStream(source, digest)
        val input = DataInputStream(checked)
        check(input.readInt() == MAGIC && input.readInt() == VERSION) { "unsupported trading snapshot" }
        check(input.readUTF() == identitySha256) { "snapshot execution identity differs" }
        val malformedMessages = input.readLong().also { check(it >= 0) }
        val lastAdmissionSequence = input.readLong().also { check(it >= 0) }
        val historyOrdinal = input.readLong().also { check(it >= 0) }
        val balances =
            input.rows {
                Balance(UserId(input.readUTF()), AssetId(input.readUTF()), Amount(input.readLong()), Amount(input.readLong()))
            }
        val usedBalances = input.rows { TradingBalanceKey(UserId(input.readUTF()), AssetId(input.readUTF())) }
        val admissions = input.rows { values.decodeAdmission(input.blob()) }
        val bySequence = admissions.associateBy { it.sequence }
        check(bySequence.size == admissions.size) { "snapshot admission sequences are duplicated" }
        val reservations = input.rows { values.decodeReservation(input.blob()) }
        val pending = input.rows(input::readLong)
        val completions = input.rows { values.decodeStoredCompletion(input.blob(), bySequence::get) }
        val markets =
            input.rows {
                val marketId = MarketId(input.readUTF())
                val sequence = input.readLong()
                val orders =
                    input.rows {
                        MatchingCheckpoint.Order(
                            OrderId(input.readUTF()),
                            UserId(input.readUTF()),
                            enumValueOf<Side>(input.readUTF()),
                            Price(input.readLong()),
                            Quantity(input.readLong()),
                            Quantity(input.readLong()),
                        )
                    }
                MatchingCheckpoint.Market(marketId, sequence, orders)
            }
        val checkpoint =
            TradingCheckpoint(
                balances,
                usedBalances,
                admissions,
                reservations,
                pending,
                completions,
                lastAdmissionSequence,
                historyOrdinal,
                MatchingCheckpoint(markets),
            )
        history.beginSnapshotRestore()
        var rows = 0L
        while (true) {
            when (input.readUnsignedByte()) {
                0 -> {
                    break
                }

                1 -> {
                    history.restoreSnapshotEntry(input.blob(), input.blob())
                    rows = Math.addExact(rows, 1)
                }

                else -> {
                    error("invalid snapshot history row marker")
                }
            }
        }
        check(input.readLong() == rows) { "snapshot history row count differs" }
        checked.on(false)
        val expected = ByteArray(32)
        input.readFully(expected)
        check(MessageDigest.isEqual(digest.digest(), expected)) { "trading snapshot checksum differs" }
        check(input.readInt() == END_MAGIC) { "trading snapshot footer is missing" }
        check(input.read() == -1) { "unexpected data after trading snapshot" }
        history.completeSnapshotRestore(historyOrdinal)
        state.restoreCheckpoint(checkpoint)
        return malformedMessages
    }

    private fun DataOutputStream.blob(value: ByteArray) {
        check(value.size in 1..MAXIMUM_VALUE_BYTES) { "snapshot value exceeds bound" }
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.blob(): ByteArray {
        val length = readInt()
        check(length in 1..MAXIMUM_VALUE_BYTES) { "invalid snapshot value length" }
        return ByteArray(length).also(::readFully)
    }

    private fun <T> DataOutputStream.rows(
        values: List<T>,
        write: (T) -> Unit,
    ) {
        check(values.size <= MAXIMUM_ACTIVE_ROWS) { "snapshot active state exceeds row bound" }
        writeInt(values.size)
        values.forEach(write)
    }

    private fun <T> DataInputStream.rows(read: () -> T): List<T> {
        val size = readInt()
        check(size in 0..MAXIMUM_ACTIVE_ROWS) { "invalid snapshot active row count" }
        val values = ArrayList<T>(minOf(size, 16))
        repeat(size) { values.add(read()) }
        return values
    }

    private companion object {
        const val MAGIC = 0x54534e50
        const val VERSION = 1
        const val END_MAGIC = 0x454e4453
        const val MAXIMUM_VALUE_BYTES = 16 * 1_024 * 1_024
        const val MAXIMUM_ACTIVE_ROWS = 10_000_000
    }
}
