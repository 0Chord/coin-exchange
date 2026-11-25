package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.ExchangeCoreApplication
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCommand
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
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 같은 WAS가 두 매칭 담당의 실제 결과를 전역 접수 순서와 마켓별 결과 순서로 수집한다. */
class MultiMarketOrderQueryHttpTest {
    @TempDir lateinit var directory: Path
    private val json = JsonMapper.builder().build()
    private val fundsLineage = ClusterLineage(41, 42)
    private val btcLineage = ClusterLineage(51, 52)
    private val ethLineage = ClusterLineage(61, 62)
    private val markets = listOf("BTC-USDT", "ETH-USDT")

    @Test
    fun `두 실제 매칭 담당의 체결을 같은 WAS GET과 전역 접수 및 마켓별 원본 ACK에서 확인한다`() {
        val base = availablePorts()
        val manifest = manifest()
        val file = directory.resolve("manifest.bin").also { Files.write(it, BoundedTradingStateCodec().encodeManifest(manifest)) }
        val fundsSettings = settings(file, TradingServiceRole.FUNDS, base, null)
        val btcSettings = settings(file, TradingServiceRole.MATCHING, base, 0)
        val ethSettings = settings(file, TradingServiceRole.MATCHING, base, 1)
        assertEquals(fundsSettings.configuration.topologySha256, btcSettings.configuration.topologySha256)
        assertEquals(fundsSettings.configuration.topologySha256, ethSettings.configuration.topologySha256)
        val captured = AtomicReference<FundsRoleRecoveryState?>()
        OrderQueryTestDatabase().use { database ->
            DevelopmentSplitTradingNode.start(fundsSettings, serviceDecorator = { observeFunds(it, captured) }).use { fundsNode ->
                DevelopmentSplitTradingNode.start(btcSettings).use { btcNode ->
                    DevelopmentSplitTradingNode.start(ethSettings).use { ethNode ->
                        MediaDriver
                            .launch(
                                MediaDriver
                                    .Context()
                                    .aeronDirectoryName(directory.resolve("control-driver").toString())
                                    .dirDeleteOnShutdown(true)
                                    .threadingMode(ThreadingMode.SHARED),
                            ).use { driver ->
                                fun endpoint(settings: SplitTradingNodeSettings) =
                                    SplitOrderDeliveryEndpoint(
                                        driver.aeronDirectoryName(),
                                        if (settings.role == TradingServiceRole.FUNDS) {
                                            settings.fundsIngressEndpoints
                                        } else {
                                            settings.matchingIngressEndpoints
                                        },
                                        "127.0.0.1:0",
                                        settings.clusterId,
                                        2,
                                    )
                                SplitOrderDeliveryClient(endpoint(fundsSettings), 10_000).use { funds ->
                                    for (settings in listOf(btcSettings, ethSettings)) {
                                        SplitOrderDeliveryClient(endpoint(settings), 10_000).use { matching ->
                                            assertEquals(
                                                "INITIALIZED",
                                                matching
                                                    .request(
                                                        SplitOrderDeliveryCommand.InitializeRole(
                                                            fundsSettings.configuration.topologySha256,
                                                        ),
                                                    ).code,
                                            )
                                        }
                                    }
                                    assertEquals(
                                        "INITIALIZED",
                                        funds
                                            .request(
                                                SplitOrderDeliveryCommand.InitializeRole(fundsSettings.configuration.topologySha256),
                                            ).code,
                                    )
                                    prepare(funds)
                                    val properties =
                                        database.properties +
                                            mapOf(
                                                "exchange.order-query.supported-markets" to markets.joinToString(","),
                                                "exchange.order-query.consumer-id" to "query-multi",
                                                "exchange.order-query.matching-ingress" to
                                                    "BTC-USDT@0=127.0.0.1:${base + 16};ETH-USDT@0=127.0.0.1:${base + 32}",
                                                "TRADING_GATEWAY_RUN_ROOT" to directory.resolve("gateway").toString(),
                                                "TRADING_GATEWAY_INGRESS_ENDPOINTS" to fundsSettings.fundsIngressEndpoints,
                                                "TRADING_GATEWAY_EGRESS_ENDPOINT" to "127.0.0.1:${base + 48}",
                                            )
                                    val args =
                                        properties.map { (key, value) -> "--$key=$value" } +
                                            listOf("--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")
                                    SpringApplicationBuilder(ExchangeCoreApplication::class.java).run(*args.toTypedArray()).use { context ->
                                        assertTrue(context.environment.activeProfiles.isEmpty())
                                        val port = (context as ServletWebServerApplicationContext).webServer!!.port
                                        val url = "http://127.0.0.1:$port/api/trading/v3"
                                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
                                            for (user in listOf("seller", "buyer")) {
                                                val epoch = post(http, "$url/channels/0/epoch", """{"userId":"$user","expectedEpoch":0}""")
                                                assertEquals(200, epoch.statusCode(), epoch.body())
                                                assertEquals(1L, json.readTree(epoch.body()).path("epoch").longValue())
                                            }
                                            val expected = mutableListOf<Pair<String, Long>>()
                                            for ((user, side) in listOf("seller" to "SELL", "buyer" to "BUY")) {
                                                for ((index, market) in markets.withIndex()) {
                                                    val admission = expected.size.toLong() + 1
                                                    val reply = post(http, "$url/markets/$market/orders", orderBody(user, side, index + 1L))
                                                    assertEquals(202, reply.statusCode(), reply.body())
                                                    assertEquals(
                                                        admission,
                                                        json.readTree(reply.body()).path("admissionSequence").longValue(),
                                                    )
                                                    assertEquals(
                                                        TradingIdentifiers.order(fundsLineage, admission).value,
                                                        json.readTree(reply.body()).path("orderId").asString(),
                                                    )
                                                    expected += market to admission
                                                }
                                            }
                                            for ((market, admission) in expected) {
                                                awaitFilled(http, url, market, admission, funds)
                                            }
                                            assertBalances(checkNotNull(captured.get()))
                                            val saved = checkNotNull(captured.get())
                                            assertEquals(4L, saved.financial.lastAdmissionSequence)
                                            assertTrue(saved.financial.liveOrders.isEmpty())
                                            assertEquals(
                                                mapOf("BTC-USDT" to 2L, "ETH-USDT" to 2L),
                                                checkNotNull(
                                                    saved.results,
                                                ).streams.associate { it.stream.marketId.value to it.resultThrough },
                                            )
                                            OrderQuerySourceClient(endpoint(fundsSettings), 10_000).use { fundSource ->
                                                OrderQuerySourceClient(endpoint(btcSettings), 10_000).use { btcSource ->
                                                    OrderQuerySourceClient(endpoint(ethSettings), 10_000).use { ethSource ->
                                                        val btc = btcSource.request(OrderQuerySourceCommand.Describe)
                                                        val eth = ethSource.request(OrderQuerySourceCommand.Describe)
                                                        for (peer in listOf(btc, eth)) {
                                                            assertEquals("DESCRIBED", peer.code)
                                                            assertEquals("query-multi", peer.consumerId)
                                                            assertEquals(fundsSettings.configuration.topologySha256, peer.topologySha256)
                                                        }
                                                        assertEquals(btcLineage, btc.lineage)
                                                        assertEquals(ethLineage, eth.lineage)
                                                        assertNotEquals(btc.lineage, eth.lineage)
                                                        assertEquals(setOf("BTC-USDT"), btc.streams.keys)
                                                        assertEquals(setOf("ETH-USDT"), eth.streams.keys)
                                                        assertEquals(fundsLineage, btc.streams.getValue("BTC-USDT").fundsLineage)
                                                        assertEquals(fundsLineage, eth.streams.getValue("ETH-USDT").fundsLineage)
                                                        await("두 마켓 조회 ACK와 전역 접수 ACK") {
                                                            funds.poll()
                                                            val global = fundSource.request(OrderQuerySourceCommand.Describe)
                                                            val first = btcSource.request(OrderQuerySourceCommand.Describe)
                                                            val second = ethSource.request(OrderQuerySourceCommand.Describe)
                                                            global.progress.single { it.market == null }.let {
                                                                it.issued == 4L && it.retiredThrough == 4L
                                                            } &&
                                                                first.progress.single().let {
                                                                    it.issued == 2L && it.retiredThrough == 2L
                                                                } &&
                                                                second.progress.single().let {
                                                                    it.issued == 2L && it.retiredThrough == 2L
                                                                }
                                                        }
                                                    }
                                                }
                                            }
                                            val jdbc = database.jdbc()
                                            assertEquals(
                                                8L,
                                                jdbc.queryForObject("select count(*) from order_query.source_receipt", Long::class.java),
                                            )
                                            assertEquals(
                                                4L,
                                                jdbc.queryForObject(
                                                    "select count(*) from order_query.source_receipt where source_kind = 'ADMISSION'",
                                                    Long::class.java,
                                                ),
                                            )
                                            for (market in markets) {
                                                assertEquals(
                                                    2L,
                                                    jdbc.queryForObject(
                                                        "select count(*) from order_query.source_receipt " +
                                                            "where source_kind = 'MATCHING' and source_key = ?",
                                                        Long::class.java,
                                                        market,
                                                    ),
                                                )
                                            }
                                            assertEquals(
                                                2L,
                                                jdbc.queryForObject(
                                                    "select count(*) from order_query.feed_stream " +
                                                        "where matching_through = 2 and financial_through = 2",
                                                    Long::class.java,
                                                ),
                                            )
                                        }
                                    }
                                    for (node in listOf(fundsNode, btcNode, ethNode)) {
                                        assertNull(node.error)
                                        assertFalse(node.isClosed)
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    private fun prepare(funds: SplitOrderDeliveryClient) {
        for ((user, asset, amount) in listOf(
            Triple("buyer", "USDT", 1_000L),
            Triple("seller", "BTC", 3L),
            Triple("seller", "ETH", 3L),
        )) {
            assertEquals(
                "PREPARED",
                funds
                    .request(
                        SplitOrderDeliveryCommand.Control(
                            BoundedTradingCommand.Funding(
                                DevelopmentBalanceCommandPayload(
                                    OpeningBalance("multi-$user-$asset", UserId(user), AssetId(asset), Amount(amount)),
                                    "multi-ledger-$user-$asset",
                                    Instant.EPOCH,
                                ),
                            ),
                        ),
                    ).code,
            )
        }
    }

    private fun awaitFilled(
        http: HttpClient,
        url: String,
        market: String,
        admission: Long,
        funds: SplitOrderDeliveryClient,
    ) {
        val order = TradingIdentifiers.order(fundsLineage, admission).value
        var last: HttpResponse<String>? = null
        await("$market $order FILLED/APPLIED", { "${last?.statusCode()}: ${last?.body()}" }) {
            funds.poll()
            val reply =
                http.send(
                    HttpRequest
                        .newBuilder(URI.create("$url/markets/$market/orders/$order"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            last = reply
            val body = json.readTree(reply.body())
            reply.statusCode() == 200 && body.path("state").asString() == "FILLED" &&
                body.path("filledQuantity").longValue() == 3L && body.path("remainingQuantity").longValue() == 0L &&
                body.path("cancelledQuantity").longValue() == 0L && body.path("financialStatus").asString() == "APPLIED" &&
                body.path("coveredAdmissionSequence").longValue() == 4L && body.path("lastAffectedResultSequence").longValue() == 2L
        }
        val stream = json.readTree(checkNotNull(last).body()).path("resultStream")
        val owner = if (market == "BTC-USDT") btcLineage else ethLineage
        assertEquals(owner.high, stream.path("matchingLineageHigh").longValue())
        assertEquals(owner.low, stream.path("matchingLineageLow").longValue())
    }

    private fun assertBalances(saved: FundsRoleRecoveryState) {
        val balances = saved.financial.balances.associateBy { it.userId.value to it.assetId.value }
        for ((user, asset, available) in listOf(
            Triple("buyer", "USDT", 400L),
            Triple("buyer", "BTC", 3L),
            Triple("buyer", "ETH", 3L),
            Triple("seller", "USDT", 600L),
            Triple("seller", "BTC", 0L),
            Triple("seller", "ETH", 0L),
        )) {
            assertEquals(Balance(UserId(user), AssetId(asset), Amount(available), Amount(0)), balances[user to asset])
        }
    }

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
        http: HttpClient,
        url: String,
        body: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun orderBody(
        user: String,
        side: String,
        sequence: Long,
    ) = """{"userId":"$user","channel":0,"epoch":1,"sequence":$sequence,"expectedConfigurationVersion":1,"side":"$side",""" +
        """"orderType":"LIMIT","timeInForce":"GTC","price":100,"quantity":3}"""

    private fun await(
        description: String,
        detail: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() - deadline < 0) {
            if (condition()) return
            check(!Thread.currentThread().isInterrupted)
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
        assertTrue(condition(), "$description 제한 시간 초과. ${detail()}")
    }

    private fun settings(
        file: Path,
        role: TradingServiceRole,
        base: Int,
        owner: Int?,
    ): SplitTradingNodeSettings {
        val port = if (owner == null) base else base + 16 * (owner + 1)
        val environment =
            mapOf(
                "SPLIT_MODE" to "RESULT_SETTLEMENT_DEV",
                "SPLIT_ROLE" to role.name,
                "SPLIT_MANIFEST" to file.toString(),
                "SPLIT_INPUT_CONTRACT_VERSION" to "2",
                "SPLIT_MAX_FUNDS_DISPATCH_BYTES" to "65536",
                "SPLIT_MAX_FUNDS_ACTIVE_BYTES" to "65536",
                "EXCHANGE_ORDER_QUERY_CONSUMER_ID" to "query-multi",
                "SPLIT_MATCHING_OWNER_COUNT" to "2",
                "SPLIT_FUNDS_INGRESS" to "0=127.0.0.1:$base",
                "SPLIT_MATCHING_0_LINEAGE_HIGH" to btcLineage.high.toString(),
                "SPLIT_MATCHING_0_LINEAGE_LOW" to btcLineage.low.toString(),
                "SPLIT_MATCHING_0_OWNERSHIP_EPOCH" to "1",
                "SPLIT_MATCHING_0_MARKETS" to "BTC-USDT",
                "SPLIT_MATCHING_0_CLUSTER_ID" to "199",
                "SPLIT_MATCHING_0_INGRESS" to "0=127.0.0.1:${base + 16}",
                "SPLIT_MATCHING_1_LINEAGE_HIGH" to ethLineage.high.toString(),
                "SPLIT_MATCHING_1_LINEAGE_LOW" to ethLineage.low.toString(),
                "SPLIT_MATCHING_1_OWNERSHIP_EPOCH" to "1",
                "SPLIT_MATCHING_1_MARKETS" to "ETH-USDT",
                "SPLIT_MATCHING_1_CLUSTER_ID" to "200",
                "SPLIT_MATCHING_1_INGRESS" to "0=127.0.0.1:${base + 32}",
                "FUNDING_RUN_ROOT" to directory.resolve(if (owner == null) "funds" else "matching-$owner").toString(),
                "FUNDING_BASE_PORT" to port.toString(),
                "FUNDING_MEMBER_ID" to "0",
                "FUNDING_HOST" to "127.0.0.1",
                "FUNDING_MEMBERS" to "0,${(0..4).joinToString(",") { "127.0.0.1:${port + it}" }}",
                "FUNDING_RUN_ID" to "query-multi",
            ) +
                if (owner == null) {
                    emptyMap()
                } else {
                    val lineage = if (owner == 0) btcLineage else ethLineage
                    mapOf(
                        "SPLIT_SELF_MATCHING_LINEAGE_HIGH" to lineage.high.toString(),
                        "SPLIT_SELF_MATCHING_LINEAGE_LOW" to lineage.low.toString(),
                    )
                }
        return splitTradingSettings(environment)
    }

    private fun manifest(): TradingClusterManifest {
        val policy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
        return TradingClusterManifest(
            fundsLineage,
            "query-multi",
            BoundedExecutionIdentity.artifactSha256,
            BoundedExecutionIdentity.schemaSha256,
            1,
            markets.mapIndexed { ordinal, id ->
                TradingMarketContext(MarketDefinition(MarketId(id), AssetId(id.substringBefore('-')), AssetId("USDT"), 0), policy, ordinal)
            },
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
                maxMarkets = 2,
                maxAssets = 3,
            ),
        )
    }

    private fun availablePorts(): Int {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceIn(1024, 65472)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                for (offset in (0..10) + (16..26) + (32..42) + 48) sockets.add(DatagramSocket(base + offset))
                return base
            } catch (_: java.net.BindException) {
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("자금·두 매칭 담당과 WAS의 분리 포트를 확보하지 못했습니다")
    }
}
