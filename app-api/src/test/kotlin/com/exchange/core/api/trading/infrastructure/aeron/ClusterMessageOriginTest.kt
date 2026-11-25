package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.codecs.MessageHeaderDecoder
import io.aeron.cluster.codecs.MessageHeaderEncoder
import io.aeron.cluster.codecs.SessionMessageHeaderDecoder
import io.aeron.cluster.codecs.SessionMessageHeaderEncoder
import org.agrona.concurrent.UnsafeBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClusterMessageOriginTest {
    @Test
    fun `비영 offset의 실제 세션 헤더로 외부와 자기 서비스를 구분한다`() {
        assertFalse(origin(frame(42)))
        assertTrue(origin(frame(Long.MIN_VALUE + 1)))
        assertTrue(origin(frame(Long.MIN_VALUE or (6L shl 56) or 1), serviceId = 6))
    }

    @Test
    fun `다른 서비스의 음수 세션을 자기 내부 입력으로 받지 않는다`() {
        assertFailsWith<IllegalArgumentException> { origin(frame(Long.MIN_VALUE or (1L shl 56) or 1)) }
        assertFailsWith<IllegalArgumentException> { origin(frame(Long.MIN_VALUE + 1), serviceId = 1) }
        listOf(-1, 128).forEach { id ->
            assertFailsWith<IllegalArgumentException> { origin(frame(42), serviceId = id) }
        }
    }

    @Test
    fun `잘린 헤더 본문과 overflow 구간은 출처를 추정하지 않는다`() {
        val buffer = frame(Long.MIN_VALUE + 1)
        listOf(-1 to 1, 0 to 1, 31 to 1, 49 to -1, 49 to 9, Int.MAX_VALUE to 1, 49 to Int.MAX_VALUE).forEach { (offset, length) ->
            assertFailsWith<IllegalArgumentException> { ClusterMessageOrigin.isServiceMessage(buffer, offset, length, 0) }
        }
    }

    @Test
    fun `schema version template blockLength를 하나라도 바꾸면 실패한다`() {
        listOf(0 to 23, 2 to 999, 4 to 999, 6 to 999).forEach { (headerField, value) ->
            val buffer = frame(Long.MIN_VALUE + 1)
            buffer.putShort(17 + headerField, value.toShort(), LITTLE_ENDIAN)
            assertFailsWith<IllegalArgumentException> { origin(buffer) }
        }
    }

    private fun origin(
        buffer: UnsafeBuffer,
        serviceId: Int = 0,
    ) = ClusterMessageOrigin.isServiceMessage(buffer, 17 + AeronCluster.SESSION_HEADER_LENGTH, 8, serviceId)

    private fun frame(sessionId: Long): UnsafeBuffer {
        val buffer = UnsafeBuffer(ByteArray(17 + AeronCluster.SESSION_HEADER_LENGTH + 8))
        SessionMessageHeaderEncoder()
            .wrapAndApplyHeader(buffer, 17, MessageHeaderEncoder())
            .leadershipTermId(1)
            .clusterSessionId(sessionId)
            .timestamp(1234)
        check(MessageHeaderDecoder.ENCODED_LENGTH + SessionMessageHeaderDecoder.BLOCK_LENGTH == AeronCluster.SESSION_HEADER_LENGTH)
        return buffer
    }
}
