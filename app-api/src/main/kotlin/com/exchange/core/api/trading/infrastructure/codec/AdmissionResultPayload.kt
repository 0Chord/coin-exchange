package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState

/** 호출자에게 보내는 접수 확인 값. 내부 잔고·예약·원장 객체를 노출하지 않는다. */
sealed interface AdmissionResultPayload {
    val userId: String
    val requestId: String
    val marketId: String
    val orderId: String

    data class Accepted(
        override val userId: String,
        override val requestId: String,
        override val marketId: String,
        override val orderId: String,
        val sequence: Long,
    ) : AdmissionResultPayload

    data class Rejected(
        override val userId: String,
        override val requestId: String,
        override val marketId: String,
        override val orderId: String,
        val reason: OrderAdmissionResult.Reason,
        val insufficientBalance: OrderAdmissionResult.InsufficientBalance? = null,
    ) : AdmissionResultPayload

    companion object {
        fun from(
            command: AdmissionCommandPayload,
            result: OrderAdmissionState.Result,
        ): AdmissionResultPayload =
            when (result) {
                is OrderAdmissionState.Result.Accepted -> {
                    check(result.record.candidate.order == command.order) { "승인 결과가 현재 주문과 다릅니다" }
                    Accepted(
                        command.order.userId.value,
                        command.requestId,
                        command.order.marketId.value,
                        command.order.orderId.value,
                        result.record.sequence,
                    )
                }

                // 충돌 응답도 지금 제출한 주문을 가리킨다. 과거 요청 이력을 다시 조회하지 않는다.
                is OrderAdmissionState.Result.Rejected -> {
                    Rejected(
                        command.order.userId.value,
                        command.requestId,
                        command.order.marketId.value,
                        command.order.orderId.value,
                        result.reason,
                        result.insufficientBalance,
                    )
                }
            }
    }
}
