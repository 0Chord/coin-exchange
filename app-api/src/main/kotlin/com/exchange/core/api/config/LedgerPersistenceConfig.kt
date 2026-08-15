package com.exchange.core.api.config

import com.exchange.core.api.ledger.persistence.PostgresBalanceStore
import com.exchange.core.api.order.persistence.PostgresOrderReservationStore
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.order.OrderReservationStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@Configuration
@ConditionalOnProperty(
    name = ["exchange.ledger.persistence.enabled"],
    havingValue = "true",
)
class LedgerPersistenceConfig {
    @Bean
    fun balanceStore(
        jdbcTemplate: NamedParameterJdbcTemplate,
    ): BalanceStore =
        PostgresBalanceStore(jdbcTemplate)

    @Bean
    fun orderReservationStore(
        jdbcTemplate: NamedParameterJdbcTemplate,
    ): OrderReservationStore =
        PostgresOrderReservationStore(jdbcTemplate)
}
