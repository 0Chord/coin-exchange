package com.exchange.core.api.config

import com.exchange.core.api.market.application.MarketConfigurationUseCase
import com.exchange.core.api.market.infrastructure.persistence.MarketConfigurationDatabaseSettings
import com.exchange.core.api.market.infrastructure.persistence.PostgresMarketConfigurationStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/** 관리 기능은 명시적으로 켠 경우에만 조립한다. 인증값은 명령행·설정 파일 대신 환경변수에서 받는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["exchange.market-admin.enabled"], havingValue = "true")
class MarketConfigurationAdminConfig {
    private val authentication = MarketConfigurationAdminAuthentication(System.getenv("EXCHANGE_MARKET_ADMIN_TOKEN"))

    @Bean
    fun marketConfigurationUseCase(): MarketConfigurationUseCase =
        MarketConfigurationUseCase(PostgresMarketConfigurationStore(MarketConfigurationDatabaseSettings.from(System.getenv())))

    @Bean
    fun marketConfigurationAdminAuthentication(): FilterRegistrationBean<MarketConfigurationAdminAuthentication> =
        FilterRegistrationBean(authentication).apply {
            addUrlPatterns("/api/admin", "/api/admin/*")
            order = Ordered.HIGHEST_PRECEDENCE
        }
}
