package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.ExclusivePublication
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.Publication
import io.aeron.cluster.service.Cluster
import org.agrona.concurrent.UnsafeBuffer
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Snapshot bytes are streamed in small Aeron messages; no whole-recording buffer is retained. */
internal class AeronSnapshotOutput(
    private val publication: ExclusivePublication,
    private val cluster: Cluster,
) : OutputStream() {
    private val bytes = ByteArray(SNAPSHOT_CHUNK_BYTES)
    private val buffer = UnsafeBuffer(bytes)
    private var length = 0

    override fun write(value: Int) {
        bytes[length++] = value.toByte()
        if (length == bytes.size) flush()
    }

    override fun write(
        source: ByteArray,
        offset: Int,
        count: Int,
    ) {
        java.util.Objects.checkFromIndexSize(offset, count, source.size)
        var position = offset
        val end = offset + count
        while (position < end) {
            val copied = minOf(end - position, bytes.size - length)
            source.copyInto(bytes, length, position, position + copied)
            length += copied
            position += copied
            if (length == bytes.size) flush()
        }
    }

    override fun flush() {
        if (length == 0) return
        val deadline = System.nanoTime() + SNAPSHOT_PROGRESS_TIMEOUT_NS
        while (true) {
            val result = publication.offer(buffer, 0, length)
            if (result > 0) {
                length = 0
                return
            }
            check(result != Publication.CLOSED && result != Publication.MAX_POSITION_EXCEEDED) {
                "snapshot publication cannot progress: $result"
            }
            check(!Thread.currentThread().isInterrupted && System.nanoTime() - deadline < 0) {
                "snapshot publication did not make progress"
            }
            cluster.idleStrategy().idle()
        }
    }
}

internal class AeronSnapshotInput(
    private val image: Image,
    private val cluster: Cluster,
) : InputStream() {
    private val chunks = ArrayDeque<ByteArray>()
    private var current = ByteArray(0)
    private var offset = 0
    private val assembler =
        FragmentAssembler { buffer, start, length, _ ->
            check(length in 1..SNAPSHOT_CHUNK_BYTES) { "invalid snapshot chunk length" }
            chunks.addLast(ByteArray(length).also { buffer.getBytes(start, it) })
        }

    override fun read(): Int = if (availableChunk()) current[offset++].toInt() and 0xff else -1

    override fun read(
        target: ByteArray,
        start: Int,
        count: Int,
    ): Int {
        java.util.Objects.checkFromIndexSize(start, count, target.size)
        if (count == 0) return 0
        if (!availableChunk()) return -1
        val copied = minOf(count, current.size - offset)
        current.copyInto(target, start, offset, offset + copied)
        offset += copied
        return copied
    }

    private fun availableChunk(): Boolean {
        if (offset < current.size) return true
        val deadline = System.nanoTime() + SNAPSHOT_PROGRESS_TIMEOUT_NS
        while (chunks.isEmpty()) {
            val work = image.poll(assembler, 8)
            if (chunks.isNotEmpty()) break
            if (image.isEndOfStream) return false
            check(!image.isClosed) { "snapshot image closed before end of recording" }
            check(!Thread.currentThread().isInterrupted && System.nanoTime() - deadline < 0) {
                "snapshot image did not make progress"
            }
            cluster.idleStrategy().idle(work)
        }
        current = chunks.removeFirst()
        offset = 0
        return true
    }
}

private const val SNAPSHOT_CHUNK_BYTES = 4_096
private val SNAPSHOT_PROGRESS_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(30)
