package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
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
     * 마켓별 in-memory order book.
     *
     * 서로 다른 marketId의 주문이 같은 book에서 체결되면 안 되므로
     * marketId마다 독립된 OrderBook을 둔다.
     */
    private val orderBooks = HashMap<MarketId, OrderBook>()

    /**
     * market별 event sequence.
     *
     * engineSequence는 market 안에서 단조 증가해야 한다.
     */
    private val nextEngineSequences = HashMap<MarketId, Long>()

    /**
     * market별로 이미 처리한 submit orderId.
     *
     * 같은 orderId를 다시 받아 새 주문처럼 처리하면 retry나 중복 요청이
     * 별도 주문으로 체결될 수 있으므로, 한 market 안에서는 submit orderId를 재사용하지 않는다.
     */
    private val seenOrderIds = HashMap<MarketId, MutableSet<OrderId>>()

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
        validateNewOrderId(command)
        rememberOrderId(command)

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
                orderBook(command.marketId).removeFilledOrder(maker)
            }
        }

        if (!takerRemaining.isZero()) {
            val restingOrder = createRestingOrder(
                command = command,
                remainingQuantity = takerRemaining,
            )

            orderBook(command.marketId).addRestingOrder(restingOrder)

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
        val cancelledOrder = orderBook(command.marketId).cancel(command.orderId)

        val event = if (cancelledOrder == null) {
            OrderCancelRejected(
                marketId = command.marketId,
                engineSequence = nextSequence(command.marketId),
                orderId = command.orderId,
                userId = command.userId,
                reason = "order not found",
            )
        } else {
            OrderCancelled(
                marketId = command.marketId,
                engineSequence = nextSequence(command.marketId),
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
     * 같은 마켓에서 이미 처리한 orderId는 다시 등록할 수 없다.
     *
     * 중복 orderId를 허용하면 retry나 중복 요청이 별도 주문처럼 체결될 수 있다.
     */
    private fun validateNewOrderId(command: SubmitOrderCommand) {
        require(command.orderId !in seenOrderIds.getOrDefault(command.marketId, emptySet())) {
            "order already exists"
        }
    }

    private fun rememberOrderId(command: SubmitOrderCommand) {
        seenOrderIds.getOrPut(command.marketId) {
            HashSet()
        }.add(command.orderId)
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
        val orderBook = orderBook(command.marketId)
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
            engineSequence = nextSequence(command.marketId),
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
            engineSequence = nextSequence(marketId),
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

    private fun orderBook(marketId: MarketId): OrderBook =
        orderBooks.getOrPut(marketId) {
            OrderBook()
        }

    private fun nextSequence(marketId: MarketId): Long {
        val nextSequence = nextEngineSequences.getOrDefault(marketId, 1L)
        nextEngineSequences[marketId] = nextSequence + 1
        return nextSequence
    }
}
