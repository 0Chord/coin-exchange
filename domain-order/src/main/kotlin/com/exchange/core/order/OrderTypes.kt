package com.exchange.core.order

// 주문 방향
enum class Side {
    BUY,
    SELL,
}

// 주문 방식
enum class OrderType {
    LIMIT,
    MARKET,
}

// 잔량 처리 방식
enum class TimeInForce {
    // book에 남김
    GTC,

    // 남은 수량 취소
    IOC,
}

// 주문 상태
enum class OrderStatus {
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
}
