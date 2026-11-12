package com.exchange.core.trading

import com.exchange.core.order.OrderReservation

/** 최초 접수 응답과 업무 완료는 별개다. */
sealed class CancelReply {
    data class Recorded(
        val receipt: CancelReceipt,
        val newlyRecorded: Boolean,
    ) : CancelReply()

    data class Refused(
        val reason: String,
    ) : CancelReply()
}

/** 취소의 정체성과 최초 대상 입력을 공통 전달 순서와 구분한다. */
data class FundsCancelDispatch(
    val cancelId: String,
    val requestToken: RequestToken,
    val cancelSequence: Long,
    val targetInputSequence: Long,
)

/** 미완료 취소만 대상의 종료 근거를 보호한다. 완료 이력을 무한 보관하지 않는다. */
data class FundsCancellationSnapshot(
    val input: FundsOrderDispatch,
    val terminalReservation: OrderReservation? = null,
    val terminalResultSequence: Long? = null,
    val terminalEngineSequence: Long? = null,
) {
    val terminalStatus get() = terminalReservation?.status

    // 종료 전에 완료 기록의 최대 공간도 확보한다. 실제 잔량 변화에 따라 예산을 줄이지 않는다.
    val canonicalBytes: Long get() = Math.addExact(input.dispatchCanonicalBytes, input.activeCanonicalBytes + 128L)
}
