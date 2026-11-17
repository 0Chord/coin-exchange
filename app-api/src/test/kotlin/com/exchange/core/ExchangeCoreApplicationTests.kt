package com.exchange.core

import com.exchange.core.api.matching.application.MatchingCoordinator
import com.exchange.core.api.order.application.OrderFundingService
import com.exchange.core.api.order.application.SubmitOrderUseCase
import com.exchange.core.api.trading.api.BoundedTradingOrderController
import com.exchange.core.api.trading.api.OrderQueryController
import com.exchange.core.api.trading.application.OrderQueryUseCase
import com.exchange.core.api.trading.infrastructure.aeron.AeronGatewaySettings
import com.exchange.core.api.trading.infrastructure.aeron.BoundedAeronGateway
import com.exchange.core.api.trading.infrastructure.aeron.BoundedGatewayTransport
import com.exchange.core.support.OrderQueryTestDatabase
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingIdentifiers
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.data.jpa.repository.JpaRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 실제 제품 조립과 PostgreSQL을 검증한다. Aeron 왕복은 split 연결 검증에서 확인한다. */
class ExchangeCoreApplicationTests {
    @TempDir lateinit var root: Path

    @Test
    fun `프로필 없이 한 WAS에 주문과 공용 DB 조회만 조립한다`() {
        OrderQueryTestDatabase().use { database ->
            BoundedAeronGateway(
                AeronGatewaySettings(root.resolve("edge"), "0=127.0.0.1:19700", "127.0.0.1:19707"),
                openTransport = {
                    object : BoundedGatewayTransport {
                        override fun offer(bytes: ByteArray): Long = error("assembly test does not submit engine commands")

                        override fun poll(): Int = 0

                        override fun close() = Unit
                    }
                },
            ).use { gateway ->
                val settings =
                    database.properties +
                        mapOf("server.port" to "0", "spring.main.banner-mode" to "off", "logging.level.root" to "ERROR")
                SpringApplicationBuilder(ExchangeCoreApplication::class.java)
                    .initializers({ context ->
                        context.addBeanFactoryPostProcessor { factory -> factory.registerSingleton("boundedAeronGateway", gateway) }
                    })
                    .run(*settings.map { (key, value) -> "--$key=$value" }.toTypedArray())
                    .use { context ->
                        assertTrue(context.environment.activeProfiles.isEmpty())
                        assertEquals(1, context.getBeansOfType(BoundedTradingOrderController::class.java).size)
                        assertEquals(1, context.getBeansOfType(OrderQueryController::class.java).size)
                        assertEquals(1, context.getBeansOfType(OrderQueryUseCase::class.java).size)
                        assertEquals(setOf("dataSource"), context.getBeansOfType(DataSource::class.java).keys)
                        assertEquals(1, context.getBeansOfType(Flyway::class.java).size)
                        assertTrue(context.getBeansOfType(EntityManagerFactory::class.java).isEmpty())
                        assertTrue(context.getBeansOfType(JpaRepository::class.java).isEmpty())
                        assertTrue(context.getBeansOfType(MatchingCoordinator::class.java).isEmpty())
                        assertTrue(context.getBeansOfType(OrderFundingService::class.java).isEmpty())
                        assertTrue(context.getBeansOfType(SubmitOrderUseCase::class.java).isEmpty())
                        val port = checkNotNull((context as ServletWebServerApplicationContext).webServer).port
                        HttpClient.newHttpClient().use { client ->
                            val orderId = TradingIdentifiers.order(ClusterLineage(41, 42), 1).value
                            val query =
                                client.send(
                                    HttpRequest
                                        .newBuilder(
                                            URI("http://127.0.0.1:$port/api/trading/v3/markets/BTC-USDT/orders/$orderId"),
                                        ).GET()
                                        .build(),
                                    HttpResponse.BodyHandlers.ofString(),
                                )
                            assertEquals(503, query.statusCode())
                            assertTrue("QUERY_NOT_READY" in query.body())
                            for (path in listOf("/api/markets/BTC-USDT/orders", "/api/trading/markets/BTC-USDT/orders")) {
                                val legacy =
                                    client.send(
                                        HttpRequest
                                            .newBuilder(URI("http://127.0.0.1:$port$path"))
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                            .build(),
                                        HttpResponse.BodyHandlers.discarding(),
                                    )
                                assertEquals(404, legacy.statusCode())
                            }
                        }
                    }
            }
        }
    }
}
