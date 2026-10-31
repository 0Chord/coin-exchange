package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingRun
import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingSettings
import com.exchange.core.api.trading.infrastructure.aeron.haBoundedProbeManifest
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 작은 파일은 기동 전 메타데이터 경계용이며 실제 Archive 재생은 별도 통합 검사에서 수행한다. */
class RemotePromotionStartupTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `필수 복구 파일이 있어도 완료되지 않은 승격은 기동 전에 거절한다`() {
        for (withProof in listOf(false, true)) {
            val fixture = fixture("pending-$withProof", withProof)
            val before = files(fixture.target.runRoot)
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    DevelopmentFundingRun.open(fixture.target.copy(recoverExisting = true)).close()
                }
            assertTrue(failure.message.orEmpty().contains("승격"))
            assertEquals(before.keys, files(fixture.target.runRoot).keys)
            before.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        }
    }

    @Test
    fun `승격 완료 대상은 같은 설정으로 일반 복구 검사를 통과한다`() {
        val fixture = fixture("completed")
        RemotePromotionAuthority.complete(fixture.backup, fixture.target.runRoot, fixture.claim)
        assertTrue(RemotePromotionAuthority.completed(fixture.backup, fixture.claim, fixture.target))
        DevelopmentFundingRun.open(fixture.target.copy(recoverExisting = true)).use { recovered ->
            assertEquals(fixture.identity, recovered.identitySha256)
        }
    }

    @Test
    fun `승격 완료 메타데이터나 증명이 누락되거나 손상되면 일반 기동을 거절한다`() {
        val markers = listOf(REQUIRED, RemotePromotionAuthority.TARGET_AUTHORITY, PROOF)
        for ((index, marker) in markers.withIndex()) {
            for (corrupt in listOf(false, true)) {
                val fixture = fixture("invalid-$index-$corrupt")
                RemotePromotionAuthority.complete(fixture.backup, fixture.target.runRoot, fixture.claim)
                val path = fixture.target.runRoot.resolve(marker)
                if (corrupt) Files.write(path, byteArrayOf(1)) else Files.delete(path)
                assertFailsWith<IllegalArgumentException> {
                    DevelopmentFundingRun.open(fixture.target.copy(recoverExisting = true)).close()
                }
            }
        }
    }

    @Test
    fun `백업 완료 기록에 실패하면 대상 기동 허가를 먼저 게시하지 않는다`() {
        val fixture = fixture("backup-write-failure")
        Files.createDirectory(fixture.backup.resolve("promotion-complete.bin.new"))
        assertFails { RemotePromotionAuthority.complete(fixture.backup, fixture.target.runRoot, fixture.claim) }
        assertFalse(Files.exists(fixture.target.runRoot.resolve(RemotePromotionAuthority.TARGET_AUTHORITY)))
        assertFailsWith<IllegalArgumentException> {
            DevelopmentFundingRun.open(fixture.target.copy(recoverExisting = true)).close()
        }
    }

    @Test
    fun `백업 완료 뒤 대상 허가 기록에 실패해도 일반 기동은 계속 거절한다`() {
        val fixture = fixture("target-write-failure")
        Files.createDirectory(fixture.target.runRoot.resolve(RemotePromotionAuthority.TARGET_AUTHORITY + ".new"))
        assertFails { RemotePromotionAuthority.complete(fixture.backup, fixture.target.runRoot, fixture.claim) }
        assertTrue(Files.isRegularFile(fixture.backup.resolve("promotion-complete.bin")))
        assertFalse(Files.exists(fixture.target.runRoot.resolve(RemotePromotionAuthority.TARGET_AUTHORITY)))
        assertFailsWith<IllegalArgumentException> {
            DevelopmentFundingRun.open(fixture.target.copy(recoverExisting = true)).close()
        }
    }

    @Test
    fun `승격 표시와 증명을 심볼릭 링크로 바꾸면 기동을 거절한다`() {
        for ((index, marker) in listOf(REQUIRED, RemotePromotionAuthority.TARGET_AUTHORITY, PROOF).withIndex()) {
            val fixture = fixture("symlink-$index")
            RemotePromotionAuthority.complete(fixture.backup, fixture.target.runRoot, fixture.claim)
            val path = fixture.target.runRoot.resolve(marker)
            val saved = path.resolveSibling(marker + ".saved")
            Files.move(path, saved)
            Files.createSymbolicLink(path, saved.fileName)
            assertFailsWith<IllegalArgumentException> {
                DevelopmentFundingRun.open(fixture.target.copy(recoverExisting = true)).close()
            }
        }
    }

    private fun fixture(
        name: String,
        withProof: Boolean = true,
    ): Fixture {
        val root = Files.createDirectory(directory.resolve(name))
        val backup = Files.createDirectory(root.resolve("backup"))
        val manifest = haBoundedProbeManifest()
        val target = DevelopmentFundingSettings(root.resolve("target"), boundedManifest = manifest, trustedBoundedControl = true)
        val identity = DevelopmentFundingRun.open(target).use { it.identitySha256 }
        Files.createDirectory(target.archiveDirectory)
        Files.createDirectory(target.clusterDirectory)
        // Run.open에서 기동 전 검사만 수행한다. 이 파일을 실제 Aeron에 전달하지 않는다.
        Files.writeString(target.archiveDirectory.resolve("archive.catalog"), "catalog metadata")
        Files.writeString(target.clusterDirectory.resolve("recording.log"), "recording metadata")
        val execution = RemoteRecoveryFiles.sha256(BoundedTradingStateCodec().encodeManifest(manifest))
        val proof = remoteTestProof(manifest.lineage, 7, 640, execution)
        val certificate = RemoteRecoveryFiles.publish(backup, proof, manifest.lineage, 7, 640, execution)
        val claim = RemotePromotionAuthority.claim(backup, certificate, target)
        RemoteRecoveryFiles.writeForced(
            target.runRoot.resolve(REQUIRED),
            Files.readAllBytes(backup.resolve(RemotePromotionAuthority.CLAIM)),
        )
        if (withProof) RemoteRecoveryFiles.writeForced(target.runRoot.resolve(PROOF), proof)
        return Fixture(backup, target, claim, identity)
    }

    private fun files(root: Path): Map<Path, ByteArray> =
        Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associateWith(Files::readAllBytes) }

    private data class Fixture(
        val backup: Path,
        val target: DevelopmentFundingSettings,
        val claim: RemotePromotionAuthority.Claim,
        val identity: String,
    )

    private companion object {
        const val REQUIRED = "promotion-required.bin"
        const val PROOF = "promoted-recovery-proof.bin"
    }
}
