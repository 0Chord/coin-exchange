package com.exchange.core.api.trading.infrastructure.aeron

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/**
 * Test-observer storage only. The disk mode retains executionId -> (Long offset, Int length),
 * never proof payloads. Every take returns ALL original proof bytes for the existing field-by-field
 * financial comparison. Digests protect the spool; they do not establish financial equivalence.
 * One writer finishes before matching starts. No production history or checkpoint is changed.
 * Spools remain beside the run evidence. close only flushes/closes the opened handle; it never
 * deletes a pathname, whose entry could have been replaced after an identity check.
 */
internal class HttpPerformanceCompletionProofStore private constructor(
    private val maximumProofs: Int,
    private val maximumEventProofBytes: Int,
    private val disk: Disk?,
) : AutoCloseable {
    constructor(maximumProofs: Int, maximumEventProofBytes: Int) : this(maximumProofs, maximumEventProofBytes, null)

    private data class Entry(
        val offset: Long,
        val length: Int,
        val bytes: ByteArray?,
        val originalSha256: ByteArray?,
    )

    private val pending = HashMap<String, Entry>()
    private var appended = 0
    private var matchingStarted = false
    private var closed = false
    private var failed = false

    init {
        require(maximumProofs > 0 && maximumEventProofBytes in 1..1_048_576)
    }

    val size: Int get() = pending.size

    fun contains(id: String): Boolean = pending.containsKey(id)

    fun append(
        id: String,
        bytes: ByteArray,
    ) = guarded {
        check(!matchingStarted) { "매칭 시작 후 완료 proof 추가" }
        check(id.length <= MAX_ID_CHARS) { "완료 proof 식별자 길이 상한 초과" }
        check(!pending.containsKey(id)) { "완료 executionId 중복: $id" }
        check(appended < maximumProofs) { "완료 proof 누적 행 상한 초과" }
        check(bytes.size <= maximumEventProofBytes) { "한 완료의 exact 사건 proof 상한 초과: ${bytes.size}" }
        val originalSha256 = disk?.let { MessageDigest.getInstance("SHA-256").digest(bytes) }
        val offset = disk?.append(id, bytes, checkNotNull(originalSha256)) ?: -1L
        pending[id] = Entry(offset, bytes.size, if (disk == null) bytes.copyOf() else null, originalSha256)
        appended = Math.incrementExact(appended)
    }

    fun take(id: String): ByteArray =
        guarded {
            matchingStarted = true
            val entry = checkNotNull(pending[id]) { "매칭에 해당하는 완료 누락: $id" }
            val bytes = disk?.read(id, entry.offset, entry.length, checkNotNull(entry.originalSha256)) ?: checkNotNull(entry.bytes)
            pending.remove(id)
            bytes
        }

    fun verifyComplete() =
        guarded {
            check(pending.isEmpty()) { "완료에 해당하는 매칭 결과 누락" }
            matchingStarted = true
            disk?.verifyComplete()
        }

    private inline fun <T> guarded(block: () -> T): T {
        check(!closed && !failed) { "종료되었거나 실패한 완료 proof 저장소" }
        try {
            return block()
        } catch (error: Throwable) {
            failed = true
            throw error
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        disk?.close()
    }

    companion object {
        private const val MAX_ID_CHARS = 256
        private const val MAX_ID_BYTES = MAX_ID_CHARS * 4
        private const val FRAME_MAGIC = 0x48504331 // HPC1: versioned frame, not JSON.
        private const val HEADER_BYTES = 12
        private const val DIGEST_BYTES = 32

        fun disk(
            directory: Path,
            maximumProofs: Int,
            maximumEventProofBytes: Int,
        ): HttpPerformanceCompletionProofStore {
            require(maximumProofs > 0 && maximumEventProofBytes in 1..1_048_576)
            val maximumFileBytes =
                Math.multiplyExact(
                    maximumProofs.toLong(),
                    Math.addExact((HEADER_BYTES + MAX_ID_BYTES + DIGEST_BYTES).toLong(), maximumEventProofBytes.toLong()),
                )
            val file = directory.resolve(".http-performance-completion-proofs-${UUID.randomUUID()}.bin")
            // CREATE_NEW is essential: never truncate, reuse, or claim a prior session's spool.
            val channel = FileChannel.open(file, CREATE_NEW, READ, WRITE, NOFOLLOW_LINKS)
            try {
                val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(attributes.isRegularFile && attributes.size() == 0L) { "잘못된 완료 proof 파일" }
                val ownedKey = checkNotNull(attributes.fileKey()) { "완료 proof 파일 식별자를 확인할 수 없습니다" }
                return HttpPerformanceCompletionProofStore(
                    maximumProofs,
                    maximumEventProofBytes,
                    Disk(file, ownedKey, channel, maximumEventProofBytes, maximumFileBytes),
                )
            } catch (original: Throwable) {
                try {
                    channel.close()
                } catch (error: Throwable) {
                    original.addSuppressed(error)
                }
                // Retain the newly-created pathname as evidence; never delete a replacement.
                throw original
            }
        }

        private fun ownedAttributes(
            file: Path,
            fileKey: Any,
        ): BasicFileAttributes =
            Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS).also {
                check(it.isRegularFile && it.fileKey() == fileKey) { "완료 proof 파일 소유권 변경: $file" }
            }
    }

    private class Disk(
        private val file: Path,
        private val fileKey: Any,
        private val channel: FileChannel,
        private val maximumEventProofBytes: Int,
        private val maximumFileBytes: Long,
    ) : AutoCloseable {
        private val writtenDigest = MessageDigest.getInstance("SHA-256")
        private var expectedDigest: ByteArray? = null
        private var writtenBytes = 0L

        fun append(
            id: String,
            proof: ByteArray,
            proofDigest: ByteArray,
        ): Long {
            check(expectedDigest == null) { "닫힌 완료 proof append 단계" }
            requireSize()
            val idBytes = id.toByteArray(Charsets.UTF_8)
            check(idBytes.size <= MAX_ID_BYTES)
            val header =
                ByteBuffer
                    .allocate(HEADER_BYTES)
                    .putInt(FRAME_MAGIC)
                    .putInt(idBytes.size)
                    .putInt(proof.size)
                    .array()
            val start = writtenBytes
            val end = boundedEnd(start, HEADER_BYTES.toLong() + idBytes.size + proof.size + DIGEST_BYTES)
            var position = start
            for (part in listOf(header, idBytes, proof, proofDigest)) {
                writeFully(ByteBuffer.wrap(part), position)
                position = Math.addExact(position, part.size.toLong())
                writtenDigest.update(part)
            }
            check(position == end)
            writtenBytes = end
            return start
        }

        fun read(
            id: String,
            offset: Long,
            length: Int,
            originalSha256: ByteArray,
        ): ByteArray {
            seal()
            requireSize()
            check(length in 0..maximumEventProofBytes)
            val expectedId = id.toByteArray(Charsets.UTF_8)
            val end = boundedEnd(offset, HEADER_BYTES.toLong() + expectedId.size + length + DIGEST_BYTES)
            check(end <= writtenBytes) { "완료 proof 인덱스가 파일 범위를 벗어났습니다" }
            val header = ByteBuffer.allocate(HEADER_BYTES)
            readFully(header, offset)
            header.flip()
            check(header.int == FRAME_MAGIC && header.int == expectedId.size && header.int == length) { "완료 proof frame 불일치" }
            var position = Math.addExact(offset, HEADER_BYTES.toLong())
            val storedId = ByteArray(expectedId.size)
            readFully(ByteBuffer.wrap(storedId), position)
            check(storedId.contentEquals(expectedId)) { "완료 proof executionId 불일치" }
            position = Math.addExact(position, storedId.size.toLong())
            val proof = ByteArray(length) // At most maximumEventProofBytes; no whole-file read.
            readFully(ByteBuffer.wrap(proof), position)
            position = Math.addExact(position, length.toLong())
            val storedDigest = ByteArray(DIGEST_BYTES)
            readFully(ByteBuffer.wrap(storedDigest), position)
            check(
                MessageDigest.isEqual(storedDigest, originalSha256) &&
                    MessageDigest.isEqual(originalSha256, MessageDigest.getInstance("SHA-256").digest(proof)),
            ) { "완료 proof 원본 SHA 불일치" }
            requireSize()
            return proof
        }

        fun verifyComplete() {
            seal()
            requireSize()
            val actual = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(64 * 1024)
            var offset = 0L
            while (offset < writtenBytes) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), writtenBytes - offset).toInt())
                val count = buffer.remaining()
                readFully(buffer, offset)
                actual.update(buffer.array(), 0, count)
                offset = Math.addExact(offset, count.toLong())
            }
            check(channel.read(ByteBuffer.allocate(1), writtenBytes) == -1) { "완료 proof EOF 뒤 추가 바이트" }
            requireSize()
            check(MessageDigest.isEqual(checkNotNull(expectedDigest), actual.digest())) { "완료 proof 전체 파일 SHA 불일치" }
        }

        private fun seal() {
            if (expectedDigest == null) {
                channel.force(false) // Write/flush errors must fail before any successful verdict.
                expectedDigest = writtenDigest.digest()
            }
        }

        private fun boundedEnd(
            offset: Long,
            length: Long,
        ): Long {
            check(offset >= 0L && length >= 0L)
            return Math.addExact(offset, length).also {
                check(it <= maximumFileBytes) { "완료 proof 파일 byte/offset 상한 초과" }
            }
        }

        private fun requireSize() {
            check(ownedAttributes(file, fileKey).size() == writtenBytes && channel.size() == writtenBytes) { "완료 proof 파일 절단 또는 길이 변경" }
        }

        private fun writeFully(
            buffer: ByteBuffer,
            start: Long,
        ) {
            var position = start
            while (buffer.hasRemaining()) {
                val count = channel.write(buffer, position)
                check(count > 0) { "완료 proof 파일 쓰기 진행 실패" }
                position = Math.addExact(position, count.toLong())
            }
        }

        private fun readFully(
            buffer: ByteBuffer,
            start: Long,
        ) {
            var position = start
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer, position)
                check(count > 0) { "완료 proof 파일 절단 또는 읽기 진행 실패" }
                position = Math.addExact(position, count.toLong())
            }
        }

        override fun close() {
            var failure: Throwable? = null

            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    val original = failure
                    if (original == null) failure = error else original.addSuppressed(error)
                }
            }
            attempt { channel.force(false) }
            attempt { channel.close() }
            failure?.let { throw it }
        }
    }
}
