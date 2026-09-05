package com.exchange.core.api.order

import com.exchange.core.api.matching.MatchingApplicationService
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.TimeInForce

/**
 * 새 주문의 검증, 자금 예약, 매칭과 체결 정산을 조율한다.
 *
 * 같은 마켓 작업 스레드에서 자금 예약 → 매칭 → 이벤트 발행·저장 → 체결별 정산 순서로
 * 실행한다. 각 체결의 예약·잔고 변경과 수수료 원장 기록은 [TradeSettlementService]가 맡는다.
 * 현재는 하나의 마켓과 수수료 정책 Bean으로 LIMIT/GTC 주문만 접수한다.
 *
 * 전체 주문 흐름이 하나의 DB 트랜잭션인 것은 아니다. 예약, 이벤트 저장과 체결별 정산은
 * 별도 경계이며, 뒤쪽 정산 실패가 이미 커밋된 작업이나 매칭 엔진 상태를 되돌리지는 않는다.
 *
 * @property fundingService 주문 예약과 잔고 hold 변경을 담당하는 서비스
 * @property matchingService 마켓 작업 스레드에 주문을 전달하는 서비스
 * @property tradeSettlementService 각 체결의 양쪽 예약·잔고와 수수료 원장을 함께 반영하는 서비스
 * @property market 주문을 접수할 마켓의 자산과 수량 단위 정보
 * @property feePolicySnapshot 이번 주문에 적용할 수수료 정책
 */
class OrderSubmissionService(
    private val fundingService: OrderFundingService,
    private val matchingService: MatchingApplicationService,
    private val tradeSettlementService: TradeSettlementService,
    private val market: MarketDefinition,
    private val feePolicySnapshot: TradingFeePolicySnapshot,
) {
    /**
     * 지원 여부를 검사하고 자금 예약이 성공한 주문만 매칭한 뒤 발생한 체결들을 정산한다.
     *
     * 예약 실패 시 엔진은 실행하지 않는다. 매칭 이벤트 발행·저장과 체결 정산까지 끝나야
     * 정상 결과를 반환한다. 예약 후 실패하거나 응답 대기 시간이 초과되어도 자금을 임의
     * 반환하지 않으며, 엔진 처리 여부를 확인한 뒤 복구하는 기능은 별도 구현이 필요하다.
     *
     * @param command 주문 마켓, 소유자, 방향, 지정가와 수량을 담은 새 주문 명령
     * @return 이벤트 발행·저장과 체결별 정산까지 끝난 매칭 이벤트 목록
     * @throws IllegalArgumentException 구성된 마켓과 다르거나 LIMIT/GTC 주문이 아닌 경우
     */
    fun submit(command: SubmitOrderCommand): List<MatchingEvent> {
        require(command.marketId == market.marketId) {
            "order market must match configured market"
        }

        require(command.orderType == OrderType.LIMIT) {
            "only LIMIT order is supported"
        }

        require(command.timeInForce == TimeInForce.GTC) {
            "only GTC order is supported"
        }

        return matchingService.process(
            command = command,
            beforeMatching = {
                fundingService.reserve(
                    market = market,
                    orderId = command.orderId,
                    userId = command.userId,
                    side = command.side,
                    limitPrice = command.price,
                    quantity = command.quantity,
                    feePolicySnapshot = feePolicySnapshot,
                )
            },
            afterMatching = { events ->
                events.forEach { event ->
                    if (event is TradeExecuted) {
                        tradeSettlementService.settle(
                            market = market,
                            trade = event,
                        )
                    }
                }
            },
        )
    }
}
