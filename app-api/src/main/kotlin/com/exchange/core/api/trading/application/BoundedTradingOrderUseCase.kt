package com.exchange.core.api.trading.application

import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.util.concurrent.CompletableFuture

/** Public application input; the transport adapter constructs the wire command. */
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

/** Engine outcome and delivery uncertainty, independent of HTTP and the wire codec. */
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

/** Keeps HTTP conversion separate from connection ownership and transport errors. */
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
