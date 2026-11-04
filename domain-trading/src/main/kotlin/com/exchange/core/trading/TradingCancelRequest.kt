package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId

/** 취소는 대상 승인만 지정한다. 잔량과 반환액은 클라이언트가 정하지 않는다. */
data class CancelIntent(
    val marketId: MarketId,
    val targetOrderId: OrderId,
    val expectedConfigurationVersion: Long,
) {
    init {
        require(expectedConfigurationVersion > 0)
        require(tradingTextBytes(marketId.value) in 1..MAX_TRADING_TEXT_BYTES)
        require(tradingTextBytes(targetOrderId.value) in 1..MAX_TRADING_TEXT_BYTES)
    }
}

enum class CancelCode { ACCEPTED, REJECTED }

/** 최초 접수 응답이다. ACCEPTED는 매칭 종료나 예약 반환 완료가 아니다. */
data class CancelReceipt(
    val code: CancelCode,
    val cancelId: String? = null,
    val targetOrderId: OrderId? = null,
    val inputSequence: Long? = null,
    val reason: String? = null,
) {
    init {
        targetOrderId?.let { require(tradingTextBytes(it.value) in 1..MAX_TRADING_TEXT_BYTES) }
        when (code) {
            CancelCode.ACCEPTED -> {
                require(cancelId != null && cancelId.length == 40 && cancelId.startsWith("C2") && cancelId.all { it.code in 33..126 })
                require(targetOrderId != null && inputSequence != null && inputSequence > 0 && reason == null)
            }

            CancelCode.REJECTED -> {
                require(cancelId == null && inputSequence == null && reason != null && tradingTextBytes(reason) in 1..64)
            }
        }
    }
}

sealed class CancelInboxLookup {
    data object Ready : CancelInboxLookup()

    data class Repeated(
        val receipt: CancelReceipt,
    ) : CancelInboxLookup()

    data class Rejected(
        val reason: InboxRejection,
    ) : CancelInboxLookup()
}

data class TradingCancelInboxEntrySnapshot(
    val sequence: Long,
    val intent: CancelIntent,
    val receipt: CancelReceipt,
)
