package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.Amount
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AckStatus
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.EpochStatus
import com.exchange.core.trading.ExecuteAdmission
import com.exchange.core.trading.ExecutionReply
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingState
import java.math.BigInteger

internal fun runBoundedTradingEngineProbe(options: TradingEngineProbeOptions): String {
    val workload = TradingEngineProbeWorkload
    val (admission, settlement) = workload.calculators()
    val state = TradingState(admission, settlement, boundedMode = true)
    val lineage = ClusterLineage(0x424F554E444544, 142)
    state.initialize(
        TradingClusterManifest(
            lineage,
            "paired-engine-probe-v1",
            "0".repeat(64),
            "1".repeat(64),
            1,
            workload.markets.mapIndexed { index, market -> TradingMarketContext(market, workload.policy, index) },
            TradingResourceLimits(),
        ),
    )
    state.prepareDevelopmentBalance(
        OpeningBalance("fund-buyer", workload.buyer, workload.quote, Amount(workload.quoteFunding(options.total))),
        "ignored-bounded-opening",
        workload.time,
    )
    for ((ordinal, market) in workload.markets.withIndex()) {
        val amount = workload.countForMarket(options.total, ordinal).toLong() * workload.quantity(market).value
        if (amount !=
            0L
        ) {
            state.prepareDevelopmentBalance(
                OpeningBalance("fund-$ordinal", workload.seller, market.baseAssetId, Amount(amount)),
                "ignored-bounded-$ordinal",
                workload.time,
            )
        }
    }
    for (user in listOf(workload.buyer, workload.seller)) check(state.rotateEpoch(user, 0, 0).status == EpochStatus.ISSUED)

    fun acknowledge(
        side: Side,
        through: Long,
    ) {
        val user = if (side == Side.SELL) workload.seller else workload.buyer
        check(state.acknowledgeReceipts(user, 0, 1, through).status == AckStatus.ACKNOWLEDGED)
    }

    fun execute(
        index: Int,
        side: Side,
    ) {
        val market = workload.market(index)
        val user = if (side == Side.SELL) workload.seller else workload.buyer
        val sequence = index + 1L
        val token = RequestToken(user, 0, 1, sequence)
        val intent = OrderIntent(market.marketId, 1, side, OrderType.LIMIT, TimeInForce.GTC, workload.price, workload.quantity(market))
        val admitted = state.admit(token, intent, workload.time)
        check(admitted is AdmissionReply.Recorded && admitted.newlyRecorded && admitted.receipt.code == AdmissionCode.ACCEPTED) {
            "unexpected admission: $admitted"
        }
        val receipt = admitted.receipt
        val completed =
            state.executePending(
                ExecuteAdmission(
                    lineage,
                    market.marketId,
                    checkNotNull(receipt.marketAdmissionSequence),
                    checkNotNull(receipt.admissionSequence),
                ),
                workload.time,
            )
        check(completed is ExecutionReply.Applied)
        if (side == Side.BUY) check(completed.receipt.fillCount == 1)
        if (sequence % workload.ACK_EVERY == 0L) acknowledge(side, sequence)
    }
    repeat(options.total) { execute(it, Side.SELL) }
    acknowledge(Side.SELL, options.total.toLong())
    val measured = measureTradingEngineProbe(options, { execute(it, Side.BUY) }, { acknowledge(Side.BUY, options.total.toLong()) })
    workload.verifyBalances(options.total, state::currentBalance)
    val snapshot = state.recoveryState()
    val fees = snapshot.feeRevenueByAsset[workload.quote] ?: BigInteger.ZERO
    check(fees == BigInteger.valueOf(options.total * workload.FEE_PER_TRADE))
    check(snapshot.liveOrders.isEmpty() && snapshot.markets.all { it.pendingAdmissionSequences.isEmpty() })
    check(workload.markets.all { state.book(it.marketId).isEmpty() })
    val retention = state.boundedRetention()
    check(retention.liveOrders == 0 && retention.pendingOrders == 0 && retention.receipts == 0 && retention.receiptCanonicalBytes == 0L)
    return "{\"mode\":\"bounded-inbox-ack256\",\"scope\":\"single-JVM BUY admit+execute+ACK; transport excluded\"," +
        "\"warmup\":${options.warmup},\"markets\":\"BTC/SOL/SUI\",\"sellPreparation\":${options.total}," +
        "\"ackEvery\":${workload.ACK_EVERY},${measured.fields()},\"financiallyVerified\":true," +
        "\"retainedLiveOrders\":${retention.liveOrders},\"retainedReceipts\":${retention.receipts}," +
        "\"retainedReceiptCanonicalBytes\":${retention.receiptCanonicalBytes},\"feeRevenue\":$fees}"
}

fun main(args: Array<String>) {
    val options = tradingEngineProbeOptions(args)
    println(if ("--legacy" in args) runLegacyTradingEngineProbe(options) else runBoundedTradingEngineProbe(options))
}
