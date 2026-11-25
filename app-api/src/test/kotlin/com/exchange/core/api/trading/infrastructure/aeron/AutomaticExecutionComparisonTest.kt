package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertTrue

/** 비교 도구의 정상·위반 자료다. 실제 세 JVM 적용 검증은 DockerOrderReplicationTest가 별도로 수행한다. */
class AutomaticExecutionComparisonTest {
    private val run = "automatic-comparison"
    private val mapper = jacksonObjectMapper()
    private val steps get() = automaticTradingSteps(true)
    private val checkpoints get() = steps.mapIndexed { index, step -> step to (128L + 320L * index) }

    @Test
    fun `외부 승인과 별도 실행의 세 사본을 모든 손계산 값 및 callback 시각과 대조한다`() {
        val issues = compareAutomaticExecutionCoverage(run, replicas(), checkpoints)
        assertTrue(issues.isEmpty(), issues.toString())
    }

    @Test
    fun `응답과 외부 접수 관찰이 모두 있어도 한 실행 사본이 없으면 검증 불가다`() {
        val missing =
            replicas().mapValues { (member, events) ->
                if (member ==
                    2
                ) {
                    events.filterNot { it.path("inputKind").asString() == "EXECUTION" && it.path("executionSequence").asLong() == 3L }
                } else {
                    events
                }
            }
        val issues = compareAutomaticExecutionCoverage(run, missing, checkpoints)
        assertTrue(issues.any { it.category == "UNVERIFIABLE" && it.message.contains("승인 3") }, issues.toString())
    }

    @Test
    fun `세 사본이 같은 잘못된 잔고 예약 호가 완료 원장이어도 독립 정답에서 차이를 찾는다`() {
        val mutations =
            listOf(
                "/state/balances/0/available" to 714L,
                "/trading/currentReservations/2/remainingAmount" to 99L,
                "/trading/books/0/orders/0/remainingQuantity" to 200_000_000L,
                "/trading/completions/2/executionId" to "EXECUTE:v1:wrong",
                "/trading/completions/2/events/0/price" to 91L,
                "/trading/completions/2/settlements/1/takerPlan/holdAmountToRelease" to 4L,
                "/trading/completions/2/settlements/1/occurredAt" to "2040-01-01T00:00:00Z",
                "/trading/completions/2/balanceChanges/0/next/hold" to 1L,
                "/trading/completions/2/reservationChanges/0/next/remainingQuantity" to 1L,
                "/state/transactions/5/postings/1/amount" to 89L,
                "/state/requests/2/order/quantity" to 200_000_000L,
            )
        for ((path, wrong) in mutations) {
            val corrupted =
                replicas().mapValues { (_, events) ->
                    events.map { value ->
                        if (value.path("inputKind").asString() == "EXECUTION" && value.path("executionSequence").asLong() == 3L) {
                            (value as ObjectNode).apply {
                                val parent = at(path.substringBeforeLast('/')) as ObjectNode
                                parent.set(path.substringAfterLast('/'), mapper.valueToTree<JsonNode>(wrong))
                            }
                        } else {
                            value
                        }
                    }
                }
            val issues = compareAutomaticExecutionCoverage(run, corrupted, checkpoints)
            assertTrue(issues.any { it.category == "MISMATCH" }, "$path: $issues")
        }
    }

    @Test
    fun `내부 실행 중복 위치와 계획 밖 승인 순번을 조용히 건너뛰지 않는다`() {
        val original = replicas()
        val extra = mapper.readTree(original.getValue(1).first { it.path("inputKind").asString() == "EXECUTION" }.toString()) as ObjectNode
        extra.put("position", 9_999L)
        for (unknownSequence in listOf(false, true)) {
            val mutation = mapper.readTree(extra.toString()) as ObjectNode
            if (unknownSequence) mutation.put("executionSequence", 999L)
            val issues = compareAutomaticExecutionCoverage(run, original + (1 to (original.getValue(1) + listOf(mutation))), checkpoints)
            assertTrue(issues.isNotEmpty(), "unknown=$unknownSequence: $issues")
        }
    }

    @Test
    fun `세 사본에서 같은 시각을 써도 실행 callback 시각과 다른 완료 시각은 실패한다`() {
        val corrupted =
            replicas().mapValues { (_, events) ->
                events.map { value ->
                    if (value.path("inputKind").asString() == "EXECUTION") {
                        (value.at("/trading/completions").last() as ObjectNode).put("occurredAt", "2040-01-01T00:00:00Z")
                    }
                    value
                }
            }
        val issues = compareAutomaticExecutionCoverage(run, corrupted, checkpoints)
        assertTrue(issues.any { it.message.contains("완료 시각") }, issues.toString())
    }

    private fun replicas(): Map<Int, List<JsonNode>> =
        (0..2).associateWith { member ->
            val result = mutableListOf<JsonNode>()
            for ((step, position) in checkpoints) {
                result.add(
                    mapper.valueToTree(
                        mapOf(
                            "kind" to "APPLIED",
                            "runId" to run,
                            "memberId" to member,
                            "role" to
                                if (member ==
                                    0
                                ) {
                                    "LEADER"
                                } else {
                                    "FOLLOWER"
                                },
                            "position" to position,
                            "inputKind" to step.inputKind,
                            "state" to step.expectedState,
                            "result" to step.expectedResult,
                        ) +
                            step.identity,
                    ),
                )
                if (step.executionState != null) {
                    val sequence = (step.expectedResult.getValue("sequence") as Number).toLong()
                    val execution =
                        mapper.valueToTree<ObjectNode>(
                            mapOf(
                                "kind" to "APPLIED",
                                "runId" to run,
                                "memberId" to member,
                                "role" to
                                    if (member ==
                                        0
                                    ) {
                                        "LEADER"
                                    } else {
                                        "FOLLOWER"
                                    },
                                "position" to (checkNotNull(position) + 96),
                                "inputKind" to "EXECUTION",
                                "executionSequence" to sequence,
                                "state" to step.executionState,
                                "trading" to (checkNotNull(step.executionTrading) + ("callbackTimestamp" to 1_000L)),
                            ),
                        )
                    execution.at("/trading/completions").forEach { completion ->
                        (completion as ObjectNode).put("occurredAt", "1970-01-01T00:00:01Z")
                        completion.path("settlements").forEach { (it as ObjectNode).put("occurredAt", "1970-01-01T00:00:01Z") }
                    }
                    execution.at("/state/transactions").filter { it.path("kind").asString() == "SETTLEMENT" }.forEach {
                        (it as ObjectNode)
                            .put(
                                "occurredAt",
                                "1970-01-01T00:00:01Z",
                            ).put("occurredAtEpochSecond", 1L)
                            .put("occurredAtNano", 0)
                    }
                    result.add(execution)
                }
            }
            result
        }
}
