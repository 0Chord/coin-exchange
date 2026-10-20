package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.codecs.ClusterActionRequestDecoder
import io.aeron.cluster.codecs.MembershipChangeEventDecoder
import io.aeron.cluster.codecs.MessageHeaderDecoder
import io.aeron.cluster.codecs.MessageHeaderEncoder
import io.aeron.cluster.codecs.NewLeadershipTermEventDecoder
import io.aeron.cluster.codecs.SessionCloseEventDecoder
import io.aeron.cluster.codecs.SessionMessageHeaderEncoder
import io.aeron.cluster.codecs.SessionOpenEventDecoder
import io.aeron.cluster.codecs.TimerEventDecoder
import io.aeron.logbuffer.FrameDescriptor
import io.aeron.logbuffer.Header
import io.aeron.protocol.DataHeaderFlyweight
import org.agrona.concurrent.UnsafeBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordedClusterFrameParserTest {
    @Test
    fun `비영 offset에서 application 헤더를 포함한 원본과 실제 끝 위치를 보존한다`() {
        val bytes = ByteArray(31) { (it + 7).toByte() }
        val message = sessionMessage(bytes)
        val padded = UnsafeBuffer(ByteArray(message.size + 43) { 99 })
        padded.putBytes(19, message)

        val frame = assertIs<RecordedApplicationFrame>(RecordedClusterFrameParser.parse(padded, 19, message.size, 320))

        assertContentEquals(bytes, frame.bytes)
        assertEquals(320L, frame.position)
        assertEquals(4L, frame.leadershipTermId)
        assertEquals(12L, frame.clusterSessionId)
        assertEquals(9876L, frame.timestamp)
        padded.putByte(19 + AeronCluster.SESSION_HEADER_LENGTH, 0)
        assertEquals(7, frame.bytes[0].toInt())
    }

    @Test
    fun `알려진 여섯 제어 template은 업무 입력과 구분한다`() {
        val controls =
            listOf(
                Triple(SessionOpenEventDecoder.TEMPLATE_ID, SessionOpenEventDecoder.BLOCK_LENGTH, 8),
                Triple(SessionCloseEventDecoder.TEMPLATE_ID, SessionCloseEventDecoder.BLOCK_LENGTH, 0),
                Triple(TimerEventDecoder.TEMPLATE_ID, TimerEventDecoder.BLOCK_LENGTH, 0),
                Triple(ClusterActionRequestDecoder.TEMPLATE_ID, ClusterActionRequestDecoder.BLOCK_LENGTH, 0),
                Triple(NewLeadershipTermEventDecoder.TEMPLATE_ID, NewLeadershipTermEventDecoder.BLOCK_LENGTH, 0),
                Triple(MembershipChangeEventDecoder.TEMPLATE_ID, MembershipChangeEventDecoder.BLOCK_LENGTH, 4),
            )
        controls.forEach { (template, blockLength, variableBytes) ->
            val buffer = control(template, blockLength, variableBytes)
            val frame = assertIs<RecordedControlFrame>(RecordedClusterFrameParser.parse(buffer, 0, buffer.capacity(), 512))
            assertEquals(template, frame.templateId)
            assertEquals(512L, frame.position)
            assertEquals(4L, frame.leadershipTermId)
        }
    }

    @Test
    fun `미지원 schema version template과 잘린 wrapper는 건너뛰지 않는다`() {
        val normal = sessionMessage(ByteArray(8))
        val schema = UnsafeBuffer(normal.copyOf()).apply { putShort(4, 9, LITTLE_ENDIAN) }
        val version = UnsafeBuffer(normal.copyOf()).apply { putShort(6, 99, LITTLE_ENDIAN) }
        val template = UnsafeBuffer(normal.copyOf()).apply { putShort(2, 999, LITTLE_ENDIAN) }
        listOf(schema, version, template).forEach { bad ->
            assertFailsWith<Exception> { RecordedClusterFrameParser.parse(bad, 0, normal.size, 128) }
        }
        listOf(0, 7, 8, 31, 32, 39).forEach { length ->
            assertFailsWith<IllegalArgumentException> { RecordedClusterFrameParser.parse(UnsafeBuffer(normal), 0, length, 128) }
        }
        assertFailsWith<IllegalArgumentException> { RecordedClusterFrameParser.parse(UnsafeBuffer(normal), -1, normal.size, 128) }
        assertFailsWith<IllegalArgumentException> { RecordedClusterFrameParser.parse(UnsafeBuffer(normal), 1, normal.size, 128) }
        val wrongBlock = UnsafeBuffer(normal.copyOf()).apply { putShort(0, 23, LITTLE_ENDIAN) }
        assertFailsWith<IllegalArgumentException> { RecordedClusterFrameParser.parse(wrongBlock, 0, normal.size, 128) }
    }

    @Test
    fun `제어 메시지의 변수 길이는 callback 범위를 넘어 읽지 않는다`() {
        val buffer = control(SessionOpenEventDecoder.TEMPLATE_ID, SessionOpenEventDecoder.BLOCK_LENGTH, 8)
        assertFailsWith<IllegalArgumentException> { RecordedClusterFrameParser.parse(buffer, 0, buffer.capacity() - 1, 128) }
        buffer.putInt(MessageHeaderDecoder.ENCODED_LENGTH + SessionOpenEventDecoder.BLOCK_LENGTH, -1, LITTLE_ENDIAN)
        assertFailsWith<IllegalArgumentException> { RecordedClusterFrameParser.parse(buffer, 0, buffer.capacity(), 128) }
        val fixed = control(TimerEventDecoder.TEMPLATE_ID, TimerEventDecoder.BLOCK_LENGTH, 1)
        assertFailsWith<IllegalArgumentException> { RecordedClusterFrameParser.parse(fixed, 0, fixed.capacity(), 128) }
    }

    @Test
    fun `두 fragment의 offset과 aligned 끝 위치로 완성 frame 하나를 만든다`() {
        val bytes = ByteArray(100) { it.toByte() }
        val message = sessionMessage(bytes)
        // Aeron fills each non-final fragment to maxPayloadLength; the last must be no larger.
        val first = fragment(message.copyOfRange(0, 80), 0, FrameDescriptor.BEGIN_FRAG_FLAG.toInt())
        val second = fragment(message.copyOfRange(80, message.size), first.header.nextTermOffset(), FrameDescriptor.END_FRAG_FLAG.toInt())
        val limit = second.header.position()
        val collector = RecordedReplayCollector(0, limit)

        collector.onFragment(first.buffer, first.payloadOffset, first.payloadLength, first.header)
        assertTrue(collector.inputs.isEmpty())
        collector.onFragment(second.buffer, second.payloadOffset, second.payloadLength, second.header)

        assertNull(collector.completionFailure(limit))
        assertContentEquals(bytes, collector.inputs.single().bytes)
        assertEquals(limit, collector.inputs.single().position)
        assertEquals(2, collector.inputs.single().fragmentCount)
        assertEquals(2, collector.fragmentCount)
        assertEquals(1, collector.fragmentedMessageCount)
    }

    @Test
    fun `L에 소비 위치만 도달해도 조각이 덜 끝났으면 검증 불가다`() {
        val first = fragment(sessionMessage(ByteArray(8)).copyOfRange(0, 20), 0, FrameDescriptor.BEGIN_FRAG_FLAG.toInt())
        val limit = first.header.position()
        val collector = RecordedReplayCollector(0, limit)

        collector.onFragment(first.buffer, first.payloadOffset, first.payloadLength, first.header)

        assertTrue(assertNotNull(collector.completionFailure(limit)).contains("재조립 미완료"))
        assertTrue(collector.inputs.isEmpty())
    }

    @Test
    fun `orphan 누락 중첩 fragment와 L 이전 종료 및 초과는 완전한 읽기가 아니다`() {
        val message = sessionMessage(ByteArray(80))
        val first = fragment(message.copyOfRange(0, 40), 0, FrameDescriptor.BEGIN_FRAG_FLAG.toInt())
        val end = fragment(message.copyOfRange(40, message.size), first.header.nextTermOffset(), FrameDescriptor.END_FRAG_FLAG.toInt())
        val orphan = RecordedReplayCollector(0, end.header.position())
        orphan.onFragment(end.buffer, end.payloadOffset, end.payloadLength, end.header)
        assertNotNull(orphan.failure)
        val gap = RecordedReplayCollector(0, 512)
        gap.onFragment(first.buffer, first.payloadOffset, first.payloadLength, first.header)
        val skipped =
            fragment(message.copyOfRange(40, message.size), first.header.nextTermOffset() + 32, FrameDescriptor.END_FRAG_FLAG.toInt())
        gap.onFragment(skipped.buffer, skipped.payloadOffset, skipped.payloadLength, skipped.header)
        assertTrue(assertNotNull(gap.failure).contains("누락"))
        val nested = RecordedReplayCollector(0, 512)
        nested.onFragment(first.buffer, first.payloadOffset, first.payloadLength, first.header)
        val nextBegin = fragment(message.copyOfRange(0, 40), first.header.nextTermOffset(), FrameDescriptor.BEGIN_FRAG_FLAG.toInt())
        nested.onFragment(nextBegin.buffer, nextBegin.payloadOffset, nextBegin.payloadLength, nextBegin.header)
        assertNotNull(nested.failure)
        val full = fragment(message, 0, FrameDescriptor.UNFRAGMENTED.toInt())
        val short = RecordedReplayCollector(0, full.header.position() + 32)
        short.onFragment(full.buffer, full.payloadOffset, full.payloadLength, full.header)
        assertNotNull(short.completionFailure(full.header.position()))
        val over = RecordedReplayCollector(0, full.header.position() - 32)
        over.onFragment(full.buffer, full.payloadOffset, full.payloadLength, full.header)
        assertTrue(assertNotNull(over.failure).contains("초과"))
    }

    @Test
    fun `업무 입력 없는 빈 범위나 제어 메시지로 끝난 L을 완료로 세지 않는다`() {
        assertNotNull(RecordedReplayCollector(0, 128).completionFailure(128))
        val control = control(TimerEventDecoder.TEMPLATE_ID, TimerEventDecoder.BLOCK_LENGTH, 0)
        val data = ByteArray(control.capacity()).also { control.getBytes(0, it) }
        val only = fragment(data, 0, FrameDescriptor.UNFRAGMENTED.toInt())
        val collector = RecordedReplayCollector(0, only.header.position())
        collector.onFragment(only.buffer, only.payloadOffset, only.payloadLength, only.header)
        assertEquals(1, collector.controls.size)
        assertTrue(collector.inputs.isEmpty())
        assertNotNull(collector.completionFailure(only.header.position()))
    }

    private fun sessionMessage(bytes: ByteArray): ByteArray {
        val buffer = UnsafeBuffer(ByteArray(AeronCluster.SESSION_HEADER_LENGTH + bytes.size))
        SessionMessageHeaderEncoder()
            .wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
            .leadershipTermId(4)
            .clusterSessionId(12)
            .timestamp(9876)
        buffer.putBytes(AeronCluster.SESSION_HEADER_LENGTH, bytes)
        return ByteArray(buffer.capacity()).also { buffer.getBytes(0, it) }
    }

    private fun control(
        template: Int,
        blockLength: Int,
        variableBytes: Int,
    ): UnsafeBuffer =
        UnsafeBuffer(ByteArray(MessageHeaderDecoder.ENCODED_LENGTH + blockLength + variableBytes)).apply {
            MessageHeaderEncoder()
                .wrap(this, 0)
                .blockLength(blockLength)
                .templateId(template)
                .schemaId(MessageHeaderDecoder.SCHEMA_ID)
                .version(MessageHeaderDecoder.SCHEMA_VERSION)
            putLong(MessageHeaderDecoder.ENCODED_LENGTH, 4, LITTLE_ENDIAN)
        }

    private data class Fragment(
        val buffer: UnsafeBuffer,
        val payloadOffset: Int,
        val payloadLength: Int,
        val header: Header,
    )

    private fun fragment(
        payload: ByteArray,
        termOffset: Int,
        flags: Int,
    ): Fragment {
        val prefix = 13
        val buffer = UnsafeBuffer(ByteArray(prefix + DataHeaderFlyweight.HEADER_LENGTH + payload.size + 9))
        val length = DataHeaderFlyweight.HEADER_LENGTH + payload.size
        buffer.putInt(prefix, length, LITTLE_ENDIAN)
        buffer.putByte(prefix + 5, flags.toByte())
        buffer.putShort(prefix + 6, DataHeaderFlyweight.HDR_TYPE_DATA.toShort(), LITTLE_ENDIAN)
        buffer.putInt(prefix + DataHeaderFlyweight.TERM_OFFSET_FIELD_OFFSET, termOffset, LITTLE_ENDIAN)
        buffer.putInt(prefix + DataHeaderFlyweight.SESSION_ID_FIELD_OFFSET, 99, LITTLE_ENDIAN)
        buffer.putInt(prefix + DataHeaderFlyweight.STREAM_ID_FIELD_OFFSET, 19999, LITTLE_ENDIAN)
        buffer.putInt(prefix + DataHeaderFlyweight.TERM_ID_FIELD_OFFSET, 10, LITTLE_ENDIAN)
        val offset = prefix + DataHeaderFlyweight.HEADER_LENGTH
        buffer.putBytes(offset, payload)
        val header = Header(10, Integer.numberOfTrailingZeros(64 * 1024)).buffer(buffer).offset(prefix)
        return Fragment(buffer, offset, payload.size, header)
    }
}
