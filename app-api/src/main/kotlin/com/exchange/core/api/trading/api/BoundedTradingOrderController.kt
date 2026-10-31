package com.exchange.core.api.trading.api

import com.exchange.core.api.trading.application.BoundedTradingOrderUseCase
import com.exchange.core.api.trading.application.BoundedTradingResult
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture

@RestController
@Profile("aeron-bounded-gateway")
@RequestMapping("/api/trading/v3")
internal class BoundedTradingOrderController(
    private val useCase: BoundedTradingOrderUseCase,
) {
    @PostMapping("/markets/{marketId}/orders", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(
        @PathVariable @NotBlank @Size(max = 256) marketId: String,
        @RequestBody @Valid request: BoundedTradingOrderRequest,
    ): CompletableFuture<ResponseEntity<BoundedTradingOrderResponse>> =
        useCase.submit(request.toSubmission(marketId)).thenApply { result ->
            val response =
                base(result, request.userId, request.channel, request.epoch)
                    .copy(sequence = request.sequence, marketId = marketId)
            ResponseEntity.status(httpStatus(result)).body(response)
        }

    @PostMapping("/channels/{channel}/ack", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun acknowledge(
        @PathVariable @PositiveOrZero channel: Int,
        @RequestBody @Valid request: BoundedReceiptAcknowledgementRequest,
    ): CompletableFuture<ResponseEntity<BoundedTradingOrderResponse>> =
        useCase.acknowledge(request.userId, channel, request.epoch, request.through).thenApply { result ->
            ResponseEntity.status(httpStatus(result)).body(base(result, request.userId, channel, request.epoch))
        }

    @PostMapping("/channels/{channel}/epoch", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun epoch(
        @PathVariable @PositiveOrZero channel: Int,
        @RequestBody @Valid request: BoundedEpochRequest,
    ): CompletableFuture<ResponseEntity<BoundedTradingOrderResponse>> =
        useCase.rotate(request.userId, channel, request.expectedEpoch).thenApply { result ->
            ResponseEntity.status(httpStatus(result)).body(base(result, request.userId, channel, result.epoch))
        }

    private fun base(
        result: BoundedTradingResult,
        userId: String,
        channel: Int,
        epoch: Long?,
    ): BoundedTradingOrderResponse =
        BoundedTradingOrderResponse(
            result.code,
            userId,
            channel,
            epoch,
            orderId = result.orderId,
            admissionSequence = result.admissionSequence,
            marketAdmissionSequence = result.marketAdmissionSequence,
            reason = result.reason,
            retiredThrough = result.retiredThrough,
            appliedLogPosition = result.appliedLogPosition,
            protection = result.protection,
        )

    private fun httpStatus(result: BoundedTradingResult): Int =
        when {
            result.failureCode != null -> {
                when {
                    result.possiblySubmitted -> 504
                    result.failureCode == "INVALID_REQUEST" -> 400
                    result.failureCode == "QUEUE_FULL" -> 429
                    else -> 503
                }
            }

            result.admissionCode == "ACCEPTED" -> {
                202
            }

            result.admissionCode == "REJECTED" -> {
                422
            }

            result.code in setOf("ISSUED", "ALREADY_ISSUED", "ACKNOWLEDGED") -> {
                200
            }

            result.code == "RETRYABLE_CAPACITY" -> {
                429
            }

            result.code in setOf("NOT_READY", "QUARANTINED") -> {
                503
            }

            else -> {
                409
            }
        }
}
