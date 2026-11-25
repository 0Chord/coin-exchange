package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MatchingResultCancellationTest : MatchingResultTestFixture() {
    @Test
    fun `같은 대상을 여러 내부 명령으로 취소 성공과 거절 결과에 남길 수 있다`() {
        val state = prepared()
        val command = CancelOrderCommand(market, OrderId("S1"), UserId("U-S1"))
        val cancelled = OrderCancelled(market, 3, OrderId("S1"), UserId("U-S1"), Quantity(1))
        val rejected = OrderCancelRejected(market, 4, OrderId("S1"), UserId("U-S1"), "order not found")

        val success = state.register("CC1", command, 1, 3, 3, listOf(cancelled))
        val rejection = state.register("CC2", command, 1, 4, 4, listOf(rejected))
        val another = state.register("CC3", command, 1, 5, 5, listOf(rejected.copy(engineSequence = 5)))

        assertEquals(listOf(cancelled), success.events)
        assertEquals(listOf(rejected), rejection.events)
        assertEquals(listOf(rejected.copy(engineSequence = 5)), another.events)
        assertSame(success, state.register("CC1", command, 1, 3, 3, listOf(cancelled)))
        assertEquals(listOf("C1", "C2", "CC1", "CC2", "CC3"), state.snapshot().results.map { it.commandId })
        assertEquals(mapOf(market to 5L), state.snapshot().lastEngineSequences)
    }

    @Test
    fun `취소 결과의 대상 요청자 마켓 양수 잔량과 비어 있지 않은 사유를 검사한다`() {
        val command = CancelOrderCommand(market, OrderId("S1"), UserId("U-S1"))
        val cancelled = OrderCancelled(market, 3, OrderId("S1"), UserId("U-S1"), Quantity(1))
        val rejected = OrderCancelRejected(market, 3, OrderId("S1"), UserId("U-S1"), "order not found")
        val invalidEvents =
            listOf(
                cancelled.copy(marketId = otherMarket),
                cancelled.copy(orderId = OrderId("different")),
                cancelled.copy(userId = UserId("different")),
                cancelled.copy(remainingQuantity = Quantity.ZERO),
                rejected.copy(marketId = otherMarket),
                rejected.copy(orderId = OrderId("different")),
                rejected.copy(userId = UserId("different")),
                rejected.copy(reason = ""),
            )

        invalidEvents.forEach { event ->
            val state = prepared()
            assertRejectedWithoutChanges(state) { state.register("CC1", command, 1, 3, 3, listOf(event)) }
            assertNull(state.find(market, "CC1"))
            assertEquals(listOf(cancelled), state.register("CC1", command, 1, 3, 3, listOf(cancelled)).events)
        }
    }

    @Test
    fun `취소는 성공 또는 거절 하나만 허용하고 체결과 잔량 대기를 거절한다`() {
        val command = CancelOrderCommand(market, OrderId("S1"), UserId("U-S1"))
        val cancelled = OrderCancelled(market, 3, OrderId("S1"), UserId("U-S1"), Quantity(1))
        val rejected = OrderCancelRejected(market, 4, OrderId("S1"), UserId("U-S1"), "order not found")
        val invalidBatches =
            listOf(
                Batch(2, 3, 4, listOf(cancelled, rejected)),
                Batch(2, 3, 4, listOf(cancelled, cancelled.copy(engineSequence = 4))),
                Batch(2, 3, 4, listOf(cancelled, secondTrade())),
                Batch(1, 3, 3, listOf(firstTrade())),
                Batch(1, 3, 3, listOf(entered(3, "S1", "U-S1", Side.SELL, 90, 1))),
            )

        invalidBatches.forEach { batch ->
            val state = prepared()
            assertRejectedWithoutChanges(state) {
                state.register("CC1", command, batch.count, batch.first, batch.last, batch.events)
            }
            assertNull(state.find(market, "CC1"))
            assertEquals(listOf(cancelled), state.register("CC1", command, 1, 3, 3, listOf(cancelled)).events)
        }
    }

    @Test
    fun `같은 취소 키의 거절 사유나 성공 여부를 바꾸면 기존 결과와 충돌한다`() {
        val command = CancelOrderCommand(market, OrderId("S1"), UserId("U-S1"))
        val rejected = OrderCancelRejected(market, 3, OrderId("S1"), UserId("U-S1"), "order not found")
        val changed =
            listOf(
                rejected.copy(reason = "order owner mismatch"),
                OrderCancelled(market, 3, OrderId("S1"), UserId("U-S1"), Quantity(1)),
            )

        changed.forEach { event ->
            val state = prepared()
            val original = state.register("CC1", command, 1, 3, 3, listOf(rejected))

            assertRejectedWithoutChanges(state, MatchingResultException.Reason.CONTENT_CONFLICT) {
                state.register("CC1", command, 1, 3, 3, listOf(event))
            }
            assertSame(original, state.find(market, "CC1"))
            assertSame(original, state.register("CC1", command, 1, 3, 3, listOf(rejected)))
        }
    }
}
