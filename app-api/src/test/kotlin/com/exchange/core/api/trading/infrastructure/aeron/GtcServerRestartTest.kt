package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedMarketProgress
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class GtcServerRestartTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `매칭 로그 확정 직후 강제 종료해도 원래 주문을 복원하고 재전송은 중복 정산하지 않는다`() {
        verifyGtcRestart(directory, matchingCrashDispatch = 2) { database, cluster, client ->
            val jdbc = database.jdbc()
            val http = TradingHttp(client, cluster.start(), cluster::recordHttp)
            for (user in listOf("buyer", "seller", "later-buyer")) http.openChannel(user)
            val sell = http.submit(order("seller", "SELL", quantity = 1, sequence = 1))
            http.awaitOrder(sell, "RESTING", filled = 0, remaining = 1, resultSequence = 1)
            http.awaitBook(resultSequence = 1, bids = 0, asks = 1)
            assertEquals(1L, readProgress(cluster).market.settledResultThrough)
            cluster.snapshot()

            val buy = http.submit(order("buyer", "BUY", quantity = 1, sequence = 1))
            http.awaitBalance("buyer", "USDT", 5, available = 898, reserved = 102)
            http.awaitBalance("seller", "BTC", 5, available = 2, reserved = 1)
            val beforeCrash = readDatabase(jdbc)
            assertLedger(beforeCrash, ledgerThrough = 5, reserveCount = 2, settlementCount = 0, releaseCount = 0, buyReserve = 102)
            assertTrue(beforeCrash.postings.isEmpty())
            val pending = readProgress(cluster)
            assertEquals(2L, pending.admissionSequence)
            assertEquals(2L, pending.market.dispatchIssued)
            assertEquals(1L, pending.market.settledResultThrough)

            cluster.crashAndRecoverMatching(buy.id)

            // 원래 명령으로 정산까지 끝나야 사용자 토큰을 다시 보낸다.
            http.awaitOrder(buy, "FILLED", filled = 1, remaining = 0, resultSequence = 2)
            http.awaitOrder(sell, "FILLED", filled = 1, remaining = 0, resultSequence = 2)
            http.awaitBook(resultSequence = 2, bids = 0)
            http.awaitBalance("buyer", "USDT", 6, available = 898, reserved = 0)
            http.awaitBalance("buyer", "BTC", 6, available = 1, reserved = 0)
            http.awaitBalance("seller", "USDT", 6, available = 99, reserved = 0)
            http.awaitBalance("seller", "BTC", 6, available = 2, reserved = 0)
            val buyFill = http.awaitFills(buy, 1, feeTotal = 2).single()
            val sellFill = http.awaitFills(sell, 1, feeTotal = 1).single()
            val trade = assertFill(buyFill, quantity = 1, role = "TAKER", fee = 2)
            assertEquals(trade, assertFill(sellFill, quantity = 1, role = "MAKER", fee = 1))
            val completed = readDatabase(jdbc)
            assertEquals(beforeCrash.ledger, completed.ledger.dropLast(1))
            assertEquals(listOf(sell.id, buy.id), completed.orders.map { it["order_id"] })
            assertLedger(completed, ledgerThrough = 6, reserveCount = 2, settlementCount = 1, releaseCount = 0, buyReserve = 102)
            assertSettlement(
                completed,
                trade,
                sell,
                buy,
                resultSequence = 2,
                quantity = 1,
                makerFee = 1,
                takerFee = 2,
                expectedPostings = firstFillPostings,
            )
            val settled = readProgress(cluster)
            assertEquals(pending.admissionSequence, settled.admissionSequence)
            assertEquals(pending.market.dispatchIssued, settled.market.dispatchIssued)
            assertEquals(2L, settled.market.settledResultThrough)

            val replay = http.submit(buy.body)
            assertEquals(buy, replay)
            assertEquals(settled, readProgress(cluster), "같은 토큰은 새 업무 순번을 만들지 않는다")

            // 재전송 뒤 별도 예약까지 반영해 오래된 조회가 중복 효과를 가리지 못하게 한다.
            val laterBid = http.submit(order("later-buyer", "BUY", quantity = 1, sequence = 1))
            assertEquals(buy.admissionSequence + 1, laterBid.admissionSequence)
            assertEquals(buy.marketAdmissionSequence + 1, laterBid.marketAdmissionSequence)
            http.awaitOrder(laterBid, "RESTING", filled = 0, remaining = 1, resultSequence = 3)
            http.awaitBook(resultSequence = 3, bids = 1)
            http.awaitBalance("later-buyer", "USDT", 7, available = 198, reserved = 102)
            http.awaitBalance("buyer", "USDT", 7, available = 898, reserved = 0)
            http.awaitOrder(buy, "FILLED", filled = 1, remaining = 0, resultSequence = 2)
            assertEquals(buyFill, http.awaitFills(buy, 1, feeTotal = 2).single())
            assertEquals(sellFill, http.awaitFills(sell, 1, feeTotal = 1).single())
            val afterReplay = readDatabase(jdbc)
            assertEquals(completed.ledger, afterReplay.ledger.dropLast(1))
            assertTransfer(afterReplay.entry(7), "RESERVE", "later-buyer", "USDT", 102)
            assertEquals(completed.orders, afterReplay.orders.filter { it["order_id"] != laterBid.id })
            assertEquals(completed.settlements, afterReplay.settlements)
            assertEquals(completed.postings, afterReplay.postings)
            println(
                "HTTP 202 동일 토큰 재전송 ${replay.body}\n" +
                    "주문 ${replay.id}, 접수 ${replay.admissionSequence}, 마켓 접수 ${replay.marketAdmissionSequence}, " +
                    "체결 $trade 1건, 예약 102 → 0 USDT, 원장 정산 ${completed.ledger.last().sequence}, " +
                    "재전송 이후 새 예약만 반영한 원장 ${afterReplay.ledger.last().sequence}",
            )
        }
    }

    @Test
    fun `정상 재기동 뒤 원래 주문이 먼저 체결되고 종료된 주문의 재전송은 중복 반영되지 않는다`() {
        verifyGtcRestart(directory) { database, cluster, client ->
            val jdbc = database.jdbc()
            val first = TradingHttp(client, cluster.start(), cluster::recordHttp)
            val (originalBuy, firstSell) = partiallyFill(first, cluster)
            val cancelledBid = first.submit(order("later-buyer", "BUY", quantity = 1, price = 150, sequence = 1))
            first.awaitOrder(cancelledBid, "RESTING", filled = 0, remaining = 1, resultSequence = 3)
            first.cancel(cancelledBid.id, user = "later-buyer", sequence = 2)
            first.awaitOrder(cancelledBid, "CANCELLED", filled = 0, remaining = 0, cancelled = 1, resultSequence = 4)
            val laterBid = first.submit(order("later-buyer", "BUY", quantity = 1, sequence = 3))
            first.awaitOrder(laterBid, "RESTING", filled = 0, remaining = 1, resultSequence = 5)
            val firstTrade = assertPartialTrade(first, originalBuy, firstSell, ledgerSequence = 9)
            first.awaitBalance("later-buyer", "USDT", 9, available = 198, reserved = 102)
            first.awaitBook(resultSequence = 5, bids = 3)
            val beforeRestart = readDatabase(jdbc)
            val progress = readProgress(cluster)

            val restarted = TradingHttp(client, cluster.restart(expectedThrough = 5), cluster::recordHttp)
            restarted.awaitOrder(cancelledBid, "CANCELLED", filled = 0, remaining = 0, cancelled = 1, resultSequence = 4)
            restarted.awaitOrder(laterBid, "RESTING", filled = 0, remaining = 1, resultSequence = 5)
            restarted.awaitBalance("later-buyer", "USDT", 9, available = 198, reserved = 102)
            restarted.awaitBook(resultSequence = 5, bids = 3)
            assertEquals(firstTrade, assertPartialTrade(restarted, originalBuy, firstSell, ledgerSequence = 9))
            assertEquals(progress, readProgress(cluster))
            assertEquals(beforeRestart, readDatabase(jdbc))

            // 주문 POST의 최초 토큰을 그대로 재전송한다. 취소 POST는 별도 요청이다.
            assertEquals(firstSell, restarted.submit(firstSell.body))
            assertEquals(cancelledBid, restarted.submit(cancelledBid.body))
            assertEquals(originalBuy, restarted.submit(originalBuy.body))
            restarted.awaitOrder(cancelledBid, "CANCELLED", filled = 0, remaining = 0, cancelled = 1, resultSequence = 4)
            restarted.awaitBook(resultSequence = 5, bids = 3)
            assertEquals(firstTrade, assertPartialTrade(restarted, originalBuy, firstSell, ledgerSequence = 9))
            assertEquals(progress, readProgress(cluster), "재전송은 접수·전달·정산 순번을 소비하지 않는다")
            assertEquals(beforeRestart, readDatabase(jdbc), "재전송은 새 예약·체결·분개를 만들지 않는다")

            val followUp = restarted.submit(order("seller", "SELL", quantity = 2, sequence = 2))
            assertEquals(laterBid.admissionSequence + 1, followUp.admissionSequence)
            assertEquals(laterBid.marketAdmissionSequence + 1, followUp.marketAdmissionSequence)
            restarted.awaitOrder(originalBuy, "FILLED", filled = 3, remaining = 0, resultSequence = 6)
            restarted.awaitOrder(followUp, "FILLED", filled = 2, remaining = 0, resultSequence = 6)
            restarted.awaitOrder(laterBid, "RESTING", filled = 0, remaining = 1, resultSequence = 5)
            restarted.awaitBook(resultSequence = 6, bids = 1)
            restarted.awaitBalance("buyer", "USDT", 11, available = 696, reserved = 0)
            restarted.awaitBalance("buyer", "BTC", 11, available = 3, reserved = 0)
            restarted.awaitBalance("seller", "USDT", 11, available = 295, reserved = 0)
            restarted.awaitBalance("seller", "BTC", 11, available = 0, reserved = 0)
            restarted.awaitBalance("later-buyer", "USDT", 11, available = 198, reserved = 102)
            val fills = restarted.awaitFills(originalBuy, 2, feeTotal = 4)
            assertEquals(firstTrade, assertFill(fills[0], quantity = 1, role = "TAKER", fee = 2))
            val secondTrade = assertFill(fills[1], quantity = 2, role = "MAKER", fee = 2)
            assertTrue(secondTrade != firstTrade)
            val laterSellFill = restarted.awaitFills(followUp, 1, feeTotal = 4).single()
            assertEquals(secondTrade, assertFill(laterSellFill, quantity = 2, role = "TAKER", fee = 4))

            val completed = readDatabase(jdbc)
            assertEquals(beforeRestart.ledger, completed.ledger.take(beforeRestart.ledger.size))
            assertLedger(completed, ledgerThrough = 11, reserveCount = 5, settlementCount = 2, releaseCount = 1)
            assertTransfer(completed.entry(7), "RESERVE", "later-buyer", "USDT", 153)
            assertTransfer(completed.entry(8), "RELEASE", "later-buyer", "USDT", 153)
            assertTransfer(completed.entry(9), "RESERVE", "later-buyer", "USDT", 102)
            assertTransfer(completed.entry(10), "RESERVE", "seller", "BTC", 2)
            assertSettlement(
                completed,
                firstTrade,
                firstSell,
                originalBuy,
                resultSequence = 2,
                quantity = 1,
                makerFee = 1,
                takerFee = 2,
                expectedPostings = firstFillPostings,
            )
            assertSettlement(
                completed,
                secondTrade,
                originalBuy,
                followUp,
                resultSequence = 6,
                quantity = 2,
                makerFee = 2,
                takerFee = 4,
                expectedPostings = remainingFillPostings,
            )
            assertEquals(beforeRestart.settlements, completed.settlements.take(1))
            assertEquals(beforeRestart.postings, completed.postings.filter { it["settlement_id"] == firstTrade })
            assertNextResult(progress, cluster)
        }
    }

    @Test
    fun `정상 재기동 뒤 남은 주문을 취소하면 같은 요청을 다시 보내도 204만 반환한다`() {
        verifyGtcRestart(directory) { database, cluster, client ->
            val jdbc = database.jdbc()
            val first = TradingHttp(client, cluster.start(), cluster::recordHttp)
            val (buy, firstSell) = partiallyFill(first, cluster)
            val firstTrade = assertPartialTrade(first, buy, firstSell, ledgerSequence = 6)
            first.awaitBook(resultSequence = 2, bids = 2)
            val beforeRestart = readDatabase(jdbc)
            val progress = readProgress(cluster)

            val restarted = TradingHttp(client, cluster.restart(expectedThrough = 2), cluster::recordHttp)
            assertEquals(firstTrade, assertPartialTrade(restarted, buy, firstSell, ledgerSequence = 6))
            restarted.awaitBook(resultSequence = 2, bids = 2)
            assertEquals(progress, readProgress(cluster))
            assertEquals(beforeRestart, readDatabase(jdbc))

            val cancelled = restarted.cancel(buy.id, user = "buyer", sequence = 2)
            restarted.awaitOrder(buy, "CANCELLED", filled = 1, remaining = 0, cancelled = 2, resultSequence = 3)
            restarted.awaitBook(resultSequence = 3, bids = 0)
            restarted.awaitBalance("buyer", "USDT", 7, available = 898, reserved = 0)
            restarted.awaitBalance("buyer", "BTC", 7, available = 1, reserved = 0)
            restarted.awaitBalance("seller", "USDT", 7, available = 99, reserved = 0)
            restarted.awaitBalance("seller", "BTC", 7, available = 2, reserved = 0)
            val retainedFill = restarted.awaitFills(buy, 1, feeTotal = 2).single()
            assertEquals(firstTrade, assertFill(retainedFill, quantity = 1, role = "TAKER", fee = 2))
            val returned = readDatabase(jdbc)
            assertEquals(beforeRestart.ledger, returned.ledger.take(beforeRestart.ledger.size))
            assertEquals(beforeRestart.settlements, returned.settlements)
            assertEquals(beforeRestart.postings, returned.postings)
            assertLedger(returned, ledgerThrough = 7, reserveCount = 2, settlementCount = 1, releaseCount = 1)
            assertTransfer(returned.entry(7), "RELEASE", "buyer", "USDT", 204)
            assertSettlement(
                returned,
                firstTrade,
                firstSell,
                buy,
                resultSequence = 2,
                quantity = 1,
                makerFee = 1,
                takerFee = 2,
                expectedPostings = firstFillPostings,
            )
            assertNextResult(progress, cluster)

            val afterCancellation = readProgress(cluster)
            assertEquals(progress.admissionSequence, afterCancellation.admissionSequence)
            assertEquals(cancelled, restarted.cancel(buy.id, user = "buyer", sequence = 2))
            restarted.awaitOrder(buy, "CANCELLED", filled = 1, remaining = 0, cancelled = 2, resultSequence = 3)
            restarted.awaitBalance("buyer", "USDT", 7, available = 898, reserved = 0)
            assertEquals(retainedFill, restarted.awaitFills(buy, 1, feeTotal = 2).single())
            assertEquals(afterCancellation, readProgress(cluster))
            assertEquals(returned, readDatabase(jdbc))

            // 재전송 이후의 실제 예약까지 반영돼야, 이전 조회 사본만 읽고 통과하지 않는다.
            val laterBid = restarted.submit(order("later-buyer", "BUY", quantity = 1, sequence = 1))
            restarted.awaitOrder(laterBid, "RESTING", filled = 0, remaining = 1, resultSequence = 4)
            restarted.awaitBook(resultSequence = 4, bids = 1)
            restarted.awaitBalance("later-buyer", "USDT", 8, available = 198, reserved = 102)
            restarted.awaitBalance("buyer", "USDT", 8, available = 898, reserved = 0)
            val afterReplay = readDatabase(jdbc)
            assertEquals(returned.ledger, afterReplay.ledger.dropLast(1))
            assertTransfer(afterReplay.entry(8), "RESERVE", "later-buyer", "USDT", 102)
            assertEquals(returned.settlements, afterReplay.settlements)
            assertEquals(returned.postings, afterReplay.postings)
            assertEquals(returned.orders, afterReplay.orders.filter { it["order_id"] != laterBid.id })
        }
    }

    private fun partiallyFill(
        http: TradingHttp,
        cluster: GtcRestartCluster,
    ): Pair<AcceptedOrder, AcceptedOrder> {
        for (user in listOf("buyer", "seller", "later-buyer")) http.openChannel(user)
        val sell = http.submit(order("seller", "SELL", quantity = 1, sequence = 1))
        http.awaitOrder(sell, "RESTING", filled = 0, remaining = 1, resultSequence = 1)
        http.awaitBook(resultSequence = 1, bids = 0, asks = 1)
        cluster.snapshot()
        val buy = http.submit(order("buyer", "BUY", quantity = 3, sequence = 1))
        http.awaitOrder(buy, "PARTIALLY_FILLED", filled = 1, remaining = 2, resultSequence = 2)
        return buy to sell
    }

    private fun assertPartialTrade(
        http: TradingHttp,
        buy: AcceptedOrder,
        sell: AcceptedOrder,
        ledgerSequence: Long,
    ): String {
        http.awaitOrder(buy, "PARTIALLY_FILLED", filled = 1, remaining = 2, resultSequence = 2)
        http.awaitOrder(sell, "FILLED", filled = 1, remaining = 0, resultSequence = 2)
        http.awaitBalance("buyer", "USDT", ledgerSequence, available = 694, reserved = 204)
        http.awaitBalance("buyer", "BTC", ledgerSequence, available = 1, reserved = 0)
        http.awaitBalance("seller", "USDT", ledgerSequence, available = 99, reserved = 0)
        http.awaitBalance("seller", "BTC", ledgerSequence, available = 2, reserved = 0)
        val buyFill = http.awaitFills(buy, 1, feeTotal = 2).single()
        val sellFill = http.awaitFills(sell, 1, feeTotal = 1).single()
        val trade = assertFill(buyFill, quantity = 1, role = "TAKER", fee = 2)
        assertEquals(trade, assertFill(sellFill, quantity = 1, role = "MAKER", fee = 1))
        return trade
    }

    private fun assertFill(
        fill: JsonNode,
        quantity: Long,
        role: String,
        fee: Long,
    ): String {
        assertEquals(100L, fill.path("price").longValue())
        assertEquals(quantity, fill.path("quantity").longValue())
        assertEquals(role, fill.path("role").asString())
        assertEquals("COMPLETED", fill.path("settlementStatus").asString())
        assertEquals(fee.toString(), fill.path("chargedFee").path("amount").asString())
        assertEquals("USDT", fill.path("chargedFee").path("assetId").asString())
        return fill.path("tradeId").asString().also { assertTrue(it.isNotBlank(), fill.toString()) }
    }

    private fun assertLedger(
        database: DatabaseRows,
        ledgerThrough: Long,
        reserveCount: Int,
        settlementCount: Int,
        releaseCount: Int,
        buyReserve: Long = 306,
    ) {
        assertEquals((1..ledgerThrough).toList(), database.ledger.map { it.sequence })
        assertTrue(database.ledger.zipWithNext().all { (first, second) -> first.position <= second.position })
        val entries = database.ledger.flatMap { it.entries }
        assertEquals(
            mapOf("OPENING" to 3, "RESERVE" to reserveCount, "SETTLEMENT" to settlementCount, "RELEASE" to releaseCount)
                .filterValues { it > 0 },
            entries.groupingBy { it.path("type").asString() }.eachCount(),
        )
        assertEquals(entries.size, entries.map { it.path("id").asString() }.toSet().size)
        assertEquals(entries.size, entries.map { it.path("sourceEventId").asString() }.toSet().size)
        assertEquals(settlementCount, database.settlements.size)
        val initialBalances = listOf(Triple("buyer", "USDT", 1_000L), Triple("seller", "BTC", 3L), Triple("later-buyer", "USDT", 300L))
        for ((user, asset, amount) in initialBalances) {
            val credit = Posting("USER:$user:$asset:AVAILABLE", asset, "CREDIT", amount)
            val opening = entries.single { it.path("type").asString() == "OPENING" && credit in postings(it) }
            assertEquals(listOf(Posting("SYSTEM:$asset:DEVELOPMENT_FUNDING", asset, "DEBIT", amount), credit), postings(opening))
        }
        assertTransfer(database.entry(4), "RESERVE", "seller", "BTC", 1)
        assertTransfer(database.entry(5), "RESERVE", "buyer", "USDT", buyReserve)
    }

    private fun assertTransfer(
        entry: JsonNode,
        type: String,
        user: String,
        asset: String,
        amount: Long,
    ) {
        assertEquals(type, entry.path("type").asString())
        val (debit, credit) = if (type == "RESERVE") "AVAILABLE" to "HOLD" else "HOLD" to "AVAILABLE"
        assertEquals(
            listOf(
                Posting("USER:$user:$asset:$debit", asset, "DEBIT", amount),
                Posting("USER:$user:$asset:$credit", asset, "CREDIT", amount),
            ),
            postings(entry),
        )
    }

    private fun assertSettlement(
        database: DatabaseRows,
        trade: String,
        maker: AcceptedOrder,
        taker: AcceptedOrder,
        resultSequence: Long,
        quantity: Long,
        makerFee: Long,
        takerFee: Long,
        expectedPostings: List<Posting>,
    ) {
        val header = database.settlements.single { it["settlement_id"] == trade }
        assertEquals(maker.id, header["maker_order_id"])
        assertEquals(taker.id, header["taker_order_id"])
        assertEquals(resultSequence, header["result_sequence"])
        assertEquals(100L, header["price"])
        assertEquals(quantity, header["quantity"])
        assertEquals(makerFee, header["maker_fee"])
        assertEquals(takerFee, header["taker_fee"])

        val entry = database.ledger.flatMap { it.entries }.single { it.path("id").asString() == trade }
        assertEquals("SETTLEMENT", entry.path("type").asString())
        assertEquals(header["source_event_id"], entry.path("sourceEventId").asString())
        val stored =
            database.postings.filter { it["settlement_id"] == trade }.map { row ->
                Posting(row["account_id"] as String, row["asset_id"] as String, row["side"] as String, row["amount"] as Long)
            }
        assertEquals(postings(entry), stored)
        assertEquals(expectedPostings.groupingBy { it }.eachCount(), stored.groupingBy { it }.eachCount(), "체결 $trade 분개")
    }

    private fun assertNextResult(
        before: EngineProgress,
        cluster: GtcRestartCluster,
    ) {
        val after = readProgress(cluster)
        assertEquals(checkNotNull(before.market.dispatchIssued) + 1, after.market.dispatchIssued)
        assertEquals(checkNotNull(before.market.settledResultThrough) + 1, after.market.settledResultThrough)
    }

    private fun readProgress(cluster: GtcRestartCluster): EngineProgress {
        val reply = cluster.progress()
        assertEquals("PROGRESS", reply.code)
        return EngineProgress(checkNotNull(reply.lastAdmissionSequence), reply.markets.single { it.marketId == MARKET })
    }

    private data class EngineProgress(
        val admissionSequence: Long,
        val market: BoundedMarketProgress,
    )

    private data class AcceptedOrder(
        val body: String,
        val id: String,
        val admissionSequence: Long,
        val marketAdmissionSequence: Long,
    )

    private data class Posting(
        val account: String,
        val asset: String,
        val side: String,
        val amount: Long,
    )

    private data class Batch(
        val sequence: Long,
        val position: Long,
        val entries: List<JsonNode>,
    )

    private data class DatabaseRows(
        val ledger: List<Batch>,
        val settlements: List<Map<String, Any?>>,
        val postings: List<Map<String, Any?>>,
        val orders: List<Map<String, Any?>>,
    ) {
        fun entry(sequence: Long): JsonNode = ledger.single { it.sequence == sequence }.entries.single()
    }

    private class TradingHttp(
        private val client: HttpClient,
        private val url: String,
        private val recordResponse: (HttpResponse<String>) -> Unit,
    ) {
        fun openChannel(user: String) {
            val receipt = post("/channels/0/epoch", """{"userId":"$user","expectedEpoch":0}""", expectedStatus = 200)
            assertEquals("ISSUED", receipt.path("status").asString())
            assertEquals(1L, receipt.path("epoch").longValue())
        }

        fun submit(body: String): AcceptedOrder {
            val receipt = post(ORDERS, body, expectedStatus = 202)
            assertEquals("ACCEPTED", receipt.path("status").asString())
            val id = receipt.path("orderId").asString()
            assertTrue(id.isNotBlank(), receipt.toString())
            val admission = receipt.path("admissionSequence").longValue()
            val marketAdmission = receipt.path("marketAdmissionSequence").longValue()
            assertTrue(admission > 0 && marketAdmission > 0, receipt.toString())
            return AcceptedOrder(body, id, admission, marketAdmission)
        }

        fun cancel(
            id: String,
            user: String,
            sequence: Int,
        ): String {
            val body = """{"userId":"$user","channel":0,"epoch":1,"sequence":$sequence,"expectedConfigurationVersion":1}"""
            val receipt = post("$ORDERS/$id/cancel", body, expectedStatus = 202)
            assertEquals("ACCEPTED", receipt.path("status").asString())
            assertEquals(id, receipt.path("orderId").asString())
            return receipt.path("cancelId").asString().also { assertTrue(it.isNotBlank(), receipt.toString()) }
        }

        fun awaitOrder(
            order: AcceptedOrder,
            state: String,
            filled: Long,
            remaining: Long,
            resultSequence: Long,
            cancelled: Long = 0,
        ) {
            awaitGet("$ORDERS/${order.id}", "$state, 체결 $filled, 잔량 $remaining, 취소 $cancelled, 결과 $resultSequence") { body ->
                body.path("code").asString() == "ORDER_FOUND" && body.path("orderId").asString() == order.id &&
                    body.path("state").asString() == state && body.path("financialStatus").asString() == "APPLIED" &&
                    body.path("admissionSequence").longValue() == order.admissionSequence &&
                    body.path("marketAdmissionSequence").longValue() == order.marketAdmissionSequence &&
                    body.path("filledQuantity").longValue(-1) == filled &&
                    body.path("remainingQuantity").longValue(-1) == remaining &&
                    body.path("cancelledQuantity").longValue(-1) == cancelled &&
                    body.path("lastAffectedResultSequence").longValue() == resultSequence
            }
        }

        fun awaitFills(
            order: AcceptedOrder,
            count: Int,
            feeTotal: Long,
        ): List<JsonNode> {
            val result =
                awaitGet("$ORDERS/${order.id}/fills", "체결 $count 건과 확정 수수료 $feeTotal USDT") { body ->
                    body.path("orderId").asString() == order.id && body.path("fills").size() == count &&
                        body.path("fills").values().all { it.path("settlementStatus").asString() == "COMPLETED" } &&
                        body.path("settledFeeTotal").path("assetId").asString() == "USDT" &&
                        body.path("settledFeeTotal").path("amount").asString() == feeTotal.toString()
                }
            return result.path("fills").values().toList()
        }

        fun awaitBalance(
            user: String,
            asset: String,
            ledgerSequence: Long,
            available: Long,
            reserved: Long,
        ) {
            awaitGet("/users/$user/balances/$asset", "가용 $available, 예약 $reserved, 원장 $ledgerSequence") { body ->
                body.path("code").asString() == "BALANCE_FOUND" && body.path("userId").asString() == user &&
                    body.path("assetId").asString() == asset && body.path("asOfLedgerSequence").longValue() == ledgerSequence &&
                    body.path("available").longValue(-1) == available && body.path("reserved").longValue(-1) == reserved
            }
        }

        fun awaitBook(
            resultSequence: Long,
            bids: Int,
            asks: Int = 0,
        ) {
            fun levels(quantity: Int) = json.readTree(if (quantity == 0) "[]" else """[{"price":100,"quantity":"$quantity"}]""")
            val bidLevels = levels(bids)
            val askLevels = levels(asks)
            awaitGet("/markets/$MARKET/order-book", "결과 $resultSequence, 매수 $bids, 매도 $asks") { body ->
                body.path("code").asString() == "ORDER_BOOK_FOUND" && body.path("marketId").asString() == MARKET &&
                    body.path("asOfMatchingResultSequence").longValue() == resultSequence &&
                    body.path("bids") == bidLevels && body.path("asks") == askLevels
            }
        }

        private fun post(
            path: String,
            body: String,
            expectedStatus: Int,
        ): JsonNode {
            val request =
                request(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            recordResponse(response)
            assertEquals(expectedStatus, response.statusCode(), "POST $path: ${response.body()}")
            return json.readTree(response.body())
        }

        private fun awaitGet(
            path: String,
            expected: String,
            matches: (JsonNode) -> Boolean,
        ): JsonNode {
            val request = request(path).GET().build()
            val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
            do {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                recordResponse(response)
                val body = json.readTree(response.body())
                if (response.statusCode() == 200 && matches(body)) return body
                if (System.nanoTime() >= deadline) {
                    fail("GET $path 기대값 $expected. 마지막 응답 ${response.statusCode()}: ${response.body()}")
                }
                Thread.sleep(25)
            } while (true)
        }

        private fun request(path: String) = HttpRequest.newBuilder(URI.create("$url/api/trading/v3$path")).timeout(Duration.ofSeconds(5))
    }

    companion object {
        private const val MARKET = "BTC-USDT"
        private const val ORDERS = "/markets/$MARKET/orders"
        private const val NAMESPACE = "41:42"
        private val json = JsonMapper.builder().build()
        private val firstFillPostings =
            listOf(
                Posting("USER:seller:BTC:HOLD", "BTC", "DEBIT", 1),
                Posting("USER:seller:USDT:AVAILABLE", "USDT", "CREDIT", 99),
                Posting("SYSTEM:USDT:FEE_REVENUE", "USDT", "CREDIT", 1),
                Posting("USER:buyer:USDT:HOLD", "USDT", "DEBIT", 102),
                Posting("USER:buyer:BTC:AVAILABLE", "BTC", "CREDIT", 1),
                Posting("SYSTEM:USDT:FEE_REVENUE", "USDT", "CREDIT", 2),
            )
        private val remainingFillPostings =
            listOf(
                Posting("USER:buyer:USDT:HOLD", "USDT", "DEBIT", 202),
                // 남은 예약 204 중 202를 쓰고 초과 예약 2를 가용 잔고로 돌린다.
                Posting("USER:buyer:USDT:HOLD", "USDT", "DEBIT", 2),
                Posting("USER:buyer:USDT:AVAILABLE", "USDT", "CREDIT", 2),
                Posting("USER:buyer:BTC:AVAILABLE", "BTC", "CREDIT", 2),
                Posting("SYSTEM:USDT:FEE_REVENUE", "USDT", "CREDIT", 2),
                Posting("USER:seller:BTC:HOLD", "BTC", "DEBIT", 2),
                Posting("USER:seller:USDT:AVAILABLE", "USDT", "CREDIT", 196),
                Posting("SYSTEM:USDT:FEE_REVENUE", "USDT", "CREDIT", 4),
            )

        private fun order(
            user: String,
            side: String,
            quantity: Int,
            sequence: Int,
            price: Int = 100,
        ) = """{"userId":"$user","channel":0,"epoch":1,"sequence":$sequence,"expectedConfigurationVersion":1,""" +
            """"side":"$side","orderType":"LIMIT","timeInForce":"GTC","price":$price,"quantity":$quantity}"""

        private fun readDatabase(jdbc: JdbcTemplate): DatabaseRows {
            val ledger =
                jdbc.query(
                    "select sequence, log_position, transactions::text from order_query.funds_ledger_batch " +
                        "where namespace = ? order by sequence",
                    { row, _ ->
                        val entries = json.readTree(row.getString("transactions")).values().toList()
                        Batch(row.getLong("sequence"), row.getLong("log_position"), entries)
                    },
                    NAMESPACE,
                )
            val settlements =
                jdbc.queryForList(
                    "select * from order_query.settlement_transaction where namespace = ? and market_id = ? order by engine_sequence",
                    NAMESPACE,
                    MARKET,
                )
            val postings =
                jdbc.queryForList(
                    "select * from order_query.settlement_posting where namespace = ? order by settlement_id, posting_sequence",
                    NAMESPACE,
                )
            val orders =
                jdbc.queryForList(
                    "select order_id, admission_sequence, record_payload::text from order_query.order_record " +
                        "where namespace = ? and market_id = ? order by admission_sequence",
                    NAMESPACE,
                    MARKET,
                )
            return DatabaseRows(ledger, settlements, postings, orders)
        }

        private fun postings(entry: JsonNode): List<Posting> =
            entry.path("postings").values().map { row ->
                Posting(
                    account = row.path("accountId").asString(),
                    asset = row.path("assetId").asString(),
                    side = row.path("side").asString(),
                    amount = row.path("amount").longValue(),
                )
            }
    }
}
