package com.exchange.core.api.trading.infrastructure.aeron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedTradingEngineCapacityTest {
    @Test
    fun `세 마켓의 실제 매칭 수수료와 최종 자금은 legacy와 bounded 양쪽에서 검증된다`() {
        val options = TradingEngineProbeOptions(warmup = 3, orders = 6)
        val legacy = runLegacyTradingEngineProbe(options)
        val bounded = runBoundedTradingEngineProbe(options)
        for (report in listOf(legacy, bounded)) {
            assertTrue(report.contains("\"financiallyVerified\":true"))
            assertTrue(report.contains("\"completed\":6"))
            assertTrue(report.contains("\"feeRevenue\":270"))
        }
        assertTrue(legacy.contains("\"retainedCompletions\":18"))
        assertTrue(bounded.contains("\"retainedReceipts\":0"))
        assertTrue(bounded.contains("\"retainedLiveOrders\":0"))
    }

    @Test
    fun `초기 짧은 입력 한도와 전체 cohort 분배를 고정한다`() {
        assertEquals(6_000, TradingEngineProbeOptions().total)
        assertEquals(listOf(2, 2, 1), (0..2).map { TradingEngineProbeWorkload.countForMarket(5, it) })
        assertFailsWith<IllegalArgumentException> { TradingEngineProbeOptions(warmup = 1_001, orders = 5_000) }
        assertFailsWith<IllegalArgumentException> { tradingEngineProbeOptions(arrayOf("--unknown")) }
    }
}
