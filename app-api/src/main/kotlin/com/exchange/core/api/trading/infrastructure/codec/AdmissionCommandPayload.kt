package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition
import com.exchange.core.trading.OrderAdmissionInput
import java.time.Instant

/** 주문 원본과 호출자가 정한 판단 정보. 재전송에서 쓰지 않을 문맥을 미리 업무 검증하지 않는다. */
data class AdmissionCommandPayload(
    val requestId: String,
    val order: OrderAdmissionInput,
    val market: MarketDefinition,
    val feePolicySnapshot: TradingFeePolicySnapshot,
    val ledgerTransactionId: String,
    val occurredAt: Instant,
)
