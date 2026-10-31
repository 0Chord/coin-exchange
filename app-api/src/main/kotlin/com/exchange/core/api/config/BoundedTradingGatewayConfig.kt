package com.exchange.core.api.config

import com.exchange.core.api.trading.application.BoundedTradingOrderGateway
import com.exchange.core.api.trading.application.BoundedTradingOrderUseCase
import com.exchange.core.api.trading.infrastructure.aeron.AeronGatewaySettings
import com.exchange.core.api.trading.infrastructure.aeron.BoundedAeronGateway
import com.exchange.core.api.trading.infrastructure.recovery.RecoveryDurabilityGate
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment

/** 명시적으로 선택하는 wire3 경계다. RDB 설정을 읽거나 거래 클러스터를 초기화하지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("aeron-bounded-gateway")
@Import(RemoteRecoveryGateConfig::class)
class BoundedTradingGatewayConfig {
    @Bean
    fun boundedTradingOrderUseCase(gateway: BoundedTradingOrderGateway): BoundedTradingOrderUseCase = BoundedTradingOrderUseCase(gateway)

    @Bean(destroyMethod = "close")
    internal fun boundedAeronGateway(
        gates: ObjectProvider<RecoveryDurabilityGate>,
        source: Environment,
    ): BoundedAeronGateway {
        val environment = boundedGatewayEnvironment(source)
        val protection = environment["TRADING_GATEWAY_PROTECTION"] ?: "local"
        require(protection in setOf("local", "remote"))
        val settings = AeronGatewaySettings.from(environment)
        if (protection == "local") return BoundedAeronGateway(settings)
        val file =
            java.nio.file.Path
                .of(requireNotNull(environment["TRADING_GATEWAY_BOUNDED_MANIFEST"]))
        val codec =
            com.exchange.core.api.trading.infrastructure.history
                .BoundedTradingStateCodec()
        require(
            file.isAbsolute &&
                java.nio.file.Files
                    .size(file) in
                1..com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec.MAX_MANIFEST_BYTES,
        )
        val manifest =
            codec.decodeManifest(
                java.nio.file.Files
                    .readAllBytes(file),
            )
        require(
            manifest.schemaSha256 ==
                com.exchange.core.api.trading.infrastructure.aeron.BoundedExecutionIdentity
                    .schemaSha256,
        ) {
            "gateway wire schema differs from bounded manifest"
        }
        return BoundedAeronGateway(
            settings,
            manifest.lineage,
            requireNotNull(gates.ifUnique) {
                "remote profile requires a configured recovery durability gate"
            },
        )
    }

    @Bean
    fun boundedTradingJsonMapperCustomizer() = TradingGatewayConfig().tradingJsonMapperBuilderCustomizer()

    @Bean
    fun boundedTradingAsyncSupport() = TradingGatewayConfig().tradingAsyncSupport()
}
