package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MatchingResultFailureTest : MatchingResultTestFixture() {
    @Test
    fun `원수량이 Long 최댓값인 정상 잔량은 overflow로 오인하지 않는다`() {
        val state = MatchingResultState()
        val command = submit("B1", "U-B1", Side.BUY, 100, Long.MAX_VALUE)
        val event = entered(1, "B1", "U-B1", Side.BUY, 100, Long.MAX_VALUE)

        val result = state.register("C1", command, 1, 1, 1, listOf(event))

        assertEquals(listOf(event), result.events)
        assertEquals(command, result.command)
        assertEquals(mapOf(market to 1L), state.snapshot().lastEngineSequences)
    }

    @Test
    fun `체결 수량 합이 음수나 원수량으로 감기는 overflow를 등록 전에 거절한다`() {
        val cases =
            listOf(
                Pair(
                    submit("B1", "U-B1", Side.BUY, 100, Long.MAX_VALUE),
                    Batch(
                        2,
                        1,
                        2,
                        listOf(
                            trade(1, "S1", "B1", "U-S1", "U-B1", Side.BUY, 90, Long.MAX_VALUE),
                            trade(2, "S2", "B1", "U-S2", "U-B1", Side.BUY, 95, 1),
                        ),
                    ),
                ),
                Pair(
                    submit("B1", "U-B1", Side.BUY, 100, 1),
                    Batch(
                        3,
                        1,
                        3,
                        listOf(
                            trade(1, "S1", "B1", "U-S1", "U-B1", Side.BUY, 90, Long.MAX_VALUE),
                            trade(2, "S2", "B1", "U-S2", "U-B1", Side.BUY, 95, Long.MAX_VALUE),
                            trade(3, "S3", "B1", "U-S3", "U-B1", Side.BUY, 95, 3),
                        ),
                    ),
                ),
            )

        cases.forEach { (command, batch) ->
            val state = MatchingResultState()
            assertRejectedWithoutChanges(state) {
                state.register("C1", command, batch.count, batch.first, batch.last, batch.events)
            }
            assertNull(state.find(market, "C1"))
            val validCommand = submit("B1", "U-B1", Side.BUY, 100, 1)
            val validEvent = entered(1, "B1", "U-B1", Side.BUY, 100, 1)
            assertEquals(listOf(validEvent), state.register("C1", validCommand, 1, 1, 1, listOf(validEvent)).events)
        }
    }

    @Test
    fun `체결과 잔량을 더할 때도 Long 범위를 넘으면 거절한다`() {
        val state = MatchingResultState()
        val command = submit("B1", "U-B1", Side.BUY, 100, Long.MAX_VALUE)
        val events =
            listOf(
                trade(1, "S1", "B1", "U-S1", "U-B1", Side.BUY, 90, Long.MAX_VALUE),
                entered(2, "B1", "U-B1", Side.BUY, 100, 1),
            )

        assertRejectedWithoutChanges(state) { state.register("C1", command, 2, 1, 2, events) }
        assertNull(state.find(market, "C1"))
    }

    @Test
    fun `선언한 범위의 끝 순번이 overflow되면 쓰기 전에 거절한다`() {
        val state = MatchingResultState()
        val command = submit("B1", "U-B1", Side.BUY, 100, 3)
        val events =
            listOf(
                trade(Long.MAX_VALUE - 1, "S1", "B1", "U-S1", "U-B1", Side.BUY, 90, 1),
                trade(Long.MAX_VALUE, "S2", "B1", "U-S2", "U-B1", Side.BUY, 95, 1),
                entered(Long.MIN_VALUE, "B1", "U-B1", Side.BUY, 100, 1),
            )

        assertRejectedWithoutChanges(state) {
            state.register("C1", command, 3, Long.MAX_VALUE - 1, Long.MAX_VALUE, events)
        }
        assertNull(state.find(market, "C1"))
    }

    @Test
    fun `마켓 마지막 순번이 최댓값이면 다음 순번을 음수로 감아 등록하지 않는다`() {
        val state = prepared()

        // 실제 Long.MAX_VALUE건 등록은 만들지 않고 내부 마지막 순번만 경계값으로 준비한다.
        @Suppress("UNCHECKED_CAST")
        val sequences = field("lastSequences").get(state) as MutableMap<MarketId, Long>
        sequences[market] = Long.MAX_VALUE
        val command = submit("B2", "U-B2", Side.BUY, 100, 1)
        val event = entered(Long.MAX_VALUE, "B2", "U-B2", Side.BUY, 100, 1)

        assertRejectedWithoutChanges(state) {
            state.register("C3", command, 1, Long.MAX_VALUE, Long.MAX_VALUE, listOf(event))
        }
        assertNull(state.find(market, "C3"))
        assertEquals(mapOf(market to Long.MAX_VALUE), state.snapshot().lastEngineSequences)
    }

    @Test
    fun `입력 복사 중 예외는 기존 이력을 유지하고 다음 정상 등록을 허용한다`() {
        val state = prepared()
        val before = state.snapshot()
        val failure = IllegalStateException("입력 목록 읽기 실패")
        val failingInput =
            object : AbstractList<MatchingEvent>() {
                override val size: Int = 3

                override fun get(index: Int): MatchingEvent = throw failure
            }

        assertSame(failure, assertFailsWith<IllegalStateException> { state.register("C3", buy(), 3, 3, 5, failingInput) })
        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
        assertNull(state.find(market, "C3"))
        assertEquals(buyEvents(), registerBuy(state).events)
    }

    @Test
    fun `실제 결과 쓰기 직후 예외나 Error가 나면 재전송과 정상 조회를 모두 차단한다`() {
        listOf(IllegalStateException("반영 중 실패"), AssertionError("반영 중 Error")).forEach { failure ->
            val state = prepared()
            val before = state.snapshot()
            val wasWritten = failAfterRecordWrite(state, failure)

            assertSame(failure, assertFailsWith<Throwable> { registerBuy(state) })
            assertTrue(wasWritten())
            assertTrue(state.isBlocked)
            assertFailsWith<IllegalStateException> { registerBuy(state) }
            assertFailsWith<IllegalStateException> {
                state.register("C4", submit("B2", "U-B2", Side.BUY, 100, 1), 1, 6, 6, listOf(entered(6, "B2", "U-B2", Side.BUY, 100, 1)))
            }
            assertFailsWith<IllegalStateException> {
                state.register("C1", submit("S1", "U-S1", Side.SELL, 90, 1), 1, 1, 1, listOf(entered(1, "S1", "U-S1", Side.SELL, 90, 1)))
            }
            assertFailsWith<IllegalStateException> { state.find(market, "C1") }
            assertFailsWith<IllegalStateException> { state.find(market, "C3") }
            assertFailsWith<IllegalStateException> { state.find(otherMarket, "unknown") }
            assertFailsWith<IllegalStateException> { state.snapshot() }
            assertEquals(listOf("C1", "C2"), before.results.map { it.commandId })
            assertEquals(mapOf(market to 2L), before.lastEngineSequences)
        }
    }

    // 공개 실패 스위치 없이 실제 내부 map 쓰기를 마친 뒤 실패시킨다. 롤백 여부는 가정하지 않는다.
    private fun failAfterRecordWrite(
        state: MatchingResultState,
        failure: Throwable,
    ): () -> Boolean {
        @Suppress("UNCHECKED_CAST")
        val original = field("records").get(state) as MutableMap<Any, MatchingCommandResult>
        var written = false
        val failing =
            object : MutableMap<Any, MatchingCommandResult> by original {
                override fun put(
                    key: Any,
                    value: MatchingCommandResult,
                ): MatchingCommandResult? {
                    original[key] = value
                    written = true
                    throw failure
                }
            }
        field("records").set(state, failing)
        return { written }
    }

    private fun field(name: String) = MatchingResultState::class.java.getDeclaredField(name).apply { isAccessible = true }
}
