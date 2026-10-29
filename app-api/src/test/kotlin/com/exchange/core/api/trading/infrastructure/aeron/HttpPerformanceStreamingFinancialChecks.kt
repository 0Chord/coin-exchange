package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * The default cohort remains N=120000; callers may explicitly select a finite ceiling through 600000.
 * Smaller per-test maximumOrders still rejects excess rows; count bounds are not heap or throughput guarantees.
 */
internal data class HttpPerformanceFinancialBounds(
    val warmup: Int,
    val orders: Int,
    val maximumOrders: Int = 120_000,
    val maximumWarmup: Int = 200,
    val maximumEventProofBytes: Int = 16_384,
) {
    init {
        require(orders in 1..maximumOrders && maximumOrders <= 600_000)
        require(warmup in 0..maximumWarmup && maximumWarmup <= 200)
        require(maximumEventProofBytes in 1..1_048_576)
    }

    val admissions: Int = Math.multiplyExact(2, Math.addExact(orders, warmup))
    val transactions: Int = Math.addExact(Math.multiplyExact(3, Math.addExact(orders, warmup)), 2)
}

/**
 * Provisional accumulator: never exposes a verdict until its caller has verified every input's EOF/SHA.
 * Keeps only exact IDs, sequence/order links and bounded matching-event proofs; it never keeps a
 * completion, settlement, request, transaction, reservation, book, or checkpoint JsonNode.
 */
internal class HttpPerformanceStreamingFinancialChecks(
    private val bounds: HttpPerformanceFinancialBounds,
    private val completionProofs: HttpPerformanceCompletionProofStore =
        HttpPerformanceCompletionProofStore(bounds.admissions, bounds.maximumEventProofBytes),
) {
    private val mapper = jacksonObjectMapper()
    private val measuredIds = HashSet<String>()
    private val acceptedSequences = HashMap<Long, String>()
    private val measuredCompletions = ArrayList<Pair<Long, String>>()
    private val transactionIds = HashSet<String>()
    private val matchingIds = HashSet<String>()
    private val balances = HashMap<Pair<String, String>, Pair<Long, Long>>()
    private val accountTotals = HashMap<String, Long>()
    private val reservationHolds = HashMap<String, Long>()
    private val scalars = HashMap<String, Long>()
    private var requests = 0
    private var preparations = 0
    private var pendingOrders = 0
    private var accepted = 0L
    private var rejected = 0
    private var completions = 0
    private var settlements = 0L
    private var transactions = 0
    private var openings = 0
    private var reserves = 0L
    private var ledgerSettlements = 0L
    private var reservations = 0L
    private var resting = 0L
    private var matchings = 0
    private var proofBytes = 0L
    private var maximumProofBytes = 0L
    private var finished = false

    fun scalar(
        pointer: String,
        value: JsonNode,
    ) {
        if (pointer in scalarPaths) {
            check(value.isIntegralNumber) { "금융 상태 scalar는 정수여야 합니다: $pointer" }
            check(scalars.put(pointer, value.asLong()) == null) { "금융 상태 scalar 중복: $pointer" }
        }
        if (pointer == "/trading/blocked") check(!value.asBoolean()) { "차단된 업무 상태" }
    }

    /** Matching rows are intentionally consumed in a later pass: JSON object order is irrelevant. */
    fun row(
        pointer: String,
        value: JsonNode,
    ) {
        check(!finished)
        check(value.isObject) { "금융 상태 행은 object여야 합니다: $pointer" }
        when {
            pointer.startsWith("/applied/state/requests/") -> {
                request(value)
            }

            pointer.startsWith("/applied/state/balances/") -> {
                balance(value)
            }

            pointer.startsWith("/applied/state/transactions/") -> {
                transaction(value)
            }

            pointer.startsWith("/applied/state/preparations/") -> {
                preparations = boundedIncrement(preparations, 2, "준비 결과")
            }

            pointer.startsWith("/applied/state/pendingOrders/") -> {
                pendingOrders = boundedIncrement(pendingOrders, bounds.orders, "미완료 승인")
            }

            pointer.startsWith("/trading/completions/") -> {
                completion(value)
            }

            pointer.startsWith("/trading/currentReservations/") -> {
                reservation(value)
            }

            bookOrder.matches(pointer) -> {
                resting = Math.incrementExact(resting)
                check(resting <= bounds.orders)
                check(value.path("userId").asString() == "SELLER" && value.path("remainingQuantity").asLong() == QUANTITY)
            }

            pointer.startsWith("/trading/matchingResults/") -> {
                Unit
            }

            else -> {
                error("지원하지 않는 금융 행: $pointer")
            }
        }
    }

    private fun request(row: JsonNode) {
        requests = boundedIncrement(requests, bounds.admissions, "최초 결과")
        val requestId = identifier(row.path("requestId").asString())
        if (row.path("order").path("userId").asString() != "BUYER" || !requestId.startsWith("M-")) return
        check(measuredIds.size < bounds.orders && measuredIds.add(requestId)) { "측정 requestId 중복 또는 상한 초과" }
        check(requestId.removePrefix("M-").toIntOrNull() in 0 until bounds.orders) { "측정 requestId 범위 초과" }
        when (row.path("result").path("outcome").asString()) {
            "ACCEPTED" -> {
                accepted = Math.incrementExact(accepted)
                // Same last-value semantics as the legacy associate(), then completion joins by sequence.
                acceptedSequences[
                    row
                        .path("result")
                        .path("record")
                        .path("sequence")
                        .asLong(),
                ] =
                    identifier(row.path("order").path("orderId").asString())
            }

            "REJECTED" -> {
                rejected = Math.incrementExact(rejected)
            }

            else -> {
                error("예상하지 않은 측정 최초 결과")
            }
        }
    }

    private fun balance(row: JsonNode) {
        val key = row.path("userId").asString() to row.path("assetId").asString()
        check(key in balanceKeys) { "예상하지 않은 사용자·자산 잔고: $key" }
        balances[key] = row.path("available").asLong() to row.path("hold").asLong()
    }

    private fun completion(row: JsonNode) {
        completions = boundedIncrement(completions, bounds.admissions, "완료")
        val order = identifier(row.path("orderId").asString())
        val events = row.path("events")
        check(events.isArray && row.path("settlements").isArray) { "완료 사건·정산 배열 누락" }
        if (order.startsWith("M-")) {
            check(measuredCompletions.size < bounds.orders)
            measuredCompletions.add(row.path("admissionSequence").asLong() to order)
            check(row.path("settlements").size() == 1) { "측정 BUY는 정산 한 건이어야 합니다" }
            check(events.count { it.path("kind").asString() == "TRADE_EXECUTED" } == 1)
        }
        for (settlement in row.path("settlements")) {
            settlements = Math.incrementExact(settlements)
            check(settlements <= (bounds.orders + bounds.warmup).toLong())
            check(settlement.path("makerUserId").asString() == "SELLER" && settlement.path("takerUserId").asString() == "BUYER")
            check(settlement.path("price").asLong() == 10_000L && settlement.path("quantity").asLong() == QUANTITY)
            check(settlement.path("makerPlan").path("actualFeeAmount").asLong() == 10L)
            check(settlement.path("takerPlan").path("actualFeeAmount").asLong() == 20L)
        }
        val id = identifier(row.path("executionId").asString())
        check(!completionProofs.contains(id)) { "완료 executionId 중복: $id" }
        for (event in events) check(event.isObject) { "완료 사건은 object여야 합니다" }
        // One compact byte array contains only five common fields and exact events, never the
        // settlement/balanceChanges/reservationChanges sections of a completion.
        val proof = mapper.writeValueAsBytes(mapOf("common" to commonFields.map { row.path(it) }, "events" to events))
        check(proof.size <= bounds.maximumEventProofBytes) { "한 완료의 exact 사건 proof 상한 초과: ${proof.size}" }
        proofBytes = Math.addExact(proofBytes, proof.size.toLong())
        maximumProofBytes = maxOf(maximumProofBytes, proofBytes)
        completionProofs.append(id, proof)
    }

    /** Called only after the completion pass has ended with verified EOF/bytes/SHA. */
    fun matching(row: JsonNode) {
        check(!finished && row.isObject)
        matchings = boundedIncrement(matchings, bounds.admissions, "매칭 결과")
        val id = identifier(row.path("commandId").asString())
        check(matchingIds.add(id)) { "매칭 결과 식별자 중복: $id" }
        val completion = completionProofs.take(id)
        val proof = mapper.readTree(completion)
        for ((index, field) in commonFields.withIndex()) {
            check(
                proof.path("common").path(index) == row.path(field),
            ) { "매칭 결과와 완료의 $field 불일치" }
        }
        val events = row.path("events")
        val completedEvents = proof.path("events")
        check(events.isArray && events.size() == completedEvents.size())
        for ((index, event) in events.withIndex()) {
            check(event.isObject)
            // Legacy comparison checks every matching field, preserving completion-only event fields.
            for ((field, value) in event.properties()) {
                check(value == completedEvents.path(index).path(field)) { "매칭 사건 불일치: $field" }
            }
        }
        proofBytes = Math.subtractExact(proofBytes, completion.size.toLong())
    }

    private fun transaction(row: JsonNode) {
        transactions = boundedIncrement(transactions, bounds.transactions, "원장 거래")
        check(transactionIds.add(identifier(row.path("transactionId").asString()))) { "원장 transactionId 중복" }
        when (row.path("kind").asString()) {
            "OPENING" -> openings = Math.incrementExact(openings)
            "RESERVE" -> reserves = Math.incrementExact(reserves)
            "SETTLEMENT" -> ledgerSettlements = Math.incrementExact(ledgerSettlements)
        }
        val byAsset = HashMap<String, Long>()
        check(row.path("postings").isArray)
        for (posting in row.path("postings")) {
            val amount = posting.path("amount").asLong()
            check(amount > 0)
            val signed =
                when (posting.path("side").asString()) {
                    "CREDIT" -> amount
                    "DEBIT" -> -amount
                    else -> error("잘못된 분개 방향")
                }
            add(byAsset, posting.path("assetId").asString(), signed, MAX_ACCOUNTS)
            add(accountTotals, posting.path("accountId").asString(), signed, MAX_ACCOUNTS)
        }
        check(byAsset.isNotEmpty() && byAsset.values.all { it == 0L }) { "원장 거래의 자산별 차변·대변 불균형" }
    }

    private fun reservation(row: JsonNode) {
        reservations = Math.incrementExact(reservations)
        check(reservations <= bounds.admissions)
        val asset = row.path("assetId").asString()
        add(reservationHolds, asset, row.path("remainingAmount").asLong(), MAX_ACCOUNTS)
        check(row.path("initialQuantity").asLong() == QUANTITY && row.path("feeRemainder").asLong() == 0L)
        when (row.path("remainingQuantity").asLong()) {
            0L -> {
                check(row.path("remainingAmount").asLong() == 0L && row.path("remainingFeeReserveAmount").asLong() == 0L)
                check(row.path("status").asString() == "SETTLED")
            }

            QUANTITY -> {
                val buy = row.path("side").asString() == "BUY"
                check(row.path("remainingAmount").asLong() == if (buy) 10_020L else QUANTITY)
                check(row.path("remainingFeeReserveAmount").asLong() == if (buy) 20L else 0L)
                check(row.path("status").asString() == "ACTIVE")
            }

            else -> {
                error("고정 완전 체결 입력에 예상 밖 예약 잔량")
            }
        }
    }

    fun finish(logPosition: Long): Map<String, Any> {
        check(!finished)
        finished = true
        val a = accepted
        val c = measuredCompletions.size.toLong()
        val w = bounds.warmup.toLong()
        val n = bounds.orders.toLong()
        val m = w + n
        check(c in 0..a && a <= n) { "실제 승인·완료 관계가 잘못됐습니다: A=$a C=$c N=$n" }
        for ((sequence, orderId) in measuredCompletions) check(acceptedSequences[sequence] == orderId) { "완료와 최초 승인 sequence/orderId 불일치" }
        val expectedBalances =
            linkedMapOf(
                ("BUYER" to "USDT") to ((n - a) * 10_020 + 1_000 to (a - c) * 10_020),
                ("BUYER" to "BTC") to ((w + c) * QUANTITY to 0L),
                ("SELLER" to "USDT") to ((w + c) * 9_990 to 0L),
                ("SELLER" to "BTC") to (0L to (n - c) * QUANTITY),
            )
        for ((key, expected) in expectedBalances) {
            check(
                (balances[key] ?: (0L to 0L)) == expected,
            ) { "$key 기대=$expected 실제=${balances[key]}" }
        }
        check(required("/applied/state/preparationCount") == 2L && preparations == 2)
        check(required("/applied/state/pendingOrderCount") == a - c && pendingOrders.toLong() == a - c)
        check(required("/applied/state/lastSequence") == m + w + a)
        check(required("/applied/state/requestRecordCount") == m + w + a + rejected)
        check(requests.toLong() == required("/applied/state/requestRecordCount")) { "최초 결과의 실제 행 수 불일치" }
        check(completions.toLong() == m + w + c)
        check(required("/trading/matchingResultCount") == m + w + c)
        check(matchings == completions && completionProofs.size == 0 && proofBytes == 0L) { "완료에 해당하는 매칭 결과 누락" }
        completionProofs.verifyComplete() // Exact-byte file integrity/EOF, never a financial hash comparison.
        check(settlements == w + c)
        check(openings == 2 && reserves == m + w + a && ledgerSettlements == w + c)
        check(transactions.toLong() == 2 + m + 2 * w + a + c)
        for ((key, balance) in expectedBalances) {
            check((accountTotals["USER:${key.first}:${key.second}:AVAILABLE"] ?: 0L) == balance.first)
            check((accountTotals["USER:${key.first}:${key.second}:HOLD"] ?: 0L) == balance.second)
        }
        val fee = (w + c) * 30
        check(accountTotals["SYSTEM:USDT:FEE_REVENUE"] == fee)
        check(reservations == m + w + a)
        for (asset in listOf("BTC", "USDT")) {
            val expectedHold = expectedBalances.filterKeys { it.second == asset }.values.sumOf { it.second }
            check((reservationHolds[asset] ?: 0L) == expectedHold)
        }
        check(resting == n - c)
        return mapOf(
            "actualMeasuredAccepted" to a,
            "actualMeasuredRejected" to rejected,
            "actualMeasuredSettled" to c,
            "actualTotalSettlements" to (w + c),
            "expectedFeeRevenue" to fee,
            "expectedBalances" to expectedBalances.mapKeys { "${it.key.first}/${it.key.second}" },
            "ledgerTransactions" to transactions,
            "logPosition" to logPosition,
            "matchingResults" to completions,
        )
    }

    fun retainedIndexSizes(): Map<String, Long> =
        mapOf(
            "measuredIds" to measuredIds.size.toLong(),
            "acceptedSequences" to acceptedSequences.size.toLong(),
            "measuredCompletions" to measuredCompletions.size.toLong(),
            "transactionIds" to transactionIds.size.toLong(),
            "completionProofs" to completionProofs.size.toLong(),
            "matchingIds" to matchingIds.size.toLong(),
            "exactProofBytes" to proofBytes,
            "peakExactProofBytes" to maximumProofBytes,
        )

    private fun required(path: String) = checkNotNull(scalars[path]) { "금융 상태 scalar 누락: $path" }

    private fun boundedIncrement(
        value: Int,
        maximum: Int,
        label: String,
    ): Int =
        Math.incrementExact(value).also {
            check(it <= maximum) { "$label 누적 행 상한 초과: $it > $maximum" }
        }

    private fun add(
        map: MutableMap<String, Long>,
        key: String,
        amount: Long,
        maximum: Int,
    ) {
        identifier(key)
        check(key in map || map.size < maximum) { "고정 금융 계정/자산 상한 초과" }
        map[key] = Math.addExact(map[key] ?: 0L, amount)
    }

    private fun identifier(value: String): String = value.also { check(it.length <= 256) { "고정 업무 식별자 길이 상한 초과" } }

    companion object {
        private const val QUANTITY = 100_000_000L
        private const val MAX_ACCOUNTS = 64
        private val bookOrder = Regex("/trading/books/[0-9]+/orders/[0-9]+")
        private val commonFields = listOf("marketId", "orderId", "expectedEventCount", "firstEngineSequence", "lastEngineSequence")
        private val balanceKeys = setOf("BUYER" to "USDT", "BUYER" to "BTC", "SELLER" to "USDT", "SELLER" to "BTC")
        private val scalarPaths =
            setOf(
                "/applied/state/preparationCount",
                "/applied/state/pendingOrderCount",
                "/applied/state/lastSequence",
                "/applied/state/requestRecordCount",
                "/trading/matchingResultCount",
            )
    }
}
