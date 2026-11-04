package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.order.OrderReservationStatus

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
        when (code) {
            CancelCode.ACCEPTED -> {
                require(cancelId != null && cancelId.length == 40 && cancelId.startsWith("C2"))
                require(targetOrderId != null && inputSequence != null && inputSequence > 0 && reason == null)
            }

            CancelCode.REJECTED -> {
                require(cancelId == null && inputSequence == null && reason != null && reason.length in 1..64)
            }
        }
    }
}

sealed class CancelReply {
    data class Recorded(
        val receipt: CancelReceipt,
        val newlyRecorded: Boolean,
    ) : CancelReply()

    data class Refused(
        val reason: String,
    ) : CancelReply()
}

data class FundsCancelDispatch(
    val cancelId: String,
    val requestToken: RequestToken,
)

/** 앞선 결과로 대상이 종료돼도 취소 의무가 끝날 때까지 원본과 종료 근거를 보존한다. */
data class FundsCancellationSnapshot(
    val input: FundsOrderDispatch,
    val terminalStatus: OrderReservationStatus? = null,
) {
    init {
        require(input.isCancellation)
        require(
            terminalStatus == null || terminalStatus == OrderReservationStatus.SETTLED || terminalStatus == OrderReservationStatus.RELEASED,
        )
    }

    val canonicalBytes: Long get() = Math.addExact(input.dispatchCanonicalBytes, 16L)
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
