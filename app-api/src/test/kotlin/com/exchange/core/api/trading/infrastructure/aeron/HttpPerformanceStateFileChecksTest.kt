package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Small, independently calculated arithmetic fixtures; no Docker, Aeron, HTTP, or domain workload. */
class HttpPerformanceStateFileChecksTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private var fixtureNumber = 0

    @Test
    @org.junit.jupiter.api.Timeout(10)
    fun `초기 세 원본 스캔부터 행 상한을 거절하며 비교 reader를 남기지 않는다`() {
        val refs =
            write(
                copies(fixture("complete")) { _, node ->
                    val row = node.at("/applied/state/requests/0") as ObjectNode
                    val extra = mapper.createArrayNode()
                    repeat(16_384) { extra.add(0) }
                    row.set("extra", extra)
                },
            )
        // A caller may still request metadata-only scans; fast comparison itself must enforce every row cap.
        for (reference in refs.values) {
            HttpPerformanceCheckpointReader.scanRows(reference, onRow = {
                _,
                _,
                ->
                error("metadata must not emit rows")
            }, includeRow = { false })
        }
        val existing = Thread.getAllStackTraces().keys.toSet()
        val failure = assertFailsWith<IllegalArgumentException> { checkHttpPerformanceCheckpointCopiesFiles(refs) }
        assertTrue(failure.message.orEmpty().contains("row node 상한 초과"))
        val remaining = Thread.getAllStackTraces().keys.filter { it !in existing && it.isAlive && it.name.startsWith("checkpoint-") }
        assertTrue(remaining.isEmpty(), "남은 section reader: ${remaining.map { it.name }}")
        assertTrue(refs.values.all { Files.isRegularFile(it.file) })
    }

    @Test
    fun `금융 callback이 없는 node1과 node2의 oversized row도 최초 검사에서 거절한다`() {
        for (memberWithLargeRow in listOf(1, 2)) {
            val refs =
                write(
                    copies(fixture("complete")) { member, node ->
                        if (member == memberWithLargeRow) {
                            val extra = mapper.createArrayNode()
                            repeat(16_384) { extra.add(0) }
                            (node.at("/applied/state/requests/0") as ObjectNode).set("extra", extra)
                        }
                    },
                )
            val failure = assertFailsWith<IllegalArgumentException> { checkHttpPerformanceCheckpointCopiesFiles(refs) }
            assertTrue(failure.message.orEmpty().contains("row node 상한 초과"))
        }
    }

    @Test
    fun `완료 부분완료 거절 미송신 부분관찰의 결과가 기존 금융 검사와 같다`() {
        val expected =
            mapOf(
                "complete" to Triple(2L, 0, 2L),
                "pending" to Triple(2L, 0, 1L),
                "rejected" to Triple(1L, 1, 1L),
                "not-sent" to Triple(0L, 0, 0L),
                "partially-observed" to Triple(1L, 0, 0L),
            )
        for ((name, counts) in expected) {
            val copies = copies(fixture(name))
            val legacy = checkHttpPerformanceState(copies, 1, 2)
            val actual = checkHttpPerformanceStateFiles(write(copies), 1, 2)
            assertEquals(legacy, actual, name)
            assertEquals(counts.first, actual["actualMeasuredAccepted"])
            assertEquals(counts.second, actual["actualMeasuredRejected"])
            assertEquals(counts.third, actual["actualMeasuredSettled"])
        }
    }

    @Test
    fun `최상위와 행 내부 객체 필드 순서는 무시한다`() {
        val original = fixture("pending")
        val copies =
            copies(original) { member, node ->
                if (member == 1) {
                    val reordered = reverseObjects(node)
                    node.removeAll()
                    for ((field, value) in reordered.properties()) node.set(field, value)
                }
            }
        assertEquals(checkHttpPerformanceState(copies, 1, 2), checkHttpPerformanceStateFiles(write(copies), 1, 2))
    }

    @Test
    fun `원문이 같고 matching 필드가 completion보다 앞서도 별도 pass에서 기존 금융 결과가 같다`() {
        val nodes =
            copies(fixture("complete")) { _, node ->
                val trading = node.path("trading") as ObjectNode
                val matching = trading.remove("matchingResults")
                val previous = trading.deepCopy()
                trading.removeAll()
                trading.set("matchingResults", matching)
                for ((name, value) in previous.properties()) trading.set(name, value)
            }
        val refs = write(nodes)
        val scans = (0..2).map { member -> HttpPerformanceCheckpointReader.scanRows(refs.getValue(member), onRow = { _, _ -> }) }
        assertTrue(compareHttpPerformanceCheckpointBusinessBytes(scans))
        assertEquals(checkHttpPerformanceState(nodes, 1, 2), checkHttpPerformanceStateFiles(refs, 1, 2))
    }

    @Test
    fun `매칭 결과 세 사본을 함께 재정렬해도 완료와 ID로 연결한다`() {
        val copies = copies(fixture("complete")) { _, node -> reverse(node.at("/trading/matchingResults") as ArrayNode) }
        assertEquals(checkHttpPerformanceState(copies, 1, 2), checkHttpPerformanceStateFiles(write(copies), 1, 2))
    }

    @Test
    fun `준비 단계의 전체 사본 대조는 최종 금융 공식과 독립적으로 호출한다`() {
        val nodes = copies(fixture("complete")) { _, node -> (node.at("/applied/state/balances/0") as ObjectNode).put("available", 999) }
        val refs = write(nodes)
        val result = checkHttpPerformanceCheckpointCopiesFiles(refs)
        assertEquals(4096L, result.appliedPosition)
        assertEquals(setOf(0, 1, 2), result.scans.keys)
        assertTrue(result.scans.values.all { it.rowCounts["/trading/completions"] == 6L && it.bookOrderCount == 0L })
        assertFails { checkHttpPerformanceStateFiles(refs, 1, 2) }
    }

    @Test
    fun `배열 순서와 합계에 쓰지 않는 필드도 세 사본 전체 동일성에 포함한다`() {
        val mutations: List<(ObjectNode) -> Unit> =
            listOf(
                { node -> (node.at("/applied/state/requests/0/feePolicy") as ObjectNode).put("scheduleVersion", 2) },
                { node -> (node.at("/applied/state/requests/0") as ObjectNode).put("occurredAt", "2026-10-27T00:00:01Z") },
                { node -> (node.at("/trading/completions/4/balanceChanges/0/next") as ObjectNode).put("available", 7) },
                { node -> reverse(node.at("/applied/state/requests") as ArrayNode) },
                { node -> reverse(node.at("/trading/matchingResults") as ArrayNode) },
                { node -> (node.at("/trading/books/0") as ObjectNode).put("lastEngineSequence", 999) },
                { node -> (node.at("/applied/state/preparations/1") as ObjectNode).put("source", "different") },
            )
        for ((index, mutation) in mutations.withIndex()) {
            val copies = copies(fixture("complete")) { member, node -> if (member == 2) mutation(node) }
            assertFailsWith<IllegalStateException>("mutation=$index") { checkHttpPerformanceState(copies, 1, 2) }
            assertFails("mutation=$index") { checkHttpPerformanceStateFiles(write(copies), 1, 2) }
        }
    }

    @Test
    fun `세 사본이 같은 금융 오류여도 잔고 최초결과 완료 정산 원장 예약 주문장 검사를 보존한다`() {
        val mutations =
            listOf<Pair<String, (ObjectNode) -> Unit>>(
                "balance" to { node -> (node.at("/applied/state/balances/0") as ObjectNode).put("available", 1001) },
                "duplicate request" to { node ->
                    val rows = node.at("/applied/state/requests") as ArrayNode
                    rows.add(rows.last().deepCopy())
                },
                "request range" to { node -> (node.at("/applied/state/requests/4") as ObjectNode).put("requestId", "M-2") },
                "unknown outcome" to { node -> (node.at("/applied/state/requests/4/result") as ObjectNode).put("outcome", "UNKNOWN") },
                "admission sequence" to { node -> (node.at("/trading/completions/4") as ObjectNode).put("admissionSequence", 1) },
                "missing matching" to { node -> (node.at("/trading/matchingResults") as ArrayNode).remove(5) },
                "duplicate matching" to { node -> (node.at("/trading/matchingResults/5") as ObjectNode).put("commandId", "EXECUTE:v1:5") },
                "matching common" to { node -> (node.at("/trading/matchingResults/4") as ObjectNode).put("marketId", "OTHER") },
                "matching event" to { node -> (node.at("/trading/matchingResults/4/events/0") as ObjectNode).put("price", 10001) },
                "event count" to { node -> (node.at("/trading/matchingResults/4/events") as ArrayNode).remove(0) },
                "settlement fee" to
                    { node -> (node.at("/trading/completions/4/settlements/0/takerPlan") as ObjectNode).put("actualFeeAmount", 21) },
                "same total wrong individual fees" to { node ->
                    (node.at("/trading/completions/4/settlements/0/makerPlan") as ObjectNode).put("actualFeeAmount", 9)
                    (node.at("/trading/completions/4/settlements/0/takerPlan") as ObjectNode).put("actualFeeAmount", 21)
                },
                "settlement quantity" to { node -> (node.at("/trading/completions/4/settlements/0") as ObjectNode).put("quantity", 1) },
                "settlement identity" to { node ->
                    (node.at("/trading/completions/4/settlements/0") as ObjectNode).put("makerUserId", "OTHER")
                },
                "duplicate transaction" to { node ->
                    (node.at("/applied/state/transactions/1") as ObjectNode).put("transactionId", "OPEN-BUY")
                },
                "transaction kind" to { node -> (node.at("/applied/state/transactions/1") as ObjectNode).put("kind", "RESERVE") },
                "posting positive" to { node -> (node.at("/applied/state/transactions/0/postings/0") as ObjectNode).put("amount", 0) },
                "posting side" to { node -> (node.at("/applied/state/transactions/0/postings/0") as ObjectNode).put("side", "UNKNOWN") },
                "asset imbalance" to { node -> (node.at("/applied/state/transactions/0/postings/0") as ObjectNode).put("assetId", "BTC") },
                "reservation quantity" to { node -> (node.at("/trading/currentReservations/0") as ObjectNode).put("initialQuantity", 1) },
                "fee remainder" to { node -> (node.at("/trading/currentReservations/0") as ObjectNode).put("feeRemainder", 1) },
                "settled amount" to { node -> (node.at("/trading/currentReservations/0") as ObjectNode).put("remainingAmount", 1) },
                "settled fee" to { node -> (node.at("/trading/currentReservations/0") as ObjectNode).put("remainingFeeReserveAmount", 1) },
                "settled status" to { node -> (node.at("/trading/currentReservations/0") as ObjectNode).put("status", "ACTIVE") },
                "unexpected remaining" to { node -> (node.at("/trading/currentReservations/0") as ObjectNode).put("remainingQuantity", 1) },
                "declared requests" to { node -> (node.at("/applied/state") as ObjectNode).put("requestRecordCount", 0) },
                "declared matching" to { node -> (node.at("/trading") as ObjectNode).put("matchingResultCount", 0) },
                "last sequence" to { node -> (node.at("/applied/state") as ObjectNode).put("lastSequence", 999) },
            )
        for ((name, mutation) in mutations) {
            val copies = copies(fixture("complete")) { _, node -> mutation(node) }
            assertFailsWith<IllegalStateException>(name) { checkHttpPerformanceState(copies, 1, 2) }
            assertFails(name) { checkHttpPerformanceStateFiles(write(copies), 1, 2) }
        }
    }

    @Test
    fun `두 원장 거래가 서로 상쇄하는 같은 계정의 오류도 거래별 자산 불균형으로 거절한다`() {
        val copies =
            copies(fixture("complete")) { _, node ->
                val first = node.at("/applied/state/transactions/0/postings/1") as ObjectNode
                val other = node.at("/applied/state/transactions/5/postings/0") as ObjectNode
                // Both are BUYER/USDT/AVAILABLE. Net account error is zero; each transaction is wrong.
                assertEquals(first.path("accountId"), other.path("accountId"))
                first.put("amount", first.path("amount").asLong() + 1)
                other.put("amount", other.path("amount").asLong() + 1)
            }
        assertFailsWith<IllegalStateException> { checkHttpPerformanceState(copies, 1, 2) }
        assertFails { checkHttpPerformanceStateFiles(write(copies), 1, 2) }
    }

    @Test
    fun `미정산 BUY와 SELL 예약의 금액 수수료 상태 및 주문장 잔량을 각각 거절한다`() {
        val mutations: List<(ObjectNode) -> Unit> =
            listOf(
                { node -> (node.at("/trading/currentReservations/2") as ObjectNode).put("remainingAmount", 1) },
                { node -> (node.at("/trading/currentReservations/2") as ObjectNode).put("remainingFeeReserveAmount", 1) },
                { node -> (node.at("/trading/currentReservations/5") as ObjectNode).put("remainingAmount", 10000) },
                { node -> (node.at("/trading/currentReservations/5") as ObjectNode).put("remainingFeeReserveAmount", 0) },
                { node -> (node.at("/trading/currentReservations/5") as ObjectNode).put("status", "SETTLED") },
                { node -> (node.at("/trading/books/0/orders/0") as ObjectNode).put("remainingQuantity", 1) },
                { node -> (node.at("/trading/books/0/orders/0") as ObjectNode).put("userId", "BUYER") },
            )
        for ((index, mutation) in mutations.withIndex()) {
            val copies = copies(fixture("pending")) { _, node -> mutation(node) }
            assertFailsWith<IllegalStateException>("mutation=$index") { checkHttpPerformanceState(copies, 1, 2) }
            assertFails("mutation=$index") { checkHttpPerformanceStateFiles(write(copies), 1, 2) }
        }
    }

    @Test
    fun `원본 바이트와 SHA 및 끝의 문법 검증을 마치기 전에 성공하지 않는다`() {
        val base = fixture("complete")
        val original = mapper.writeValueAsBytes(base)
        val corruptions =
            listOf(
                "truncated" to original.copyOf(original.size - 1),
                "trailing" to (original + " {}".toByteArray()),
                "duplicate" to ("{\"kind\":\"PERFORMANCE_CHECKPOINT\"," + String(original).removePrefix("{")).toByteArray(),
            )
        for ((name, bytes) in corruptions) {
            val refs = write(copies(base)).toMutableMap()
            val reference = refs.getValue(0)
            Files.write(reference.file, bytes)
            // Correct bytes/SHA for the bad source, so syntax and EOF cannot be hidden by hash failure.
            refs[0] = reference.copy(bytes = bytes.size.toLong(), sha256 = sha(bytes))
            assertFails(name) { checkHttpPerformanceStateFiles(refs, 1, 2) }
        }
        val refs = write(copies(base))
        assertFails { checkHttpPerformanceStateFiles(refs + (0 to refs.getValue(0).copy(bytes = refs.getValue(0).bytes + 1)), 1, 2) }
        assertFails { checkHttpPerformanceStateFiles(refs + (0 to refs.getValue(0).copy(sha256 = "0".repeat(64))), 1, 2) }
    }

    @Test
    fun `내용 식별자 null 적용상태 차단 상태 위치 차이를 거절한다`() {
        val mutations: List<(ObjectNode) -> Unit> =
            listOf(
                { it.put("runId", "OTHER") },
                { it.put("memberId", 99) },
                { it.put("label", "OTHER") },
                { it.put("kind", "OTHER") },
                { (it.path("applied") as ObjectNode).putNull("state") },
                { (it.path("trading") as ObjectNode).put("blocked", true) },
                { (it.path("applied") as ObjectNode).put("position", 4097) },
            )
        for ((index, mutation) in mutations.withIndex()) {
            val nodes = copies(fixture("complete"))
            mutation(nodes.getValue(2) as ObjectNode)
            // The constructor intentionally uses the expected header, independent of mutated content.
            assertFails("mutation=$index") { checkHttpPerformanceStateFiles(write(nodes), 1, 2) }
        }
    }

    @Test
    fun `행 크기와 compact index 상한은 명시적으로 실패하고 proof는 매칭 뒤 해제된다`() {
        assertFailsWith<IllegalArgumentException> { HttpPerformanceFinancialBounds(200, 120_001) }
        val checks = HttpPerformanceStreamingFinancialChecks(HttpPerformanceFinancialBounds(1, 2))
        val reference = write(copies(fixture("complete"))).getValue(0)
        HttpPerformanceCheckpointReader.scanRows(reference, checks::row, checks::scalar)
        assertEquals(6L, checks.retainedIndexSizes()["completionProofs"])
        assertTrue(checks.retainedIndexSizes().getValue("exactProofBytes") > 0)
        HttpPerformanceCheckpointReader.scanRows(reference, onRow = {
            pointer,
            row,
            ->
            if (pointer.startsWith("/trading/matchingResults/")) checks.matching(row)
        })
        assertEquals(0L, checks.retainedIndexSizes()["completionProofs"])
        assertEquals(0L, checks.retainedIndexSizes()["exactProofBytes"])
        assertEquals(11L, checks.retainedIndexSizes()["transactionIds"])
        checks.finish(4096)
        assertFails { HttpPerformanceCheckpointReader.scanRows(reference, onRow = { _, _ -> }, maxRowTextChars = 8) }
        val overflow = HttpPerformanceStreamingFinancialChecks(HttpPerformanceFinancialBounds(1, 1))
        val row = fixture("complete").at("/applied/state/requests/4")
        overflow.row("/applied/state/requests/0", row)
        val extra = row.deepCopy() as ObjectNode
        extra.put("requestId", "M-1")
        assertFails { overflow.row("/applied/state/requests/1", extra) }
        assertEquals(1L, overflow.retainedIndexSizes()["measuredIds"])

        val smallBounds = HttpPerformanceFinancialBounds(1, 1)
        val ledger = HttpPerformanceStreamingFinancialChecks(smallBounds)
        val transaction = fixture("complete").at("/applied/state/transactions/0").deepCopy() as ObjectNode
        for (index in 0 until smallBounds.transactions) {
            transaction.put("transactionId", "TX-$index")
            ledger.row("/applied/state/transactions/$index", transaction)
        }
        transaction.put("transactionId", "TX-overflow")
        assertFails { ledger.row("/applied/state/transactions/${smallBounds.transactions}", transaction) }
        assertEquals(smallBounds.transactions.toLong(), ledger.retainedIndexSizes()["transactionIds"])
    }

    @Test
    fun `명시된120k금융상한은60k경계뒤와120k까지허용하고정확한최대행수를계산한다`() {
        for (orders in listOf(60_000, 60_001, 119_999, 120_000)) {
            assertEquals(orders, HttpPerformanceFinancialBounds(200, orders).orders)
        }
        val bounds = HttpPerformanceFinancialBounds(200, 120_000)
        assertEquals(120_000, bounds.maximumOrders)
        assertEquals(200, bounds.maximumWarmup)
        assertEquals(16_384, bounds.maximumEventProofBytes)
        assertEquals(240_400, bounds.admissions)
        assertEquals(360_602, bounds.transactions)
    }

    @Test
    fun `120001와600k와Int범위극값은상한우회나산술overflow전에거절한다`() {
        for (orders in listOf(-1, 0, 120_001, 600_000, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("orders=$orders") { HttpPerformanceFinancialBounds(200, orders) }
        }
        for (maximum in listOf(-1, 0, 600_001, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("maximum=$maximum") {
                HttpPerformanceFinancialBounds(200, 1, maximumOrders = maximum)
            }
        }
    }

    @Test
    fun `명시적600k상한의180k300k600kcount와Longproof산술은heap검증없이정확하다`() {
        val expected =
            mapOf(
                180_000 to Triple(360_400, 540_602, 5_904_793_600L),
                300_000 to Triple(600_400, 900_602, 9_836_953_600L),
                600_000 to Triple(1_200_400, 1_800_602, 19_667_353_600L),
            )
        for ((orders, counts) in expected) {
            // Constructor arithmetic only: no cohort rows, whole financial verdict, or heap-fit claim.
            val bounds = HttpPerformanceFinancialBounds(200, orders, maximumOrders = 600_000)
            assertEquals(600_000, bounds.maximumOrders)
            assertEquals(counts.first, bounds.admissions)
            assertEquals(counts.second, bounds.transactions)
            val proofBytes = Math.multiplyExact(bounds.admissions.toLong(), bounds.maximumEventProofBytes.toLong())
            assertEquals(counts.third, proofBytes)
            assertTrue(proofBytes > Int.MAX_VALUE.toLong())
        }
        val largestProof =
            HttpPerformanceFinancialBounds(200, 600_000, maximumOrders = 600_000, maximumEventProofBytes = 1_048_576)
        assertEquals(
            1_258_710_630_400L,
            Math.multiplyExact(largestProof.admissions.toLong(), largestProof.maximumEventProofBytes.toLong()),
        )
    }

    @Test
    fun `명시적600k상한도초과와Int극값을거절하고더작은개별상한을유지한다`() {
        for (orders in listOf(-1, 0, 600_001, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("orders=$orders") {
                HttpPerformanceFinancialBounds(200, orders, maximumOrders = 600_000)
            }
        }
        val smaller = HttpPerformanceFinancialBounds(200, 180_000, maximumOrders = 180_000)
        assertEquals(360_400, smaller.admissions)
        assertEquals(540_602, smaller.transactions)
        assertFailsWith<IllegalArgumentException> { HttpPerformanceFinancialBounds(200, 180_001, maximumOrders = 180_000) }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceFinancialBounds(200, 300_001, maximumOrders = 300_000) }
    }

    @Test
    fun `파일검사기기본120k는유지하고명시적600k선택만bounds에전달한다`() {
        assertFailsWith<IllegalArgumentException> { checkHttpPerformanceStateFiles(emptyMap(), 200, 180_000) }
        // Empty references prove only that explicit bounds passed; this is not a full 180k financial check.
        val missingCopies =
            assertFailsWith<IllegalStateException> {
                checkHttpPerformanceStateFiles(emptyMap(), 200, 180_000, maxOrders = 600_000)
            }
        assertTrue(missingCopies.message.orEmpty().contains("세 노드 사후 상태가 모두 필요합니다"))
        assertFailsWith<IllegalArgumentException> { checkHttpPerformanceStateFiles(emptyMap(), 200, 600_001, maxOrders = 600_000) }
    }

    @Test
    fun `작은시험의별도상한과warmup상한은120k허용뒤에도유지한다`() {
        val small = HttpPerformanceFinancialBounds(1, 2, maximumOrders = 2, maximumWarmup = 1)
        assertEquals(6, small.admissions)
        assertEquals(11, small.transactions)
        assertFailsWith<IllegalArgumentException> { HttpPerformanceFinancialBounds(1, 3, maximumOrders = 2) }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceFinancialBounds(2, 2, maximumOrders = 2, maximumWarmup = 1) }
        for (warmup in listOf(-1, 201, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("warmup=$warmup") { HttpPerformanceFinancialBounds(warmup, 120_000) }
        }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceFinancialBounds(0, 1, maximumWarmup = 201) }
        val withoutWarmup = HttpPerformanceFinancialBounds(0, 120_000)
        assertEquals(240_000, withoutWarmup.admissions)
        assertEquals(360_002, withoutWarmup.transactions)
    }

    @Test
    fun `120k최대index와proof누적은정확한정수수학이며빈검사기는금융성공을만들지않는다`() {
        val bounds = HttpPerformanceFinancialBounds(200, 120_000)
        // These are exact count/byte bounds, not allocated/live heap sizes or throughput results.
        assertEquals(360_000, Math.multiplyExact(bounds.orders, 3))
        assertEquals(721_200, Math.multiplyExact(bounds.admissions, 3))
        val maximumProofBytes = Math.multiplyExact(bounds.admissions.toLong(), bounds.maximumEventProofBytes.toLong())
        assertEquals(3_938_713_600L, maximumProofBytes)
        assertTrue(maximumProofBytes > Int.MAX_VALUE.toLong())
        val checks = HttpPerformanceStreamingFinancialChecks(bounds)
        assertTrue(checks.retainedIndexSizes().values.all { it == 0L })
        assertFailsWith<IllegalStateException> { checks.finish(4096) }
    }

    @Test
    fun `측정requestId는120k의마지막ID만허용하고실제N의경계와중복검사는유지한다`() {
        fun rejected(id: String): JsonNode =
            mapper.valueToTree(
                mapOf(
                    "requestId" to id,
                    "order" to mapOf("userId" to "BUYER", "orderId" to id),
                    "result" to mapOf("outcome" to "REJECTED"),
                ),
            )
        val expanded = HttpPerformanceStreamingFinancialChecks(HttpPerformanceFinancialBounds(200, 120_000))
        expanded.row("/applied/state/requests/0", rejected("M-119999"))
        assertEquals(1L, expanded.retainedIndexSizes()["measuredIds"])
        assertFailsWith<IllegalStateException> { expanded.row("/applied/state/requests/1", rejected("M-119999")) }
        val outside = HttpPerformanceStreamingFinancialChecks(HttpPerformanceFinancialBounds(200, 120_000))
        val failure = assertFailsWith<IllegalStateException> { outside.row("/applied/state/requests/0", rejected("M-120000")) }
        assertTrue(failure.message.orEmpty().contains("requestId 범위 초과"))
        val previousCohort = HttpPerformanceStreamingFinancialChecks(HttpPerformanceFinancialBounds(200, 60_000))
        assertFailsWith<IllegalStateException> { previousCohort.row("/applied/state/requests/0", rejected("M-60000")) }
    }

    private fun fixture(name: String): JsonNode =
        requireNotNull(javaClass.getResourceAsStream("/http-performance-stream-fixtures/$name.json")).use { mapper.readTree(it) }

    private fun copies(
        original: JsonNode,
        mutate: (Int, ObjectNode) -> Unit = { _, _ -> },
    ): Map<Int, JsonNode> =
        (0..2).associateWith { member ->
            (original.deepCopy() as ObjectNode).also {
                it.put("memberId", member)
                mutate(member, it)
            }
        }

    private fun write(nodes: Map<Int, JsonNode>): Map<Int, HttpPerformanceCheckpointFileReference> {
        val target = Files.createDirectory(directory.resolve("fixture-${fixtureNumber++}"))
        return nodes.mapValues { (member, node) ->
            val bytes = mapper.writeValueAsBytes(node)
            val file = target.resolve("node$member-checkpoint-final.json").toAbsolutePath()
            Files.write(file, bytes)
            HttpPerformanceCheckpointFileReference(file, "stream-financial-test", member, "final", bytes.size.toLong(), sha(bytes))
        }
    }

    private fun sha(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun reverse(array: ArrayNode) {
        val values = array.toList().asReversed()
        array.removeAll()
        for (value in values) array.add(value)
    }

    private fun reverseObjects(node: JsonNode): JsonNode =
        when {
            node.isObject -> {
                mapper.createObjectNode().also { result ->
                    for ((field, value) in node.properties().toList().asReversed()) result.set(field, reverseObjects(value))
                }
            }

            node.isArray -> {
                mapper.createArrayNode().also { result -> for (value in node) result.add(reverseObjects(value)) }
            }

            else -> {
                node.deepCopy()
            }
        }
}
