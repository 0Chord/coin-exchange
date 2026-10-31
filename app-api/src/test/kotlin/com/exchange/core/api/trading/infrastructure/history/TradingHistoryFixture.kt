package com.exchange.core.api.trading.infrastructure.history

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
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingHistoryBatch
import com.exchange.core.trading.TradingHistoryStore
import com.exchange.core.trading.TradingState
import java.time.Instant

internal class TradingHistoryFixture(
    historyStore: TradingHistoryStore? = null,
) {
    val market = MarketDefinition(MarketId("BTC-KRW"), AssetId("BTC"), AssetId("KRW"), 0)
    val policy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(1_000), FeeRate(2_000)))
    val time: Instant = Instant.parse("2026-10-29T01:02:03.123456789Z")
    private val fees = TradingFeeReserveCalculator()
    private val reservation = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fees))
    val state =
        TradingState(
            OrderAdmissionCalculator(reservation),
            OrderSettlementCalculator(
                OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
                reservation,
                fees,
                SettlementLedgerTransactions(),
            ),
            historyStore = historyStore,
            historyBatchLimit = 3,
        )
    val batches = ArrayList<TradingHistoryBatch>()

    fun funding(
        user: String,
        asset: String,
        amount: Long,
    ) {
        val opening = OpeningBalance("fund-$user-$asset", UserId(user), AssetId(asset), Amount(amount))
        state.prepareDevelopmentBalance(opening, "ledger-${opening.preparationId}", time)
        val preparation = state.snapshot().preparations.last()
        batches.add(TradingHistoryBatch(nextOrdinal(), preparation = preparation, ledgerTransactions = listOf(preparation.transaction)))
    }

    fun admit(
        id: String,
        user: String,
        side: Side,
        quantity: Long = 2,
    ): OrderAdmissionState.Result {
        val input = order(id, user, side, quantity)
        val result = state.admit("request-$id", input, market, policy, "reserve-$id", time)
        val record = (result as? OrderAdmissionState.Result.Accepted)?.record
        batches.add(
            TradingHistoryBatch(
                nextOrdinal(),
                request = state.snapshot().requests.last(),
                admission = record,
                reservations = listOfNotNull(record?.candidate?.reservation),
                ledgerTransactions = listOfNotNull(record?.candidate?.reserveTransaction),
            ),
        )
        return result
    }

    fun execute(record: OrderAdmissionState.Record): TradingState.Completion {
        val completion = state.executePending(record.sequence, time.plusNanos(789))
        batches.add(
            TradingHistoryBatch(
                nextOrdinal(),
                completion = completion,
                reservations = completion.settlement.reservationChanges.map { it.next },
                ledgerTransactions = completion.settlement.settlements.map { it.transaction },
            ),
        )
        return completion
    }

    fun completedTrade() {
        funding("seller", "BTC", 100)
        funding("buyer", "KRW", 100_000)
        execute((admit("sell", "seller", Side.SELL) as OrderAdmissionState.Result.Accepted).record)
        execute((admit("buy", "buyer", Side.BUY) as OrderAdmissionState.Result.Accepted).record)
        admit("rejected", "unfunded", Side.BUY)
    }

    fun order(
        id: String,
        user: String,
        side: Side,
        quantity: Long = 2,
    ) = OrderAdmissionInput(
        market.marketId,
        OrderId(id),
        UserId(user),
        side,
        OrderType.LIMIT,
        TimeInForce.GTC,
        Price(1_000),
        Quantity(quantity),
    )

    private fun nextOrdinal(): Long = batches.size.toLong() + 1
}
