package com.exchange.core.api.market.api

import com.exchange.core.api.market.application.MarketConfigurationError
import com.exchange.core.api.market.application.MarketConfigurationException
import com.exchange.core.api.market.application.MarketConfigurationRegistration
import com.exchange.core.api.market.application.MarketConfigurationRelease
import com.exchange.core.api.market.application.MarketConfigurationSummary
import com.exchange.core.api.market.application.MarketConfigurationUseCase
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.IOException

/** 등록은 설정을 보관할 뿐 실행 중인 노드에 적용하지 않는다. 본문을 재직렬화하지 않는다. */
@RestController
@ConditionalOnProperty(name = ["exchange.market-admin.enabled"], havingValue = "true")
@RequestMapping("/api/admin/market-configurations", produces = ["application/json"])
class MarketConfigurationController(
    private val marketConfigurationUseCase: MarketConfigurationUseCase,
) {
    @PutMapping("/{version}")
    fun register(
        @PathVariable version: String,
        request: HttpServletRequest,
    ): MarketConfigurationRegistration {
        requireJsonResponse(request)
        val configVersion = parseVersion(version)
        val contentType =
            try {
                request.contentType?.let(MediaType::parseMediaType)
            } catch (_: IllegalArgumentException) {
                null
            }
        if (contentType == null || contentType.type != "application" || contentType.subtype != "json") {
            throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "content-type", configVersion)
        }
        val document =
            try {
                request.inputStream.readNBytes(MarketConfigurationUseCase.MAX_DOCUMENT_BYTES + 1)
            } catch (_: IOException) {
                throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "document-read", configVersion)
            }
        return marketConfigurationUseCase.register(configVersion, document)
    }

    @GetMapping("/{version}")
    fun find(
        @PathVariable version: String,
        request: HttpServletRequest,
    ): MarketConfigurationRelease {
        requireJsonResponse(request)
        return marketConfigurationUseCase.find(parseVersion(version))
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) beforeVersion: String?,
        @RequestParam(required = false) limit: String?,
        request: HttpServletRequest,
    ): MarketConfigurationPage {
        requireJsonResponse(request)
        val pageLimit =
            if (limit == null) {
                20
            } else {
                limit.toIntOrNull() ?: throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "limit")
            }
        val items = marketConfigurationUseCase.list(beforeVersion?.let(::parseVersion), pageLimit)
        // 꽉 찬 마지막 페이지에서도 안전한 다음 조회 위치를 제공한다. 전체 개수·hasMore를 보장하지 않는다.
        return MarketConfigurationPage(items, items.lastOrNull()?.configVersion?.takeIf { items.size == pageLimit })
    }

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

    private fun requireJsonResponse(request: HttpServletRequest) {
        val ranges =
            try {
                MediaType.parseMediaTypes(request.getHeaders("Accept").toList())
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
        // 구체적인 JSON 거부(q=0)를 뒤의 */* 허용으로 뒤집지 않는다.
        if (matching.none { specificity(it) == mostSpecific && it.qualityValue > 0.0 }) {
            throw HttpMediaTypeNotAcceptableException(listOf(MediaType.APPLICATION_JSON))
        }
    }

    private fun parseVersion(value: String): Long =
        value.toLongOrNull()?.takeIf { it > 0 }
            ?: throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "version")
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
