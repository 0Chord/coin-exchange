package com.exchange.core.api.common

import com.exchange.core.api.market.application.MarketConfigurationUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import java.io.InputStream
import java.lang.reflect.Type

/** 마켓 설정 API에서 본문 읽기 상한과 저장 전 응답 형식을 확인한다. */
@ControllerAdvice(basePackages = ["com.exchange.core.api.market.api"])
@ConditionalOnProperty(name = ["exchange.market-admin.enabled"], havingValue = "true")
class MarketConfigurationHttpExceptionHandler : RequestBodyAdviceAdapter() {
    override fun supports(
        methodParameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = targetType == ByteArray::class.java

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): HttpInputMessage {
        // Bean Validation은 변환 후에 실행되므로, 변환기에 넘기기 전에도 읽기 크기를 제한한다.
        val document = inputMessage.body.readNBytes(MarketConfigurationUseCase.MAX_DOCUMENT_BYTES + 1)
        val headers =
            HttpHeaders().apply {
                putAll(inputMessage.headers)
                contentLength = document.size.toLong()
            }
        return object : HttpInputMessage {
            override fun getBody(): InputStream = document.inputStream()

            override fun getHeaders() = headers
        }
    }

    override fun handleEmptyBody(
        body: Any?,
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Any = ByteArray(0)

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
