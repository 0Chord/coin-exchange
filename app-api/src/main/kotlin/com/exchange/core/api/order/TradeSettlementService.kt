package com.exchange.core.api.order

import com.exchange.core.common.OrderId
import com.exchange.core.fee.LiquidityRole
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderFillSettlementPlan
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationNotFoundException
import com.exchange.core.order.OrderReservationStore
import org.springframework.transaction.annotation.Transactional

/**
 * 매칭 엔진이 만든 한 체결을 maker와 taker의 주문 예약 및 Balance에 반영하는 서비스.
 *
 * 양쪽 [OrderReservation]을 잠금 조회한 뒤 [OrderFillSettlementCalculator]로 각 주문의
 * 예약 감소액, hold 소비·반환액과 지급 자산을 계산한다. 두 예약 갱신과 양쪽 Balance
 * 변경은 하나의 트랜잭션으로 실행되므로 어느 한 작업이라도 실패하면 모두 rollback된다.
 *
 * 이 서비스는 주문을 매칭하거나 체결 가격을 결정하지 않는다. 이미 확정된 [TradeExecuted]
 * 결과를 영속 장부에 반영하는 역할만 담당한다.
 *
 * @property calculator 주문 방향별 정산 금액을 계산하는 도메인 계산기
 * @property balanceStore 체결에 따른 hold 소비·반환과 반대편 자산 지급 포트
 * @property reservationStore maker와 taker의 주문별 예약을 잠금·갱신하는 포트
 */
open class TradeSettlementService(
    private val calculator: OrderFillSettlementCalculator,
    private val balanceStore: BalanceStore,
    private val reservationStore: OrderReservationStore,
) {
    /**
     * 한 체결을 maker와 taker 양쪽 주문 예약 및 Balance에 원자적으로 반영한다.
     *
     * [TradeExecuted.side]는 taker 주문의 방향이다. taker 예약은 같은 방향이고 maker 예약은
     * 반대 방향인지 확인한 뒤, 두 주문의 정산 계획을 계산하고 순서대로 저장한다.
     *
     * @param market 체결이 발생한 마켓의 자산과 수량 scale 정보
     * @param trade 매칭 엔진이 확정한 maker/taker 주문, 체결 가격과 수량
     * @throws IllegalArgumentException 마켓, 주문 소유자 또는 주문 방향이 체결 정보와 다른 경우
     * @throws OrderReservationNotFoundException maker 또는 taker 주문 예약을 찾을 수 없는 경우
     * @throws com.exchange.core.ledger.BalanceNotFoundException 지급 또는 소비할 Balance가 없는 경우
     * @throws com.exchange.core.ledger.InsufficientHoldException 체결에 소비할 hold가 부족한 경우
     */
    @Transactional
    open fun settle(
        market: MarketDefinition,
        trade: TradeExecuted,
    ) {
        require(market.marketId == trade.marketId) {
            "trade market must match settlement market"
        }

        val makerReservation =
            findReservationForUpdate(
                market = market,
                orderId = trade.makerOrderId,
            )

        val takerReservation =
            findReservationForUpdate(
                market = market,
                orderId = trade.takerOrderId,
            )

        require(makerReservation.userId == trade.makerUserId) {
            "maker reservation owner must match trade maker"
        }

        require(takerReservation.userId == trade.takerUserId) {
            "taker reservation owner must match trade taker"
        }

        require(takerReservation.side == trade.side) {
            "taker reservation side must match trade side"
        }

        require(makerReservation.side != trade.side) {
            "maker and taker reservations must have opposite sides"
        }

        val makerPlan =
            calculator.calculate(
                market = market,
                reservation = makerReservation,
                executionPrice = trade.price,
                filledQuantity = trade.quantity,
                liquidityRole = LiquidityRole.MAKER,
            )

        val takerPlan =
            calculator.calculate(
                market = market,
                reservation = takerReservation,
                executionPrice = trade.price,
                filledQuantity = trade.quantity,
                liquidityRole = LiquidityRole.TAKER,
            )

        applySettlement(
            reservation = makerReservation,
            plan = makerPlan,
        )

        applySettlement(
            reservation = takerReservation,
            plan = takerPlan,
        )
    }

    /**
     * 갱신 중 다른 체결이나 취소가 같은 예약을 변경하지 못하도록 row lock과 함께
     * 조회한다.
     *
     * @param market 주문 예약을 찾을 마켓
     * @param orderId 잠금 조회할 주문 식별자
     * @return 잠금이 적용된 주문 예약
     * @throws OrderReservationNotFoundException 주문 예약이 존재하지 않는 경우
     */
    private fun findReservationForUpdate(
        market: MarketDefinition,
        orderId: OrderId,
    ): OrderReservation =
        reservationStore.findForUpdate(
            marketId = market.marketId,
            orderId = orderId,
        ) ?: throw OrderReservationNotFoundException(
            marketId = market.marketId,
            orderId = orderId,
        )

    /**
     * 한 주문의 정산 계획을 주문 예약과 사용자 Balance에 적용한다.
     *
     * 주문 예약을 저장하고, 예약 자산 hold에서 실제 사용액을 소비하며, BUY 가격 개선처럼
     * 사용하지 않은 금액은 available로 반환한다. 마지막으로 체결로 받은 반대편 자산을
     * available에 지급한다.
     *
     * @param reservation 체결 직전 주문 예약
     * @param plan [calculator]가 계산한 주문별 정산 계획
     */
    private fun applySettlement(
        reservation: OrderReservation,
        plan: OrderFillSettlementPlan,
    ) {
        reservationStore.update(plan.updatedReservation)

        balanceStore.consumeHold(
            userId = reservation.userId,
            assetId = reservation.assetId,
            amount = plan.holdAmountToConsume,
        )

        if (!plan.holdAmountToRelease.isZero()) {
            balanceStore.release(
                userId = reservation.userId,
                assetId = reservation.assetId,
                amount = plan.holdAmountToRelease,
            )
        }

        balanceStore.credit(
            userId = reservation.userId,
            assetId = plan.creditAssetId,
            amount = plan.creditAmount,
        )
    }
}
