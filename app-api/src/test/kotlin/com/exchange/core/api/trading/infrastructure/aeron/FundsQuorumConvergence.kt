package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import java.time.Instant

internal enum class FundsQuorumStage { INITIALIZED, PROTECTED, RESOLVED, FOLLOWUP, COMPLETE }

/** 부팅·선출·단절의 성립과 별개로 각 업무 지점의 독립 정답을 판정한다. */
internal object FundsQuorumConvergence {
    private val names = (0..2).flatMap { listOf("funds$it", "btc$it", "sui$it") }.toSet()
    private val markets = listOf("BTC-USDT", "SUI-USDT")
    private val requests = listOf("B0", "P_B", "P_S", "N_B", "S_B", "S_S")
    private val digest = Regex("[0-9a-f]{64}")
    private val meaning =
        listOf(
            "marketId",
            "resultSequence",
            "resultId",
            "orderId",
            "cancelId",
            "inputSequence",
            "outcome",
            "engineBefore",
            "engineAfter",
            "events",
            "effects",
            "payloadSha256",
        )

    fun violations(
        stage: FundsQuorumStage,
        states: Map<String, JsonNode>,
        originals: Map<String, JsonNode>,
        expectedServices: Set<String> = names,
    ): List<String> {
        val c = Check()
        c.require(expectedServices.isNotEmpty() && expectedServices.all { it in names }, "명시한 검사 대상이 비었거나 알 수 없는 사본입니다")
        c.require(states.keys == expectedServices, "명시한 현재 사본 집합과 실제 관찰이 다릅니다")
        val keys =
            requests.take(
                when (stage) {
                    FundsQuorumStage.INITIALIZED -> 0
                    FundsQuorumStage.PROTECTED -> 1
                    FundsQuorumStage.RESOLVED -> 3
                    FundsQuorumStage.FOLLOWUP -> 4
                    FundsQuorumStage.COMPLETE -> 6
                },
            )
        val completed = stage == FundsQuorumStage.COMPLETE
        val live = keys.filter { !seller(it) && !(completed && it == "P_S") }
        c.require(originals.keys == keys.toSet(), "이 단계의 최초 응답 목록 불일치")
        firstReplies(c, keys, originals)

        // 진단 cache는 재시작 뒤 비어도 된다. 영속 업무 상태는 선택된 같은 업무군끼리 같아야 한다.
        for (group in listOf("funds", "btc", "sui")) {
            val copies = states.filterKeys { it in expectedServices && it.startsWith(group) }.values
            if (copies.isEmpty()) continue
            val fields =
                if (group ==
                    "funds"
                ) {
                    listOf("funds")
                } else {
                    listOf("markets", "resultEntries", "resultBytes", "activeOrders", "activeBytes")
                }
            for (field in fields) {
                val values = copies.map { if (group == "funds") it.path(field) else it.path("matching").path(field) }
                c.require(values.none { it.isMissingNode } && values.distinct().size == 1, "$group.$field 사본 의미 상태 불일치")
            }
        }
        val fundsState = states.entries.firstOrNull { it.key in expectedServices && it.key.startsWith("funds") }?.value
        val funds = fundsState?.path("funds")
        if (funds != null) {
            val balance =
                when (stage) {
                    FundsQuorumStage.INITIALIZED -> 1000L to 0L
                    FundsQuorumStage.PROTECTED -> 400L to 600L
                    FundsQuorumStage.RESOLVED -> 200L to 800L
                    FundsQuorumStage.FOLLOWUP -> 100L to 900L
                    FundsQuorumStage.COMPLETE -> 100L to 700L
                }
            balances(c, funds, balance, completed)
            c.number(funds, "lastAdmissionSequence", keys.size.toLong())
            c.number(funds, "lastCancellationSequence", 0)
            for (field in listOf("pendingOrders", "dispatchEntries", "dispatchBytes")) c.number(funds, field, 0)
            for (field in listOf("liveOrders", "activeOrders")) c.number(funds, field, live.size.toLong())
            c.bytes(funds, "activeBytes", live.size)
            receipts(c, funds, keys, originals, integer(fundsState?.path("configurationVersion")))
            liveOrders(c, funds.path("liveOrderDetails"), live, originals, completed)
            for (field in listOf("dispatch", "resultProgress")) {
                val rows = funds.path(field)
                c.require(rows.isObject && rows.properties().map { it.key }.toSet() == markets.toSet(), "자금의 두 마켓 $field 관찰 누락/추가")
            }
            val results = funds.path("resultDetails")
            c.require(results.isArray && results.size() == keys.size, "자금 전체 결과 누락/중복")
            unique(c, results.toList(), "resultId", "결과")
            val trades = results.toList().flatMap { it.path("events").toList() }.filter { it.path("kind").asString() == "TRADE" }
            c.require(trades.size == if (completed) 2 else 0, "논리 체결 수기 정답 불일치")
            unique(c, trades, "tradeId", "체결")
        }
        for ((index, marketName) in markets.withIndex()) {
            val issuedKeys = keys.filter { market(it) == marketName }
            val liveKeys = live.filter { market(it) == marketName }
            val count = issuedKeys.size.toLong()
            val settled = funds?.path("resultDetails")?.toList()?.filter { it.path("marketId").asString() == marketName }
            if (funds != null) {
                val dispatch = funds.path("dispatch").path(marketName)
                for (field in listOf("issued", "matchingAppliedThrough")) c.number(dispatch, field, count)
                for (field in listOf("dispatchEntries", "dispatchBytes")) c.number(dispatch, field, 0)
                c.number(dispatch, "activeOrders", liveKeys.size.toLong())
                c.bytes(dispatch, "activeBytes", liveKeys.size)
                empty(c, dispatch.path("outstanding"), "$marketName 미전달 payload")
                for (field in listOf(
                    "resultThrough",
                    "settledEngineSequence",
                )) {
                    c.number(funds.path("resultProgress").path(marketName), field, count)
                }
                c.require(settled?.size == issuedKeys.size, "$marketName 자금 결과 누락/중복")
                results(c, settled.orEmpty(), issuedKeys, originals, applied = true)
            }
            val label = if (index == 0) "btc" else "sui"
            for (state in states.filterKeys { it in expectedServices && it.startsWith(label) }.values) {
                val matching = state.path("matching")
                val marketRows = matching.path("markets")
                c.require(
                    marketRows.isObject && marketRows.properties().map { it.key }.toSet() == setOf(marketName),
                    "$label 자기 담당 주문장 불일치",
                )
                val row = marketRows.path(marketName)
                for (field in listOf("engineSequence", "appliedThrough", "resultIssued", "fundsSettledThrough")) c.number(row, field, count)
                for (field in listOf("resultEntries", "resultBytes")) c.number(matching, field, 0)
                c.number(matching, "activeOrders", liveKeys.size.toLong())
                c.bytes(matching, "activeBytes", liveKeys.size)
                empty(c, row.path("outstanding"), "$marketName 미확인 결과 payload")
                book(c, row.path("orders"), liveKeys, originals, completed)
                val retained = matching.path("resultDetails")
                c.require(retained.isArray && retained.size() <= issuedKeys.size, "$marketName 진단 결과 중복/미래 결과")
                val retainedRows = retained.toList()
                // 자금 관찰 없이 매칭6만 검사할 때는 생략된 결과로 통과할 수 없다.
                if (funds == null) c.require(retainedRows.size == issuedKeys.size, "$marketName 매칭 단독 관찰의 결과 누락")
                results(c, retainedRows, issuedKeys, originals, applied = false)
                for (result in retainedRows) {
                    if (settled != null) {
                        val twin = settled.singleOrNull { it.path("resultSequence") == result.path("resultSequence") }
                        c.require(twin != null && meaning.all { twin.path(it) == result.path(it) }, "$marketName 매칭 원문과 자금 결과 불일치")
                    }
                }
            }
        }
        if (funds == null) {
            val canonical =
                listOf("btc", "sui").flatMap { group ->
                    states.entries
                        .firstOrNull {
                            it.key in expectedServices && it.key.startsWith(group)
                        }?.value
                        ?.path("matching")
                        ?.path("resultDetails")
                        ?.toList()
                        .orEmpty()
                }
            unique(c, canonical, "resultId", "마켓 간 결과")
            unique(
                c,
                canonical.flatMap { it.path("events").toList() }.filter { it.path("kind").asString() == "TRADE" },
                "tradeId",
                "마켓 간 체결",
            )
        }
        return c.issues
    }

    private fun firstReplies(
        c: Check,
        keys: List<String>,
        originals: Map<String, JsonNode>,
    ) {
        val ids = keys.map { id(originals, it) }
        c.require(ids.none { it == null } && ids.distinct().size == keys.size, "서로 다른 신규 주문의 ID 누락/재사용")
        val admissions = keys.map { integer(originals[it]?.path("receipt")?.path("admissionSequence")) }
        c.require(admissions.toSet() == (1L..keys.size.toLong()).toSet(), "최초 승인 순번 누락/중복")
        for (key in keys) {
            val original = originals[key] ?: continue
            val receipt = original.path("receipt")
            c.require(original.path("code").asString() == "ACCEPTED" && receipt.path("code").asString() == "ACCEPTED", "$key 최초 성공 응답 누락")
            c.require(receipt.path("reason").isNull && receipt.path("insufficientBalance").isNull, "$key 승인에 거절 상세가 있습니다")
            c.number(receipt, "marketAdmissionSequence", input(key))
            val allowed =
                when (key) {
                    "B0" -> setOf(1L)
                    "P_B", "P_S" -> setOf(2L, 3L)
                    "N_B" -> setOf(4L)
                    else -> setOf(5L, 6L)
                }
            c.require(integer(receipt.path("admissionSequence")) in allowed, "$key 업무 단계의 승인 순번 불일치")
        }
    }

    private fun receipts(
        c: Check,
        funds: JsonNode,
        keys: List<String>,
        originals: Map<String, JsonNode>,
        version: Long?,
    ) {
        val rows = funds.path("receipts")
        c.require(rows.isArray && rows.size() == keys.size, "최초 receipt 개수 불일치")
        for (key in keys) {
            val matches = rows.toList().filter { tokenMatches(it, key) }
            c.require(matches.size == 1, "$key 원 token 기록 누락/중복")
            for (row in matches) {
                c.require(row.path("kind").asString() == "ORDER" && row.path("marketId").asString() == market(key), "$key 요청 종류/마켓 변경")
                c.require(row.path("receipt") == originals[key]?.path("receipt"), "$key 최초 receipt 변경")
                val intent = row.path("intent")
                c.require(
                    version != null && version > 0 && integer(intent.path("expectedConfigurationVersion")) == version,
                    "$key 원문 설정 버전 변경",
                )
                c.require(intent.path("side").asString() == if (seller(key)) "SELL" else "BUY", "$key 원문 방향 변경")
                c.require(
                    intent.path("orderType").asString() == "LIMIT" && intent.path("timeInForce").asString() == "GTC",
                    "$key 원문 주문 종류 변경",
                )
                c.number(intent, "price", 100)
                c.number(intent, "quantity", quantity(key))
            }
        }
    }

    private fun liveOrders(
        c: Check,
        rows: JsonNode,
        keys: List<String>,
        originals: Map<String, JsonNode>,
        complete: Boolean,
    ) {
        orderIds(c, rows, keys, originals, "활성 예약")
        for (key in keys) {
            val row = rows.toList().singleOrNull { text(it.path("orderId")) == id(originals, key) } ?: continue
            c.require(row.path("userId").asString() == "buyer" && row.path("marketId").asString() == market(key), "$key 예약 계정/마켓 불일치")
            c.require(row.path("phase").asString() == "RESTING" && row.path("status").asString() == "ACTIVE", "$key 예약 phase/status 불일치")
            c.require(row.path("assetId").asString() == "USDT", "$key 예약 자산 불일치")
            c.require(row.path("admissionSequence") == originals[key]?.path("receipt")?.path("admissionSequence"), "$key 예약 최초 승인 순번 변경")
            c.number(row, "marketAdmissionSequence", input(key))
            c.number(row, "initialQuantity", quantity(key))
            c.number(row, "remainingQuantity", remaining(key, complete))
            c.number(row, "reservedAmount", quantity(key) * 100)
            c.number(row, "remainingAmount", remaining(key, complete) * 100)
            for (field in listOf("remainingFeeReserveAmount", "feeRemainder")) c.number(row, field, 0)
        }
    }

    private fun book(
        c: Check,
        rows: JsonNode,
        keys: List<String>,
        originals: Map<String, JsonNode>,
        complete: Boolean,
    ) {
        orderIds(c, rows, keys, originals, "주문장")
        for (key in keys) {
            val row = rows.toList().singleOrNull { text(it.path("orderId")) == id(originals, key) } ?: continue
            c.require(row.path("userId").asString() == "buyer" && row.path("side").asString() == "BUY", "$key 주문장 주체/방향 불일치")
            c.number(row, "price", 100)
            c.number(row, "originalQuantity", quantity(key))
            c.number(row, "remainingQuantity", remaining(key, complete))
        }
    }

    private fun orderIds(
        c: Check,
        rows: JsonNode,
        keys: List<String>,
        originals: Map<String, JsonNode>,
        label: String,
    ) {
        c.require(
            rows.isArray && rows.size() == keys.size &&
                rows.toList().map { text(it.path("orderId")) }.toSet() == keys.map { id(originals, it) }.toSet(),
            "$label 원 주문 ID 누락/중복",
        )
    }

    private fun balances(
        c: Check,
        funds: JsonNode,
        amounts: Pair<Long, Long>,
        complete: Boolean,
    ) {
        val rows = funds.path("balances")
        val keyed = rows.toList().associateBy { it.path("userId").asString() to it.path("assetId").asString() }
        c.require(rows.isArray && keyed.size == rows.size(), "잔고 키 누락/중복")
        c.require(keyed.keys.all { it.first in setOf("buyer", "seller") && it.second in setOf("USDT", "BTC", "SUI") }, "예상 밖 잔고 계정/자산")
        val expected =
            mapOf(
                ("buyer" to "USDT") to amounts,
                ("seller" to "USDT") to ((if (complete) 200L else 0L) to 0L),
                ("buyer" to "BTC") to ((if (complete) 1L else 0L) to 0L),
                ("buyer" to "SUI") to ((if (complete) 1L else 0L) to 0L),
                ("seller" to "BTC") to ((if (complete) 0L else 1L) to 0L),
                ("seller" to "SUI") to ((if (complete) 0L else 1L) to 0L),
            )
        for ((key, amount) in expected) {
            val row = keyed[key]
            if (row == null) {
                c.require(amount == (0L to 0L), "$key 잔고 누락")
            } else {
                c.number(row, "available", amount.first)
                c.number(row, "hold", amount.second)
            }
        }
        val fees = funds.path("fees")
        c.require(
            fees.isObject &&
                fees.properties().all {
                    it.key in setOf("USDT", "BTC", "SUI") && it.value
                        .asString()
                        .toBigIntegerOrNull()
                        ?.signum() == 0
                },
            "수수료0 수기 정답 불일치",
        )
    }

    private fun results(
        c: Check,
        rows: List<JsonNode>,
        keys: List<String>,
        originals: Map<String, JsonNode>,
        applied: Boolean,
    ) {
        val sequences = rows.map { integer(it.path("resultSequence")) }
        c.require(sequences.distinct().size == rows.size, "결과 순번 중복")
        unique(c, rows, "resultId", "결과")
        for (row in rows) {
            val key = keys.singleOrNull { input(it) == integer(row.path("resultSequence")) }
            c.require(key != null, "미래 또는 다른 마켓 결과")
            if (key == null) continue
            val sequence = input(key)
            c.require(
                row.path("marketId").asString() == market(key) && text(row.path("orderId")) == id(originals, key),
                "$key 결과 원 주문/마켓 불일치",
            )
            c.require(row.path("cancelId").isNull && row.path("outcome").asString() == "MATCHED", "$key 결과 종류 불일치")
            for (field in listOf("inputSequence", "engineAfter")) c.number(row, field, sequence)
            c.number(row, "engineBefore", sequence - 1)
            c.require(text(row.path("payloadSha256"))?.matches(digest) == true, "$key 결과 원문 지문 누락")
            if (applied) c.require(runCatching { Instant.parse(row.path("appliedAt").asString()) }.isSuccess, "$key 실제 자금 반영 시점 누락")
            val event = row.path("events").toList().singleOrNull()
            c.require(row.path("events").isArray && event != null, "$key 전체 사건 누락/중복")
            if (event != null) {
                c.number(event, "engineSequence", sequence)
                c.number(event, "price", 100)
                c.number(event, "quantity", quantity(key))
                if (seller(key)) {
                    val maker = if (key == "S_B") "B0" else "P_S"
                    c.require(
                        event.path("kind").asString() == "TRADE" && text(event.path("makerOrderId")) == id(originals, maker) &&
                            text(event.path("takerOrderId")) == id(originals, key),
                        "$key FIFO 체결 상대 불일치",
                    )
                    c.require(text(event.path("tradeId")) == "${text(row.path("resultId"))}:$sequence", "$key 논리 체결 ID 불일치")
                } else {
                    c.require(
                        event.path("kind").asString() == "RESTING" && text(event.path("orderId")) == id(originals, key),
                        "$key 원 대기 사건 불일치",
                    )
                }
            }
            effects(c, row.path("effects"), key, originals)
        }
    }

    private fun effects(
        c: Check,
        rows: JsonNode,
        key: String,
        originals: Map<String, JsonNode>,
    ) {
        val expected =
            if (seller(key)) {
                val maker = if (key == "S_B") "B0" else "P_S"
                mapOf(maker to listOf(1L, quantity(maker), if (key == "S_B") 5L else 0L), key to listOf(input(key), 1L, 0L))
            } else {
                mapOf(key to listOf(input(key), quantity(key), quantity(key)))
            }
        orderIds(c, rows, expected.keys.toList(), originals, "$key 자금 효과")
        for ((target, values) in expected) {
            val row = rows.toList().singleOrNull { text(it.path("orderId")) == id(originals, target) } ?: continue
            for ((i, field) in listOf("originInputSequence", "beforeQuantity", "afterQuantity").withIndex()) c.number(row, field, values[i])
            c.require(row.path("disposition").asString() == if (values[2] == 0L) "TERMINAL" else "RESTING", "$key 자금 효과 종료 상태 불일치")
        }
    }

    private fun unique(
        c: Check,
        rows: List<JsonNode>,
        field: String,
        label: String,
    ) {
        val ids = rows.map { text(it.path(field)) }
        c.require(ids.none { it == null } && ids.distinct().size == ids.size, "$label ID 누락/중복")
    }

    private fun empty(
        c: Check,
        rows: JsonNode,
        label: String,
    ) = c.require(rows.isArray && rows.size() == 0, "$label 잔존/관찰 누락")

    private fun market(key: String): String = markets[if (key.endsWith("_S")) 1 else 0]

    private fun seller(key: String): Boolean = key.startsWith("S_")

    private fun quantity(key: String): Long = if (key == "B0") 6 else 1

    private fun remaining(
        key: String,
        complete: Boolean,
    ): Long = if (key == "B0" && complete) 5 else quantity(key)

    private fun input(key: String): Long =
        when (key) {
            "B0", "P_S" -> 1
            "P_B", "S_S" -> 2
            "N_B" -> 3
            else -> 4
        }

    private fun tokenMatches(
        row: JsonNode,
        key: String,
    ): Boolean =
        row.path("userId").asString() == (if (seller(key)) "seller" else "buyer") &&
            integer(row.path("channel")) == (if (key.endsWith("_S")) 1L else 0L) && integer(row.path("epoch")) == 1L &&
            integer(row.path("sequence")) ==
            when (key) {
                "P_B" -> 2L
                "N_B" -> 3L
                else -> 1L
            }

    private fun id(
        originals: Map<String, JsonNode>,
        key: String,
    ): String? = text(originals[key]?.path("receipt")?.path("orderId"))

    private fun integer(node: JsonNode?): Long? = if (node?.isIntegralNumber == true && node.canConvertToLong()) node.asLong() else null

    private fun text(node: JsonNode?): String? = if (node?.isTextual == true) node.asString().takeIf(String::isNotBlank) else null

    private class Check {
        val issues = mutableListOf<String>()

        fun require(
            condition: Boolean,
            message: String,
        ) {
            if (!condition) issues += message
        }

        fun number(
            node: JsonNode,
            field: String,
            expected: Long,
        ) = require(integer(node.path(field)) == expected, "$field 기대=$expected 실제=${node.path(field)}")

        fun bytes(
            node: JsonNode,
            field: String,
            entries: Int,
        ) {
            val value = integer(node.path(field))
            require(value != null && value in 0..2_097_152L && if (entries == 0) value == 0L else value > 0L, "$field 보관 개수/byte 예산 불일치")
        }
    }
}
