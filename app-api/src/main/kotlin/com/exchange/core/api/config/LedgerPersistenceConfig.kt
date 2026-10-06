package com.exchange.core.api.config

import com.exchange.core.api.ledger.application.PrepareDevelopmentBalanceUseCase
import com.exchange.core.api.ledger.application.ReconcileLedgerUseCase
import com.exchange.core.api.ledger.infrastructure.persistence.PostgresBalanceStore
import com.exchange.core.api.ledger.infrastructure.persistence.PostgresDevelopmentBalanceStore
import com.exchange.core.api.ledger.infrastructure.persistence.PostgresLedgerReconciliationStore
import com.exchange.core.api.ledger.infrastructure.persistence.PostgresLedgerTransactionStore
import com.exchange.core.api.order.application.OrderFundingService
import com.exchange.core.api.order.application.OrderReservationReleaseService
import com.exchange.core.api.order.application.TradeSettlementService
import com.exchange.core.api.order.infrastructure.persistence.PostgresOrderReservationStore
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.ledger.DevelopmentBalanceStore
import com.exchange.core.ledger.LedgerReconciliationStore
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * PostgreSQL 기반 잔고, 주문 예약과 원장 저장 기능을 조립하는 Spring 구성.
 *
 * `exchange.ledger.persistence.enabled=true`일 때만 활성화된다. [BalanceStore],
 * [OrderReservationStore]와 [LedgerTransactionStore]가 같은 DataSource와 Spring 트랜잭션을
 * 사용한다. 주문 예약 생성과 hold 변경, 예약 해제와 hold 반환을 각각 원자적으로 처리하며,
 * 체결 정산에서는 양쪽 예약·잔고 변경과 수수료를 포함한 원장 기록을 함께 커밋하거나 롤백한다.
 * 주문 예약 생성은 RESERVE 원장까지 함께 기록한다. 취소 해제도 RELEASE 원장과 함께 기록한다.
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
    fun balanceStore(jdbcTemplate: NamedParameterJdbcTemplate): BalanceStore = PostgresBalanceStore(jdbcTemplate)

    /**
     * `order_reservations` 테이블을 사용하는 주문별 예약 저장소를 등록한다.
     *
     * @param jdbcTemplate Spring이 구성한 PostgreSQL named-parameter template
     * @return [OrderReservationStore] 포트의 PostgreSQL 구현체
     */
    @Bean
    fun orderReservationStore(jdbcTemplate: NamedParameterJdbcTemplate): OrderReservationStore = PostgresOrderReservationStore(jdbcTemplate)

    /**
     * 주문 접수 전에 필요 자금을 계산하고 잔고 hold·예약·RESERVE 원장을 함께 만드는 서비스를
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
        ledgerTransactionStore: LedgerTransactionStore,
    ): OrderFundingService =
        OrderFundingService(
            calculator =
                OrderReservationCalculator(
                    buyOrderFundingQuoteCalculator =
                        BuyOrderFundingQuoteCalculator(
                            feeReserveCalculator = TradingFeeReserveCalculator(),
                        ),
                ),
            balanceStore = balanceStore,
            reservationStore = orderReservationStore,
            ledgerTransactionStore = ledgerTransactionStore,
        )

    /**
     * 취소된 주문의 남은 예약·잔고와 반환 원장을 함께 저장하는 서비스를 등록한다.
     *
     * @param balanceStore 사용자·자산별 잔고 변경 포트
     * @param orderReservationStore 주문별 예약 저장 포트
     * @param ledgerTransactionStore 반환 원장의 조회·추가 포트
     * @return 주문 예약 해제 application service
     */
    @Bean
    fun orderReservationReleaseService(
        balanceStore: BalanceStore,
        orderReservationStore: OrderReservationStore,
        ledgerTransactionStore: LedgerTransactionStore,
    ): OrderReservationReleaseService =
        OrderReservationReleaseService(
            balanceStore = balanceStore,
            reservationStore = orderReservationStore,
            ledgerTransactionStore = ledgerTransactionStore,
        )

    /**
     * 한 체결의 양쪽 예약·잔고 변경과 원장 기록을 함께 실행하는 서비스를 등록한다.
     *
     * @param balanceStore 사용자·자산별 체결 잔고 변경 포트
     * @param orderReservationStore maker와 taker의 주문별 예약 저장 포트
     * @param ledgerTransactionStore 양쪽 자산 이동과 거래소 수수료 수익을 기록하는 원장 저장 포트
     * @return 양쪽 정산과 원장 저장을 하나의 트랜잭션으로 실행하는 application service
     */
    @Bean
    fun tradeSettlementService(
        balanceStore: BalanceStore,
        orderReservationStore: OrderReservationStore,
        ledgerTransactionStore: LedgerTransactionStore,
    ): TradeSettlementService =
        TradeSettlementService(
            calculator =
                OrderFillSettlementCalculator(
                    tradingFeeCalculator = TradingFeeCalculator(),
                    tradingFeeReserveCalculator = TradingFeeReserveCalculator(),
                ),
            balanceStore = balanceStore,
            reservationStore = orderReservationStore,
            ledgerTransactionStore = ledgerTransactionStore,
        )

    /**
     * 원장 거래와 항목을 함께 추가하는 PostgreSQL 저장소를 등록한다.
     *
     * @param jdbcTemplate 기존 잔고 및 주문 예약 저장소와 같은 DataSource를 사용하는 template
     * @return [LedgerTransactionStore] 포트의 PostgreSQL 구현체
     */
    @Bean
    fun ledgerTransactionStore(jdbcTemplate: NamedParameterJdbcTemplate): LedgerTransactionStore =
        PostgresLedgerTransactionStore(jdbcTemplate)

    /** 명시적인 개발용 개시 호출에만 원장·잔고의 원자 저장을 제공한다. */
    @Bean
    fun developmentBalanceStore(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionManager: PlatformTransactionManager,
        ledgerTransactionStore: LedgerTransactionStore,
        balanceStore: BalanceStore,
    ): DevelopmentBalanceStore =
        PostgresDevelopmentBalanceStore(
            jdbcTemplate,
            transactionManager,
            ledgerTransactionStore,
            balanceStore,
        )

    @Bean
    fun prepareDevelopmentBalanceUseCase(developmentBalanceStore: DevelopmentBalanceStore) =
        PrepareDevelopmentBalanceUseCase(developmentBalanceStore)

    /** 거래 쓰기 경로와 분리된 읽기 전용 대조 저장소를 조립한다. */
    @Bean
    fun ledgerReconciliationStore(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ): LedgerReconciliationStore = PostgresLedgerReconciliationStore(jdbcTemplate, transactionManager)

    @Bean
    fun reconcileLedgerUseCase(ledgerReconciliationStore: LedgerReconciliationStore) = ReconcileLedgerUseCase(ledgerReconciliationStore)
}
