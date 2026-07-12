package com.exchange.core.api.matching

import com.exchange.core.common.*
import com.exchange.core.matching.CancelOrderCommand
import com.exchange.core.matching.SubmitOrderCommand
import org.springframework.web.bind.annotation.*

/**
 * Matching core를 HTTP로 호출하는 API.
 *
 * Controller는 요청/응답 변환만 맡고, 실제 처리는 MatchingApplicationService가 담당한다.
 */
@RestController
@RequestMapping("/api/markets/{marketId}/orders")
class MatchingController(
    private val matchingService: MatchingApplicationService
) {
    /**
     * 주문을 matching command로 변환해 processor에 넣는다.
     */
    @PostMapping
    fun submitOrder(
        @PathVariable marketId: String,
        @RequestBody request: SubmitOrderRequest
    ): MatchingResponse {
        val command = SubmitOrderCommand(
            marketId = MarketId(marketId),
            orderId = OrderId(request.orderId),
            userId = UserId(request.userId),
            side = request.side,
            orderType = request.orderType,
            timeInForce = request.timeInForce,
            price = Price(request.price),
            quantity = Quantity(request.quantity),
        )

        val events = matchingService.process(command)

        return MatchingResponse(
            events = events.map { it.toResponse() }
        )
    }

    @DeleteMapping("/{orderId}")
    fun cancelOrder(
        @PathVariable marketId: String,
        @PathVariable orderId: String,
        @RequestParam userId: String
    ): MatchingResponse {
        val command = CancelOrderCommand(
            marketId = MarketId(marketId),
            orderId = OrderId(orderId),
            userId = UserId(userId)
        )

        val events = matchingService.process(command)

        return MatchingResponse(
            events = events.map { it.toResponse() }
        )
    }
}
