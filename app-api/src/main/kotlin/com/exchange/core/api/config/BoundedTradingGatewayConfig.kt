package com.exchange.core.api.config

import com.exchange.core.api.trading.application.BoundedTradingOrderGateway
import com.exchange.core.api.trading.application.BoundedTradingOrderUseCase
import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.infrastructure.aeron.AeronGatewaySettings
import com.exchange.core.api.trading.infrastructure.aeron.BoundedAeronGateway
import com.exchange.core.api.trading.infrastructure.aeron.BoundedExecutionIdentity
import com.exchange.core.api.trading.infrastructure.aeron.SplitFundsGatewayTransport
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.api.trading.infrastructure.recovery.RecoveryDurabilityGate
import com.exchange.core.trading.ClusterLineage
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.nio.file.Files
import java.nio.file.Path

/** 주문은 자금 엔진에 전달하고 조회와 같은 funds lineage를 사용한다. */
@Configuration(proxyBeanMethods = false)
class BoundedTradingGatewayConfig {
    @Bean
    fun boundedTradingOrderUseCase(gateway: BoundedTradingOrderGateway): BoundedTradingOrderUseCase = BoundedTradingOrderUseCase(gateway)

    @Bean(name = ["boundedAeronGateway"], destroyMethod = "close")
    internal fun boundedAeronGateway(
        gates: ObjectProvider<RecoveryDurabilityGate>,
        source: Environment,
        namespace: OrderQueryNamespace,
    ): BoundedAeronGateway {
        val environment = boundedGatewayEnvironment(source)
        val protection = environment["TRADING_GATEWAY_PROTECTION"] ?: "local"
        require(protection in setOf("local", "remote"))
        val settings = AeronGatewaySettings.from(environment)

        fun gateway(
            protectedLineage: ClusterLineage? = null,
            durabilityGate: RecoveryDurabilityGate? = null,
        ) = BoundedAeronGateway(
            settings,
            protectedLineage,
            durabilityGate,
            commandEncoder = { correlationId, command ->
                SplitFundsGatewayTransport.encodeCommand(correlationId, command)
            },
            openTransport = { receive -> SplitFundsGatewayTransport(settings, namespace.fundsLineage, receive) },
        )
        if (protection == "local") return gateway()
        val file = Path.of(requireNotNull(environment["TRADING_GATEWAY_BOUNDED_MANIFEST"]))
        val codec = BoundedTradingStateCodec()
        require(file.isAbsolute && Files.size(file) in 1..BoundedTradingStateCodec.MAX_MANIFEST_BYTES)
        val manifest = codec.decodeManifest(Files.readAllBytes(file))
        require(manifest.schemaSha256 == BoundedExecutionIdentity.schemaSha256) {
            "gateway wire schema differs from bounded manifest"
        }
        require(manifest.lineage == namespace.fundsLineage) { "gateway manifest differs from order query funds lineage" }
        return gateway(
            manifest.lineage,
            requireNotNull(gates.ifUnique) {
                "remote protection requires a configured recovery durability gate"
            },
        )
    }
}
