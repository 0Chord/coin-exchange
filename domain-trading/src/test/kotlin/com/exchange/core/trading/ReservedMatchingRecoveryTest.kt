package com.exchange.core.trading

import com.exchange.core.common.Quantity
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.matching.MatchingEngine
import com.exchange.core.order.Side
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReservedMatchingRecoveryTest {
    private val fixture = MatchingIntakeTestFixture()

    @Test
    fun `부분 체결의 현재 잔량과 최초 예약 수량을 구분해 복원한다`() {
        val owner = fixture.owner()
        owner.apply(fixture.input(1, Side.SELL, 90, 1, user = "seller"))
        owner.apply(fixture.input(2, Side.BUY, 100, 3))
        val saved = owner.recoveryState()
        val original = saved.active.single()
        assertEquals(3L, original.reservation.remainingQuantity.value)
        assertEquals(
            2L,
            saved.matching.markets
                .single()
                .orders
                .single()
                .remainingQuantity.value,
        )
        val restored = ReservedMatchingState(saved.configuration).also { it.restore(saved) }
        assertEquals(
            2L,
            restored
                .restingOrders(fixture.a.marketId)
                .single()
                .remainingQuantity.value,
        )
        assertEquals(MatchingIntakeStatus.DUPLICATE, restored.apply(original).status)
    }

    @Test
    fun `진행 숫자 결과 구멍 카운터와 사건 연결이 잘못되면 빈 소유자도 차단한다`() {
        val saved = prepared()
        val stream = saved.streams.first()
        val result = stream.outstanding.last()
        val alteredResult =
            MatchingResultEnvelope(
                result.stream,
                result.resultSequence,
                result.input,
                result.outcome,
                result.reason,
                result.engineSequenceBefore + 1,
                result.engineSequenceAfter,
                result.events,
                result.effects,
            )
        val broken =
            listOf(
                replace(saved, resultBytes = saved.resultBytes + 1),
                replace(saved, activeBytes = saved.activeBytes + 1),
                replace(saved, streams = listOf(stream(stream, outstanding = stream.outstanding.drop(1))) + saved.streams.drop(1)),
                replace(saved, streams = listOf(stream(stream, lastSource = 99)) + saved.streams.drop(1)),
                replace(
                    saved,
                    streams =
                        listOf(stream(stream, outstanding = stream.outstanding.dropLast(1) + alteredResult)) + saved.streams.drop(1),
                ),
                replace(saved, streams = listOf(stream(stream, settledEngine = 1)) + saved.streams.drop(1)),
                replace(saved, active = emptyList()),
                replace(saved, active = saved.active + saved.active.single()),
            )
        for (state in broken) {
            val owner = ReservedMatchingState(saved.configuration)
            assertFailsWith<IllegalArgumentException> { owner.restore(state) }
            assertTrue(owner.isBlocked)
            assertFailsWith<IllegalStateException> { owner.recoveryState() }
            assertFailsWith<IllegalStateException> { owner.initialize() }
        }
    }

    @Test
    fun `가격이 같은 두 대기 주문의 순서를 바꾼 복원을 거절한다`() {
        val owner = fixture.owner()
        owner.apply(fixture.input(1, quantity = 1))
        owner.apply(fixture.input(2, quantity = 1))
        val saved = owner.recoveryState()
        val market = saved.matching.markets.single()
        val swapped =
            MatchingCheckpoint(listOf(MatchingCheckpoint.Market(market.marketId, market.lastEngineSequence, market.orders.reversed())))
        val restored = ReservedMatchingState(saved.configuration)
        assertFailsWith<IllegalArgumentException> { restored.restore(replace(saved, matching = swapped)) }
        assertTrue(restored.isBlocked)
    }

    @Test
    fun `활성 원본과 미확인 결과가 같은 입력에 다른 원문을 쓰면 거절한다`() {
        val saved = prepared()
        val original = saved.active.single()
        val changed = fixture.input(original.dispatchSequence, price = 101, quantity = 3)
        val restored = ReservedMatchingState(saved.configuration)
        assertFailsWith<IllegalArgumentException> { restored.restore(replace(saved, active = listOf(changed))) }
        assertTrue(restored.isBlocked)
    }

    @Test
    fun `이벤트는 같아도 maker 효과의 원본이나 수량이 다르면 거절한다`() {
        val saved = prepared()
        val progress = saved.streams.first()
        val last = progress.outstanding.last()
        val maker = last.effects.first { it.origin.dispatchSequence == 1L }
        for (badEffect in listOf(maker.copy(afterQuantity = Quantity(1)), maker.copy(origin = last.input))) {
            val changed =
                MatchingResultEnvelope(
                    last.stream,
                    last.resultSequence,
                    last.input,
                    last.outcome,
                    last.reason,
                    last.engineSequenceBefore,
                    last.engineSequenceAfter,
                    last.events,
                    last.effects.map { if (it === maker) badEffect else it },
                )
            val restored = ReservedMatchingState(saved.configuration)
            assertFailsWith<IllegalArgumentException> {
                restored.restore(
                    replace(
                        saved,
                        streams =
                            listOf(stream(progress, outstanding = progress.outstanding.dropLast(1) + changed)) + saved.streams.drop(1),
                    ),
                )
            }
            assertTrue(restored.isBlocked)
        }
    }

    @Test
    fun `회수한 대기 결과의 원본을 유지하고 같은 후속열을 재현한다`() {
        val original = fixture.owner()
        original.apply(fixture.input(1, quantity = 3))
        val configuration = original.recoveryState().configuration
        original.acknowledgeFundsSettled(MatchingFundsSettledAck(configuration.resultStream(fixture.a.marketId), 1, fixture.contexts[0]))
        val saved = original.recoveryState()
        assertEquals(0, saved.resultEntries)
        assertEquals(1, saved.activeOrders)
        val restored = ReservedMatchingState(configuration).also { it.restore(saved) }
        for (state in listOf(original, restored)) {
            assertEquals(MatchingIntakeStatus.PRUNED, state.apply(fixture.input(1)).status)
            assertEquals(MatchingIntakeStatus.GAP, state.apply(fixture.input(3, Side.SELL, 100, 1)).status)
            assertEquals(MatchingIntakeStatus.APPLIED, state.apply(fixture.input(2, Side.SELL, 100, 1)).status)
            assertEquals(MatchingIntakeStatus.DUPLICATE, state.apply(fixture.input(2, Side.SELL, 100, 1)).status)
            state.acknowledgeFundsSettled(MatchingFundsSettledAck(configuration.resultStream(fixture.a.marketId), 2, fixture.contexts[0]))
        }
        val continued = original.recoveryState()
        val resumed = restored.recoveryState()
        assertEquals(continued.active, resumed.active)
        assertEquals(continued.resultBytes, resumed.resultBytes)
        assertEquals(continued.activeBytes, resumed.activeBytes)
        assertEquals(
            continued.matching.markets
                .single()
                .orders,
            resumed.matching.markets
                .single()
                .orders,
        )
        assertEquals(
            continued.streams.map {
                listOf(it.appliedThrough, it.lastSourceAdmissionSequence, it.resultIssued, it.fundsSettledThrough, it.settledEngineSequence)
            },
            resumed.streams.map {
                listOf(it.appliedThrough, it.lastSourceAdmissionSequence, it.resultIssued, it.fundsSettledThrough, it.settledEngineSequence)
            },
        )
        assertFalse(restored.isBlocked)
    }

    @Test
    fun `엔진 반영 후 결과 기록 오류는 부분 상태를 정상 공개하지 않고 차단한다`() {
        val owner = fixture.owner()
        val data = field(owner, "data")!!
        val markets = field(data, "markets") as Map<*, *>
        val progress = markets[fixture.a.marketId]!!
        val outstanding = progress.javaClass.getDeclaredField("outstanding").also { it.isAccessible = true }
        outstanding.set(
            progress,
            object : LinkedHashMap<Long, MatchingResultEnvelope>() {
                override fun put(
                    key: Long,
                    value: MatchingResultEnvelope,
                ): MatchingResultEnvelope? = throw IllegalStateException("injected result write failure")
            },
        )
        val input = fixture.input(1)
        val failure = assertFailsWith<IllegalStateException> { owner.apply(input) }
        assertEquals("injected result write failure", failure.message)
        assertTrue(owner.isBlocked)
        assertEquals(TradingLifecycle.QUARANTINED, owner.lifecycle)
        // 실패 전 엔진 쓰기는 이미 끝났다. 자동 롤백했다고 오인하지 않는다.
        val engine = field(data, "engine") as MatchingEngine
        assertEquals(1L, engine.lastEngineSequence(fixture.a.marketId))
        assertEquals(1, engine.restingOrders(fixture.a.marketId).size)
        val config = owner.configuration
        assertFailsWith<IllegalStateException> { owner.apply(input) }
        assertFailsWith<IllegalStateException> { owner.restingOrders(fixture.a.marketId) }
        assertFailsWith<IllegalStateException> { owner.lastEngineSequence(fixture.a.marketId) }
        assertFailsWith<IllegalStateException> { owner.recoveryState() }
        assertFailsWith<IllegalStateException> { owner.readResults(config.resultStream(fixture.a.marketId), 1, 1, 10000) }
        assertFailsWith<IllegalStateException> {
            owner.acknowledgeFundsSettled(MatchingFundsSettledAck(config.resultStream(fixture.a.marketId), 0, fixture.contexts[0]))
        }
        assertFailsWith<IllegalStateException> { owner.initialize() }
        assertFailsWith<IllegalStateException> { owner.restore(prepared()) }
    }

    private fun field(
        target: Any,
        name: String,
    ): Any? =
        target.javaClass
            .getDeclaredField(name)
            .also { it.isAccessible = true }
            .get(target)

    @Test
    fun `결과를 정산한 적 없는 초기 frontier에 과거 사건을 만들어 복원하지 않는다`() {
        val empty = fixture.owner().recoveryState()
        val first = empty.streams.first()
        val forged =
            MatchingRoleRecoveryState(
                empty.configuration,
                empty.lifecycle,
                MatchingCheckpoint(listOf(MatchingCheckpoint.Market(fixture.a.marketId, 100, emptyList()))),
                emptyList(),
                listOf(MatchingStreamRecovery(first.stream, 0, 0, 0, 0, 100, emptyList(), 0, 0, 0)) + empty.streams.drop(1),
                0,
                0,
                0,
                0,
            )
        val owner = ReservedMatchingState(empty.configuration)
        assertFailsWith<IllegalArgumentException> { owner.restore(forged) }
        assertTrue(owner.isBlocked)
        val underflowedSource =
            MatchingRoleRecoveryState(
                empty.configuration,
                empty.lifecycle,
                empty.matching,
                emptyList(),
                listOf(MatchingStreamRecovery(first.stream, 2, 1, 2, 2, 0, emptyList(), 0, 0, 0)) + empty.streams.drop(1),
                0,
                0,
                0,
                0,
            )
        assertFailsWith<IllegalArgumentException> { ReservedMatchingState(empty.configuration).restore(underflowedSource) }
        ReservedMatchingState(empty.configuration).restore(empty)
    }

    @Test
    fun `사건 상한과 순번 소진이 동시에 발생해도 입력과 결과를 소비하지 않는다`() {
        val config = fixture.configuration(fixture.limits(maxResultEvents = 1))
        val seed = ReservedMatchingState(config).also { it.initialize() }
        val sell = fixture.input(1, Side.SELL, 90, 1, user = "seller")
        seed.apply(sell)
        seed.acknowledgeFundsSettled(MatchingFundsSettledAck(config.resultStream(fixture.a.marketId), 1, fixture.contexts[0]))
        val saved = seed.recoveryState()
        val market = saved.matching.markets.single()
        val nearEnd = Long.MAX_VALUE - 1
        val boundary =
            MatchingRoleRecoveryState(
                config,
                saved.lifecycle,
                MatchingCheckpoint(listOf(MatchingCheckpoint.Market(market.marketId, nearEnd, market.orders))),
                saved.active,
                listOf(
                    MatchingStreamRecovery(
                        saved.streams[0].stream,
                        nearEnd,
                        nearEnd,
                        nearEnd,
                        nearEnd,
                        nearEnd,
                        emptyList(),
                        0,
                        saved.activeOrders,
                        saved.activeBytes,
                    ),
                ) + saved.streams.drop(1),
                0,
                0,
                saved.activeOrders,
                saved.activeBytes,
            )
        val owner = ReservedMatchingState(config).also { it.restore(boundary) }
        val reply = owner.apply(fixture.input(Long.MAX_VALUE, Side.BUY, 100, 2))
        assertEquals(MatchingIntakeStatus.SEQUENCE_EXHAUSTED, reply.status)
        assertEquals(null, reply.result)
        assertEquals(null, reply.ack)
        val after = owner.recoveryState()
        assertEquals(nearEnd, after.streams.first().appliedThrough)
        assertEquals(nearEnd, after.streams.first().resultIssued)
        assertEquals(0, after.resultEntries)
        assertEquals(boundary.active, after.active)
        assertEquals(
            market.orders,
            after.matching.markets
                .single()
                .orders,
        )
        assertEquals(
            nearEnd,
            after.matching.markets
                .single()
                .lastEngineSequence,
        )
        assertFalse(owner.isBlocked)
    }

    private fun prepared(): MatchingRoleRecoveryState {
        val owner = fixture.owner()
        owner.apply(fixture.input(1, Side.SELL, 90, 1, user = "seller"))
        owner.apply(fixture.input(2, Side.BUY, 100, 3))
        return owner.recoveryState()
    }

    private fun replace(
        saved: MatchingRoleRecoveryState,
        matching: MatchingCheckpoint = saved.matching,
        active: List<FundsOrderDispatch> = saved.active,
        streams: List<MatchingStreamRecovery> = saved.streams,
        resultBytes: Long = saved.resultBytes,
        activeBytes: Long = saved.activeBytes,
    ) = MatchingRoleRecoveryState(
        saved.configuration,
        saved.lifecycle,
        matching,
        active,
        streams,
        saved.resultEntries,
        resultBytes,
        saved.activeOrders,
        activeBytes,
    )

    private fun stream(
        saved: MatchingStreamRecovery,
        outstanding: List<MatchingResultEnvelope> = saved.outstanding,
        lastSource: Long = saved.lastSourceAdmissionSequence,
        settledEngine: Long = saved.settledEngineSequence,
    ) = MatchingStreamRecovery(
        saved.stream,
        saved.appliedThrough,
        lastSource,
        saved.resultIssued,
        saved.fundsSettledThrough,
        settledEngine,
        outstanding,
        saved.resultBytes,
        saved.activeOrders,
        saved.activeBytes,
    )
}
