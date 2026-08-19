package com.exchange.core.api.common

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * API 처리 실패를 클라이언트에 전달하는 공통 오류 body.
 *
 * @property message 사람이 읽을 수 있는 오류 설명
 */
data class ApiErrorResponse(
    val message: String,
)

/**
 * controller 밖으로 전파된 애플리케이션 예외를 HTTP 응답으로 변환한다.
 *
 * 현재는 입력값과 도메인 사전조건 위반인 [IllegalArgumentException]만 400으로 변환한다.
 * 그 밖의 예외는 Spring의 기본 500 처리에 맡긴다.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    /**
     * 잘못된 요청 값을 HTTP 400 응답으로 변환한다.
     *
     * @param error value class 또는 도메인 로직의 사전조건 위반
     * @return 상태 코드 400과 오류 message를 담은 응답
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(error: IllegalArgumentException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ApiErrorResponse(
                    message = error.message ?: "bad request",
                ),
            )
}
