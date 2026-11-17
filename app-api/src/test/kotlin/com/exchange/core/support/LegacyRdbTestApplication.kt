package com.exchange.core.support

import com.exchange.core.api.common.ApiExceptionHandler
import com.exchange.core.api.config.LedgerPersistenceConfig
import com.exchange.core.api.config.MatchingConfig
import com.exchange.core.api.config.MatchingPersistenceConfig
import com.exchange.core.api.config.OrderApplicationConfig
import com.exchange.core.api.order.api.OrderController
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/** 이전 RDB 주문 실행의 회귀 검증을 위한 명시적 test 전용 조립이다. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(
    LegacyPersistenceTestApplication::class,
    LedgerPersistenceConfig::class,
    MatchingConfig::class,
    MatchingPersistenceConfig::class,
    OrderApplicationConfig::class,
    OrderController::class,
    ApiExceptionHandler::class,
)
class LegacyRdbTestApplication
