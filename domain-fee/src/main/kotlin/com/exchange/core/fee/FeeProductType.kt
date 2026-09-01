package com.exchange.core.fee

/** 거래 수수료 정책을 구분하는 상품 종류. */
enum class FeeProductType {
    /** 실제 자산을 매수·매도하는 현물 거래. */
    SPOT,

    /** 만기 없이 펀딩비를 정산하는 무기한 선물 거래. */
    PERPETUAL_FUTURES,

    /** 정해진 만기일이 있는 선물 거래. */
    DATED_FUTURES,
}
