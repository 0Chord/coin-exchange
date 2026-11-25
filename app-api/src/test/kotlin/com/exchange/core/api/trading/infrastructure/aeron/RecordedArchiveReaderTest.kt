package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.archive.client.AeronArchive
import io.aeron.archive.status.RecordingPos
import io.aeron.driver.status.PublisherPos
import io.aeron.driver.status.StreamCounter
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.concurrent.status.CountersManager
import org.agrona.concurrent.status.CountersReader
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RecordedArchiveReaderTest {
    @Test
    fun `활성 recording이 하나일 때만 그 identity를 선택한다`() {
        val identity = identity(7)

        assertEquals(identity, RecordedArchiveReader.selectUniqueRecording(listOf(identity), null))
        assertEquals(identity, RecordedArchiveReader.selectUniqueRecording(listOf(identity), FundingRecording(97, 7, 12)))
    }

    @Test
    fun `첫 항목이나 가장 큰 ID 또는 기대 ID로 모호한 recording을 숨기지 않는다`() {
        assertFailsWith<IllegalArgumentException> { RecordedArchiveReader.selectUniqueRecording(emptyList(), null) }
        val first = identity(7)
        val later = identity(8)
        assertFailsWith<IllegalArgumentException> { RecordedArchiveReader.selectUniqueRecording(listOf(first, later), null) }
        assertFailsWith<IllegalArgumentException> {
            RecordedArchiveReader.selectUniqueRecording(listOf(first, later), FundingRecording(97, 8, 12))
        }
    }

    @Test
    fun `다른 Archive recording session identity는 검증 불가다`() {
        val identity = identity(7)
        listOf(FundingRecording(98, 7, 12), FundingRecording(97, 8, 12), FundingRecording(97, 7, 13)).forEach { other ->
            assertFailsWith<IllegalArgumentException> { RecordedArchiveReader.selectUniqueRecording(listOf(identity), other) }
        }
    }

    @Test
    fun `진짜 RecordingPos counter가 해제되거나 다른 identity로 재사용되면 거절한다`() {
        val counters =
            CountersManager(
                UnsafeBuffer(ByteBuffer.allocateDirect(CountersReader.METADATA_LENGTH * 4)),
                UnsafeBuffer(ByteBuffer.allocateDirect(CountersReader.COUNTER_LENGTH * 4)),
            )
        val identity = identity(7)
        val counterId = recordingCounter(counters, identity)
        RecordedArchiveReader.checkCounter(counters, counterId, identity)

        counters.free(counterId)
        assertFailsWith<IllegalArgumentException> { RecordedArchiveReader.checkCounter(counters, counterId, identity) }
        val reused = recordingCounter(counters, identity.copy(recordingId = 8))
        assertEquals(counterId, reused)
        assertFailsWith<IllegalArgumentException> { RecordedArchiveReader.checkCounter(counters, reused, identity) }
        counters.free(reused)
        val otherArchive = recordingCounter(counters, identity.copy(archiveId = 98))
        assertEquals(counterId, otherArchive)
        assertFailsWith<IllegalArgumentException> { RecordedArchiveReader.checkCounter(counters, otherArchive, identity) }
        counters.free(otherArchive)
        val otherSource = recordingCounter(counters, identity.copy(sourceIdentity = "127.0.0.1:19703"))
        assertEquals(counterId, otherSource)
        assertFailsWith<IllegalArgumentException> { RecordedArchiveReader.checkCounter(counters, otherSource, identity) }
    }

    @Test
    fun `publication tag와 session stream을 연결하고 잘리지 않은 전체 channel로 설정을 확인한다`() {
        val counters = counters()
        val channel = publicationChannel()
        assertTrue(channel.length > StreamCounter.MAX_CHANNEL_LENGTH)
        val counterId = publicationCounter(counters, channel)

        val actual = RecordedArchiveReader.publicationIdentity(counters, identity(7), expectedChannel())

        assertEquals(counterId, actual.counterId)
        assertEquals(123L, actual.registrationId)
        assertEquals(channel, actual.channel)
        listOf(
            expectedChannel().replace("19702", "1"),
            expectedChannel().replace("manual", "dynamic"),
            expectedChannel().replace("64k", "128k"),
            expectedChannel() + "|tags=99",
            expectedChannel() + "|session-id=99",
            expectedChannel() + "|endpoint=127.0.0.1:1",
            "aeron:udp",
            "aeron-spy:" + expectedChannel(),
        ).forEach { wrong ->
            assertFailsWith<IllegalArgumentException>(wrong) {
                RecordedArchiveReader.publicationIdentity(counters, identity(7), wrong)
            }
        }
    }

    @Test
    fun `publication label 잘림 key 불일치 registration 변경은 부분 channel로 보충하지 않는다`() {
        val truncated = counters()
        publicationCounter(truncated, publicationChannel() + "|ignored=" + "x".repeat(400))
        assertFailsWith<IllegalArgumentException> {
            RecordedArchiveReader.publicationIdentity(truncated, identity(7), expectedChannel())
        }
        val differentLabel = counters()
        val labelId = publicationCounter(differentLabel, publicationChannel())
        differentLabel.setCounterLabel(labelId, differentLabel.getCounterLabel(labelId).replace("19702", "19703"))
        assertFailsWith<IllegalArgumentException> {
            RecordedArchiveReader.publicationIdentity(differentLabel, identity(7), expectedChannel())
        }
        val differentRegistration = counters()
        val registrationId = publicationCounter(differentRegistration, publicationChannel())
        differentRegistration.setCounterRegistrationId(registrationId, 124)
        assertFailsWith<IllegalArgumentException> {
            RecordedArchiveReader.publicationIdentity(differentRegistration, identity(7), expectedChannel())
        }
        val malformedLabel = counters()
        val malformedId = publicationCounter(malformedLabel, publicationChannel())
        malformedLabel.setCounterLabel(malformedId, malformedLabel.getCounterLabel(malformedId).replace("123 12 100", "124 12 100"))
        assertFailsWith<IllegalArgumentException> {
            RecordedArchiveReader.publicationIdentity(malformedLabel, identity(7), expectedChannel())
        }
    }

    @Test
    fun `publication이 누락 모호하거나 counter가 재사용되면 최초 identity와 같지 않다`() {
        val missing = counters()
        publicationCounter(missing, publicationChannel().replace("tags=21,22", "tags=99,22"))
        assertFailsWith<IllegalArgumentException> {
            RecordedArchiveReader.publicationIdentity(missing, identity(7), expectedChannel())
        }
        val ambiguous = counters()
        publicationCounter(ambiguous, publicationChannel())
        publicationCounter(ambiguous, publicationChannel(), registrationId = 124)
        assertFailsWith<IllegalArgumentException> {
            RecordedArchiveReader.publicationIdentity(ambiguous, identity(7), expectedChannel())
        }
        val reused = counters()
        val firstCounter = publicationCounter(reused, publicationChannel())
        val before = RecordedArchiveReader.publicationIdentity(reused, identity(7), expectedChannel())
        reused.free(firstCounter)
        assertFailsWith<IllegalArgumentException> {
            RecordedArchiveReader.publicationIdentity(reused, identity(7), expectedChannel())
        }
        assertEquals(firstCounter, publicationCounter(reused, publicationChannel(), registrationId = 124))
        val after = RecordedArchiveReader.publicationIdentity(reused, identity(7), expectedChannel())
        assertNotEquals(before, after)
    }

    private fun expectedChannel(): String = "aeron:udp?term-length=64k|control-mode=manual|control=127.0.0.1:19702"

    private fun publicationChannel(): String = expectedChannel() + "|tags=21,22|alias=log|ssc=true|initial-term-id=12"

    private fun counters(): CountersManager =
        CountersManager(
            UnsafeBuffer(ByteBuffer.allocateDirect(CountersReader.METADATA_LENGTH * 8)),
            UnsafeBuffer(ByteBuffer.allocateDirect(CountersReader.COUNTER_LENGTH * 8)),
        )

    private fun publicationCounter(
        counters: CountersManager,
        channel: String,
        registrationId: Long = 123,
    ): Int =
        PublisherPos
            .allocate(UnsafeBuffer(ByteBuffer.allocateDirect(2048)), counters, 1, registrationId, 12, 100, channel, true)
            .id()

    private fun recordingCounter(
        counters: CountersManager,
        identity: RecordedLogIdentity,
    ): Int =
        counters.allocate("test recording", RecordingPos.RECORDING_POSITION_TYPE_ID) { key ->
            // Official RecordingPos key: recordingId, sessionId, sourceIdentity length/bytes, archiveId.
            key.putLong(0, identity.recordingId)
            key.putInt(8, identity.logSessionId)
            key.putInt(12, identity.sourceIdentity.length)
            key.putStringWithoutLengthAscii(16, identity.sourceIdentity)
            key.putLong(16 + identity.sourceIdentity.length, identity.archiveId)
        }

    private fun identity(recordingId: Long): RecordedLogIdentity =
        RecordedLogIdentity(
            97,
            recordingId,
            12,
            100,
            "aeron:udp?tags=21|session-id=12|alias=log",
            "aeron:udp?tags=21|session-id=12",
            "127.0.0.1:19702",
            0,
            AeronArchive.NULL_POSITION,
            64 * 1024,
            1408,
        )
}
