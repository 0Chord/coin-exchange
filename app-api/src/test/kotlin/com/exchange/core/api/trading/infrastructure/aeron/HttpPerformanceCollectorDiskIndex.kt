package com.exchange.core.api.trading.infrastructure.aeron

import org.rocksdb.BlockBasedTableConfig
import org.rocksdb.LRUCache
import org.rocksdb.Options
import org.rocksdb.RocksDB
import org.rocksdb.WriteOptions
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * 관찰 원문의 위치·완료 여부도 디스크에 둔다. 누적 순번별 JVM Map을 만들지 않는다.
 * 원문 journal의 보조 인덱스이며 재시작 복구에 사용하지 않는다. 실행이 중단되면 측정은 실패다.
 */
internal class HttpPerformanceCollectorDiskIndex(
    path: Path,
) : AutoCloseable {
    private val cache: LRUCache
    private val options: Options
    private val writes: WriteOptions
    private val db: RocksDB
    private val recent =
        object : LinkedHashMap<Long, Entry>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean = size > CACHE_ENTRIES
        }
    private var closed = false

    private data class Entry(
        val offset: Long = -1,
        val completed: Boolean = false,
    )

    val retainedCount: Int get() = recent.size

    init {
        RocksDB.loadLibrary()
        Files.createDirectory(path)
        cache = LRUCache(4L * 1024 * 1024, -1, true)
        options =
            Options()
                .setCreateIfMissing(true)
                .setErrorIfExists(true)
                .setWriteBufferSize(2L * 1024 * 1024)
                .setMaxWriteBufferNumber(2)
                .setMaxOpenFiles(32)
                .setTableFormatConfig(BlockBasedTableConfig().setBlockCache(cache).setCacheIndexAndFilterBlocks(true))
        writes = WriteOptions().setDisableWAL(true)
        try {
            db = RocksDB.open(options, path.toString())
        } catch (failure: Throwable) {
            writes.close()
            options.close()
            cache.close()
            throw failure
        }
    }

    fun offset(sequence: Long): Long = entry(sequence).offset

    fun completed(sequence: Long): Boolean = entry(sequence).completed

    fun recordOffset(
        sequence: Long,
        offset: Long,
    ) {
        require(offset >= 0)
        val previous = entry(sequence)
        check(previous.offset == -1L) { "중복 Accepted 저장 확인" }
        put(sequence, previous.copy(offset = offset))
    }

    fun markCompleted(sequence: Long): Boolean {
        val previous = entry(sequence)
        if (previous.completed) return false
        put(sequence, previous.copy(completed = true))
        return true
    }

    private fun entry(sequence: Long): Entry {
        check(!closed)
        recent[sequence]?.let { return it }
        val bytes = db.get(key(sequence)) ?: return Entry()
        check(bytes.size == 9) { "완료 표식 인덱스 길이 오류" }
        val value = ByteBuffer.wrap(bytes)
        val offset = value.long
        val flag = value.get().toInt()
        check(offset >= -1 && flag in 0..1) { "완료 표식 인덱스 값 오류" }
        return Entry(offset, flag == 1).also { recent[sequence] = it }
    }

    private fun put(
        sequence: Long,
        entry: Entry,
    ) {
        db.put(
            writes,
            key(sequence),
            ByteBuffer
                .allocate(9)
                .putLong(entry.offset)
                .put(if (entry.completed) 1.toByte() else 0.toByte())
                .array(),
        )
        recent[sequence] = entry
    }

    private fun key(sequence: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(sequence).array()

    override fun close() {
        if (closed) return
        closed = true
        try {
            db.close()
        } finally {
            writes.close()
            options.close()
            cache.close()
            recent.clear()
        }
    }

    companion object {
        const val CACHE_ENTRIES = 1024
    }
}
