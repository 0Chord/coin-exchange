package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.order.OrderReservation
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.PreparedTradingHistoryBatch
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
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.Arrays
import java.util.concurrent.TimeUnit

/** 대기 중인 overlay·네이티브 블록 캐시·memtable·table reader에 각각 한도를 적용한다. */
data class TradingHistoryStorageLimits(
    val cacheBytes: Long = 32L * 1_024 * 1_024,
    val writeBufferBytes: Long = 16L * 1_024 * 1_024,
    val maximumPendingBytes: Long = 64L * 1_024 * 1_024,
    val maximumPendingBatches: Int = 1_024,
    val maximumValueBytes: Int = 16 * 1_024 * 1_024,
    val maximumGroupBatches: Int = 256,
    val targetGroupBytes: Long = 256L * 1_024,
    val maximumOpenFiles: Int = 64,
    val maximumWrittenBytes: Long = 8L * 1_024 * 1_024 * 1_024,
    val maximumCachedAdmissionIndexes: Int = 16_384,
    val maximumCachedAdmissionIndexBytes: Long = 8L * 1_024 * 1_024,
) {
    init {
        // RocksDB 10.1.3은 고정 메모리 예약을 256 KiB 단위로 계산한다. 엄격한 한도를 적용한 캐시 하나에
        // memtable과 동시에 사용하는 table reader·filter 예약 공간도 남겨 둔다.
        require(cacheBytes >= 2L * 1_024 * 1_024) { "history cache must be at least 2 MiB" }
        require(writeBufferBytes >= 65_536 && writeBufferBytes <= cacheBytes / 2) {
            "history write buffers must fit within half of the strict cache"
        }
        require(maximumPendingBytes > 0 && maximumPendingBatches > 0)
        require(maximumValueBytes in 1_024..64 * 1_024 * 1_024)
        require(maximumGroupBatches in 1..maximumPendingBatches && targetGroupBytes > 0)
        require(maximumOpenFiles in 16..1_024)
        require(maximumWrittenBytes > 0)
        require(maximumCachedAdmissionIndexes >= 0 && maximumCachedAdmissionIndexBytes >= 0)
    }
}

/**
 * 업무 소유자 한 개가 불변 전이를 큐에 넣고, 정해진 수의 worker가 이를 원자적으로 저장한다.
 * 대기 중인 overlay로 아직 저장하지 않은 쓰기도 읽을 수 있다. [create]는 WAL도 동기화한다.
 * 내부의 재구성 가능한 인덱스는 호출자가 보관하는 영속 Archive를 복구 원본으로 삼는다.
 * 이 체크포인트를 복원하려면 같은 시점의 현재 상태 체크포인트가 필요하다. 이력만으로는
 * 잔고·활성 주문·거래 소유자를 복원하지 못한다.
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
    private val rebuildableIndex: Boolean,
) : TradingHistoryStore,
    AutoCloseable {
    private class Key(
        val bytes: ByteArray,
    ) {
        private val hash = bytes.contentHashCode()

        override fun equals(other: Any?): Boolean = other is Key && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = hash
    }

    private class Put(
        val key: Key,
        val value: ByteArray,
    )

    private class EncodedTransition(
        val puts: List<Put>,
        val bytes: Long,
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
    private val knownKeys = TradingHistoryKeyFilter()
    private val admissionIndexes = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)

    // 동일 객체가 적중하면 활성 승인 객체 그래프를 붙잡지 않고도 재인코딩을 생략한다.
    private val admissionIdentities = LinkedHashMap<Long, WeakReference<OrderAdmissionState.Record>>(16, 0.75f, true)
    private var admissionIndexBytes = 0L
    private var pendingBytes = 0L

    // 묶음 구성에는 큐에 남아 있는 행만 세지만, pendingBytes에는 처리 중인 묶음도 포함한다.
    private var queuedEncodedBytes = 0L
    private var pendingCount = 0
    private var appendedOrdinal = 0L
    private var maximumAdmissionSequence = 0L
    private var rawAppendUsed = false
    private var appendedBytes = 0L
    private var committedOrdinal = 0L
    private var failure: Throwable? = null
    private var stopping = false
    private var closed = false
    private var flushRequested = false
    private var snapshotOperation: Thread? = null
    private var restoringSnapshot = false
    private var restoredPreviousKey: ByteArray? = null
    private var restoredOrdinal: Long? = null
    private var restoredWrittenBytes: Long? = null
    private var restoredEntryBytes = 0L
    private val writer =
        Thread(::writeLoop, "trading-history-writer").apply {
            isDaemon = true
            start()
        }

    /**
     * 소유자가 안전하게 해제할 수 있도록 저장을 마친 연속 구간이다. [create]에서는 해당 WAL도 동기화한다.
     * 재구성 가능한 인덱스의 확인은 로컬 DB의 내구성이 아니라 호출자 Archive에서 복구할 수 있음을 뜻한다.
     */
    override val durableOrdinal: Long
        get() =
            synchronized(monitor) {
                checkOperational()
                committedOrdinal
            }

    internal val pendingBatchCount: Int get() = synchronized(monitor) { pendingCount }
    internal val retainedPendingBytes: Long get() = synchronized(monitor) { pendingBytes }
    internal val cachedAdmissionIndexCount: Int get() = synchronized(monitor) { admissionIndexes.size }
    internal val cachedAdmissionIndexBytes: Long get() = synchronized(monitor) { admissionIndexBytes }
    internal val cachedAdmissionIdentityCount: Int get() = synchronized(monitor) { admissionIdentities.size }

    override fun append(batch: TradingHistoryBatch) = appendTransition(batch, null)

    override fun appendPrepared(prepared: PreparedTradingHistoryBatch) =
        guarded {
            prepared.requireOwner(this)
            if (rebuildableIndex) appendTransition(prepared.batch, prepared) else append(prepared.batch)
        }

    private fun appendTransition(
        batch: TradingHistoryBatch,
        prepared: PreparedTradingHistoryBatch?,
    ) = guarded {
        val ownerProof =
            synchronized(monitor) {
                checkOperational()
                // 일반 입력이 섞인 세대는 원래의 전체 검사를 유지한다. 이 세대의 현재 도메인 소유자가
                // 발행한 전이만 해당 참조를 재사용할 수 있다.
                if (prepared == null) rawAppendUsed = true
                prepared?.takeUnless { rawAppendUsed }
            }
        val transition = encode(batch, ownerProof)
        val puts = transition.puts
        val bytes = transition.bytes
        synchronized(monitor) {
            checkOperational()
            check(batch.ordinal == Math.addExact(appendedOrdinal, 1L)) { "history ordinals must be contiguous" }
            check(
                transition.bytes <= limits.maximumWrittenBytes - appendedBytes,
            ) { "trading history cumulative write byte limit exceeded" }
            val deadline = System.nanoTime() + WAIT_NANOS
            while (pendingCount >= limits.maximumPendingBatches || bytes > limits.maximumPendingBytes - pendingBytes) {
                flushRequested = true
                monitor.notifyAll()
                awaitProgress(deadline)
            }
            val pending = Pending(batch.ordinal, puts, bytes)
            queue.addLast(pending)
            queuedEncodedBytes += transition.bytes
            for (put in puts) {
                knownKeys.add(put.key.bytes)
                pendingValues[put.key] = put
                if (put.key.bytes[0] == ADMISSION_INDEX) cacheAdmissionIndex(put.key, put.value)
            }
            batch.admission?.let {
                cacheAdmissionIdentity(it)
                maximumAdmissionSequence = maxOf(maximumAdmissionSequence, it.sequence)
            }
            pendingBytes += bytes
            pendingCount++
            appendedOrdinal = batch.ordinal
            appendedBytes += transition.bytes
            monitor.notifyAll()
        }
    }

    override fun flush(): Long =
        guarded {
            synchronized(monitor) {
                checkOperational()
                awaitDurableLocked(appendedOrdinal)
            }
        }

    override fun awaitDurable(ordinal: Long): Long =
        guarded {
            synchronized(monitor) {
                checkOperational()
                require(ordinal in 0..appendedOrdinal) { "history durability target has not been appended" }
                awaitDurableLocked(ordinal)
            }
        }

    private fun awaitDurableLocked(ordinal: Long): Long {
        if (committedOrdinal < ordinal) {
            val deadline = System.nanoTime() + WAIT_NANOS
            flushRequested = true
            monitor.notifyAll()
            while (committedOrdinal < ordinal) awaitProgress(deadline)
        }
        return committedOrdinal
    }

    /**
     * 업무 소유자가 멈춘 동안 전체 과거 이력 체크포인트를 스트리밍한다. 메모리는 행 하나 크기로
     * 제한하지만, Snapshot 크기와 소요 시간은 보관한 전체 이력에 비례한다. 마지막의 Snapshot 전용
     * 행은 덮어쓴 이전 값까지 포함한 누적 쓰기 예산을 보존한다.
     */
    fun exportSnapshot(accept: (ByteArray, ByteArray) -> Unit): Long =
        guarded {
            val ordinal =
                synchronized(monitor) {
                    checkOperational()
                    awaitDurableLocked(appendedOrdinal)
                    check(pendingCount == 0 && queue.isEmpty() && pendingValues.isEmpty())
                    snapshotOperation = Thread.currentThread()
                    committedOrdinal
                }
            try {
                database.newIterator().use { cursor ->
                    cursor.seekToFirst()
                    while (cursor.isValid) {
                        cursor.status()
                        accept(cursor.key(), cursor.value())
                        cursor.next()
                    }
                    cursor.status()
                }
                accept(byteArrayOf(SNAPSHOT_WRITTEN_BYTES), ByteBuffer.allocate(Long.SIZE_BYTES).putLong(appendedBytes).array())
                ordinal
            } finally {
                synchronized(monitor) { snapshotOperation = null }
            }
        }

    /** 비어 있는 새 세대만 Snapshot을 받을 수 있다. 완료 전에는 정상 실행 메서드를 사용할 수 없다. */
    fun beginSnapshotRestore() =
        guarded {
            synchronized(monitor) {
                checkOperational()
                check(appendedOrdinal == 0L && committedOrdinal == 0L && pendingCount == 0 && queue.isEmpty()) {
                    "history snapshot requires an empty store"
                }
                database.newIterator().use { cursor ->
                    cursor.seekToFirst()
                    cursor.status()
                    check(!cursor.isValid) { "history snapshot requires an empty database" }
                }
                restoredPreviousKey = null
                restoredOrdinal = null
                restoredWrittenBytes = null
                restoredEntryBytes = 0
                snapshotOperation = Thread.currentThread()
                restoringSnapshot = true
            }
        }

    /** 엄격한 바이트 순서를 검사해 과거 키 집합을 보관하지 않고도 중복 행을 거절한다. */
    fun restoreSnapshotEntry(
        key: ByteArray,
        value: ByteArray,
    ) = guarded {
        synchronized(monitor) {
            checkSnapshotRestore()
            check(key.isNotEmpty() && key.size <= limits.maximumValueBytes) { "invalid history snapshot key length" }
            check(value.isNotEmpty() && value.size <= limits.maximumValueBytes) { "invalid history snapshot value length" }
            restoredPreviousKey?.let { check(Arrays.compareUnsigned(it, key) < 0) { "history snapshot keys are not strictly ordered" } }
            validateSnapshotKey(key, value)
            if (key[0] == SNAPSHOT_WRITTEN_BYTES) {
                restoredWrittenBytes = ByteBuffer.wrap(value).long.also { check(it >= 0) { "invalid history snapshot write budget" } }
            } else {
                val bytes = Math.addExact(key.size.toLong() + value.size, ENTRY_OVERHEAD_BYTES)
                restoredEntryBytes = Math.addExact(restoredEntryBytes, bytes)
                check(restoredEntryBytes <= limits.maximumWrittenBytes) { "history snapshot exceeds the write byte limit" }
                database.put(writeOptions, key, value)
                knownKeys.add(key)
                if (key[0] == METADATA) {
                    restoredOrdinal = ByteBuffer.wrap(value).long.also { check(it > 0) { "invalid history snapshot ordinal" } }
                }
            }
            restoredPreviousKey = key.copyOf()
        }
    }

    /** 저장한 모든 값과 정확한 키 인덱스의 양방향 관계를 검증한 뒤에만 공개한다. */
    fun completeSnapshotRestore(expectedOrdinal: Long) =
        guarded {
            synchronized(monitor) {
                checkSnapshotRestore()
                check(expectedOrdinal >= 0 && (restoredOrdinal ?: 0L) == expectedOrdinal) { "history snapshot ordinal mismatch" }
                val written = checkNotNull(restoredWrittenBytes) { "history snapshot write budget is missing" }
                check(written in restoredEntryBytes..limits.maximumWrittenBytes) { "history snapshot write budget mismatch" }
                check(expectedOrdinal != 0L || (restoredEntryBytes == 0L && written == 0L)) { "empty history snapshot contains data" }
                var restoredMaximumAdmissionSequence = 0L
                database.newIterator().use { cursor ->
                    cursor.seekToFirst()
                    while (cursor.isValid) {
                        cursor.status()
                        val key = cursor.key()
                        validateSnapshotRow(key, cursor.value(), expectedOrdinal)
                        if (key[0] == ADMISSION_DATA) {
                            restoredMaximumAdmissionSequence =
                                maxOf(restoredMaximumAdmissionSequence, ByteBuffer.wrap(key, 1, Long.SIZE_BYTES).long)
                        }
                        cursor.next()
                    }
                    cursor.status()
                }
                maximumAdmissionSequence = restoredMaximumAdmissionSequence
                appendedOrdinal = expectedOrdinal
                committedOrdinal = expectedOrdinal
                appendedBytes = written
                restoringSnapshot = false
                snapshotOperation = null
                restoredPreviousKey = null
            }
        }

    private fun checkSnapshotRestore() {
        checkUsable()
        check(restoringSnapshot && snapshotOperation === Thread.currentThread()) { "history snapshot restore is not owned by this thread" }
    }

    private fun validateSnapshotKey(
        key: ByteArray,
        value: ByteArray,
    ) {
        when (key[0]) {
            REQUEST_DATA, ADMISSION_DATA, COMPLETION_DATA, PREPARATION_DATA, RESERVATION_DATA, COMPLETION_INDEX -> {
                check(key.size == 9 && ByteBuffer.wrap(key, 1, Long.SIZE_BYTES).long > 0) { "invalid history snapshot numeric key" }
            }

            TRANSACTION_DATA -> {
                check(
                    key.size == 17 && ByteBuffer.wrap(key, 1, Long.SIZE_BYTES).long > 0 &&
                        ByteBuffer.wrap(key, 9, Long.SIZE_BYTES).long >= 0,
                ) { "invalid history snapshot transaction key" }
            }

            REQUEST_INDEX, ADMISSION_INDEX, PREPARATION_INDEX, TRANSACTION_INDEX, SOURCE_INDEX -> {
                val fields = if (key[0] == REQUEST_INDEX || key[0] == ADMISSION_INDEX) 2 else 1
                val buffer = ByteBuffer.wrap(key).position(1)
                repeat(fields) {
                    check(buffer.remaining() >= Int.SIZE_BYTES) { "truncated history snapshot string key" }
                    val characters = buffer.int
                    check(characters > 0 && characters <= buffer.remaining() / Char.SIZE_BYTES) { "invalid history snapshot string length" }
                    buffer.position(buffer.position() + characters * Char.SIZE_BYTES)
                }
                check(!buffer.hasRemaining()) { "trailing history snapshot key bytes" }
            }

            METADATA, SNAPSHOT_WRITTEN_BYTES -> {
                check(key.size == 1 && value.size == Long.SIZE_BYTES) { "invalid history snapshot metadata" }
            }

            else -> {
                error("unknown history snapshot key prefix")
            }
        }
    }

    private fun validateSnapshotRow(
        rowKey: ByteArray,
        value: ByteArray,
        ordinal: Long,
    ) {
        fun exactIndex(index: ByteArray) {
            check(database.get(index)?.contentEquals(rowKey) == true) { "history snapshot data/index mismatch" }
        }

        fun admission(sequence: Long): OrderAdmissionState.Record =
            codec.decodeAdmission(
                checkNotNull(database.get(numberKey(ADMISSION_DATA, sequence))) { "history snapshot admission is missing" },
            )

        when (rowKey[0]) {
            REQUEST_DATA -> {
                check(ByteBuffer.wrap(rowKey, 1, Long.SIZE_BYTES).long <= ordinal) { "history snapshot request ordinal is ahead" }
                val request = codec.decodeStoredRequest(value, ::admission)
                exactIndex(key(REQUEST_INDEX, request.order.userId.value, request.requestId))
            }

            ADMISSION_DATA -> {
                val record = codec.decodeAdmission(value)
                check(
                    ByteBuffer.wrap(rowKey, 1, Long.SIZE_BYTES).long == record.sequence,
                ) { "history snapshot admission sequence mismatch" }
                exactIndex(key(ADMISSION_INDEX, record.candidate.order.marketId.value, record.candidate.order.orderId.value))
            }

            COMPLETION_DATA -> {
                check(ByteBuffer.wrap(rowKey, 1, Long.SIZE_BYTES).long <= ordinal) { "history snapshot completion ordinal is ahead" }
                val completion = codec.decodeStoredCompletion(value, ::admission)
                exactIndex(numberKey(COMPLETION_INDEX, completion.admission.sequence))
            }

            PREPARATION_DATA -> {
                check(ByteBuffer.wrap(rowKey, 1, Long.SIZE_BYTES).long <= ordinal) { "history snapshot preparation ordinal is ahead" }
                exactIndex(
                    key(
                        PREPARATION_INDEX,
                        codec
                            .decodePreparation(value)
                            .result.opening.preparationId,
                    ),
                )
            }

            RESERVATION_DATA -> {
                val reservation = codec.decodeReservation(value)
                val order = admission(ByteBuffer.wrap(rowKey, 1, Long.SIZE_BYTES).long).candidate.order
                check(
                    order.marketId == reservation.marketId && order.orderId == reservation.orderId,
                ) { "history snapshot reservation mismatch" }
            }

            TRANSACTION_DATA -> {
                check(ByteBuffer.wrap(rowKey, 1, Long.SIZE_BYTES).long <= ordinal) { "history snapshot transaction ordinal is ahead" }
                val transaction = codec.decodeTransaction(value)
                exactIndex(key(TRANSACTION_INDEX, transaction.ledgerTransactionId))
                exactIndex(key(SOURCE_INDEX, transaction.sourceEventId))
            }

            REQUEST_INDEX, ADMISSION_INDEX, COMPLETION_INDEX, PREPARATION_INDEX, TRANSACTION_INDEX, SOURCE_INDEX -> {
                val prefix =
                    when (rowKey[0]) {
                        REQUEST_INDEX -> REQUEST_DATA
                        ADMISSION_INDEX -> ADMISSION_DATA
                        COMPLETION_INDEX -> COMPLETION_DATA
                        PREPARATION_INDEX -> PREPARATION_DATA
                        else -> TRANSACTION_DATA
                    }
                check(value.size == (if (prefix == TRANSACTION_DATA) 17 else 9) && value[0] == prefix) { "invalid history snapshot index" }
                val target = checkNotNull(database.get(value)) { "history snapshot index target is missing" }
                val expected =
                    when (rowKey[0]) {
                        REQUEST_INDEX -> {
                            codec
                                .decodeStoredRequest(
                                    target,
                                    ::admission,
                                ).let { key(REQUEST_INDEX, it.order.userId.value, it.requestId) }
                        }

                        ADMISSION_INDEX -> {
                            codec.decodeAdmission(target).let {
                                key(ADMISSION_INDEX, it.candidate.order.marketId.value, it.candidate.order.orderId.value)
                            }
                        }

                        COMPLETION_INDEX -> {
                            numberKey(
                                COMPLETION_INDEX,
                                codec.decodeStoredCompletion(target, ::admission).admission.sequence,
                            )
                        }

                        PREPARATION_INDEX -> {
                            key(
                                PREPARATION_INDEX,
                                codec
                                    .decodePreparation(target)
                                    .result.opening.preparationId,
                            )
                        }

                        TRANSACTION_INDEX -> {
                            key(TRANSACTION_INDEX, codec.decodeTransaction(target).ledgerTransactionId)
                        }

                        else -> {
                            key(SOURCE_INDEX, codec.decodeTransaction(target).sourceEventId)
                        }
                    }
                check(rowKey.contentEquals(expected)) { "history snapshot index identity mismatch" }
            }

            METADATA -> {
                check(ByteBuffer.wrap(value).long == ordinal) { "history snapshot metadata mismatch" }
            }

            else -> {
                error("unknown history snapshot stored key")
            }
        }
    }

    override fun findRequest(
        userId: UserId,
        requestId: String,
    ): OrderAdmissionState.RequestRecord? =
        guarded {
            indexed(key(REQUEST_INDEX, userId.value, requestId), REQUEST_DATA)?.let(::decodeRequest)?.also {
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
            synchronized(monitor) { checkOperational() }
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
            synchronized(monitor) { checkOperational() }
            if (sequence <= 0) {
                null
            } else {
                indexed(numberKey(COMPLETION_INDEX, sequence), COMPLETION_DATA)?.let(::decodeCompletion)?.also {
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

    override fun requests(): Sequence<OrderAdmissionState.RequestRecord> = scan(REQUEST_DATA, ::decodeRequest)

    override fun admissions(): Sequence<OrderAdmissionState.Record> = scan(ADMISSION_DATA, codec::decodeAdmission)

    override fun completions(): Sequence<TradingState.Completion> = scan(COMPLETION_DATA, ::decodeCompletion)

    override fun preparations(): Sequence<OrderAdmissionState.PreparationRecord> = scan(PREPARATION_DATA, codec::decodePreparation)

    override fun reservations(): Sequence<OrderReservation> = scan(RESERVATION_DATA, codec::decodeReservation)

    override fun transactions(): Sequence<LedgerTransaction> = scan(TRANSACTION_DATA, codec::decodeTransaction)

    private fun encode(
        batch: TradingHistoryBatch,
        ownerProof: PreparedTradingHistoryBatch?,
    ): EncodedTransition {
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
            val accepted = it.result as? OrderAdmissionState.Result.Accepted
            val value =
                if (accepted != null && accepted.record === batch.admission) {
                    codec.encodeReferencedRequest(it)
                } else {
                    codec.encodeRequest(it)
                }
            put(target, value)
            put(key(REQUEST_INDEX, it.order.userId.value, it.requestId), target)
        }
        batch.admission?.let {
            val target = numberKey(ADMISSION_DATA, it.sequence)
            val value = codec.encodeAdmission(it)
            // 참조를 사용하려면 승인 순번마다 불변 원본이 하나여야 한다. 전이의 어떤 키도 게시하기 전에
            // 검증한다. 같은 원본을 다시 등록하는 것은 허용한다.
            val previous =
                if (ownerProof != null && it.sequence > maximumAdmissionSequence) {
                    // 최대 순번에는 가져와서 검증한 모든 과거 승인도 포함한다.
                    null
                } else {
                    read(target)
                }
            check(previous == null || previous.contentEquals(value)) { "history admission sequence changed its original value" }
            put(target, value)
            put(key(ADMISSION_INDEX, it.candidate.order.marketId.value, it.candidate.order.orderId.value), target)
        }
        var referencedCompletion: TradingState.Completion? = null
        batch.completion?.let {
            val target = numberKey(COMPLETION_DATA, batch.ordinal)
            val value =
                if (ownerProof != null || it.admission === batch.admission || canReferenceAdmission(it.admission)) {
                    referencedCompletion = it
                    codec.encodeReferencedCompletion(it)
                } else {
                    // 독립 호출 또는 표준 형태가 아닌 완료 묶음은 원래 전체 값을 유지한다.
                    codec.encodeCompletion(it)
                }
            put(target, value)
            put(numberKey(COMPLETION_INDEX, it.admission.sequence), target)
        }
        batch.preparation?.let {
            val target = numberKey(PREPARATION_DATA, batch.ordinal)
            put(target, codec.encodePreparation(it))
            put(key(PREPARATION_INDEX, it.result.opening.preparationId), target)
        }
        batch.reservations.forEachIndexed { index, reservation ->
            val sequence =
                ownerProof?.reservationSequences?.get(index) ?: (batch.admission ?: referencedCompletion?.admission)
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
            check(sequence > 0) { "reservation history admission sequence must be positive" }
            put(numberKey(RESERVATION_DATA, sequence), codec.encodeReservation(reservation))
        }
        batch.ledgerTransactions.forEachIndexed { index, transaction ->
            val target = numberKey(TRANSACTION_DATA, batch.ordinal, index.toLong())
            put(target, codec.encodeTransaction(transaction))
            put(key(TRANSACTION_INDEX, transaction.ledgerTransactionId), target)
            put(key(SOURCE_INDEX, transaction.sourceEventId), target)
        }
        // 영속 순번은 묶음의 모든 행·보조 인덱스와 함께 원자적으로 커밋한다.
        put(byteArrayOf(METADATA), ByteBuffer.allocate(Long.SIZE_BYTES).putLong(batch.ordinal).array())
        return EncodedTransition(puts, encodedBytes)
    }

    private fun canReferenceAdmission(record: OrderAdmissionState.Record): Boolean {
        synchronized(monitor) {
            checkUsable()
            if (admissionIdentities[record.sequence]?.get() === record) return true
        }
        val original = read(numberKey(ADMISSION_DATA, record.sequence)) ?: return false
        if (!original.contentEquals(codec.encodeAdmission(record))) {
            // 유효하지만 다른 원본이면 기존 전체 frame을 사용한다. 손상된 값을 우회 처리하지는 않는다.
            check(codec.decodeAdmission(original).sequence == record.sequence) { "history admission sequence mismatch" }
            return false
        }
        synchronized(monitor) { cacheAdmissionIdentity(record) }
        return true
    }

    private fun cacheAdmissionIdentity(record: OrderAdmissionState.Record) {
        if (limits.maximumCachedAdmissionIndexes == 0) return
        admissionIdentities[record.sequence] = WeakReference(record)
        while (admissionIdentities.size > limits.maximumCachedAdmissionIndexes) {
            val iterator = admissionIdentities.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    private fun decodeRequest(bytes: ByteArray): OrderAdmissionState.RequestRecord = codec.decodeStoredRequest(bytes) { findAdmission(it) }

    private fun decodeCompletion(bytes: ByteArray): TradingState.Completion = codec.decodeStoredCompletion(bytes) { findAdmission(it) }

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
            checkOperational()
            // 복원하거나 큐에 넣은 모든 키는 게시 전에 필터에 등록한다.
            // 없다는 결과는 확정적이며, 있다는 결과는 전체 키를 읽어 확인한다.
            if (!knownKeys.mightContain(key)) {
                null
            } else {
                val wrapped = Key(key)
                pendingValues[wrapped]?.value ?: admissionIndexes[wrapped] ?: database.get(key)?.also {
                    cacheAdmissionIndex(wrapped, it)
                }
            }
        }

    /** 정산 중 최근 주문과 순번의 인덱스를 정확히 재사용하며, 바이트 수와 항목 수에 한도를 둔다. */
    private fun cacheAdmissionIndex(
        key: Key,
        value: ByteArray,
    ) {
        if (key.bytes.first() != ADMISSION_INDEX) return
        val bytes = key.bytes.size.toLong() + value.size + ENTRY_OVERHEAD_BYTES
        val old = admissionIndexes.remove(key)
        if (old != null) admissionIndexBytes -= key.bytes.size.toLong() + old.size + ENTRY_OVERHEAD_BYTES
        if (limits.maximumCachedAdmissionIndexes == 0 || bytes > limits.maximumCachedAdmissionIndexBytes) return
        while (admissionIndexes.size >= limits.maximumCachedAdmissionIndexes ||
            bytes > limits.maximumCachedAdmissionIndexBytes - admissionIndexBytes
        ) {
            val iterator = admissionIndexes.entries.iterator()
            val first = iterator.next()
            val firstKeySize = first.key.bytes.size
            admissionIndexBytes -= firstKeySize.toLong() + first.value.size + ENTRY_OVERHEAD_BYTES
            iterator.remove()
        }
        admissionIndexes[key] = value
        admissionIndexBytes += bytes
    }

    /** 먼저 flush한 뒤 행마다 단기 iterator를 다시 열어, 순회를 중단해도 네이티브 핸들이 남지 않게 한다. */
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
                        // append도 이 모니터에 알림을 보낸다. 알림마다 전이 하나의 WAL을 동기화하지 않도록
                        // 기한까지 계속 전이를 모은다.
                        val groupDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1)
                        while (!flushRequested && !stopping && failure == null &&
                            queue.size < limits.maximumGroupBatches && queuedEncodedBytes < limits.targetGroupBytes
                        ) {
                            val remaining = groupDeadline - System.nanoTime()
                            if (remaining <= 0) break
                            TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
                        }
                        if (failure != null) return
                        val selected = ArrayList<Pending>()
                        var bytes = 0L
                        while (queue.isNotEmpty() && selected.size < limits.maximumGroupBatches) {
                            if (selected.isNotEmpty() && bytes + queue.first.bytes > limits.targetGroupBytes) break
                            val pending = queue.removeFirst()
                            selected.add(pending)
                            bytes += pending.bytes
                            queuedEncodedBytes -= pending.bytes
                        }
                        flushRequested = false
                        selected
                    }
                WriteBatch().use { write ->
                    val last = group.last()
                    group.forEach { pending ->
                        pending.puts.forEach { put ->
                            if (put.key.bytes[0] != METADATA || pending === last) write.put(put.key.bytes, put.value)
                        }
                    }
                    database.write(writeOptions, write)
                }
                synchronized(monitor) {
                    for (pending in group) {
                        for (put in pending.puts) pendingValues.remove(put.key, put)
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

    private fun checkOperational() {
        checkUsable()
        check(snapshotOperation == null) { "history snapshot operation is in progress" }
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
        // 종료 중 인터럽트를 받아도 소유 자원을 끝까지 정리하고, 호출자의 인터럽트 상태를 유지한다.
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
                admissionIndexes.clear()
                admissionIdentities.clear()
                admissionIndexBytes = 0
                queue.clear()
                queuedEncodedBytes = 0
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
        private const val SNAPSHOT_WRITTEN_BYTES: Byte = 21
        private const val ENTRY_OVERHEAD_BYTES = 128L
        private val WAIT_NANOS = TimeUnit.SECONDS.toNanos(30)

        fun create(
            directory: Path,
            limits: TradingHistoryStorageLimits = TradingHistoryStorageLimits(),
            onFatal: (Throwable) -> Unit = {
            },
        ): RocksDbTradingHistoryStore = open(directory, limits, onFatal, rebuildable = false)

        /**
         * 자체 WAL이나 강제 동기화가 없는 재생성 가능한 인덱스를 만든다. 호출자는 복구 원본인
         * Archive(fileSyncLevel=2)에 강제 동기화를 마친 커밋 입력만 적용하고, 필요한 로그와 완전한
         * Snapshot을 보존하며, 재시작할 때마다 새 세대를 재구성해야 한다.
         * 작성자의 성공 확인은 저장을 마쳤고 해당 원본에서 복구할 수 있다는 뜻이다.
         * 이 DB를 복구 원본으로 다시 열어서는 안 된다. 독립 사용자는 [create]를 사용한다.
         */
        internal fun createRebuildableIndex(
            directory: Path,
            limits: TradingHistoryStorageLimits = TradingHistoryStorageLimits(),
            onFatal: (Throwable) -> Unit = {},
        ): RocksDbTradingHistoryStore = open(directory, limits, onFatal, rebuildable = true)

        private fun open(
            directory: Path,
            limits: TradingHistoryStorageLimits,
            onFatal: (Throwable) -> Unit,
            rebuildable: Boolean,
        ): RocksDbTradingHistoryStore {
            RocksDB.loadLibrary()
            // 배타적으로 사용하는 새 세대가 필요하다. 기존 데이터로 현재 거래 소유자를 복원할 수 없다.
            Files.createDirectory(directory)
            val resources = ArrayList<AutoCloseable>()
            try {
                // 자동 샤딩은 32 MiB를 각각 512 KiB 한도가 있는 독립 샤드 64개로 나눈다.
                // 고정된 256 KiB 메모리 예약 때문에 캐시 대부분이 비어 있어도 샤드 하나가 먼저 찰 수 있다.
                // 샤드 하나를 사용해도 같은 엄격한 전체 바이트 한도를 유지한다.
                val cache = LRUCache(limits.cacheBytes, 0, true).also(resources::add)
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
                val writes = WriteOptions().setSync(!rebuildable).setDisableWAL(rebuildable).also(resources::add)
                val database = RocksDB.open(options, directory.toString()).also(resources::add)
                return RocksDbTradingHistoryStore(database, options, writes, cache, buffers, filter, limits, onFatal, rebuildable)
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
            var size = 1
            for (value in values) {
                size = Math.addExact(size, Math.addExact(Int.SIZE_BYTES, Math.multiplyExact(value.length, Char.SIZE_BYTES)))
            }
            val buffer = ByteBuffer.allocate(size).put(prefix)
            for (value in values) {
                buffer.putInt(value.length)
                for (character in value) buffer.putChar(character)
            }
            return buffer.array()
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

/** 고정 8 MiB 부재 조회 필터다. 포화되어도 정확한 조회만 늘어나며 이력이 누락되지는 않는다. */
internal class TradingHistoryKeyFilter(
    wordCount: Int = 1 shl 20,
) {
    init {
        require(wordCount > 0 && wordCount <= (1 shl 20) && wordCount and (wordCount - 1) == 0)
    }

    private val words = LongArray(wordCount)
    private val mask = wordCount * Long.SIZE_BITS - 1

    fun add(key: ByteArray) {
        var hash = hash(key)
        val step = (hash ushr 32).toInt() or 1
        repeat(4) {
            val bit = hash.toInt() and mask
            words[bit ushr 6] = words[bit ushr 6] or (1L shl (bit and 63))
            hash += step
        }
    }

    fun mightContain(key: ByteArray): Boolean {
        var hash = hash(key)
        val step = (hash ushr 32).toInt() or 1
        repeat(4) {
            val bit = hash.toInt() and mask
            if (words[bit ushr 6] and (1L shl (bit and 63)) == 0L) return false
            hash += step
        }
        return true
    }

    private fun hash(key: ByteArray): Long {
        var hash = -3750763034362895579L
        for (value in key) hash = (hash xor (value.toLong() and 255)) * 1099511628211L
        hash = (hash xor (hash ushr 33)) * -49064778989728563L
        hash = (hash xor (hash ushr 33)) * -4265267296055464877L
        return hash xor (hash ushr 33)
    }
}
