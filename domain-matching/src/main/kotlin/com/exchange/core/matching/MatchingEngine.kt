package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

/**
 * 매칭 엔진의 public 진입점.
 *
 * MatchingEngine은 command를 받아 OrderBook을 변경하고,
 * 그 결과를 MatchingEvent로 반환한다.
 *
 * Phase 1에서는 LIMIT + GTC + CANCEL만 처리한다.
 */
class MatchingEngine {
    /**
     * 현재 마켓의 in-memory order book.
     */
    private val orderBook = OrderBook()

    /**
     * event에 붙일 다음 engine sequence.
     *
     * 같은 command 순서라면 같은 event 순서가 나와야 하므로
     * 엔진 내부에서 단조 증가시킨다.
     */
    private var nextEngineSequence = 1L

    /**
     * 매칭 엔진의 단일 진입점.
     *
     * command 종류에 따라 주문 등록 또는 주문 취소 처리를 위임한다.
     */
    fun process(command: MatchingCommand): List<MatchingEvent> =
        when (command) {
            is SubmitOrderCommand -> processSubmit(command)
            is CancelOrderCommand -> processCancel(command)
        }

    /**
     * 새 주문을 처리한다.
     *
     * 새로 들어온 주문은 taker가 되고, 기존 book에 있던 주문은 maker가 된다.
     * 핵심 상태 변경 순서가 보이도록 체결/북 등록 흐름은 이 함수에 남긴다.
     */
    private fun processSubmit(command: SubmitOrderCommand): List<MatchingEvent> {
        validateSupportedSubmit(command)

        val events = mutableListOf<MatchingEvent>()
        var takerRemaining = command.quantity

        while (!takerRemaining.isZero()) {
            val maker = nextMatchableMaker(command) ?: break
            val tradeQuantity = minOf(takerRemaining, maker.remainingQuantity)

            maker.fill(tradeQuantity)
            takerRemaining -= tradeQuantity

            events += tradeExecutedEvent(
                command = command,
                maker = maker,
                quantity = tradeQuantity,
            )

            if (maker.isFilled()) {
                orderBook.removeFilledOrder(maker)
            }
        }

        if (!takerRemaining.isZero()) {
            val restingOrder = createRestingOrder(
                command = command,
                remainingQuantity = takerRemaining,
            )

            orderBook.addRestingOrder(restingOrder)

            events += orderEnteredBookEvent(
                order = restingOrder,
                marketId = command.marketId,
            )
        }

        return events
    }

    /**
     * book에 남아 있는 주문을 취소한다.
     */
    private fun processCancel(command: CancelOrderCommand): List<MatchingEvent> {
        val cancelledOrder = orderBook.cancel(command.orderId)

        val event = if (cancelledOrder == null) {
            OrderCancelRejected(
                marketId = command.marketId,
                engineSequence = nextSequence(),
                orderId = command.orderId,
                userId = command.userId,
                reason = "order not found",
            )
        } else {
            OrderCancelled(
                marketId = command.marketId,
                engineSequence = nextSequence(),
                orderId = cancelledOrder.orderId,
                userId = cancelledOrder.userId,
                remainingQuantity = cancelledOrder.remainingQuantity,
            )
        }

        return listOf(event)
    }

    /**
     * Phase 1에서 지원하는 submit command인지 확인한다.
     *
     * 지금은 LIMIT + GTC만 처리한다.
     */
    private fun validateSupportedSubmit(command: SubmitOrderCommand) {
        require(command.orderType == OrderType.LIMIT) {
            "only LIMIT order is supported"
        }

        require(command.timeInForce == TimeInForce.GTC) {
            "only GTC order is supported"
        }
    }

    /**
     * 현재 taker 주문과 체결 가능한 다음 maker 주문을 찾는다.
     *
     * BUY taker는 가장 낮은 ask level에서,
     * SELL taker는 가장 높은 bid level에서 maker를 찾는다.
     *
     * 반대편 book이 비었거나 가격이 crossing 되지 않으면 null을 반환한다.
     */
    private fun nextMatchableMaker(command: SubmitOrderCommand): BookOrder? {
        val bestLevel = when (command.side) {
            Side.BUY -> orderBook.bestAskLevel()
            Side.SELL -> orderBook.bestBidLevel()
        } ?: return null

        val maker = bestLevel.firstOrder() ?: return null

        return if (priceCrosses(command.side, command.price, maker.price)) {
            maker
        } else {
            null
        }
    }

    /**
     * 체결 event를 만든다.
     *
     * 체결 가격은 항상 maker 가격이고,
     * side는 새로 들어온 taker 주문 기준이다.
     */
    private fun tradeExecutedEvent(
        command: SubmitOrderCommand,
        maker: BookOrder,
        quantity: Quantity,
    ): TradeExecuted =
        TradeExecuted(
            marketId = command.marketId,
            engineSequence = nextSequence(),
            makerOrderId = maker.orderId,
            takerOrderId = command.orderId,
            makerUserId = maker.userId,
            takerUserId = command.userId,
            side = command.side,
            price = maker.price,
            quantity = quantity,
        )

    /**
     * book에 남길 내부 주문 상태를 만든다.
     */
    private fun createRestingOrder(
        command: SubmitOrderCommand,
        remainingQuantity: Quantity,
    ): BookOrder =
        BookOrder(
            orderId = command.orderId,
            userId = command.userId,
            side = command.side,
            price = command.price,
            originalQuantity = command.quantity,
            remainingQuantity = remainingQuantity,
        )

    /**
     * 주문 잔량이 book에 들어갔다는 event를 만든다.
     */
    private fun orderEnteredBookEvent(
        order: BookOrder,
        marketId: MarketId,
    ): OrderEnteredBook =
        OrderEnteredBook(
            marketId = marketId,
            engineSequence = nextSequence(),
            orderId = order.orderId,
            userId = order.userId,
            side = order.side,
            price = order.price,
            remainingQuantity = order.remainingQuantity,
        )

    /**
     * taker 주문 가격이 maker 주문 가격과 체결 가능한지 확인한다.
     *
     * BUY taker는 maker ask 가격이 자기 가격 이하이면 체결 가능하고,
     * SELL taker는 maker bid 가격이 자기 가격 이상이면 체결 가능하다.
     */
    private fun priceCrosses(
        takerSide: Side,
        takerPrice: Price,
        makerPrice: Price,
    ): Boolean =
        when (takerSide) {
            Side.BUY -> makerPrice <= takerPrice
            Side.SELL -> makerPrice >= takerPrice
        }

    private fun nextSequence(): Long =
        nextEngineSequence++
}
