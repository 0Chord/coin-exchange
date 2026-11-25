package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CancellationCoreValidationTest {
    @Test
    fun `C06 대상 소유자 마켓 설정 거절은 금융과 업무 카운터를 움직이지 않는다`() {
        val f = CancellationValidationFixture()
        f.fund(f.buyer, f.quote, 1_000)
        val target = f.admit(1)
        f.funds.rotateEpoch(f.seller, 0, 0)
        val before = f.saved()
        val policies =
            listOf(
                RequestToken(f.seller, 0, 1, 1) to CancelIntent(f.market, target.order.orderId, 7),
                RequestToken(f.buyer, 0, 1, 2) to CancelIntent(f.market, OrderId("missing-order"), 7),
                RequestToken(f.buyer, 0, 1, 3) to CancelIntent(f.otherMarket, target.order.orderId, 7),
            )
        for ((token, intent) in policies) {
            val rejected = assertIs<CancelReply.Recorded>(f.funds.admitCancel(token, intent))
            assertEquals(CancelCode.REJECTED, rejected.receipt.code)
            assertEquals("NOT_ACTIVE", rejected.receipt.reason)
            f.assertBusinessUnchanged(before)
        }
        val contexts =
            listOf(
                CancelIntent(MarketId("UNKNOWN"), target.order.orderId, 7),
                CancelIntent(f.market, target.order.orderId, 8),
            )
        for ((offset, intent) in contexts.withIndex()) {
            val token = RequestToken(f.buyer, 0, 1, 4L + offset)
            assertEquals("EXECUTION_CONTEXT_MISMATCH", assertIs<CancelReply.Refused>(f.funds.admitCancel(token, intent)).reason)
            f.assertBusinessUnchanged(before)
        }
        val accepted = assertIs<CancelReply.Recorded>(f.cancel(6, target))
        assertEquals(CancelCode.ACCEPTED, accepted.receipt.code)
        assertEquals(2L, accepted.receipt.inputSequence)
        assertEquals(1L, f.saved().lastCancellationSequence)
        f.assertBalance(f.buyer, f.quote, 700, 300)
    }

    @Test
    fun `C06 초기화 전과 차단 소유자는 취소 업무를 시작하지 않는다`() {
        val f = CancellationValidationFixture()
        val intent = CancelIntent(f.market, OrderId("unavailable"), 7)
        val token = RequestToken(f.buyer, 0, 1, 1)
        val uninitialized = f.emptyOwner()
        assertFailsWith<IllegalStateException> { uninitialized.admitCancel(token, intent) }
        assertEquals(TradingLifecycle.UNINITIALIZED, uninitialized.lifecycle)
        assertFalse(uninitialized.isBlocked)
        f.funds.block()
        assertFailsWith<IllegalStateException> { f.funds.admitCancel(token, intent) }
        assertTrue(f.funds.isBlocked)
    }

    @Test
    fun `C14 취소 결과의 수량 사건 원본 취소 ID 결과 순번 변조는 무변경 거절 뒤 정상 결과를 허용한다`() {
        val changes: List<Pair<String, (MatchingResultEnvelope) -> MatchingResultEnvelope>> =
            listOf(
                "financial remaining quantity" to { result ->
                    val event = assertIs<OrderCancelled>(result.events.single())
                    changedResult(
                        result,
                        events = listOf(event.copy(remainingQuantity = Quantity(2))),
                        effects = listOf(result.effects.single().copy(beforeQuantity = Quantity(2))),
                    )
                },
                "event owner" to { result ->
                    val event = assertIs<OrderCancelled>(result.events.single())
                    changedResult(result, events = listOf(event.copy(userId = UserId("other-owner"))))
                },
                "event target" to { result ->
                    val event = assertIs<OrderCancelled>(result.events.single())
                    changedResult(result, events = listOf(event.copy(orderId = OrderId("other-order"))))
                },
                "original approval identity" to { result ->
                    val effect = result.effects.single()
                    val id = TradingIdentifiers.order(result.input.stream.fundsLineage, 2)
                    val altered =
                        effect.origin.copy(
                            admissionSequence = 2,
                            order = effect.origin.order.copy(orderId = id),
                            reservation = effect.origin.reservation.copy(orderId = id),
                        )
                    changedResult(result, effects = listOf(effect.copy(origin = altered)))
                },
                "cancel identity" to { result ->
                    val cancel = assertNotNull(result.input.cancel)
                    val altered =
                        cancel.copy(
                            cancelId = TradingIdentifiers.cancel(result.input.stream.fundsLineage, 2),
                            cancelSequence = 2,
                        )
                    changedResult(result, input = result.input.copy(cancel = altered))
                },
                "result identity sequence" to { result -> changedResult(result, sequence = 3) },
                "engine boundary" to { result ->
                    val event = assertIs<OrderCancelled>(result.events.single())
                    changedResult(result, before = 0, after = 1, events = listOf(event.copy(engineSequence = 1)))
                },
                "no target while funds remain active" to { result ->
                    changedResult(
                        result,
                        outcome = MatchingOutcome.CANCEL_REJECTED,
                        events =
                            listOf(
                                OrderCancelRejected(
                                    result.stream.marketId,
                                    2,
                                    result.input.order.orderId,
                                    result.input.order.userId,
                                    "order not found",
                                ),
                            ),
                        effects = emptyList(),
                    )
                },
            )
        for ((name, change) in changes) {
            val f = CancellationValidationFixture()
            val valid = f.validCancellation()
            val before = f.saved()
            val reply = f.apply(change(valid))
            assertEquals(FundsResultStatus.INVALID_RESULT, reply.status, name)
            f.assertUnchanged(before)
            assertFalse(f.funds.isBlocked, name)
            f.assertBalance(f.buyer, f.quote, 700, 300)
            assertEquals(1L, f.resultThrough())
            assertEquals(FundsResultStatus.APPLIED, f.apply(valid).status, name)
            f.assertBalance(f.buyer, f.quote, 1_000)
            assertEquals(2L, f.resultThrough())
            f.assertConserved()
        }
    }

    @Test
    fun `C19 사용자와 적용 ACK로 payload를 회수해도 보호 취소 의무를 누락한 복원은 거절한다`() {
        val f = CancellationValidationFixture()
        val valid = f.validCancellation()
        val ack = FundsMatchingAppliedAck(valid.input.stream, 2, valid.input.context)
        assertEquals(FundsDispatchAckStatus.ACKNOWLEDGED, f.funds.acknowledgeMatchingApplied(ack).status)
        assertEquals(AckStatus.ACKNOWLEDGED, f.funds.acknowledgeReceipts(f.buyer, 0, 1, 2).status)
        val saved = f.saved()
        assertEquals(0, saved.dispatch.dispatchEntries)
        assertTrue(
            saved.financial.inbox.channels
                .single()
                .cancelEntries
                .isEmpty(),
        )
        assertEquals(1, saved.cancellations.size)
        val restored = f.emptyOwner().also { it.restoreFundsRecoveryState(saved, f.calculator) }
        assertEquals(FundsResultStatus.APPLIED, restored.applyMatchingResult(valid, f.at, f.calculator).status)
        f.assertRestoreRejected(copyRecovery(saved, cancellations = emptyList()))
        f.assertBalance(f.buyer, f.quote, 700, 300)
        assertFalse(f.funds.isBlocked)
    }

    @Test
    fun `C19 서로 다른 취소 ID와 입력 순번이어도 같은 대상의 보호 작업 둘은 복원하지 않는다`() {
        val f = CancellationValidationFixture()
        f.fund(f.buyer, f.quote, 1_000)
        val first = f.admit(1)
        val second = f.admit(2, quantity = 1, price = 50)
        assertEquals(CancelCode.ACCEPTED, assertIs<CancelReply.Recorded>(f.cancel(3, first)).receipt.code)
        assertEquals(CancelCode.ACCEPTED, assertIs<CancelReply.Recorded>(f.cancel(4, second)).receipt.code)
        val saved = f.saved()
        f.emptyOwner().restoreFundsRecoveryState(saved, f.calculator)
        val firstCancel = saved.cancellations[0]
        val secondCancel = saved.cancellations[1]
        val identity = assertNotNull(secondCancel.input.cancel).copy(targetInputSequence = 1)
        val duplicate = secondCancel.copy(input = firstCancel.input.copy(dispatchSequence = 4, cancel = identity))
        val streams =
            saved.dispatch.streams.map { stream ->
                FundsDispatchStreamSnapshot(
                    stream.stream,
                    stream.issued,
                    stream.matchingAppliedThrough,
                    stream.outstanding.map { if (it.dispatchSequence == 4L) duplicate.input else it },
                    stream.dispatchBytes,
                    stream.activeOrders,
                    stream.activeBytes,
                )
            }
        val dispatch =
            FundsDispatchSnapshot(
                streams,
                saved.dispatch.dispatchEntries,
                saved.dispatch.dispatchBytes,
                saved.dispatch.activeOrders,
                saved.dispatch.activeBytes,
            )
        f.assertRestoreRejected(copyRecovery(saved, dispatch = dispatch, cancellations = listOf(firstCancel, duplicate)))
    }

    @Test
    fun `C19 선행 전량 체결의 terminal 예약은 주문 소유자 자산과 최초 값을 바꿔 복원할 수 없다`() {
        val f = CancellationValidationFixture()
        f.fund(f.buyer, f.quote, 1_000)
        f.fund(f.seller, f.base, 3)
        val target = f.admit(1)
        f.settle(f.match(target))
        val sell = f.admit(1, f.seller, Side.SELL)
        assertEquals(CancelCode.ACCEPTED, assertIs<CancelReply.Recorded>(f.cancel(2, target)).receipt.code)
        f.settle(f.match(sell))
        val saved = f.saved()
        val protected = saved.cancellations.single()
        val terminal = assertNotNull(protected.terminalReservation)
        assertEquals(OrderReservationStatus.SETTLED, terminal.status)
        assertEquals(Amount.ZERO, terminal.remainingAmount)
        f.emptyOwner().restoreFundsRecoveryState(saved, f.calculator)
        val corruptions =
            listOf(
                terminal.copy(orderId = OrderId("other-order")),
                terminal.copy(userId = f.seller),
                terminal.copy(assetId = f.base),
                terminal.copy(limitPrice = Price(99)),
                terminal.copy(reservedAmount = Amount(301)),
                terminal.copy(initialQuantity = Quantity(4)),
            )
        for (invalid in corruptions) {
            f.assertRestoreRejected(copyRecovery(saved, cancellations = listOf(protected.copy(terminalReservation = invalid))))
        }
    }

    @Test
    fun `C19 완료 뒤 응답을 ACK해도 미발급 취소 번호와 요청 토큰은 복원하지 않는다`() {
        val f = CancellationValidationFixture()
        val result = f.validCancellation()
        f.settle(result)
        assertEquals(AckStatus.ACKNOWLEDGED, f.funds.acknowledgeReceipts(f.buyer, 0, 1, 2).status)
        val saved = f.saved()
        f.emptyOwner().restoreFundsRecoveryState(saved, f.calculator)
        val identity = assertNotNull(result.input.cancel)
        val changes =
            listOf(
                identity.copy(cancelId = TradingIdentifiers.cancel(saved.configuration.manifest.lineage, 2), cancelSequence = 2),
                identity.copy(requestToken = identity.requestToken.copy(epoch = 2)),
                identity.copy(requestToken = identity.requestToken.copy(channel = 1)),
                identity.copy(requestToken = identity.requestToken.copy(sequence = 3)),
            )
        val results = assertNotNull(saved.results)
        assertEquals(1L, saved.lastCancellationSequence)
        for (change in changes) {
            val altered =
                results.receipts.map { receipt ->
                    if (!receipt.result.input.isCancellation) {
                        receipt
                    } else {
                        val input = receipt.result.input.copy(cancel = change)
                        FundsResultReceipt(changedResult(receipt.result, input = input), receipt.appliedAt)
                    }
                }
            f.assertRestoreRejected(
                copyRecovery(saved, results = FundsResultRecoveryState(results.limits, results.streams, altered, results.receiptBytes)),
            )
        }
        // 이전 세대의 정상 완료 결과는 현재 세대로 바꾸지 않고도 복원할 수 있다.
        f.funds.rotateEpoch(f.buyer, 0, 1)
        f.emptyOwner().restoreFundsRecoveryState(f.saved(), f.calculator)
        // 번호2가 비어 있어도 실제 접수한 번호3의 최초 응답이 있으면 정상 원본이다.
        val gap = CancellationValidationFixture()
        gap.fund(gap.buyer, gap.quote, 1_000)
        val target = gap.admit(1)
        gap.settle(gap.match(target))
        assertEquals(CancelCode.ACCEPTED, assertIs<CancelReply.Recorded>(gap.cancel(3, target)).receipt.code)
        gap.settle(
            gap.match(
                gap
                    .saved()
                    .cancellations
                    .single()
                    .input,
            ),
        )
        assertEquals(
            1L,
            gap
                .saved()
                .financial.inbox.channels
                .single()
                .processedThrough,
        )
        gap.emptyOwner().restoreFundsRecoveryState(gap.saved(), gap.calculator)
    }

    @Test
    fun `C19 완료 결과를 회수했어도 두 최초 취소 응답은 다른 발급 ID를 가져야 한다`() {
        for (capacity in listOf(1, 4_096)) {
            val f = CancellationValidationFixture(FundsResultLimits(maxReceiptEntries = capacity, maxMarketReceiptEntries = capacity))
            f.fund(f.buyer, f.quote, 1_000)
            for (sequence in listOf(1L, 3L)) {
                val target = f.admit(sequence)
                f.settle(f.match(target))
                assertEquals(CancelCode.ACCEPTED, assertIs<CancelReply.Recorded>(f.cancel(sequence + 1, target)).receipt.code)
                f.settle(
                    f.match(
                        f
                            .saved()
                            .cancellations
                            .single()
                            .input,
                    ),
                )
            }
            val saved = f.saved()
            f.emptyOwner().restoreFundsRecoveryState(saved, f.calculator)
            val financial = saved.financial
            val channels =
                financial.inbox.channels.map { channel ->
                    channel.copy(
                        cancelEntries =
                            channel.cancelEntries.map { entry ->
                                if (entry.sequence != 2L) {
                                    entry
                                } else {
                                    entry.copy(
                                        receipt =
                                            entry.receipt.copy(
                                                cancelId = TradingIdentifiers.cancel(saved.configuration.manifest.lineage, 2),
                                            ),
                                    )
                                }
                            },
                    )
                }
            val changedFinancial =
                BoundedFundingRecoveryState(
                    financial.manifest,
                    financial.lifecycle,
                    financial.balances,
                    financial.feeRevenueByAsset,
                    financial.usedBalances,
                    financial.preparations,
                    financial.accounts,
                    financial.liveOrders,
                    financial.markets,
                    TradingInboxSnapshot(channels),
                    financial.lastAdmissionSequence,
                    financial.lastPreparationSequence,
                    financial.preparationCanonicalBytes,
                    financial.incident,
                )
            f.assertRestoreRejected(copyRecovery(saved, financial = changedFinancial))
        }
    }

    @Test
    fun `C22 취소 ID는 음수 lineage와 Long 마지막 순번을 유일하게 보존하고 다른 lineage를 거절한다`() {
        val lineages = listOf(ClusterLineage(1, 2), ClusterLineage(-1, -2), ClusterLineage(Long.MIN_VALUE, Long.MAX_VALUE))
        val ids = mutableSetOf<String>()
        for (lineage in lineages) {
            for (sequence in listOf(1L, Long.MAX_VALUE)) {
                val id = TradingIdentifiers.cancel(lineage, sequence)
                assertEquals(40, id.length)
                assertTrue(id.startsWith("C2"))
                assertTrue(ids.add(id))
                assertEquals(sequence, TradingIdentifiers.cancelSequence(lineage, id))
                for (other in lineages.filter { it != lineage }) {
                    assertFailsWith<IllegalArgumentException> { TradingIdentifiers.cancelSequence(other, id) }
                }
            }
            assertFailsWith<IllegalArgumentException> { TradingIdentifiers.cancel(lineage, 0) }
            assertFailsWith<IllegalArgumentException> { TradingIdentifiers.cancel(lineage, -1) }
        }
        assertEquals(6, ids.size)
    }

    @Test
    fun `C22 복구 목록과 결과 사건 목록은 호출자 입력을 바꿔도 보존되는 독립 복사다`() {
        val f = CancellationValidationFixture()
        f.fund(f.buyer, f.quote, 1_000)
        val target = f.admit(1)
        assertEquals(CancelCode.ACCEPTED, assertIs<CancelReply.Recorded>(f.cancel(2, target)).receipt.code)
        val saved = f.saved()
        val approvals = saved.approvalInputs.toMutableList()
        val cancellations = saved.cancellations.toMutableList()
        val copy = copyRecovery(saved, approvals = approvals, cancellations = cancellations)
        approvals.clear()
        cancellations.clear()
        assertEquals(1, copy.approvalInputs.size)
        assertEquals(1, copy.cancellations.size)
        assertFailsWith<UnsupportedOperationException> { (copy.approvalInputs as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (copy.cancellations as MutableList).clear() }
        val restored = f.emptyOwner().also { it.restoreFundsRecoveryState(copy, f.calculator) }
        val first = f.match(target)
        assertEquals(FundsResultStatus.APPLIED, restored.applyMatchingResult(first, f.at, f.calculator).status)
        val valid = f.match(saved.cancellations.single().input)
        val events = valid.events.toMutableList()
        val effects = valid.effects.toMutableList()
        val result = changedResult(valid, events = events, effects = effects)
        events.clear()
        effects.clear()
        assertEquals(1, result.events.size)
        assertEquals(1, result.effects.size)
        assertEquals(FundsResultStatus.APPLIED, restored.applyMatchingResult(result, f.at, f.calculator).status)
        assertEquals(Amount(1_000), restored.currentBalance(f.buyer, f.quote).available)
        assertEquals(Amount.ZERO, restored.currentBalance(f.buyer, f.quote).hold)
    }

    @Test
    fun `C22 UTF8 256바이트 소유자의 실제 취소 원본과 반환도 보존 한도 안에서 처리한다`() {
        val f = CancellationValidationFixture()
        // 개시 잔고의 64 code point와 주문 원본의 UTF-8 256-byte 상한을 함께 만족한다.
        val user = UserId("😀".repeat(64))
        assertEquals(256, user.value.toByteArray(Charsets.UTF_8).size)
        f.fund(user, f.quote, 1_000)
        val target = f.admit(1, user)
        f.settle(f.match(target))
        assertEquals(CancelCode.ACCEPTED, assertIs<CancelReply.Recorded>(f.cancel(2, target)).receipt.code)
        f.settle(
            f.match(
                f
                    .saved()
                    .cancellations
                    .single()
                    .input,
            ),
        )
        f.assertBalance(user, f.quote, 1_000)
        assertTrue(f.saved().cancellations.isEmpty())
        assertEquals(0, f.saved().dispatch.activeOrders)
        f.assertConserved()
    }
}

private class CancellationValidationFixture(
    private val resultLimits: FundsResultLimits = FundsResultLimits(),
) {
    private val existing = FundsResultTestFixture(resultLimits = resultLimits)
    val buyer = existing.buyer
    val seller = existing.seller
    val quote = existing.quote
    val base = existing.a.baseAssetId
    val market = existing.a.marketId
    val otherMarket = existing.b.marketId
    val at = existing.at
    val calculator get() = existing.calculator
    private val configuration =
        FundsDispatchConfiguration(
            existing.topology,
            FundsDispatchLimits(
                existing.contexts.associate {
                    it.market.marketId to
                        MarketFundsDispatchLimits(maxDispatchBytes = 65_536, maxActiveBytes = 65_536)
                },
                maxDispatchBytes = 65_536,
                maxActiveBytes = 65_536,
            ),
            inputContractVersion = 2,
        )
    private val matchingConfiguration =
        MatchingIntakeConfiguration(
            existing.topology,
            TradingRoleDeclaration(
                TradingServiceRole.MATCHING,
                ClusterLineage(3, 4),
                7,
                existing.contexts,
                existing.contexts.associate {
                    it.market.marketId to
                        1L
                },
            ),
            inputContractVersion = 2,
        )
    val funds = emptyOwner().also { it.initialize(existing.manifest) }
    private val matching = ReservedMatchingState(matchingConfiguration).also { it.initialize() }
    private var preparation = 0

    fun emptyOwner() =
        BoundedFundingState(
            OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))),
            expectedManifest = existing.manifest,
            dispatchConfiguration = configuration,
            resultLimits = resultLimits,
        )

    fun fund(
        user: UserId,
        asset: AssetId,
        amount: Long,
    ) {
        funds.prepareDevelopmentBalance(OpeningBalance("seed-${preparation++}", user, asset, Amount(amount)), at)
    }

    fun admit(
        sequence: Long,
        user: UserId = buyer,
        side: Side = Side.BUY,
        quantity: Long = 3,
        price: Long = 100,
    ): FundsOrderDispatch {
        funds.rotateEpoch(user, 0, 0)
        val reply =
            assertIs<AdmissionReply.Recorded>(
                funds.admit(
                    RequestToken(user, 0, 1, sequence),
                    OrderIntent(market, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity)),
                    at,
                ),
            )
        assertEquals(AdmissionCode.ACCEPTED, reply.receipt.code)
        val issued =
            saved()
                .dispatch.streams
                .first()
                .issued
        return funds.pendingDispatch(configuration.stream(market), issued, 1, 65_536).entries.single()
    }

    fun cancel(
        sequence: Long,
        target: FundsOrderDispatch,
    ): CancelReply = funds.admitCancel(RequestToken(target.order.userId, 0, 1, sequence), CancelIntent(market, target.order.orderId, 7))

    fun match(input: FundsOrderDispatch): MatchingResultEnvelope {
        val applied = matching.apply(input)
        assertEquals(MatchingIntakeStatus.APPLIED, applied.status)
        return assertNotNull(applied.result)
    }

    fun apply(result: MatchingResultEnvelope): FundsResultReply = funds.applyMatchingResult(result, at, calculator)

    fun settle(result: MatchingResultEnvelope) = assertEquals(FundsResultStatus.APPLIED, apply(result).status)

    fun saved(): FundsRoleRecoveryState = funds.fundsRecoveryState()

    fun resultThrough() = assertNotNull(saved().results).streams.first().resultThrough

    fun validCancellation(): MatchingResultEnvelope {
        fund(buyer, quote, 1_000)
        val target = admit(1)
        settle(match(target))
        assertEquals(CancelCode.ACCEPTED, assertIs<CancelReply.Recorded>(cancel(2, target)).receipt.code)
        return match(saved().cancellations.single().input)
    }

    fun assertBalance(
        user: UserId,
        asset: AssetId,
        available: Long,
        hold: Long = 0,
    ) = existing.assertBalance(funds, user, asset, available, hold)

    fun assertBusinessUnchanged(before: FundsRoleRecoveryState) {
        val after = saved()
        assertEquals(before.financial.balances, after.financial.balances)
        assertEquals(before.financial.liveOrders, after.financial.liveOrders)
        assertEquals(before.financial.markets, after.financial.markets)
        assertEquals(before.financial.lastAdmissionSequence, after.financial.lastAdmissionSequence)
        assertEquals(before.dispatch, after.dispatch)
        assertEquals(before.approvalInputs, after.approvalInputs)
        assertEquals(before.cancellations, after.cancellations)
        assertEquals(before.lastCancellationSequence, after.lastCancellationSequence)
        assertFalse(funds.isBlocked)
    }

    fun assertUnchanged(before: FundsRoleRecoveryState) {
        existing.assertSame(before, saved())
        assertBusinessUnchanged(before)
    }

    fun assertRestoreRejected(candidate: FundsRoleRecoveryState) {
        val empty = emptyOwner()
        assertFailsWith<IllegalArgumentException> { empty.restoreFundsRecoveryState(candidate, calculator) }
        assertTrue(empty.isBlocked)
        assertEquals(TradingLifecycle.UNINITIALIZED, empty.lifecycle)
    }

    fun assertConserved() = existing.assertConserved(funds)
}

private fun changedResult(
    original: MatchingResultEnvelope,
    input: FundsOrderDispatch = original.input,
    sequence: Long = original.resultSequence,
    outcome: MatchingOutcome = original.outcome,
    before: Long = original.engineSequenceBefore,
    after: Long = original.engineSequenceAfter,
    events: List<MatchingEvent> = original.events,
    effects: List<MatchingOrderEffect> = original.effects,
) = MatchingResultEnvelope(original.stream, sequence, input, outcome, original.reason, before, after, events, effects)

private fun copyRecovery(
    saved: FundsRoleRecoveryState,
    dispatch: FundsDispatchSnapshot = saved.dispatch,
    approvals: List<FundsOrderDispatch> = saved.approvalInputs,
    cancellations: List<FundsCancellationSnapshot> = saved.cancellations,
    results: FundsResultRecoveryState? = saved.results,
    financial: BoundedFundingRecoveryState = saved.financial,
) = FundsRoleRecoveryState(
    saved.configuration,
    financial,
    dispatch,
    saved.assetTotals,
    results,
    approvals,
    cancellations,
    saved.lastCancellationSequence,
)
