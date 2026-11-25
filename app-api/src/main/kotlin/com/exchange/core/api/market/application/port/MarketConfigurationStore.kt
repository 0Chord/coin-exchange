package com.exchange.core.api.market.application.port

import com.exchange.core.api.market.application.MarketConfigurationRegistration
import com.exchange.core.api.market.application.MarketConfigurationRelease
import com.exchange.core.api.market.application.MarketConfigurationSummary

interface MarketConfigurationStore {
    fun register(
        version: Long,
        document: ByteArray,
    ): MarketConfigurationRegistration

    fun find(version: Long): MarketConfigurationRelease?

    fun list(
        beforeVersion: Long?,
        limit: Int,
    ): List<MarketConfigurationSummary>
}
