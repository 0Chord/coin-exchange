package com.exchange.core.fee

/** 체결에서 주문이 유동성을 제공했는지 소비했는지를 나타낸다. */
enum class LiquidityRole {
    /** Order book에 먼저 대기하며 유동성을 제공한 주문. */
    MAKER,

    /** 기존 order book 주문과 즉시 체결되며 유동성을 소비한 주문. */
    TAKER,
}
