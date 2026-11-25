package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide.CREDIT
import com.exchange.core.ledger.LedgerPostingSide.DEBIT
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.ReservationRequirement
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderSettlementExampleTest {
    private val btc = AssetId("BTC")
    private val usdt = AssetId("USDT")
    private val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 0)
    private val buyer = UserId("B")
    private val order =
        OrderAdmissionInput(market.marketId, OrderId("B1"), buyer, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(3))
    private val time = Instant.parse("2026-10-25T00:00:00Z")
    private val policy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
    private val calculator =
        OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), TradingFeeReserveCalculator()),
            OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())),
            TradingFeeReserveCalculator(),
            SettlementLedgerTransactions(),
        )

    @Test
    fun `두 체결은 앞 후보를 이어가 715와 예약100 BTC2를 만들고 원본은 그대로 둔다`() {
        val buy = reservation("B1", "B", Side.BUY, 100, 3, 300)
        val sell1 = reservation("S1", "S1", Side.SELL, 90, 1, 1)
        val sell2 = reservation("S2", "S2", Side.SELL, 95, 1, 1)
        val reservations = listOf(buy, sell1, sell2)
        val balances = listOf(balance("B", usdt, 700, 300), balance("S1", btc, 0, 1), balance("S2", btc, 0, 1))
        val executions = listOf(execution(3, "S1", 90), execution(4, "S2", 95))

        val candidate = calculator.calculate(order, market, executions, reservations, balances)

        assertEquals(order, candidate.order)
        assertEquals(market, candidate.market)
        assertEquals(
            setOf(
                balance("B", usdt, 715, 100),
                balance("B", btc, 2, 0),
                balance("S1", btc, 0, 0),
                balance("S1", usdt, 90, 0),
                balance("S2", btc, 0, 0),
                balance("S2", usdt, 95, 0),
            ),
            candidate.balanceChanges.map { it.next }.toSet(),
        )
        assertEquals(
            setOf(
                buy.copy(remainingQuantity = Quantity(1), remainingAmount = Amount(100)),
                sell1.copy(remainingQuantity = Quantity.ZERO, remainingAmount = Amount.ZERO, status = OrderReservationStatus.SETTLED),
                sell2.copy(remainingQuantity = Quantity.ZERO, remainingAmount = Amount.ZERO, status = OrderReservationStatus.SETTLED),
            ),
            candidate.reservationChanges.map { it.next }.toSet(),
        )
        assertEquals(reservations.toSet(), candidate.reservationChanges.map { it.previous }.toSet())
        assertEquals(listOf(balance("B", usdt, 700, 300), balance("S1", btc, 0, 1), balance("S2", btc, 0, 1)), balances)
        assertEquals(listOf(buy, sell1, sell2), reservations)
        assertEquals(executions, candidate.settlements.map { it.execution })
        for ((index, price) in listOf(90L, 95L).withIndex()) {
            val seller = "S${index + 1}"
            val transaction = candidate.settlements[index].transaction
            assertEquals("ledger-${index + 3}", transaction.ledgerTransactionId)
            assertEquals("MATCHING:BTC-USDT:${index + 3}", transaction.sourceEventId)
            assertEquals(time, transaction.occurredAt)
            assertEquals(LedgerTransactionType.SETTLEMENT, transaction.transactionType)
            assertEquals(
                listOf(
                    LedgerPosting("USER:$seller:BTC:HOLD", btc, DEBIT, Amount(1)),
                    LedgerPosting("USER:$seller:USDT:AVAILABLE", usdt, CREDIT, Amount(price)),
                    LedgerPosting("USER:B:USDT:HOLD", usdt, DEBIT, Amount(price)),
                    LedgerPosting("USER:B:USDT:HOLD", usdt, DEBIT, Amount(100 - price)),
                    LedgerPosting("USER:B:USDT:AVAILABLE", usdt, CREDIT, Amount(100 - price)),
                    LedgerPosting("USER:B:BTC:AVAILABLE", btc, CREDIT, Amount(1)),
                ),
                transaction.postings,
            )
        }
    }

    private fun reservation(
        id: String,
        user: String,
        side: Side,
        price: Long,
        quantity: Long,
        held: Long,
    ): OrderReservation =
        OrderReservation.create(
            market.marketId,
            OrderId(id),
            UserId(user),
            side,
            Price(price),
            Quantity(quantity),
            ReservationRequirement(if (side == Side.BUY) usdt else btc, Amount(held)),
            policy,
        )

    private fun balance(
        user: String,
        asset: AssetId,
        available: Long,
        hold: Long,
    ) = Balance(UserId(user), asset, Amount(available), Amount(hold))

    private fun execution(
        sequence: Long,
        seller: String,
        price: Long,
    ) = SettlementExecution(
        market.marketId,
        sequence,
        OrderId(seller),
        order.orderId,
        UserId(seller),
        buyer,
        Side.BUY,
        Price(price),
        Quantity(1),
        "ledger-$sequence",
        time,
    )
}
