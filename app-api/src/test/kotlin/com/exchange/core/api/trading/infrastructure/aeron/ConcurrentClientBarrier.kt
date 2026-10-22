package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 연결된 두 클라이언트의 준비 근거를 확인한 뒤 공통 출발 파일을 원자적으로 공개한다. */
internal class ConcurrentClientBarrier(
    private val directory: Path,
    private val runId: String,
    private val scenario: ConcurrentOrderReplicationScenario = ConcurrentOrderReplicationScenario.BTC_SHARED_FUNDS,
) {
    val go: Path = directory.resolve("go")

    fun release(events: Map<String, List<JsonNode>>): List<FundingVerificationIssue> {
        val issues = compareConcurrentClientReadiness(runId, events, scenario).toMutableList()
        if (Files.exists(go)) issues.add(FundingVerificationIssue("SETUP", "이미 존재하는 GO는 이번 출발에 사용할 수 없습니다"))
        if (issues.isNotEmpty()) return issues
        val pending = directory.resolve("go.pending")
        Files.writeString(pending, runId)
        Files.move(pending, go, StandardCopyOption.ATOMIC_MOVE)
        return emptyList()
    }
}

internal fun compareConcurrentClientReadiness(
    runId: String,
    events: Map<String, List<JsonNode>>,
    scenario: ConcurrentOrderReplicationScenario = ConcurrentOrderReplicationScenario.BTC_SHARED_FUNDS,
): List<FundingVerificationIssue> {
    val issues = mutableListOf<FundingVerificationIssue>()
    val ready = mutableListOf<JsonNode>()
    for (id in listOf("A", "B")) {
        val source = events[id].orEmpty()
        val rows = source.filter { it.path("kind").asString() == "READY" }
        if (rows.size != 1) {
            issues.add(FundingVerificationIssue("SETUP", "$id 실제 연결 READY 한 건 미확인"))
            continue
        }
        val row = rows.single()
        val expected =
            mapOf(
                "runId" to runId,
                "clientId" to id,
                "requestId" to "R$id",
                "orderId" to "O$id",
                "userId" to "U1",
                "marketId" to scenario.marketId(id),
                "inputKind" to "ORDER",
                "advertisedHost" to "client-${id.lowercase()}",
                "runRoot" to "/tmp/$runId-client-${id.lowercase()}",
            )
        val differences = mutableListOf<FundingVerificationIssue>()
        compareOrderFields("$id.READY", row, expected, differences)
        issues.addAll(differences.map { it.copy(category = "SETUP") })
        if (!row.path("sessionId").isIntegralNumber || row.path("sessionId").asLong() < 0 || row.path("pid").asLong() <= 0) {
            issues.add(FundingVerificationIssue("SETUP", "$id 실제 세션·JVM PID 근거 없음"))
        }
        for (field in listOf("driverDirectory", "egressChannel")) {
            if (row.path(field).asString().isBlank()) issues.add(FundingVerificationIssue("SETUP", "$id $field 누락"))
        }
        if (source.any { it.path("kind").asString() != "READY" }) issues.add(FundingVerificationIssue("SETUP", "$id GO 전에 진행·실패 이벤트가 있습니다"))
        ready.add(row)
    }
    if (ready.size == 2) {
        for (field in listOf("sessionId", "advertisedHost", "egressChannel", "driverDirectory", "runRoot")) {
            if (ready[0].path(field) == ready[1].path(field)) issues.add(FundingVerificationIssue("SETUP", "두 클라이언트의 $field 공유"))
        }
    }
    return issues.distinct()
}

/** 같은 JVM의 이벤트 순서만 검사한다. 서로 다른 컴퓨터의 시각은 비교하지 않는다. */
internal fun compareConcurrentClientProgress(
    runId: String,
    events: Map<String, List<JsonNode>>,
    scenario: ConcurrentOrderReplicationScenario = ConcurrentOrderReplicationScenario.BTC_SHARED_FUNDS,
): List<FundingVerificationIssue> {
    val issues = mutableListOf<FundingVerificationIssue>()
    val kinds = listOf("READY", "GO_OBSERVED", "SEND_STARTED", "TRANSPORT_ACCEPTED", "RESULT_RECEIVED")
    for (id in listOf("A", "B")) {
        val source = events[id].orEmpty()
        val actualKinds = source.map { it.path("kind").asString() }
        if (actualKinds != kinds) issues.add(FundingVerificationIssue("UNVERIFIABLE", "$id READY→GO→SEND→전송→응답 순서 미확인: $actualKinds"))
        val ready = source.firstOrNull() ?: continue
        for (event in source) {
            val expected =
                mapOf(
                    "runId" to runId,
                    "clientId" to id,
                    "requestId" to "R$id",
                    "orderId" to "O$id",
                    "userId" to "U1",
                    "marketId" to scenario.marketId(id),
                    "inputKind" to "ORDER",
                )
            compareOrderFields("$id.${event.path("kind")}", event, expected, issues)
            for (field in listOf("sessionId", "advertisedHost", "egressChannel", "driverDirectory", "runRoot", "pid")) {
                if (event.path(field) != ready.path(field)) issues.add(FundingVerificationIssue("MISMATCH", "$id 진행 중 $field 변경"))
            }
        }
    }
    return issues.distinct()
}
