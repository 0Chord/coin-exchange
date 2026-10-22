package com.exchange.core.api.market.api

import com.exchange.core.api.common.ApiExceptionHandler
import com.exchange.core.api.common.MarketConfigurationHttpExceptionHandler
import com.exchange.core.api.config.MarketConfigurationAdminConfig
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.annotation.Import
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/** 실제 환경변수와 제품 controller/config를 사용하는 별도 JVM의 HTTP 테스트 서버다. */
fun main(args: Array<String>) {
    val context =
        SpringApplicationBuilder(MarketConfigurationHttpTestApplication::class.java)
            .run("--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")
    context.use {
        val port = requireNotNull((context as ServletWebServerApplicationContext).webServer).port
        Files.writeString(Path.of(args.single()), port.toString())
        CountDownLatch(1).await()
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    excludeName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    ],
)
@Import(
    MarketConfigurationAdminConfig::class,
    MarketConfigurationController::class,
    ApiExceptionHandler::class,
    MarketConfigurationHttpExceptionHandler::class,
)
class MarketConfigurationHttpTestApplication
