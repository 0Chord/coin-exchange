package com.exchange.core.api.trading.infrastructure.aeron

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLongArray
import java.util.zip.CRC32

/** Test evidence only. One sender writes; snapshots decode bounded batches after the timing window. */
internal class HttpPerformanceMarkerJournal(
    private val path: Path,
    capacity: Int,
    private val maximumBytes: Long = 256L * 1024 * 1024,
) : AutoCloseable {
    internal data class Record(
        val ordinal: Int,
        val marker: HttpPerformanceCompletion,
        val status: HttpPerformanceMarkerOutbox.Status,
    )

    internal data class Written(
        val ordinal: Int,
        val offset: Long,
        val end: Long,
    )

    // Payloads are on disk. This bounded plan index uses eight bytes per offered identity.
    private val offsets = AtomicLongArray(capacity)
    private val channel: FileChannel
    private var writtenBytes = 8L
    private var publishedBytes = 8L
    private var closed = false
    private val encoder = HttpPerformanceMarkerCodec.Encoder()

    init {
        require(capacity > 0 && maximumBytes >= 8)
        Files.createDirectories(requireNotNull(path.parent))
        channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)
        try {
            writeFully(
                ByteBuffer
                    .allocate(8)
                    .putInt(MAGIC)
                    .putInt(1)
                    .flip(),
                0,
            )
        } catch (failure: Throwable) {
            channel.close()
            throw failure
        }
    }

    /** Called on the existing sender thread, never on the business callback. */
    fun append(record: Record): Written {
        check(!closed)
        require(record.ordinal in 0 until offsets.length())
        val payload = encoder.encode(record.marker)
        val bytes = ByteBuffer.allocate(16 + payload.size)
        bytes.putInt(record.ordinal).putInt(record.status.ordinal).putInt(payload.size)
        bytes.put(payload).putInt(CRC32().apply { update(bytes.array(), 0, 12 + payload.size) }.value.toInt()).flip()
        val end = Math.addExact(writtenBytes, bytes.remaining().toLong())
        check(end <= maximumBytes) { "완료 표식 증거 파일 한도 초과: maximumBytes=$maximumBytes" }
        val offset = writtenBytes
        writeFully(bytes, offset)
        writtenBytes = end
        return Written(record.ordinal, offset, end)
    }

    /** Publication and removing the corresponding hot Pending happen under the outbox lock. */
    fun publish(written: Written) {
        check(offsets.get(written.ordinal) == 0L) { "완료 표식 증거가 이미 저장됐습니다" }
        offsets.set(written.ordinal, written.offset)
        publishedBytes = written.end
    }

    fun snapshot(
        count: Int,
        sentCount: Int,
        live: List<Record>,
    ): Pair<List<HttpPerformanceCompletion>, List<Map<String, Any?>>> {
        val limit = publishedBytes
        val remaining = live.associateBy { it.ordinal }

        fun records(): Iterator<Record> =
            object : Iterator<Record> {
                private var cursor = 0
                private val batch = ArrayDeque<Record>()

                override fun hasNext(): Boolean = batch.isNotEmpty() || cursor < count

                override fun next(): Record {
                    if (batch.isEmpty()) {
                        if (cursor == count) throw NoSuchElementException()
                        // No file descriptor escapes a batch, including partial iteration or JSON failure.
                        FileChannel.open(path, StandardOpenOption.READ).use { input ->
                            check(input.size() >= limit) { "완료 표식 증거 파일이 잘렸습니다" }
                            val prefix = ByteBuffer.allocate(8)
                            readFully(input, prefix, 0)
                            prefix.flip()
                            check(prefix.int == MAGIC && prefix.int == 1) { "완료 표식 증거 파일 형식이 다릅니다" }
                            repeat(minOf(64, count - cursor)) {
                                val ordinal = cursor++
                                val offset = offsets.get(ordinal)
                                batch.addLast(
                                    if (offset != 0L && offset < limit) {
                                        read(input, ordinal, offset, limit)
                                    } else {
                                        checkNotNull(remaining[ordinal]) { "완료 표식 원문 증거가 누락됐습니다: ordinal=$ordinal" }
                                    },
                                )
                            }
                        }
                    }
                    return batch.removeFirst()
                }
            }

        val sent =
            evidenceList(sentCount) {
                records()
                    .asSequence()
                    .filter { it.status == HttpPerformanceMarkerOutbox.Status.SENT }
                    .map { it.marker }
                    .iterator()
            }
        val undelivered =
            evidenceList(count - sentCount) {
                records()
                    .asSequence()
                    .filter { it.status != HttpPerformanceMarkerOutbox.Status.SENT }
                    .map { mapOf<String, Any?>("marker" to it.marker, "status" to it.status.name) }
                    .iterator()
            }
        return sent to undelivered
    }

    private fun read(
        input: FileChannel,
        ordinal: Int,
        offset: Long,
        limit: Long,
    ): Record {
        val header = ByteBuffer.allocate(12)
        readFully(input, header, offset)
        header.flip()
        check(header.int == ordinal) { "완료 표식 증거 순서가 다릅니다" }
        val status = header.int
        val length = header.int
        check(status in HttpPerformanceMarkerOutbox.Status.entries.indices && length in 1..2048)
        check(offset + 16 + length <= limit) { "완료 표식 증거 경계가 다릅니다" }
        val body = ByteBuffer.allocate(length + 4)
        readFully(input, body, offset + 12)
        body.flip()
        val payload = ByteArray(length)
        body.get(payload)
        val checksum =
            CRC32()
                .apply {
                    update(header.array())
                    update(payload)
                }.value
                .toInt()
        check(body.int == checksum) { "완료 표식 증거 지문이 다릅니다" }
        return Record(ordinal, HttpPerformanceMarkerCodec.decode(payload), HttpPerformanceMarkerOutbox.Status.entries[status])
    }

    private fun writeFully(
        buffer: ByteBuffer,
        offset: Long,
    ) {
        var position = offset
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer, position)
            check(written > 0) { "완료 표식 증거 쓰기가 진행되지 않았습니다" }
            position += written
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            channel.force(false)
        } finally {
            channel.close()
        }
    }

    private companion object {
        const val MAGIC = 0x4D4A3134

        fun readFully(
            channel: FileChannel,
            buffer: ByteBuffer,
            offset: Long,
        ) {
            var position = offset
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                check(read > 0) { "완료 표식 증거가 누락됐습니다" }
                position += read
            }
        }

        fun <T> evidenceList(
            count: Int,
            records: () -> Iterator<T>,
        ): List<T> =
            object : AbstractList<T>() {
                override val size: Int = count

                override fun iterator(): Iterator<T> = records()

                override fun get(index: Int): T {
                    if (index !in indices) throw IndexOutOfBoundsException(index)
                    return records().asSequence().drop(index).first()
                }
            }
    }
}
