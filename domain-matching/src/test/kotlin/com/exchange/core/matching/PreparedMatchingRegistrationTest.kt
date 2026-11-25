package com.exchange.core.matching

import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedMatchingRegistrationTest : MatchingResultTestFixture() {
    @Test
    fun `결과 준비는 상태를 바꾸지 않고 입력 목록과 분리된 값을 등록한다`() {
        val state = prepared()
        val before = state.snapshot()
        val input = buyEvents().toMutableList()

        val registration = state.prepareRegistration("C3", buy(), 3, 3, 5, input)
        input.clear()

        assertEquals(before, state.snapshot())
        assertNull(state.find(market, "C3"))
        val result = state.register(registration)
        assertEquals(buyEvents(), result.events)
        assertSame(result, state.find(market, "C3"))
        assertFailsWith<UnsupportedOperationException> { (result.events as MutableList<MatchingEvent>).clear() }
        assertEquals(5L, state.lastEngineSequence(market))
    }

    @Test
    fun `다른 상태 객체에서 준비한 결과는 같은 순번이어도 등록하지 않는다`() {
        val owner = prepared()
        val other = prepared()
        val ownerBefore = owner.snapshot()
        val otherBefore = other.snapshot()
        val registration = owner.prepareRegistration("C3", buy(), 3, 3, 5, buyEvents())

        assertFailsWith<IllegalArgumentException> { other.register(registration) }

        assertEquals(ownerBefore, owner.snapshot())
        assertEquals(otherBefore, other.snapshot())
        assertFalse(other.isBlocked)
        assertEquals(buyEvents(), owner.register(registration).events)
    }

    @Test
    fun `준비 뒤 같은 마켓의 다른 결과가 등록되면 오래된 준비는 쓰기 전에 거절한다`() {
        val state = prepared()
        val registration = state.prepareRegistration("C3", buy(), 3, 3, 5, buyEvents())
        val cancellation = CancelOrderCommand(market, OrderId("S1"), UserId("U-S1"))
        state.register(
            "cancel",
            cancellation,
            1,
            3,
            3,
            listOf(OrderCancelled(market, 3, cancellation.orderId, cancellation.userId, Quantity(1))),
        )

        assertRejectedWithoutChanges(state) { state.register(registration) }
        assertNull(state.find(market, "C3"))
        assertEquals(3L, state.lastEngineSequence(market))
    }

    @Test
    fun `다른 마켓의 진행은 준비한 마켓의 결과 등록을 무효화하지 않는다`() {
        val state = prepared()
        val registration = state.prepareRegistration("C3", buy(), 3, 3, 5, buyEvents())
        val other = buy().copy(marketId = otherMarket)
        val event = entered(1, "B1", "U-B1", Side.BUY, 100, 3).copy(marketId = otherMarket)
        val otherResult = state.register("C3", other, 1, 1, 1, listOf(event))

        val result = state.register(registration)

        assertEquals(buyEvents(), result.events)
        assertSame(otherResult, state.find(otherMarket, "C3"))
        assertEquals(mapOf(market to 5L, otherMarket to 1L), state.snapshot().lastEngineSequences)
    }

    @Test
    fun `같은 준비의 재등록은 후속 결과 뒤에도 최초 객체를 반환한다`() {
        val state = prepared()
        val registration = state.prepareRegistration("C3", buy(), 3, 3, 5, buyEvents())
        val original = state.register(registration)
        val cancellation = CancelOrderCommand(market, OrderId("B1"), UserId("U-B1"))
        state.register(
            "C4",
            cancellation,
            1,
            6,
            6,
            listOf(OrderCancelled(market, 6, cancellation.orderId, cancellation.userId, Quantity(1))),
        )
        val before = state.snapshot()

        assertSame(original, state.register(registration))
        assertSame(original, state.register(state.prepareRegistration("C3", buy(), 3, 3, 5, buyEvents())))
        assertEquals(before, state.snapshot())
    }

    @Test
    fun `동일 키로 따로 준비한 동일 내용은 최초 객체를 반환하고 다른 내용은 충돌이다`() {
        val state = prepared()
        val first = state.prepareRegistration("C3", buy(), 3, 3, 5, buyEvents())
        val same = state.prepareRegistration("C3", buy().copy(), 3, 3, 5, buyEvents().toMutableList())
        val changedEvents = listOf(firstTrade().copy(makerUserId = UserId("another-maker")), secondTrade(), remainder())
        val changed = state.prepareRegistration("C3", buy(), 3, 3, 5, changedEvents)
        val original = state.register(first)

        assertSame(original, state.register(same))
        assertRejectedWithoutChanges(state, MatchingResultException.Reason.CONTENT_CONFLICT) { state.register(changed) }
        assertSame(original, state.find(market, "C3"))
    }

    @Test
    fun `준비한 결과도 실제 쓰기 중 오류가 나면 같은 준비의 재전송까지 차단한다`() {
        val state = prepared()
        val registration = state.prepareRegistration("C3", buy(), 3, 3, 5, buyEvents())
        val failure = IllegalStateException("결과 쓰기 직후 실패")
        val recordsField = MatchingResultState::class.java.getDeclaredField("records").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val records = recordsField.get(state) as MutableMap<Any, MatchingCommandResult>
        var written = false
        recordsField.set(
            state,
            object : MutableMap<Any, MatchingCommandResult> by records {
                override fun put(
                    key: Any,
                    value: MatchingCommandResult,
                ): MatchingCommandResult? {
                    records[key] = value
                    written = true
                    throw failure
                }
            },
        )

        assertSame(failure, assertFailsWith<IllegalStateException> { state.register(registration) })
        assertTrue(written)
        assertTrue(state.isBlocked)
        assertFailsWith<IllegalStateException> { state.register(registration) }
        assertFailsWith<IllegalStateException> { state.prepareRegistration("C3", buy(), 3, 3, 5, buyEvents()) }
        assertFailsWith<IllegalStateException> { state.find(market, "C3") }
    }
}
