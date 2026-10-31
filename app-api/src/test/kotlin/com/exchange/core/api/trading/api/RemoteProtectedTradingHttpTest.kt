package com.exchange.core.api.trading.api

import com.exchange.core.api.common.TradingOrderHttpExceptionHandler
import com.exchange.core.api.config.RemoteRecoveryGateConfig
import com.exchange.core.api.config.TradingGatewayConfig
import com.exchange.core.api.trading.application.BoundedTradingOrderGateway
import com.exchange.core.api.trading.application.BoundedTradingOrderUseCase
import com.exchange.core.api.trading.infrastructure.aeron.AeronGatewaySettings
import com.exchange.core.api.trading.infrastructure.aeron.BoundedAeronGateway
import com.exchange.core.api.trading.infrastructure.aeron.BoundedGatewayTransport
import com.exchange.core.api.trading.infrastructure.aeron.haBoundedProbeManifest
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.api.trading.infrastructure.recovery.FileRecoveryDurabilityGate
import com.exchange.core.api.trading.infrastructure.recovery.RemoteRecoveryFiles
import com.exchange.core.api.trading.infrastructure.recovery.remoteTestProof
import com.exchange.core.common.OrderId
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReceipt
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteProtectedTradingHttpTest {
    @TempDir lateinit var root: Path

    @Test fun `configured file gate holds a real HTTP response until a validated matching prefix`() {
        val manifest = haBoundedProbeManifest()
        val manifestBytes = BoundedTradingStateCodec().encodeManifest(manifest)
        val manifestFile = root.resolve("manifest.bin").also { Files.write(it, manifestBytes) }
        val shared = root.resolve("shared-backup-certificates").also { Files.createDirectory(it) }
        val execution = RemoteRecoveryFiles.sha256(manifestBytes)
        RemoteRecoveryFiles.publish(shared, remoteTestProof(manifest.lineage, 1, 608, execution), manifest.lineage, 1, 608, execution)
        val context =
            SpringApplicationBuilder(RemoteProtectedHttpConfig::class.java).profiles("aeron-bounded-gateway").run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--TRADING_GATEWAY_PROTECTION=remote",
                "--TRADING_GATEWAY_RUN_ROOT=${root.resolve("edge")}",
                "--TRADING_GATEWAY_INGRESS_ENDPOINTS=0=127.0.0.1:19700",
                "--TRADING_GATEWAY_EGRESS_ENDPOINT=127.0.0.1:19707",
                "--TRADING_GATEWAY_RESULT_TIMEOUT_MS=10000",
                "--TRADING_GATEWAY_BOUNDED_MANIFEST=$manifestFile",
                "--TRADING_GATEWAY_RECOVERY_ROOT=$shared",
                "--TRADING_GATEWAY_RECOVERY_AUTHORITY_EPOCH=1",
                "--TRADING_GATEWAY_RECOVERY_POLL_INTERVAL_MS=1",
            )
        context.use {
            val gate = context.getBean(FileRecoveryDurabilityGate::class.java)
            val observed = context.getBean(RemoteHttpProbe::class.java).observed
            val port = checkNotNull((context as ServletWebServerApplicationContext).webServer).port
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
                val request =
                    HttpRequest
                        .newBuilder(URI.create("http://127.0.0.1:$port/api/trading/v3/markets/BTC-USDT/orders"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                """{"userId":"U1","channel":0,"epoch":1,"sequence":1,"expectedConfigurationVersion":1,""" +
                                    """"side":"BUY","orderType":"LIMIT","timeInForce":"GTC","price":100,"quantity":100000000}""",
                            ),
                        ).build()
                val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                assertTrue(observed.await(3, TimeUnit.SECONDS))
                eventually { gate.certifiedThrough == 608L }
                assertFalse(response.isDone)
                // 체크섬을 다시 계산한 봉투의 X도 증명에 담긴 실제 X=608을 덮어쓸 수 없다.
                val certificateFile = shared.resolve(RemoteRecoveryFiles.CERTIFICATE_FILE)
                val original = Files.readAllBytes(certificateFile)
                val forged = original.copyOf(original.size - 32).also { it[34] = (it[34].toInt() xor 4).toByte() }
                Files.write(certificateFile, forged + MessageDigest.getInstance("SHA-256").digest(forged))
                eventually { gate.lastReadFailure != null }
                assertEquals(608L, gate.certifiedThrough)
                assertFalse(response.isDone)
                Files.write(certificateFile, original)
                RemoteRecoveryFiles.publish(
                    shared,
                    remoteTestProof(manifest.lineage, 1, 640, execution),
                    manifest.lineage,
                    1,
                    640,
                    execution,
                )
                val result = response.get(5, TimeUnit.SECONDS)
                assertEquals(202, result.statusCode())
                assertTrue(result.body().contains("REMOTE_RECOVERY"))
            }
        }
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            check(System.nanoTime() < deadline)
            Thread.sleep(1)
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    excludeName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    ],
)
@Import(RemoteRecoveryGateConfig::class, BoundedTradingOrderController::class, TradingOrderHttpExceptionHandler::class)
internal class RemoteProtectedHttpConfig {
    @Bean fun remoteHttpUseCase(gateway: BoundedTradingOrderGateway) = BoundedTradingOrderUseCase(gateway)

    @Bean fun remoteHttpProbe() = RemoteHttpProbe()

    @Bean(destroyMethod = "close")
    fun remoteHttpGateway(
        environment: Environment,
        gate: FileRecoveryDurabilityGate,
        probe: RemoteHttpProbe,
    ): BoundedAeronGateway {
        val settings =
            AeronGatewaySettings(
                Path.of(environment.getRequiredProperty("TRADING_GATEWAY_RUN_ROOT")),
                environment.getRequiredProperty("TRADING_GATEWAY_INGRESS_ENDPOINTS"),
                environment.getRequiredProperty("TRADING_GATEWAY_EGRESS_ENDPOINT"),
                resultTimeoutMs = 10_000,
            )
        return BoundedAeronGateway(settings, haBoundedProbeManifest().lineage, gate) { receive -> probe.open(receive) }
    }

    @Bean fun remoteJson() = TradingGatewayConfig().tradingJsonMapperBuilderCustomizer()

    @Bean fun remoteAsync() = TradingGatewayConfig().tradingAsyncSupport()
}

internal class RemoteHttpProbe {
    val observed = CountDownLatch(1)

    fun open(receive: (BoundedTradingReply) -> Unit): BoundedGatewayTransport =
        object : BoundedGatewayTransport {
            private val codec = BoundedTradingCodec()
            private var response: BoundedTradingReply? = null

            override fun offer(bytes: ByteArray): Long {
                val command = codec.decode(bytes)
                response =
                    BoundedTradingReply(
                        command.correlationId,
                        "ACCEPTED",
                        640,
                        AdmissionReceipt(AdmissionCode.ACCEPTED, OrderId("O2remote"), 4, 2),
                        lineage = haBoundedProbeManifest().lineage,
                    )
                observed.countDown()
                return 1
            }

            override fun poll(): Int {
                val ready = response ?: return 0
                response = null
                receive(ready)
                return 1
            }

            override fun close() = Unit
        }
}
