package com.exchange.core

import com.exchange.core.api.common.TradingOrderHttpExceptionHandler
import com.exchange.core.api.config.BoundedTradingGatewayConfig
import com.exchange.core.api.config.OrderQueryConfig
import com.exchange.core.api.trading.api.BoundedTradingOrderController
import com.exchange.core.api.trading.api.OrderQueryController
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import

/**
 * Exchange Core HTTP 애플리케이션의 Spring Boot 구성 진입점.
 *
 * 단일 WAS에서 자금 엔진에 전달하는 주문 API와 공용 RDB 조회 API를 함께 조립한다.
 * 이전 RDB 주문 실행 구성과 JPA 저장소는 이 실행 경로에 등록하지 않는다.
 */
@SpringBootApplication(
    excludeName = [
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
    ],
)
@ComponentScan(useDefaultFilters = false)
@Import(
    BoundedTradingGatewayConfig::class,
    OrderQueryConfig::class,
    BoundedTradingOrderController::class,
    OrderQueryController::class,
    TradingOrderHttpExceptionHandler::class,
)
class ExchangeCoreApplication

/**
 * Spring Boot 애플리케이션을 시작한다.
 *
 * @param args JVM 프로세스에서 전달된 command-line 인자
 */
fun main(args: Array<String>) {
    runApplication<ExchangeCoreApplication>(*args)
}
