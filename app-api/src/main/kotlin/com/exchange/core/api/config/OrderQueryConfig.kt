package com.exchange.core.api.config

import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.OrderQueryUseCase
import com.exchange.core.api.trading.application.port.OrderQueryStore
import com.exchange.core.api.trading.infrastructure.persistence.PostgresOrderQueryStore
import com.exchange.core.trading.ClusterLineage
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/** 주문 접수와 같은 WAS에서 공용 RDB 연결과 마이그레이션을 사용하는 조회 구성이다. */
@Configuration(proxyBeanMethods = false)
class OrderQueryConfig {
    @Bean
    fun orderQueryNamespace(environment: Environment): OrderQueryNamespace {
        val prefix = "exchange.order-query."
        val markets = environment.getRequiredProperty(prefix + "supported-markets").split(',').map(String::trim)
        require(markets.none(String::isBlank) && markets.distinct().size == markets.size) { "invalid order query markets" }
        return OrderQueryNamespace(
            ClusterLineage(
                environment.getRequiredProperty(prefix + "funds-lineage-high").toLong(),
                environment.getRequiredProperty(prefix + "funds-lineage-low").toLong(),
            ),
            markets.toSet(),
        )
    }

    @Bean
    fun orderQueryStore(
        namespace: OrderQueryNamespace,
        jdbc: NamedParameterJdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ): OrderQueryStore = PostgresOrderQueryStore(namespace, jdbc, transactionManager).also { it.initialize() }

    @Bean
    fun orderQueryUseCase(store: OrderQueryStore): OrderQueryUseCase = OrderQueryUseCase(store)
}
