package com.exchange.core.api.trading.application

import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionResult
import java.util.concurrent.CompletableFuture

/** HTTP 재전송에서도 같은 사용자·요청 ID와 주문 원본을 유지한다. */
data class TradingOrderSubmission(
    val requestId: String,
    val order: OrderAdmissionInput,
)

/** 전송 수용, 엔진의 최초 판단, 결과 미확인을 구분하는 애플리케이션 결과다. */
sealed interface TradingAdmissionResult {
    data class Accepted(
        val sequence: Long,
    ) : TradingAdmissionResult

    data class Rejected(
        val reason: String,
        val insufficientBalance: OrderAdmissionResult.InsufficientBalance? = null,
    ) : TradingAdmissionResult

    data class InvalidInput(
        val reason: String,
    ) : TradingAdmissionResult

    data class NotSubmitted(
        val reason: String,
        val overloaded: Boolean = false,
    ) : TradingAdmissionResult

    data class Unknown(
        val reason: String,
        val timedOut: Boolean = false,
    ) : TradingAdmissionResult
}

fun interface TradingOrderGateway {
    fun submit(submission: TradingOrderSubmission): CompletableFuture<TradingAdmissionResult>
}

/** HTTP 변환과 실제 Aeron 연결의 수명·송수신 책임을 분리한다. */
class SubmitTradingOrderUseCase(
    private val gateway: TradingOrderGateway,
) {
    fun submit(submission: TradingOrderSubmission): CompletableFuture<TradingAdmissionResult> = gateway.submit(submission)
}
