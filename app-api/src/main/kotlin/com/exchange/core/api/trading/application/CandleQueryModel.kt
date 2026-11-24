package com.exchange.core.api.trading.application

import java.time.DayOfWeek.MONDAY
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.time.temporal.TemporalAdjusters.previousOrSame

enum class CandleInterval(
    val code: String,
    private val seconds: Long? = null,
) {
    ONE_MINUTE("1m", 60),
    FIVE_MINUTES("5m", 300),
    FIFTEEN_MINUTES("15m", 900),
    ONE_HOUR("1h", 3_600),
    ONE_DAY("1d", 86_400),
    ONE_WEEK("1w"),
    ONE_MONTH("1mo"),
    ONE_YEAR("1y"),
    ;

    fun start(at: Instant): Instant {
        if (seconds != null) {
            return Instant.ofEpochSecond(Math.floorDiv(at.epochSecond, seconds) * seconds)
        }

        val date = at.atOffset(UTC).toLocalDate()
        val start =
            when (this) {
                ONE_WEEK -> date.with(previousOrSame(MONDAY))
                ONE_MONTH -> date.withDayOfMonth(1)
                ONE_YEAR -> date.withDayOfYear(1)
                else -> error("시간 길이가 정의되지 않은 봉 주기입니다")
            }
        return start.atStartOfDay().toInstant(UTC)
    }

    fun shift(
        start: Instant,
        count: Long,
    ): Instant {
        if (seconds != null) return start.plusSeconds(Math.multiplyExact(seconds, count))

        val date = start.atOffset(UTC)
        return when (this) {
            ONE_WEEK -> date.plusWeeks(count)
            ONE_MONTH -> date.plusMonths(count)
            ONE_YEAR -> date.plusYears(count)
            else -> error("시간 길이가 정의되지 않은 봉 주기입니다")
        }.toInstant()
    }
}

data class Candle(
    val startedAt: Instant,
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: String,
)

data class CandleQueryResult(
    val code: OrderQueryCode,
    val marketId: String? = null,
    val interval: String? = null,
    val asOfSettlementResultSequence: Long? = null,
    val sourceSettlementResultSequence: Long? = null,
    val candles: List<Candle>? = null,
)
