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
    fun `세 독립 사례의 준비와 모든 주문 단계를 새 동일 위치에서 확인한다`() {
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
            listOf(128L, 448L, 928L)[index],
            if (index == 0) 0 else listOf(128L, 448L, 928L)[index - 1],
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
        ): FundingReplicationComparison = compareOrderReplication(runId, step, events, client, previousPosition, collectionIssues)
    }

    private fun tree(value: Any): ObjectNode = jacksonObjectMapper().valueToTree(value)
}
