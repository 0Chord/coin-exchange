package com.exchange.core.api.trading.application.port

import com.exchange.core.api.trading.application.CandleInterval
import com.exchange.core.api.trading.application.CandleQueryResult
import java.time.Instant

internal interface CandleQueryReader {
    fun read(
        marketId: String,
        interval: CandleInterval,
        from: Instant,
        until: Instant,
        count: Int,
    ): CandleQueryResult
}
