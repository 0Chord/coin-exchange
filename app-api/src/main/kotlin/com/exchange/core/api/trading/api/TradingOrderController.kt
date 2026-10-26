package com.exchange.core.api.trading.api

import com.exchange.core.api.trading.application.SubmitTradingOrderUseCase
import com.exchange.core.api.trading.application.TradingAdmissionResult
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture

@RestController
@Profile("aeron-gateway")
@RequestMapping("/api/trading/markets/{marketId}/orders")
class TradingOrderController(
    private val submitTradingOrderUseCase: SubmitTradingOrderUseCase,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(
        @PathVariable @NotBlank @Size(max = 256) marketId: String,
        @RequestBody @Valid request: SubmitTradingOrderRequest,
    ): CompletableFuture<ResponseEntity<TradingOrderAdmissionResponse>> =
        submitTradingOrderUseCase.submit(request.toSubmission(marketId)).thenApply { result ->
            val (httpStatus, admissionStatus) = result.httpStatus()
            val response =
                TradingOrderAdmissionResponse(
                    admissionStatus,
                    request.userId,
                    request.requestId,
                    marketId,
                    request.orderId,
                    sequence = (result as? TradingAdmissionResult.Accepted)?.sequence,
                    reason = result.reason(),
                )
            ResponseEntity.status(httpStatus).body(response)
        }

    private fun TradingAdmissionResult.httpStatus(): Pair<HttpStatus, TradingOrderAdmissionStatus> =
        when (this) {
            is TradingAdmissionResult.Accepted -> {
                HttpStatus.ACCEPTED to TradingOrderAdmissionStatus.ACCEPTED
            }

            is TradingAdmissionResult.Rejected -> {
                (if (reason in conflicts) HttpStatus.CONFLICT else HttpStatus.UNPROCESSABLE_CONTENT) to TradingOrderAdmissionStatus.REJECTED
            }

            is TradingAdmissionResult.InvalidInput -> {
                HttpStatus.BAD_REQUEST to TradingOrderAdmissionStatus.INVALID_INPUT
            }

            is TradingAdmissionResult.NotSubmitted -> {
                (if (overloaded) HttpStatus.TOO_MANY_REQUESTS else HttpStatus.SERVICE_UNAVAILABLE) to
                    TradingOrderAdmissionStatus.NOT_SUBMITTED
            }

            is TradingAdmissionResult.Unknown -> {
                (if (timedOut) HttpStatus.GATEWAY_TIMEOUT else HttpStatus.SERVICE_UNAVAILABLE) to
                    TradingOrderAdmissionStatus.OUTCOME_UNKNOWN
            }
        }

    private fun TradingAdmissionResult.reason(): String? =
        when (this) {
            is TradingAdmissionResult.Accepted -> null
            is TradingAdmissionResult.Rejected -> reason
            is TradingAdmissionResult.InvalidInput -> reason
            is TradingAdmissionResult.NotSubmitted -> reason
            is TradingAdmissionResult.Unknown -> reason
        }

    private companion object {
        val conflicts = setOf("REQUEST_CONTENT_CONFLICT", "DUPLICATE_ORDER", "REQUEST_IN_FLIGHT_CONTENT_CONFLICT")
    }
}
