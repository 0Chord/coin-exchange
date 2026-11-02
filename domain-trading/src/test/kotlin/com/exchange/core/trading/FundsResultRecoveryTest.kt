package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.Quantity
import com.exchange.core.ledger.Balance
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FundsResultRecoveryTest {
    @Test
    fun `부분 정산을 복원한 뒤 같은 결과와 다음 maker 체결을 이어 처리한다`() {
        val f = FundsResultTestFixture()
        val original = f.owner()
        val matching = f.matching()
        f.fund(original, f.buyer, f.quote, 1000)
        f.fund(original, f.seller, f.a.baseAssetId, 2)
        f.apply(original, f.match(matching, f.admit(original, 1, f.seller, Side.SELL, 90)))
        val partial = f.match(matching, f.admit(original, 1, quantity = 2))
        f.apply(original, partial)
        val saved = original.fundsRecoveryState()

        val restored = f.owner(false).also { it.restoreFundsRecoveryState(saved, f.calculator) }

        f.assertSame(saved, restored.fundsRecoveryState())
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(restored, partial).status)
        f.assertSame(saved, restored.fundsRecoveryState())
        val next = f.admit(original, 2, f.seller, Side.SELL, 95)
        val sameNext = f.admit(restored, 2, f.seller, Side.SELL, 95)
        assertEquals(next, sameNext)
        val finished = f.match(matching, next)
        assertEquals(FundsResultStatus.APPLIED, f.apply(original, finished).status)
        assertEquals(FundsResultStatus.APPLIED, f.apply(restored, finished).status)
        f.assertSame(original.fundsRecoveryState(), restored.fundsRecoveryState())
        f.assertBalance(restored, f.buyer, f.quote, 810)
        f.assertBalance(restored, f.buyer, f.a.baseAssetId, 2)
        f.assertBalance(restored, f.seller, f.quote, 190)
        f.assertConserved(restored)
    }

    @Test
    fun `정산이 적용 ACK보다 먼저 와 종료된 주문은 복원 뒤에도 되살리지 않는다`() {
        val f = FundsResultTestFixture()
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        f.fund(owner, f.seller, f.a.baseAssetId, 1)
        f.apply(owner, f.match(matching, f.admit(owner, 1, f.seller, Side.SELL)))
        val input = f.admit(owner, 1)
        val result = f.match(matching, input)
        f.apply(owner, result)
        val saved = owner.fundsRecoveryState()
        assertTrue(saved.financial.liveOrders.isEmpty())
        assertEquals(0, saved.dispatch.dispatchEntries)
        val restored = f.owner(false).also { it.restoreFundsRecoveryState(saved, f.calculator) }

        restored.acknowledgeMatchingApplied(FundsMatchingAppliedAck(input.stream, 2, input.context))
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(restored, result).status)

        f.assertSame(saved, restored.fundsRecoveryState())
        f.assertBalance(restored, f.buyer, f.quote, 900)
        assertEquals(2L, restored.matchingSettlementAck(result.stream)!!.through)
    }

    @Test
    fun `최종 거절 반환 뒤 복원과 늦은 적용 ACK에도 추가 반환은 없다`() {
        val f = FundsResultTestFixture()
        val owner = f.owner()
        f.fund(owner, f.buyer, f.quote, 1000)
        val input = f.admit(owner, 1, price = 300)
        val result =
            MatchingResultEnvelope(
                f.matchingConfiguration.resultStream(f.a.marketId),
                1,
                input,
                MatchingOutcome.NOT_EXECUTED,
                MatchingNotExecutedReason.ACTIVE_ORDER_LIMIT,
                0,
                0,
                emptyList(),
                listOf(MatchingOrderEffect(input, Quantity(1), Quantity(1), MatchingOrderDisposition.NOT_EXECUTED)),
            )
        assertEquals(FundsResultStatus.APPLIED, f.apply(owner, result).status)
        val saved = owner.fundsRecoveryState()
        assertTrue(saved.financial.liveOrders.isEmpty())
        assertEquals(0, saved.dispatch.dispatchEntries)
        assertEquals(0, saved.dispatch.activeOrders)
        val restored = f.owner(false).also { it.restoreFundsRecoveryState(saved, f.calculator) }

        val repeated = f.apply(restored, result, f.at.plusSeconds(99))
        val lateAck = restored.acknowledgeMatchingApplied(FundsMatchingAppliedAck(input.stream, 1, input.context))

        assertEquals(FundsResultStatus.DUPLICATE, repeated.status)
        assertEquals(1L, repeated.ack!!.through)
        assertEquals(FundsDispatchAckStatus.ACKNOWLEDGED, lateAck.status)
        f.assertSame(saved, restored.fundsRecoveryState())
        f.assertBalance(restored, f.buyer, f.quote, 1000)
        assertEquals(
            0L,
            restored
                .fundsRecoveryState()
                .results!!
                .streams
                .first()
                .settledEngineSequence,
        )
        f.assertConserved(restored)
    }

    @Test
    fun `결과 원문 창을 회수한 뒤 복원해도 과거 결과는 재정산하지 않는다`() {
        val f = FundsResultTestFixture(resultLimits = FundsResultLimits(maxReceiptEntries = 2, maxMarketReceiptEntries = 2))
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        val results =
            (1L..3L).map { request ->
                f
                    .match(
                        matching,
                        f.admit(owner, request, price = 10),
                    ).also { assertEquals(FundsResultStatus.APPLIED, f.apply(owner, it).status) }
            }
        val saved = owner.fundsRecoveryState()
        val progress = saved.results!!.streams.first()
        assertEquals(3L, progress.resultThrough)
        assertEquals(1L, progress.retiredThrough)
        assertEquals(1L, progress.retiredEngineSequence)
        assertEquals(2, progress.receiptEntries)
        assertEquals(listOf(2L, 3L), saved.results!!.receipts.map { it.result.resultSequence })
        val restored = f.owner(false).also { it.restoreFundsRecoveryState(saved, f.calculator) }

        val old = f.apply(restored, results.first(), f.at.plusSeconds(100))

        assertEquals(FundsResultStatus.PRUNED, old.status)
        assertEquals(3L, old.ack!!.through)
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(restored, results.last()).status)
        f.assertSame(saved, restored.fundsRecoveryState())
        f.assertBalance(restored, f.buyer, f.quote, 970, 30)
        f.assertConserved(restored)
    }

    @Test
    fun `공통 receipt 한도는 마켓별 prefix를 유지하며 복원 뒤 같은 FIFO로 회수한다`() {
        val f = FundsResultTestFixture(resultLimits = FundsResultLimits(maxReceiptEntries = 2, maxMarketReceiptEntries = 2))
        val original = f.owner()
        val matching = f.matching()
        f.fund(original, f.buyer, f.quote, 1000)
        val a1 = f.match(matching, f.admit(original, 1, price = 10))
        f.apply(original, a1)
        val b1 = f.match(matching, f.admit(original, 2, price = 10, market = f.b))
        f.apply(original, b1)
        val a2 = f.match(matching, f.admit(original, 3, price = 10))
        f.apply(original, a2)
        val saved = original.fundsRecoveryState()
        assertEquals(listOf(f.b.marketId, f.a.marketId), saved.results!!.receipts.map { it.result.stream.marketId })
        assertEquals(
            1L,
            saved.results!!
                .streams
                .first()
                .retiredThrough,
        )
        assertEquals(
            0L,
            saved.results!!
                .streams
                .last()
                .retiredThrough,
        )
        val restored = f.owner(false).also { it.restoreFundsRecoveryState(saved, f.calculator) }
        val next = f.admit(original, 4, price = 10, market = f.b)
        assertEquals(next, f.admit(restored, 4, price = 10, market = f.b))
        val b2 = f.match(matching, next)

        f.apply(original, b2)
        f.apply(restored, b2)

        f.assertSame(original.fundsRecoveryState(), restored.fundsRecoveryState())
        assertEquals(FundsResultStatus.PRUNED, f.apply(restored, b1).status)
        assertEquals(FundsResultStatus.PRUNED, f.apply(restored, a1).status)
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(restored, a2).status)
        f.assertBalance(restored, f.buyer, f.quote, 960, 40)
        f.assertConserved(restored)
    }

    @Test
    fun `receipt byte 한도로 회수해도 다음 정산은 BUSY 없이 진행한다`() {
        val probe = FundsResultTestFixture()
        val probeFunds = probe.owner()
        probe.fund(probeFunds, probe.buyer, probe.quote, 1000)
        val firstBytes = probe.match(probe.matching(), probe.admit(probeFunds, 1)).canonicalBytes
        val f =
            FundsResultTestFixture(
                resultLimits =
                    FundsResultLimits(
                        maxSingleResultBytes = firstBytes,
                        maxReceiptBytes = firstBytes * 2 - 1,
                        maxMarketReceiptBytes = firstBytes * 2 - 1,
                    ),
            )
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        val first = f.match(matching, f.admit(owner, 1))
        assertEquals(FundsResultStatus.APPLIED, f.apply(owner, first).status)
        val second = f.match(matching, f.admit(owner, 2))

        assertEquals(FundsResultStatus.APPLIED, f.apply(owner, second).status)

        val saved = owner.fundsRecoveryState()
        assertEquals(1, saved.results!!.receipts.size)
        assertTrue(saved.results!!.receiptBytes <= f.resultLimits.maxReceiptBytes)
        assertEquals(
            2L,
            saved.results!!
                .streams
                .first()
                .resultThrough,
        )
        assertEquals(FundsResultStatus.PRUNED, f.apply(owner, first).status)
        f.assertBalance(owner, f.buyer, f.quote, 800, 200)
    }

    @Test
    fun `정상 checksum과 별개인 정산 prefix receipt 구멍 engine 경계 손상은 거절한다`() {
        val f = FundsResultTestFixture()
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        for (request in 1L..2L) f.apply(owner, f.match(matching, f.admit(owner, request, price = 10)))
        val saved = owner.fundsRecoveryState()
        val results = saved.results!!
        val first = results.streams.first()
        val badResults =
            listOf(
                replace(results, streams = listOf(first.copy(resultThrough = 3)) + results.streams.drop(1)),
                replace(results, streams = listOf(first.copy(retiredThrough = 1)) + results.streams.drop(1)),
                replace(results, streams = listOf(first.copy(settledEngineSequence = 3)) + results.streams.drop(1)),
                replace(results, streams = listOf(first.copy(retiredEngineSequence = 1)) + results.streams.drop(1)),
                replace(results, receipts = results.receipts.drop(1)),
                replace(results, receipts = results.receipts + results.receipts.last()),
                replace(results, receiptBytes = results.receiptBytes + 1),
                replace(results, streams = results.streams.drop(1)),
            )
        for (invalid in badResults) assertRestoreRejected(f, replace(saved, results = invalid))
    }

    @Test
    fun `hold 공급 총량과 활성 카운터 손상은 정산 복원에서 거절한다`() {
        val f = FundsResultTestFixture()
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        f.apply(owner, f.match(matching, f.admit(owner, 1, price = 300)))
        val saved = owner.fundsRecoveryState()
        val balances = saved.financial.balances.map { if (it.assetId == f.quote) it.copy(hold = Amount(301)) else it }
        assertRestoreRejected(f, replace(saved, financial = financial(saved.financial, balances = balances)))
        assertRestoreRejected(f, replace(saved, totals = saved.assetTotals + (f.quote to 1001L)))
        val delivery = saved.dispatch
        assertRestoreRejected(
            f,
            replace(
                saved,
                dispatch =
                    FundsDispatchSnapshot(
                        delivery.streams,
                        delivery.dispatchEntries,
                        delivery.dispatchBytes,
                        delivery.activeOrders + 1,
                        delivery.activeBytes,
                    ),
            ),
        )
    }

    @Test
    fun `보존한 maker의 중간 효과가 앞뒤 잔량과 이어지지 않으면 복원을 거절한다`() {
        val f = FundsResultTestFixture()
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        f.fund(owner, f.seller, f.a.baseAssetId, 2)
        f.apply(owner, f.match(matching, f.admit(owner, 1, quantity = 3)))
        f.apply(owner, f.match(matching, f.admit(owner, 1, f.seller, Side.SELL, 95)))
        f.apply(owner, f.match(matching, f.admit(owner, 2, f.seller, Side.SELL, 95)))
        val saved = owner.fundsRecoveryState()
        val results = saved.results!!
        val middle = results.receipts[1]
        val forged =
            f.changed(
                middle.result,
                effects =
                    middle.result.effects.mapIndexed { index, effect ->
                        if (index == 1) effect.copy(beforeQuantity = Quantity(2), afterQuantity = Quantity(1)) else effect
                    },
            )
        // 각 묶음의 체결1·잔량 감소1 모양은 맞지만 앞 결과의 잔량3에서 이어지지 않는다.
        val broken = replace(results, receipts = listOf(results.receipts[0], middle.copy(result = forged), results.receipts[2]))

        assertRestoreRejected(f, replace(saved, results = broken))
    }

    @Test
    fun `마지막 결과 순번 자체는 정산하고 이후 과거 ACK와 중복은 계속 제공한다`() {
        val f = FundsResultTestFixture()
        val owner = f.owner()
        f.fund(owner, f.buyer, f.quote, 1000)
        val original = f.admit(owner, 1)
        val saved = owner.fundsRecoveryState()
        val orderId = TradingIdentifiers.order(f.manifest.lineage, Long.MAX_VALUE)
        val lastInput =
            original.copy(
                dispatchSequence = Long.MAX_VALUE,
                admissionSequence = Long.MAX_VALUE,
                marketAdmissionSequence = Long.MAX_VALUE,
                order = original.order.copy(orderId = orderId),
                reservation = original.reservation.copy(orderId = orderId),
            )
        val live = TradingLiveOrderSnapshot(Long.MAX_VALUE, Long.MAX_VALUE, lastInput.order, lastInput.reservation, LiveOrderPhase.PENDING)
        val delivery = saved.dispatch
        val firstStream = delivery.streams.first()
        val dispatch =
            FundsDispatchSnapshot(
                listOf(
                    FundsDispatchStreamSnapshot(
                        firstStream.stream,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE - 1,
                        listOf(lastInput),
                        firstStream.dispatchBytes,
                        firstStream.activeOrders,
                        firstStream.activeBytes,
                    ),
                ) + delivery.streams.drop(1),
                delivery.dispatchEntries,
                delivery.dispatchBytes,
                delivery.activeOrders,
                delivery.activeBytes,
            )
        val results = saved.results!!
        val frontier = results.streams.first().copy(resultThrough = Long.MAX_VALUE - 1, retiredThrough = Long.MAX_VALUE - 1)
        val advanced =
            replace(
                saved,
                financial =
                    financial(
                        saved.financial,
                        live = listOf(live),
                        markets =
                            listOf(
                                saved.financial.markets.first().copy(
                                    lastAssigned = Long.MAX_VALUE,
                                    executedThrough = Long.MAX_VALUE - 1,
                                    pendingAdmissionSequences = listOf(Long.MAX_VALUE),
                                ),
                            ) + saved.financial.markets.drop(1),
                        lastAdmission = Long.MAX_VALUE,
                    ),
                dispatch = dispatch,
                results = replace(results, streams = listOf(frontier) + results.streams.drop(1)),
            )
        val restored = f.owner(false).also { it.restoreFundsRecoveryState(advanced, f.calculator) }
        val result =
            MatchingResultEnvelope(
                frontier.stream,
                Long.MAX_VALUE,
                lastInput,
                MatchingOutcome.NOT_EXECUTED,
                MatchingNotExecutedReason.ACTIVE_ORDER_LIMIT,
                0,
                0,
                emptyList(),
                listOf(MatchingOrderEffect(lastInput, Quantity(1), Quantity(1), MatchingOrderDisposition.NOT_EXECUTED)),
            )

        assertEquals(FundsResultStatus.APPLIED, f.apply(restored, result).status)

        assertEquals(Long.MAX_VALUE, restored.matchingSettlementAck(result.stream)!!.through)
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(restored, result).status)
        val old =
            f.changed(
                result,
                sequence = 1,
                input = original,
                effects = listOf(MatchingOrderEffect(original, Quantity(1), Quantity(1), MatchingOrderDisposition.NOT_EXECUTED)),
            )
        assertEquals(FundsResultStatus.PRUNED, f.apply(restored, old).status)
        f.assertBalance(restored, f.buyer, f.quote, 1000)
        val final = restored.fundsRecoveryState()
        assertEquals(
            Long.MAX_VALUE,
            final.results!!
                .streams
                .first()
                .resultThrough,
        )
        f.assertSame(final, f.owner(false).also { it.restoreFundsRecoveryState(final, f.calculator) }.fundsRecoveryState())
        f.assertConserved(restored)
    }

    @Test
    fun `신규 접수가 포화돼도 최종 거절 정산으로 공간을 회수한다`() {
        val limits =
            TradingResourceLimits(
                maxActiveOrders = 1,
                maxPendingOrders = 1,
                maxMarketActiveOrders = 1,
                maxMarketPendingOrders = 1,
            )
        val f = FundsResultTestFixture(limits = limits)
        val owner = f.owner()
        f.fund(owner, f.buyer, f.quote, 1000)
        val first = f.admit(owner, 1, price = 300)
        val intent =
            OrderIntent(f.a.marketId, 7, Side.BUY, first.order.orderType, first.order.timeInForce, first.order.price, first.order.quantity)
        assertEquals(AdmissionReply.Refused("RETRYABLE_CAPACITY"), owner.admit(RequestToken(f.buyer, 0, 1, 2), intent, f.at))
        val rejected =
            MatchingResultEnvelope(
                f.matchingConfiguration.resultStream(f.a.marketId),
                1,
                first,
                MatchingOutcome.NOT_EXECUTED,
                MatchingNotExecutedReason.ACTIVE_ORDER_LIMIT,
                0,
                0,
                emptyList(),
                listOf(MatchingOrderEffect(first, Quantity(1), Quantity(1), MatchingOrderDisposition.NOT_EXECUTED)),
            )

        assertEquals(FundsResultStatus.APPLIED, f.apply(owner, rejected).status)

        val second = f.admit(owner, 2, price = 300)
        assertEquals(2L, second.dispatchSequence)
        f.assertBalance(owner, f.buyer, f.quote, 700, 300)
        assertEquals(1, owner.fundsRecoveryState().dispatch.activeOrders)
        assertFalse(owner.isBlocked)
    }

    private fun assertRestoreRejected(
        fixture: FundsResultTestFixture,
        state: FundsRoleRecoveryState,
    ) {
        val restored = fixture.owner(false)
        assertFailsWith<IllegalArgumentException> { restored.restoreFundsRecoveryState(state, fixture.calculator) }
        assertTrue(restored.isBlocked)
        assertFailsWith<IllegalStateException> { restored.fundsRecoveryState() }
        assertFailsWith<IllegalStateException> {
            restored.matchingSettlementAck(
                fixture.matchingConfiguration.resultStream(fixture.a.marketId),
            )
        }
    }

    private fun replace(
        source: FundsResultRecoveryState,
        streams: List<FundsResultStreamRecovery> = source.streams,
        receipts: List<FundsResultReceipt> = source.receipts,
        receiptBytes: Long = source.receiptBytes,
    ) = FundsResultRecoveryState(source.limits, streams, receipts, receiptBytes)

    private fun replace(
        source: FundsRoleRecoveryState,
        financial: BoundedFundingRecoveryState = source.financial,
        dispatch: FundsDispatchSnapshot = source.dispatch,
        totals: Map<com.exchange.core.common.AssetId, Long> = source.assetTotals,
        results: FundsResultRecoveryState? = source.results,
    ) = FundsRoleRecoveryState(source.configuration, financial, dispatch, totals, results)

    private fun financial(
        source: BoundedFundingRecoveryState,
        balances: List<Balance> = source.balances,
        live: List<TradingLiveOrderSnapshot> = source.liveOrders,
        markets: List<TradingMarketProgressSnapshot> = source.markets,
        lastAdmission: Long = source.lastAdmissionSequence,
    ) = BoundedFundingRecoveryState(
        source.manifest,
        source.lifecycle,
        balances,
        source.feeRevenueByAsset,
        source.usedBalances,
        source.preparations,
        source.accounts,
        live,
        markets,
        source.inbox,
        lastAdmission,
        source.lastPreparationSequence,
        source.preparationCanonicalBytes,
        source.incident,
    )
}
