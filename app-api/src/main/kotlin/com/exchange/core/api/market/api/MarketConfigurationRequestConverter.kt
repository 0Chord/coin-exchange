package com.exchange.core.api.market.api

import com.exchange.core.api.market.application.MarketConfigurationUseCase
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.AbstractHttpMessageConverter

/** 요청 JSON 원문을 제한된 크기로 읽어 DTO에 담는다. 응답 변환에는 참여하지 않는다. */
class MarketConfigurationRequestConverter : AbstractHttpMessageConverter<MarketConfigurationRegistrationRequest>(MediaType.ALL) {
    override fun supports(clazz: Class<*>): Boolean = clazz == MarketConfigurationRegistrationRequest::class.java

    override fun canWrite(
        clazz: Class<*>,
        mediaType: MediaType?,
    ): Boolean = false

    override fun readInternal(
        clazz: Class<out MarketConfigurationRegistrationRequest>,
        inputMessage: HttpInputMessage,
    ): MarketConfigurationRegistrationRequest =
        MarketConfigurationRegistrationRequest(inputMessage.body.readNBytes(MarketConfigurationUseCase.MAX_DOCUMENT_BYTES + 1))

    override fun writeInternal(
        request: MarketConfigurationRegistrationRequest,
        outputMessage: HttpOutputMessage,
    ) {
        error("Market configuration request converter does not write responses")
    }
}
