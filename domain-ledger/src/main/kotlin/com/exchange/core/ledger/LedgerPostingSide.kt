package com.exchange.core.ledger

/**
 * 원장 항목의 차변·대변 구분. 주문의 BUY/SELL 방향과는 다르다.
 * 계정 잔고의 증가·감소 의미는 계정 종류에 따라 달라진다.
 */
enum class LedgerPostingSide {
    /** 차변. */
    DEBIT,

    /** 대변. */
    CREDIT,
}
