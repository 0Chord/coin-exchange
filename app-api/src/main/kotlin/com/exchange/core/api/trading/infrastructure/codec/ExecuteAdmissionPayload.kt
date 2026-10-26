package com.exchange.core.api.trading.infrastructure.codec

/** 같은 거래 상태에 이미 승인된 주문을 가리킨다. 주문·정책·잔고를 다시 전달하지 않는다. */
data class ExecuteAdmissionPayload(
    val admissionSequence: Long,
)
