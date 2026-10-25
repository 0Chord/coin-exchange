package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide.CREDIT
import com.exchange.core.order.OrderReservationStatus
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
import kotlin.test.assertSame

class TradingStateFeeTest {
    @Test
    fun `taker 체결에서 저장한 수수료 나머지를 다음 maker 체결이 읽어 실제 잔고744 BTC5를 만든다`() {
        val state = state()
        val nextSeller = UserId("S2")
        val policy = zeroPolicy.copy(feeRates = MakerTakerFeeRates(FeeRate(5_000), FeeRate(10_000)))
        fund(state, buyer, usdt, 1_000)
        fund(state, seller, btc, 1)
        fund(state, nextSeller, btc, 4)
        val sell = admit(state, order("S1", seller, Side.SELL, 51, 1), policy = policy)
        state.executePending(sell.record.sequence, time.plusSeconds(1))
        val buy = admit(state, order("B1", buyer, Side.BUY, 51, 5), policy = policy)
        assertEquals(balance(buyer, usdt, 742, 258), buy.record.candidate.nextBalance)
        assertEquals(Amount(3), buy.record.candidate.reservation.remainingFeeReserveAmount)

        val firstCompletion = state.executePending(buy.record.sequence, time.plusSeconds(2))
        val afterFirst = state.snapshot()

        val firstBuy = afterFirst.reservations.single { it.orderId == buy.record.candidate.order.orderId }
        assertEquals(OrderReservationStatus.ACTIVE, firstBuy.status)
        assertEquals(Quantity(4), firstBuy.remainingQuantity)
        assertEquals(Amount(207), firstBuy.remainingAmount)
        assertEquals(Amount(3), firstBuy.remainingFeeReserveAmount)
        assertEquals(FeeRemainder(510_000), firstBuy.feeRemainder)
        assertEquals(balance(buyer, usdt, 742, 207), afterFirst.balances.single { it.userId == buyer && it.assetId == usdt })
        assertEquals(balance(buyer, btc, 1), afterFirst.balances.single { it.userId == buyer && it.assetId == btc })
        val firstSettlement = firstCompletion.settlement.settlements.single()
        assertEquals(Amount.ZERO, firstSettlement.takerPlan.actualFeeAmount)
        assertEquals(Amount(51), firstSettlement.takerPlan.holdAmountToConsume)
        assertEquals(Amount.ZERO, firstSettlement.takerPlan.holdAmountToRelease)
        assertEquals(Quantity(4), state.book(market.marketId).single().remainingQuantity)

        val next = admit(state, order("S2", nextSeller, Side.SELL, 51, 4), policy = policy.copy(scheduleVersion = 2))
        val secondCompletion = state.executePending(next.record.sequence, time.plusSeconds(3))
        val afterSecond = state.snapshot()

        val finalBuy = afterSecond.reservations.single { it.orderId == buy.record.candidate.order.orderId }
        assertEquals(OrderReservationStatus.SETTLED, finalBuy.status)
        assertEquals(Quantity.ZERO, finalBuy.remainingQuantity)
        assertEquals(Amount.ZERO, finalBuy.remainingAmount)
        assertEquals(Amount.ZERO, finalBuy.remainingFeeReserveAmount)
        assertEquals(FeeRemainder(530_000), finalBuy.feeRemainder)
        assertEquals(policy, finalBuy.feePolicySnapshot)
        assertEquals(balance(buyer, usdt, 744), afterSecond.balances.single { it.userId == buyer && it.assetId == usdt })
        assertEquals(balance(buyer, btc, 5), afterSecond.balances.single { it.userId == buyer && it.assetId == btc })
        assertEquals(balance(nextSeller, usdt, 202), afterSecond.balances.single { it.userId == nextSeller && it.assetId == usdt })
        assertEquals(balance(nextSeller, btc, 0), afterSecond.balances.single { it.userId == nextSeller && it.assetId == btc })
        val finalSell = afterSecond.reservations.single { it.orderId == next.record.candidate.order.orderId }
        assertEquals(OrderReservationStatus.SETTLED, finalSell.status)
        assertEquals(FeeRemainder(40_000), finalSell.feeRemainder)
        val secondSettlement = secondCompletion.settlement.settlements.single()
        assertEquals(Amount(1), secondSettlement.makerPlan.actualFeeAmount)
        assertEquals(Amount(205), secondSettlement.makerPlan.holdAmountToConsume)
        assertEquals(Amount(2), secondSettlement.makerPlan.holdAmountToRelease)
        assertEquals(Amount(2), secondSettlement.takerPlan.actualFeeAmount)
        assertEquals(Amount(202), secondSettlement.takerPlan.creditAmount)
        assertEquals(
            listOf(
                LedgerPosting("SYSTEM:USDT:FEE_REVENUE", usdt, CREDIT, Amount(1)),
                LedgerPosting("SYSTEM:USDT:FEE_REVENUE", usdt, CREDIT, Amount(2)),
            ),
            secondSettlement.transaction.postings.filter { it.accountId == "SYSTEM:USDT:FEE_REVENUE" },
        )
        assertEquals(emptyList(), state.book(market.marketId))
        assertEquals(emptyList(), afterSecond.pendingOrders)
        assertEquals(4L, state.lastEngineSequence(market.marketId))

        assertSame(firstCompletion, state.executePending(buy.record.sequence, time.plusSeconds(99)))
        assertEquals(afterSecond, state.snapshot())
        assertEquals(Quantity(4), firstSettlement.takerPlan.updatedReservation.remainingQuantity)
        assertEquals(Amount(207), firstSettlement.takerPlan.updatedReservation.remainingAmount)
        assertEquals(FeeRemainder(510_000), firstSettlement.takerPlan.updatedReservation.feeRemainder)
        assertEquals(Amount(258), buy.record.candidate.reservation.remainingAmount)
        assertEquals(FeeRemainder.ZERO, buy.record.candidate.reservation.feeRemainder)
    }

    @Test
    fun `maker와 taker는 각자 접수한 서로 다른 정책으로 정산한다`() {
        val state = state()
        val makerPolicy = zeroPolicy.copy(feeRates = MakerTakerFeeRates(FeeRate(10_000), FeeRate(20_000)))
        val takerPolicy = zeroPolicy.copy(scheduleVersion = 2, feeRates = MakerTakerFeeRates(FeeRate(30_000), FeeRate(40_000)))
        fund(state, buyer, usdt, 20_000)
        fund(state, seller, btc, 100)
        val buy = admit(state, order("B1", buyer, Side.BUY, 100, 100), policy = makerPolicy)
        state.executePending(buy.record.sequence, time.plusSeconds(1))
        val sell = admit(state, order("S1", seller, Side.SELL, 100, 100), policy = takerPolicy)

        val completion = state.executePending(sell.record.sequence, time.plusSeconds(2))

        val settlement = completion.settlement.settlements.single()
        assertEquals(Amount(100), settlement.makerPlan.actualFeeAmount)
        assertEquals(Amount(400), settlement.takerPlan.actualFeeAmount)
        assertEquals(Amount(100), settlement.makerPlan.holdAmountToRelease)
        assertEquals(Amount(9_600), settlement.takerPlan.creditAmount)
        assertEquals(
            setOf(balance(buyer, usdt, 9_900), balance(buyer, btc, 100), balance(seller, btc, 0), balance(seller, usdt, 9_600)),
            state.snapshot().balances.toSet(),
        )
        assertEquals(
            makerPolicy,
            state
                .snapshot()
                .reservations
                .single { it.side == Side.BUY }
                .feePolicySnapshot,
        )
        assertEquals(
            takerPolicy,
            state
                .snapshot()
                .reservations
                .single { it.side == Side.SELL }
                .feePolicySnapshot,
        )
    }
}
