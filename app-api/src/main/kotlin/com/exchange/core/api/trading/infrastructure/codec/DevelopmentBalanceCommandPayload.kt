package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.ledger.OpeningBalance
import java.time.Instant

/** 명시적으로 준비할 자금 원본과 호출자가 정한 원장 문맥. 현재 잔고를 담지 않는다. */
data class DevelopmentBalanceCommandPayload(
    val opening: OpeningBalance,
    val ledgerTransactionId: String,
    val occurredAt: Instant,
)
