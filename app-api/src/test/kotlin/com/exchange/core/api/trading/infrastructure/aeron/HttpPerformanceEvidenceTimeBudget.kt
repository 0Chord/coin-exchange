package com.exchange.core.api.trading.infrastructure.aeron

import java.util.concurrent.TimeUnit

/** Test-only evidence budgets. These values never extend an order request, send window or drain. */
internal enum class HttpPerformanceEvidenceTimeBudget(
    val checkpointCaptureMaxMs: Long,
    val preparationObservationMs: Long,
    val nodeShutdownReportWaitMs: Long,
    val nodeStopGraceSeconds: Int,
    val nodeStopCommandTimeoutMs: Long,
) {
    STANDARD(120_000, 120_000, 10_000, 15, 20_000),
    LARGE_SUSTAINED(600_000, 600_000, 120_000, 150, 155_000),
    ;

    fun requireForOrders(orders: Int) {
        require(this != LARGE_SUSTAINED || orders in 120_001..DIRECT_AERON_SUSTAINED_MAX_ORDERS) {
            "확장 증거 예산은 명시적 큰 sustained cohort에서만 허용합니다"
        }
    }

    fun requireCheckpointTimeout(timeoutMs: Long) {
        require(timeoutMs in 1..checkpointCaptureMaxMs) { "체크포인트 capture timeout 범위 초과: $name/$timeoutMs" }
    }

    fun fields(): Map<String, Any> =
        mapOf(
            "profile" to name,
            "checkpointCaptureMaxMs" to checkpointCaptureMaxMs,
            "preparationObservationMs" to preparationObservationMs,
            "nodeShutdownReportWaitMs" to nodeShutdownReportWaitMs,
            "nodeStopGraceSeconds" to nodeStopGraceSeconds,
            "nodeStopCommandTimeoutMs" to nodeStopCommandTimeoutMs,
        )
}

/** Only the existing explicit direct sustained runner selects an expanded profile. */
internal fun directAeronEvidenceTimeBudget(options: DirectAeronCapacityOptions): HttpPerformanceEvidenceTimeBudget =
    if (options.sustained && options.orders > 120_000) {
        HttpPerformanceEvidenceTimeBudget.LARGE_SUSTAINED.also { it.requireForOrders(options.orders) }
    } else {
        HttpPerformanceEvidenceTimeBudget.STANDARD
    }

internal fun httpPerformanceNodeEvidenceTimeBudget(
    configured: String?,
    orders: Int,
): HttpPerformanceEvidenceTimeBudget =
    HttpPerformanceEvidenceTimeBudget.valueOf(configured ?: "STANDARD").also { it.requireForOrders(orders) }

/**
 * A late docker-logs poll cannot make a file capture succeed after its original deadline.
 * This is a cooperative capture deadline, not cancellation of a synchronous parser/financial check.
 */
internal fun awaitHttpPerformanceCheckpointFileCapture(
    description: String,
    timeoutMs: Long,
    reports: String,
    predicate: () -> Boolean,
    nanoTime: () -> Long = System::nanoTime,
    pause: () -> Unit = { Thread.sleep(250) },
) {
    require(timeoutMs in 1..600_000)
    val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

    fun requireActive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("checkpoint capture interrupted")
        check(deadline - nanoTime() > 0) { "$description 제한 시간 초과. 보고서: $reports" }
    }
    try {
        while (true) {
            requireActive()
            val captured = predicate()
            requireActive()
            if (captured) return
            pause()
        }
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw failure
    }
}
