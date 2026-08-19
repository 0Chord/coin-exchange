package com.exchange.core.api.config

import com.exchange.core.api.ledger.persistence.PostgresBalanceStore
import com.exchange.core.api.order.OrderFundingService
import com.exchange.core.api.order.OrderReservationReleaseService
import com.exchange.core.api.order.persistence.PostgresOrderReservationStore
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * PostgreSQL 기반 잔고와 주문 예약 기능을 조립하는 Spring 구성.
 *
 * `exchange.ledger.persistence.enabled=true`일 때만 활성화된다. BalanceStore와
 * OrderReservationStore가 같은 DataSource와 Spring 트랜잭션을 사용하므로 주문 예약 생성과
 * 잔고 hold 변경, 예약 해제와 hold 반환을 각각 하나의 트랜잭션으로 묶을 수 있다.
 */
@Configuration
@ConditionalOnProperty(
    name = ["exchange.ledger.persistence.enabled"],
    havingValue = "true",
)
class LedgerPersistenceConfig {
    /**
     * `balance_projection`을 조건부 UPDATE로 변경하는 잔고 저장소를 등록한다.
     *
     * @param jdbcTemplate Spring이 구성한 PostgreSQL named-parameter template
     * @return [BalanceStore] 포트의 PostgreSQL 구현체
     */
    @Bean
    fun balanceStore(
        jdbcTemplate: NamedParameterJdbcTemplate,
    ): BalanceStore =
        PostgresBalanceStore(jdbcTemplate)

    /**
     * `order_reservations` 테이블을 사용하는 주문별 예약 저장소를 등록한다.
     *
     * @param jdbcTemplate Spring이 구성한 PostgreSQL named-parameter template
     * @return [OrderReservationStore] 포트의 PostgreSQL 구현체
     */
    @Bean
    fun orderReservationStore(
        jdbcTemplate: NamedParameterJdbcTemplate,
    ): OrderReservationStore =
        PostgresOrderReservationStore(jdbcTemplate)

    /**
     * 주문 접수 전에 필요 자금을 계산하고 Balance hold와 Reservation을 함께 만드는 서비스를
     * 등록한다.
     *
     * @param balanceStore 사용자·자산별 잔고 변경 포트
     * @param orderReservationStore 주문별 예약 저장 포트
     * @return 주문 자금 예약 application service
     */
    @Bean
    fun orderFundingService(
        balanceStore: BalanceStore,
        orderReservationStore: OrderReservationStore,
    ): OrderFundingService =
        OrderFundingService(
            calculator = OrderReservationCalculator(),
            balanceStore = balanceStore,
            reservationStore = orderReservationStore,
        )

    /**
     * 취소된 주문의 남은 Reservation과 Balance hold를 함께 해제하는 서비스를 등록한다.
     *
     * @param balanceStore 사용자·자산별 잔고 변경 포트
     * @param orderReservationStore 주문별 예약 저장 포트
     * @return 주문 예약 해제 application service
     */
    @Bean
    fun orderReservationReleaseService(
        balanceStore: BalanceStore,
        orderReservationStore: OrderReservationStore,
    ): OrderReservationReleaseService =
        OrderReservationReleaseService(
            balanceStore = balanceStore,
            reservationStore = orderReservationStore,
        )
}
