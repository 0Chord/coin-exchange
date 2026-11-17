package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.ExchangeCoreApplication
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.MarketDefinition
import com.exchange.core.support.OrderQueryTestDatabase
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsRoleRecoveryState
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingServiceRole
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.ClusteredService
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.net.DatagramSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 제품 WAS와 실제 단일 자금·매칭 노드를 연결한다. 복제 HA와 조회 자동 적재는 이 검사의 범위가 아니다. */
class SplitFundsTradingHttpTest {
    @TempDir lateinit var directory: Path
    private val json = JsonMapper.builder().build()
    private val lineage = ClusterLineage(41, 42)

    @Test
    fun `프로필 없는 제품 WAS의 주문이 자금과 매칭을 거치고 같은 WAS 조회는 미준비를 반환한다`() {
        val basePort = availablePorts()
        val manifest = manifest()
        val manifestFile = directory.resolve("manifest.bin").also { Files.write(it, BoundedTradingStateCodec().encodeManifest(manifest)) }
        val fundsSettings = settings(manifestFile, TradingServiceRole.FUNDS, basePort)
        val matchingSettings = settings(manifestFile, TradingServiceRole.MATCHING, basePort)
        val fundsSnapshot = AtomicReference<FundsRoleRecoveryState?>()
        OrderQueryTestDatabase().use { database ->
            DevelopmentSplitTradingNode.start(fundsSettings, serviceDecorator = { observeFunds(it, fundsSnapshot) }).use { fundsNode ->
                DevelopmentSplitTradingNode.start(matchingSettings).use { matchingNode ->
                    MediaDriver
                        .launch(
                            MediaDriver
                                .Context()
                                .aeronDirectoryName(directory.resolve("setup-driver").toString())
                                .dirDeleteOnStart(false)
                                .dirDeleteOnShutdown(true)
                                .threadingMode(ThreadingMode.SHARED),
                        ).use { driver ->
                            fun endpoint(matching: Boolean) =
                                SplitOrderDeliveryEndpoint(
                                    driver.aeronDirectoryName(),
                                    if (matching) matchingSettings.matchingIngressEndpoints else fundsSettings.fundsIngressEndpoints,
                                    "127.0.0.1:0",
                                    if (matching) matchingSettings.clusterId else fundsSettings.clusterId,
                                    2,
                                )
                            SplitOrderDeliveryClient(endpoint(false), 10_000).use { funds ->
                                SplitOrderDeliveryClient(endpoint(true), 10_000).use { matching ->
                                    for (client in listOf(matching, funds)) {
                                        assertEquals(
                                            "INITIALIZED",
                                            client
                                                .request(
                                                    SplitOrderDeliveryCommand.InitializeRole(fundsSettings.configuration.topologySha256),
                                                ).code,
                                        )
                                    }
                                }
                                for ((user, asset, amount) in listOf(Triple("buyer", "USDT", 1_000L), Triple("seller", "BTC", 3L))) {
                                    assertEquals(
                                        "PREPARED",
                                        funds
                                            .request(
                                                SplitOrderDeliveryCommand.Control(
                                                    BoundedTradingCommand.Funding(
                                                        DevelopmentBalanceCommandPayload(
                                                            OpeningBalance(
                                                                "http-opening-$user",
                                                                UserId(user),
                                                                AssetId(asset),
                                                                Amount(amount),
                                                            ),
                                                            "http-opening-ledger-$user",
                                                            Instant.EPOCH,
                                                        ),
                                                    ),
                                                ),
                                            ).code,
                                    )
                                }
                                val properties =
                                    database.properties +
                                        mapOf(
                                            "exchange.order-query.supported-markets" to "BTC-USDT",
                                            "TRADING_GATEWAY_RUN_ROOT" to directory.resolve("gateway").toString(),
                                            "TRADING_GATEWAY_INGRESS_ENDPOINTS" to fundsSettings.fundsIngressEndpoints,
                                            "TRADING_GATEWAY_EGRESS_ENDPOINT" to "127.0.0.1:${basePort + 32}",
                                        )
                                val args =
                                    properties.map { (key, value) -> "--$key=$value" } +
                                        listOf("--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")
                                SpringApplicationBuilder(ExchangeCoreApplication::class.java).run(*args.toTypedArray()).use { context ->
                                    assertTrue(context.environment.activeProfiles.isEmpty())
                                    val url = "http://127.0.0.1:${(context as ServletWebServerApplicationContext).webServer!!.port}"
                                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
                                        for (user in listOf("seller", "buyer")) {
                                            val epoch = post(http, url, "/channels/0/epoch", """{"userId":"$user","expectedEpoch":0}""")
                                            assertEquals(200, epoch.statusCode(), epoch.body())
                                            assertEquals(1L, json.readTree(epoch.body()).path("epoch").longValue())
                                        }
                                        val seller =
                                            post(
                                                http,
                                                url,
                                                "/markets/BTC-USDT/orders",
                                                """{"userId":"seller","channel":0,"epoch":1,"sequence":1,"expectedConfigurationVersion":1,"side":"SELL","orderType":"LIMIT","timeInForce":"GTC","price":100,"quantity":3}""",
                                            )
                                        assertEquals(202, seller.statusCode(), seller.body())
                                        val body =
                                            """{"userId":"buyer","channel":0,"epoch":1,"sequence":1,"expectedConfigurationVersion":1,"side":"BUY","orderType":"LIMIT","timeInForce":"GTC","price":100,"quantity":3}"""
                                        val accepted = post(http, url, "/markets/BTC-USDT/orders", body)
                                        assertEquals(202, accepted.statusCode(), accepted.body())
                                        val receipt = json.readTree(accepted.body())
                                        val orderId = TradingIdentifiers.order(lineage, 2).value
                                        assertEquals(orderId, receipt.path("orderId").asString())
                                        assertEquals(2L, receipt.path("admissionSequence").longValue())
                                        assertEquals("LOCAL_QUORUM", receipt.path("protection").asString())
                                        val repeated = post(http, url, "/markets/BTC-USDT/orders", body)
                                        assertEquals(202, repeated.statusCode(), repeated.body())
                                        assertEquals(orderId, json.readTree(repeated.body()).path("orderId").asString())
                                        assertEquals(2L, json.readTree(repeated.body()).path("admissionSequence").longValue())

                                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                                        while (fundsSnapshot
                                                .get()
                                                ?.financial
                                                ?.markets
                                                ?.single()
                                                ?.executedThrough != 2L &&
                                            System.nanoTime() - deadline < 0
                                        ) {
                                            Thread.sleep(10)
                                        }
                                        val settled = checkNotNull(fundsSnapshot.get()).financial
                                        assertEquals(2L, settled.markets.single().executedThrough, "실제 체결 결과가 자금 노드까지 돌아와야 한다")
                                        assertTrue(settled.liveOrders.isEmpty(), "전량 체결된 두 주문의 예약이 모두 종료되어야 한다")
                                        val balances = settled.balances.associateBy { it.userId.value to it.assetId.value }
                                        assertEquals(
                                            Balance(UserId("buyer"), AssetId("USDT"), Amount(700), Amount(0)),
                                            balances[
                                                "buyer" to
                                                    "USDT",
                                            ],
                                        )
                                        assertEquals(
                                            Balance(UserId("buyer"), AssetId("BTC"), Amount(3), Amount(0)),
                                            balances[
                                                "buyer" to
                                                    "BTC",
                                            ],
                                        )
                                        assertEquals(
                                            Balance(UserId("seller"), AssetId("BTC"), Amount(0), Amount(0)),
                                            balances[
                                                "seller" to
                                                    "BTC",
                                            ],
                                        )
                                        assertEquals(
                                            Balance(UserId("seller"), AssetId("USDT"), Amount(300), Amount(0)),
                                            balances[
                                                "seller" to
                                                    "USDT",
                                            ],
                                        )
                                        val ack = post(http, url, "/channels/0/ack", """{"userId":"buyer","epoch":1,"through":1}""")
                                        assertEquals(200, ack.statusCode(), ack.body())
                                        assertEquals(1L, json.readTree(ack.body()).path("retiredThrough").longValue())
                                        val query =
                                            http.send(
                                                HttpRequest
                                                    .newBuilder(
                                                        URI.create("$url/api/trading/v3/markets/BTC-USDT/orders/$orderId"),
                                                    ).GET()
                                                    .build(),
                                                HttpResponse.BodyHandlers.ofString(),
                                            )
                                        assertEquals(503, query.statusCode(), query.body())
                                        assertEquals("QUERY_NOT_READY", json.readTree(query.body()).path("code").asString())
                                    }
                                }
                                assertNull(fundsNode.error)
                                assertNull(matchingNode.error)
                                assertFalse(fundsNode.isClosed)
                                assertFalse(matchingNode.isClosed)
                            }
                        }
                }
            }
        }
    }

    /** 테스트만 확정 callback 안에서 불변 사본을 만든다. WAS나 제품 관찰 API는 이 상태에 접근하지 않는다. */
    private fun observeFunds(
        service: SplitTradingClusterAdapter,
        captured: AtomicReference<FundsRoleRecoveryState?>,
    ): ClusteredService {
        val field = service.javaClass.getDeclaredField("funds").also { it.isAccessible = true }
        return object : ClusteredService by service {
            override fun onSessionMessage(
                session: ClientSession?,
                timestamp: Long,
                buffer: DirectBuffer,
                offset: Int,
                length: Int,
                header: Header,
            ) {
                service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                if (service.lifecycle == TradingLifecycle.RUNNING) {
                    captured.set((field.get(service) as BoundedFundingState).fundsRecoveryState())
                }
            }

            override fun doBackgroundWork(nowNs: Long): Int = service.doBackgroundWork(nowNs)
        }
    }

    private fun post(
        client: HttpClient,
        url: String,
        path: String,
        body: String,
    ): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("$url/api/trading/v3$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun settings(
        manifest: Path,
        role: TradingServiceRole,
        base: Int,
    ): SplitTradingNodeSettings {
        val port = if (role == TradingServiceRole.FUNDS) base else base + 16
        return splitTradingSettings(
            mapOf(
                "SPLIT_MODE" to "RESULT_SETTLEMENT_DEV",
                "SPLIT_ROLE" to role.name,
                "SPLIT_MANIFEST" to manifest.toString(),
                "SPLIT_INPUT_CONTRACT_VERSION" to "2",
                "SPLIT_MAX_FUNDS_DISPATCH_BYTES" to "65536",
                "SPLIT_MAX_FUNDS_ACTIVE_BYTES" to "65536",
                "SPLIT_MATCHING_LINEAGE_HIGH" to "51",
                "SPLIT_MATCHING_LINEAGE_LOW" to "52",
                "SPLIT_OWNERSHIP_EPOCH" to "1",
                "SPLIT_FUNDS_INGRESS" to "0=127.0.0.1:$base",
                "SPLIT_MATCHING_INGRESS" to "0=127.0.0.1:${base + 16}",
                "FUNDING_RUN_ROOT" to directory.resolve(role.name.lowercase()).toString(),
                "FUNDING_BASE_PORT" to port.toString(),
                "FUNDING_MEMBER_ID" to "0",
                "FUNDING_HOST" to "127.0.0.1",
                "FUNDING_MEMBERS" to "0,${(0..4).joinToString(",") { "127.0.0.1:${port + it}" }}",
                "FUNDING_RUN_ID" to "split-http",
            ),
        )
    }

    private fun manifest(): TradingClusterManifest =
        TradingClusterManifest(
            lineage,
            "split-http",
            BoundedExecutionIdentity.artifactSha256,
            BoundedExecutionIdentity.schemaSha256,
            1,
            listOf(
                TradingMarketContext(
                    MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), AssetId("USDT"), 0),
                    TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)),
                    0,
                ),
            ),
            TradingResourceLimits(
                maxAccounts = 8,
                channelsPerAccount = 1,
                maxChannels = 8,
                requestWindow = 16,
                maxInboxEntries = 128,
                maxActiveOrders = 32,
                maxPendingOrders = 32,
                maxMarketActiveOrders = 32,
                maxMarketPendingOrders = 32,
                maxBalanceKeys = 32,
                maxPreparations = 16,
                maxMarkets = 1,
                maxAssets = 2,
            ),
        )

    private fun availablePorts(): Int {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceIn(1024, 65480)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                for (offset in (0..8) + (16..24) + 32) sockets.add(DatagramSocket(base + offset))
                return base
            } catch (_: java.net.BindException) {
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("자금·매칭 노드와 WAS 응답에 필요한 포트를 확보하지 못했습니다")
    }
}
