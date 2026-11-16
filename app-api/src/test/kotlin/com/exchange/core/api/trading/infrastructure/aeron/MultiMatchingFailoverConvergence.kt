package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import java.time.Instant

internal enum class MultiMatchingFailoverStage {
    ORDER_HELD,
    BUY_RESTING,
    RESULT_HELD,
    ORDER_HELD_SUI_RESERVED,
    RESULT_HELD_SUI_RESERVED,
    ORDER_HELD_SUI_TRADED,
    RESULT_HELD_SUI_TRADED,
    BUY_RESTING_SUI_TRADED,
    TRADED,
    FOLLOWUP,
}

/** 장애 중간창의 수기 정답이다. 현재 실행·probe의 결속은 별도 수명 판정에서 검사한다. */
internal object MultiMatchingFailoverConvergence {
    private val markets = listOf("BTC-USDT", "SUI-USDT")
    private val names = (0..2).flatMap { listOf("funds$it", "btc$it", "sui$it") }.toSet()
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

    private data class Expected(
        val btcIssued: Long,
        val btcApplied: Long,
        val btcSettled: Long,
        val sui: Long,
        val available: Long,
        val hold: Long,
    )

    fun violations(
        stage: MultiMatchingFailoverStage,
        states: Map<String, JsonNode>,
        originals: Map<String, JsonNode>,
    ): List<String> {
        val c = Check()
        val e = expected(stage)
        c.require(states.keys.all { it in names } && states.size in 8..9, "현재 생존8개 또는 재합류9개의 관찰이 필요합니다")
        for (group in listOf("funds", "btc", "sui")) {
            val copies = states.filterKeys { it.startsWith(group) }.values
            c.require(copies.size == if (group == "btc" && states.size == 8) 2 else 3, "$group: 현재 사본 누락")
            val field = if (group == "funds") "funds" else "matching"
            // 매칭의 진단 cache는 재합류 시 비어도 된다. 영속 의미 상태만 사본끼리 대조한다.
            val fields =
                if (group ==
                    "funds"
                ) {
                    listOf(field)
                } else {
                    listOf("markets", "resultEntries", "resultBytes", "activeOrders", "activeBytes")
                }
            for (f in fields) {
                val values = copies.map { if (group == "funds") it.path(f) else it.path(field).path(f) }
                c.require(values.none { it.isMissingNode } && values.distinct().size == 1, "$group.$f: 사본 의미 상태 불일치")
            }
        }
        val funds =
            states.entries
                .firstOrNull { it.key.startsWith("funds") }
                ?.value
                ?.path("funds") ?: return c.issues
        val keys =
            buildList {
                add("buy-btc")
                if (e.btcIssued >= 2) add("sell-btc")
                if (e.sui >= 1) add("buy-sui")
                if (e.sui >= 2) add("sell-sui")
                if (e.btcIssued == 3L) add("followup-btc")
            }
        receipts(c, funds, keys, originals)
        val btcTraded = e.btcSettled >= 2
        val suiTraded = e.sui >= 2
        val btcHeld = e.btcIssued >= 2 && !btcTraded
        val activeBtc = if (e.btcIssued == 3L || btcHeld) 2L else 1L
        val activeSui = if (e.sui > 0) 1L else 0L
        val pending = e.btcIssued - e.btcSettled
        balances(c, funds, e, btcTraded, suiTraded, btcHeld)
        c.number(funds, "lastAdmissionSequence", keys.size.toLong(), "funds")
        c.number(funds, "lastCancellationSequence", 0, "funds")
        c.number(funds, "pendingOrders", pending, "funds")
        for (field in listOf("activeOrders", "liveOrders")) c.number(funds, field, activeBtc + activeSui, "funds")
        c.bytes(funds, "activeBytes", activeBtc + activeSui, "funds")
        val heldOrder = e.btcIssued - e.btcApplied
        c.number(funds, "dispatchEntries", heldOrder, "funds")
        c.bytes(funds, "dispatchBytes", heldOrder, "funds")
        for ((index, market) in markets.withIndex()) {
            val label = if (index == 0) "btc" else "sui"
            val issued = if (index == 0) e.btcIssued else e.sui
            val applied = if (index == 0) e.btcApplied else e.sui
            val settled = if (index == 0) e.btcSettled else e.sui
            val active = if (index == 0) activeBtc else activeSui
            val dispatch = funds.path("dispatch").path(market)
            c.number(dispatch, "issued", issued, market)
            c.number(dispatch, "matchingAppliedThrough", applied, market)
            c.number(dispatch, "dispatchEntries", issued - applied, market)
            c.bytes(dispatch, "dispatchBytes", issued - applied, market)
            c.number(dispatch, "activeOrders", active, market)
            c.bytes(dispatch, "activeBytes", active, market)
            val outstanding = dispatch.path("outstanding")
            c.require(outstanding.isArray && outstanding.size().toLong() == issued - applied, "$market: 전달 원문 개수 불일치")
            if (issued > applied) {
                val row = outstanding.path(0)
                c.number(row, "dispatchSequence", 1, market)
                c.require(
                    id(originals, "buy-btc") != null && text(row.path("orderId")) == id(originals, "buy-btc"),
                    "$market: 원본 전달 주문 불일치",
                )
                token(c, row.path("token"), "buy-btc")
                c.sha(row, market)
            }
            val progress = funds.path("resultProgress").path(market)
            for (field in listOf("resultThrough", "settledEngineSequence")) c.number(progress, field, settled, market)
            val copies = states.filterKeys { it.startsWith(label) }.values
            for (copy in copies) {
                val matching = copy.path("matching")
                val m = matching.path("markets").path(market)
                c.require(matching.path("markets").isObject && matching.path("markets").size() == 1, "$label: 자기 담당 주문장 하나가 필요합니다")
                for (field in listOf("engineSequence", "appliedThrough", "resultIssued")) c.number(m, field, applied, market)
                c.number(m, "fundsSettledThrough", settled, market)
                c.number(matching, "resultEntries", applied - settled, market)
                c.bytes(matching, "resultBytes", applied - settled, market)
                val bookKeys =
                    buildList {
                        if (applied > 0) add("buy-$label")
                        if (applied == 3L) add("followup-btc")
                    }
                c.number(matching, "activeOrders", bookKeys.size.toLong(), market)
                c.bytes(matching, "activeBytes", bookKeys.size.toLong(), market)
                book(c, m.path("orders"), bookKeys, originals, applied)
                val pendingResults = m.path("outstanding")
                c.require(pendingResults.isArray && pendingResults.size().toLong() == applied - settled, "$market: 미정산 결과 원문 누락/중복")
                for (row in pendingResults) {
                    c.number(row, "resultSequence", 2, market)
                    c.number(row, "inputSequence", 2, market)
                    c.require(text(row.path("orderId")) == id(originals, "sell-btc"), "$market: 미정산 주문 불일치")
                    c.sha(row, market)
                    val detail = matching.path("resultDetails").toList().singleOrNull { number(it.path("resultSequence")) == 2L }
                    c.require(
                        detail != null &&
                            listOf("resultId", "orderId", "inputSequence", "payloadSha256").all { row.path(it) == detail.path(it) },
                        "$market: 미정산 결과2 원문 불일치",
                    )
                }
                val settledRows = funds.path("resultDetails").toList().filter { it.path("marketId").asString() == market }
                c.require(settledRows.size.toLong() == settled, "$market: 자금 정산 결과 누락/중복")
                for (sequence in 1L..applied) {
                    val settledRow = settledRows.singleOrNull { number(it.path("resultSequence")) == sequence }
                    val retained = matching.path("resultDetails").toList().singleOrNull { number(it.path("resultSequence")) == sequence }
                    val row = settledRow ?: retained
                    c.require(row != null, "$market/$sequence: 결과 의미 관찰 누락")
                    if (row != null) result(c, row, market, sequence, originals)
                    if (sequence <=
                        settled
                    ) {
                        c.require(
                            settledRow != null &&
                                runCatching {
                                    Instant.parse(settledRow.path("appliedAt").asString())
                                }.isSuccess,
                            "$market/$sequence: 정산 완료 근거 누락",
                        )
                    }
                    if (settledRow != null &&
                        retained != null
                    ) {
                        c.require(meaning.all { settledRow.path(it) == retained.path(it) }, "$market/$sequence: 매칭 원문과 정산 결과 불일치")
                    }
                }
                val retained = matching.path("resultDetails")
                c.require(
                    retained.isArray &&
                        retained.toList().all {
                            it
                                .path(
                                    "marketId",
                                ).asString() == market && number(it.path("resultSequence")) in 1L..applied
                        },
                    "$market: 다른 담당/미래 결과 관찰",
                )
                c.require(
                    retained
                        .toList()
                        .map { text(it.path("resultId")) }
                        .distinct()
                        .size == retained.size(),
                    "$market: 중복 결과 ID",
                )
            }
        }
        val details = funds.path("resultDetails")
        c.require(details.isArray && details.size().toLong() == e.btcSettled + e.sui, "자금 전체 결과 개수 불일치")
        val ids = details.toList().map { text(it.path("resultId")) }
        c.require(ids.none { it == null } && ids.distinct().size == ids.size, "결과 ID 누락/중복")
        val trades = details.toList().flatMap { it.path("events").toList() }.filter { it.path("kind").asString() == "TRADE" }
        c.require(trades.size == (if (btcTraded) 1 else 0) + (if (suiTraded) 1 else 0), "논리 체결 수 불일치")
        c.require(
            trades.map { text(it.path("tradeId")) }.let {
                it.none { id ->
                    id == null
                } && it.distinct().size == it.size
            },
            "체결 ID 누락/중복",
        )
        return c.issues
    }

    private fun expected(stage: MultiMatchingFailoverStage): Expected =
        when (stage) {
            MultiMatchingFailoverStage.ORDER_HELD -> Expected(1, 0, 0, 0, 798, 202)
            MultiMatchingFailoverStage.BUY_RESTING -> Expected(1, 1, 1, 0, 798, 202)
            MultiMatchingFailoverStage.RESULT_HELD -> Expected(2, 2, 1, 0, 798, 202)
            MultiMatchingFailoverStage.ORDER_HELD_SUI_RESERVED -> Expected(1, 0, 0, 1, 495, 505)
            MultiMatchingFailoverStage.RESULT_HELD_SUI_RESERVED -> Expected(2, 2, 1, 1, 495, 505)
            MultiMatchingFailoverStage.ORDER_HELD_SUI_TRADED -> Expected(1, 0, 0, 2, 495, 404)
            MultiMatchingFailoverStage.RESULT_HELD_SUI_TRADED -> Expected(2, 2, 1, 2, 495, 404)
            MultiMatchingFailoverStage.BUY_RESTING_SUI_TRADED -> Expected(1, 1, 1, 2, 495, 404)
            MultiMatchingFailoverStage.TRADED -> Expected(2, 2, 2, 2, 495, 303)
            MultiMatchingFailoverStage.FOLLOWUP -> Expected(3, 3, 3, 2, 394, 404)
        }

    /** controller 한 시계의 순서와, 실제 현재 probe·같은 기록으로 복귀한 한 member를 대조한다. */
    fun lifecycleViolations(
        evidence: JsonNode,
        fundsFailover: Boolean = false,
    ): List<String> {
        val c = Check()
        val target = text(evidence.path("target"))
        val initial = evidence.path("initial")
        val elected = evidence.path("elected")
        val rejoined = evidence.path("rejoined")
        val owner = if (fundsFailover) "funds" else "btc"
        val members = (0..2).map { "$owner$it" }
        c.require(target in members, "종료 대상은 실제 $owner member여야 합니다")
        c.require(initial.properties().map { it.key }.toSet() == names, "초기9개 실행 근거 누락")
        c.require(elected.properties().map { it.key }.toSet() == names - target, "생존8개에 죽은 관찰 혼합/사본 누락")
        c.require(rejoined.properties().map { it.key }.toSet() == names, "재합류9개 실행 근거 누락")
        if (target == null) return c.issues
        val stop = evidence.path("stopped")
        c.require(
            stop.path("service").asString() == target && stop.path("containerId") == initial.path(target).path("containerId"),
            "다른 container 종료",
        )
        c.require(
            stop.path("running").isBoolean && !stop.path("running").asBoolean() && stop.path("oomKilled").isBoolean &&
                !stop.path("oomKilled").asBoolean(),
            "종료 미확인/OOM 종료",
        )
        c.number(stop, "exitCode", 137, "SIGKILL")
        val firstLeader = leader(c, initial, members, "종료 전")
        c.require(firstLeader.second == target.last().digitToInt().toLong(), "실제 $owner 리더가 아닌 member 종료")
        val survivors = members.filter { it != target }
        val newLeader = leader(c, elected, survivors, "구 리더 정지 중")
        c.require(
            firstLeader.first != null && newLeader.first != null && (newLeader.first ?: -1L) > (firstLeader.first ?: Long.MAX_VALUE),
            "더 높은 새 임기 관찰 누락",
        )
        val finalLeader = leader(c, rejoined, members, "재합류 뒤")
        c.require(
            finalLeader.first != null && newLeader.first != null && (finalLeader.first ?: -1L) >= (newLeader.first ?: Long.MAX_VALUE),
            "재합류 뒤 임기 후퇴",
        )
        val uniqueBoots = mutableSetOf<String>()
        val uniqueContainers = mutableSetOf<String>()
        val stable =
            listOf(
                "runId",
                "role",
                "memberId",
                "clusterId",
                "lineageHigh",
                "lineageLow",
                "markets",
                "manifestSha256",
                "topologySha256",
                "resultTopologySha256",
                "imageId",
                "runRoot",
                "archiveDirectory",
                "clusterDirectory",
            )
        for (name in names) {
            val before = initial.path(name)
            val after = rejoined.path(name)
            for (field in stable) {
                c.require(
                    !before.path(field).isMissingNode && before.path(field) == after.path(field),
                    "$name.$field: 기존 실행/기록 identity 변경",
                )
            }
            val volumeBefore = recordVolume(before)
            c.require(volumeBefore != null && volumeBefore == recordVolume(after), "$name: 원래 기록 볼륨 대체")
            for ((phase, rows) in listOf("initial" to initial, "elected" to elected, "rejoined" to rejoined)) {
                if (name == target && phase == "elected") continue
                val runtime = rows.path(name)
                val state = runtime.path("state")
                c.require(
                    runtime
                        .path("inspection")
                        .path("State")
                        .path("Running")
                        .isBoolean &&
                        runtime
                            .path("inspection")
                            .path("State")
                            .path("Running")
                            .asBoolean() &&
                        !runtime
                            .path("inspection")
                            .path("State")
                            .path("OOMKilled")
                            .asBoolean(),
                    "$phase/$name: 생존 프로세스 미확인",
                )
                c.number(runtime.path("inspection"), "RestartCount", 0, "$phase/$name")
                for (field in listOf(
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
                )) {
                    c.require(
                        !runtime.path(field).isMissingNode && runtime.path(field) == state.path(field),
                        "$phase/$name.$field: 현재 runtime 관찰 불일치",
                    )
                }
                val probe = state.path("probe")
                c.require(
                    number(probe.path("correlationId"))?.let { it >= 1_000_000_000_000L } == true &&
                        probe.path("bootId") == state.path("bootId") &&
                        probe.path("leadershipTermId") == state.path("leadershipTermId") &&
                        probe.path("leaderMemberId") == state.path("leaderMemberId"),
                    "$phase/$name: 현재 boot/임기의 고유 probe 누락",
                )
                c.require(
                    number(probe.path("logPosition"))?.let {
                        it > 0 && it <= (number(state.path("logPosition")) ?: -1L)
                    } == true,
                    "$phase/$name: probe의 복제 위치 누락",
                )
                if (phase !=
                    "initial"
                ) {
                    c.require(
                        probe.path("correlationId") != before.path("state").path("probe").path("correlationId"),
                        "$phase/$name: 이전 probe 재사용",
                    )
                }
                if (phase == "rejoined" &&
                    name != target
                ) {
                    c.require(
                        probe.path("correlationId") !=
                            elected
                                .path(name)
                                .path("state")
                                .path("probe")
                                .path("correlationId"),
                        "$phase/$name: 재합류 전 probe 재사용",
                    )
                }
                if (phase ==
                    "elected"
                ) {
                    for (field in listOf(
                        "containerId",
                        "bootId",
                    )) {
                        c.require(runtime.path(field) == before.path(field), "$name: 생존 노드 재생성/재부팅")
                    }
                }
            }
            val changed = name == target
            for (field in listOf("containerId", "bootId")) {
                c.require(
                    text(after.path(field)) != null && (after.path(field) != before.path(field)) == changed,
                    "$name.$field: 종료 member만 새 실행이어야 합니다",
                )
            }
            c.require(
                after.path("recoverExisting").isBoolean && after.path("recoverExisting").asBoolean() == changed,
                "$name: 기존 Archive 복구 모드 불일치",
            )
            c.require(
                text(after.path("bootId"))?.let(uniqueBoots::add) == true &&
                    text(after.path("containerId"))?.let(uniqueContainers::add) == true,
                "$name: 현재 boot/container 복제",
            )
        }
        val timeline = evidence.path("timeline")
        val phases =
            if (fundsFailover) {
                listOf(
                    "acceptedObserved",
                    "killSent",
                    "killConfirmed",
                    "electionObserved",
                    "refusalConfirmed",
                    "gateReleased",
                    "btcSettled",
                    "rejoined",
                    "followupCompleted",
                )
            } else {
                listOf(
                    "killSent",
                    "killConfirmed",
                    "electionObserved",
                    "suiStarted",
                    "suiSettled",
                    "gateReleased",
                    "btcSettled",
                    "rejoined",
                    "followupCompleted",
                )
            }
        val times = phases.map { number(timeline.path(it)) }
        val order = phases.map { number(evidence.path("eventOrder").path(it)) }
        c.require(
            times.all { it != null && it >= 0 } && times.zipWithNext().all { (a, b) -> a != null && b != null && a <= b } &&
                order.all { it != null && it > 0 } &&
                order.zipWithNext().all { (a, b) -> a != null && b != null && a < b },
            "종료→새 임기→SUI 정산→gate 해제→BTC 정산→재합류→후속 순서 위반",
        )
        if (!fundsFailover) gates(c, evidence, initial, elected, timeline, newLeader.second)
        return c.issues
    }

    private fun gates(
        c: Check,
        evidence: JsonNode,
        initial: JsonNode,
        elected: JsonNode,
        timeline: JsonNode,
        newLeader: Long?,
    ) {
        val rows = evidence.path("gates")
        c.require(rows.isArray && rows.size() == 1, "BTC 대상 실제 전송 gate 하나의 근거가 필요합니다")
        for (gate in rows) {
            val order = gate.path("gate").asString() == "btc-dispatch"
            c.require(order || gate.path("gate").asString() == "btc-result", "알 수 없는 보류 경계")
            val sequence = if (order) 1L else 2L
            c.number(gate, "sequence", sequence, "gate")
            val blocked = gate.path("blocked")
            val offered = gate.path("offered")
            val reply = gate.path("reply")
            c.require(text(gate.path("installationId")) != null, "gate 설치 식별 누락")
            val blockedService = text(gate.path("blockedService"))
            val offeredService = text(gate.path("offeredService"))
            c.require(blockedService != null && blockedService.startsWith(if (order) "funds" else "btc"), "실제 보류 작업의 담당 불일치")
            c.require(
                offeredService != null && if (order) offeredService == blockedService else offeredService == "btc$newLeader",
                "실제 재개 작업의 담당 불일치",
            )
            c.require(blocked.path("bootId") == initial.path(blockedService ?: "").path("bootId"), "보류 작업의 원래 boot 불일치")
            c.require(offered.path("bootId") == elected.path(offeredService ?: "").path("bootId"), "재개 작업의 생존 boot 불일치")
            // 관찰과 worker 모두 correlation=0으로 같은 Apply 원문을 인코딩한 SHA다.
            // 두 진단 파일끼리 일치하는 것에 더해, 종료 전·선출 후 실제 전달 의무와 연결한다.
            for ((snapshot, service) in listOf(initial to blockedService, elected to offeredService)) {
                val state = snapshot.path(service ?: "").path("state")
                val pending =
                    if (order) {
                        state
                            .path("funds")
                            .path("dispatch")
                            .path(markets[0])
                            .path("outstanding")
                    } else {
                        state
                            .path("matching")
                            .path("markets")
                            .path(markets[0])
                            .path("outstanding")
                    }
                val sequenceField = if (order) "dispatchSequence" else "resultSequence"
                val original = pending.toList().singleOrNull { number(it.path(sequenceField)) == sequence }
                val fields = if (order) listOf("orderId", "payloadSha256") else listOf("orderId", "resultId", "payloadSha256")
                c.require(
                    original != null && fields.all { original.path(it) == blocked.path(it) },
                    "실제 전달 의무와 보류·재개 원문 불일치",
                )
            }
            c.require(
                blocked.path("enabled").isBoolean && blocked.path("enabled").asBoolean() && offered.path("enabled").isBoolean &&
                    !offered.path("enabled").asBoolean(),
                "실제 작업의 보류/해제 관찰 누락",
            )
            for (row in listOf(blocked, offered)) {
                for (field in listOf(
                    "installationId",
                    "gate",
                    "sequence",
                )) {
                    c.require(row.path(field) == gate.path(field), "gate 설치와 실제 작업 불일치")
                }
                c.require(row.path("marketId").asString() == markets[0], "BTC 외 전송 보류")
                c.sha(row, "gate")
                c.require(
                    text(row.path("wireSha256"))?.let {
                        digest.matches(it)
                    } == true && (number(row.path("wireBytes")) ?: 0) > 0,
                    "실제 전송 원문 지문/bytes 누락",
                )
                c.require(
                    row.path("described").path("code").asString() == "DESCRIBED" &&
                        (number(row.path("described").path("appliedLogPosition")) ?: 0L) > 0,
                    "실제 상대 확인 누락",
                )
                c.require(text(row.path("connectionId")) != null && (number(row.path("correlationId")) ?: 0L) > 0, "실제 작업 연결/상관 식별 누락")
                val stream = row.path("stream")
                c.require(
                    stream.path("direction").asString() == if (order) "FUNDS_TO_MATCHING" else "MATCHING_TO_FUNDS",
                    "보류 stream 방향 불일치",
                )
                for ((field, owner, identity) in listOf(
                    Triple("fundsLineageHigh", "funds0", "lineageHigh"),
                    Triple("fundsLineageLow", "funds0", "lineageLow"),
                    Triple("matchingLineageHigh", "btc0", "lineageHigh"),
                    Triple("matchingLineageLow", "btc0", "lineageLow"),
                )) {
                    c.require(stream.path(field) == initial.path(owner).path(identity), "보류 stream lineage 불일치")
                }
                c.require(
                    (number(stream.path("ownershipEpoch")) ?: 0L) > 0 && (number(stream.path("configurationVersion")) ?: 0L) > 0,
                    "보류 stream 버전 누락",
                )
            }
            c.require(
                listOf("payloadSha256", "orderId", "resultId", "stream").all { blocked.path(it) == offered.path(it) },
                "재개된 원본 업무/payload 변경",
            )
            c.require((number(offered.path("positiveOfferPosition")) ?: 0L) > 0, "실제 양수 전송 수용 누락")
            c.require(
                reply.path("code").asString() in setOf("APPLIED", "DUPLICATE") && (number(reply.path("through")) ?: -1L) >= sequence &&
                    (number(reply.path("appliedLogPosition")) ?: 0L) > 0,
                "실제 적용 응답 누락",
            )
            c.require(
                listOf("bootId", "connectionId", "correlationId", "payloadSha256").all {
                    reply.path(it) == offered.path(it)
                },
                "재개 전송과 실제 응답의 연결 불일치",
            )
            c.require(
                gate.path("heldWireSha256") == blocked.path("wireSha256") && gate.path("heldWireBytes") == blocked.path("wireBytes"),
                "controller 보존 원문과 실제 작업 원문 불일치",
            )
            val installed = number(gate.path("installedAt"))
            val blockedAt = number(gate.path("blockedAt"))
            val released = number(gate.path("releaseAt"))
            c.require(
                installed != null && blockedAt != null && released != null && installed <= blockedAt &&
                    blockedAt < (number(timeline.path("killSent")) ?: -1L) &&
                    released >= (number(timeline.path("gateReleased")) ?: Long.MAX_VALUE) &&
                    released < (number(timeline.path("btcSettled")) ?: -1L),
                "gate 설치→실제 보류→종료→해제 순서 불일치",
            )
        }
    }

    private fun recordVolume(runtime: JsonNode): String? =
        runtime
            .path("inspection")
            .path("Mounts")
            .toList()
            .singleOrNull {
                it.path("Destination").asString() ==
                    "/records"
            }?.let { text(it.path("Name")) }

    private fun leader(
        c: Check,
        runtimes: JsonNode,
        members: List<String>,
        label: String,
    ): Pair<Long?, Long?> {
        val states = members.map { runtimes.path(it).path("state") }
        val terms = states.map { number(it.path("leadershipTermId")) }
        val leaders = states.map { number(it.path("leaderMemberId")) }
        c.require(
            terms.all { it != null && it >= 0 } && terms.distinct().size == 1 &&
                leaders.all { it != null && it in members.map { name -> name.last().digitToInt().toLong() } } &&
                leaders.distinct().size == 1,
            "$label: 공통 현재 임기/생존 리더 불일치",
        )
        val leaderId = leaders.firstOrNull()
        c.require(
            states.count { it.path("clusterRole").asString() == "LEADER" } == 1 &&
                states.all {
                    it.path("clusterRole").asString() ==
                        if (number(it.path("memberId")) ==
                            leaderId
                        ) {
                            "LEADER"
                        } else {
                            "FOLLOWER"
                        }
                },
            "$label: 실제 현재 리더/팔로워 불일치",
        )
        return terms.firstOrNull() to leaderId
    }

    private fun balances(
        c: Check,
        funds: JsonNode,
        e: Expected,
        btc: Boolean,
        sui: Boolean,
        held: Boolean,
    ) {
        val rows = funds.path("balances")
        val keyed = rows.toList().associateBy { it.path("userId").asString() to it.path("assetId").asString() }
        c.require(
            rows.isArray && keyed.size == rows.size() &&
                keyed.keys.all { it.first in listOf("buyer", "seller") && it.second in listOf("USDT", "BTC", "SUI") },
            "잔고 키 누락/중복/다른 자산",
        )

        fun balance(
            user: String,
            asset: String,
            available: Long,
            hold: Long = 0,
        ) {
            val row = keyed[user to asset]
            if (row == null && available == 0L && hold == 0L) return
            c.number(row, "available", available, "$user/$asset")
            c.number(row, "hold", hold, "$user/$asset")
        }
        balance("buyer", "USDT", e.available, e.hold)
        balance("buyer", "BTC", if (btc) 1 else 0)
        balance("buyer", "SUI", if (sui) 1 else 0)
        balance("seller", "USDT", (if (btc) 99L else 0L) + (if (sui) 99L else 0L))
        balance("seller", "BTC", if (btc || held) 0 else 1, if (held) 1 else 0)
        balance("seller", "SUI", if (sui) 0 else 1)
        val fees = funds.path("fees")
        c.require(
            fees.isObject &&
                fees.properties().all { (asset, value) ->
                    asset in listOf("USDT", "BTC", "SUI") &&
                        value.asString().toLongOrNull() ==
                        if (asset ==
                            "USDT"
                        ) {
                            (if (btc) 2L else 0L) + (if (sui) 2L else 0L)
                        } else {
                            0L
                        }
                },
            "독립 수수료 기대값 불일치",
        )
        c.require((fees.path("USDT").asString().toLongOrNull() ?: 0L) == (if (btc) 2L else 0L) + (if (sui) 2L else 0L), "USDT 수수료 누락/불일치")
    }

    private fun receipts(
        c: Check,
        funds: JsonNode,
        keys: List<String>,
        originals: Map<String, JsonNode>,
    ) {
        val rows = funds.path("receipts")
        c.require(rows.isArray && rows.size() == keys.size, "최초 응답 개수 불일치")
        val ids = keys.map { id(originals, it) }
        c.require(ids.none { it == null } && ids.distinct().size == keys.size, "원 신규 주문 ID 누락/재사용")
        val admissions = keys.map { number(originals[it]?.path("receipt")?.path("admissionSequence")) }
        c.require(admissions.toSet() == (1L..keys.size.toLong()).toSet(), "신규 승인 순번 누락/중복")
        for (key in keys) {
            val original = originals[key]
            c.require(
                original?.path("code")?.asString() == "ACCEPTED" && original?.path("receipt")?.path("code")?.asString() == "ACCEPTED",
                "$key: 최초 승인 응답 누락",
            )
            val receipt = original?.path("receipt")
            c.number(
                receipt,
                "marketAdmissionSequence",
                if (key.startsWith("followup")) {
                    3
                } else if (key.startsWith("sell")) {
                    2
                } else {
                    1
                },
                key,
            )
            val matches = rows.toList().filter { text(it.path("receipt").path("orderId")) == id(originals, key) }
            c.require(matches.size == 1 && matches.single().path("receipt") == receipt, "$key: 최초 응답 변경/누락")
            for (row in matches) {
                token(c, row, key)
                c.require(
                    row.path("kind").asString() == "ORDER" &&
                        row.path("marketId").asString() == if (key.endsWith("btc")) markets[0] else markets[1],
                    "$key: 원본 요청 종류/마켓 불일치",
                )
            }
        }
    }

    private fun token(
        c: Check,
        row: JsonNode,
        key: String,
    ) {
        c.require(row.path("userId").asString() == if (key.startsWith("sell")) "seller" else "buyer", "$key: 원 token 사용자 불일치")
        c.number(row, "channel", if (key.endsWith("btc")) 0 else 1, key)
        c.number(row, "epoch", 1, key)
        c.number(row, "sequence", if (key.startsWith("followup")) 2 else 1, key)
    }

    private fun book(
        c: Check,
        rows: JsonNode,
        keys: List<String>,
        originals: Map<String, JsonNode>,
        applied: Long,
    ) {
        c.require(rows.isArray && rows.size() == keys.size, "원 주문장 크기 불일치")
        c.require(rows.toList().map { text(it.path("orderId")) }.toSet() == keys.map { id(originals, it) }.toSet(), "원 주문장 주문 ID 불일치")
        for (key in keys) {
            val row = rows.toList().singleOrNull { text(it.path("orderId")) == id(originals, key) } ?: continue
            c.require(row.path("userId").asString() == "buyer" && row.path("side").asString() == "BUY", "$key: 주문장 주체/방향 불일치")
            val quantity =
                if (key.startsWith("followup")) {
                    1L
                } else if (key.endsWith("btc")) {
                    2L
                } else {
                    3L
                }
            c.number(row, "price", 100, key)
            c.number(row, "originalQuantity", quantity, key)
            c.number(row, "remainingQuantity", if (key.startsWith("followup") || applied < 2) quantity else quantity - 1, key)
        }
    }

    private fun result(
        c: Check,
        row: JsonNode,
        market: String,
        sequence: Long,
        originals: Map<String, JsonNode>,
    ) {
        val suffix = if (market == markets[0]) "btc" else "sui"
        val key =
            if (sequence == 3L) {
                "followup-btc"
            } else if (sequence == 2L) {
                "sell-$suffix"
            } else {
                "buy-$suffix"
            }
        val order = id(originals, key)
        c.require(
            order != null && text(row.path("orderId")) == order && text(row.path("resultId")) != null,
            "$market/$sequence: 결과/원 주문 identity 불일치",
        )
        c.require(
            row.path("marketId").asString() == market && row.path("outcome").asString() == "MATCHED" && row.path("cancelId").isNull,
            "$market/$sequence: 결과 대상/종류 불일치",
        )
        for (field in listOf("resultSequence", "inputSequence", "engineAfter")) c.number(row, field, sequence, market)
        c.number(row, "engineBefore", sequence - 1, market)
        c.sha(row, market)
        val event = row.path("events").toList().singleOrNull()
        c.require(row.path("events").isArray && event != null, "$market/$sequence: 결과 전체 사건 누락/중복")
        val buy = id(originals, "buy-$suffix")
        val quantity = if (suffix == "btc") 2L else 3L
        if (event != null) {
            c.number(event, "engineSequence", sequence, market)
            c.number(event, "quantity", if (sequence == 1L) quantity else 1, market)
            c.number(event, "price", 100, market)
            if (sequence == 2L) {
                c.require(
                    event.path("kind").asString() == "TRADE" && text(event.path("makerOrderId")) == buy &&
                        text(event.path("takerOrderId")) == order &&
                        text(event.path("tradeId")) == "${row.path("resultId").asString()}:$sequence",
                    "$market: 체결 상대/고유 ID 불일치",
                )
            } else {
                c.require(event.path("kind").asString() == "RESTING" && text(event.path("orderId")) == order, "$market: 대기 사건 불일치")
            }
        }
        val effects = row.path("effects")
        val expected =
            if (sequence ==
                2L
            ) {
                mapOf(buy to listOf(1L, quantity, quantity - 1, "RESTING"), order to listOf(2L, 1L, 0L, "TERMINAL"))
            } else {
                mapOf(order to listOf(sequence, if (sequence == 1L) quantity else 1L, if (sequence == 1L) quantity else 1L, "RESTING"))
            }
        c.require(
            effects.isArray && effects.size() == expected.size && effects
                .toList()
                .map {
                    text(it.path("orderId"))
                }.toSet() == expected.keys,
            "$market: 자금 효과 대상 누락/중복",
        )
        for (effect in effects) {
            val values = expected[text(effect.path("orderId"))] ?: continue
            for ((i, field) in listOf(
                "originInputSequence",
                "beforeQuantity",
                "afterQuantity",
            ).withIndex()) {
                c.number(effect, field, values[i] as Long, market)
            }
            c.require(effect.path("disposition").asString() == values[3], "$market: 자금 효과 상태 불일치")
        }
    }

    private fun id(
        originals: Map<String, JsonNode>,
        key: String,
    ): String? = text(originals[key]?.path("receipt")?.path("orderId"))

    private fun number(node: JsonNode?): Long? = if (node?.isIntegralNumber == true && node.canConvertToLong()) node.asLong() else null

    private fun text(node: JsonNode?): String? = if (node?.isTextual == true) node.asString().takeIf { it.isNotBlank() } else null

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
            label: String,
        ) = require(number(node?.path(field)) == expected, "$label.$field: expected=$expected actual=${node?.path(field)}")

        fun sha(
            node: JsonNode,
            label: String,
        ) = require(
            text(node.path("payloadSha256"))?.let {
                digest.matches(it)
            } == true,
            "$label: 실제 원문 SHA 누락",
        )

        fun bytes(
            node: JsonNode,
            field: String,
            entries: Long,
            label: String,
        ) {
            val bytes = number(node.path(field))
            require(
                bytes != null && bytes in 0L..2_097_152L &&
                    if (entries ==
                        0L
                    ) {
                        bytes == 0L
                    } else {
                        bytes > 0L
                    },
                "$label.$field: 보관 개수/byte 예산 불일치",
            )
        }
    }
}
