package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerConcurrentCoverageTest {
    private val scenario = ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS
    private val mapper = jacksonObjectMapper()

    @Test
    fun `실행기의 최종 검사는 두 처리 순서 모두 P1과 BTC SUI 세 checkpoint를 포함한다`() {
        for (first in listOf("A", "B")) {
            val runner = verification(first)

            recheck(runner)

            val comparison = field<FundingReplicationComparison>(runner, "comparison")
            assertTrue(comparison.succeeded, comparison.issues.toString())
            assertEquals(768L, comparison.position)
            assertTrue(field<VerificationCommands>(runner, "commandProcesses").records.isEmpty())
        }
    }

    @Test
    fun `최종 수집의 미지 입력 추가 위치와 P1 상태 손상은 앞선 업무 불일치와 함께 남는다`() {
        val original = FundingVerificationIssue("MISMATCH", "앞서 확인한 예약액 불일치")
        for (mutation in listOf("UNKNOWN_INPUT", "EXTRA_POSITION", "OPENING_STATE")) {
            val runner = verification("B")
            val events = field<MutableMap<Int, List<JsonNode>>>(runner, "events")
            val source = events.getValue(1)
            val extra =
                mapper.readTree(source[if (mutation == "OPENING_STATE") 0 else 2].toString()) as ObjectNode
            when (mutation) {
                "UNKNOWN_INPUT" -> extra.put("position", 1_088L).put("requestId", "RC").put("orderId", "OC")
                "EXTRA_POSITION" -> extra.put("position", 1_088L)
                "OPENING_STATE" -> (extra.at("/state/balances/0") as ObjectNode).put("hold", 1L)
            }
            events[1] = source + listOf(extra)
            field<MutableList<FundingVerificationIssue>>(runner, "issues").add(original)

            recheck(runner)

            val comparison = field<FundingReplicationComparison>(runner, "comparison")
            assertFalse(comparison.succeeded, mutation)
            assertTrue(original in comparison.issues, comparison.issues.toString())
            if (mutation == "OPENING_STATE") {
                assertIssue(comparison.issues, "MISMATCH", "P1.node1.state.balances[0].hold")
            } else {
                assertIssue(comparison.issues, "UNVERIFIABLE", "node1 계획 밖 적용 위치", "1088")
            }
            if (mutation == "UNKNOWN_INPUT") assertIssue(comparison.issues, "MISMATCH", "계획 밖 입력", "RC")
            assertTrue(field<VerificationCommands>(runner, "commandProcesses").records.isEmpty())
        }
    }

    private fun verification(first: String): DockerFundingVerification {
        val runner = DockerFundingVerification(concurrentScenario = scenario)
        val runId = field<String>(runner, "runId")
        // 도메인 결과를 재검증하는 대신, 최종 실행기 경로가 세 단계 전체를 검사하는지 확인하는 자료다.
        val orders = scenario.orderedSteps(first)
        val checkpoints = listOf(scenario.openingStep to 128L, orders[0] to 448L, orders[1] to 768L)
        setField(runner, "openingPosition", 128L)
        setField(runner, "pairAttempted", true)
        setField(runner, "pairPositions", mapOf(orders[0].name to 448L, orders[1].name to 768L))
        field<MutableMap<Int, List<JsonNode>>>(runner, "events").putAll(
            (0..2).associateWith { member ->
                checkpoints.map { (step, position) -> applied(runId, member, step, position) } +
                    listOf(execution(runId, member, orders.last().expectedState))
            },
        )
        field<MutableMap<String, List<JsonNode>>>(runner, "pairClientEvents").putAll(
            orders.associate { step ->
                step.name to
                    listOf("READY", "GO_OBSERVED", "SEND_STARTED", "TRANSPORT_ACCEPTED", "RESULT_RECEIVED").map { kind ->
                        clientEvent(runId, step, kind)
                    }
            },
        )
        return runner
    }

    private fun execution(
        runId: String,
        member: Int,
        expectedState: Map<String, Any?>,
    ): JsonNode {
        // 실행기 배선 시험 자료다. 숫자 판정 자체의 반례는 AutomaticExecutionComparisonTest가 별도로 검증한다.
        val event =
            mapper.valueToTree<ObjectNode>(
                mapOf(
                    "kind" to "APPLIED",
                    "runId" to runId,
                    "memberId" to member,
                    "role" to if (member == 0) "LEADER" else "FOLLOWER",
                    "position" to 864L,
                    "inputKind" to "EXECUTION",
                    "executionSequence" to 1L,
                ),
            )
        val executions = listOf<JsonNode>(event)
        event.set("state", mapper.valueToTree<JsonNode>(pendingStateAtPosition(expectedState, executions, 864L)))
        event.set(
            "trading",
            mapper.valueToTree<JsonNode>(restingOnlyExpectedTrading(expectedState, executions, 864L) + ("callbackTimestamp" to 1_000L)),
        )
        (event.at("/trading/completions/0") as ObjectNode).put("occurredAt", "1970-01-01T00:00:01Z")
        return event
    }

    private fun applied(
        runId: String,
        member: Int,
        step: OrderReplicationStep,
        position: Long,
    ): JsonNode =
        mapper.valueToTree(
            mapOf(
                "kind" to "APPLIED",
                "runId" to runId,
                "memberId" to member,
                "role" to if (member == 0) "LEADER" else "FOLLOWER",
                "position" to position,
                "inputKind" to step.inputKind,
                "result" to step.expectedResult,
                "state" to step.expectedState,
            ) + step.identity,
        )

    private fun clientEvent(
        runId: String,
        step: OrderReplicationStep,
        kind: String,
    ): JsonNode {
        val id = step.name
        return mapper.valueToTree(
            mapOf(
                "kind" to kind,
                "runId" to runId,
                "clientId" to id,
                "requestId" to "R$id",
                "orderId" to "O$id",
                "userId" to "U1",
                "marketId" to if (id == "A") "BTC-USDT" else "SUI-USDT",
                "inputKind" to "ORDER",
                "sessionId" to if (id == "A") 1 else 2,
                "pid" to 1,
                "advertisedHost" to "client-${id.lowercase()}",
                "egressChannel" to "aeron:udp?endpoint=client-${id.lowercase()}:19707",
                "driverDirectory" to "/tmp/$runId-client-${id.lowercase()}/driver",
                "runRoot" to "/tmp/$runId-client-${id.lowercase()}",
                "result" to step.expectedResult,
            ),
        )
    }

    private fun recheck(runner: DockerFundingVerification) {
        runner.javaClass
            .getDeclaredMethod("recheckConcurrentInputs")
            .apply { isAccessible = true }
            .invoke(runner)
    }

    private fun assertIssue(
        issues: List<FundingVerificationIssue>,
        category: String,
        vararg fragments: String,
    ) {
        assertTrue(
            issues.any { issue -> issue.category == category && fragments.all(issue.message::contains) },
            "기대한 $category ${fragments.toList()}, 실제 $issues",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(
        target: Any,
        name: String,
    ): T =
        target.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(target) as T

    private fun setField(
        target: Any,
        name: String,
        value: Any,
    ) {
        target.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(target, value)
    }
}
