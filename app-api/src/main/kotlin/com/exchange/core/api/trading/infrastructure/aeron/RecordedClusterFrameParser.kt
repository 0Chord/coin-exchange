package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.FragmentAssembler
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.codecs.ClusterActionRequestDecoder
import io.aeron.cluster.codecs.ClusterTimeUnit
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
import org.agrona.BitUtil
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
    val leadershipChange: RecordedLeadershipChange? = null,
) : RecordedClusterFrame

internal data class RecordedLeadershipChange(
    val basePosition: Long,
    val endPosition: Long,
    val leaderMemberId: Int,
    val logSessionId: Int,
    val timeUnit: ClusterTimeUnit,
)

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
            require(length >= AeronCluster.SESSION_HEADER_LENGTH) { "잘린 session wrapper" }
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
        if (templateId == NewLeadershipTermEventDecoder.TEMPLATE_ID) {
            val term = NewLeadershipTermEventDecoder().wrap(buffer, body, header.blockLength(), header.version())
            val change =
                RecordedLeadershipChange(
                    basePosition = term.termBaseLogPosition(),
                    endPosition = term.logPosition(),
                    leaderMemberId = term.leaderMemberId(),
                    logSessionId = term.logSessionId(),
                    timeUnit = term.timeUnit(),
                )
            return RecordedControlFrame(templateId, position, term.leadershipTermId(), change)
        }
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
    private val allowLeadershipChanges: Boolean = false,
    private val allowTermPadding: Boolean = false,
    initialLeadershipTermId: Long? = null,
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
    var lastCompletedStartPosition: Long = startPosition
        private set
    var fragmentCount: Int = 0
        private set
    var fragmentedMessageCount: Int = 0
        private set
    private var pending = false
    private var nextTermOffset = 0
    private var fragmentTermId = 0
    private var lastFragmentPosition = startPosition
    private var leadershipTermId: Long? = initialLeadershipTermId
    private var messageStartPosition = startPosition
    private var messageFragmentCount = 0
    private var messageBytes = 0L
    private var applicationCount = 0L
    private var lastApplicationPosition = startPosition
    private val assembler =
        FragmentAssembler { buffer, offset, length, header ->
            val frame = RecordedClusterFrameParser.parse(buffer, offset, length, header.position())
            require(frame.position > lastCompletedPosition && frame.position <= limitPosition) { "완성 메시지 위치가 범위를 벗어났습니다" }
            verifyLeadershipTerm(frame)
            lastCompletedStartPosition = messageStartPosition
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

    private fun verifyLeadershipTerm(frame: RecordedClusterFrame) {
        val previousTerm = leadershipTermId
        val change = (frame as? RecordedControlFrame)?.leadershipChange
        if (allowLeadershipChanges) {
            if (previousTerm != frame.leadershipTermId) {
                // Aeron은 term 끝을 padding으로 마친 뒤 새 리더의 첫 프레임 위치를 기준으로 삼을 수 있다.
                require(change != null && (change.basePosition == lastCompletedPosition || change.basePosition == messageStartPosition)) {
                    "이어지는 임기 시작 위치가 없습니다"
                }
                require(previousTerm == null || frame.leadershipTermId > previousTerm) { "자금 로그 임기가 역행했습니다" }
                require(change.endPosition == frame.position) { "기록된 임기 변경의 끝 위치가 다릅니다" }
                require(change.timeUnit == ClusterTimeUnit.MILLIS) { "기록된 임기 변경의 시각은 밀리초여야 합니다" }
            } else {
                require(change == null) { "같은 임기 안에 임기 시작 기록이 반복됩니다" }
            }
        } else {
            require(previousTerm == null || previousTerm == frame.leadershipTermId) { "여러 leadership term의 기록은 지원하지 않습니다" }
        }
        leadershipTermId = frame.leadershipTermId
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
            val alignedLength = BitUtil.align(header.frameLength(), FrameDescriptor.FRAME_ALIGNMENT)
            val fragmentStart = position - alignedLength
            if (allowLeadershipChanges) {
                require(fragmentStart == lastFragmentPosition || isTermPadding(header, fragmentStart)) {
                    "Archive 재생 범위에 공백이 있습니다"
                }
            }
            val flags = header.flags().toInt() and 0xff
            val begin = flags and FrameDescriptor.BEGIN_FRAG_FLAG.toInt() != 0
            val end = flags and FrameDescriptor.END_FRAG_FLAG.toInt() != 0
            if (begin) {
                require(!pending) { "이전 메시지가 재조립되기 전에 BEGIN을 받았습니다" }
                messageStartPosition = fragmentStart
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

    private fun isTermPadding(
        header: Header,
        fragmentStart: Long,
    ): Boolean {
        if (!allowTermPadding || pending || header.termOffset() != 0) return false
        val termLength = 1L shl header.positionBitsToShift()
        val previousOffset = lastFragmentPosition and (termLength - 1)
        return previousOffset != 0L && fragmentStart - lastFragmentPosition == termLength - previousOffset
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
