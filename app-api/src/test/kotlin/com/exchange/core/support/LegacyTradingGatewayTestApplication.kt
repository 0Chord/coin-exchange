package com.exchange.core.support

import com.exchange.core.api.common.TradingOrderHttpExceptionHandler
import com.exchange.core.api.config.TradingGatewayConfig
import com.exchange.core.api.config.TradingGatewayHttpWorkerConfig
import com.exchange.core.api.trading.api.TradingOrderController
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/** 이전 wire2 HTTP 회귀 검증용이며 제품 배포물에는 포함하지 않는다. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    excludeName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    ],
)
@Import(
    TradingGatewayConfig::class,
    TradingOrderController::class,
    TradingOrderHttpExceptionHandler::class,
    TradingGatewayHttpWorkerConfig::class,
)
class LegacyTradingGatewayTestApplication

fun main(args: Array<String>) {
    SpringApplicationBuilder(LegacyTradingGatewayTestApplication::class.java).run(*args)
}
