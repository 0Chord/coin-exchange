package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/** 단계별 입력·새 적용 위치·응답·독립 정답을 함께 확인한다. 엔진 함수는 호출하지 않는다. */
internal fun compareOrderReplication(
    runId: String,
    step: OrderReplicationStep,
    eventsByMember: Map<Int, List<JsonNode>>,
    clientEvents: List<JsonNode>,
    previousPosition: Long,
    collectionIssues: List<FundingVerificationIssue> = emptyList(),
): FundingReplicationComparison {
    val issues = collectionIssues.toMutableList()

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
            .map { it.path("position").asLong() }
            .distinct()
    val position = leaders.singleOrNull()
    if (position == null || position <= previousPosition) {
        issues.add(FundingVerificationIssue("UNVERIFIABLE", "${step.name} 새 리더 적용 위치 없음: previous=$previousPosition, actual=$leaders"))
    }
    for (member in 0..2) {
        val source = eventsByMember[member].orEmpty().filter { it.path("runId").asString() == runId }
        source.filter { it.path("kind").asString() == "ERROR" }.forEach {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "node$member 오류: ${it.path("failure")}"))
        }
        val identified = applied.getValue(member)
        val observations =
            source.filter {
                it.path("kind").asString() == "APPLIED" &&
                    (belongs(it) || (position != null && it.path("position").asLong() == position))
            }
        if (position == null || position <= previousPosition || identified.none { it.path("position").asLong() == position }) {
            issues.add(FundingVerificationIssue("UNVERIFIABLE", "${step.name} node$member 새 동일 위치 관찰 누락: expected=$position"))
        }
        if (observations.any { it.path("position").asLong() != position }) {
            issues.add(
                FundingVerificationIssue("UNVERIFIABLE", "${step.name} node$member 적용 위치 불일치: ${observations.map { it.path("position") }}"),
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
            val matches = if (expected is Number) actual.isIntegralNumber && actual.asLong() == expected.toLong() else actual == wanted
            if (!matches) issues.add(FundingVerificationIssue("MISMATCH", "$path expected=$wanted, actual=$actual"))
        }
    }
}
