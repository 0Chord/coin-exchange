package com.exchange.core.api.market.api

import com.exchange.core.api.trading.infrastructure.aeron.MarketConfigurationDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketConfigurationHttpTest {
    private lateinit var database: MarketConfigurationDatabase
    private lateinit var server: MarketConfigurationHttpProcess
    private val mapper = jacksonObjectMapper()

    @BeforeAll
    fun start() {
        database = MarketConfigurationDatabase(password = DATABASE_PASSWORD)
        server = MarketConfigurationHttpProcess(environment())
        server.awaitReady()
    }

    @AfterAll
    fun stop() {
        if (::server.isInitialized) server.close()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `실제 PUT 재실행은 같은 지문과 원문 시각을 보존하고 다른 원문은 충돌한다`() {
        val created = server.put("100", DOCUMENT)
        assertEquals(200, created.statusCode())
        assertFalse(created.headers().firstValue("Set-Cookie").isPresent)
        val expected = mapper.readTree("""{"configVersion":100,"sha256":"$DOCUMENT_SHA256"}""")
        assertEquals(expected, json(created))
        val detail = server.get("/100")
        assertEquals(200, detail.statusCode())
        assertEquals(DOCUMENT, json(detail).path("document").asString())
        assertEquals(DOCUMENT_SHA256, json(detail).path("sha256").asString())
        Instant.parse(json(detail).path("createdAt").asString())
        assertEquals(expected, json(server.put("100", DOCUMENT)))
        assertEquals(json(detail), json(server.get("/100")))

        val conflict = server.put("100", "$DOCUMENT ")
        assertEquals(409, conflict.statusCode())
        assertEquals("CONTENT_CONFLICT", json(conflict).path("code").asString())
        assertEquals(json(detail), json(server.get("/100")))
        assertEquals(1, rowCount(100))
        assertEquals(200, server.put("101", "$DOCUMENT ").statusCode())
        assertEquals(404, server.get("/999999").statusCode())
        assertEquals(405, server.request("DELETE", "/100").statusCode())
    }

    @Test
    fun `목록은 버전 역순이며 마지막 꽉 찬 페이지의 cursor 다음은 빈 페이지다`() {
        assertEquals(200, server.put("10", DOCUMENT).statusCode())
        assertEquals(200, server.put("20", DOCUMENT).statusCode())
        val first = json(server.get("?beforeVersion=21&limit=1"))
        assertEquals(
            listOf(20L),
            first
                .path("items")
                .asSequence()
                .map { it.path("configVersion").asLong() }
                .toList(),
        )
        assertEquals(20L, first.path("nextBeforeVersion").asLong())
        assertFalse(first.path("items").first().has("document"))
        val second = json(server.get("?beforeVersion=20&limit=1"))
        assertEquals(
            listOf(10L),
            second
                .path("items")
                .asSequence()
                .map { it.path("configVersion").asLong() }
                .toList(),
        )
        assertEquals(10L, second.path("nextBeforeVersion").asLong())
        val last = json(server.get("?beforeVersion=10&limit=1"))
        assertEquals(0, last.path("items").size())
        assertTrue(last.path("nextBeforeVersion").isNull)
        assertEquals(200, server.get("?limit=100").statusCode())
        assertEquals(200, server.get("").statusCode())
        listOf("?limit=0", "?limit=101", "?limit=bad", "?beforeVersion=0", "?beforeVersion=9223372036854775808")
            .forEach { assertEquals(400, server.get(it).statusCode(), it) }
    }

    @Test
    fun `빠진 틀린 중복 잘못된 인증과 쿠키는 본문 오류보다 먼저 거절하며 저장하지 않는다`() {
        val cases =
            listOf(
                emptyList(),
                listOf("Bearer ${"x".repeat(32)}"),
                listOf("Basic $TOKEN"),
                listOf("Bearer  $TOKEN"),
                listOf("Bearer $TOKEN extra"),
                listOf("Bearer $TOKEN", "Bearer $TOKEN"),
                listOf("Bearer $TOKEN, Bearer $TOKEN"),
            )
        cases.forEach { headers ->
            val response = server.request("PUT", "/200", "invalid-json".toByteArray(), headers)
            assertEquals(401, response.statusCode(), headers.size.toString())
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow())
            assertFalse(response.headers().firstValue("Set-Cookie").isPresent)
            assertEquals("UNAUTHORIZED", json(response).path("code").asString())
            assertSanitized(response.body())
        }
        assertEquals(401, server.request("GET", "/200", authorization = emptyList()).statusCode())
        server.unauthorizedHeadersWithoutBody()
        assertEquals(0, rowCount(200))
        assertEquals(200, server.request("PUT", "/200", DOCUMENT.toByteArray(), listOf("bearer $TOKEN")).statusCode())
    }

    @Test
    fun `본문 형식 누락과 잘못된 형식도 정제한 입력 오류이며 JSON은 등록된다`() {
        listOf(null, "text/plain", "not-a-media-type", "application/*").forEach { contentType ->
            val response = server.request("PUT", "/250", DOCUMENT.toByteArray(), contentType = contentType)
            assertEquals(400, response.statusCode(), contentType)
            assertEquals("INVALID_INPUT", json(response).path("code").asString())
            assertEquals("content-type", json(response).path("stage").asString())
            assertEquals(250L, json(response).path("configVersion").asLong())
            assertEquals(setOf("code", "stage", "configVersion"), json(response).properties().map { it.key }.toSet())
            assertEquals(0, rowCount(250))
        }
        listOf("application/json", "application/json; charset=UTF-8").forEach { contentType ->
            assertEquals(200, server.request("PUT", "/250", DOCUMENT.toByteArray(), contentType = contentType).statusCode())
        }
        assertEquals(1, rowCount(250))
        assertEquals(DOCUMENT, json(server.get("/250")).path("document").asString())
    }

    @Test
    fun `JSON 응답을 받지 않는 요청은 저장 전에 거절하며 JSON 응답 요청은 등록된다`() {
        val rejected =
            listOf(
                "text/plain",
                "application/json;q=0",
                "*/*;q=0",
                "application/json;q=0, */*;q=1",
                "application/json;q=0, text/plain;q=1",
                "application/*;q=0, */*;q=1",
            )
        rejected.forEach { accept ->
            assertEquals(406, server.request("PUT", "/260", DOCUMENT.toByteArray(), accept = accept).statusCode(), accept)
            assertEquals(0, rowCount(260))
        }
        listOf("application/json", "application/json;q=0.5", "application/*", "*/*", "application/json;q=1, */*;q=0")
            .forEach { accept ->
                assertEquals(200, server.request("PUT", "/260", DOCUMENT.toByteArray(), accept = accept).statusCode(), accept)
            }
        rejected.forEach { accept ->
            assertEquals(406, server.request("GET", "/260", accept = accept).statusCode(), accept)
            assertEquals(406, server.request("GET", "", accept = accept).statusCode(), accept)
        }
        assertEquals(1, rowCount(260))
        assertEquals(DOCUMENT, json(server.get("/260")).path("document").asString())
    }

    @Test
    fun `최대 바이트는 그대로 보관하고 초과 손상 UTF8 잘못된 문서 버전은 저장하지 않는다`() {
        val boundary = DOCUMENT + " ".repeat(65_536 - DOCUMENT.toByteArray().size)
        assertEquals(200, server.put("300", boundary).statusCode())
        assertEquals(boundary, json(server.get("/300")).path("document").asString())
        val invalid = listOf((boundary + " ").toByteArray(), byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(), "[]".toByteArray())
        invalid.forEachIndexed { index, bytes ->
            val version = 301L + index
            val response = server.request("PUT", "/$version", bytes)
            assertEquals(400, response.statusCode())
            assertEquals("INVALID_INPUT", json(response).path("code").asString())
            assertEquals(0, rowCount(version))
            assertSanitized(response.body())
        }
        listOf("0", "-1", "bad", "9223372036854775808").forEach {
            assertEquals(400, server.put(it, DOCUMENT).statusCode())
            assertEquals(400, server.get("/$it").statusCode())
        }
    }

    @Test
    fun `원문 SELECT 권한 실패는 등록과 조회 오류로 구분하고 INSERT를 롤백한다`() {
        database.connection().use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("create role market_http_limited login password '$LIMITED_PASSWORD'")
                sql.execute("grant usage on schema public to market_http_limited")
                sql.execute("grant insert on market_configuration_release to market_http_limited")
                sql.execute("grant select (config_version) on market_configuration_release to market_http_limited")
            }
        }
        val restricted =
            environment() + mapOf("FUNDING_MARKET_DB_USER" to "market_http_limited", "FUNDING_MARKET_DB_PASSWORD" to LIMITED_PASSWORD)
        MarketConfigurationHttpProcess(restricted).use { limited ->
            limited.awaitReady()
            val registration = limited.put("400", DOCUMENT)
            assertEquals(503, registration.statusCode())
            assertEquals("REGISTRATION_FAILED", json(registration).path("code").asString())
            assertEquals("compare", json(registration).path("stage").asString())
            assertEquals(0, rowCount(400))
            val lookup = limited.get("/400")
            assertEquals(503, lookup.statusCode())
            assertEquals("LOOKUP_FAILED", json(lookup).path("code").asString())
            assertSanitized(registration.body() + lookup.body() + limited.output())
        }
    }

    @Test
    fun `쓰기 도중 실제 DB 연결 단절은 확정 실패와 다른 미확인 코드다`() {
        database.connection().use { connection ->
            connection.createStatement().use { sql ->
                sql.execute(
                    """
                    create function interrupt_market_http_registration() returns trigger language plpgsql as ${'$'}${'$'}
                    begin
                        if new.config_version = 450 then perform pg_terminate_backend(pg_backend_pid()); end if;
                        return new;
                    end;
                    ${'$'}${'$'}
                    """.trimIndent(),
                )
                sql.execute(
                    "create trigger interrupt_market_http_registration after insert on market_configuration_release " +
                        "for each row execute function interrupt_market_http_registration()",
                )
            }
        }
        try {
            val response = server.put("450", DOCUMENT)
            assertEquals(503, response.statusCode())
            assertEquals("RESULT_UNCONFIRMED", json(response).path("code").asString())
            assertEquals("insert", json(response).path("stage").asString())
            assertSanitized(response.body())
        } finally {
            database.connection().use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("drop trigger interrupt_market_http_registration on market_configuration_release")
                    sql.execute("drop function interrupt_market_http_registration()")
                }
            }
        }
        // 이 장애는 INSERT 연결 단절이다. COMMIT 응답 유실을 재현했다는 뜻은 아니다.
        assertEquals(0, rowCount(450))
        assertEquals(200, server.put("450", DOCUMENT).statusCode())
    }

    @Test
    fun `기본 비활성화는 자격정보 없이 시작되며 관리 경로는 404다`() {
        MarketConfigurationHttpProcess(emptyMap()).use { disabled ->
            disabled.awaitReady()
            assertEquals(404, disabled.get("").statusCode())
            assertEquals(404, disabled.put("500", DOCUMENT).statusCode())
            assertEquals(404, disabled.get("/500").statusCode())
            assertEquals(0, rowCount(500))
        }
    }

    @Test
    fun `활성화 상태에서 토큰 누락 짧음 공백 비ASCII는 서버 시작을 거절한다`() {
        listOf(null, "short", "x".repeat(31) + " ", "x".repeat(31) + "가").forEach { token ->
            val settings = environment().toMutableMap()
            if (token == null) settings.remove("EXCHANGE_MARKET_ADMIN_TOKEN") else settings["EXCHANGE_MARKET_ADMIN_TOKEN"] = token
            MarketConfigurationHttpProcess(settings).use { invalid ->
                invalid.awaitFailure()
                assertTrue(invalid.output().contains("EXCHANGE_MARKET_ADMIN_TOKEN must contain"))
                assertSanitized(invalid.output())
            }
        }
    }

    private fun environment(): Map<String, String> =
        database.environment(1, DOCUMENT) + mapOf("EXCHANGE_MARKET_ADMIN_ENABLED" to "true", "EXCHANGE_MARKET_ADMIN_TOKEN" to TOKEN)

    private fun rowCount(version: Long): Int =
        database.connection().use { connection ->
            connection.prepareStatement("select count(*) from market_configuration_release where config_version = ?").use { statement ->
                statement.setLong(1, version)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())

    private fun assertSanitized(output: String) {
        listOf(TOKEN, DATABASE_PASSWORD, LIMITED_PASSWORD, DOCUMENT, database.environment(1, DOCUMENT).getValue("FUNDING_MARKET_DB_URL"))
            .forEach { assertFalse(output.contains(it), "응답과 로그에 비밀·문서·DB 주소를 노출하면 안 됩니다") }
    }

    companion object {
        private const val TOKEN = "market-admin-http-token-secret-1234567890"
        private const val DATABASE_PASSWORD = "market-http-database-secret"
        private const val LIMITED_PASSWORD = "limited-http-database-secret"
        private const val DOCUMENT =
            "[{\"marketId\":\"BTC-USDT\",\"baseAssetId\":\"BTC\",\"quoteAssetId\":\"USDT\",\"baseQuantityScale\":8," +
                "\"feePolicy\":{\"productType\":\"SPOT\",\"feeTier\":\"NORMAL\",\"scheduleVersion\":1," +
                "\"makerPartsPerMillion\":0,\"takerPartsPerMillion\":0}}]"
        private const val DOCUMENT_SHA256 = "a01e6f92204d886b5f75f803484333d506ce1155045ef3efe54128e0c62bfcbe"
    }
}

private class MarketConfigurationHttpProcess(
    environment: Map<String, String>,
) : AutoCloseable {
    private val directory = Files.createTempDirectory("market-http-")
    private val ready = directory.resolve("ready")
    private val log = directory.resolve("server.log")
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val process =
        ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("marketConfigurationHttpTestClasspath"),
            "com.exchange.core.api.market.api.MarketConfigurationHttpTestServerKt",
            ready.toString(),
        ).apply {
            environment().keys.removeIf { it.startsWith("FUNDING_") || it.startsWith("EXCHANGE_MARKET_ADMIN_") }
            environment().putAll(environment)
            redirectErrorStream(true)
            redirectOutput(log.toFile())
        }.start()

    private var port = 0

    fun awaitReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!Files.exists(ready) && process.isAlive && System.nanoTime() < deadline) Thread.sleep(25)
        check(Files.exists(ready)) { "HTTP test server did not start: ${output()}" }
        port = Files.readString(ready).toInt()
    }

    fun awaitFailure() {
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "잘못된 인증 설정은 시작을 끝내야 합니다")
        assertTrue(process.exitValue() != 0)
        assertFalse(Files.exists(ready))
    }

    fun put(
        version: String,
        document: String,
    ): HttpResponse<String> = request("PUT", "/$version", document.toByteArray(Charsets.UTF_8))

    fun get(suffix: String): HttpResponse<String> = request("GET", suffix)

    fun request(
        method: String,
        suffix: String,
        body: ByteArray? = null,
        authorization: List<String> = listOf("Bearer market-admin-http-token-secret-1234567890"),
        contentType: String? = "application/json",
        accept: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/api/admin/market-configurations$suffix"))
                .timeout(Duration.ofSeconds(15))
                .header("Cookie", "EXCHANGE_MARKET_ADMIN_TOKEN=market-admin-http-token-secret-1234567890")
                .method(method, body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody())
        contentType?.let { request.header("Content-Type", it) }
        accept?.let { request.header("Accept", it) }
        authorization.forEach { request.header("Authorization", it) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    }

    fun unauthorizedHeadersWithoutBody() {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write(
                (
                    "PUT /api/admin/market-configurations/200 HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 65537\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII),
            )
            socket.getOutputStream().flush()
            // 본문을 한 바이트도 보내지 않아도 인증 거절을 받아야 한다.
            assertTrue(
                socket
                    .getInputStream()
                    .bufferedReader(Charsets.US_ASCII)
                    .readLine()
                    .contains("401"),
            )
        }
    }

    fun output(): String = Files.readString(log)

    override fun close() {
        client.close()
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS))
        }
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
