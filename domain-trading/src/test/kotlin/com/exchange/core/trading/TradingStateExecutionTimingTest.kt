package com.exchange.core.trading

import com.exchange.core.order.Side
import com.exchange.core.trading.TradingStateFailureTestSupport.actualValues
import com.exchange.core.trading.TradingStateFailureTestSupport.failAfterPut
import com.exchange.core.trading.TradingStateFailureTestSupport.read
import com.exchange.core.trading.TradingStateFixtures.admit
import com.exchange.core.trading.TradingStateFixtures.btc
import com.exchange.core.trading.TradingStateFixtures.buyer
import com.exchange.core.trading.TradingStateFixtures.fund
import com.exchange.core.trading.TradingStateFixtures.market
import com.exchange.core.trading.TradingStateFixtures.order
import com.exchange.core.trading.TradingStateFixtures.seller
import com.exchange.core.trading.TradingStateFixtures.state
import com.exchange.core.trading.TradingStateFixtures.time
import com.exchange.core.trading.TradingStateFixtures.usdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 가짜 외부 시계로 경계만 검사한다. 실제 경과 시간은 도메인이 읽거나 저장하지 않는다. */
class TradingStateExecutionTimingTest {
    @Test
    fun `외부 관측자는 여섯 실행 단계를 재며 기본 실행과 같은 금융 상태를 남긴다`() {
        val baseline = pendingMatch()
        val observed = pendingMatch()
        val observer = RecordingObserver()

        baseline.state.executePending(baseline.sequence, time)
        val completion = observed.state.executePending(observed.sequence, time, observer)

        assertEquals(actualValues(baseline.state), actualValues(observed.state))
        assertEquals(TradingExecutionPhase.entries, observer.completed.map { it.phase })
        assertEquals(
            TradingExecutionPhase.entries.flatMap { listOf(it to true, it to false) },
            observer.boundaries,
        )
        assertTrue(observer.completed.all { it.sequence == observed.sequence && it.failure == null && it.elapsedNs == 10L })
        assertEquals(1, completion.settlement.settlements.size)
        assertFalse(observed.state.isBlocked)

        val after = actualValues(observed.state)
        val callbackCount = observer.boundaries.size
        assertSame(completion, observed.state.executePending(observed.sequence, time.plusSeconds(1), observer))
        assertEquals(callbackCount, observer.boundaries.size, "완료 재사용은 후보를 다시 만들거나 시간을 재지 않는다")
        assertEquals(after, actualValues(observed.state))
    }

    @Test
    fun `대상 선택 실패는 관측 콜백과 금융 상태와 정상 입구의 차단을 바꾸지 않는다`() {
        val pending = pendingMatch()
        val observer = RecordingObserver()
        val next = admit(pending.state, order("next", buyer, Side.BUY, 100, 1))
        val before = actualValues(pending.state)

        for (sequence in listOf(0L, -1L, 999L, next.record.sequence)) {
            assertFailsWith<IllegalArgumentException> { pending.state.executePending(sequence, time, observer) }
            assertEquals(before, actualValues(pending.state))
            assertFalse(pending.state.isBlocked)
            assertTrue(observer.boundaries.isEmpty())
        }
    }

    @Test
    fun `시작과 종료 관측 예외는 정상 체결과 완료와 차단 판정을 바꾸지 않는다`() {
        val baseline = pendingMatch()
        val observed = pendingMatch()
        val observer = RecordingObserver(IllegalStateException("observer unavailable"))

        baseline.state.executePending(baseline.sequence, time)
        val completion = observed.state.executePending(observed.sequence, time, observer)

        assertEquals(actualValues(baseline.state), actualValues(observed.state))
        assertEquals(TradingExecutionPhase.entries, observer.completed.map { it.phase })
        assertTrue(observer.completed.all { it.failure == null })
        assertFalse(observed.state.isBlocked)
        assertSame(completion, observed.state.executePending(observed.sequence, time, observer))
    }

    @Test
    fun `정산 계산 실패는 같은 원본 예외와 실패 단계를 전달하고 첫 쓰기 전 상태를 보존하며 차단한다`() {
        val pending = pendingMatch(sellerQuote = Long.MAX_VALUE)
        val observer = RecordingObserver(IllegalStateException("observer unavailable"))
        val before = actualValues(pending.state)

        val failure = assertFailsWith<ArithmeticException> { pending.state.executePending(pending.sequence, time, observer) }

        assertEquals(before, actualValues(pending.state))
        assertEquals(
            listOf(
                TradingExecutionPhase.MATCHING_CANDIDATE,
                TradingExecutionPhase.SETTLEMENT_INPUT_VALIDATION,
                TradingExecutionPhase.SETTLEMENT_CANDIDATE,
            ),
            observer.completed.map { it.phase },
        )
        assertSame(failure, observer.completed.last().failure)
        assertBlocked(pending)
    }

    @Test
    fun `실제 적용과 완료 쓰기 후 실패도 원본 예외와 해당 단계를 보존하며 모든 정상 입구를 차단한다`() {
        for (phase in listOf(TradingExecutionPhase.APPLY, TradingExecutionPhase.COMPLETION)) {
            val pending = pendingMatch()
            val failure = IllegalStateException("business write failed in $phase")
            val observer = RecordingObserver(IllegalStateException("observer unavailable"))
            val before = actualValues(pending.state)
            val probe =
                if (phase == TradingExecutionPhase.APPLY) {
                    failAfterPut(read(pending.state, "admission"), "balances", failure)
                } else {
                    failAfterPut(pending.state, "completions", failure)
                }

            assertSame(
                failure,
                assertFailsWith<IllegalStateException> { pending.state.executePending(pending.sequence, time, observer) },
            )

            assertEquals(1, probe.count, "도메인의 실제 put 뒤 발생한 실패여야 한다")
            assertNotEquals(before, actualValues(pending.state), "실제 쓰기 실패를 계산 실패처럼 되돌렸다고 주장하지 않는다")
            assertEquals(phase, observer.completed.last().phase)
            assertSame(failure, observer.completed.last().failure)
            assertBlocked(pending)
        }
    }

    @Test
    fun `관측 VM 오류는 정상 결과처럼 숨기지 않고 원본 오류를 전달한다`() {
        val pending = pendingMatch()
        val failure = OutOfMemoryError("synthetic observer VM failure")
        val before = actualValues(pending.state)
        val observer =
            object : TradingExecutionObserver {
                override fun onPhaseStarted(
                    admissionSequence: Long,
                    phase: TradingExecutionPhase,
                ): Unit = throw failure
            }

        assertSame(failure, assertFailsWith<OutOfMemoryError> { pending.state.executePending(pending.sequence, time, observer) })
        assertEquals(before, actualValues(pending.state))
        assertBlocked(pending)
    }

    @Test
    fun `업무 실패 도중의 관측 VM 오류는 원본 업무 예외를 덮지 않고 suppressed로 전달한다`() {
        val pending = pendingMatch(sellerQuote = Long.MAX_VALUE)
        val observerFailure = OutOfMemoryError("synthetic observer VM failure")
        var observedBusinessFailure: Throwable? = null
        val observer =
            object : TradingExecutionObserver {
                override fun onPhaseCompleted(
                    admissionSequence: Long,
                    phase: TradingExecutionPhase,
                    failure: Throwable?,
                ) {
                    if (failure != null) {
                        observedBusinessFailure = failure
                        throw observerFailure
                    }
                }
            }

        val failure = assertFailsWith<ArithmeticException> { pending.state.executePending(pending.sequence, time, observer) }

        assertSame(failure, observedBusinessFailure)
        assertEquals(listOf(observerFailure), failure.suppressed.toList())
        assertBlocked(pending)
    }

    private data class PendingMatch(
        val state: TradingState,
        val sequence: Long,
    )

    private fun pendingMatch(sellerQuote: Long = 0): PendingMatch {
        val state = state()
        fund(state, seller, btc, 1)
        if (sellerQuote > 0) fund(state, seller, usdt, sellerQuote)
        fund(state, buyer, usdt, 1_000)
        val maker = admit(state, order("maker", seller, Side.SELL, 90, 1))
        state.executePending(maker.record.sequence, time)
        val taker = admit(state, order("taker", buyer, Side.BUY, 100, 1))
        return PendingMatch(state, taker.record.sequence)
    }

    private fun assertBlocked(pending: PendingMatch) {
        assertTrue(pending.state.isBlocked)
        assertFailsWith<IllegalStateException> { pending.state.snapshot() }
        assertFailsWith<IllegalStateException> { pending.state.book(market.marketId) }
        assertFailsWith<IllegalStateException> { pending.state.pendingOrders(market.marketId) }
        assertFailsWith<IllegalStateException> { pending.state.executePending(pending.sequence, time) }
        assertFailsWith<IllegalStateException> { admit(pending.state, order("after-failure", buyer, Side.BUY, 100, 1)) }
    }

    private data class PhaseCompletion(
        val sequence: Long,
        val phase: TradingExecutionPhase,
        val failure: Throwable?,
        val elapsedNs: Long,
    )

    private class RecordingObserver(
        private val callbackFailure: Exception? = null,
    ) : TradingExecutionObserver {
        val boundaries = mutableListOf<Pair<TradingExecutionPhase, Boolean>>()
        val completed = mutableListOf<PhaseCompletion>()
        private var clockNs = 0L
        private var startedNs = 0L

        override fun onPhaseStarted(
            admissionSequence: Long,
            phase: TradingExecutionPhase,
        ) {
            clockNs += 10
            startedNs = clockNs
            boundaries.add(phase to true)
            callbackFailure?.let { throw it }
        }

        override fun onPhaseCompleted(
            admissionSequence: Long,
            phase: TradingExecutionPhase,
            failure: Throwable?,
        ) {
            clockNs += 10
            boundaries.add(phase to false)
            completed.add(PhaseCompletion(admissionSequence, phase, failure, clockNs - startedNs))
            callbackFailure?.let { throw it }
        }
    }
}
