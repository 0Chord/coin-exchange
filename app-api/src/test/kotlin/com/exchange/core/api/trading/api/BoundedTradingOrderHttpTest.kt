package com.exchange.core.api.trading.api

import com.exchange.core.api.common.TradingOrderHttpExceptionHandler
import com.exchange.core.api.config.TradingGatewayConfig
import com.exchange.core.api.trading.application.BoundedTradingOrderGateway
import com.exchange.core.api.trading.application.BoundedTradingOrderUseCase
import com.exchange.core.api.trading.infrastructure.aeron.AeronGatewaySettings
import com.exchange.core.api.trading.infrastructure.aeron.BoundedAeronGateway
import com.exchange.core.api.trading.infrastructure.aeron.BoundedGatewayTransport
import com.exchange.core.api.trading.infrastructure.codec.BoundedCommandEnvelope
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.common.OrderId
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReceipt
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Real HTTP/JSON/validation edge; actual Aeron and recovery are verified by the HA fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoundedTradingOrderHttpTest {
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var root: Path
    private lateinit var url: String
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = JsonMapper.builder().build()

    @BeforeAll fun start() {
        context =
            SpringApplicationBuilder(BoundedTradingHttpConfig::class.java)
                .profiles("aeron-bounded-gateway")
                .run("--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")
        root = context.getBean("boundedHttpRoot", Path::class.java)
        url = "http://127.0.0.1:${checkNotNull((context as ServletWebServerApplicationContext).webServer).port}"
    }

    @AfterAll fun stop() {
        if (::context.isInitialized) context.close()
        client.close()
        if (::root.isInitialized) Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test fun `HTTP v3 preserves token and returns the server issued order identity`() {
        val response = post("/api/trading/v3/markets/BTC-USDT/orders", order())
        assertEquals(202, response.statusCode())
        val body = json.readTree(response.body())
        assertEquals("U1", body.path("userId").asString())
        assertEquals(7L, body.path("sequence").longValue())
        assertEquals("O2server", body.path("orderId").asString())
        assertEquals(4L, body.path("admissionSequence").longValue())
        assertEquals("LOCAL_QUORUM", body.path("protection").asString())
        val admitted = context.getBean(BoundedHttpProbe::class.java).requests.last { it.command is BoundedTradingCommand.Admit }
        val command = assertIs<BoundedTradingCommand.Admit>(admitted.command)
        assertEquals(7L, command.token.sequence)
    }

    @Test fun `invalid DTO is rejected before transmission and internal controls have no HTTP endpoint`() {
        assertEquals(400, post("/api/trading/v3/markets/BTC-USDT/orders", order().replace("\"price\":100", "\"price\":0")).statusCode())
        assertEquals(404, post("/api/trading/v3/initialize", "{}").statusCode())
    }

    @Test fun `UTF8 byte limit failures return 400 without disabling later HTTP requests`() {
        val user = "가".repeat(86)
        assertEquals(400, post("/api/trading/v3/channels/0/ack", """{"userId":"$user","epoch":1,"through":0}""").statusCode())
        assertEquals(400, post("/api/trading/v3/channels/0/epoch", """{"userId":"$user","expectedEpoch":0}""").statusCode())
        assertEquals(202, post("/api/trading/v3/markets/BTC-USDT/orders", order()).statusCode())
    }

    private fun post(
        path: String,
        body: String,
    ) = client.send(
        HttpRequest
            .newBuilder(URI.create(url + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun order() =
        """{"userId":"U1","channel":0,"epoch":1,"sequence":7,"expectedConfigurationVersion":1,""" +
            """"side":"BUY","orderType":"LIMIT","timeInForce":"GTC","price":100,"quantity":100000000}"""
}

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    excludeName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    ],
)
@Import(BoundedTradingOrderController::class, TradingOrderHttpExceptionHandler::class)
internal class BoundedTradingHttpConfig {
    @Bean fun boundedHttpUseCase(gateway: BoundedTradingOrderGateway) = BoundedTradingOrderUseCase(gateway)

    @Bean fun boundedHttpRoot(): Path = Files.createTempDirectory("bounded-http-")

    @Bean fun boundedHttpProbe() = BoundedHttpProbe()

    @Bean(destroyMethod = "close")
    fun boundedHttpGateway(
        boundedHttpRoot: Path,
        probe: BoundedHttpProbe,
    ) = BoundedAeronGateway(
        AeronGatewaySettings(boundedHttpRoot.resolve("edge"), "0=127.0.0.1:19700", "127.0.0.1:19707"),
    ) { receive -> probe.open(receive) }

    @Bean fun boundedJson() = TradingGatewayConfig().tradingJsonMapperBuilderCustomizer()

    @Bean fun boundedAsync() = TradingGatewayConfig().tradingAsyncSupport()
}

internal class BoundedHttpProbe {
    val requests = ConcurrentLinkedQueue<BoundedCommandEnvelope>()

    fun open(receive: (BoundedTradingReply) -> Unit): BoundedGatewayTransport =
        object : BoundedGatewayTransport {
            private val codec = BoundedTradingCodec()
            private val responses = ArrayDeque<BoundedTradingReply>()

            override fun offer(bytes: ByteArray): Long {
                val request = codec.decode(bytes)
                requests.add(request)
                responses.addLast(
                    BoundedTradingReply(
                        request.correlationId,
                        "ACCEPTED",
                        640,
                        AdmissionReceipt(AdmissionCode.ACCEPTED, OrderId("O2server"), 4, 2),
                    ),
                )
                return 1
            }

            override fun poll(): Int {
                val r = responses.removeFirstOrNull() ?: return 0
                receive(r)
                return 1
            }

            override fun close() = Unit
        }
}
