package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import java.util.HexFormat
import java.util.Locale

private const val LEGACY_MARKETS_ENV = "FUNDING_MARKETS_JSON"
private const val DATABASE_URL_ENV = "FUNDING_MARKET_DB_URL"
private const val DATABASE_USER_ENV = "FUNDING_MARKET_DB_USER"
private const val DATABASE_PASSWORD_ENV = "FUNDING_MARKET_DB_PASSWORD"
private const val CONFIGURATION_VERSION_ENV = "FUNDING_MARKET_CONFIG_VERSION"
private const val CONTENT_SHA256_ENV = "FUNDING_MARKET_CONFIG_SHA256"
private const val MARKETS_DOCUMENT = "market_configuration_release.markets_document"
private const val MAX_MARKET_BYTES = 65_536
private const val MAX_MARKETS = 32
private const val DATABASE_TIMEOUT_SECONDS = 5
private val databaseEnvironmentKeys =
    setOf(DATABASE_URL_ENV, DATABASE_USER_ENV, DATABASE_PASSWORD_ENV, CONFIGURATION_VERSION_ENV, CONTENT_SHA256_ENV)
internal val DEVELOPMENT_MARKET_ENVIRONMENT_KEYS: Set<String> = databaseEnvironmentKeys + LEGACY_MARKETS_ENV
private val marketJson =
    JsonMapper
        .builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()
private val marketKeys = setOf("marketId", "baseAssetId", "quoteAssetId", "baseQuantityScale", "feePolicy")
private val policyKeys = setOf("productType", "feeTier", "scheduleVersion", "makerPartsPerMillion", "takerPartsPerMillion")
private val separateDatabaseOptions = setOf("user", "password", "service", "host", "port", "dbname", "pghost", "pgport", "pgdbname")

/** 시작 경계에서만 읽는다. 미설정만 BTC 기본값이며, 잘못된 설정은 자원 생성 전에 실패한다. */
internal fun readDevelopmentMarkets(environment: Map<String, String>): DevelopmentMarketRegistry {
    require(LEGACY_MARKETS_ENV !in environment) { "$LEGACY_MARKETS_ENV: 지원하지 않습니다. RDB 설정 버전을 지정하세요" }
    if (databaseEnvironmentKeys.none { it in environment }) {
        return DevelopmentMarketRegistry(listOf(DevelopmentMarketContext.BTC_USDT))
    }

    fun required(name: String): String = requireNotNull(environment[name]?.takeIf { it.isNotBlank() }) { "$name: 비어 있지 않은 설정이 필요합니다" }

    val url = required(DATABASE_URL_ENV)
    val user = required(DATABASE_USER_ENV)
    val password = required(DATABASE_PASSWORD_ENV)
    val version = required(CONFIGURATION_VERSION_ENV).toLongOrNull()
    require(version != null && version > 0) { "$CONFIGURATION_VERSION_ENV: 양수 Long이 필요합니다" }
    val expectedHash = required(CONTENT_SHA256_ENV)
    require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "$CONTENT_SHA256_ENV: 소문자 SHA-256 64자리가 필요합니다" }
    validateDevelopmentMarketDatabaseUrl(url)
    val raw = readMarketsDocument(url, user, password, version)
    val actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)))
    require(actualHash == expectedHash) { "$CONTENT_SHA256_ENV: 설정 버전 $version 내용이 기대값과 다릅니다" }
    val markets = parseDevelopmentMarketsDocument(raw)
    return DevelopmentMarketRegistry(markets.all(), version, actualHash)
}

/** 드라이버가 잘못된 주소를 로그에 남기기 전에 검사한다. URL 원문과 파싱 예외는 외부로 내보내지 않는다. */
internal fun validateDevelopmentMarketDatabaseUrl(url: String) {
    val uri =
        try {
            URI(url.removePrefix("jdbc:"))
        } catch (_: URISyntaxException) {
            null
        }
    val path = uri?.rawPath.orEmpty()
    val optionsValid =
        uri
            ?.rawQuery
            .orEmpty()
            .split('&')
            .filter { it.isNotEmpty() }
            .all { option ->
                val name = option.substringBefore('=')
                name.matches(Regex("[A-Za-z][A-Za-z0-9_]*")) && name.lowercase(Locale.ROOT) !in separateDatabaseOptions
            }
    require(
        url.startsWith("jdbc:postgresql://") && uri != null && uri.host != null &&
            uri.rawUserInfo == null && uri.rawFragment == null && !uri.rawAuthority.endsWith(':') &&
            (uri.port == -1 || uri.port in 1..65_535) &&
            path.startsWith('/') && path.length > 1 && '/' !in path.drop(1) && optionsValid,
    ) { "$DATABASE_URL_ENV: 서버와 데이터베이스를 명시한 PostgreSQL JDBC URL이 필요하며 사용자·비밀번호는 별도 환경으로 지정합니다" }
}

private fun readMarketsDocument(
    url: String,
    user: String,
    password: String,
    version: Long,
): String {
    // PostgreSQL JDBC는 URL의 마지막 값을 사용한다. 호출자가 제한시간이나 읽기 전용 모드를 풀 수 없게 고정한다.
    val boundedUrl =
        url + (if ('?' in url) "&" else "?") +
            "connectTimeout=$DATABASE_TIMEOUT_SECONDS&socketTimeout=$DATABASE_TIMEOUT_SECONDS&" +
            "loginTimeout=$DATABASE_TIMEOUT_SECONDS&readOnlyMode=always"
    val document =
        try {
            DriverManager.getConnection(boundedUrl, user, password).use { connection ->
                connection.autoCommit = false
                connection.isReadOnly = true
                val sql = "select markets_document from market_configuration_release where config_version = ?"
                connection.prepareStatement(sql).use { statement ->
                    statement.queryTimeout = DATABASE_TIMEOUT_SECONDS
                    statement.setLong(1, version)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.getString(1) else null
                    }
                }
            }
        } catch (_: SQLException) {
            // JDBC 오류의 URL·사용자·비밀번호가 예외 cause나 메시지로 전파되지 않게 한다.
            throw IllegalStateException("$DATABASE_URL_ENV: 설정 버전 $version 조회에 실패했습니다")
        }
    return requireNotNull(document) { "$CONFIGURATION_VERSION_ENV: 설정 버전 $version 이 없습니다" }
}

/** DB에서 읽은 원문을 검증한다. 공백·순서·식별자를 정규화하거나 다른 내용으로 대체하지 않는다. */
internal fun parseDevelopmentMarketsDocument(raw: String): DevelopmentMarketRegistry {
    require(raw.length <= MAX_MARKET_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_MARKET_BYTES) {
        "$MARKETS_DOCUMENT: UTF-8 $MAX_MARKET_BYTES 바이트를 초과했습니다"
    }
    val root =
        try {
            marketJson.readTree(raw)
        } catch (error: JacksonException) {
            throw IllegalArgumentException("$MARKETS_DOCUMENT: JSON 형식 또는 중복 필드가 잘못됐습니다", error)
        }
    require(root != null && root.isArray && root.size() in 1..MAX_MARKETS) { "$MARKETS_DOCUMENT: 마켓 배열은 1~$MAX_MARKETS 개여야 합니다" }
    val contexts = root.mapIndexed { index, node -> readMarket(node, "$MARKETS_DOCUMENT[$index]") }
    require(contexts.map { it.market.marketId }.distinct().size == contexts.size) { "$MARKETS_DOCUMENT: 마켓 ID가 중복됐습니다" }
    return DevelopmentMarketRegistry(contexts)
}

private fun readMarket(
    node: JsonNode,
    location: String,
): DevelopmentMarketContext {
    requireFields(node, marketKeys, location)
    val policy = node.path("feePolicy")
    val policyLocation = "$location.feePolicy"
    requireFields(policy, policyKeys, policyLocation)
    val scale = integer(node, "baseQuantityScale", location)
    require(scale in 0..18) { "$location.baseQuantityScale: 0~18 정수가 필요합니다" }
    try {
        return DevelopmentMarketContext(
            MarketDefinition(
                MarketId(text(node, "marketId", location)),
                AssetId(text(node, "baseAssetId", location)),
                AssetId(text(node, "quoteAssetId", location)),
                scale.toInt(),
            ),
            TradingFeePolicySnapshot(
                FeeProductType.valueOf(text(policy, "productType", policyLocation)),
                FeeTier.valueOf(text(policy, "feeTier", policyLocation)),
                integer(policy, "scheduleVersion", policyLocation),
                MakerTakerFeeRates(
                    FeeRate(integer(policy, "makerPartsPerMillion", policyLocation)),
                    FeeRate(integer(policy, "takerPartsPerMillion", policyLocation)),
                ),
            ),
        )
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("$location: ${error.message}", error)
    }
}

private fun requireFields(
    node: JsonNode,
    expected: Set<String>,
    location: String,
) {
    require(node.isObject) { "$location: 객체가 필요합니다" }
    val actual = node.propertyNames().toSet()
    require(actual == expected) { "$location: 누락 필드=${expected - actual}, 알 수 없는 필드=${actual - expected}" }
}

private fun text(
    node: JsonNode,
    field: String,
    location: String,
): String {
    val value = node.path(field)
    require(value.isString) { "$location.$field: 문자열이 필요합니다" }
    return value.asString()
}

private fun integer(
    node: JsonNode,
    field: String,
    location: String,
): Long {
    val value = node.path(field)
    require(value.isIntegralNumber && value.canConvertToLong()) { "$location.$field: Long 범위의 정수가 필요합니다" }
    return value.longValue()
}
