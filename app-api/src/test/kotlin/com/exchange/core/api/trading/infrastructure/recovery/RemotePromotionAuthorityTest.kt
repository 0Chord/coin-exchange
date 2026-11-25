package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingSettings
import com.exchange.core.api.trading.infrastructure.aeron.haBoundedProbeManifest
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingClusterManifest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RemotePromotionAuthorityTest {
    @Test fun `the same source authority can claim only one exact target and one next epoch`(
        @TempDir root: Path,
    ) {
        val certificate = RemoteRecoveryCertificate(ClusterLineage(1, 2), 7, 640, "a".repeat(64), "b".repeat(64))
        val manifest = haBoundedProbeManifest()
        val target = DevelopmentFundingSettings(root.resolve("target-a"), 19700, boundedManifest = manifest)
        val claim = RemotePromotionAuthority.claim(root, certificate, target)
        assertEquals(8L, claim.nextEpoch)
        assertEquals(claim, RemotePromotionAuthority.claim(root, certificate, target))
        assertFalse(RemotePromotionAuthority.completed(root, claim, target))
        assertThrows(IllegalArgumentException::class.java) {
            RemotePromotionAuthority.claim(root, certificate, target.copy(runRoot = root.resolve("target-b")))
        }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { RemotePromotionAuthority.claim(root, certificate, target.copy(basePort = 19800)) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { RemotePromotionAuthority.claim(root, certificate.copy(authorityEpoch = 8), target) }
        val changedVersion =
            TradingClusterManifest(
                manifest.lineage,
                manifest.algorithmId,
                manifest.executionArtifactSha256,
                manifest.schemaSha256,
                manifest.configurationVersion + 1,
                manifest.markets,
                manifest.limits,
            )
        val changedLimits =
            TradingClusterManifest(
                manifest.lineage,
                manifest.algorithmId,
                manifest.executionArtifactSha256,
                manifest.schemaSha256,
                manifest.configurationVersion,
                manifest.markets,
                manifest.limits.copy(
                    maxInboxBytes =
                        manifest.limits.maxInboxBytes + 1,
                ),
            )
        for (different in listOf(changedVersion, changedLimits)) {
            assertThrows(IllegalArgumentException::class.java) {
                RemotePromotionAuthority.claim(root, certificate, target.copy(boundedManifest = different))
            }
        }
    }

    @Test
    fun `한도형 제어를 신뢰하지 않는 승격 권한은 신뢰 설정을 켠 재시도를 거절하고 원래 권한을 보존한다`(
        @TempDir root: Path,
    ) {
        assertTrustedControlChangeRejected(root, trusted = false)
    }

    @Test
    fun `한도형 제어를 신뢰하는 승격 권한은 신뢰 설정을 끈 재시도를 거절하고 원래 권한을 보존한다`(
        @TempDir root: Path,
    ) {
        assertTrustedControlChangeRejected(root, trusted = true)
    }

    private fun assertTrustedControlChangeRejected(
        root: Path,
        trusted: Boolean,
    ) {
        val certificate = RemoteRecoveryCertificate(ClusterLineage(1, 2), 7, 640, "a".repeat(64), "b".repeat(64))
        val target =
            DevelopmentFundingSettings(
                root.resolve("target"),
                boundedManifest = haBoundedProbeManifest(),
                trustedBoundedControl = trusted,
            )
        val claim = RemotePromotionAuthority.claim(root, certificate, target)
        val claimPath = root.resolve(RemotePromotionAuthority.CLAIM)
        val originalClaimBytes = Files.readAllBytes(claimPath)

        assertEquals(claim, RemotePromotionAuthority.claim(root, certificate, target.copy()))
        assertArrayEquals(originalClaimBytes, Files.readAllBytes(claimPath))

        assertThrows(IllegalArgumentException::class.java) {
            RemotePromotionAuthority.claim(root, certificate, target.copy(trustedBoundedControl = !trusted))
        }
        assertArrayEquals(originalClaimBytes, Files.readAllBytes(claimPath))

        assertEquals(claim, RemotePromotionAuthority.claim(root, certificate, target.copy()))
        assertArrayEquals(originalClaimBytes, Files.readAllBytes(claimPath))
    }
}
