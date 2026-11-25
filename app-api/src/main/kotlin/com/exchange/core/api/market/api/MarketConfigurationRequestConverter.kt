package com.exchange.core.api.market.api

import com.exchange.core.api.market.application.MarketConfigurationError
import com.exchange.core.api.market.application.MarketConfigurationException
import com.exchange.core.api.market.application.MarketConfigurationUseCase
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.AbstractHttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.type.LogicalType
import tools.jackson.module.kotlin.KotlinModule
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 읽기 상한 안에서 등록 DTO를 읽고, 검증된 마켓 목록을 기존 저장 형식으로 변환한다. */
class MarketConfigurationRequestConverter : AbstractHttpMessageConverter<MarketConfigurationRegistrationRequest>(MediaType.ALL) {
    private val mapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
            ).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual) { config ->
                listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)
                    .forEach { config.setCoercion(it, CoercionAction.Fail) }
            }.build()

    override fun supports(clazz: Class<*>): Boolean = clazz == MarketConfigurationRegistrationRequest::class.java

    override fun canWrite(
        clazz: Class<*>,
        mediaType: MediaType?,
    ): Boolean = false

    override fun readInternal(
        clazz: Class<out MarketConfigurationRegistrationRequest>,
        inputMessage: HttpInputMessage,
    ): MarketConfigurationRegistrationRequest {
        val bytes =
            try {
                inputMessage.body.readNBytes(MarketConfigurationUseCase.MAX_DOCUMENT_BYTES + 1)
            } catch (_: IOException) {
                throw invalidRequest(inputMessage)
            }
        if (bytes.isEmpty() || bytes.size > MarketConfigurationUseCase.MAX_DOCUMENT_BYTES) throw invalidRequest(inputMessage)
        val document =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw invalidRequest(inputMessage)
            }
        return try {
            mapper.readValue(document, MarketConfigurationRegistrationRequest::class.java) ?: throw invalidRequest(inputMessage)
        } catch (_: JacksonException) {
            throw invalidRequest(inputMessage)
        }
    }

    fun document(request: MarketConfigurationRegistrationRequest): ByteArray =
        try {
            mapper.writeValueAsBytes(request.markets)
        } catch (_: JacksonException) {
            throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "request")
        }

    override fun writeInternal(
        request: MarketConfigurationRegistrationRequest,
        outputMessage: HttpOutputMessage,
    ) {
        error("Market configuration request converter does not write responses")
    }

    private fun invalidRequest(inputMessage: HttpInputMessage) =
        HttpMessageNotReadableException("Invalid market configuration request", inputMessage)
}
