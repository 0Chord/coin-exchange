package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.FragmentAssembler
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.codecs.ClusterActionRequestDecoder
import io.aeron.cluster.codecs.MembershipChangeEventDecoder
import io.aeron.cluster.codecs.MessageHeaderDecoder
import io.aeron.cluster.codecs.NewLeadershipTermEventDecoder
import io.aeron.cluster.codecs.SessionCloseEventDecoder
import io.aeron.cluster.codecs.SessionMessageHeaderDecoder
import io.aeron.cluster.codecs.SessionOpenEventDecoder
import io.aeron.cluster.codecs.TimerEventDecoder
import io.aeron.logbuffer.FrameDescriptor
import io.aeron.logbuffer.Header
import io.aeron.protocol.DataHeaderFlyweight
import org.agrona.DirectBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

internal sealed interface RecordedClusterFrame {
    val position: Long
    val leadershipTermId: Long
}

internal data class RecordedApplicationFrame(
    val bytes: ByteArray,
    override val position: Long,
    override val leadershipTermId: Long,
    val clusterSessionId: Long,
    val timestamp: Long,
    val fragmentCount: Int = 1,
) : RecordedClusterFrame

internal data class RecordedControlFrame(
    val templateId: Int,
    override val position: Long,
    override val leadershipTermId: Long,
) : RecordedClusterFrame

/** Parses only the current Cluster schema, preserving all application bytes, including its own header. */
internal object RecordedClusterFrameParser {
    fun parse(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
        position: Long,
    ): RecordedClusterFrame {
        require(offset >= 0 && length >= MessageHeaderDecoder.ENCODED_LENGTH && offset <= buffer.capacity() - length) {
            "잘린 Cluster header 또는 잘못된 offset/length"
        }
        val header = MessageHeaderDecoder().wrap(buffer, offset)
        require(header.schemaId() == MessageHeaderDecoder.SCHEMA_ID) { "미지원 Cluster schema=${header.schemaId()}" }
        require(header.version() == MessageHeaderDecoder.SCHEMA_VERSION) { "미지원 Cluster version=${header.version()}" }
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        val templateId = header.templateId()
        if (templateId == SessionMessageHeaderDecoder.TEMPLATE_ID) {
            require(header.blockLength() == SessionMessageHeaderDecoder.BLOCK_LENGTH) { "잘못된 session wrapper blockLength" }
            require(length >= AeronCluster.SESSION_HEADER_LENGTH + 8) { "잘린 session wrapper 또는 application header" }
            val session = SessionMessageHeaderDecoder().wrap(buffer, body, header.blockLength(), header.version())
            val bytes = ByteArray(length - AeronCluster.SESSION_HEADER_LENGTH)
            buffer.getBytes(offset + AeronCluster.SESSION_HEADER_LENGTH, bytes)
            return RecordedApplicationFrame(bytes, position, session.leadershipTermId(), session.clusterSessionId(), session.timestamp())
        }
        val blockLength =
            when (templateId) {
                SessionOpenEventDecoder.TEMPLATE_ID -> SessionOpenEventDecoder.BLOCK_LENGTH
                SessionCloseEventDecoder.TEMPLATE_ID -> SessionCloseEventDecoder.BLOCK_LENGTH
                TimerEventDecoder.TEMPLATE_ID -> TimerEventDecoder.BLOCK_LENGTH
                ClusterActionRequestDecoder.TEMPLATE_ID -> ClusterActionRequestDecoder.BLOCK_LENGTH
                NewLeadershipTermEventDecoder.TEMPLATE_ID -> NewLeadershipTermEventDecoder.BLOCK_LENGTH
                MembershipChangeEventDecoder.TEMPLATE_ID -> MembershipChangeEventDecoder.BLOCK_LENGTH
                else -> error("미지원 Cluster template=$templateId")
            }
        require(header.blockLength() == blockLength && length >= MessageHeaderDecoder.ENCODED_LENGTH + blockLength) {
            "잘린 control wrapper template=$templateId"
        }
        var cursor = MessageHeaderDecoder.ENCODED_LENGTH + blockLength
        val variableFields =
            when (templateId) {
                SessionOpenEventDecoder.TEMPLATE_ID -> 2
                MembershipChangeEventDecoder.TEMPLATE_ID -> 1
                else -> 0
            }
        repeat(variableFields) {
            require(cursor <= length - Int.SIZE_BYTES) { "잘린 control variable header template=$templateId" }
            val fieldLength = buffer.getInt(offset + cursor, LITTLE_ENDIAN).toLong() and 0xffff_ffffL
            cursor += Int.SIZE_BYTES
            require(fieldLength <= (length - cursor).toLong()) { "잘린 control variable body template=$templateId" }
            cursor += fieldLength.toInt()
        }
        require(cursor == length) { "해석되지 않은 control trailing bytes template=$templateId" }
        // Every supported control starts with the same SBE leadershipTermId field.
        return RecordedControlFrame(templateId, position, buffer.getLong(body, LITTLE_ENDIAN))
    }
}

/** The assembler alone silently drops orphan fragments; track their boundaries before delegating. */
internal class RecordedReplayCollector(
    private val startPosition: Long,
    private val limitPosition: Long,
    private val retainFrames: Boolean = true,
    private val maximumMessageBytes: Int = Int.MAX_VALUE,
    private val onValidatedMessage: (RecordedClusterFrame, DirectBuffer, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
) {
    init {
        require(maximumMessageBytes > 0)
    }

    val inputs = mutableListOf<RecordedApplicationFrame>()
    val controls = mutableListOf<RecordedControlFrame>()
    var failure: String? = null
        private set
    var lastCompletedPosition: Long = startPosition
        private set
    var fragmentCount: Int = 0
        private set
    var fragmentedMessageCount: Int = 0
        private set
    private var pending = false
    private var nextTermOffset = 0
    private var fragmentTermId = 0
    private var lastFragmentPosition = startPosition
    private var leadershipTermId: Long? = null
    private var messageFragmentCount = 0
    private var messageBytes = 0L
    private var applicationCount = 0L
    private var lastApplicationPosition = startPosition
    private val assembler =
        FragmentAssembler { buffer, offset, length, header ->
            val frame = RecordedClusterFrameParser.parse(buffer, offset, length, header.position())
            require(frame.position > lastCompletedPosition && frame.position <= limitPosition) { "완성 메시지 위치가 범위를 벗어났습니다" }
            require(leadershipTermId == null || leadershipTermId == frame.leadershipTermId) { "여러 leadership term의 기록은 지원하지 않습니다" }
            leadershipTermId = frame.leadershipTermId
            when (frame) {
                is RecordedApplicationFrame -> {
                    applicationCount++
                    lastApplicationPosition = frame.position
                    if (retainFrames) inputs.add(frame.copy(fragmentCount = messageFragmentCount))
                }

                is RecordedControlFrame -> {
                    if (retainFrames) controls.add(frame)
                }
            }
            onValidatedMessage(frame, buffer, offset, length, messageFragmentCount)
            lastCompletedPosition = frame.position
        }

    fun onFragment(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
        header: Header,
    ) {
        if (failure != null) return
        try {
            require(offset >= 0 && length >= 0 && offset <= buffer.capacity() - length) { "잘못된 fragment offset/length" }
            require(header.frameLength() == length + DataHeaderFlyweight.HEADER_LENGTH) { "fragment 전송 헤더 길이가 다릅니다" }
            val position = header.position()
            require(position > lastFragmentPosition && position <= limitPosition) { "fragment가 고정한 L을 초과하거나 역행했습니다" }
            val flags = header.flags().toInt() and 0xff
            val begin = flags and FrameDescriptor.BEGIN_FRAG_FLAG.toInt() != 0
            val end = flags and FrameDescriptor.END_FRAG_FLAG.toInt() != 0
            if (begin) {
                require(!pending) { "이전 메시지가 재조립되기 전에 BEGIN을 받았습니다" }
                messageFragmentCount = 0
                messageBytes = 0
                pending = !end
                if (pending) fragmentedMessageCount++
            } else {
                require(pending) { "BEGIN 없는 fragment를 받았습니다" }
                require(header.termId() == fragmentTermId && header.termOffset() == nextTermOffset) { "fragment 중간이 누락됐습니다" }
                pending = !end
            }
            messageBytes = Math.addExact(messageBytes, length.toLong())
            require(messageBytes <= maximumMessageBytes) { "assembled message byte limit exceeded" }
            nextTermOffset = header.nextTermOffset()
            fragmentTermId = header.termId()
            lastFragmentPosition = position
            fragmentCount++
            messageFragmentCount++
            assembler.onFragment(buffer, offset, length, header)
        } catch (error: Exception) {
            failure = error.message ?: error.javaClass.simpleName
        }
    }

    fun completionFailure(consumedPosition: Long): String? =
        failure ?: when {
            consumedPosition != limitPosition -> "Image consumed=$consumedPosition, 고정한 L=$limitPosition 이전 종료 또는 초과"
            pending -> "L에서 fragment 재조립 미완료"
            lastCompletedPosition != limitPosition -> "마지막 완성 메시지 끝=$lastCompletedPosition, L=$limitPosition"
            applicationCount == 0L || lastApplicationPosition != limitPosition -> "마지막 대상 application frame이 L에 없습니다"
            else -> null
        }
}
