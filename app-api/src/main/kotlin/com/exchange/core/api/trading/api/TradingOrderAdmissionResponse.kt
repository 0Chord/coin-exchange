package com.exchange.core.api.trading.api

import com.fasterxml.jackson.annotation.JsonInclude

enum class TradingOrderAdmissionStatus {
    ACCEPTED,
    REJECTED,
    INVALID_INPUT,
    NOT_SUBMITTED,
    OUTCOME_UNKNOWN,
}

/** ACCEPTED는 엔진 최초 접수 결과이며 체결·정산 완료를 뜻하지 않는다. */
data class TradingOrderAdmissionResponse(
    val status: TradingOrderAdmissionStatus,
    val userId: String,
    val requestId: String,
    val marketId: String,
    val orderId: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val sequence: Long? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val reason: String? = null,
)

/** JSON 해석·Bean Validation 단계에서는 유효한 요청 식별자가 아직 없을 수 있다. */
data class TradingOrderInputFailureResponse(
    val status: TradingOrderAdmissionStatus = TradingOrderAdmissionStatus.INVALID_INPUT,
    val reason: String = "INVALID_REQUEST",
)
