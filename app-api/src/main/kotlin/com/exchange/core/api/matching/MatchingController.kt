package com.exchange.core.api.matching

import com.exchange.core.api.order.OrderCancellationService
import com.exchange.core.api.order.OrderSubmissionService
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.CancelOrderCommand
import com.exchange.core.matching.SubmitOrderCommand
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Matching core를 HTTP로 호출하는 API.
 *
 * Controller는 요청/응답 변환만 맡는다. 새 주문은 [OrderSubmissionService]를 통해
 * 자금 예약·매칭·체결 정산을 수행하며, 취소는 [OrderCancellationService]를 통해
 * 매칭 엔진의 주문 제거와 남은 거래 대금·수수료 예약금 반환을 수행한다.
 * 모든 endpoint는 `/api/markets/{marketId}/orders` 아래에 있으며 URL의 marketId를
 * command에 명시적으로 넣어 서로 다른 마켓의 book이 섞이지 않게 한다.
 *
 * @property orderSubmissionService 새 주문의 검증, 자금 예약·매칭·정산을 담당하는 서비스
 * @property orderCancellationService 주문 취소와 남은 예약금 반환을 담당하는 서비스
 */
@RestController
@RequestMapping("/api/markets/{marketId}/orders")
class MatchingController(
    private val orderSubmissionService: OrderSubmissionService,
    private val orderCancellationService: OrderCancellationService,
) {
    /**
     * 주문을 command로 변환하고 자금 예약부터 체결 정산까지 수행하는 서비스에 전달한다.
     *
     * 문자열과 Long 입력을 value class로 감싸는 시점에 빈 id, 0 이하 가격, 음수 수량이
     * 검증된다. command 처리 결과 event는 입력 순서를 유지한 API DTO 목록으로 변환된다.
     *
     * @param marketId URL path에 들어온 주문 마켓 식별자
     * @param request 주문 소유자, 방향, 가격과 수량을 담은 JSON body
     * @return 한 주문 처리에서 발생한 체결 및 book 진입 event 목록
     */
    @PostMapping
    fun submitOrder(
        @PathVariable marketId: String,
        @RequestBody request: SubmitOrderRequest,
    ): MatchingResponse {
        val command =
            SubmitOrderCommand(
                marketId = MarketId(marketId),
                orderId = OrderId(request.orderId),
                userId = UserId(request.userId),
                side = request.side,
                orderType = request.orderType,
                timeInForce = request.timeInForce,
                price = Price(request.price),
                quantity = Quantity(request.quantity),
            )

        val events = orderSubmissionService.submit(command)

        return MatchingResponse(
            events = events.map { it.toResponse() },
        )
    }

    /**
     * book에 대기 중인 주문을 취소하고 남은 예약금을 반환한다.
     *
     * 주문이 없거나 [userId]가 원래 주문 소유자와 다르면 예외 대신
     * `ORDER_CANCEL_REJECTED` event가 반환된다.
     *
     * @param marketId 주문이 들어 있는 마켓
     * @param orderId 취소할 주문 식별자
     * @param userId 취소를 요청한 사용자 식별자
     * @return 취소 성공 또는 거절 event 하나를 가진 응답
     */
    @DeleteMapping("/{orderId}")
    fun cancelOrder(
        @PathVariable marketId: String,
        @PathVariable orderId: String,
        @RequestParam userId: String,
    ): MatchingResponse {
        val command =
            CancelOrderCommand(
                marketId = MarketId(marketId),
                orderId = OrderId(orderId),
                userId = UserId(userId),
            )

        val events = orderCancellationService.cancel(command)

        return MatchingResponse(
            events = events.map { it.toResponse() },
        )
    }
}
