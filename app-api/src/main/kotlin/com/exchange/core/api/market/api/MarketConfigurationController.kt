package com.exchange.core.api.market.api

import com.exchange.core.api.market.application.MarketConfigurationUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 등록은 설정을 보관할 뿐 실행 중인 노드에 적용하지 않는다. */
@RestController
@ConditionalOnProperty(name = ["exchange.market-admin.enabled"], havingValue = "true")
@RequestMapping("/api/admin/market-configurations", produces = ["application/json"])
class MarketConfigurationController(
    private val marketConfigurationUseCase: MarketConfigurationUseCase,
) {
    @PutMapping("/{version}", consumes = ["application/json"])
    fun register(
        @PathVariable @Positive version: Long,
        @Valid @RequestBody request: MarketConfigurationRegistrationRequest,
    ): MarketConfigurationRegistrationResponse =
        MarketConfigurationRegistrationResponse.from(marketConfigurationUseCase.register(version, request.document))

    @GetMapping("/{version}")
    fun find(
        @PathVariable @Positive version: Long,
    ): MarketConfigurationReleaseResponse = MarketConfigurationReleaseResponse.from(marketConfigurationUseCase.find(version))

    @GetMapping
    fun list(
        @RequestParam(required = false) @Positive beforeVersion: Long?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): MarketConfigurationPageResponse = MarketConfigurationPageResponse.from(marketConfigurationUseCase.list(beforeVersion, limit), limit)
}
