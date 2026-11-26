package com.exchange.core.api.config

import com.exchange.core.api.trading.application.BoundedTradingOrderGateway
import com.exchange.core.api.trading.application.BoundedTradingOrderUseCase
import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.infrastructure.aeron.AeronGatewaySettings
import com.exchange.core.api.trading.infrastructure.aeron.BoundedAeronGateway
import com.exchange.core.api.trading.infrastructure.aeron.BoundedExecutionIdentity
import com.exchange.core.api.trading.infrastructure.aeron.OrderQueryFeedWorker
import com.exchange.core.api.trading.infrastructure.aeron.SplitFundsGatewayTransport
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.api.trading.infrastructure.recovery.RecoveryDurabilityGate
import com.exchange.core.trading.ClusterLineage
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationListener
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ContextClosedEvent
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import java.nio.file.Files
import java.nio.file.Path

/** 주문은 자금 엔진에 전달하고 조회와 같은 funds lineage를 사용한다. */
@Configuration(proxyBeanMethods = false)
class BoundedTradingGatewayConfig {
    @Bean
    fun boundedTradingOrderUseCase(gateway: BoundedTradingOrderGateway): BoundedTradingOrderUseCase = BoundedTradingOrderUseCase(gateway)

    @Bean
    internal fun tradingGatewayShutdown(
        gateway: BoundedAeronGateway,
        context: ApplicationContext,
    ): TradingGatewayShutdown = TradingGatewayShutdown(gateway, context)

    @Bean(name = ["boundedAeronGateway"], destroyMethod = "close")
    internal fun boundedAeronGateway(
        gates: ObjectProvider<RecoveryDurabilityGate>,
        queryWorkers: ObjectProvider<OrderQueryFeedWorker>,
        source: Environment,
        namespace: OrderQueryNamespace,
    ): BoundedAeronGateway {
        val environment = boundedGatewayEnvironment(source)
        val protection = environment["TRADING_GATEWAY_PROTECTION"] ?: "local"
        require(protection in setOf("local", "remote"))
        val settings = AeronGatewaySettings.from(environment)
        val requiresRecoveryReady = source.getProperty("exchange.order-query.require-recovery-ready", Boolean::class.java, false)
        require(!requiresRecoveryReady || source.getProperty("exchange.order-query.source", "live") == "live") {
            "복구 대사 승인으로 접수를 제한하는 설정은 실시간 조회 모드에서 사용합니다"
        }
        val recoveryGate =
            if (requiresRecoveryReady) {
                require(namespace.markets.size == 1) { "복구 접수 보호는 단일 마켓 설정이 필요합니다" }
                val worker = requireNotNull(queryWorkers.ifAvailable) { "복구 접수 보호는 exchange.order-query.consumer-id 설정이 필요합니다" }
                worker.recoveryGate
            } else {
                null
            }

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
            cancelEncoder = SplitFundsGatewayTransport.Companion::encodeCancellation,
            recoveryGate = recoveryGate,
            openTransport = { receive ->
                SplitFundsGatewayTransport(settings, namespace.fundsLineage, receive, onCancellation = receive.cancellation)
            },
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

internal class TradingGatewayShutdown(
    private val gateway: BoundedAeronGateway,
    private val context: ApplicationContext,
) : ApplicationListener<ContextClosedEvent>,
    SmartLifecycle,
    Ordered {
    @Volatile private var running = false

    @Volatile private var contextClosing = false

    override fun supportsAsyncExecution(): Boolean = false

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun onApplicationEvent(event: ContextClosedEvent) {
        if (event.applicationContext !== context) return
        contextClosing = true
        gateway.beginShutdown()
    }

    override fun getPhase(): Int = WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE - 1

    override fun isRunning(): Boolean = running

    override fun start() {
        running = true
    }

    override fun stop(callback: Runnable) {
        Thread.startVirtualThread {
            try {
                stop()
            } finally {
                callback.run()
            }
        }
    }

    override fun stop() {
        try {
            if (contextClosing) gateway.awaitShutdown()
        } finally {
            running = false
        }
    }
}
