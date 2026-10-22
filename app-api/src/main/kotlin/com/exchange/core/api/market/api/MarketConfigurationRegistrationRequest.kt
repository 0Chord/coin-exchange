package com.exchange.core.api.market.api

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

/** 관리 API에서 등록할 마켓 설정 목록이다. 저장 형식과 분리해 입력 항목을 명시한다. */
data class MarketConfigurationRegistrationRequest(
    @field:Valid
    @field:Size(min = 1, max = 32)
    val markets: List<MarketConfigurationMarketRequest>,
)

data class MarketConfigurationMarketRequest(
    @field:NotBlank val marketId: String,
    @field:NotBlank val baseAssetId: String,
    @field:NotBlank val quoteAssetId: String,
    @field:Min(0) @field:Max(18) val baseQuantityScale: Int,
    @field:Valid val feePolicy: MarketConfigurationFeePolicyRequest,
)

data class MarketConfigurationFeePolicyRequest(
    @field:Pattern(regexp = "SPOT") val productType: String,
    @field:Pattern(regexp = "NORMAL|VIP|VVIP|VVVIP") val feeTier: String,
    @field:Positive val scheduleVersion: Long,
    @field:Min(0) @field:Max(1_000_000) val makerPartsPerMillion: Long,
    @field:Min(0) @field:Max(1_000_000) val takerPartsPerMillion: Long,
)
