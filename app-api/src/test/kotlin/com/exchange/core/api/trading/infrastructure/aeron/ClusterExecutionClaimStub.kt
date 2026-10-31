package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.codecs.MessageHeaderEncoder
import io.aeron.cluster.codecs.SessionMessageHeaderEncoder
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.BufferClaim
import io.aeron.protocol.DataHeaderFlyweight
import io.aeron.protocol.HeaderFlyweight
import org.agrona.concurrent.UnsafeBuffer
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import java.nio.ByteOrder.LITTLE_ENDIAN

/** 실제 BufferClaim의 commit·abort와 Cluster가 먼저 쓴 세션 헤더를 함께 검사한다. */
internal class ClusterExecutionClaimStub(
    cluster: Cluster,
    results: List<Long> = listOf(1L),
    failure: Throwable? = null,
    onAttempt: () -> Unit = {},
) {
    val attempts = mutableListOf<Int>()
    val attemptClaims = mutableListOf<BufferClaim>()
    val claims = mutableListOf<UnsafeBuffer>()
    private val backings = mutableListOf<ByteArray>()
    private val sessionHeaders = mutableListOf<ByteArray>()

    val committedMessages: List<ByteArray>
        get() =
            claims.filter { it.isPublishedAs(HeaderFlyweight.HDR_TYPE_DATA) }.map { frame ->
                ByteArray(frame.capacity() - PAYLOAD_OFFSET).also { frame.getBytes(PAYLOAD_OFFSET, it) }
            }

    val abortedClaims: Int
        get() = claims.count { it.isPublishedAs(HeaderFlyweight.HDR_TYPE_PAD) }

    init {
        require(results.isNotEmpty())
        doAnswer { invocation ->
            val length = invocation.getArgument<Int>(0)
            val claim = invocation.getArgument<BufferClaim>(1)
            attempts += length
            attemptClaims += claim
            onAttempt()
            if (failure != null) throw failure
            val result = results[(attempts.size - 1).coerceAtMost(results.lastIndex)]
            if (result > 0) {
                val frameLength = PAYLOAD_OFFSET + length
                val backing = ByteArray(PREFIX_LENGTH + frameLength + SUFFIX_LENGTH) { SENTINEL }
                val frame = UnsafeBuffer(backing, PREFIX_LENGTH, frameLength)
                frame.setMemory(0, frameLength, 0.toByte())
                frame.putInt(HeaderFlyweight.FRAME_LENGTH_FIELD_OFFSET, -frameLength, LITTLE_ENDIAN)
                frame.putByte(HeaderFlyweight.VERSION_FIELD_OFFSET, HeaderFlyweight.CURRENT_VERSION)
                frame.putByte(HeaderFlyweight.FLAGS_FIELD_OFFSET, DataHeaderFlyweight.BEGIN_AND_END_FLAGS.toByte())
                frame.putShort(HeaderFlyweight.TYPE_FIELD_OFFSET, HeaderFlyweight.HDR_TYPE_DATA.toShort(), LITTLE_ENDIAN)
                SessionMessageHeaderEncoder()
                    .wrapAndApplyHeader(frame, DataHeaderFlyweight.HEADER_LENGTH, MessageHeaderEncoder())
                    .leadershipTermId(1)
                    .clusterSessionId(0)
                    .timestamp(1_000)
                sessionHeaders +=
                    ByteArray(AeronCluster.SESSION_HEADER_LENGTH).also {
                        frame.getBytes(DataHeaderFlyweight.HEADER_LENGTH, it)
                    }
                backings += backing
                claims += frame
                claim.wrap(UnsafeBuffer(backing), PREFIX_LENGTH, frameLength)
            }
            result
        }.`when`(cluster).tryClaim(anyInt(), any(BufferClaim::class.java))
    }

    fun sessionHeaderIntact(): Boolean =
        claims.indices.all { index ->
            val actual = ByteArray(AeronCluster.SESSION_HEADER_LENGTH)
            claims[index].getBytes(DataHeaderFlyweight.HEADER_LENGTH, actual)
            sessionHeaders[index].contentEquals(actual)
        }

    fun boundariesIntact(): Boolean =
        backings.all { backing ->
            (0 until PREFIX_LENGTH).all { backing[it] == SENTINEL } &&
                (backing.size - SUFFIX_LENGTH until backing.size).all { backing[it] == SENTINEL }
        }

    private fun UnsafeBuffer.isPublishedAs(type: Int): Boolean =
        getInt(HeaderFlyweight.FRAME_LENGTH_FIELD_OFFSET, LITTLE_ENDIAN) == capacity() &&
            getShort(HeaderFlyweight.TYPE_FIELD_OFFSET, LITTLE_ENDIAN).toInt() == type

    private companion object {
        // 실제 commit·abort의 release int 쓰기는 macOS ARM에서도 자연 정렬을 유지한다.
        const val PREFIX_LENGTH = 64
        const val SUFFIX_LENGTH = 7
        const val SENTINEL: Byte = 0x5a
        const val PAYLOAD_OFFSET = DataHeaderFlyweight.HEADER_LENGTH + AeronCluster.SESSION_HEADER_LENGTH
    }
}
