package com.exchange.core.api.trading.api

import com.exchange.core.api.trading.application.BoundedTradingOrderSubmission
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class BoundedTradingOrderRequest(
    @field:NotBlank @field:Size(max = 256) val userId: String,
    @field:PositiveOrZero val channel: Int,
    @field:Positive val epoch: Long,
    @field:Positive val sequence: Long,
    @field:Positive val expectedConfigurationVersion: Long,
    val side: Side,
    val orderType: OrderType,
    val timeInForce: TimeInForce,
    @field:Positive val price: Long,
    @field:Positive val quantity: Long,
) {
    fun toSubmission(marketId: String) =
        BoundedTradingOrderSubmission(
            userId,
            channel,
            epoch,
            sequence,
            expectedConfigurationVersion,
            marketId,
            side,
            orderType,
            timeInForce,
            price,
            quantity,
        )
}

data class BoundedReceiptAcknowledgementRequest(
    @field:NotBlank @field:Size(max = 256) val userId: String,
    @field:Positive val epoch: Long,
    @field:PositiveOrZero val through: Long,
)

data class BoundedEpochRequest(
    @field:NotBlank @field:Size(max = 256) val userId: String,
    @field:PositiveOrZero val expectedEpoch: Long,
)

/** 전달 결과를 알 수 없어도 token은 그대로 반환한다. */
data class BoundedTradingOrderResponse(
    val status: String,
    val userId: String,
    val channel: Int,
    val epoch: Long?,
    val sequence: Long? = null,
    val marketId: String? = null,
    val orderId: String? = null,
    val admissionSequence: Long? = null,
    val marketAdmissionSequence: Long? = null,
    val reason: String? = null,
    val retiredThrough: Long? = null,
    val appliedLogPosition: Long? = null,
    val protection: String = "LOCAL_QUORUM",
)
