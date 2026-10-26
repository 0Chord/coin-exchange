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
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch

/** 실제 환경변수와 제품 controller/config를 사용하는 별도 JVM의 HTTP 테스트 서버다. */
fun main(args: Array<String>) {
    val context =
        SpringApplicationBuilder(MarketConfigurationHttpTestApplication::class.java)
            .run("--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")
    context.use {
        val port = requireNotNull((context as ServletWebServerApplicationContext).webServer).port
        val ready = Path.of(args.single())
        val pending = ready.resolveSibling("${ready.fileName}.pending")
        Files.writeString(pending, port.toString())
        // 준비 파일이 보일 때에는 포트 내용도 모두 기록되어 있어야 한다.
        Files.move(pending, ready, StandardCopyOption.ATOMIC_MOVE)
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
