package com.exchange.core.trading

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TradingIdentifiersTest {
    @Test
    fun `업무 종류 lineage 순번과 market ordinal이 서로 구분된다`() {
        val first = ClusterLineage(0, 1)
        val second = ClusterLineage(1, 0)
        val ids =
            listOf(
                TradingIdentifiers.order(first, 1).value,
                TradingIdentifiers.order(first, 2).value,
                TradingIdentifiers.order(second, 1).value,
                TradingIdentifiers.reserve(first, 1),
                TradingIdentifiers.opening(first, 1),
                TradingIdentifiers.settlement(first, 0, 1),
                TradingIdentifiers.settlement(first, 1, 1),
                TradingIdentifiers.settlement(second, 0, 1),
            )
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.length <= 64 && it.all { char -> char.code in 33..126 } })
        assertEquals(TradingIdentifiers.order(first, 1), TradingIdentifiers.order(first, 1))
        assertNotEquals(TradingIdentifiers.order(ClusterLineage(-1, -1), Long.MAX_VALUE), TradingIdentifiers.order(first, 1))
    }

    @Test
    fun `ID는 고정 big endian 필드의 원문을 encode한다`() {
        assertEquals("O2AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB", TradingIdentifiers.order(ClusterLineage(0, 0), 1).value)
        assertFailsWith<IllegalArgumentException> { TradingIdentifiers.order(ClusterLineage(0, 0), 0) }
        assertFailsWith<IllegalArgumentException> { TradingIdentifiers.settlement(ClusterLineage(0, 0), -1, 1) }
    }

    @Test
    fun `한 번 인코딩한 승인 ID 쌍은 기존 주문 예약 ID와 모든 경계값에서 동일하다`() {
        for (lineage in listOf(ClusterLineage(0, 0), ClusterLineage(1, 2), ClusterLineage(Long.MIN_VALUE, Long.MAX_VALUE))) {
            for (sequence in listOf(1L, 255L, 256L, Long.MAX_VALUE)) {
                val identifiers = TradingIdentifiers.admission(lineage, sequence)
                assertEquals(TradingIdentifiers.order(lineage, sequence), identifiers.orderId)
                assertEquals(TradingIdentifiers.reserve(lineage, sequence), identifiers.reserveTransactionId)
                assertEquals(identifiers.orderId.value.substring(2), identifiers.reserveTransactionId.substring(2))
            }
        }
        assertEquals("O2AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB", TradingIdentifiers.admission(ClusterLineage(0, 0), 1).orderId.value)
        assertEquals("R2AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB", TradingIdentifiers.admission(ClusterLineage(0, 0), 1).reserveTransactionId)
        assertFailsWith<IllegalArgumentException> { TradingIdentifiers.admission(ClusterLineage(0, 0), 0) }
        assertFailsWith<IllegalArgumentException> { TradingIdentifiers.admission(ClusterLineage(0, 0), -1) }
    }
}
