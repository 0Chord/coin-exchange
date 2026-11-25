package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class MatchingRegistrationValidationTest : MatchingResultTestFixture() {
    @Test
    fun `사전 검증은 전체 결과를 반환하고 등록 이력과 다음 순번을 소비하지 않는다`() {
        val state = prepared()
        val before = state.snapshot()

        val candidate = state.validateRegistration("C3", buy(), 3, 3, 5, buyEvents())

        assertEquals("C3", candidate.commandId)
        assertEquals(buy(), candidate.command)
        assertEquals(3, candidate.expectedEventCount)
        assertEquals(3L, candidate.firstEngineSequence)
        assertEquals(5L, candidate.lastEngineSequence)
        assertEquals(buyEvents(), candidate.events)
        assertEquals(before, state.snapshot())
        assertNull(state.find(market, "C3"))
        assertEquals(2L, state.lastEngineSequence(market))
        assertEquals(0L, state.lastEngineSequence(otherMarket))
        assertFalse(state.isBlocked)

        assertEquals(candidate, registerBuy(state))
        assertEquals(5L, state.lastEngineSequence(market))
    }

    @Test
    fun `사전 검증도 과거 동일 결과를 다음 순번보다 먼저 재사용하고 내용 충돌을 거절한다`() {
        val state = prepared()
        val original = registerBuy(state)
        val before = state.snapshot()

        assertSame(original, state.validateRegistration("C3", buy(), 3, 3, 5, buyEvents()))
        assertRejectedWithoutChanges(state, MatchingResultException.Reason.CONTENT_CONFLICT) {
            state.validateRegistration("C3", buy(), 3, 3, 5, listOf(firstTrade().copy(price = Price(89)), secondTrade(), remainder()))
        }
        assertEquals(before, state.snapshot())
    }

    @Test
    fun `새 결과의 명령 ID 전체 이벤트 다음 순번과 중복 제출을 쓰기 없이 모두 검사한다`() {
        val invalidBatches =
            listOf(
                Batch(2, 3, 5, buyEvents()),
                Batch(3, 3, 5, buyEvents().dropLast(1)),
                Batch(3, 3, 5, listOf(firstTrade(), secondTrade().copy(engineSequence = 3), remainder())),
                Batch(3, 3, 5, listOf(firstTrade().copy(marketId = otherMarket), secondTrade(), remainder())),
                Batch(3, 3, 5, listOf(firstTrade().copy(takerOrderId = OrderId("other")), secondTrade(), remainder())),
                Batch(3, 3, 5, listOf(firstTrade(), secondTrade(), remainder().copy(remainingQuantity = Quantity(2)))),
                Batch(1, 4, 4, listOf(entered(4, "B1", "U-B1", Side.BUY, 100, 3))),
            )
        invalidBatches.forEach { batch ->
            val state = prepared()
            assertRejectedWithoutChanges(state) {
                state.validateRegistration("C3", buy(), batch.count, batch.first, batch.last, batch.events)
            }
            assertEquals(buyEvents(), registerBuy(state).events)
        }
        val state = prepared()
        assertRejectedWithoutChanges(state) { state.validateRegistration(" ", buy(), 3, 3, 5, buyEvents()) }
        registerBuy(state)
        assertRejectedWithoutChanges(state, MatchingResultException.Reason.DUPLICATE_SUBMIT) {
            state.validateRegistration("another", buy(), 1, 6, 6, listOf(entered(6, "B1", "U-B1", Side.BUY, 100, 3)))
        }
    }

    @Test
    fun `취소 사전 검증은 성공과 거절을 등록하지 않고 제출 이벤트를 거절한다`() {
        val state = prepared()
        val command = CancelOrderCommand(market, OrderId("S1"), UserId("U-S1"))
        val cancelled = OrderCancelled(market, 3, command.orderId, command.userId, Quantity(1))
        val rejected = OrderCancelRejected(market, 3, command.orderId, command.userId, "not found")
        val before = state.snapshot()

        assertEquals(listOf(cancelled), state.validateRegistration("cancel", command, 1, 3, 3, listOf(cancelled)).events)
        assertEquals(listOf(rejected), state.validateRegistration("cancel", command, 1, 3, 3, listOf(rejected)).events)
        assertRejectedWithoutChanges(state) { state.validateRegistration("cancel", command, 1, 3, 3, listOf(firstTrade())) }
        assertEquals(before, state.snapshot())
    }

    @Test
    fun `차단된 결과 상태는 과거 사전 검증과 마지막 순번 조회도 차단한다`() {
        val state = prepared()
        MatchingResultState::class.java
            .getDeclaredField("isBlocked")
            .apply { isAccessible = true }
            .setBoolean(state, true)

        assertFailsWith<IllegalStateException> {
            state.validateRegistration(
                "C1",
                submit("S1", "U-S1", Side.SELL, 90, 1),
                1,
                1,
                1,
                listOf(entered(1, "S1", "U-S1", Side.SELL, 90, 1)),
            )
        }
        assertFailsWith<IllegalStateException> { state.lastEngineSequence(market) }
        assertFailsWith<IllegalStateException> { state.lastEngineSequence(otherMarket) }
    }
}
