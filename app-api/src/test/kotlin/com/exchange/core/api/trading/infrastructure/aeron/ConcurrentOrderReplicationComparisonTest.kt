package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConcurrentOrderReplicationComparisonTest {
    @Test
    fun `A와 B 어느 쪽이 먼저여도 실제 로그 순서에 맞는 한 승인과 한 거절을 확인한다`() {
        for (first in listOf("A", "B")) {
            val fixture = Fixture(first)
            val result = fixture.compare()

            assertTrue(result.succeeded, result.issues.toString())
            assertEquals(first, result.orderedSteps.first().name)
            assertEquals(fixture.positions, result.positions)
            assertEquals(1_504L, result.position)
        }
    }

    @Test
    fun `응답 수집 순서와 업무 시각은 실제 적용 순서를 바꾸지 않는다`() {
        val fixture = Fixture("B")
        val clients =
            fixture
                .clients()
                .entries
                .reversed()
                .associate { it.toPair() }
        val events = fixture.events().mapValues { (_, rows) -> rows.reversed() }

        val result = fixture.compare(events, clients)

        assertTrue(result.succeeded, result.issues.toString())
        assertEquals(listOf("B", "A"), result.orderedSteps.map { it.name })
        assertEquals(448L, result.positions["B"])
        assertEquals(1_504L, result.positions["A"])
    }

    @Test
    fun `노드 둘의 일치나 최종 상태만으로 누락된 첫 적용을 대신할 수 없다`() {
        val fixture = Fixture()
        val missingFirst = fixture.events() + (2 to listOf(fixture.applied("B", 2)))
        val finalOnly = (0..2).associateWith { listOf(fixture.applied("B", it)) }

        assertIssue(fixture.compare(events = missingFirst), "UNVERIFIABLE", "A node2 새 동일 위치 관찰 누락")
        assertIssue(fixture.compare(events = finalOnly), "UNVERIFIABLE", "A 새 리더 적용 위치")
        assertIssue(fixture.compare(events = emptyMap(), clients = emptyMap()), "UNVERIFIABLE", "A 새 리더 적용 위치")
    }

    @Test
    fun `과거 실행과 준비 위치와 서로 다른 사본 위치는 이번 두 적용을 증명하지 못한다`() {
        val fixture = Fixture()
        val variations =
            listOf(
                fixture.applied("A", 2).apply { put("runId", "old-run") },
                fixture.applied("A", 2).apply { put("position", fixture.openingPosition) },
                fixture.applied("A", 2).apply { put("position", 704L) },
            )

        for (changed in variations) {
            val result = fixture.compare(events = fixture.events() + (2 to listOf(changed, fixture.applied("B", 2))))
            assertIssue(result, "UNVERIFIABLE", "A node2 새 동일 위치 관찰 누락")
        }
    }

    @Test
    fun `두 요청이 같은 위치이거나 한 요청에 새 위치가 더 있으면 고정 뒤에도 실패한다`() {
        val fixture = Fixture()
        val samePosition =
            fixture.events().mapValues { (_, rows) ->
                rows.map { (it.deepCopy() as ObjectNode).apply { put("position", 448L) } }
            }
        assertIssue(fixture.compare(events = samePosition), "UNVERIFIABLE", "서로 다른 적용 위치")

        for (member in 0..2) {
            val extra = fixture.applied("A", member).apply { put("position", 2_048L) }
            val rows = fixture.events() + (member to (fixture.events().getValue(member) + listOf(extra)))
            assertIssue(fixture.compare(events = rows, pinned = fixture.positions), "UNVERIFIABLE", "A 추가 또는 불일치 적용 위치")
        }
    }

    @Test
    fun `잘못된 고정 위치와 숫자가 아닌 위치는 검증 불가다`() {
        val fixture = Fixture()
        assertIssue(fixture.compare(pinned = mapOf("A" to 704L, "B" to 1_504L)), "UNVERIFIABLE", "A 고정 위치")
        assertIssue(fixture.compare(pinned = mapOf("A" to 448L)), "UNVERIFIABLE", "고정 위치에는 A와 B")
        val malformed = fixture.applied("A", 2).apply { put("position", "448") }
        val events = fixture.events() + (2 to (fixture.events().getValue(2) + listOf(malformed)))
        assertIssue(fixture.compare(events = events), "UNVERIFIABLE", "정수 적용 위치")
    }

    @Test
    fun `같은 위치의 진단 갱신은 허용하고 뒤늦은 업무 모순은 버리지 않는다`() {
        val fixture = Fixture()
        val diagnostics =
            listOf("PENDING", "OFFERED", "NOT_LEADER").mapIndexed { index, status ->
                fixture.applied("A", 0).apply {
                    put("replyStatus", status)
                    put("responseAttempts", index)
                    put("sessionId", 200 + index)
                    put("role", "FOLLOWER")
                }
            }
        val events = fixture.events() + (0 to (fixture.events().getValue(0) + diagnostics))
        assertTrue(fixture.compare(events = events, pinned = fixture.positions).succeeded)

        val contradiction = fixture.applied("A", 2).change("/state/balances/0/hold", 600_000_001L)
        val result =
            fixture.compare(
                events = events + (2 to (events.getValue(2) + listOf(contradiction))),
                pinned = fixture.positions,
                collectionIssues = listOf(FundingVerificationIssue("COLLECTION", "node1 로그 회수 실패")),
            )
        assertIssue(result, "MISMATCH", "A.node2.state.balances[0].hold")
        assertIssue(result, "COLLECTION", "node1 로그 회수 실패")
    }

    @Test
    fun `정상 행 뒤에 같은 위치의 다른 요청을 붙여도 숨길 수 없다`() {
        val fixture = Fixture()
        val bad = fixture.applied("A", 1).apply { put("requestId", "RB") }
        val events = fixture.events() + (1 to (fixture.events().getValue(1) + listOf(bad)))

        assertIssue(fixture.compare(events = events, pinned = fixture.positions), "MISMATCH", "A.node1.requestId")
    }

    @Test
    fun `각 클라이언트의 응답과 전송 수용이 하나씩 필요하며 서로 바꾸면 실패한다`() {
        val fixture = Fixture()
        val clients = fixture.clients()
        val swapped = mapOf("A" to clients.getValue("B"), "B" to clients.getValue("A"))
        assertIssue(fixture.compare(clients = swapped), "MISMATCH", "A.client.requestId")
        for (id in listOf("A", "B")) {
            val rows = clients.getValue(id)
            assertIssue(fixture.compare(clients = clients + (id to rows.take(1))), "UNVERIFIABLE", "$id 응답 한 건 미확인: 0")
            assertIssue(fixture.compare(clients = clients + (id to (rows + listOf(rows.first())))), "UNVERIFIABLE", "$id 전송 수용 한 건 미확인: 2")
            assertIssue(fixture.compare(clients = clients + (id to (rows + listOf(rows.last())))), "UNVERIFIABLE", "$id 응답 한 건 미확인: 2")
            val unknown = tree(mapOf("runId" to fixture.runId, "kind" to "UNCONFIRMED"))
            assertIssue(fixture.compare(clients = clients + (id to (rows + listOf(unknown)))), "UNVERIFIABLE", "$id 클라이언트 결과 미확인")
        }
    }

    @Test
    fun `세 노드가 모두 같은 잘못된 돈을 보여도 독립 정답에 어긋나면 실패한다`() {
        val fixture = Fixture()
        val events =
            fixture.events().mapValues { (_, rows) ->
                rows.map { (it.deepCopy() as ObjectNode).change("/state/balances/0/hold", 1_100_000_000L) }
            }
        val result = fixture.compare(events = events)

        for (member in 0..2) assertIssue(result, "MISMATCH", "node$member.state.balances[0].hold")
    }

    @Test
    fun `두 승인이나 두 거절을 실제 적용 순서로부터 기대한 결과와 대조한다`() {
        val fixture = Fixture()
        for ((id, outcome) in listOf("A" to "REJECTED", "B" to "ACCEPTED")) {
            val changed = fixture.applied(id, 2).change("/result/outcome", outcome)
            val events =
                fixture.events() +
                    (2 to fixture.events().getValue(2).map { if (it.path("requestId").asString() == "R$id") changed else it })
            assertIssue(fixture.compare(events = events), "MISMATCH", "$id.node2.result.outcome")
        }
    }

    @Test
    fun `최종 잔고가 맞아도 거절 이력 누락과 추가 주문 원장을 허용하지 않는다`() {
        val fixture = Fixture()
        val mutations =
            listOf<(ObjectNode) -> Unit>(
                { (it.at("/state/requests") as ArrayNode).remove(1) },
                { (it.at("/state/pendingOrders") as ArrayNode).add(it.at("/state/pendingOrders/0").deepCopy()) },
                { (it.at("/state/transactions") as ArrayNode).add(it.at("/state/transactions/1").deepCopy()) },
                { (it.path("state") as ObjectNode).put("lastSequence", 2L) },
            )
        val paths = listOf("requests count", "pendingOrders count", "transactions count", "lastSequence")
        mutations.forEachIndexed { index, mutate ->
            val bad = fixture.applied("B", 2).apply(mutate)
            assertIssue(
                fixture.compare(events = fixture.events() + (2 to listOf(fixture.applied("A", 2), bad))),
                "MISMATCH",
                "B.node2.state.${paths[index]}",
            )
        }
    }

    @Test
    fun `후보와 원본 정책 원장 및 부족 수치의 변조를 모두 확인한다`() {
        val fixture = Fixture()
        val mutations =
            listOf(
                "/state/pendingOrders/0/candidate/previousBalance/available" to 400_000_000L,
                "/state/pendingOrders/0/candidate/nextBalance/hold" to 500_000_000L,
                "/state/pendingOrders/0/candidate/reservation/status" to "RELEASED",
                "/state/pendingOrders/0/candidate/reservation/remainingQuantity" to 0L,
                "/state/pendingOrders/0/candidate/reservation/remainingAmount" to 0L,
                "/state/pendingOrders/0/candidate/reservation/initialFeeReserveAmount" to 1L,
                "/state/pendingOrders/0/candidate/reservation/remainingFeeReserveAmount" to 1L,
                "/state/pendingOrders/0/candidate/reservation/feeRemainder" to 1L,
                "/state/pendingOrders/0/candidate/reservation/releasedAmount" to 1L,
                "/state/requests/0/order/quantity" to 500_000_000L,
                "/state/requests/0/market/baseQuantityScale" to 6L,
                "/state/requests/0/feePolicy/scheduleVersion" to 2L,
                "/state/requests/1/ledgerTransactionId" to "L-RESERVE-A",
                "/state/requests/1/occurredAtNano" to 123_456_788L,
                "/state/requests/0/result/record/candidate/nextBalance/available" to 500_000_000L,
                "/state/transactions/1/transactionId" to "L-RESERVE-B",
                "/state/transactions/1/sourceEventId" to "RESERVE:wrong",
                "/state/transactions/1/kind" to "SETTLEMENT",
                "/state/transactions/1/occurredAtNano" to 123_456_788L,
                "/state/transactions/1/postings/0/accountId" to "USER:U1:USDT:HOLD",
                "/state/transactions/1/postings/0/side" to "CREDIT",
                "/state/transactions/1/postings/1/assetId" to "BTC",
                "/state/transactions/1/postings/1/amount" to 500_000_000L,
                "/result/insufficientBalance/assetId" to "BTC",
                "/result/insufficientBalance/available" to 500_000_000L,
                "/result/insufficientBalance/required" to 600_000_000L,
                "/result/insufficientBalance/shortfall" to 0L,
            )
        for ((path, value) in mutations) {
            val bad = fixture.applied("B", 2)
            assertFalse(bad.at(path).isMissingNode, "잘못된 변형 대상: $path")
            bad.change(path, value)
            val result = fixture.compare(events = fixture.events() + (2 to listOf(fixture.applied("A", 2), bad)))
            assertIssue(result, "MISMATCH", reportPath("B.node2", path))
        }
    }

    @Test
    fun `버퍼 초과와 실패 행을 정상 적용 행으로 가리지 않는다`() {
        val fixture = Fixture()
        val error = tree(mapOf("runId" to fixture.runId, "kind" to "ERROR", "failure" to "observation overflow"))
        val events = fixture.events() + (2 to (fixture.events().getValue(2) + listOf(error)))

        assertIssue(fixture.compare(events = events), "UNVERIFIABLE", "node2 오류", "observation overflow")
    }

    @Test
    fun `식별자가 없는 잘못된 메시지 관찰도 뒤의 정상 주문에 가려지지 않는다`() {
        val fixture = Fixture()
        val malformedRows =
            listOf(
                tree(mapOf("runId" to fixture.runId, "kind" to "APPLIED", "malformedMessages" to 1L)),
                tree(mapOf("runId" to fixture.runId, "kind" to "APPLIED", "replyStatus" to "MALFORMED_OR_UNSUPPORTED: bad frame")),
            )
        for (malformed in malformedRows) {
            val followedByNormal = fixture.events() + (2 to (listOf(malformed) + fixture.events().getValue(2)))
            assertIssue(fixture.compare(events = followedByNormal), "UNVERIFIABLE", "node2 잘못된 메시지 관찰")
            assertIssue(fixture.compare(events = mapOf(2 to listOf(malformed))), "UNVERIFIABLE", "node2 잘못된 메시지 관찰")
        }
    }

    @Test
    fun `적용 위치를 정하지 못해도 코어 오류의 원인을 함께 남긴다`() {
        val fixture = Fixture()
        val failure = tree(mapOf("runId" to fixture.runId, "kind" to "ERROR", "failure" to "core failure"))

        val result = fixture.compare(events = mapOf(2 to listOf(failure)))

        assertIssue(result, "UNVERIFIABLE", "node2 오류", "core failure")
        assertIssue(result, "UNVERIFIABLE", "서로 다른 적용 위치")
    }

    @Test
    fun `이전 실행의 잘못된 메시지와 오류 행은 이번 실행에 섞지 않는다`() {
        val fixture = Fixture()
        val old =
            listOf(
                tree(mapOf("runId" to "old-run", "kind" to "APPLIED", "malformedMessages" to 1L)),
                tree(mapOf("runId" to "old-run", "kind" to "ERROR", "failure" to "old failure")),
            )

        assertTrue(fixture.compare(events = fixture.events() + (2 to (old + fixture.events().getValue(2)))).succeeded)
    }

    @Test
    fun `BTC와 SUI 두 순서의 고정 관찰은 독립 정답과 일치한다`() {
        for (first in listOf("A", "B")) {
            val fixture = Fixture(first, ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS)
            val result =
                fixture.compare(
                    events =
                        fixture
                            .events()
                            .mapValues { (_, rows) -> rows.reversed() }
                            .entries
                            .reversed()
                            .associate { it.toPair() },
                    clients =
                        fixture
                            .clients()
                            .entries
                            .reversed()
                            .associate { it.toPair() },
                )

            assertTrue(result.succeeded, result.issues.toString())
            assertEquals(first, result.orderedSteps.first().name)
            assertEquals(fixture.positions, result.positions)
            assertEquals(listOf("BTC-USDT", "SUI-USDT"), result.orderedSteps.sortedBy { it.name }.map { it.marketId })
        }
    }

    @Test
    fun `동시 SUI 입력은 수량 250억과 예약 금액 5억을 분리하고 마켓을 명시한다`() {
        val scenario = ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS
        assertEquals(
            listOf(
                "RB",
                "U1",
                "OB",
                "BUY",
                "20000000",
                "25000000000",
                "L-RESERVE-B",
                "2026-10-21T00:00:02.123456789Z",
                "--market",
                "SUI-USDT",
            ),
            scenario.clientArguments.getValue("B"),
        )
        assertEquals(listOf("--market", "BTC-USDT"), scenario.clientArguments.getValue("A").takeLast(2))
        assertEquals("SUI-USDT", scenario.marketId("B"))
        val accepted = tree(scenario.orderedSteps("B").first().expectedState)
        assertEquals(25_000_000_000L, accepted.at("/pendingOrders/0/candidate/reservation/initialQuantity").asLong())
        assertEquals(500_000_000L, accepted.at("/pendingOrders/0/candidate/reservation/reservedAmount").asLong())
        assertEquals(2, accepted.at("/requests/0/feePolicy/scheduleVersion").asInt())
        assertEquals(
            "RESERVE:v1:da01faa1fcc3da26f22206344f4d0ca2ffdd865c446e4e5777c6a9dbd997a47f",
            accepted.at("/transactions/1/sourceEventId").asString(),
        )
    }

    @Test
    fun `세 노드의 같은 SUI 단위 정책 예약 및 원장 오답도 통과하지 않는다`() {
        val fixture = Fixture("B", ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS)
        val mutations =
            listOf(
                "/marketId" to "BTC-USDT",
                "/state/pendingOrders/0/candidate/order/quantity" to 500_000_000L,
                "/state/pendingOrders/0/candidate/market/baseQuantityScale" to 8L,
                "/state/pendingOrders/0/candidate/feePolicy/scheduleVersion" to 1L,
                "/state/pendingOrders/0/candidate/reservation/initialQuantity" to 500_000_000L,
                "/state/pendingOrders/0/candidate/reservation/remainingQuantity" to 500_000_000L,
                "/state/pendingOrders/0/candidate/reservation/reservedAmount" to 25_000_000_000L,
                "/state/pendingOrders/0/candidate/reservation/remainingAmount" to 25_000_000_000L,
                "/state/pendingOrders/0/candidate/reservation/feePolicy/scheduleVersion" to 1L,
                "/state/requests/0/order/price" to 100_000_000L,
                "/state/requests/0/feePolicy/scheduleVersion" to 1L,
                "/state/requests/0/result/record/sequence" to 2L,
                "/state/transactions/1/sourceEventId" to "RESERVE:v1:2a1bde88d4f4a215c4be6ab51451a0d0164860b6d7d3c35ed44993d2f2545cae",
                "/state/transactions/1/postings/0/amount" to 25_000_000_000L,
                "/state/transactions/1/postings/1/accountId" to "USER:U1:SUI:HOLD",
                "/state/transactions/1/transactionId" to "L-RESERVE-A",
                "/state/transactions/1/occurredAtEpochSecond" to 1_792_540_801L,
                "/state/transactions/1/occurredAtNano" to 123_456_788L,
                "/state/balances/0/hold" to 25_000_000_000L,
                "/result/sequence" to 2L,
            )
        for ((path, value) in mutations) {
            val events =
                fixture.events().mapValues { (_, rows) ->
                    rows.map { row ->
                        (row.deepCopy() as ObjectNode).apply { if (this.path("requestId").asString() == "RB") change(path, value) }
                    }
                }
            val result = fixture.compare(events = events)
            for (member in 0..2) assertIssue(result, "MISMATCH", reportPath("B.node$member", path))
        }
    }

    @Test
    fun `다중 마켓의 자금과 승인 거절 흔적 변조는 실패한다`() {
        for (first in listOf("A", "B")) {
            val fixture = Fixture(first, ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS)
            val second = if (first == "A") "B" else "A"
            for ((id, outcome) in listOf(first to "REJECTED", second to "ACCEPTED")) {
                val changed = fixture.applied(id, 2).change("/result/outcome", outcome)
                val events =
                    fixture.events() + (
                        2 to
                            fixture.events().getValue(2).map {
                                if (it.path("requestId").asString() == "R$id") changed else it
                            }
                    )
                assertIssue(fixture.compare(events = events), "MISMATCH", "$id.node2.result.outcome")
            }
            val mutations =
                listOf<Pair<String, (ObjectNode) -> Unit>>(
                    "balances count" to { (it.at("/state/balances") as ArrayNode).add(it.at("/state/balances/0").deepCopy()) },
                    "requests count" to { (it.at("/state/requests") as ArrayNode).remove(1) },
                    "transactions count" to { (it.at("/state/transactions") as ArrayNode).add(it.at("/state/transactions/1").deepCopy()) },
                    "pendingOrders count" to
                        { (it.at("/state/pendingOrders") as ArrayNode).add(it.at("/state/pendingOrders/0").deepCopy()) },
                    "lastSequence" to { it.change("/state/lastSequence", 2L) },
                )
            for ((path, mutate) in mutations) {
                val bad = fixture.applied(second, 2).apply(mutate)
                val result = fixture.compare(events = fixture.events() + (2 to listOf(fixture.applied(first, 2), bad)))
                assertIssue(result, "MISMATCH", "$second.node2.state.$path")
            }
            val badShortage = fixture.applied(second, 2).change("/result/insufficientBalance/shortfall", 0L)
            assertIssue(
                fixture.compare(events = fixture.events() + (2 to listOf(fixture.applied(first, 2), badShortage))),
                "MISMATCH",
                "$second.node2.result.insufficientBalance.shortfall",
            )
        }
    }

    @Test
    fun `다중 마켓 위치는 양수 Long만 허용하고 P1 이전 중복 위치와 멤버 누락을 거절한다`() {
        val fixture = Fixture("B", ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS)
        val invalid = listOf("0", "-1", "128", "127", "9223372036854775808", "18446744073709552064", "448.0", "\"448\"")
        for (raw in invalid) {
            val bad = fixture.applied("B", 2).apply { set("position", jacksonObjectMapper().readTree(raw)) }
            val result = fixture.compare(events = fixture.events() + (2 to (fixture.events().getValue(2) + listOf(bad))))
            assertIssue(result, "UNVERIFIABLE")
        }
        assertIssue(fixture.compare(events = fixture.events().minus(2)), "UNVERIFIABLE", "node2")
        val same = fixture.events().mapValues { (_, rows) -> rows.map { (it.deepCopy() as ObjectNode).apply { put("position", 448L) } } }
        assertIssue(fixture.compare(events = same), "UNVERIFIABLE", "서로 다른 적용 위치")
        val missingFirst = fixture.events().mapValues { (_, rows) -> rows.filter { it.path("requestId").asString() == "RA" } }
        assertIssue(fixture.compare(events = missingFirst), "UNVERIFIABLE", "B 새 리더 적용 위치")
    }

    @Test
    fun `다중 마켓 최종 세 checkpoint는 진단 갱신만 허용하고 계획 밖 입력 위치와 업무 변경을 잡는다`() {
        for (first in listOf("A", "B")) {
            val fixture = Fixture(first, ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS)
            val pair = fixture.compare()
            val opening = ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS.openingStep
            val checkpoints = listOf(opening to fixture.openingPosition) + pair.orderedSteps.map { it to pair.positions[it.name] }
            val events =
                fixture.events().mapValues { (member, rows) ->
                    listOf(
                        tree(
                            opening.identity +
                                mapOf(
                                    "kind" to "APPLIED",
                                    "runId" to fixture.runId,
                                    "memberId" to member,
                                    "role" to if (member == 0) "LEADER" else "FOLLOWER",
                                    "position" to fixture.openingPosition,
                                    "inputKind" to "FUNDING",
                                    "result" to opening.expectedResult,
                                    "state" to opening.expectedState,
                                ),
                        ),
                    ) + rows
                }
            val diagnostic =
                fixture.applied(first, 2).apply {
                    put("replyStatus", "NOT_LEADER")
                    put("responseAttempts", 2)
                }
            val updated = events + (2 to (events.getValue(2) + listOf(diagnostic)))
            assertTrue(compareOrderReplicationCoverage(fixture.runId, updated, checkpoints).isEmpty())
            val mutations =
                listOf<Pair<String, ObjectNode>>(
                    "계획 밖 입력" to
                        fixture.applied(first, 2).apply {
                            put("requestId", "RC")
                            put("orderId", "OC")
                        },
                    "계획 밖 적용 위치" to fixture.applied(first, 2).apply { put("position", 2_048L) },
                    "state.balances[0].hold" to fixture.applied(first, 2).change("/state/balances/0/hold", 1L),
                    "requestId" to fixture.applied(first, 2).apply { put("position", pair.positions.values.max()) },
                )
            for ((fragment, bad) in mutations) {
                val issues = compareOrderReplicationCoverage(fixture.runId, events + (2 to (events.getValue(2) + listOf(bad))), checkpoints)
                assertTrue(issues.any { fragment in it.message }, issues.toString())
            }
        }
    }

    private inner class Fixture(
        first: String = "A",
        val scenario: ConcurrentOrderReplicationScenario = ConcurrentOrderReplicationScenario.BTC_SHARED_FUNDS,
    ) {
        val runId = "concurrent-109"
        val openingPosition = 128L
        val steps =
            if (scenario == ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS) {
                independentConcurrentMultiMarketSteps(first)
            } else {
                scenario.orderedSteps(first)
            }
        val positions = mapOf(steps[0].name to 448L, steps[1].name to 1_504L)

        fun applied(
            id: String,
            member: Int,
        ): ObjectNode {
            val step = steps.single { it.name == id }
            return tree(
                step.identity +
                    mapOf(
                        "kind" to "APPLIED",
                        "runId" to runId,
                        "memberId" to member,
                        "role" to if (member == 0) "LEADER" else "FOLLOWER",
                        "position" to positions.getValue(id),
                        "inputKind" to "ORDER",
                        "result" to step.expectedResult,
                        "state" to step.expectedState,
                    ),
            )
        }

        fun events(): Map<Int, List<JsonNode>> = (0..2).associateWith { member -> steps.map { applied(it.name, member) } }

        fun clients(): Map<String, List<JsonNode>> =
            steps.associate { step ->
                step.name to
                    listOf(
                        tree(step.identity + mapOf("runId" to runId, "kind" to "TRANSPORT_ACCEPTED")),
                        tree(
                            step.identity +
                                mapOf(
                                    "runId" to runId,
                                    "kind" to "RESULT_RECEIVED",
                                    "inputKind" to "ORDER",
                                    "result" to step.expectedResult,
                                ),
                        ),
                    )
            }

        fun compare(
            events: Map<Int, List<JsonNode>> = events(),
            clients: Map<String, List<JsonNode>> = clients(),
            pinned: Map<String, Long>? = null,
            collectionIssues: List<FundingVerificationIssue> = emptyList(),
        ) = compareConcurrentOrderReplication(runId, events, clients, openingPosition, collectionIssues, pinned, scenario)
    }

    private fun tree(value: Any): ObjectNode = jacksonObjectMapper().valueToTree(value)

    private fun ObjectNode.change(
        pointer: String,
        value: Any,
    ): ObjectNode {
        val parent = at(pointer.substringBeforeLast('/')) as ObjectNode
        val field = pointer.substringAfterLast('/')
        when (value) {
            is String -> parent.put(field, value)
            is Long -> parent.put(field, value)
            else -> error("지원하지 않는 변형: $value")
        }
        return this
    }

    private fun reportPath(
        prefix: String,
        pointer: String,
    ) = pointer.removePrefix("/").split('/').fold(prefix) { path, part ->
        if (part.toIntOrNull() ==
            null
        ) {
            "$path.$part"
        } else {
            "$path[$part]"
        }
    }

    private fun assertIssue(
        result: ConcurrentOrderReplicationComparison,
        category: String,
        vararg fragments: String,
    ) {
        assertFalse(result.succeeded, "실패 자료를 성공으로 판정함")
        assertTrue(
            result.issues.any { it.category == category && fragments.all(it.message::contains) },
            "기대한 $category ${fragments.toList()}, 실제 ${result.issues}",
        )
    }
}
