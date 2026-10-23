package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

abstract class MatchingResultTestFixture {
    protected val market = MarketId("BTC-USDT")
    protected val otherMarket = MarketId("SUI-USDT")

    protected fun assertRejectedWithoutChanges(
        state: MatchingResultState,
        reason: MatchingResultException.Reason = MatchingResultException.Reason.INVALID_RESULT,
        action: () -> Unit,
    ) {
        val before = state.snapshot()
        val error = assertFailsWith<MatchingResultException> { action() }
        assertEquals(reason, error.reason)
        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
    }

    protected data class Batch(
        val count: Int,
        val first: Long,
        val last: Long,
        val events: List<MatchingEvent>,
    )

    protected fun register(
        state: MatchingResultState,
        command: MatchingCommand,
        batch: Batch,
    ) = state.register("C3", command, batch.count, batch.first, batch.last, batch.events)

    protected fun registerBuy(state: MatchingResultState) = state.register("C3", buy(), 3, 3, 5, buyEvents())

    protected fun prepared(): MatchingResultState =
        MatchingResultState().apply {
            register("C1", submit("S1", "U-S1", Side.SELL, 90, 1), 1, 1, 1, listOf(entered(1, "S1", "U-S1", Side.SELL, 90, 1)))
            register("C2", submit("S2", "U-S2", Side.SELL, 95, 1), 1, 2, 2, listOf(entered(2, "S2", "U-S2", Side.SELL, 95, 1)))
        }

    protected fun buy() = submit("B1", "U-B1", Side.BUY, 100, 3)

    protected fun buyEvents(): List<MatchingEvent> = listOf(firstTrade(), secondTrade(), remainder())

    protected fun firstTrade() = trade(3, "S1", "B1", "U-S1", "U-B1", Side.BUY, 90, 1)

    protected fun secondTrade() = trade(4, "S2", "B1", "U-S2", "U-B1", Side.BUY, 95, 1)

    protected fun remainder() = entered(5, "B1", "U-B1", Side.BUY, 100, 1)

    protected fun submit(
        order: String,
        user: String,
        side: Side,
        price: Long,
        quantity: Long,
    ) = SubmitOrderCommand(market, OrderId(order), UserId(user), side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity))

    protected fun entered(
        sequence: Long,
        order: String,
        user: String,
        side: Side,
        price: Long,
        remaining: Long,
    ) = OrderEnteredBook(market, sequence, OrderId(order), UserId(user), side, Price(price), Quantity(remaining))

    protected fun trade(
        sequence: Long,
        maker: String,
        taker: String,
        makerUser: String,
        takerUser: String,
        side: Side,
        price: Long,
        quantity: Long,
    ) = TradeExecuted(
        market,
        sequence,
        OrderId(maker),
        OrderId(taker),
        UserId(makerUser),
        UserId(takerUser),
        side,
        Price(price),
        Quantity(quantity),
    )
}
