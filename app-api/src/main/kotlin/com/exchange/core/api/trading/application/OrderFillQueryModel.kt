package com.exchange.core.api.trading.application

data class OrderFillCursor(
    val resultSequence: Long,
    val engineSequence: Long,
) {
    init {
        require(resultSequence > 0 && engineSequence > 0) { "invalid fill cursor" }
    }
}

enum class OrderFillRole { MAKER, TAKER }

enum class OrderFillSettlementStatus { PENDING, COMPLETED }

data class OrderChargedFee(
    val amount: String,
    val assetId: String,
)

data class OrderFill(
    val tradeId: String,
    val price: Long,
    val quantity: Long,
    val role: OrderFillRole,
    val settlementStatus: OrderFillSettlementStatus,
    val chargedFee: OrderChargedFee?,
) {
    init {
        require((settlementStatus == OrderFillSettlementStatus.COMPLETED) == (chargedFee != null))
    }
}

/** 주문·체결·정산 저장 위치를 한 DB 스냅샷에서 관찰한 값이다. */
data class OrderFillQuerySnapshot(
    val order: OrderQuerySnapshot,
    val settlementEnabled: Boolean,
    val fills: List<OrderFill> = emptyList(),
    val nextCursor: OrderFillCursor? = null,
    val settledFeeTotal: OrderChargedFee? = null,
)

data class OrderFillQueryResult(
    val code: OrderQueryCode,
    val progress: OrderQueryProgress? = null,
    val fills: List<OrderFill> = emptyList(),
    val nextCursor: OrderFillCursor? = null,
    val settledFeeTotal: OrderChargedFee? = null,
)
