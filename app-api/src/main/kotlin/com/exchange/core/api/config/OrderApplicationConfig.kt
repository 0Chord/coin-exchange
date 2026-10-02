package com.exchange.core.api.config

import com.exchange.core.api.matching.application.MatchingCoordinator
import com.exchange.core.api.order.application.CancelOrderUseCase
import com.exchange.core.api.order.application.OrderFundingService
import com.exchange.core.api.order.application.OrderReservationReleaseService
import com.exchange.core.api.order.application.SubmitOrderUseCase
import com.exchange.core.api.order.application.TradeSettlementService
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 주문 제출·취소 유즈케이스를 명시적 Bean으로 조립한다.
 *
 * 자금 예약·반환·정산 서비스와 매칭 조율자, 주문에 적용할 마켓·수수료 정책 Bean이 필요하다.
 * 마켓과 정책 자체는 이 구성에서 만들지 않으며, E2E에서는 테스트 구성으로 제공한다.
 */
@Configuration
class OrderApplicationConfig {
    /**
     * 주문 접수부터 예약·매칭·체결 정산까지 연결하는 유즈케이스를 등록한다.
     *
     * @param fundingService 매칭 전 주문 예약과 잔고 hold를 함께 만드는 서비스
     * @param matchingCoordinator 마켓별 작업 스레드에서 명령과 전후 작업을 연결하는 조율자
     * @param tradeSettlementService 한 체결의 양쪽 예약·잔고와 원장을 함께 반영하는 서비스
     * @param market 주문을 접수할 단일 마켓의 자산과 수량 단위 정보
     * @param feePolicySnapshot 접수하는 주문에 저장할 maker/taker 수수료 정책
     * @return 주문 제출 유즈케이스
     */
    @Bean
    fun submitOrderUseCase(
        fundingService: OrderFundingService,
        matchingCoordinator: MatchingCoordinator,
        tradeSettlementService: TradeSettlementService,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
    ): SubmitOrderUseCase =
        SubmitOrderUseCase(
            fundingService = fundingService,
            matchingCoordinator = matchingCoordinator,
            tradeSettlementService = tradeSettlementService,
            market = market,
            feePolicySnapshot = feePolicySnapshot,
        )

    /** 매칭 취소 성공 후 남은 거래 대금과 수수료 예약금을 반환하는 취소 유즈케이스를 등록한다. */
    @Bean
    fun cancelOrderUseCase(
        matchingCoordinator: MatchingCoordinator,
        reservationReleaseService: OrderReservationReleaseService,
    ): CancelOrderUseCase =
        CancelOrderUseCase(
            matchingCoordinator = matchingCoordinator,
            reservationReleaseService = reservationReleaseService,
        )
}
