package com.exchange.core.api.market.api

import com.exchange.core.api.market.application.MarketConfigurationException
import com.exchange.core.api.market.application.MarketConfigurationRegistration
import com.exchange.core.api.market.application.MarketConfigurationRelease
import com.exchange.core.api.market.application.MarketConfigurationSummary
import java.time.Instant

data class MarketConfigurationRegistrationResponse(
    val configVersion: Long,
    val sha256: String,
) {
    companion object {
        fun from(result: MarketConfigurationRegistration): MarketConfigurationRegistrationResponse =
            MarketConfigurationRegistrationResponse(result.configVersion, result.sha256)
    }
}

data class MarketConfigurationReleaseResponse(
    val configVersion: Long,
    val sha256: String,
    val document: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(result: MarketConfigurationRelease): MarketConfigurationReleaseResponse =
            MarketConfigurationReleaseResponse(result.configVersion, result.sha256, result.document, result.createdAt)
    }
}

data class MarketConfigurationSummaryResponse(
    val configVersion: Long,
    val sha256: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(result: MarketConfigurationSummary): MarketConfigurationSummaryResponse =
            MarketConfigurationSummaryResponse(result.configVersion, result.sha256, result.createdAt)
    }
}

data class MarketConfigurationPageResponse(
    val items: List<MarketConfigurationSummaryResponse>,
    val nextBeforeVersion: Long?,
) {
    companion object {
        fun from(
            items: List<MarketConfigurationSummary>,
            limit: Int,
        ): MarketConfigurationPageResponse =
            MarketConfigurationPageResponse(
                items.map(MarketConfigurationSummaryResponse::from),
                // 꽉 찬 마지막 페이지에서도 안전한 다음 위치이며, 다음 행의 존재를 보장하지 않는다.
                items.lastOrNull()?.configVersion?.takeIf { items.size == limit },
            )
    }
}

data class MarketConfigurationFailureResponse(
    val code: String,
    val stage: String,
    val configVersion: Long?,
) {
    companion object {
        fun from(error: MarketConfigurationException): MarketConfigurationFailureResponse =
            MarketConfigurationFailureResponse(error.code.name, error.stage, error.configVersion)
    }
}
