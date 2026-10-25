package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

internal const val FUNDING_EVENT_PREFIX = "FUNDING_EVENT "

internal data class FundingVerificationIssue(
    val category: String,
    val message: String,
)

internal data class ExpectedMarketConfiguration(
    val version: Long,
    val sha256: String,
)

internal data class FundingReplicationComparison(
    val position: Long?,
    val issues: List<FundingVerificationIssue>,
) {
    val succeeded: Boolean get() = issues.isEmpty()
}

internal fun observedApplicationPosition(event: JsonNode): Long? {
    val value = event.path("position")
    if (!value.isIntegralNumber || !value.canConvertToLong()) return null
    return value.longValue().takeIf { it > 0 }
}

/** stdout의 개발 이벤트를 읽을 뿐, State나 자금 준비 함수를 다시 실행하지 않는다. */
internal fun readFundingEvents(
    output: String,
    problems: MutableList<FundingVerificationIssue>,
): List<JsonNode> =
    output
        .lineSequence()
        .filter { it.startsWith(FUNDING_EVENT_PREFIX) }
        .mapNotNull { line ->
            try {
                jacksonObjectMapper().readTree(line.removePrefix(FUNDING_EVENT_PREFIX))
            } catch (error: Exception) {
                problems.add(FundingVerificationIssue("UNVERIFIABLE", "개발 이벤트 해석 실패: ${error.message}"))
                null
            }
        }.toList()

/** 세 사본끼리 같다는 것과 명세의 독립 기대값에 맞는다는 것을 모두 확인한다. */
internal fun compareFundingReplication(
    runId: String,
    eventsByMember: Map<Int, List<JsonNode>>,
    clientEvents: List<JsonNode>,
    previousPosition: Long = 0,
    collectionIssues: List<FundingVerificationIssue> = emptyList(),
): FundingReplicationComparison {
    val issues = collectionIssues.toMutableList()
    val current = (0..2).associateWith { member -> eventsByMember[member].orEmpty().filter { it.path("runId").asString() == runId } }
    val candidates =
        current.values.flatten().filter {
            it.path("kind").asString() == "APPLIED" &&
                it.path("preparationId").asString() == "P1"
        }
    val leaderPositions =
        candidates.filter { it.path("role").asString() == "LEADER" }.mapNotNull(::observedApplicationPosition).distinct()
    val position = leaderPositions.singleOrNull()
    if (position == null || position <= previousPosition) {
        issues.add(
            FundingVerificationIssue(
                "UNVERIFIABLE",
                "이번 P1의 새 리더 적용 위치가 없습니다: previous=$previousPosition, leaderPositions=$leaderPositions",
            ),
        )
    }
    for (member in 0..2) {
        val events = current.getValue(member)
        events.filter { it.path("kind").asString() == "ERROR" }.forEach {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "node$member 실행 오류: ${it.path("failure").asString()}"))
        }
        val applied = events.filter { it.path("kind").asString() == "APPLIED" && it.path("preparationId").asString() == "P1" }
        // 같은 L의 응답 시도 관찰은 중복 업무 적용으로 세지 않는다.
        val atPosition = applied.filter { observedApplicationPosition(it) == position }
        applied.filter { observedApplicationPosition(it) == null }.forEach {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "node$member 유효한 적용 위치 없음: actual=${it.path("position")}"))
        }
        if (atPosition.isEmpty() || position == null || position <= previousPosition) {
            issues.add(
                FundingVerificationIssue(
                    "UNVERIFIABLE",
                    "node$member 새 P1 관찰 부족: expectedPosition=$position, actual=${applied.map { it.path("position") }}",
                ),
            )
        }
        if (position != null && applied.any { observedApplicationPosition(it) != position }) {
            issues.add(
                FundingVerificationIssue(
                    "UNVERIFIABLE",
                    "node$member 같은 P1의 서로 다른 적용 위치가 관찰됐습니다: expected=$position, actual=${applied.map {
                        it
                            .path(
                                "position",
                            )
                    }.distinct()}",
                ),
            )
        }
        // 하나라도 읽은 업무 불일치는 다른 위치·사본의 누락/수집 오류와 함께 남긴다.
        applied.forEach { checkApplied(member, it, issues) }
    }
    val client = clientEvents.filter { it.path("runId").asString() == runId }
    if (client.none { it.path("kind").asString() == "TRANSPORT_ACCEPTED" }) {
        issues.add(FundingVerificationIssue("UNVERIFIABLE", "클라이언트의 P1 전송 수용 근거가 없습니다"))
    }
    val results = client.filter { it.path("kind").asString() == "RESULT_RECEIVED" }
    if (results.size != 1) {
        issues.add(FundingVerificationIssue("UNVERIFIABLE", "클라이언트 최초 결과 한 건을 확인하지 못했습니다: actual=${results.size}"))
    }
    results.forEach { checkFields("client.result", it.path("result"), expectedFundingResult(), issues) }
    return FundingReplicationComparison(position, issues.distinct())
}

internal fun compareFundingReadiness(
    runId: String,
    eventsByMember: Map<Int, List<JsonNode>>,
    expectedMarkets: List<Map<String, Any>> = expectedFundingMarkets(),
    expectedConfiguration: ExpectedMarketConfiguration? = null,
): List<FundingVerificationIssue> {
    val issues = mutableListOf<FundingVerificationIssue>()
    val mapper = jacksonObjectMapper()
    val wantedMarkets = mapper.readTree(mapper.writeValueAsString(expectedMarkets))
    val ready =
        (0..2).associateWith { member ->
            eventsByMember[member].orEmpty().lastOrNull { it.path("kind").asString() == "READY" && it.path("runId").asString() == runId }
        }
    if (ready.values.any { it == null }) {
        return listOf(FundingVerificationIssue("SETUP", "준비 이벤트 누락: ${ready.filterValues { it == null }.keys}"))
    }
    val roles = ready.values.map { it!!.path("role").asString() }
    if (roles.count { it == "LEADER" } != 1 || roles.count { it == "FOLLOWER" } != 2) {
        issues.add(FundingVerificationIssue("SETUP", "리더 하나·팔로워 둘이 아닙니다: $roles"))
    }
    val firstSettings = ready.getValue(0)!!.path("settings")
    for ((member, value) in ready) {
        val event = value!!
        checkFields("node$member", event, mapOf("memberId" to member), issues, "SETUP")
        if (event.path("pid").asLong() <= 0) issues.add(FundingVerificationIssue("SETUP", "node$member JVM PID가 없습니다"))
        val settings = event.path("settings")
        checkFields(
            "node$member.settings",
            settings,
            mapOf("clusterId" to 97, "serviceId" to 0, "runRoot" to "/records/$runId", "storageLevels" to listOf(2, 2, 2)),
            issues,
            "SETUP",
        )
        expectedConfiguration?.let { expected ->
            checkFields(
                "node$member.settings",
                settings,
                mapOf("marketConfigVersion" to expected.version, "marketConfigSha256" to expected.sha256),
                issues,
                "SETUP",
            )
        }
        for (name in listOf("members", "markets")) {
            if (settings.path(name).isMissingNode || settings.path(name) != firstSettings.path(name)) {
                issues.add(FundingVerificationIssue("SETUP", "node$member.$name 공통 실행 설정 불일치"))
            }
        }
        val markets = settings.path("markets")
        if (!markets.isArray || markets.isEmpty || markets != wantedMarkets) {
            issues.add(FundingVerificationIssue("SETUP", "node$member.settings.markets expected=$wantedMarkets, actual=$markets"))
        }
        for (legacyField in listOf("market", "feePolicy")) {
            if (settings.has(legacyField)) {
                issues.add(FundingVerificationIssue("SETUP", "node$member.settings.$legacyField 단일 마켓 필드가 남아 있습니다"))
            }
        }
        val members = settings.path("members").asString()
        if (!(0..2).all { members.contains("$it,node$it:") } || members.contains("127.0.0.1") || members.contains("0.0.0.0")) {
            issues.add(FundingVerificationIssue("SETUP", "node$member 광고 멤버 주소가 세 컨테이너를 가리키지 않습니다: $members"))
        }
        for (directory in listOf("driverDirectory", "archiveDirectory", "clusterDirectory")) {
            if (!settings.path(directory).asString().startsWith("/records/$runId/")) {
                issues.add(FundingVerificationIssue("SETUP", "node$member.$directory 실행별 기록 경로가 아닙니다"))
            }
        }
    }
    return issues
}

private fun expectedFundingMarkets(): List<Map<String, Any>> =
    listOf(
        mapOf(
            "marketId" to "BTC-USDT",
            "baseAssetId" to "BTC",
            "quoteAssetId" to "USDT",
            "baseQuantityScale" to 8,
            "priceTick" to 1L,
            "quantityStep" to 100_000_000L,
            "feePolicy" to
                mapOf(
                    "productType" to "SPOT",
                    "feeTier" to "NORMAL",
                    "scheduleVersion" to 1,
                    "makerPartsPerMillion" to 0,
                    "takerPartsPerMillion" to 0,
                ),
        ),
    )

private fun checkApplied(
    member: Int,
    event: JsonNode,
    issues: MutableList<FundingVerificationIssue>,
) {
    checkFields("node$member", event, mapOf("memberId" to member), issues)
    val state = event.path("state")
    checkFields(
        "node$member.state",
        state,
        mapOf("preparationCount" to 1, "pendingOrderCount" to 0, "requestRecordCount" to 0, "lastSequence" to 0),
        issues,
    )
    checkFields("node$member.result", event.path("result"), expectedFundingResult(), issues)
    val preparations = state.path("preparations")
    if (!preparations.isArray || preparations.size() != 1) {
        issues.add(FundingVerificationIssue("MISMATCH", "node$member.state.preparations expected=1, actual=${preparations.size()}"))
    } else {
        checkFields("node$member.preparation", preparations.path(0), expectedFundingResult(), issues)
    }
    val balances = state.path("balances")
    if (!balances.isArray || balances.size() != 1) {
        issues.add(FundingVerificationIssue("MISMATCH", "node$member.state.balances expected=1, actual=${balances.size()}"))
    } else {
        checkFields(
            "node$member.balance",
            balances.path(0),
            mapOf(
                "userId" to "U1",
                "assetId" to "USDT",
                "available" to 1_000_000_000L,
                "hold" to 0,
            ),
            issues,
        )
    }
    val transactions = state.path("transactions")
    if (!transactions.isArray || transactions.size() != 1) {
        issues.add(FundingVerificationIssue("MISMATCH", "node$member.state.transactions expected=1, actual=${transactions.size()}"))
        return
    }
    val transaction = transactions.path(0)
    checkFields(
        "node$member.transaction",
        transaction,
        mapOf(
            "transactionId" to "L-OPEN-1",
            "sourceEventId" to "OPENING:P1",
            "kind" to "OPENING",
            "occurredAt" to "2026-10-20T00:00:00.123456789Z",
        ),
        issues,
    )
    val postings = transaction.path("postings")
    val expectedPostings =
        listOf(
            mapOf("accountId" to "SYSTEM:USDT:DEVELOPMENT_FUNDING", "assetId" to "USDT", "side" to "DEBIT", "amount" to 1_000_000_000L),
            mapOf("accountId" to "USER:U1:USDT:AVAILABLE", "assetId" to "USDT", "side" to "CREDIT", "amount" to 1_000_000_000L),
        )
    if (!postings.isArray || postings.size() != 2) {
        issues.add(FundingVerificationIssue("MISMATCH", "node$member.transaction.postings expected=2, actual=${postings.size()}"))
    } else {
        val sorted = postings.sortedBy { it.path("side").asString() }
        expectedPostings.sortedBy { it.getValue("side").toString() }.zip(sorted).forEachIndexed { index, (expected, posting) ->
            checkFields("node$member.posting[$index]", posting, expected, issues)
        }
    }
}

internal fun expectedFundingResult(): Map<String, Any> =
    mapOf(
        "preparationId" to "P1",
        "userId" to "U1",
        "assetId" to "USDT",
        "amount" to 1_000_000_000L,
        "ledgerTransactionId" to "L-OPEN-1",
        "occurredAt" to "2026-10-20T00:00:00.123456789Z",
        "alreadyPrepared" to false,
    )

private fun checkFields(
    prefix: String,
    actual: JsonNode,
    expected: Map<String, Any>,
    issues: MutableList<FundingVerificationIssue>,
    category: String = "MISMATCH",
) {
    val mapper = jacksonObjectMapper()
    for ((field, value) in expected) {
        val wanted = mapper.valueToTree<JsonNode>(value)
        val found = actual.path(field)
        val matches =
            if (value is Number) {
                found.isIntegralNumber && found.canConvertToLong() && found.longValue() == value.toLong()
            } else {
                found == wanted
            }
        if (!matches) {
            issues.add(FundingVerificationIssue(category, "$prefix.$field expected=$wanted, actual=${actual.path(field)}"))
        }
    }
}

/** 부분 기동 실패에서도 남아 있는 노드 식별과 원래 오류를 함께 보존한다. */
internal fun discoverFundingNodeIds(
    issues: MutableList<FundingVerificationIssue>,
    lookup: (Int) -> String,
): Map<Int, String> {
    val found = linkedMapOf<Int, String>()
    for (member in 0..2) {
        try {
            found[member] = lookup(member)
        } catch (error: Exception) {
            issues.add(FundingVerificationIssue("COLLECTION", "node$member 컨테이너 식별 실패: ${error.message}"))
        }
    }
    return found
}

/** 로그 조회가 일부만 성공해도 한 번 관찰한 업무 차이를 잃지 않는다. P1 한 실행의 출력만 보존한다. */
internal fun mergeFundingObservations(
    previous: List<JsonNode>,
    current: List<JsonNode>,
): List<JsonNode> = (previous + current).distinct()
