package com.exchange.core.api.trading.api

import com.exchange.core.api.trading.application.OrderQueryCode
import com.exchange.core.api.trading.application.OrderQueryResult
import com.exchange.core.api.trading.application.OrderQueryUseCase
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("aeron-bounded-gateway")
@ConditionalOnProperty(name = ["exchange.order-query.enabled"], havingValue = "true")
@RequestMapping("/api/trading/v3")
internal class OrderQueryController(
    private val useCase: OrderQueryUseCase,
) {
    @GetMapping("/markets/{marketId}/orders/{orderId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun get(
        @PathVariable @NotBlank @Size(max = 256) marketId: String,
        @PathVariable @NotBlank @Size(min = 34, max = 34) @Pattern(regexp = "O2[A-Za-z0-9_-]{32}") orderId: String,
        @RequestParam(defaultValue = "0") @PositiveOrZero minAdmissionSequence: Long,
    ): ResponseEntity<OrderQueryResponse> {
        val result = useCase.get(marketId, orderId, minAdmissionSequence)
        val status =
            when (result.code) {
                OrderQueryCode.ORDER_FOUND -> 200
                OrderQueryCode.QUERY_CATCHING_UP -> 202
                OrderQueryCode.ORDER_NOT_FOUND_AS_OF -> 404
                OrderQueryCode.QUERY_NOT_READY, OrderQueryCode.QUERY_UNAVAILABLE, OrderQueryCode.QUERY_INCONSISTENT -> 503
            }
        return ResponseEntity.status(status).body(response(result))
    }

    private fun response(result: OrderQueryResult): OrderQueryResponse {
        val record = result.record
        val progress = result.progress
        return OrderQueryResponse(
            code = result.code.name,
            orderId = record?.orderId,
            marketId = record?.marketId,
            userId = record?.userId,
            side = record?.side?.name,
            orderType = record?.orderType?.name,
            timeInForce = record?.timeInForce?.name,
            price = record?.price,
            initialQuantity = record?.initialQuantity,
            admissionSequence = record?.admissionSequence,
            marketAdmissionSequence = record?.marketAdmissionSequence,
            filledQuantity = record?.filledQuantity,
            remainingQuantity = record?.remainingQuantity,
            cancelledQuantity = record?.cancelledQuantity,
            notExecutedQuantity = record?.notExecutedQuantity,
            state = record?.state?.name,
            financialStatus = record?.financialStatus?.name,
            pendingReason = record?.pendingReason?.name,
            resultStream =
                record?.resultStream?.let {
                    OrderQueryResultStreamResponse(
                        it.matchingLineage.high,
                        it.matchingLineage.low,
                        it.fundsLineage.high,
                        it.fundsLineage.low,
                        it.marketId.value,
                        it.ownershipEpoch,
                        it.configurationVersion,
                        it.direction.name,
                    )
                },
            lastAffectedResultSequence = record?.lastAffectedResultSequence,
            financialThrough = record?.financialThrough,
            appliedRevision = progress?.appliedRevision,
            observedRevision = progress?.observedRevision,
            coveredAdmissionSequence = progress?.coveredAdmissionSequence,
            lifecycle = progress?.lifecycle?.name,
            freshness = result.freshness?.name,
            updatedAt = progress?.updatedAt,
        )
    }
}
