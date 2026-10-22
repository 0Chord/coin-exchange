package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 실제 main을 별도 JVM에서 실행해 종료 코드와 출력, 저장된 원문을 함께 확인한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevelopmentMarketRegistrationMainTest {
    @TempDir
    lateinit var root: Path
    private lateinit var database: MarketConfigurationDatabase
    private val runs = AtomicInteger()

    @BeforeAll
    fun startDatabase() {
        database = MarketConfigurationDatabase()
    }

    @AfterAll
    fun stopDatabase() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `처음 출력이 소비되지 않아도 같은 명령은 한 행과 최초 등록 시각을 유지한다`() {
        val environment = database.environment(7101, DOCUMENT)
        val first = run(7101, DOCUMENT.toByteArray(), environment)
        val row = row(7101)
        // 첫 출력으로 다음 입력을 만들지 않는다. 응답을 놓쳐도 명시한 같은 요청을 보낸다.
        val repeated = run(7101, DOCUMENT.toByteArray(), environment)

        assertEquals(0, first.exitCode, first.stderr)
        assertEquals(0, repeated.exitCode, repeated.stderr)
        assertEquals(
            "CONFIRMED configVersion=7101 sha256=ad7f03a451841c9d62d0e98581ea7aeff35b1b310e44d23c72404473d50baa3a\n",
            repeated.stdout,
        )
        assertEquals(first.stdout, repeated.stdout)
        assertEquals("", first.stderr + repeated.stderr)
        assertEquals(DOCUMENT, row.first)
        assertEquals(row, row(7101))
        val loaded = readDevelopmentMarkets(environment)
        assertEquals(listOf("BTC-USDT", "SUI-USDT"), loaded.all().map { it.market.marketId.value })
        assertEquals("ad7f03a451841c9d62d0e98581ea7aeff35b1b310e44d23c72404473d50baa3a", loaded.contentSha256)
    }

    @Test
    fun `표현만 다른 재실행은 최초 결과를 반환하고 실제 수수료 변경은 새 버전으로 등록한다`() {
        val first = run(7102, DOCUMENT.toByteArray())
        assertEquals(0, first.exitCode)
        val original = row(7102)

        listOf(" \n$DOCUMENT\n", REORDERED_DOCUMENT).forEach { equivalent ->
            val repeated = run(7102, equivalent.toByteArray())
            assertEquals(0, repeated.exitCode, repeated.stderr)
            assertEquals(first.stdout, repeated.stdout)
            assertEquals("", repeated.stderr)
            assertEquals(original, row(7102))
        }

        val changed = DOCUMENT.replaceFirst("\"takerPartsPerMillion\":0", "\"takerPartsPerMillion\":1000")
        val conflict = run(7102, changed.toByteArray())
        assertEquals(2, conflict.exitCode)
        assertEquals("", conflict.stdout)
        assertTrue(conflict.stderr.contains("CONTENT_CONFLICT"), conflict.stderr)
        assertEquals(original, row(7102))
        assertEquals(0, run(7103, changed.toByteArray()).exitCode)
        assertEquals(changed, row(7103).first)
    }

    @Test
    fun `잘못된 바이트와 문서 옵션은 성공 출력과 새 행을 만들지 않는다`() {
        val invalid =
            listOf(
                byteArrayOf(0xC3.toByte(), 0x28),
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + DOCUMENT.toByteArray(),
                ByteArray(0),
                """{"sensitive-document-field": 1}""".toByteArray(),
                (DOCUMENT + " ".repeat(65_537 - DOCUMENT.length)).toByteArray(),
            )
        invalid.forEachIndexed { index, bytes ->
            val version = 7200L + index
            val result = run(version, bytes)
            assertEquals(2, result.exitCode, result.stderr)
            assertEquals("", result.stdout)
            assertTrue(result.stderr.contains("INVALID_INPUT"), result.stderr)
            assertFalse(result.stderr.contains("sensitive-document-field"))
            assertAbsent(version)
        }
        val tooMany = run(7299, DOCUMENT.toByteArray(), extra = listOf("--version", "5"))
        assertEquals(2, tooMany.exitCode)
        assertEquals("", tooMany.stdout)
        assertAbsent(7299)
        val badVersion = run(0, DOCUMENT.toByteArray())
        assertEquals(2, badVersion.exitCode)
        assertEquals("", badVersion.stdout)
    }

    @Test
    fun `최대 크기 원문도 바이트를 바꾸지 않고 등록한다`() {
        val document = DOCUMENT + " ".repeat(65_536 - DOCUMENT.length)
        val result = run(7301, document.toByteArray())
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals(document, row(7301).first)
    }

    @Test
    fun `환경과 실제 접속 오류는 주소 자격정보나 원문을 노출하지 않는다`() {
        val base = database.environment(7401, DOCUMENT)
        val failures =
            listOf(
                base - "FUNDING_MARKET_DB_PASSWORD",
                base + ("FUNDING_MARKET_DB_URL" to "jdbc:postgresql://user-secret:password-secret@localhost/db"),
                base +
                    mapOf(
                        "FUNDING_MARKET_DB_URL" to "jdbc:postgresql://127.0.0.1:1/unavailable",
                        "FUNDING_MARKET_DB_USER" to "user-secret",
                        "FUNDING_MARKET_DB_PASSWORD" to "password-secret",
                    ),
            )
        failures.forEach { environment ->
            val result = run(7401, DOCUMENT.toByteArray(), environment)
            assertEquals(2, result.exitCode, result.stderr)
            assertEquals("", result.stdout)
            listOf("user-secret", "password-secret", "jdbc:postgresql", DOCUMENT, "Exception", "at com.exchange").forEach {
                assertFalse(result.stderr.contains(it), result.stderr)
            }
            assertAbsent(7401)
        }
    }

    private fun run(
        version: Long,
        bytes: ByteArray,
        environment: Map<String, String> = database.environment(version, DOCUMENT),
        extra: List<String> = emptyList(),
    ): Result {
        val directory = Files.createDirectory(root.resolve("run-" + runs.incrementAndGet()))
        val input = directory.resolve("markets.json")
        Files.write(input, bytes)
        val stdout = directory.resolve("stdout")
        val stderr = directory.resolve("stderr")
        val command =
            listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("developmentFundingRuntimeClasspath"),
                "com.exchange.core.api.trading.infrastructure.aeron.DevelopmentMarketRegistrationKt",
                "--version",
                version.toString(),
                "--document",
                input.toString(),
            ) + extra
        val builder = ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
        builder.environment().keys.removeAll { it.startsWith("FUNDING_MARKET") || it.startsWith("EXCHANGE_MARKET_ADMIN") }
        builder.environment().putAll(environment)
        val process = builder.start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "등록 main이 종료되지 않았습니다")
            return Result(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun row(version: Long): Pair<String, String> =
        database.connection().use { connection ->
            connection
                .prepareStatement(
                    "select markets_document, created_at from market_configuration_release where config_version = ?",
                ).use { statement ->
                    statement.setLong(1, version)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        val result = rows.getString(1) to rows.getString(2)
                        assertFalse(rows.next())
                        result
                    }
                }
        }

    private fun assertAbsent(version: Long) {
        database.connection().use { connection ->
            connection.prepareStatement("select count(*) from market_configuration_release where config_version = ?").use { statement ->
                statement.setLong(1, version)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
    }

    private data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    companion object {
        private const val DOCUMENT =
            """[{"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,""" +
                """"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,"makerPartsPerMillion":0,""" +
                """"takerPartsPerMillion":0}},{"marketId":"SUI-USDT","baseAssetId":"SUI","quoteAssetId":"USDT",""" +
                """"baseQuantityScale":9,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":2,""" +
                """"makerPartsPerMillion":0,"takerPartsPerMillion":0}}]"""
        private const val REORDERED_DOCUMENT =
            """[{"feePolicy":{"takerPartsPerMillion":0,"makerPartsPerMillion":0,"scheduleVersion":2,""" +
                """"feeTier":"NORMAL","productType":"SPOT"},"baseQuantityScale":9,"quoteAssetId":"USDT",""" +
                """"baseAssetId":"SUI","marketId":"SUI-USDT"},{"feePolicy":{"takerPartsPerMillion":0,""" +
                """"makerPartsPerMillion":0,"scheduleVersion":1,"feeTier":"NORMAL","productType":"SPOT"},""" +
                """"baseQuantityScale":8,"quoteAssetId":"USDT","baseAssetId":"BTC","marketId":"BTC-USDT"}]"""
    }
}
