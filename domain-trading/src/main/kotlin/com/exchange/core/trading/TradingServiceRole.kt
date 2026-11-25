package com.exchange.core.trading

/** 업무 상태의 조립 역할이다. Aeron의 리더·팔로워 역할이나 사용자 채널 세대와 구분한다. */
enum class TradingServiceRole {
    INTEGRATED,
    FUNDS,
    MATCHING,
    ;

    /** 원격 주문·결과 연결을 갖추기 전에는 단독 서비스를 시작하지 않는다. */
    fun requireRuntimeEnabled() {
        check(this == INTEGRATED) { "SPLIT_ROLE_NOT_ENABLED: $name" }
    }

    companion object {
        fun parse(raw: String?): TradingServiceRole {
            if (raw == null) return INTEGRATED
            return entries.firstOrNull { it.name == raw }
                ?: throw IllegalArgumentException("INVALID_SERVICE_ROLE")
        }
    }
}
