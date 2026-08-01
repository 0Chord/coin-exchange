package com.exchange.core.order

/**
 * 주문 방향.
 *
 * BUY는 bid book에 남거나 ask book과 체결되고,
 * SELL은 ask book에 남거나 bid book과 체결된다.
 */
enum class Side {
    BUY,
    SELL,
}

/**
 * 주문 방식.
 *
 * Phase 1에서는 LIMIT만 처리하고 MARKET은 이후 단계에서 구현한다.
 */
enum class OrderType {
    /**
     * 지정가 주문. 지정한 가격보다 불리한 가격으로는 체결하지 않는다.
     */
    LIMIT,

    /**
     * 시장가 주문. 가격을 직접 지정하지 않고 현재 book의 가격을 따라간다.
     */
    MARKET,
}

/**
 * 미체결 잔량 처리 방식.
 */
enum class TimeInForce {
    /**
     * 체결되지 않은 잔량을 book에 남긴다.
     */
    GTC,

    /**
     * 즉시 체결 가능한 수량만 처리하고 남은 수량은 취소한다.
     */
    IOC,
}

/**
 * 주문 상태.
 *
 * 매칭 엔진 내부 결과와 나중에 붙을 API/저장소에서 같은 용어를 쓰기 위한 값이다.
 */
enum class OrderStatus {
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
}
