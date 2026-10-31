package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
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
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.SettlementLedgerTransactions
import java.lang.management.ManagementFactory
import java.time.Instant
import kotlin.math.ceil

/** 새 도메인 API에 의존하지 않고 legacy/bounded 진입점이 공유하는 작은 비교 측정이다. */
internal data class TradingEngineProbeOptions(
    val warmup: Int = 1_000,
    val orders: Int = 5_000,
) {
    val total: Int get() = Math.addExact(warmup, orders)

    init {
        require(warmup >= 0 && orders > 0 && warmup.toLong() + orders <= 6_000)
    }
}

internal object TradingEngineProbeWorkload {
    val buyer = UserId("BUYER")
    val seller = UserId("SELLER")
    val quote = AssetId("USDT")
    val price = Price(10_000)
    const val RESERVE_PER_BUY = 10_020L
    const val FEE_PER_TRADE = 30L
    const val SELLER_NET_PER_TRADE = 9_990L
    const val ACK_EVERY = 256L
    val time: Instant = Instant.parse("2026-10-27T00:00:00Z")
    val markets =
        listOf("BTC" to 8, "SOL" to 9, "SUI" to 9).map { (asset, scale) ->
            MarketDefinition(MarketId("$asset-USDT"), AssetId(asset), quote, scale)
        }
    val policy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(1_000), FeeRate(2_000)))

    fun market(index: Int): MarketDefinition = markets[index % markets.size]

    fun quantity(market: MarketDefinition): Quantity = Quantity(if (market.baseAssetScale == 8) 100_000_000 else 1_000_000_000)

    fun countForMarket(
        total: Int,
        ordinal: Int,
    ): Int = total / markets.size + if (ordinal < total % markets.size) 1 else 0

    fun quoteFunding(total: Int): Long = Math.addExact(Math.multiplyExact(total.toLong(), RESERVE_PER_BUY), 1_000)

    fun calculators(): Pair<OrderAdmissionCalculator, OrderSettlementCalculator> {
        val fees = TradingFeeReserveCalculator()
        val reservation = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fees))
        return OrderAdmissionCalculator(reservation) to
            OrderSettlementCalculator(
                OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
                reservation,
                fees,
                SettlementLedgerTransactions(),
            )
    }

    fun verifyBalances(
        total: Int,
        balance: (UserId, AssetId) -> Balance,
    ) {
        check(balance(buyer, quote).available == Amount(1_000))
        check(balance(seller, quote).available == Amount(total * SELLER_NET_PER_TRADE))
        for ((ordinal, market) in markets.withIndex()) {
            val amount = countForMarket(total, ordinal).toLong() * quantity(market).value
            check(balance(buyer, market.baseAssetId).available == Amount(amount))
            check(balance(seller, market.baseAssetId).available == Amount.ZERO)
            check(balance(buyer, market.baseAssetId).hold == Amount.ZERO)
            check(balance(seller, market.baseAssetId).hold == Amount.ZERO)
        }
        check(balance(buyer, quote).hold == Amount.ZERO && balance(seller, quote).hold == Amount.ZERO)
    }
}

internal data class TradingEngineProbeMeasurement(
    val completed: Int,
    val elapsedNs: Long,
    val currentThreadCpuNs: Long?,
    val currentThreadAllocatedBytes: Long?,
    val p95Ns: Long,
    val p99Ns: Long,
    val finalAckNs: Long,
) {
    val tps: Double get() = completed * 1_000_000_000.0 / elapsedNs

    fun fields(): String =
        "\"completed\":$completed,\"elapsedNs\":$elapsedNs,\"tps\":$tps," +
            "\"currentThreadCpuNs\":$currentThreadCpuNs,\"currentThreadCpuNsPerOrder\":${currentThreadCpuNs?.div(completed.toDouble())}," +
            "\"currentThreadAllocatedBytes\":$currentThreadAllocatedBytes,\"allocatedBytesPerOrder\":${currentThreadAllocatedBytes?.div(
                completed.toDouble(),
            )}," +
            "\"p95Ns\":$p95Ns,\"p99Ns\":$p99Ns,\"finalAckNs\":$finalAckNs"
}

internal fun measureTradingEngineProbe(
    options: TradingEngineProbeOptions,
    buy: (Int) -> Unit,
    finish: () -> Unit = {},
): TradingEngineProbeMeasurement {
    val bean = ManagementFactory.getThreadMXBean()
    if (bean.isCurrentThreadCpuTimeSupported && !bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true
    val allocation = bean as? com.sun.management.ThreadMXBean
    if (allocation?.isThreadAllocatedMemorySupported == true &&
        !allocation.isThreadAllocatedMemoryEnabled
    ) {
        allocation.isThreadAllocatedMemoryEnabled = true
    }
    val threadId = Thread.currentThread().threadId()

    fun cpu(): Long? = if (bean.isCurrentThreadCpuTimeSupported) bean.currentThreadCpuTime.takeIf { it >= 0 } else null

    fun allocated(): Long? =
        if (allocation?.isThreadAllocatedMemorySupported ==
            true
        ) {
            allocation.getThreadAllocatedBytes(threadId).takeIf { it >= 0 }
        } else {
            null
        }
    repeat(options.warmup, buy)
    val latency = LongArray(options.orders)
    val cpuBefore = cpu()
    val bytesBefore = allocated()
    val began = System.nanoTime()
    repeat(options.orders) { index ->
        val start = System.nanoTime()
        buy(options.warmup + index)
        latency[index] = System.nanoTime() - start
    }
    val beforeFinalAck = System.nanoTime()
    finish()
    val end = System.nanoTime()
    val elapsed = end - began
    val cpuAfter = cpu()
    val bytesAfter = allocated()
    latency.sort()

    fun percentile(value: Double): Long = latency[(ceil(value * latency.size).toInt() - 1).coerceIn(0, latency.lastIndex)]
    return TradingEngineProbeMeasurement(
        options.orders,
        elapsed,
        if (cpuBefore != null && cpuAfter != null) cpuAfter - cpuBefore else null,
        if (bytesBefore != null && bytesAfter != null) bytesAfter - bytesBefore else null,
        percentile(0.95),
        percentile(0.99),
        end - beforeFinalAck,
    )
}

internal fun tradingEngineProbeOptions(args: Array<String>): TradingEngineProbeOptions {
    require(args.all { it.startsWith("--warmup=") || it.startsWith("--orders=") || it == "--legacy" }) { "unknown engine probe option" }
    require(args.map { it.substringBefore('=') }.distinct().size == args.size) { "duplicate engine probe option" }
    return TradingEngineProbeOptions(
        args.singleOrNull { it.startsWith("--warmup=") }?.substringAfter('=')?.toInt() ?: 1_000,
        args.singleOrNull { it.startsWith("--orders=") }?.substringAfter('=')?.toInt() ?: 5_000,
    )
}
