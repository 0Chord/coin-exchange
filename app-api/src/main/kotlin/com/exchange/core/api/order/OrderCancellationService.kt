package com.exchange.core.api.order

import com.exchange.core.api.matching.MatchingApplicationService
import com.exchange.core.matching.CancelOrderCommand
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.OrderCancelled

/**
 * 매칭 엔진에서 주문 취소가 성공하면 남은 거래 대금과 수수료 예약금을 반환한다.
 *
 * 취소 이벤트 저장 후 같은 마켓 작업 스레드에서 예약금을 반환하므로 다음 명령은 반환이
 * 끝난 뒤 실행된다. 예약·잔고 변경의 트랜잭션은 [OrderReservationReleaseService]가
 * 담당하며, 반환 실패가 이미 완료된 엔진 취소나 이벤트 저장까지 되돌리지는 않는다.
 *
 * @property matchingService 마켓별 취소 명령 처리와 이벤트 발행을 담당하는 서비스
 * @property reservationReleaseService 남은 주문 예약과 사용자 잔고 hold를 함께 해제하는 서비스
 */
class OrderCancellationService(
    private val matchingService: MatchingApplicationService,
    private val reservationReleaseService: OrderReservationReleaseService,
) {
    /**
     * 취소 성공 이벤트에 대해서만 예약금을 반환하고 매칭 결과를 그대로 돌려준다.
     * 주문이 없거나 소유자가 달라 취소가 거절되면 예약과 잔고는 변경하지 않는다.
     */
    fun cancel(command: CancelOrderCommand): List<MatchingEvent> {
        return matchingService.process(
            command = command,
            afterMatching = { events ->
                for (event in events) {
                    if (event is OrderCancelled) {
                        reservationReleaseService.release(
                            marketId = event.marketId,
                            orderId = event.orderId,
                        )
                    }
                }
            },
        )
    }
}
