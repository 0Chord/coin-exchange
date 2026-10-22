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
        assertEquals(setOf("configVersion", "sha256", "document", "createdAt"), json(detail).properties().map { it.key }.toSet())
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
        assertEquals(setOf("items", "nextBeforeVersion"), first.properties().map { it.key }.toSet())
        assertEquals(
            setOf("configVersion", "sha256", "createdAt"),
            first
                .path("items")
                .first()
                .properties()
                .map { it.key }
                .toSet(),
        )
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
        assertEquals(200, server.get("?limit=").statusCode())
        assertEquals(200, server.get("?beforeVersion=").statusCode())
        listOf("?limit=0", "?limit=101", "?limit=bad", "?beforeVersion=0", "?beforeVersion=9223372036854775808")
            .forEach { assertEquals(400, server.get(it).statusCode(), it) }
    }

    @Test
    fun `정의하지 않은 조회 키는 무시하고 명시한 조회 조건만 사용한다`() {
        assertEquals(200, server.put("700", DOCUMENT).statusCode())
        assertEquals(200, server.put("701", DOCUMENT).statusCode())
        val defaultPage = json(server.get(""))
        val singleItemPage = json(server.get("?limit=1"))
        assertTrue(defaultPage.path("items").size() >= 2)
        assertEquals(1, singleItemPage.path("items").size())

        listOf(
            "?limit%5B0%5D=1",
            "?beforeVersion%5B0%5D=1",
            "?limit.value=1",
            "?beforeVersion.value=1",
            "?pageLimit%5B0%5D=1",
            "?pageLimit=1",
            "?%21limit=1",
            "?%21beforeVersion=1",
            "?_limit=1",
            "?_beforeVersion=1",
        ).forEach { suffix ->
            val response = server.get(suffix)
            assertEquals(200, response.statusCode(), suffix)
            assertEquals(defaultPage, json(response), suffix)
        }
        val explicitLimit = server.get("?limit=1&limit%5B0%5D=1")
        assertEquals(200, explicitLimit.statusCode())
        assertEquals(singleItemPage, json(explicitLimit))
        val arrayLimit = server.get("?limit%5B%5D=1")
        assertEquals(200, arrayLimit.statusCode())
        assertEquals(singleItemPage, json(arrayLimit))
    }

    @Test
    fun `JSON이 아닌 본문 형식은 Spring이 저장 전에 거절하며 JSON은 등록된다`() {
        listOf(null, "text/plain", "not-a-media-type", "application/*").forEach { contentType ->
            val response = server.request("PUT", "/250", DOCUMENT.toByteArray(), contentType = contentType)
            assertEquals(415, response.statusCode(), contentType)
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
            if (index == 0 || index == 2) assertEquals("request", json(response).path("stage").asString())
            assertEquals(0, rowCount(version))
            assertSanitized(response.body())
        }
        listOf("0", "-1", "bad", "9223372036854775808").forEach {
            assertEquals(400, server.put(it, DOCUMENT).statusCode())
            assertEquals(400, server.get("/$it").statusCode())
        }
    }

    @Test
    fun `숫자 변환과 Bean Validation 오류는 저장 전에 정제한 입력 오류로 반환한다`() {
        listOf("0", "-1", "bad", "9223372036854775808").forEach { version ->
            listOf(server.put(version, DOCUMENT), server.get("/$version")).forEach { response ->
                assertEquals(400, response.statusCode(), version)
                assertEquals("INVALID_INPUT", json(response).path("code").asString())
                assertEquals("request", json(response).path("stage").asString())
                assertEquals(setOf("code", "stage", "configVersion"), json(response).properties().map { it.key }.toSet())
            }
        }
        listOf("?beforeVersion=0", "?beforeVersion=-1", "?beforeVersion=bad", "?limit=0", "?limit=101", "?limit=bad")
            .forEach { suffix ->
                val response = server.get(suffix)
                assertEquals(400, response.statusCode(), suffix)
                assertEquals("INVALID_INPUT", json(response).path("code").asString())
                assertEquals("request", json(response).path("stage").asString())
                assertEquals(setOf("code", "stage", "configVersion"), json(response).properties().map { it.key }.toSet())
            }
    }

    @Test
    fun `본문 전체가 도착하지 않아도 상한 초과를 거절하고 저장하지 않는다`() {
        listOf(false, true).forEach { chunked ->
            server.sendIncompleteOversizedBody(chunked)
            assertEquals(0, rowCount(350))
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
    fun `기본 비활성화는 DB 설정 없이 시작되며 관리 경로는 404다`() {
        MarketConfigurationHttpProcess(emptyMap()).use { disabled ->
            disabled.awaitReady()
            assertEquals(404, disabled.get("").statusCode())
            assertEquals(404, disabled.put("500", DOCUMENT).statusCode())
            assertEquals(404, disabled.get("/500").statusCode())
            assertEquals(0, rowCount(500))
        }
    }

    private fun environment(): Map<String, String> = database.environment(1, DOCUMENT) + mapOf("EXCHANGE_MARKET_ADMIN_ENABLED" to "true")

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
        listOf(DATABASE_PASSWORD, LIMITED_PASSWORD, DOCUMENT, database.environment(1, DOCUMENT).getValue("FUNDING_MARKET_DB_URL"))
            .forEach { assertFalse(output.contains(it), "응답과 로그에 비밀·문서·DB 주소를 노출하면 안 됩니다") }
    }

    companion object {
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

    fun put(
        version: String,
        document: String,
    ): HttpResponse<String> = request("PUT", "/$version", document.toByteArray(Charsets.UTF_8))

    fun get(suffix: String): HttpResponse<String> = request("GET", suffix)

    fun request(
        method: String,
        suffix: String,
        body: ByteArray? = null,
        contentType: String? = "application/json",
        accept: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/api/admin/market-configurations$suffix"))
                .timeout(Duration.ofSeconds(15))
                .method(method, body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody())
        contentType?.let { request.header("Content-Type", it) }
        accept?.let { request.header("Accept", it) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    }

    fun sendIncompleteOversizedBody(chunked: Boolean) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val framing = if (chunked) "Transfer-Encoding: chunked" else "Content-Length: 10000000"
            val output = socket.getOutputStream()
            output.write(
                (
                    "PUT /api/admin/market-configurations/350 HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\nContent-Type: application/json\r\n$framing\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII),
            )
            if (chunked) output.write("10001\r\n".toByteArray(Charsets.US_ASCII))
            output.write(ByteArray(65_537) { ' '.code.toByte() })
            if (chunked) output.write("\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            // 나머지 본문과 chunked 종료 표식을 보내지 않아도, 읽기 상한에서 응답해야 한다.
            val status = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
            assertTrue(status.contains("400"), status)
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
