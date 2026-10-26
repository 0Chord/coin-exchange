package com.exchange.core.api.trading.api

import com.exchange.core.ExchangeCoreApplication
import com.exchange.core.TradingGatewayApplication
import com.exchange.core.api.common.TradingOrderHttpExceptionHandler
import com.exchange.core.api.config.TradingGatewayConfig
import com.exchange.core.api.trading.application.SubmitTradingOrderUseCase
import com.exchange.core.api.trading.application.TradingAdmissionResult
import com.exchange.core.api.trading.application.TradingOrderGateway
import com.exchange.core.api.trading.application.TradingOrderSubmission
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.boot.test.context.AnnotatedClassFinder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 실제 HTTP 포트와 제품 DTO·controller를 확인한다. Aeron 송수신은 별도 세 노드 통합 검증의 책임이다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradingOrderHttpTest {
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var gateway: HttpContractTradingGateway
    private lateinit var baseUrl: String
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = JsonMapper.builder().build()

    @BeforeAll
    fun startHttpServer() {
        context =
            SpringApplicationBuilder(TradingOrderHttpConfig::class.java)
                .profiles("aeron-gateway")
                .run("--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")
        gateway = context.getBean(HttpContractTradingGateway::class.java)
        val port = requireNotNull((context as ServletWebServerApplicationContext).webServer).port
        baseUrl = "http://127.0.0.1:$port"
    }

    @AfterAll
    fun stopHttpServer() {
        if (::context.isInitialized) context.close()
        client.close()
    }

    @BeforeEach
    fun prepareResult() {
        gateway.submissions.clear()
        gateway.result = CompletableFuture.completedFuture(TradingAdmissionResult.Accepted(17))
        gateway.submitted = CountDownLatch(1)
    }

    @Test
    fun `엔진 접수 결과를 기다린 뒤 202에 서로 다른 식별자와 최초 순번을 반환한다`() {
        gateway.result = CompletableFuture()
        val pending = client.sendAsync(request(order()), HttpResponse.BodyHandlers.ofString())

        assertTrue(gateway.submitted.await(3, TimeUnit.SECONDS))
        assertFalse(pending.isDone)
        gateway.result.complete(TradingAdmissionResult.Accepted(71))

        val response = pending.get(3, TimeUnit.SECONDS)
        assertEquals(202, response.statusCode())
        val body = json.readTree(response.body())
        assertIdentifiers(body)
        assertEquals("ACCEPTED", body.path("status").asString())
        assertEquals(71L, body.path("sequence").longValue())
        assertFalse(body.has("reason"))
        val submitted = gateway.submissions.single()
        assertEquals("R1", submitted.requestId)
        assertEquals("O1", submitted.order.orderId.value)
        assertEquals("buyer", submitted.order.userId.value)
        assertEquals(100_000_000L, submitted.order.quantity.value)
    }

    @Test
    fun `요청 충돌과 주문 중복은 각각의 reason을 보존해 409로 반환한다`() {
        listOf("REQUEST_CONTENT_CONFLICT", "DUPLICATE_ORDER", "REQUEST_IN_FLIGHT_CONTENT_CONFLICT").forEach { reason ->
            assertResult(TradingAdmissionResult.Rejected(reason), 409, "REJECTED", reason)
        }
    }

    @Test
    fun `자금 부족과 미지원 주문 마켓 정책은 422 업무 거절이다`() {
        listOf("INSUFFICIENT_BALANCE", "UNSUPPORTED_ORDER_TYPE", "UNKNOWN_MARKET", "FEE_POLICY_MISMATCH").forEach { reason ->
            assertResult(TradingAdmissionResult.Rejected(reason), 422, "REJECTED", reason)
        }
    }

    @Test
    fun `큐 초과와 종료의 미전달을 결과 미확인과 구분한다`() {
        assertResult(TradingAdmissionResult.NotSubmitted("QUEUE_CAPACITY", overloaded = true), 429, "NOT_SUBMITTED", "QUEUE_CAPACITY")
        assertResult(TradingAdmissionResult.NotSubmitted("GATEWAY_CLOSED"), 503, "NOT_SUBMITTED", "GATEWAY_CLOSED")
        assertResult(TradingAdmissionResult.Unknown("CONNECTION_LOST"), 503, "OUTCOME_UNKNOWN", "CONNECTION_LOST")
        assertResult(TradingAdmissionResult.Unknown("RESULT_TIMEOUT", timedOut = true), 504, "OUTCOME_UNKNOWN", "RESULT_TIMEOUT")
        assertResult(TradingAdmissionResult.InvalidInput("INVALID_IDENTIFIER"), 400, "INVALID_INPUT", "INVALID_IDENTIFIER")
    }

    @Test
    fun `JSON 필수값 enum 정수 범위 양수 조건 오류는 gateway 호출 전에 400이다`() {
        val valid = order()
        val malformed =
            listOf(
                "{",
                valid.replace("\"requestId\":\"R1\",", ""),
                valid.replace("\"requestId\":\"R1\"", "\"requestId\":null"),
                valid.replace("\"userId\":\"buyer\"", "\"userId\":\" \""),
                valid.replace("\"side\":\"BUY\"", "\"side\":\"INVALID\""),
                valid.replace("\"side\":\"BUY\"", "\"side\":0"),
                valid.replace("\"price\":10000", "\"price\":0"),
                valid.replace("\"quantity\":100000000", "\"quantity\":-1"),
                valid.replace("\"quantity\":100000000", "\"quantity\":0"),
                valid.replace("\"price\":10000", "\"price\":1.5"),
                valid.replace("\"price\":10000", "\"price\":\"10000\""),
                valid.replace("\"price\":10000", "\"price\":9223372036854775808"),
                valid.replace("\"price\":10000", "\"price\":null"),
                valid.replace("\"price\":10000,", ""),
            )
        malformed.forEach { body ->
            val response = client.send(request(body), HttpResponse.BodyHandlers.ofString())
            assertEquals(400, response.statusCode(), body)
            val failure = json.readTree(response.body())
            assertEquals("INVALID_INPUT", failure.path("status").asString(), body)
            assertEquals("INVALID_REQUEST", failure.path("reason").asString(), body)
        }
        val invalidPath = client.send(request(valid, "/api/trading/markets/%20/orders"), HttpResponse.BodyHandlers.ofString())
        assertEquals(400, invalidPath.statusCode())
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `본문의 각 식별자는 문자열 아닌 JSON 값을 변환하지 않고 400으로 거절한다`() {
        val identifiers = mapOf("requestId" to "R1", "orderId" to "O1", "userId" to "buyer")
        val invalidValues = listOf("123", "1.0", "true", "false", "[]", "{}", "null")
        identifiers.forEach { (field, original) ->
            invalidValues.forEach { value ->
                val body = order().replace("\"$field\":\"$original\"", "\"$field\":$value")
                val response = client.send(request(body), HttpResponse.BodyHandlers.ofString())
                assertEquals(400, response.statusCode(), body)
                val failure = json.readTree(response.body())
                assertEquals("INVALID_INPUT", failure.path("status").asString(), body)
                assertEquals("INVALID_REQUEST", failure.path("reason").asString(), body)
                assertTrue(gateway.submissions.isEmpty(), body)
            }
        }
    }

    @Test
    fun `숫자 불리언처럼 보이는 문자열 식별자와 URL 마켓 원문은 그대로 보존한다`() {
        listOf("123", "1.0", "false").forEach { identifier ->
            val body =
                order()
                    .replace("\"R1\"", "\"$identifier\"")
                    .replace("\"O1\"", "\"$identifier\"")
                    .replace("\"buyer\"", "\"$identifier\"")
            val response =
                client.send(request(body, "/api/trading/markets/$identifier/orders"), HttpResponse.BodyHandlers.ofString())
            assertEquals(202, response.statusCode(), body)
            val accepted = json.readTree(response.body())
            listOf("requestId", "orderId", "userId", "marketId").forEach { field ->
                assertEquals(identifier, accepted.path(field).asString(), field)
            }
            val submitted = gateway.submissions.last()
            assertEquals(identifier, submitted.requestId)
            assertEquals(identifier, submitted.order.orderId.value)
            assertEquals(identifier, submitted.order.userId.value)
            assertEquals(identifier, submitted.order.marketId.value)
        }
    }

    @Test
    fun `Aeron 전용 HTTP 구성은 기존 RDB 제출과 취소 경로를 열지 않는다`() {
        val post = client.send(request(order(), "/api/markets/BTC-USDT/orders"), HttpResponse.BodyHandlers.ofString())
        val delete =
            client.send(
                HttpRequest
                    .newBuilder(URI("$baseUrl/api/markets/BTC-USDT/orders/O1?userId=buyer"))
                    .DELETE()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(404, post.statusCode())
        assertEquals(404, delete.statusCode())
        assertTrue(gateway.submissions.isEmpty())
        val application = TradingGatewayApplication::class.java.getAnnotation(SpringBootApplication::class.java)
        assertEquals(listOf("com.exchange.core.api.trading.api"), application.scanBasePackages.toList())
    }

    @Test
    fun `기본 프로필의 기존 Boot 테스트는 원래 애플리케이션을 계속 발견한다`() {
        val application = AnnotatedClassFinder(SpringBootConfiguration::class.java).findFromPackage("com.exchange.core")
        assertEquals(ExchangeCoreApplication::class.java, application)
    }

    private fun assertResult(
        result: TradingAdmissionResult,
        expectedHttpStatus: Int,
        expectedStatus: String,
        expectedReason: String,
    ) {
        gateway.result = CompletableFuture.completedFuture(result)
        val response = client.send(request(order()), HttpResponse.BodyHandlers.ofString())
        assertEquals(expectedHttpStatus, response.statusCode())
        val body = json.readTree(response.body())
        assertIdentifiers(body)
        assertEquals(expectedStatus, body.path("status").asString())
        assertEquals(expectedReason, body.path("reason").asString())
        assertFalse(body.has("sequence"))
    }

    private fun assertIdentifiers(body: JsonNode) {
        assertEquals("buyer", body.path("userId").asString())
        assertEquals("R1", body.path("requestId").asString())
        assertEquals("BTC-USDT", body.path("marketId").asString())
        assertEquals("O1", body.path("orderId").asString())
    }

    private fun request(
        body: String,
        path: String = "/api/trading/markets/BTC-USDT/orders",
    ): HttpRequest =
        HttpRequest
            .newBuilder(URI(baseUrl + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    private fun order(): String =
        """{"requestId":"R1","orderId":"O1","userId":"buyer","side":"BUY","orderType":"LIMIT","timeInForce":"GTC","price":10000,"quantity":100000000}"""
}

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    excludeName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    ],
)
@Import(TradingOrderController::class, TradingOrderHttpExceptionHandler::class)
class TradingOrderHttpConfig {
    @Bean
    fun tradingOrderGateway(): HttpContractTradingGateway = HttpContractTradingGateway()

    @Bean
    fun submitTradingOrderUseCase(gateway: TradingOrderGateway): SubmitTradingOrderUseCase =
        TradingGatewayConfig().submitTradingOrderUseCase(gateway)

    @Bean
    fun tradingJsonMapperBuilderCustomizer(): JsonMapperBuilderCustomizer = TradingGatewayConfig().tradingJsonMapperBuilderCustomizer()

    @Bean
    fun tradingAsyncSupport(): WebMvcConfigurer = TradingGatewayConfig().tradingAsyncSupport()
}

class HttpContractTradingGateway : TradingOrderGateway {
    val submissions = CopyOnWriteArrayList<TradingOrderSubmission>()

    @Volatile
    var result: CompletableFuture<TradingAdmissionResult> = CompletableFuture.completedFuture(TradingAdmissionResult.Accepted(17))

    @Volatile
    var submitted = CountDownLatch(1)

    override fun submit(submission: TradingOrderSubmission): CompletableFuture<TradingAdmissionResult> {
        submissions += submission
        submitted.countDown()
        return result
    }
}
