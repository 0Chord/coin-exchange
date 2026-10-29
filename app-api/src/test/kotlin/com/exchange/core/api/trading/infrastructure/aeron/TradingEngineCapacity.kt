package com.exchange.core.api.trading.infrastructure.aeron

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
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.matching.MatchingEngine
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingState
import jdk.jfr.Configuration
import jdk.jfr.Recording
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil

internal data class TradingEngineCapacityOptions(
    val warmup: Int = 10_000,
    val orders: Int = 10_000,
    val durationSeconds: Long? = null,
    val maxHeapFraction: Double = 0.65,
    val postGc: Boolean = false,
    val jfrPath: String? = null,
    val pacedRate: Int? = null,
    val preGc: Boolean = false,
) {
    init {
        require(warmup in 0..100_000 && orders in 1..2_000_000)
        require(durationSeconds == null || durationSeconds in 1..3600)
        require(maxHeapFraction in 0.10..0.75)
        require(
            pacedRate == null ||
                (
                    pacedRate in 1..2_000_000 && durationSeconds != null &&
                        orders.toLong() == Math.multiplyExact(pacedRate.toLong(), durationSeconds)
                ),
        ) { "paced orders must equal rate * seconds within the existing 2,000,000-order limit" }
        require(jfrPath == null || Path.of(jfrPath).let { it.isAbsolute && it.fileName != null }) {
            "JFR output must be an absolute file path"
        }
    }
}

internal data class TradingEnginePacedProgress(
    val elapsedNs: Long,
    val attempts: Int,
    val completed: Int,
    val failedAttempts: Int,
    val heapUsedBytes: Long,
    val gcCollectionCountDelta: Long,
    val gcCollectionTimeMsDelta: Long,
)

/** Window membership is [0, windowNs); all comparisons use differences from one JVM's first due time. */
internal data class TradingEnginePacedSummary(
    val rate: Int,
    val plannedOrders: Int,
    val firstDueNs: Long,
    val windowNs: Long,
    val windowReached: Boolean,
    val startedWithinWindow: Int,
    val completedWithinWindow: Int,
    val completedAtOrAfterWindowEnd: Int,
    val failedAttempts: Int,
    val notExecuted: Int,
    val firstNotExecutedDueOffsetNs: Long?,
    val lastNotExecutedDueOffsetNs: Long?,
    val notExecutedDelayLowerBoundNs: Long?,
    val scheduledStartDelay: TradingEngineCapacityLatency,
    val scheduledAttemptLatency: TradingEngineCapacityLatency,
    val progress: List<TradingEnginePacedProgress>,
    val progressOverflow: Long,
)

internal data class TradingEngineCapacityLatency(
    val count: Long,
    val totalNs: Long,
    val meanNs: Double?,
    val p50UpperBoundNs: Long?,
    val p95UpperBoundNs: Long?,
    val p99UpperBoundNs: Long?,
    val maxNs: Long?,
)

/** 2,016 fixed buckets; every attempt, including an exception, contributes to the distribution. */
internal class TradingEngineCapacityHistogram {
    private val buckets = LongArray(63 * 32)
    private var count = 0L
    private var totalNs = 0L
    private var maximum = 0L
    private var zeroCount = 0L

    fun record(durationNs: Long) {
        require(durationNs >= 0)
        if (durationNs == 0L) {
            zeroCount++
        } else {
            val exponent = 63 - java.lang.Long.numberOfLeadingZeros(durationNs)
            val base = 1L shl exponent
            val width = 1L shl maxOf(0, exponent - 5)
            val offset = ((durationNs - base) / width).toInt()
            buckets[exponent * 32 + offset]++
        }
        count++
        totalNs = Math.addExact(totalNs, durationNs)
        maximum = maxOf(maximum, durationNs)
    }

    fun summary(): TradingEngineCapacityLatency {
        fun quantile(fraction: Double): Long? {
            if (count == 0L) return null
            val target = ceil(count * fraction).toLong()
            if (target <= zeroCount) return 0
            var seen = zeroCount
            for ((index, frequency) in buckets.withIndex()) {
                seen += frequency
                if (seen >= target) {
                    val exponent = index / 32
                    val width = 1L shl maxOf(0, exponent - 5)
                    val base = 1L shl exponent
                    val offset = index % 32
                    val upper = if (exponent == 62 && offset == 31) Long.MAX_VALUE else base + offset * width + (width - 1)
                    return minOf(upper, maximum)
                }
            }
            error("histogram count mismatch")
        }
        return TradingEngineCapacityLatency(
            count,
            totalNs,
            totalNs.takeIf { count > 0 }?.toDouble()?.div(count),
            quantile(0.50),
            quantile(0.95),
            quantile(0.99),
            maximum.takeIf { count > 0 },
        )
    }
}

internal data class TradingEngineCapacityResult(
    val options: TradingEngineCapacityOptions,
    val stopReason: String,
    val preparationSells: Int,
    val warmupCompleted: Int,
    val measuredAttempts: Int,
    val measuredCompleted: Int,
    val elapsedNs: Long,
    val completedTransactionsPerSecond: Double?,
    val latency: TradingEngineCapacityLatency,
    val runtime: Map<String, Any?>,
    val stateCheck: Map<String, Any?>?,
    val errors: List<String>,
    val failureStateEvidence: Map<String, Any?>? = null,
    val paced: TradingEnginePacedSummary? = null,
) {
    val measurementMode: String
        get() =
            when {
                options.jfrPath != null -> "JFR_PROFILE"
                options.preGc -> "PRE_GC_DIAGNOSTIC"
                else -> "BASELINE"
            }

    val profiled: Boolean
        get() = runtime["profiled"] == true

    val fixedCohortComplete: Boolean
        get() = options.durationSeconds == null && stopReason == "ORDER_LIMIT" && measuredCompleted == options.orders && errors.isEmpty()

    val sustainedWindowComplete: Boolean
        get() =
            options.pacedRate == null && options.durationSeconds != null && stopReason == "DURATION_REACHED" && measuredCompleted > 0 &&
                measuredCompleted == measuredAttempts &&
                errors.isEmpty()

    val pacedWindowComplete: Boolean
        get() =
            options.pacedRate != null && stopReason == "PACED_WINDOW_REACHED" &&
                measuredAttempts == options.orders && measuredCompleted == options.orders &&
                paced?.windowReached == true && paced.completedWithinWindow == options.orders &&
                paced.completedAtOrAfterWindowEnd == 0 && paced.notExecuted == 0 && paced.failedAttempts == 0 &&
                paced.progressOverflow == 0L && stateCheck?.get("verified") == true && errors.isEmpty()
}

private val engineCapacityMarket = MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), AssetId("USDT"), 8)
private val engineCapacityPolicy =
    TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(1000), FeeRate(2000)))
private val engineCapacityTime = Instant.parse("2026-10-27T00:00:00Z")
private val engineCapacityBuyer = UserId("BUYER")
private val engineCapacitySeller = UserId("SELLER")
private const val ENGINE_CAPACITY_QUANTITY = 100_000_000L
private const val ENGINE_CAPACITY_BUY_RESERVE = 10_020L
private const val ENGINE_CAPACITY_HEAP_CHECK_INTERVAL = 128
private val engineCapacityMemoryBean = ManagementFactory.getMemoryMXBean()

private fun engineCapacityState(): TradingState {
    val feeReserve = TradingFeeReserveCalculator()
    val reservation = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(feeReserve))
    return TradingState(
        OrderAdmissionCalculator(reservation),
        OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), feeReserve),
            reservation,
            feeReserve,
            SettlementLedgerTransactions(),
        ),
    )
}

private fun engineCapacityOrder(
    id: String,
    user: UserId,
    side: Side,
): OrderAdmissionInput =
    OrderAdmissionInput(
        engineCapacityMarket.marketId,
        OrderId(id),
        user,
        side,
        OrderType.LIMIT,
        TimeInForce.GTC,
        Price(10_000),
        Quantity(ENGINE_CAPACITY_QUANTITY),
    )

/** One caller owns the real state. No transport, persistence, history reset or snapshots in the timed loop. */
internal fun runTradingEngineCapacity(
    options: TradingEngineCapacityOptions,
    nanoTime: () -> Long = System::nanoTime,
    heapUsedBytes: () -> Long = { engineCapacityMemoryBean.heapMemoryUsage.used },
    maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
    // Test-only fault injection; never replaces admission, matching or settlement on a successful attempt.
    beforeMeasuredExecution: ((Int) -> Unit)? = null,
    // Only paced runs wait. Tests advance the same fake nanoTime clock instead of sleeping.
    parkNanos: (Long) -> Unit = { LockSupport.parkNanos(it) },
): TradingEngineCapacityResult {
    val state = engineCapacityState()
    val total = options.warmup.toLong() + options.orders
    state.prepareDevelopmentBalance(
        OpeningBalance(
            "fund-buyer",
            engineCapacityBuyer,
            engineCapacityMarket.quoteAssetId,
            Amount(total * ENGINE_CAPACITY_BUY_RESERVE + 1000),
        ),
        "opening-buyer",
        engineCapacityTime,
    )
    state.prepareDevelopmentBalance(
        OpeningBalance("fund-seller", engineCapacitySeller, engineCapacityMarket.baseAssetId, Amount(total * ENGINE_CAPACITY_QUANTITY)),
        "opening-seller",
        engineCapacityTime,
    )
    val heapLimit = (maxHeapBytes * options.maxHeapFraction).toLong()
    var peakHeap = 0L

    fun heapLimitReached(): Boolean {
        val used = heapUsedBytes()
        peakHeap = maxOf(peakHeap, used)
        return used >= heapLimit
    }
    var sells = 0
    var warmed = 0
    var attempts = 0
    var completed = 0
    var stop = "ORDER_LIMIT"
    val errors = mutableListOf<String>()
    var replayInput: OrderAdmissionInput? = null
    var replayAdmission: OrderAdmissionState.Result.Accepted? = null
    var replayCompletion: TradingState.Completion? = null
    val histogram = TradingEngineCapacityHistogram()

    fun execute(input: OrderAdmissionInput): TradingState.Completion {
        val result =
            state.admit(
                input.orderId.value,
                input,
                engineCapacityMarket,
                engineCapacityPolicy,
                "reserve-${input.orderId.value}",
                engineCapacityTime,
            )
        check(result is OrderAdmissionState.Result.Accepted) { "unexpected admission result: $result" }
        val completion = state.executePending(result.record.sequence, engineCapacityTime)
        if (input.side == Side.BUY && replayInput == null) {
            replayInput = input
            replayAdmission = result
            replayCompletion = completion
        }
        return completion
    }
    try {
        while (sells.toLong() < total) {
            if (sells % ENGINE_CAPACITY_HEAP_CHECK_INTERVAL == 0 && heapLimitReached()) {
                stop = "PREPARATION_HEAP_LIMIT"
                break
            }
            execute(engineCapacityOrder("S-$sells", engineCapacitySeller, Side.SELL))
            sells++
        }
        if (sells.toLong() == total) {
            while (warmed < options.warmup) {
                if (warmed % ENGINE_CAPACITY_HEAP_CHECK_INTERVAL == 0 && heapLimitReached()) {
                    stop = "WARMUP_HEAP_LIMIT"
                    break
                }
                execute(engineCapacityOrder("W-$warmed", engineCapacityBuyer, Side.BUY))
                warmed++
            }
        }
    } catch (failure: Exception) {
        stop = "PREPARATION_FAILURE"
        errors += "preparation: ${failure.stackTraceToString()}"
    }

    val preGc =
        if (options.preGc && stop == "ORDER_LIMIT" && warmed == options.warmup && sells.toLong() == total) {
            engineCapacityGcDiagnostic(
                "after preparation and warmup, before JFR and all measurement counters; state remains reachable; not a sustainable maximum test",
                heapUsedBytes,
            )
        } else {
            null
        }
    val allocation = EngineCapacityThreadAllocation()
    val cpuTime = EngineCapacityThreadCpuTime()
    var recording: Recording? = null
    var profileStarted = false
    var profileStopped = false
    var profileDumped = false
    var profileBytes: Long? = null
    val profileFailedStages = mutableListOf<String>()
    val profilePath = options.jfrPath?.let { Path.of(it).normalize() }
    if (stop == "ORDER_LIMIT" && warmed == options.warmup && sells.toLong() == total && profilePath != null) {
        try {
            check(!Files.exists(profilePath)) { "JFR output already exists: $profilePath" }
            Files.createDirectories(profilePath.parent)
            val active = Recording(Configuration.getConfiguration("profile"))
            recording = active
            active.name = "trading-engine-capacity"
            active.maxSize = 256L * 1024 * 1024
            active.start()
            profileStarted = true
        } catch (failure: Exception) {
            stop = "PROFILE_START_FAILURE"
            profileFailedStages += "START"
            errors += "profile start: ${failure.stackTraceToString()}"
        }
    }
    val gcBefore = engineCapacityGc()
    val heapBefore = heapUsedBytes()
    val cpuBefore = cpuTime.read()
    val allocatedBefore = allocation.read()
    val measuredStart = nanoTime()
    val pacedRate = options.pacedRate
    val pacedWindowNs = if (pacedRate != null) requireNotNull(options.durationSeconds) * 1_000_000_000L else null
    val scheduledStartDelay = if (pacedRate != null) TradingEngineCapacityHistogram() else null
    val scheduledAttemptLatency = if (pacedRate != null) TradingEngineCapacityHistogram() else null
    val progressCapacity = if (pacedRate != null) requireNotNull(options.durationSeconds).toInt() + 2 else 0
    val pacedProgress = if (pacedRate != null) ArrayList<TradingEnginePacedProgress>(progressCapacity) else null
    var progressOverflow = 0L
    var lastProgressSecond = 0L
    var startedWithinWindow = 0
    var completedWithinWindow = 0
    var completedAtOrAfterWindowEnd = 0
    pacedProgress?.add(TradingEnginePacedProgress(0, 0, 0, 0, heapBefore, 0, 0))

    fun recordPacedProgress(nowNs: Long) {
        val frames = pacedProgress ?: return
        val elapsedNs = nowNs - measuredStart
        check(elapsedNs >= 0) { "paced nanoTime difference is negative" }
        val elapsedSecond = elapsedNs / 1_000_000_000L
        if (elapsedSecond <= lastProgressSecond) return
        lastProgressSecond = elapsedSecond
        if (frames.size >= progressCapacity) {
            progressOverflow++
            return
        }
        val used = heapUsedBytes()
        peakHeap = maxOf(peakHeap, used)
        val gc = engineCapacityGc()
        frames.add(
            TradingEnginePacedProgress(
                elapsedNs,
                attempts,
                completed,
                attempts - completed,
                used,
                gc.first - gcBefore.first,
                gc.second - gcBefore.second,
            ),
        )
    }

    fun waitForPacedOffset(offsetNs: Long): Long {
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("paced wait interrupted")
            val nowNs = nanoTime()
            // Valid plans span at most one hour, far below nanoTime's signed-difference horizon.
            val elapsedNs = nowNs - measuredStart
            check(elapsedNs >= 0) { "paced nanoTime difference is negative" }
            recordPacedProgress(nowNs)
            if (elapsedNs >= offsetNs) return nowNs
            parkNanos(minOf(offsetNs - elapsedNs, 1_000_000L))
        }
    }
    if (stop == "ORDER_LIMIT" && warmed == options.warmup && sells.toLong() == total) {
        try {
            while (attempts < options.orders) {
                val dueOffsetNs = if (pacedRate != null) attempts.toLong() * 1_000_000_000L / pacedRate else null
                if (dueOffsetNs != null) {
                    if (waitForPacedOffset(dueOffsetNs) - measuredStart >= requireNotNull(pacedWindowNs)) {
                        stop = "PACED_WINDOW_REACHED"
                        break
                    }
                } else if (options.durationSeconds != null && nanoTime() - measuredStart >= options.durationSeconds * 1_000_000_000L) {
                    stop = "DURATION_REACHED"
                    break
                }
                if (attempts % ENGINE_CAPACITY_HEAP_CHECK_INTERVAL == 0 && heapLimitReached()) {
                    stop = "MEASUREMENT_HEAP_LIMIT"
                    break
                }
                val input = engineCapacityOrder("M-$attempts", engineCapacityBuyer, Side.BUY)
                val start = nanoTime()
                if (dueOffsetNs != null && start - measuredStart >= requireNotNull(pacedWindowNs)) {
                    stop = "PACED_WINDOW_REACHED"
                    break
                }
                attempts++
                var executionCompleted = false
                try {
                    beforeMeasuredExecution?.invoke(attempts - 1)
                    execute(input)
                    completed++
                    executionCompleted = true
                } finally {
                    val end = nanoTime()
                    histogram.record(end - start)
                    if (dueOffsetNs != null) {
                        startedWithinWindow++
                        requireNotNull(scheduledStartDelay).record(start - measuredStart - dueOffsetNs)
                        val completionOffsetNs = end - measuredStart
                        requireNotNull(scheduledAttemptLatency).record(completionOffsetNs - dueOffsetNs)
                        if (executionCompleted) {
                            if (completionOffsetNs <
                                requireNotNull(pacedWindowNs)
                            ) {
                                completedWithinWindow++
                            } else {
                                completedAtOrAfterWindowEnd++
                            }
                        }
                        recordPacedProgress(end)
                    }
                }
            }
            if (pacedWindowNs != null && stop == "ORDER_LIMIT") {
                waitForPacedOffset(pacedWindowNs)
                stop = "PACED_WINDOW_REACHED"
            }
        } catch (failure: Exception) {
            stop = "MEASUREMENT_FAILURE"
            errors += "measurement: ${failure.stackTraceToString()}"
            if (pacedRate != null && failure is InterruptedException) Thread.currentThread().interrupt()
        }
    }
    val elapsed = nanoTime() - measuredStart
    val allocatedAfter = allocation.read()
    val cpuAfter = cpuTime.read()
    val heapAfter = heapUsedBytes()
    peakHeap = maxOf(peakHeap, heapBefore, heapAfter)
    val gcAfter = engineCapacityGc()
    recording?.let { active ->
        var finishingStage = "STOP"
        try {
            if (profileStarted) {
                active.stop()
                profileStopped = true
                finishingStage = "DUMP"
                active.dump(requireNotNull(profilePath))
                profileDumped = true
                finishingStage = "DUMP_METADATA"
                profileBytes = Files.size(profilePath)
            }
        } catch (failure: Exception) {
            profileFailedStages += finishingStage
            errors += "profile ${finishingStage.lowercase()}: ${failure.stackTraceToString()}"
        } finally {
            try {
                active.close()
            } catch (failure: Exception) {
                profileFailedStages += "CLOSE"
                errors += "profile close: ${failure.stackTraceToString()}"
            }
        }
    }
    val postGc =
        if (options.postGc) {
            engineCapacityGcDiagnostic(
                "after measurement and before the sole snapshot; state remains strongly reachable; explicit GC request may be disabled or asynchronous",
                heapUsedBytes,
            )
        } else {
            null
        }
    var capturedFinalSnapshot: TradingState.Snapshot? = null
    var finalSnapshotCaptureAttempted = false
    val stateCheck =
        try {
            var duplicateVerified: Boolean? = null
            replayInput?.let { input ->
                val result =
                    state.admit(
                        input.orderId.value,
                        input,
                        engineCapacityMarket,
                        engineCapacityPolicy,
                        "unused-replay",
                        engineCapacityTime.plusSeconds(1),
                    )
                check(result === replayAdmission) { "duplicate admission did not reuse the first result" }
                check(
                    state.executePending(requireNotNull(replayAdmission).record.sequence, engineCapacityTime.plusSeconds(1)) ===
                        replayCompletion,
                ) { "duplicate execution did not reuse the first completion for ${input.orderId.value}" }
                duplicateVerified = true
            }
            val unfunded = engineCapacityOrder("REJECTED-UNFUNDED", UserId("UNFUNDED"), Side.BUY)
            val rejection =
                state.admit(
                    "REJECTED-UNFUNDED",
                    unfunded,
                    engineCapacityMarket,
                    engineCapacityPolicy,
                    "unused-rejected",
                    engineCapacityTime,
                )
            check(
                rejection is OrderAdmissionState.Result.Rejected && rejection.reason == OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE,
            ) {
                "unfunded BUY expected INSUFFICIENT_BALANCE, actual=$rejection"
            }
            check(rejection.insufficientBalance?.available == Amount.ZERO) { "unfunded BUY must have zero available balance" }
            check(rejection.insufficientBalance?.required == Amount(ENGINE_CAPACITY_BUY_RESERVE)) {
                "unfunded BUY expected reserve=$ENGINE_CAPACITY_BUY_RESERVE, actual=${rejection.insufficientBalance?.required}"
            }
            // This is the only full state snapshot in the run, after all measurement and replay checks.
            finalSnapshotCaptureAttempted = true
            val snapshot = state.snapshot().also { capturedFinalSnapshot = it }
            checkEngineCapacityState(state, snapshot, total, sells, warmed, attempts, completed, duplicateVerified)
        } catch (failure: Exception) {
            errors += "state verification: ${failure.stackTraceToString()}"
            null
        }
    val allocated = if (allocatedBefore != null && allocatedAfter != null) allocatedAfter - allocatedBefore else null
    val cpuNs = if (cpuBefore != null && cpuAfter != null) cpuAfter - cpuBefore else null
    val pacedSummary =
        if (pacedRate != null) {
            val windowNs = requireNotNull(pacedWindowNs)
            val notExecuted = options.orders - attempts
            val firstMissing = if (notExecuted > 0) attempts.toLong() * 1_000_000_000L / pacedRate else null
            val lastMissing = if (notExecuted > 0) (options.orders - 1L) * 1_000_000_000L / pacedRate else null
            TradingEnginePacedSummary(
                rate = pacedRate,
                plannedOrders = options.orders,
                firstDueNs = measuredStart,
                windowNs = windowNs,
                windowReached = elapsed >= windowNs,
                startedWithinWindow = startedWithinWindow,
                completedWithinWindow = completedWithinWindow,
                completedAtOrAfterWindowEnd = completedAtOrAfterWindowEnd,
                failedAttempts = attempts - completed,
                notExecuted = notExecuted,
                firstNotExecutedDueOffsetNs = firstMissing,
                lastNotExecutedDueOffsetNs = lastMissing,
                notExecutedDelayLowerBoundNs = lastMissing?.let { (elapsed - it).coerceAtLeast(0) },
                scheduledStartDelay = requireNotNull(scheduledStartDelay).summary(),
                scheduledAttemptLatency = requireNotNull(scheduledAttemptLatency).summary(),
                progress = requireNotNull(pacedProgress).toList(),
                progressOverflow = progressOverflow,
            )
        } else {
            null
        }
    val result =
        TradingEngineCapacityResult(
            options,
            stop,
            sells,
            warmed,
            attempts,
            completed,
            elapsed,
            if (attempts > 0 && elapsed > 0) completed * 1_000_000_000.0 / elapsed else null,
            histogram.summary(),
            mapOf(
                "heapBeforeMeasurementBytes" to heapBefore,
                "heapAfterMeasurementBytes" to heapAfter,
                "maxObservedHeapUsedBytes" to peakHeap,
                "heapAdmissionLimitBytes" to heapLimit,
                "maxHeapBytes" to maxHeapBytes,
                "heapChecks" to "before first and every 128th SELL, warmup BUY and measured BUY; up to 127 operations between observations",
                "gcCollectionCountDelta" to (gcAfter.first - gcBefore.first),
                "gcCollectionTimeMsDelta" to (gcAfter.second - gcBefore.second),
                "currentThreadAllocatedBytes" to allocated,
                "currentThreadAllocatedBytesPerAttempt" to allocated?.takeIf { attempts > 0 }?.toDouble()?.div(attempts),
                "allocationUnavailableReason" to allocation.unavailableReason,
                "allocationBoundary" to
                    "single caller thread; measured loop including input creation, timers, histogram and heap checks; excludes final verification",
                "currentThreadCpuTimeNs" to cpuNs,
                "currentThreadCpuTimeNsPerAttempt" to cpuNs?.takeIf { attempts > 0 }?.toDouble()?.div(attempts),
                "cpuTimeUnavailableReason" to cpuTime.unavailableReason,
                "cpuTimeBoundary" to
                    "current-thread CPU counter around the whole loop; includes boundary counter overhead; excludes JFR lifecycle and final verification; separate from elapsed wall time",
                "testOnlyFailureInjectionEnabled" to (beforeMeasuredExecution != null),
                "profiled" to profileStarted,
                "jfr" to
                    mapOf(
                        "requested" to (profilePath != null),
                        "path" to profilePath?.toString(),
                        "configuration" to "profile",
                        "maximumRecordingBytes" to 256L * 1024 * 1024,
                        "started" to profileStarted,
                        "stopped" to profileStopped,
                        "dumped" to profileDumped,
                        "dumpBytes" to profileBytes,
                        "failedStages" to profileFailedStages.toList(),
                        "boundary" to
                            "start after successful preparation and warmup, before measurement counters; stop after end counters; stop, dump and close outside elapsed/CPU/allocation/GC measurements",
                        "comparison" to "JFR_PROFILE is a diagnostic run and must be compared separately from BASELINE",
                    ),
                "histogram" to
                    mapOf(
                        "positiveBuckets" to 2016,
                        "zeroBucket" to true,
                        "rangeNs" to "0..Long.MAX_VALUE",
                        "percentiles" to "bucket upper bounds",
                        "maximumRelativeBucketWidth" to 0.03125,
                        "exactBelowNs" to 64,
                    ),
                "preGcDiagnostic" to preGc,
                "postGcDiagnostic" to postGc,
                "pacing" to
                    pacedRate?.let {
                        mapOf(
                            "rate" to it,
                            "schedule" to "first due plus floor(i * 1,000,000,000 / rate); schedule never resets to actual starts",
                            "clock" to "same-JVM nanoTime differences; no absolute signed-clock comparisons",
                            "window" to "[first due, first due + requested seconds)",
                            "businessLatency" to
                                "admit + executePending attempt only; excludes all park and caller preparation; includes failed attempts",
                            "scheduledAttemptLatency" to
                                "planned due to attempt end, including failed attempts; unexecuted requests reported separately",
                            "wallDuration" to
                                "whole measured loop including all paced waits and the final wait through the scheduled window end",
                            "progress" to
                                "bounded observations at most once per elapsed second plus initial state; no TradingState snapshot",
                            "progressCapacity" to progressCapacity,
                            "heapGuard" to
                                "existing fraction and 128-operation checks unchanged; progress reads are observations only",
                        )
                    },
            ),
            stateCheck,
            errors,
            paced = pacedSummary,
        )
    // Full financial verification above still uses the sole snapshot. Only avoid serializing it again for a pure timing miss.
    val timingOnlyPacedMiss =
        pacedSummary != null && !result.pacedWindowComplete && stop == "PACED_WINDOW_REACHED" &&
            sells.toLong() == total && warmed == options.warmup && attempts == options.orders && completed == options.orders &&
            stateCheck?.get("verified") == true && errors.isEmpty() && pacedSummary.windowReached &&
            pacedSummary.completedWithinWindow + pacedSummary.completedAtOrAfterWindowEnd == options.orders &&
            pacedSummary.completedAtOrAfterWindowEnd > 0 && pacedSummary.notExecuted == 0 && pacedSummary.failedAttempts == 0 &&
            pacedSummary.progressOverflow == 0L
    // Keep readable financial state for every other failure. Never bypass a blocked state's normal query guard.
    val failureStateEvidence =
        if (stateCheck == null || errors.isNotEmpty() || stop.contains("HEAP_LIMIT") ||
            (pacedRate != null && !result.pacedWindowComplete)
        ) {
            if (timingOnlyPacedMiss) {
                mapOf(
                    "financialVerificationPassed" to true,
                    "isBlocked" to state.isBlocked,
                    "snapshotCaptureAttempted" to finalSnapshotCaptureAttempted,
                    "snapshotOmittedReason" to
                        "all planned orders completed and the sole final snapshot passed full financial verification; " +
                        "only the paced time window target was missed, so the full snapshot and book are omitted from this report",
                    "stateCheck" to stateCheck,
                    "captureErrors" to emptyList<String>(),
                )
            } else {
                val captureErrors = mutableListOf<String>()
                if (!finalSnapshotCaptureAttempted) {
                    finalSnapshotCaptureAttempted = true
                    try {
                        capturedFinalSnapshot = state.snapshot()
                    } catch (failure: Exception) {
                        captureErrors += "snapshot: ${failure.javaClass.name}: ${failure.message}"
                    }
                }
                val book =
                    try {
                        state.book(engineCapacityMarket.marketId)
                    } catch (failure: Exception) {
                        captureErrors += "book: ${failure.javaClass.name}: ${failure.message}"
                        null
                    }
                mapOf(
                    "financialVerificationPassed" to (stateCheck != null),
                    "isBlocked" to state.isBlocked,
                    "snapshotCaptureAttempted" to finalSnapshotCaptureAttempted,
                    "snapshot" to capturedFinalSnapshot,
                    "book" to book,
                    "captureErrors" to captureErrors.toList(),
                )
            }
        } else {
            null
        }
    return if (failureStateEvidence == null) result else result.copy(failureStateEvidence = failureStateEvidence)
}

private class EngineCapacityThreadCpuTime {
    private val bean = ManagementFactory.getThreadMXBean()
    var unavailableReason: String? = null
        private set

    init {
        try {
            if (!bean.isCurrentThreadCpuTimeSupported) {
                unavailableReason = "ThreadMXBean current-thread CPU time unsupported"
            } else if (!bean.isThreadCpuTimeEnabled) {
                bean.isThreadCpuTimeEnabled = true
            }
        } catch (failure: Exception) {
            unavailableReason = "${failure.javaClass.name}: ${failure.message}"
        }
    }

    fun read(): Long? {
        if (unavailableReason != null) return null
        return try {
            bean.currentThreadCpuTime.takeIf { it >= 0 }.also {
                if (it == null) unavailableReason = "ThreadMXBean returned no current-thread CPU time"
            }
        } catch (failure: Exception) {
            unavailableReason = "${failure.javaClass.name}: ${failure.message}"
            null
        }
    }
}

private class EngineCapacityThreadAllocation {
    private val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    var unavailableReason: String? = null
        private set

    init {
        try {
            if (bean == null || !bean.isThreadAllocatedMemorySupported) {
                unavailableReason = "ThreadMXBean thread allocation counters unsupported"
            } else if (!bean.isThreadAllocatedMemoryEnabled) {
                bean.isThreadAllocatedMemoryEnabled = true
            }
        } catch (failure: Exception) {
            unavailableReason = "${failure.javaClass.name}: ${failure.message}"
        }
    }

    fun read(): Long? {
        if (unavailableReason != null) return null
        return try {
            bean?.getThreadAllocatedBytes(Thread.currentThread().threadId())?.takeIf { it >= 0 }.also {
                if (it == null) unavailableReason = "ThreadMXBean returned no current-thread allocation value"
            }
        } catch (failure: Exception) {
            unavailableReason = "${failure.javaClass.name}: ${failure.message}"
            null
        }
    }
}

private fun engineCapacityGc(): Pair<Long, Long> {
    val collectors = ManagementFactory.getGarbageCollectorMXBeans()
    return collectors.sumOf { maxOf(0, it.collectionCount) } to collectors.sumOf { maxOf(0, it.collectionTime) }
}

private fun checkEngineCapacityState(
    state: TradingState,
    snapshot: TradingState.Snapshot,
    total: Long,
    sells: Int,
    warmup: Int,
    attempts: Int,
    measured: Int,
    duplicateVerified: Boolean?,
): Map<String, Any?> {
    val buys = warmup.toLong() + measured
    val commands = sells.toLong() + buys
    check(!state.isBlocked && snapshot.preparations.size == 2 && snapshot.pendingOrders.isEmpty())
    check(attempts == measured) { "a measured BUY was not completed" }
    check(snapshot.lastAdmissionSequence == commands)
    check(snapshot.completions.size.toLong() == commands && snapshot.matchingResults.size.toLong() == commands)
    check(snapshot.requests.size.toLong() == commands + 1)
    check(snapshot.requests.count { it.result is OrderAdmissionState.Result.Rejected } == 1)
    check(
        snapshot.requests.count {
            it.order.orderId.value
                .startsWith("M-")
        } == attempts,
    )
    check(
        snapshot.requests
            .map { it.order.userId to it.requestId }
            .toSet()
            .size == snapshot.requests.size,
    )
    val expectedBalances =
        mapOf(
            "BUYER/USDT" to ((total - buys) * ENGINE_CAPACITY_BUY_RESERVE + 1000 to 0L),
            "BUYER/BTC" to (buys * ENGINE_CAPACITY_QUANTITY to 0L),
            "SELLER/USDT" to (buys * 9990 to 0L),
            "SELLER/BTC" to ((total - sells) * ENGINE_CAPACITY_QUANTITY to (sells - buys) * ENGINE_CAPACITY_QUANTITY),
        )
    val balances = snapshot.balances.associate { "${it.userId.value}/${it.assetId.value}" to (it.available.value to it.hold.value) }
    check(balances.keys.all { it in expectedBalances })
    expectedBalances.forEach { (key, expected) ->
        check((balances[key] ?: (0L to 0L)) == expected) { "$key expected=$expected actual=${balances[key]}" }
    }
    check(snapshot.reservations.size.toLong() == commands)
    for (reservation in snapshot.reservations) {
        check(reservation.initialQuantity.value == ENGINE_CAPACITY_QUANTITY && reservation.feeRemainder.numerator == 0L)
        val resting =
            reservation.side == Side.SELL && reservation.orderId.value
                .removePrefix("S-")
                .toLong() >= buys
        val expectedStatus = if (resting) OrderReservationStatus.ACTIVE else OrderReservationStatus.SETTLED
        val expectedRemaining = if (resting) ENGINE_CAPACITY_QUANTITY else 0L
        check(reservation.status == expectedStatus) {
            "${reservation.orderId.value} reservation status expected=$expectedStatus actual=${reservation.status}"
        }
        check(reservation.remainingAmount.value == expectedRemaining) {
            "${reservation.orderId.value} remaining amount expected=$expectedRemaining actual=${reservation.remainingAmount.value}"
        }
        check(reservation.remainingQuantity.value == expectedRemaining) {
            "${reservation.orderId.value} remaining quantity expected=$expectedRemaining actual=${reservation.remainingQuantity.value}"
        }
        check(reservation.remainingFeeReserveAmount == Amount.ZERO) {
            "${reservation.orderId.value} remaining fee reserve expected=0 actual=${reservation.remainingFeeReserveAmount.value}"
        }
    }
    val matching = snapshot.matchingResults.associateBy { it.commandId }
    check(matching.size == snapshot.matchingResults.size)
    var settlements = 0L
    for (completion in snapshot.completions) {
        check(matching[completion.executionId] === completion.matchingResult)
        val order = completion.admission.candidate.order
        if (order.side == Side.SELL) {
            check(completion.settlement.settlements.isEmpty())
            check(completion.matchingResult.events.single() is OrderEnteredBook)
        } else {
            val trade = completion.matchingResult.events.single() as TradeExecuted
            check(trade.makerUserId == engineCapacitySeller && trade.takerUserId == engineCapacityBuyer)
            check(trade.price.value == 10_000L && trade.quantity.value == ENGINE_CAPACITY_QUANTITY)
            val settlement = completion.settlement.settlements.single()
            check(settlement.execution.price.value == 10_000L && settlement.execution.quantity.value == ENGINE_CAPACITY_QUANTITY)
            check(settlement.makerPlan.actualFeeAmount.value == 10L && settlement.takerPlan.actualFeeAmount.value == 20L)
            settlements++
        }
    }
    check(settlements == buys)
    val transactions = snapshot.ledgerTransactions
    check(transactions.size.toLong() == 2 + sells + 2 * buys)
    check(transactions.count { it.transactionType == LedgerTransactionType.OPENING } == 2)
    check(transactions.count { it.transactionType == LedgerTransactionType.RESERVE }.toLong() == commands)
    check(transactions.count { it.transactionType == LedgerTransactionType.SETTLEMENT }.toLong() == buys)
    check(transactions.map { it.ledgerTransactionId }.toSet().size == transactions.size)
    check(transactions.map { it.sourceEventId }.toSet().size == transactions.size)
    val accounts = mutableMapOf<String, Long>()
    for (transaction in transactions) {
        val assets = mutableMapOf<AssetId, Long>()
        for (posting in transaction.postings) {
            check(posting.amount.value > 0)
            val signed = if (posting.side == LedgerPostingSide.CREDIT) posting.amount.value else -posting.amount.value
            assets[posting.assetId] = Math.addExact(assets[posting.assetId] ?: 0, signed)
            accounts[posting.accountId] = Math.addExact(accounts[posting.accountId] ?: 0, signed)
        }
        check(assets.isNotEmpty() && assets.values.all { it == 0L }) { "unbalanced ledger transaction" }
    }
    for ((key, balance) in expectedBalances) {
        val account = "USER:${key.replace('/', ':')}"
        check((accounts["$account:AVAILABLE"] ?: 0L) == balance.first)
        check((accounts["$account:HOLD"] ?: 0L) == balance.second)
    }
    check((accounts["SYSTEM:USDT:FEE_REVENUE"] ?: 0L) == buys * 30)
    val book = state.book(engineCapacityMarket.marketId)
    check(book.size.toLong() == sells - buys)
    check(book.all { it.userId == engineCapacitySeller && it.remainingQuantity.value == ENGINE_CAPACITY_QUANTITY })
    check(duplicateVerified == true || buys == 0L)
    return mapOf(
        "verified" to true,
        "snapshotCount" to 1,
        "actualSettlements" to settlements,
        "expectedFeeRevenue" to buys * 30,
        "expectedBalances" to expectedBalances,
        "ledgerTransactions" to transactions.size,
        "requests" to snapshot.requests.size,
        "completions" to snapshot.completions.size,
        "insufficientBalanceVerified" to true,
        "duplicateAdmissionAndExecutionVerified" to duplicateVerified,
        "ledgerBalancedByAsset" to true,
    )
}

private fun engineCapacityGcDiagnostic(
    scope: String,
    heapUsedBytes: () -> Long,
): Map<String, Any> {
    val heapBefore = heapUsedBytes()
    val gcBefore = engineCapacityGc()
    System.gc()
    val heapAfter = heapUsedBytes()
    val gcAfter = engineCapacityGc()
    return mapOf(
        "scope" to scope,
        "heapBeforeRequestBytes" to heapBefore,
        "heapAfterRequestBytes" to heapAfter,
        "gcCollectionCountDelta" to gcAfter.first - gcBefore.first,
        "gcCollectionTimeMsDelta" to gcAfter.second - gcBefore.second,
        "collectionCountIncreased" to (gcAfter.first > gcBefore.first),
    )
}

internal fun engineCapacityOptionsFromArgs(args: Array<String>): List<TradingEngineCapacityOptions> {
    val flags = args.filter { '=' !in it && it !in setOf("--post-gc", "--pre-gc") }.toSet()
    require(flags.size == 1 && flags.single() in setOf("--smoke", "--probe", "--sustain", "--paced"))
    val values = args.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    require(values.keys.all { it in setOf("--orders", "--warmup", "--seconds", "--max-heap-fraction", "--jfr", "--rate") })
    val mode = flags.single()
    require(mode in setOf("--sustain", "--paced") || "--seconds" !in values)
    require(mode == "--paced" || "--rate" !in values)
    val warmup = values["--warmup"]?.toInt() ?: if (mode == "--smoke") 2 else 10_000
    val seconds = if (mode in setOf("--sustain", "--paced")) values["--seconds"]?.toLong() ?: 60 else null
    val pacedRate = if (mode == "--paced") requireNotNull(values["--rate"]) { "--paced requires --rate" }.toInt() else null
    val cohorts =
        if (pacedRate != null) {
            require("--orders" !in values) { "--paced derives its single orders cohort from rate * seconds" }
            require(pacedRate in 1..2_000_000)
            require(requireNotNull(seconds) in 1..3600)
            val planned = Math.multiplyExact(pacedRate.toLong(), requireNotNull(seconds))
            require(planned in 1..2_000_000L) { "paced rate * seconds must stay within the existing 2,000,000-order limit" }
            listOf(planned.toInt())
        } else {
            values["--orders"]?.split(',')?.map { it.toInt() }
                ?: if (mode == "--probe") listOf(10_000, 50_000, 100_000) else listOf(if (mode == "--smoke") 4 else 100_000)
        }
    require(mode == "--probe" || cohorts.size == 1)
    require(values["--jfr"] == null || cohorts.size == 1) { "--jfr requires one --orders cohort per JVM" }
    return cohorts.map { orders ->
        TradingEngineCapacityOptions(
            warmup,
            orders,
            seconds,
            values["--max-heap-fraction"]?.toDouble() ?: 0.65,
            "--post-gc" in args,
            values["--jfr"],
            pacedRate,
            preGc = "--pre-gc" in args,
        )
    }
}

/** Preserve an interrupted run's evidence without consuming its interrupt signal. */
internal fun writeEngineCapacityReport(
    output: Path,
    report: Map<String, Any?>,
) {
    val interrupted = Thread.interrupted()
    try {
        Files.newOutputStream(output, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use {
            jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(it, report)
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/** --probe uses separate retained-history cohorts; --sustain and --paced never reset their single state. */
fun main(args: Array<String>) {
    val options = engineCapacityOptionsFromArgs(args)
    val runtime = ManagementFactory.getRuntimeMXBean()
    val sourceClasses =
        listOf(
            TradingState::class.java,
            OrderAdmissionState::class.java,
            OrderAdmissionCalculator::class.java,
            OrderSettlementCalculator::class.java,
            MatchingEngine::class.java,
        )
    val bytecode =
        sourceClasses.associate { type ->
            val bytes = requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class")).use { it.readAllBytes() }
            type.name to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    val results = mutableListOf<TradingEngineCapacityResult>()
    for (cohort in options) {
        val result = runTradingEngineCapacity(cohort)
        results += result
        // A heap stop is evidence of a limit; do not begin a larger cohort in the same JVM.
        if (result.stopReason.contains("HEAP_LIMIT") || result.errors.isNotEmpty()) break
    }
    val report =
        mapOf(
            "kind" to "TRADING_ENGINE_CAPACITY",
            "profiled" to results.any { it.profiled },
            "startedAt" to Instant.ofEpochMilli(runtime.startTime).toString(),
            "boundary" to
                "one measured BUY admit + executePending including reserve, match, settlement, fees, ledger and deduplication; SELL preparation and warmup excluded",
            "throughputDenominator" to
                if (results.any { it.options.pacedRate != null }) {
                    "whole measured-loop wall duration including fixed-schedule waits and the tail through the requested window; no prep/final verification"
                } else {
                    "whole measured-loop wall duration; includes inputs, clocks, histogram and heap checks"
                },
            "input" to
                mapOf(
                    "market" to "BTC-USDT",
                    "baseAssetScale" to 8,
                    "price" to 10_000,
                    "quantity" to ENGINE_CAPACITY_QUANTITY,
                    "makerPpm" to 1000,
                    "takerPpm" to 2000,
                ),
            "runtime" to
                mapOf(
                    "javaVersion" to System.getProperty("java.version"),
                    "vm" to System.getProperty("java.vm.name"),
                    "arguments" to runtime.inputArguments,
                    "processors" to Runtime.getRuntime().availableProcessors(),
                    "os" to System.getProperty("os.name"),
                    "architecture" to System.getProperty("os.arch"),
                ),
            "loadedCoreClassSha256" to bytecode,
            "cohortIsolation" to
                "each cohort uses a new independent TradingState; cohorts share the JVM and prior cohort garbage may affect GC; use one --orders value per fresh JVM for comparisons",
            "runs" to results,
            "limitation" to
                "fixed input and retained in-memory history; ORDER_LIMIT or HEAP_LIMIT before requested duration does not establish 60-second sustainable capacity; proactive heap guard cannot guarantee immunity from JVM OOM",
        )
    val directory = Path.of(System.getProperty("engineCapacityReportDirectory", "app-api/build/reports/engine-capacity"))
    Files.createDirectories(directory)
    val output = directory.resolve("engine-capacity-${System.currentTimeMillis()}.json")
    writeEngineCapacityReport(output, report)
    println("TRADING_ENGINE_CAPACITY_REPORT ${output.toAbsolutePath()}")
    results.forEach {
        val pacedStatus = if (it.options.pacedRate != null) ", pacedWindowComplete=${it.pacedWindowComplete}" else ""
        println(
            "${it.measurementMode} ${it.options.orders}: ${it.stopReason}, completed=${it.measuredCompleted}, tps=${it.completedTransactionsPerSecond}, verified=${it.stateCheck != null}$pacedStatus, errors=${it.errors}",
        )
    }
    check(results.all { it.errors.isEmpty() && (it.options.pacedRate == null || it.pacedWindowComplete) }) {
        "capacity run, final state verification or requested paced target failed; see $output"
    }
}
