package com.exchange.core.trading

import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.order.Side
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReservedMatchingCancellationTest {
    private val f = MatchingIntakeTestFixture()

    @Test
    fun `대기 신규 다음 취소를 적용하면 남은 수량과 대상 원본을 결과에 보관한다`() {
        val owner = owner()
        val original = input(1)
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(original).status)
        val cancellation = cancellation(original, 2)

        val applied = owner.apply(cancellation)

        assertEquals(MatchingIntakeStatus.APPLIED, applied.status)
        val result = assertNotNull(applied.result)
        assertEquals("CANCELLED", result.outcome.name)
        assertEquals(cancellation, result.input)
        assertEquals(listOf(original), result.effects.map { it.origin })
        assertEquals(Quantity(3), result.effects.single().beforeQuantity)
        assertEquals(Quantity.ZERO, result.effects.single().afterQuantity)
        assertEquals(listOf(OrderCancelled(f.a.marketId, 2, original.order.orderId, original.order.userId, Quantity(3))), result.events)
        assertTrue(owner.restingOrders(f.a.marketId).isEmpty())
        assertEquals(2L, applied.ack?.through)
        assertEquals(MatchingIntakeStatus.DUPLICATE, owner.apply(cancellation).status)
    }

    @Test
    fun `부분 체결 뒤 취소는 남은 수량만 제거하고 이후 신규는 신규 승인 두 경계를 잇는다`() {
        val owner = owner()
        val original = input(1)
        owner.apply(original)
        owner.apply(input(2, side = Side.SELL, quantity = 1, user = "seller"))
        val applied = owner.apply(cancellation(original, 3))
        val result = assertNotNull(applied.result)
        assertEquals(MatchingOutcome.CANCELLED, result.outcome)
        assertEquals(Quantity(2), result.effects.single().beforeQuantity)
        assertEquals(3L, result.engineSequenceAfter)
        assertTrue(owner.restingOrders(f.a.marketId).isEmpty())

        val restored = ReservedMatchingState(owner.configuration).also { it.restore(owner.recoveryState()) }
        val following = input(4, admission = 3).copy(marketAdmissionSequence = 3)
        assertEquals(MatchingIntakeStatus.APPLIED, restored.apply(following).status)
        assertEquals(
            3L,
            restored
                .recoveryState()
                .streams
                .first()
                .lastSourceAdmissionSequence,
        )
        assertEquals(
            3L,
            restored
                .recoveryState()
                .streams
                .first()
                .lastSourceMarketAdmissionSequence,
        )
        assertEquals(
            4L,
            restored
                .recoveryState()
                .streams
                .first()
                .appliedThrough,
        )
        assertEquals(listOf(following.order.orderId), restored.restingOrders(f.a.marketId).map { it.orderId })
    }

    @Test
    fun `전량 체결한 대상의 취소도 빈 효과와 취소 거절 사건을 가진 완결 결과다`() {
        val owner = owner()
        val original = input(1, quantity = 1)
        owner.apply(original)
        owner.apply(input(2, side = Side.SELL, quantity = 1, user = "seller"))

        val cancellation = cancellation(original, 3)
        val result = assertNotNull(owner.apply(cancellation).result)

        assertEquals(MatchingOutcome.CANCEL_REJECTED, result.outcome)
        assertTrue(result.effects.isEmpty())
        assertEquals(
            listOf(OrderCancelRejected(f.a.marketId, 3, original.order.orderId, original.order.userId, "order not found")),
            result.events,
        )
        val restored = ReservedMatchingState(owner.configuration).also { it.restore(owner.recoveryState()) }
        assertEquals(result, restored.apply(cancellation).result)
        assertTrue(restored.restingOrders(f.a.marketId).isEmpty())
    }

    @Test
    fun `앞 신규가 빠진 취소와 보존 결과 공간이 찬 취소는 경계를 전진하지 않는다`() {
        val owner = owner(f.limits(resultEntries = 1))
        val original = input(1)
        val cancellation = cancellation(original, 2)
        assertEquals(MatchingIntakeStatus.GAP, owner.apply(cancellation).status)
        owner.apply(original)
        assertEquals(MatchingIntakeStatus.BUSY, owner.apply(cancellation).status)
        assertEquals(1L, owner.lastEngineSequence(f.a.marketId))
        assertEquals(
            1L,
            owner
                .recoveryState()
                .streams
                .first()
                .appliedThrough,
        )
        assertEquals(Quantity(3), owner.restingOrders(f.a.marketId).single().remainingQuantity)

        owner.acknowledgeFundsSettled(MatchingFundsSettledAck(owner.configuration.resultStream(f.a.marketId), 1, f.contexts.first()))
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(cancellation).status)
        assertTrue(owner.restingOrders(f.a.marketId).isEmpty())
        val restored = ReservedMatchingState(owner.configuration).also { it.restore(owner.recoveryState()) }
        assertEquals(MatchingIntakeStatus.DUPLICATE, restored.apply(cancellation).status)
    }

    @Test
    fun `취소 원본의 대상 입력 요청자 식별자 변조는 적용과 성공 확인을 남기지 않는다`() {
        val owner = owner()
        val original = input(1)
        owner.apply(original)
        val valid = cancellation(original, 2)
        val metadata = assertNotNull(valid.cancel)
        val invalid =
            listOf(
                valid.copy(cancel = metadata.copy(targetInputSequence = 2)),
                valid.copy(cancel = metadata.copy(cancelSequence = 2)),
                valid.copy(cancel = metadata.copy(cancelId = "C2" + "x".repeat(38))),
                valid.copy(cancel = metadata.copy(requestToken = metadata.requestToken.copy(userId = UserId("other")))),
                cancellation(input(1, quantity = 2), 2),
            )
        for (changed in invalid) {
            val reply = owner.apply(changed)
            assertEquals(MatchingIntakeStatus.INVALID_INPUT, reply.status)
            assertNull(reply.ack)
            assertEquals(1L, owner.lastEngineSequence(f.a.marketId))
            assertEquals(
                1L,
                owner
                    .recoveryState()
                    .streams
                    .first()
                    .appliedThrough,
            )
            assertEquals(Quantity(3), owner.restingOrders(f.a.marketId).single().remainingQuantity)
            assertFalse(owner.isBlocked)
        }
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(valid).status)
        assertEquals(MatchingIntakeStatus.CONTENT_CONFLICT, owner.apply(invalid.first()).status)
    }

    @Test
    fun `신규 전용과 취소 계약은 입력 결과와 복원 구성을 섞지 않는다`() {
        val legacy = f.owner()
        val mixed = owner()
        val original = input(1)
        assertEquals(MatchingIntakeStatus.INVALID_STREAM, legacy.apply(original).status)
        assertEquals(MatchingIntakeStatus.INVALID_STREAM, mixed.apply(f.input(1)).status)
        val legacyResult = assertNotNull(legacy.apply(f.input(1)).result)
        val mixedResult = assertNotNull(mixed.apply(original).result)
        assertEquals(1, legacyResult.formatVersion)
        assertEquals(2, mixedResult.formatVersion)
        assertTrue(legacyResult.resultId.startsWith("M1:"))
        assertTrue(mixedResult.resultId.startsWith("M2:"))
        assertFalse(legacy.configuration == mixed.configuration)
        val wrongOwner = ReservedMatchingState(legacy.configuration)
        assertFailsWith<IllegalArgumentException> { wrongOwner.restore(mixed.recoveryState()) }
        assertTrue(wrongOwner.isBlocked)
    }

    @Test
    fun `취소 거절이 보존 중인 다른 신규를 최초 대상으로 가리킨 복원 후보는 차단한다`() {
        val owner = owner()
        val original = input(1, quantity = 1)
        owner.apply(original)
        owner.apply(input(2, side = Side.SELL, quantity = 1, user = "seller"))
        owner.apply(cancellation(original, 3))
        val saved = owner.recoveryState()
        val row = saved.streams.first()
        val result = row.outstanding.last()
        val changedInput = result.input.copy(cancel = assertNotNull(result.input.cancel).copy(targetInputSequence = 2))
        val changedResult =
            MatchingResultEnvelope(
                result.stream,
                result.resultSequence,
                changedInput,
                result.outcome,
                result.reason,
                result.engineSequenceBefore,
                result.engineSequenceAfter,
                result.events,
                result.effects,
            )
        val changedRow =
            MatchingStreamRecovery(
                row.stream,
                row.appliedThrough,
                row.lastSourceAdmissionSequence,
                row.resultIssued,
                row.fundsSettledThrough,
                row.settledEngineSequence,
                row.outstanding.dropLast(1) + changedResult,
                row.resultBytes,
                row.activeOrders,
                row.activeBytes,
                row.lastSourceMarketAdmissionSequence,
            )
        val changed =
            MatchingRoleRecoveryState(
                saved.configuration,
                saved.lifecycle,
                saved.matching,
                saved.active,
                listOf(changedRow) + saved.streams.drop(1),
                saved.resultEntries,
                saved.resultBytes,
                saved.activeOrders,
                saved.activeBytes,
            )
        val restored = ReservedMatchingState(owner.configuration)

        assertFailsWith<IllegalArgumentException> { restored.restore(changed) }
        assertTrue(restored.isBlocked)
    }

    @Test
    fun `결과 회수 뒤 다른 가격의 활성 원본도 같은 신규 승인 순번을 공유할 수 없다`() {
        val owner = owner()
        owner.apply(input(1))
        val cancelled = f.input(2, price = 90).copy(inputContractVersion = 2)
        owner.apply(cancelled)
        owner.apply(cancellation(cancelled, 3))
        val later = f.input(4, price = 80, admission = 3).copy(inputContractVersion = 2, marketAdmissionSequence = 3)
        owner.apply(later)
        owner.acknowledgeFundsSettled(MatchingFundsSettledAck(owner.configuration.resultStream(f.a.marketId), 4, f.contexts.first()))
        val saved = owner.recoveryState()
        val changed =
            MatchingRoleRecoveryState(
                saved.configuration,
                saved.lifecycle,
                saved.matching,
                saved.active.map { if (it == later) it.copy(marketAdmissionSequence = 1) else it },
                saved.streams,
                saved.resultEntries,
                saved.resultBytes,
                saved.activeOrders,
                saved.activeBytes,
            )
        val restored = ReservedMatchingState(owner.configuration)

        assertFailsWith<IllegalArgumentException> { restored.restore(changed) }
        assertTrue(restored.isBlocked)
    }

    private fun owner(limits: MatchingIntakeLimits = MatchingIntakeLimits.defaults(f.topology, f.declaration)) =
        ReservedMatchingState(MatchingIntakeConfiguration(f.topology, f.declaration, limits, inputContractVersion = 2))
            .also { it.initialize() }

    private fun input(
        dispatch: Long,
        side: Side = Side.BUY,
        quantity: Long = 3,
        admission: Long = dispatch,
        user: String = "buyer",
    ) = f.input(dispatch, side = side, quantity = quantity, admission = admission, user = user).copy(inputContractVersion = 2)

    private fun cancellation(
        original: FundsOrderDispatch,
        dispatch: Long,
    ): FundsOrderDispatch =
        original.copy(
            dispatchSequence = dispatch,
            cancel =
                FundsCancelDispatch(
                    TradingIdentifiers.cancel(f.funds, 1),
                    RequestToken(original.order.userId, 0, 1, 1),
                    1,
                    original.dispatchSequence,
                ),
        )
}
