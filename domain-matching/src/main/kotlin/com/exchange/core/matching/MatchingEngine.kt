package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.util.Collections

/**
 * LIMIT/GTC 매칭 상태의 소유자. 한 실행자가 같은 마켓의 명령을 순차 호출해야 한다.
 *
 * 새 주문은 [prepareSubmit]으로 읽기 전용 후보를 만들고 [apply]에서만 반영한다.
 * 기존 [process]도 동일한 계산·적용 경로를 사용하며, CANCEL은 즉시 처리한다.
 */
class MatchingEngine {
    private val orderBooks = HashMap<MarketId, OrderBook>()
    private val lastEngineSequences = HashMap<MarketId, Long>()
    private val seenOrderIds = HashMap<MarketId, MutableSet<OrderId>>()

    // 로컬 계획의 발급자 확인에만 쓰며 거래 ID·복제 결과에는 넣지 않는다.
    private val planToken = Any()

    fun process(command: MatchingCommand): List<MatchingEvent> =
        when (command) {
            is SubmitOrderCommand -> apply(prepareSubmit(command))
            is CancelOrderCommand -> processCancel(command)
        }

    /** 매수 가격 우선·매도 가격 우선, 같은 가격 FIFO 순서의 현재 주문 값 복사본. */
    fun restingOrders(marketId: MarketId): List<RestingOrderSnapshot> {
        val book = orderBooks[marketId]
        val orders =
            if (book == null) {
                emptyList()
            } else {
                (book.oppositeOrders(Side.SELL) + book.oppositeOrders(Side.BUY))
                    .map { order ->
                        RestingOrderSnapshot(
                            order.orderId,
                            order.userId,
                            order.side,
                            order.price,
                            order.originalQuantity,
                            order.remainingQuantity,
                        )
                    }.toList()
            }
        return Collections.unmodifiableList(orders)
    }

    /** 조회만으로 없는 마켓의 주문장이나 순번을 만들지 않는다. */
    fun lastEngineSequence(marketId: MarketId): Long = lastSequence(marketId)

    /** 가격·FIFO 순으로 읽기만 한다. 후보를 버려도 별도 해제 작업이 필요 없다. */
    fun prepareSubmit(command: SubmitOrderCommand): SubmitOrderPlan {
        validateSupportedSubmit(command)
        validateNewOrderId(command)
        val baseSequence = lastSequence(command.marketId)
        val fills = mutableListOf<MakerFill>()
        val events = mutableListOf<MatchingEvent>()
        var remaining = command.quantity

        // 없는 마켓을 읽을 때 getOrPut으로 빈 주문장을 만들지 않는다.
        val makers = orderBooks[command.marketId]?.oppositeOrders(command.side) ?: emptySequence()
        for (maker in makers) {
            if (remaining.isZero() || !priceCrosses(command.side, command.price, maker.price)) break
            val quantity = minOf(remaining, maker.remainingQuantity)
            fills +=
                MakerFill(
                    maker.orderId,
                    maker.userId,
                    maker.side,
                    maker.price,
                    maker.originalQuantity,
                    maker.remainingQuantity,
                    quantity,
                )
            events +=
                TradeExecuted(
                    command.marketId,
                    sequenceAt(baseSequence, events.size),
                    maker.orderId,
                    command.orderId,
                    maker.userId,
                    command.userId,
                    command.side,
                    maker.price,
                    quantity,
                )
            remaining -= quantity
        }
        if (!remaining.isZero()) {
            events +=
                OrderEnteredBook(
                    command.marketId,
                    sequenceAt(baseSequence, events.size),
                    command.orderId,
                    command.userId,
                    command.side,
                    command.price,
                    remaining,
                )
        }
        return SubmitOrderPlan(planToken, baseSequence, command, fills, remaining, events)
    }

    /** 후보 생성이 아닌 같은 마켓의 실제 처리가 기준 상태를 전진시킨다. */
    fun validate(plan: SubmitOrderPlan) {
        require(plan.belongsTo(planToken)) { "matching plan belongs to another engine" }
        val command = plan.command
        check(lastSequence(command.marketId) == plan.baseSequence) { "matching plan is stale or already applied" }
        validateSupportedSubmit(command)
        validateNewOrderId(command)
        check(plan.events.isNotEmpty()) { "matching plan must contain an event" }
        check(plan.lastEngineSequence == Math.addExact(plan.baseSequence, plan.events.size.toLong())) { "invalid plan sequence range" }

        val book = orderBooks[command.marketId]
        val makerCursor = (book?.oppositeOrders(command.side) ?: emptySequence()).iterator()
        var filled = 0L
        val makers = hashSetOf<OrderId>()
        plan.fills.forEachIndexed { index, fill ->
            check(makerCursor.hasNext()) { "matching plan maker is missing" }
            val maker = makerCursor.next()
            check(fill.matches(maker)) { "matching plan maker or price/FIFO position changed" }
            check(book?.find(fill.orderId) === maker) { "matching plan maker index changed" }
            check(makers.add(fill.orderId) && fill.orderId != command.orderId) { "invalid matching plan maker" }
            check(
                fill.side != command.side && priceCrosses(command.side, command.price, fill.price),
            ) { "invalid matching plan price or side" }
            check(fill.filledQuantity.value > 0 && fill.filledQuantity <= fill.remainingQuantity) { "invalid matching plan fill quantity" }
            filled = Math.addExact(filled, fill.filledQuantity.value)
            check(
                plan.events[index] ==
                    TradeExecuted(
                        command.marketId,
                        sequenceAt(plan.baseSequence, index),
                        fill.orderId,
                        command.orderId,
                        fill.userId,
                        command.userId,
                        command.side,
                        fill.price,
                        fill.filledQuantity,
                    ),
            ) { "matching plan event does not match maker change" }
        }
        check(Math.addExact(filled, plan.restingQuantity.value) == command.quantity.value) { "matching plan quantity is not conserved" }
        val hasResting = !plan.restingQuantity.isZero()
        check(plan.events.size == plan.fills.size + if (hasResting) 1 else 0) { "invalid matching plan event count" }
        if (hasResting) {
            if (makerCursor.hasNext()) {
                check(!priceCrosses(command.side, command.price, makerCursor.next().price)) { "matching plan leaves a matchable maker" }
            }
            check(
                plan.events.last() ==
                    OrderEnteredBook(
                        command.marketId,
                        sequenceAt(plan.baseSequence, plan.fills.size),
                        command.orderId,
                        command.userId,
                        command.side,
                        command.price,
                        plan.restingQuantity,
                    ),
            ) { "matching plan resting event does not match remaining quantity" }
        }
    }

    /** 전체 검증 뒤에만 쓴다. 예상 밖 JVM 오류의 SQL식 원상복구를 제공하지 않는다. */
    fun apply(plan: SubmitOrderPlan): List<MatchingEvent> {
        validate(plan)
        val command = plan.command
        val restingOrder =
            if (plan.restingQuantity.isZero()) {
                null
            } else {
                BookOrder(command.orderId, command.userId, command.side, command.price, command.quantity, plan.restingQuantity)
            }
        val book = orderBook(command.marketId)
        seenOrderIds.getOrPut(command.marketId) { HashSet() }.add(command.orderId)
        for (fill in plan.fills) {
            val maker = checkNotNull(book.find(fill.orderId))
            maker.fill(fill.filledQuantity)
            if (maker.isFilled()) book.removeFilledOrder(maker)
        }
        if (restingOrder != null) book.addRestingOrder(restingOrder)
        lastEngineSequences[command.marketId] = plan.lastEngineSequence
        return plan.events
    }

    private fun processCancel(command: CancelOrderCommand): List<MatchingEvent> {
        // 순번이 부족하면 빈 book 생성이나 주문 삭제보다 먼저 실패한다.
        val sequence = Math.addExact(lastSequence(command.marketId), 1L)
        val book = orderBook(command.marketId)
        val existing = book.find(command.orderId)
        val event =
            when {
                existing == null -> {
                    OrderCancelRejected(command.marketId, sequence, command.orderId, command.userId, "order not found")
                }

                existing.userId != command.userId -> {
                    OrderCancelRejected(command.marketId, sequence, command.orderId, command.userId, "order owner mismatch")
                }

                else -> {
                    OrderCancelled(command.marketId, sequence, existing.orderId, existing.userId, existing.remainingQuantity)
                }
            }
        if (event is OrderCancelled) checkNotNull(book.cancel(command.orderId)) { "order disappeared while cancelling" }
        lastEngineSequences[command.marketId] = sequence
        return listOf(event)
    }

    private fun validateSupportedSubmit(command: SubmitOrderCommand) {
        require(command.orderType == OrderType.LIMIT) { "only LIMIT order is supported" }
        require(command.timeInForce == TimeInForce.GTC) { "only GTC order is supported" }
    }

    private fun validateNewOrderId(command: SubmitOrderCommand) {
        require(command.orderId !in seenOrderIds.getOrDefault(command.marketId, emptySet())) { "order already exists" }
    }

    private fun priceCrosses(
        side: Side,
        limit: Price,
        maker: Price,
    ): Boolean =
        when (side) {
            Side.BUY -> maker <= limit
            Side.SELL -> maker >= limit
        }

    private fun orderBook(marketId: MarketId): OrderBook = orderBooks.getOrPut(marketId) { OrderBook() }

    private fun lastSequence(marketId: MarketId): Long = lastEngineSequences[marketId] ?: 0L

    private fun sequenceAt(
        last: Long,
        eventIndex: Int,
    ): Long = Math.addExact(last, eventIndex.toLong() + 1)
}
