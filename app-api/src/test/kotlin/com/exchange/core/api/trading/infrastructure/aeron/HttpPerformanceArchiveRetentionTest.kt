package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tiny local fixtures only. These tests neither start Docker nor assert an Archive replay result. */
class HttpPerformanceArchiveRetentionTest {
    @TempDir
    lateinit var directory: Path

    private val mapper = jacksonObjectMapper()
    private val runId = "archive-test"
    private val volumes get() = (0..2).map { "${runId}_node$it-records" }

    @Test
    fun `stopped three-node originals retain every byte hash and actual identity before volume cleanup`() {
        val calls = mutableListOf<String>()
        val result =
            preserveHttpPerformanceNodeRecordings(directory, runId, candidates(), volumes) { source, path, target ->
                calls.add(path)
                writeCopy(source, path, target)
            }
        assertTrue(result.volumeDeletionAllowed)
        assertEquals(emptyList(), result.failures)
        assertEquals(emptyList(), result.retainedVolumes)
        assertEquals(6, calls.size)
        val manifest = mapper.readTree(Files.readString(directory.resolve(checkNotNull(result.manifestFile))))
        assertEquals(runId, manifest.path("runId").asString())
        assertTrue(manifest.path("volumeDeletionAllowed").asBoolean())
        val inventory = checkNotNull(result.inventory)
        val raw = directory.resolve(inventory.getValue("artifactFile").toString())
        assertEquals(Files.size(raw), inventory["bytes"])
        assertEquals(hash(Files.readAllBytes(raw)), inventory["sha256"])
        val rows = Files.readAllLines(raw).map { mapper.readTree(it) }
        assertEquals(6, rows.size)
        assertEquals(6L, inventory["records"])
        for (row in rows) {
            val copied = directory.resolve(row.path("artifactFile").asString())
            assertEquals(Files.size(copied), row.path("bytes").asLong())
            assertEquals(hash(Files.readAllBytes(copied)), row.path("sha256").asString())
            assertEquals(runId, row.path("runId").asString())
            assertEquals(id(row.path("memberId").asInt()), row.path("containerId").asString())
            assertTrue(row.path("sourcePath").asString().startsWith("/records/$runId/"))
            assertTrue(row.path("mountSource").asString().startsWith("/var/lib/docker/volumes/"))
        }
        assertTrue("--volumes" in httpPerformanceComposeDownArguments(keepVolumes = false))
    }

    @Test
    fun `OOM stopped node still copies original files without changing failed exit evidence`() {
        val result = preserveHttpPerformanceNodeRecordings(directory, runId, candidates(oom = true), volumes, copyDirectory = ::writeCopy)
        assertTrue(result.volumeDeletionAllowed)
        val manifest = mapper.readTree(Files.readString(directory.resolve(checkNotNull(result.manifestFile))))
        val stopped = manifest.path("members").path(0).path("stoppedState")
        assertEquals(137, stopped.path("ExitCode").asInt())
        assertTrue(stopped.path("OOMKilled").asBoolean())
        // Copy validity does not turn the existing Cluster.close normal-exit checks into success.
        assertFalse(stopped.path("Running").asBoolean())
    }

    @Test
    fun `one failed copy preserves partial data continues remaining copies and removes no owned volumes`() {
        val calls = mutableListOf<String>()
        val result =
            preserveHttpPerformanceNodeRecordings(directory, runId, candidates(), volumes) { source, path, target ->
                calls.add(path)
                writeCopy(source, path, target)
                if (source.memberId == 0 && path.endsWith("/archive")) error("injected docker cp failure")
            }
        assertEquals(6, calls.size)
        assertTrue(Files.exists(directory.resolve("node-recordings/node0/archive/record.bin")))
        assertFalse(result.volumeDeletionAllowed)
        assertEquals(volumes, result.retainedVolumes)
        assertTrue(result.failures.any { "injected docker cp failure" in it })
        assertFalse("--volumes" in httpPerformanceComposeDownArguments(keepVolumes = !result.volumeDeletionAllowed))
        assertEquals(listOf("down", "--remove-orphans", "--timeout", "15"), httpPerformanceComposeDownArguments(true))
    }

    @Test
    fun `startup before node and volume creation is explicit NOT_CREATED rather than empty successful copy`() {
        var copied = false
        val missing = (0..2).map { HttpPerformanceRecordingCandidate(it, null, null, emptyList()) }
        val result = preserveHttpPerformanceNodeRecordings(directory, runId, missing, emptyList()) { _, _, _ -> copied = true }
        assertFalse(copied)
        assertTrue(result.volumeDeletionAllowed)
        assertEquals(listOf("NOT_CREATED", "NOT_CREATED", "NOT_CREATED"), result.members.map { it["status"] })
        assertEquals(0L, checkNotNull(result.inventory)["records"])
    }

    @Test
    fun `startup without container but with created volume is unverified and leaves owned volume intact`() {
        var copied = false
        val missing = (0..2).map { HttpPerformanceRecordingCandidate(it, null, null, emptyList()) }
        val result = preserveHttpPerformanceNodeRecordings(directory, runId, missing, listOf(volumes.first())) { _, _, _ -> copied = true }
        assertFalse(copied)
        assertFalse(result.volumeDeletionAllowed)
        assertEquals(listOf(volumes.first()), result.retainedVolumes)
        assertTrue(result.failures.any { "container missing" in it })
    }

    @Test
    fun `startup before READY still copies planned-path evidence but cannot delete originals`() {
        val missingReady = candidates().map { it.copy(readyEvents = emptyList()) }
        val result = preserveHttpPerformanceNodeRecordings(directory, runId, missingReady, volumes, copyDirectory = ::writeCopy)
        assertFalse(result.volumeDeletionAllowed)
        assertEquals(volumes, result.retainedVolumes)
        assertEquals(6L, checkNotNull(result.inventory)["records"])
        assertEquals(3, result.failures.count { "READY not observed" in it })
    }

    @Test
    fun `unknown volume discovery still salvages verified node paths but cannot allow deletion`() {
        var copies = 0
        val result =
            preserveHttpPerformanceNodeRecordings(directory, runId, candidates(), null) { source, path, target ->
                copies++
                writeCopy(source, path, target)
            }
        assertEquals(6, copies)
        assertFalse(result.ownedVolumeDiscoveryVerified)
        assertFalse(result.volumeDeletionAllowed)
        assertTrue(result.failures.any { "discovery unconfirmed" in it })
    }

    @Test
    fun `READY path identity Compose ownership duplicate env and running node mismatches all fail closed`() {
        val base = candidates().first()
        val mutations =
            listOf(
                base.copy(readyEvents = listOf(ready(0, archivePath = "/other-run/archive"))),
                base.copy(inspection = inspection(0, project = "other-run")),
                base.copy(inspection = inspection(0, running = true)),
                base.copy(inspection = inspection(0, volume = "somebody-else-records")),
                base.copy(inspection = inspection(0, environment = environment(0) + environment(0).first())),
                base.copy(containerId = id(1)),
            )
        for (candidate in mutations) assertFailsWith<IllegalStateException> { httpPerformanceRecordingSource(runId, candidate) }
        assertFalse(httpPerformanceRecordingSource(runId, base.copy(readyEvents = emptyList())).readyObserved)
    }

    @Test
    fun `empty copied directories and symbolic links cannot be verified even with successful copy return`() {
        for (link in listOf(false, true)) {
            val reports = Files.createDirectory(directory.resolve(if (link) "link" else "empty"))
            val outside = Files.writeString(directory.resolve("outside-${if (link) 1 else 0}"), "outside")
            val result =
                preserveHttpPerformanceNodeRecordings(reports, runId, candidates(), volumes) { _, _, target ->
                    Files.createDirectory(target)
                    if (link) Files.createSymbolicLink(target.resolve("record.bin"), outside)
                }
            assertFalse(result.volumeDeletionAllowed)
            assertEquals(volumes, result.retainedVolumes)
            assertTrue(result.failures.any { if (link) "link or special file" in it else "empty recording copy" in it })
            assertEquals("outside", Files.readString(outside))
        }
    }

    @Test
    fun `existing host evidence is neither merged nor overwritten and keeps volumes`() {
        val root = Files.createDirectory(directory.resolve("node-recordings"))
        val sentinel = Files.writeString(root.resolve("manifest.json"), "previous evidence")
        var copied = false
        val result = preserveHttpPerformanceNodeRecordings(directory, runId, candidates(), volumes) { _, _, _ -> copied = true }
        assertFalse(copied)
        assertFalse(result.volumeDeletionAllowed)
        assertEquals("previous evidence", Files.readString(sentinel))
        assertTrue(result.failures.any { it.startsWith("recording manifest:") })
    }

    @Test
    fun `manifest write failure after valid copies still prevents volume deletion`() {
        val result =
            preserveHttpPerformanceNodeRecordings(directory, runId, candidates(), volumes) { source, path, target ->
                writeCopy(source, path, target)
                if (source.memberId == 2 && path.endsWith("/cluster")) {
                    Files.createDirectory(directory.resolve("node-recordings/manifest.json"))
                }
            }
        assertFalse(result.volumeDeletionAllowed)
        assertEquals(null, result.manifestFile)
        assertEquals(6L, checkNotNull(result.inventory)["records"])
        assertTrue(result.failures.any { it.startsWith("recording manifest:") })
    }

    @Test
    fun `credential scan does not follow rejected evidence symlink into another host file`() {
        val reports = Files.createDirectory(directory.resolve("reports"))
        val outside = Files.writeString(directory.resolve("unowned"), "private-fixture-secret")
        Files.createSymbolicLink(reports.resolve("rejected-recording-link"), outside)
        Files.writeString(reports.resolve("ordinary.log"), "ordinary")
        assertTrue(credentialLeakFiles(reports, "private-fixture-secret").isEmpty())
        assertEquals("private-fixture-secret", Files.readString(outside))
    }

    @Test
    fun `retention inspect projects only selected non-secret settings`() {
        assertTrue(".Config.Env" in HTTP_PERFORMANCE_RECORDING_INSPECT_FORMAT)
        assertTrue("FUNDING_RUN_ID=" in HTTP_PERFORMANCE_RECORDING_INSPECT_FORMAT)
        assertTrue("FUNDING_MEMBER_ID=" in HTTP_PERFORMANCE_RECORDING_INSPECT_FORMAT)
        assertTrue("FUNDING_RUN_ROOT=" in HTTP_PERFORMANCE_RECORDING_INSPECT_FORMAT)
        assertFalse("{{json .Config.Env}}" in HTTP_PERFORMANCE_RECORDING_INSPECT_FORMAT)
        assertFalse("FUNDING_MARKET_DB_PASSWORD" in HTTP_PERFORMANCE_RECORDING_INSPECT_FORMAT)
        assertFalse("\\\"" in HTTP_PERFORMANCE_RECORDING_INSPECT_FORMAT)
    }

    private fun candidates(oom: Boolean = false) =
        (0..2).map { HttpPerformanceRecordingCandidate(it, id(it), inspection(it, oom = oom && it == 0), listOf(ready(it))) }

    private fun id(member: Int): String = (member + 1).toString().repeat(64)

    private fun environment(member: Int) = listOf("FUNDING_RUN_ID=$runId", "FUNDING_MEMBER_ID=$member", "FUNDING_RUN_ROOT=/records/$runId")

    private fun inspection(
        member: Int,
        project: String = runId,
        running: Boolean = false,
        volume: String = volumes[member],
        environment: List<String> = environment(member),
        oom: Boolean = false,
    ): JsonNode =
        json(
            mapOf(
                "Id" to id(member),
                "composeProject" to project,
                "composeService" to "node$member",
                "State" to mapOf("Running" to running, "ExitCode" to if (oom) 137 else 0, "OOMKilled" to oom),
                "Mounts" to
                    listOf(
                        mapOf(
                            "Type" to "volume",
                            "Name" to volume,
                            "Destination" to "/records",
                            "Source" to "/var/lib/docker/volumes/$volume/_data",
                        ),
                    ),
                "selectedEnvironment" to (environment + listOf(null)),
            ),
        )

    private fun ready(
        member: Int,
        archivePath: String = "/records/$runId/archive",
    ): JsonNode =
        json(
            mapOf(
                "kind" to "READY",
                "runId" to runId,
                "memberId" to member,
                "settings" to
                    mapOf(
                        "runRoot" to "/records/$runId",
                        "archiveDirectory" to archivePath,
                        "clusterDirectory" to "/records/$runId/cluster",
                    ),
            ),
        )

    private fun writeCopy(
        source: HttpPerformanceRecordingSource,
        path: String,
        target: Path,
    ) {
        Files.createDirectory(target)
        Files.write(target.resolve("record.bin"), byteArrayOf(source.memberId.toByte(), 0, 0x7f, path.length.toByte(), 0xff.toByte()))
    }

    private fun json(value: Any): JsonNode = mapper.readTree(mapper.writeValueAsString(value))

    private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
