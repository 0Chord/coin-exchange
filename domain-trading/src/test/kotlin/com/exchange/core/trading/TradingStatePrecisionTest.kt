package com.exchange.core.trading

import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.Side
import com.exchange.core.trading.TradingStateFixtures.admit
import com.exchange.core.trading.TradingStateFixtures.balance
import com.exchange.core.trading.TradingStateFixtures.btc
import com.exchange.core.trading.TradingStateFixtures.buyer
import com.exchange.core.trading.TradingStateFixtures.fund
import com.exchange.core.trading.TradingStateFixtures.market
import com.exchange.core.trading.TradingStateFixtures.order
import com.exchange.core.trading.TradingStateFixtures.seller
import com.exchange.core.trading.TradingStateFixtures.state
import com.exchange.core.trading.TradingStateFixtures.time
import com.exchange.core.trading.TradingStateFixtures.usdt
import com.exchange.core.trading.TradingStateFixtures.zeroPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class TradingStatePrecisionTest {
    @Test
    fun `단위가 없는 예전 마켓 설정은 작은 SELL을 예약 전에 거절하고 최초 거절을 보존한다`() {
        val state = state()
        val scaled = MarketDefinition(market.marketId, btc, usdt, 1)
        fund(state, seller, btc, 20)
        val before = state.snapshot()
        val input = order("S1", seller, Side.SELL, 10, 1, scaled)

        val rejected =
            assertIs<OrderAdmissionState.Result.Rejected>(
                state.admit("R1", input, scaled, zeroPolicy, "reserve-S1", time),
            )

        assertEquals(OrderAdmissionResult.Reason.UNREPRESENTABLE_RESERVATION, rejected.reason)
        val after = state.snapshot()
        assertEquals(before.balances, after.balances)
        assertEquals(before.reservations, after.reservations)
        assertEquals(before.ledgerTransactions, after.ledgerTransactions)
        assertEquals(emptyList(), after.pendingOrders)
        assertEquals(0L, after.lastAdmissionSequence)
        assertEquals(1, after.requests.size)
        assertSame(rejected, state.admit("R1", input, scaled, zeroPolicy, "retry-ledger", time.plusSeconds(1)))
        assertEquals(after, state.snapshot())
        val corrected =
            input.copy(
                quantity =
                    com.exchange.core.common
                        .Quantity(10),
            )
        assertEquals(
            OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT,
            assertIs<OrderAdmissionState.Result.Rejected>(
                state.admit("R1", corrected, scaled, zeroPolicy, "corrected-ledger", time),
            ).reason,
        )
        val accepted = admit(state, corrected, scaled, requestId = "R2")
        assertEquals(1L, accepted.record.sequence)
        state.executePending(accepted.record.sequence, time)
        assertFalse(state.isBlocked)
    }

    @Test
    fun `소수 수량을 허용한 마켓은 위험한 BUY 가격을 예약 전에 거절하고 다음 정상 주문을 정산한다`() {
        val state = state()
        val scaled = MarketDefinition(market.marketId, btc, usdt, 1, priceTick = 10, quantityStep = 1)
        fund(state, seller, btc, 1)
        fund(state, buyer, usdt, 100)
        val sell = admit(state, order("S1", seller, Side.SELL, 10, 1, scaled), scaled)
        state.executePending(sell.record.sequence, time)
        val before = state.snapshot()
        val book = state.book(scaled.marketId)

        val rejected =
            assertIs<OrderAdmissionState.Result.Rejected>(
                state.admit("R1", order("B1", buyer, Side.BUY, 11, 10, scaled), scaled, zeroPolicy, "bad-buy", time),
            )

        assertEquals(OrderAdmissionResult.Reason.UNREPRESENTABLE_RESERVATION, rejected.reason)
        val rejectedState = state.snapshot()
        assertEquals(before.balances, rejectedState.balances)
        assertEquals(before.reservations, rejectedState.reservations)
        assertEquals(before.ledgerTransactions, rejectedState.ledgerTransactions)
        assertEquals(before.pendingOrders, rejectedState.pendingOrders)
        assertEquals(before.completions, rejectedState.completions)
        assertEquals(before.lastAdmissionSequence, rejectedState.lastAdmissionSequence)
        assertEquals(book, state.book(scaled.marketId))
        assertFalse(state.isBlocked)

        val buy = admit(state, order("B2", buyer, Side.BUY, 20, 10, scaled), scaled)
        val completion = state.executePending(buy.record.sequence, time.plusSeconds(1))
        assertEquals(1, completion.settlement.settlements.size)
        assertEquals(
            setOf(balance(buyer, usdt, 81, 18), balance(buyer, btc, 1), balance(seller, btc, 0), balance(seller, usdt, 1)),
            state.snapshot().balances.toSet(),
        )
        assertEquals(
            9L,
            state
                .book(scaled.marketId)
                .single()
                .remainingQuantity.value,
        )
        assertEquals(
            18L,
            state
                .snapshot()
                .reservations
                .single { it.orderId == buy.record.candidate.order.orderId }
                .remainingAmount.value,
        )
        assertFalse(state.isBlocked)
    }

    @Test
    fun `SELL 가격도 단위에 맞아야 하며 BUY와 SELL의 수량 위반은 예약과 순번을 남기지 않는다`() {
        val state = state()
        val scaled = MarketDefinition(market.marketId, btc, usdt, 1, priceTick = 10, quantityStep = 1)
        fund(state, seller, btc, 10)
        fund(state, buyer, usdt, 100)
        val rejected =
            assertIs<OrderAdmissionState.Result.Rejected>(
                state.admit("S-R1", order("S1", seller, Side.SELL, 11, 10, scaled), scaled, zeroPolicy, "bad-sell", time),
            )
        assertEquals(OrderAdmissionResult.Reason.UNREPRESENTABLE_RESERVATION, rejected.reason)
        assertEquals(0L, state.snapshot().lastAdmissionSequence)
        assertEquals(emptyList(), state.snapshot().reservations)

        val coarse = scaled.copy(priceTick = 1, quantityStep = 10)
        for ((index, side) in listOf(Side.BUY, Side.SELL).withIndex()) {
            val user = if (side == Side.BUY) buyer else seller
            val input = order("Q$index", user, side, 20, 1, coarse)
            val before = state.snapshot()
            val result = assertIs<OrderAdmissionState.Result.Rejected>(state.admit(input, coarse, zeroPolicy, "bad-$index", time))
            assertEquals(OrderAdmissionResult.Reason.UNREPRESENTABLE_RESERVATION, result.reason)
            assertEquals(before, state.snapshot())
        }
        val sell = admit(state, order("S2", seller, Side.SELL, 11, 10, coarse), coarse)
        state.executePending(sell.record.sequence, time)
        val buy = admit(state, order("B2", buyer, Side.BUY, 20, 10, coarse), coarse)
        state.executePending(buy.record.sequence, time)
        assertEquals(
            setOf(balance(buyer, usdt, 89), balance(buyer, btc, 10), balance(seller, btc, 0), balance(seller, usdt, 11)),
            state.snapshot().balances.toSet(),
        )
        assertEquals(emptyList(), state.book(coarse.marketId))
        assertFalse(state.isBlocked)
    }

    @Test
    fun `서로 다른 가격의 두 체결과 남은 예약은 같은 마켓 단위로 정확히 이어진다`() {
        val state = state()
        val scaled = MarketDefinition(market.marketId, btc, usdt, 1, priceTick = 2, quantityStep = 5)
        fund(state, seller, btc, 10)
        fund(state, buyer, usdt, 100)
        val sell1 = admit(state, order("S1", seller, Side.SELL, 10, 5, scaled), scaled)
        state.executePending(sell1.record.sequence, time)
        val sell2 = admit(state, order("S2", seller, Side.SELL, 12, 5, scaled), scaled)
        state.executePending(sell2.record.sequence, time)
        val buy = admit(state, order("B1", buyer, Side.BUY, 14, 15, scaled), scaled)

        val completion = state.executePending(buy.record.sequence, time)

        assertEquals(listOf(5L, 6L), completion.settlement.settlements.map { it.takerPlan.holdAmountToConsume.value })
        assertEquals(listOf(2L, 1L), completion.settlement.settlements.map { it.takerPlan.holdAmountToRelease.value })
        assertEquals(
            setOf(balance(buyer, usdt, 82, 7), balance(buyer, btc, 10), balance(seller, btc, 0), balance(seller, usdt, 11)),
            state.snapshot().balances.toSet(),
        )
        assertEquals(
            5L,
            state
                .book(scaled.marketId)
                .single()
                .remainingQuantity.value,
        )
        assertEquals(
            7L,
            state
                .snapshot()
                .reservations
                .single { it.orderId == buy.record.candidate.order.orderId }
                .remainingAmount.value,
        )
        assertFalse(state.isBlocked)
    }
}
