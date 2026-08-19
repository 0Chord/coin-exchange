package com.exchange.core.order

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId

/**
 * 주문별 자산 예약을 저장하고 조회하는 저장소 경계.
 *
 * 구현체는 `(marketId, orderId)` 조합을 하나의 예약을 식별하는 키로 사용해야 한다.
 * 체결이나 취소처럼 기존 예약을 변경하는 흐름에서는 동시 갱신을 막기 위해
 * [findForUpdate]로 조회한 뒤 [update]해야 한다.
 *
 * 이 저장소는 사용자·자산별 총 hold를 저장하지 않는다. 총 hold는 BalanceStore가,
 * 그중 특정 주문이 책임지는 금액은 이 저장소가 관리한다.
 */
interface OrderReservationStore {
    /**
     * 새 주문 예약을 저장한다.
     *
     * @param reservation 처음 생성된 ACTIVE 주문 예약
     * @throws OrderReservationAlreadyExistsException 같은 market과 order의 예약이 이미 존재할 경우
     */
    fun create(reservation: OrderReservation)

    /**
     * 주문 예약을 읽기 전용으로 조회한다.
     *
     * @param marketId 주문이 속한 마켓
     * @param orderId 주문 식별자
     * @return 저장된 주문 예약. 존재하지 않으면 `null`
     */
    fun find(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation?

    /**
     * 주문 예약을 변경하기 위해 잠금을 획득하여 조회한다.
     *
     * 구현체가 관계형 DB를 사용한다면 일반적으로 `SELECT ... FOR UPDATE`에 대응한다.
     * 호출자는 트랜잭션 안에서 조회하고 [update]까지 완료해야 한다.
     *
     * @param marketId 주문이 속한 마켓
     * @param orderId 주문 식별자
     * @return 잠금이 적용된 주문 예약. 존재하지 않으면 `null`
     */
    fun findForUpdate(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation?

    /**
     * 체결이나 취소가 반영된 주문 예약을 저장한다.
     *
     * @param reservation 새 상태로 교체할 주문 예약
     * @throws OrderReservationNotFoundException 갱신할 예약이 존재하지 않을 경우
     */
    fun update(reservation: OrderReservation)
}

/**
 * 동일한 market과 order의 주문 예약을 중복 생성하려 할 때 발생하는 예외.
 *
 * @property marketId 중복 예약이 발생한 마켓
 * @property orderId 이미 예약이 존재하는 주문
 */
class OrderReservationAlreadyExistsException(
    val marketId: MarketId,
    val orderId: OrderId,
) : IllegalStateException(
    "order reservation already exists: " +
        "marketId=${marketId.value}, " +
        "orderId=${orderId.value}",
)

/**
 * 조회하거나 갱신할 주문 예약이 저장소에 없을 때 발생하는 예외.
 *
 * @property marketId 예약을 찾지 못한 마켓
 * @property orderId 예약을 찾지 못한 주문
 */
class OrderReservationNotFoundException(
    val marketId: MarketId,
    val orderId: OrderId,
) : IllegalStateException(
    "order reservation not found: " +
        "marketId=${marketId.value}, " +
        "orderId=${orderId.value}",
)
