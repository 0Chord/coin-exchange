package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import java.math.BigInteger
import java.time.Instant

/** 각 단계는 실제 worker가 전달·정산·확인까지 수렴한 뒤의 수기 기대값이다. */
internal enum class MultiMatchingStage { INITIALIZED, RACE, RESERVED, TRADED, BTC_CANCELLED, COMPLETE }

/** 실제 전송과 별개로 관찰의 누락·다른 담당·잘못된 금융 결과를 거절한다. */
internal object MultiMatchingConvergence {
    private val markets = listOf("BTC-USDT", "SUI-USDT")
    private val names = listOf("funds0", "funds1", "funds2", "btc0", "btc1", "btc2", "sui0", "sui1", "sui2")
    private val identity =
        listOf(
            "runId",
            "bootId",
            "role",
            "memberId",
            "clusterId",
            "lineageHigh",
            "lineageLow",
            "markets",
            "manifestSha256",
            "topologySha256",
            "resultTopologySha256",
            "pid",
        )
    private val digest = Regex("[0-9a-f]{64}")

    fun failures(
        states: Map<String, JsonNode>,
        runtimes: Map<String, JsonNode>,
        stage: MultiMatchingStage,
        originals: Map<String, JsonNode> = emptyMap(),
    ): List<String> {
        val issues = mutableListOf<String>()
        if (states.keys != names.toSet() || runtimes.keys != names.toSet()) issues += "현재 사본과 runtime이 정확히9개여야 합니다"
        val containers = mutableSetOf<String>()
        val boots = mutableSetOf<String>()
        for (name in names) {
            val state = states[name] ?: continue
            val runtime = runtimes[name] ?: continue
            if (!runtime.path("storageLevels").isArray ||
                runtime.path("storageLevels").toList().map { integer(it) } != listOf(2L, 2L, 2L)
            ) {
                issues += "$name: 실제 sync2/2/2가 아님"
            }
            val group = name.dropLast(1)
            val expectedMarkets = if (group == "funds") markets.toSet() else setOf(if (group == "btc") markets[0] else markets[1])
            for (field in identity) {
                if (state.path(field).isMissingNode || state.path(field) != runtime.path(field)) issues += "$name.$field: 현재 실행과 관찰 불일치"
            }
            if (text(state.path("runId")) == null || text(state.path("bootId")) == null) issues += "$name: run/boot 식별 누락"
            if (!boots.add(state.path("bootId").asString())) issues += "$name: 다른 사본의 boot 복제"
            val container = text(runtime.path("containerId"))
            if (container == null || !containers.add(container)) issues += "$name: 별도 container 식별 누락/복제"
            if (state.path("role").asString() != if (group == "funds") "FUNDS" else "MATCHING") issues += "$name: 역할 불일치"
            val clusterId =
                when (group) {
                    "funds" -> 198L
                    "btc" -> 199L
                    else -> 200L
                }
            if (integer(state.path("clusterId")) != clusterId ||
                integer(state.path("memberId")) != name.last().digitToInt().toLong()
            ) {
                issues += "$name: cluster/member 불일치"
            }
            if (!state.path("markets").isArray || state
                    .path("markets")
                    .toList()
                    .map { it.asString() }
                    .toSet() != expectedMarkets ||
                state.path("markets").size() != expectedMarkets.size
            ) {
                issues += "$name: 담당 마켓 불일치"
            }
            if (integer(state.path("lineageHigh")) == null || integer(state.path("lineageLow")) == null ||
                (integer(state.path("pid")) ?: 0L) <= 0
            ) {
                issues += "$name: lineage/PID 누락"
            }
            if (!state.path("initialized").isBoolean || !state.path("initialized").asBoolean() ||
                state.path("lifecycle").asString() != "RUNNING"
            ) {
                issues += "$name: 처리 준비 미완료"
            }
            if (state.path("clusterRole").asString() !in setOf("LEADER", "FOLLOWER")) issues += "$name: 선출 미완료"
        }
        for (field in listOf("manifestSha256", "topologySha256", "resultTopologySha256")) {
            val values = runtimes.values.map { text(it.path(field)) }
            if (values.size != 9 || values.any { it == null || !digest.matches(it) } ||
                values.distinct().size != 1
            ) {
                issues += "$field:9사본 공통 지문 불일치"
            }
        }
        if (runtimes.values
                .map { text(it.path("runId")) }
                .distinct()
                .size != 1
        ) {
            issues += "runId: 서로 다른 실행 혼합"
        }
        val images = runtimes.values.map { text(it.path("imageId")) }
        if (images.size != 9 || images.any { it == null } || images.distinct().size != 1) issues += "imageId: 동결 image 불일치"
        for (group in listOf("funds", "btc", "sui")) {
            val copies = (0..2).mapNotNull { states["$group$it"] }
            if (copies.size != 3) continue
            if (copies.count { it.path("clusterRole").asString() == "LEADER" } != 1) issues += "$group: 현재 리더가 하나가 아님"
            if (copies.map { it.path("lineageHigh") to it.path("lineageLow") }.distinct().size != 1) issues += "$group: 사본 lineage 불일치"
            val field = if (group == "funds") "funds" else "matching"
            if (copies.map { it.path(field) }.distinct().size != 1) issues += "$group: 세 사본 의미 상태 불일치"
        }
        val ownerLineages =
            listOf(
                "funds0",
                "btc0",
                "sui0",
            ).mapNotNull { states[it] }.map { it.path("lineageHigh") to it.path("lineageLow") }
        if (ownerLineages.size != 3 || ownerLineages.distinct().size != 3) issues += "서로 다른 세 업무 lineage가 필요합니다"
        if (names.any { it !in states || it !in runtimes }) return issues

        val winner = if (stage == MultiMatchingStage.RACE) race(states, originals, issues) else null
        if (stage == MultiMatchingStage.RACE && winner == null) return issues
        val progress =
            when (stage) {
                MultiMatchingStage.INITIALIZED -> listOf(0L, 0L)
                MultiMatchingStage.RACE -> markets.map { if (it == winner) 1L else 0L }
                MultiMatchingStage.RESERVED -> listOf(1L, 1L)
                MultiMatchingStage.TRADED -> listOf(2L, 2L)
                MultiMatchingStage.BTC_CANCELLED -> listOf(3L, 2L)
                MultiMatchingStage.COMPLETE -> listOf(3L, 3L)
            }
        val quantities =
            when (stage) {
                MultiMatchingStage.INITIALIZED, MultiMatchingStage.COMPLETE -> {
                    listOf(0L, 0L)
                }

                MultiMatchingStage.RACE -> {
                    markets.map {
                        if (it == winner) {
                            if (it == markets[0]) {
                                6L
                            } else {
                                5L
                            }
                        } else {
                            0L
                        }
                    }
                }

                MultiMatchingStage.RESERVED -> {
                    listOf(2L, 3L)
                }

                MultiMatchingStage.TRADED -> {
                    listOf(1L, 2L)
                }

                MultiMatchingStage.BTC_CANCELLED -> {
                    listOf(0L, 2L)
                }
            }
        val availableHold =
            when (stage) {
                MultiMatchingStage.INITIALIZED -> 1000L to 0L
                MultiMatchingStage.RACE -> if (winner == markets[0]) 400L to 600L else 500L to 500L
                MultiMatchingStage.RESERVED -> 495L to 505L
                MultiMatchingStage.TRADED -> 495L to 303L
                MultiMatchingStage.BTC_CANCELLED -> 596L to 202L
                MultiMatchingStage.COMPLETE -> 798L to 0L
            }
        val traded = stage in setOf(MultiMatchingStage.TRADED, MultiMatchingStage.BTC_CANCELLED, MultiMatchingStage.COMPLETE)
        val required =
            when (stage) {
                MultiMatchingStage.INITIALIZED -> emptyList()
                MultiMatchingStage.RACE -> listOf("race-btc", "race-sui")
                MultiMatchingStage.RESERVED -> listOf("buy-btc", "buy-sui")
                MultiMatchingStage.TRADED -> listOf("buy-btc", "buy-sui", "sell-btc", "sell-sui")
                MultiMatchingStage.BTC_CANCELLED -> listOf("buy-btc", "buy-sui", "sell-btc", "sell-sui", "cancel-btc")
                MultiMatchingStage.COMPLETE -> listOf("buy-btc", "buy-sui", "sell-btc", "sell-sui", "cancel-btc", "cancel-sui")
            }
        for (key in required) {
            if (originals[key]?.path("receipt")?.isObject != true) issues += "$key: 원래 제어 응답 누락"
            if (originals[key]?.path("code") != originals[key]?.path("receipt")?.path("code")) issues += "$key: 최초 응답과 receipt 코드 불일치"
        }
        val funds = states.getValue("funds0").path("funds")
        balances(funds, stage, availableHold, traded, issues)
        val admissions =
            when (stage) {
                MultiMatchingStage.INITIALIZED -> 0L
                MultiMatchingStage.RACE -> 1L
                MultiMatchingStage.RESERVED -> 2L
                else -> 4L
            }
        number(funds, "lastAdmissionSequence", admissions, "funds", issues)
        val cancellations =
            when (stage) {
                MultiMatchingStage.BTC_CANCELLED -> 1L
                MultiMatchingStage.COMPLETE -> 2L
                else -> 0L
            }
        number(funds, "lastCancellationSequence", cancellations, "funds", issues)
        val active = quantities.count { it > 0 }.toLong()
        for (field in listOf("activeOrders", "liveOrders")) number(funds, field, active, "funds", issues)
        for (field in listOf("dispatchEntries", "dispatchBytes", "pendingOrders")) number(funds, field, 0, "funds", issues)
        bytes(funds, "activeBytes", active, "funds", issues, 1_048_576)
        receipts(funds, required, originals, issues)
        for ((index, market) in markets.withIndex()) {
            val name = if (index == 0) "btc" else "sui"
            val count = progress[index]
            val fDispatch = funds.path("dispatch").path(market)
            number(fDispatch, "issued", count, "$market.dispatch", issues)
            number(fDispatch, "matchingAppliedThrough", count, "$market.dispatch", issues)
            number(fDispatch, "dispatchEntries", 0, "$market.dispatch", issues)
            number(fDispatch, "dispatchBytes", 0, "$market.dispatch", issues)
            number(fDispatch, "activeOrders", if (quantities[index] > 0) 1 else 0, "$market.dispatch", issues)
            bytes(fDispatch, "activeBytes", if (quantities[index] > 0) 1 else 0, "$market.dispatch", issues, 1_048_576)
            val fResult = funds.path("resultProgress").path(market)
            number(fResult, "resultThrough", count, "$market.result", issues)
            number(fResult, "settledEngineSequence", count, "$market.result", issues)
            val matching = states.getValue("${name}0").path("matching")
            if (!matching.path("markets").isObject || matching.path("markets").size() != 1) issues += "$name: 자기 주문장 하나가 필요합니다"
            val m = matching.path("markets").path(market)
            for (field in listOf(
                "engineSequence",
                "appliedThrough",
                "resultIssued",
                "fundsSettledThrough",
            )) {
                number(m, field, count, market, issues)
            }
            for (field in listOf("resultEntries", "resultBytes")) number(matching, field, 0, name, issues)
            number(matching, "activeOrders", if (quantities[index] > 0) 1 else 0, name, issues)
            bytes(matching, "activeBytes", if (quantities[index] > 0) 1 else 0, name, issues)
            book(
                m.path("orders"),
                market,
                quantities[index],
                if (stage ==
                    MultiMatchingStage.RACE
                ) {
                    "race-$name"
                } else {
                    "buy-$name"
                },
                originals,
                stage,
                issues,
            )
            results(funds.path("resultDetails"), matching.path("resultDetails"), market, count, originals, stage, issues)
        }
        if (!funds.path("dispatch").isObject || funds.path("dispatch").size() != 2 || !funds.path("resultProgress").isObject ||
            funds.path("resultProgress").size() != 2
        ) {
            issues += "자금의 두 market별 진행 관찰이 필요합니다"
        }
        val details = funds.path("resultDetails")
        if (!details.isArray || details.size().toLong() != progress.sum()) issues += "자금 결과 전체 개수 불일치"
        if (details.toList().map { text(it.path("resultId")) }.let { it.any { id -> id == null } || it.distinct().size != it.size }) {
            issues +=
                "결과 ID 누락/중복"
        }
        val trades = details.toList().flatMap { it.path("events").toList() }.count { it.path("kind").asString() == "TRADE" }
        if (trades != if (traded) 2 else 0) issues += "체결 수기 기대값 불일치"
        return issues
    }

    private fun race(
        states: Map<String, JsonNode>,
        originals: Map<String, JsonNode>,
        issues: MutableList<String>,
    ): String? {
        val rows = states.getValue("funds0").path("raceRows")
        if ((1..2).any { states.getValue("funds$it").path("raceRows") != rows }) issues += "최초 요청의 자금3사본 로그 위치 불일치"
        if (!rows.isArray || rows.size() != 2 ||
            rows.toList().map { text(it.path("marketId")) }.toSet() != markets.toSet()
        ) {
            issues += "두 원본 요청 로그 관찰 누락/중복"
            return null
        }
        val positions = rows.toList().map { integer(it.path("position")) }
        if (positions.any { it == null || it <= 0 } || positions.distinct().size != 2) {
            issues += "현재 요청의 두 고유 로그 위치가 필요합니다"
            return null
        }
        val winner =
            rows
                .toList()
                .minBy { it.path("position").asLong() }
                .path("marketId")
                .asString()
        for (row in rows) {
            val market = row.path("marketId").asString()
            val key = if (market == markets[0]) "race-btc" else "race-sui"
            val accepted = market == winner
            val expected = if (accepted) "ACCEPTED" else "REJECTED"
            number(row, "channel", if (market == markets[0]) 0 else 1, key, issues)
            number(row, "sequence", 1, key, issues)
            number(row, "epoch", 1, key, issues)
            if (row.path("code").asString() != expected ||
                originals[key]?.path("receipt")?.path("code")?.asString() != expected
            ) {
                issues += "$key: 실제 로그 순서와 최초 승인/거절 불일치"
            }
            if (accepted &&
                text(row.path("orderId")) != text(originals[key]?.path("receipt")?.path("orderId"))
            ) {
                issues += "$key: 원래 주문 ID와 로그 관찰 불일치"
            }
            if (!accepted &&
                (text(row.path("orderId")) != null || originals[key]?.path("receipt")?.path("reason")?.asString() != "INSUFFICIENT_BALANCE")
            ) {
                issues +=
                    "$key: 부족 거절이 주문을 발급했습니다"
            }
        }
        return winner
    }

    private fun balances(
        funds: JsonNode,
        stage: MultiMatchingStage,
        q: Pair<Long, Long>,
        traded: Boolean,
        issues: MutableList<String>,
    ) {
        val rows = funds.path("balances")
        if (!rows.isArray) {
            issues += "잔고 관찰 누락"
            return
        }
        val keyed = rows.associateBy { it.path("userId").asString() to it.path("assetId").asString() }
        if (keyed.size != rows.size()) issues += "중복 잔고 키"
        if (keyed.keys.any { it.first !in setOf("buyer", "seller") || it.second !in setOf("USDT", "BTC", "SUI") }) {
            issues +=
                "알 수 없는 잔고 계정/자산"
        }

        fun expected(
            user: String,
            asset: String,
            available: Long,
            hold: Long = 0,
        ) {
            val value = keyed[user to asset]
            if (value == null) {
                if (available != 0L || hold != 0L) issues += "$user/$asset: 잔고 누락"
                return
            }
            number(value, "available", available, "$user/$asset", issues)
            number(value, "hold", hold, "$user/$asset", issues)
        }
        expected("buyer", "USDT", q.first, q.second)
        expected("seller", "USDT", if (traded) 198 else 0)
        for (asset in listOf("BTC", "SUI")) {
            expected("buyer", asset, if (traded) 1 else 0)
            if (stage == MultiMatchingStage.INITIALIZED) {
                val amount = keyed["seller" to asset]?.path("available")?.let(::integer) ?: 0L
                if (amount !in 0L..1L) issues += "seller/$asset: 준비 수량 불일치"
                expected("seller", asset, amount)
            } else {
                expected("seller", asset, if (stage == MultiMatchingStage.RESERVED) 1 else 0)
            }
        }
        if (stage == MultiMatchingStage.INITIALIZED &&
            (keyed["seller" to "BTC"]?.path("available")?.let(::integer) ?: 0L) !=
            (keyed["seller" to "SUI"]?.path("available")?.let(::integer) ?: 0L)
        ) {
            issues += "판매자 두 자산 준비 상태 불일치"
        }
        val fees = funds.path("fees")
        val expectedFee = if (traded) BigInteger.valueOf(4) else BigInteger.ZERO
        val actualFee = if (fees.has("USDT")) fees.path("USDT").asString().toBigIntegerOrNull() else BigInteger.ZERO
        if (!fees.isObject || actualFee != expectedFee) issues += "USDT 수수료 수기 기대값 불일치"
        for ((asset, value) in fees.properties()) {
            if (asset !in setOf("USDT", "BTC", "SUI") ||
                (asset != "USDT" && value.asString().toBigIntegerOrNull() != BigInteger.ZERO)
            ) {
                issues += "예상 밖 수수료 자산"
            }
        }
    }

    private fun receipts(
        funds: JsonNode,
        required: List<String>,
        originals: Map<String, JsonNode>,
        issues: MutableList<String>,
    ) {
        val rows = funds.path("receipts")
        if (!rows.isArray || rows.size() != required.size) issues += "최초 사용자 응답 개수 불일치"
        val expected =
            required.associateBy { key ->
                val parts = key.split('-')
                val actor = if (parts[0] == "sell") "seller" else "buyer"
                listOf(
                    actor,
                    if (parts[1] ==
                        "btc"
                    ) {
                        "0"
                    } else {
                        "1"
                    },
                    if (parts[0] == "cancel") "2" else "1",
                    if (parts[0] == "cancel") "CANCEL" else "ORDER",
                )
            }
        val seen = mutableSetOf<List<String>>()
        for (row in rows) {
            val id =
                listOf(
                    row.path("userId").asString(),
                    row.path("channel").asString(),
                    row.path("sequence").asString(),
                    row.path("kind").asString(),
                )
            val key = expected[id]
            if (!seen.add(id) || key == null) {
                issues += "최초 사용자 응답의 요청 identity 누락/중복"
                continue
            }
            number(row, "epoch", 1, key, issues)
            if (row.path("marketId").asString() != if (id[1] == "0") markets[0] else markets[1]) issues += "$key: 최초 응답 대상 마켓 불일치"
            if (row.path("receipt") != originals[key]?.path("receipt")) issues += "$key: 최초 응답 내용 변경"
            val original = originals[key]?.path("receipt") ?: continue
            if (original.path("code").asString() == "ACCEPTED" && id[3] == "ORDER" &&
                text(original.path("orderId")) == null
            ) {
                issues += "$key: 승인 주문 ID 누락"
            }
            if (id[3] == "CANCEL") {
                val buy = originals["buy-${key.substringAfter('-')}"]?.path("receipt")?.path("orderId")
                if (original.path("code").asString() != "ACCEPTED" || text(original.path("cancelId")) == null ||
                    text(original.path("targetOrderId")) != text(buy)
                ) {
                    issues += "$key: 원래 구매 주문의 취소 접수 결과 불일치"
                }
                number(original, "inputSequence", 3, key, issues)
            }
        }
        val accepted =
            required.filterNot { it.startsWith("cancel") }.mapNotNull { originals[it]?.path("receipt") }.filter {
                it.path("code").asString() ==
                    "ACCEPTED"
            }
        if (accepted.map { text(it.path("orderId")) }.let { it.any { id -> id == null } || it.distinct().size != it.size }) {
            issues +=
                "고유한 최초 주문 ID가 필요합니다"
        }
        val admissions =
            required
                .filterNot { it.startsWith("cancel") }
                .mapNotNull { originals[it]?.path("receipt") }
                .filter {
                    it.path("code").asString() ==
                        "ACCEPTED"
                }.map { integer(it.path("admissionSequence")) }
        if (admissions.any { it == null } || admissions.toSet() != (1L..admissions.size.toLong()).toSet()) issues += "최초 신규 승인 순번 누락/중복"
    }

    private fun book(
        orders: JsonNode,
        market: String,
        remaining: Long,
        key: String,
        originals: Map<String, JsonNode>,
        stage: MultiMatchingStage,
        issues: MutableList<String>,
    ) {
        if (!orders.isArray || orders.size() != if (remaining > 0) 1 else 0) {
            issues += "$market: 주문장 주문 수 불일치"
            return
        }
        if (remaining == 0L) return
        val order = orders.toList().single()
        val id = text(originals[key]?.path("receipt")?.path("orderId"))
        if (id == null || text(order.path("orderId")) != id || order.path("userId").asString() != "buyer" ||
            order.path("side").asString() != "BUY"
        ) {
            issues += "$market: 원래 구매 주문과 주문장 대상 불일치"
        }
        number(order, "price", 100, market, issues)
        number(order, "remainingQuantity", remaining, market, issues)
        number(
            order,
            "originalQuantity",
            if (stage ==
                MultiMatchingStage.RACE
            ) {
                if (market == markets[0]) {
                    6
                } else {
                    5
                }
            } else if (market == markets[0]) {
                2
            } else {
                3
            },
            market,
            issues,
        )
    }

    private fun results(
        all: JsonNode,
        retained: JsonNode,
        market: String,
        count: Long,
        originals: Map<String, JsonNode>,
        stage: MultiMatchingStage,
        issues: MutableList<String>,
    ) {
        val suffix = if (market == markets[0]) "btc" else "sui"
        val rows = all.toList().filter { it.path("marketId").asString() == market }
        if (!all.isArray || !retained.isArray || rows.size.toLong() != count ||
            retained.size().toLong() != count
        ) {
            issues += "$market: 전체 결과 누락/중복"
        }
        if (rows.toList().map { integer(it.path("resultSequence")) }.toSet() != (1L..count).toSet()) issues += "$market: 결과 순서 누락/중복"
        for (row in rows) {
            val sequence = integer(row.path("resultSequence")) ?: continue
            val originalKey =
                when (sequence) {
                    1L -> {
                        if (stage ==
                            MultiMatchingStage.RACE
                        ) {
                            "race-$suffix"
                        } else {
                            "buy-$suffix"
                        }
                    }

                    2L -> {
                        "sell-$suffix"
                    }

                    3L -> {
                        "cancel-$suffix"
                    }

                    else -> {
                        issues +=
                            "$market: 예상 밖 결과"
                        continue
                    }
                }
            val receipt = originals[originalKey]?.path("receipt")
            val orderId = if (sequence == 3L) text(receipt?.path("targetOrderId")) else text(receipt?.path("orderId"))
            if (orderId == null || text(row.path("orderId")) != orderId) issues += "$market/$sequence: 결과 대상 주문 불일치"
            if (sequence == 3L && text(row.path("cancelId")) != text(receipt?.path("cancelId"))) issues += "$market: 취소 결과 identity 불일치"
            number(row, "inputSequence", sequence, market, issues)
            number(row, "engineBefore", sequence - 1, market, issues)
            number(row, "engineAfter", sequence, market, issues)
            if (row.path("outcome").asString() != if (sequence == 3L) "CANCELLED" else "MATCHED") issues += "$market: 결과 outcome 불일치"
            val twin = retained.toList().singleOrNull { integer(it.path("resultSequence")) == sequence }
            if (twin == null ||
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
                ).any {
                    row.path(it) !=
                        twin.path(it)
                }
            ) {
                issues += "$market/$sequence: 매칭 원본과 자금 정산 결과 불일치"
            }
            if (runCatching { Instant.parse(row.path("appliedAt").asString()) }.isFailure) issues += "$market: 원래 정산 시각 누락"
            val events = row.path("events")
            val event = events.toList().singleOrNull()
            if (!events.isArray || event == null) {
                issues += "$market: 한 결과의 전체 사건 필요"
                continue
            }
            number(event, "engineSequence", sequence, market, issues)
            if (sequence == 2L) {
                if (event.path("kind").asString() != "TRADE" ||
                    text(event.path("makerOrderId")) != text(originals["buy-$suffix"]?.path("receipt")?.path("orderId")) ||
                    text(event.path("takerOrderId")) != orderId
                ) {
                    issues += "$market: 체결 상대 불일치"
                }
                number(event, "quantity", 1, market, issues)
                number(event, "price", 100, market, issues)
            } else {
                if (event.path("kind").asString() != (if (sequence == 3L) "CANCELLED" else "RESTING") ||
                    text(event.path("orderId")) != orderId
                ) {
                    issues += "$market: 대기/취소 사건 불일치"
                }
                val quantity =
                    if (sequence ==
                        1L
                    ) {
                        if (stage ==
                            MultiMatchingStage.RACE
                        ) {
                            if (suffix ==
                                "btc"
                            ) {
                                6L
                            } else {
                                5L
                            }
                        } else if (suffix == "btc") {
                            2L
                        } else {
                            3L
                        }
                    } else if (suffix == "btc") {
                        1L
                    } else {
                        2L
                    }
                number(event, "quantity", quantity, market, issues)
                if (sequence == 1L) number(event, "price", 100, market, issues)
            }
            val buyer =
                text(
                    originals[
                        if (stage ==
                            MultiMatchingStage.RACE
                        ) {
                            "race-$suffix"
                        } else {
                            "buy-$suffix"
                        },
                    ]?.path("receipt")?.path("orderId"),
                )
            val originalQuantity =
                if (stage ==
                    MultiMatchingStage.RACE
                ) {
                    if (suffix == "btc") {
                        6L
                    } else {
                        5L
                    }
                } else if (suffix == "btc") {
                    2L
                } else {
                    3L
                }
            val expectedEffects =
                when (sequence) {
                    1L -> {
                        mapOf(buyer to listOf(1L, originalQuantity, originalQuantity, "RESTING"))
                    }

                    2L -> {
                        mapOf(
                            buyer to listOf(1L, originalQuantity, originalQuantity - 1, "RESTING"),
                            orderId to listOf(2L, 1L, 0L, "TERMINAL"),
                        )
                    }

                    else -> {
                        mapOf(buyer to listOf(1L, originalQuantity - 1, 0L, "CANCELLED"))
                    }
                }
            val effects = row.path("effects")
            if (!effects.isArray || effects.size() != expectedEffects.size ||
                effects.toList().map { text(it.path("orderId")) }.toSet() != expectedEffects.keys
            ) {
                issues += "$market: 전체 자금 효과 대상 누락/중복"
            }
            for (effect in effects) {
                val targets = expectedEffects[text(effect.path("orderId"))] ?: continue
                for ((i, field) in listOf(
                    "originInputSequence",
                    "beforeQuantity",
                    "afterQuantity",
                ).withIndex()) {
                    number(effect, field, targets[i] as Long, market, issues)
                }
                if (effect.path("disposition").asString() != targets[3]) issues += "$market: 효과 종료 상태 불일치"
            }
        }
    }

    private fun number(
        node: JsonNode,
        field: String,
        expected: Long,
        label: String,
        issues: MutableList<String>,
    ) {
        if (integer(node.path(field)) !=
            expected
        ) {
            issues += "$label.$field: expected=$expected actual=${node.path(field)}"
        }
    }

    private fun bytes(
        node: JsonNode,
        field: String,
        entries: Long,
        label: String,
        issues: MutableList<String>,
        maximum: Long = 2_097_152,
    ) {
        val n = integer(node.path(field))
        if (n ==
            null ||
            n < 0 ||
            n > maximum ||
            (entries == 0L && n != 0L) ||
            (entries > 0 && n == 0L)
        ) {
            issues += "$label.$field: 보관 수와 byte 예산 불일치"
        }
    }

    private fun integer(node: JsonNode): Long? = if (node.isIntegralNumber && node.canConvertToLong()) node.asLong() else null

    private fun text(node: JsonNode?): String? = if (node?.isTextual == true) node.asString().takeIf { it.isNotBlank() } else null
}
