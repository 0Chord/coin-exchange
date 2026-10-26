package com.exchange.core.api.common

import com.exchange.core.api.trading.api.TradingOrderInputFailureResponse
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** 전송 형식과 입력 사전조건의 실패를 엔진의 정상 업무 거절과 구분한다. */
@RestControllerAdvice(basePackages = ["com.exchange.core.api.trading.api"])
@Profile("aeron-gateway")
class TradingOrderHttpExceptionHandler {
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class,
        MethodArgumentTypeMismatchException::class,
        IllegalArgumentException::class,
    )
    fun invalidRequest(): ResponseEntity<TradingOrderInputFailureResponse> =
        ResponseEntity.badRequest().body(TradingOrderInputFailureResponse())
}
