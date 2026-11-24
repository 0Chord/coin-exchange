package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.ExchangeCoreApplication
import com.exchange.core.api.trading.application.OrderQueryRecoveryFailure
import com.exchange.core.api.trading.application.OrderQueryRecoveryStatus
import com.exchange.core.api.trading.application.OrderQueryRecoveryVerdict
import com.exchange.core.api.trading.application.port.OrderQueryFeedProgress
import com.exchange.core.api.trading.application.port.OrderQueryFeedStore
import com.exchange.core.api.trading.application.port.OrderQuerySourceBounds
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.OrderQueryRecovery
import com.exchange.core.api.trading.infrastructure.codec.OrderQueryRecoveryOrder
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCodec
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCommand
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceReply
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryReply
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.api.trading.infrastructure.persistence.PostgresBalanceProjection
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerReconciliation
import com.exchange.core.ledger.LedgerReconciliationScope
import com.exchange.core.ledger.LedgerReconciliationSnapshot
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.ReconciliationItem
import com.exchange.core.ledger.ReconciliationStatus
import com.exchange.core.ledger.ReservationHold
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.support.OrderQueryTestDatabase
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReceipt
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsLedgerBatch
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.FundsRoleRecoveryState
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.SettlementFact
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingServiceRole
import io.aeron.ChannelUri
import io.aeron.Image
import io.aeron.Publication
import io.aeron.cluster.ClusterControl
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.aopalliance.intercept.MethodInterceptor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import org.springframework.aop.framework.Advised
import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.net.DatagramSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 제품 WAS·PostgreSQL과 실제 자금·매칭 노드를 연결한다. Archive 장애 사례는 역할별 세 멤버로 검증한다. */
class SplitFundsTradingHttpTest {
    @TempDir lateinit var directory: Path
    private val json = JsonMapper.builder().build()
    private val lineage = ClusterLineage(41, 42)

    @Test
    fun `Archive 잔고는 미체결 매수 예약과 잔량 반환을 실제 HTTP로 확인한다`() {
        withTradingNodes(archiveQuery = true) { harness ->
            val pauseBalanceProjection = AtomicBoolean()
            HttpClient.newHttpClient().use { http ->
                archiveBalanceWas(harness, 0, pauseBalanceProjection).use { was ->
                    val url = wasUrl(was)
                    awaitBalance(http, url, "USDT", sequence = 2, available = 1_000, reserved = 0)
                    val invalid = getBalance(http, url, "A".repeat(257))
                    assertEquals(400, invalid.statusCode(), invalid.body())
                    val absent = getBalance(http, url, "BTC")
                    assertEquals(404, absent.statusCode(), absent.body())
                    val missing = json.readTree(absent.body())
                    assertEquals("BALANCE_NOT_FOUND", missing.path("code").asString())
                    assertEquals(2L, missing.path("asOfLedgerSequence").longValue())
                    assertFalse(missing.has("available"))
                    assertFalse(missing.has("reserved"))

                    val epoch = post(http, url, "/channels/0/epoch", """{"userId":"buyer","expectedEpoch":0}""")
                    assertEquals(200, epoch.statusCode(), epoch.body())
                    pauseBalanceProjection.set(true)
                    val orderId =
                        try {
                            submitWhileBalanceProjectionWaits(harness, http, url)
                        } finally {
                            pauseBalanceProjection.set(false)
                        }
                    awaitBalance(http, url, "USDT", sequence = 3, available = 700, reserved = 300)
                    awaitBalance(http, url, "BTC", sequence = 3, available = 0, reserved = 0)

                    val cancelPath = "/markets/BTC-USDT/orders/$orderId/cancel"
                    val cancelBody = """{"userId":"buyer","channel":0,"epoch":1,"sequence":2,"expectedConfigurationVersion":1}"""
                    harness.settlementGate.paused.set(true)
                    val cancelId =
                        try {
                            val cancel = post(http, url, cancelPath, cancelBody)
                            assertEquals(202, cancel.statusCode(), cancel.body())
                            awaitQuery(harness, http, url, orderId, "CANCELLED", 0, 0, 3, "PENDING", "RELEASE")
                            awaitBalance(http, url, "USDT", sequence = 3, available = 700, reserved = 300)
                            val receipt = json.readTree(cancel.body())
                            assertEquals("ACCEPTED", receipt.path("status").asString())
                            receipt.path("cancelId").asString().also { assertTrue(it.isNotBlank()) }
                        } finally {
                            harness.settlementGate.paused.set(false)
                        }
                    awaitQuery(harness, http, url, orderId, "CANCELLED", 0, 0, 3)
                    awaitBalance(http, url, "USDT", sequence = 4, available = 1_000, reserved = 0)

                    val repeated = post(http, url, cancelPath, cancelBody)
                    assertEquals(202, repeated.statusCode(), repeated.body())
                    val receipt = json.readTree(repeated.body())
                    assertEquals("ACCEPTED", receipt.path("status").asString())
                    assertEquals(cancelId, receipt.path("cancelId").asString())
                    awaitBalance(http, url, "USDT", sequence = 4, available = 1_000, reserved = 0)
                }

                verifyBalanceRecoveryAndFailure(harness, http)
            }
        }
    }

    private fun verifyBalanceRecoveryAndFailure(
        harness: TradingHarness,
        http: HttpClient,
    ) {
        val jdbc = harness.database.jdbc()
        assertEquals("READY", jdbc.queryForObject("select lifecycle from order_query.query_progress", String::class.java))
        // 이전 WAS의 READY가 남아 있어도 새 WAS가 원본 검증을 커밋하기 전에는 금액을 내주지 않는다.
        withLockedQueryRow(jdbc, "select 1 from order_query.feed_consumer where namespace = '41:42' for update") { unlock ->
            archiveBalanceWas(harness, 1).use { restarted ->
                val url = wasUrl(restarted)
                try {
                    awaitBalanceUnavailable(http, url, "QUERY_NOT_READY")
                } finally {
                    unlock()
                }
                awaitBalance(http, url, "USDT", sequence = 4, available = 1_000, reserved = 0)
                awaitBalance(http, url, "BTC", sequence = 4, available = 0, reserved = 0)
                assertEquals(4L, jdbc.queryForObject("select count(*) from order_query.funds_ledger_batch", Long::class.java))

                harness.database.whilePaused {
                    assertFailsWith<DataAccessException> { jdbc.queryForObject("select 1", Int::class.java) }
                    awaitBalanceUnavailable(http, url, "QUERY_UNAVAILABLE")
                }
                awaitBalance(http, url, "USDT", sequence = 4, available = 1_000, reserved = 0)
                verifyBalanceProjectionFailure(harness, http, url)
            }
        }
    }

    private fun verifyBalanceProjectionFailure(
        harness: TradingHarness,
        http: HttpClient,
        url: String,
    ) {
        val jdbc = harness.database.jdbc()
        jdbc.execute(
            """
            create function order_query.reject_test_balance_write() returns trigger language plpgsql as $$
            begin
                raise exception '시험 잔고 쓰기 거절' using errcode = '23514';
            end;
            $$
            """.trimIndent(),
        )
        jdbc.execute(
            """
            create trigger reject_test_balance_write before insert or update on order_query.balance_projection
            for each row execute function order_query.reject_test_balance_write()
            """.trimIndent(),
        )
        try {
            val first = post(http, url, "/markets/BTC-USDT/orders", orderBody("buyer", "BUY", 1, sequence = 3))
            assertEquals(202, first.statusCode(), first.body())
            awaitBalanceUnavailable(http, url, "QUERY_INCONSISTENT")

            val later = post(http, url, "/markets/BTC-USDT/orders", orderBody("buyer", "BUY", 1, sequence = 4))
            assertEquals(202, later.statusCode(), later.body())
            val laterOrder = json.readTree(later.body()).path("orderId").asString()
            awaitQuery(harness, http, url, laterOrder, "RESTING", 0, 1, 0)
            assertEquals(6L, jdbc.queryForObject("select count(*) from order_query.funds_ledger_batch", Long::class.java))
            assertEquals(4L, jdbc.queryForObject("select projected_through from order_query.balance_progress", Long::class.java))
            awaitBalanceUnavailable(http, url, "QUERY_INCONSISTENT")
        } finally {
            jdbc.execute("drop trigger reject_test_balance_write on order_query.balance_projection")
            jdbc.execute("drop function order_query.reject_test_balance_write()")
        }
    }

    private fun archiveBalanceWas(
        harness: TradingHarness,
        run: Int,
        pauseBalanceProjection: AtomicBoolean? = null,
    ): ConfigurableApplicationContext {
        val manifest = directory.resolve("manifest.bin")
        val settingsFiles =
            listOf(TradingServiceRole.FUNDS, TradingServiceRole.MATCHING).map { role ->
                val values = settingsEnvironment(manifest, role, harness.basePort, archiveQuery = true)
                val file = directory.resolve("balance-${role.name.lowercase()}.properties")
                val properties = Properties()
                properties.putAll(values)
                Files.newOutputStream(file).use { output -> properties.store(output, null) }
                file
            }
        val args =
            wasArguments(harness, gatewayRoot = "balance-gateway-$run", egressOffset = 32 + run) +
                arrayOf(
                    "--exchange.order-query.source=archive",
                    "--exchange.order-query.consumer-id=balance-test",
                    "--exchange.order-query.archive-node-configs=${settingsFiles.joinToString(",")}",
                    "--spring.datasource.hikari.connection-timeout=1500",
                    "--spring.datasource.hikari.validation-timeout=1000",
                )
        val was = SpringApplicationBuilder(ExchangeCoreApplication::class.java).run(*args)
        if (pauseBalanceProjection != null) {
            try {
                val projection = was.getBean(PostgresBalanceProjection::class.java) as Advised
                // 실제 Spring 빈의 트랜잭션 앞에서 시험 중인 잔고 쓰기만 보류한다.
                val pauseWrites =
                    MethodInterceptor { invocation ->
                        if (invocation.method.name == PostgresBalanceProjection::applyNextPage.name && pauseBalanceProjection.get()) {
                            false
                        } else {
                            invocation.proceed()
                        }
                    }
                projection.addAdvice(0, pauseWrites)
            } catch (failure: Throwable) {
                try {
                    was.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }
        return was
    }

    private fun submitWhileBalanceProjectionWaits(
        harness: TradingHarness,
        http: HttpClient,
        url: String,
    ): String {
        val jdbc = harness.database.jdbc()
        val submitted = post(http, url, "/markets/BTC-USDT/orders", orderBody("buyer", "BUY", 3))
        assertEquals(202, submitted.statusCode(), submitted.body())
        await("잔고 반영을 보류해도 예약 원장은 커밋된다") {
            jdbc.queryForObject("select max(sequence) from order_query.funds_ledger_batch", Long::class.java) == 3L
        }
        val pending = getBalance(http, url, "USDT")
        assertEquals(202, pending.statusCode(), pending.body())
        val body = json.readTree(pending.body())
        assertEquals("QUERY_CATCHING_UP", body.path("code").asString())
        assertEquals(2L, body.path("projectedThroughLedgerSequence").longValue())
        assertEquals(3L, body.path("observedLedgerSequence").longValue())
        assertFalse(body.has("available"))
        assertFalse(body.has("reserved"))
        val orderId = json.readTree(submitted.body()).path("orderId").asString()
        assertTrue(orderId.isNotBlank())
        return orderId
    }

    private fun <T> withLockedQueryRow(
        jdbc: JdbcTemplate,
        sql: String,
        work: (() -> Unit) -> T,
    ): T =
        checkNotNull(jdbc.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(sql).use { statement -> statement.executeQuery().use { assertTrue(it.next()) } }
                work(connection::rollback)
            } finally {
                connection.rollback()
            }
        }

    private fun awaitBalanceUnavailable(
        http: HttpClient,
        url: String,
        code: String,
    ) {
        var last: HttpResponse<String>? = null
        await("잔고 조회 불가 $code", { "마지막 응답 ${last?.statusCode()}: ${last?.body()}" }) {
            val response = getBalance(http, url, "USDT", timeout = Duration.ofSeconds(10))
            last = response
            val body = json.readTree(response.body())
            if (response.statusCode() != 503 || body.path("code").asString() != code) return@await false
            assertFalse(body.has("available"), response.body())
            assertFalse(body.has("reserved"), response.body())
            true
        }
    }

    private fun awaitBalance(
        http: HttpClient,
        url: String,
        asset: String,
        sequence: Long,
        available: Long,
        reserved: Long,
    ) {
        var last: HttpResponse<String>? = null
        await("$asset 잔고 $available/$reserved, 원장 $sequence", { "마지막 응답 ${last?.statusCode()}: ${last?.body()}" }) {
            val response = getBalance(http, url, asset)
            last = response
            val body = json.readTree(response.body())
            response.statusCode() == 200 && body.path("code").asString() == "BALANCE_FOUND" &&
                body.path("userId").asString() == "buyer" && body.path("assetId").asString() == asset &&
                body.path("asOfLedgerSequence").longValue() == sequence &&
                body.path("available").isIntegralNumber && body.path("available").longValue() == available &&
                body.path("reserved").isIntegralNumber && body.path("reserved").longValue() == reserved
        }
    }

    private fun getBalance(
        http: HttpClient,
        url: String,
        asset: String,
        timeout: Duration = Duration.ofSeconds(3),
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("$url/api/trading/v3/users/buyer/balances/$asset"))
                .timeout(timeout)
                .GET()
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `PostgreSQL이 멈춘 동안 실제 삼대삼 거래와 리더 교체를 이어가고 Archive에서 복구한다`(reporter: TestReporter) {
        val tradeCount = 8L
        val basePort = availablePorts(ArchiveReplayCluster.additionalPortOffsets)
        val fees = MakerTakerFeeRates(makerFeeRate = FeeRate.ZERO, takerFeeRate = FeeRate.ZERO)
        val manifestFile = directory.resolve("archive-manifest.bin")
        Files.write(manifestFile, BoundedTradingStateCodec().encodeManifest(manifest(fees, 8)))
        val batchEnabled = "SPLIT_TRANSFER_BATCH_ENABLED" to "true"
        val fundsEnvironment = settingsEnvironment(manifestFile, TradingServiceRole.FUNDS, basePort, archiveQuery = true) + batchEnabled
        val matchingEnvironment =
            settingsEnvironment(manifestFile, TradingServiceRole.MATCHING, basePort, archiveQuery = true) + batchEnabled
        val deliveryGate = ArchiveOrderDeliveryGate()
        val (funds, matching) =
            ArchiveReplayCluster.startBoth(
                splitTradingSettings(fundsEnvironment),
                splitTradingSettings(matchingEnvironment),
                deliveryGate,
            )
        funds.use {
            matching.use {
                OrderQueryTestDatabase(socketTimeoutSeconds = 2).use { database ->
                    val settingsFiles =
                        writeArchiveMemberProperties(funds, fundsEnvironment) + writeArchiveMemberProperties(matching, matchingEnvironment)
                    MediaDriver
                        .launch(
                            MediaDriver
                                .Context()
                                .aeronDirectoryName(directory.resolve("archive-mode-client").toString())
                                .dirDeleteOnShutdown(true)
                                .threadingMode(ThreadingMode.SHARED),
                        ).use { driver ->
                            val fundsEndpoint = prepareArchiveTrading(driver, funds, matching, tradeCount)
                            HttpClient.newHttpClient().use { http ->
                                verifyArchiveOutage(
                                    database,
                                    funds,
                                    matching,
                                    fundsEndpoint,
                                    settingsFiles,
                                    http,
                                    tradeCount,
                                    basePort,
                                    deliveryGate,
                                    reporter,
                                )
                            }
                        }
                }
            }
        }
    }

    private fun prepareArchiveTrading(
        driver: MediaDriver,
        funds: ArchiveReplayCluster,
        matching: ArchiveReplayCluster,
        tradeCount: Long,
    ): SplitOrderDeliveryEndpoint {
        fun endpoint(
            cluster: ArchiveReplayCluster,
            ingress: String,
        ) = SplitOrderDeliveryEndpoint(driver.aeronDirectoryName(), ingress, "127.0.0.1:0", cluster.settings.clusterId, 2)
        val fundsEndpoint = endpoint(funds, funds.settings.fundsIngressEndpoints)
        val matchingEndpoint = endpoint(matching, matching.settings.matchingIngressEndpoints)
        val initialize = SplitOrderDeliveryCommand.InitializeRole(funds.settings.configuration.topologySha256)
        SplitOrderDeliveryClient(matchingEndpoint, 10_000).use { client ->
            assertEquals("INITIALIZED", client.request(initialize).code)
        }
        SplitOrderDeliveryClient(fundsEndpoint, 10_000).use { client ->
            assertEquals("INITIALIZED", client.request(initialize).code)
            for ((user, asset, amount) in listOf(Triple("buyer", "USDT", tradeCount * 100), Triple("seller", "BTC", tradeCount))) {
                val opening = OpeningBalance("archive-mode-$user", UserId(user), AssetId(asset), Amount(amount))
                val payload = DevelopmentBalanceCommandPayload(opening, "archive-mode-$user", Instant.EPOCH)
                val command = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Funding(payload))
                assertEquals("PREPARED", client.request(command).code)
            }
        }
        return fundsEndpoint
    }

    private fun writeArchiveMemberProperties(
        cluster: ArchiveReplayCluster,
        environment: Map<String, String>,
    ): List<Path> =
        cluster.memberSettings.map { member ->
            val transport = member.transport
            val values =
                environment +
                    mapOf(
                        "SPLIT_FUNDS_INGRESS" to member.fundsIngressEndpoints,
                        "SPLIT_MATCHING_INGRESS" to member.matchingIngressEndpoints,
                        "FUNDING_RUN_ROOT" to transport.runRoot.toString(),
                        "FUNDING_BASE_PORT" to transport.basePort.toString(),
                        "FUNDING_MEMBER_ID" to transport.memberId.toString(),
                        "FUNDING_MEMBERS" to transport.members,
                    )
            val path = directory.resolve("archive-${member.role.name.lowercase()}-${transport.memberId}.properties")
            Files.newOutputStream(path).use { output -> Properties().apply { putAll(values) }.store(output, null) }
            path
        }

    private fun verifyArchiveOutage(
        database: OrderQueryTestDatabase,
        funds: ArchiveReplayCluster,
        matching: ArchiveReplayCluster,
        fundsEndpoint: SplitOrderDeliveryEndpoint,
        settingsFiles: List<Path>,
        http: HttpClient,
        tradeCount: Long,
        basePort: Int,
        deliveryGate: ArchiveOrderDeliveryGate,
        reporter: TestReporter,
    ) {
        fun openWas(run: Int): ConfigurableApplicationContext {
            val properties =
                database.properties +
                    mapOf(
                        "exchange.order-query.supported-markets" to "BTC-USDT",
                        "exchange.order-query.source" to "archive",
                        "exchange.order-query.consumer-id" to "archive-outage",
                        "exchange.order-query.archive-node-configs" to settingsFiles.joinToString(","),
                        "TRADING_GATEWAY_RUN_ROOT" to directory.resolve("archive-gateway-$run").toString(),
                        "TRADING_GATEWAY_INGRESS_ENDPOINTS" to funds.settings.fundsIngressEndpoints,
                        "TRADING_GATEWAY_EGRESS_ENDPOINT" to "127.0.0.1:${basePort + 32 + run}",
                        "server.port" to "0",
                        "spring.main.banner-mode" to "off",
                        "logging.level.root" to "ERROR",
                    )
            return SpringApplicationBuilder(ExchangeCoreApplication::class.java)
                .run(*properties.map { "--${it.key}=${it.value}" }.toTypedArray())
        }

        val jdbc = database.jdbc()
        val sellerOrder = TradingIdentifiers.order(lineage, 1).value

        fun awaitSaved(
            url: String,
            state: String,
            filled: Long,
            ledgerCount: Long,
        ) {
            var lastResponse: HttpResponse<String>? = null
            await("Archive 주문 $sellerOrder 조회 $state", { "마지막 응답 ${lastResponse?.statusCode()} ${lastResponse?.body()}" }) {
                val response = getQuery(http, url, sellerOrder)
                lastResponse = response
                response.statusCode() == 200 &&
                    json.readTree(response.body()).let {
                        it.path("state").asString() == state && it.path("financialStatus").asString() == "APPLIED" &&
                            it.path("filledQuantity").longValue() == filled
                    }
            }
            await("Archive 원장 ${ledgerCount}개 반영") {
                jdbc.queryForObject("select count(*) from order_query.funds_ledger_batch", Long::class.java) == ledgerCount
            }
        }

        openWas(0).use { was ->
            val url = wasUrl(was)
            for (user in listOf("buyer", "seller")) {
                val epoch = post(http, url, "/channels/0/epoch", """{"userId":"$user","expectedEpoch":0}""")
                assertEquals(200, epoch.statusCode(), epoch.body())
            }
            val orderPath = "/markets/BTC-USDT/orders"
            val seller = post(http, url, orderPath, orderBody("seller", "SELL", tradeCount))
            assertEquals(202, seller.statusCode(), seller.body())
            awaitSaved(url, "RESTING", 0, 3)

            database.whilePaused {
                assertFailsWith<DataAccessException> { jdbc.queryForObject("select 1", Long::class.java) }

                fun admitBuyer(sequence: Long): JsonNode {
                    val response = post(http, url, orderPath, orderBody("buyer", "BUY", 1, sequence = sequence))
                    assertEquals(202, response.statusCode(), response.body())
                    val receipt = json.readTree(response.body())
                    assertEquals(sequence + 1, receipt.path("admissionSequence").longValue())
                    return receipt
                }
                val half = tradeCount / 2
                val firstBuyer =
                    deliveryGate.whilePaused {
                        val receipt = admitBuyer(1)
                        for (sequence in 2L..half) admitBuyer(sequence)
                        receipt
                    }
                awaitArchiveSettlement(fundsEndpoint, half + 1)

                val oldLeader = matching.awaitLeader()
                matching.stopLeader(oldLeader)
                val newLeader = matching.awaitLeader(after = oldLeader)
                reporter.publishEntry("매칭 리더와 임기", "${oldLeader.memberId}/${oldLeader.term} → ${newLeader.memberId}/${newLeader.term}")

                for (sequence in half + 1..tradeCount) admitBuyer(sequence)
                assertEquals(firstBuyer.path("orderId"), admitBuyer(1).path("orderId"))
                awaitArchiveSettlement(fundsEndpoint, tradeCount + 1)
                val matchingEndpoint =
                    fundsEndpoint.copy(
                        ingressEndpoints = matching.settings.matchingIngressEndpoints,
                        clusterId = matching.settings.clusterId,
                    )
                for (endpoint in listOf(fundsEndpoint, matchingEndpoint)) {
                    OrderQuerySourceClient(endpoint, 10_000).use { source ->
                        val disabled = source.request(OrderQuerySourceCommand.Describe)
                        assertEquals("DISABLED", disabled.code)
                        assertTrue(disabled.progress.isEmpty())
                        assertNull(disabled.ledgerProgress)
                    }
                }
                assertFailsWith<DataAccessException> { jdbc.queryForObject("select 1", Long::class.java) }
            }
            awaitSaved(url, "FILLED", tradeCount, 2 * tradeCount + 3)
        }

        val saved = archiveProjectionRows(jdbc)
        assertEquals((1L..tradeCount + 1).toList(), saved.admissions)
        assertEquals((1L..tradeCount + 1).toList(), saved.matches)
        assertEquals((2L..tradeCount + 1).toList(), saved.settlements.map { it.getValue("result_sequence") })
        val settlementIds = saved.settlements.mapTo(hashSetOf()) { it.getValue("settlement_id") }
        assertEquals(tradeCount.toInt(), settlementIds.size)
        for ((index, settlement) in saved.settlements.withIndex()) {
            assertEquals(sellerOrder, settlement.getValue("maker_order_id"))
            assertEquals(TradingIdentifiers.order(lineage, index + 2L).value, settlement.getValue("taker_order_id"))
            assertEquals(100L, settlement.getValue("price"))
            assertEquals(1L, settlement.getValue("quantity"))
        }
        assertEquals((1L..2 * tradeCount + 3).toList(), saved.ledger.map { it.getValue("sequence") })
        val appliedPositions = saved.ledger.mapTo(hashSetOf()) { it.getValue("log_position") }
        assertTrue(appliedPositions.size < saved.ledger.size, "묶음 정산으로 같은 로그 위치의 원장이 저장되어야 한다")
        assertArchiveTransferFrames(funds)
        assertArchiveTransferFrames(matching)
        val transactions = saved.ledger.flatMap { readRecoveryTransactions(it.getValue("transactions") as String) }
        assertEquals(
            mapOf(
                LedgerTransactionType.OPENING to 2,
                LedgerTransactionType.RESERVE to tradeCount.toInt() + 1,
                LedgerTransactionType.SETTLEMENT to tradeCount.toInt(),
            ),
            transactions.groupingBy { it.transactionType }.eachCount(),
        )
        assertEquals(transactions.size, transactions.map { it.ledgerTransactionId }.toSet().size)

        funds.assertHealthy()
        matching.assertHealthy()
        funds.close()
        matching.close()
        funds.reopen().use { restartedFunds ->
            matching.reopen().use { restartedMatching ->
                restartedFunds.awaitLeader()
                restartedMatching.awaitLeader()
                awaitArchiveSettlement(fundsEndpoint, tradeCount + 1)
                openWas(1).use { was ->
                    awaitSaved(wasUrl(was), "FILLED", tradeCount, 2 * tradeCount + 3)
                    assertEquals(saved, archiveProjectionRows(jdbc))
                }
                restartedFunds.assertHealthy()
                restartedMatching.assertHealthy()
            }
        }
    }

    private fun assertArchiveTransferFrames(cluster: ArchiveReplayCluster) {
        val settings = cluster.settings
        val members =
            cluster.memberSettings.map { member ->
                val transport = member.transport
                SplitArchiveMember(transport.memberId, transport.driverDirectory, transport.clusterDirectory)
            }
        val orderCodec = SplitOrderDeliveryCodec(2)
        val resultCodec = SplitResultDeliveryCodec(2)
        val entries = mutableListOf<Int>()
        val orders = settings.role == TradingServiceRole.MATCHING
        val schema = if (orders) 88 else 89
        val schemaOffset = if (orders) 4 else 0
        AeronSplitArchiveSource(members, settings.clusterId, settings.configuration.resultTopologySha256).use { source ->
            var position = 0L
            await("${settings.role} Archive의 단건 및 묶음 전송") {
                val replayed =
                    source.read(position) { frame ->
                        if (UnsafeBuffer(frame.bytes).getShort(schemaOffset, ByteOrder.LITTLE_ENDIAN).toInt() == schema) {
                            if (orders) {
                                when (val command = orderCodec.decode(frame.bytes).command) {
                                    is SplitOrderDeliveryCommand.Apply -> entries.add(1)
                                    is SplitOrderDeliveryCommand.ApplyBatch -> entries.add(command.inputs.size)
                                    else -> Unit
                                }
                            } else {
                                when (val command = resultCodec.decode(frame.bytes).command) {
                                    is SplitResultDeliveryCommand.Apply -> entries.add(1)
                                    is SplitResultDeliveryCommand.ApplyBatch -> entries.add(command.results.size)
                                    else -> Unit
                                }
                            }
                        }
                    }
                position = replayed.position
                replayed.caughtUp
            }
        }
        assertTrue(1 in entries && entries.any { it > 1 }, "${settings.role} Archive 전송 크기: $entries")
    }

    private data class ArchiveProjectionRows(
        val receipts: List<Map<String, Any?>>,
        val settlements: List<Map<String, Any?>>,
        val ledger: List<Map<String, Any?>>,
    ) {
        val admissions: List<Any?> get() = sourceSequences("ADMISSION")
        val matches: List<Any?> get() = sourceSequences("MATCHING")

        private fun sourceSequences(kind: String): List<Any?> =
            receipts.filter { it.getValue("source_kind") == kind }.map { it.getValue("source_sequence") }
    }

    private fun archiveProjectionRows(jdbc: JdbcTemplate): ArchiveProjectionRows =
        ArchiveProjectionRows(
            receipts =
                jdbc.queryForList(
                    """
                    select source_kind, source_sequence, first_revision, encode(canonical_payload, 'hex') as payload
                    from order_query.source_receipt order by source_kind, source_sequence
                    """.trimIndent(),
                ),
            settlements = jdbc.queryForList("select * from order_query.settlement_transaction order by result_sequence"),
            ledger = storedLedgerRows(jdbc),
        )

    private fun awaitArchiveSettlement(
        endpoint: SplitOrderDeliveryEndpoint,
        through: Long,
    ) {
        SplitOrderDeliveryClient(endpoint, 10_000).use { client ->
            val progress = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Progress)
            await("실제 자금 클러스터의 $through 순번 정산") {
                val reply = client.request(progress)
                reply.control
                    ?.markets
                    ?.single()
                    ?.executedThrough == through
            }
        }
    }

    @Test
    fun `프로필 없는 제품 WAS의 주문이 자금과 매칭을 거치고 같은 WAS 조회는 미준비를 반환한다`() {
        withTradingNodes { harness ->
            val fundsSnapshot = harness.fundsSnapshot
            SpringApplicationBuilder(ExchangeCoreApplication::class.java).run(*wasArguments(harness)).use { context ->
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
                            orderBody("seller", "SELL", 3),
                        )
                    assertEquals(202, seller.statusCode(), seller.body())
                    val body = orderBody("buyer", "BUY", 3)
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
        }
    }

    @Test
    fun `실제 부분 체결과 잔량 취소를 같은 WAS에서 조회하고 DB 원본과 ACK 뒤 WAS 재시작에도 유지한다`() {
        withTradingNodes("query-test") { harness ->
            val sellerOrderId = TradingIdentifiers.order(lineage, 1).value
            val buyerOrderId = TradingIdentifiers.order(lineage, 2).value
            var finalRevision = 0L

            fun runWas(first: Boolean) {
                val gatewayRoot = if (first) "gateway" else "gateway-restart"
                val args = wasArguments(harness, true, gatewayRoot)
                SpringApplicationBuilder(ExchangeCoreApplication::class.java).run(*args).use { context ->
                    assertTrue(context.environment.activeProfiles.isEmpty())
                    val url = "http://127.0.0.1:${(context as ServletWebServerApplicationContext).webServer!!.port}"
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
                        if (first) {
                            for (user in listOf("seller", "buyer")) {
                                val epoch = post(http, url, "/channels/0/epoch", """{"userId":"$user","expectedEpoch":0}""")
                                assertEquals(200, epoch.statusCode(), epoch.body())
                            }
                            val seller = post(http, url, "/markets/BTC-USDT/orders", orderBody("seller", "SELL", 3))
                            assertEquals(202, seller.statusCode(), seller.body())
                            assertEquals(sellerOrderId, json.readTree(seller.body()).path("orderId").asString())
                            awaitQuery(harness, http, url, sellerOrderId, "RESTING", 0, 3, 0)

                            harness.settlementGate.paused.set(true)
                            val buyerBody = orderBody("buyer", "BUY", 1)
                            val buyer = post(http, url, "/markets/BTC-USDT/orders", buyerBody)
                            assertEquals(202, buyer.statusCode(), buyer.body())
                            assertEquals(buyerOrderId, json.readTree(buyer.body()).path("orderId").asString())
                            val repeated = post(http, url, "/markets/BTC-USDT/orders", buyerBody)
                            assertEquals(202, repeated.statusCode(), repeated.body())
                            assertEquals(buyerOrderId, json.readTree(repeated.body()).path("orderId").asString())
                            awaitQuery(harness, http, url, sellerOrderId, "PARTIALLY_FILLED", 1, 2, 0, "PENDING", "SETTLEMENT")
                            awaitQuery(harness, http, url, buyerOrderId, "FILLED", 1, 0, 0, "PENDING", "SETTLEMENT")
                            assertEquals(
                                1L,
                                harness.fundsSnapshot
                                    .get()
                                    ?.results
                                    ?.streams
                                    ?.single()
                                    ?.resultThrough,
                            )
                            harness.settlementGate.paused.set(false)
                            awaitQuery(harness, http, url, sellerOrderId, "PARTIALLY_FILLED", 1, 2, 0)
                            awaitQuery(harness, http, url, buyerOrderId, "FILLED", 1, 0, 0)

                            harness.settlementGate.paused.set(true)
                            verifyCancellationResponses(http, url, sellerOrderId, buyerOrderId)
                            awaitQuery(harness, http, url, sellerOrderId, "CANCELLED", 1, 0, 2, "PENDING", "RELEASE")
                            val pendingRelease = checkNotNull(harness.fundsSnapshot.get())
                            assertEquals(2L, checkNotNull(pendingRelease.results).streams.single().resultThrough)
                            val sellerBalances =
                                pendingRelease.financial.balances
                                    .filter { it.userId.value == "seller" }
                                    .associateBy { it.assetId }
                            val btc = AssetId("BTC")
                            val usdt = AssetId("USDT")
                            assertEquals(Balance(UserId("seller"), btc, Amount(0), Amount(2)), sellerBalances[btc])
                            assertEquals(Balance(UserId("seller"), usdt, Amount(100), Amount(0)), sellerBalances[usdt])
                            assertEquals(1L, pendingRelease.lastCancellationSequence)
                            harness.settlementGate.paused.set(false)
                        }
                        val seller = awaitQuery(harness, http, url, sellerOrderId, "CANCELLED", 1, 0, 2)
                        awaitQuery(harness, http, url, buyerOrderId, "FILLED", 1, 0, 0)
                        val revision = json.readTree(seller.body()).path("appliedRevision").longValue()
                        if (first) finalRevision = revision else assertEquals(finalRevision, revision)
                        assertEquals(2L, json.readTree(seller.body()).path("coveredAdmissionSequence").longValue())
                        if (first) {
                            // 수집 작업이 살아 있는 동안 DB 저장 뒤 두 소스 ACK의 확정·회수까지 기다린다.
                            OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { source ->
                                await("조회 원본의 확정 ACK") {
                                    harness.funds.poll()
                                    val matching = source.request(OrderQuerySourceCommand.Describe)
                                    matching.code == "DESCRIBED" && matching.progress.single().retiredThrough == 3L &&
                                        harness.fundsSnapshot
                                            .get()
                                            ?.queryAdmissions
                                            ?.retiredThrough == 2L
                                }
                            }
                        }
                    }
                }
            }

            runWas(true)
            val settled = checkNotNull(harness.fundsSnapshot.get())
            assertFinalBalances(settled)
            assertEquals(3L, checkNotNull(settled.results).streams.single().resultThrough)
            assertEquals(2L, settled.financial.lastAdmissionSequence)
            assertEquals(1L, settled.lastCancellationSequence)
            assertTrue(settled.financial.liveOrders.isEmpty())
            val jdbc = harness.database.jdbc()
            assertEquals(5L, jdbc.queryForObject("select count(*) from order_query.source_receipt", Long::class.java))
            assertEquals(
                2L,
                jdbc.queryForObject("select count(*) from order_query.source_receipt where source_kind = 'ADMISSION'", Long::class.java),
            )
            assertEquals(
                3L,
                jdbc.queryForObject("select count(*) from order_query.source_receipt where source_kind = 'MATCHING'", Long::class.java),
            )
            assertEquals(
                5L,
                jdbc.queryForObject(
                    "select count(*) from order_query.source_receipt where octet_length(canonical_payload) > 0",
                    Long::class.java,
                ),
            )

            runWas(false)
            val resumed = checkNotNull(harness.fundsSnapshot.get())
            assertFinalBalances(resumed)
            assertEquals(settled.financial.balances, resumed.financial.balances)
            assertEquals(3L, checkNotNull(resumed.results).streams.single().resultThrough)
            assertEquals(5L, jdbc.queryForObject("select count(*) from order_query.source_receipt", Long::class.java))
        }
    }

    @Test
    fun `체결 정산 기록을 PostgreSQL에 한 번 저장한다`() {
        val market = MarketId("BTC-USDT")
        val buyer = UserId("buyer")
        val usdt = AssetId("USDT")
        withTradingNodes("query-test", feeRate = FeeRate(10_000), takerFeeRate = FeeRate(20_000), persistSettlements = true) { harness ->
            val committed = CountDownLatch(1)
            val committedFact = AtomicReference<SettlementFact>()
            val actualStore = AtomicReference<OrderQueryFeedStore>()
            queryWas(harness, decorate = { store ->
                actualStore.set(store)
                object : OrderQueryFeedStore by store {
                    override fun applySettlement(fact: SettlementFact): Long {
                        val through = store.applySettlement(fact)
                        if (fact.fills.isNotEmpty() && committedFact.compareAndSet(null, fact)) {
                            harness.sourceReplies.pauseDescriptions.set(true)
                            committed.countDown()
                            error("시험에서 PostgreSQL 커밋 확인만 유실시킨다")
                        }
                        return through
                    }
                }
            }).use { context ->
                val jdbc = harness.database.jdbc()
                val url = wasUrl(context)
                HttpClient.newHttpClient().use { http ->
                    awaitEmptyReady(harness, http, url)
                    val sellId = json.readTree(submitSeller(http, url, quantity = 1).body()).path("orderId").asString()
                    awaitQuery(harness, http, url, sellId, "RESTING", 0, 1, 0)

                    harness.settlementGate.paused.set(true)
                    val buyId: String
                    try {
                        val epoch = post(http, url, "/channels/0/epoch", """{"userId":"buyer","expectedEpoch":0}""")
                        assertEquals(200, epoch.statusCode(), epoch.body())
                        val buy = post(http, url, "/markets/BTC-USDT/orders", orderBody("buyer", "BUY", quantity = 1, price = 150))
                        assertEquals(202, buy.statusCode(), buy.body())
                        buyId = json.readTree(buy.body()).path("orderId").asString()
                        val expectedReservation = Balance(buyer, usdt, Amount(847), Amount(153))
                        await("매수 대금 153 예약", { describeFunds(harness.fundsSnapshot.get()) }) {
                            val financial = harness.fundsSnapshot.get()?.financial
                            financial != null && expectedReservation in financial.balances
                        }
                        val reserved = checkNotNull(harness.fundsSnapshot.get())
                        assertTrue(reserved.financial.feeRevenueByAsset.isEmpty())
                        assertEquals(0L, jdbc.queryForObject("select count(*) from order_query.settlement_transaction", Long::class.java))
                    } finally {
                        harness.settlementGate.paused.set(false)
                    }

                    assertTrue(committed.await(15, TimeUnit.SECONDS), "PostgreSQL에 첫 정산이 커밋되어야 한다")
                    val first = checkNotNull(harness.fundsSnapshot.get())
                    val original = checkNotNull(first.results).receipts.single { it.result.resultSequence == 2L }.result
                    assertFinalBalances(first, buyerQuote = 898, sellerQuote = 99)
                    assertTrue(first.financial.liveOrders.isEmpty())
                    assertEquals(mapOf(usdt to BigInteger.valueOf(3)), first.financial.feeRevenueByAsset)
                    val pendingExport = first.querySettlements.getValue(market)
                    assertTrue(pendingExport.retiredThrough < 2)
                    assertEquals(2L, pendingExport.entries.last().resultSequence)
                    OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { matching ->
                        val reply = matching.request(OrderQuerySourceCommand.Describe)
                        val matchingProgress = reply.progress.single { it.market == market.value }
                        assertEquals(2L, matchingProgress.issued)
                        assertTrue(matchingProgress.retiredThrough < 2)
                    }

                    val ledgerSql = "select * from order_query.settlement_transaction"
                    val postingsSql = "select * from order_query.settlement_posting order by posting_sequence"
                    val ledger = jdbc.queryForList(ledgerSql).single()
                    val postings = jdbc.queryForList(postingsSql)
                    val fact = checkNotNull(committedFact.get())
                    val settlement = fact.fills.single().transaction
                    assertEquals("SETTLEMENT", ledger["transaction_type"])
                    assertEquals(settlement.ledgerTransactionId, ledger["settlement_id"])
                    assertEquals(settlement.occurredAt, (ledger["occurred_at"] as Timestamp).toInstant())
                    assertEquals(listOf(sellId, buyId), listOf(ledger["maker_order_id"], ledger["taker_order_id"]))
                    val tradeColumns = listOf("result_sequence", "price", "quantity", "maker_fee", "taker_fee")
                    assertEquals(listOf(2L, 100L, 1L, 1L, 2L), tradeColumns.map { ledger[it] })
                    assertEquals(
                        listOf(
                            listOf("USER:seller:BTC:HOLD", "BTC", "DEBIT", 1L),
                            listOf("USER:seller:USDT:AVAILABLE", "USDT", "CREDIT", 99L),
                            listOf("SYSTEM:USDT:FEE_REVENUE", "USDT", "CREDIT", 1L),
                            listOf("USER:buyer:USDT:HOLD", "USDT", "DEBIT", 102L),
                            listOf("USER:buyer:USDT:HOLD", "USDT", "DEBIT", 51L),
                            listOf("USER:buyer:USDT:AVAILABLE", "USDT", "CREDIT", 51L),
                            listOf("USER:buyer:BTC:AVAILABLE", "BTC", "CREDIT", 1L),
                            listOf("SYSTEM:USDT:FEE_REVENUE", "USDT", "CREDIT", 2L),
                        ),
                        postings.map { posting -> listOf("account_id", "asset_id", "side", "amount").map { posting[it] } },
                    )
                    assertEquals(2L, jdbc.queryForObject("select settlement_through from order_query.feed_stream", Long::class.java))
                    assertEquals(2L, actualStore.get().applySettlement(fact), "동일한 확정 분개를 DB에 다시 적용한다")

                    harness.sourceReplies.dropNextSettlementAck.set(true)
                    harness.sourceReplies.pauseDescriptions.set(false)
                    assertTrue(harness.sourceReplies.dropped.await(15, TimeUnit.SECONDS), "자금 ACK 적용 후 응답을 유실시켜야 한다")
                    val acknowledgedFunds = checkNotNull(harness.fundsSnapshot.get())
                    assertEquals(2L, acknowledgedFunds.querySettlements.getValue(market).retiredThrough)
                    OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { matching ->
                        val reply = matching.request(OrderQuerySourceCommand.Describe)
                        assertTrue(reply.progress.single { it.market == market.value }.retiredThrough < 2L)
                    }
                    harness.sourceReplies.pauseDescriptions.set(false)
                    awaitQuery(harness, http, url, buyId, "FILLED", 1, 0, 0)
                    OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { matching ->
                        await("매칭 조회 원본 회수") {
                            val described = matching.request(OrderQuerySourceCommand.Describe)
                            described.progress.singleOrNull { it.market == market.value }?.retiredThrough == 2L
                        }
                    }
                    DevelopmentSplitResultDeliveryClient(harness.fundsEndpoint, 10_000).use { funds ->
                        assertEquals("DUPLICATE", funds.request(SplitResultDeliveryCommand.Apply(original)).code)
                    }
                    OrderQuerySourceClient(harness.fundsEndpoint, 10_000).use { funds ->
                        // 같은 서비스 스레드의 재전송 callback과 상태 캡처가 끝날 때까지 기다린다.
                        assertEquals("DESCRIBED", funds.request(OrderQuerySourceCommand.Describe).code)
                    }
                    val replayed = checkNotNull(harness.fundsSnapshot.get())
                    assertEquals(first.financial.balances, replayed.financial.balances)
                    assertEquals(first.financial.liveOrders, replayed.financial.liveOrders)
                    assertEquals(first.financial.feeRevenueByAsset, replayed.financial.feeRevenueByAsset)
                    assertEquals(2L, checkNotNull(replayed.results).streams.single().resultThrough)
                    val exported = replayed.querySettlements.getValue(market)
                    assertEquals(2L, exported.retiredThrough)
                    assertTrue(exported.entries.isEmpty())
                    assertEquals(listOf(ledger), jdbc.queryForList(ledgerSql))
                    assertEquals(postings, jdbc.queryForList(postingsSql))
                }
            }
        }
    }

    @Test
    fun `같은 지정가 매수 주문이 첫 체결 뒤 남은 수량까지 실제 HTTP로 체결된다`() {
        val buyer = UserId("buyer")
        val seller = UserId("seller")
        val btc = AssetId("BTC")
        val usdt = AssetId("USDT")
        val ordersPath = "/markets/BTC-USDT/orders"
        withTradingNodes(consumerId = "query-test", feeRate = FeeRate(10_000), takerFeeRate = FeeRate(20_000)) { harness ->
            SpringApplicationBuilder(ExchangeCoreApplication::class.java).run(*wasArguments(harness, queryEnabled = true)).use { context ->
                val url = wasUrl(context)
                HttpClient.newHttpClient().use { http ->
                    awaitEmptyReady(harness, http, url)
                    val firstSale = submitSeller(http, url, quantity = 1)
                    val firstSaleId = json.readTree(firstSale.body()).path("orderId").asString()
                    val resting = awaitQuery(harness, http, url, firstSaleId, "RESTING", filled = 0, remaining = 1, cancelled = 0)

                    val restingFunds = checkNotNull(harness.fundsSnapshot.get()) { resting.body() }
                    val restingDetail = "GET ${resting.body()}, ${describeFunds(restingFunds)}"
                    val fundedBalances =
                        restingFunds.financial.balances
                            .filter { balance -> !balance.available.isZero() || !balance.hold.isZero() }
                            .toSet()
                    assertEquals(
                        setOf(Balance(buyer, usdt, Amount(1_000), Amount.ZERO), Balance(seller, btc, Amount(2), Amount(1))),
                        fundedBalances,
                        restingDetail,
                    )
                    assertTrue(restingFunds.financial.feeRevenueByAsset.isEmpty(), restingDetail)

                    val buyerEpoch = post(http, url, "/channels/0/epoch", """{"userId":"buyer","expectedEpoch":0}""")
                    assertEquals(200, buyerEpoch.statusCode(), buyerEpoch.body())
                    val buy = post(http, url, ordersPath, orderBody("buyer", "BUY", quantity = 3))
                    assertEquals(202, buy.statusCode(), buy.body())
                    val buyReceipt = json.readTree(buy.body())
                    assertEquals("ACCEPTED", buyReceipt.path("status").asString())
                    val buyId = buyReceipt.path("orderId").asString()
                    val buyAdmission = buyReceipt.path("admissionSequence").longValue()

                    val partial = awaitQuery(harness, http, url, buyId, "PARTIALLY_FILLED", filled = 1, remaining = 2, cancelled = 0)
                    val partialOrder = json.readTree(partial.body())
                    val partialFunds = checkNotNull(harness.fundsSnapshot.get()) { partial.body() }
                    val partialDetail = "GET ${partial.body()}, ${describeFunds(partialFunds)}"
                    assertEquals(buyId, partialOrder.path("orderId").asString(), partialDetail)
                    assertEquals(3L, partialOrder.path("initialQuantity").longValue(), partialDetail)
                    assertEquals(0L, partialOrder.path("cancelledQuantity").longValue(-1), partialDetail)
                    assertEquals(0L, partialOrder.path("notExecutedQuantity").longValue(-1), partialDetail)
                    assertEquals(2L, partialOrder.path("lastAffectedResultSequence").longValue(), partialDetail)
                    val partialResults = checkNotNull(partialFunds.results) { partialDetail }
                    assertEquals(2L, partialResults.streams.singleOrNull()?.resultThrough, partialDetail)
                    assertEquals(
                        setOf(
                            Balance(buyer, usdt, Amount(694), Amount(204)),
                            Balance(buyer, btc, Amount(1), Amount.ZERO),
                            Balance(seller, usdt, Amount(99), Amount.ZERO),
                            Balance(seller, btc, Amount(2), Amount.ZERO),
                        ),
                        partialFunds.financial.balances.toSet(),
                        partialDetail,
                    )
                    assertEquals(1, partialFunds.financial.liveOrders.size, partialDetail)
                    val liveBuy = partialFunds.financial.liveOrders.single()
                    val reservation = liveBuy.reservation
                    assertEquals(buyId, reservation.orderId.value, partialDetail)
                    assertEquals(Amount(306), reservation.reservedAmount, partialDetail)
                    assertEquals(Amount(6), reservation.initialFeeReserveAmount, partialDetail)
                    assertEquals(Quantity(2), reservation.remainingQuantity, partialDetail)
                    assertEquals(Amount(204), reservation.remainingAmount, partialDetail)
                    assertEquals(Amount(4), reservation.remainingFeeReserveAmount, partialDetail)
                    assertEquals(mapOf(usdt to BigInteger.valueOf(3)), partialFunds.financial.feeRevenueByAsset, partialDetail)

                    val followUp = post(http, url, ordersPath, orderBody("seller", "SELL", quantity = 2, sequence = 2))
                    assertEquals(202, followUp.statusCode(), followUp.body())
                    val followUpReceipt = json.readTree(followUp.body())
                    assertEquals("ACCEPTED", followUpReceipt.path("status").asString())
                    val followUpSaleId = followUpReceipt.path("orderId").asString()
                    assertTrue(followUpSaleId.isNotEmpty() && followUpSaleId != firstSaleId)

                    val full = awaitQuery(harness, http, url, buyId, "FILLED", filled = 3, remaining = 0, cancelled = 0)
                    val fullOrder = json.readTree(full.body())
                    val fullFunds = checkNotNull(harness.fundsSnapshot.get()) { full.body() }
                    val fullDetail = "GET ${full.body()}, ${describeFunds(fullFunds)}"
                    assertEquals(buyId, fullOrder.path("orderId").asString(), fullDetail)
                    assertEquals(buyAdmission, fullOrder.path("admissionSequence").longValue(), fullDetail)
                    assertEquals(3L, fullOrder.path("initialQuantity").longValue(), fullDetail)
                    assertEquals(0L, fullOrder.path("remainingQuantity").longValue(-1), fullDetail)
                    assertEquals(0L, fullOrder.path("cancelledQuantity").longValue(-1), fullDetail)
                    assertEquals(0L, fullOrder.path("notExecutedQuantity").longValue(-1), fullDetail)
                    assertEquals(3L, fullOrder.path("lastAffectedResultSequence").longValue(), fullDetail)
                    val appliedResults = checkNotNull(fullFunds.results) { fullDetail }
                    assertEquals(3L, appliedResults.streams.singleOrNull()?.resultThrough, fullDetail)
                    assertTrue(fullFunds.financial.liveOrders.isEmpty(), fullDetail)
                    assertEquals(
                        setOf(
                            Balance(buyer, usdt, Amount(696), Amount.ZERO),
                            Balance(buyer, btc, Amount(3), Amount.ZERO),
                            Balance(seller, usdt, Amount(295), Amount.ZERO),
                            Balance(seller, btc, Amount.ZERO, Amount.ZERO),
                        ),
                        fullFunds.financial.balances.toSet(),
                        fullDetail,
                    )
                    assertEquals(mapOf(usdt to BigInteger.valueOf(9)), fullFunds.financial.feeRevenueByAsset, fullDetail)

                    val trades = appliedResults.receipts.flatMap { it.result.events }.filterIsInstance<TradeExecuted>()
                    assertEquals(2, trades.size, fullDetail)
                    val (firstTrade, secondTrade) = trades
                    assertEquals(firstSaleId, firstTrade.makerOrderId.value, fullDetail)
                    assertEquals(buyId, firstTrade.takerOrderId.value, fullDetail)
                    assertEquals(Price(100), firstTrade.price, fullDetail)
                    assertEquals(Quantity(1), firstTrade.quantity, fullDetail)
                    assertEquals(buyId, secondTrade.makerOrderId.value, fullDetail)
                    assertEquals(followUpSaleId, secondTrade.takerOrderId.value, fullDetail)
                    assertEquals(Price(100), secondTrade.price, fullDetail)
                    assertEquals(Quantity(2), secondTrade.quantity, fullDetail)
                }
            }
        }
    }

    @Test
    fun `주문별 체결 조회는 양쪽 주문과 정산 저장 상태를 연결한다`() =
        verifyFillFees(
            makerFeeRate = FeeRate(10_000),
            takerFeeRate = FeeRate(20_000),
            first = FillFees(buyer = 2, seller = 1),
            followUp = FillFees(buyer = 2, seller = 4),
            buyerTotal = 4,
        )

    @Test
    fun `주문별 체결 수수료는 확정 0과 수수료 이월을 표시한다`() =
        verifyFillFees(
            makerFeeRate = FeeRate(4_000),
            takerFeeRate = FeeRate(6_000),
            first = FillFees(buyer = 0, seller = 0),
            // 매수의 첫 0.6과 후속 0.84가 합쳐져 1을 실제 청구한다.
            followUp = FillFees(buyer = 1, seller = 1),
            buyerTotal = 1,
        )

    private fun verifyFillFees(
        makerFeeRate: FeeRate,
        takerFeeRate: FeeRate,
        first: FillFees,
        followUp: FillFees,
        buyerTotal: Long,
    ) {
        withTradingNodes("query-test", feeRate = makerFeeRate, takerFeeRate = takerFeeRate, persistSettlements = true) { harness ->
            val firstCommit = CallbackGate()
            val followUpCommit = CallbackGate()
            queryWas(harness, decorate = { store ->
                object : OrderQueryFeedStore by store {
                    override fun applySettlement(fact: SettlementFact): Long {
                        // 매칭 및 자금 반영 뒤, 실제 PostgreSQL 트랜잭션 시작 전에 정산 저장만 기다린다.
                        when (fact.resultSequence) {
                            2L -> firstCommit.pause()
                            3L -> followUpCommit.pause()
                        }
                        return store.applySettlement(fact)
                    }
                }
            }).use { context ->
                try {
                    val url = wasUrl(context)
                    val jdbc = harness.database.jdbc()
                    val positions = "select matching_through, financial_through, settlement_through from order_query.feed_stream"
                    val settlementIds = "select settlement_id from order_query.settlement_transaction order by result_sequence"

                    fun assertStoredProgress(
                        appliedThrough: Long,
                        storedThrough: Long,
                        vararg storedIds: String,
                    ) {
                        val progress = jdbc.queryForMap(positions)
                        assertEquals(appliedThrough, progress["matching_through"])
                        assertEquals(appliedThrough, progress["financial_through"])
                        assertEquals(storedThrough, progress["settlement_through"])
                        assertEquals(storedIds.toList(), jdbc.queryForList(settlementIds, String::class.java))
                    }

                    fun assertStoredFees(
                        tradeId: String,
                        makerFee: Long,
                        takerFee: Long,
                    ) {
                        val settlement =
                            jdbc.queryForMap(
                                "select maker_fee, taker_fee, quote_asset_id from order_query.settlement_transaction where settlement_id = ?",
                                tradeId,
                            )
                        assertEquals(makerFee, settlement["maker_fee"])
                        assertEquals(takerFee, settlement["taker_fee"])
                        assertEquals("USDT", settlement["quote_asset_id"])
                        val postedFees =
                            jdbc.queryForList(
                                """
                                select amount from order_query.settlement_posting where settlement_id = ?
                                    and account_id = 'SYSTEM:USDT:FEE_REVENUE' and asset_id = 'USDT' and side = 'CREDIT'
                                order by amount
                                """.trimIndent(),
                                Long::class.java,
                                tradeId,
                            )
                        assertEquals(listOf(makerFee, takerFee).filter { it > 0 }.sorted(), postedFees)
                    }

                    HttpClient.newHttpClient().use { http ->
                        fun assertFills(
                            orderId: String,
                            settledTotal: JsonNode?,
                            vararg expectedFills: HttpOrderFill,
                        ) {
                            val actual = readOrderFills(http, url, orderId)
                            assertEquals(expectedFills.toList(), actual.fills)
                            assertEquals(settledTotal, actual.settledFeeTotal)
                            assertTrue(actual.nextCursor.isNull)
                        }

                        fun assertLatestTrade(
                            settledThrough: Long,
                            expectedPrice: Long = 100,
                            expectedQuantity: Long? = null,
                        ): JsonNode {
                            val response = getLatestSettledTrade(http, url)
                            assertEquals(200, response.statusCode(), response.body())
                            val body = json.readTree(response.body())
                            assertEquals(setOf("code", "marketId", "asOfSettlementResultSequence", "trade"), body.propertyNames().toSet())
                            assertEquals("BTC-USDT", body.path("marketId").asString())
                            val sequence = body.path("asOfSettlementResultSequence")
                            assertTrue(sequence.isIntegralNumber, response.body())
                            assertEquals(settledThrough, sequence.longValue())

                            val trade = body.path("trade")
                            if (expectedQuantity == null) {
                                assertEquals("NO_SETTLED_TRADE_AS_OF", body.path("code").asString())
                                assertTrue(trade.isNull, response.body())
                            } else {
                                assertEquals("LATEST_SETTLED_TRADE", body.path("code").asString())
                                assertEquals(setOf("price", "quantity", "settledAt"), trade.propertyNames().toSet())
                                assertEquals(expectedPrice, trade.path("price").longValue())
                                assertEquals(expectedQuantity, trade.path("quantity").longValue())
                            }
                            return trade
                        }

                        fun assertSettlementTime(
                            tradeId: String,
                            trade: JsonNode,
                        ) {
                            val storedTime =
                                jdbc.queryForObject(
                                    "select occurred_at from order_query.settlement_transaction where settlement_id = ?",
                                    Timestamp::class.java,
                                    tradeId,
                                )
                            assertEquals(storedTime?.toInstant(), Instant.parse(trade.path("settledAt").asString()))
                        }

                        awaitEmptyReady(harness, http, url)
                        assertLatestTrade(settledThrough = 0)
                        val firstSale = submitSeller(http, url, quantity = 1)
                        val firstSaleId = json.readTree(firstSale.body()).path("orderId").asString()
                        awaitQuery(harness, http, url, firstSaleId, "RESTING", filled = 0, remaining = 1, cancelled = 0)
                        assertFills(firstSaleId, settledTotal = null)
                        assertTrue(jdbc.queryForList(settlementIds, String::class.java).isEmpty())

                        val buyerEpoch = post(http, url, "/channels/0/epoch", """{"userId":"buyer","expectedEpoch":0}""")
                        assertEquals(200, buyerEpoch.statusCode(), buyerEpoch.body())
                        // 첫 체결은 기존 매도 주문의 100, 후속 체결은 남은 매수 주문의 105에 체결된다.
                        val buy = post(http, url, "/markets/BTC-USDT/orders", orderBody("buyer", "BUY", quantity = 3, price = 105))
                        assertEquals(202, buy.statusCode(), buy.body())
                        val buyId = json.readTree(buy.body()).path("orderId").asString()
                        assertTrue(firstCommit.reached.await(10, TimeUnit.SECONDS), "첫 체결이 정산 분개 저장 직전까지 진행해야 한다")
                        awaitQuery(harness, http, url, buyId, "PARTIALLY_FILLED", filled = 1, remaining = 2, cancelled = 0)
                        assertStoredProgress(appliedThrough = 2, storedThrough = 1)
                        assertLatestTrade(settledThrough = 1)
                        assertCommittedChart(http, url, through = 1, volume = 0)

                        val firstPendingResponse = readOrderFills(http, url, buyId)
                        assertNull(firstPendingResponse.settledFeeTotal)
                        val firstPending = firstPendingResponse.fills.single()
                        assertEquals(HttpOrderFill(firstPending.tradeId, 100, 1, "TAKER", "PENDING"), firstPending)
                        assertFills(firstSaleId, null, firstPending.copy(role = "MAKER"))

                        firstCommit.release.countDown()
                        await("첫 체결의 정산 분개 커밋") { jdbc.queryForMap(positions)["settlement_through"] == 2L }
                        val firstBuyerFee = usdtFee(first.buyer)
                        val firstSellerFee = usdtFee(first.seller)
                        val firstCompleted = firstPending.copy(settlementStatus = "COMPLETED", chargedFee = firstBuyerFee)
                        assertStoredProgress(2, 2, firstCompleted.tradeId)
                        assertStoredFees(firstCompleted.tradeId, makerFee = first.seller, takerFee = first.buyer)
                        val firstSettledTrade = assertLatestTrade(settledThrough = 2, expectedQuantity = 1)
                        assertSettlementTime(firstCompleted.tradeId, firstSettledTrade)
                        assertCommittedChart(http, url, through = 2, volume = 1, closingPrice = 100)
                        assertFills(buyId, firstBuyerFee, firstCompleted)
                        assertFills(firstSaleId, firstSellerFee, firstCompleted.copy(role = "MAKER", chargedFee = firstSellerFee))

                        val followUpOrder = orderBody("seller", "SELL", quantity = 2, sequence = 2)
                        val followUpSale = post(http, url, "/markets/BTC-USDT/orders", followUpOrder)
                        assertEquals(202, followUpSale.statusCode(), followUpSale.body())
                        val followUpSaleId = json.readTree(followUpSale.body()).path("orderId").asString()
                        assertTrue(followUpCommit.reached.await(10, TimeUnit.SECONDS), "후속 체결이 정산 분개 저장 직전까지 진행해야 한다")
                        awaitQuery(harness, http, url, buyId, "FILLED", filled = 3, remaining = 0, cancelled = 0)
                        assertStoredProgress(3, 2, firstCompleted.tradeId)
                        assertEquals(firstSettledTrade, assertLatestTrade(settledThrough = 2, expectedQuantity = 1))
                        assertCommittedChart(http, url, through = 2, volume = 1, closingPrice = 100)

                        val pendingResponse = readOrderFills(http, url, buyId)
                        assertEquals(firstBuyerFee, pendingResponse.settledFeeTotal)
                        val pendingFills = pendingResponse.fills
                        assertEquals(2, pendingFills.size)
                        assertEquals(firstCompleted, pendingFills.first())
                        val followUpPending = pendingFills.last()
                        assertTrue(followUpPending.tradeId != firstCompleted.tradeId)
                        assertEquals(HttpOrderFill(followUpPending.tradeId, 105, 2, "MAKER", "PENDING"), followUpPending)
                        assertFills(followUpSaleId, null, followUpPending.copy(role = "TAKER"))

                        followUpCommit.release.countDown()
                        await("후속 체결의 정산 분개 커밋") { jdbc.queryForMap(positions)["settlement_through"] == 3L }
                        val followUpBuyerFee = usdtFee(followUp.buyer)
                        val followUpSellerFee = usdtFee(followUp.seller)
                        val buyerSettledTotal = usdtFee(buyerTotal)
                        val followUpCompleted = followUpPending.copy(settlementStatus = "COMPLETED", chargedFee = followUpBuyerFee)
                        assertStoredProgress(3, 3, firstCompleted.tradeId, followUpCompleted.tradeId)
                        assertStoredFees(followUpCompleted.tradeId, makerFee = followUp.buyer, takerFee = followUp.seller)
                        val latestSettledTrade = assertLatestTrade(settledThrough = 3, expectedPrice = 105, expectedQuantity = 2)
                        assertSettlementTime(followUpCompleted.tradeId, latestSettledTrade)
                        assertCommittedChart(http, url, through = 3, volume = 3, closingPrice = 105)
                        assertFills(buyId, buyerSettledTotal, firstCompleted, followUpCompleted)
                        assertFills(firstSaleId, firstSellerFee, firstCompleted.copy(role = "MAKER", chargedFee = firstSellerFee))
                        val followUpSellerFill = followUpCompleted.copy(role = "TAKER", chargedFee = followUpSellerFee)
                        assertFills(followUpSaleId, followUpSellerFee, followUpSellerFill)

                        val firstPage = readOrderFills(http, url, buyId, "?limit=1")
                        assertEquals(listOf(firstCompleted), firstPage.fills)
                        assertEquals(buyerSettledTotal, firstPage.settledFeeTotal)
                        assertTrue(firstPage.nextCursor.isObject)
                        val resultSequence = firstPage.nextCursor.path("resultSequence").longValue()
                        val engineSequence = firstPage.nextCursor.path("engineSequence").longValue()
                        val cursorQuery = "?limit=1&afterResultSequence=$resultSequence&afterEngineSequence=$engineSequence"
                        val nextPage = readOrderFills(http, url, buyId, cursorQuery)
                        assertEquals(listOf(followUpCompleted), nextPage.fills)
                        assertEquals(buyerSettledTotal, nextPage.settledFeeTotal)
                        assertTrue(nextPage.nextCursor.isNull)

                        val sourceEventId =
                            jdbc.queryForObject(
                                "select source_event_id from order_query.settlement_transaction where settlement_id = ?",
                                String::class.java,
                                followUpCompleted.tradeId,
                            )
                        val changeSource = "update order_query.settlement_transaction set source_event_id = ? where settlement_id = ?"
                        try {
                            assertEquals(1, jdbc.update(changeSource, "incorrect-source", followUpCompleted.tradeId))
                            val inconsistent = getOrderFills(http, url, buyId)
                            assertEquals(503, inconsistent.statusCode(), inconsistent.body())
                            assertEquals("QUERY_INCONSISTENT", json.readTree(inconsistent.body()).path("code").asString())
                        } finally {
                            jdbc.update(changeSource, sourceEventId, followUpCompleted.tradeId)
                        }

                        // 실제 SQL 오류가 발생해도 체결 조회의 오류 응답을 유지한다.
                        jdbc.execute("alter table order_query.settlement_transaction rename to unavailable_settlement_transaction")
                        try {
                            val unavailable = getOrderFills(http, url, buyId)
                            assertEquals(503, unavailable.statusCode(), unavailable.body())
                            assertEquals("QUERY_UNAVAILABLE", json.readTree(unavailable.body()).path("code").asString())
                        } finally {
                            jdbc.execute("alter table order_query.unavailable_settlement_transaction rename to settlement_transaction")
                        }
                        assertFills(buyId, buyerSettledTotal, firstCompleted, followUpCompleted)
                    }
                } finally {
                    firstCommit.release.countDown()
                    followUpCommit.release.countDown()
                }
            }
        }
    }

    private data class FillFees(
        val buyer: Long,
        val seller: Long,
    )

    private data class HttpOrderFill(
        val tradeId: String,
        val price: Long,
        val quantity: Long,
        val role: String,
        val settlementStatus: String,
        val chargedFee: JsonNode? = null,
    )

    private data class HttpFillPage(
        val fills: List<HttpOrderFill>,
        val settledFeeTotal: JsonNode?,
        val nextCursor: JsonNode,
    )

    private fun readOrderFills(
        http: HttpClient,
        url: String,
        orderId: String,
        query: String = "",
    ): HttpFillPage {
        val response = getOrderFills(http, url, orderId, query)
        assertEquals(200, response.statusCode(), response.body())
        val body = json.readTree(response.body())
        assertEquals(orderId, body.path("orderId").asString(), response.body())
        assertEquals("BTC-USDT", body.path("marketId").asString(), response.body())
        val fills = body.path("fills")
        assertTrue(fills.isArray, response.body())
        val entries =
            List(fills.size()) { index ->
                val fill = fills.path(index)
                val tradeId = fill.path("tradeId").asString()
                assertTrue(tradeId.isNotBlank(), response.body())
                HttpOrderFill(
                    tradeId = tradeId,
                    price = fill.path("price").longValue(),
                    quantity = fill.path("quantity").longValue(),
                    role = fill.path("role").asString(),
                    settlementStatus = fill.path("settlementStatus").asString(),
                    chargedFee = feeField(fill, "chargedFee"),
                )
            }
        return HttpFillPage(entries, feeField(body, "settledFeeTotal"), body.path("nextCursor"))
    }

    private fun feeField(
        body: JsonNode,
        name: String,
    ): JsonNode? {
        val fee = body.path(name)
        assertFalse(fee.isMissingNode, "응답에 $name 필드가 있어야 한다")
        return fee.takeUnless { it.isNull }
    }

    private fun usdtFee(amount: Long): JsonNode = json.readTree("""{"amount":"$amount","assetId":"USDT"}""")

    private fun getOrderFills(
        http: HttpClient,
        url: String,
        orderId: String,
        query: String = "",
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("$url/api/trading/v3/markets/BTC-USDT/orders/$orderId/fills$query"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun getLatestSettledTrade(
        http: HttpClient,
        url: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("$url/api/trading/v3/markets/BTC-USDT/trades/latest"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun assertCommittedChart(
        http: HttpClient,
        url: String,
        through: Long,
        volume: Long,
        closingPrice: Long? = null,
    ) {
        val request =
            HttpRequest
                .newBuilder(URI.create("$url/api/trading/v3/markets/BTC-USDT/candles?interval=1m"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
        var chart = json.readTree("{}")
        await("정산 순번 $through 차트 조회", detail = { chart.toString() }) {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            chart = json.readTree(response.body())
            response.statusCode() == 200 &&
                chart.path("code").asString() == "CANDLES_FOUND" &&
                chart.path("asOfSettlementResultSequence").asLong(-1) == through
        }
        val candles = chart.path("candles")
        assertTrue(candles.isArray)
        assertEquals(volume, candles.sumOf { candle -> candle.path("volume").asString().toLong() })
        if (closingPrice == null) {
            assertTrue(candles.isEmpty)
        } else {
            assertEquals(100L, candles.first().path("open").longValue())
            assertEquals(closingPrice, candles.last().path("close").longValue())
        }
    }

    private fun verifyCancellationResponses(
        http: HttpClient,
        url: String,
        sellerOrderId: String,
        buyerOrderId: String,
    ) {
        fun cancel(
            user: String,
            orderId: String,
            sequence: Long,
        ) = post(
            http,
            url,
            "/markets/BTC-USDT/orders/$orderId/cancel",
            """{"userId":"$user","channel":0,"epoch":1,"sequence":$sequence,"expectedConfigurationVersion":1}""",
        )

        val first = cancel("seller", sellerOrderId, 2)
        assertEquals(202, first.statusCode(), first.body())
        val firstReceipt = json.readTree(first.body())
        assertEquals("ACCEPTED", firstReceipt.path("status").asString())
        assertEquals(sellerOrderId, firstReceipt.path("orderId").asString())
        assertEquals(TradingIdentifiers.cancel(lineage, 1), firstReceipt.path("cancelId").asString())

        val repeated = cancel("seller", sellerOrderId, 2)
        assertEquals(202, repeated.statusCode(), repeated.body())
        val repeatedReceipt = json.readTree(repeated.body())
        assertEquals("ACCEPTED", repeatedReceipt.path("status").asString())
        assertEquals(sellerOrderId, repeatedReceipt.path("orderId").asString())
        assertEquals(firstReceipt.path("cancelId").asString(), repeatedReceipt.path("cancelId").asString())

        val conflict = cancel("seller", buyerOrderId, 2)
        assertEquals(409, conflict.statusCode(), conflict.body())
        assertEquals("REQUEST_CONTENT_CONFLICT", json.readTree(conflict.body()).path("status").asString())

        val alreadyPending = cancel("seller", sellerOrderId, 3)
        assertEquals(422, alreadyPending.statusCode(), alreadyPending.body())
        assertEquals("CANCEL_PENDING", json.readTree(alreadyPending.body()).path("reason").asString())

        val wrongOwner = cancel("buyer", sellerOrderId, 2)
        assertEquals(422, wrongOwner.statusCode(), wrongOwner.body())
        assertEquals("NOT_ACTIVE", json.readTree(wrongOwner.body()).path("reason").asString())
    }

    @Test
    fun `주문 응답 유실 뒤 새 WAS에서 같은 요청을 재전송하고 한 번 정산된 주문을 조회한다`() {
        val buyerToken = RequestToken(UserId("buyer"), channel = 0, epoch = 1, sequence = 1)
        val reservedBuyerBalance = Balance(buyerToken.userId, AssetId("USDT"), Amount(899), Amount(101))
        val lostReceipt = AtomicReference<AdmissionReceipt?>()
        withTradingNodes(
            consumerId = "query-test",
            feeRate = FeeRate(10_000),
            decorateFunds = { service -> dropFirstAdmissionReply(service, buyerToken, lostReceipt) },
        ) { harness ->
            val orderPath = "/markets/BTC-USDT/orders"
            val buyOrder = orderBody("buyer", "BUY", 1)
            val initialArguments =
                wasArguments(harness, queryEnabled = true) + "--TRADING_GATEWAY_RESULT_TIMEOUT_MS=1500"
            val initialPid =
                OrderRecoveryWas.start(initialArguments, directory.resolve("initial-was.log")).use { was ->
                    HttpClient.newHttpClient().use { http ->
                        awaitEmptyReady(harness, http, was.url)
                        val seller = submitSeller(http, was.url, quantity = 1)
                        val sellerOrderId = json.readTree(seller.body()).path("orderId").asString()
                        awaitQuery(harness, http, was.url, sellerOrderId, "RESTING", 0, 1, 0)
                        val epoch = post(http, was.url, "/channels/0/epoch", """{"userId":"buyer","expectedEpoch":0}""")
                        assertEquals(200, epoch.statusCode(), epoch.body())

                        harness.settlementGate.paused.set(true)
                        val unknown = post(http, was.url, orderPath, buyOrder)
                        assertEquals(504, unknown.statusCode(), unknown.body())
                        val unknownBody = json.readTree(unknown.body())
                        assertEquals("OUTCOME_UNKNOWN", unknownBody.path("status").asString())
                        assertEquals("RESULT_UNCONFIRMED", unknownBody.path("reason").asString())
                        assertEquals("buyer", unknownBody.path("userId").asString())
                        assertEquals(0, unknownBody.path("channel").asInt(-1))
                        assertEquals(1L, unknownBody.path("epoch").longValue())
                        assertEquals(1L, unknownBody.path("sequence").longValue())
                        assertTrue(unknownBody.path("orderId").isMissingNode || unknownBody.path("orderId").isNull)
                        val committed = checkNotNull(harness.fundsSnapshot.get()).financial
                        val buyerBalance = committed.balances.single { it.userId.value == "buyer" && it.assetId.value == "USDT" }
                        assertEquals(reservedBuyerBalance, buyerBalance)
                        val buyerInbox =
                            committed.inbox.channels.single { channel ->
                                channel.userId == buyerToken.userId && channel.channel == buyerToken.channel
                            }
                        assertEquals(lostReceipt.get(), buyerInbox.entries.single { it.sequence == buyerToken.sequence }.receipt)
                        assertEquals(2L, committed.lastAdmissionSequence)
                    }
                    was.pid
                }
            assertFalse(ProcessHandle.of(initialPid).isPresent, "첫 WAS가 종료돼야 한다")

            val original = checkNotNull(lostReceipt.get()) { "자금 엔진의 성공 응답이 유실돼야 한다" }
            val originalOrderId = checkNotNull(original.orderId).value
            // 새 클라이언트 세션이 이전 WAS의 Aeron 응답 채널을 재사용하지 않도록 분리한다.
            val restartedArguments = wasArguments(harness, queryEnabled = true, gatewayRoot = "gateway-restart", egressOffset = 33)
            OrderRecoveryWas.start(restartedArguments, directory.resolve("restarted-was.log")).use { was ->
                assertTrue(was.pid != initialPid, "새 JVM에서 같은 요청을 다시 보내야 한다")
                HttpClient.newHttpClient().use { http ->
                    val recovered = post(http, was.url, orderPath, buyOrder)
                    assertEquals(202, recovered.statusCode(), recovered.body())
                    val recoveredBody = json.readTree(recovered.body())
                    val orderId = recoveredBody.path("orderId").asString()
                    val admissionSequence = recoveredBody.path("admissionSequence").longValue()
                    assertEquals("ACCEPTED", recoveredBody.path("status").asString())
                    assertEquals(originalOrderId, orderId)
                    assertEquals(original.admissionSequence, admissionSequence)
                    assertEquals(2L, admissionSequence)
                    assertEquals(original.marketAdmissionSequence, recoveredBody.path("marketAdmissionSequence").longValue())

                    val conflicting = post(http, was.url, orderPath, orderBody("buyer", "BUY", 1, price = 101))
                    assertEquals(409, conflicting.statusCode(), conflicting.body())
                    assertEquals("REQUEST_CONTENT_CONFLICT", json.readTree(conflicting.body()).path("status").asString())
                    awaitQuery(harness, http, was.url, orderId, "FILLED", 1, 0, 0, "PENDING", "SETTLEMENT", minimum = admissionSequence)
                    val beforeSettlement = checkNotNull(harness.fundsSnapshot.get())
                    val buyerBalance =
                        beforeSettlement.financial.balances.single { balance ->
                            balance.userId == buyerToken.userId && balance.assetId == reservedBuyerBalance.assetId
                        }
                    assertEquals(reservedBuyerBalance, buyerBalance)
                    assertEquals(2L, beforeSettlement.financial.lastAdmissionSequence)
                    val beforeSettlementStream = checkNotNull(beforeSettlement.results).streams.single()
                    assertEquals(1L, beforeSettlementStream.resultThrough)

                    harness.settlementGate.paused.set(false)
                    awaitQuery(harness, http, was.url, orderId, "FILLED", 1, 0, 0, minimum = admissionSequence)
                    val ack = post(http, was.url, "/channels/0/ack", """{"userId":"buyer","epoch":1,"through":1}""")
                    assertEquals(200, ack.statusCode(), ack.body())
                    assertEquals(1L, json.readTree(ack.body()).path("retiredThrough").longValue())
                    val retired = post(http, was.url, orderPath, buyOrder)
                    assertEquals(409, retired.statusCode(), retired.body())
                    assertEquals("RETIRED_UNAVAILABLE", json.readTree(retired.body()).path("status").asString())
                    val epoch = post(http, was.url, "/channels/0/epoch", """{"userId":"buyer","expectedEpoch":1}""")
                    assertEquals(200, epoch.statusCode(), epoch.body())
                    assertEquals(2L, json.readTree(epoch.body()).path("epoch").longValue())
                    val fenced = post(http, was.url, orderPath, buyOrder)
                    assertEquals(409, fenced.statusCode(), fenced.body())
                    assertEquals("FENCED_EPOCH", json.readTree(fenced.body()).path("status").asString())

                    val unaffordableOrder = orderBody("buyer", "BUY", 10, epoch = 2)
                    repeat(2) {
                        val rejected = post(http, was.url, orderPath, unaffordableOrder)
                        assertEquals(422, rejected.statusCode(), rejected.body())
                        val rejectedBody = json.readTree(rejected.body())
                        assertEquals("REJECTED", rejectedBody.path("status").asString())
                        assertEquals("INSUFFICIENT_BALANCE", rejectedBody.path("reason").asString())
                        val rejectedOrderId = rejectedBody.path("orderId")
                        assertTrue(rejectedOrderId.isMissingNode || rejectedOrderId.isNull)
                    }

                    val settled = getQuery(http, was.url, orderId, minimum = admissionSequence)
                    assertEquals(200, settled.statusCode(), settled.body())
                    val settledBody = json.readTree(settled.body())
                    assertEquals(orderId, settledBody.path("orderId").asString())
                    assertEquals("FILLED", settledBody.path("state").asString())
                    assertEquals("APPLIED", settledBody.path("financialStatus").asString())
                    assertEquals(1L, settledBody.path("filledQuantity").longValue())
                    assertEquals(2L, settledBody.path("coveredAdmissionSequence").longValue())
                    val finalFunds = checkNotNull(harness.fundsSnapshot.get())
                    assertFinalBalances(finalFunds, buyerQuote = 899, sellerQuote = 99)
                    assertEquals(mapOf(AssetId("USDT") to BigInteger.TWO), finalFunds.financial.feeRevenueByAsset)
                    assertEquals(2L, finalFunds.financial.lastAdmissionSequence)
                    val finalStream = checkNotNull(finalFunds.results).streams.single()
                    assertEquals(2L, finalStream.resultThrough)
                    assertTrue(finalFunds.financial.liveOrders.isEmpty())
                    assertEquals(4L, receiptCount(harness))
                }
            }
        }
    }

    @Test
    fun `전송 전에 막힌 요청을 그대로 다시 보내면 실제 자금 엔진이 한 번 접수한다`() {
        withTradingNodes(consumerId = "query-test") { harness ->
            val blockOffer = AtomicBoolean()
            val offerBlocked = AtomicBoolean()
            val settings =
                AeronGatewaySettings(
                    runRoot = directory.resolve("gateway"),
                    ingressEndpoints = harness.fundsSettings.fundsIngressEndpoints,
                    egressEndpoint = "127.0.0.1:${harness.basePort + 32}",
                    resultTimeoutMs = 1_500,
                )
            val application = SpringApplicationBuilder(ExchangeCoreApplication::class.java)
            application.initializers({ context ->
                context.addBeanFactoryPostProcessor { factory ->
                    val gateway = factory.getBeanDefinition("boundedAeronGateway") as AbstractBeanDefinition
                    gateway.instanceSupplier =
                        Supplier {
                            BoundedAeronGateway(settings, commandEncoder = SplitFundsGatewayTransport.Companion::encodeCommand) { receive ->
                                val transport = SplitFundsGatewayTransport(settings, lineage, receive)
                                object : BoundedGatewayTransport by transport {
                                    override fun offer(bytes: ByteArray): Long {
                                        if (!blockOffer.get()) return transport.offer(bytes)
                                        offerBlocked.set(true)
                                        return Publication.BACK_PRESSURED
                                    }
                                }
                            }
                        }
                }
            })
            application.run(*wasArguments(harness, queryEnabled = true)).use { context ->
                val url = wasUrl(context)
                val path = "/markets/BTC-USDT/orders"
                val request = orderBody("buyer", "BUY", 1)
                HttpClient.newHttpClient().use { http ->
                    awaitEmptyReady(harness, http, url)
                    val epoch = post(http, url, "/channels/0/epoch", """{"userId":"buyer","expectedEpoch":0}""")
                    assertEquals(200, epoch.statusCode(), epoch.body())
                    blockOffer.set(true)
                    val unsent = post(http, url, path, request)
                    assertEquals(503, unsent.statusCode(), unsent.body())
                    val unsentBody = json.readTree(unsent.body())
                    assertEquals("NOT_SUBMITTED", unsentBody.path("status").asString())
                    assertEquals("QUEUE_TIMEOUT", unsentBody.path("reason").asString())
                    assertEquals("buyer", unsentBody.path("userId").asString())
                    assertEquals(0, unsentBody.path("channel").asInt(-1))
                    assertEquals(1L, unsentBody.path("epoch").longValue())
                    assertEquals(1L, unsentBody.path("sequence").longValue())
                    val unsentOrderId = unsentBody.path("orderId")
                    assertTrue(unsentOrderId.isMissingNode || unsentOrderId.isNull)
                    assertTrue(offerBlocked.get())
                    val afterTimeout = checkNotNull(harness.fundsSnapshot.get()).financial
                    assertEquals(0L, afterTimeout.lastAdmissionSequence)
                    val buyerChannel = afterTimeout.inbox.channels.single()
                    assertTrue(buyerChannel.entries.isEmpty())
                    assertTrue(afterTimeout.liveOrders.isEmpty())
                    assertTrue(afterTimeout.balances.all { it.hold == Amount.ZERO })

                    blockOffer.set(false)
                    val accepted = post(http, url, path, request)
                    assertEquals(202, accepted.statusCode(), accepted.body())
                    val acceptedBody = json.readTree(accepted.body())
                    val orderId = acceptedBody.path("orderId").asString()
                    assertEquals(1L, acceptedBody.path("admissionSequence").longValue())
                    val duplicate = post(http, url, path, request)
                    assertEquals(202, duplicate.statusCode(), duplicate.body())
                    val duplicateBody = json.readTree(duplicate.body())
                    assertEquals(orderId, duplicateBody.path("orderId").asString())
                    assertEquals(1L, duplicateBody.path("admissionSequence").longValue())
                    awaitQuery(harness, http, url, orderId, "RESTING", 0, 1, 0)
                    val afterRetry = checkNotNull(harness.fundsSnapshot.get()).financial
                    val quote = afterRetry.balances.single { it.userId.value == "buyer" && it.assetId.value == "USDT" }
                    assertEquals(Balance(UserId("buyer"), AssetId("USDT"), Amount(900), Amount(100)), quote)
                    assertEquals(1L, afterRetry.lastAdmissionSequence)
                    assertEquals(1, afterRetry.liveOrders.size)
                }
            }
        }
    }

    @Test
    fun `자금 Archive 승인 위치에서 복원 잔고와 활성 예약을 대조한다`() {
        withTradingNodes(sourceRestart = true) { harness ->
            val buyer = UserId("buyer")
            val quote = AssetId("USDT")
            val market = MarketId("BTC-USDT")
            val epoch = harness.funds.request(SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(buyer, 0, 0)))
            assertEquals("ISSUED", epoch.code)
            takeSnapshot(harness.fundsNode, harness.funds)

            val intent = OrderIntent(market, 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(3))
            val order = BoundedTradingCommand.Admit(RequestToken(buyer, 0, 1, 1), intent)
            val accepted = harness.funds.request(SplitOrderDeliveryCommand.Control(order))
            assertEquals("ACCEPTED", accepted.code)
            val receipt = checkNotNull(checkNotNull(accepted.control).admission)
            val orderId = checkNotNull(receipt.orderId)
            val admissionSequence = checkNotNull(receipt.admissionSequence)
            val approvalPosition = accepted.appliedLogPosition

            val settings = harness.fundsSettings
            val consensus = harness.fundsNode.consensusContext
            val archiveId = harness.fundsNode.archiveId
            val logChannel = ChannelUri.parse(consensus.logChannel())
            // Aeron은 UDP 로그 publication을 열 때 제어 포트를 wildcard로 바꾼다.
            logChannel.put("control", ChannelUri.replacePortWithWildcard(checkNotNull(logChannel.get("control"))))
            val archive =
                RecordedArchiveReader(
                    settings.transport,
                    consensus.logStreamId(),
                    logChannel.toString(),
                    expectedArchiveId = archiveId,
                )
            val read = archive.read(approvalPosition)
            val archived = assertIs<RecordedArchiveRead.Complete>(read, (read as? RecordedArchiveRead.Unavailable)?.reason)
            val successfulInputs = harness.openingInputs + AppliedFundsInput(order, accepted)
            val recorded = verifyArchivedFundsInputs(archived, archiveId, successfulInputs)
            val buyerOpening = recorded.filterIsInstance<BoundedTradingCommand.Funding>().single { it.command.opening.userId == buyer }
            val recordedBuy = recorded.filterIsInstance<BoundedTradingCommand.Admit>().single()
            val funded = buyerOpening.command.opening
            val reservationAmount = Amount(Math.multiplyExact(recordedBuy.intent.price.value, recordedBuy.intent.quantity.value))
            assertEquals(Amount(1_000), funded.amount)
            assertEquals(Amount(300), reservationAmount)

            // 원본이 없거나 읽은 위치가 다르면 다른 쪽의 잔고가 맞아도 대조할 수 없다.
            val buyerOpeningReply = harness.openingInputs.first().reply
            val openingPosition = buyerOpeningReply.appliedLogPosition
            for (missingPosition in listOf(openingPosition, approvalPosition)) {
                val incomplete = archived.copy(inputs = archived.inputs.filter { it.position != missingPosition })
                assertFailsWith<IllegalStateException> {
                    verifyArchivedFundsInputs(incomplete, archiveId, successfulInputs)
                }
            }
            assertFailsWith<IllegalStateException> {
                verifyArchivedFundsInputs(
                    archived.copy(consumedPosition = openingPosition),
                    archiveId,
                    successfulInputs,
                )
            }

            harness.funds.close()
            harness.fundsNode.close()
            harness.matchingNode.close()
            val restored = AtomicBoolean()
            val captured = AtomicReference<FundsAtLogPosition?>()
            val recovery = settings.copy(transport = settings.transport.copy(recoverExisting = true))
            DevelopmentSplitTradingNode
                .start(
                    recovery,
                    serviceDecorator = { adapter ->
                        observeFunds(adapter, restored, replayPosition = approvalPosition) { position, state ->
                            captured.compareAndSet(null, FundsAtLogPosition(position, state))
                        }
                    },
                ).use { node ->
                    await("자금 스냅샷과 Archive 주문의 같은 위치 복구") {
                        assertNull(node.error)
                        captured.get() != null
                    }
                    assertTrue(restored.get(), "실제 스냅샷 이미지가 필요하다")
                    val restoredFunds = checkNotNull(captured.get())
                    verifyRestoredFundsPosition(restoredFunds, accepted)
                    assertFailsWith<IllegalStateException> {
                        verifyRestoredFundsPosition(restoredFunds.copy(logPosition = approvalPosition + 32), accepted)
                    }
                    val differentLineage = accepted.lineage.copy(low = accepted.lineage.low + 1)
                    assertFailsWith<IllegalStateException> {
                        verifyRestoredFundsPosition(restoredFunds, accepted.copy(lineage = differentLineage))
                    }
                    val stateAtApproval = restoredFunds.state.financial
                    assertEquals(admissionSequence, stateAtApproval.lastAdmissionSequence)
                    val balance = stateAtApproval.balances.single { it.userId == buyer && it.assetId == quote }
                    val restoredOrder = stateAtApproval.liveOrders.single()
                    assertEquals(admissionSequence, restoredOrder.admissionSequence)
                    val reservation = restoredOrder.reservation
                    assertEquals(orderId, reservation.orderId)
                    assertEquals(buyer, reservation.userId)
                    assertEquals(quote, reservation.assetId)
                    assertEquals("ACTIVE", reservation.status.name)
                    assertEquals(reservationAmount, reservation.remainingAmount)
                    assertEquals(Balance(buyer, quote, Amount(700), Amount(300)), balance)

                    val preparation = checkNotNull(buyerOpeningReply.control?.funding)
                    assertEquals(preparation, stateAtApproval.preparations.single { it.opening.preparationId == funded.preparationId })
                    val comparisonScope = LedgerReconciliationScope(market, AssetId("BTC"), quote)
                    // 두 분개는 Archive에서 읽은 입력으로 이 시험 안에서 재구성한다.
                    val transactions =
                        listOf(
                            funded.transaction(preparation.ledgerTransactionId, preparation.occurredAt),
                            LedgerTransaction(
                                TradingIdentifiers.reserve(accepted.lineage, admissionSequence),
                                "RESERVE:${orderId.value}",
                                LedgerTransactionType.RESERVE,
                                Instant.ofEpochMilli(archived.inputs.single { it.position == approvalPosition }.timestamp),
                                listOf(
                                    LedgerPosting(funded.availableAccount, quote, LedgerPostingSide.DEBIT, reservationAmount),
                                    LedgerPosting(funded.holdAccount, quote, LedgerPostingSide.CREDIT, reservationAmount),
                                ),
                            ),
                        )
                    val activeHold = ReservationHold(market.value, orderId.value, buyer, quote, "ACTIVE", reservation.remainingAmount.value)
                    val reconciliation = LedgerReconciliation()
                    val comparison = LedgerReconciliationSnapshot(transactions, listOf(balance), listOf(activeHold))
                    val matched = reconciliation.compare(comparisonScope, comparison)
                    assertEquals(ReconciliationStatus.MATCHED, matched.status, matched.differences.toString())
                    val sameTotal = balance.copy(available = Amount(800), hold = Amount(200))
                    val wrongComparison = LedgerReconciliationSnapshot(transactions, listOf(sameTotal), listOf(activeHold))
                    val mismatched = reconciliation.compare(comparisonScope, wrongComparison)
                    assertEquals(ReconciliationStatus.MISMATCHED, mismatched.status)
                    assertEquals(
                        setOf(ReconciliationItem.AVAILABLE, ReconciliationItem.HOLD, ReconciliationItem.RESERVATION_HOLD),
                        mismatched.differences.map { it.item }.toSet(),
                    )
                }
        }
    }

    @Test
    fun `실제 Archive의 양쪽 snapshot 뒤 원본과 조회 ACK tail은 역할 재시작에 그대로 복원된다`() {
        withTradingNodes("query-test", sourceRestart = true) { harness ->
            for (user in listOf("seller", "buyer")) {
                assertEquals(
                    "ISSUED",
                    harness.funds.request(SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(UserId(user), 0, 0))).code,
                )
            }

            fun admit(
                user: String,
                side: Side,
                quantity: Long,
            ) {
                val intent = OrderIntent(MarketId("BTC-USDT"), 1, side, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(quantity))
                assertEquals(
                    "ACCEPTED",
                    harness.funds
                        .request(
                            SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Admit(RequestToken(UserId(user), 0, 1, 1), intent)),
                        ).code,
                )
            }

            admit("seller", Side.SELL, 3)
            await("첫 실제 결과의 자금 반영") {
                harness.funds.poll()
                harness.fundsSnapshot
                    .get()
                    ?.results
                    ?.streams
                    ?.single()
                    ?.resultThrough == 1L
            }
            assertEquals(
                0L,
                harness.fundsSnapshot
                    .get()
                    ?.queryAdmissions
                    ?.retiredThrough,
            )
            assertEquals(
                1,
                harness.fundsSnapshot
                    .get()
                    ?.queryAdmissions
                    ?.entries
                    ?.size,
            )
            OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { assertSourcePrefix(it, "BTC-USDT", 1, 0) }
            takeSnapshot(harness.fundsNode, harness.funds)
            takeSnapshot(harness.matchingNode, harness.funds)
            admit("buyer", Side.BUY, 1)
            await("snapshot 뒤 실제 결과의 자금 반영") {
                harness.funds.poll()
                harness.fundsSnapshot
                    .get()
                    ?.results
                    ?.streams
                    ?.single()
                    ?.resultThrough == 2L
            }
            val (expectedAdmission, expectedResult) =
                OrderQuerySourceClient(harness.fundsEndpoint, 10_000).use { funds ->
                    OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { matching ->
                        val admission = funds.request(OrderQuerySourceCommand.Read("query-test", null, 2))
                        val result = matching.request(OrderQuerySourceCommand.Read("query-test", "BTC-USDT", 2))
                        assertEquals("FACT", admission.code)
                        assertEquals("FACT", result.code)
                        // 신뢰하는 내부 소비자의 저장 확인을 실제 소스 복제 명령으로 확정한다.
                        assertEquals("ACKNOWLEDGED", funds.request(OrderQuerySourceCommand.Acknowledge("query-test", null, 1)).code)
                        assertEquals(
                            "ACKNOWLEDGED",
                            matching.request(OrderQuerySourceCommand.Acknowledge("query-test", "BTC-USDT", 1)).code,
                        )
                        admission to result
                    }
                }
            val expectedFinancial = checkNotNull(harness.fundsSnapshot.get()).financial
            harness.funds.close()
            harness.fundsNode.close()
            harness.matchingNode.close()
            assertTrue(harness.fundsNode.isClosed)
            assertTrue(harness.matchingNode.isClosed)
            val fundsRestored = AtomicBoolean()
            val matchingRestored = AtomicBoolean()
            val fundsRecovery = harness.fundsSettings.copy(transport = harness.fundsSettings.transport.copy(recoverExisting = true))
            val matchingRecovery =
                harness.matchingSettings.copy(transport = harness.matchingSettings.transport.copy(recoverExisting = true))
            val recoveredFunds =
                DevelopmentSplitTradingNode.start(
                    fundsRecovery,
                    serviceDecorator = { adapter ->
                        observeFunds(adapter, fundsRestored) { _, state -> harness.fundsSnapshot.set(state) }
                    },
                )
            recoveredFunds.use { fundsNode ->
                val recoveredMatching =
                    DevelopmentSplitTradingNode.start(
                        matchingRecovery,
                        serviceDecorator = { observeSnapshotRestore(it, matchingRestored) },
                    )
                recoveredMatching.use { matchingNode ->
                    await("실제 snapshot과 ACK tail의 역할 복원") { fundsNode.tradingReady && matchingNode.tradingReady }
                    assertTrue(fundsRestored.get(), "자금 역할은 실제 snapshot image를 읽어야 한다")
                    assertTrue(matchingRestored.get(), "매칭 역할은 실제 snapshot image를 읽어야 한다")
                    OrderQuerySourceClient(harness.fundsEndpoint, 10_000).use { funds ->
                        OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { matching ->
                            assertSourcePrefix(funds, null, 2, 1)
                            assertSourcePrefix(matching, "BTC-USDT", 2, 1)
                            assertEquals("PRUNED", funds.request(OrderQuerySourceCommand.Read("query-test", null, 1)).code)
                            assertEquals("PRUNED", matching.request(OrderQuerySourceCommand.Read("query-test", "BTC-USDT", 1)).code)
                            assertEquals(
                                expectedAdmission.admission,
                                funds.request(OrderQuerySourceCommand.Read("query-test", null, 2)).admission,
                            )
                            assertEquals(
                                expectedResult.result,
                                matching.request(OrderQuerySourceCommand.Read("query-test", "BTC-USDT", 2)).result,
                            )
                        }
                    }
                    val recovered = checkNotNull(harness.fundsSnapshot.get())
                    assertEquals(expectedFinancial.balances, recovered.financial.balances)
                    assertEquals(expectedFinancial.lastAdmissionSequence, recovered.financial.lastAdmissionSequence)
                    assertEquals(2L, checkNotNull(recovered.results).streams.single().resultThrough)
                    assertEquals(1L, checkNotNull(recovered.queryAdmissions).retiredThrough)
                    assertNull(fundsNode.error)
                    assertNull(matchingNode.error)
                    assertFalse(fundsNode.isClosed)
                    assertFalse(matchingNode.isClosed)
                }
                assertTrue(recoveredMatching.isClosed)
            }
            assertTrue(recoveredFunds.isClosed)
        }
    }

    @Test
    fun `같은 Archive와 PostgreSQL로 복구하면 지급부터 잔량 반환까지 대사한다`() {
        withTradingNodes("query-test", sourceRestart = true, persistSettlements = true) { harness ->
            val buyer = UserId("buyer")
            val seller = UserId("seller")
            val market = MarketId("BTC-USDT")
            for (user in listOf(buyer, seller)) {
                val rotate = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(user, 0, 0))
                assertEquals("ISSUED", harness.funds.request(rotate).code)
            }

            fun admit(
                user: UserId,
                side: Side,
                quantity: Long,
            ): AdmissionReceipt {
                val intent = OrderIntent(market, 1, side, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(quantity))
                val command = BoundedTradingCommand.Admit(RequestToken(user, 0, 1, 1), intent)
                val reply = harness.funds.request(SplitOrderDeliveryCommand.Control(command))
                assertEquals("ACCEPTED", reply.code)
                return checkNotNull(reply.control?.admission)
            }

            fun awaitSettlement(sequence: Long): FundsRoleRecoveryState {
                await("자금의 $sequence 번 매칭 결과 반영", { describeFunds(harness.fundsSnapshot.get()) }) {
                    harness.funds.poll()
                    val recovered = harness.fundsSnapshot.get()
                    val streams = recovered?.results?.streams
                    streams?.single()?.resultThrough == sequence
                }
                return checkNotNull(harness.fundsSnapshot.get())
            }

            val buy = admit(buyer, Side.BUY, 3)
            val buyOrderId = checkNotNull(buy.orderId)
            val reserved = awaitSettlement(1)
            val quote = AssetId("USDT")
            val reservedQuote = reserved.financial.balances.single { it.userId == buyer && it.assetId == quote }
            assertEquals(Balance(buyer, quote, Amount(700), Amount(300)), reservedQuote)

            // WAS 수집기가 시작되기 전의 미전달 분개를 스냅샷으로 남긴다.
            takeSnapshot(harness.fundsNode, harness.funds)
            takeSnapshot(harness.matchingNode, harness.funds)

            admit(seller, Side.SELL, 1)
            val partiallySettled = awaitSettlement(2)
            val buyerQuote = partiallySettled.financial.balances.single { it.userId == buyer && it.assetId == quote }
            assertEquals(Balance(buyer, quote, Amount(700), Amount(200)), buyerQuote)
            val restingOrder = partiallySettled.financial.liveOrders.single()
            assertEquals(Quantity(2), restingOrder.reservation.remainingQuantity)

            val cancel = SplitOrderDeliveryCommand.Cancel(RequestToken(buyer, 0, 1, 2), CancelIntent(market, buyOrderId, 1))
            assertEquals("ACCEPTED", harness.funds.request(cancel).code)
            val settled = awaitSettlement(3)
            assertFinalBalances(settled)
            assertTrue(settled.financial.liveOrders.isEmpty())

            val jdbc = harness.database.jdbc()

            fun readLedger() =
                jdbc.queryForList(
                    "select sequence, log_position, transactions::text as transactions " +
                        "from order_query.funds_ledger_batch order by sequence",
                )

            fun verifyReady(context: ConfigurableApplicationContext): Long {
                val ready = awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.READY)
                assertNull(ready.reason)
                assertEquals(6L, ready.ledgerThrough)
                return checkNotNull(ready.logPosition)
            }

            fun verifyBlocked(
                gatewayRoot: String,
                reason: OrderQueryRecoveryFailure,
            ) {
                queryWas(harness, gatewayRoot = gatewayRoot).use { context ->
                    val blocked = awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.BLOCKED)
                    assertEquals(reason, blocked.reason)
                }
            }

            val originalLedger =
                queryWas(harness, gatewayRoot = "recovery-before-restart").use { context ->
                    val verifiedPosition = verifyReady(context)
                    val persisted = readLedger()
                    assertEquals((1L..6L).toList(), persisted.map { it.getValue("sequence") })
                    val positions = persisted.map { it.getValue("log_position") as Long }
                    assertTrue(positions.zipWithNext().all { (before, after) -> before > 0L && before < after })
                    assertTrue(verifiedPosition >= positions.last())
                    val transactions = persisted.flatMap { row -> readRecoveryTransactions(row.getValue("transactions") as String) }
                    assertRecoveryLedgerTransactions(transactions)
                    OrderQuerySourceClient(harness.fundsEndpoint, 10_000).use { source ->
                        await("PostgreSQL 저장 뒤 원장 ACK 회수") {
                            source.request(OrderQuerySourceCommand.Describe).ledgerProgress?.retiredThrough == 6L
                        }
                    }
                    persisted
                }

            harness.funds.close()
            harness.fundsNode.close()
            harness.matchingNode.close()

            withRestartedTradingNodes(harness) {
                queryWas(harness, gatewayRoot = "recovery-query").use { context ->
                    verifyReady(context)
                    assertEquals(originalLedger, readLedger(), "재시작으로 커밋된 분개를 다시 쓰면 안 된다")
                }

                // 복구 응답의 판정 경계만 시험하면서 실제 Archive·DB와 총 자금은 유지한다.
                try {
                    harness.sourceReplies.rewriteRecovery.set { state ->
                        val balances =
                            state.balances.map { balance ->
                                if (balance.userId == buyer && balance.assetId == quote) {
                                    balance.copy(available = Amount(800), hold = Amount(100))
                                } else {
                                    balance
                                }
                            }
                        state.copy(balances = balances)
                    }
                    verifyBlocked("recovery-balance-mismatch", OrderQueryRecoveryFailure.BALANCE_MISMATCH)

                    harness.sourceReplies.rewriteRecovery.set { state ->
                        val orphan = OrderQueryRecoveryOrder(buyOrderId, buyer, Quantity(1), quote, Amount(100))
                        state.copy(orders = listOf(orphan))
                    }
                    verifyBlocked("recovery-orphan-order", OrderQueryRecoveryFailure.ORDERS_MISMATCH)
                } finally {
                    harness.sourceReplies.rewriteRecovery.set(null)
                }

                val restored = checkNotNull(harness.fundsSnapshot.get())
                assertFinalBalances(restored)
                assertTrue(restored.financial.liveOrders.isEmpty())
            }

            // 차변·대변은 서로 맞지만 실제 반환 200과 다른 199를 저장한다.
            val updateReleaseAmounts =
                """
                update order_query.funds_ledger_batch
                   set transactions = jsonb_set(
                       jsonb_set(transactions, '{0,postings,0,amount}', cast(? as jsonb)),
                       '{0,postings,1,amount}', cast(? as jsonb)
                   )
                 where sequence = 6
                """.trimIndent()
            assertEquals(1, jdbc.update(updateReleaseAmounts, "199", "199"))
            val corruptedLedger = readLedger()
            withRestartedTradingNodes(harness) {
                verifyBlocked("recovery-corrupt-query", OrderQueryRecoveryFailure.LEDGER_MISMATCH)
                assertEquals(corruptedLedger, readLedger(), "원장 대사는 손상된 분개를 자동 수정하면 안 된다")
                val restored = checkNotNull(harness.fundsSnapshot.get())
                assertFinalBalances(restored)
                assertTrue(restored.financial.liveOrders.isEmpty())

                assertEquals(1, jdbc.update(updateReleaseAmounts, "200.125", "200.125"))
                verifyBlocked("recovery-fractional-ledger", OrderQueryRecoveryFailure.LEDGER_MISMATCH)

                assertEquals(1, jdbc.update("update order_query.funds_ledger_batch set transactions = '[{}]'::jsonb where sequence = 6"))
                verifyBlocked("recovery-malformed-ledger", OrderQueryRecoveryFailure.LEDGER_MISMATCH)

                assertEquals(1, jdbc.update("delete from order_query.funds_ledger_batch where sequence = 6"))
                verifyBlocked("recovery-missing-ledger", OrderQueryRecoveryFailure.LEDGER_INCOMPLETE)

                // 실제 DB에서 원장 읽기가 실패해도 금액 불일치로 분류하면 안 된다.
                jdbc.execute("alter table order_query.funds_ledger_batch rename to temporarily_unreadable_ledger")
                try {
                    verifyBlocked("recovery-unreadable-ledger", OrderQueryRecoveryFailure.DB_UNAVAILABLE)
                } finally {
                    jdbc.execute("alter table order_query.temporarily_unreadable_ledger rename to funds_ledger_batch")
                }
            }
        }
    }

    @Test
    fun `원장 두 번째 저장에 실패하면 묶음을 롤백하고 커밋한 뒤 ACK한다`() {
        withTradingNodes("query-test", persistSettlements = true) { harness ->
            val jdbc = harness.database.jdbc()
            queryWas(harness, decorate = { store ->
                jdbc.execute(
                    """
                    create function order_query.reject_ledger_middle() returns trigger language plpgsql as '
                        begin
                            raise exception ''시험 원장 저장 충돌'' using errcode = ''40001'';
                        end;
                    '
                    """.trimIndent(),
                )
                jdbc.execute(
                    """
                    create trigger reject_ledger_middle before insert on order_query.funds_ledger_batch
                    for each row when (new.sequence = 2) execute function order_query.reject_ledger_middle()
                    """.trimIndent(),
                )
                store
            }).use { context ->
                try {
                    val blocked = awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.BLOCKED)
                    assertEquals(OrderQueryRecoveryFailure.DB_UNAVAILABLE, blocked.reason)
                    assertTrue(storedLedgerRows(harness).isEmpty())
                    assertEquals(0L, harness.retiredLedgerThrough())
                    assertEquals(0, harness.sourceReplies.ledgerAcknowledgements.get())
                } finally {
                    jdbc.execute("drop trigger reject_ledger_middle on order_query.funds_ledger_batch")
                    jdbc.execute("drop function order_query.reject_ledger_middle()")
                }

                assertEquals(2L, awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.READY).ledgerThrough)
                assertEquals(listOf(1L, 2L), storedLedgerRows(harness).map { it.getValue("sequence") })
                assertEquals(2L, harness.retiredLedgerThrough())
                assertEquals(1, harness.sourceReplies.ledgerAcknowledgements.get())
            }
        }
    }

    @Test
    fun `원장 커밋 응답을 잃으면 저장된 묶음을 다시 대조한 뒤 한 번만 ACK한다`() {
        withTradingNodes("query-test", persistSettlements = true) { harness ->
            val firstCommit = CountDownLatch(1)
            val storedPages = AtomicInteger()
            val sequences = listOf(1L, 2L)
            val replies = harness.sourceReplies

            try {
                queryWas(harness, decorate = { store ->
                    object : OrderQueryFeedStore by store {
                        override fun applyLedgerPage(batches: List<FundsLedgerBatch>): Long {
                            assertEquals(sequences, batches.map { it.sequence })
                            val through = store.applyLedgerPage(batches)
                            if (storedPages.incrementAndGet() == 1) {
                                replies.pauseDescriptions.set(true)
                                firstCommit.countDown()
                                throw IllegalStateException("시험에서 커밋 완료 응답을 유실한다")
                            }
                            return through
                        }
                    }
                }).use { context ->
                    assertTrue(firstCommit.await(10, TimeUnit.SECONDS), "두 지급 분개가 ACK 전에 커밋되어야 한다")
                    val savedRows = storedLedgerRows(harness)
                    assertEquals(sequences, savedRows.map { it.getValue("sequence") })
                    assertEquals(0L, harness.retiredLedgerThrough())
                    assertEquals(0, replies.ledgerAcknowledgements.get())

                    replies.pauseDescriptions.set(false)
                    assertEquals(2L, awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.READY).ledgerThrough)
                    assertEquals(2, storedPages.get())
                    assertEquals(2L, harness.retiredLedgerThrough())
                    assertEquals(1, replies.ledgerAcknowledgements.get())
                    assertEquals(savedRows, storedLedgerRows(harness))
                }
            } finally {
                replies.pauseDescriptions.set(false)
            }
        }
    }

    @Test
    fun `원장 ACK 응답을 잃으면 회수 순번을 읽고 다음 분개를 처리한다`() {
        withTradingNodes("query-test", persistSettlements = true) { harness ->
            val replies = harness.sourceReplies
            replies.dropNextLedgerAcknowledgement.set(true)

            try {
                queryWas(harness).use { context ->
                    assertTrue(replies.dropped.await(10, TimeUnit.SECONDS), "원장 회수 뒤 ACK 응답이 유실되어야 한다")
                    val savedRows = storedLedgerRows(harness)
                    assertEquals(listOf(1L, 2L), savedRows.map { it.getValue("sequence") })
                    assertEquals(2L, harness.retiredLedgerThrough())
                    assertEquals(1, replies.ledgerAcknowledgements.get())

                    replies.pauseDescriptions.set(false)
                    assertEquals(2L, awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.READY).ledgerThrough)
                    val opening = OpeningBalance("opening-late", UserId("late"), AssetId("USDT"), Amount(1))
                    val payload = DevelopmentBalanceCommandPayload(opening, "ledger-opening-late", Instant.EPOCH)
                    val command = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Funding(payload))
                    assertEquals("PREPARED", harness.funds.request(command).code)
                    await("후속 원장 3번 회수") { harness.retiredLedgerThrough() == 3L }

                    val updatedRows = storedLedgerRows(harness)
                    assertEquals(listOf(1L, 2L, 3L), updatedRows.map { it.getValue("sequence") })
                    assertEquals(savedRows, updatedRows.take(2))
                    assertEquals(2, replies.ledgerAcknowledgements.get())
                }
            } finally {
                replies.pauseDescriptions.set(false)
            }
        }
    }

    private fun TradingHarness.retiredLedgerThrough(): Long? {
        val snapshot = fundsSnapshot.get()
        return snapshot?.ledger?.retiredThrough
    }

    private fun storedLedgerRows(harness: TradingHarness): List<Map<String, Any?>> = storedLedgerRows(harness.database.jdbc())

    private fun storedLedgerRows(jdbc: JdbcTemplate): List<Map<String, Any?>> =
        jdbc.queryForList(
            "select sequence, log_position, transactions::text from order_query.funds_ledger_batch order by sequence",
        )

    @Test
    fun `한 Aeron 정산 묶음의 두 체결은 재전송과 재시작 뒤에도 원장에 한 번씩 남는다`() {
        withTradingNodes("query-test", sourceRestart = true, persistSettlements = true) { harness ->
            val market = MarketId("BTC-USDT")
            val buyer = UserId("buyer")
            val seller = UserId("seller")
            for (user in listOf(buyer, seller)) {
                val rotate = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(user, 0, 0))
                assertEquals("ISSUED", harness.funds.request(rotate).code)
            }

            fun admit(
                user: UserId,
                side: Side,
                quantity: Long,
                sequence: Long,
            ) {
                val intent = OrderIntent(market, 1, side, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(quantity))
                val command = BoundedTradingCommand.Admit(RequestToken(user, 0, 1, sequence), intent)
                assertEquals("ACCEPTED", harness.funds.request(SplitOrderDeliveryCommand.Control(command)).code)
            }

            admit(seller, Side.SELL, 3, 1)
            await("매도 대기 결과의 자금 적용") {
                harness.funds.poll()
                val streams =
                    harness.fundsSnapshot
                        .get()
                        ?.results
                        ?.streams
                streams?.single()?.resultThrough == 1L
            }
            harness.settlementGate.paused.set(true)
            admit(buyer, Side.BUY, 1, 1)
            admit(buyer, Side.BUY, 1, 2)

            val results =
                OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { matching ->
                    await("두 매수 체결의 매칭 로그 반영") {
                        harness.funds.poll()
                        matching.request(OrderQuerySourceCommand.DescribeRecovery("query-test")).recovery?.resultThrough == 3L
                    }
                    (2L..3L).map { sequence ->
                        checkNotNull(matching.request(OrderQuerySourceCommand.Read("query-test", market.value, sequence)).result)
                    }
                }
            val pending =
                harness.fundsSnapshot
                    .get()
                    ?.results
                    ?.streams
            assertEquals(1L, pending?.single()?.resultThrough)
            takeSnapshot(harness.fundsNode, harness.funds)
            takeSnapshot(harness.matchingNode, harness.funds)

            val command = SplitResultDeliveryCommand.ApplyBatch(results)
            val applied =
                DevelopmentSplitResultDeliveryClient(harness.fundsEndpoint, 10_000).use { funds ->
                    val reply = funds.request(command)
                    assertEquals("APPLIED", reply.code)
                    assertEquals(3L, reply.ack?.through)
                    val repeated = funds.request(command)
                    assertEquals("DUPLICATE", repeated.code)
                    assertEquals(reply.ack, repeated.ack)
                    reply
                }
            DevelopmentSplitResultDeliveryClient(harness.matchingEndpoint, 10_000).use { matching ->
                assertEquals("ACKNOWLEDGED", matching.request(SplitResultDeliveryCommand.RecordAck(checkNotNull(applied.ack))).code)
            }
            val beforeRestart = checkNotNull(harness.fundsSnapshot.get())
            val expectedBalance = Balance(buyer, AssetId("USDT"), Amount(800), Amount.ZERO)
            assertTrue(expectedBalance in beforeRestart.financial.balances)

            harness.funds.close()
            harness.fundsNode.close()
            harness.matchingNode.close()
            withRestartedTradingNodes(harness) {
                assertEquals(beforeRestart.financial.balances, checkNotNull(harness.fundsSnapshot.get()).financial.balances)
                queryWas(harness, gatewayRoot = "batch-ledger", requireRecoveryReady = true).use { context ->
                    assertEquals(7L, awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.READY).ledgerThrough)
                    val rows = storedLedgerRows(harness)
                    assertEquals((1L..7L).toList(), rows.map { it.getValue("sequence") })
                    val settlements = rows.takeLast(2)
                    val positions = settlements.map { it.getValue("log_position") }
                    assertEquals(listOf(applied.appliedLogPosition, applied.appliedLogPosition), positions)
                    val transactions = settlements.flatMap { readRecoveryTransactions(it.getValue("transactions") as String) }
                    val types = transactions.map { it.transactionType }
                    assertEquals(listOf(LedgerTransactionType.SETTLEMENT, LedgerTransactionType.SETTLEMENT), types)
                    assertEquals(2, transactions.map { it.ledgerTransactionId }.toSet().size)
                }
            }
        }
    }

    @Test
    fun `원장 ACK 전에 DB 첫 기록을 잃으면 마지막 순번이 남아 있어도 회수하지 않는다`() {
        withTradingNodes("query-test", persistSettlements = true) { harness ->
            val gate = CallbackGate()
            val jdbc = harness.database.jdbc()

            fun storedSequences() =
                jdbc.queryForList("select sequence from order_query.funds_ledger_batch order by sequence", Long::class.java)

            try {
                queryWas(harness, gatewayRoot = "ledger-ack-lost", decorate = { store ->
                    object : OrderQueryFeedStore by store {
                        override fun applyLedgerPage(batches: List<FundsLedgerBatch>): Long {
                            val committed = store.applyLedgerPage(batches)
                            if (committed == 2L) gate.pause()
                            return committed
                        }
                    }
                }).use {
                    assertTrue(gate.reached.await(10, TimeUnit.SECONDS), "두 지급 기록이 커밋된 뒤 ACK 전에서 멈춰야 한다")
                    assertEquals(listOf(1L, 2L), storedSequences())
                    OrderQuerySourceClient(harness.fundsEndpoint, 10_000).use { funds ->
                        val progress = checkNotNull(funds.request(OrderQuerySourceCommand.Describe).ledgerProgress)
                        assertEquals(2L, progress.issued)
                        assertEquals(0L, progress.retiredThrough)
                    }
                }
            } finally {
                gate.release.countDown()
            }

            assertEquals(1, jdbc.update("delete from order_query.funds_ledger_batch where sequence = 1"))
            assertEquals(listOf(2L), storedSequences())
            queryWas(harness, gatewayRoot = "ledger-ack-gap").use { context ->
                val blocked = awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.BLOCKED)
                assertEquals(OrderQueryRecoveryFailure.LEDGER_INCOMPLETE, blocked.reason)
                assertEquals(listOf(2L), storedSequences())
                OrderQuerySourceClient(harness.fundsEndpoint, 10_000).use { funds ->
                    val progress = checkNotNull(funds.request(OrderQuerySourceCommand.Describe).ledgerProgress)
                    assertEquals(0L, progress.retiredThrough)
                    val original = funds.request(OrderQuerySourceCommand.ReadLedger("query-test", 1))
                    assertEquals("FACT", original.code)
                    assertEquals(1L, original.ledgerBatch?.sequence)
                }
            }
        }
    }

    @Test
    fun `회수된 원장의 중간 기록이 없으면 새로 저장할 분개도 회수하지 않는다`() {
        withTradingNodes("query-test", persistSettlements = true) { harness ->
            queryWas(harness, gatewayRoot = "retired-ledger-prefix").use { context ->
                val ready = awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.READY)
                assertEquals(2L, ready.ledgerThrough)
                OrderQuerySourceClient(harness.fundsEndpoint, 10_000).use { funds ->
                    await("첫 두 지급의 원장 회수") {
                        funds.request(OrderQuerySourceCommand.Describe).ledgerProgress?.retiredThrough == 2L
                    }
                }
            }

            val opening = OpeningBalance("opening-late", UserId("late"), AssetId("USDT"), Amount(1))
            val payload = DevelopmentBalanceCommandPayload(opening, "ledger-opening-late", Instant.EPOCH)
            val command = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Funding(payload))
            SplitOrderDeliveryClient(harness.fundsEndpoint, 10_000).use { writer ->
                assertEquals("PREPARED", writer.request(command).code)
            }
            val jdbc = harness.database.jdbc()
            assertEquals(1, jdbc.update("delete from order_query.funds_ledger_batch where sequence = 1"))

            queryWas(harness, gatewayRoot = "retired-ledger-gap").use { context ->
                val blocked = awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.BLOCKED)
                assertEquals(OrderQueryRecoveryFailure.LEDGER_INCOMPLETE, blocked.reason)
                val persisted =
                    jdbc.queryForList("select sequence from order_query.funds_ledger_batch order by sequence", Long::class.java)
                assertEquals(listOf(2L), persisted)
                OrderQuerySourceClient(harness.fundsEndpoint, 10_000).use { funds ->
                    val progress = checkNotNull(funds.request(OrderQuerySourceCommand.Describe).ledgerProgress)
                    assertEquals(3L, progress.issued)
                    assertEquals(2L, progress.retiredThrough)
                    val retained = funds.request(OrderQuerySourceCommand.ReadLedger("query-test", 3))
                    assertEquals("FACT", retained.code)
                    assertEquals(3L, retained.ledgerBatch?.sequence)
                }
            }
        }
    }

    @Test
    fun `복구 중 HTTP 주문과 취소를 막고 대사 완료 뒤 같은 토큰으로 한 번씩 접수한다`() {
        withTradingNodes("query-test", sourceRestart = true, persistSettlements = true) { harness ->
            val buyer = UserId("buyer")
            val quote = AssetId("USDT")
            for (user in listOf(buyer, UserId("seller"))) {
                val rotate = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(user, 0, 0))
                assertEquals("ISSUED", harness.funds.request(rotate).code)
            }
            takeSnapshot(harness.fundsNode, harness.funds)
            takeSnapshot(harness.matchingNode, harness.funds)

            harness.settlementGate.paused.set(true)
            val intent = OrderIntent(MarketId("BTC-USDT"), 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(3))
            val command = BoundedTradingCommand.Admit(RequestToken(buyer, 0, 1, 1), intent)
            val accepted = harness.funds.request(SplitOrderDeliveryCommand.Control(command))
            assertEquals("ACCEPTED", accepted.code)
            val orderId = checkNotNull(accepted.control?.admission?.orderId)
            OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { matching ->
                await("매칭은 결과를 확정했지만 자금에는 아직 미전달") {
                    harness.funds.poll()
                    val state = matching.request(OrderQuerySourceCommand.DescribeRecovery("query-test"))
                    state.recovery?.resultThrough == 1L
                }
            }
            val beforeRestart = checkNotNull(harness.fundsSnapshot.get())
            assertEquals(0L, checkNotNull(beforeRestart.results).streams.single().resultThrough)

            harness.funds.close()
            harness.fundsNode.close()
            harness.matchingNode.close()

            withRestartedTradingNodes(harness, expectedResultThrough = 0) {
                queryWas(harness, gatewayRoot = "recovery-pending-result", requireRecoveryReady = true).use { context ->
                    val worker = context.getBean(OrderQueryFeedWorker::class.java)
                    val jdbc = harness.database.jdbc()
                    await("원장 저장과 미완료 결과의 재개 판정 요청") {
                        val stored = jdbc.queryForObject("select count(*) from order_query.funds_ledger_batch", Long::class.java)
                        stored == 3L && harness.sourceReplies.recoveryDescriptions.get() >= 2
                    }
                    assertEquals(OrderQueryRecoveryStatus.RECOVERING, worker.recoveryVerdict.status)
                    val pending = checkNotNull(harness.fundsSnapshot.get())
                    assertEquals(0L, checkNotNull(pending.results).streams.single().resultThrough)

                    val url = wasUrl(context)
                    HttpClient.newHttpClient().use { http ->
                        assertRecoveryRequestsBlocked(harness, http, url, orderId, "RECOVERY_IN_PROGRESS")

                        harness.settlementGate.paused.set(false)
                        val ready = awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.READY)
                        assertEquals(3L, ready.ledgerThrough)
                        val recovered = checkNotNull(harness.fundsSnapshot.get())
                        assertEquals(1L, checkNotNull(recovered.results).streams.single().resultThrough)
                        val balance = recovered.financial.balances.single { it.userId == buyer && it.assetId == quote }
                        assertEquals(Balance(buyer, quote, Amount(700), Amount(300)), balance)
                        val recoveredOrder = recovered.financial.liveOrders.single()
                        val reservation = recoveredOrder.reservation
                        assertEquals(orderId, reservation.orderId)
                        assertEquals(Amount(300), reservation.remainingAmount)
                        assertEquals(Quantity(3), reservation.remainingQuantity)

                        val sell = postRecoverySell(http, url)
                        assertEquals(202, sell.statusCode(), sell.body())
                        val sellId = OrderId(json.readTree(sell.body()).path("orderId").asString())
                        val cancel = postRecoveryCancel(http, url, orderId)
                        assertEquals(202, cancel.statusCode(), cancel.body())
                        val cancelId = json.readTree(cancel.body()).path("cancelId").asString()
                        assertEquals(TradingIdentifiers.cancel(lineage, 1), cancelId)

                        val repeatedSell = postRecoverySell(http, url)
                        assertEquals(202, repeatedSell.statusCode(), repeatedSell.body())
                        assertEquals(sellId.value, json.readTree(repeatedSell.body()).path("orderId").asString())
                        val repeatedCancel = postRecoveryCancel(http, url, orderId)
                        assertEquals(202, repeatedCancel.statusCode(), repeatedCancel.body())
                        assertEquals(cancelId, json.readTree(repeatedCancel.body()).path("cancelId").asString())

                        await("매수 예약 반환과 매도 신규 예약 분개 저장") {
                            val results = harness.fundsSnapshot.get()?.results
                            val resultThrough = results?.streams?.single()?.resultThrough
                            val count = jdbc.queryForObject("select count(*) from order_query.funds_ledger_batch", Long::class.java)
                            resultThrough == 3L && count == 5L
                        }
                        val settled = checkNotNull(harness.fundsSnapshot.get())
                        val balances = settled.financial.balances.associateBy { it.userId to it.assetId }
                        val seller = UserId("seller")
                        val btc = AssetId("BTC")
                        assertEquals(Balance(buyer, quote, Amount(1_000), Amount(0)), balances[buyer to quote])
                        assertEquals(Balance(seller, btc, Amount(2), Amount(1)), balances[seller to btc])
                        assertEquals(2L, settled.financial.lastAdmissionSequence)
                        assertEquals(1L, settled.lastCancellationSequence)
                        val dispatch = settled.dispatch.streams.single()
                        val liveOrder = settled.financial.liveOrders.single()
                        assertEquals(3L, dispatch.issued)
                        assertEquals(sellId, liveOrder.reservation.orderId)
                        OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { matching ->
                            val description = matching.request(OrderQuerySourceCommand.DescribeRecovery("query-test"))
                            val recoveredBook = checkNotNull(description.recovery)
                            assertEquals(listOf(OrderQueryRecoveryOrder(sellId, seller, Quantity(1))), recoveredBook.orders)
                        }
                        val transactions =
                            jdbc
                                .queryForList(
                                    "select transactions::text from order_query.funds_ledger_batch order by sequence",
                                    String::class.java,
                                ).flatMap { readRecoveryTransactions(checkNotNull(it)) }
                        assertEquals(
                            listOf(
                                LedgerTransactionType.OPENING,
                                LedgerTransactionType.OPENING,
                                LedgerTransactionType.RESERVE,
                                LedgerTransactionType.RESERVE,
                                LedgerTransactionType.RELEASE,
                            ),
                            transactions.map { it.transactionType },
                        )
                        assertEquals(listOf(Amount(1), Amount(1)), transactions[3].postings.map { it.amount })
                        assertEquals(listOf(Amount(300), Amount(300)), transactions[4].postings.map { it.amount })
                    }
                }
            }
        }
    }

    @Test
    fun `원장 불일치면 재시작 뒤에도 HTTP 주문과 취소가 자금에 적용되지 않는다`() {
        withTradingNodes("query-test", sourceRestart = true, persistSettlements = true) { harness ->
            val seller = UserId("seller")
            val buyer = UserId("buyer")
            for (user in listOf(buyer, seller)) {
                val rotate = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(user, 0, 0))
                assertEquals("ISSUED", harness.funds.request(rotate).code)
            }
            val intent = OrderIntent(MarketId("BTC-USDT"), 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(3))
            val command = BoundedTradingCommand.Admit(RequestToken(buyer, 0, 1, 1), intent)
            val accepted = harness.funds.request(SplitOrderDeliveryCommand.Control(command))
            assertEquals("ACCEPTED", accepted.code)
            val orderId = checkNotNull(accepted.control?.admission?.orderId)

            queryWas(harness, gatewayRoot = "recovery-before-corruption", requireRecoveryReady = true).use { context ->
                val ready = awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.READY)
                assertEquals(3L, ready.ledgerThrough)
                OrderQuerySourceClient(harness.fundsEndpoint, 10_000).use { funds ->
                    await("원장 3번 저장 뒤 ACK 확정") {
                        funds.request(OrderQuerySourceCommand.Describe).ledgerProgress?.retiredThrough == 3L
                    }
                }
                takeSnapshot(harness.fundsNode, harness.funds)
                takeSnapshot(harness.matchingNode, harness.funds)
            }

            val jdbc = harness.database.jdbc()
            // 균형은 맞지만 자금 엔진에 기록된 예약 300과 다른 금액을 저장한다.
            val updateReserveAmounts =
                """
                update order_query.funds_ledger_batch
                   set transactions = jsonb_set(
                       jsonb_set(transactions, '{0,postings,0,amount}', '299'::jsonb),
                       '{0,postings,1,amount}', '299'::jsonb
                   )
                 where sequence = 3
                """.trimIndent()
            assertEquals(1, jdbc.update(updateReserveAmounts))
            val readLedger = "select sequence, log_position, transactions::text from order_query.funds_ledger_batch order by sequence"
            val corruptedLedger = jdbc.queryForList(readLedger)
            assertEquals(3, corruptedLedger.size)

            harness.funds.close()
            harness.fundsNode.close()
            harness.matchingNode.close()
            withRestartedTradingNodes(harness, expectedResultThrough = 1) {
                repeat(2) { restart ->
                    queryWas(harness, gatewayRoot = "recovery-blocked-$restart", requireRecoveryReady = true).use { context ->
                        val blocked = awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.BLOCKED)
                        assertEquals(OrderQueryRecoveryFailure.LEDGER_MISMATCH, blocked.reason)
                        HttpClient.newHttpClient().use { http ->
                            assertRecoveryRequestsBlocked(harness, http, wasUrl(context), orderId, "RECOVERY_BLOCKED")
                        }
                        assertEquals(corruptedLedger, jdbc.queryForList(readLedger))
                    }
                }
            }
        }
    }

    @Test
    fun `복구 보호에는 수집기가 필요하고 원본이 복구를 지원하지 않으면 HTTP 주문을 막는다`() {
        withTradingNodes("query-test") { harness ->
            val failure =
                assertFailsWith<BeanCreationException> {
                    val arguments = wasArguments(harness, gatewayRoot = "recovery-no-consumer", requireRecoveryReady = true)
                    SpringApplicationBuilder(ExchangeCoreApplication::class.java).run(*arguments).use {}
                }
            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("exchange.order-query.consumer-id") == true },
                failure.message,
            )

            val seller = UserId("seller")
            val rotate = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(seller, 0, 0))
            assertEquals("ISSUED", harness.funds.request(rotate).code)
            queryWas(harness, gatewayRoot = "recovery-scope-unsupported", requireRecoveryReady = true).use { context ->
                val blocked = awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.BLOCKED)
                assertEquals(OrderQueryRecoveryFailure.SCOPE_UNSUPPORTED, blocked.reason)
                HttpClient.newHttpClient().use { http ->
                    val response = postRecoverySell(http, wasUrl(context))
                    assertEquals(503, response.statusCode(), response.body())
                    val body = json.readTree(response.body())
                    assertEquals("NOT_SUBMITTED", body.path("status").asString(), response.body())
                    assertEquals("RECOVERY_BLOCKED", body.path("reason").asString(), response.body())
                }
                val saved = checkNotNull(harness.fundsSnapshot.get())
                assertEquals(0L, saved.financial.lastAdmissionSequence)
                assertTrue(saved.financial.liveOrders.isEmpty())
            }
        }
    }

    private fun assertRecoveryRequestsBlocked(
        harness: TradingHarness,
        http: HttpClient,
        url: String,
        orderId: OrderId,
        reason: String,
    ) {
        val responses = listOf(postRecoverySell(http, url), postRecoveryCancel(http, url, orderId))
        for ((index, response) in responses.withIndex()) {
            assertEquals(503, response.statusCode(), response.body())
            val body = json.readTree(response.body())
            assertEquals("NOT_SUBMITTED", body.path("status").asString(), response.body())
            assertEquals(reason, body.path("reason").asString(), response.body())
            assertEquals(1L, body.path("epoch").longValue(), response.body())
            assertEquals(0, body.path("channel").intValue(), response.body())
            assertEquals(if (index == 0) 1L else 2L, body.path("sequence").longValue(), response.body())
            val resultId = body.path(if (index == 0) "orderId" else "cancelId")
            assertTrue(resultId.isMissingNode || resultId.isNull, response.body())
        }
        OrderQuerySourceClient(harness.fundsEndpoint, 10_000).use { funds ->
            val progress = checkNotNull(funds.request(OrderQuerySourceCommand.Describe).ledgerProgress)
            assertEquals(3L, progress.issued)
        }
        val saved = checkNotNull(harness.fundsSnapshot.get())
        val buyer = UserId("buyer")
        val seller = UserId("seller")
        val quote = AssetId("USDT")
        val btc = AssetId("BTC")
        val balances = saved.financial.balances.associateBy { it.userId to it.assetId }
        assertEquals(Balance(buyer, quote, Amount(700), Amount(300)), balances[buyer to quote])
        assertEquals(Balance(seller, btc, Amount(3), Amount(0)), balances[seller to btc])
        assertEquals(1L, saved.financial.lastAdmissionSequence)
        assertEquals(0L, saved.lastCancellationSequence)
        val dispatch = saved.dispatch.streams.single()
        val liveOrder = saved.financial.liveOrders.single()
        assertEquals(1L, dispatch.issued)
        assertEquals(orderId, liveOrder.reservation.orderId)
        val inbox = saved.financial.inbox
        val channels = inbox.channels.associateBy { it.userId }
        assertEquals(0L, channels.getValue(seller).processedThrough)
        assertEquals(1L, channels.getValue(buyer).processedThrough)
        assertTrue(channels.getValue(buyer).cancelEntries.isEmpty())
        OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { matching ->
            val restoredBook = checkNotNull(matching.request(OrderQuerySourceCommand.DescribeRecovery("query-test")).recovery)
            assertEquals(listOf(OrderQueryRecoveryOrder(orderId, buyer, Quantity(3))), restoredBook.orders)
        }
    }

    private fun postRecoverySell(
        http: HttpClient,
        url: String,
    ) = post(http, url, "/markets/BTC-USDT/orders", orderBody("seller", "SELL", 1, price = 101))

    private fun postRecoveryCancel(
        http: HttpClient,
        url: String,
        orderId: OrderId,
    ) = post(
        http,
        url,
        "/markets/BTC-USDT/orders/${orderId.value}/cancel",
        """{"userId":"buyer","channel":0,"epoch":1,"sequence":2,"expectedConfigurationVersion":1}""",
    )

    private fun awaitRecoveryVerdict(
        context: ConfigurableApplicationContext,
        expected: OrderQueryRecoveryStatus,
    ): OrderQueryRecoveryVerdict {
        val worker = context.getBean(OrderQueryFeedWorker::class.java)
        var latest = worker.recoveryVerdict
        await("원장 대사 상태 $expected", { "마지막 대사 결과 $latest" }) {
            latest = worker.recoveryVerdict
            latest.status == expected
        }
        return latest
    }

    @Test
    fun `원장 대사는 잔고가 512개를 넘어도 다음 페이지까지 읽고 재개한다`() {
        val pageSize = OrderQuerySourceCodec.RECOVERY_PAGE_ROWS
        val expectedAccounts = pageSize + 1
        withTradingNodes("query-test", persistSettlements = true, maxFundingAccounts = 520) { harness ->
            val quote = AssetId("USDT")
            // 공통 준비의 두 지급을 포함해 잔고 한 페이지와 마지막 한 건을 만든다.
            for (index in 1 until pageSize) {
                val user = UserId("recovery-page-$index")
                val opening = OpeningBalance("opening-${user.value}", user, quote, Amount(1))
                val payload = DevelopmentBalanceCommandPayload(opening, "ledger-${user.value}", Instant.EPOCH)
                val command = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Funding(payload))
                assertEquals("PREPARED", harness.funds.request(command).code)
            }
            // 지급 응답을 받은 뒤에도 테스트 관찰용 사본은 갱신 중일 수 있다.
            await(
                "지급 이후 잔고 ${expectedAccounts}건 관측",
                { "관측 잔고 수=${harness.fundsSnapshot.get()?.financial?.balances?.size}" },
            ) {
                val financial = harness.fundsSnapshot.get()?.financial
                financial?.balances?.size == expectedAccounts
            }

            queryWas(harness, gatewayRoot = "recovery-balance-pages").use { context ->
                val ready = awaitRecoveryVerdict(context, OrderQueryRecoveryStatus.READY)
                assertEquals(expectedAccounts.toLong(), ready.ledgerThrough)
                assertTrue(harness.sourceReplies.recoverySuffixRead.get())
                val jdbc = harness.database.jdbc()
                val stored = jdbc.queryForObject("select count(*) from order_query.funds_ledger_batch", Long::class.java)
                assertEquals(expectedAccounts.toLong(), stored)
            }
        }
    }

    private fun withRestartedTradingNodes(
        harness: TradingHarness,
        expectedResultThrough: Long = 3L,
        verify: () -> Unit,
    ) {
        harness.fundsSnapshot.set(null)
        val fundsRestored = AtomicBoolean()
        val matchingRestored = AtomicBoolean()
        val fundsSettings = harness.fundsSettings.copy(transport = harness.fundsSettings.transport.copy(recoverExisting = true))
        val matchingSettings = harness.matchingSettings.copy(transport = harness.matchingSettings.transport.copy(recoverExisting = true))
        val fundsNode =
            DevelopmentSplitTradingNode.start(
                fundsSettings,
                serviceDecorator = { adapter ->
                    val observed = observeFunds(adapter, fundsRestored) { _, state -> harness.fundsSnapshot.set(state) }
                    harness.sourceReplies.decorate(observed)
                },
            )
        fundsNode.use {
            val matchingNode =
                DevelopmentSplitTradingNode.start(
                    matchingSettings,
                    AeronSplitOrderDeliveryConnectionFactory,
                    harness.settlementGate,
                    serviceDecorator = { adapter -> observeSnapshotRestore(adapter, matchingRestored) },
                )
            matchingNode.use {
                await("같은 Archive의 양쪽 역할 복원") {
                    assertNull(fundsNode.error)
                    assertNull(matchingNode.error)
                    val recovered = harness.fundsSnapshot.get()
                    val streams = recovered?.results?.streams
                    fundsNode.tradingReady && matchingNode.tradingReady &&
                        streams?.single()?.resultThrough == expectedResultThrough
                }
                assertTrue(fundsRestored.get(), "자금 역할은 기존 스냅샷에서 시작해야 한다")
                assertTrue(matchingRestored.get(), "매칭 역할은 기존 스냅샷에서 시작해야 한다")
                verify()
                assertNull(fundsNode.error)
                assertNull(matchingNode.error)
            }
        }
    }

    private fun assertRecoveryLedgerTransactions(transactions: List<LedgerTransaction>) {
        val btc = AssetId("BTC")
        val usdt = AssetId("USDT")
        val debit = LedgerPostingSide.DEBIT
        val credit = LedgerPostingSide.CREDIT
        assertEquals(
            listOf(
                LedgerTransactionType.OPENING,
                LedgerTransactionType.OPENING,
                LedgerTransactionType.RESERVE,
                LedgerTransactionType.RESERVE,
                LedgerTransactionType.SETTLEMENT,
                LedgerTransactionType.RELEASE,
            ),
            transactions.map { it.transactionType },
        )
        assertEquals(
            listOf(
                listOf(
                    LedgerPosting("SYSTEM:USDT:DEVELOPMENT_FUNDING", usdt, debit, Amount(1_000)),
                    LedgerPosting("USER:buyer:USDT:AVAILABLE", usdt, credit, Amount(1_000)),
                ),
                listOf(
                    LedgerPosting("SYSTEM:BTC:DEVELOPMENT_FUNDING", btc, debit, Amount(3)),
                    LedgerPosting("USER:seller:BTC:AVAILABLE", btc, credit, Amount(3)),
                ),
                listOf(
                    LedgerPosting("USER:buyer:USDT:AVAILABLE", usdt, debit, Amount(300)),
                    LedgerPosting("USER:buyer:USDT:HOLD", usdt, credit, Amount(300)),
                ),
                listOf(
                    LedgerPosting("USER:seller:BTC:AVAILABLE", btc, debit, Amount(1)),
                    LedgerPosting("USER:seller:BTC:HOLD", btc, credit, Amount(1)),
                ),
                listOf(
                    LedgerPosting("USER:buyer:USDT:HOLD", usdt, debit, Amount(100)),
                    LedgerPosting("USER:buyer:BTC:AVAILABLE", btc, credit, Amount(1)),
                    LedgerPosting("USER:seller:BTC:HOLD", btc, debit, Amount(1)),
                    LedgerPosting("USER:seller:USDT:AVAILABLE", usdt, credit, Amount(100)),
                ),
                listOf(
                    LedgerPosting("USER:buyer:USDT:HOLD", usdt, debit, Amount(200)),
                    LedgerPosting("USER:buyer:USDT:AVAILABLE", usdt, credit, Amount(200)),
                ),
            ),
            transactions.map { it.postings },
        )
        assertEquals(transactions.size, transactions.map { it.ledgerTransactionId }.toSet().size)
        assertEquals(transactions.size, transactions.map { it.sourceEventId }.toSet().size)
    }

    private fun readRecoveryTransactions(payload: String): List<LedgerTransaction> {
        val transactions = json.readTree(payload)
        return transactions.values().map { transaction ->
            val postings = transaction.path("postings").values()
            LedgerTransaction(
                ledgerTransactionId = transaction.path("id").asString(),
                sourceEventId = transaction.path("sourceEventId").asString(),
                transactionType = LedgerTransactionType.valueOf(transaction.path("type").asString()),
                occurredAt = Instant.parse(transaction.path("occurredAt").asString()),
                postings =
                    postings.map { posting ->
                        LedgerPosting(
                            posting.path("accountId").asString(),
                            AssetId(posting.path("assetId").asString()),
                            LedgerPostingSide.valueOf(posting.path("side").asString()),
                            Amount(posting.path("amount").longValue()),
                        )
                    },
            )
        }
    }

    /** 실제 source 2는 유지하고 QUERY Describe의 입력 계약 메타데이터만 1로 바꾸어 호환성 확인 경계를 시험한다. */
    @Test
    fun `계약 2 소스의 조회 Describe에 계약 1 메타데이터를 주입하면 준비와 ACK 전에 차단된다`() {
        withTradingNodes("query-test") { harness ->
            assertEquals(2, harness.fundsSettings.configuration.inputContractVersion)
            harness.sourceReplies.describeInputContractVersion.set(1)
            val blocked = CountDownLatch(1)
            val sourcesVerified = AtomicBoolean()
            queryWas(harness, decorate = { actual ->
                object : OrderQueryFeedStore by actual {
                    override fun verifySources(
                        admission: OrderQuerySourceBounds,
                        matching: Map<String, OrderQuerySourceBounds>,
                        financial: Map<String, Long>,
                        expectedBeforeRead: OrderQueryFeedProgress?,
                    ): OrderQueryFeedProgress {
                        sourcesVerified.set(true)
                        return actual.verifySources(admission, matching, financial, expectedBeforeRead)
                    }

                    override fun block(reason: String) {
                        actual.block(reason)
                        blocked.countDown()
                    }
                }
            }).use { context ->
                assertTrue(blocked.await(10, TimeUnit.SECONDS), "주문이 없어도 source 입력 계약의 불일치를 차단해야 한다")
                HttpClient.newHttpClient().use { http ->
                    val query = getQuery(http, wasUrl(context), TradingIdentifiers.order(lineage, 1).value)
                    assertEquals(503, query.statusCode(), query.body())
                    assertEquals("QUERY_NOT_READY", json.readTree(query.body()).path("code").asString())
                    assertEquals("BLOCKED", json.readTree(query.body()).path("lifecycle").asString())
                }
                assertFalse(sourcesVerified.get(), "호환되지 않는 빈 소스를 조회 완료로 검증하면 안 된다")
                assertTrue(harness.sourceReplies.rewrittenDescriptions.get() > 0, "실제 QUERY Describe 전송에만 계약 1 메타데이터를 주입해야 한다")
                assertEquals(
                    "BLOCKED",
                    harness.database.jdbc().queryForObject("select lifecycle from order_query.query_progress", String::class.java),
                )
                assertEquals(0L, receiptCount(harness))
                assertEquals(0, harness.sourceReplies.acknowledgements.get())
                assertEquals(
                    0L,
                    harness.fundsSnapshot
                        .get()
                        ?.financial
                        ?.lastAdmissionSequence,
                )
                harness.sourceReplies.describeInputContractVersion.set(2)
                for ((endpoint, market) in listOf(harness.fundsEndpoint to null, harness.matchingEndpoint to "BTC-USDT")) {
                    OrderQuerySourceClient(endpoint, 10_000).use { source -> assertSourcePrefix(source, market, 0, 0) }
                }
            }
        }
    }

    @Test
    fun `계약 2 소스의 조회 Describe 메타데이터에 계약 1을 주입하고 DB 차단 쓰기를 잃어도 재연결 없이 재시도한다`() {
        withTradingNodes("query-test") { harness ->
            assertEquals(2, harness.fundsSettings.configuration.inputContractVersion)
            harness.sourceReplies.describeInputContractVersion.set(1)
            val blocked = CountDownLatch(1)
            val blockAttempts = AtomicInteger()
            val firstReason = AtomicReference<String>()
            val sameReason = AtomicBoolean()
            val sourcesVerified = AtomicBoolean()
            queryWas(harness, decorate = { actual ->
                object : OrderQueryFeedStore by actual {
                    override fun verifySources(
                        admission: OrderQuerySourceBounds,
                        matching: Map<String, OrderQuerySourceBounds>,
                        financial: Map<String, Long>,
                        expectedBeforeRead: OrderQueryFeedProgress?,
                    ): OrderQueryFeedProgress {
                        sourcesVerified.set(true)
                        return actual.verifySources(admission, matching, financial, expectedBeforeRead)
                    }

                    override fun block(reason: String) {
                        if (blockAttempts.incrementAndGet() == 1) {
                            firstReason.set(reason)
                            throw OrderQueryUnavailableException("test: first block write is unavailable")
                        }
                        sameReason.set(reason == firstReason.get())
                        actual.block(reason)
                        blocked.countDown()
                    }
                }
            }).use { context ->
                assertTrue(blocked.await(10, TimeUnit.SECONDS), "첫 DB 쓰기 실패 뒤 차단 저장을 다시 시도해야 한다")
                assertEquals(2, blockAttempts.get())
                assertTrue(sameReason.get(), "처음 확인한 영구 충돌 이유를 재시도에도 유지해야 한다")
                assertEquals(1, harness.sourceReplies.descriptions.get(), "DB 차단 재시도는 소스 재연결을 시작하면 안 된다")
                assertTrue(harness.sourceReplies.rewrittenDescriptions.get() > 0)
                assertFalse(sourcesVerified.get())
                assertEquals(
                    "BLOCKED",
                    harness.database.jdbc().queryForObject("select lifecycle from order_query.query_progress", String::class.java),
                )
                HttpClient.newHttpClient().use { http ->
                    val query = getQuery(http, wasUrl(context), TradingIdentifiers.order(lineage, 1).value)
                    assertEquals(503, query.statusCode(), query.body())
                    assertEquals("QUERY_NOT_READY", json.readTree(query.body()).path("code").asString())
                    assertEquals("BLOCKED", json.readTree(query.body()).path("lifecycle").asString())
                }
                assertEquals(0L, receiptCount(harness))
                assertEquals(0, harness.sourceReplies.acknowledgements.get())
                assertEquals(
                    0L,
                    harness.fundsSnapshot
                        .get()
                        ?.queryAdmissions
                        ?.retiredThrough,
                )
                harness.sourceReplies.describeInputContractVersion.set(2)
                for ((endpoint, market) in listOf(harness.fundsEndpoint to null, harness.matchingEndpoint to "BTC-USDT")) {
                    OrderQuerySourceClient(endpoint, 10_000).use { source -> assertSourcePrefix(source, market, 0, 0) }
                }
            }
        }
    }

    @Test
    fun `실제 접수 원본의 DB 반영을 늦추면 최소 접수 순번 조회는 202이고 해제 뒤 주문이 보인다`() {
        withTradingNodes("query-test") { harness ->
            val gate = CallbackGate()
            try {
                queryWas(harness, decorate = { actual ->
                    object : OrderQueryFeedStore by actual {
                        override fun applyAdmission(
                            fact: FundsOrderDispatch,
                            canonicalPayload: ByteArray,
                        ): Long {
                            gate.pause()
                            return actual.applyAdmission(fact, canonicalPayload)
                        }
                    }
                }).use { context ->
                    val url = wasUrl(context)
                    HttpClient.newHttpClient().use { http ->
                        awaitEmptyReady(harness, http, url)
                        val accepted = submitSeller(http, url)
                        val orderId = json.readTree(accepted.body()).path("orderId").asString()
                        assertTrue(gate.reached.await(10, TimeUnit.SECONDS), "실제 접수 원본을 읽은 DB 적용 직전이어야 한다")
                        val pending = getQuery(http, url, orderId, 1)
                        assertEquals(202, pending.statusCode(), pending.body())
                        assertEquals("QUERY_CATCHING_UP", json.readTree(pending.body()).path("code").asString())
                        assertEquals(0L, json.readTree(pending.body()).path("coveredAdmissionSequence").longValue())
                        assertEquals(
                            0L,
                            harness.fundsSnapshot
                                .get()
                                ?.queryAdmissions
                                ?.retiredThrough,
                        )
                        assertEquals(0L, receiptCount(harness))
                        gate.release.countDown()
                        val caughtUp = awaitQuery(harness, http, url, orderId, "RESTING", 0, 3, 0)
                        assertEquals(1L, json.readTree(caughtUp.body()).path("coveredAdmissionSequence").longValue())
                        assertEquals(200, getQuery(http, url, orderId, 1).statusCode())
                    }
                }
            } finally {
                gate.release.countDown()
            }
        }
    }

    @Test
    fun `실제 DB 커밋의 응답을 잃어도 재확인 전 원본 ACK가 없고 재적용 영수증과 주문 값이 같다`() {
        withTradingNodes("query-test") { harness ->
            val committed = CountDownLatch(1)
            val saved = AtomicReference<Triple<FundsOrderDispatch, ByteArray, Long>>()
            val actualStore = AtomicReference<OrderQueryFeedStore>()
            val loseOnce = AtomicBoolean(true)
            try {
                queryWas(harness, decorate = { actual ->
                    actualStore.set(actual)
                    object : OrderQueryFeedStore by actual {
                        override fun applyAdmission(
                            fact: FundsOrderDispatch,
                            canonicalPayload: ByteArray,
                        ): Long {
                            val revision = actual.applyAdmission(fact, canonicalPayload)
                            if (loseOnce.compareAndSet(true, false)) {
                                saved.set(Triple(fact, canonicalPayload.copyOf(), revision))
                                harness.sourceReplies.pauseDescriptions.set(true)
                                committed.countDown()
                                throw IllegalStateException("test: DB committed but its result was lost")
                            }
                            return revision
                        }
                    }
                }).use { context ->
                    val url = wasUrl(context)
                    HttpClient.newHttpClient().use { http ->
                        awaitEmptyReady(harness, http, url)
                        val accepted = submitSeller(http, url)
                        val receipt = json.readTree(accepted.body())
                        val orderId = receipt.path("orderId").asString()
                        assertTrue(committed.await(10, TimeUnit.SECONDS), "제품 저장소가 실제 커밋을 마쳐야 한다")
                        awaitQueryStatus(harness, http, url, orderId, 503, "QUERY_NOT_READY")
                        assertEquals(1L, receiptCount(harness))
                        assertEquals(
                            1L,
                            harness.database.jdbc().queryForObject(
                                "select admission_through from order_query.feed_consumer",
                                Long::class.java,
                            ),
                        )
                        assertEquals(
                            0L,
                            harness.fundsSnapshot
                                .get()
                                ?.queryAdmissions
                                ?.retiredThrough,
                        )
                        assertEquals(
                            1,
                            harness.fundsSnapshot
                                .get()
                                ?.queryAdmissions
                                ?.entries
                                ?.size,
                        )

                        val (fact, payload, revision) = checkNotNull(saved.get())
                        assertEquals(revision, actualStore.get().applyAdmission(fact, payload), "같은 확정 원본의 재적용은 첫 영수증을 반환해야 한다")
                        assertEquals(1L, receiptCount(harness))
                        assertEquals(
                            revision,
                            harness.database.jdbc().queryForObject(
                                "select first_revision from order_query.source_receipt",
                                Long::class.java,
                            ),
                        )
                        val repeated = post(http, url, "/markets/BTC-USDT/orders", orderBody("seller", "SELL", 3))
                        assertEquals(202, repeated.statusCode(), repeated.body())
                        assertEquals(receipt.path("orderId").asString(), json.readTree(repeated.body()).path("orderId").asString())
                        assertEquals(1L, json.readTree(repeated.body()).path("admissionSequence").longValue())

                        harness.sourceReplies.pauseDescriptions.set(false)
                        val recovered = awaitQuery(harness, http, url, orderId, "RESTING", 0, 3, 0)
                        val body = json.readTree(recovered.body())
                        assertEquals(3L, body.path("initialQuantity").longValue())
                        assertEquals(100L, body.path("price").longValue())
                        await("확인한 DB cursor의 원본 ACK") {
                            harness.funds.poll()
                            harness.fundsSnapshot
                                .get()
                                ?.queryAdmissions
                                ?.retiredThrough == 1L
                        }
                        assertEquals(2L, receiptCount(harness), "접수와 매칭 원본이 각각 한 번만 저장되어야 한다")
                    }
                }
            } finally {
                harness.sourceReplies.pauseDescriptions.set(false)
            }
        }
    }

    @Test
    fun `실제 원본 Read 응답 유실은 cursor나 보존 범위를 앞당기지 않고 재검증 실패 뒤 복구한다`() {
        withTradingNodes("query-test") { assertSourceReplyLoss(it, acknowledge = false) }
    }

    @Test
    fun `실제 확정된 조회 ACK의 응답 유실은 저장된 cursor를 보존하고 재검증 실패 뒤 복구한다`() {
        withTradingNodes("query-test") { assertSourceReplyLoss(it, acknowledge = true) }
    }

    @Test
    fun `두 WAS에서 먼저 저장한 소비자가 원본을 회수해도 오래된 cursor의 PRUNED는 DB를 다시 읽어 복구한다`() {
        withTradingNodes("query-test") { harness ->
            val pause = CallbackGate()
            val armed = AtomicBoolean()
            val staleReturned = AtomicBoolean()
            val reread = CountDownLatch(1)
            val blocked = AtomicReference<String?>()
            try {
                queryWas(harness, gatewayRoot = "gateway-a", decorate = { actual ->
                    object : OrderQueryFeedStore by actual {
                        override fun verifySources(
                            admission: OrderQuerySourceBounds,
                            matching: Map<String, OrderQuerySourceBounds>,
                            financial: Map<String, Long>,
                            expectedBeforeRead: OrderQueryFeedProgress?,
                        ): OrderQueryFeedProgress {
                            val checked = actual.verifySources(admission, matching, financial, expectedBeforeRead)
                            if (admission.issued == 1L && checked.admissionThrough == 0L) armed.set(true)
                            return checked
                        }

                        override fun progress(): OrderQueryFeedProgress {
                            val captured = actual.progress()
                            if (armed.compareAndSet(true, false)) {
                                assertEquals(0L, captured.admissionThrough)
                                pause.pause()
                                staleReturned.set(true)
                            } else if (staleReturned.get() && captured.admissionThrough == 1L) {
                                reread.countDown()
                            }
                            return captured
                        }

                        override fun block(reason: String) {
                            blocked.set(reason)
                            actual.block(reason)
                        }
                    }
                }).use { first ->
                    HttpClient.newHttpClient().use { http ->
                        val firstUrl = wasUrl(first)
                        awaitEmptyReady(harness, http, firstUrl)
                        val accepted = submitSeller(http, firstUrl)
                        val orderId = json.readTree(accepted.body()).path("orderId").asString()
                        assertTrue(pause.reached.await(10, TimeUnit.SECONDS), "WAS A는 소스 head 1을 확인한 뒤 DB cursor 0을 보유해야 한다")
                        queryWas(harness, gatewayRoot = "gateway-b", egressOffset = 33).use { second ->
                            val secondUrl = wasUrl(second)
                            awaitQuery(harness, http, secondUrl, orderId, "RESTING", 0, 3, 0)
                            await("WAS B가 저장한 원본 1의 확정 ACK") {
                                harness.funds.poll()
                                harness.fundsSnapshot
                                    .get()
                                    ?.queryAdmissions
                                    ?.retiredThrough == 1L
                            }
                            harness.sourceReplies.observeNextRead.set(true)
                            pause.release.countDown()
                            assertTrue(harness.sourceReplies.observedRead.await(10, TimeUnit.SECONDS), "WAS A가 실제로 회수된 원본 1을 요청해야 한다")
                            assertTrue(reread.await(10, TimeUnit.SECONDS), "PRUNED 뒤 저장된 DB cursor 1을 다시 읽어야 한다")
                            awaitQuery(harness, http, firstUrl, orderId, "RESTING", 0, 3, 0)
                            awaitQuery(harness, http, secondUrl, orderId, "RESTING", 0, 3, 0)
                            assertNull(blocked.get(), "동일 consumer의 정상 경합을 영구 차단으로 분류하면 안 된다")
                            assertEquals(2L, receiptCount(harness))
                            assertEquals(
                                "READY",
                                harness.database.jdbc().queryForObject(
                                    "select lifecycle from order_query.query_progress",
                                    String::class.java,
                                ),
                            )
                        }
                    }
                }
            } finally {
                pause.release.countDown()
            }
        }
    }

    private class CallbackGate {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun pause() {
            reached.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "test callback was not released" }
        }
    }

    /** 접수는 실제 callback으로 처리하고, 지정한 요청의 첫 승인 응답만 버린다. */
    private fun dropFirstAdmissionReply(
        service: ClusteredService,
        token: RequestToken,
        lostReceipt: AtomicReference<AdmissionReceipt?>,
    ): ClusteredService {
        val codec = SplitOrderDeliveryCodec(2)
        return object : ClusteredService by service {
            override fun onSessionMessage(
                session: ClientSession?,
                timestamp: Long,
                buffer: DirectBuffer,
                offset: Int,
                length: Int,
                header: Header,
            ) {
                if (session == null || lostReceipt.get() != null || length <= SplitOrderDeliveryCodec.HEADER_BYTES ||
                    buffer.getShort(offset + 4, ByteOrder.LITTLE_ENDIAN).toInt() != 88
                ) {
                    service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                    return
                }
                val request = ByteArray(length)
                buffer.getBytes(offset, request)
                val control = codec.decode(request).command as? SplitOrderDeliveryCommand.Control
                val admission = control?.command as? BoundedTradingCommand.Admit
                if (admission?.token != token) {
                    service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                    return
                }
                val droppingSession =
                    object : ClientSession by session {
                        override fun offer(
                            buffer: DirectBuffer,
                            offset: Int,
                            length: Int,
                        ): Long {
                            val bytes = ByteArray(length)
                            buffer.getBytes(offset, bytes)
                            val receipt = codec.decodeReply(bytes).control?.admission
                            if (receipt?.code != AdmissionCode.ACCEPTED) return session.offer(buffer, offset, length)
                            check(lostReceipt.compareAndSet(null, receipt)) { "첫 승인 응답만 버려야 한다" }
                            return 1L
                        }
                    }
                service.onSessionMessage(droppingSession, timestamp, buffer, offset, length, header)
            }

            override fun doBackgroundWork(nowNs: Long): Int = service.doBackgroundWork(nowNs)
        }
    }

    /** 실제 복제 callback 뒤 지정한 QUERY 응답만 유실시키거나 Describe 메타데이터를 바꾼다. 제품 훅은 없다. */
    private class SourceReplyGate {
        val acknowledgements = AtomicInteger()
        val ledgerAcknowledgements = AtomicInteger()
        val descriptions = AtomicInteger()
        val recoveryDescriptions = AtomicInteger()
        val recoverySuffixRead = AtomicBoolean()
        val describeInputContractVersion = AtomicInteger(2)
        val rewrittenDescriptions = AtomicInteger()
        val dropNextRead = AtomicBoolean()
        val dropNextAcknowledgement = AtomicBoolean()
        val dropNextSettlementAck = AtomicBoolean()
        val dropNextLedgerAcknowledgement = AtomicBoolean()
        val pauseDescriptions = AtomicBoolean()
        val dropped = CountDownLatch(1)
        val observeNextRead = AtomicBoolean()
        val observedRead = CountDownLatch(1)
        val rewriteRecovery = AtomicReference<((OrderQueryRecovery) -> OrderQueryRecovery)?>(null)
        private val codec = OrderQuerySourceCodec()

        fun decorate(actual: ClusteredService): ClusteredService =
            object : ClusteredService by actual {
                override fun onSessionMessage(
                    session: ClientSession?,
                    timestamp: Long,
                    buffer: DirectBuffer,
                    offset: Int,
                    length: Int,
                    header: Header,
                ) {
                    val command =
                        if (OrderQuerySourceCodec.isQueryFrame(buffer, offset, length)) {
                            val bytes = ByteArray(length)
                            buffer.getBytes(offset, bytes)
                            codec.decode(bytes).command
                        } else {
                            null
                        }
                    if (command is OrderQuerySourceCommand.Acknowledge) acknowledgements.incrementAndGet()
                    if (command is OrderQuerySourceCommand.AckLedger) ledgerAcknowledgements.incrementAndGet()
                    if (command == OrderQuerySourceCommand.Describe) descriptions.incrementAndGet()
                    val loseReply =
                        when (command) {
                            is OrderQuerySourceCommand.Read -> dropNextRead.compareAndSet(true, false)
                            is OrderQuerySourceCommand.Acknowledge -> dropNextAcknowledgement.compareAndSet(true, false)
                            is OrderQuerySourceCommand.AcknowledgeSettlements -> dropNextSettlementAck.compareAndSet(true, false)
                            is OrderQuerySourceCommand.AckLedger -> dropNextLedgerAcknowledgement.compareAndSet(true, false)
                            else -> false
                        }
                    if (loseReply) pauseDescriptions.set(true)
                    val loseDescription = command == OrderQuerySourceCommand.Describe && pauseDescriptions.get()
                    val rewrite = rewriteRecovery.get()
                    val replySession =
                        when {
                            loseReply || loseDescription -> {
                                null
                            }

                            command == OrderQuerySourceCommand.Describe && session != null && describeInputContractVersion.get() != 2 -> {
                                descriptorSession(session, describeInputContractVersion.get())
                            }

                            command is OrderQuerySourceCommand.DescribeRecovery && session != null && rewrite != null -> {
                                rewriteReply(session) { reply ->
                                    val recovery = reply.recovery
                                    if (recovery == null) reply else reply.copy(recovery = rewrite(recovery))
                                }
                            }

                            else -> {
                                session
                            }
                        }
                    actual.onSessionMessage(replySession, timestamp, buffer, offset, length, header)
                    if (command is OrderQuerySourceCommand.DescribeRecovery) {
                        recoveryDescriptions.incrementAndGet()
                        if (command.offset > 0) recoverySuffixRead.set(true)
                    }
                    if (loseReply) dropped.countDown()
                    if (command is OrderQuerySourceCommand.Read && command.from == 1L && observeNextRead.compareAndSet(true, false)) {
                        observedRead.countDown()
                    }
                }

                override fun doBackgroundWork(nowNs: Long): Int = actual.doBackgroundWork(nowNs)
            }

        /** QUERY Describe에만 적용한다. 실제 계약 2 소스와 POST gateway의 응답은 그대로 사용한다. */
        private fun descriptorSession(
            actual: ClientSession,
            inputContractVersion: Int,
        ): ClientSession =
            rewriteReply(actual) { described ->
                rewrittenDescriptions.incrementAndGet()
                described.copy(inputContractVersion = inputContractVersion)
            }

        private fun rewriteReply(
            actual: ClientSession,
            update: (OrderQuerySourceReply) -> OrderQuerySourceReply,
        ): ClientSession =
            object : ClientSession by actual {
                override fun offer(
                    buffer: DirectBuffer,
                    offset: Int,
                    length: Int,
                ): Long {
                    val bytes = ByteArray(length)
                    buffer.getBytes(offset, bytes)
                    val reply = codec.decodeReply(bytes)
                    val changed = codec.encodeReply(update(reply))
                    return actual.offer(UnsafeBuffer(changed), 0, changed.size)
                }
            }
    }

    /** 실제 결과 송신만 잠시 막아 매칭 사실과 자금 확정 사이의 관찰 구간을 만든다. */
    private class SettlementGate : SplitResultDeliveryConnectionFactory {
        val paused = AtomicBoolean()
        private val codec = SplitResultDeliveryCodec(2)

        override fun begin(
            endpoint: SplitOrderDeliveryEndpoint,
            timeoutNs: Long,
            onReply: (SplitResultDeliveryReply) -> Unit,
        ): SplitResultDeliveryConnectAttempt {
            val actual = AeronSplitResultDeliveryConnectionFactory.begin(endpoint, timeoutNs, onReply)
            return object : SplitResultDeliveryConnectAttempt {
                override fun poll(): SplitResultDeliveryConnection? =
                    actual.poll()?.let { connection ->
                        object : SplitResultDeliveryConnection by connection {
                            override fun offer(bytes: ByteArray): Long =
                                if (paused.get() && codec.decode(bytes).command is SplitResultDeliveryCommand.Apply) {
                                    Publication.BACK_PRESSURED
                                } else {
                                    connection.offer(bytes)
                                }
                        }
                    }

                override fun close() = actual.close()
            }
        }
    }

    private data class AppliedFundsInput(
        val command: BoundedTradingCommand,
        val reply: SplitOrderDeliveryReply,
    )

    private data class FundsAtLogPosition(
        val logPosition: Long,
        val state: FundsRoleRecoveryState,
    )

    private fun verifyRestoredFundsPosition(
        restored: FundsAtLogPosition,
        approval: SplitOrderDeliveryReply,
    ) {
        check(restored.logPosition == approval.appliedLogPosition) { "복원 상태와 주문 승인 위치가 다르다" }
        check(restored.state.financial.manifest.lineage == approval.lineage) { "복원 상태와 주문 승인 계보가 다르다" }
    }

    private fun verifyArchivedFundsInputs(
        archived: RecordedArchiveRead.Complete,
        archiveId: Long,
        successfulInputs: List<AppliedFundsInput>,
    ): List<BoundedTradingCommand> {
        val approval = successfulInputs.last().reply
        check(archived.recording.archiveId == archiveId && archived.startPosition == 0L) { "자금 Archive의 최초 범위를 확인할 수 없다" }
        check(archived.limitPosition == approval.appliedLogPosition && archived.consumedPosition == approval.appliedLogPosition) {
            "자금 Archive가 주문 승인 위치까지 읽히지 않았다"
        }
        val expected =
            successfulInputs.associateBy { input ->
                check(input.reply.role == TradingServiceRole.FUNDS && input.reply.lineage == approval.lineage)
                input.reply.appliedLogPosition
            }
        check(expected.size == successfulInputs.size)
        val commands = mutableListOf<BoundedTradingCommand>()
        val remaining = expected.toMutableMap()
        val orderCodec = SplitOrderDeliveryCodec(2)
        val resultCodec = SplitResultDeliveryCodec(2)
        for (frame in archived.inputs) {
            val bytes = frame.bytes
            if (UnsafeBuffer(bytes).getShort(4, ByteOrder.LITTLE_ENDIAN).toInt() != 88) {
                val command = resultCodec.decode(bytes).command
                check(command == SplitResultDeliveryCommand.DescribePeer || command is SplitResultDeliveryCommand.Progress)
                continue
            }
            val envelope = orderCodec.decode(bytes)
            val command = envelope.command
            if (command == SplitOrderDeliveryCommand.DescribePeer || command is SplitOrderDeliveryCommand.InitializeRole) continue
            check(command is SplitOrderDeliveryCommand.Control) { "지원하지 않는 전달 입력: $command" }
            val control = command.command
            if (control == BoundedTradingCommand.Progress || control is BoundedTradingCommand.Rotate) continue
            check(control is BoundedTradingCommand.Funding || control is BoundedTradingCommand.Admit) { "지원하지 않는 자금 입력: $control" }
            val input = checkNotNull(remaining.remove(frame.position)) { "확인되지 않은 자금 입력 위치 ${frame.position}" }
            check(input.reply.correlationId == envelope.correlationId && input.command == control) {
                "성공 응답과 Archive 자금 입력이 일치하지 않는다"
            }
            commands.add(control)
        }
        check(remaining.isEmpty()) { "Archive에 필요한 자금 입력이 없다: ${remaining.keys}" }
        return commands
    }

    private data class TradingHarness(
        val database: OrderQueryTestDatabase,
        val basePort: Int,
        val fundsSettings: SplitTradingNodeSettings,
        val matchingSettings: SplitTradingNodeSettings,
        val fundsNode: DevelopmentSplitTradingNode,
        val matchingNode: DevelopmentSplitTradingNode,
        val fundsSnapshot: AtomicReference<FundsRoleRecoveryState?>,
        val funds: SplitOrderDeliveryClient,
        val fundsEndpoint: SplitOrderDeliveryEndpoint,
        val matchingEndpoint: SplitOrderDeliveryEndpoint,
        val settlementGate: SettlementGate,
        val sourceReplies: SourceReplyGate,
        val openingInputs: List<AppliedFundsInput>,
    )

    private fun withTradingNodes(
        consumerId: String? = null,
        sourceRestart: Boolean = false,
        feeRate: FeeRate = FeeRate.ZERO,
        takerFeeRate: FeeRate = feeRate,
        persistSettlements: Boolean = false,
        maxFundingAccounts: Int = 8,
        archiveQuery: Boolean = false,
        decorateFunds: (ClusteredService) -> ClusteredService = { it },
        verify: (TradingHarness) -> Unit,
    ) {
        val basePort = availablePorts()
        val feeRates = MakerTakerFeeRates(makerFeeRate = feeRate, takerFeeRate = takerFeeRate)
        val manifest = manifest(feeRates, maxFundingAccounts)
        val manifestFile = directory.resolve("manifest.bin").also { Files.write(it, BoundedTradingStateCodec().encodeManifest(manifest)) }
        val fundsSettings = settings(manifestFile, TradingServiceRole.FUNDS, basePort, consumerId, persistSettlements, archiveQuery)
        val matchingSettings = settings(manifestFile, TradingServiceRole.MATCHING, basePort, consumerId, persistSettlements, archiveQuery)
        val fundsSnapshot = AtomicReference<FundsRoleRecoveryState?>()
        val settlementGate = SettlementGate()
        val sourceReplies = SourceReplyGate()
        OrderQueryTestDatabase(socketTimeoutSeconds = if (archiveQuery) 2 else null).use { database ->
            DevelopmentSplitTradingNode
                .start(
                    fundsSettings,
                    serviceDecorator = { adapter ->
                        val observed = observeFunds(adapter) { _, state -> fundsSnapshot.set(state) }
                        sourceReplies.decorate(decorateFunds(observed))
                    },
                ).use { fundsNode ->
                    DevelopmentSplitTradingNode
                        .start(
                            matchingSettings,
                            AeronSplitOrderDeliveryConnectionFactory,
                            settlementGate,
                        ).use { matchingNode ->
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
                                            if (matching) {
                                                matchingSettings.matchingIngressEndpoints
                                            } else {
                                                fundsSettings.fundsIngressEndpoints
                                            },
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
                                                            SplitOrderDeliveryCommand.InitializeRole(
                                                                fundsSettings.configuration.topologySha256,
                                                            ),
                                                        ).code,
                                                )
                                            }
                                        }
                                        val openingInputs =
                                            listOf(
                                                Triple("buyer", "USDT", 1_000L),
                                                Triple("seller", "BTC", 3L),
                                            ).map { (user, asset, amount) ->
                                                val opening =
                                                    OpeningBalance("http-opening-$user", UserId(user), AssetId(asset), Amount(amount))
                                                val payload =
                                                    DevelopmentBalanceCommandPayload(opening, "http-opening-ledger-$user", Instant.EPOCH)
                                                val command = BoundedTradingCommand.Funding(payload)
                                                val reply = funds.request(SplitOrderDeliveryCommand.Control(command))
                                                assertEquals("PREPARED", reply.code)
                                                AppliedFundsInput(command, reply)
                                            }
                                        verify(
                                            TradingHarness(
                                                database,
                                                basePort,
                                                fundsSettings,
                                                matchingSettings,
                                                fundsNode,
                                                matchingNode,
                                                fundsSnapshot,
                                                funds,
                                                endpoint(false),
                                                endpoint(true),
                                                settlementGate,
                                                sourceReplies,
                                                openingInputs,
                                            ),
                                        )
                                        assertNull(fundsNode.error)
                                        assertNull(matchingNode.error)
                                        if (sourceRestart) {
                                            assertTrue(fundsNode.isClosed)
                                            assertTrue(matchingNode.isClosed)
                                        } else {
                                            assertFalse(fundsNode.isClosed)
                                            assertFalse(matchingNode.isClosed)
                                        }
                                    }
                                }
                        }
                }
        }
    }

    private fun queryWas(
        harness: TradingHarness,
        gatewayRoot: String = "gateway",
        egressOffset: Int = 32,
        requireRecoveryReady: Boolean = false,
        decorate: (OrderQueryFeedStore) -> OrderQueryFeedStore = { it },
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(ExchangeCoreApplication::class.java)
            .initializers({ context ->
                context.addBeanFactoryPostProcessor { factory ->
                    factory.addBeanPostProcessor(
                        object : BeanPostProcessor {
                            override fun postProcessAfterInitialization(
                                bean: Any,
                                beanName: String,
                            ): Any = if (bean is OrderQueryFeedStore) decorate(bean) else bean
                        },
                    )
                }
            })
            .run(*wasArguments(harness, true, gatewayRoot, egressOffset, requireRecoveryReady))

    private fun wasUrl(context: ConfigurableApplicationContext): String =
        "http://127.0.0.1:${(context as ServletWebServerApplicationContext).webServer!!.port}"

    private fun receiptCount(harness: TradingHarness): Long =
        checkNotNull(harness.database.jdbc().queryForObject("select count(*) from order_query.source_receipt", Long::class.java))

    private fun awaitEmptyReady(
        harness: TradingHarness,
        http: HttpClient,
        url: String,
    ) {
        awaitQueryStatus(harness, http, url, TradingIdentifiers.order(lineage, 1).value, 404, "ORDER_NOT_FOUND_AS_OF")
    }

    private fun submitSeller(
        http: HttpClient,
        url: String,
        quantity: Long = 3,
    ): HttpResponse<String> {
        val epoch = post(http, url, "/channels/0/epoch", """{"userId":"seller","expectedEpoch":0}""")
        assertEquals(200, epoch.statusCode(), epoch.body())
        val accepted = post(http, url, "/markets/BTC-USDT/orders", orderBody("seller", "SELL", quantity))
        assertEquals(202, accepted.statusCode(), accepted.body())
        assertEquals(1L, json.readTree(accepted.body()).path("admissionSequence").longValue())
        return accepted
    }

    private fun assertSourceReplyLoss(
        harness: TradingHarness,
        acknowledge: Boolean,
    ) {
        val beforeSourceRead = CallbackGate()
        val pauseOnce = AtomicBoolean()
        try {
            queryWas(harness, decorate = { actual ->
                object : OrderQueryFeedStore by actual {
                    override fun verifySources(
                        admission: OrderQuerySourceBounds,
                        matching: Map<String, OrderQuerySourceBounds>,
                        financial: Map<String, Long>,
                        expectedBeforeRead: OrderQueryFeedProgress?,
                    ): OrderQueryFeedProgress {
                        if (pauseOnce.compareAndSet(true, false)) beforeSourceRead.pause()
                        return actual.verifySources(admission, matching, financial, expectedBeforeRead)
                    }
                }
            }).use { context ->
                val url = wasUrl(context)
                HttpClient.newHttpClient().use { http ->
                    awaitEmptyReady(harness, http, url)
                    pauseOnce.set(true)
                    assertTrue(beforeSourceRead.reached.await(10, TimeUnit.SECONDS), "새 소스 head를 읽기 전에 수집 작업을 멈춰야 한다")
                    val accepted = submitSeller(http, url)
                    val orderId = json.readTree(accepted.body()).path("orderId").asString()
                    await("실제 매칭 결과와 자금 callback 확정") {
                        harness.funds.poll()
                        harness.fundsSnapshot
                            .get()
                            ?.results
                            ?.streams
                            ?.single()
                            ?.resultThrough == 1L
                    }
                    if (acknowledge) {
                        harness.sourceReplies.dropNextAcknowledgement.set(
                            true,
                        )
                    } else {
                        harness.sourceReplies.dropNextRead.set(true)
                    }
                    beforeSourceRead.release.countDown()
                    assertTrue(harness.sourceReplies.dropped.await(10, TimeUnit.SECONDS), "실제 소스 callback 뒤 응답을 잃어야 한다")
                    awaitQueryStatus(harness, http, url, orderId, 503, "QUERY_NOT_READY")
                    val expectedThrough = if (acknowledge) 1L else 0L
                    assertEquals(
                        expectedThrough,
                        harness.database.jdbc().queryForObject("select admission_through from order_query.feed_consumer", Long::class.java),
                    )
                    assertEquals(
                        expectedThrough,
                        harness.fundsSnapshot
                            .get()
                            ?.queryAdmissions
                            ?.retiredThrough,
                    )
                    assertEquals(
                        if (acknowledge) 0 else 1,
                        harness.fundsSnapshot
                            .get()
                            ?.queryAdmissions
                            ?.entries
                            ?.size,
                    )
                    assertEquals(if (acknowledge) 2L else 0L, receiptCount(harness))
                    val receiptsBefore =
                        harness.database.jdbc().queryForList(
                            "select source_kind, source_sequence, first_revision, encode(canonical_payload, 'hex') as payload " +
                                "from order_query.source_receipt order by source_kind, source_sequence",
                        )
                    OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { source ->
                        assertSourcePrefix(source, "BTC-USDT", 1, 0)
                    }
                    harness.sourceReplies.pauseDescriptions.set(false)
                    val recovered = awaitQuery(harness, http, url, orderId, "RESTING", 0, 3, 0)
                    assertEquals(1L, json.readTree(recovered.body()).path("coveredAdmissionSequence").longValue())
                    OrderQuerySourceClient(harness.matchingEndpoint, 10_000).use { source ->
                        await("응답 유실 뒤 양쪽 원본 ACK 복구") {
                            harness.funds.poll()
                            source
                                .request(OrderQuerySourceCommand.Describe)
                                .progress
                                .single()
                                .retiredThrough == 1L &&
                                harness.fundsSnapshot
                                    .get()
                                    ?.queryAdmissions
                                    ?.retiredThrough == 1L
                        }
                    }
                    assertEquals(2L, receiptCount(harness))
                    if (acknowledge) {
                        assertEquals(
                            receiptsBefore,
                            harness.database.jdbc().queryForList(
                                "select source_kind, source_sequence, first_revision, encode(canonical_payload, 'hex') as payload " +
                                    "from order_query.source_receipt order by source_kind, source_sequence",
                            ),
                            "ACK 결과 재확인은 이미 커밋한 원본과 첫 revision을 바꾸면 안 된다",
                        )
                    }
                }
            }
        } finally {
            beforeSourceRead.release.countDown()
            harness.sourceReplies.pauseDescriptions.set(false)
        }
    }

    private fun getQuery(
        http: HttpClient,
        url: String,
        orderId: String,
        minimum: Long = 0,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("$url/api/trading/v3/markets/BTC-USDT/orders/$orderId?minAdmissionSequence=$minimum"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun awaitQueryStatus(
        harness: TradingHarness,
        http: HttpClient,
        url: String,
        orderId: String,
        status: Int,
        code: String,
    ) {
        var last: HttpResponse<String>? = null
        await("주문 조회 $status $code", { "마지막 응답 ${last?.statusCode()}: ${last?.body()}" }) {
            harness.funds.poll()
            last = getQuery(http, url, orderId)
            last!!.statusCode() == status && json.readTree(last!!.body()).path("code").asString() == code
        }
    }

    private fun wasArguments(
        harness: TradingHarness,
        queryEnabled: Boolean = false,
        gatewayRoot: String = "gateway",
        egressOffset: Int = 32,
        requireRecoveryReady: Boolean = false,
    ): Array<String> {
        val properties =
            harness.database.properties +
                mapOf(
                    "exchange.order-query.supported-markets" to "BTC-USDT",
                    "TRADING_GATEWAY_RUN_ROOT" to directory.resolve(gatewayRoot).toString(),
                    "TRADING_GATEWAY_INGRESS_ENDPOINTS" to harness.fundsSettings.fundsIngressEndpoints,
                    "TRADING_GATEWAY_EGRESS_ENDPOINT" to "127.0.0.1:${harness.basePort + egressOffset}",
                ) +
                if (queryEnabled) {
                    mapOf(
                        "exchange.order-query.consumer-id" to "query-test",
                        "exchange.order-query.matching-ingress" to "BTC-USDT@0=127.0.0.1:${harness.basePort + 16}",
                    )
                } else {
                    emptyMap()
                } +
                if (requireRecoveryReady) {
                    mapOf("exchange.order-query.require-recovery-ready" to "true")
                } else {
                    emptyMap()
                }
        return (
            properties.map { (key, value) -> "--$key=$value" } +
                listOf("--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")
        ).toTypedArray()
    }

    private fun orderBody(
        user: String,
        side: String,
        quantity: Long,
        epoch: Long = 1,
        price: Long = 100,
        sequence: Long = 1,
    ) = """{"userId":"$user","channel":0,"epoch":$epoch,"sequence":$sequence,"expectedConfigurationVersion":1,""" +
        """"side":"$side","orderType":"LIMIT","timeInForce":"GTC","price":$price,"quantity":$quantity}"""

    private fun awaitQuery(
        harness: TradingHarness,
        http: HttpClient,
        url: String,
        orderId: String,
        state: String,
        filled: Long,
        remaining: Long,
        cancelled: Long,
        financialStatus: String = "APPLIED",
        pendingReason: String? = null,
        minimum: Long = 0,
    ): HttpResponse<String> {
        var last: HttpResponse<String>? = null
        await(
            "주문 $orderId 조회 $state",
            { "마지막 응답 ${last?.statusCode()}: ${last?.body()}, ${describeFunds(harness.fundsSnapshot.get())}" },
        ) {
            harness.funds.poll()
            val response = getQuery(http, url, orderId, minimum)
            last = response
            val body = json.readTree(response.body())
            response.statusCode() == 200 && body.path("state").asString() == state &&
                body.path("filledQuantity").longValue() == filled && body.path("remainingQuantity").longValue() == remaining &&
                body.path("cancelledQuantity").longValue() == cancelled && body.path("financialStatus").asString() == financialStatus &&
                (pendingReason == null || body.path("pendingReason").asString() == pendingReason)
        }
        return checkNotNull(last)
    }

    private fun describeFunds(saved: FundsRoleRecoveryState?): String {
        if (saved == null) return "자금 적용 상태 미관측"
        val resultSequences = saved.results?.streams?.map { it.resultThrough }
        val financial = saved.financial
        val reservations = financial.liveOrders.map { it.reservation }
        return "자금 결과 순번=$resultSequences, 잔고=${financial.balances}, 예약=$reservations, 수수료=${financial.feeRevenueByAsset}"
    }

    private fun await(
        description: String,
        detail: () -> String = { "" },
        ready: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() - deadline < 0) {
            if (ready()) return
            check(!Thread.currentThread().isInterrupted)
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
        assertTrue(ready(), "$description 제한 시간 안에 완료되어야 한다. ${detail()}")
    }

    private fun assertFinalBalances(
        saved: FundsRoleRecoveryState,
        buyerQuote: Long = 900,
        sellerQuote: Long = 100,
    ) {
        val balances = saved.financial.balances.associateBy { it.userId.value to it.assetId.value }
        for ((user, asset, available) in listOf(
            Triple("buyer", "USDT", buyerQuote),
            Triple("buyer", "BTC", 1L),
            Triple("seller", "USDT", sellerQuote),
            Triple("seller", "BTC", 2L),
        )) {
            assertEquals(Balance(UserId(user), AssetId(asset), Amount(available), Amount(0)), balances[user to asset])
        }
    }

    private fun takeSnapshot(
        node: DevelopmentSplitTradingNode,
        control: SplitOrderDeliveryClient,
    ) {
        val context = node.consensusContext
        val before = context.snapshotCounter().get()
        assertTrue(node.requestSnapshot())
        await("${node.settings.role} 실제 snapshot 기록") {
            control.poll()
            assertNull(node.error)
            context.snapshotCounter().get() > before &&
                ClusterControl.ToggleState.get(context.controlToggleCounter()) == ClusterControl.ToggleState.NEUTRAL
        }
    }

    private fun assertSourcePrefix(
        source: OrderQuerySourceClient,
        market: String?,
        issued: Long,
        retired: Long,
    ) {
        val reply = source.request(OrderQuerySourceCommand.Describe)
        assertEquals("DESCRIBED", reply.code)
        val progress = reply.progress.single { it.market == market }
        assertEquals(issued, progress.issued)
        assertEquals(retired, progress.retiredThrough)
    }

    private fun observeSnapshotRestore(
        service: SplitTradingClusterAdapter,
        restored: AtomicBoolean,
    ): ClusteredService =
        object : ClusteredService by service {
            override fun onStart(
                cluster: Cluster,
                snapshotImage: Image?,
            ) {
                restored.set(snapshotImage != null)
                service.onStart(cluster, snapshotImage)
            }

            override fun doBackgroundWork(nowNs: Long): Int = service.doBackgroundWork(nowNs)
        }

    /** 테스트만 확정 callback 안에서 불변 사본을 만든다. WAS나 제품 관찰 API는 이 상태에 접근하지 않는다. */
    private fun observeFunds(
        service: SplitTradingClusterAdapter,
        restored: AtomicBoolean? = null,
        replayPosition: Long? = null,
        onCaptured: (Long, FundsRoleRecoveryState) -> Unit,
    ): ClusteredService {
        val field = service.javaClass.getDeclaredField("funds").also { it.isAccessible = true }
        return object : ClusteredService by service {
            private fun capture() {
                if (service.lifecycle == TradingLifecycle.RUNNING) {
                    onCaptured(service.appliedLogPosition, (field.get(service) as BoundedFundingState).fundsRecoveryState())
                }
            }

            override fun onStart(
                cluster: Cluster,
                snapshotImage: Image?,
            ) {
                restored?.set(snapshotImage != null)
                service.onStart(cluster, snapshotImage)
                if (replayPosition == null) capture()
            }

            override fun onSessionMessage(
                session: ClientSession?,
                timestamp: Long,
                buffer: DirectBuffer,
                offset: Int,
                length: Int,
                header: Header,
            ) {
                service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                if (replayPosition == null || service.appliedLogPosition == replayPosition) capture()
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
        consumerId: String? = null,
        persistSettlements: Boolean = false,
        archiveQuery: Boolean = false,
    ): SplitTradingNodeSettings =
        splitTradingSettings(settingsEnvironment(manifest, role, base, consumerId, persistSettlements, archiveQuery))

    private fun settingsEnvironment(
        manifest: Path,
        role: TradingServiceRole,
        base: Int,
        consumerId: String? = null,
        persistSettlements: Boolean = false,
        archiveQuery: Boolean = false,
    ): Map<String, String> {
        val port = if (role == TradingServiceRole.FUNDS) base else base + 16
        val querySettings =
            buildMap {
                if (consumerId != null) put("EXCHANGE_ORDER_QUERY_CONSUMER_ID", consumerId)
                if (persistSettlements) put("EXCHANGE_ORDER_QUERY_PERSIST_SETTLEMENTS", "true")
                if (archiveQuery) put("EXCHANGE_ORDER_QUERY_SOURCE", "archive")
            }
        return mapOf(
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
        ) + querySettings
    }

    private fun manifest(
        feeRates: MakerTakerFeeRates,
        maxFundingAccounts: Int,
    ): TradingClusterManifest =
        TradingClusterManifest(
            lineage,
            "split-http",
            BoundedExecutionIdentity.artifactSha256,
            BoundedExecutionIdentity.schemaSha256,
            1,
            listOf(
                TradingMarketContext(
                    MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), AssetId("USDT"), 0),
                    TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, feeRates),
                    0,
                ),
            ),
            TradingResourceLimits(
                maxAccounts = maxFundingAccounts,
                channelsPerAccount = 1,
                maxChannels = 8,
                requestWindow = 16,
                maxInboxEntries = 128,
                maxActiveOrders = 32,
                maxPendingOrders = 32,
                maxMarketActiveOrders = 32,
                maxMarketPendingOrders = 32,
                maxBalanceKeys = maxOf(32, maxFundingAccounts),
                maxPreparations = maxOf(16, maxFundingAccounts),
                maxMarkets = 1,
                maxAssets = 2,
            ),
        )

    private fun availablePorts(extraOffsets: List<Int> = emptyList()): Int {
        repeat(100) {
            // 노드 기동 뒤에 바인딩하는 WAS의 고정 포트를 OS 임시 UDP 응답 포트와 분리한다.
            val base = ThreadLocalRandom.current().nextInt(16_000, 30_000)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                for (offset in (0..8) + (16..24) + (32..33) + extraOffsets) sockets.add(DatagramSocket(base + offset))
                return base
            } catch (_: java.net.BindException) {
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("자금·매칭 노드와 WAS 응답에 필요한 포트를 확보하지 못했습니다")
    }
}
