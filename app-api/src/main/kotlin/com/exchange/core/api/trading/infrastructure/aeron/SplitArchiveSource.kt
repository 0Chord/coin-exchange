package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.Aeron
import io.aeron.AeronCounters
import io.aeron.CncFileDescriptor
import io.aeron.archive.client.AeronArchive
import io.aeron.archive.client.ArchiveException
import io.aeron.archive.status.RecordingPos
import io.aeron.cluster.ConsensusModule
import io.aeron.cluster.ElectionState
import io.aeron.cluster.RecordingLog
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusterCounters
import io.aeron.driver.Configuration
import io.aeron.logbuffer.FrameDescriptor
import io.aeron.logbuffer.LogBufferDescriptor
import org.agrona.concurrent.status.CountersReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val SPLIT_ARCHIVE_APPLIED_TYPE_ID = 4_059
internal const val SPLIT_QUERY_REPLAY_STREAM_ID = 19_999
internal const val SPLIT_QUERY_REPLAY_ALIAS_PREFIX = "split-query-replay-"

internal fun splitArchiveAppliedLabel(
    clusterId: Int,
    memberId: Int,
    topologySha256: String,
): String = "split archive applied $clusterId $memberId $topologySha256"

internal data class SplitArchiveRead(
    val position: Long,
    val caughtUp: Boolean,
)

internal fun interface SplitArchiveSource : AutoCloseable {
    fun read(
        afterPosition: Long,
        consume: (RecordedApplicationFrame) -> Unit,
    ): SplitArchiveRead

    override fun close() = Unit
}

internal data class SplitArchiveMember(
    val memberId: Int,
    val driverDirectory: String,
    val clusterDirectory: Path,
) {
    init {
        require(memberId >= 0 && driverDirectory.isNotBlank())
        require(Path.of(driverDirectory).isAbsolute && clusterDirectory.isAbsolute)
    }
}

/** 같은 호스트의 노드에서 과반 커밋·서비스 적용·Archive 기록이 확인된 로그만 재생한다. */
internal class AeronSplitArchiveSource(
    members: List<SplitArchiveMember>,
    private val clusterId: Int,
    private val topologySha256: String,
    private val timeoutMs: Long = 3_000,
) : SplitArchiveSource {
    private class Connection(
        val member: SplitArchiveMember,
    ) {
        val failed = AtomicBoolean()
        var aeron: Aeron? = null
        var retryAt = 0L
        var appliedCounterId = CountersReader.NULL_COUNTER_ID
        var appliedCounterRegistration = Aeron.NULL_VALUE.toLong()
    }

    private data class Leader(
        val connection: Connection,
        val aeron: Aeron,
        val term: Long,
        val applied: Long,
        val committed: Long,
    ) {
        val position: Long get() = minOf(applied, committed)
    }

    private data class FrameBoundary(
        val start: Long,
        val frame: RecordedClusterFrame,
        val controlBytes: ByteArray?,
    )

    private data class Frontier(
        val leaderMemberId: Int,
        val leaderTerm: Long,
        val recordingId: Long,
        val aeron: Aeron,
        val boundary: FrameBoundary,
    )

    private val connections = members.map(::Connection)
    private var frontier: Frontier? = null
    private var archive: AeronArchive? = null
    private var archiveAeron: Aeron? = null

    init {
        require(members.isNotEmpty() && members.map { it.memberId }.distinct().size == members.size)
        require(members.map { it.driverDirectory }.distinct().size == members.size)
        require(members.map { it.clusterDirectory }.distinct().size == members.size)
        require(clusterId >= 0 && topologySha256.length == 64 && timeoutMs in 1..60_000)
    }

    override fun read(
        afterPosition: Long,
        consume: (RecordedApplicationFrame) -> Unit,
    ): SplitArchiveRead {
        if (afterPosition == 0L) frontier = null
        val previous = frontier
        require(afterPosition == (previous?.boundary?.frame?.position ?: 0L)) { "Archive 재생 시작 위치를 이어갈 수 없습니다" }
        val leader = selectLeader()
        check(previous == null || leader.term >= previous.leaderTerm) { "Archive 리더 임기가 이전 읽기보다 오래됐습니다" }
        if (leader.position < afterPosition) error("리더가 이전 Archive 재생 위치까지 적용하지 않았습니다")
        if (leader.position == afterPosition &&
            (
                previous == null || (
                    previous.leaderMemberId == leader.connection.member.memberId && previous.leaderTerm == leader.term &&
                        previous.aeron === leader.aeron && archiveAeron === leader.aeron
                )
            )
        ) {
            return SplitArchiveRead(afterPosition, afterPosition > 0L)
        }

        return try {
            val client = archive(leader.aeron)
            readCommitted(leader, client, previous, afterPosition, consume)
        } catch (failure: Exception) {
            try {
                closeArchive()
            } catch (cleanup: Exception) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun readCommitted(
        leader: Leader,
        client: AeronArchive,
        previous: Frontier?,
        from: Long,
        consume: (RecordedApplicationFrame) -> Unit,
    ): SplitArchiveRead {
        val recordingId = recordingId(leader)
        val recordingSessionId = recordingSession(client, recordingId)
        val reader = leader.aeron.countersReader()
        val counterId = RecordingPos.findCounterIdByRecording(reader, recordingId, clusterId.toLong())
        check(
            counterId != CountersReader.NULL_COUNTER_ID &&
                counterId == RecordingPos.findCounterIdBySession(reader, recordingSessionId, clusterId.toLong()),
        ) { "활성 Archive 세션과 recording.log의 기록이 일치하지 않습니다" }
        val bound = minOf(leader.position, reader.getCounterValue(counterId), client.getMaxRecordedPosition(recordingId))
        check(bound >= from) { "새 Archive가 이전 읽기 범위까지 기록하지 않았습니다" }
        require(bound % FrameDescriptor.FRAME_ALIGNMENT == 0L) { "Archive 끝 위치가 정렬되지 않았습니다" }

        val previousBoundary = previous?.boundary
        if (previous != null &&
            (
                previous.leaderMemberId != leader.connection.member.memberId || previous.leaderTerm != leader.term ||
                    previous.recordingId != recordingId || previous.aeron !== leader.aeron
            )
        ) {
            checkNotNull(previousBoundary)
            val overlap =
                replay(client, recordingId, previousBoundary.start, from, verifyTerms = false) { }
            require(overlap != null && sameFrame(overlap, previousBoundary)) { "리더 교체 후 Archive의 겹친 범위가 다릅니다" }
        }

        val frames = ArrayList<RecordedApplicationFrame>()
        val next =
            if (bound == from) {
                null
            } else {
                replay(client, recordingId, from, bound, initialTerm = previousBoundary?.frame?.leadershipTermId) { frame ->
                    if (frame is RecordedApplicationFrame) frames.add(frame)
                }
            }
        val verified = findLeader(leader.connection)
        check(
            verified?.term == leader.term && verified.aeron === leader.aeron && verified.position >= bound &&
                RecordingPos.isActive(reader, counterId, recordingId),
        ) { "읽는 동안 Archive 리더 또는 기록이 바뀌었습니다" }
        val boundary = next ?: previousBoundary
        if (boundary != null) {
            for (frame in frames) {
                try {
                    consume(frame)
                } catch (failure: Exception) {
                    // 일부 상태를 적용한 뒤에는 같은 위치를 재시도할 수 없다.
                    throw IllegalArgumentException("Archive 위치 ${frame.position} 반영 실패", failure)
                }
            }
            frontier = Frontier(leader.connection.member.memberId, leader.term, recordingId, leader.aeron, boundary)
        }
        val position = boundary?.frame?.position ?: from
        return SplitArchiveRead(position, position == leader.position && position > 0L)
    }

    private fun selectLeader(): Leader {
        val leaders = connections.mapNotNull(::findLeader)
        check(leaders.size == 1) { "확정된 Archive 리더가 한 명이어야 합니다" }
        return leaders.single()
    }

    private fun findLeader(connection: Connection): Leader? {
        val aeron = aeron(connection) ?: return null
        val reader = aeron.countersReader()

        fun value(type: Int): Long? {
            val id = ClusterCounters.find(reader, type, clusterId)
            return if (id == CountersReader.NULL_COUNTER_ID) null else reader.getCounterValue(id)
        }

        if (value(AeronCounters.CLUSTER_NODE_ROLE_TYPE_ID) !=
            Cluster.Role.LEADER
                .code()
                .toLong() ||
            value(AeronCounters.CLUSTER_ELECTION_STATE_TYPE_ID) != ElectionState.CLOSED.code().toLong()
        ) {
            return null
        }
        val term = value(AeronCounters.CLUSTER_LEADERSHIP_TERM_ID_TYPE_ID) ?: return null
        val committed = value(AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID) ?: return null
        val appliedCounterId = appliedCounterId(connection, reader)
        if (appliedCounterId == CountersReader.NULL_COUNTER_ID) return null
        val applied = reader.getCounterValue(appliedCounterId)
        if (minOf(term, committed, applied) < 0L || value(AeronCounters.CLUSTER_LEADERSHIP_TERM_ID_TYPE_ID) != term ||
            value(AeronCounters.CLUSTER_ELECTION_STATE_TYPE_ID) != ElectionState.CLOSED.code().toLong() ||
            value(AeronCounters.CLUSTER_NODE_ROLE_TYPE_ID) !=
            Cluster.Role.LEADER
                .code()
                .toLong()
        ) {
            return null
        }
        return Leader(connection, aeron, term, applied, committed)
    }

    private fun appliedCounterId(
        connection: Connection,
        reader: CountersReader,
    ): Int {
        val cached = connection.appliedCounterId
        if (cached != CountersReader.NULL_COUNTER_ID && reader.getCounterState(cached) == CountersReader.RECORD_ALLOCATED &&
            reader.getCounterTypeId(cached) == SPLIT_ARCHIVE_APPLIED_TYPE_ID &&
            reader.getCounterRegistrationId(cached) == connection.appliedCounterRegistration
        ) {
            return cached
        }
        val appliedLabel = splitArchiveAppliedLabel(clusterId, connection.member.memberId, topologySha256)
        var appliedCounterId = CountersReader.NULL_COUNTER_ID
        reader.forEach { id, typeId, _, label ->
            if (typeId == SPLIT_ARCHIVE_APPLIED_TYPE_ID && label == appliedLabel) {
                check(appliedCounterId == CountersReader.NULL_COUNTER_ID) { "Archive 적용 위치 counter가 여러 개입니다" }
                appliedCounterId = id
            }
        }
        connection.appliedCounterId = appliedCounterId
        if (appliedCounterId != CountersReader.NULL_COUNTER_ID) {
            connection.appliedCounterRegistration = reader.getCounterRegistrationId(appliedCounterId)
        }
        return appliedCounterId
    }

    private fun aeron(connection: Connection): Aeron? {
        connection.aeron?.let { existing ->
            if (!existing.isClosed && !connection.failed.get()) return existing
            if (archiveAeron === existing) closeArchive()
            existing.close()
            connection.aeron = null
            connection.appliedCounterId = CountersReader.NULL_COUNTER_ID
        }
        val now = System.nanoTime()
        if (now - connection.retryAt < 0L) return null
        if (!Files.isRegularFile(Path.of(connection.member.driverDirectory).resolve(CncFileDescriptor.CNC_FILE))) return null
        connection.retryAt = now + TimeUnit.MILLISECONDS.toNanos(250)
        connection.failed.set(false)
        return try {
            Aeron
                .connect(
                    Aeron
                        .Context()
                        .aeronDirectoryName(connection.member.driverDirectory)
                        .driverTimeoutMs(minOf(timeoutMs, 1_000))
                        .errorHandler { connection.failed.set(true) },
                ).also { connection.aeron = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun archive(aeron: Aeron): AeronArchive {
        if (archiveAeron !== aeron) closeArchive()
        archive?.let { return it }
        val opened =
            AeronArchive.connect(
                AeronArchive
                    .Context()
                    .aeron(aeron)
                    .ownsAeronClient(false)
                    .controlRequestChannel("aeron:ipc")
                    .controlResponseChannel("aeron:ipc")
                    .messageTimeoutNs(TimeUnit.MILLISECONDS.toNanos(timeoutMs)),
            )
        archive = opened
        archiveAeron = aeron
        require(opened.archiveId() == clusterId.toLong()) { "다른 클러스터 Archive입니다" }
        return opened
    }

    private fun recordingId(leader: Leader): Long {
        val directory = leader.connection.member.clusterDirectory
        check(Files.isRegularFile(directory.resolve(RecordingLog.RECORDING_LOG_FILE_NAME))) { "Archive recording.log가 없습니다" }
        return RecordingLog(directory.toFile(), false).use { log ->
            val entries = log.entries().filter { it.type == RecordingLog.ENTRY_TYPE_TERM && it.isValid }
            check(entries.isNotEmpty() && entries.last().leadershipTermId == leader.term) { "현재 선거 임기의 Archive 기록이 없습니다" }
            require(
                entries.first().termBaseLogPosition == 0L &&
                    entries.zipWithNext().all { (before, after) ->
                        before.leadershipTermId < after.leadershipTermId && before.logPosition == after.termBaseLogPosition
                    },
            ) { "Archive의 genesis나 임기별 기록 연결이 끊겼습니다" }
            entries.map { it.recordingId }.distinct().single()
        }
    }

    private fun recordingSession(
        client: AeronArchive,
        recordingId: Long,
    ): Int {
        var session: Int? = null
        val listed =
            client.listRecording(recordingId) { _, _, id, _, _, start, _, _, _, _, _, sessionId, streamId, _, _, _ ->
                require(id == recordingId && start == 0L && streamId == ConsensusModule.Configuration.logStreamId()) {
                    "Archive 카탈로그의 genesis 또는 로그 스트림이 다릅니다"
                }
                session = sessionId
            }
        check(listed == 1 && client.getStartPosition(recordingId) == 0L) { "필요한 genesis Archive 범위를 읽을 수 없습니다" }
        return checkNotNull(session)
    }

    private fun replay(
        archive: AeronArchive,
        recordingId: Long,
        from: Long,
        through: Long,
        initialTerm: Long? = null,
        verifyTerms: Boolean = true,
        onFrame: (RecordedClusterFrame) -> Unit,
    ): FrameBoundary? {
        val aeron = archive.context().aeron()
        val correlation = aeron.nextCorrelationId()
        val sessionId = correlation.toInt()
        val replayChannel = "aeron:ipc?session-id=$sessionId|alias=$SPLIT_QUERY_REPLAY_ALIAS_PREFIX$correlation"
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var last: RecordedClusterFrame? = null
        var lastControlBytes: ByteArray? = null
        val collector =
            RecordedReplayCollector(
                from,
                through,
                retainFrames = false,
                maximumMessageBytes = FrameDescriptor.computeMaxMessageLength(TradingAeronRuntime.TERM_LENGTH_BYTES),
                allowLeadershipChanges = verifyTerms,
                allowTermPadding = true,
                initialLeadershipTermId = initialTerm,
            ) { frame, buffer, offset, length, _ ->
                last = frame
                lastControlBytes =
                    if (frame is RecordedControlFrame) {
                        ByteArray(length).also { buffer.getBytes(offset, it) }
                    } else {
                        null
                    }
                onFrame(frame)
            }
        // 이전 재생의 IPC 로그가 해제될 때까지 기다려 거래 노드의 새 로그 공간을 남긴다.
        val driverFiles = Files.getFileStore(Path.of(aeron.context().aeronDirectoryName()))
        while (driverFiles.usableSpace < 2 * replayLogBytes) {
            check(System.nanoTime() - deadline < 0L) { "Archive 재생 IPC 로그 공간이 부족합니다" }
            Thread.sleep(20)
        }
        // 재생을 요청하기 전에 이 세션만 받을 구독을 열어 둔다.
        aeron.addSubscription(replayChannel, SPLIT_QUERY_REPLAY_STREAM_ID).use { subscription ->
            val replayId = archive.startReplay(recordingId, from, through - from, replayChannel, SPLIT_QUERY_REPLAY_STREAM_ID)
            try {
                check(replayId.toInt() == sessionId) { "Archive 재생 세션이 요청과 다릅니다" }
                var image = subscription.imageBySessionId(sessionId)
                while (image == null) {
                    waitForReplay(deadline)
                    image = subscription.imageBySessionId(sessionId)
                }
                require(image.joinPosition() == from) { "재생 시작 위치가 요청과 다릅니다" }
                while (image.position() < through && collector.lastCompletedPosition - from < TARGET_REPLAY_BYTES) {
                    val fragments = image.boundedPoll(collector::onFragment, through, 32)
                    collector.failure?.let { throw IllegalArgumentException(it) }
                    if (fragments == 0) {
                        check(!image.isClosed && !image.isEndOfStream) { "확정된 끝 위치보다 앞에서 Archive 재생이 종료됐습니다" }
                        waitForReplay(deadline)
                    }
                    check(System.nanoTime() - deadline < 0L) { "Archive 재생 시간이 초과했습니다" }
                }
            } finally {
                try {
                    archive.stopReplay(replayId)
                } catch (failure: ArchiveException) {
                    if (failure.errorCode() != ArchiveException.UNKNOWN_REPLAY) throw failure
                }
            }
        }
        return last?.let { FrameBoundary(collector.lastCompletedStartPosition, it, lastControlBytes) }
    }

    private fun waitForReplay(deadline: Long) {
        check(System.nanoTime() - deadline < 0L) { "Archive 재생 시간이 초과했습니다" }
        Thread.sleep(1)
    }

    private fun sameFrame(
        current: FrameBoundary,
        prior: FrameBoundary,
    ): Boolean {
        val actual = current.frame
        val previous = prior.frame
        return if (actual is RecordedApplicationFrame && previous is RecordedApplicationFrame) {
            actual.position == previous.position && actual.leadershipTermId == previous.leadershipTermId &&
                actual.clusterSessionId == previous.clusterSessionId && actual.timestamp == previous.timestamp &&
                actual.bytes.contentEquals(previous.bytes)
        } else {
            actual == previous && current.controlBytes.contentEquals(prior.controlBytes)
        }
    }

    private fun closeArchive() {
        val opened = archive
        archive = null
        archiveAeron = null
        opened?.close()
    }

    override fun close() {
        closeArchive()
        connections.forEach { it.aeron?.close() }
    }

    private companion object {
        // 한꺼번에 읽는 바이트는 제한하고, 뒤처진 원본은 IPC 재생 한 번에 더 많이 따라간다.
        const val TARGET_REPLAY_BYTES = 4 * 1_048_576L
        val replayLogBytes =
            LogBufferDescriptor.computeLogLength(TradingAeronRuntime.TERM_LENGTH_BYTES, Configuration.filePageSize())
    }
}
