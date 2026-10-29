package com.exchange.core.api.trading.infrastructure.aeron

import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

internal data class SavedArchiveReplayBounds(
    val maxRangeBytes: Long = 4L * 1024 * 1024,
    val maxMessageBytes: Int = 65_536,
    val maxApplicationFrames: Long = 256,
    val maxControlFrames: Long = 1024,
    val maxDescriptors: Int = 256,
    val maxFiles: Int = 4096,
    val timeoutMs: Long = 30_000,
) {
    init {
        require(maxRangeBytes in 32..(64L * 1024 * 1024))
        require(maxMessageBytes in 40..65_536)
        require(maxApplicationFrames in 1..1_000_000 && maxControlFrames in 0..1_000_000)
        require(maxDescriptors in 1..4096 && maxFiles in 1..4096 && timeoutMs in 1..60_000)
    }
}

internal data class SavedArchiveFingerprint(
    val bytes: Long,
    val sha256: String,
)

internal fun savedArchiveFingerprint(
    file: Path,
    checkDeadline: () -> Unit = {},
): SavedArchiveFingerprint {
    val before = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(before.isRegularFile && !before.isSymbolicLink) { "not a regular file: $file" }
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            checkDeadline()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            bytes = Math.addExact(bytes, count.toLong())
        }
    }
    val after = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(
        after.isRegularFile && bytes == before.size() && bytes == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey(),
    ) { "file changed during hash: $file" }
    return SavedArchiveFingerprint(bytes, HexFormat.of().formatHex(digest.digest()))
}

internal data class SavedReplaySpoolScan(
    val startPosition: Long,
    val limitPosition: Long,
    val leadershipTermId: Long,
    val applications: Long,
    val controls: Long,
    val messageBytes: Long,
    val lastPosition: Long,
)

internal data class SavedReplaySpoolArtifact(
    val file: String,
    val complete: Boolean,
    val fingerprint: SavedArchiveFingerprint?,
    val scan: SavedReplaySpoolScan?,
)

private const val SAVED_REPLAY_SPOOL_MAGIC = 0x53415250 // SARP, deliberately excludes member-specific identity.
private const val SAVED_REPLAY_SPOOL_VERSION = 1

/** Full assembled Cluster SBE messages, including controls. No whole-run message list. */
internal class SavedReplaySpoolWriter(
    private val file: Path,
    private val start: Long,
    private val limit: Long,
    private val term: Long,
    private val bounds: SavedArchiveReplayBounds,
) : AutoCloseable {
    private val output: DataOutputStream
    private val buffer = ByteArray(65_536)
    private var applications = 0L
    private var controls = 0L
    private var bytes = 0L
    private var last = start
    private var lastApplication = start
    private var closed = false
    private var complete = false
    private var poisoned = false
    private var result: SavedReplaySpoolScan? = null

    init {
        require(start >= 0 && start % 32 == 0L && limit > start && limit % 32 == 0L && limit - start <= bounds.maxRangeBytes)
        output = DataOutputStream(BufferedOutputStream(Files.newOutputStream(file, CREATE_NEW, WRITE)))
        try {
            output.writeInt(SAVED_REPLAY_SPOOL_MAGIC)
            output.writeInt(SAVED_REPLAY_SPOOL_VERSION)
            output.writeLong(start)
            output.writeLong(limit)
            output.writeLong(term)
        } catch (failure: Exception) {
            try {
                output.close()
            } catch (secondary: Exception) {
                failure.addSuppressed(secondary)
            }
            throw failure
        }
    }

    fun append(
        frame: RecordedClusterFrame,
        source: DirectBuffer,
        offset: Int,
        length: Int,
        fragments: Int,
    ) {
        check(!closed && !complete)
        try {
            require(length in 8..bounds.maxMessageBytes && offset >= 0 && offset <= source.capacity() - length)
            require(frame.position > last && frame.position <= limit && frame.leadershipTermId == term && fragments > 0)
            val application = frame is RecordedApplicationFrame
            val nextApplications = applications + if (application) 1 else 0
            val nextControls = controls + if (application) 0 else 1
            val nextBytes = Math.addExact(bytes, length.toLong())
            require(
                nextApplications <= bounds.maxApplicationFrames && nextControls <= bounds.maxControlFrames &&
                    nextBytes <= bounds.maxRangeBytes,
            ) {
                "spool frame or byte limit exceeded"
            }
            output.writeByte(if (application) 1 else 2)
            output.writeLong(frame.position)
            output.writeLong(frame.leadershipTermId)
            output.writeInt(length)
            output.writeInt(fragments)
            var copied = 0
            while (copied < length) {
                val count = minOf(buffer.size, length - copied)
                source.getBytes(offset + copied, buffer, 0, count)
                output.write(buffer, 0, count)
                copied += count
            }
            applications = nextApplications
            controls = nextControls
            bytes = nextBytes
            last = frame.position
            if (application) lastApplication = last
        } catch (failure: Exception) {
            poisoned = true
            throw failure
        }
    }

    fun finish(): SavedReplaySpoolArtifact {
        check(!closed && !complete && !poisoned) { "spool writer failed or closed" }
        require(last == limit && applications > 0 && lastApplication == limit) { "spool did not end at application L" }
        output.writeByte(0)
        output.writeLong(applications)
        output.writeLong(controls)
        output.writeLong(bytes)
        output.writeLong(last)
        close()
        complete = true
        result = SavedReplaySpoolScan(start, limit, term, applications, controls, bytes, last)
        return artifact()
    }

    fun artifact(checkDeadline: () -> Unit = SavedArchiveDeadline(bounds.timeoutMs)::check): SavedReplaySpoolArtifact =
        SavedReplaySpoolArtifact(
            file.toString(),
            complete,
            if (Files.isRegularFile(file, NOFOLLOW_LINKS)) savedArchiveFingerprint(file, checkDeadline) else null,
            result,
        )

    override fun close() {
        if (!closed) {
            closed = true
            output.close()
        }
    }
}

internal fun scanSavedReplaySpool(
    file: Path,
    bounds: SavedArchiveReplayBounds,
    checkDeadline: () -> Unit = {},
): SavedReplaySpoolScan {
    require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
    DataInputStream(BufferedInputStream(Files.newInputStream(file, NOFOLLOW_LINKS))).use { input ->
        require(input.readInt() == SAVED_REPLAY_SPOOL_MAGIC && input.readInt() == SAVED_REPLAY_SPOOL_VERSION) { "spool header" }
        val start = input.readLong()
        val limit = input.readLong()
        val term = input.readLong()
        require(start >= 0 && start % 32 == 0L && limit > start && limit % 32 == 0L && limit - start <= bounds.maxRangeBytes)
        var applications = 0L
        var controls = 0L
        var bytes = 0L
        var last = start
        var lastApplication = start
        while (true) {
            checkDeadline()
            val kind = input.readUnsignedByte() // EOF without a trailer is a failure, including partial spools.
            if (kind == 0) {
                require(
                    input.readLong() == applications && input.readLong() == controls && input.readLong() == bytes &&
                        input.readLong() == last,
                ) {
                    "spool trailer counts"
                }
                require(last == limit && lastApplication == limit && applications > 0) { "spool application L missing" }
                require(input.read() == -1) { "spool trailing bytes" }
                return SavedReplaySpoolScan(start, limit, term, applications, controls, bytes, last)
            }
            require(kind == 1 || kind == 2) { "spool record kind" }
            val position = input.readLong()
            val recordedTerm = input.readLong()
            val length = input.readInt()
            val fragments = input.readInt()
            require(position > last && position <= limit && recordedTerm == term && fragments > 0) { "spool record identity/order" }
            require(length in 8..bounds.maxMessageBytes) { "spool message length" }
            bytes = Math.addExact(bytes, length.toLong())
            require(bytes <= bounds.maxRangeBytes) { "spool byte limit" }
            val payload = ByteArray(length) // One bounded message, never N retained messages.
            input.readFully(payload)
            val parsed = RecordedClusterFrameParser.parse(UnsafeBuffer(payload), 0, length, position)
            require(
                parsed.leadershipTermId == term && ((parsed is RecordedApplicationFrame) == (kind == 1)),
            ) { "spool payload/header mismatch" }
            if (kind == 1) {
                applications++
                lastApplication = position
            } else {
                controls++
            }
            require(applications <= bounds.maxApplicationFrames && controls <= bounds.maxControlFrames) { "spool count limit" }
            last = position
        }
    }
    error("spool scan ended without trailer")
}

internal data class SavedReplaySpoolComparison(
    val status: String,
    val reason: String?,
    val comparedBytes: Long,
    val scans: List<SavedReplaySpoolScan>,
)

/** Validate framing and recorded metadata, then directly compare every byte and EOF of all three files. */
internal fun compareSavedReplaySpools(
    artifacts: List<SavedReplaySpoolArtifact>,
    bounds: SavedArchiveReplayBounds,
): SavedReplaySpoolComparison {
    val scans = mutableListOf<SavedReplaySpoolScan>()
    var compared = 0L
    val deadline = SavedArchiveDeadline(bounds.timeoutMs)
    return try {
        require(
            artifacts.size == 3 && artifacts.map { Path.of(it.file).toAbsolutePath().normalize() }.distinct().size == 3 &&
                artifacts.all { it.complete && it.fingerprint != null && it.scan != null },
        ) { "three complete spools required" }
        for (artifact in artifacts) {
            val file = Path.of(artifact.file)
            require(savedArchiveFingerprint(file, deadline::check) == artifact.fingerprint) { "spool file identity changed" }
            val scan = scanSavedReplaySpool(file, bounds, deadline::check)
            require(scan == artifact.scan) { "spool scan metadata changed" }
            scans.add(scan)
        }
        if (scans.distinct().size != 1) return SavedReplaySpoolComparison("MISMATCH", "spool scope/count metadata differ", 0, scans)
        val inputs = mutableListOf<BufferedInputStream>()
        try {
            for (artifact in artifacts) inputs.add(BufferedInputStream(Files.newInputStream(Path.of(artifact.file), NOFOLLOW_LINKS)))
            val buffers = Array(3) { ByteArray(65_536) }
            while (true) {
                deadline.check()
                // readNBytes avoids treating different short-read sizes as byte differences.
                val sizes = inputs.mapIndexed { index, input -> input.readNBytes(buffers[index], 0, buffers[index].size) }
                if (sizes.all { it == 0 }) break
                if (sizes.distinct().size !=
                    1
                ) {
                    return SavedReplaySpoolComparison("MISMATCH", "different EOF at/after byte $compared", compared, scans)
                }
                for (offset in 0 until sizes.first()) {
                    if (buffers[0][offset] != buffers[1][offset] || buffers[0][offset] != buffers[2][offset]) {
                        return SavedReplaySpoolComparison(
                            "MISMATCH",
                            "full byte mismatch at ${compared + offset}",
                            compared + offset,
                            scans,
                        )
                    }
                }
                compared += sizes.first()
            }
        } finally {
            var closeFailure: Exception? = null
            for (input in inputs) {
                try {
                    input.close()
                } catch (problem: Exception) {
                    if (closeFailure == null) closeFailure = problem else closeFailure.addSuppressed(problem)
                }
            }
            closeFailure?.let { throw it }
        }
        require(
            artifacts.all {
                savedArchiveFingerprint(Path.of(it.file), deadline::check) == it.fingerprint
            },
        ) { "spool changed during comparison" }
        SavedReplaySpoolComparison("MATCH", null, compared, scans)
    } catch (failure: Exception) {
        SavedReplaySpoolComparison("UNVERIFIABLE", "${failure.javaClass.simpleName}: ${failure.message?.take(4096)}", compared, scans)
    }
}
