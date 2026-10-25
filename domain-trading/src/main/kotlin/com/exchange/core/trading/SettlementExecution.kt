package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import java.time.Instant

/** 정산할 체결의 원본 값. 매칭 객체·전송 형식에 의존하지 않으며 검증은 계산기가 맡는다. */
data class SettlementExecution(
    val marketId: MarketId,
    val engineSequence: Long,
    val makerOrderId: OrderId,
    val takerOrderId: OrderId,
    val makerUserId: UserId,
    val takerUserId: UserId,
    val side: Side,
    val price: Price,
    val quantity: Quantity,
    val ledgerTransactionId: String,
    val occurredAt: Instant,
)
