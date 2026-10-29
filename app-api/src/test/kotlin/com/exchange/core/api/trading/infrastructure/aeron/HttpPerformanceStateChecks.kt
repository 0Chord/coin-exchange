package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode

/** HTTP 응답·표식 개수 대신 실제 최초 결과·정산·원장을 읽어 고정 입력의 손계산과 대조한다. */
internal fun checkHttpPerformanceState(
    checkpoints: Map<Int, JsonNode>,
    warmup: Int,
    orders: Int,
): Map<String, Any> {
    check(checkpoints.keys == setOf(0, 1, 2)) { "세 노드 사후 상태가 모두 필요합니다" }
    val positions = checkpoints.values.map { it.path("applied").path("position").asLong() }
    check(positions.distinct().size == 1) { "세 노드 적용 위치가 다릅니다: $positions" }
    val copies = checkpoints.values.map { it.path("applied").path("state") to it.path("trading") }
    check(copies.distinct().size == 1) { "세 노드의 전체 업무 상태가 다릅니다" }
    val state = copies.first().first
    val trading = copies.first().second
    val requests = state.path("requests").toList()
    val measured =
        requests.filter {
            it.path("order").path("userId").asString() == "BUYER" &&
                it.path("requestId").asString().startsWith("M-")
        }
    check(measured.map { it.path("requestId").asString() }.distinct().size == measured.size)
    check(
        measured.all {
            it
                .path("requestId")
                .asString()
                .removePrefix("M-")
                .toIntOrNull() in 0 until orders
        },
    )
    val accepted = measured.filter { it.path("result").path("outcome").asString() == "ACCEPTED" }
    val rejected = measured.filter { it.path("result").path("outcome").asString() == "REJECTED" }
    check(accepted.size + rejected.size == measured.size)
    val completions = trading.path("completions").toList()
    val measuredCompletions = completions.filter { it.path("orderId").asString().startsWith("M-") }
    val a = accepted.size.toLong()
    val c = measuredCompletions.size.toLong()
    val w = warmup.toLong()
    val n = orders.toLong()
    val m = w + n
    check(c in 0..a && a <= n) { "실제 승인·완료 관계가 잘못됐습니다: A=$a C=$c N=$n" }
    val acceptedSequences =
        accepted.associate {
            it
                .path("result")
                .path("record")
                .path("sequence")
                .asLong() to
                it.path("order").path("orderId").asString()
        }
    for (completion in measuredCompletions) {
        check(acceptedSequences[completion.path("admissionSequence").asLong()] == completion.path("orderId").asString())
        check(completion.path("settlements").size() == 1) { "측정 BUY는 정산 한 건이어야 합니다" }
        check(completion.path("events").count { it.path("kind").asString() == "TRADE_EXECUTED" } == 1)
    }
    val expectedBalances =
        mapOf(
            ("BUYER" to "USDT") to ((n - a) * 10_020 + 1_000 to (a - c) * 10_020),
            ("BUYER" to "BTC") to ((w + c) * 100_000_000 to 0L),
            ("SELLER" to "USDT") to ((w + c) * 9_990 to 0L),
            ("SELLER" to "BTC") to (0L to (n - c) * 100_000_000),
        )
    val balances =
        state.path("balances").associate {
            (it.path("userId").asString() to it.path("assetId").asString()) to (it.path("available").asLong() to it.path("hold").asLong())
        }
    for ((key, expected) in expectedBalances) check((balances[key] ?: (0L to 0L)) == expected) { "$key 기대=$expected 실제=${balances[key]}" }
    check(balances.keys.all { it in expectedBalances }) { "예상하지 않은 사용자·자산 잔고" }
    check(state.path("preparationCount").asLong() == 2L)
    check(state.path("pendingOrderCount").asLong() == a - c)
    check(state.path("lastSequence").asLong() == m + w + a)
    check(state.path("requestRecordCount").asLong() == m + w + a + rejected.size)
    check(completions.size.toLong() == m + w + c)
    check(trading.path("matchingResultCount").asLong() == m + w + c)
    check(trading.path("matchingResults").size() == completions.size)
    val matchingById = trading.path("matchingResults").associateBy { it.path("commandId").asString() }
    check(matchingById.size == completions.size) { "매칭 결과 식별자 중복" }
    for (completion in completions) {
        val matching = checkNotNull(matchingById[completion.path("executionId").asString()]) { "완료에 해당하는 매칭 결과 누락" }
        for (field in listOf("marketId", "orderId", "expectedEventCount", "firstEngineSequence", "lastEngineSequence")) {
            check(matching.path(field) == completion.path(field)) { "매칭 결과와 완료의 $field 불일치" }
        }
        check(matching.path("events").size() == completion.path("events").size())
        matching.path("events").zip(completion.path("events")).forEach { (event, completedEvent) ->
            event.properties().forEach { (field, value) -> check(value == completedEvent.path(field)) { "매칭 사건 불일치: $field" } }
        }
    }
    val settlements = completions.flatMap { it.path("settlements").toList() }
    check(settlements.size.toLong() == w + c)
    for (settlement in settlements) {
        check(settlement.path("makerUserId").asString() == "SELLER" && settlement.path("takerUserId").asString() == "BUYER")
        check(settlement.path("price").asLong() == 10_000L && settlement.path("quantity").asLong() == 100_000_000L)
        check(settlement.path("makerPlan").path("actualFeeAmount").asLong() == 10L)
        check(settlement.path("takerPlan").path("actualFeeAmount").asLong() == 20L)
    }
    val transactions = state.path("transactions").toList()
    check(transactions.map { it.path("transactionId").asString() }.distinct().size == transactions.size)
    check(transactions.count { it.path("kind").asString() == "OPENING" } == 2)
    check(transactions.count { it.path("kind").asString() == "RESERVE" }.toLong() == m + w + a)
    check(transactions.count { it.path("kind").asString() == "SETTLEMENT" }.toLong() == w + c)
    check(transactions.size.toLong() == 2 + m + 2 * w + a + c)
    val accountTotals = mutableMapOf<String, Long>()
    for (transaction in transactions) {
        val byAsset = mutableMapOf<String, Long>()
        for (posting in transaction.path("postings")) {
            val amount = posting.path("amount").asLong()
            check(amount > 0)
            val signed =
                when (posting.path("side").asString()) {
                    "CREDIT" -> amount
                    "DEBIT" -> -amount
                    else -> error("잘못된 분개 방향")
                }
            val asset = posting.path("assetId").asString()
            val account = posting.path("accountId").asString()
            byAsset[asset] = Math.addExact(byAsset[asset] ?: 0L, signed)
            accountTotals[account] = Math.addExact(accountTotals[account] ?: 0L, signed)
        }
        check(byAsset.isNotEmpty() && byAsset.values.all { it == 0L }) { "원장 거래의 자산별 차변·대변 불균형" }
    }
    for ((key, balance) in expectedBalances) {
        check((accountTotals["USER:${key.first}:${key.second}:AVAILABLE"] ?: 0L) == balance.first)
        check((accountTotals["USER:${key.first}:${key.second}:HOLD"] ?: 0L) == balance.second)
    }
    val fee = (w + c) * 30
    check(accountTotals["SYSTEM:USDT:FEE_REVENUE"] == fee)
    val reservations = trading.path("currentReservations").toList()
    check(reservations.size.toLong() == m + w + a)
    for (asset in listOf("BTC", "USDT")) {
        val expectedHold = expectedBalances.filterKeys { it.second == asset }.values.sumOf { it.second }
        check(reservations.filter { it.path("assetId").asString() == asset }.sumOf { it.path("remainingAmount").asLong() } == expectedHold)
    }
    for (reservation in reservations) {
        check(reservation.path("initialQuantity").asLong() == 100_000_000L)
        check(reservation.path("feeRemainder").asLong() == 0L)
        when (reservation.path("remainingQuantity").asLong()) {
            0L -> {
                check(reservation.path("remainingAmount").asLong() == 0L)
                check(reservation.path("remainingFeeReserveAmount").asLong() == 0L)
                check(reservation.path("status").asString() == "SETTLED")
            }

            100_000_000L -> {
                val buy = reservation.path("side").asString() == "BUY"
                check(reservation.path("remainingAmount").asLong() == if (buy) 10_020L else 100_000_000L)
                check(reservation.path("remainingFeeReserveAmount").asLong() == if (buy) 20L else 0L)
                check(reservation.path("status").asString() == "ACTIVE")
            }

            else -> {
                error("고정 완전 체결 입력에 예상 밖 예약 잔량")
            }
        }
    }
    val resting = trading.path("books").flatMap { it.path("orders").toList() }
    check(resting.size.toLong() == n - c)
    check(resting.all { it.path("userId").asString() == "SELLER" && it.path("remainingQuantity").asLong() == 100_000_000L })
    return mapOf(
        "actualMeasuredAccepted" to a,
        "actualMeasuredRejected" to rejected.size,
        "actualMeasuredSettled" to c,
        "actualTotalSettlements" to (w + c),
        "expectedFeeRevenue" to fee,
        "expectedBalances" to expectedBalances.mapKeys { "${it.key.first}/${it.key.second}" },
        "ledgerTransactions" to transactions.size,
        "logPosition" to positions.first(),
        "matchingResults" to completions.size,
    )
}
