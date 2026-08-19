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
 * 엔진 내부 상태는 mutable이므로 한 마켓의 command는 반드시 한 thread에서 순서대로
 * 호출해야 한다. 애플리케이션에서는 [MarketCommandProcessor]가 이 조건을 보장한다.
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
     * 별도 주문으로 체결될 수 있으므로, 한 market 안에서는 submit orderId를 재사용하지
     * 않는다.
     */
    private val seenOrderIds = HashMap<MarketId, MutableSet<OrderId>>()

    /**
     * 매칭 엔진의 단일 진입점.
     *
     * command 종류에 따라 주문 등록 또는 주문 취소 처리를 위임한다.
     *
     * @param command 새 주문 또는 취소 요청
     * @return 상태 변경 순서대로 생성된 event 목록
     * @throws IllegalArgumentException 지원하지 않는 주문 조건 또는 중복 orderId인 경우
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
     *
     * 반복 한 번마다 현재 최우선 maker 하나와 체결한다. maker 또는 taker 수량이
     * 소진될 때까지 반복하며, taker 잔량이 있으면 마지막에 book에 대기시킨다.
     *
     * @param command 새로 들어온 taker 주문
     * @return 각 체결 event와 선택적인 book 진입 event
     */
    private fun processSubmit(command: SubmitOrderCommand): List<MatchingEvent> {
        validateSupportedSubmit(command)
        validateNewOrderId(command)
        rememberOrderId(command)

        // 한 command에서 체결이 여러 번 발생할 수 있어 생성 순서대로 event를 모은다.
        val events = mutableListOf<MatchingEvent>()

        // takerRemaining은 아직 maker에게 배정되지 않은 taker의 base 자산 수량이다.
        var takerRemaining = command.quantity

        while (!takerRemaining.isZero()) {
            // 가격 우선, 같은 가격에서는 시간 우선인 다음 maker를 가져온다.
            val maker = nextMatchableMaker(command) ?: break

            // 어느 한쪽도 잔량보다 많이 체결되지 않도록 두 잔량의 최솟값을 사용한다.
            val tradeQuantity = minOf(takerRemaining, maker.remainingQuantity)

            // 동일한 tradeQuantity를 maker와 taker 양쪽 잔량에서 차감한다.
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
            // 현재 Phase의 GTC 주문은 즉시 체결되지 않은 잔량을 자기 side book에 남긴다.
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
     *
     * 주문이 없거나 요청자가 소유자가 아니면 book을 변경하지 않고 거절 event를 만든다.
     * 정상 요청이면 남은 수량 전체를 book에서 제거하고 취소 event를 만든다.
     *
     * @param command 마켓, 주문, 취소 요청자를 담은 command
     * @return 성공 또는 거절 event 하나를 가진 목록
     */
    private fun processCancel(command: CancelOrderCommand): List<MatchingEvent> {
        val orderBook = orderBook(command.marketId)
        val existingOrder = orderBook.find(command.orderId)

        val event = when {
            existingOrder == null -> OrderCancelRejected(
                marketId = command.marketId,
                engineSequence = nextSequence(command.marketId),
                orderId = command.orderId,
                userId = command.userId,
                reason = "order not found",
            )
            existingOrder.userId != command.userId -> OrderCancelRejected(
                marketId = command.marketId,
                engineSequence = nextSequence(command.marketId),
                orderId = command.orderId,
                userId = command.userId,
                reason = "order owner mismatch",
            )
            else -> {
                val cancelledOrder =
                    orderBook.cancel(command.orderId)
                        ?: error("order disappeared while cancelling")

                OrderCancelled(
                    marketId = command.marketId,
                    engineSequence = nextSequence(command.marketId),
                    orderId = cancelledOrder.orderId,
                    userId = cancelledOrder.userId,
                    remainingQuantity = cancelledOrder.remainingQuantity,
                )
            }
        }

        return listOf(event)
    }

    /**
     * Phase 1에서 지원하는 submit command인지 확인한다.
     *
     * 지금은 LIMIT + GTC만 처리한다.
     *
     * @param command 지원 여부를 확인할 새 주문
     * @throws IllegalArgumentException LIMIT 또는 GTC가 아닌 경우
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
     * 취소되거나 전량 체결된 주문의 id도 다시 사용할 수 없다.
     *
     * @param command id 중복 여부를 확인할 새 주문
     * @throws IllegalArgumentException 같은 마켓에서 orderId가 이미 사용된 경우
     */
    private fun validateNewOrderId(command: SubmitOrderCommand) {
        require(command.orderId !in seenOrderIds.getOrDefault(command.marketId, emptySet())) {
            "order already exists"
        }
    }

    /**
     * 검증을 통과한 주문 id를 해당 마켓의 처리 이력에 기록한다.
     *
     * @param command 다시 사용할 수 없도록 기억할 새 주문
     */
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
     *
     * @param command maker를 찾는 taker 주문
     * @return 가격 조건을 만족하는 최우선 maker. 없으면 `null`
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
     *
     * @param command 체결을 일으킨 taker 주문
     * @param maker book에 먼저 대기하던 주문
     * @param quantity 양쪽 주문에서 차감한 체결 수량
     * @return 다음 sequence가 부여된 체결 event
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
     *
     * @param command 최초 주문 정보
     * @param remainingQuantity 즉시 체결을 마치고 남은 수량
     * @return originalQuantity와 remainingQuantity를 함께 가진 대기 주문
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
     *
     * @param order 방금 book에 추가한 주문
     * @param marketId 주문이 속한 마켓
     * @return 다음 sequence가 부여된 book 진입 event
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
     *
     * @param takerSide 새 주문 방향
     * @param takerPrice 새 주문의 한계 가격
     * @param makerPrice 기존 주문 가격이자 체결 예정 가격
     * @return 두 가격이 지정가 조건을 만족하면 `true`
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

    /**
     * 마켓별 독립 오더북을 반환하며, 처음 접근한 마켓이면 빈 book을 만든다.
     *
     * @param marketId 조회할 마켓
     * @return 해당 마켓의 mutable OrderBook
     */
    private fun orderBook(marketId: MarketId): OrderBook =
        orderBooks.getOrPut(marketId) {
            OrderBook()
        }

    /**
     * 마켓 안에서 다음 event 순번을 발급한다.
     *
     * 첫 event는 1이며 호출할 때마다 해당 마켓의 다음 값만 1 증가한다. 다른 마켓의
     * sequence에는 영향을 주지 않는다.
     *
     * @param marketId event가 발생한 마켓
     * @return 이번 event에 사용할 sequence
     */
    private fun nextSequence(marketId: MarketId): Long {
        val nextSequence = nextEngineSequences.getOrDefault(marketId, 1L)
        nextEngineSequences[marketId] = nextSequence + 1
        return nextSequence
    }
}
