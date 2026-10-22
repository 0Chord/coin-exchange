package com.exchange.core.api.common

import com.exchange.core.api.market.api.MarketConfigurationFailureResponse
import com.exchange.core.api.market.application.MarketConfigurationError
import com.exchange.core.api.market.application.MarketConfigurationException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** 마켓 설정 API의 응답 형식과 오류 응답을 변환한다. */
@ControllerAdvice(basePackages = ["com.exchange.core.api.market.api"])
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = ["exchange.market-admin.enabled"], havingValue = "true")
class MarketConfigurationHttpExceptionHandler {
    @ExceptionHandler(
        HandlerMethodValidationException::class,
        MethodArgumentNotValidException::class,
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalidRequest(): ResponseEntity<MarketConfigurationFailureResponse> =
        failure(MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "request"))

    @ExceptionHandler(MarketConfigurationException::class)
    fun failure(error: MarketConfigurationException): ResponseEntity<MarketConfigurationFailureResponse> {
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
        return ResponseEntity.status(status).body(MarketConfigurationFailureResponse.from(error))
    }

    @ModelAttribute
    fun requireJsonResponse(
        @RequestHeader("Accept", required = false) accept: List<String>?,
    ) {
        val ranges =
            try {
                MediaType.parseMediaTypes(accept.orEmpty())
            } catch (_: IllegalArgumentException) {
                throw HttpMediaTypeNotAcceptableException(listOf(MediaType.APPLICATION_JSON))
            }
        val matching = ranges.filter { it.includes(MediaType.APPLICATION_JSON) }

        fun specificity(range: MediaType): Int =
            when {
                range.isWildcardType -> 0
                range.isWildcardSubtype -> 1
                else -> 2
            }
        val mostSpecific = matching.maxOfOrNull(::specificity) ?: return
        // 구체적인 JSON 거부(q=0)를 더 넓은 */* 허용으로 뒤집지 않는다.
        if (matching.none { specificity(it) == mostSpecific && it.qualityValue > 0.0 }) {
            throw HttpMediaTypeNotAcceptableException(listOf(MediaType.APPLICATION_JSON))
        }
    }
}
