package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DockerHttpPerformanceSmokeTest {
    @Test
    fun `실제 HTTP와 세 노드에서 접수와 정산 표본을 별도로 받고 수수료까지 대조한다`() {
        val root = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory"))).resolve("build/reports/http-performance/smoke")
        val result = runHttpPerformanceBaseline(HttpPerformanceOptions(1, 2, listOf(10), 1, optimizedProduct = true), 10, 1, root)
        assertTrue(result.invalidReasons.isEmpty(), "${result.invalidReasons} 보고서=${result.reportDirectory}")
        val summary = requireNotNull(result.summary)
        assertTrue(summary.completionLatencyValid, summary.instrumentationErrors.toString())
        assertEquals(2, summary.planned)
        assertEquals(2, summary.accepted)
        assertEquals(2, summary.completed)
        assertEquals(2L, summary.settlementCount)
        assertEquals(2, summary.ackRtt.sampleCount)
        assertEquals(2, summary.settlementLatency.sampleCount)
        assertEquals(0, summary.completionUnknown)
        assertEquals(90L, result.stateCheck?.get("expectedFeeRevenue"))
        assertEquals(3L, result.stateCheck?.get("actualTotalSettlements"))
        assertEquals(11, result.stateCheck?.get("ledgerTransactions"))
        val mapper = jacksonObjectMapper()
        val finalCheckpoint =
            Files.list(Path.of(result.reportDirectory)).use { files ->
                files
                    .filter { it.fileName.toString().startsWith("checkpoint-final-attempt-") }
                    .sorted()
                    .toList()
                    .last()
            }
        val original = mapper.readTree(Files.readString(finalCheckpoint))
        val mutations: List<(JsonNode) -> Unit> =
            listOf(
                { node ->
                    val balance =
                        node.at("/applied/state/balances").first {
                            it.path("userId").asString() == "BUYER" && it.path("assetId").asString() == "USDT"
                        } as ObjectNode
                    balance.put("available", balance.path("available").asLong() + 1)
                },
                { node ->
                    val transactions = node.at("/applied/state/transactions") as ArrayNode
                    transactions.remove(transactions.indexOfFirst { it.path("kind").asString() == "SETTLEMENT" })
                },
                { node ->
                    val transaction = node.at("/applied/state/transactions").first { it.path("kind").asString() == "SETTLEMENT" }
                    val posting = transaction.path("postings").first() as ObjectNode
                    posting.put("amount", posting.path("amount").asLong() + 1)
                },
                { node -> (node.at("/trading/matchingResults/0") as ObjectNode).put("orderId", "OTHER") },
                { node ->
                    val reservation = node.at("/trading/currentReservations").first { it.path("orderId").asString() == "M-0" } as ObjectNode
                    reservation.put("remainingQuantity", 100_000_000L).put("remainingAmount", 0L).put("status", "RELEASED")
                },
            )
        // 세 사본이 똑같이 잘못돼도 복제 일치만으로 정합성 검사를 통과하지 못한다.
        for (mutate in mutations) {
            val copies = (0..2).associateWith { member -> original.path(member.toString()).deepCopy().also(mutate) }
            assertFailsWith<IllegalStateException> { checkHttpPerformanceState(copies, 1, 2) }
        }
    }
}
