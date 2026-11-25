package com.exchange.core.api.market.infrastructure.persistence

import com.exchange.core.api.market.application.MarketConfigurationError
import com.exchange.core.api.market.application.MarketConfigurationException
import com.exchange.core.api.trading.infrastructure.aeron.validateDevelopmentMarketDatabaseUrl
import java.sql.Connection
import java.sql.DriverManager

class MarketConfigurationDatabaseSettings private constructor(
    private val url: String,
    private val user: String,
    private val password: String,
) {
    internal fun connect(): Connection {
        // pgJDBC는 URL의 마지막 값을 사용하므로 사용자 옵션 뒤에 유한 제한을 고정한다.
        val boundedUrl =
            url + (if ('?' in url) "&" else "?") +
                "connectTimeout=5&socketTimeout=5&loginTimeout=5&cancelSignalTimeout=3&" +
                "readOnly=false&readOnlyMode=transaction&options=-c%20statement_timeout%3D3000"
        return DriverManager.getConnection(boundedUrl, user, password)
    }

    companion object {
        fun from(environment: Map<String, String>): MarketConfigurationDatabaseSettings {
            fun required(name: String): String =
                environment[name]?.takeIf { it.isNotBlank() }
                    ?: throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "environment")

            val url = required("FUNDING_MARKET_DB_URL")
            val user = required("FUNDING_MARKET_DB_USER")
            val password = required("FUNDING_MARKET_DB_PASSWORD")
            try {
                validateDevelopmentMarketDatabaseUrl(url)
            } catch (_: IllegalArgumentException) {
                throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "database-url")
            }
            return MarketConfigurationDatabaseSettings(url, user, password)
        }
    }
}
