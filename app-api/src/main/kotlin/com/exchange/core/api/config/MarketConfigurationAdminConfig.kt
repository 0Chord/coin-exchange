package com.exchange.core.api.config

import com.exchange.core.api.market.application.MarketConfigurationUseCase
import com.exchange.core.api.market.infrastructure.persistence.MarketConfigurationDatabaseSettings
import com.exchange.core.api.market.infrastructure.persistence.PostgresMarketConfigurationStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 관리 기능은 명시적으로 켠 경우에만 조립한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["exchange.market-admin.enabled"], havingValue = "true")
class MarketConfigurationAdminConfig {
    @Bean
    fun marketConfigurationUseCase(): MarketConfigurationUseCase =
        MarketConfigurationUseCase(PostgresMarketConfigurationStore(MarketConfigurationDatabaseSettings.from(System.getenv())))
}
