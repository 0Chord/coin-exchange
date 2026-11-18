package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.ExchangeCoreApplication
import com.exchange.core.api.trading.application.port.OrderQueryFeedProgress
import com.exchange.core.api.trading.application.port.OrderQueryFeedStore
import com.exchange.core.api.trading.application.port.OrderQuerySourceBounds
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCodec
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryReply
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.support.OrderQueryTestDatabase
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReceipt
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.ClusterLineage
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 제품 WAS·PostgreSQL과 실제 단일 자금·매칭 노드를 연결한다. 복제 HA는 이 검사의 범위가 아니다. */
class SplitFundsTradingHttpTest {
    @TempDir lateinit var directory: Path
    private val json = JsonMapper.builder().build()
    private val lineage = ClusterLineage(41, 42)

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
            // 매수의 첫 0.6이 후속 0.8로 이월되어 1을 실제 청구한다.
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

                        awaitEmptyReady(harness, http, url)
                        val firstSale = submitSeller(http, url, quantity = 1)
                        val firstSaleId = json.readTree(firstSale.body()).path("orderId").asString()
                        awaitQuery(harness, http, url, firstSaleId, "RESTING", filled = 0, remaining = 1, cancelled = 0)
                        assertFills(firstSaleId, settledTotal = null)
                        assertTrue(jdbc.queryForList(settlementIds, String::class.java).isEmpty())

                        val buyerEpoch = post(http, url, "/channels/0/epoch", """{"userId":"buyer","expectedEpoch":0}""")
                        assertEquals(200, buyerEpoch.statusCode(), buyerEpoch.body())
                        val buy = post(http, url, "/markets/BTC-USDT/orders", orderBody("buyer", "BUY", quantity = 3))
                        assertEquals(202, buy.statusCode(), buy.body())
                        val buyId = json.readTree(buy.body()).path("orderId").asString()
                        assertTrue(firstCommit.reached.await(10, TimeUnit.SECONDS), "첫 체결이 정산 분개 저장 직전까지 진행해야 한다")
                        awaitQuery(harness, http, url, buyId, "PARTIALLY_FILLED", filled = 1, remaining = 2, cancelled = 0)
                        assertStoredProgress(appliedThrough = 2, storedThrough = 1)

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
                        assertFills(buyId, firstBuyerFee, firstCompleted)
                        assertFills(firstSaleId, firstSellerFee, firstCompleted.copy(role = "MAKER", chargedFee = firstSellerFee))

                        val followUpOrder = orderBody("seller", "SELL", quantity = 2, sequence = 2)
                        val followUpSale = post(http, url, "/markets/BTC-USDT/orders", followUpOrder)
                        assertEquals(202, followUpSale.statusCode(), followUpSale.body())
                        val followUpSaleId = json.readTree(followUpSale.body()).path("orderId").asString()
                        assertTrue(followUpCommit.reached.await(10, TimeUnit.SECONDS), "후속 체결이 정산 분개 저장 직전까지 진행해야 한다")
                        awaitQuery(harness, http, url, buyId, "FILLED", filled = 3, remaining = 0, cancelled = 0)
                        assertStoredProgress(3, 2, firstCompleted.tradeId)

                        val pendingResponse = readOrderFills(http, url, buyId)
                        assertEquals(firstBuyerFee, pendingResponse.settledFeeTotal)
                        val pendingFills = pendingResponse.fills
                        assertEquals(2, pendingFills.size)
                        assertEquals(firstCompleted, pendingFills.first())
                        val followUpPending = pendingFills.last()
                        assertTrue(followUpPending.tradeId != firstCompleted.tradeId)
                        assertEquals(HttpOrderFill(followUpPending.tradeId, 100, 2, "MAKER", "PENDING"), followUpPending)
                        assertFills(followUpSaleId, null, followUpPending.copy(role = "TAKER"))

                        followUpCommit.release.countDown()
                        await("후속 체결의 정산 분개 커밋") { jdbc.queryForMap(positions)["settlement_through"] == 3L }
                        val followUpBuyerFee = usdtFee(followUp.buyer)
                        val followUpSellerFee = usdtFee(followUp.seller)
                        val buyerSettledTotal = usdtFee(buyerTotal)
                        val followUpCompleted = followUpPending.copy(settlementStatus = "COMPLETED", chargedFee = followUpBuyerFee)
                        assertStoredProgress(3, 3, firstCompleted.tradeId, followUpCompleted.tradeId)
                        assertStoredFees(followUpCompleted.tradeId, makerFee = followUp.buyer, takerFee = followUp.seller)
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
            val restartedArguments = wasArguments(harness, queryEnabled = true, gatewayRoot = "gateway-restart")
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
                    serviceDecorator = { observeFunds(it, harness.fundsSnapshot, fundsRestored) },
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
        val descriptions = AtomicInteger()
        val describeInputContractVersion = AtomicInteger(2)
        val rewrittenDescriptions = AtomicInteger()
        val dropNextRead = AtomicBoolean()
        val dropNextAcknowledgement = AtomicBoolean()
        val dropNextSettlementAck = AtomicBoolean()
        val pauseDescriptions = AtomicBoolean()
        val dropped = CountDownLatch(1)
        val observeNextRead = AtomicBoolean()
        val observedRead = CountDownLatch(1)
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
                    if (command == OrderQuerySourceCommand.Describe) descriptions.incrementAndGet()
                    val loseReply =
                        when (command) {
                            is OrderQuerySourceCommand.Read -> dropNextRead.compareAndSet(true, false)
                            is OrderQuerySourceCommand.Acknowledge -> dropNextAcknowledgement.compareAndSet(true, false)
                            is OrderQuerySourceCommand.AcknowledgeSettlements -> dropNextSettlementAck.compareAndSet(true, false)
                            else -> false
                        }
                    if (loseReply) pauseDescriptions.set(true)
                    val loseDescription = command == OrderQuerySourceCommand.Describe && pauseDescriptions.get()
                    val replySession =
                        when {
                            loseReply || loseDescription -> {
                                null
                            }

                            command == OrderQuerySourceCommand.Describe && session != null && describeInputContractVersion.get() != 2 -> {
                                descriptorSession(session, describeInputContractVersion.get())
                            }

                            else -> {
                                session
                            }
                        }
                    actual.onSessionMessage(replySession, timestamp, buffer, offset, length, header)
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
            object : ClientSession by actual {
                override fun offer(
                    buffer: DirectBuffer,
                    offset: Int,
                    length: Int,
                ): Long {
                    val bytes = ByteArray(length)
                    buffer.getBytes(offset, bytes)
                    val described = codec.decodeReply(bytes)
                    val changed = codec.encodeReply(described.copy(inputContractVersion = inputContractVersion))
                    rewrittenDescriptions.incrementAndGet()
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
    )

    private fun withTradingNodes(
        consumerId: String? = null,
        sourceRestart: Boolean = false,
        feeRate: FeeRate = FeeRate.ZERO,
        takerFeeRate: FeeRate = feeRate,
        persistSettlements: Boolean = false,
        decorateFunds: (ClusteredService) -> ClusteredService = { it },
        verify: (TradingHarness) -> Unit,
    ) {
        val basePort = availablePorts()
        val manifest = manifest(MakerTakerFeeRates(makerFeeRate = feeRate, takerFeeRate = takerFeeRate))
        val manifestFile = directory.resolve("manifest.bin").also { Files.write(it, BoundedTradingStateCodec().encodeManifest(manifest)) }
        val fundsSettings = settings(manifestFile, TradingServiceRole.FUNDS, basePort, consumerId, persistSettlements)
        val matchingSettings = settings(manifestFile, TradingServiceRole.MATCHING, basePort, consumerId, persistSettlements)
        val fundsSnapshot = AtomicReference<FundsRoleRecoveryState?>()
        val settlementGate = SettlementGate()
        val sourceReplies = SourceReplyGate()
        OrderQueryTestDatabase().use { database ->
            DevelopmentSplitTradingNode
                .start(
                    fundsSettings,
                    serviceDecorator = { adapter ->
                        val observed = observeFunds(adapter, fundsSnapshot)
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
                                        for ((user, asset, amount) in listOf(
                                            Triple("buyer", "USDT", 1_000L),
                                            Triple("seller", "BTC", 3L),
                                        )) {
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
            .run(*wasArguments(harness, true, gatewayRoot, egressOffset))

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
        captured: AtomicReference<FundsRoleRecoveryState?>,
        restored: AtomicBoolean? = null,
    ): ClusteredService {
        val field = service.javaClass.getDeclaredField("funds").also { it.isAccessible = true }
        return object : ClusteredService by service {
            private fun capture() {
                if (service.lifecycle == TradingLifecycle.RUNNING) {
                    captured.set((field.get(service) as BoundedFundingState).fundsRecoveryState())
                }
            }

            override fun onStart(
                cluster: Cluster,
                snapshotImage: Image?,
            ) {
                restored?.set(snapshotImage != null)
                service.onStart(cluster, snapshotImage)
                capture()
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
                capture()
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
    ): SplitTradingNodeSettings {
        val port = if (role == TradingServiceRole.FUNDS) base else base + 16
        val querySettings =
            buildMap {
                if (consumerId != null) put("EXCHANGE_ORDER_QUERY_CONSUMER_ID", consumerId)
                if (persistSettlements) put("EXCHANGE_ORDER_QUERY_PERSIST_SETTLEMENTS", "true")
            }
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
            ) + querySettings,
        )
    }

    private fun manifest(feeRates: MakerTakerFeeRates): TradingClusterManifest =
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
                for (offset in (0..8) + (16..24) + (32..33)) sockets.add(DatagramSocket(base + offset))
                return base
            } catch (_: java.net.BindException) {
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("자금·매칭 노드와 WAS 응답에 필요한 포트를 확보하지 못했습니다")
    }
}
