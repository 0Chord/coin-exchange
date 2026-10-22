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

private const val MARKETS_ENV = "FUNDING_MARKETS_JSON"
private const val MAX_MARKET_BYTES = 65_536
private const val MAX_MARKETS = 32
private val marketJson =
    JsonMapper
        .builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()
private val marketKeys = setOf("marketId", "baseAssetId", "quoteAssetId", "baseQuantityScale", "feePolicy")
private val policyKeys = setOf("productType", "feeTier", "scheduleVersion", "makerPartsPerMillion", "takerPartsPerMillion")

/** 시작 경계에서만 읽는다. 미설정만 BTC 기본값이며, 잘못된 설정은 자원 생성 전에 실패한다. */
internal fun readDevelopmentMarkets(environment: Map<String, String>): DevelopmentMarketRegistry {
    val raw = environment[MARKETS_ENV] ?: return DevelopmentMarketRegistry(listOf(DevelopmentMarketContext.BTC_USDT))
    require(raw.length <= MAX_MARKET_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_MARKET_BYTES) {
        "$MARKETS_ENV: UTF-8 $MAX_MARKET_BYTES 바이트를 초과했습니다"
    }
    val root =
        try {
            marketJson.readTree(raw)
        } catch (error: JacksonException) {
            throw IllegalArgumentException("$MARKETS_ENV: JSON 형식 또는 중복 필드가 잘못됐습니다", error)
        }
    require(root != null && root.isArray && root.size() in 1..MAX_MARKETS) { "$MARKETS_ENV: 마켓 배열은 1~$MAX_MARKETS 개여야 합니다" }
    val contexts = root.mapIndexed { index, node -> readMarket(node, "$MARKETS_ENV[$index]") }
    require(contexts.map { it.market.marketId }.distinct().size == contexts.size) { "$MARKETS_ENV: 마켓 ID가 중복됐습니다" }
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
