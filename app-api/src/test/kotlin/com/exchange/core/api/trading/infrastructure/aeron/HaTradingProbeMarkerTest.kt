package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.MarketId
import com.exchange.core.trading.ExecutionReceipt
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HaTradingProbeMarkerTest {
    @Test
    fun `완료 관측은 실제로 새 실행을 성공 적용한 경우에만 기록한다`() {
        val executed =
            FundingObservation(
                position = 128,
                snapshot = null,
                result = null,
                replyStatus = "EXECUTED",
                executionSequence = 1,
                compactExecution = ExecutionReceipt(1, 1, MarketId("BTC-USDT"), 1, 1, false),
            )

        fun records(observation: FundingObservation) = haProbeIsAppliedExecution(observation) { it.compactExecution != null }

        assertTrue(records(executed))
        assertFalse(records(executed.copy(replyStatus = "QUARANTINED", blocked = true, compactExecution = null)))
        assertFalse(records(executed.copy(blocked = true)))
        assertFalse(records(executed.copy(failure = "state apply failed")))
        assertFalse(records(executed.copy(compactExecution = null)), "AlreadyPassed must not emit another applied marker")
        assertFalse(records(executed.copy(replyStatus = "BOUNDED_APPLIED")))
        assertFalse(records(executed.copy(executionSequence = null)))
    }
}
