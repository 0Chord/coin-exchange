package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.Aeron
import io.aeron.ChannelUri
import io.aeron.archive.Archive
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.archive.client.AeronArchive
import io.aeron.archive.client.ArchiveException
import io.aeron.cluster.RecordingLog
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal enum class SavedRecordingChannelKind { LEADER_LOCAL_LOG_SHAPE, FOLLOWER_REMOTE_LOG_CM_SHAPE }

/** 호출자가 전달하는 모든 위치는 Cluster 로그 위치이며 ingress나 클라이언트 offer 위치가 아니다. */
internal data class SavedArchiveReplayPlan(
    val sourceReports: Path,
    val workDirectory: Path,
    val runId: String,
    val startPosition: Long,
    val limitPosition: Long,
    val leadershipTermId: Long,
    val logStreamId: Int,
    val leaderMemberId: Int,
    val bounds: SavedArchiveReplayBounds = SavedArchiveReplayBounds(),
) {
    init {
        require(
            sourceReports.isAbsolute && workDirectory.isAbsolute && sourceReports.normalize() == sourceReports &&
                workDirectory.normalize() == workDirectory,
        )
        require(!workDirectory.startsWith(sourceReports) && !sourceReports.startsWith(workDirectory)) {
            "work directory must be separate from originals"
        }
        require(runId.matches(Regex("[a-z0-9-]{1,64}")) && leaderMemberId in 0..2 && logStreamId > 0 && leadershipTermId >= 0)
        require(startPosition >= 0 && startPosition % 32 == 0L && limitPosition > startPosition && limitPosition % 32 == 0L)
        require(limitPosition - startPosition <= bounds.maxRangeBytes)
    }

    fun kind(member: Int) =
        if (member ==
            leaderMemberId
        ) {
            SavedRecordingChannelKind.LEADER_LOCAL_LOG_SHAPE
        } else {
            SavedRecordingChannelKind.FOLLOWER_REMOTE_LOG_CM_SHAPE
        }
}

internal class SavedArchiveDeadline(
    timeoutMs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val started = nanoTime()
    private val timeoutNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)

    init {
        require(timeoutMs in 1..60_000)
    }

    fun check() {
        val elapsed = nanoTime() - started
        check(elapsed >= 0) { "replay clock regression" }
        check(elapsed < timeoutNs) { "saved archive stage timeout" }
    }
}

internal data class SavedArchiveSourceFile(
    val memberId: Int,
    val kind: String,
    val artifactFile: String,
    val fingerprint: SavedArchiveFingerprint,
)

/** 명령이나 메시지 행이 아닌, 유한한 파일 메타데이터만 담는다. */
internal data class SavedArchiveSources(
    val files: List<SavedArchiveSourceFile>,
    val manifest: SavedArchiveFingerprint,
    val inventory: SavedArchiveFingerprint,
)

internal val savedArchiveJson: JsonMapper =
    JsonMapper
        .builder(
            JsonFactory
                .builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints
                        .builder()
                        .maxNestingDepth(64)
                        .maxNameLength(256)
                        .maxStringLength(65_536)
                        .maxNumberLength(128)
                        .build(),
                ).build(),
        ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

internal fun requireSavedArchiveNoLinks(path: Path) {
    require(path.isAbsolute && path.normalize() == path)
    var current = path.root
    for (part in path) {
        current = current.resolve(part)
        require(Files.exists(current, NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) { "missing or symbolic path: $current" }
    }
}

private fun savedArchiveOwnedPath(
    root: Path,
    relative: String,
): Path {
    val suffix = Path.of(relative)
    require(
        relative.isNotBlank() && !suffix.isAbsolute && suffix.normalize() == suffix &&
            suffix.none {
                it.toString() == ".."
            },
    ) { "unowned artifact path" }
    val resolved = root.resolve(suffix).normalize()
    require(resolved.startsWith(root) && resolved != root)
    requireSavedArchiveNoLinks(resolved)
    return resolved
}

private fun savedArchiveReadJson(
    file: Path,
    maximum: Long = 1_048_576,
): JsonNode {
    requireSavedArchiveNoLinks(file)
    require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) in 1..maximum) { "metadata size/type" }
    return Files.newInputStream(file, NOFOLLOW_LINKS).use { savedArchiveJson.readTree(it) }
}

private fun savedArchiveLong(
    node: JsonNode,
    field: String,
): Long {
    require(node.path(field).isIntegralNumber && node.path(field).canConvertToLong()) { "missing integer $field" }
    return node.path(field).asLong()
}

/** Archive 프로세스를 시작하기 전에 보존 계약과 정확한 파일 구성을 검증한다. */
internal fun verifySavedArchiveSources(
    plan: SavedArchiveReplayPlan,
    deadline: SavedArchiveDeadline,
): SavedArchiveSources {
    requireSavedArchiveNoLinks(plan.sourceReports)
    val manifestFile = savedArchiveOwnedPath(plan.sourceReports, "node-recordings/manifest.json")
    val manifest = savedArchiveReadJson(manifestFile)
    require(manifest.path("kind").asString() == "HTTP_PERFORMANCE_NODE_RECORDINGS" && savedArchiveLong(manifest, "schemaVersion") == 1L)
    require(manifest.path("runId").asString() == plan.runId && manifest.path("status").asString() == "VERIFIED")
    require(manifest.path("volumeDeletionAllowed").asBoolean() && manifest.path("ownedVolumeDiscoveryVerified").asBoolean())
    require(manifest.path("failures").isArray && manifest.path("failures").isEmpty) { "retention failures" }
    require(
        manifest
            .path("ownedVolumes")
            .toList()
            .map { it.asString() }
            .sorted() == (0..2).map { "${plan.runId}_node$it-records" },
    )
    val members = manifest.path("members").toList()
    require(members.size == 3 && members.map { savedArchiveLong(it, "memberId") }.sorted() == listOf(0L, 1L, 2L))
    for (member in members) {
        val number = savedArchiveLong(member, "memberId").toInt()
        require(member.path("readyObserved").asBoolean() && member.path("runRoot").asString() == "/records/${plan.runId}")
        require(member.path("volume").asString() == "${plan.runId}_node$number-records")
        require(
            member.path("containerId").asString().matches(Regex("[a-f0-9]{64}")) && member.path("mountSource").asString().startsWith("/"),
        )
        val state = member.path("stoppedState")
        require(
            state.path("Running").isBoolean && !state.path("Running").asBoolean() && state.path("Restarting").isBoolean &&
                !state.path("Restarting").asBoolean(),
        )
        require(
            state.path("OOMKilled").isBoolean && !state.path("OOMKilled").asBoolean() &&
                savedArchiveLong(state, "ExitCode") in setOf(0L, 143L),
        ) { "normal closed source required; OOM/tail recovery is outside scope" }
        val copies = member.path("copies").toList()
        require(copies.size == 2 && copies.map { it.path("kind").asString() }.toSet() == setOf("archive", "cluster"))
        for (copy in copies) {
            val kind = copy.path("kind").asString()
            require(copy.path("verified").asBoolean() && savedArchiveLong(copy, "files") > 0 && savedArchiveLong(copy, "bytes") > 0)
            require(copy.path("sourceDirectory").asString() == "/records/${plan.runId}/$kind")
            require(copy.path("artifactDirectory").asString() == "node-recordings/node$number/$kind")
        }
    }
    val inventory = manifest.path("inventory")
    require(inventory.path("complete").asBoolean() && inventory.path("artifactFile").asString() == "node-recordings/files.jsonl")
    val inventoryFile = savedArchiveOwnedPath(plan.sourceReports, inventory.path("artifactFile").asString())
    require(Files.size(inventoryFile) in 1L..4_194_304L) { "finite inventory metadata limit" }
    val inventoryFingerprint = savedArchiveFingerprint(inventoryFile, deadline::check)
    require(
        inventoryFingerprint.bytes == savedArchiveLong(inventory, "bytes") &&
            inventoryFingerprint.sha256 == inventory.path("sha256").asString(),
    )
    val files = mutableListOf<SavedArchiveSourceFile>()
    val seen = mutableSetOf<String>()
    Files.newInputStream(inventoryFile, NOFOLLOW_LINKS).buffered().use { input ->
        val line = ByteArrayOutputStream()
        while (true) {
            deadline.check()
            val value = input.read()
            if (value < 0) {
                require(line.size() == 0) { "inventory missing final LF" }
                break
            }
            if (value != 10) {
                require(line.size() < 65_536) { "inventory line limit" }
                line.write(value)
                continue
            }
            require(line.size() > 0 && files.size < plan.bounds.maxFiles) { "inventory empty row/file limit" }
            val text =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                        CodingErrorAction.REPORT,
                    ).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(line.toByteArray()))
                    .toString()
            line.reset()
            val row = savedArchiveJson.readTree(text)
            val memberValue = savedArchiveLong(row, "memberId")
            require(memberValue in 0..2)
            val memberId = memberValue.toInt()
            val kind = row.path("recordingDirectoryKind").asString()
            require(row.path("kind").asString() == "HTTP_PERFORMANCE_NODE_RECORDING_FILE" && savedArchiveLong(row, "schemaVersion") == 1L)
            require(row.path("runId").asString() == plan.runId && memberId in 0..2 && kind in setOf("archive", "cluster"))
            val member = members.single { savedArchiveLong(it, "memberId").toInt() == memberId }
            require(
                row.path("containerId").asString() == member.path("containerId").asString() &&
                    row.path("volume").asString() == member.path("volume").asString(),
            )
            require(row.path("mountSource").asString() == member.path("mountSource").asString())
            val relative = row.path("artifactFile").asString()
            val prefix = "node-recordings/node$memberId/$kind/"
            require(relative.startsWith(prefix) && seen.add(relative)) { "duplicate/wrong-member file" }
            require(
                row.path("sourceDirectory").asString() == "/records/${plan.runId}/$kind" &&
                    row.path("sourcePath").asString() == "/records/${plan.runId}/$kind/${relative.removePrefix(prefix)}",
            )
            val expected = SavedArchiveFingerprint(savedArchiveLong(row, "bytes"), row.path("sha256").asString())
            require(expected.bytes >= 0 && expected.sha256.matches(Regex("[0-9a-f]{64}")))
            require(savedArchiveFingerprint(savedArchiveOwnedPath(plan.sourceReports, relative), deadline::check) == expected) {
                "source bytes/SHA mismatch: $relative"
            }
            files.add(SavedArchiveSourceFile(memberId, kind, relative, expected))
        }
    }
    require(files.size.toLong() == savedArchiveLong(inventory, "records"))
    val actual = mutableSetOf<String>()
    var visited = 0
    for (member in 0..2) {
        for (kind in listOf("archive", "cluster")) {
            val directory = savedArchiveOwnedPath(plan.sourceReports, "node-recordings/node$member/$kind")
            Files.walk(directory).use { paths ->
                paths.forEach { path ->
                    deadline.check()
                    require(++visited <= plan.bounds.maxFiles * 4 + 64) { "source tree limit" }
                    require(!Files.isSymbolicLink(path)) { "source symlink" }
                    if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                        actual.add(plan.sourceReports.relativize(path).toString())
                    } else {
                        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "source special file" }
                    }
                }
            }
            val selected = files.filter { it.memberId == member && it.kind == kind }
            val copy =
                members.single { savedArchiveLong(it, "memberId").toInt() == member }.path("copies").toList().single {
                    it.path("kind").asString() ==
                        kind
                }
            require(
                selected.size.toLong() == savedArchiveLong(copy, "files") &&
                    selected.fold(0L) { total, file -> Math.addExact(total, file.fingerprint.bytes) } == savedArchiveLong(copy, "bytes"),
            )
        }
    }
    require(actual == seen) { "source inventory/file-set mismatch" }
    return SavedArchiveSources(files.toList(), savedArchiveFingerprint(manifestFile, deadline::check), inventoryFingerprint)
}

internal fun copySavedArchiveSources(
    plan: SavedArchiveReplayPlan,
    sources: SavedArchiveSources,
    deadline: SavedArchiveDeadline,
) {
    for (source in sources.files) {
        val sourcePath = savedArchiveOwnedPath(plan.sourceReports, source.artifactFile)
        val target = plan.workDirectory.resolve(source.artifactFile.removePrefix("node-recordings/"))
        Files.createDirectories(target.parent)
        requireSavedArchiveNoLinks(target.parent)
        Files.newInputStream(sourcePath, NOFOLLOW_LINKS).use { input ->
            Files.newOutputStream(target, CREATE_NEW, WRITE).use { output ->
                val buffer = ByteArray(65_536)
                while (true) {
                    deadline.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        require(savedArchiveFingerprint(target, deadline::check) == source.fingerprint) { "working copy bytes/SHA mismatch" }
    }
}

internal data class SavedRecordingDescriptor(
    val recordingId: Long,
    val sessionId: Int,
    val streamId: Int,
    val start: Long,
    val stop: Long,
    val termBufferLength: Int,
    val mtuLength: Int,
    val originalChannel: String,
    val strippedChannel: String,
    val sourceIdentity: String,
    val initialTermId: Int = 0,
    val segmentFileLength: Int = 65_536,
)

internal fun validateSavedRecordingDescriptor(
    descriptor: SavedRecordingDescriptor,
    plan: SavedArchiveReplayPlan,
    member: Int,
) {
    require(
        descriptor.streamId == plan.logStreamId && descriptor.start == plan.startPosition && descriptor.stop >= plan.limitPosition &&
            descriptor.stop % 32 == 0L,
    )
    require(
        descriptor.termBufferLength > 0 && Integer.bitCount(descriptor.termBufferLength) == 1 && descriptor.mtuLength > 0 &&
            descriptor.sourceIdentity.isNotBlank(),
    )
    require(descriptor.segmentFileLength >= descriptor.termBufferLength && Integer.bitCount(descriptor.segmentFileLength) == 1)
    val original = ChannelUri.parse(descriptor.originalChannel)
    val stripped = ChannelUri.parse(descriptor.strippedChannel)
    val alias = if (plan.kind(member) == SavedRecordingChannelKind.LEADER_LOCAL_LOG_SHAPE) "log" else "log-cm"
    require(
        original.prefix().isNullOrEmpty() && stripped.prefix().isNullOrEmpty() && original.media() == "udp" && stripped.media() == "udp",
    )
    require(original.get("alias") == alias && stripped.get("alias") == alias) { "leader/follower/other channel classification mismatch" }
    for (channel in listOf(original, stripped)) {
        require(channel.get("session-id") == descriptor.sessionId.toString() && !channel.get("tags").isNullOrBlank())
    }
    require(original.get("tags") == stripped.get("tags"))
    if (plan.kind(member) == SavedRecordingChannelKind.FOLLOWER_REMOTE_LOG_CM_SHAPE) {
        for (channel in listOf(original, stripped)) {
            require(channel.get("control-mode") == "manual" && channel.get("group") == "true" && channel.get("rejoin") == "false")
        }
    }
}

internal data class SavedNodeReplayResult(
    val memberId: Int,
    val channelKind: SavedRecordingChannelKind,
    val stagingArchiveId: Long,
    val descriptor: SavedRecordingDescriptor?,
    val spool: SavedReplaySpoolArtifact?,
    val failure: String?,
    val cleanupFailures: List<String>,
)

private fun savedArchiveFailure(failure: Exception) = "${failure.javaClass.simpleName}: ${failure.message?.take(4096)}"

private fun readSavedArchiveMember(
    plan: SavedArchiveReplayPlan,
    sources: SavedArchiveSources,
    member: Int,
): SavedNodeReplayResult {
    val deadline = SavedArchiveDeadline(plan.bounds.timeoutMs)
    val directory = plan.workDirectory.resolve("node$member")
    val archiveDirectory = directory.resolve("archive")
    val catalog = archiveDirectory.resolve("archive.catalog") // 고정 버전 1.53.3의 디스크 파일 이름이다.
    var driver: MediaDriver? = null
    var server: Archive? = null
    var client: AeronArchive? = null
    var spool: SavedReplaySpoolWriter? = null
    var artifact: SavedReplaySpoolArtifact? = null
    var descriptor: SavedRecordingDescriptor? = null
    var failure: String? = null
    val cleanup = mutableListOf<String>()
    val transportFailure = AtomicReference<Throwable?>()
    val stagingId = 9000L + member // 기존 실행 중 Archive의 식별자를 복원한 값이 아니다.
    try {
        deadline.check()
        val logFile = directory.resolve("cluster").resolve(RecordingLog.RECORDING_LOG_FILE_NAME)
        require(Files.size(logFile) in 1L..1_048_576L) { "finite recording.log metadata limit" }
        val recordingId =
            RecordingLog(directory.resolve("cluster").toFile(), false).use { log ->
                val terms = log.entries().filter { it.type == RecordingLog.ENTRY_TYPE_TERM }
                require(
                    terms.size == 1 && terms.single().isValid && terms.single().leadershipTermId == plan.leadershipTermId &&
                        terms.single().termBaseLogPosition <= plan.startPosition,
                ) { "exactly one expected TERM required" }
                terms.single().recordingId
            }
        require(Files.size(catalog) in 1L..67_108_864L) { "finite catalog size limit" }
        require(!Files.exists(archiveDirectory.resolve("archive-mark.lnk"), NOFOLLOW_LINKS)) { "external original mark link unsupported" }
        val originalCatalog = savedArchiveFingerprint(catalog, deadline::check)
        val driverDirectory = directory.resolve("replay-driver")
        driver =
            MediaDriver.launch(
                MediaDriver
                    .Context()
                    .aeronDirectoryName(driverDirectory.toString())
                    .dirDeleteOnStart(false)
                    .dirDeleteOnShutdown(false)
                    .threadingMode(ThreadingMode.SHARED)
                    .errorHandler { transportFailure.compareAndSet(null, it) },
            )
        server =
            Archive.launch(
                Archive
                    .Context()
                    .aeronDirectoryName(driverDirectory.toString())
                    .archiveId(stagingId)
                    .archiveDir(archiveDirectory.toFile())
                    .markFileDir(directory.resolve("replay-mark").toFile())
                    .deleteArchiveOnStart(false)
                    .catalogCapacity(
                        Files.size(catalog),
                    ).controlChannelEnabled(false)
                    .recordingEventsEnabled(false)
                    .localControlChannel("aeron:ipc")
                    .replicationChannel("aeron:ipc")
                    .archiveClientContext(AeronArchive.Context().controlRequestChannel("aeron:ipc").controlResponseChannel("aeron:ipc"))
                    .connectTimeoutNs(TimeUnit.MILLISECONDS.toNanos(plan.bounds.timeoutMs))
                    .threadingMode(ArchiveThreadingMode.SHARED)
                    .fileSyncLevel(2)
                    .catalogFileSyncLevel(2)
                    .errorHandler { transportFailure.compareAndSet(null, it) },
            )
        require(savedArchiveFingerprint(catalog, deadline::check) == originalCatalog) {
            "WORKCOPY_CATALOG_CHANGED_ON_OPEN; tail recovery is outside strict scope"
        }
        val connected =
            AeronArchive.connect(
                AeronArchive
                    .Context()
                    .aeronDirectoryName(driverDirectory.toString())
                    .controlRequestChannel("aeron:ipc")
                    .controlResponseChannel("aeron:ipc")
                    .messageTimeoutNs(
                        TimeUnit.MILLISECONDS.toNanos(plan.bounds.timeoutMs),
                    ).errorHandler { transportFailure.compareAndSet(null, it) },
            )
        client = connected
        val descriptors = mutableListOf<SavedRecordingDescriptor>()
        var from = 0L
        while (true) {
            deadline.check()
            val before = descriptors.size
            val count =
                connected.listRecordings(
                    from,
                    32,
                ) {
                    _,
                    _,
                    id,
                    _,
                    _,
                    start,
                    stop,
                    initialTermId,
                    segmentLength,
                    termLength,
                    mtu,
                    sessionId,
                    streamId,
                    stripped,
                    original,
                    source,
                    ->
                    require(descriptors.size < plan.bounds.maxDescriptors) { "descriptor limit" }
                    descriptors.add(
                        SavedRecordingDescriptor(
                            id,
                            sessionId,
                            streamId,
                            start,
                            stop,
                            termLength,
                            mtu,
                            original,
                            stripped,
                            source,
                            initialTermId,
                            segmentLength,
                        ),
                    )
                }
            require(count == descriptors.size - before && descriptors.drop(before).all { it.recordingId >= from })
            require(
                descriptors.zipWithNext().all { (left, right) ->
                    left.recordingId < right.recordingId
                },
            ) { "duplicate/reversed descriptor" }
            if (count < 32) break
            from = Math.addExact(descriptors.last().recordingId, 1)
        }
        Files.newOutputStream(directory.resolve("descriptors.json"), CREATE_NEW, WRITE).use {
            savedArchiveJson.writerWithDefaultPrettyPrinter().writeValue(it, descriptors)
        }
        val selected = descriptors.single { it.recordingId == recordingId }
        descriptor = selected
        validateSavedRecordingDescriptor(selected, plan, member)
        require(
            connected.getStartPosition(recordingId) == selected.start && connected.getStopPosition(recordingId) == selected.stop &&
                connected.getMaxRecordedPosition(recordingId) == selected.stop &&
                connected.getRecordingPosition(recordingId) == AeronArchive.NULL_POSITION,
        )
        val writer =
            SavedReplaySpoolWriter(
                directory.resolve("messages.sarp"),
                plan.startPosition,
                plan.limitPosition,
                plan.leadershipTermId,
                plan.bounds,
            )
        spool = writer
        val collector =
            RecordedReplayCollector(
                plan.startPosition,
                plan.limitPosition,
                retainFrames = false,
                maximumMessageBytes = plan.bounds.maxMessageBytes,
                onValidatedMessage = writer::append,
            )
        val aeron = connected.context().aeron()
        val replayChannel = "aeron:ipc?alias=saved-replay-$member-${aeron.nextCorrelationId()}"
        var replayId = Aeron.NULL_VALUE.toLong()
        aeron.addSubscription(replayChannel, 19999).use { subscription ->
            try {
                replayId =
                    connected.startReplay(recordingId, plan.startPosition, plan.limitPosition - plan.startPosition, replayChannel, 19999)
                var image = subscription.imageBySessionId(replayId.toInt())
                while (image == null) {
                    deadline.check()
                    transportFailure.get()?.let { throw IllegalStateException("replay transport", it) }
                    Thread.sleep(1)
                    image = subscription.imageBySessionId(replayId.toInt())
                }
                require(
                    image.joinPosition() == plan.startPosition && image.sessionId() == replayId.toInt() &&
                        image.termBufferLength() == selected.termBufferLength,
                )
                while (true) {
                    deadline.check()
                    transportFailure.get()?.let { throw IllegalStateException("replay transport", it) }
                    val read = image.poll(collector::onFragment, 1)
                    collector.failure?.let { throw IllegalStateException(it) }
                    if (image.position() >= plan.limitPosition || image.isClosed || image.isEndOfStream) {
                        require(
                            collector.completionFailure(image.position()) == null,
                        ) { collector.completionFailure(image.position()).toString() }
                        artifact = writer.finish()
                        break
                    }
                    if (read == 0) Thread.sleep(1)
                }
            } finally {
                if (replayId != Aeron.NULL_VALUE.toLong()) {
                    try {
                        connected.stopReplay(replayId)
                    } catch (problem: ArchiveException) {
                        if (problem.errorCode() != ArchiveException.UNKNOWN_REPLAY) throw problem
                    }
                }
            }
        }
    } catch (problem: Exception) {
        failure = savedArchiveFailure(problem)
    } finally {
        val resources: List<Pair<String, AutoCloseable?>> =
            listOf(
                "spool" to spool,
                "client" to client,
                "archive" to server,
                "driver" to driver,
            )
        for ((label, resource) in resources) {
            try {
                resource?.close()
            } catch (problem: Exception) {
                cleanup.add("$label: ${savedArchiveFailure(problem)}")
            }
        }
        val invariantDeadline = SavedArchiveDeadline(plan.bounds.timeoutMs)
        try {
            if (spool !=
                null
            ) {
                artifact = spool.artifact(invariantDeadline::check)
            }
        } catch (problem: Exception) {
            cleanup.add("partial spool fingerprint: ${savedArchiveFailure(problem)}")
        }
        for (source in sources.files.filter { it.memberId == member }) {
            try {
                val copy = plan.workDirectory.resolve(source.artifactFile.removePrefix("node-recordings/"))
                require(savedArchiveFingerprint(copy, invariantDeadline::check) == source.fingerprint) {
                    "working source changed: ${source.artifactFile}"
                }
            } catch (problem: Exception) {
                cleanup.add("working-copy invariant: ${savedArchiveFailure(problem)}")
            }
        }
        transportFailure.get()?.let { cleanup.add("transport handler: ${it.javaClass.simpleName}: ${it.message?.take(4096)}") }
    }
    return SavedNodeReplayResult(member, plan.kind(member), stagingId, descriptor, artifact, failure, cleanup.toList())
}

internal data class SavedArchiveReplayResult(
    val runId: String,
    val startPosition: Long,
    val limitPosition: Long,
    val nodes: List<SavedNodeReplayResult>,
    val comparison: SavedReplaySpoolComparison?,
    val failures: List<String>,
    val reportWritten: Boolean,
    val originalInvariantVerified: Boolean,
    val livePublicationEvidence: String = "NOT_REESTABLISHED_FROM_STOPPED_FILES",
    val evidenceScope: String = "offline closed single-term logical message prefix only; not commit, finance, throughput, or recovery",
) {
    val verified: Boolean get() =
        reportWritten && originalInvariantVerified && failures.isEmpty() && nodes.size == 3 &&
            nodes.all { it.failure == null && it.cleanupFailures.isEmpty() && it.spool?.complete == true } && comparison?.status == "MATCH"
}

/** 엔진이나 서비스를 생성하지 않는다. Archive.launch에 도달하기 전에 원본을 검증하고 복사한다. */
internal fun readSavedArchiveThreeCopies(plan: SavedArchiveReplayPlan): SavedArchiveReplayResult {
    requireSavedArchiveNoLinks(checkNotNull(plan.workDirectory.parent))
    Files.createDirectory(plan.workDirectory)
    val errors = mutableListOf<String>()
    val nodes = mutableListOf<SavedNodeReplayResult>()
    var sources: SavedArchiveSources? = null
    var comparison: SavedReplaySpoolComparison? = null
    var unchanged = false
    try {
        val verified = verifySavedArchiveSources(plan, SavedArchiveDeadline(plan.bounds.timeoutMs))
        sources = verified
        copySavedArchiveSources(plan, verified, SavedArchiveDeadline(plan.bounds.timeoutMs))
        for (member in 0..2) nodes.add(readSavedArchiveMember(plan, verified, member))
        if (nodes.all { it.failure == null && it.cleanupFailures.isEmpty() && it.spool?.complete == true }) {
            comparison = compareSavedReplaySpools(nodes.map { checkNotNull(it.spool) }, plan.bounds)
            if (comparison.status != "MATCH") errors.add("spool comparison: ${comparison.status}: ${comparison.reason}")
        } else {
            errors.add("one or more member replays unavailable; partial spools are not full-prefix evidence")
        }
    } catch (problem: Exception) {
        errors.add(savedArchiveFailure(problem))
    } finally {
        sources?.let { expected ->
            try {
                require(
                    verifySavedArchiveSources(plan, SavedArchiveDeadline(plan.bounds.timeoutMs)) == expected,
                ) { "original source evidence changed" }
                unchanged = true
            } catch (problem: Exception) {
                errors.add("original invariant: ${savedArchiveFailure(problem)}")
            }
        }
    }
    var result =
        SavedArchiveReplayResult(
            plan.runId,
            plan.startPosition,
            plan.limitPosition,
            nodes.toList(),
            comparison,
            errors.toList(),
            false,
            unchanged,
        )
    try {
        result = result.copy(reportWritten = true)
        Files.newOutputStream(plan.workDirectory.resolve("replay-report.json"), CREATE_NEW, WRITE).use {
            savedArchiveJson.writerWithDefaultPrettyPrinter().writeValue(it, result)
        }
    } catch (problem: Exception) {
        result = result.copy(reportWritten = false, failures = result.failures + "report: ${savedArchiveFailure(problem)}")
        try {
            Files.writeString(
                plan.workDirectory.resolve("replay-report-failure.txt"),
                result.failures.joinToString("\n"),
                CREATE_NEW,
                WRITE,
            )
        } catch (
            secondary: Exception,
        ) {
            result =
                result.copy(failures = result.failures + "fallback report: ${savedArchiveFailure(secondary)}")
        }
    }
    return result
}
