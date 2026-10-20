package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FundingReplicationComparisonTest {
    @Test
    fun `세 사본이 이번 입력의 같은 L에서 독립 잔고와 OPENING 기대값에 맞으면 통과한다`() {
        val events = replicas()
        // 같은 L의 응답 시도 이벤트가 추가되어도 돈을 다시 지급한 것으로 세지 않는다.
        val withReply = events + (0 to (events.getValue(0) + applied(0)))
        assertTrue(compareFundingReplication("run", withReply, client()).succeeded)
    }

    @Test
    fun `하나의 새 관찰이 없거나 다른 실행 관찰이면 검증 불가다`() {
        val wrongRun = replicas() + (2 to listOf(applied(2).apply { put("runId", "old-run") }))
        for (events in listOf(replicas() - 2, wrongRun)) {
            val result = compareFundingReplication("run", events, client())
            assertFalse(result.succeeded)
            assertTrue(result.issues.any { it.category == "UNVERIFIABLE" && it.message.contains("node2") })
        }
    }

    @Test
    fun `같은 P1이라도 오래된 L이나 다른 L은 이번 완료 근거가 아니다`() {
        val old = compareFundingReplication("run", replicas(), client(), previousPosition = 128)
        assertFalse(old.succeeded)
        val different = replicas() + (2 to listOf(applied(2).apply { put("position", 96) }))
        assertTrue(compareFundingReplication("run", different, client()).issues.any { it.message.contains("actual=[96]") })
        val contradictory = replicas() + (2 to listOf(applied(2), applied(2).apply { put("position", 160) }))
        assertTrue(compareFundingReplication("run", contradictory, client()).issues.any { it.message.contains("서로 다른 적용 위치") })
    }

    @Test
    fun `금액 계정 시각은 각각 독립 기대값과 대조한다`() {
        for (field in listOf("available", "accountId", "occurredAt")) {
            val changed = applied(2)
            when (field) {
                "available" -> (changed.at("/state/balances/0") as ObjectNode).put(field, 999)
                "accountId" -> (changed.at("/state/transactions/0/postings/0") as ObjectNode).put(field, "USER:OTHER:USDT:AVAILABLE")
                else -> (changed.at("/state/transactions/0") as ObjectNode).put(field, "2026-10-20T00:00:00Z")
            }
            val result = compareFundingReplication("run", replicas() + (2 to listOf(changed)), client())
            assertTrue(result.issues.any { it.category == "MISMATCH" && it.message.contains(field) }, field)
        }
    }

    @Test
    fun `세 사본이 모두 똑같이 틀려도 통과하지 않는다`() {
        val events =
            (0..2).associateWith { member ->
                listOf(applied(member).apply { (at("/state/balances/0") as ObjectNode).put("available", 2_000_000_000) })
            }
        assertTrue(compareFundingReplication("run", events, client()).issues.count { it.category == "MISMATCH" } == 3)
    }

    @Test
    fun `현재 응답이 맞아도 보존한 최초 준비 원본이 다르면 실패한다`() {
        val changed = applied(1).apply { (at("/state/preparations/0") as ObjectNode).put("amount", 2_000_000_000) }
        val result = compareFundingReplication("run", replicas() + (1 to listOf(changed)), client())
        assertTrue(result.issues.any { it.category == "MISMATCH" && it.message.contains("preparation.amount") })
    }

    @Test
    fun `이미 확인한 불일치는 뒤의 수집 오류와 누락으로 덮어쓰지 않는다`() {
        val bad = applied(0).apply { (at("/state/balances/0") as ObjectNode).put("hold", 1) }
        val result =
            compareFundingReplication(
                "run",
                mapOf(0 to listOf(bad)),
                client(),
                collectionIssues = listOf(FundingVerificationIssue("COLLECTION", "node2 로그 조회 실패")),
            )
        assertTrue(result.issues.any { it.category == "MISMATCH" && it.message.contains("hold") })
        assertTrue(result.issues.any { it.category == "COLLECTION" })
        assertTrue(result.issues.any { it.category == "UNVERIFIABLE" })
    }

    @Test
    fun `전송 수용만 있고 결과가 없으면 반영 관찰이 같아도 성공이 아니다`() {
        val result = compareFundingReplication("run", replicas(), client().filter { it.path("kind").asString() == "TRANSPORT_ACCEPTED" })
        assertFalse(result.succeeded)
        assertTrue(result.issues.any { it.message.contains("최초 결과") })
    }

    @Test
    fun `손상된 구조화 출력은 빈 성공 대신 해석 실패로 남는다`() {
        val issues = mutableListOf<FundingVerificationIssue>()
        assertTrue(readFundingEvents("일반 로그\n${FUNDING_EVENT_PREFIX}{bad", issues).isEmpty())
        assertTrue(issues.single().category == "UNVERIFIABLE")
    }

    @Test
    fun `첫 노드가 생성되지 않아도 다른 두 노드의 기록 식별을 계속한다`() {
        val issues = mutableListOf<FundingVerificationIssue>()
        val found =
            discoverFundingNodeIds(issues) { member ->
                check(member != 0) { "node0 생성 실패" }
                "container-$member"
            }
        kotlin.test.assertEquals(mapOf(1 to "container-1", 2 to "container-2"), found)
        assertTrue(issues.single().message.contains("node0"))
    }

    @Test
    fun `후속 로그가 일부만 반환돼도 앞서 읽은 업무 불일치를 잃지 않는다`() {
        val bad = applied(0).apply { (at("/state/balances/0") as ObjectNode).put("available", 999) }
        val truncated = tree(mapOf("kind" to "READY", "runId" to "run", "memberId" to 0))
        val history = mergeFundingObservations(listOf(bad), listOf(truncated))
        val result =
            compareFundingReplication(
                "run",
                mapOf(0 to history),
                client(),
                collectionIssues = listOf(FundingVerificationIssue("COLLECTION", "부분 로그 조회 실패")),
            )
        assertTrue(result.issues.any { it.category == "MISMATCH" && it.message.contains("available") })
        assertTrue(result.issues.any { it.category == "COLLECTION" })
        assertTrue(result.issues.any { it.category == "UNVERIFIABLE" })
    }

    private fun replicas(): Map<Int, List<JsonNode>> = (0..2).associateWith { listOf(applied(it)) }

    private fun client(): List<JsonNode> =
        listOf(
            tree(mapOf("kind" to "TRANSPORT_ACCEPTED", "runId" to "run")),
            tree(mapOf("kind" to "RESULT_RECEIVED", "runId" to "run", "result" to resultFixture())),
        )

    private fun applied(member: Int): ObjectNode =
        tree(
            mapOf(
                "kind" to "APPLIED",
                "runId" to "run",
                "memberId" to member,
                "role" to if (member == 0) "LEADER" else "FOLLOWER",
                "preparationId" to "P1",
                "position" to 128,
                "result" to resultFixture(),
                "state" to
                    mapOf(
                        "balances" to listOf(mapOf("userId" to "U1", "assetId" to "USDT", "available" to 1_000_000_000, "hold" to 0)),
                        "preparationCount" to 1,
                        "preparations" to listOf(resultFixture()),
                        "pendingOrderCount" to 0,
                        "requestRecordCount" to 0,
                        "lastSequence" to 0,
                        "transactions" to
                            listOf(
                                mapOf(
                                    "transactionId" to "L-OPEN-1",
                                    "sourceEventId" to "OPENING:P1",
                                    "kind" to "OPENING",
                                    "occurredAt" to "2026-10-20T00:00:00.123456789Z",
                                    "postings" to
                                        listOf(
                                            mapOf(
                                                "accountId" to "SYSTEM:USDT:DEVELOPMENT_FUNDING",
                                                "assetId" to "USDT",
                                                "side" to "DEBIT",
                                                "amount" to 1_000_000_000,
                                            ),
                                            mapOf(
                                                "accountId" to "USER:U1:USDT:AVAILABLE",
                                                "assetId" to "USDT",
                                                "side" to "CREDIT",
                                                "amount" to 1_000_000_000,
                                            ),
                                        ),
                                ),
                            ),
                    ),
            ),
        )

    private fun tree(value: Any): ObjectNode = jacksonObjectMapper().valueToTree(value)

    private fun resultFixture(): Map<String, Any> =
        mapOf(
            "preparationId" to "P1",
            "userId" to "U1",
            "assetId" to "USDT",
            "amount" to 1_000_000_000,
            "ledgerTransactionId" to "L-OPEN-1",
            "occurredAt" to "2026-10-20T00:00:00.123456789Z",
            "alreadyPrepared" to false,
        )
}
