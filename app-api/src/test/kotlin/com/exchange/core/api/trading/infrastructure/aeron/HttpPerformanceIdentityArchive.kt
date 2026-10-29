package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

internal data class HttpPerformanceIdentityArchiveLimits(
    val pendingCapacity: Int = HTTP_PERFORMANCE_CALLBACK_CAPACITY,
    val queueCapacity: Int = HTTP_PERFORMANCE_CALLBACK_CAPACITY,
    val maximumRecordBytes: Int = 4096,
    val maximumFileBytes: Long = 512L * 1024 * 1024,
    val closeTimeoutMs: Long = 5000,
) {
    init {
        require(pendingCapacity in 1..HTTP_PERFORMANCE_CALLBACK_CAPACITY)
        require(queueCapacity in 1..pendingCapacity)
        require(maximumRecordBytes in 36..65_536)
        require(maximumFileBytes in maximumRecordBytes.toLong()..(4L * 1024 * 1024 * 1024))
        require(closeTimeoutMs in 1..30_000)
    }
}

/**
 * Test observer only. The sole writer opens/writes/flushes/closes a CREATE_NEW evidence file.
 * A callback only checks lengths and offers an immutable Accepted reference to a bounded queue.
 * Acknowledgements contain primitives; the collector alone updates its index and releases payloads.
 * Cold replay reads the full original UTF-16 fields, never a digest or a shortened identity.
 * A frame CRC detects byte damage; identity equivalence still compares every original field.
 */
internal class HttpPerformanceIdentityArchive(
    val evidencePath: Path,
    val limits: HttpPerformanceIdentityArchiveLimits = HttpPerformanceIdentityArchiveLimits(),
    private val openChannel: (Path) -> FileChannel = { FileChannel.open(it, CREATE_NEW, READ, WRITE, NOFOLLOW_LINKS) },
    startImmediately: Boolean = true,
) : AutoCloseable {
    internal data class Acknowledgement(
        val sequence: Long,
        val offset: Long,
    )

    private val queue = ArrayBlockingQueue<AdmissionResultPayload.Accepted>(limits.queueCapacity)
    private val acknowledgements = ArrayBlockingQueue<Acknowledgement>(limits.pendingCapacity)
    private val accepting = AtomicBoolean(true)
    private val started = AtomicBoolean()
    private val failure = AtomicReference<String?>()
    private val worker = Thread(::writeLoop, "http-performance-identity-writer").apply { isDaemon = true }

    @Volatile
    private var channel: FileChannel? = null

    @Volatile
    var writtenBytes: Long = 0
        private set

    val error: String? get() = failure.get()
    val queuedCount: Int get() = queue.size
    val acknowledgedCount: Int get() = acknowledgements.size

    init {
        if (startImmediately) start()
    }

    /** Kept explicit so a regression can deterministically fill the bounded queue before writer startup. */
    internal fun start() {
        check(accepting.get()) { "닫힌 Accepted 원문 저장소" }
        if (started.compareAndSet(false, true)) worker.start()
    }

    fun offer(accepted: AdmissionResultPayload.Accepted) {
        checkHealthy()
        check(accepting.get()) { "닫힌 Accepted 원문 저장소" }
        require(recordBytes(accepted) <= limits.maximumRecordBytes) { "Accepted 원문 record byte 상한 초과" }
        check(queue.offer(accepted)) { "Accepted 원문 파일 queue 초과: capacity=${limits.queueCapacity}" }
    }

    fun pollAcknowledgement(): Acknowledgement? = acknowledgements.poll()

    fun checkHealthy() {
        check(error == null) { checkNotNull(error) }
    }

    /** Only old, acknowledged identities enter this synchronous read path. */
    fun read(
        sequence: Long,
        offset: Long,
    ): AdmissionResultPayload.Accepted {
        checkHealthy()
        check(accepting.get()) { "닫힌 Accepted 원문 저장소" }
        try {
            val source = checkNotNull(channel) { "Accepted 원문 파일이 아직 열리지 않았습니다" }
            check(offset >= 0 && offset <= writtenBytes - HEADER_BYTES) { "Accepted 원문 offset 범위 오류" }
            val header = ByteBuffer.allocate(HEADER_BYTES)
            readFully(source, header, offset)
            header.flip()
            check(header.int == MAGIC) { "Accepted 원문 frame 식별 오류" }
            val length = header.int
            check(length in MINIMUM_RECORD_BYTES..limits.maximumRecordBytes) { "Accepted 원문 frame 길이 오류" }
            check(header.long == sequence) { "Accepted 원문 순번 불일치" }
            check(length.toLong() <= writtenBytes - offset) { "Accepted 원문 frame 절단" }
            val fields = ByteBuffer.allocate(length - HEADER_BYTES)
            readFully(source, fields, offset + HEADER_BYTES)
            fields.flip()
            val checksumOffset = fields.limit() - CHECKSUM_BYTES
            val checksum = CRC32()
            checksum.update(header.array())
            checksum.update(fields.array(), 0, checksumOffset)
            check(fields.getInt(checksumOffset) == checksum.value.toInt()) { "Accepted 원문 frame CRC32 불일치" }
            fields.limit(checksumOffset)

            fun field(): String {
                check(fields.remaining() >= Int.SIZE_BYTES) { "Accepted 원문 필드 길이 누락" }
                val chars = fields.int
                check(chars >= 0 && chars <= fields.remaining() / Char.SIZE_BYTES) { "Accepted 원문 필드 범위 오류" }
                return CharArray(chars) { fields.char }.concatToString()
            }
            val value = AdmissionResultPayload.Accepted(field(), field(), field(), field(), sequence)
            check(!fields.hasRemaining()) { "Accepted 원문 frame 뒤 추가 데이터" }
            return value
        } catch (error: Throwable) {
            fail("읽기", error)
            throw error
        }
    }

    private fun writeLoop() {
        try {
            val target = openChannel(evidencePath)
            channel = target
            try {
                val batch = ByteBuffer.allocate(maxOf(BATCH_BYTES, limits.maximumRecordBytes))
                while (accepting.get() || queue.isNotEmpty()) {
                    // One positional write covers a bounded group; all original frame boundaries remain intact.
                    val written = appendBatch(target, batch)
                    for (acknowledgement in written) {
                        check(acknowledgements.offer(acknowledgement)) { "Accepted 원문 acknowledgement queue 초과" }
                    }
                }
            } finally {
                try {
                    target.force(false)
                } finally {
                    target.close()
                }
            }
        } catch (error: Throwable) {
            fail("쓰기·종료", error)
        } finally {
            accepting.set(false)
            queue.clear()
        }
    }

    private fun appendBatch(
        target: FileChannel,
        batch: ByteBuffer,
    ): List<Acknowledgement> {
        var accepted = queue.poll(10, TimeUnit.MILLISECONDS) ?: return emptyList()
        batch.clear()
        val written = ArrayList<Acknowledgement>(MAX_BATCH_RECORDS)
        val start = writtenBytes
        while (true) {
            val offset = Math.addExact(start, batch.position().toLong())
            appendFrame(batch, accepted, offset)
            written += Acknowledgement(accepted.sequence, offset)
            if (written.size == MAX_BATCH_RECORDS) break
            val next = queue.peek() ?: break
            if (recordBytes(next) > batch.remaining()) break
            accepted = checkNotNull(queue.poll())
        }
        batch.flip()
        var position = start
        while (batch.hasRemaining()) {
            val count = target.write(batch, position)
            check(count > 0) { "Accepted 원문 파일 쓰기 진행 실패" }
            position = Math.addExact(position, count.toLong())
        }
        writtenBytes = position
        // The complete batch is readable before acknowledgements escape. Shutdown force failures still invalidate STOPPED.
        return written
    }

    private fun appendFrame(
        batch: ByteBuffer,
        accepted: AdmissionResultPayload.Accepted,
        offset: Long,
    ) {
        val length = recordBytes(accepted)
        check(length <= limits.maximumRecordBytes) { "Accepted 원문 record byte 상한 초과" }
        check(length <= limits.maximumFileBytes - offset) { "Accepted 원문 file byte 상한 초과" }
        val frameStart = batch.position()
        batch.putInt(MAGIC).putInt(length.toInt()).putLong(accepted.sequence)
        for (field in arrayOf(accepted.userId, accepted.requestId, accepted.marketId, accepted.orderId)) {
            batch.putInt(field.length)
            for (character in field) batch.putChar(character)
        }
        val checksum = CRC32()
        checksum.update(batch.array(), frameStart, batch.position() - frameStart)
        batch.putInt(checksum.value.toInt())
    }

    override fun close() {
        accepting.set(false)
        if (started.get()) {
            try {
                worker.join(limits.closeTimeoutMs)
                if (worker.isAlive) {
                    fail("종료", IllegalStateException("Accepted 원문 writer 종료 시간 초과: ${limits.closeTimeoutMs}ms"))
                    worker.interrupt()
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                fail("종료", error)
                worker.interrupt()
            }
        }
        queue.clear()
        checkHealthy()
    }

    private fun fail(
        operation: String,
        error: Throwable,
    ) {
        failure.compareAndSet(null, "Accepted 원문 $operation 실패: ${error.javaClass.simpleName}: ${error.message}")
        accepting.set(false)
    }

    private fun recordBytes(accepted: AdmissionResultPayload.Accepted): Long =
        MINIMUM_RECORD_BYTES.toLong() +
            Char.SIZE_BYTES.toLong() *
            (accepted.userId.length.toLong() + accepted.requestId.length + accepted.marketId.length + accepted.orderId.length)

    private fun readFully(
        source: FileChannel,
        buffer: ByteBuffer,
        start: Long,
    ) {
        var position = start
        while (buffer.hasRemaining()) {
            val count = source.read(buffer, position)
            check(count > 0) { "Accepted 원문 파일 절단 또는 읽기 진행 실패" }
            position = Math.addExact(position, count.toLong())
        }
    }

    private companion object {
        const val BATCH_BYTES = 64 * 1024
        const val MAX_BATCH_RECORDS = 256
        const val MAGIC = 0x48504931 // HPI1: original Java string code units, including unpaired surrogates.
        const val HEADER_BYTES = 16
        const val CHECKSUM_BYTES = Int.SIZE_BYTES
        const val MINIMUM_RECORD_BYTES = HEADER_BYTES + 4 * Int.SIZE_BYTES + CHECKSUM_BYTES
    }
}
