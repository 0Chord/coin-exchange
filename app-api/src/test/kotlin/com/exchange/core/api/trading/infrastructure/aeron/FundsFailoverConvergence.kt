package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import java.time.Instant

internal enum class FundsFailoverStage { INITIALIZED, ORDER_HELD, REFUSED_HELD, BTC_RESTING, FOLLOWUP }

/** 승인 600·부족 거절 500·후속 승인 100의 독립 정답이다. 금액은 관찰 함수에서 계산하지 않는다. */
internal object FundsFailoverConvergence {
    private val markets = listOf("BTC-USDT", "SUI-USDT")
    private val names = (0..2).flatMap { listOf("funds$it", "btc$it", "sui$it") }.toSet()
    private val digest = Regex("[0-9a-f]{64}")
    private val resultFields =
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
        stage: FundsFailoverStage,
        states: Map<String, JsonNode>,
        originals: Map<String, JsonNode>,
    ): List<String> {
        val c = Check()
        c.require(states.size in 8..9 && states.keys.all { it in names }, "현재 생존8개 또는 재합류9개의 관찰이 필요합니다")
        for (group in listOf("funds", "btc", "sui")) {
            val copies = states.filterKeys { it.startsWith(group) }.values
            c.require(copies.size == if (group == "funds" && states.size == 8) 2 else 3, "$group 현재 사본 누락")
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
        val fundsState = states.entries.firstOrNull { it.key.startsWith("funds") }?.value ?: return c.issues
        val funds = fundsState.path("funds")
        val initialized = stage == FundsFailoverStage.INITIALIZED
        val followup = stage == FundsFailoverStage.FOLLOWUP
        val resting = stage in setOf(FundsFailoverStage.BTC_RESTING, FundsFailoverStage.FOLLOWUP)
        val keys =
            when (stage) {
                FundsFailoverStage.INITIALIZED -> emptyList()
                FundsFailoverStage.ORDER_HELD -> listOf("B1")
                FundsFailoverStage.FOLLOWUP -> listOf("B1", "S1", "S2")
                else -> listOf("B1", "S1")
            }
        c.require(originals.keys == keys.toSet(), "이 단계의 최초 응답 목록 불일치")
        val orders =
            if (initialized) {
                0L
            } else if (followup) {
                2L
            } else {
                1L
            }
        c.number(funds, "lastAdmissionSequence", orders)
        c.number(funds, "lastCancellationSequence", 0)
        c.number(funds, "pendingOrders", if (!initialized && !resting) 1 else 0)
        c.number(funds, "liveOrders", orders)
        c.number(funds, "activeOrders", orders)
        c.bytes(funds, "activeBytes", orders)
        c.number(funds, "dispatchEntries", if (!initialized && !resting) 1 else 0)
        c.bytes(funds, "dispatchBytes", if (!initialized && !resting) 1 else 0)
        balances(
            c,
            funds,
            if (initialized) {
                1000
            } else if (followup) {
                300
            } else {
                400
            },
            if (initialized) {
                0
            } else if (followup) {
                700
            } else {
                600
            },
        )
        receipts(c, funds, keys, originals, number(fundsState.path("configurationVersion")))
        liveOrders(c, funds, keys.filter { it != "S1" }, originals, resting)

        for ((index, market) in markets.withIndex()) {
            val label = if (index == 0) "btc" else "sui"
            val key = if (index == 0) "B1" else "S2"
            val issued = if ((index == 0 && !initialized) || (index == 1 && followup)) 1L else 0L
            val applied = if ((index == 0 && resting) || (index == 1 && followup)) 1L else 0L
            val dispatch = funds.path("dispatch").path(market)
            c.number(dispatch, "issued", issued)
            c.number(dispatch, "matchingAppliedThrough", applied)
            c.number(dispatch, "dispatchEntries", issued - applied)
            c.bytes(dispatch, "dispatchBytes", issued - applied)
            c.number(dispatch, "activeOrders", issued)
            c.bytes(dispatch, "activeBytes", issued)
            val pending = dispatch.path("outstanding")
            c.require(pending.isArray && pending.size().toLong() == issued - applied, "$market 미전달 원문 누락/중복")
            for (row in pending) {
                c.number(row, "dispatchSequence", 1)
                c.number(row, "price", 100)
                c.number(row, "quantity", 6)
                c.require(
                    row.path("marketId").asString() == markets[0] && row.path("userId").asString() == "buyer" &&
                        row.path("side").asString() == "BUY",
                    "B1 전달 원문 마켓/주체/방향 변경",
                )
                c.require(text(row.path("orderId")) == id(originals, "B1"), "B1 원본 전달 주문 변경")
                token(c, row.path("token"), "B1")
                c.sha(row, "payloadSha256")
            }
            val progress = funds.path("resultProgress").path(market)
            for (field in listOf("resultThrough", "settledEngineSequence")) c.number(progress, field, applied)
            val results = funds.path("resultDetails").toList().filter { text(it.path("marketId")) == market }
            c.require(results.size.toLong() == applied, "$market 자금 결과 누락/중복")
            val settled = results.singleOrNull()
            if (applied == 1L) {
                c.require(settled != null, "$market 자금 대기 결과 누락")
                if (settled != null) {
                    result(c, settled, market, key, originals)
                    c.require(runCatching { Instant.parse(settled.path("appliedAt").asString()) }.isSuccess, "$market 실제 자금 반영 시점 누락")
                }
            }
            for (state in states.filterKeys { it.startsWith(label) }.values) {
                val matching = state.path("matching")
                val m = matching.path("markets").path(market)
                c.require(matching.path("markets").isObject && matching.path("markets").size() == 1, "$label 자기 담당 주문장 불일치")
                for (field in listOf("engineSequence", "appliedThrough", "resultIssued", "fundsSettledThrough")) c.number(m, field, applied)
                c.number(matching, "resultEntries", 0)
                c.bytes(matching, "resultBytes", 0)
                c.number(matching, "activeOrders", applied)
                c.bytes(matching, "activeBytes", applied)
                c.require(m.path("outstanding").isArray && m.path("outstanding").size() == 0, "$market 미확인 결과 payload 잔존")
                val book = m.path("orders")
                c.require(book.isArray && book.size().toLong() == applied, "$market 원 주문장 크기 불일치")
                for (order in book) {
                    c.require(text(order.path("orderId")) == id(originals, key), "$market 원 주문 ID 변경")
                    c.require(text(order.path("userId")) == "buyer" && text(order.path("side")) == "BUY", "$market 주문 주체/방향 변경")
                    c.number(order, "price", 100)
                    for (field in listOf("originalQuantity", "remainingQuantity")) c.number(order, field, if (key == "B1") 6 else 1)
                }
                val retained = matching.path("resultDetails")
                c.require(retained.isArray && retained.size() <= applied, "$market 진단 결과 중복/미래 결과")
                for (row in retained) {
                    result(c, row, market, key, originals)
                    c.require(settled != null && resultFields.all { row.path(it) == settled.path(it) }, "$market 매칭 원문과 자금 결과 불일치")
                }
            }
        }
        val details = funds.path("resultDetails")
        c.require(
            details.isArray && details.size() ==
                if (followup) {
                    2
                } else if (resting) {
                    1
                } else {
                    0
                },
            "자금 전체 결과 개수 불일치",
        )
        c.require(
            details.toList().map { text(it.path("resultId")) }.let {
                it.none { id ->
                    id == null
                } && it.distinct().size == it.size
            },
            "결과 ID 누락/재사용",
        )
        return c.issues
    }

    fun lifecycleViolations(evidence: JsonNode): List<String> {
        val c = Check()
        c.issues += MultiMatchingFailoverConvergence.lifecycleViolations(evidence, fundsFailover = true)
        val target = text(evidence.path("target")) ?: return c.issues
        val initial = evidence.path("initial").path(target)
        val admission = evidence.path("admission")
        val completed = evidence.path("admissionCompleted")
        val commit = admission.path("commit")
        val reply = admission.path("reply")
        c.require(
            reply.path("code").asString() == "ACCEPTED" && reply.path("receipt").path("code").asString() == "ACCEPTED",
            "실제 최초 성공 응답 누락",
        )
        for (field in listOf(
            "runId",
            "service",
            "bootId",
            "memberId",
        )) {
            c.require(commit.path(field) == initial.path(field), "최초 응답의 실제 $field 불일치")
        }
        for (field in listOf(
            "leadershipTermId",
            "leaderMemberId",
        )) {
            c.require(commit.path(field) == initial.path("state").path(field), "접수와 종료 사이 실제 임기/리더 변경")
        }
        for (field in listOf(
            "correlationId",
            "requestSha256",
            "wireSha256",
        )) {
            c.require(commit.path(field) == admission.path(field), "서비스 commit·실제 응답 $field 불일치")
        }
        for (field in listOf("requestSha256", "wireSha256")) c.sha(admission, field)
        c.require(commit.path("receipt") == reply.path("receipt"), "서비스 최초 결과와 실제 수신 결과 불일치")
        c.require(
            number(commit.path("logPosition"))?.let {
                it > 0 && it == number(admission.path("appliedLogPosition"))
            } == true,
            "실제 확정 callback 위치 누락/불일치",
        )
        c.require(
            (number(admission.path("positiveOfferPosition")) ?: 0) > 0 && admission.path("transportAccepted").asBoolean(),
            "실제 요청 전송 수용 누락",
        )
        c.require((number(admission.path("correlationId")) ?: 0) > 0, "실제 요청 상관값 누락")
        val hidden = admission.path("hidden")
        c.require(
            hidden.isBoolean && admission.path("forwarded").isBoolean && admission.path("forwarded").asBoolean() != hidden.asBoolean(),
            "실제 응답 상위 전달/숨김 불일치",
        )
        c.require(completed.path("hidden") == hidden && completed.path("transportAccepted").asBoolean(), "최종 caller의 전송 수용/숨김 결과 불일치")
        c.require(completed.path("code").asString() == if (hidden.asBoolean()) "UNCONFIRMED" else "ACCEPTED", "실제 caller 성공/미확인 종료 누락")
        c.require(
            admission.path("requestReturned").asBoolean() != hidden.asBoolean() && !admission.path("unconfirmed").asBoolean(),
            "최초 callback 관찰을 caller 종료로 오인",
        )
        c.require(
            completed.path("unconfirmed").isBoolean && completed.path("unconfirmed").asBoolean() == hidden.asBoolean() &&
                completed.path("requestReturned").asBoolean() != hidden.asBoolean(),
            "실제 미확인 예외/요청 반환 결속 누락",
        )
        c.require(commit.path("clusterRole").asString() == "LEADER", "실제 응답 송신 리더 역할 누락")
        val balance = commit.path("balance")
        c.require(
            balance.path("userId").asString() == "buyer" && balance.path("assetId").asString() == "USDT",
            "확정 시점의 원 자금 잔고 누락",
        )
        c.number(balance, "available", 400)
        c.number(balance, "hold", 600)
        c.require(completed.path("reply") == reply, "caller 종료 근거의 실제 최초 응답 변경")
        val elected = evidence.path("elected")
        val newLeader =
            elected.properties().singleOrNull {
                it.key.startsWith("funds") &&
                    it.value
                        .path("state")
                        .path("clusterRole")
                        .asString() == "LEADER"
            }
        c.require(newLeader != null && newLeader.key != target, "새 자금 리더 실제 작업 누락")
        val receipt =
            elected
                .properties()
                .firstOrNull {
                    it.key.startsWith("funds")
                }?.value
                ?.path("state")
                ?.path("funds")
                ?.path("receipts")
                ?.toList()
                ?.singleOrNull {
                    it.path("channel").asLong(-1) ==
                        0L
                }
        c.require(receipt?.path("receipt") == reply.path("receipt"), "새 리더가 보존한 최초 결과 변경")
        gates(c, evidence, newLeader?.key.orEmpty())
        return c.issues
    }

    private fun gates(
        c: Check,
        evidence: JsonNode,
        leader: String,
    ) {
        val gates = evidence.path("gates")
        c.require(gates.isArray && gates.size() == 1, "BTC 주문 원본 gate 하나가 필요합니다")
        for (gate in gates) {
            c.require(gate.path("gate").asString() == "btc-dispatch", "BTC 주문 전달 이외의 보류")
            c.number(gate, "sequence", 1)
            val blocked = gate.path("blocked")
            val offered = gate.path("offered")
            val reply = gate.path("reply")
            val runtime = evidence.path("elected").path(leader)
            val pending =
                runtime
                    .path("state")
                    .path("funds")
                    .path("dispatch")
                    .path(markets[0])
                    .path("outstanding")
                    .toList()
                    .singleOrNull()
            c.require(
                gate.path("blockedService").asString() == leader && gate.path("offeredService").asString() == leader,
                "새 자금 리더 worker가 아닌 보류/재개",
            )
            for (row in listOf(blocked, offered)) {
                for (field in listOf(
                    "installationId",
                    "gate",
                    "sequence",
                )) {
                    c.require(row.path(field) == gate.path(field), "설치와 실제 작업 $field 불일치")
                }
                c.require(row.path("bootId") == runtime.path("bootId"), "새 리더 worker boot 불일치")
                c.require(
                    pending != null && listOf("orderId", "payloadSha256").all { row.path(it) == pending.path(it) },
                    "선출 뒤 전달 의무와 실제 원문 불일치",
                )
                c.sha(row, "payloadSha256")
                c.sha(row, "wireSha256")
                c.require(
                    (number(row.path("wireBytes")) ?: 0) > 0 && text(row.path("connectionId")) != null &&
                        (number(row.path("correlationId")) ?: 0) > 0,
                    "실제 전송 원문/연결/상관값 누락",
                )
                c.require(
                    row.path("marketId").asString() == markets[0] && row.path("described").path("code").asString() == "DESCRIBED" &&
                        (number(row.path("described").path("appliedLogPosition")) ?: 0) > 0,
                    "실제 BTC 상대 확인 누락",
                )
                val stream = row.path("stream")
                c.require(stream.path("direction").asString() == "FUNDS_TO_MATCHING", "전달 stream 방향 불일치")
                for ((field, owner, identity) in listOf(
                    Triple("fundsLineageHigh", "funds0", "lineageHigh"),
                    Triple("fundsLineageLow", "funds0", "lineageLow"),
                    Triple("matchingLineageHigh", "btc0", "lineageHigh"),
                    Triple("matchingLineageLow", "btc0", "lineageLow"),
                )) {
                    c.require(stream.path(field) == evidence.path("initial").path(owner).path(identity), "전달 stream lineage 불일치")
                }
                for (field in listOf(
                    "ownershipEpoch",
                    "configurationVersion",
                )) {
                    c.require((number(stream.path(field)) ?: 0) > 0, "전달 stream 버전 누락")
                }
            }
            c.require(
                blocked.path("enabled").isBoolean && blocked.path("enabled").asBoolean() && offered.path("enabled").isBoolean &&
                    !offered.path("enabled").asBoolean(),
                "실제 보류/해제 누락",
            )
            c.require((number(offered.path("positiveOfferPosition")) ?: 0) > 0, "실제 재개 전송 수용 누락")
            c.require(
                reply.path("code").asString() in setOf("APPLIED", "DUPLICATE") && (number(reply.path("through")) ?: 0) >= 1 &&
                    (number(reply.path("appliedLogPosition")) ?: 0) > 0,
                "실제 BTC 적용 응답 누락",
            )
            c.require(
                listOf("bootId", "connectionId", "correlationId", "payloadSha256").all { reply.path(it) == offered.path(it) },
                "실제 전송·응답 연결 불일치",
            )
            c.require(
                gate.path("heldWireSha256") == blocked.path("wireSha256") && gate.path("heldWireBytes") == blocked.path("wireBytes"),
                "보존 원문과 실제 worker 원문 불일치",
            )
            val timeline = evidence.path("timeline")
            val installed = number(gate.path("installedAt"))
            val blockedAt = number(gate.path("blockedAt"))
            val released = number(gate.path("releaseAt"))
            c.require(installed != null && installed <= (number(timeline.path("acceptedObserved")) ?: -1), "접수 전 gate 설치 누락")
            c.require(
                blockedAt != null && blockedAt >= (number(timeline.path("electionObserved")) ?: Long.MAX_VALUE) &&
                    blockedAt <= (released ?: -1),
                "새 리더 실제 보류→해제 순서 불일치",
            )
            c.require(
                released != null && released >= (number(timeline.path("gateReleased")) ?: Long.MAX_VALUE) &&
                    released <= (number(timeline.path("btcSettled")) ?: -1),
                "해제→실제 결과 반영 순서 불일치",
            )
        }
    }

    private fun balances(
        c: Check,
        funds: JsonNode,
        available: Long,
        hold: Long,
    ) {
        val rows = funds.path("balances")
        c.require(
            rows.isArray &&
                rows
                    .toList()
                    .map { it.path("userId").asString() to it.path("assetId").asString() }
                    .distinct()
                    .size == rows.size(),
            "잔고 키 누락/중복",
        )
        c.require(
            rows.toList().count { it.path("userId").asString() == "buyer" && it.path("assetId").asString() == "USDT" } == 1,
            "원 자금 잔고 누락",
        )
        for (row in rows) {
            val source = row.path("userId").asString() == "buyer" && row.path("assetId").asString() == "USDT"
            c.require(
                row.path("userId").asString() in setOf("buyer", "seller") && row.path("assetId").asString() in setOf("USDT", "BTC", "SUI"),
                "예상 밖 잔고 주체/자산",
            )
            c.number(row, "available", if (source) available else 0)
            c.number(row, "hold", if (source) hold else 0)
        }
        val fees = funds.path("fees")
        c.require(
            fees.isObject &&
                fees.properties().all {
                    it.key in setOf("USDT", "BTC", "SUI") && it.value.asString().toLongOrNull() == 0L
                },
            "체결0의 수수료가 생겼습니다",
        )
    }

    private fun receipts(
        c: Check,
        funds: JsonNode,
        keys: List<String>,
        originals: Map<String, JsonNode>,
        version: Long?,
    ) {
        val rows = funds.path("receipts")
        c.require(rows.isArray && rows.size() == keys.size, "최초 응답 개수 불일치")
        for (key in keys) {
            val original = originals[key]
            val expectedCode = if (key == "S1") "REJECTED" else "ACCEPTED"
            c.require(
                original?.path("code")?.asString() == expectedCode && original?.path("receipt")?.path("code")?.asString() == expectedCode,
                "$key 최초 응답 종류 불일치",
            )
            val matches =
                rows.toList().filter {
                    it.path("channel").asLong(-1) == (if (key == "B1") 0L else 1L) &&
                        it.path("sequence").asLong(-1) == (if (key == "S2") 2L else 1L)
                }
            c.require(matches.size == 1, "$key 원 token 기록 누락/중복")
            for (row in matches) {
                token(c, row, key)
                c.require(
                    row.path("kind").asString() == "ORDER" && row.path("marketId").asString() ==
                        if (key ==
                            "B1"
                        ) {
                            markets[0]
                        } else {
                            markets[1]
                        },
                    "$key 원 요청 종류/마켓 변경",
                )
                c.require(row.path("receipt") == original?.path("receipt"), "$key 최초 응답 변경")
                val intent = row.path("intent")
                c.require(version != null && version > 0 && number(intent.path("expectedConfigurationVersion")) == version, "$key 설정 버전 변경")
                for ((field, value) in listOf("side" to "BUY", "orderType" to "LIMIT", "timeInForce" to "GTC")) {
                    c.require(intent.path(field).asString() == value, "$key 원문 $field 변경")
                }
                c.number(intent, "price", 100)
                c.number(
                    intent,
                    "quantity",
                    if (key == "B1") {
                        6
                    } else if (key == "S1") {
                        5
                    } else {
                        1
                    },
                )
            }
            val receipt = original?.path("receipt")
            if (key == "S1") {
                c.require(receipt?.path("reason")?.asString() == "INSUFFICIENT_BALANCE", "S1 금융적 부족 이외의 거절")
                for (field in listOf("orderId", "admissionSequence", "marketAdmissionSequence")) {
                    c.require(receipt?.path(field)?.isNull == true, "S1 거절에 승인 식별 발급")
                }
                val insufficient = receipt?.path("insufficientBalance")
                c.require(insufficient?.path("assetId")?.asString() == "USDT", "S1 부족 자산 불일치")
                c.number(insufficient, "required", 500)
                c.number(insufficient, "available", 400)
                c.number(insufficient, "shortfall", 100)
            } else {
                c.require(
                    id(originals, key) != null && receipt?.path("reason")?.isNull == true &&
                        receipt?.path("insufficientBalance")?.isNull == true,
                    "$key 승인 식별/거절 상세 불일치",
                )
                c.number(receipt, "admissionSequence", if (key == "B1") 1 else 2)
                c.number(receipt, "marketAdmissionSequence", 1)
            }
        }
        val ids = keys.filter { it != "S1" }.map { id(originals, it) }
        c.require(ids.distinct().size == ids.size, "서로 다른 신규 주문의 ID 재사용")
    }

    private fun liveOrders(
        c: Check,
        funds: JsonNode,
        keys: List<String>,
        originals: Map<String, JsonNode>,
        resting: Boolean,
    ) {
        val rows = funds.path("liveOrderDetails")
        c.require(
            rows.isArray && rows.size() == keys.size &&
                rows.toList().map { text(it.path("orderId")) }.toSet() == keys.map { id(originals, it) }.toSet(),
            "활성 주문 예약의 원 주문 ID 누락/중복",
        )
        for (key in keys) {
            val row = rows.toList().singleOrNull { text(it.path("orderId")) == id(originals, key) } ?: continue
            c.require(row.path("phase").asString() == if (resting) "RESTING" else "PENDING", "$key 예약 상태 불일치")
            c.require(
                row.path("userId").asString() == "buyer" && row.path("marketId").asString() ==
                    if (key ==
                        "B1"
                    ) {
                        markets[0]
                    } else {
                        markets[1]
                    },
                "$key 예약 주체/마켓 불일치",
            )
            c.number(row, "admissionSequence", if (key == "B1") 1 else 2)
            c.number(row, "marketAdmissionSequence", 1)
            for (field in listOf("initialQuantity", "remainingQuantity")) c.number(row, field, if (key == "B1") 6 else 1)
            for (field in listOf("reservedAmount", "remainingAmount")) c.number(row, field, if (key == "B1") 600 else 100)
            c.number(row, "remainingFeeReserveAmount", 0)
        }
    }

    private fun result(
        c: Check,
        row: JsonNode,
        market: String,
        key: String,
        originals: Map<String, JsonNode>,
    ) {
        val order = id(originals, key)
        c.require(order != null && text(row.path("orderId")) == order && text(row.path("resultId")) != null, "$market 결과/원 주문 식별 불일치")
        c.require(
            row.path("marketId").asString() == market && row.path("outcome").asString() == "MATCHED" && row.path("cancelId").isNull,
            "$market 대기 결과 종류 불일치",
        )
        for (field in listOf("resultSequence", "inputSequence", "engineAfter")) c.number(row, field, 1)
        c.number(row, "engineBefore", 0)
        c.sha(row, "payloadSha256")
        val event = row.path("events").toList().singleOrNull()
        c.require(row.path("events").isArray && event != null, "$market 전체 대기 사건 누락/중복")
        val quantity = if (key == "B1") 6L else 1L
        if (event != null) {
            c.require(event.path("kind").asString() == "RESTING" && text(event.path("orderId")) == order, "$market 체결0의 원 대기 사건 불일치")
            c.number(event, "engineSequence", 1)
            c.number(event, "quantity", quantity)
            c.number(event, "price", 100)
        }
        val effect = row.path("effects").toList().singleOrNull()
        c.require(row.path("effects").isArray && effect != null, "$market 전체 자금 효과 누락/중복")
        if (effect != null) {
            c.require(
                text(effect.path("orderId")) == order && effect.path("disposition").asString() == "RESTING",
                "$market 자금 효과 대상/상태 불일치",
            )
            c.number(effect, "originInputSequence", 1)
            c.number(effect, "beforeQuantity", quantity)
            c.number(effect, "afterQuantity", quantity)
        }
    }

    private fun token(
        c: Check,
        row: JsonNode,
        key: String,
    ) {
        c.require(row.path("userId").asString() == "buyer", "$key 원 요청 사용자 불일치")
        c.number(row, "channel", if (key == "B1") 0 else 1)
        c.number(row, "epoch", 1)
        c.number(row, "sequence", if (key == "S2") 2 else 1)
    }

    private fun id(
        originals: Map<String, JsonNode>,
        key: String,
    ): String? = text(originals[key]?.path("receipt")?.path("orderId"))

    private fun number(node: JsonNode?): Long? = if (node?.isIntegralNumber == true && node.canConvertToLong()) node.asLong() else null

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
            node: JsonNode?,
            field: String,
            expected: Long,
        ) = require(number(node?.path(field)) == expected, "$field 기대=$expected 실제=${node?.path(field)}")

        fun sha(
            node: JsonNode,
            field: String,
        ) = require(text(node.path(field))?.matches(digest) == true, "$field 실제 원문 지문 누락")

        fun bytes(
            node: JsonNode,
            field: String,
            entries: Long,
        ) {
            val bytes = number(node.path(field))
            require(bytes != null && bytes in 0..2_097_152L && if (entries == 0L) bytes == 0L else bytes > 0L, "$field 보관 개수/byte 예산 불일치")
        }
    }
}
