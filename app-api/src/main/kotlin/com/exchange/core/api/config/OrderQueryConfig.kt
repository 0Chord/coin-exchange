package com.exchange.core.api.config

import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.OrderQueryUseCase
import com.exchange.core.api.trading.application.port.OrderQueryStore
import com.exchange.core.api.trading.infrastructure.persistence.PostgresOrderQueryStore
import com.exchange.core.trading.ClusterLineage
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/** 조회 DB는 선택 실행에서만 열며 금융 저장소와 자동 설정을 공유하지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("aeron-bounded-gateway")
@ConditionalOnProperty(name = ["exchange.order-query.enabled"], havingValue = "true")
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

    @Bean(destroyMethod = "close")
    @DependsOn("orderQueryNamespace")
    fun orderQueryDataSource(environment: Environment): HikariDataSource {
        val prefix = "exchange.order-query."
        val url = environment.getRequiredProperty(prefix + "jdbc-url")
        val user = environment.getRequiredProperty(prefix + "username")
        require(url.startsWith("jdbc:postgresql:") && user.isNotBlank()) { "invalid order query database settings" }
        val settings = HikariConfig()
        settings.jdbcUrl = url
        settings.username = user
        settings.password = environment.getRequiredProperty(prefix + "password")
        settings.poolName = "order-query"
        settings.maximumPoolSize = 2
        settings.minimumIdle = 0
        settings.connectionTimeout = 5_000
        settings.initializationFailTimeout = 5_000
        return HikariDataSource(settings)
    }

    @Bean(initMethod = "migrate")
    fun orderQueryFlyway(
        @Qualifier("orderQueryDataSource") dataSource: DataSource,
    ): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .defaultSchema("order_query")
            .schemas("order_query")
            .table("flyway_order_query_history")
            .locations("classpath:db/order-query")
            .load()

    @Bean
    fun orderQueryJdbcTemplate(
        @Qualifier("orderQueryDataSource") dataSource: DataSource,
    ): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(dataSource)

    @Bean
    fun orderQueryTransactionManager(
        @Qualifier("orderQueryDataSource") dataSource: DataSource,
    ): PlatformTransactionManager = DataSourceTransactionManager(dataSource)

    @Bean
    @DependsOn("orderQueryFlyway")
    fun orderQueryStore(
        @Qualifier("orderQueryDataSource") dataSource: DataSource,
        namespace: OrderQueryNamespace,
        @Qualifier("orderQueryJdbcTemplate") jdbc: NamedParameterJdbcTemplate,
        @Qualifier("orderQueryTransactionManager") transactionManager: PlatformTransactionManager,
    ): OrderQueryStore =
        PostgresOrderQueryStore(dataSource, namespace, jdbc = jdbc, transactionManager = transactionManager).also { it.initialize() }

    @Bean
    fun orderQueryUseCase(store: OrderQueryStore): OrderQueryUseCase = OrderQueryUseCase(store)
}
