package com.exchange.core.api.trading.application

import com.exchange.core.api.trading.application.port.CandleQueryReader
import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

internal interface CandleQueryUseCase {
    fun get(
        marketId: String,
        interval: CandleInterval,
        count: Int,
    ): CandleQueryResult
}

internal open class CandleQueryUseCaseImpl(
    private val reader: CandleQueryReader,
) : CandleQueryUseCase {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    override fun get(
        marketId: String,
        interval: CandleInterval,
        count: Int,
    ): CandleQueryResult {
        val currentStart = interval.start(Instant.now())
        val until = interval.shift(currentStart, 1)
        val from = interval.shift(until, -count.toLong())
        return try {
            reader.read(marketId, interval, from, until, count)
        } catch (_: OrderQueryUnavailableException) {
            CandleQueryResult(OrderQueryCode.QUERY_UNAVAILABLE)
        } catch (_: OrderQueryInconsistentException) {
            CandleQueryResult(OrderQueryCode.QUERY_INCONSISTENT)
        }
    }
}
