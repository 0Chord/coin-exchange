package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.fee.FeeRate
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DevelopmentFundingRunTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `복구 잠금은 설정 검사와 이력 생성 전 같은 루트의 두 소유자를 막는다`() {
        val config = DevelopmentFundingSettings(directory.resolve("run"))
        DevelopmentFundingRun.open(config).use {
            createRecordedDirectories(config)
            val before = files(config.runRoot)
            assertFailsWith<OverlappingFileLockException> { DevelopmentFundingRun.open(config.copy(recoverExisting = true)) }
            assertEquals(before, files(config.runRoot))
        }
        DevelopmentFundingRun.open(config.copy(recoverExisting = true)).close()
    }

    @Test
    fun `복구는 정확한 정책과 구성 버전을 검증하고 새 이력 세대만 연다`() {
        val context = DevelopmentMarketContext.BTC_USDT
        val config =
            DevelopmentFundingSettings(
                directory.resolve("run"),
                markets = DevelopmentMarketRegistry(listOf(context), 3, "a".repeat(64)),
            )
        lateinit var previous: Path
        lateinit var fingerprint: String
        DevelopmentFundingRun.open(config).use { run ->
            previous = run.historyDirectory
            fingerprint = run.identitySha256
            createRecordedDirectories(config)
            Files.writeString(config.archiveDirectory.resolve("sentinel"), "preserve")
            Files.writeString(config.clusterDirectory.resolve("sentinel"), "preserve")
        }
        assertFalse(Files.exists(previous.parent))
        val bytes = Files.readAllBytes(config.runRoot.resolve("recovery-identity.bin"))
        val before = files(config.runRoot)
        val changedPolicy =
            context.copy(
                feePolicySnapshot =
                    context.feePolicySnapshot.copy(
                        feeRates = context.feePolicySnapshot.feeRates.copy(makerFeeRate = FeeRate(1)),
                    ),
            )
        listOf(
            config.copy(markets = DevelopmentMarketRegistry(listOf(changedPolicy), 3, "a".repeat(64))),
            config.copy(markets = DevelopmentMarketRegistry(listOf(context), 4, "a".repeat(64))),
            config.copy(markets = DevelopmentMarketRegistry(listOf(context), 3, "b".repeat(64))),
            config.copy(basePort = config.basePort + 10),
        ).forEach { changed ->
            assertFailsWith<IllegalArgumentException> { DevelopmentFundingRun.open(changed.copy(recoverExisting = true)) }
            assertEquals(before, files(config.runRoot))
            assertContentEquals(bytes, Files.readAllBytes(config.runRoot.resolve("recovery-identity.bin")))
        }
        DevelopmentFundingRun.open(config.copy(recoverExisting = true)).use { recovered ->
            assertNotEquals(previous, recovered.historyDirectory)
            assertFalse(Files.exists(recovered.historyDirectory), "RocksDB는 존재하지 않는 새 store 경로만 받는다")
            assertEquals(fingerprint, recovered.identitySha256)
        }
        assertEquals("preserve", Files.readString(config.archiveDirectory.resolve("sentinel")))
        assertEquals("preserve", Files.readString(config.clusterDirectory.resolve("sentinel")))
        assertTrue(Files.exists(config.runRoot.resolve("recovery-identity.bin")))
    }

    @Test
    fun `없는 정체성과 손상된 정체성은 복구 전에 거절한다`() {
        val config = DevelopmentFundingSettings(directory.resolve("run"))
        DevelopmentFundingRun.open(config).use {
            createRecordedDirectories(config)
        }
        val identity = config.runRoot.resolve("recovery-identity.bin")
        Files.write(identity, byteArrayOf(1, 2, 3))
        assertFailsWith<IllegalArgumentException> { DevelopmentFundingRun.open(config.copy(recoverExisting = true)) }
        Files.delete(identity)
        assertFailsWith<IllegalArgumentException> { DevelopmentFundingRun.open(config.copy(recoverExisting = true)) }
    }

    @Test
    fun `세 멤버의 Snapshot 정체성은 같고 로컬 기록의 주소 변경은 거절한다`() {
        val members =
            (0..2).joinToString("|") { member ->
                "$member," + (0..4).joinToString(",") { "node$member:${19700 + member * 10 + it}" }
            }
        val settings =
            (0..2).map { member ->
                DevelopmentFundingSettings(
                    directory.resolve("member-$member"),
                    basePort = 19700 + member * 10,
                    memberId = member,
                    advertisedHost = "node$member",
                    bindHost = if (member == 0) "127.0.0.1" else "0.0.0.0",
                    clusterMembers = members,
                    runId = "shared-recovery",
                    archiveFileSyncLevel = member,
                )
            }
        val identities =
            settings.map { config ->
                DevelopmentFundingRun.open(config).use { run ->
                    createRecordedDirectories(config)
                    run.identitySha256 to run.snapshotIdentitySha256
                }
            }
        assertEquals(3, identities.map { it.first }.distinct().size)
        assertEquals(1, identities.map { it.second }.distinct().size)

        val original = settings.first()
        listOf(
            original.copy(memberId = 1),
            original.copy(basePort = original.basePort + 1),
            original.copy(bindHost = "0.0.0.0"),
            original.copy(advertisedHost = "changed-node0"),
        ).forEach { changed ->
            assertFailsWith<IllegalArgumentException> { DevelopmentFundingRun.open(changed.copy(recoverExisting = true)) }
        }
        val changedContext = original.markets.single().let { it.copy(feePolicySnapshot = it.feePolicySnapshot.copy(scheduleVersion = 2)) }
        val changedPolicy =
            original.copy(
                runRoot = directory.resolve("changed-policy"),
                markets = DevelopmentMarketRegistry(listOf(changedContext)),
            )
        DevelopmentFundingRun.open(changedPolicy).use { changed ->
            assertNotEquals(identities.first().second, changed.snapshotIdentitySha256)
        }
        DevelopmentFundingRun.open(original.copy(recoverExisting = true)).use { recovered ->
            assertEquals(identities.first().first, recovered.identitySha256)
            assertEquals(identities.first().second, recovered.snapshotIdentitySha256)
        }
    }

    private fun createRecordedDirectories(config: DevelopmentFundingSettings) {
        Files.createDirectory(config.archiveDirectory)
        Files.createDirectory(config.clusterDirectory)
        Files.writeString(config.archiveDirectory.resolve("archive.catalog"), "metadata")
        Files.writeString(config.clusterDirectory.resolve("recording.log"), "metadata")
    }

    private fun files(root: Path): Set<Path> = Files.walk(root).use { it.toList().toSet() }
}
