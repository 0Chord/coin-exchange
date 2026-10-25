package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
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
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.DatagramSocket
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 등록된 두 문맥의 기존 Protobuf 요청을 같은 실제 Aeron 노드와 자금 상태에 보낸다. */
class DevelopmentMultiMarketOrderNodeTest {
    @Test
    fun `BTC 600 예약 뒤 SUI 500은 부족하고 SUI 400 승인 뒤에도 최초 부족값은 유지된다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            val ready = jacksonObjectMapper().valueToTree<JsonNode>(fundingReadyFields(config, node))
            val expectedMarkets =
                jacksonObjectMapper().readTree(
                    """
                    [
                      {"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,
                       "priceTick":1,"quantityStep":100000000,
                       "feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,
                                    "makerPartsPerMillion":0,"takerPartsPerMillion":0}},
                      {"marketId":"SUI-USDT","baseAssetId":"SUI","quoteAssetId":"USDT","baseQuantityScale":9,
                       "priceTick":1,"quantityStep":1000000000,
                       "feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":2,
                                    "makerPartsPerMillion":0,"takerPartsPerMillion":0}}
                    ]
                    """.trimIndent(),
                )
            val actualMarkets = jacksonObjectMapper().readTree(ready.at("/settings/markets").toString())
            assertEquals(expectedMarkets, actualMarkets)
            assertFalse(ready.path("settings").has("market"))
            assertFalse(ready.path("settings").has("feePolicy"))
            val opening = prepare(node)
            assertBalance(opening, available = 1_000_000_000, hold = 0)
            assertEquals(0L, opening.lastSequence)
            assertTrue(opening.pendingOrders.isEmpty())
            assertTrue(opening.requests.isEmpty())
            assertEquals(1, opening.preparations.size)
            val openingTransaction = opening.preparations.single().transaction
            assertEquals("OPENING-1", openingTransaction.ledgerTransactionId)
            assertEquals("OPENING:P1", openingTransaction.sourceEventId)
            assertEquals("OPENING", openingTransaction.transactionType.name)
            assertEquals(OPENING_AT, openingTransaction.occurredAt)

            val btc = order(BTC, "R-BTC", "O-BTC", price = 100_000_000, quantity = 600_000_000)
            val first = request(node, btc)
            assertEquals(1L, assertIs<AdmissionResultPayload.Accepted>(first.admissionResult).sequence)
            val afterBtc = assertNotNull(first.snapshot)
            assertBalance(afterBtc, available = 400_000_000, hold = 600_000_000)
            assertEquals(1L, afterBtc.lastSequence)
            assertEquals(listOf("O-BTC"), pendingOrders(afterBtc, BTC))
            assertTrue(pendingOrders(afterBtc, SUI).isEmpty())
            assertEquals(listOf("R-BTC"), afterBtc.requests.map { it.requestId })
            assertReservation(afterBtc, btc, amount = 600_000_000, sequence = 1, sourceEventId = BTC_RESERVE_SOURCE)

            val suiLarge = order(SUI, "R-SUI", "O-SUI", price = 20_000_000, quantity = 25_000_000_000)
            val denied = request(node, suiLarge)
            val shortage = assertIs<AdmissionResultPayload.Rejected>(denied.admissionResult)
            assertShortage(shortage, asset = "USDT", available = 400_000_000, required = 500_000_000, shortfall = 100_000_000)
            val afterShortage = assertNotNull(denied.snapshot)
            assertEquals(fundingValues(afterBtc), fundingValues(afterShortage))
            assertEquals(listOf("R-BTC", "R-SUI"), afterShortage.requests.map { it.requestId })
            assertRequest(afterShortage, suiLarge)
            val recordedShortage = assertIs<OrderAdmissionState.Result.Rejected>(afterShortage.requests.last().result)
            assertEquals(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE, recordedShortage.reason)
            assertEquals(shortage.insufficientBalance, recordedShortage.insufficientBalance)

            val repeated = request(node, suiLarge)
            assertEquals(shortage, repeated.admissionResult)
            assertEquals(snapshotValues(afterShortage), snapshotValues(assertNotNull(repeated.snapshot)))

            val suiSmall = order(SUI, "R-SUI-2", "O-SUI-2", price = 20_000_000, quantity = 20_000_000_000)
            val second = request(node, suiSmall)
            assertEquals(2L, assertIs<AdmissionResultPayload.Accepted>(second.admissionResult).sequence)
            val completed = assertNotNull(second.snapshot)
            assertBalance(completed, available = 0, hold = 1_000_000_000)
            assertEquals(1, completed.balances.size)
            assertEquals(2L, completed.lastSequence)
            assertEquals(listOf("O-BTC"), pendingOrders(completed, BTC))
            assertEquals(listOf("O-SUI-2"), pendingOrders(completed, SUI))
            assertEquals(listOf("R-BTC", "R-SUI", "R-SUI-2"), completed.requests.map { it.requestId })
            assertEquals(1, completed.preparations.size)
            assertEquals(transactionValues(openingTransaction), transactionValues(completed.preparations.single().transaction))
            assertReservation(completed, btc, amount = 600_000_000, sequence = 1, sourceEventId = BTC_RESERVE_SOURCE)
            assertReservation(completed, suiSmall, amount = 400_000_000, sequence = 2, sourceEventId = SUI_RESERVE_SOURCE)
            assertRequest(completed, btc)
            assertRequest(completed, suiSmall)
            assertEquals(1L, assertIs<OrderAdmissionState.Result.Accepted>(completed.requests[0].result).record.sequence)
            assertEquals(2L, assertIs<OrderAdmissionState.Result.Accepted>(completed.requests[2].result).record.sequence)

            val replayAfterBalanceChanged = request(node, suiLarge)
            assertEquals(shortage, replayAfterBalanceChanged.admissionResult)
            assertEquals(snapshotValues(completed), snapshotValues(assertNotNull(replayAfterBalanceChanged.snapshot)))
            assertNull(node.error)
        }
    }

    @Test
    fun `신규 SUI 문맥의 불일치는 기록 없이 거절하고 고친 같은 요청을 접수한다`() {
        DevelopmentFundingNode.start(config()).use { node ->
            prepare(node)
            val prototype = order(SUI, "R-SUI", "O-SUI", price = 20_000_000, quantity = 1_000_000_000)
            val mismatches = suiContextChanges(prototype)
            assertEquals(12, mismatches.size)

            mismatches.forEachIndexed { index, (field, mismatch) ->
                assertNotEquals(prototype, mismatch, "$field 변형은 SUI 정상값과 달라야 합니다")
                val correct =
                    prototype.copy(
                        requestId = "context-R$index",
                        order = prototype.order.copy(orderId = OrderId("context-O$index")),
                        ledgerTransactionId = "context-ledger-$index",
                    )
                val incorrect =
                    mismatch.copy(
                        requestId = correct.requestId,
                        order = mismatch.order.copy(orderId = correct.order.orderId),
                        ledgerTransactionId = correct.ledgerTransactionId,
                    )
                val before = snapshotValues(assertNotNull(node.observation?.snapshot))

                val rejected = request(node, incorrect)
                assertEquals(
                    AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH,
                    assertIs<AdmissionResultPayload.Rejected>(rejected.admissionResult, field).reason,
                    field,
                )
                assertNull(assertIs<AdmissionResultPayload.Rejected>(rejected.admissionResult).insufficientBalance, field)
                assertEquals(before, snapshotValues(assertNotNull(rejected.snapshot)), field)
                assertTrue(rejected.snapshot.requests.none { it.requestId == correct.requestId }, field)

                val accepted = request(node, correct)
                assertEquals(index + 1L, assertIs<AdmissionResultPayload.Accepted>(accepted.admissionResult, field).sequence, field)
                val current = assertNotNull(accepted.snapshot)
                assertBalance(current, available = 980_000_000L - index * 20_000_000L, hold = (index + 1L) * 20_000_000L)
                assertEquals(index + 1, current.requests.size, field)
                assertEquals(index + 1, current.pendingOrders.size, field)
                assertTrue(pendingOrders(current, BTC).isEmpty(), field)
                assertEquals(index + 1, pendingOrders(current, SUI).size, field)
                assertRequest(current, correct)
                assertEquals(
                    SUI.feePolicySnapshot,
                    current.pendingOrders
                        .last()
                        .candidate.reservation.feePolicySnapshot,
                )
                assertNull(node.error, field)
            }
        }
    }

    @Test
    fun `기존 SUI 승인과 부족은 전달 마켓 정책 원장 시각을 바꿔도 최초 결과와 기록을 보존한다`() {
        DevelopmentFundingNode.start(config()).use { node ->
            prepare(node)
            val acceptedCommand = order(SUI, "R-ACCEPTED", "O-ACCEPTED", price = 20_000_000, quantity = 20_000_000_000)
            val accepted = assertIs<AdmissionResultPayload.Accepted>(request(node, acceptedCommand).admissionResult)
            val deniedCommand = order(SUI, "R-DENIED", "O-DENIED", price = 20_000_000, quantity = 40_000_000_000)
            val denied = assertIs<AdmissionResultPayload.Rejected>(request(node, deniedCommand).admissionResult)
            assertShortage(denied, asset = "USDT", available = 600_000_000, required = 800_000_000, shortfall = 200_000_000)
            val original = assertNotNull(node.observation?.snapshot)
            val before = snapshotValues(original)

            listOf(acceptedCommand to accepted, deniedCommand to denied).forEach { (command, firstResult) ->
                val retransmission =
                    command.copy(
                        market = MarketDefinition(MarketId("ETH-USDT"), AssetId("ETH"), AssetId("USDT"), 18),
                        feePolicySnapshot = command.feePolicySnapshot.copy(scheduleVersion = 3),
                        ledgerTransactionId = "ignored-${command.requestId}",
                        occurredAt = Instant.parse("2040-01-01T00:00:00.987654321Z"),
                    )
                val repeated = request(node, retransmission)
                assertEquals(firstResult, repeated.admissionResult)
                val current = assertNotNull(repeated.snapshot)
                assertEquals(before, snapshotValues(current))
                assertRequest(current, command)
                assertNull(node.error)
            }
            assertEquals(1L, original.lastSequence)
            assertEquals(2, original.requests.size)
            assertEquals(listOf("O-ACCEPTED"), pendingOrders(original, SUI))
        }
    }

    @Test
    fun `기존 BTC 요청의 마켓을 SUI로 바꾸면 내용 충돌이고 신규 중복은 문맥 확인 뒤 기록한다`() {
        DevelopmentFundingNode.start(config()).use { node ->
            prepare(node)
            val btc = order(BTC, "R-BTC", "SAME-ORDER", price = 100_000_000, quantity = 100_000_000)
            request(node, btc)
            val before = assertNotNull(node.observation?.snapshot)
            val changedMarket =
                btc.copy(
                    order = btc.order.copy(marketId = SUI.market.marketId),
                    market = SUI.market,
                    feePolicySnapshot = SUI.feePolicySnapshot,
                )

            val conflict = request(node, changedMarket)
            assertEquals(
                AdmissionResultPayload.Reason.REQUEST_CONTENT_CONFLICT,
                assertIs<AdmissionResultPayload.Rejected>(conflict.admissionResult).reason,
            )
            assertEquals(snapshotValues(before), snapshotValues(assertNotNull(conflict.snapshot)))

            val duplicate = btc.copy(requestId = "R-DUPLICATE", ledgerTransactionId = "ledger-duplicate")
            val badContext = duplicate.copy(feePolicySnapshot = SUI.feePolicySnapshot)
            val mismatch = request(node, badContext)
            assertEquals(
                AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH,
                assertIs<AdmissionResultPayload.Rejected>(mismatch.admissionResult).reason,
            )
            assertEquals(snapshotValues(before), snapshotValues(assertNotNull(mismatch.snapshot)))

            val denied = request(node, duplicate)
            assertEquals(
                AdmissionResultPayload.Reason.DUPLICATE_ORDER,
                assertIs<AdmissionResultPayload.Rejected>(denied.admissionResult).reason,
            )
            val current = assertNotNull(denied.snapshot)
            assertEquals(fundingValues(before), fundingValues(current))
            assertEquals(listOf("R-BTC", "R-DUPLICATE"), current.requests.map { it.requestId })
            assertRequest(current, btc)
            assertRequest(current, duplicate)
            assertEquals(denied.admissionResult, request(node, badContext).admissionResult)
            assertEquals(snapshotValues(current), snapshotValues(assertNotNull(node.observation?.snapshot)))
            assertNull(node.error)
        }
    }

    @Test
    fun `다른 마켓의 같은 주문 ID는 별개 예약과 원장 사건으로 전역 순번을 받는다`() {
        DevelopmentFundingNode.start(config()).use { node ->
            prepare(node)
            val btc = order(BTC, "R-BTC", "SAME-ORDER", price = 100_000_000, quantity = 100_000_000)
            val sui = order(SUI, "R-SUI", "SAME-ORDER", price = 20_000_000, quantity = 5_000_000_000)

            assertEquals(1L, assertIs<AdmissionResultPayload.Accepted>(request(node, btc).admissionResult).sequence)
            val second = request(node, sui)
            assertEquals(2L, assertIs<AdmissionResultPayload.Accepted>(second.admissionResult).sequence)

            val current = assertNotNull(second.snapshot)
            assertBalance(current, available = 800_000_000, hold = 200_000_000)
            assertEquals(1, current.balances.size)
            assertEquals(2L, current.lastSequence)
            assertEquals(listOf("SAME-ORDER"), pendingOrders(current, BTC))
            assertEquals(listOf("SAME-ORDER"), pendingOrders(current, SUI))
            assertEquals(2, current.requests.size)
            assertReservation(current, btc, amount = 100_000_000, sequence = 1, sourceEventId = BTC_SAME_ORDER_SOURCE)
            assertReservation(current, sui, amount = 100_000_000, sequence = 2, sourceEventId = SUI_SAME_ORDER_SOURCE)
            assertEquals(
                2,
                current.pendingOrders
                    .map { it.candidate.reserveTransaction.ledgerTransactionId }
                    .distinct()
                    .size,
            )
            assertEquals(
                2,
                current.pendingOrders
                    .map { it.candidate.reserveTransaction.sourceEventId }
                    .distinct()
                    .size,
            )
            assertNull(node.error)
        }
    }

    @Test
    fun `U1의 USDT와 BTC가 있어도 U2의 SUI 매수와 U1의 SUI 매도에는 사용하지 않는다`() {
        DevelopmentFundingNode.start(config()).use { node ->
            prepare(node)
            val prepared = prepare(node, preparation = "P-BTC", asset = "BTC", amount = 300_000_000, ledger = "OPENING-BTC")
            val otherUser = order(SUI, "R-U2", "O-U2", price = 20_000_000, quantity = 1_000_000_000, user = "U2")
            val otherAsset = order(SUI, "R-SELL", "O-SELL", price = 20_000_000, quantity = 1_000_000_000, side = Side.SELL)

            val buy = request(node, otherUser)
            assertShortage(
                assertIs<AdmissionResultPayload.Rejected>(buy.admissionResult),
                asset = "USDT",
                available = 0,
                required = 20_000_000,
                shortfall = 20_000_000,
            )
            assertEquals(fundingValues(prepared), fundingValues(assertNotNull(buy.snapshot)))
            val sell = request(node, otherAsset)
            assertShortage(
                assertIs<AdmissionResultPayload.Rejected>(sell.admissionResult),
                asset = "SUI",
                available = 0,
                required = 1_000_000_000,
                shortfall = 1_000_000_000,
            )
            val current = assertNotNull(sell.snapshot)
            assertEquals(fundingValues(prepared), fundingValues(current))
            assertBalance(current, available = 1_000_000_000, hold = 0)
            assertBalance(current, available = 300_000_000, hold = 0, asset = "BTC")
            assertEquals(2, current.balances.size)
            assertEquals(listOf("R-U2", "R-SELL"), current.requests.map { it.requestId })
            assertRequest(current, otherUser)
            assertRequest(current, otherAsset)
            assertNull(node.error)
        }
    }

    @Test
    fun `BTC에서 발행한 원장 ID는 부족한 SUI를 중단시키지 않지만 충분한 SUI의 재사용은 노드를 중단한다`() {
        DevelopmentFundingNode.start(config()).use { node ->
            prepare(node)
            val btc = order(BTC, "R-BTC", "O-BTC", price = 100_000_000, quantity = 600_000_000)
            val first = request(node, btc)
            assertEquals(1L, assertIs<AdmissionResultPayload.Accepted>(first.admissionResult).sequence)
            val afterBtc = assertNotNull(first.snapshot)
            val large =
                order(SUI, "R-LARGE", "O-LARGE", price = 20_000_000, quantity = 25_000_000_000)
                    .copy(ledgerTransactionId = btc.ledgerTransactionId)

            val rejected = request(node, large)
            assertShortage(
                assertIs<AdmissionResultPayload.Rejected>(rejected.admissionResult),
                asset = "USDT",
                available = 400_000_000,
                required = 500_000_000,
                shortfall = 100_000_000,
            )
            val beforeFailure = assertNotNull(rejected.snapshot)
            assertEquals(fundingValues(afterBtc), fundingValues(beforeFailure))
            assertEquals(listOf("R-BTC", "R-LARGE"), beforeFailure.requests.map { it.requestId })
            assertFalse(node.isClosed)
            assertNull(node.error)
            val collision =
                order(SUI, "R-COLLISION", "O-COLLISION", price = 20_000_000, quantity = 20_000_000_000)
                    .copy(ledgerTransactionId = btc.ledgerTransactionId)
            val previousPosition = assertNotNull(node.observation).position

            val unconfirmed =
                DevelopmentOrderClient(node.config).use { client ->
                    assertIs<OrderRequestOutcome.Unconfirmed>(client.request(collision))
                }

            assertTrue(unconfirmed.transportAccepted)
            assertTrue(node.awaitClosed(5_000), "다른 마켓의 발행 원장 ID 재사용은 실제 노드를 닫아야 합니다")
            assertTrue(node.isClosed)
            val failure = assertIs<IllegalStateException>(node.error)
            assertEquals("원장 거래 ID가 이미 사용됐습니다", failure.message)
            val failed = assertNotNull(node.observation)
            assertTrue(failed.position > previousPosition)
            assertTrue(assertNotNull(failed.failure).contains("원장 거래 ID가 이미 사용됐습니다"))
            assertNull(failed.snapshot)
            assertNull(failed.admissionResult)
            assertFalse(failed.blocked, "승인 후보 검사 오류이므로 반영 중 차단과 구분합니다")
        }
    }

    @Test
    fun `시작 때 읽은 설정은 DB가 중단돼도 이미 연결한 노드와 클라이언트에서 유지된다`() {
        MarketConfigurationDatabase().use { database ->
            val document =
                """
                [{"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,
                  "feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,"makerPartsPerMillion":0,"takerPartsPerMillion":0}},
                 {"marketId":"SUI-USDT","baseAssetId":"SUI","quoteAssetId":"USDT","baseQuantityScale":9,
                  "feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":2,"makerPartsPerMillion":0,"takerPartsPerMillion":0}}]
                """.trimIndent()
            val settings = config().copy(markets = readDevelopmentMarkets(database.register(document)))
            DevelopmentFundingNode.start(settings).use { node ->
                prepare(node)
                DevelopmentOrderClient(settings).use { client ->
                    database.close()
                    val previousPosition = assertNotNull(node.observation).position
                    val command = order(SUI, "R-AFTER-DB", "O-AFTER-DB", price = 20_000_000, quantity = 20_000_000_000)
                    val response = assertIs<OrderRequestOutcome.Confirmed>(client.request(command)).result
                    assertEquals(1L, assertIs<AdmissionResultPayload.Accepted>(response).sequence)
                    val observed = applied(node, previousPosition)
                    assertEquals(response, observed.admissionResult)
                    val snapshot = assertNotNull(observed.snapshot)
                    assertBalance(snapshot, available = 600_000_000, hold = 400_000_000)
                    assertEquals(listOf("O-AFTER-DB"), pendingOrders(snapshot, SUI))
                    assertEquals(
                        2L,
                        snapshot.pendingOrders
                            .single()
                            .candidate.feePolicySnapshot.scheduleVersion,
                    )
                    assertNull(node.error)
                }
            }
        }
    }

    private fun suiContextChanges(command: AdmissionCommandPayload): List<Pair<String, AdmissionCommandPayload>> =
        listOf(
            "미등록 주문 마켓" to command.copy(order = command.order.copy(marketId = MarketId("ETH-USDT"))),
            "마켓 정의 ID" to command.copy(market = command.market.copy(marketId = MarketId("ETH-USDT"))),
            "기준 자산" to command.copy(market = command.market.copy(baseAssetId = AssetId("BTC"))),
            "표시 자산" to command.copy(market = command.market.copy(quoteAssetId = AssetId("KRW"))),
            "수량 스케일" to command.copy(market = command.market.copy(baseAssetScale = 8)),
            "가격 단위" to command.copy(market = command.market.copy(priceTick = 2)),
            "수량 단위" to command.copy(market = command.market.copy(quantityStep = 2_000_000_000)),
            "상품" to command.copy(feePolicySnapshot = command.feePolicySnapshot.copy(productType = FeeProductType.PERPETUAL_FUTURES)),
            "수수료 등급" to command.copy(feePolicySnapshot = command.feePolicySnapshot.copy(feeTier = FeeTier.VIP)),
            "수수료 버전" to command.copy(feePolicySnapshot = command.feePolicySnapshot.copy(scheduleVersion = 1)),
            "maker 요율" to
                command.copy(
                    feePolicySnapshot =
                        command.feePolicySnapshot.copy(
                            feeRates = command.feePolicySnapshot.feeRates.copy(makerFeeRate = FeeRate(1_000)),
                        ),
                ),
            "taker 요율" to
                command.copy(
                    feePolicySnapshot =
                        command.feePolicySnapshot.copy(
                            feeRates = command.feePolicySnapshot.feeRates.copy(takerFeeRate = FeeRate(2_000)),
                        ),
                ),
        )

    private fun order(
        context: DevelopmentMarketContext,
        requestId: String,
        orderId: String,
        price: Long,
        quantity: Long,
        user: String = "U1",
        side: Side = Side.BUY,
    ): AdmissionCommandPayload =
        AdmissionCommandPayload(
            requestId,
            OrderAdmissionInput(
                context.market.marketId,
                OrderId(orderId),
                UserId(user),
                side,
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(price),
                Quantity(quantity),
            ),
            context.market,
            context.feePolicySnapshot,
            "ledger-$requestId",
            ORDER_AT,
        )

    private fun prepare(
        node: DevelopmentFundingNode,
        preparation: String = "P1",
        user: String = "U1",
        asset: String = "USDT",
        amount: Long = 1_000_000_000,
        ledger: String = "OPENING-1",
    ): OrderAdmissionState.Snapshot {
        val previousPosition = node.observation?.position ?: 0
        val opening = OpeningBalance(preparation, UserId(user), AssetId(asset), Amount(amount))
        val response =
            DevelopmentFundingClient(node.config).use { client ->
                assertIs<FundingRequestOutcome.Confirmed>(
                    client.request(DevelopmentBalanceCommandPayload(opening, ledger, OPENING_AT)),
                ).result
            }
        assertEquals(opening, assertIs<DevelopmentBalanceResultPayload.Prepared>(response).result.opening)
        val observation = applied(node, previousPosition)
        assertEquals(response, observation.result)
        return assertNotNull(observation.snapshot)
    }

    private fun request(
        node: DevelopmentFundingNode,
        command: AdmissionCommandPayload,
    ): FundingObservation {
        val previousPosition = node.observation?.position ?: 0
        val response =
            DevelopmentOrderClient(node.config).use { client ->
                assertIs<OrderRequestOutcome.Confirmed>(client.request(command)).result
            }
        assertEquals(command.order.userId.value, response.userId)
        assertEquals(command.requestId, response.requestId)
        assertEquals(command.order.marketId.value, response.marketId)
        assertEquals(command.order.orderId.value, response.orderId)
        val observation = applied(node, previousPosition)
        assertEquals(response, observation.admissionResult)
        return observation
    }

    private fun applied(
        node: DevelopmentFundingNode,
        previousPosition: Long,
    ): FundingObservation {
        val start = System.nanoTime()
        while (true) {
            assertNull(node.error, "새 요청의 관찰 전에 실제 노드가 실패했습니다")
            val observed = node.observation
            if (observed != null && observed.position > previousPosition && observed.replyStatus != "PENDING") {
                assertNull(observed.failure)
                assertFalse(observed.blocked)
                assertEquals(0L, observed.malformedMessages)
                assertNotNull(observed.snapshot)
                return observed
            }
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "해당 요청의 새 관찰 대기 초과")
            Thread.sleep(1)
        }
    }

    private fun assertBalance(
        snapshot: OrderAdmissionState.Snapshot,
        available: Long,
        hold: Long,
        user: String = "U1",
        asset: String = "USDT",
    ) {
        val balance = snapshot.balances.single { it.userId == UserId(user) && it.assetId == AssetId(asset) }
        assertEquals(Amount(available), balance.available)
        assertEquals(Amount(hold), balance.hold)
    }

    private fun assertShortage(
        rejected: AdmissionResultPayload.Rejected,
        asset: String,
        available: Long,
        required: Long,
        shortfall: Long,
    ) {
        assertEquals(AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE, rejected.reason)
        val shortage = assertNotNull(rejected.insufficientBalance)
        assertEquals(AssetId(asset), shortage.assetId)
        assertEquals(Amount(available), shortage.available)
        assertEquals(Amount(required), shortage.required)
        assertEquals(Amount(shortfall), shortage.shortfall)
    }

    private fun pendingOrders(
        snapshot: OrderAdmissionState.Snapshot,
        context: DevelopmentMarketContext,
    ): List<String> =
        snapshot.pendingOrders
            .filter { it.candidate.order.marketId == context.market.marketId }
            .map { it.candidate.order.orderId.value }

    private fun assertReservation(
        snapshot: OrderAdmissionState.Snapshot,
        command: AdmissionCommandPayload,
        amount: Long,
        sequence: Long,
        sourceEventId: String,
    ) {
        val record = snapshot.pendingOrders.single { it.candidate.order == command.order }
        assertEquals(sequence, record.sequence)
        val candidate = record.candidate
        assertEquals(command.market, candidate.market)
        assertEquals(command.feePolicySnapshot, candidate.feePolicySnapshot)
        val reservation = candidate.reservation
        assertEquals(command.order.marketId, reservation.marketId)
        assertEquals(command.order.orderId, reservation.orderId)
        assertEquals(command.order.userId, reservation.userId)
        assertEquals(AssetId("USDT"), reservation.assetId)
        assertEquals(command.order.quantity, reservation.initialQuantity)
        assertEquals(command.order.quantity, reservation.remainingQuantity)
        assertEquals(Amount(amount), reservation.reservedAmount)
        assertEquals(Amount(amount), reservation.remainingAmount)
        assertEquals(Amount.ZERO, reservation.initialFeeReserveAmount)
        assertEquals(Amount.ZERO, reservation.remainingFeeReserveAmount)
        assertEquals("ACTIVE", reservation.status.name)
        assertEquals(command.feePolicySnapshot, reservation.feePolicySnapshot)
        val transaction = candidate.reserveTransaction
        assertEquals(command.ledgerTransactionId, transaction.ledgerTransactionId)
        assertEquals(sourceEventId, transaction.sourceEventId)
        assertEquals("RESERVE", transaction.transactionType.name)
        assertEquals(command.occurredAt, transaction.occurredAt)
        assertEquals(listOf("USER:U1:USDT:AVAILABLE", "USER:U1:USDT:HOLD"), transaction.postings.map { it.accountId })
        assertEquals(listOf(AssetId("USDT"), AssetId("USDT")), transaction.postings.map { it.assetId })
        assertEquals(listOf(Amount(amount), Amount(amount)), transaction.postings.map { it.amount })
        assertEquals(listOf("DEBIT", "CREDIT"), transaction.postings.map { it.side.name })
    }

    private fun assertRequest(
        snapshot: OrderAdmissionState.Snapshot,
        command: AdmissionCommandPayload,
    ) {
        val request = snapshot.requests.single { it.requestId == command.requestId && it.order.userId == command.order.userId }
        assertEquals(command.order, request.order)
        assertEquals(command.market, request.market)
        assertEquals(command.feePolicySnapshot, request.feePolicySnapshot)
        assertEquals(command.ledgerTransactionId, request.ledgerTransactionId)
        assertEquals(command.occurredAt, request.occurredAt)
    }

    private fun fundingValues(snapshot: OrderAdmissionState.Snapshot): List<Any?> =
        listOf(
            snapshot.balances,
            snapshot.lastSequence,
            snapshot.pendingOrders.map { recordValues(it) },
            snapshot.preparations.map { listOf(it.result, transactionValues(it.transaction)) },
        )

    private fun snapshotValues(snapshot: OrderAdmissionState.Snapshot): List<Any?> =
        fundingValues(snapshot) +
            listOf(
                snapshot.requests.map {
                    listOf(
                        it.requestId,
                        it.order,
                        it.market,
                        it.feePolicySnapshot,
                        it.ledgerTransactionId,
                        it.occurredAt,
                        resultValues(it.result),
                    )
                },
            )

    private fun recordValues(record: OrderAdmissionState.Record): List<Any?> =
        record.candidate.let {
            listOf(
                record.sequence,
                it.order,
                it.market,
                it.feePolicySnapshot,
                it.previousBalance,
                it.nextBalance,
                it.reservation,
                transactionValues(it.reserveTransaction),
            )
        }

    private fun resultValues(result: OrderAdmissionState.Result): List<Any?> =
        when (result) {
            is OrderAdmissionState.Result.Accepted -> listOf("승인", recordValues(result.record))
            is OrderAdmissionState.Result.Rejected -> listOf("거절", result.reason, result.insufficientBalance)
        }

    private fun transactionValues(transaction: LedgerTransaction): List<Any?> =
        listOf(
            transaction.ledgerTransactionId,
            transaction.sourceEventId,
            transaction.transactionType,
            transaction.occurredAt,
            transaction.postings,
        )

    private fun config(): DevelopmentFundingSettings {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceAtMost(65528)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                (0..7).forEach { sockets.add(DatagramSocket(base + it)) }
                return DevelopmentFundingSettings(
                    Files.createTempDirectory("multi-market114-").resolve("run"),
                    base,
                    markets = DevelopmentMarketRegistry(listOf(BTC, SUI)),
                )
            } catch (_: java.net.BindException) {
                // 실제 UDP 노드끼리 포트가 겹치면 이 테스트의 연속 포트를 다시 찾는다.
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("격리 테스트용 포트를 확보하지 못했습니다")
    }

    companion object {
        private val BTC =
            DevelopmentMarketContext(
                MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), AssetId("USDT"), 8),
                TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)),
            )
        private val SUI =
            DevelopmentMarketContext(
                MarketDefinition(MarketId("SUI-USDT"), AssetId("SUI"), AssetId("USDT"), 9),
                TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 2, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)),
            )
        private val OPENING_AT = Instant.parse("2026-10-22T00:00:00Z")
        private val ORDER_AT = Instant.parse("2026-10-22T00:01:00.123456789Z")
        private const val BTC_RESERVE_SOURCE = "RESERVE:v1:018073f242abd9639ef45ee15937dc46ccac8474aaa7741930508b38a2a023d6"
        private const val SUI_RESERVE_SOURCE = "RESERVE:v1:4743e36b7cefdefa470ba94427a4197aebf6519fe301aa6fdcf2fcf866a7354a"
        private const val BTC_SAME_ORDER_SOURCE = "RESERVE:v1:45af1019ecee2cfca3f632ff304d956553b621fc43f87592ce3b45db56fcd76e"
        private const val SUI_SAME_ORDER_SOURCE = "RESERVE:v1:525ac38eeebee43afd320d9442ab1d3f539a87f35b6f535db9fa8e1f347c116a"
    }
}
