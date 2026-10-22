package com.exchange.core.api.market.api

import com.exchange.core.api.market.application.MarketConfigurationUseCase
import jakarta.validation.constraints.Size

/** 기존 JSON 배열의 원문 바이트를 담는다. 파싱·재직렬화로 공백과 순서를 바꾸지 않는다. */
data class MarketConfigurationRegistrationRequest(
    @field:Size(min = 1, max = MarketConfigurationUseCase.MAX_DOCUMENT_BYTES)
    val document: ByteArray,
)
