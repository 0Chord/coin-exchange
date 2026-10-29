package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.order.OrderReservation
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingHistoryBatch
import com.exchange.core.trading.TradingHistoryStore
import com.exchange.core.trading.TradingState
import org.rocksdb.BlockBasedTableConfig
import org.rocksdb.BloomFilter
import org.rocksdb.LRUCache
import org.rocksdb.Options
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import org.rocksdb.WriteBufferManager
import org.rocksdb.WriteOptions
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** Limits cover the pending overlay, native block cache, memtables and table readers separately. */
data class TradingHistoryStorageLimits(
    val cacheBytes: Long = 32L * 1_024 * 1_024,
    val writeBufferBytes: Long = 8L * 1_024 * 1_024,
    val maximumPendingBytes: Long = 64L * 1_024 * 1_024,
    val maximumPendingBatches: Int = 1_024,
    val maximumValueBytes: Int = 16 * 1_024 * 1_024,
    val maximumGroupBatches: Int = 256,
    val targetGroupBytes: Long = 256L * 1_024,
    val maximumOpenFiles: Int = 64,
    val maximumWrittenBytes: Long = 8L * 1_024 * 1_024 * 1_024,
) {
    init {
        require(cacheBytes >= 65_536 && writeBufferBytes >= 65_536)
        require(maximumPendingBytes > 0 && maximumPendingBatches > 0)
        require(maximumValueBytes in 1_024..64 * 1_024 * 1_024)
        require(maximumGroupBatches in 1..maximumPendingBatches && targetGroupBytes > 0)
        require(maximumOpenFiles in 16..1_024)
        require(maximumWrittenBytes > 0)
    }
}

/**
 * One business owner enqueues immutable transitions. A bounded worker group commits them with WAL sync.
 * The bounded pending overlay supplies read-your-writes; only a successful sync advances durableOrdinal.
 * This is a fresh, per-run derived history, not recovery of the in-memory trading state.
 */
class RocksDbTradingHistoryStore private constructor(
    private val database: RocksDB,
    private val options: Options,
    private val writeOptions: WriteOptions,
    private val cache: LRUCache,
    private val buffers: WriteBufferManager,
    private val filter: BloomFilter,
    private val limits: TradingHistoryStorageLimits,
    private val onFatal: (Throwable) -> Unit,
) : TradingHistoryStore,
    AutoCloseable {
    private class Key(
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Key && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private class Put(
        val key: Key,
        val value: ByteArray,
    )

    private class Pending(
        val ordinal: Long,
        val puts: List<Put>,
        val bytes: Long,
    )

    private val codec = TradingHistoryCodec(limits.maximumValueBytes)
    private val monitor = Object()
    private val queue = ArrayDeque<Pending>()
    private val pendingValues = HashMap<Key, Put>()
    private var pendingBytes = 0L
    private var pendingCount = 0
    private var appendedOrdinal = 0L
    private var appendedBytes = 0L
    private var committedOrdinal = 0L
    private var failure: Throwable? = null
    private var stopping = false
    private var closed = false
    private var flushRequested = false
    private val writer =
        Thread(::writeLoop, "trading-history-writer").apply {
            isDaemon = true
            start()
        }

    override val durableOrdinal: Long
        get() =
            synchronized(monitor) {
                checkUsable()
                committedOrdinal
            }

    internal val pendingBatchCount: Int get() = synchronized(monitor) { pendingCount }
    internal val retainedPendingBytes: Long get() = synchronized(monitor) { pendingBytes }

    override fun append(batch: TradingHistoryBatch) =
        guarded {
            val puts = encode(batch)
            val bytes =
                puts.fold(
                    0L,
                ) { total, put ->
                    Math.addExact(
                        total,
                        put.key.bytes.size
                            .toLong() + put.value.size + ENTRY_OVERHEAD_BYTES,
                    )
                }
            check(bytes <= limits.maximumPendingBytes) { "one trading history transition exceeds the pending byte limit" }
            synchronized(monitor) {
                checkUsable()
                check(batch.ordinal == Math.addExact(appendedOrdinal, 1L)) { "history ordinals must be contiguous" }
                check(bytes <= limits.maximumWrittenBytes - appendedBytes) { "trading history cumulative write byte limit exceeded" }
                val deadline = System.nanoTime() + WAIT_NANOS
                while (pendingCount >= limits.maximumPendingBatches || bytes > limits.maximumPendingBytes - pendingBytes) {
                    flushRequested = true
                    monitor.notifyAll()
                    awaitProgress(deadline)
                }
                val pending = Pending(batch.ordinal, puts, bytes)
                queue.addLast(pending)
                for (put in puts) pendingValues[put.key] = put
                pendingBytes += bytes
                pendingCount++
                appendedOrdinal = batch.ordinal
                appendedBytes += bytes
                monitor.notifyAll()
            }
        }

    override fun flush(): Long =
        guarded {
            synchronized(monitor) {
                checkUsable()
                val target = appendedOrdinal
                val deadline = System.nanoTime() + WAIT_NANOS
                flushRequested = true
                monitor.notifyAll()
                while (committedOrdinal < target) awaitProgress(deadline)
                committedOrdinal
            }
        }

    override fun findRequest(
        userId: UserId,
        requestId: String,
    ): OrderAdmissionState.RequestRecord? =
        guarded {
            indexed(key(REQUEST_INDEX, userId.value, requestId), REQUEST_DATA)?.let(codec::decodeRequest)?.also {
                check(it.order.userId == userId && it.requestId == requestId) { "history request index mismatch" }
            }
        }

    override fun findAdmission(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderAdmissionState.Record? =
        guarded {
            indexed(key(ADMISSION_INDEX, marketId.value, orderId.value), ADMISSION_DATA)?.let(codec::decodeAdmission)?.also {
                check(it.candidate.order.marketId == marketId && it.candidate.order.orderId == orderId) { "history order index mismatch" }
            }
        }

    override fun findAdmission(sequence: Long): OrderAdmissionState.Record? =
        guarded {
            if (sequence <= 0) {
                null
            } else {
                read(numberKey(ADMISSION_DATA, sequence))?.let(codec::decodeAdmission)?.also {
                    check(it.sequence == sequence) { "history admission sequence mismatch" }
                }
            }
        }

    override fun findCompletion(sequence: Long): TradingState.Completion? =
        guarded {
            if (sequence <= 0) {
                null
            } else {
                indexed(numberKey(COMPLETION_INDEX, sequence), COMPLETION_DATA)?.let(codec::decodeCompletion)?.also {
                    check(it.admission.sequence == sequence) { "history completion sequence mismatch" }
                }
            }
        }

    override fun findPreparation(preparationId: String): OrderAdmissionState.PreparationRecord? =
        guarded {
            indexed(key(PREPARATION_INDEX, preparationId), PREPARATION_DATA)?.let(codec::decodePreparation)?.also {
                check(it.result.opening.preparationId == preparationId) { "history preparation index mismatch" }
            }
        }

    override fun containsTransactionId(id: String): Boolean =
        guarded {
            indexed(key(TRANSACTION_INDEX, id), TRANSACTION_DATA)?.let(codec::decodeTransaction)?.also {
                check(it.ledgerTransactionId == id) { "history transaction index mismatch" }
            } != null
        }

    override fun containsSourceEventId(id: String): Boolean =
        guarded {
            indexed(key(SOURCE_INDEX, id), TRANSACTION_DATA)?.let(codec::decodeTransaction)?.also {
                check(it.sourceEventId == id) { "history source event index mismatch" }
            } != null
        }

    override fun requests(): Sequence<OrderAdmissionState.RequestRecord> = scan(REQUEST_DATA, codec::decodeRequest)

    override fun admissions(): Sequence<OrderAdmissionState.Record> = scan(ADMISSION_DATA, codec::decodeAdmission)

    override fun completions(): Sequence<TradingState.Completion> = scan(COMPLETION_DATA, codec::decodeCompletion)

    override fun preparations(): Sequence<OrderAdmissionState.PreparationRecord> = scan(PREPARATION_DATA, codec::decodePreparation)

    override fun reservations(): Sequence<OrderReservation> = scan(RESERVATION_DATA, codec::decodeReservation)

    override fun transactions(): Sequence<LedgerTransaction> = scan(TRANSACTION_DATA, codec::decodeTransaction)

    private fun encode(batch: TradingHistoryBatch): List<Put> {
        val puts = ArrayList<Put>()
        var encodedBytes = 0L

        fun put(
            key: ByteArray,
            value: ByteArray,
        ) {
            encodedBytes = Math.addExact(encodedBytes, key.size.toLong() + value.size + ENTRY_OVERHEAD_BYTES)
            check(encodedBytes <= limits.maximumPendingBytes) { "one trading history transition exceeds the pending byte limit" }
            puts.add(Put(Key(key), value))
        }
        batch.request?.let {
            val target = numberKey(REQUEST_DATA, batch.ordinal)
            put(target, codec.encodeRequest(it))
            put(key(REQUEST_INDEX, it.order.userId.value, it.requestId), target)
        }
        batch.admission?.let {
            val target = numberKey(ADMISSION_DATA, it.sequence)
            put(target, codec.encodeAdmission(it))
            put(key(ADMISSION_INDEX, it.candidate.order.marketId.value, it.candidate.order.orderId.value), target)
        }
        batch.completion?.let {
            val target = numberKey(COMPLETION_DATA, batch.ordinal)
            put(target, codec.encodeCompletion(it))
            put(numberKey(COMPLETION_INDEX, it.admission.sequence), target)
        }
        batch.preparation?.let {
            val target = numberKey(PREPARATION_DATA, batch.ordinal)
            put(target, codec.encodePreparation(it))
            put(key(PREPARATION_INDEX, it.result.opening.preparationId), target)
        }
        batch.reservations.forEach { reservation ->
            val sequence =
                batch.admission
                    ?.takeIf {
                        it.candidate.order.marketId == reservation.marketId && it.candidate.order.orderId == reservation.orderId
                    }?.sequence ?: run {
                    val target =
                        checkNotNull(read(key(ADMISSION_INDEX, reservation.marketId.value, reservation.orderId.value))) {
                            "reservation history has no admission"
                        }
                    check(target.size == 9 && target[0] == ADMISSION_DATA) { "invalid reservation admission index" }
                    ByteBuffer.wrap(target, 1, Long.SIZE_BYTES).long.also { check(it > 0) }
                }
            put(numberKey(RESERVATION_DATA, sequence), codec.encodeReservation(reservation))
        }
        batch.ledgerTransactions.forEachIndexed { index, transaction ->
            val target = numberKey(TRANSACTION_DATA, batch.ordinal, index.toLong())
            put(target, codec.encodeTransaction(transaction))
            put(key(TRANSACTION_INDEX, transaction.ledgerTransactionId), target)
            put(key(SOURCE_INDEX, transaction.sourceEventId), target)
        }
        // The durable ordinal is committed atomically with every row and secondary index in the group.
        put(byteArrayOf(METADATA), ByteBuffer.allocate(Long.SIZE_BYTES).putLong(batch.ordinal).array())
        return puts
    }

    private fun indexed(
        index: ByteArray,
        prefix: Byte,
    ): ByteArray? {
        val target = read(index) ?: return null
        check(target.isNotEmpty() && target[0] == prefix && (target.size == 9 || target.size == 17)) { "invalid trading history index" }
        return checkNotNull(read(target)) { "trading history index points to missing data" }
    }

    private fun read(key: ByteArray): ByteArray? =
        synchronized(monitor) {
            checkUsable()
            pendingValues[Key(key)]?.value ?: database.get(key)
        }

    /** Flush first; reopen a short-lived iterator per row, so abandoning a sequence leaks no native handle. */
    private fun <T> scan(
        prefix: Byte,
        decode: (ByteArray) -> T,
    ): Sequence<T> =
        sequence {
            flush()
            var previous: ByteArray? = null
            while (true) {
                val row =
                    guarded {
                        synchronized(monitor) {
                            checkUsable()
                            database.newIterator().use { cursor ->
                                val last = previous
                                if (last == null) {
                                    cursor.seek(byteArrayOf(prefix))
                                } else {
                                    cursor.seek(last)
                                    if (cursor.isValid && cursor.key().contentEquals(last)) cursor.next()
                                }
                                cursor.status()
                                if (!cursor.isValid) {
                                    null
                                } else {
                                    val key = cursor.key()
                                    if (key.isEmpty() || key[0] != prefix) null else key to cursor.value()
                                }
                            }
                        }
                    } ?: break
                previous = row.first
                yield(guarded { decode(row.second) })
            }
        }

    private fun writeLoop() {
        try {
            while (true) {
                val group =
                    synchronized(monitor) {
                        while (queue.isEmpty() && !stopping && failure == null) monitor.wait()
                        if (failure != null || (stopping && queue.isEmpty())) return
                        // A short collection window groups sparse transition pairs without unbounded latency.
                        if (!flushRequested && queue.size < limits.maximumGroupBatches) monitor.wait(1)
                        if (failure != null) return
                        val selected = ArrayList<Pending>()
                        var bytes = 0L
                        while (queue.isNotEmpty() && selected.size < limits.maximumGroupBatches) {
                            if (selected.isNotEmpty() && bytes + queue.first.bytes > limits.targetGroupBytes) break
                            val pending = queue.removeFirst()
                            selected.add(pending)
                            bytes += pending.bytes
                        }
                        flushRequested = false
                        selected
                    }
                WriteBatch().use { write ->
                    group.forEach { pending -> pending.puts.forEach { put -> write.put(put.key.bytes, put.value) } }
                    database.write(writeOptions, write)
                }
                synchronized(monitor) {
                    for (pending in group) {
                        for (put in pending.puts) if (pendingValues[put.key] === put) pendingValues.remove(put.key)
                        pendingBytes -= pending.bytes
                        pendingCount--
                    }
                    committedOrdinal = group.last().ordinal
                    monitor.notifyAll()
                }
            }
        } catch (error: Throwable) {
            recordFailure(error)
        }
    }

    private fun awaitProgress(deadline: Long) {
        checkUsable()
        val remaining = deadline - System.nanoTime()
        check(remaining > 0) { "trading history writer did not make progress within 30 seconds" }
        TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
        checkUsable()
    }

    private fun checkUsable() {
        failure?.let { throw IllegalStateException("trading history store has failed", it) }
        check(!closed && !stopping) { "trading history store is closed" }
    }

    private fun recordFailure(error: Throwable) {
        val first =
            synchronized(monitor) {
                val firstFailure = failure == null
                if (firstFailure) failure = error
                monitor.notifyAll()
                firstFailure
            }
        if (first) {
            try {
                onFatal(error)
            } catch (notification: Throwable) {
                if (notification !== error) error.addSuppressed(notification)
            }
        }
    }

    private inline fun <T> guarded(action: () -> T): T =
        try {
            action()
        } catch (error: Throwable) {
            recordFailure(error)
            throw error
        }

    override fun close() {
        synchronized(monitor) { if (closed) return }
        // Complete ownership cleanup even when shutdown was interrupted; preserve the caller's signal.
        var interrupted = Thread.interrupted()
        try {
            var closeFailure: Throwable? = null
            try {
                flush()
            } catch (error: Throwable) {
                if (error is InterruptedException) interrupted = true
                closeFailure = error
            }
            synchronized(monitor) {
                stopping = true
                monitor.notifyAll()
            }
            val deadline = System.nanoTime() + WAIT_NANOS
            while (writer.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                try {
                    TimeUnit.NANOSECONDS.timedJoin(writer, remaining)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            check(!writer.isAlive) { "trading history writer did not stop; native handles remain owned by it" }
            synchronized(monitor) {
                closed = true
                pendingValues.clear()
                queue.clear()
                pendingBytes = 0
                pendingCount = 0
                for (resource in listOf(database, writeOptions, options, buffers, cache, filter)) {
                    try {
                        resource.close()
                    } catch (error: Throwable) {
                        if (closeFailure == null) closeFailure = error else closeFailure?.addSuppressed(error)
                    }
                }
            }
            closeFailure?.let { throw it }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val REQUEST_DATA: Byte = 1
        private const val ADMISSION_DATA: Byte = 2
        private const val COMPLETION_DATA: Byte = 3
        private const val PREPARATION_DATA: Byte = 4
        private const val RESERVATION_DATA: Byte = 5
        private const val TRANSACTION_DATA: Byte = 6
        private const val REQUEST_INDEX: Byte = 11
        private const val ADMISSION_INDEX: Byte = 12
        private const val COMPLETION_INDEX: Byte = 13
        private const val PREPARATION_INDEX: Byte = 14
        private const val TRANSACTION_INDEX: Byte = 16
        private const val SOURCE_INDEX: Byte = 17
        private const val METADATA: Byte = 20
        private const val ENTRY_OVERHEAD_BYTES = 128L
        private val WAIT_NANOS = TimeUnit.SECONDS.toNanos(30)

        fun create(
            directory: Path,
            limits: TradingHistoryStorageLimits = TradingHistoryStorageLimits(),
            onFatal: (Throwable) -> Unit = {
            },
        ): RocksDbTradingHistoryStore {
            RocksDB.loadLibrary()
            // A new exclusive generation is mandatory; existing data cannot restore the live trading owner.
            Files.createDirectory(directory)
            val resources = ArrayList<AutoCloseable>()
            try {
                val cache = LRUCache(limits.cacheBytes, -1, true).also(resources::add)
                val buffers = WriteBufferManager(limits.writeBufferBytes, cache, true).also(resources::add)
                val filter = BloomFilter(10.0).also(resources::add)
                val table =
                    BlockBasedTableConfig()
                        .setBlockCache(cache)
                        .setCacheIndexAndFilterBlocks(true)
                        .setCacheIndexAndFilterBlocksWithHighPriority(true)
                        .setPinL0FilterAndIndexBlocksInCache(false)
                        .setPinTopLevelIndexAndFilter(false)
                        .setFilterPolicy(filter)
                val options =
                    Options()
                        .setCreateIfMissing(true)
                        .setErrorIfExists(true)
                        .setWriteBufferSize(limits.writeBufferBytes / 2)
                        .setMaxWriteBufferNumber(2)
                        .setWriteBufferManager(buffers)
                        .setMaxOpenFiles(limits.maximumOpenFiles)
                        .setMaxBackgroundJobs(2)
                        .setMaxTotalWalSize(limits.writeBufferBytes * 2)
                        .setTableFormatConfig(table)
                        .also(resources::add)
                val writes = WriteOptions().setSync(true).setDisableWAL(false).also(resources::add)
                val database = RocksDB.open(options, directory.toString()).also(resources::add)
                return RocksDbTradingHistoryStore(database, options, writes, cache, buffers, filter, limits, onFatal)
            } catch (error: Throwable) {
                resources.asReversed().forEach {
                    try {
                        it.close()
                    } catch (closing: Throwable) {
                        error.addSuppressed(closing)
                    }
                }
                throw error
            }
        }

        private fun key(
            prefix: Byte,
            vararg values: String,
        ): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeByte(prefix.toInt())
                values.forEach {
                    output.writeInt(it.length)
                    output.writeChars(it)
                }
            }
            return bytes.toByteArray()
        }

        private fun numberKey(
            prefix: Byte,
            vararg values: Long,
        ): ByteArray =
            ByteBuffer
                .allocate(1 + values.size * Long.SIZE_BYTES)
                .put(prefix)
                .apply {
                    values.forEach {
                        require(it >= 0)
                        putLong(it)
                    }
                }.array()
    }
}
