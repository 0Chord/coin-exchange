package com.exchange.core.api.matching.persistence

/**
 * DB에 저장할 matching event 종류.
 */
enum class MatchingEventType {
    TRADE_EXECUTED,
    ORDER_ENTERED_BOOK,
    ORDER_CANCELLED,
    ORDER_CANCEL_REJECTED
}