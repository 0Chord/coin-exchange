package com.exchange.core.trading

import com.exchange.core.common.Quantity
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FundsResultValidationRegressionTest {
    @Test
    fun `보이는 첫 maker의 잔량을 남기고 다음 maker로 넘어간 결과는 거절한다`() {
        val f = FundsResultTestFixture()
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        f.fund(owner, f.seller, f.a.baseAssetId, 3)
        f.apply(owner, f.match(matching, f.admit(owner, 1, f.seller, Side.SELL, 90, 2)))
        val second = f.admit(owner, 2, f.seller, Side.SELL, 95, 1)
        f.apply(owner, f.match(matching, second))
        val normal = f.match(matching, f.admit(owner, 1, quantity = 2))
        val before = owner.fundsRecoveryState()
        val control = f.owner(false).also { it.restoreFundsRecoveryState(before, f.calculator) }
        assertEquals(FundsResultStatus.APPLIED, f.apply(control, normal).status)
        f.assertBalance(control, f.buyer, f.quote, 820)
        val trade = normal.events.single() as TradeExecuted
        val forged =
            f.changed(
                normal,
                after = normal.engineSequenceAfter + 1,
                events =
                    listOf(
                        trade.copy(quantity = Quantity(1)),
                        trade.copy(
                            engineSequence = trade.engineSequence + 1,
                            makerOrderId = second.order.orderId,
                            price = second.order.price,
                            quantity = Quantity(1),
                        ),
                    ),
                effects =
                    listOf(
                        normal.effects[0],
                        normal.effects[1].copy(afterQuantity = Quantity(1), disposition = MatchingOrderDisposition.RESTING),
                        MatchingOrderEffect(second, Quantity(1), Quantity(0), MatchingOrderDisposition.TERMINAL),
                    ),
            )

        val reply = f.apply(owner, forged)

        assertEquals(FundsResultStatus.INVALID_RESULT, reply.status)
        assertNull(reply.ack)
        f.assertSame(before, owner.fundsRecoveryState())
        // taker가 먼저 소진되어 마지막 maker에 잔량을 남기는 정상 부분 체결은 허용한다.
        val partial = FundsResultTestFixture()
        val partialOwner = partial.owner()
        val partialMatching = partial.matching()
        partial.fund(partialOwner, partial.buyer, partial.quote, 1000)
        partial.fund(partialOwner, partial.seller, partial.a.baseAssetId, 3)
        partial.apply(partialOwner, partial.match(partialMatching, partial.admit(partialOwner, 1, partial.seller, Side.SELL, 90, 3)))
        val result = partial.match(partialMatching, partial.admit(partialOwner, 1, quantity = 2))
        assertEquals(FundsResultStatus.APPLIED, partial.apply(partialOwner, result).status)
        assertEquals(
            Quantity(1),
            partialOwner
                .fundsRecoveryState()
                .financial.liveOrders
                .single()
                .reservation.remainingQuantity,
        )
        partial.assertBalance(partialOwner, partial.buyer, partial.quote, 820)
    }

    @Test
    fun `복원한 활성 maker의 최초 승인 순번은 로컬 승인과 같아야 한다`() {
        val f = FundsResultTestFixture(resultLimits = FundsResultLimits(maxReceiptEntries = 1, maxMarketReceiptEntries = 1))
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        f.fund(owner, f.seller, f.a.baseAssetId, 3)
        f.apply(owner, f.match(matching, f.admit(owner, 1, market = f.b, price = 10)))
        f.apply(owner, f.match(matching, f.admit(owner, 1, f.seller, Side.SELL, 90, 3)))
        f.apply(owner, f.match(matching, f.admit(owner, 2)))
        val result = f.match(matching, f.admit(owner, 3))
        f.apply(owner, result)
        val saved = owner.fundsRecoveryState()
        val control = f.owner(false).also { it.restoreFundsRecoveryState(saved, f.calculator) }
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(control, result).status)
        val retained = saved.results!!
        val receipt = retained.receipts.single()
        val maker = receipt.result.effects[1]
        assertEquals(1L, maker.origin.dispatchSequence)
        assertEquals(2L, maker.origin.admissionSequence)
        val altered =
            f.changed(
                receipt.result,
                effects =
                    listOf(
                        receipt.result.effects[0],
                        maker.copy(origin = maker.origin.copy(dispatchSequence = 2, marketAdmissionSequence = 2)),
                    ),
            )
        val damaged =
            FundsRoleRecoveryState(
                saved.configuration,
                saved.financial,
                saved.dispatch,
                saved.assetTotals,
                FundsResultRecoveryState(retained.limits, retained.streams, listOf(receipt.copy(result = altered)), retained.receiptBytes),
            )

        assertFailsWith<IllegalArgumentException> { f.owner(false).restoreFundsRecoveryState(damaged, f.calculator) }
    }
}
