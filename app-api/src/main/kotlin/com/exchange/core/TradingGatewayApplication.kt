package com.exchange.core

import com.exchange.core.api.common.TradingOrderHttpExceptionHandler
import com.exchange.core.api.config.TradingGatewayConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile

/** Aeron 접수 API만 열며 기존 RDB 주문·취소 조립과 실행을 분리한다. */
@SpringBootApplication(
    scanBasePackages = ["com.exchange.core.api.trading.api"],
    excludeName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    ],
)
@Profile("aeron-gateway")
@Import(TradingGatewayConfig::class, TradingOrderHttpExceptionHandler::class)
class TradingGatewayApplication

fun main(args: Array<String>) {
    SpringApplicationBuilder(TradingGatewayApplication::class.java)
        .profiles("aeron-gateway")
        .run(*args)
}
