package com.exchange.core.trading

import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReservedMatchingCancellationTest {
    private val f = MatchingIntakeTestFixture()

    @Test
    fun `같은 입력 prefix에서 부분 체결 뒤 취소 잔량을 확정하고 후속 반대 주문과 체결하지 않는다`() {
        val owner = f.owner()
        val buy = f.input(1)
        owner.apply(buy)
        owner.apply(f.input(2, Side.SELL, quantity = 1, user = "seller"))
        val cancel = cancel(buy, 3)
        assertEquals(MatchingIntakeStatus.GAP, f.owner().apply(cancel).status)
        val result = assertNotNull(owner.apply(cancel).result)
        assertEquals(MatchingOutcome.CANCELLED, result.outcome)
        assertEquals(listOf(OrderCancelled(f.a.marketId, 3, buy.order.orderId, buy.order.userId, Quantity(2))), result.events)
        assertEquals(listOf(MatchingOrderEffect(buy, Quantity(2), Quantity.ZERO, MatchingOrderDisposition.CANCELLED)), result.effects)
        assertEquals(0, owner.recoveryState().activeOrders)
        assertEquals(0L, owner.recoveryState().activeBytes)
        assertEquals(
            2L,
            owner
                .recoveryState()
                .streams
                .first()
                .lastSourceAdmissionSequence,
        )
        val later = f.input(4, Side.SELL, quantity = 2, admission = 3).copy(marketAdmissionSequence = 3)
        assertTrue(assertNotNull(owner.apply(later).result).events.single() is OrderEnteredBook)
        assertEquals(MatchingIntakeStatus.DUPLICATE, owner.apply(cancel).status)
        assertEquals(
            MatchingIntakeStatus.CONTENT_CONFLICT,
            owner.apply(cancel.copy(cancel = cancel.cancel!!.copy(requestToken = RequestToken(buy.order.userId, 0, 1, 99)))).status,
        )
    }

    @Test
    fun `전량 체결 또는 신규 최종 거절 뒤 취소 거절도 하나의 사건과 결과 prefix를 보존한다`() {
        val filled = f.owner()
        val buy = f.input(1, quantity = 1)
        filled.apply(buy)
        filled.apply(f.input(2, Side.SELL, quantity = 1))
        val rejected = assertNotNull(filled.apply(cancel(buy, 3)).result)
        assertEquals(MatchingOutcome.CANCEL_REJECTED, rejected.outcome)
        assertEquals(listOf(OrderCancelRejected(f.a.marketId, 3, buy.order.orderId, buy.order.userId, "order not found")), rejected.events)
        assertTrue(rejected.effects.isEmpty())
        assertEquals(
            3L,
            filled
                .recoveryState()
                .streams
                .first()
                .appliedThrough,
        )

        val limited = f.owner(f.limits(activeOrders = 1))
        limited.apply(f.input(1))
        val denied = f.input(2, price = 90)
        assertEquals(MatchingOutcome.NOT_EXECUTED, limited.apply(denied).result?.outcome)
        val deniedCancel = assertNotNull(limited.apply(cancel(denied, 3)).result)
        assertEquals(MatchingOutcome.CANCEL_REJECTED, deniedCancel.outcome)
        assertEquals(1L, deniedCancel.engineSequenceBefore)
        assertEquals(2L, deniedCancel.engineSequenceAfter)
        assertEquals(1, limited.recoveryState().activeOrders)
        ReservedMatchingState(limited.configuration).restore(limited.recoveryState())
    }

    @Test
    fun `활성 슬롯이 가득 차도 취소는 적용하고 결과 공간이 가득 차면 삭제와 prefix를 보존한다`() {
        val owner = f.owner(f.limits(resultEntries = 1, activeOrders = 1))
        val buy = f.input(1)
        owner.apply(buy)
        val cancel = cancel(buy, 2)
        val before = owner.recoveryState()
        assertEquals(MatchingIntakeStatus.BUSY, owner.apply(cancel).status)
        assertEquals(before.active, owner.recoveryState().active)
        assertEquals(
            before.matching.markets
                .single()
                .orders,
            owner
                .recoveryState()
                .matching.markets
                .single()
                .orders,
        )
        assertEquals(1L, owner.lastEngineSequence(f.a.marketId))
        assertEquals(
            1L,
            owner
                .recoveryState()
                .streams
                .first()
                .appliedThrough,
        )
        owner.acknowledgeFundsSettled(ack(1))
        assertEquals(MatchingOutcome.CANCELLED, owner.apply(cancel).result?.outcome)
        assertTrue(owner.restingOrders(f.a.marketId).isEmpty())
        assertEquals(0, owner.recoveryState().activeOrders)
        owner.acknowledgeFundsSettled(ack(2))
        assertEquals(MatchingIntakeStatus.PRUNED, owner.apply(cancel).status)
        assertEquals(0L, owner.recoveryState().resultBytes)
    }

    @Test
    fun `취소 requester 원본 identity와 공통 순서 모순은 아무 사건 없이 거절한다`() {
        val owner = f.owner()
        val buy = f.input(1)
        owner.apply(buy)
        val valid = cancel(buy, 2)
        val invalid =
            listOf(
                valid.copy(cancel = valid.cancel!!.copy(requestToken = RequestToken(UserId("intruder"), 0, 1, 1))),
                valid.copy(cancel = valid.cancel.copy(cancelId = "forged")),
                valid.copy(marketAdmissionSequence = 2),
                valid.copy(admissionSequence = 3),
                valid.copy(order = valid.order.copy(quantity = Quantity(2))),
            )
        for (input in invalid) {
            assertEquals(MatchingIntakeStatus.INVALID_INPUT, owner.apply(input).status)
            assertEquals(1L, owner.lastEngineSequence(f.a.marketId))
            assertEquals(
                1L,
                owner
                    .recoveryState()
                    .streams
                    .first()
                    .appliedThrough,
            )
            assertEquals(1, owner.restingOrders(f.a.marketId).size)
            assertFalse(owner.isBlocked)
        }
        assertEquals(MatchingOutcome.CANCELLED, owner.apply(valid).result?.outcome)
    }

    @Test
    fun `취소 성공의 삭제된 주문을 suffix에서 복구해 재계산하며 ACK 뒤에도 새 입력을 받는다`() {
        for (settleOriginal in listOf(false, true)) {
            val owner = f.owner()
            val buy = f.input(1)
            owner.apply(buy)
            if (settleOriginal) owner.acknowledgeFundsSettled(ack(1))
            val cancel = cancel(buy, 2)
            owner.apply(cancel)
            val saved = owner.recoveryState()
            val restored = ReservedMatchingState(owner.configuration).also { it.restore(saved) }
            assertTrue(restored.restingOrders(f.a.marketId).isEmpty())
            assertEquals(MatchingIntakeStatus.DUPLICATE, restored.apply(cancel).status)
            restored.acknowledgeFundsSettled(ack(2))
            val clean = ReservedMatchingState(owner.configuration).also { it.restore(restored.recoveryState()) }
            assertEquals(MatchingIntakeStatus.PRUNED, clean.apply(cancel).status)
            val next = f.input(3, admission = 2).copy(marketAdmissionSequence = 2)
            assertEquals(MatchingIntakeStatus.APPLIED, clean.apply(next).status)
        }
    }

    @Test
    fun `순번과 byte가 맞아도 주문이 존재할 prefix의 허위 취소 거절은 복원하지 않는다`() {
        val owner = f.owner()
        val buy = f.input(1)
        owner.apply(buy)
        val real = assertNotNull(owner.apply(cancel(buy, 2)).result)
        val forged =
            MatchingResultEnvelope(
                real.stream,
                real.resultSequence,
                real.input,
                MatchingOutcome.CANCEL_REJECTED,
                null,
                real.engineSequenceBefore,
                real.engineSequenceAfter,
                listOf(OrderCancelRejected(f.a.marketId, 2, buy.order.orderId, buy.order.userId, "order not found")),
                emptyList(),
            )
        val saved = owner.recoveryState()
        val first = saved.streams.first()
        val replacement = listOf(first.outstanding.first(), forged)
        val bytes = replacement.sumOf { it.canonicalBytes }
        val stream =
            MatchingStreamRecovery(
                first.stream,
                first.appliedThrough,
                first.lastSourceAdmissionSequence,
                first.resultIssued,
                first.fundsSettledThrough,
                first.settledEngineSequence,
                replacement,
                bytes,
                first.activeOrders,
                first.activeBytes,
                first.lastSourceMarketAdmissionSequence,
            )
        val broken =
            MatchingRoleRecoveryState(
                saved.configuration,
                saved.lifecycle,
                saved.matching,
                saved.active,
                listOf(stream) + saved.streams.drop(1),
                saved.resultEntries,
                bytes,
                saved.activeOrders,
                saved.activeBytes,
            )
        val restored = ReservedMatchingState(owner.configuration)
        assertFailsWith<IllegalArgumentException> { restored.restore(broken) }
        assertTrue(restored.isBlocked)
    }

    @Test
    fun `취소 뒤 새 신규 승인 번호를 입력 번호로 바꾸거나 건너뛰면 거절한다`() {
        val owner = f.owner()
        val buy = f.input(1)
        owner.apply(buy)
        owner.apply(cancel(buy, 2))
        val next = f.input(3, admission = 2).copy(marketAdmissionSequence = 2)
        assertEquals(MatchingIntakeStatus.INVALID_INPUT, owner.apply(next.copy(marketAdmissionSequence = 3)).status)
        assertEquals(MatchingIntakeStatus.INVALID_INPUT, owner.apply(next.copy(marketAdmissionSequence = 1)).status)
        assertEquals(2L, owner.lastEngineSequence(f.a.marketId))
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(next).status)
        assertEquals(
            2L,
            owner
                .recoveryState()
                .streams
                .first()
                .lastSourceMarketAdmissionSequence,
        )
    }

    @Test
    fun `최대 ID와 순번의 취소 성공 거절 전체 결과를 단일건 한도 안에 보존한다`() {
        val original = f.input(Long.MAX_VALUE - 1, admission = Long.MAX_VALUE, user = "u".repeat(256))
        val input = cancel(original, Long.MAX_VALUE)
        val success =
            MatchingResultEnvelope(
                f.configuration().resultStream(f.a.marketId),
                Long.MAX_VALUE,
                input,
                MatchingOutcome.CANCELLED,
                null,
                Long.MAX_VALUE - 1,
                Long.MAX_VALUE,
                listOf(OrderCancelled(f.a.marketId, Long.MAX_VALUE, original.order.orderId, original.order.userId, Quantity(3))),
                listOf(MatchingOrderEffect(original, Quantity(3), Quantity.ZERO, MatchingOrderDisposition.CANCELLED)),
            )
        // 원본946 + 취소추가336 + effect966 + 사건329 + 공통161 = 2738.
        assertEquals(2_738L, success.canonicalBytes)
        val config = f.configuration(f.limits(resultBytes = 2_738, maxSingleResultBytes = 2_738))
        MatchingInputValidation.requireResult(config, success)
        val rejected =
            MatchingResultEnvelope(
                success.stream,
                Long.MAX_VALUE,
                input,
                MatchingOutcome.CANCEL_REJECTED,
                null,
                Long.MAX_VALUE - 1,
                Long.MAX_VALUE,
                listOf(OrderCancelRejected(f.a.marketId, Long.MAX_VALUE, original.order.orderId, original.order.userId, "order not found")),
                emptyList(),
            )
        // 사건 고정12 + market11 + order38 + user260 + 사유(길이4 + ASCII15) = 340.
        // effect가 없으므로 원본1282 + 공통161 + 사건340 = 1783.
        assertEquals(1_783L, rejected.canonicalBytes)
        MatchingInputValidation.requireResult(config, rejected)
        assertFailsWith<IllegalArgumentException> {
            f.configuration(f.limits(resultBytes = 2_738, maxSingleResultBytes = 2_737))
        }
    }

    private fun cancel(
        original: FundsOrderDispatch,
        sequence: Long,
    ) = original.copy(
        dispatchSequence = sequence,
        cancel =
            FundsCancelDispatch(
                TradingIdentifiers.cancel(f.funds, original.context.ordinal, sequence),
                RequestToken(original.order.userId, 0, 1, sequence),
            ),
    )

    private fun ack(sequence: Long) = MatchingFundsSettledAck(f.configuration().resultStream(f.a.marketId), sequence, f.contexts[0])
}
