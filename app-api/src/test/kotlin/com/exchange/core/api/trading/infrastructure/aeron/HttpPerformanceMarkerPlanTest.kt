package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 순수 관측 fixture다. Aeron/driver/도메인 대량 부하를 시작하지 않는다. */
class HttpPerformanceMarkerPlanTest {
    @Test
    fun `고정 업무의 run 계획에서 준비 예열 측정 전체 identity 한도를 계산한다`() {
        assertEquals(6, HttpPerformanceMarkerPlan("marker-test", 1, 2).identitiesPerMember)
        assertEquals(4400, HttpPerformanceMarkerPlan("marker-test", 200, 2000).identitiesPerMember)
        assertEquals(204_000, HttpPerformanceMarkerPlan("marker-test", 12_000, 90_000).identitiesPerMember)
        val sixtyThousand = HttpPerformanceMarkerPlan("marker-test", 200, 60_000)
        assertEquals(120_400, sixtyThousand.identitiesPerMember)
        assertEquals(361_200, sixtyThousand.fields()["identityCapacityAllMembers"])
        assertEquals(8192, sixtyThousand.fields()["senderPendingCapacity"])
        // 상한 계산만 검증한다. 이 크기에서 실제 노드 heap/처리량을 입증하지 않는다.
        assertEquals(1_200_400, HttpPerformanceMarkerPlan("marker-test", 200, 600_000).identitiesPerMember)
        val maximum = HttpPerformanceMarkerPlan("marker-test", 12_000, 3_000_000)
        assertEquals(6_024_000, maximum.identitiesPerMember)
        assertEquals(18_072_000, maximum.fields()["identityCapacityAllMembers"])
    }

    @Test
    fun `계획은 무제한 총량 잘못된 run과 범위 밖 W N을 거절한다`() {
        listOf(0, -1, 12_001, Int.MAX_VALUE).forEach { warmup ->
            assertFailsWith<IllegalArgumentException> { HttpPerformanceMarkerPlan("marker-test", warmup, 2) }
        }
        listOf(0, -1, 3_000_001, Int.MAX_VALUE).forEach { orders ->
            assertFailsWith<IllegalArgumentException> { HttpPerformanceMarkerPlan("marker-test", 1, orders) }
        }
        listOf("", "UPPER", "x".repeat(65)).forEach { run ->
            assertFailsWith<IllegalArgumentException> { HttpPerformanceMarkerPlan(run, 1, 2) }
        }
    }

    @Test
    fun `노드 환경 계획은 두 값이 모두 있어야 하며 부모 run으로 귀속한다`() {
        val environment = mapOf("PERFORMANCE_MARKER_PLAN_WARMUP" to "200", "PERFORMANCE_MARKER_PLAN_ORDERS" to "60000")
        assertEquals(
            HttpPerformanceMarkerPlan("marker-test", 200, 60_000),
            httpPerformanceMarkerPlanFromEnvironment("marker-test", environment::get),
        )
        environment.keys.forEach { missing ->
            assertFailsWith<IllegalArgumentException> {
                httpPerformanceMarkerPlanFromEnvironment("marker-test", (environment - missing)::get)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            httpPerformanceMarkerPlanFromEnvironment("marker-test") { if (it.endsWith("WARMUP")) "200" else "overflow" }
        }
    }

    @Test
    fun `송신중 한 건도 순간 한도에 포함하고 overflow 원문은 누적 증거에 남긴다`() {
        val outbox = HttpPerformanceMarkerOutbox(1, HttpPerformanceMarkerPlan("marker-test", 1, 2))
        val first = marker(1)
        val overflow = marker(2).copy(logPosition = 991, tradeCount = 2, settlementCount = 3)
        outbox.offer(first, -10)
        val sending = assertNotNull(outbox.poll())
        assertEquals(0, outbox.summary()["queueSize"])
        assertEquals(1, outbox.summary()["pendingCount"])
        outbox.offer(overflow, -9)
        assertEquals(1L, outbox.summary()["queueOverflow"])
        assertEquals(
            listOf(mapOf("marker" to first, "status" to "SENDING"), mapOf("marker" to overflow, "status" to "QUEUE_OVERFLOW")),
            outbox.summary()["undeliveredMarkers"],
        )

        outbox.finish(sending, true)
        outbox.offer(marker(3), -8)
        outbox.finish(assertNotNull(outbox.poll()), true)
        assertEquals(listOf(first, marker(3)), outbox.summary()["sentMarkers"])
        assertEquals(
            listOf(mapOf("marker" to overflow, "status" to "QUEUE_OVERFLOW")),
            outbox.summary()["undeliveredMarkers"],
        )
        assertEquals(0, outbox.summary()["pendingCount"])
        assertFalse(outbox.isDrained)
    }

    @Test
    fun `작은 순간 큐를 반복 배출해도 8192번째 이전의 모든 송신 원문을 계획량까지 보존한다`() {
        val plan = HttpPerformanceMarkerPlan("marker-test", 1, 4096)
        val outbox = HttpPerformanceMarkerOutbox(1, plan)
        val expected = (1..plan.identitiesPerMember).map { marker(it.toLong()) }
        expected.forEach { value ->
            outbox.offer(value, null)
            outbox.finish(assertNotNull(outbox.poll()), true)
        }
        assertEquals(8194, outbox.summary()["historyCount"])
        assertEquals(expected, outbox.summary()["sentMarkers"])
        assertEquals(emptyList<Any>(), outbox.summary()["undeliveredMarkers"])
        assertEquals(0L, outbox.summary()["queueOverflow"])
        assertEquals(0L, outbox.summary()["historyOverflow"])
        assertTrue(outbox.isDrained)
    }

    @Test
    fun `송신 누적 계획 초과는 기존 증거를 지우지 않고 첫 초과 원문과 실패를 남긴다`() {
        val outbox = HttpPerformanceMarkerOutbox(1, HttpPerformanceMarkerPlan("marker-test", 1, 1))
        val expected = (1L..4).map { marker(it) }
        expected.forEach {
            outbox.offer(it, null)
            outbox.finish(assertNotNull(outbox.poll()), true)
        }
        outbox.offer(marker(5), null)
        assertEquals(expected, outbox.summary()["sentMarkers"])
        assertEquals(4, outbox.summary()["historyCount"])
        assertEquals(1L, outbox.summary()["historyOverflow"])
        assertEquals(marker(5), outbox.summary()["firstHistoryOverflow"])
        assertEquals(5L, outbox.summary()["offered"])
        assertTrue(outbox.capacityFailures().isNotEmpty())
        assertFalse(outbox.isDrained)
    }

    @Test
    fun `실패한 송신은 원문과 상태를 남기고 다음 건의 순간 슬롯만 해제한다`() {
        val outbox = HttpPerformanceMarkerOutbox(1, HttpPerformanceMarkerPlan("marker-test", 1, 1))
        outbox.offer(marker(1), null)
        val first = assertNotNull(outbox.poll())
        outbox.finish(first, false)
        assertEquals(
            listOf(mapOf("marker" to marker(1), "status" to "SEND_FAILED")),
            outbox.summary()["undeliveredMarkers"],
        )
        assertEquals(0, outbox.summary()["pendingCount"])
        assertEquals(0, outbox.summary()["sentCount"])
        assertFailsWith<IllegalStateException> { outbox.finish(first, true) }
        outbox.offer(marker(2), null)
        outbox.finish(assertNotNull(outbox.poll()), true)
        assertFalse(outbox.isDrained)
        assertEquals(listOf(marker(2)), outbox.summary()["sentMarkers"])
    }

    @Test
    fun `잘못된 순간 큐 크기와 다른 run의 송신은 시작 전에 거절한다`() {
        listOf(0, 3, 8193, Int.MAX_VALUE).forEach { capacity ->
            assertFailsWith<IllegalArgumentException> { HttpPerformanceMarkerOutbox(capacity) }
        }
        val outbox = HttpPerformanceMarkerOutbox(1, HttpPerformanceMarkerPlan("marker-test", 1, 1))
        assertFailsWith<IllegalArgumentException> { outbox.offer(marker(1).copy(runId = "another-run"), null) }
        assertEquals(0, outbox.summary()["historyCount"])
    }

    @Test
    fun `수신기는 세 멤버 각각 8192 이후의 모든 payload를 계획 한도까지 정확히 보존한다`() {
        val plan = HttpPerformanceMarkerPlan("marker-test", 1, 4096)
        val received = HttpPerformanceReceivedMarkerHistory(plan.runId, plan.identitiesPerMember)
        val expected =
            (0..2).flatMap { member -> (1..plan.identitiesPerMember).map { marker(it.toLong(), member) } }
        expected.forEach { assertTrue(received.observe(it)) }
        assertEquals(expected, received.markers())
        (0..2).forEach { assertFalse(received.observe(marker(1, it))) }
        assertEquals(3L, received.duplicateCount())
        assertEquals(expected, received.markers())
    }

    @Test
    fun `수신 전체비교는 한도에 도달한 뒤에도 중복을 허용하고 충돌과 초과를 거절한다`() {
        val received = HttpPerformanceReceivedMarkerHistory("marker-test", 4)
        val expected = (1L..4).map { marker(it) }
        expected.forEach { assertTrue(received.observe(it)) }
        assertFalse(received.observe(marker(1)))
        listOf(
            marker(1).copy(userId = "OTHER"),
            marker(1).copy(requestId = "OTHER"),
            marker(1).copy(orderId = "OTHER"),
            marker(1).copy(role = "FOLLOWER"),
            marker(1).copy(logPosition = 123),
            marker(1).copy(tradeCount = 2),
            marker(1).copy(settlementCount = 2),
            marker(5),
            marker(1).copy(runId = "another-run"),
            marker(1).copy(memberId = 3),
        ).forEach { conflict ->
            assertFailsWith<IllegalArgumentException> { received.observe(conflict) }
            assertEquals(expected, received.markers())
        }
        assertEquals(1L, received.duplicateCount())
    }

    @Test
    fun `collector 계획량은 정확한 과거 접수 식별자를 계속 비교하고 새 초과만 무효로 한다`() {
        val plan = HttpPerformanceMarkerPlan("marker-test", 1, 4096)
        val collector = newCollector(plan)
        (1..plan.identitiesPerMember).forEach { collector.observe(admission(it.toLong())) }
        collector.observe(admission(1))
        assertNull(collector.error)
        collector.observe(admission(plan.identitiesPerMember.toLong() + 1))
        assertTrue(assertNotNull(collector.error).contains("누적 식별 계획 초과"))

        val conflicting = newCollector(plan)
        (1..plan.identitiesPerMember).forEach { conflicting.observe(admission(it.toLong())) }
        conflicting.observe(admission(1, market = "OTHER-MARKET"))
        assertTrue(assertNotNull(conflicting.error).contains("식별 충돌"))
    }

    @Test
    fun `collector는 대량 관찰 뒤에도 실제 완료와 중복 판정을 보존하고 금융 이력은 바꾸지 않는다`() {
        val now = Instant.parse("2026-10-27T00:00:00Z")
        val market = DevelopmentMarketContext.BTC_USDT.market
        val state = tradingState()
        state.prepareDevelopmentBalance(
            OpeningBalance("P-BUYER-USDT", UserId("BUYER"), AssetId("USDT"), Amount(10_020)),
            "OPEN",
            now,
        )
        val result =
            assertIs<OrderAdmissionState.Result.Accepted>(
                state.admit(
                    "M-1",
                    OrderAdmissionInput(
                        market.marketId,
                        OrderId("M-1"),
                        UserId("BUYER"),
                        Side.BUY,
                        OrderType.LIMIT,
                        TimeInForce.GTC,
                        Price(10_000),
                        Quantity(100_000_000),
                    ),
                    market,
                    TradingFeePolicySnapshot(
                        FeeProductType.SPOT,
                        FeeTier.NORMAL,
                        1,
                        MakerTakerFeeRates(FeeRate(1000), FeeRate(2000)),
                    ),
                    "RESERVE-M-1",
                    now,
                ),
            )
        val completion = state.executePending(result.record.sequence, now)
        val before = state.snapshot()
        val markers = mutableListOf<HttpPerformanceCompletion>()
        val plan = HttpPerformanceMarkerPlan("marker-test", 1, 4096)
        val collector = newCollector(plan) { markers.add(it) }
        (1..plan.identitiesPerMember).forEach { collector.observe(admission(it.toLong())) }
        val execution =
            FundingObservation(256, null, null, executionSequence = 1, appliedRole = "LEADER", executionCompletion = completion)
        collector.observe(execution)
        collector.observe(execution)
        assertNull(collector.error)
        assertEquals(listOf(marker(1).copy(logPosition = 256, tradeCount = 0, settlementCount = 0)), markers)
        assertEquals(before, state.snapshot())
        assertEquals(1, before.completions.size)
        assertFalse(state.isBlocked)
    }

    @Test
    fun `collector는 다른 run의 계획을 거절하고 계획 미지정 기존 한도는 유지한다`() {
        assertFailsWith<IllegalArgumentException> {
            HttpPerformanceCollector("another-run", 0, {}, { 0L }, 1, false, HttpPerformanceMarkerPlan("marker-test", 1, 2))
        }
        val legacy = HttpPerformanceCollector("marker-test", 0) {}
        (1..8192).forEach { legacy.observe(admission(it.toLong())) }
        assertNull(legacy.error)
        legacy.observe(admission(8193))
        assertNotNull(legacy.error)
    }

    private fun newCollector(
        plan: HttpPerformanceMarkerPlan,
        send: (HttpPerformanceCompletion) -> Unit = {},
    ) = HttpPerformanceCollector(plan.runId, 0, send, { 0L }, 1, false, plan)

    private fun admission(
        sequence: Long,
        market: String = "BTC-USDT",
    ) = FundingObservation(
        128,
        null,
        null,
        admissionResult = AdmissionResultPayload.Accepted("BUYER", "M-$sequence", market, "M-$sequence", sequence),
        appliedRole = "LEADER",
    )

    private fun marker(
        sequence: Long,
        member: Int = 0,
    ) = HttpPerformanceCompletion(
        "marker-test",
        member,
        if (member == 0) "LEADER" else "FOLLOWER",
        "BUYER",
        "M-$sequence",
        "M-$sequence",
        sequence,
        128L * sequence,
        1,
        1,
    )
}
