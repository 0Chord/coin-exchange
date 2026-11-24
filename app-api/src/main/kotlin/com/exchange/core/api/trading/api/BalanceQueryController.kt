package com.exchange.core.api.trading.api

import com.exchange.core.api.trading.application.BalanceQueryCode
import com.exchange.core.api.trading.application.BalanceQueryUseCase
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/trading/v3")
internal class BalanceQueryController(
    private val useCase: BalanceQueryUseCase,
) {
    @GetMapping("/users/{userId}/balances/{assetId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getBalance(
        @PathVariable userId: String,
        @PathVariable assetId: String,
    ): ResponseEntity<BalanceQueryResponse> {
        val result = useCase.get(userId, assetId)
        val response =
            when (result.code) {
                BalanceQueryCode.BALANCE_FOUND -> {
                    BalanceQueryResponse(
                        code = result.code,
                        userId = userId,
                        assetId = assetId,
                        available = result.available,
                        reserved = result.reserved,
                        asOfLedgerSequence = result.projectedThrough,
                    )
                }

                BalanceQueryCode.BALANCE_NOT_FOUND -> {
                    BalanceQueryResponse(code = result.code, asOfLedgerSequence = result.projectedThrough)
                }

                BalanceQueryCode.QUERY_CATCHING_UP -> {
                    BalanceQueryResponse(
                        code = result.code,
                        projectedThroughLedgerSequence = result.projectedThrough,
                        observedLedgerSequence = result.observedThrough,
                    )
                }

                else -> {
                    BalanceQueryResponse(code = result.code)
                }
            }
        return ResponseEntity.status(result.code.status).body(response)
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class BalanceQueryResponse(
    val code: BalanceQueryCode,
    val userId: String? = null,
    val assetId: String? = null,
    val available: Long? = null,
    val reserved: Long? = null,
    val asOfLedgerSequence: Long? = null,
    val projectedThroughLedgerSequence: Long? = null,
    val observedLedgerSequence: Long? = null,
)
