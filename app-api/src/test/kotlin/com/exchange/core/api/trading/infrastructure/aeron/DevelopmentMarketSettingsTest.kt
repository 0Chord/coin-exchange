package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.MarketId
import com.exchange.core.fee.FeeTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevelopmentMarketSettingsTest {
    @Test
    fun `미설정만 기존 BTC를 사용하고 DB 문서는 순서에 상관없이 원래 값을 보존한다`() {
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
    fun `명시한 PostgreSQL 주소와 DNS IPv4 IPv6 SSL 제한시간 옵션을 보존한다`() {
        listOf(
            "jdbc:postgresql://localhost/database",
            "jdbc:postgresql://market-db:5432/exchange_core",
            "jdbc:postgresql://db.example.com:6543/exchange",
            "jdbc:postgresql://127.0.0.1:1/database",
            "jdbc:postgresql://[::1]:5432/database",
            "jdbc:postgresql://[2001:db8::1]/database",
            "jdbc:postgresql://localhost/database?ssl=true&sslmode=verify-full&sslrootcert=%2Ftmp%2Froot.crt",
            "jdbc:postgresql://localhost/database?connectTimeout=0&socketTimeout=0&loginTimeout=0&readOnlyMode=ignore",
            "jdbc:postgresql://localhost/database?ApplicationName=market%20reader&currentSchema=public",
        ).forEach(::validateDevelopmentMarketDatabaseUrl)
    }

    @Test
    fun `잘못된 URL과 주소 자격정보 우회 옵션은 드라이버 호출 전에 원문과 cause 없이 거절한다`() {
        val invalidUrls =
            listOf(
                "jdbc:postgresql://127.0.0.1:1?password=review-secret",
                "jdbc:postgresql://127.0.0.1:1/db/extra?password=review-secret",
                "jdbc:postgresql://localhost:review-secret/database",
                "jdbc:postgresql://localhost:0/database",
                "jdbc:postgresql://localhost:65536/database",
                "jdbc:postgresql://localhost:/database",
                "jdbc:postgresql://localhost/",
                "jdbc:postgresql:///database",
                "jdbc:postgresql:database",
                "jdbc:postgresql://private-user:review-secret@localhost/database",
                "jdbc:postgresql://localhost/database#review-secret",
                "jdbc:postgresql://localhost/database?sslmode=%review-secret",
                "jdbc:postgresql://localhost/database?%70assword=review-secret",
                "jdbc:postgresql://[::1/database",
                "jdbc:postgresql://localhost,other/database",
            ) +
                listOf("user", "password", "PASSWORD", "service", "host", "port", "dbname", "PGHOST", "PGPORT", "PGDBNAME").map {
                    "jdbc:postgresql://localhost/database?$it=review-secret"
                }
        invalidUrls.forEach { url ->
            val environment =
                mapOf(
                    "FUNDING_MARKET_DB_URL" to url,
                    "FUNDING_MARKET_DB_USER" to "private-user",
                    "FUNDING_MARKET_DB_PASSWORD" to "private-password",
                    "FUNDING_MARKET_CONFIG_VERSION" to "1",
                    "FUNDING_MARKET_CONFIG_SHA256" to "a".repeat(64),
                )

            val error = assertFailsWith<IllegalArgumentException> { readDevelopmentMarkets(environment) }

            assertTrue(error.message.orEmpty().contains("FUNDING_MARKET_DB_URL"))
            assertFalse(error.stackTraceToString().contains(url))
            listOf("review-secret", "private-user", "private-password").forEach { secret ->
                assertFalse(error.stackTraceToString().contains(secret))
            }
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
        }
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
        assertTrue(error.message.orEmpty().contains("markets_document"), error.message)
    }

    private fun read(json: String): DevelopmentMarketRegistry = parseDevelopmentMarketsDocument(json)

    private fun market(
        id: String = "BTC-USDT",
        asset: String = "BTC",
        scale: Int = 8,
        version: Long = 1,
    ): String =
        """{"marketId":"$id","baseAssetId":"$asset","quoteAssetId":"USDT","baseQuantityScale":$scale,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":$version,"makerPartsPerMillion":0,"takerPartsPerMillion":0}}"""
}
