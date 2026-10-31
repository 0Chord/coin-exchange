package com.exchange.core.api.trading.application

import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.util.concurrent.CompletableFuture

/** 공개 애플리케이션 입력이다. 전송 adapter가 wire 명령을 구성한다. */
data class BoundedTradingOrderSubmission(
    val userId: String,
    val channel: Int,
    val epoch: Long,
    val sequence: Long,
    val expectedConfigurationVersion: Long,
    val marketId: String,
    val side: Side,
    val orderType: OrderType,
    val timeInForce: TimeInForce,
    val price: Long,
    val quantity: Long,
)

/** HTTP·wire codec과 무관하게 엔진 결과와 전달의 불확실성을 나타낸다. */
data class BoundedTradingResult(
    val code: String,
    val admissionCode: String? = null,
    val orderId: String? = null,
    val admissionSequence: Long? = null,
    val marketAdmissionSequence: Long? = null,
    val reason: String? = null,
    val epoch: Long? = null,
    val retiredThrough: Long? = null,
    val appliedLogPosition: Long? = null,
    val protection: String,
    val failureCode: String? = null,
    val possiblySubmitted: Boolean = false,
)

interface BoundedTradingOrderGateway {
    fun submit(submission: BoundedTradingOrderSubmission): CompletableFuture<BoundedTradingResult>

    fun acknowledge(
        userId: String,
        channel: Int,
        epoch: Long,
        through: Long,
    ): CompletableFuture<BoundedTradingResult>

    fun rotate(
        userId: String,
        channel: Int,
        expectedEpoch: Long,
    ): CompletableFuture<BoundedTradingResult>
}

/** HTTP 변환을 연결 소유권·전송 오류 처리와 분리한다. */
class BoundedTradingOrderUseCase(
    private val gateway: BoundedTradingOrderGateway,
) {
    fun submit(submission: BoundedTradingOrderSubmission): CompletableFuture<BoundedTradingResult> = gateway.submit(submission)

    fun acknowledge(
        userId: String,
        channel: Int,
        epoch: Long,
        through: Long,
    ): CompletableFuture<BoundedTradingResult> = gateway.acknowledge(userId, channel, epoch, through)

    fun rotate(
        userId: String,
        channel: Int,
        expectedEpoch: Long,
    ): CompletableFuture<BoundedTradingResult> = gateway.rotate(userId, channel, expectedEpoch)
}
