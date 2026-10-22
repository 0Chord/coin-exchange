package com.exchange.core.api.market.api

import com.exchange.core.api.market.application.MarketConfigurationError
import com.exchange.core.api.market.application.MarketConfigurationException
import com.exchange.core.api.market.application.MarketConfigurationRegistration
import com.exchange.core.api.market.application.MarketConfigurationRelease
import com.exchange.core.api.market.application.MarketConfigurationSummary
import com.exchange.core.api.market.application.MarketConfigurationUseCase
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** 등록은 설정을 보관할 뿐 실행 중인 노드에 적용하지 않는다. 본문을 재직렬화하지 않는다. */
@RestController
@ConditionalOnProperty(name = ["exchange.market-admin.enabled"], havingValue = "true")
@RequestMapping("/api/admin/market-configurations", produces = ["application/json"])
class MarketConfigurationController(
    private val marketConfigurationUseCase: MarketConfigurationUseCase,
) {
    @PutMapping("/{version}", consumes = ["application/json"])
    fun register(
        @PathVariable @Positive version: Long,
        @RequestBody @Size(min = 1, max = MarketConfigurationUseCase.MAX_DOCUMENT_BYTES) document: ByteArray,
    ): MarketConfigurationRegistration = marketConfigurationUseCase.register(version, document)

    @GetMapping("/{version}")
    fun find(
        @PathVariable @Positive version: Long,
    ): MarketConfigurationRelease = marketConfigurationUseCase.find(version)

    @GetMapping
    fun list(
        @RequestParam(required = false) @Positive beforeVersion: Long?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): MarketConfigurationPage {
        val items = marketConfigurationUseCase.list(beforeVersion, limit)
        // 꽉 찬 마지막 페이지에서도 안전한 다음 조회 위치를 제공한다. 전체 개수·hasMore를 보장하지 않는다.
        return MarketConfigurationPage(items, items.lastOrNull()?.configVersion?.takeIf { items.size == limit })
    }

    @ExceptionHandler(
        HandlerMethodValidationException::class,
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalidRequest(): ResponseEntity<MarketConfigurationFailure> =
        failure(MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "request"))

    @ExceptionHandler(MarketConfigurationException::class)
    fun failure(error: MarketConfigurationException): ResponseEntity<MarketConfigurationFailure> {
        val status =
            when (error.code) {
                MarketConfigurationError.INVALID_INPUT -> HttpStatus.BAD_REQUEST

                MarketConfigurationError.CONTENT_CONFLICT -> HttpStatus.CONFLICT

                MarketConfigurationError.NOT_FOUND -> HttpStatus.NOT_FOUND

                MarketConfigurationError.REGISTRATION_FAILED,
                MarketConfigurationError.RESULT_UNCONFIRMED,
                MarketConfigurationError.LOOKUP_FAILED,
                -> HttpStatus.SERVICE_UNAVAILABLE
            }
        return ResponseEntity.status(status).body(MarketConfigurationFailure(error.code.name, error.stage, error.configVersion))
    }
}

data class MarketConfigurationPage(
    val items: List<MarketConfigurationSummary>,
    val nextBeforeVersion: Long?,
)

data class MarketConfigurationFailure(
    val code: String,
    val stage: String,
    val configVersion: Long?,
)
