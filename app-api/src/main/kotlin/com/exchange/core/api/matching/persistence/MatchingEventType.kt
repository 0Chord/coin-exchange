package com.exchange.core.api.matching.persistence

/**
 * DB에 저장할 matching event 종류.
 */
enum class MatchingEventType {
    /** maker와 taker 주문 수량이 실제로 체결된 event. */
    TRADE_EXECUTED,

    /** 즉시 체결되지 않은 주문 잔량이 book에 추가된 event. */
    ORDER_ENTERED_BOOK,

    /** 대기 주문이 소유자의 요청으로 book에서 제거된 event. */
    ORDER_CANCELLED,

    /** 주문 없음 또는 소유자 불일치로 취소하지 못한 event. */
    ORDER_CANCEL_REJECTED,
}
