package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import org.agrona.collections.Long2ObjectHashMap
import org.agrona.collections.LongHashSet

/** 단일 collector 소유. 파일 모드는 완료와 저장 확인 뒤 원문과 누적 인덱스를 디스크로 옮긴다. */
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
    private var indexFailure: String? = null
    private val diskIndex =
        archive?.let {
            try {
                HttpPerformanceCollectorDiskIndex(it.evidencePath.resolveSibling("${it.evidencePath.fileName}.index"))
            } catch (failure: Throwable) {
                try {
                    it.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                indexFailure = "Accepted 원문 인덱스 열기 실패: ${failure.javaClass.simpleName}: ${failure.message}"
                null
            }
        }
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
    val error: String? get() = indexFailure ?: archive?.error
    val retainedIndexCount: Int get() = diskIndex?.retainedCount ?: 0
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
        val position = checkNotNull(diskIndex).offset(sequence)
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
        if (archive != null) return checkNotNull(diskIndex).completed(sequence)
        val segment = segments.get(sequence shr SEGMENT_SHIFT) ?: return false
        val offset = offset(sequence)
        return (segment.completed[offset / Long.SIZE_BITS] and (1L shl (offset % Long.SIZE_BITS))) != 0L
    }

    fun markCompleted(sequence: Long): Boolean {
        check(!closed) { "닫힌 완료 표식 기록" }
        if (archive != null) {
            val index = checkNotNull(diskIndex)
            check(pending.containsKey(sequence) || index.offset(sequence) != -1L) { "접수하지 않은 완료 순번" }
            if (!index.markCompleted(sequence)) return false
            completedCount++
            // Keep the current callback's original for JFR/timing; only its tail drain releases it.
            if (index.offset(sequence) != -1L) releaseAfterCallback.add(sequence)
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
        check(indexFailure == null) { checkNotNull(indexFailure) }
        archive.checkHealthy()
        return drain()
    }

    private fun drain(): Int {
        val storage = archive ?: return 0
        var count = 0
        while (true) {
            val ack = storage.pollAcknowledgement() ?: break
            check(pending.containsKey(ack.sequence)) { "원문 없는 Accepted 저장 확인" }
            checkNotNull(diskIndex).recordOffset(ack.sequence, ack.offset)
            if (diskIndex.completed(ack.sequence)) pending.remove(ack.sequence)
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
                diskIndex?.close()
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
