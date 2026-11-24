package com.exchange.core.api.trading.application

data class PublicOrderBookQueryResult(
    val code: OrderQueryCode,
    val marketId: String? = null,
    val asOfMatchingResultSequence: Long? = null,
    val bids: List<PublicOrderBookLevel>? = null,
    val asks: List<PublicOrderBookLevel>? = null,
)
