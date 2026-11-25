package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderReplicationCoverageTest {
    private val runId = "shared-funds"
    private val steps get() = OrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS.steps()
    private val checkpoints get() = steps.mapIndexed { index, step -> step to (128L + 320L * index) }

    @Test
    fun `최종 수집의 Long 범위 밖 금액과 순번도 앞서 찾은 불일치와 함께 보존한다`() {
        val outsideLong = listOf(java.math.BigInteger("9223372036854775808"), java.math.BigInteger("-9223372036854775809"))
        for (value in outsideLong) {
            val changed =
                applied(1, 5).apply {
                    (at("/state/balances/0") as ObjectNode).put("hold", value)
                    (path("state") as ObjectNode).put("lastSequence", value)
                    put("marketId", "BTC-USDT")
                }
            val issues = compare(replicas() + (1 to (replicas().getValue(1) + listOf(changed))))

            assertIssue(issues, "MISMATCH", "node1.marketId")
            assertIssue(issues, "MISMATCH", "hold expected=1000000000", "actual=$value")
            assertIssue(issues, "MISMATCH", "lastSequence expected=2", "actual=$value")
        }
    }

    @Test
    fun `여섯 전송은 여섯 위치에 귀속되고 같은 SUI 거절의 세 위치와 응답 진단 갱신은 허용한다`() {
        assertEquals(6, checkpoints.size)
        assertEquals(listOf("R-SUI", "R-SUI", "R-SUI"), listOf(2, 3, 5).map { steps[it].identity["requestId"] })
        val events = replicas().toMutableMap()
        events[2] =
            events.getValue(2) +
            listOf("PENDING", "OFFERED", "NOT_LEADER").map { diagnostic ->
                applied(2, 5).put("replyStatus", diagnostic).put("responseAttempts", 9)
            }

        assertTrue(compare(events).isEmpty(), compare(events).toString())
    }

    @Test
    fun `최종 수집에서 다른 identity 또는 같은 identity의 계획 밖 위치가 추가되면 실패한다`() {
        for (unknownIdentity in listOf(false, true)) {
            val extra = applied(2, 5).put("position", 9_999L)
            if (unknownIdentity) extra.put("requestId", "UNPLANNED")
            val events = replicas() + (2 to (replicas().getValue(2) + listOf(extra)))
            val issues = compare(events)

            assertIssue(issues, "UNVERIFIABLE", "node2", "계획 밖 적용 위치", "9999")
            if (unknownIdentity) assertIssue(issues, "MISMATCH", "계획 밖 입력")
        }
    }

    @Test
    fun `같은 고정 위치의 다른 입력과 모순된 업무값은 정상 행 뒤에 있어도 검출한다`() {
        val wrongInput = applied(1, 5).put("marketId", "BTC-USDT")
        val wrongState = applied(1, 5).apply { (at("/state/balances/0") as ObjectNode).put("hold", 600_000_000L) }
        for ((bad, expectedField) in listOf(wrongInput to "marketId", wrongState to "hold")) {
            val events = replicas() + (1 to (replicas().getValue(1) + listOf(bad)))

            assertIssue(compare(events), "MISMATCH", "node1", expectedField)
        }
    }

    @Test
    fun `한 멤버의 마지막 재전송 관찰이 없고 과거 SUI 관찰만 남으면 실패한다`() {
        val events = replicas() + (2 to replicas().getValue(2).dropLast(1))

        assertIssue(compare(events), "UNVERIFIABLE", "node2 새 동일 위치 관찰 누락")
    }

    @Test
    fun `checkpoint 누락 중복 역순은 전송마다 새 위치를 확인한 근거가 아니다`() {
        val missing = checkpoints.mapIndexed { index, (step, position) -> step to if (index == 3) null else position }
        val duplicate = checkpoints.mapIndexed { index, (step, position) -> step to if (index == 3) checkpoints[2].second else position }
        val reversed = checkpoints.mapIndexed { index, (step, _) -> step to checkpoints.reversed()[index].second }

        for (invalid in listOf(missing, duplicate, reversed)) {
            assertIssue(compareOrderReplicationCoverage(runId, replicas(), invalid), "UNVERIFIABLE", "checkpoint")
        }
        assertIssue(compareOrderReplicationCoverage(runId, replicas(), emptyList()), "UNVERIFIABLE", "checkpoint")
    }

    @Test
    fun `읽을 수 없는 적용 위치와 관찰 실패는 정상 여섯 단계가 있어도 남긴다`() {
        val malformedPositions =
            listOf(null, "unreadable", "1728", 1728.5, 0L, -1L, java.math.BigInteger("9223372036854775808"))
        for (position in malformedPositions) {
            val malformed = applied(2, 5).apply { set("position", jacksonObjectMapper().valueToTree<JsonNode>(position)) }
            assertIssue(compare(replicas() + (2 to (replicas().getValue(2) + listOf(malformed)))), "UNVERIFIABLE", "적용 위치")
        }
        val missing = applied(2, 5).apply { remove("position") }
        assertIssue(compare(replicas() + (2 to (replicas().getValue(2) + listOf(missing)))), "UNVERIFIABLE", "적용 위치")
        val failure = applied(2, 5).put("kind", "ERROR").put("failure", "관찰 큐 용량 초과")
        assertIssue(compare(replicas() + (2 to (replicas().getValue(2) + listOf(failure)))), "UNVERIFIABLE", "관찰 큐 용량 초과")
    }

    @Test
    fun `다른 실행 기록과 READY 응답 진단은 이번 실행의 업무 적용 개수에 포함하지 않는다`() {
        val unrelated = applied(0, 5).put("runId", "old-run").put("position", 9_999L)
        val ready = applied(0, 5).put("kind", "READY").put("position", 9_999L)
        val events = replicas() + (0 to (replicas().getValue(0) + listOf(unrelated, ready)))

        assertTrue(compare(events).isEmpty(), compare(events).toString())
    }

    private fun replicas(): Map<Int, List<JsonNode>> = (0..2).associateWith { member -> steps.indices.map { applied(member, it) } }

    private fun applied(
        member: Int,
        index: Int,
    ): ObjectNode {
        val (step, position) = checkpoints[index]
        // 비교기의 반례 자료만 조립한다. 실제 도메인 처리와 출력은 Docker 사례에서 대조한다.
        return jacksonObjectMapper().valueToTree(
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
    }

    private fun compare(events: Map<Int, List<JsonNode>>): List<FundingVerificationIssue> =
        compareOrderReplicationCoverage(runId, events, checkpoints)

    private fun assertIssue(
        issues: List<FundingVerificationIssue>,
        category: String,
        vararg fragments: String,
    ) {
        assertTrue(
            issues.any { issue -> issue.category == category && fragments.all { issue.message.contains(it) } },
            "기대한 $category ${fragments.toList()}, 실제 $issues",
        )
    }
}
