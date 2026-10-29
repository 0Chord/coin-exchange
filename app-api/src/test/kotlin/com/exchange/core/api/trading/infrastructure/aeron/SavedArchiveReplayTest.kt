package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.codecs.MessageHeaderDecoder
import io.aeron.cluster.codecs.MessageHeaderEncoder
import io.aeron.cluster.codecs.SessionMessageHeaderEncoder
import io.aeron.cluster.codecs.TimerEventDecoder
import io.aeron.logbuffer.FrameDescriptor
import io.aeron.logbuffer.Header
import io.aeron.protocol.DataHeaderFlyweight
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure tiny metadata/SBE/filesystem fixtures: no Driver, Archive, Docker, or trading core is started. */
class SavedArchiveReplayTest {
    @TempDir lateinit var directory: Path
    private val bounds = SavedArchiveReplayBounds()

    @Test
    fun `all three complete spools compare every message byte and EOF including controls`() {
        val artifacts = (0..2).map { spool("copy$it", controlValue = 10) }
        val result = compareSavedReplaySpools(artifacts, bounds)
        assertEquals("MATCH", result.status)
        assertEquals(Files.size(Path.of(artifacts.first().file)), result.comparedBytes)
        assertEquals(1L, result.scans.first().applications)
        assertEquals(1L, result.scans.first().controls)
    }

    @Test
    fun `same template term position and counts cannot conceal a control payload byte difference`() {
        val artifacts = listOf(spool("a", 10), spool("b", 11), spool("c", 10))
        assertEquals(artifacts[0].scan, artifacts[1].scan)
        assertEquals("MISMATCH", compareSavedReplaySpools(artifacts, bounds).status)
    }

    @Test
    fun `valid framing and equal counts do not hide different application bytes`() {
        val artifacts = listOf(spool("a", 10), spool("b", 10, applicationValue = 12), spool("c", 10))
        assertEquals(artifacts[0].scan, artifacts[1].scan)
        assertEquals("MISMATCH", compareSavedReplaySpools(artifacts, bounds).status)
    }

    @Test
    fun `partial truncated and trailing byte spools are never complete prefix evidence`() {
        val good = spool("good", 10)
        val other = spool("other", 10)
        assertEquals("UNVERIFIABLE", compareSavedReplaySpools(listOf(good, good, other), bounds).status)
        val truncated = spool("truncated", 10)
        val path = Path.of(truncated.file)
        Files.write(path, Files.readAllBytes(path).dropLast(1).toByteArray())
        val rehashed = truncated.copy(fingerprint = savedArchiveFingerprint(path))
        assertEquals("UNVERIFIABLE", compareSavedReplaySpools(listOf(good, rehashed, other), bounds).status)
        val trailing = spool("trailing", 10)
        Files.write(Path.of(trailing.file), byteArrayOf(1), APPEND)
        assertEquals(
            "UNVERIFIABLE",
            compareSavedReplaySpools(
                listOf(good, trailing.copy(fingerprint = savedArchiveFingerprint(Path.of(trailing.file))), other),
                bounds,
            ).status,
        )
        val partial = SavedReplaySpoolWriter(directory.resolve("partial"), 0, 320, 4, bounds)
        val message = control(10)
        partial.append(
            RecordedClusterFrameParser.parse(UnsafeBuffer(message), 0, message.size, 128),
            UnsafeBuffer(message),
            0,
            message.size,
            1,
        )
        assertFailsWith<IllegalArgumentException> { partial.finish() }
        partial.close()
        val artifact = partial.artifact()
        assertFalse(artifact.complete)
        assertTrue(Files.size(Path.of(artifact.file)) > 0)
        assertEquals("UNVERIFIABLE", compareSavedReplaySpools(listOf(good, artifact, other), bounds).status)
    }

    @Test
    fun `writer enforces order term counts bytes and CREATE_NEW ownership`() {
        val file = directory.resolve("bounded")
        val writer = SavedReplaySpoolWriter(file, 0, 320, 4, bounds.copy(maxControlFrames = 0))
        val message = control(10)
        assertFailsWith<IllegalArgumentException> {
            writer.append(
                RecordedClusterFrameParser.parse(UnsafeBuffer(message), 0, message.size, 128),
                UnsafeBuffer(message),
                0,
                message.size,
                1,
            )
        }
        writer.close()
        assertFailsWith<Exception> { SavedReplaySpoolWriter(file, 0, 320, 4, bounds) }
        val order = SavedReplaySpoolWriter(directory.resolve("order"), 0, 320, 4, bounds)
        order.append(
            RecordedClusterFrameParser.parse(UnsafeBuffer(message), 0, message.size, 128),
            UnsafeBuffer(message),
            0,
            message.size,
            1,
        )
        assertFailsWith<IllegalArgumentException> {
            order.append(RecordedControlFrame(TimerEventDecoder.TEMPLATE_ID, 128, 4), UnsafeBuffer(message), 0, message.size, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            order.append(RecordedControlFrame(TimerEventDecoder.TEMPLATE_ID, 160, 5), UnsafeBuffer(message), 0, message.size, 1)
        }
        val app = application(8)
        order.append(RecordedClusterFrameParser.parse(UnsafeBuffer(app), 0, app.size, 320), UnsafeBuffer(app), 0, app.size, 1)
        assertFailsWith<IllegalStateException> { order.finish() } // A rejected record cannot be hidden by retrying a valid L.
        order.close()
    }

    @Test
    fun `sink receives exact assembled bytes while N-frame lists stay empty and original default retains frames`() {
        val message = application(8)
        val first = fragment(message.copyOfRange(0, 24), 0, FrameDescriptor.BEGIN_FRAG_FLAG.toInt())
        val second = fragment(message.copyOfRange(24, message.size), first.header.nextTermOffset(), FrameDescriptor.END_FRAG_FLAG.toInt())
        val limit = second.header.position()
        var captured: ByteArray? = null
        var fragments = 0
        val streamed =
            RecordedReplayCollector(0, limit, retainFrames = false, maximumMessageBytes = 64) { _, buffer, offset, length, count ->
                captured = ByteArray(length).also { buffer.getBytes(offset, it) }
                fragments = count
            }
        val original = RecordedReplayCollector(0, limit)
        for (collector in listOf(streamed, original)) {
            collector.onFragment(first.buffer, first.offset, first.length, first.header)
            collector.onFragment(second.buffer, second.offset, second.length, second.header)
            assertNull(collector.completionFailure(limit))
        }
        assertContentEquals(message, captured)
        assertEquals(2, fragments)
        assertTrue(streamed.inputs.isEmpty() && streamed.controls.isEmpty())
        assertEquals(1, original.inputs.size)
        assertEquals(2, original.inputs.single().fragmentCount)
    }

    @Test
    fun `sink failure message limit and orphan fragment remain explicit unsuccessful reads`() {
        val message = application(8)
        val frame = fragment(message, 0, FrameDescriptor.UNFRAGMENTED.toInt())
        val sinkFailure = RecordedReplayCollector(0, frame.header.position(), retainFrames = false) { _, _, _, _, _ -> error("spool full") }
        sinkFailure.onFragment(frame.buffer, frame.offset, frame.length, frame.header)
        assertTrue(assertNotNull(sinkFailure.completionFailure(frame.header.position())).contains("spool full"))
        val small = RecordedReplayCollector(0, frame.header.position(), retainFrames = false, maximumMessageBytes = 39)
        small.onFragment(frame.buffer, frame.offset, frame.length, frame.header)
        assertTrue(assertNotNull(small.failure).contains("byte limit"))
        val orphan = fragment(message, 0, FrameDescriptor.END_FRAG_FLAG.toInt())
        val collector = RecordedReplayCollector(0, orphan.header.position(), retainFrames = false)
        collector.onFragment(orphan.buffer, orphan.offset, orphan.length, orphan.header)
        assertTrue(assertNotNull(collector.failure).contains("BEGIN"))
    }

    @Test
    fun `leader follower and other channel shapes never share a permissive OR predicate`() {
        val plan = plan()
        val leader = descriptor("log", "")
        val follower = descriptor("log-cm", "|control-mode=manual|group=true|rejoin=false")
        validateSavedRecordingDescriptor(leader, plan, 0)
        validateSavedRecordingDescriptor(follower, plan, 1)
        assertFailsWith<IllegalArgumentException> { validateSavedRecordingDescriptor(follower, plan, 0) }
        assertFailsWith<IllegalArgumentException> { validateSavedRecordingDescriptor(leader, plan, 1) }
        for (wrong in listOf(
            leader.copy(streamId = 101),
            leader.copy(sessionId = 13),
            leader.copy(stop = -1),
            leader.copy(start = 32),
            leader.copy(originalChannel = leader.originalChannel.replace("alias=log", "alias=other")),
        )) {
            assertFailsWith<IllegalArgumentException> { validateSavedRecordingDescriptor(wrong, plan, 0) }
        }
        assertFailsWith<IllegalArgumentException> {
            validateSavedRecordingDescriptor(
                follower.copy(strippedChannel = follower.strippedChannel.replace("group=true", "group=false")),
                plan,
                1,
            )
        }
    }

    @Test
    fun `fresh SHA-verified work copy contains every owned source file and never overwrites evidence`() {
        val fixture = sourceFixture()
        val checked = verifySavedArchiveSources(fixture, SavedArchiveDeadline(30_000))
        assertEquals(6, checked.files.size)
        Files.createDirectory(fixture.workDirectory)
        copySavedArchiveSources(fixture, checked, SavedArchiveDeadline(30_000))
        for (file in checked.files) {
            assertEquals(file.fingerprint, savedArchiveFingerprint(fixture.sourceReports.resolve(file.artifactFile)))
            assertEquals(
                file.fingerprint,
                savedArchiveFingerprint(fixture.workDirectory.resolve(file.artifactFile.removePrefix("node-recordings/"))),
            )
        }
        assertFailsWith<Exception> { copySavedArchiveSources(fixture, checked, SavedArchiveDeadline(30_000)) }
        assertEquals(checked, verifySavedArchiveSources(fixture, SavedArchiveDeadline(30_000)))
    }

    @Test
    fun `source tamper unlisted files OOM and wrong member metadata reject before any replay`() {
        for (variant in listOf("tamper", "extra", "oom", "member")) {
            val fixture = sourceFixture(variant, oom = variant == "oom", wrongMember = variant == "member")
            if (variant ==
                "tamper"
            ) {
                Files.writeString(fixture.sourceReports.resolve("node-recordings/node0/archive/archive.catalog"), "changed")
            }
            if (variant == "extra") Files.writeString(fixture.sourceReports.resolve("node-recordings/node0/archive/unlisted"), "extra")
            assertFailsWith<IllegalArgumentException> { verifySavedArchiveSources(fixture, SavedArchiveDeadline(30_000)) }
            assertFalse(Files.exists(fixture.workDirectory))
        }
    }

    @Test
    fun `source symlinks cannot read another host file or become a verified working source`() {
        val fixture = sourceFixture()
        val source = fixture.sourceReports.resolve("node-recordings/node0/archive/archive.catalog")
        val outside = Files.writeString(directory.resolve("outside"), "outside data")
        Files.delete(source)
        Files.createSymbolicLink(source, outside)
        assertFailsWith<IllegalArgumentException> { verifySavedArchiveSources(fixture, SavedArchiveDeadline(30_000)) }
        assertEquals("outside data", Files.readString(outside))
    }

    @Test
    fun `negative nanoTime and Long wrap use elapsed differences while timeout and regression fail`() {
        var now = Long.MAX_VALUE - 3
        val deadline = SavedArchiveDeadline(1) { now }
        now += 10
        deadline.check()
        now += 1_000_000
        assertFailsWith<IllegalStateException> { deadline.check() }
        now = -100
        val negative = SavedArchiveDeadline(1) { now }
        now = -99
        negative.check()
        now = -101
        assertFailsWith<IllegalStateException> { negative.check() }
    }

    @Test
    fun `partial spool hash honors the cleanup deadline and leaves its original bytes for inspection`() {
        val file = directory.resolve("partial-hash-timeout")
        val writer = SavedReplaySpoolWriter(file, 0, 320, 4, bounds)
        val message = control(10)
        writer.append(
            RecordedClusterFrameParser.parse(UnsafeBuffer(message), 0, message.size, 128),
            UnsafeBuffer(message),
            0,
            message.size,
            1,
        )
        writer.close()
        val before = Files.readAllBytes(file)
        var checks = 0

        assertFailsWith<IllegalStateException> {
            writer.artifact {
                checks++
                error("cleanup deadline")
            }
        }

        assertEquals(1, checks)
        assertContentEquals(before, Files.readAllBytes(file))
        assertFalse(writer.artifact().complete)
    }

    private fun plan(
        source: Path = directory.resolve("source"),
        work: Path = directory.resolve("work"),
    ) = SavedArchiveReplayPlan(source, work, "saved-test", 0, 320, 4, 100, 0)

    private fun descriptor(
        alias: String,
        extra: String,
    ) = SavedRecordingDescriptor(
        0,
        12,
        100,
        0,
        640,
        65_536,
        1408,
        "aeron:udp?tags=21|session-id=12|alias=$alias$extra",
        "aeron:udp?tags=21|session-id=12|alias=$alias$extra",
        "127.0.0.1:12345",
    )

    private fun spool(
        name: String,
        controlValue: Long,
        applicationValue: Byte = 8,
    ): SavedReplaySpoolArtifact {
        val writer = SavedReplaySpoolWriter(directory.resolve(name), 0, 320, 4, bounds)
        for ((position, message) in listOf(128L to control(controlValue), 320L to application(applicationValue))) {
            val buffer = UnsafeBuffer(message)
            writer.append(RecordedClusterFrameParser.parse(buffer, 0, message.size, position), buffer, 0, message.size, 1)
        }
        return writer.finish()
    }

    private fun application(value: Byte): ByteArray {
        val buffer = UnsafeBuffer(ByteArray(AeronCluster.SESSION_HEADER_LENGTH + 8))
        SessionMessageHeaderEncoder()
            .wrapAndApplyHeader(
                buffer,
                0,
                MessageHeaderEncoder(),
            ).leadershipTermId(4)
            .clusterSessionId(12)
            .timestamp(9876)
        buffer.putBytes(AeronCluster.SESSION_HEADER_LENGTH, ByteArray(8) { value })
        return ByteArray(buffer.capacity()).also { buffer.getBytes(0, it) }
    }

    private fun control(value: Long): ByteArray {
        val buffer = UnsafeBuffer(ByteArray(MessageHeaderDecoder.ENCODED_LENGTH + TimerEventDecoder.BLOCK_LENGTH))
        MessageHeaderEncoder()
            .wrap(buffer, 0)
            .blockLength(TimerEventDecoder.BLOCK_LENGTH)
            .templateId(TimerEventDecoder.TEMPLATE_ID)
            .schemaId(MessageHeaderDecoder.SCHEMA_ID)
            .version(MessageHeaderDecoder.SCHEMA_VERSION)
        buffer.putLong(MessageHeaderDecoder.ENCODED_LENGTH, 4, LITTLE_ENDIAN)
        buffer.putLong(buffer.capacity() - 8, value, LITTLE_ENDIAN)
        return ByteArray(buffer.capacity()).also { buffer.getBytes(0, it) }
    }

    private data class Fragment(
        val buffer: UnsafeBuffer,
        val offset: Int,
        val length: Int,
        val header: Header,
    )

    private fun fragment(
        payload: ByteArray,
        termOffset: Int,
        flags: Int,
    ): Fragment {
        val buffer = UnsafeBuffer(ByteArray(DataHeaderFlyweight.HEADER_LENGTH + payload.size))
        buffer.putInt(0, buffer.capacity(), LITTLE_ENDIAN)
        buffer.putByte(5, flags.toByte())
        buffer.putShort(6, DataHeaderFlyweight.HDR_TYPE_DATA.toShort(), LITTLE_ENDIAN)
        buffer.putInt(DataHeaderFlyweight.TERM_OFFSET_FIELD_OFFSET, termOffset, LITTLE_ENDIAN)
        buffer.putInt(DataHeaderFlyweight.SESSION_ID_FIELD_OFFSET, 99, LITTLE_ENDIAN)
        buffer.putInt(DataHeaderFlyweight.STREAM_ID_FIELD_OFFSET, 19999, LITTLE_ENDIAN)
        buffer.putInt(DataHeaderFlyweight.TERM_ID_FIELD_OFFSET, 10, LITTLE_ENDIAN)
        buffer.putBytes(DataHeaderFlyweight.HEADER_LENGTH, payload)
        return Fragment(buffer, DataHeaderFlyweight.HEADER_LENGTH, payload.size, Header(10, 16).buffer(buffer).offset(0))
    }

    private fun sourceFixture(
        name: String = "fixture",
        oom: Boolean = false,
        wrongMember: Boolean = false,
    ): SavedArchiveReplayPlan {
        // macOS의 /var TempDir 별칭을 실제 경로로 해소한다. 입력의 symlink 거절 규칙은 유지한다.
        val root = directory.toRealPath()
        val reports = Files.createDirectory(root.resolve(name))
        val result = plan(reports, root.resolve("work-$name"))
        val rows = mutableListOf<Map<String, Any?>>()
        val members = mutableListOf<Map<String, Any?>>()
        for (member in 0..2) {
            val copies = mutableListOf<Map<String, Any?>>()
            for (kind in listOf("archive", "cluster")) {
                val relative = "node-recordings/node$member/$kind/${if (kind == "archive") "archive.catalog" else "recording.log"}"
                val file = reports.resolve(relative)
                Files.createDirectories(file.parent)
                Files.write(file, "$member-$kind-fixture".toByteArray())
                val fingerprint = savedArchiveFingerprint(file)
                val id = (member + 1).toString().repeat(64)
                val mount = "/var/lib/docker/volumes/saved-test_node$member-records/_data"
                rows.add(
                    mapOf(
                        "kind" to "HTTP_PERFORMANCE_NODE_RECORDING_FILE",
                        "schemaVersion" to 1,
                        "runId" to "saved-test",
                        "memberId" to member,
                        "containerId" to id,
                        "volume" to "saved-test_node$member-records",
                        "mountSource" to mount,
                        "recordingDirectoryKind" to kind,
                        "sourceDirectory" to "/records/saved-test/$kind",
                        "sourcePath" to "/records/saved-test/$kind/${file.fileName}",
                        "artifactFile" to if (wrongMember && member == 0) relative.replace("node0", "node1") else relative,
                        "bytes" to fingerprint.bytes,
                        "sha256" to fingerprint.sha256,
                    ),
                )
                copies.add(
                    mapOf(
                        "kind" to kind,
                        "sourceDirectory" to "/records/saved-test/$kind",
                        "artifactDirectory" to "node-recordings/node$member/$kind",
                        "files" to 1,
                        "bytes" to fingerprint.bytes,
                        "verified" to true,
                    ),
                )
            }
            members.add(
                mapOf(
                    "memberId" to member,
                    "containerId" to (member + 1).toString().repeat(64),
                    "volume" to "saved-test_node$member-records",
                    "mountSource" to "/var/lib/docker/volumes/saved-test_node$member-records/_data",
                    "runRoot" to "/records/saved-test",
                    "readyObserved" to true,
                    "stoppedState" to
                        mapOf("Running" to false, "Restarting" to false, "OOMKilled" to oom, "ExitCode" to if (oom) 137 else 0),
                    "copies" to copies,
                ),
            )
        }
        val inventoryFile = reports.resolve("node-recordings/files.jsonl")
        Files.writeString(inventoryFile, rows.joinToString("\n", postfix = "\n") { savedArchiveJson.writeValueAsString(it) })
        val fingerprint = savedArchiveFingerprint(inventoryFile)
        val manifest =
            mapOf(
                "kind" to "HTTP_PERFORMANCE_NODE_RECORDINGS",
                "schemaVersion" to 1,
                "runId" to "saved-test",
                "status" to "VERIFIED",
                "volumeDeletionAllowed" to true,
                "ownedVolumeDiscoveryVerified" to true,
                "ownedVolumes" to (0..2).map { "saved-test_node$it-records" },
                "failures" to emptyList<String>(),
                "members" to members,
                "inventory" to
                    mapOf(
                        "complete" to true,
                        "artifactFile" to "node-recordings/files.jsonl",
                        "records" to rows.size,
                        "bytes" to fingerprint.bytes,
                        "sha256" to fingerprint.sha256,
                    ),
            )
        Files.writeString(reports.resolve("node-recordings/manifest.json"), savedArchiveJson.writeValueAsString(manifest))
        return result
    }
}
