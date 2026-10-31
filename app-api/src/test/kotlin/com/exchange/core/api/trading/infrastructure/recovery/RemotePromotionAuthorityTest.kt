package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingSettings
import com.exchange.core.api.trading.infrastructure.aeron.haBoundedProbeManifest
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingClusterManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
}
