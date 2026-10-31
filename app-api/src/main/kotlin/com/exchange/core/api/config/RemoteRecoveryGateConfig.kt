package com.exchange.core.api.config

import com.exchange.core.api.trading.infrastructure.aeron.AeronGatewaySettings
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.api.trading.infrastructure.recovery.FileRecoveryDurabilityGate
import com.exchange.core.api.trading.infrastructure.recovery.RemoteRecoveryFiles
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Mount TRADING_GATEWAY_RECOVERY_ROOT from the independent backup volume read-only.
 * This reader has no write API and never creates or repairs certificates in that shared path.
 */
@Configuration(proxyBeanMethods = false)
@Profile("aeron-bounded-gateway")
@ConditionalOnProperty(name = ["TRADING_GATEWAY_PROTECTION"], havingValue = "remote")
class RemoteRecoveryGateConfig {
    @Bean(destroyMethod = "close")
    internal fun fileRecoveryDurabilityGate(environment: Environment): FileRecoveryDurabilityGate {
        val values = boundedGatewayEnvironment(environment)
        val settings = AeronGatewaySettings.from(values)
        val manifestFile = Path.of(requireNotNull(values["TRADING_GATEWAY_BOUNDED_MANIFEST"]))
        require(
            manifestFile.isAbsolute && Files.isRegularFile(manifestFile, NOFOLLOW_LINKS) &&
                Files.size(manifestFile) in 1..BoundedTradingStateCodec.MAX_MANIFEST_BYTES,
        )
        val codec = BoundedTradingStateCodec()
        val manifestBytes = Files.readAllBytes(manifestFile)
        val manifest = codec.decodeManifest(manifestBytes)
        val root = Path.of(requireNotNull(values["TRADING_GATEWAY_RECOVERY_ROOT"]))
        require(root.isAbsolute && root.normalize() == root && Files.isDirectory(root, NOFOLLOW_LINKS))
        val epoch = requireNotNull(values["TRADING_GATEWAY_RECOVERY_AUTHORITY_EPOCH"]).toLong()
        require(epoch > 0)
        val pollMs = values["TRADING_GATEWAY_RECOVERY_POLL_INTERVAL_MS"]?.toLong() ?: 5
        require(pollMs in 1..1_000)
        return FileRecoveryDurabilityGate(
            root,
            manifest.lineage,
            epoch,
            RemoteRecoveryFiles.sha256(manifestBytes),
            maxPending = settings.queueCapacity + settings.maxInFlight,
            timeoutMs = settings.resultTimeoutMs,
            pollIntervalMs = pollMs,
        )
    }
}

internal fun boundedGatewayEnvironment(environment: Environment): Map<String, String> {
    val names =
        listOf(
            "TRADING_GATEWAY_PROTECTION",
            "TRADING_GATEWAY_RUN_ROOT",
            "TRADING_GATEWAY_INGRESS_ENDPOINTS",
            "TRADING_GATEWAY_EGRESS_ENDPOINT",
            "TRADING_GATEWAY_QUEUE_CAPACITY",
            "TRADING_GATEWAY_MAX_IN_FLIGHT",
            "TRADING_GATEWAY_RESULT_TIMEOUT_MS",
            "TRADING_GATEWAY_STARTUP_TIMEOUT_MS",
            "TRADING_GATEWAY_BOUNDED_MANIFEST",
            "TRADING_GATEWAY_RECOVERY_ROOT",
            "TRADING_GATEWAY_RECOVERY_AUTHORITY_EPOCH",
            "TRADING_GATEWAY_RECOVERY_POLL_INTERVAL_MS",
        )
    return names.mapNotNull { name -> environment.getProperty(name)?.let { name to it } }.toMap()
}
