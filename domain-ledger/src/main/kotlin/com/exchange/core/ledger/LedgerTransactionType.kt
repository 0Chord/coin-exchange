package com.exchange.core.ledger

/** 원장 거래가 기록하는 회계 사건의 종류. 이 값 자체가 잔고를 변경하지는 않는다. */
enum class LedgerTransactionType {
    /** 주문에 사용할 자산을 available에서 hold로 예약하는 사건. */
    RESERVE,

    /** 사용하지 않은 예약 자산을 hold에서 available로 반환하는 사건. */
    RELEASE,

    /** 체결에 따른 자산 이동과 실제 수수료를 기록하는 사건. */
    SETTLEMENT,

    /** 기존 기록을 삭제하지 않고 반대 방향의 항목으로 효과를 되돌리는 역분개 사건. */
    REVERSAL,
}
