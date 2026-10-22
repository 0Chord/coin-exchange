package com.exchange.core.api.config

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest

/** 관리 경로의 본문·DB 접근 전에 환경에서 받은 토큰 하나를 확인한다. 세션은 만들지 않는다. */
class MarketConfigurationAdminAuthentication(
    token: String?,
) : Filter {
    private val tokenDigest: ByteArray

    init {
        check(token != null && token.length >= 32 && token.all { it.code in 33..126 }) {
            "EXCHANGE_MARKET_ADMIN_TOKEN must contain at least 32 non-space printable ASCII characters"
        }
        tokenDigest = digest(token)
    }

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val httpRequest = request as HttpServletRequest
        val headers = httpRequest.getHeaders("Authorization").toList()
        val authorization = headers.singleOrNull()
        val candidate = authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
        if (candidate == null || candidate.isEmpty() || candidate.any { it.code !in 33..126 } ||
            !MessageDigest.isEqual(tokenDigest, digest(candidate))
        ) {
            val httpResponse = response as HttpServletResponse
            httpResponse.status = HttpServletResponse.SC_UNAUTHORIZED
            httpResponse.setHeader("WWW-Authenticate", "Bearer")
            httpResponse.contentType = "application/json"
            httpResponse.writer.write("""{"code":"UNAUTHORIZED","stage":"authentication"}""")
            return
        }
        chain.doFilter(request, response)
    }

    private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
}
