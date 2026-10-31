package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.Amount
import com.exchange.core.common.OrderId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingState

/** This file plus TradingEngineProbeSupport also compiles against the unmodified a39b0d8 production API. */
internal fun runLegacyTradingEngineProbe(options: TradingEngineProbeOptions): String {
    val workload = TradingEngineProbeWorkload
    val (admission, settlement) = workload.calculators()
    val state = TradingState(admission, settlement)
    state.prepareDevelopmentBalance(
        OpeningBalance("fund-buyer", workload.buyer, workload.quote, Amount(workload.quoteFunding(options.total))),
        "opening-buyer",
        workload.time,
    )
    for ((ordinal, market) in workload.markets.withIndex()) {
        val amount = workload.countForMarket(options.total, ordinal).toLong() * workload.quantity(market).value
        if (amount !=
            0L
        ) {
            state.prepareDevelopmentBalance(
                OpeningBalance("fund-$ordinal", workload.seller, market.baseAssetId, Amount(amount)),
                "opening-$ordinal",
                workload.time,
            )
        }
    }

    fun execute(
        index: Int,
        side: Side,
    ) {
        val market = workload.market(index)
        val id = "${if (side == Side.SELL) "S" else "B"}-$index"
        val input =
            OrderAdmissionInput(
                market.marketId,
                OrderId(id),
                if (side == Side.SELL) workload.seller else workload.buyer,
                side,
                OrderType.LIMIT,
                TimeInForce.GTC,
                workload.price,
                workload.quantity(market),
            )
        val accepted = state.admit(id, input, market, workload.policy, "reserve-$id", workload.time)
        check(accepted is OrderAdmissionState.Result.Accepted)
        val completed = state.executePending(accepted.record.sequence, workload.time)
        if (side == Side.BUY) check(completed.settlement.settlements.size == 1)
    }
    repeat(options.total) { execute(it, Side.SELL) }
    val measured = measureTradingEngineProbe(options, { execute(it, Side.BUY) })
    val snapshot = state.snapshot()
    val balances = snapshot.balances.associateBy { it.userId to it.assetId }
    workload.verifyBalances(options.total) { user, asset -> balances[user to asset] ?: Balance(user, asset, Amount.ZERO, Amount.ZERO) }
    val fees =
        snapshot.ledgerTransactions
            .flatMap { it.postings }
            .filter {
                it.accountId == "SYSTEM:USDT:FEE_REVENUE" && it.side == LedgerPostingSide.CREDIT
            }.sumOf { it.amount.value }
    check(fees == options.total * workload.FEE_PER_TRADE)
    check(snapshot.pendingOrders.isEmpty())
    check(workload.markets.all { state.book(it.marketId).isEmpty() })
    check(snapshot.completions.size == options.total * 2)
    return "{\"mode\":\"legacy-a39-api-history-off\",\"scope\":\"single-JVM BUY admit+execute; transport excluded\"," +
        "\"warmup\":${options.warmup}," +
        "\"markets\":\"BTC/SOL/SUI\",\"sellPreparation\":${options.total},${measured.fields()},\"financiallyVerified\":true," +
        "\"retainedCompletions\":${snapshot.completions.size},\"retainedRequests\":${snapshot.requests.size}," +
        "\"retainedLedgerTransactions\":${snapshot.ledgerTransactions.size},\"feeRevenue\":$fees}"
}

fun main(args: Array<String>) {
    println(runLegacyTradingEngineProbe(tradingEngineProbeOptions(args)))
}
