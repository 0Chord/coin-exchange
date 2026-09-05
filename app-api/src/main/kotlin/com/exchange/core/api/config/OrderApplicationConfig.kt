package com.exchange.core.api.config

import com.exchange.core.api.matching.MatchingApplicationService
import com.exchange.core.api.order.OrderCancellationService
import com.exchange.core.api.order.OrderFundingService
import com.exchange.core.api.order.OrderReservationReleaseService
import com.exchange.core.api.order.OrderSubmissionService
import com.exchange.core.api.order.TradeSettlementService
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 주문 접수와 취소 서비스를 명시적 Bean으로 조립한다.
 *
 * 자금 예약·반환·정산 서비스와 매칭 서비스, 주문에 적용할 마켓·수수료 정책 Bean이 필요하다.
 * 마켓과 정책 자체는 이 구성에서 만들지 않으며, E2E에서는 테스트 구성으로 제공한다.
 */
@Configuration
class OrderApplicationConfig {
    /**
     * 주문 접수부터 예약·매칭·체결 정산까지 연결하는 서비스를 등록한다.
     *
     * @param fundingService 매칭 전 주문 예약과 잔고 hold를 함께 만드는 서비스
     * @param matchingService 마켓별 작업 스레드에서 명령과 전후 작업을 실행하는 서비스
     * @param tradeSettlementService 한 체결의 양쪽 예약·잔고와 원장을 함께 반영하는 서비스
     * @param market 주문을 접수할 단일 마켓의 자산과 수량 단위 정보
     * @param feePolicySnapshot 접수하는 주문에 저장할 maker/taker 수수료 정책
     * @return 주문 접수 application service
     */
    @Bean
    fun orderSubmissionService(
        fundingService: OrderFundingService,
        matchingService: MatchingApplicationService,
        tradeSettlementService: TradeSettlementService,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
    ): OrderSubmissionService =
        OrderSubmissionService(
            fundingService = fundingService,
            matchingService = matchingService,
            tradeSettlementService = tradeSettlementService,
            market = market,
            feePolicySnapshot = feePolicySnapshot,
        )

    /** 매칭 취소 성공 후 남은 거래 대금과 수수료 예약금을 반환하는 서비스를 등록한다. */
    @Bean
    fun orderCancellationService(
        matchingService: MatchingApplicationService,
        reservationReleaseService: OrderReservationReleaseService,
    ): OrderCancellationService =
        OrderCancellationService(
            matchingService = matchingService,
            reservationReleaseService = reservationReleaseService,
        )
}
