package com.exchange.core.api.order

import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStore
import com.exchange.core.order.Side
import org.springframework.transaction.annotation.Transactional

/**
 * 주문을 matching engine에 제출하기 전에 자금을 예약하는 application service.
 *
 * 처리 순서는 다음과 같다.
 * 1. [OrderReservationCalculator]로 BUY/SELL에 맞는 자산과 금액을 계산한다.
 * 2. 특정 주문이 책임지는 [OrderReservation]을 만든다.
 * 3. 주문별 reservation row를 저장한다.
 * 4. 사용자·자산 Balance의 available을 hold로 이동한다.
 *
 * 3번 뒤 4번이 실패해도 [reserve] 전체가 같은 Spring 트랜잭션이므로 reservation insert도
 * rollback된다. 따라서 주문 예약만 있고 실제 hold는 없는 불일치를 남기지 않는다.
 *
 * @property calculator 주문 방향, 마켓 scale과 수수료 정책으로 예약 요구사항을 계산하는 객체
 * @property balanceStore 사용자·자산별 available/hold 변경 포트
 * @property reservationStore 주문별 예약 영속성 포트
 */
open class OrderFundingService(
    private val calculator: OrderReservationCalculator,
    private val balanceStore: BalanceStore,
    private val reservationStore: OrderReservationStore,
) {
    /**
     * 주문 하나의 필요 자금을 계산하고 reservation과 Balance hold를 함께 생성한다.
     *
     * BUY는 quote 자산에서 지정가 기준 거래 대금과 최대 수수료를 함께 예약한다.
     * SELL은 base 자산을 주문 수량만큼 예약하고 수수료는 이후 체결 대금에서 차감한다.
     * 이 메서드가 정상 반환된 뒤에만 matching engine에 주문을 제출해야 한다.
     *
     * @param market base/quote 자산과 base 수량 scale 정보
     * @param orderId 자금을 예약할 주문 식별자
     * @param userId 주문 소유자이자 Balance 소유자
     * @param side BUY 또는 SELL 방향
     * @param limitPrice 주문 지정가
     * @param quantity base 자산 최소 단위 기준 주문 수량
     * @param feePolicySnapshot 주문 접수 시점에 확정한 maker/taker 수수료 정책
     * @return 저장과 hold가 끝난 ACTIVE 주문 예약
     * @throws com.exchange.core.order.OrderReservationAlreadyExistsException 동일 주문 예약이
     * 이미 존재하는 경우
     * @throws com.exchange.core.ledger.BalanceNotFoundException 사용자·자산 잔고가 없는 경우
     * @throws com.exchange.core.ledger.InsufficientBalanceException available이 부족한 경우
     */
    @Transactional
    open fun reserve(
        market: MarketDefinition,
        orderId: OrderId,
        userId: UserId,
        side: Side,
        limitPrice: Price,
        quantity: Quantity,
        feePolicySnapshot: TradingFeePolicySnapshot,
    ): OrderReservation {
        // requirement는 side에 따른 hold 자산과 거래·수수료 예약액을 함께 표현한다.
        val requirement =
            calculator.calculate(
                market = market,
                side = side,
                price = limitPrice,
                quantity = quantity,
                feePolicySnapshot = feePolicySnapshot,
            )

        // reservation은 전체 Balance hold 중 이 주문이 책임지는 몫을 별도로 추적한다.
        val reservation =
            OrderReservation.create(
                marketId = market.marketId,
                orderId = orderId,
                userId = userId,
                side = side,
                limitPrice = limitPrice,
                quantity = quantity,
                requirement = requirement,
                feePolicySnapshot = feePolicySnapshot,
            )

        reservationStore.create(reservation)

        // 거래 예약액과 수수료 예약액의 합을 hold한다. 이 호출이 실패하면 위 insert도
        // 같은 트랜잭션에서 rollback된다.
        balanceStore.reserve(
            userId = userId,
            assetId = requirement.assetId,
            amount = requirement.totalReserveAmount,
        )

        return reservation
    }
}
