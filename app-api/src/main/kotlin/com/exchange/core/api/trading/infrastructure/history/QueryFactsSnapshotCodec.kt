package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.trading.QueryFactsRecovery
import com.exchange.core.trading.QueryRetentionConfiguration
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** 조회 원문은 기존 payload codec을 사용하고, 별도 수명과 유한한 복구 예산만 덧붙인다. */
internal object QueryFactsSnapshotCodec {
    fun writeConfiguration(
        output: DataOutputStream,
        configuration: QueryRetentionConfiguration,
    ) {
        output.writeInt(configuration.capabilityVersion)
        val consumer = configuration.consumerId.toByteArray(Charsets.UTF_8)
        output.writeInt(consumer.size)
        output.write(consumer)
        output.writeInt(configuration.maxEntries)
        output.writeLong(configuration.maxBytes)
        output.writeInt(configuration.marketLimits.size)
        for ((market, limits) in configuration.marketLimits.entries.sortedBy { it.key.value }) {
            val name = market.value.toByteArray(Charsets.UTF_8)
            output.writeInt(name.size)
            output.write(name)
            output.writeInt(limits.maxEntries)
            output.writeLong(limits.maxBytes)
        }
    }

    fun <T> write(
        output: DataOutputStream,
        state: QueryFactsRecovery<T>,
        writeEntry: (DataOutputStream, T) -> Unit,
    ) {
        output.writeLong(state.issued)
        output.writeLong(state.retiredThrough)
        output.writeLong(state.retainedBytes)
        output.writeInt(state.entries.size)
        for (entry in state.entries) {
            val frame = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { writeEntry(it, entry) } }.toByteArray()
            output.writeInt(frame.size)
            output.write(frame)
        }
    }

    fun <T> read(
        input: DataInputStream,
        maxEntries: Int,
        maxBytes: Long,
        maximumEntryBytes: Long = maxBytes,
        readEntry: (DataInputStream) -> T,
        charge: (T) -> Long,
    ): QueryFactsRecovery<T> {
        val issued = input.readLong()
        val retired = input.readLong()
        val bytes = input.readLong()
        require(issued >= 0 && retired in 0..issued) { "query snapshot prefix differs" }
        require(bytes in 0..maxBytes) { "query snapshot byte total exceeds its bound" }
        val count = input.readInt()
        require(count in 0..minOf(maxEntries, 1_000_000) && count.toLong() == issued - retired) { "query snapshot entry count differs" }
        var actualBytes = 0L
        val entries =
            List(count) {
                val remaining = minOf(maximumEntryBytes, bytes - actualBytes)
                val frameLength = input.readInt()
                require(remaining > 0 && frameLength.toLong() in 1..Math.multiplyExact(remaining, 4L)) {
                    "query snapshot frame exceeds its remaining byte bound"
                }
                val frame = ByteArrayInputStream(ByteArray(frameLength).also(input::readFully))
                val entry = readEntry(DataInputStream(frame))
                require(frame.available() == 0) { "query snapshot frame has trailing bytes" }
                val actual = charge(entry)
                require(actual in 1..remaining) { "query snapshot entry exceeds its remaining byte bound" }
                actualBytes = Math.addExact(actualBytes, actual)
                entry
            }
        require(actualBytes == bytes) { "query snapshot byte total differs" }
        return QueryFactsRecovery(issued, retired, entries, bytes)
    }
}
