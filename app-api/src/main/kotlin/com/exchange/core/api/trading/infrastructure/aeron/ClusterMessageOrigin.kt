package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.codecs.MessageHeaderDecoder
import io.aeron.cluster.codecs.SessionMessageHeaderDecoder
import org.agrona.DirectBuffer

/** Aeron 1.53.3 BoundedLogAdapter의 callback 직전 헤더와 서비스 세션 ID 배치에 의존한다. */
internal object ClusterMessageOrigin {
    fun isServiceMessage(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
        expectedServiceId: Int,
    ): Boolean {
        require(expectedServiceId in 0..127) { "잘못된 서비스 ID입니다" }
        require(offset >= AeronCluster.SESSION_HEADER_LENGTH && length >= 0 && offset <= buffer.capacity() - length) {
            "Cluster callback의 세션 헤더 또는 본문 구간이 잘렸습니다"
        }
        val headerOffset = offset - AeronCluster.SESSION_HEADER_LENGTH
        val header = MessageHeaderDecoder().wrap(buffer, headerOffset)
        require(
            header.schemaId() == MessageHeaderDecoder.SCHEMA_ID &&
                header.version() == MessageHeaderDecoder.SCHEMA_VERSION &&
                header.templateId() == SessionMessageHeaderDecoder.TEMPLATE_ID &&
                header.blockLength() == SessionMessageHeaderDecoder.BLOCK_LENGTH,
        ) { "Aeron 1.53.3 callback 세션 헤더와 다릅니다" }
        val session =
            SessionMessageHeaderDecoder().wrap(
                buffer,
                headerOffset + MessageHeaderDecoder.ENCODED_LENGTH,
                header.blockLength(),
                header.version(),
            )
        val sessionId = session.clusterSessionId()
        if (sessionId >= 0) return false
        // PendingServiceMessageTracker.serviceIdFromLogMessage와 동일한 1.53.3 인코딩이다.
        val serviceId = (sessionId ushr 56).toInt() and 0x7F
        require(serviceId == expectedServiceId) { "다른 서비스의 내부 실행 입력입니다: serviceId=$serviceId" }
        return true
    }
}
