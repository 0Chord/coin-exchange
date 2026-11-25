package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DevelopmentFundingDriverPathTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `external Driver root is opt in and uniquely names only transport files`() {
        val original = DevelopmentFundingSettings(directory.resolve("records"))
        assertEquals(original.runRoot.resolve("driver").toString(), original.driverDirectory)
        val selected = original.copy(driverRoot = Path.of("/dev/shm"))
        val path = Path.of(selected.driverDirectory)
        assertEquals(Path.of("/dev/shm"), path.parent)
        assertTrue(path.fileName.toString().matches(Regex("exchange-node-0-[0-9a-f]{64}")))
        assertEquals(selected.driverDirectory, selected.copy().driverDirectory)
        assertNotEquals(selected.driverDirectory, selected.copy(memberId = 1).driverDirectory)
        assertNotEquals(selected.driverDirectory, selected.copy(runRoot = directory.resolve("other-records")).driverDirectory)
        assertEquals(original.archiveDirectory, selected.archiveDirectory)
        assertEquals(original.clusterDirectory, selected.clusterDirectory)
        assertEquals(2, selected.archiveFileSyncLevel)
        assertEquals(path.toString(), selected.archiveClient().aeronDirectoryName())
    }

    @Test
    fun `environment selects Driver root without relocating persistent records`() {
        val environment =
            mapOf(
                "FUNDING_RUN_ROOT" to directory.resolve("records").toString(),
                "FUNDING_RUN_ID" to "driver-path",
                "FUNDING_HOST" to "127.0.0.1",
                "FUNDING_MEMBERS" to "0,127.0.0.1:19700,127.0.0.1:19701,127.0.0.1:19702,127.0.0.1:19703,127.0.0.1:19704",
            )
        val original = developmentClusterSettings(environment)
        val selected = developmentClusterSettings(environment + ("FUNDING_DRIVER_ROOT" to "/dev/shm"))
        assertEquals(Path.of("/dev/shm"), selected.driverRoot)
        assertEquals(original.archiveDirectory, selected.archiveDirectory)
        assertEquals(original.clusterDirectory, selected.clusterDirectory)
        assertEquals(original.members, selected.members)
        assertFailsWith<IllegalArgumentException> { selected.copy(driverRoot = Path.of("relative")) }
        assertFailsWith<IllegalArgumentException> { selected.copy(driverRoot = Path.of("/dev/shm/../shm")) }
    }

    @Test
    fun `missing external root is rejected before creating persistent state`() {
        val config = DevelopmentFundingSettings(directory.resolve("records"), driverRoot = directory.resolve("missing"))
        assertFailsWith<IllegalArgumentException> { DevelopmentFundingRun.open(config) }
        assertFalse(Files.exists(config.runRoot))
        assertFalse(Files.exists(config.driverRoot))
    }

    @Test
    fun `recovery identity permits lost and relocated transport files without changing durable records`() {
        val firstRoot = Files.createDirectory(directory.resolve("first-volatile"))
        val config = DevelopmentFundingSettings(directory.resolve("records"), driverRoot = firstRoot)
        lateinit var fingerprint: String
        lateinit var snapshotFingerprint: String
        DevelopmentFundingRun.open(config).use { run ->
            fingerprint = run.identitySha256
            snapshotFingerprint = run.snapshotIdentitySha256
            recordedDirectories(config)
            Files.createDirectory(Path.of(config.driverDirectory))
            Files.writeString(Path.of(config.driverDirectory).resolve("cnc.dat"), "disposable")
        }
        val identity = Files.readAllBytes(config.runRoot.resolve("recovery-identity.bin"))
        Files.walk(firstRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        val secondRoot = Files.createDirectory(directory.resolve("replacement-volatile"))
        val recovered = config.copy(recoverExisting = true, driverRoot = secondRoot)
        DevelopmentFundingRun.open(recovered).use { run ->
            assertEquals(fingerprint, run.identitySha256)
            assertEquals(snapshotFingerprint, run.snapshotIdentitySha256)
        }
        assertContentEquals(identity, Files.readAllBytes(config.runRoot.resolve("recovery-identity.bin")))
        assertEquals("archive", Files.readString(config.archiveDirectory.resolve("archive.catalog")))
        assertEquals("consensus", Files.readString(config.clusterDirectory.resolve("recording.log")))
        // 기본 경로로 되돌리는 것도 전송 계층만 복구하는 선택이다.
        DevelopmentFundingRun.open(config.copy(recoverExisting = true, driverRoot = null)).close()
    }

    @Test
    fun `archive cluster and symlink targets cannot become disposable Driver directories`() {
        val config = DevelopmentFundingSettings(directory.resolve("records"))
        DevelopmentFundingRun.open(config).use { recordedDirectories(config) }
        val external = Files.createDirectory(directory.resolve("volatile"))
        val selected = config.copy(recoverExisting = true, driverRoot = external)
        val symlink = Path.of(selected.driverDirectory)
        Files.createSymbolicLink(symlink, config.archiveDirectory)
        assertFailsWith<IllegalArgumentException> { DevelopmentFundingRun.open(selected) }
        Files.delete(symlink)
        for (overlap in listOf(config.runRoot, config.archiveDirectory, config.clusterDirectory)) {
            assertFailsWith<IllegalArgumentException> {
                DevelopmentFundingRun.open(
                    config.copy(recoverExisting = true, driverRoot = overlap),
                )
            }
        }
        val alias = directory.resolve("archive-alias")
        Files.createSymbolicLink(alias, config.archiveDirectory)
        assertFailsWith<IllegalArgumentException> { DevelopmentFundingRun.open(config.copy(recoverExisting = true, driverRoot = alias)) }
        assertEquals("archive", Files.readString(config.archiveDirectory.resolve("archive.catalog")))
        assertEquals("consensus", Files.readString(config.clusterDirectory.resolve("recording.log")))
    }

    private fun recordedDirectories(config: DevelopmentFundingSettings) {
        Files.createDirectory(config.archiveDirectory)
        Files.createDirectory(config.clusterDirectory)
        Files.writeString(config.archiveDirectory.resolve("archive.catalog"), "archive")
        Files.writeString(config.clusterDirectory.resolve("recording.log"), "consensus")
    }
}
