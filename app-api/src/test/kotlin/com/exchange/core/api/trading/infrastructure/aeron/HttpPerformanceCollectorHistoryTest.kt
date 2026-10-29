package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpPerformanceCollectorHistoryTest {
    @Test
    fun `희소한 전체 Long 순번과 구간 경계는 서로 다른 원본과 완료 bit를 유지한다`() {
        val sequences = listOf(Long.MIN_VALUE, -1L, 0L, 63L, 64L, 1023L, 1024L, 1L shl 45, Long.MAX_VALUE)
        val history = HttpPerformanceCollectorHistory(sequences.size)
        val originals = sequences.map(::accepted)

        originals.forEach(history::record)
        originals.forEach { assertSame(it, history[it.sequence]) }
        assertEquals(sequences.size, history.identityCount)
        assertEquals(0, history.completedCount)
        assertNull(history[1025L])

        sequences.forEachIndexed { index, sequence ->
            assertFalse(history.isCompleted(sequence))
            assertTrue(history.markCompleted(sequence))
            assertFalse(history.markCompleted(sequence))
            assertTrue(history.isCompleted(sequence))
            assertEquals(index + 1, history.completedCount)
            sequences.drop(index + 1).forEach { assertFalse(history.isCompleted(it)) }
        }
        originals.forEach { assertSame(it, history[it.sequence]) }
    }

    @Test
    fun `한도는 순번 크기가 아닌 고유 원본 수이며 완료 후에도 동일 재접수를 허용한다`() {
        val history = HttpPerformanceCollectorHistory(2)
        val first = accepted(Long.MIN_VALUE)
        val last = accepted(Long.MAX_VALUE)
        history.record(first)
        history.record(last)
        history.markCompleted(first.sequence)

        history.record(first.copy())
        history.record(last.copy())
        assertEquals(2, history.identityCount)
        assertEquals(1, history.completedCount)
        assertSame(first, history[first.sequence])
        assertSame(last, history[last.sequence])

        val failure = assertFailsWith<IllegalArgumentException> { history.record(accepted(0)) }
        assertTrue(failure.message.orEmpty().contains("누적 식별 계획 초과"))
        assertNull(history[0])
        assertEquals(2, history.identityCount)
        assertEquals(1, history.completedCount)
    }

    @Test
    fun `완료 뒤에도 같은 순번의 모든 Accepted 필드를 원본 전체와 비교한다`() {
        val history = HttpPerformanceCollectorHistory(1)
        val original = accepted(1024)
        history.record(original)
        history.markCompleted(original.sequence)
        val conflicts =
            listOf(
                original.copy(userId = "OTHER"),
                original.copy(requestId = "OTHER"),
                original.copy(marketId = "OTHER"),
                original.copy(orderId = "OTHER"),
            )

        conflicts.forEach { conflict ->
            val failure = assertFailsWith<IllegalArgumentException> { history.record(conflict) }
            assertEquals("완료 표식 접수 순번 식별 충돌", failure.message)
            assertSame(original, history[original.sequence])
            assertEquals(1, history.identityCount)
            assertEquals(1, history.completedCount)
            assertTrue(history.isCompleted(original.sequence))
        }
    }

    private fun accepted(sequence: Long) = AdmissionResultPayload.Accepted("USER", "REQUEST", "BTC-USDT", "ORDER", sequence)
}
