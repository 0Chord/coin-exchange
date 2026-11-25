package com.exchange.core.support

import com.exchange.core.api.common.TradingOrderHttpExceptionHandler
import com.exchange.core.api.config.TradingHttpConfig
import com.exchange.core.api.config.boundedGatewayEnvironment
import com.exchange.core.api.trading.api.BoundedTradingOrderController
import com.exchange.core.api.trading.application.BoundedTradingOrderGateway
import com.exchange.core.api.trading.application.BoundedTradingOrderUseCase
import com.exchange.core.api.trading.infrastructure.aeron.AeronGatewaySettings
import com.exchange.core.api.trading.infrastructure.aeron.BoundedAeronGateway
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

/** 이전 단일 거래 클러스터 wire3의 회귀 검증용이며 제품 실행 경로가 아니다. */
@TestComponent
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    excludeName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    ],
)
@Import(BoundedTradingOrderController::class, TradingOrderHttpExceptionHandler::class, TradingHttpConfig::class)
class LegacyBoundedGatewayTestApplication {
    @Bean
    fun boundedTradingOrderUseCase(gateway: BoundedTradingOrderGateway) = BoundedTradingOrderUseCase(gateway)

    @Bean(destroyMethod = "close")
    internal fun boundedAeronGateway(environment: Environment): BoundedAeronGateway {
        val values = boundedGatewayEnvironment(environment)
        require((values["TRADING_GATEWAY_PROTECTION"] ?: "local") == "local")
        return BoundedAeronGateway(AeronGatewaySettings.from(values))
    }
}

fun main(args: Array<String>) {
    SpringApplicationBuilder(LegacyBoundedGatewayTestApplication::class.java).run(*args)
}
