package com.exchange.core.api.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.tomcat.autoconfigure.TomcatVirtualThreadsWebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** HTTP 실행기 비교 실험 전용이며 제품 배포물에는 포함하지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("aeron-gateway")
class TradingGatewayHttpWorkerConfig {
    @Bean
    @ConditionalOnProperty(
        prefix = "trading.aeron.gateway",
        name = ["http-virtual-threads"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun tradingGatewayHttpVirtualThreads(): TomcatVirtualThreadsWebServerFactoryCustomizer =
        TomcatVirtualThreadsWebServerFactoryCustomizer()
}
