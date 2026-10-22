package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/** 최종 수집한 이번 실행의 모든 업무 관찰을 전송 때 고정한 위치에 귀속시킨다. */
internal fun compareOrderReplicationCoverage(
    runId: String,
    eventsByMember: Map<Int, List<JsonNode>>,
    checkpoints: List<Pair<OrderReplicationStep, Long?>>,
): List<FundingVerificationIssue> {
    val issues = mutableListOf<FundingVerificationIssue>()
    if (checkpoints.isEmpty()) issues.add(FundingVerificationIssue("UNVERIFIABLE", "이번 실행의 checkpoint가 없습니다"))
    var previousPosition = 0L
    for ((step, position) in checkpoints) {
        if (position == null || position <= previousPosition) {
            issues.add(
                FundingVerificationIssue("UNVERIFIABLE", "${step.name} checkpoint 새 위치 미확인: previous=$previousPosition, actual=$position"),
            )
        }
        if (position != null) {
            issues.addAll(compareOrderAppliedObservations(runId, step, eventsByMember, previousPosition, position).issues)
            previousPosition = maxOf(previousPosition, position)
        }
    }
    val positions = checkpoints.mapNotNull { it.second }.toSet()
    for ((member, events) in eventsByMember) {
        for (event in events.filter { it.path("runId").asString() == runId }) {
            if (event.path("kind").asString() == "ERROR") {
                issues.add(FundingVerificationIssue("UNVERIFIABLE", "node$member 오류: ${event.path("failure")}"))
            }
            if (event.path("kind").asString() != "APPLIED") continue
            val position = event.path("position")
            if (observedApplicationPosition(event) !in positions) {
                issues.add(FundingVerificationIssue("UNVERIFIABLE", "node$member 계획 밖 적용 위치: actual=$position, checkpoints=$positions"))
            }
            if (checkpoints.none { (step, _) ->
                    step.inputKind == event.path("inputKind").asString() &&
                        step.identity.all { (key, value) -> event.path(key).asString() == value }
                }
            ) {
                issues.add(
                    FundingVerificationIssue(
                        "MISMATCH",
                        "node$member 계획 밖 입력: position=$position, inputKind=${event.path("inputKind")}, " +
                            "preparationId=${event.path("preparationId")}, requestId=${event.path("requestId")}, " +
                            "marketId=${event.path("marketId")}, orderId=${event.path("orderId")}",
                    ),
                )
            }
            if (member !in 0..2) issues.add(FundingVerificationIssue("MISMATCH", "계획 밖 멤버 node$member 적용 관찰: position=$position"))
        }
    }
    return issues.distinct()
}

/** 단계별 입력·새 적용 위치·응답·독립 정답을 함께 확인한다. 엔진 함수는 호출하지 않는다. */
internal fun compareOrderReplication(
    runId: String,
    step: OrderReplicationStep,
    eventsByMember: Map<Int, List<JsonNode>>,
    clientEvents: List<JsonNode>,
    previousPosition: Long,
    collectionIssues: List<FundingVerificationIssue> = emptyList(),
    pinnedPosition: Long? = null,
): FundingReplicationComparison {
    val applied = compareOrderAppliedObservations(runId, step, eventsByMember, previousPosition, pinnedPosition)
    val issues = (collectionIssues + applied.issues).toMutableList()
    val client = clientEvents.filter { it.path("runId").asString() == runId }
    val accepted = client.filter { it.path("kind").asString() == "TRANSPORT_ACCEPTED" }
    if (accepted.size != 1) issues.add(FundingVerificationIssue("UNVERIFIABLE", "${step.name} 전송 수용 한 건 미확인: ${accepted.size}"))
    val results = client.filter { it.path("kind").asString() == "RESULT_RECEIVED" }
    if (results.size != 1) issues.add(FundingVerificationIssue("UNVERIFIABLE", "${step.name} 응답 한 건 미확인: ${results.size}"))
    accepted.forEach { compareOrderFields("${step.name}.client", it, step.identity, issues) }
    results.forEach {
        compareOrderFields("${step.name}.client.result", it.path("result"), step.expectedResult, issues)
        if (step.inputKind == "ORDER") compareOrderFields("${step.name}.client", it, step.identity + mapOf("inputKind" to "ORDER"), issues)
    }
    if (client.any {
            it.path("kind").asString() == "UNCONFIRMED"
        }
    ) {
        issues.add(FundingVerificationIssue("UNVERIFIABLE", "${step.name} 클라이언트 결과 미확인"))
    }
    return FundingReplicationComparison(applied.position, issues.distinct())
}

private fun compareOrderAppliedObservations(
    runId: String,
    step: OrderReplicationStep,
    eventsByMember: Map<Int, List<JsonNode>>,
    previousPosition: Long,
    pinnedPosition: Long?,
): FundingReplicationComparison {
    val issues = mutableListOf<FundingVerificationIssue>()

    fun belongs(event: JsonNode): Boolean =
        event.path("runId").asString() == runId &&
            step.identity.all { (key, value) -> event.path(key).asString() == value }
    val applied =
        (0..2).associateWith { member ->
            eventsByMember[member].orEmpty().filter {
                belongs(it) &&
                    it.path("kind").asString() == "APPLIED"
            }
        }
    val leaders =
        applied.values
            .flatten()
            .filter { it.path("role").asString() == "LEADER" }
            .mapNotNull(::observedApplicationPosition)
            .distinct()
    // 최초 수집에서는 직전 입력보다 새 위치를 고른다. 재검사에서는 그때 고정한 위치만 읽는다.
    val position = pinnedPosition ?: leaders.filter { it > previousPosition }.singleOrNull()
    if (position == null || position <= previousPosition || position !in leaders) {
        issues.add(FundingVerificationIssue("UNVERIFIABLE", "${step.name} 새 리더 적용 위치 없음: previous=$previousPosition, actual=$leaders"))
    }
    for (member in 0..2) {
        val source = eventsByMember[member].orEmpty().filter { it.path("runId").asString() == runId }
        source.filter { it.path("kind").asString() == "ERROR" }.forEach {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "node$member 오류: ${it.path("failure")}"))
        }
        source.filter { it.path("kind").asString() == "APPLIED" && observedApplicationPosition(it) == null }.forEach {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "node$member 유효한 적용 위치 없음: actual=${it.path("position")}"))
        }
        val identified = applied.getValue(member)
        val observations =
            source.filter {
                val observedPosition = observedApplicationPosition(it)
                it.path("kind").asString() == "APPLIED" &&
                    observedPosition != null &&
                    (
                        observedPosition == position ||
                            (pinnedPosition == null && belongs(it) && observedPosition > previousPosition)
                    )
            }
        if (position == null || position <= previousPosition || identified.none { observedApplicationPosition(it) == position }) {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "${step.name} node$member 새 동일 위치 관찰 누락: expected=$position"))
        }
        if (observations.any { observedApplicationPosition(it) != position } ||
            (position != null && identified.isNotEmpty() && identified.none { observedApplicationPosition(it) == position })
        ) {
            issues.add(
                FundingVerificationIssue("UNVERIFIABLE", "${step.name} node$member 적용 위치 불일치: ${identified.map { it.path("position") }}"),
            )
        }
        // 같은 L의 응답 진단 갱신은 허용한다. 한 행의 업무 차이도 다른 정상 행으로 가리지 않는다.
        observations.forEach { event ->
            compareOrderFields(
                "${step.name}.node$member",
                event,
                step.identity + mapOf("memberId" to member, "inputKind" to step.inputKind),
                issues,
            )
            compareOrderFields("${step.name}.node$member.result", event.path("result"), step.expectedResult, issues)
            compareOrderFields("${step.name}.node$member.state", event.path("state"), step.expectedState, issues)
            if (event.hasNonNull(
                    "failure",
                )
            ) {
                issues.add(FundingVerificationIssue("UNVERIFIABLE", "node$member 실패 관찰: ${event.path("failure")}"))
            }
        }
    }
    return FundingReplicationComparison(position, issues.distinct())
}

/** 알려진 의미 필드는 전부, 배열은 개수와 순서까지 대조한다. 숫자의 JVM 표현 차이는 무시한다. */
internal fun compareOrderFields(
    path: String,
    actual: JsonNode,
    expected: Any?,
    issues: MutableList<FundingVerificationIssue>,
) {
    when (expected) {
        is Map<*, *> -> {
            if (!actual.isObject) {
                issues.add(FundingVerificationIssue("MISMATCH", "$path expected=object, actual=$actual"))
                return
            }
            expected.forEach { (key, value) -> compareOrderFields("$path.$key", actual.path(key.toString()), value, issues) }
        }

        is List<*> -> {
            if (!actual.isArray ||
                actual.size() != expected.size
            ) {
                issues.add(FundingVerificationIssue("MISMATCH", "$path count expected=${expected.size}, actual=$actual"))
            }
            expected.forEachIndexed { index, value -> compareOrderFields("$path[$index]", actual.path(index), value, issues) }
        }

        else -> {
            val wanted = jacksonObjectMapper().valueToTree<JsonNode>(expected)
            val matches =
                if (expected is Number) {
                    actual.isIntegralNumber && actual.canConvertToLong() && actual.longValue() == expected.toLong()
                } else {
                    actual == wanted
                }
            if (!matches) issues.add(FundingVerificationIssue("MISMATCH", "$path expected=$wanted, actual=$actual"))
        }
    }
}
