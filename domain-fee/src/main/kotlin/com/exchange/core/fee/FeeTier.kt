package com.exchange.core.fee

/** 월간 실제 체결 금액에 따라 사용자에게 부여하는 수수료 등급. */
enum class FeeTier {
    /** 거래량 우대가 적용되지 않는 기본 등급. */
    NORMAL,

    /** 첫 번째 거래량 기준을 충족한 등급. */
    VIP,

    /** 두 번째 거래량 기준을 충족한 등급. */
    VVIP,

    /** 가장 높은 거래량 기준을 충족한 등급. */
    VVVIP,
}
