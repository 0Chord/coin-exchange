package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresDevelopmentMarketSettingsTest {
    private lateinit var database: MarketConfigurationDatabase

    @BeforeAll
    fun startDatabase() {
        database = MarketConfigurationDatabase()
    }

    @AfterAll
    fun stopDatabase() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `새 버전이 있어도 지정한 원문 한 버전과 실제 계산한 지문을 읽는다`() {
        val original = database.register(BTC_DOCUMENT)
        database.register(BTC_DOCUMENT.replace("\"baseQuantityScale\":8", "\"baseQuantityScale\":9"))

        val markets = readDevelopmentMarkets(original)

        assertEquals(DevelopmentMarketContext.BTC_USDT, markets.single())
        assertEquals(original.getValue("FUNDING_MARKET_CONFIG_VERSION").toLong(), markets.configurationVersion)
        assertEquals("a01e6f92204d886b5f75f803484333d506ce1155045ef3efe54128e0c62bfcbe", markets.contentSha256)
        database.connection().use { connection ->
            val sql = "select markets_document from market_configuration_release where config_version = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, markets.configurationVersion!!)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(BTC_DOCUMENT, result.getString(1))
                    assertFalse(result.next())
                }
            }
        }
    }

    @Test
    fun `같은 의미라도 원문 공백이 바뀌면 외부 기대 지문과 일치하지 않는다`() {
        val original = database.register(BTC_DOCUMENT)
        val spaced = database.register(" $BTC_DOCUMENT ")
        val mismatch = spaced + ("FUNDING_MARKET_CONFIG_SHA256" to original.getValue("FUNDING_MARKET_CONFIG_SHA256"))

        val error = assertFailsWith<IllegalArgumentException> { readDevelopmentMarkets(mismatch) }

        assertTrue(error.message.orEmpty().contains("FUNDING_MARKET_CONFIG_SHA256"))
        assertEquals(DevelopmentMarketContext.BTC_USDT, readDevelopmentMarkets(spaced).single())
    }

    @Test
    fun `없는 지정 버전은 기본 BTC나 최신 버전으로 대체하지 않는다`() {
        database.register(BTC_DOCUMENT)

        val error =
            assertFailsWith<IllegalArgumentException> {
                readDevelopmentMarkets(database.environment(Long.MAX_VALUE, BTC_DOCUMENT))
            }

        assertTrue(error.message.orEmpty().contains("FUNDING_MARKET_CONFIG_VERSION"))
    }

    @Test
    fun `DB가 보관할 수 있는 손상 JSON과 잘못된 도메인 값도 지문 일치 후 거절한다`() {
        val invalidDocuments =
            listOf(
                "[]",
                "{",
                BTC_DOCUMENT.replace("\"baseQuantityScale\":8", "\"baseQuantityScale\":19"),
                BTC_DOCUMENT.replace("\"scheduleVersion\":1", "\"scheduleVersion\":0"),
                BTC_DOCUMENT.replace("\"baseAssetId\":\"BTC\"", "\"baseAssetId\":\"BTC:INVALID\""),
            )
        invalidDocuments.forEach { document ->
            val environment = database.register(document)

            val error = assertFailsWith<IllegalArgumentException> { readDevelopmentMarkets(environment) }

            assertTrue(error.message.orEmpty().contains("markets_document"), error.message)
        }
    }

    @Test
    fun `등록한 버전은 UPDATE DELETE TRUNCATE와 같은 버전 INSERT로 바뀌지 않는다`() {
        val environment = database.register(BTC_DOCUMENT)
        val version = environment.getValue("FUNDING_MARKET_CONFIG_VERSION").toLong()
        database.connection().use { connection ->
            listOf(
                "update market_configuration_release set markets_document = '[]' where config_version = $version",
                "delete from market_configuration_release where config_version = $version",
                "truncate table market_configuration_release",
                "insert into market_configuration_release(config_version, markets_document) values ($version, '[]')",
            ).forEach { sql ->
                connection.createStatement().use { statement ->
                    assertFailsWith<SQLException> { statement.execute(sql) }
                }
                assertEquals(DevelopmentMarketContext.BTC_USDT, readDevelopmentMarkets(environment).single())
            }
        }
    }

    @Test
    fun `DB 제약은 양수 버전과 1에서 65536 UTF8 바이트의 문서를 요구한다`() {
        database.connection().use { connection ->
            val insert = "insert into market_configuration_release(config_version, markets_document) values (?, ?)"
            connection.prepareStatement(insert).use { statement ->
                val invalidRows =
                    listOf(0L to BTC_DOCUMENT, -1L to BTC_DOCUMENT, Long.MAX_VALUE to "", Long.MAX_VALUE to "가".repeat(21_846))
                invalidRows.forEach { (version, document) ->
                    statement.setLong(1, version)
                    statement.setString(2, document)
                    assertFailsWith<SQLException> { statement.executeUpdate() }
                }
            }
            connection.createStatement().use { statement ->
                val count = "select count(*) from market_configuration_release where config_version = ${Long.MAX_VALUE}"
                statement.executeQuery(count).use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
            }
        }
        val atLimit = database.register(BTC_DOCUMENT + " ".repeat(65_536 - BTC_DOCUMENT.length))
        assertEquals(DevelopmentMarketContext.BTC_USDT, readDevelopmentMarkets(atLimit).single())
    }

    @Test
    fun `DB 연결 실패는 자격정보와 원본 SQLException 없이 실패하고 기본값을 주지 않는다`() {
        val environment =
            database.environment(1, BTC_DOCUMENT) +
                mapOf(
                    "FUNDING_MARKET_DB_URL" to "jdbc:postgresql://127.0.0.1:1/unavailable",
                    "FUNDING_MARKET_DB_USER" to "private-market-user",
                    "FUNDING_MARKET_DB_PASSWORD" to "private-market-password",
                )

        val error = assertFailsWith<IllegalStateException> { readDevelopmentMarkets(environment) }

        assertTrue(error.message.orEmpty().contains("FUNDING_MARKET_DB_URL"))
        assertFalse(error.toString().contains("private-market"))
        assertFalse(error.toString().contains("jdbc:postgresql"))
        assertNull(error.cause)
    }

    @Test
    fun `조회가 잠금에 막혀도 URL의 무제한 설정을 덮어쓴 제한시간 안에 실패한다`() {
        val environment = database.register(BTC_DOCUMENT)
        val url = environment.getValue("FUNDING_MARKET_DB_URL")
        val unlimitedUrl = url + (if ('?' in url) "&" else "?") + "socketTimeout=0&connectTimeout=0&loginTimeout=0"
        val unlimited = environment + ("FUNDING_MARKET_DB_URL" to unlimitedUrl)
        database.connection().use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.execute("lock table market_configuration_release in access exclusive mode") }
            val started = System.nanoTime()

            val error = assertFailsWith<IllegalStateException> { readDevelopmentMarkets(unlimited) }

            assertTrue(error.message.orEmpty().contains("FUNDING_MARKET_DB_URL"))
            val elapsed = System.nanoTime() - started
            assertTrue(elapsed in 1_000_000_000L..15_000_000_000L, "잠긴 DB 조회는 기다리되 무기한 대기하면 안 됩니다")
            connection.rollback()
        }
        assertEquals(DevelopmentMarketContext.BTC_USDT, readDevelopmentMarkets(environment).single())
    }

    companion object {
        private const val BTC_DOCUMENT =
            "[{\"marketId\":\"BTC-USDT\",\"baseAssetId\":\"BTC\",\"quoteAssetId\":\"USDT\",\"baseQuantityScale\":8," +
                "\"feePolicy\":{\"productType\":\"SPOT\",\"feeTier\":\"NORMAL\",\"scheduleVersion\":1," +
                "\"makerPartsPerMillion\":0,\"takerPartsPerMillion\":0}}]"
    }
}
