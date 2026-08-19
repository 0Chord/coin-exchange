package com.exchange.core.api.order

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationNotFoundException
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderReservationStore
import org.springframework.transaction.annotation.Transactional

/**
 * 주문 취소 후 남은 주문별 예약과 사용자 Balance hold를 함께 해제하는 application service.
 *
 * 같은 주문에 대한 체결 또는 중복 취소와 경쟁하지 않도록 reservation row를 `FOR UPDATE`로
 * 잠근다. 주문 예약의 남은 금액을 0으로 저장하고 같은 금액을 `hold -> available`로 옮기는
 * 작업은 하나의 Spring 트랜잭션에서 처리된다.
 *
 * @property balanceStore 실제 사용자 잔고의 hold를 반환하는 포트
 * @property reservationStore 주문별 남은 예약 금액을 잠금·갱신하는 포트
 */
open class OrderReservationReleaseService(
    private val balanceStore: BalanceStore,
    private val reservationStore: OrderReservationStore,
) {
    /**
     * ACTIVE 주문의 남은 예약 금액 전부를 해제한다.
     *
     * 이미 RELEASED라면 Balance를 다시 증가시키지 않고 저장된 값을 그대로 반환하여
     * 멱등성을 보장한다. SETTLED 주문은 반환할 예약이 없으므로 domain
     * [OrderReservation.release] 검증에 따라 실패한다.
     *
     * @param marketId 취소된 주문이 속한 마켓
     * @param orderId 예약을 해제할 주문
     * @return remainingAmount가 0이고 RELEASED인 주문 예약
     * @throws OrderReservationNotFoundException 주문 예약이 없는 경우
     * @throws IllegalStateException SETTLED 예약을 해제하려는 경우
     * @throws com.exchange.core.ledger.InsufficientHoldException 실제 hold가 예약 잔액보다 적은 경우
     */
    @Transactional
    open fun release(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation {
        // 잠금 조회부터 update와 Balance 반환까지 같은 트랜잭션 안에 유지한다.
        val reservation =
            reservationStore.findForUpdate(
                marketId = marketId,
                orderId = orderId,
            ) ?: throw OrderReservationNotFoundException(
                marketId = marketId,
                orderId = orderId,
            )

        if (reservation.status == OrderReservationStatus.RELEASED) {
            // 재시도 요청에서 동일 금액을 두 번 available로 돌려놓지 않는다.
            return reservation
        }

        // domain 객체를 0으로 바꾸기 전에 실제 Balance에 돌려줄 기존 잔액을 보관한다.
        val amountToRelease = reservation.remainingAmount
        val released = reservation.release()

        reservationStore.update(released)

        // 실패하면 위 reservation update도 같은 트랜잭션에서 rollback된다.
        balanceStore.release(
            userId = reservation.userId,
            assetId = reservation.assetId,
            amount = amountToRelease,
        )

        return released
    }
}
