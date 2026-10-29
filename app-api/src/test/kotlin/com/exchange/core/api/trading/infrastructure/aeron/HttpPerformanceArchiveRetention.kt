package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.BufferedWriter
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/** Inspect only these non-secret environment values; Config.Env also contains the test DB password. */
internal val HTTP_PERFORMANCE_RECORDING_INSPECT_FORMAT: String =
    buildString {
        append(
            """{"Id":{{json .Id}},"State":{{json .State}},"Mounts":{{json .Mounts}},"composeProject":{{json (index .Config.Labels "com.docker.compose.project")}},"composeService":{{json (index .Config.Labels "com.docker.compose.service")}},"selectedEnvironment":[""",
        )
        val keys = listOf("FUNDING_RUN_ID=", "FUNDING_MEMBER_ID=", "FUNDING_RUN_ROOT=")
        val predicates = keys.joinToString(" ") { "(eq (printf \"%.${it.length}s\" .) \"$it\")" }
        append("{{range .Config.Env}}{{if or $predicates}}{{json .}},{{end}}{{end}}null]}")
    }

internal data class HttpPerformanceRecordingCandidate(
    val memberId: Int,
    val containerId: String?,
    val inspection: JsonNode?,
    val readyEvents: List<JsonNode>,
    val lookupFailure: String? = null,
)

internal data class HttpPerformanceRecordingSource(
    val runId: String,
    val memberId: Int,
    val containerId: String,
    val volume: String,
    val mountSource: String,
    val runRoot: String,
    val readyObserved: Boolean,
    val stoppedState: JsonNode,
) {
    fun directory(kind: String): String = "$runRoot/$kind"
}

internal data class HttpPerformanceArchiveRetentionResult(
    val runId: String,
    val manifestFile: String?,
    val inventory: Map<String, Any?>?,
    val volumeDeletionAllowed: Boolean,
    val ownedVolumes: List<String>,
    val ownedVolumeDiscoveryVerified: Boolean,
    val failures: List<String>,
    val members: List<Map<String, Any?>>,
) {
    val retainedVolumes: List<String> get() = if (volumeDeletionAllowed) emptyList() else ownedVolumes
    val status: String get() =
        if (failures.isNotEmpty()) {
            "FAILED"
        } else if (members.all { it["status"] == "NOT_CREATED" }) {
            "NOT_CREATED"
        } else {
            "VERIFIED"
        }
}

/** The original default cleanup is retained unless an opted-in copy lacks complete evidence. */
internal fun httpPerformanceComposeDownArguments(keepVolumes: Boolean): List<String> =
    listOf("down") + (if (keepVolumes) emptyList() else listOf("--volumes")) +
        listOf("--remove-orphans", "--timeout", "15")

internal fun httpPerformanceRecordingSource(
    runId: String,
    candidate: HttpPerformanceRecordingCandidate,
): HttpPerformanceRecordingSource {
    require(runId.matches(Regex("[a-z0-9-]{1,64}")))
    require(candidate.memberId in 0..2)
    check(candidate.lookupFailure == null) { checkNotNull(candidate.lookupFailure) }
    val id = checkNotNull(candidate.containerId) { "node${candidate.memberId} container missing" }
    val inspected = checkNotNull(candidate.inspection) { "node${candidate.memberId} inspection missing" }
    val service = "node${candidate.memberId}"
    val root = "/records/$runId"
    check(inspected.path("Id").asString() == id && id.matches(Regex("[a-f0-9]{64}"))) { "container identity mismatch" }
    check(inspected.path("composeProject").asString() == runId && inspected.path("composeService").asString() == service) {
        "Compose ownership mismatch"
    }
    val state = inspected.path("State")
    check(state.path("Running").isBoolean && !state.path("Running").asBoolean() && !state.path("Restarting").asBoolean()) {
        "node is not confirmed stopped"
    }
    val expectedEnvironment =
        listOf("FUNDING_RUN_ID=$runId", "FUNDING_MEMBER_ID=${candidate.memberId}", "FUNDING_RUN_ROOT=$root")
    val selectedEnvironment =
        inspected
            .path("selectedEnvironment")
            .toList()
            .filterNot { it.isNull }
            .map { it.asString() }
    check(selectedEnvironment.sorted() == expectedEnvironment.sorted()) { "Compose recording environment mismatch" }
    val mount = inspected.path("Mounts").toList().single { it.path("Destination").asString() == "/records" }
    val volume = "${runId}_$service-records"
    check(mount.path("Type").asString() == "volume" && mount.path("Name").asString() == volume) { "recording volume ownership mismatch" }
    val mountSource = mount.path("Source").asString()
    check(mountSource.startsWith("/") && '\u0000' !in mountSource) { "recording volume source missing" }
    check(candidate.readyEvents.size <= 1) { "multiple READY configurations" }
    candidate.readyEvents.singleOrNull()?.let { ready ->
        check(
            ready.path("kind").asString() == "READY" && ready.path("runId").asString() == runId &&
                ready.path("memberId").asInt(-1) == candidate.memberId,
        ) { "READY identity mismatch" }
        val settings = ready.path("settings")
        check(
            settings.path("runRoot").asString() == root &&
                settings.path("archiveDirectory").asString() == "$root/archive" &&
                settings.path("clusterDirectory").asString() == "$root/cluster",
        ) { "READY recording path mismatch" }
    }
    return HttpPerformanceRecordingSource(
        runId,
        candidate.memberId,
        id,
        volume,
        mountSource,
        root,
        candidate.readyEvents.isNotEmpty(),
        state,
    )
}

/**
 * A stopped-node evidence copy, not a recovery implementation. Docker cp never receives -L or '/.'.
 * All files are read to EOF with a fixed buffer; the inventory is JSONL, not an in-memory file list.
 * Any uncertainty keeps all volumes owned by this Compose run, including partial host copies.
 */
internal fun preserveHttpPerformanceNodeRecordings(
    reports: Path,
    runId: String,
    candidates: List<HttpPerformanceRecordingCandidate>,
    ownedVolumes: List<String>?,
    discoveryFailures: List<String> = emptyList(),
    copyDirectory: (HttpPerformanceRecordingSource, String, Path) -> Unit,
): HttpPerformanceArchiveRetentionResult {
    require(reports.isAbsolute && runId.matches(Regex("[a-z0-9-]{1,64}")))
    require(candidates.map { it.memberId }.sorted() == listOf(0, 1, 2))
    val mapper = jacksonObjectMapper()
    val errors = discoveryFailures.toMutableList()
    val members = mutableListOf<Map<String, Any?>>()
    val expectedVolumes = (0..2).map { "${runId}_node$it-records" }
    val knownVolumes = ownedVolumes.orEmpty().distinct().sorted()
    if (ownedVolumes == null) errors.add("owned volume discovery unconfirmed; keep project volumes")
    if (knownVolumes.any { it !in expectedVolumes } || (ownedVolumes != null && ownedVolumes.size != knownVolumes.size)) {
        errors.add("owned volume inventory differs from the three fixed Compose volumes")
    }
    val directory = reports.resolve("node-recordings")
    val inventoryFile = directory.resolve("files.jsonl")
    var inventory: Map<String, Any?>? = null
    var rows = 0L

    fun failure(
        stage: String,
        problem: Exception,
    ) {
        errors.add("$stage: ${problem.javaClass.simpleName}: ${problem.message?.take(4096)}")
    }
    try {
        check(Files.isDirectory(reports, NOFOLLOW_LINKS) && !Files.isSymbolicLink(reports)) { "reports directory is missing or a link" }
        Files.createDirectory(directory) // No reuse, replacement, merge, or deletion of earlier evidence.
        Files.newBufferedWriter(inventoryFile, CREATE_NEW, WRITE).use { writer ->
            for (candidate in candidates.sortedBy { it.memberId }) {
                val member = candidate.memberId
                val volume = "${runId}_node$member-records"
                if (candidate.lookupFailure == null && candidate.containerId == null && ownedVolumes != null && volume !in knownVolumes) {
                    members.add(mapOf("memberId" to member, "status" to "NOT_CREATED", "volume" to volume))
                    continue
                }
                try {
                    val source = httpPerformanceRecordingSource(runId, candidate)
                    check(ownedVolumes == null || source.volume in knownVolumes) { "node volume not present in owned volume inventory" }
                    if (!source.readyObserved) {
                        errors.add("node$member READY not observed; planned-path copies cannot authorize volume deletion")
                    }
                    val memberDirectory = Files.createDirectory(directory.resolve("node$member"))
                    val copies = mutableListOf<Map<String, Any?>>()
                    for (kind in listOf("archive", "cluster")) {
                        val target = memberDirectory.resolve(kind)
                        try {
                            check(!Files.exists(target, NOFOLLOW_LINKS)) { "copy destination already exists" }
                            copyDirectory(source, source.directory(kind), target)
                            val checked =
                                inventoryHttpPerformanceRecordingDirectory(reports, source, kind, target, writer) {
                                    rows = Math.addExact(rows, 1)
                                }
                            copies.add(
                                mapOf(
                                    "kind" to kind,
                                    "sourceDirectory" to source.directory(kind),
                                    "artifactDirectory" to
                                        reports.relativize(target).toString(),
                                    "files" to checked.first,
                                    "bytes" to checked.second,
                                    "verified" to true,
                                ),
                            )
                        } catch (problem: Exception) {
                            failure("node$member $kind", problem)
                            copies.add(
                                mapOf(
                                    "kind" to kind,
                                    "sourceDirectory" to source.directory(kind),
                                    "artifactDirectory" to
                                        reports.relativize(target).toString(),
                                    "verified" to false,
                                    "failure" to "${problem.javaClass.simpleName}: ${problem.message?.take(4096)}",
                                ),
                            )
                        }
                    }
                    members.add(
                        mapOf(
                            "memberId" to member,
                            "containerId" to source.containerId,
                            "volume" to source.volume,
                            "mountDestination" to "/records",
                            "mountSource" to source.mountSource,
                            "runRoot" to source.runRoot,
                            "readyObserved" to source.readyObserved,
                            "stoppedState" to source.stoppedState,
                            "copies" to copies,
                        ),
                    )
                } catch (problem: Exception) {
                    failure("node$member identity or startup evidence", problem)
                    members.add(
                        mapOf(
                            "memberId" to member,
                            "containerId" to candidate.containerId,
                            "volume" to volume,
                            "status" to "UNVERIFIED",
                            "failure" to "${problem.javaClass.simpleName}: ${problem.message?.take(4096)}",
                        ),
                    )
                }
            }
        }
        val fingerprint = httpPerformanceRecordingFileFingerprint(inventoryFile)
        inventory =
            mapOf(
                "artifactFile" to reports.relativize(inventoryFile).toString(),
                "records" to rows,
                "bytes" to fingerprint.first,
                "sha256" to fingerprint.second,
                "complete" to errors.isEmpty(),
            )
    } catch (problem: Exception) {
        failure("recording inventory", problem)
    }
    var manifestFile: String? = null
    try {
        val manifest = directory.resolve("manifest.json")
        Files.newBufferedWriter(manifest, CREATE_NEW, WRITE).use { writer ->
            writer.write(
                mapper.writeValueAsString(
                    mapOf(
                        "kind" to "HTTP_PERFORMANCE_NODE_RECORDINGS",
                        "schemaVersion" to 1,
                        "runId" to runId,
                        "copyPhase" to "after-node-stop-before-compose-down",
                        "inventory" to inventory,
                        "runEvidenceManifest" to "manifest.json",
                        "readyValidationRequiredForDeletion" to true,
                        "status" to
                            (
                                if (errors.isNotEmpty()) {
                                    "FAILED"
                                } else if (members.all { it["status"] == "NOT_CREATED" }) {
                                    "NOT_CREATED"
                                } else {
                                    "VERIFIED"
                                }
                            ),
                        "ownedVolumes" to knownVolumes,
                        "ownedVolumeDiscoveryVerified" to (ownedVolumes != null),
                        "volumeDeletionAllowed" to errors.isEmpty(),
                        "retainedVolumes" to (if (errors.isEmpty()) emptyList() else knownVolumes),
                        "failures" to errors.toList(),
                        "members" to members,
                    ),
                ),
            )
            writer.newLine()
        }
        manifestFile = reports.relativize(manifest).toString()
    } catch (problem: Exception) {
        failure("recording manifest", problem)
    }
    return HttpPerformanceArchiveRetentionResult(
        runId,
        manifestFile,
        inventory,
        errors.isEmpty(),
        knownVolumes,
        ownedVolumes != null,
        errors.toList(),
        members.toList(),
    )
}

private fun inventoryHttpPerformanceRecordingDirectory(
    reports: Path,
    source: HttpPerformanceRecordingSource,
    kind: String,
    directory: Path,
    writer: BufferedWriter,
    onRow: () -> Unit,
): Pair<Long, Long> {
    check(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory)) { "copy is not a real directory" }
    val mapper = jacksonObjectMapper()
    var files = 0L
    var bytes = 0L
    Files.walkFileTree(
        directory,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                path: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                check(attributes.isDirectory && !attributes.isSymbolicLink) { "recording directory is a link or special file: $path" }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                path: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                check(attributes.isRegularFile && !attributes.isSymbolicLink) { "recording file is a link or special file: $path" }
                val fingerprint = httpPerformanceRecordingFileFingerprint(path)
                files = Math.addExact(files, 1)
                bytes = Math.addExact(bytes, fingerprint.first)
                val relative = directory.relativize(path).toString().replace('\\', '/')
                writer.write(
                    mapper.writeValueAsString(
                        mapOf(
                            "kind" to "HTTP_PERFORMANCE_NODE_RECORDING_FILE",
                            "schemaVersion" to 1,
                            "runId" to source.runId,
                            "memberId" to source.memberId,
                            "containerId" to source.containerId,
                            "volume" to source.volume,
                            "mountSource" to source.mountSource,
                            "recordingDirectoryKind" to kind,
                            "sourceDirectory" to source.directory(kind),
                            "sourcePath" to "${source.directory(kind)}/$relative",
                            "artifactFile" to reports.relativize(path).toString(),
                            "bytes" to fingerprint.first,
                            "sha256" to fingerprint.second,
                        ),
                    ),
                )
                writer.newLine()
                onRow()
                return FileVisitResult.CONTINUE
            }
        },
    )
    check(files > 0 && bytes > 0) { "empty recording copy ($kind) is not preservation" }
    return files to bytes
}

private fun httpPerformanceRecordingFileFingerprint(file: Path): Pair<Long, String> {
    val before = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(before.isRegularFile && !before.isSymbolicLink) { "not a regular recording file: $file" }
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            bytes = Math.addExact(bytes, count.toLong())
            digest.update(buffer, 0, count)
        }
    }
    val after = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(
        after.isRegularFile && bytes == before.size() && bytes == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey(),
    ) { "recording file changed during verification: $file" }
    return bytes to HexFormat.of().formatHex(digest.digest())
}
