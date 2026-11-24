package com.exchange.core.api.trading.infrastructure.persistence

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 기존 정산 저장이 커밋한 범위를 별도 스레드에서 따라간다. */
internal class CandleProjectionWorker(
    private val markets: Set<String>,
    private val projection: PostgresCandleProjection,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val failedMarkets = mutableSetOf<String>()
    private val executor =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "order-query-candles").apply { isDaemon = true }
        }

    fun start() {
        executor.scheduleWithFixedDelay(::projectMarkets, 0, POLL_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun projectMarkets() {
        for (market in markets) {
            if (executor.isShutdown) return
            try {
                projection.applyNextPage(market)
                failedMarkets.remove(market)
            } catch (failure: RuntimeException) {
                if (executor.isShutdown) return
                if (failedMarkets.add(market)) logger.warn("차트 배치에 실패했습니다. 저장된 순번부터 다시 시도합니다. market={}", market, failure)
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("차트 배치 스레드가 종료 대기 시간을 넘겼습니다")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val POLL_DELAY_MS = 100L
    }
}
