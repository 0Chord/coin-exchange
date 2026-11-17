package com.exchange.core.api.config

import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.OrderQueryUseCase
import com.exchange.core.api.trading.application.port.OrderQueryFeedStore
import com.exchange.core.api.trading.application.port.OrderQueryStore
import com.exchange.core.api.trading.infrastructure.aeron.AeronGatewaySettings
import com.exchange.core.api.trading.infrastructure.aeron.OrderQueryFeedWorker
import com.exchange.core.api.trading.infrastructure.persistence.PostgresOrderQueryFeedStore
import com.exchange.core.api.trading.infrastructure.persistence.PostgresOrderQueryStore
import com.exchange.core.trading.ClusterLineage
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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
    ): OrderQueryStore {
        val store = PostgresOrderQueryStore(namespace, jdbc, transactionManager)
        store.initialize()
        // 기존 DB가 READY여도 이 WAS의 원본 재검증 전에는 조회를 열지 않는다.
        store.suspendSourceReads()
        return store
    }

    @Bean
    fun orderQueryUseCase(store: OrderQueryStore): OrderQueryUseCase = OrderQueryUseCase(store)

    @Bean
    fun orderQueryFeedStore(
        store: OrderQueryStore,
        jdbc: NamedParameterJdbcTemplate,
    ): OrderQueryFeedStore = PostgresOrderQueryFeedStore(store as PostgresOrderQueryStore, jdbc)

    /** 원본 보존 capability와 같은 consumer 설정이 공급되면 동일 WAS에서 반영을 시작한다. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "exchange.order-query", name = ["consumer-id"])
    internal fun orderQueryFeedWorker(
        feed: OrderQueryFeedStore,
        namespace: OrderQueryNamespace,
        environment: Environment,
    ): OrderQueryFeedWorker {
        val matchingIngressEntries = environment.getRequiredProperty("exchange.order-query.matching-ingress").split(';')
        val matchingIngressByMarket =
            matchingIngressEntries.associate { entry ->
                require(entry.count { character -> character == '@' } == 1) { "matching query address must be MARKET@ingress" }
                val market = entry.substringBefore('@')
                val ingress = entry.substringAfter('@')
                market to ingress
            }
        require(matchingIngressByMarket.size == matchingIngressEntries.size) { "duplicate query market" }
        return OrderQueryFeedWorker(
            store = feed,
            namespace = namespace,
            consumerId = environment.getRequiredProperty("exchange.order-query.consumer-id"),
            settings = AeronGatewaySettings.from(boundedGatewayEnvironment(environment)),
            matchingIngress = matchingIngressByMarket,
        )
    }
}
