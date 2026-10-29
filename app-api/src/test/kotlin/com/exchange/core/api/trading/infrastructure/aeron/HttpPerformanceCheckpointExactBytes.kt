package com.exchange.core.api.trading.infrastructure.aeron

import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat

/** An exclusive byte range of one complete JSON object, including its two delimiters. */
internal data class HttpPerformanceCheckpointByteSpan(
    val startInclusive: Long,
    val endExclusive: Long,
) {
    init {
        require(startInclusive >= 0 && endExclusive > startInclusive)
        require(endExclusive - startInclusive >= 2)
    }

    val length: Long get() = endExclusive - startInclusive
}

/**
 * True proves full-byte equality of both business objects, never digest-only content equality.
 * False means only a raw representation/layout difference; the caller MUST use semantic rows.
 * Invalid ranges, I/O, interruption, EOF, byte-count and saved-SHA failures always throw.
 * Every opened source is consumed through EOF and rechecked against its own original reference
 * before either boolean result. The digest covers the SAME reads that supplied compared bytes.
 * open is a small-fixture fault-injection seam; normal callers use the original files directly.
 */
internal fun compareHttpPerformanceCheckpointBusinessBytes(
    scans: List<HttpPerformanceCheckpointScan>,
    open: (HttpPerformanceCheckpointFileReference) -> InputStream = { Files.newInputStream(it.file) },
): Boolean {
    require(scans.size == 3 && scans.map { it.reference.memberId } == listOf(0, 1, 2))
    requireExactByteComparisonActive()
    val spans = scans.map { it.businessByteSpans.entries.sortedBy { entry -> entry.value.startInclusive } }
    for ((scan, entries) in scans.zip(spans)) {
        check(entries.all { (pointer, span) -> pointer in BUSINESS_BYTE_POINTERS && span.endExclusive <= scan.reference.bytes }) {
            "체크포인트 업무 byte 범위가 잘못되었습니다"
        }
        check(entries.zipWithNext().all { (previous, next) -> previous.value.endExclusive <= next.value.startInclusive }) {
            "체크포인트 업무 byte 범위가 겹칩니다"
        }
    }
    // Unavailable byte offsets (for example a Reader-based UTF-16 parser) are not a JSON error.
    // Field ordering/whitespace/escaping differences are also normal semantic-fallback inputs.
    val comparable =
        spans.all { it.map { entry -> entry.key }.toSet() == BUSINESS_BYTE_POINTERS } &&
            spans.all { it.map { entry -> entry.key } == spans.first().map { entry -> entry.key } } &&
            spans.all { it.map { entry -> entry.value.length } == spans.first().map { entry -> entry.value.length } }

    VerifiedCheckpointBytes(scans[0].reference, open).use { first ->
        VerifiedCheckpointBytes(scans[1].reference, open).use { second ->
            VerifiedCheckpointBytes(scans[2].reference, open).use { third ->
                val inputs = listOf(first, second, third)
                var identical = comparable
                if (comparable) {
                    for (section in spans.first().indices) {
                        for (index in inputs.indices) inputs[index].consumeUntil(spans[index][section].value.startInclusive)
                        var remaining = spans.first()[section].value.length
                        var firstChunk = true
                        while (remaining > 0) {
                            requireExactByteComparisonActive()
                            val amount = minOf(remaining, EXACT_BYTE_BUFFER_SIZE.toLong()).toInt()
                            for (input in inputs) input.readExactly(amount)
                            for (input in inputs) {
                                check(!firstChunk || input.buffer[0] == '{'.code.toByte()) { "체크포인트 업무 byte 시작 경계 오류" }
                                check(remaining != amount.toLong() || input.buffer[amount - 1] == '}'.code.toByte()) {
                                    "체크포인트 업무 byte 종료 경계 오류"
                                }
                            }
                            // Keep reading even after a mismatch: no false result can hide a later SHA/EOF failure.
                            for (index in 1 until inputs.size) {
                                if (!Arrays.equals(first.buffer, 0, amount, inputs[index].buffer, 0, amount)) identical = false
                            }
                            firstChunk = false
                            remaining -= amount.toLong()
                        }
                    }
                }
                for (input in inputs) input.consumeEofAndVerify()
                requireExactByteComparisonActive()
                return identical
            }
        }
    }
}

private val BUSINESS_BYTE_POINTERS = setOf("/applied/state", "/trading")
private const val EXACT_BYTE_BUFFER_SIZE = 65_536

private class VerifiedCheckpointBytes(
    private val reference: HttpPerformanceCheckpointFileReference,
    open: (HttpPerformanceCheckpointFileReference) -> InputStream,
) : AutoCloseable {
    val buffer = ByteArray(EXACT_BYTE_BUFFER_SIZE)
    private val digest = MessageDigest.getInstance("SHA-256")
    private var bytes = 0L
    private val input =
        run {
            require(Files.isRegularFile(reference.file) && Files.size(reference.file) == reference.bytes) {
                "체크포인트 byte 비교 전 파일 크기가 다릅니다"
            }
            open(reference)
        }

    fun consumeUntil(target: Long) {
        check(target in bytes..reference.bytes) { "체크포인트 업무 byte 이동 범위 오류" }
        while (bytes < target) readExactly(minOf(target - bytes, buffer.size.toLong()).toInt())
    }

    fun readExactly(amount: Int) {
        require(amount in 1..buffer.size)
        var offset = 0
        while (offset < amount) {
            val count = read(offset, amount - offset)
            require(count > 0) { "체크포인트 byte 비교 중 이른 EOF" }
            offset += count
        }
    }

    fun consumeEofAndVerify() {
        while (read(0, buffer.size) >= 0) Unit
        require(bytes == reference.bytes) { "byte 비교에서 읽은 체크포인트 bytes가 다릅니다" }
        require(HexFormat.of().formatHex(digest.digest()) == reference.sha256) {
            "byte 비교에서 읽은 체크포인트 SHA-256이 다릅니다"
        }
    }

    private fun read(
        offset: Int,
        length: Int,
    ): Int {
        requireExactByteComparisonActive()
        var count = input.read(buffer, offset, length)
        if (count == 0) {
            val one = input.read()
            if (one < 0) return -1
            buffer[offset] = one.toByte()
            count = 1
        }
        if (count > 0) {
            digest.update(buffer, offset, count)
            bytes = Math.addExact(bytes, count.toLong())
            require(bytes <= reference.bytes) { "byte 비교에서 읽은 체크포인트 bytes가 다릅니다" }
        }
        requireExactByteComparisonActive()
        return count
    }

    override fun close() = input.close()
}

private fun requireExactByteComparisonActive() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("체크포인트 byte 비교 interrupt")
}
