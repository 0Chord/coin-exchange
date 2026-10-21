package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode

internal data class ConcurrentOrderReplicationComparison(
    val positions: Map<String, Long>,
    val orderedSteps: List<OrderReplicationStep>,
    val issues: List<FundingVerificationIssue>,
) {
    val succeeded: Boolean get() = issues.isEmpty()
    val position: Long? get() = positions.values.maxOrNull()
}

/** 두 실제 위치로 정답 분기를 고른다. 응답의 승인 여부나 수집 순서는 정렬 기준이 아니다. */
internal fun compareConcurrentOrderReplication(
    runId: String,
    eventsByMember: Map<Int, List<JsonNode>>,
    clientEventsById: Map<String, List<JsonNode>>,
    openingPosition: Long,
    collectionIssues: List<FundingVerificationIssue> = emptyList(),
    pinnedPositions: Map<String, Long>? = null,
): ConcurrentOrderReplicationComparison {
    val issues = collectionIssues.toMutableList()
    val clientIds = setOf("A", "B")
    val positions = mutableMapOf<String, Long>()
    val current = eventsByMember.values.flatten().filter { it.path("runId").asString() == runId }
    for (member in 0..2) {
        eventsByMember[member].orEmpty().filter { it.path("runId").asString() == runId }.forEach { event ->
            if (event.path("kind").asString() == "ERROR") {
                issues.add(FundingVerificationIssue("UNVERIFIABLE", "node$member 오류: ${event.path("failure")}"))
            }
            if (event.path("kind").asString() == "APPLIED" &&
                (
                    event.path("malformedMessages").asLong() > 0 ||
                        event.path("replyStatus").asString().startsWith("MALFORMED_OR_UNSUPPORTED")
                )
            ) {
                issues.add(
                    FundingVerificationIssue(
                        "UNVERIFIABLE",
                        "node$member 잘못된 메시지 관찰: count=${event.path("malformedMessages")}, status=${event.path("replyStatus")}",
                    ),
                )
            }
        }
    }
    if (openingPosition <= 0) issues.add(FundingVerificationIssue("UNVERIFIABLE", "P1의 준비 위치가 유효하지 않습니다: $openingPosition"))
    if (pinnedPositions != null && pinnedPositions.keys != clientIds) {
        issues.add(FundingVerificationIssue("UNVERIFIABLE", "고정 위치에는 A와 B가 모두 있어야 합니다: $pinnedPositions"))
    }
    if (clientEventsById.keys != clientIds) {
        issues.add(FundingVerificationIssue("UNVERIFIABLE", "두 클라이언트의 출력이 필요합니다: ${clientEventsById.keys}"))
    }
    for (id in clientIds) {
        val applied = current.filter { it.path("kind").asString() == "APPLIED" && it.path("requestId").asString() == "R$id" }
        applied.filter { !it.path("position").isIntegralNumber }.forEach {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "$id 정수 적용 위치가 아닙니다: ${it.path("position")}"))
        }
        val fresh = applied.filter { it.path("position").isIntegralNumber && it.path("position").asLong() > openingPosition }
        val leaderPositions = fresh.filter { it.path("role").asString() == "LEADER" }.map { it.path("position").asLong() }.distinct()
        val allPositions = fresh.map { it.path("position").asLong() }.distinct()
        val selected = pinnedPositions?.get(id) ?: leaderPositions.singleOrNull()
        if (leaderPositions.size != 1 || selected == null || selected <= openingPosition || selected !in leaderPositions) {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "$id 새 리더 적용 위치 미확인: previous=$openingPosition, actual=$leaderPositions"))
        }
        if (pinnedPositions != null && leaderPositions != listOf(selected)) {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "$id 고정 위치와 수집한 위치가 다릅니다: expected=$selected, actual=$leaderPositions"))
        }
        // 기존 순차 비교기는 후속 재전송을 허용한다. 이 쌍은 요청별 전송 한 번이므로 어느 사본의 추가 L도 실패다.
        if (allPositions.size != 1 || (selected != null && allPositions != listOf(selected))) {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "$id 추가 또는 불일치 적용 위치: expected=$selected, actual=$allPositions"))
        }
        if (selected != null) positions[id] = selected
    }
    val hasOrderedPositions =
        positions.keys == clientIds && positions.values.distinct().size == 2 && positions.values.all { it > openingPosition }
    if (!hasOrderedPositions) {
        issues.add(FundingVerificationIssue("UNVERIFIABLE", "P1 뒤 서로 다른 적용 위치 두 개가 필요합니다: $positions"))
        return ConcurrentOrderReplicationComparison(positions.toMap(), emptyList(), issues.distinct())
    }
    val first = positions.minBy { it.value }.key
    val steps = ConcurrentOrderReplicationScenario.orderedSteps(first)
    var previous = openingPosition
    for (step in steps) {
        val compared =
            compareOrderReplication(
                runId,
                step,
                eventsByMember,
                clientEventsById[step.name].orEmpty(),
                previous,
                pinnedPosition = positions.getValue(step.name),
            )
        issues.addAll(compared.issues)
        previous = positions.getValue(step.name)
    }
    return ConcurrentOrderReplicationComparison(positions.toMap(), steps, issues.distinct())
}
