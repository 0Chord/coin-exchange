package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderReplicationComparisonTest {
    @Test
    fun `주문 관찰과 응답의 금액 순번이 Long 범위를 벗어나도 불일치로 남긴다`() {
        val fixture = fixture(OrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS, 4)
        val outsideLong = listOf(java.math.BigInteger("9223372036854775808"), java.math.BigInteger("-9223372036854775809"))
        for (value in outsideLong) {
            for ((parent, field) in listOf("/state/balances/0" to "hold", "/state" to "lastSequence", "/result" to "sequence")) {
                val changed =
                    fixture.applied(2).apply { (at(parent) as ObjectNode).set(field, jacksonObjectMapper().valueToTree<JsonNode>(value)) }

                assertIssue(
                    fixture.compare(events = fixture.replicas() + (2 to listOf(changed))),
                    "MISMATCH",
                    "$field expected=",
                    "actual=$value",
                )
            }
            val response = fixture.client().last().apply { (path("result") as ObjectNode).put("sequence", value) }
            assertIssue(
                fixture.compare(client = fixture.client().take(1) + listOf(response)),
                "MISMATCH",
                "client.result.sequence",
                "actual=$value",
            )
        }
    }

    @Test
    fun `주문 의미 필드의 Long 최댓값은 정수 표현이 달라도 기대값과 같으면 통과한다`() {
        val expected = mapOf("amount" to Long.MAX_VALUE, "sequence" to Long.MAX_VALUE)
        val actual = tree(expected.mapValues { java.math.BigInteger(Long.MAX_VALUE.toString()) })
        val issues = mutableListOf<FundingVerificationIssue>()

        compareOrderFields("boundary", actual, expected, issues)

        assertTrue(issues.isEmpty(), issues.toString())
    }

    @Test
    fun `리더나 팔로워의 적용 위치가 손상되면 예외 대신 관찰 부족과 검증 불가를 남긴다`() {
        val fixture = fixture()
        val malformedPositions =
            listOf(null, "unreadable", "448", 448.5, 0L, -1L, java.math.BigInteger("9223372036854775808"))
        for (member in listOf(0, 2)) {
            val malformed =
                malformedPositions.map { position ->
                    fixture.applied(member).apply { set("position", jacksonObjectMapper().valueToTree<JsonNode>(position)) }
                } + listOf(fixture.applied(member).apply { remove("position") })
            for (event in malformed) {
                assertIssue(fixture.compare(events = fixture.replicas() + (member to listOf(event))), "UNVERIFIABLE", "적용 위치")
            }
        }
    }

    @Test
    fun `SUI 단계의 마켓 identity를 BTC로 바꾸거나 누락하면 관찰과 클라이언트 모두 실패한다`() {
        val fixture = fixture(OrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS, 4)
        for (missing in listOf(false, true)) {
            fun changeMarket(event: ObjectNode): ObjectNode =
                event.apply { if (missing) remove("marketId") else put("marketId", "BTC-USDT") }
            val events = fixture.replicas() + (2 to listOf(changeMarket(fixture.applied(2))))
            assertIssue(fixture.compare(events = events), "MISMATCH", "node2.marketId")
            assertIssue(fixture.compare(client = fixture.client().map(::changeMarket)), "MISMATCH", "client.marketId")
        }
    }

    @Test
    fun `공유 USDT 잔고를 BTC와 SUI별 두 항목으로 나누면 합계가 맞아도 실패한다`() {
        val fixture = fixture(OrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS, 5)
        val changed =
            fixture.applied(2).apply {
                val balances = at("/state/balances") as ArrayNode
                (balances.path(0) as ObjectNode).put("hold", 600_000_000L).put("marketId", "BTC-USDT")
                balances.add(
                    tree(
                        mapOf(
                            "userId" to "U1",
                            "assetId" to "USDT",
                            "marketId" to "SUI-USDT",
                            "available" to 0,
                            "hold" to 400_000_000,
                        ),
                    ),
                )
            }

        assertIssue(fixture.compare(events = fixture.replicas() + (2 to listOf(changed))), "MISMATCH", "balances count expected=1")
    }

    @Test
    fun `최종 BTC SUI의 순번과 대기 순서 및 각 마켓의 예약 문맥은 독립 정답과 맞아야 한다`() {
        val fixture = fixture(OrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS, 5)
        fixture.assertMutations(
            listOf(
                Mutation("/state/pendingOrders/1/sequence", 1L),
                Mutation("/state/requests/2/result/record/sequence", 1L),
                Mutation("/state/pendingOrders/1/candidate/order/marketId", "BTC-USDT"),
                Mutation("/state/pendingOrders/1/candidate/order/quantity", 2_000_000_000L),
                Mutation("/state/pendingOrders/1/candidate/market/baseQuantityScale", 8L),
                Mutation("/state/pendingOrders/1/candidate/feePolicy/scheduleVersion", 1L),
                Mutation("/state/pendingOrders/1/candidate/reservation/marketId", "BTC-USDT"),
                Mutation("/state/pendingOrders/1/candidate/reservation/reservedAmount", 600_000_000L),
                Mutation("/state/pendingOrders/1/candidate/reservation/feePolicy/scheduleVersion", 1L),
                Mutation("/state/requests/1/market/baseQuantityScale", 8L),
                Mutation("/state/requests/1/feePolicy/scheduleVersion", 1L),
                Mutation("/state/requests/2/ledgerTransactionId", "ledger-R-SUI"),
                Mutation("/state/transactions/2/sourceEventId", "RESERVE:OTHER"),
            ),
        )
        val reversed =
            fixture.applied(2).apply {
                val pending = at("/state/pendingOrders") as ArrayNode
                pending.add(pending.remove(0))
            }
        assertIssue(fixture.compare(events = fixture.replicas() + (2 to listOf(reversed))), "MISMATCH", "pendingOrders[0].sequence")
    }

    @Test
    fun `최종 잔고가 0이어도 최초 SUI 부족값과 과거 BTC 후보는 갱신하지 않는다`() {
        val fixture = fixture(OrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS, 5)
        fixture.assertMutations(
            listOf(
                Mutation("/result/insufficientBalance/available", 0L),
                Mutation("/state/requests/1/result/insufficientBalance/available", 0L),
                Mutation("/state/pendingOrders/0/candidate/previousBalance/available", 0L),
                Mutation("/state/pendingOrders/0/candidate/nextBalance/available", 0L),
                Mutation("/state/requests/0/result/record/candidate/previousBalance/available", 0L),
                Mutation("/state/requests/0/result/record/candidate/nextBalance/hold", 1_000_000_000L),
            ),
        )
        val wrong = (0..2).associateWith { member -> listOf(fixture.applied(member).change("/result/insufficientBalance/available", 0L)) }
        val result = fixture.compare(events = wrong)
        for (member in 0..2) {
            assertIssue(
                result,
                "MISMATCH",
                "node$member.result.insufficientBalance.available expected=400000000, actual=0",
            )
        }
    }

    @Test
    fun `SUI 거절 이력 누락 또는 거절 주문의 예약과 전표 추가는 실패한다`() {
        val fixture = fixture(OrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS, 3)
        val accepted = this.fixture(OrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS, 4).applied(2)
        val missing = fixture.applied(2).apply { (at("/state/requests") as ArrayNode).remove(1) }
        assertIssue(fixture.compare(events = fixture.replicas() + (2 to listOf(missing))), "MISMATCH", "requests count expected=2")
        for (field in listOf("pendingOrders", "transactions")) {
            val changed =
                fixture.applied(2).apply {
                    (at("/state/$field") as ArrayNode).add(accepted.at("/state/$field").last())
                }
            assertIssue(fixture.compare(events = fixture.replicas() + (2 to listOf(changed))), "MISMATCH", "$field count expected=")
        }
    }

    @Test
    fun `같은 R1의 과거 관찰이 있어도 이번 전송의 새 위치를 따로 확인한다`() {
        val first = fixture()
        val replay = Fixture(first.runId, first.step, 928L, first.position)
        val history = (0..2).associateWith { listOf(first.applied(it), replay.applied(it)) }

        val result = replay.compare(events = history)

        assertTrue(result.succeeded, result.issues.toString())
        assertEquals(928L, result.position)
    }

    @Test
    fun `나중 R1 위치는 고정한 과거 위치를 바꾸지 않고 뒤늦은 과거 모순은 잡는다`() {
        val first = fixture()
        val replay = Fixture(first.runId, first.step, 928L, first.position)
        val history = (0..2).associateWith { listOf(first.applied(it), replay.applied(it)) }
        assertTrue(first.compare(events = history, pinnedPosition = first.position).succeeded)

        val late = first.applied(2).change("/state/balances/0/hold", 600_000_000L)
        val result =
            first.compare(
                events = history + (2 to (history.getValue(2) + listOf(late))),
                pinnedPosition = first.position,
            )

        assertEquals(first.position, result.position)
        assertIssue(result, "MISMATCH", "state.balances[0].hold expected=300000000, actual=600000000")
    }

    @Test
    fun `R1 재전송에 과거 위치만 있거나 한 사본이 새 위치를 빠뜨리면 실패한다`() {
        val first = fixture()
        val replay = Fixture(first.runId, first.step, 928L, first.position)
        assertIssue(replay.compare(events = first.replicas()), "UNVERIFIABLE", "새 리더 적용 위치 없음")
        val missing = replay.replicas() + (2 to listOf(first.applied(2)))
        assertIssue(replay.compare(events = missing), "UNVERIFIABLE", "node2 새 동일 위치 관찰 누락")
    }

    @Test
    fun `전송 단계 이름과 실제 R1 O1 식별자를 분리한다`() {
        val first = fixture().step
        val replay = first.copy(name = "A-2")

        assertEquals(first.identity, replay.identity)
        assertEquals("R1", replay.identity["requestId"])
        assertEquals("O1", replay.identity["orderId"])
    }

    @Test
    fun `모든 독립 사례의 준비와 주문 단계를 새 동일 위치에서 확인한다`() {
        for (scenario in OrderReplicationScenario.entries) {
            for (index in scenario.steps().indices) {
                val fixture = fixture(scenario, index)
                val result = fixture.compare()

                assertTrue(result.succeeded, "${scenario.name}/${fixture.step.name}: ${result.issues}")
                assertEquals(fixture.position, result.position)
            }
        }
    }

    @Test
    fun `노드 관찰 누락과 이전 실행 관찰은 누락 대상을 남기고 검증 불가다`() {
        val fixture = fixture()
        val oldRun = fixture.applied(2).apply { put("runId", "old-run") }
        val cases = listOf(fixture.replicas() - 2, fixture.replicas() + (2 to listOf(oldRun)))

        for (events in cases) {
            val result = fixture.compare(events = events)

            assertIssue(result, "UNVERIFIABLE", "R1 node2 새 동일 위치 관찰 누락", "expected=448")
        }
    }

    @Test
    fun `전송 수용과 세 사본이 있어도 이번 응답이 없으면 검증 불가다`() {
        val fixture = fixture()
        val transportOnly = fixture.client().take(1)
        val oldResponse = fixture.client().last().apply { put("runId", "old-run") }

        for (client in listOf(transportOnly, transportOnly + listOf(oldResponse))) {
            val result = fixture.compare(client = client)

            assertIssue(result, "UNVERIFIABLE", "R1 응답 한 건 미확인: 0")
        }
    }

    @Test
    fun `비어 있는 관찰과 응답을 성공으로 판정하지 않는다`() {
        val fixture = fixture()
        val result = fixture.compare(events = emptyMap(), client = emptyList())

        assertIssue(result, "UNVERIFIABLE", "새 리더 적용 위치 없음")
        for (member in 0..2) {
            assertIssue(result, "UNVERIFIABLE", "node$member 새 동일 위치 관찰 누락")
        }
        assertIssue(result, "UNVERIFIABLE", "전송 수용 한 건 미확인: 0")
        assertIssue(result, "UNVERIFIABLE", "응답 한 건 미확인: 0")
    }

    @Test
    fun `같거나 더 오래된 리더 위치는 다음 checkpoint가 되지 않는다`() {
        val fixture = fixture()

        for (previous in listOf(fixture.position, fixture.position + 64)) {
            val result = fixture.compare(previousPosition = previous)

            assertIssue(result, "UNVERIFIABLE", "R1 새 리더 적용 위치 없음", "previous=$previous", "actual=[448]")
            for (member in 0..2) {
                assertIssue(result, "UNVERIFIABLE", "node$member 새 동일 위치 관찰 누락")
            }
        }
    }

    @Test
    fun `노드별 과거 위치와 더 뒤의 위치 모두 이번 리더 위치를 대신하지 못한다`() {
        val fixture = fixture()

        for (position in listOf(128L, 928L)) {
            val changed = fixture.applied(2).apply { put("position", position) }
            val result = fixture.compare(events = fixture.replicas() + (2 to listOf(changed)))

            assertIssue(result, "UNVERIFIABLE", "R1 node2 새 동일 위치 관찰 누락", "expected=448")
            assertIssue(result, "UNVERIFIABLE", "R1 node2 적용 위치 불일치", position.toString())
        }
    }

    @Test
    fun `같은 입력의 서로 다른 리더 위치를 정상 행 하나로 가리지 않는다`() {
        val fixture = fixture()
        val later = fixture.applied(0).apply { put("position", 928) }
        val events = fixture.replicas() + (0 to listOf(fixture.applied(0), later))
        val result = fixture.compare(events = events)

        assertIssue(result, "UNVERIFIABLE", "R1 새 리더 적용 위치 없음", "actual=[448, 928]")
    }

    @Test
    fun `같은 위치의 역할 세션 주소와 응답 시도 갱신은 업무 차이가 아니다`() {
        val fixture = fixture()
        val updates =
            listOf("PENDING", "OFFERED", "NOT_LEADER").mapIndexed { index, status ->
                fixture.applied(0).apply {
                    put("role", "FOLLOWER")
                    put("sessionId", 90 + index)
                    put("pid", 1_000 + index)
                    put("responseAddress", "client:$index")
                    put("responseAttempts", index + 1)
                    put("replyStatus", status)
                }
            }
        val result = fixture.compare(events = fixture.replicas() + (0 to (listOf(fixture.applied(0)) + updates)))

        assertTrue(result.succeeded, result.issues.toString())
        assertEquals(448L, result.position)
    }

    @Test
    fun `같은 위치에 모순된 업무 값이 있으면 정상 행의 앞뒤 순서와 관계없이 실패한다`() {
        val fixture = fixture()
        val bad = fixture.applied(1).change("/state/balances/0/available", 699_999_999L)
        val good = fixture.applied(1)

        for (observations in listOf(listOf(bad, good), listOf(good, bad))) {
            val result = fixture.compare(events = fixture.replicas() + (1 to observations))

            assertIssue(result, "MISMATCH", "R1.node1.state.balances[0].available expected=700000000, actual=699999999")
        }
    }

    @Test
    fun `같은 위치의 다른 요청 주문 사용자 관찰을 정상 행 뒤에 붙여도 숨길 수 없다`() {
        val fixture = fixture()

        for ((field, value) in listOf("requestId" to "R2", "orderId" to "O2", "userId" to "U2")) {
            val bad = fixture.applied(1).apply { put(field, value) }
            val expected = fixture.applied(1).path(field)
            val events = fixture.replicas() + (1 to listOf(fixture.applied(1), bad))
            val result = fixture.compare(events = events)

            assertIssue(result, "MISMATCH", "R1.node1.$field expected=$expected, actual=\"$value\"")
        }
    }

    @Test
    fun `한 단계의 성공 뒤 수집한 같은 위치의 모순도 다시 대조하면 실패한다`() {
        val fixture = fixture()
        val initial = fixture.replicas()
        assertTrue(fixture.compare(events = initial).succeeded)

        val late = fixture.applied(2).change("/state/balances/0/hold", 1L)
        val collected = initial + (2 to mergeFundingObservations(initial.getValue(2), listOf(late)))
        val result = fixture.compare(events = collected)

        assertIssue(result, "MISMATCH", "R1.node2.state.balances[0].hold expected=300000000, actual=1")
    }

    @Test
    fun `세 사본이 똑같이 잘못된 잔고를 가져도 독립 정답과 다르면 실패한다`() {
        val fixture = fixture(OrderReplicationScenario.BUY_SEQUENCE, 2)
        val events =
            (0..2).associateWith { member ->
                listOf(fixture.applied(member).change("/state/balances/0/available", 700_000_000L))
            }
        val result = fixture.compare(events = events)

        for (member in 0..2) {
            assertIssue(result, "MISMATCH", "R2.node$member.state.balances[0].available expected=600000000, actual=700000000")
        }
        assertEquals(3, result.issues.size)
    }

    @Test
    fun `잔고와 개수만 맞아도 예약의 모든 의미 필드가 틀리면 실패한다`() {
        val fixture = fixture()
        val root = "/state/pendingOrders/0/candidate/reservation"
        fixture.assertMutations(
            listOf(
                Mutation("$root/marketId", "ETH-USDT"),
                Mutation("$root/orderId", "O2"),
                Mutation("$root/userId", "U2"),
                Mutation("$root/side", "SELL"),
                Mutation("$root/assetId", "BTC"),
                Mutation("$root/limitPrice", 100_000_001L),
                Mutation("$root/initialQuantity", 299_999_999L),
                Mutation("$root/remainingQuantity", 299_999_999L),
                Mutation("$root/reservedAmount", 299_999_999L),
                Mutation("$root/remainingAmount", 299_999_999L),
                Mutation("$root/initialFeeReserveAmount", 1L),
                Mutation("$root/remainingFeeReserveAmount", 1L),
                Mutation("$root/status", "RELEASED"),
                Mutation("$root/feeRemainder", 1L),
                Mutation("$root/releasedAmount", 0L),
            ),
        )
    }

    @Test
    fun `실제 원장과 후보 및 최초 결과의 원장은 식별자 계정 방향 금액과 전체 시각을 확인한다`() {
        val fixture = fixture()
        val roots =
            listOf(
                "/state/transactions/0",
                "/state/transactions/1",
                "/state/pendingOrders/0/candidate/reserveTransaction",
                "/state/requests/0/result/record/candidate/reserveTransaction",
            )

        for (root in roots) {
            fixture.assertMutations(
                listOf(
                    Mutation("$root/transactionId", "L-OTHER"),
                    Mutation("$root/sourceEventId", "OTHER-EVENT"),
                    Mutation("$root/kind", "SETTLEMENT"),
                    Mutation("$root/occurredAt", "2026-10-21T00:00:01.123456788Z"),
                    Mutation("$root/occurredAtEpochSecond", 1_792_540_799L),
                    Mutation("$root/occurredAtNano", 123_456_788L),
                    Mutation("$root/postings/0/accountId", "USER:U2:USDT:AVAILABLE"),
                    Mutation("$root/postings/1/accountId", "USER:U2:USDT:HOLD"),
                    Mutation("$root/postings/0/assetId", "BTC"),
                    Mutation("$root/postings/1/assetId", "BTC"),
                    Mutation("$root/postings/0/side", "CREDIT"),
                    Mutation("$root/postings/1/side", "DEBIT"),
                    Mutation("$root/postings/0/amount", 1L),
                    Mutation("$root/postings/1/amount", 1L),
                ),
            )
            val changed = fixture.applied(2).apply { (at("$root/postings") as ArrayNode).remove(1) }
            val result = fixture.compare(events = fixture.replicas() + (2 to listOf(changed)))

            assertIssue(result, "MISMATCH", reportPath("R1.node2", "$root/postings"), "count expected=2, actual=")
        }
    }

    @Test
    fun `후보와 최초 요청에 보존한 주문 원본 마켓과 수수료 정책도 독립 정답과 대조한다`() {
        val fixture = fixture()
        val contextRoots =
            listOf(
                "/state/pendingOrders/0/candidate",
                "/state/requests/0",
                "/state/requests/0/result/record/candidate",
            )

        for (root in contextRoots) {
            fixture.assertMutations(orderMutations("$root/order"))
            fixture.assertMutations(marketMutations("$root/market"))
            fixture.assertMutations(policyMutations("$root/feePolicy"))
        }
        for (root in listOf("/state/pendingOrders/0/candidate", "/state/requests/0/result/record/candidate")) {
            fixture.assertMutations(policyMutations("$root/reservation/feePolicy"))
        }
    }

    @Test
    fun `준비 원본과 승인 순번은 현재 주문 응답이 맞아도 바꿀 수 없다`() {
        val fixture = fixture()
        fixture.assertMutations(
            listOf(
                Mutation("/state/balances/0/userId", "U2"),
                Mutation("/state/balances/0/assetId", "BTC"),
                Mutation("/state/balances/0/hold", 299_999_999L),
                Mutation("/state/preparationCount", 2L),
                Mutation("/state/pendingOrderCount", 2L),
                Mutation("/state/requestRecordCount", 2L),
                Mutation("/state/lastSequence", 2L),
                Mutation("/state/preparations/0/preparationId", "P2"),
                Mutation("/state/preparations/0/userId", "U2"),
                Mutation("/state/preparations/0/assetId", "BTC"),
                Mutation("/state/preparations/0/amount", 999_999_999L),
                Mutation("/state/preparations/0/ledgerTransactionId", "L-OTHER"),
                Mutation("/state/preparations/0/occurredAt", "2026-10-21T00:00:00.123456788Z"),
                Mutation("/state/pendingOrders/0/sequence", 2L),
                Mutation("/state/requests/0/requestId", "R2"),
                Mutation("/state/requests/0/ledgerTransactionId", "L-OTHER"),
                Mutation("/state/requests/0/occurredAt", "2026-10-21T00:00:01.123456788Z"),
                Mutation("/state/requests/0/occurredAtEpochSecond", 1_792_540_800L),
                Mutation("/state/requests/0/occurredAtNano", 123_456_788L),
                Mutation("/state/requests/0/result/outcome", "REJECTED"),
                Mutation("/state/requests/0/result/record/sequence", 2L),
                Mutation("/result/sequence", 2L),
            ),
        )
    }

    @Test
    fun `잔고와 개수가 같아도 실제 대기 순서를 O2 O1로 뒤집으면 실패한다`() {
        val fixture = fixture(OrderReplicationScenario.BUY_SEQUENCE, 2)
        val changed =
            fixture.applied(2).apply {
                val pending = at("/state/pendingOrders") as ArrayNode
                val first = pending.remove(0)
                pending.add(first)
            }
        val result = fixture.compare(events = fixture.replicas() + (2 to listOf(changed)))

        assertIssue(result, "MISMATCH", "R2.node2.state.pendingOrders[0].sequence expected=1, actual=2")
        assertIssue(result, "MISMATCH", "R2.node2.state.pendingOrders[0].candidate.order.orderId expected=\"O1\", actual=\"O2\"")
        assertIssue(result, "MISMATCH", "R2.node2.state.pendingOrders[1].sequence expected=2, actual=1")
    }

    @Test
    fun `둘째 매수 뒤 O1의 당시 후보를 현재 잔고로 덮어쓰면 최초 결과에서도 검출한다`() {
        val fixture = fixture(OrderReplicationScenario.BUY_SEQUENCE, 2)
        val roots = listOf("/state/pendingOrders/0/candidate", "/state/requests/0/result/record/candidate")

        for (root in roots) {
            fixture.assertMutations(
                listOf(
                    Mutation("$root/previousBalance/available", 600_000_000L),
                    Mutation("$root/previousBalance/hold", 400_000_000L),
                    Mutation("$root/nextBalance/available", 600_000_000L),
                    Mutation("$root/nextBalance/hold", 400_000_000L),
                ),
            )
        }
    }

    @Test
    fun `매도는 BTC 예약과 수수료 0을 확인하고 USDT 잔고 추가를 허용하지 않는다`() {
        val fixture = fixture(OrderReplicationScenario.SELL, 1)
        val root = "/state/pendingOrders/0/candidate/reservation"
        fixture.assertMutations(
            listOf(
                Mutation("/state/balances/0/available", 99_999_999L),
                Mutation("/state/balances/0/hold", 199_999_999L),
                Mutation("$root/assetId", "USDT"),
                Mutation("$root/initialQuantity", 199_999_999L),
                Mutation("$root/remainingQuantity", 199_999_999L),
                Mutation("$root/reservedAmount", 199_999_999L),
                Mutation("$root/remainingAmount", 199_999_999L),
                Mutation("$root/initialFeeReserveAmount", 1L),
                Mutation("$root/remainingFeeReserveAmount", 1L),
                Mutation("/state/transactions/1/postings/0/accountId", "USER:U1:USDT:AVAILABLE"),
                Mutation("/state/transactions/1/postings/1/accountId", "USER:U1:USDT:HOLD"),
            ),
        )
        val changed =
            fixture.applied(2).apply {
                (at("/state/balances") as ArrayNode).add(
                    tree(mapOf("userId" to "U1", "assetId" to "USDT", "available" to 0, "hold" to 0)),
                )
            }
        val result = fixture.compare(events = fixture.replicas() + (2 to listOf(changed)))

        assertIssue(result, "MISMATCH", "R1.node2.state.balances count expected=1, actual=")
    }

    @Test
    fun `부족 거절의 요청 이력을 없애면 돈과 대기 주문이 그대로여도 실패한다`() {
        val fixture = fixture(OrderReplicationScenario.INSUFFICIENT, 2)
        val changed = fixture.applied(2).apply { (at("/state/requests") as ArrayNode).remove(1) }
        val result = fixture.compare(events = fixture.replicas() + (2 to listOf(changed)))

        assertIssue(result, "MISMATCH", "R2.node2.state.requests count expected=2, actual=")
        assertIssue(result, "MISMATCH", "R2.node2.state.requests[1] expected=object")
    }

    @Test
    fun `부족 거절은 제출한 원본 문맥 시각과 거절 상세를 보존해야 한다`() {
        val fixture = fixture(OrderReplicationScenario.INSUFFICIENT, 2)
        val root = "/state/requests/1"
        fixture.assertMutations(orderMutations("$root/order"))
        fixture.assertMutations(marketMutations("$root/market"))
        fixture.assertMutations(policyMutations("$root/feePolicy"))
        fixture.assertMutations(
            listOf(
                Mutation("$root/requestId", "R1"),
                Mutation("$root/ledgerTransactionId", "L-RESERVE-1"),
                Mutation("$root/occurredAt", "2026-10-21T00:00:02.123456788Z"),
                Mutation("$root/occurredAtEpochSecond", 1_792_540_801L),
                Mutation("$root/occurredAtNano", 123_456_788L),
                Mutation("$root/result/outcome", "ACCEPTED"),
                Mutation("$root/result/reason", "DUPLICATE_ORDER"),
            ) + shortageMutations("$root/result/insufficientBalance"),
        )
    }

    @Test
    fun `부족 거절 응답도 식별자 사유와 부족 수치가 최초 결과에 대응해야 한다`() {
        val fixture = fixture(OrderReplicationScenario.INSUFFICIENT, 2)
        val mutations =
            listOf(
                Mutation("/result/userId", "U2"),
                Mutation("/result/requestId", "R1"),
                Mutation("/result/marketId", "ETH-USDT"),
                Mutation("/result/orderId", "O1"),
                Mutation("/result/outcome", "ACCEPTED"),
                Mutation("/result/reason", "DUPLICATE_ORDER"),
            ) + shortageMutations("/result/insufficientBalance")
        fixture.assertMutations(mutations)

        for (mutation in mutations) {
            val response = fixture.client().last()
            val expected = response.at(mutation.pointer).toString()
            response.change(mutation.pointer, mutation.value)
            val result = fixture.compare(client = fixture.client().take(1) + listOf(response))

            assertIssue(
                result,
                "MISMATCH",
                "${reportPath("R2.client", mutation.pointer)} expected=$expected, actual=${response.at(mutation.pointer)}",
            )
        }
    }

    @Test
    fun `부족 거절 뒤 O2 예약이나 실제 RESERVE를 추가하면 실패한다`() {
        val fixture = fixture(OrderReplicationScenario.INSUFFICIENT, 2)
        val acceptedSecond = this.fixture(OrderReplicationScenario.BUY_SEQUENCE, 2).applied(2)
        val withReservation =
            fixture.applied(2).apply {
                (at("/state/pendingOrders") as ArrayNode).add(acceptedSecond.at("/state/pendingOrders/1"))
                (at("/state") as ObjectNode).put("pendingOrderCount", 2)
                (at("/state") as ObjectNode).put("lastSequence", 2)
            }
        val withReserve =
            fixture.applied(2).apply {
                (at("/state/transactions") as ArrayNode).add(acceptedSecond.at("/state/transactions/2"))
            }
        val reservationResult = fixture.compare(events = fixture.replicas() + (2 to listOf(withReservation)))
        val reserveResult = fixture.compare(events = fixture.replicas() + (2 to listOf(withReserve)))

        assertIssue(reservationResult, "MISMATCH", "R2.node2.state.pendingOrders count expected=1, actual=")
        assertIssue(reservationResult, "MISMATCH", "R2.node2.state.lastSequence expected=1, actual=2")
        assertIssue(reserveResult, "MISMATCH", "R2.node2.state.transactions count expected=2, actual=")
    }

    @Test
    fun `부족 거절 뒤 잔고 O1 예약과 최초 결과는 첫 승인 값 그대로여야 한다`() {
        val fixture = fixture(OrderReplicationScenario.INSUFFICIENT, 2)
        fixture.assertMutations(
            listOf(
                Mutation("/state/balances/0/available", 600_000_000L),
                Mutation("/state/balances/0/hold", 400_000_000L),
                Mutation("/state/lastSequence", 2L),
                Mutation("/state/pendingOrders/0/sequence", 2L),
                Mutation("/state/pendingOrders/0/candidate/reservation/remainingAmount", 400_000_000L),
                Mutation("/state/requests/0/result/record/candidate/nextBalance/available", 600_000_000L),
                Mutation("/state/transactions/1/postings/0/amount", 400_000_000L),
            ),
        )
    }

    @Test
    fun `앞선 불일치는 부분 로그 수집 실패와 관찰 누락을 추가해도 보존한다`() {
        val fixture = fixture()
        val bad = fixture.applied(0).change("/state/balances/0/hold", 1L)
        val partial = tree(mapOf("kind" to "READY", "runId" to fixture.runId, "memberId" to 0))
        val history = mergeFundingObservations(listOf(bad), listOf(partial))
        val result =
            fixture.compare(
                events = mapOf(0 to history),
                collectionIssues = listOf(FundingVerificationIssue("COLLECTION", "node2 부분 로그 수집 실패")),
            )

        assertIssue(result, "MISMATCH", "R1.node0.state.balances[0].hold expected=300000000, actual=1")
        assertIssue(result, "COLLECTION", "node2 부분 로그 수집 실패")
        assertIssue(result, "UNVERIFIABLE", "node1 새 동일 위치 관찰 누락")
        assertIssue(result, "UNVERIFIABLE", "node2 새 동일 위치 관찰 누락")
    }

    @Test
    fun `응답과 전송 증거의 중복이나 명시적 미확인은 성공이 아니다`() {
        val fixture = fixture()
        val client = fixture.client()
        val cases =
            listOf(
                (client + listOf(client.first())) to "전송 수용 한 건 미확인: 2",
                (client + listOf(client.last())) to "응답 한 건 미확인: 2",
                (client + listOf(tree(mapOf("kind" to "UNCONFIRMED", "runId" to fixture.runId)))) to "클라이언트 결과 미확인",
            )

        for ((events, message) in cases) {
            assertIssue(fixture.compare(client = events), "UNVERIFIABLE", message)
        }
    }

    @Test
    fun `재전송이 최초 후보 원장 정책 시각이나 현재 돈과 이력을 바꾸면 실패한다`() {
        val replay = fixture(OrderReplicationScenario.ACCEPTED_REPLAY, 3)
        replay.assertMutations(
            listOf(
                Mutation("/state/balances/0/hold", 600_000_000L),
                Mutation("/state/pendingOrderCount", 2L),
                Mutation("/state/requestRecordCount", 2L),
                Mutation("/state/lastSequence", 2L),
                Mutation("/state/requests/0/order/price", 101_000_000L),
                Mutation("/state/requests/0/feePolicy/scheduleVersion", 2L),
                Mutation("/state/requests/0/ledgerTransactionId", "L-RETRY-3"),
                Mutation("/state/requests/0/occurredAtEpochSecond", 1_792_540_803L),
                Mutation("/state/requests/0/occurredAtNano", 123_456_788L),
                Mutation("/state/requests/0/result/record/candidate/nextBalance/hold", 600_000_000L),
                Mutation("/state/transactions/1/transactionId", "L-RETRY-3"),
            ),
        )
        val extra = replay.applied(2)
        (extra.at("/state/transactions") as ArrayNode).add(extra.at("/state/transactions/1").deepCopy())
        assertIssue(replay.compare(events = replay.replicas() + (2 to listOf(extra))), "MISMATCH", "transactions count expected=2")
    }

    @Test
    fun `새 요청의 주문 중복은 이력을 늘리고 내용 충돌과 정책 불일치는 늘리지 않는다`() {
        val cases =
            listOf(
                Triple(OrderReplicationScenario.ACCEPTED_REPLAY, 5, 1L),
                Triple(OrderReplicationScenario.ACCEPTED_REPLAY, 4, 2L),
                Triple(OrderReplicationScenario.CONTEXT_CORRECTION, 1, 1L),
            )
        for ((scenario, index, wrongCount) in cases) {
            fixture(scenario, index).assertMutations(listOf(Mutation("/state/requestRecordCount", wrongCount)))
        }
    }

    @Test
    fun `첫 부족 상세를 현재 돈이나 주문 중복으로 바꾸면 세 사본이 같아도 실패한다`() {
        for (index in listOf(2, 4)) {
            val replay = fixture(OrderReplicationScenario.REJECTED_REPLAY, index)
            replay.assertMutations(
                listOf(
                    Mutation("/result/insufficientBalance/available", 1_000_000_000L),
                    Mutation("/result/reason", "DUPLICATE_ORDER"),
                    Mutation("/state/requests/0/result/insufficientBalance/available", 1_000_000_000L),
                ),
            )
            val wrong =
                (0..2).associateWith { member ->
                    listOf(replay.applied(member).change("/result/reason", "DUPLICATE_ORDER"))
                }
            assertIssue(replay.compare(events = wrong), "MISMATCH", ".result.reason")
        }
    }

    @Test
    fun `나중 C2 승인이 있어도 C1 정책 불일치의 관찰을 대신하지 못한다`() {
        val mismatch = fixture(OrderReplicationScenario.CONTEXT_CORRECTION, 1)
        val corrected = fixture(OrderReplicationScenario.CONTEXT_CORRECTION, 2)

        val result = mismatch.compare(events = corrected.replicas(), pinnedPosition = mismatch.position)

        assertIssue(result, "UNVERIFIABLE", "node0 새 동일 위치 관찰 누락")
    }

    @Test
    fun `각 실행의 전송 단계 이름은 고유하고 같은 R1은 서로 다른 client 파일을 사용한다`() {
        for (scenario in OrderReplicationScenario.entries) {
            val steps = scenario.steps()
            assertEquals(steps.size, steps.map { it.name.lowercase() }.toSet().size)
        }
        val steps = OrderReplicationScenario.ACCEPTED_REPLAY.steps()
        assertEquals(listOf("R1", "R1", "R1", "R1"), steps.subList(1, 5).map { it.identity["requestId"] })
        assertEquals(listOf("A-1", "A-2", "A-3", "A-4"), steps.subList(1, 5).map { it.name })
        assertEquals(
            "ORDER",
            OrderReplicationScenario.REJECTED_REPLAY
                .steps()
                .first()
                .inputKind,
        )
    }

    private fun orderMutations(root: String): List<Mutation> =
        listOf(
            Mutation("$root/marketId", "ETH-USDT"),
            Mutation("$root/orderId", "OTHER-ORDER"),
            Mutation("$root/userId", "U2"),
            Mutation("$root/side", "SELL"),
            Mutation("$root/orderType", "MARKET"),
            Mutation("$root/timeInForce", "IOC"),
            Mutation("$root/price", 100_000_001L),
            Mutation("$root/quantity", 1L),
        )

    private fun marketMutations(root: String): List<Mutation> =
        listOf(
            Mutation("$root/marketId", "ETH-USDT"),
            Mutation("$root/baseAssetId", "ETH"),
            Mutation("$root/quoteAssetId", "KRW"),
            Mutation("$root/baseQuantityScale", 6L),
            Mutation("$root/priceTick", 2L),
            Mutation("$root/quantityStep", 200_000_000L),
        )

    private fun policyMutations(root: String): List<Mutation> =
        listOf(
            Mutation("$root/productType", "FUTURES"),
            Mutation("$root/feeTier", "VIP"),
            Mutation("$root/scheduleVersion", 2L),
            Mutation("$root/makerPartsPerMillion", 1L),
            Mutation("$root/takerPartsPerMillion", 1L),
        )

    private fun shortageMutations(root: String): List<Mutation> =
        listOf(
            Mutation("$root/assetId", "BTC"),
            Mutation("$root/available", 699_999_999L),
            Mutation("$root/required", 799_999_999L),
            Mutation("$root/shortfall", 99_999_999L),
        )

    private fun Fixture.assertMutations(mutations: List<Mutation>) {
        for (mutation in mutations) {
            val changed = applied(2)
            val expected = changed.at(mutation.pointer).toString()
            assertFalse(changed.at(mutation.pointer).isMissingNode, "변형 대상 누락: ${mutation.pointer}")
            changed.change(mutation.pointer, mutation.value)
            val result = compare(events = replicas() + (2 to listOf(changed)))

            assertIssue(
                result,
                "MISMATCH",
                "${reportPath("${step.name}.node2", mutation.pointer)} expected=$expected, actual=${changed.at(mutation.pointer)}",
            )
        }
    }

    private fun assertIssue(
        result: FundingReplicationComparison,
        category: String,
        vararg fragments: String,
    ) {
        assertFalse(result.succeeded, "실패 사례가 성공으로 판정됨: ${fragments.toList()}")
        assertTrue(
            result.issues.any { issue -> issue.category == category && fragments.all { issue.message.contains(it) } },
            "기대한 $category ${fragments.toList()}, 실제 ${result.issues}",
        )
    }

    private fun ObjectNode.change(
        pointer: String,
        value: Any,
    ): ObjectNode {
        val parent = at(pointer.substringBeforeLast('/')) as ObjectNode
        val field = pointer.substringAfterLast('/')
        when (value) {
            is String -> parent.put(field, value)
            is Long -> parent.put(field, value)
            else -> error("지원하지 않는 변형 값: $value")
        }
        return this
    }

    private fun reportPath(
        prefix: String,
        pointer: String,
    ): String =
        pointer.removePrefix("/").split('/').fold(prefix) { path, part ->
            if (part.toIntOrNull() == null) "$path.$part" else "$path[$part]"
        }

    private fun fixture(
        scenario: OrderReplicationScenario = OrderReplicationScenario.BUY_SEQUENCE,
        index: Int = 1,
    ): Fixture =
        Fixture(
            "run-${scenario.name.lowercase()}",
            scenario.steps()[index],
            128L + 320L * index,
            if (index == 0) 0 else 128L + 320L * (index - 1),
        )

    private data class Mutation(
        val pointer: String,
        val value: Any,
    )

    private inner class Fixture(
        val runId: String,
        val step: OrderReplicationStep,
        val position: Long,
        val previousPosition: Long,
    ) {
        // 비교기 자체의 JSON 반례다. 도메인 계산기·State·관찰 직렬화기는 호출하지 않는다.
        // 실제 세 JVM의 출력과 독립 정답 대조는 DockerOrderReplicationTest의 별도 책임이다.
        fun applied(member: Int): ObjectNode =
            tree(
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

        fun replicas(): Map<Int, List<JsonNode>> = (0..2).associateWith { listOf(applied(it)) }

        fun client(): List<ObjectNode> =
            listOf(
                tree(mapOf("kind" to "TRANSPORT_ACCEPTED", "runId" to runId) + step.identity),
                tree(
                    mapOf(
                        "kind" to "RESULT_RECEIVED",
                        "runId" to runId,
                        "inputKind" to step.inputKind,
                        "result" to step.expectedResult,
                    ) + step.identity,
                ),
            )

        fun compare(
            events: Map<Int, List<JsonNode>> = replicas(),
            client: List<JsonNode> = client(),
            previousPosition: Long = this.previousPosition,
            collectionIssues: List<FundingVerificationIssue> = emptyList(),
            pinnedPosition: Long? = null,
        ): FundingReplicationComparison =
            compareOrderReplication(runId, step, events, client, previousPosition, collectionIssues, pinnedPosition)
    }

    private fun tree(value: Any): ObjectNode = jacksonObjectMapper().valueToTree(value)
}
