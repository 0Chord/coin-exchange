package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.MarketId
import com.exchange.core.fee.FeeTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DevelopmentMarketSettingsTest {
    @Test
    fun `미설정만 기존 BTC를 사용하고 외부 목록은 순서에 상관없이 원래 값을 보존한다`() {
        assertEquals(DevelopmentMarketContext.BTC_USDT, readDevelopmentMarkets(emptyMap()).single())
        val btc = market()
        val sui = market("SUI-USDT", "SUI", 9, 2)
        val registry = read("[$sui,$btc]")
        assertEquals(listOf("BTC-USDT", "SUI-USDT"), registry.all().map { it.market.marketId.value })
        assertEquals(DevelopmentMarketContext.BTC_USDT, registry[MarketId("BTC-USDT")])
        val actual = requireNotNull(registry[MarketId("SUI-USDT")])
        assertEquals("SUI", actual.market.baseAssetId.value)
        assertEquals("USDT", actual.market.quoteAssetId.value)
        assertEquals(9, actual.market.baseAssetScale)
        assertEquals(2L, actual.feePolicySnapshot.scheduleVersion)
        assertEquals(0L, actual.feePolicySnapshot.feeRates.makerFeeRate.partsPerMillion)
        assertEquals(0L, actual.feePolicySnapshot.feeRates.takerFeeRate.partsPerMillion)
    }

    @Test
    fun `빈 값 루트 오류 뒤따르는 JSON과 중복 필드를 거절한다`() {
        listOf(
            "",
            " ",
            "null",
            "[]",
            "{}",
            "[null]",
            "[1]",
            "[{}]",
            "[",
            "[${market()}] []",
            "[${market()},${market()}]",
            "[${market()},${market(scale = 9)}]",
            "[${market().replace("\"marketId\":", "\"marketId\":\"X\",\"marketId\":")}]",
            "[${market().replace("\"feeTier\":", "\"feeTier\":\"VIP\",\"feeTier\":")}]",
        ).forEach { invalid -> assertInvalid(invalid) }
    }

    @Test
    fun `마켓과 정책의 필수 필드 누락 추가와 null을 거절한다`() {
        val fields =
            mapOf(
                "marketId" to "\"BTC-USDT\"",
                "baseAssetId" to "\"BTC\"",
                "quoteAssetId" to "\"USDT\"",
                "baseQuantityScale" to "8",
                "productType" to "\"SPOT\"",
                "feeTier" to "\"NORMAL\"",
                "scheduleVersion" to "1",
                "makerPartsPerMillion" to "0",
                "takerPartsPerMillion" to "0",
            )
        fields.forEach { (field, value) ->
            val entry = "\"$field\":$value"
            val missing = market().replace("$entry,", "").replace(",$entry", "")
            assertTrue(missing != market(), field)
            assertInvalid("[$missing]")
            assertInvalid("[${market().replace(entry, "\"$field\":null")}]")
        }
        assertInvalid("[${market().replace("\"marketId\":", "\"extra\":1,\"marketId\":")}]")
        assertInvalid("[${market().replace("\"feeTier\":", "\"extra\":1,\"feeTier\":")}]")
        assertInvalid("[${market().replace(Regex("\"feePolicy\":\\{[^}]*}"), "\"feePolicy\":null")}]")
        assertInvalid("[${market().replace(Regex(",\"feePolicy\":\\{[^}]*}"), "")}]")
    }

    @Test
    fun `정수 문자열 소수 지수와 Long 범위 초과를 강제 변환하지 않는다`() {
        mapOf("baseQuantityScale" to "8", "scheduleVersion" to "1", "makerPartsPerMillion" to "0", "takerPartsPerMillion" to "0")
            .forEach { (field, value) ->
                listOf("\"$value\"", "$value.0", "${value}e0", "true", "9223372036854775808", "-9223372036854775809")
                    .forEach { invalid -> assertInvalid("[${market().replace("\"$field\":$value", "\"$field\":$invalid")}]") }
            }
    }

    @Test
    fun `기존 도메인의 수량 요율 버전 식별자 규칙을 그대로 검사한다`() {
        listOf(-1, 19).forEach { assertInvalid("[${market(scale = it)}]") }
        listOf(0L, -1L).forEach { assertInvalid("[${market(version = it)}]") }
        listOf("makerPartsPerMillion", "takerPartsPerMillion").forEach { field ->
            listOf(-1, 1_000_001).forEach { rate -> assertInvalid("[${market().replace("\"$field\":0", "\"$field\":$rate")}]") }
        }
        listOf(
            market(asset = "USDT"),
            market(asset = "B:TC"),
            market(asset = ""),
            market(id = ""),
            market(id = "x".repeat(65)),
            market(id = "\\u0000"),
            market(id = "\\uD800"),
            market().replace("\"SPOT\"", "\"FUTURES\""),
            market().replace("\"NORMAL\"", "\"UNKNOWN\""),
            market().replace("\"BTC\"", "100"),
        ).forEach { assertInvalid("[$it]") }
        listOf(0, 18).forEach { scale ->
            val result = read("[${market(scale = scale, version = Long.MAX_VALUE)}]").single()
            assertEquals(scale, result.market.baseAssetScale)
            assertEquals(Long.MAX_VALUE, result.feePolicySnapshot.scheduleVersion)
        }
        FeeTier.entries.forEach { tier ->
            val json =
                market()
                    .replace(
                        "\"NORMAL\"",
                        "\"${tier.name}\"",
                    ).replace("\"makerPartsPerMillion\":0", "\"makerPartsPerMillion\":1000000")
            val policy = read("[$json]").single().feePolicySnapshot
            assertEquals(tier, policy.feeTier)
            assertEquals(1_000_000L, policy.feeRates.makerFeeRate.partsPerMillion)
        }
    }

    @Test
    fun `입력은 최대 65536 UTF8 바이트와 32 마켓까지 읽는다`() {
        val json = "[${market()}]"
        assertEquals(DevelopmentMarketContext.BTC_USDT, read(json.padEnd(65_536)).single())
        assertInvalid(json.padEnd(65_537))
        val korean = "[${market(id = "가")}]".padEnd(65_535)
        assertEquals(65_537, korean.toByteArray(Charsets.UTF_8).size)
        assertInvalid(korean)
        assertEquals(32, read((1..32).joinToString(",", "[", "]") { market("M$it") }).all().size)
        assertInvalid((1..33).joinToString(",", "[", "]") { market("M$it") })
    }

    private fun assertInvalid(json: String) {
        val error = assertFailsWith<IllegalArgumentException> { read(json) }
        assertTrue(error.message.orEmpty().contains("FUNDING_MARKETS_JSON"), error.message)
    }

    private fun read(json: String): DevelopmentMarketRegistry = readDevelopmentMarkets(mapOf("FUNDING_MARKETS_JSON" to json))

    private fun market(
        id: String = "BTC-USDT",
        asset: String = "BTC",
        scale: Int = 8,
        version: Long = 1,
    ): String =
        """{"marketId":"$id","baseAssetId":"$asset","quoteAssetId":"USDT","baseQuantityScale":$scale,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":$version,"makerPartsPerMillion":0,"takerPartsPerMillion":0}}"""
}
