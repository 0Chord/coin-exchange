package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.Aeron
import io.aeron.ChannelUri
import io.aeron.archive.client.AeronArchive
import io.aeron.archive.client.ArchiveException
import io.aeron.archive.status.RecordingPos
import io.aeron.driver.status.PublisherPos
import io.aeron.driver.status.StreamCounter
import org.agrona.SystemUtil
import org.agrona.concurrent.status.CountersReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class RecordedLogIdentity(
    val archiveId: Long,
    val recordingId: Long,
    val logSessionId: Int,
    val streamId: Int,
    val originalChannel: String,
    val strippedChannel: String,
    val sourceIdentity: String,
    val startPosition: Long,
    val stopPosition: Long,
    val termBufferLength: Int,
    val mtuLength: Int,
)

internal data class RecordedPublicationIdentity(
    val counterId: Int,
    val registrationId: Long,
    val channel: String,
)

internal sealed interface RecordedArchiveRead {
    data class Complete(
        val recording: RecordedLogIdentity,
        val startPosition: Long,
        val limitPosition: Long,
        val consumedPosition: Long,
        val inputs: List<RecordedApplicationFrame>,
        val controls: List<RecordedControlFrame>,
    ) : RecordedArchiveRead

    data class Unavailable(
        val reason: String,
        val lastPosition: Long,
    ) : RecordedArchiveRead
}

/** Reads a finite replay while the original node remains alive; it never calls the trading core. */
internal class RecordedArchiveReader(
    private val config: DevelopmentFundingSettings,
    private val expectedLogStreamId: Int,
    private val expectedLogChannel: String,
    private val timeoutMs: Long = config.resultTimeoutMs,
) {
    init {
        require(timeoutMs in 1..60_000)
    }

    fun read(
        limitPosition: Long,
        expectedRecording: FundingRecording? = null,
    ): RecordedArchiveRead {
        var lastPosition = 0L
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val transportFailure = AtomicReference<Throwable?>()
        return try {
            require(limitPosition > 0 && limitPosition % 32 == 0L) { "L은 양수의 aligned 완료 위치여야 합니다" }
            val context =
                config
                    .archiveClient()
                    .messageTimeoutNs(TimeUnit.MILLISECONDS.toNanos(timeoutMs))
                    .errorHandler { transportFailure.compareAndSet(null, it) }
            AeronArchive.connect(context).use { archive ->
                require(archive.archiveId() == config.clusterId.toLong()) { "현재 실행과 다른 Archive입니다" }
                val aeron = archive.context().aeron()
                val counters = aeron.countersReader()
                val descriptors = listAllRecordings(archive, deadline)
                val active =
                    descriptors.filter { descriptor ->
                        descriptor.streamId == expectedLogStreamId &&
                            descriptor.stopPosition == AeronArchive.NULL_POSITION &&
                            channelMatches(descriptor) &&
                            RecordingPos.findCounterIdByRecording(counters, descriptor.recordingId, descriptor.archiveId).let { counter ->
                                if (counter == CountersReader.NULL_COUNTER_ID) {
                                    false
                                } else {
                                    require(
                                        RecordingPos.findCounterIdBySession(counters, descriptor.logSessionId, descriptor.archiveId) ==
                                            counter &&
                                            RecordingPos.getRecordingId(counters, counter) == descriptor.recordingId &&
                                            RecordingPos.isActive(counters, counter, descriptor.recordingId),
                                    ) { "recording/session counter 식별이 모호하거나 재사용됐습니다" }
                                    true
                                }
                            }
                    }
                val recording = selectUniqueRecording(active, expectedRecording)
                lastPosition = recording.startPosition
                require(recording.startPosition >= 0 && recording.startPosition % 32 == 0L && recording.startPosition < limitPosition) {
                    "S < L인 aligned 유효 범위가 없습니다"
                }
                val expectedTermLength = ChannelUri.parse(expectedLogChannel).get("term-length")
                require(
                    expectedTermLength == null ||
                        recording.termBufferLength.toLong() == SystemUtil.parseSize("term-length", expectedTermLength),
                ) {
                    "descriptor의 term 길이가 현재 로그와 다릅니다"
                }
                val counter = RecordingPos.findCounterIdBySession(counters, recording.logSessionId, recording.archiveId)
                checkCounter(counters, counter, recording)
                val publication = publicationIdentity(counters, recording, expectedLogChannel)
                require(archive.getStartPosition(recording.recordingId) == recording.startPosition) { "descriptor와 실제 시작 위치가 다릅니다" }
                while (true) {
                    checkDeadline(deadline)
                    checkCounter(counters, counter, recording)
                    transportFailure.get()?.let { throw IllegalStateException("Aeron 읽기 연결 실패", it) }
                    val position = counters.getCounterValue(counter)
                    require(position >= recording.startPosition) { "recording counter 위치가 시작보다 작습니다" }
                    if (position >= limitPosition) break
                    Thread.sleep(1)
                }
                val maximum = archive.getMaxRecordedPosition(recording.recordingId)
                val counterPosition = counters.getCounterValue(counter)
                require(maximum <= counterPosition) { "Archive 최대 위치와 활성 counter가 일치하지 않습니다" }
                require(maximum >= limitPosition && maximum >= recording.startPosition) { "실제 기록이 L에 도달하지 않았습니다" }
                checkCounter(counters, counter, recording)
                require(publicationIdentity(counters, recording, expectedLogChannel) == publication) { "로그 publication identity가 바뀌었습니다" }
                val replayChannel = "aeron:ipc?alias=recorded-input-${aeron.nextCorrelationId()}"
                val replayStreamId = 19999
                val collector = RecordedReplayCollector(recording.startPosition, limitPosition)
                var replayId = Aeron.NULL_VALUE.toLong()
                var result: RecordedArchiveRead
                aeron.addSubscription(replayChannel, replayStreamId).use { subscription ->
                    try {
                        replayId =
                            archive.startReplay(
                                recording.recordingId,
                                recording.startPosition,
                                limitPosition - recording.startPosition,
                                replayChannel,
                                replayStreamId,
                            )
                        var image = subscription.imageBySessionId(replayId.toInt())
                        while (image == null) {
                            checkDeadline(deadline)
                            checkCounter(counters, counter, recording)
                            transportFailure.get()?.let { throw IllegalStateException("Aeron replay 연결 실패", it) }
                            Thread.sleep(1)
                            image = subscription.imageBySessionId(replayId.toInt())
                        }
                        require(image.joinPosition() == recording.startPosition) { "replay Image 시작 위치가 S와 다릅니다" }
                        require(image.sessionId() == replayId.toInt()) { "다른 replay Image입니다" }
                        require(image.termBufferLength() == recording.termBufferLength) { "replay Image의 term 길이가 다릅니다" }
                        while (true) {
                            checkDeadline(deadline)
                            checkCounter(counters, counter, recording)
                            transportFailure.get()?.let { throw IllegalStateException("Aeron replay 읽기 실패", it) }
                            val fragments = image.poll(collector::onFragment, 10)
                            lastPosition = image.position()
                            collector.failure?.let { throw IllegalStateException(it) }
                            if (lastPosition >= limitPosition || image.isClosed || image.isEndOfStream) {
                                checkCounter(counters, counter, recording)
                                require(publicationIdentity(counters, recording, expectedLogChannel) == publication) {
                                    "읽는 동안 로그 publication identity가 바뀌었습니다"
                                }
                                val problem = collector.completionFailure(lastPosition)
                                result =
                                    if (problem == null) {
                                        RecordedArchiveRead.Complete(
                                            recording,
                                            recording.startPosition,
                                            limitPosition,
                                            lastPosition,
                                            collector.inputs.toList(),
                                            collector.controls.toList(),
                                        )
                                    } else {
                                        RecordedArchiveRead.Unavailable(problem, lastPosition)
                                    }
                                break
                            }
                            if (fragments == 0) Thread.sleep(1)
                        }
                    } finally {
                        if (replayId != Aeron.NULL_VALUE.toLong()) {
                            try {
                                archive.stopReplay(replayId)
                            } catch (error: ArchiveException) {
                                // A finite replay can have completed and removed its own session already.
                                if (error.errorCode() != ArchiveException.UNKNOWN_REPLAY) throw error
                            }
                        }
                    }
                }
                result
            }
        } catch (error: Exception) {
            RecordedArchiveRead.Unavailable(error.message ?: error.javaClass.simpleName, lastPosition)
        }
    }

    private fun listAllRecordings(
        archive: AeronArchive,
        deadline: Long,
    ): List<RecordedLogIdentity> {
        val descriptors = mutableListOf<RecordedLogIdentity>()
        var fromId = 0L
        while (true) {
            checkDeadline(deadline)
            val page = mutableListOf<RecordedLogIdentity>()
            val count =
                archive.listRecordings(fromId, 100) {
                    _,
                    _,
                    id,
                    _,
                    _,
                    start,
                    stop,
                    _,
                    _,
                    termLength,
                    mtu,
                    sessionId,
                    streamId,
                    stripped,
                    original,
                    source,
                    ->
                    page.add(
                        RecordedLogIdentity(
                            archive.archiveId(),
                            id,
                            sessionId,
                            streamId,
                            original,
                            stripped,
                            source,
                            start,
                            stop,
                            termLength,
                            mtu,
                        ),
                    )
                }
            require(count == page.size && page.all { it.recordingId >= fromId }) { "불완전하거나 역행한 descriptor 목록" }
            require(page.zipWithNext().all { (left, right) -> left.recordingId < right.recordingId }) { "중복 descriptor 목록" }
            descriptors.addAll(page)
            if (count < 100) return descriptors
            fromId = Math.addExact(page.last().recordingId, 1)
        }
    }

    private fun channelMatches(recording: RecordedLogIdentity): Boolean {
        val expected = ChannelUri.parse(expectedLogChannel)
        val original = ChannelUri.parse(recording.originalChannel)
        val stripped = ChannelUri.parse(recording.strippedChannel)
        // Cluster records a tag reference to its publication, rather than copying its control endpoint.
        return original.media() == expected.media() && stripped.media() == expected.media() &&
            original.get("alias") == "log" && !original.get("tags").isNullOrBlank() &&
            original.get("tags") == stripped.get("tags") &&
            original.get("session-id") == recording.logSessionId.toString() &&
            stripped.get("session-id") == recording.logSessionId.toString()
    }

    private fun checkDeadline(deadline: Long) {
        check(System.nanoTime() < deadline) { "Archive 식별/기록/replay 기한을 초과했습니다" }
    }

    companion object {
        internal fun publicationIdentity(
            counters: CountersReader,
            recording: RecordedLogIdentity,
            expectedChannel: String,
        ): RecordedPublicationIdentity {
            val original = ChannelUri.parse(recording.originalChannel)
            val expected = ChannelUri.parse(expectedChannel)
            require(!original.channelTag().isNullOrBlank()) { "recording publication tag가 없습니다" }
            val matches = mutableListOf<RecordedPublicationIdentity>()
            counters.forEach { counterId, typeId, key, label ->
                if (typeId == PublisherPos.PUBLISHER_POS_TYPE_ID &&
                    key.getInt(StreamCounter.SESSION_ID_OFFSET) == recording.logSessionId &&
                    key.getInt(StreamCounter.STREAM_ID_OFFSET) == recording.streamId
                ) {
                    val registrationId = key.getLong(StreamCounter.REGISTRATION_ID_OFFSET)
                    require(counters.getCounterRegistrationId(counterId) == registrationId) { "publication counter registration이 다릅니다" }
                    val prefix = "pub-pos (exclusive): $registrationId ${recording.logSessionId} ${recording.streamId} "
                    require(label.startsWith(prefix) && label.length < CountersReader.MAX_LABEL_LENGTH) {
                        "publication channel label이 잘렸거나 식별되지 않습니다"
                    }
                    val channel = label.substring(prefix.length)
                    // StreamCounter truncates its key channel to 92 bytes; its bounded label can retain the full URI.
                    val keyLength = key.getInt(StreamCounter.CHANNEL_OFFSET)
                    require(
                        keyLength in 1..StreamCounter.MAX_CHANNEL_LENGTH &&
                            keyLength == minOf(channel.length, StreamCounter.MAX_CHANNEL_LENGTH),
                    ) {
                        "publication channel key 길이가 다릅니다"
                    }
                    require(
                        key.getStringWithoutLengthAscii(
                            StreamCounter.CHANNEL_OFFSET + Int.SIZE_BYTES,
                            keyLength,
                        ) == channel.take(keyLength),
                    ) {
                        "publication channel key와 label이 다릅니다"
                    }
                    val actual = ChannelUri.parse(channel)
                    if (actual.channelTag() == original.channelTag()) {
                        require(
                            actual.prefix() == original.prefix() && actual.media() == original.media() && actual.get("alias") == "log",
                        ) {
                            "recording tag가 다른 publication channel을 가리킵니다"
                        }
                        matches.add(RecordedPublicationIdentity(counterId, registrationId, channel))
                    }
                }
            }
            require(matches.size == 1) { "recording tag의 활성 publication을 하나로 확정할 수 없습니다: ${matches.size}개" }
            val publication = matches.single()
            require(
                counters.getCounterState(publication.counterId) == CountersReader.RECORD_ALLOCATED &&
                    counters.getCounterTypeId(publication.counterId) == PublisherPos.PUBLISHER_POS_TYPE_ID &&
                    counters.getCounterRegistrationId(publication.counterId) == publication.registrationId,
            ) { "확인 중 publication counter가 종료되거나 재사용됐습니다" }
            val actual = ChannelUri.parse(publication.channel)
            require(actual.prefix() == expected.prefix() && actual.media() == expected.media()) { "기대한 로그 channel prefix/media가 다릅니다" }
            listOf("control", "control-mode", "endpoint", "term-length").forEach { parameter ->
                require(actual.get(parameter) == expected.get(parameter)) { "기대한 로그 channel $parameter 값이 다릅니다" }
            }
            expected.forEachParameter { parameter, value ->
                require(actual.get(parameter) == value) { "기대한 로그 channel $parameter 값이 다릅니다" }
            }
            return publication
        }

        internal fun checkCounter(
            counters: CountersReader,
            counterId: Int,
            recording: RecordedLogIdentity,
        ) {
            require(RecordingPos.isActive(counters, counterId, recording.recordingId)) { "recording counter가 종료되거나 재사용됐습니다" }
            require(RecordingPos.findCounterIdBySession(counters, recording.logSessionId, recording.archiveId) == counterId) {
                "recording counter의 Archive/session identity가 바뀌었습니다"
            }
            require(RecordingPos.getSourceIdentity(counters, counterId) == recording.sourceIdentity) {
                "recording counter의 source identity가 바뀌었습니다"
            }
        }

        internal fun selectUniqueRecording(
            active: List<RecordedLogIdentity>,
            expected: FundingRecording?,
        ): RecordedLogIdentity {
            require(active.size == 1) { "활성 로그 recording을 하나로 확정할 수 없습니다: ${active.size}개" }
            val recording = active.single()
            require(
                expected == null ||
                    expected == FundingRecording(recording.archiveId, recording.recordingId, recording.logSessionId),
            ) { "요청한 recording/session/Archive와 활성 로그가 다릅니다" }
            return recording
        }
    }
}
