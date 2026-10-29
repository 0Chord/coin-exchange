package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import org.agrona.collections.Long2LongHashMap
import org.agrona.collections.Long2ObjectHashMap
import org.agrona.collections.LongHashSet

/** 단일 collector 소유. 파일 모드는 완료와 저장 확인 뒤 원문을 해제하고 primitive 인덱스만 누적한다. */
internal class HttpPerformanceCollectorHistory(
    private val capacity: Int,
    private val archive: HttpPerformanceIdentityArchive? = null,
) : AutoCloseable {
    private class Segment {
        val identities = arrayOfNulls<AdmissionResultPayload.Accepted>(SEGMENT_SIZE)
        val completed = LongArray(SEGMENT_SIZE / Long.SIZE_BITS)
    }

    private val segments = Long2ObjectHashMap<Segment>()
    private val pending = Long2ObjectHashMap<AdmissionResultPayload.Accepted>()
    private val offsets = Long2LongHashMap(-1L)
    private val diskCompleted = LongHashSet()
    private val releaseAfterCallback = LongHashSet()
    private var closed = false
    val retainedCount: Int get() =
        if (closed) {
            0
        } else if (archive == null) {
            identityCount
        } else {
            pending.size
        }
    val error: String? get() = archive?.error
    var identityCount: Int = 0
        private set
    var completedCount: Int = 0
        private set

    init {
        require(capacity > 0)
    }

    operator fun get(sequence: Long): AdmissionResultPayload.Accepted? {
        check(!closed) { "닫힌 완료 표식 기록" }
        if (archive == null) return segments.get(sequence shr SEGMENT_SHIFT)?.identities?.get(offset(sequence))
        pending.get(sequence)?.let { return it }
        val position = offsets.get(sequence)
        return if (position == -1L) null else archive.read(sequence, position)
    }

    fun record(accepted: AdmissionResultPayload.Accepted) {
        val previous = get(accepted.sequence)
        require(previous == null || previous == accepted) { "완료 표식 접수 순번 식별 충돌" }
        if (previous != null) return
        require(identityCount < capacity) { "완료 표식 누적 식별 계획 초과: capacity=$capacity, accepted=$accepted" }
        if (archive != null) {
            require(pending.size < archive.limits.pendingCapacity) {
                "완료 표식 pending 원문 초과: capacity=${archive.limits.pendingCapacity}"
            }
            archive.offer(accepted)
            pending.put(accepted.sequence, accepted)
            identityCount++
            return
        }
        val segmentKey = accepted.sequence shr SEGMENT_SHIFT
        var segment = segments.get(segmentKey)
        if (segment == null) {
            segment = Segment()
            segments.put(segmentKey, segment)
        }
        segment.identities[offset(accepted.sequence)] = accepted
        identityCount++
    }

    fun isCompleted(sequence: Long): Boolean {
        if (archive != null) return diskCompleted.contains(sequence)
        val segment = segments.get(sequence shr SEGMENT_SHIFT) ?: return false
        val offset = offset(sequence)
        return (segment.completed[offset / Long.SIZE_BITS] and (1L shl (offset % Long.SIZE_BITS))) != 0L
    }

    fun markCompleted(sequence: Long): Boolean {
        check(!closed) { "닫힌 완료 표식 기록" }
        if (archive != null) {
            check(pending.containsKey(sequence) || offsets.containsKey(sequence)) { "접수하지 않은 완료 순번" }
            if (!diskCompleted.add(sequence)) return false
            completedCount++
            // Keep the current callback's original for JFR/timing; only its tail drain releases it.
            if (offsets.containsKey(sequence)) releaseAfterCallback.add(sequence)
            return true
        }
        val segment = checkNotNull(segments.get(sequence shr SEGMENT_SHIFT))
        val offset = offset(sequence)
        checkNotNull(segment.identities[offset])
        val word = offset / Long.SIZE_BITS
        val bit = 1L shl (offset % Long.SIZE_BITS)
        if ((segment.completed[word] and bit) != 0L) return false
        segment.completed[word] = segment.completed[word] or bit
        completedCount++
        return true
    }

    /** Service callback tail and idle both run on the collector thread, never on the writer. */
    fun drainAcknowledgements(): Int {
        if (archive == null) return 0
        archive.checkHealthy()
        return drain()
    }

    private fun drain(): Int {
        val storage = archive ?: return 0
        var count = 0
        while (true) {
            val ack = storage.pollAcknowledgement() ?: break
            check(pending.containsKey(ack.sequence)) { "원문 없는 Accepted 저장 확인" }
            check(offsets.put(ack.sequence, ack.offset) == -1L) { "중복 Accepted 저장 확인" }
            if (diskCompleted.contains(ack.sequence)) pending.remove(ack.sequence)
            count++
        }
        val iterator = releaseAfterCallback.iterator()
        while (iterator.hasNext()) pending.remove(iterator.nextValue())
        releaseAfterCallback.clear()
        return count
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            archive?.close()
        } finally {
            try {
                drain()
            } finally {
                pending.clear()
                segments.clear()
                offsets.clear()
                diskCompleted.clear()
                releaseAfterCallback.clear()
            }
        }
    }

    private fun offset(sequence: Long): Int = (sequence and SEGMENT_MASK).toInt()

    private companion object {
        const val SEGMENT_SHIFT = 10
        const val SEGMENT_SIZE = 1 shl SEGMENT_SHIFT
        const val SEGMENT_MASK = 1_023L
    }
}
