package com.exchange.core.api.market.application

import com.exchange.core.api.market.application.port.MarketConfigurationStore
import java.time.Instant

/** 등록은 실행 중인 노드의 설정 적용과 별개다. 이미 등록한 버전은 변경하지 않는다. */
class MarketConfigurationUseCase(
    private val marketConfigurationStore: MarketConfigurationStore,
) {
    fun register(
        version: Long,
        document: ByteArray,
    ): MarketConfigurationRegistration {
        requireVersion(version)
        if (document.isEmpty() || document.size > MAX_DOCUMENT_BYTES) {
            throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "document", version)
        }
        return marketConfigurationStore.register(version, document)
    }

    fun find(version: Long): MarketConfigurationRelease {
        requireVersion(version)
        return marketConfigurationStore.find(version)
            ?: throw MarketConfigurationException(MarketConfigurationError.NOT_FOUND, "lookup", version)
    }

    fun list(
        beforeVersion: Long?,
        limit: Int,
    ): List<MarketConfigurationSummary> {
        beforeVersion?.let(::requireVersion)
        if (limit !in 1..100) throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "limit")
        return marketConfigurationStore.list(beforeVersion, limit)
    }

    private fun requireVersion(version: Long) {
        if (version <= 0) throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "version")
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = 65_536
    }
}

data class MarketConfigurationRegistration(
    val configVersion: Long,
    val sha256: String,
)

data class MarketConfigurationRelease(
    val configVersion: Long,
    val sha256: String,
    val document: String,
    val createdAt: Instant,
)

data class MarketConfigurationSummary(
    val configVersion: Long,
    val sha256: String,
    val createdAt: Instant,
)

enum class MarketConfigurationError {
    INVALID_INPUT,
    CONTENT_CONFLICT,
    NOT_FOUND,
    REGISTRATION_FAILED,
    RESULT_UNCONFIRMED,
    LOOKUP_FAILED,
}

/** 입력 원문·DB 자격정보·하위 예외를 외부 경계로 운반하지 않는다. */
class MarketConfigurationException(
    val code: MarketConfigurationError,
    val stage: String,
    val configVersion: Long? = null,
) : RuntimeException("${code.name} stage=$stage" + (configVersion?.let { " configVersion=$it" } ?: ""))
