package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QueryRetentionCoreTest {
    private val f = MatchingIntakeTestFixture()
    private val buyer = UserId("buyer")
    private val seller = UserId("seller")
    private val at = Instant.parse("2026-11-17T00:00:00Z")

    @Test
    fun `접수 원본은 실제 전역 순서이고 업무 확인으로 회수되지 않는다`() {
        val funds = funds(query())
        fund(funds, buyer, f.quote, 1_000)
        val first = admit(funds, 1)
        admit(funds, 2, market = f.b)
        val third = admit(funds, 3)
        val initial = funds.readQueryAdmissions(1, 10, 10_000)
        assertEquals(listOf(1L, 2L, 3L), initial.entries.map { it.admissionSequence })
        assertEquals(listOf(1L, 1L, 2L), initial.entries.map { it.marketAdmissionSequence })
        funds.acknowledgeMatchingApplied(FundsMatchingAppliedAck(initial.entries[0].stream, 2, f.contexts[0]))
        funds.acknowledgeReceipts(buyer, 0, 1, 3)
        assertEquals(initial.entries, funds.readQueryAdmissions(1, 10, 10_000).entries)
        assertEquals(3L, funds.queryAdmissionsProgress()!!.issued)
        assertEquals(0L, funds.queryAdmissionsProgress()!!.retiredThrough)
        assertEquals(first.receipt.admissionSequence, initial.entries[0].admissionSequence)
        assertEquals(third.receipt.admissionSequence, initial.entries[2].admissionSequence)
    }

    @Test
    fun `조회 접수 포화는 예약 전 거절하며 재전송과 독립 확인은 계속한다`() {
        val funds = funds(query(entries = 1))
        fund(funds, buyer, f.quote, 1_000)
        val first = admit(funds, 1)
        val fact = funds.readQueryAdmissions(1, 1, 10_000).entries.single()
        funds.acknowledgeMatchingApplied(FundsMatchingAppliedAck(fact.stream, 1, fact.context))
        val before = funds.fundsRecoveryState()
        assertEquals(AdmissionReply.Refused("RETRYABLE_CAPACITY"), submit(funds, 2))
        val repeated = admit(funds, 1)
        assertFalse(repeated.newlyRecorded)
        assertEquals(first.receipt, repeated.receipt)
        assertEquals(before.financial.balances, funds.fundsRecoveryState().financial.balances)
        assertEquals(before.financial.inbox, funds.fundsRecoveryState().financial.inbox)
        assertEquals(1L, funds.queryAdmissionsProgress()!!.issued)
        assertEquals(QueryFactsAckStatus.INVALID_CONSUMER, funds.acknowledgeQueryAdmissions("other", 1).status)
        assertEquals(QueryFactsAckStatus.FUTURE_ACK, funds.acknowledgeQueryAdmissions("query", 2).status)
        assertEquals(QueryFactsAckStatus.INVALID_SEQUENCE, funds.acknowledgeQueryAdmissions("query", -1).status)
        assertEquals(listOf(fact), funds.readQueryAdmissions(1, 1, 10_000).entries)
        assertEquals(QueryFactsAckStatus.ACKNOWLEDGED, funds.acknowledgeQueryAdmissions("query", 1).status)
        assertEquals(QueryFactsAckStatus.ACKNOWLEDGED, funds.acknowledgeQueryAdmissions("query", 0).status)
        assertEquals(QueryFactsReadStatus.PRUNED, funds.readQueryAdmissions(1, 1, 10_000).status)
        assertEquals(2L, admit(funds, 2).receipt.admissionSequence)
        assertEquals(1L, funds.queryAdmissionsProgress()!!.retiredThrough)
        assertFalse(funds.isBlocked)
    }

    @Test
    fun `건수와 byte 읽기 상한은 원본을 자르지 않고 필요한 크기를 돌려준다`() {
        val funds = funds(query())
        fund(funds, buyer, f.quote, 1_000)
        admit(funds, 1)
        admit(funds, 2)
        val fact = funds.readQueryAdmissions(1, 1, 10_000).entries.single()
        val bytes = fact.dispatchCanonicalBytes
        val small = funds.readQueryAdmissions(1, 10, bytes - 1)
        assertEquals(QueryFactsReadStatus.BATCH_TOO_SMALL, small.status)
        assertEquals(bytes, small.requiredBytes)
        assertTrue(small.entries.isEmpty())
        assertEquals(1L, small.nextSequence)
        val exact = funds.readQueryAdmissions(1, 10, bytes)
        assertEquals(listOf(fact), exact.entries)
        assertEquals(2L, exact.nextSequence)
        assertEquals(QueryFactsReadStatus.INVALID_QUERY, funds.readQueryAdmissions(4, 1, 10_000).status)
        assertEquals(QueryFactsReadStatus.BATCH, funds.readQueryAdmissions(3, 1, 10_000).status)
    }

    @Test
    fun `마켓 조회 한도는 분리하고 전체 예산은 함께 제한한다`() {
        val funds = funds(query(entries = 2, marketEntries = 1))
        fund(funds, buyer, f.quote, 1_000)
        admit(funds, 1)
        assertEquals(AdmissionReply.Refused("RETRYABLE_CAPACITY"), submit(funds, 2))
        admit(funds, 2, market = f.b)
        assertEquals(AdmissionReply.Refused("RETRYABLE_CAPACITY"), submit(funds, 3, market = f.b))
        assertEquals(listOf(f.a.marketId, f.b.marketId), funds.readQueryAdmissions(1, 10, 10_000).entries.map { it.order.marketId })

        val matching = matching(query(entries = 2, marketEntries = 1))
        val first = assertNotNull(matching.apply(f.input(1)).result)
        matching.acknowledgeFundsSettled(MatchingFundsSettledAck(first.stream, 1, first.input.context))
        val beforeBook = matching.restingOrders(f.a.marketId)
        assertEquals(MatchingIntakeStatus.BUSY, matching.apply(f.input(2)).status)
        assertEquals(beforeBook, matching.restingOrders(f.a.marketId))
        assertEquals(1L, matching.queryResultsProgress(first.stream)!!.issued)
        assertEquals(MatchingIntakeStatus.APPLIED, matching.apply(f.input(1, admission = 3, market = f.b)).status)
        assertEquals(MatchingIntakeStatus.BUSY, matching.apply(f.input(2, admission = 4, market = f.b)).status)
        assertEquals(listOf(first), matching.readQueryResults(first.stream, 1, 10, 10_000).entries)
        assertEquals(
            QueryFactsAckStatus.INVALID_STREAM,
            matching.acknowledgeQueryResults("query", first.stream.copy(ownershipEpoch = 2), 1).status,
        )
        assertEquals(QueryFactsAckStatus.INVALID_CONSUMER, matching.acknowledgeQueryResults("other", first.stream, 1).status)
        assertEquals(QueryFactsAckStatus.FUTURE_ACK, matching.acknowledgeQueryResults("query", first.stream, 2).status)
        matching.acknowledgeQueryResults("query", first.stream, 1)
        assertEquals(MatchingIntakeStatus.APPLIED, matching.apply(f.input(2)).status)
    }

    @Test
    fun `조회가 포화되어도 확정 체결 정산과 업무 확인은 한 번 적용하고 원문은 남는다`() {
        val config = query(entries = 2)
        val funds = funds(config)
        val matching = matching(config)
        fund(funds, buyer, f.quote, 1_000)
        fund(funds, seller, f.a.baseAssetId, 3)
        admit(funds, 1, side = Side.SELL, quantity = 3, user = seller)
        val sell = funds.readQueryAdmissions(1, 1, 10_000).entries.single()
        val first = assertNotNull(matching.apply(sell).result)
        assertEquals(FundsResultStatus.APPLIED, funds.applyMatchingResult(first, at, settlement()).status)
        admit(funds, 1, side = Side.BUY, quantity = 1)
        val buy = funds.readQueryAdmissions(2, 1, 10_000).entries.single()
        val second = assertNotNull(matching.apply(buy).result)
        assertEquals(2, funds.queryAdmissionsProgress()!!.retainedEntries)
        assertEquals(2, matching.queryResultsProgress(second.stream)!!.retainedEntries)
        assertEquals(FundsResultStatus.APPLIED, funds.applyMatchingResult(second, at, settlement()).status)
        assertEquals(FundsResultStatus.DUPLICATE, funds.applyMatchingResult(second, at, settlement()).status)
        val ack = assertNotNull(funds.matchingSettlementAck(second.stream))
        assertEquals(2L, ack.through)
        matching.acknowledgeFundsSettled(ack)
        assertEquals(0, matching.recoveryState().resultEntries)
        val facts = matching.readQueryResults(second.stream, 1, 10, 10_000).entries
        assertSame(first, facts[0])
        assertSame(second, facts[1])
        assertEquals(listOf(0L, 2L), second.effects.map { it.afterQuantity.value })
        assertEquals(1L, funds.currentBalance(buyer, f.a.baseAssetId)!!.available.value)
        assertEquals(100L, funds.currentBalance(seller, f.quote)!!.available.value)
        assertEquals(
            2L,
            matching
                .restingOrders(f.a.marketId)
                .single()
                .remainingQuantity.value,
        )
        assertEquals(2L, funds.matchingSettlementAck(second.stream)!!.through)
    }

    @Test
    fun `정산 조회 여유를 먼저 확보하여 매칭 한도가 차도 체결을 정산하고 복원한다`() {
        val config = query(entries = 2, capabilityVersion = 2)
        val funds = funds(config)
        val matching = matching(config)
        fund(funds, buyer, f.quote, 1_000)
        fund(funds, seller, f.a.baseAssetId, 3)
        admit(funds, 1, side = Side.SELL, quantity = 3, user = seller)
        val sell = funds.readQueryAdmissions(1, 1, 10_000).entries.single()
        val resting = assertNotNull(matching.apply(sell).result)
        assertEquals(FundsResultStatus.APPLIED, funds.applyMatchingResult(resting, at, settlement()).status)
        admit(funds, 1)
        val buy = funds.readQueryAdmissions(2, 1, 10_000).entries.single()
        val matched = assertNotNull(matching.apply(buy).result)
        assertEquals(2, matching.queryResultsProgress(matched.stream)?.retainedEntries)
        assertEquals(MatchingIntakeStatus.BUSY, matching.apply(f.input(3)).status)

        assertEquals(FundsResultStatus.APPLIED, funds.applyMatchingResult(matched, at, settlement()).status)
        val saved = funds.fundsRecoveryState()
        val restored = funds(config, initialize = false).also { it.restoreFundsRecoveryState(saved, settlement()) }
        val facts = restored.readQuerySettlements(matched.stream, 1, 2, 10_000).entries
        assertEquals(listOf(1L, 2L), facts.map { it.resultSequence })
        assertTrue(facts.first().fills.isEmpty())
        val fill = facts.last().fills.single()
        assertEquals(TradingIdentifiers.settlement(f.funds, 0, fill.trade.engineSequence), fill.transaction.ledgerTransactionId)
        assertEquals(at, fill.transaction.occurredAt)
        assertEquals(Amount.ZERO, fill.makerFee)
        assertEquals(Amount.ZERO, fill.takerFee)
        assertTrue(facts.last().canonicalBytes <= matching.configuration.queryResultCharge(matched))

        assertEquals(FundsResultStatus.DUPLICATE, restored.applyMatchingResult(matched, at.plusSeconds(1), settlement()).status)
        assertEquals(facts, restored.readQuerySettlements(matched.stream, 1, 2, 10_000).entries)
        assertEquals(saved.financial.balances, restored.fundsRecoveryState().financial.balances)
        assertEquals(QueryFactsAckStatus.ACKNOWLEDGED, restored.acknowledgeQuerySettlements("query", matched.stream, 2).status)
        assertEquals(QueryFactsReadStatus.PRUNED, restored.readQuerySettlements(matched.stream, 2, 2, 10_000).status)
        assertEquals(QueryFactsAckStatus.ACKNOWLEDGED, matching.acknowledgeQueryResults("query", matched.stream, 2).status)
        assertEquals(MatchingIntakeStatus.APPLIED, matching.apply(f.input(3)).status)
    }

    @Test
    fun `정산 크기 한도를 넘는 주문은 주문장을 바꾸기 전에 최종 거절되고 복원된다`() {
        val bytes = 5_000L
        val limits = f.limits(maxSingleResultBytes = bytes)
        val query = query(bytes = bytes, capabilityVersion = 2)
        val config = MatchingIntakeConfiguration(f.topology, f.declaration, limits, queryRetention = query)
        val matching = ReservedMatchingState(config).also { it.initialize() }
        val control = f.owner(limits)
        for (sequence in 1L..6L) {
            val sell = f.input(sequence, side = Side.SELL, quantity = 1, user = "seller$sequence")
            val result = assertNotNull(matching.apply(sell).result)
            assertNotNull(control.apply(sell).result)
            matching.acknowledgeQueryResults("query", result.stream, sequence)
            val ack = MatchingFundsSettledAck(result.stream, sequence, sell.context)
            matching.acknowledgeFundsSettled(ack)
            control.acknowledgeFundsSettled(ack)
        }
        val before = matching.restingOrders(f.a.marketId)
        val buy = f.input(7, quantity = 6)
        val full = assertNotNull(control.apply(buy).result)
        assertEquals(MatchingOutcome.MATCHED, full.outcome)
        assertTrue(config.queryResultCharge(full) > bytes)

        val rejected = assertNotNull(matching.apply(buy).result)
        assertEquals(MatchingNotExecutedReason.RESULT_TOO_LARGE, rejected.reason)
        assertEquals(before, matching.restingOrders(f.a.marketId))
        val saved = matching.recoveryState()
        val restored = ReservedMatchingState(config).also { it.restore(saved) }
        assertEquals(before, restored.restingOrders(f.a.marketId))
        assertEquals(rejected, restored.apply(buy).result)
    }

    @Test
    fun `취소 조회 포화는 주문장을 보존하고 취소 성공과 거절을 구분한다`() {
        val matching = matching(query(entries = 1), inputVersion = 2)
        val input = f.input(1).copy(inputContractVersion = 2)
        val first = assertNotNull(matching.apply(input).result)
        matching.acknowledgeFundsSettled(MatchingFundsSettledAck(first.stream, 1, input.context))
        val cancel = cancellation(input, 2)
        assertEquals(MatchingIntakeStatus.BUSY, matching.apply(cancel).status)
        assertEquals(
            3L,
            matching
                .restingOrders(f.a.marketId)
                .single()
                .remainingQuantity.value,
        )
        assertEquals(1L, matching.lastEngineSequence(f.a.marketId))
        matching.acknowledgeQueryResults("query", first.stream, 1)
        val cancelled = assertNotNull(matching.apply(cancel).result)
        assertEquals(MatchingOutcome.CANCELLED, cancelled.outcome)
        assertEquals(
            3L,
            cancelled.effects
                .single()
                .beforeQuantity.value,
        )
        assertEquals(
            0L,
            cancelled.effects
                .single()
                .afterQuantity.value,
        )
        assertEquals(MatchingIntakeStatus.DUPLICATE, matching.apply(cancel).status)
        matching.acknowledgeQueryResults("query", first.stream, 2)
        matching.acknowledgeFundsSettled(MatchingFundsSettledAck(first.stream, 2, input.context))
        val rejected = assertNotNull(matching.apply(cancellation(input, 3)).result)
        assertEquals(MatchingOutcome.CANCEL_REJECTED, rejected.outcome)
        assertTrue(rejected.effects.isEmpty())
        assertTrue(matching.restingOrders(f.a.marketId).isEmpty())
        assertEquals(listOf(rejected), matching.readQueryResults(first.stream, 3, 1, 10_000).entries)
    }

    @Test
    fun `복원과 후속 원본 확인이 같은 발급 회수 금융 주문장을 만든다`() {
        val config = query(entries = 4)
        val funds = funds(config)
        fund(funds, buyer, f.quote, 1_000)
        admit(funds, 1)
        funds.acknowledgeQueryAdmissions("query", 1)
        admit(funds, 2, market = f.b)
        val savedFunds = funds.fundsRecoveryState()
        val restoredFunds = funds(config, initialize = false).also { it.restoreFundsRecoveryState(savedFunds, settlement()) }
        for (state in listOf(funds, restoredFunds)) {
            admit(state, 3)
            state.acknowledgeQueryAdmissions("query", 2)
        }
        assertEquals(funds.queryAdmissionsProgress(), restoredFunds.queryAdmissionsProgress())
        assertEquals(funds.readQueryAdmissions(3, 10, 10_000).entries, restoredFunds.readQueryAdmissions(3, 10, 10_000).entries)
        assertEquals(funds.fundsRecoveryState().financial.balances, restoredFunds.fundsRecoveryState().financial.balances)

        val matching = matching(config)
        val first = assertNotNull(matching.apply(f.input(1, Side.SELL, quantity = 3, user = "seller")).result)
        matching.acknowledgeFundsSettled(MatchingFundsSettledAck(first.stream, 1, first.input.context))
        val savedMatching = matching.recoveryState()
        val restoredMatching = ReservedMatchingState(savedMatching.configuration).also { it.restore(savedMatching) }
        for (state in listOf(matching, restoredMatching)) {
            state.apply(f.input(2, Side.BUY, quantity = 1))
            state.acknowledgeQueryResults("query", first.stream, 1)
        }
        assertEquals(matching.queryResultsProgress(first.stream), restoredMatching.queryResultsProgress(first.stream))
        assertEquals(
            matching.readQueryResults(first.stream, 2, 10, 10_000).entries,
            restoredMatching.readQueryResults(first.stream, 2, 10, 10_000).entries,
        )
        assertEquals(matching.restingOrders(f.a.marketId), restoredMatching.restingOrders(f.a.marketId))
        assertEquals(1L, restoredMatching.queryResultsProgress(first.stream)!!.retiredThrough)
    }

    @Test
    fun `조회 원본 누락 손상과 구성 불일치는 복원을 차단한다`() {
        val config = query()
        val funds = funds(config)
        fund(funds, buyer, f.quote, 1_000)
        admit(funds, 1)
        val saved = funds.fundsRecoveryState()
        val missing = FundsRoleRecoveryState(saved.configuration, saved.financial, saved.dispatch, saved.assetTotals, saved.results)
        val target = funds(config, initialize = false)
        assertFailsWith<IllegalArgumentException> { target.restoreFundsRecoveryState(missing, settlement()) }
        assertTrue(target.isBlocked)
        val matching = matching(config)
        matching.apply(f.input(1))
        val state = matching.recoveryState()
        val queryState = state.queryResults.getValue(f.a.marketId)
        val corrupt =
            MatchingRoleRecoveryState(
                state.configuration,
                state.lifecycle,
                state.matching,
                state.active,
                state.streams,
                state.resultEntries,
                state.resultBytes,
                state.activeOrders,
                state.activeBytes,
                state.queryResults + (f.a.marketId to QueryFactsRecovery(queryState.issued, 0, emptyList(), 0)),
            )
        val matchingTarget = ReservedMatchingState(state.configuration)
        assertFailsWith<IllegalArgumentException> { matchingTarget.restore(corrupt) }
        assertTrue(matchingTarget.isBlocked)
        assertNotEquals(
            FundsDispatchConfiguration(f.topology, queryRetention = config),
            FundsDispatchConfiguration(f.topology, queryRetention = query(consumer = "another")),
        )
        assertFailsWith<IllegalArgumentException> { matching(query(bytes = 1_048_575)) }
        assertFailsWith<IllegalArgumentException> { funds(query(bytes = 1)) }
    }

    @Test
    fun `회수한 업무 원본과 별도로 보존한 조회의 마켓 접수 suffix 손상을 거절한다`() {
        val config = query()
        val funds = funds(config)
        fund(funds, buyer, f.quote, 1_000)
        admit(funds, 1)
        admit(funds, 2, market = f.b)
        admit(funds, 3)
        val initial = funds.readQueryAdmissions(1, 10, 10_000).entries
        funds.acknowledgeMatchingApplied(FundsMatchingAppliedAck(initial[0].stream, 2, initial[0].context))
        funds.acknowledgeMatchingApplied(FundsMatchingAppliedAck(initial[1].stream, 1, initial[1].context))
        val saved = funds.fundsRecoveryState()
        val retained = assertNotNull(saved.queryAdmissions)
        val damagedAdmission = initial.last().copy(dispatchSequence = 1, marketAdmissionSequence = 1)
        val corruptedFunds =
            FundsRoleRecoveryState(
                saved.configuration,
                saved.financial,
                saved.dispatch,
                saved.assetTotals,
                saved.results,
                queryAdmissions =
                    QueryFactsRecovery(
                        retained.issued,
                        retained.retiredThrough,
                        initial.dropLast(1) + damagedAdmission,
                        retained.retainedBytes,
                    ),
            )
        val fundsTarget = funds(config, initialize = false)
        assertFailsWith<IllegalArgumentException> { fundsTarget.restoreFundsRecoveryState(corruptedFunds, settlement()) }
        assertTrue(fundsTarget.isBlocked)

        val matching = matching(config, inputVersion = 2)
        val first =
            assertNotNull(matching.apply(f.input(1, Side.SELL, quantity = 1, user = "seller").copy(inputContractVersion = 2)).result)
        val second = assertNotNull(matching.apply(f.input(2, quantity = 1).copy(inputContractVersion = 2)).result)
        matching.acknowledgeFundsSettled(MatchingFundsSettledAck(first.stream, 2, first.input.context))
        val state = matching.recoveryState()
        val badInput = second.input.copy(marketAdmissionSequence = 1)
        val damagedResult =
            MatchingResultEnvelope(
                second.stream,
                second.resultSequence,
                badInput,
                second.outcome,
                second.reason,
                second.engineSequenceBefore,
                second.engineSequenceAfter,
                second.events,
                second.effects.map { if (it.origin == second.input) it.copy(origin = badInput) else it },
            )
        val queryState = state.queryResults.getValue(f.a.marketId)
        val corruptedMatching =
            MatchingRoleRecoveryState(
                state.configuration,
                state.lifecycle,
                state.matching,
                state.active,
                state.streams,
                state.resultEntries,
                state.resultBytes,
                state.activeOrders,
                state.activeBytes,
                state.queryResults +
                    (
                        f.a.marketId to
                            QueryFactsRecovery(
                                queryState.issued,
                                queryState.retiredThrough,
                                listOf(first, damagedResult),
                                queryState.retainedBytes,
                            )
                    ),
            )
        val matchingTarget = ReservedMatchingState(state.configuration)
        assertFailsWith<IllegalArgumentException> { matchingTarget.restore(corruptedMatching) }
        assertTrue(matchingTarget.isBlocked)
    }

    @Test
    fun `업무 확인 뒤 살아 있는 금융 주문과 다른 조회 접수 원본의 복원을 거절한다`() {
        for (partiallyFilled in listOf(false, true)) {
            val config = query()
            val funds = funds(config)
            fund(funds, buyer, f.quote, 1_000)
            fund(funds, seller, f.a.baseAssetId, 1)
            admit(funds, 1, quantity = 3)
            val original = funds.readQueryAdmissions(1, 1, 10_000).entries.single()
            if (partiallyFilled) {
                val matching = matching(config)
                val first = assertNotNull(matching.apply(original).result)
                assertEquals(FundsResultStatus.APPLIED, funds.applyMatchingResult(first, at, settlement()).status)
                admit(funds, 1, side = Side.SELL, quantity = 1, user = seller)
                val sell = funds.readQueryAdmissions(2, 1, 10_000).entries.single()
                val second = assertNotNull(matching.apply(sell).result)
                assertEquals(FundsResultStatus.APPLIED, funds.applyMatchingResult(second, at, settlement()).status)
            }
            funds.acknowledgeMatchingApplied(FundsMatchingAppliedAck(original.stream, if (partiallyFilled) 2 else 1, original.context))
            val saved = funds.fundsRecoveryState()
            assertEquals(0, saved.dispatch.dispatchEntries)
            assertTrue(saved.approvalInputs.isEmpty())
            val live = saved.financial.liveOrders.single()
            assertEquals(Quantity(3), live.order.quantity)
            assertEquals(Quantity(if (partiallyFilled) 2 else 3), live.reservation.remainingQuantity)
            val restored = funds(config, initialize = false)
            restored.restoreFundsRecoveryState(saved, settlement())
            assertEquals(saved.financial.liveOrders, restored.fundsRecoveryState().financial.liveOrders)
            assertEquals(original, restored.readQueryAdmissions(1, 1, 10_000).entries.single())

            // 순번과 주문 ID는 같고 예약 계산도 유효하지만 실제 금융 주문의 최초 수량과 다르다.
            val changed = f.input(1, quantity = 4)
            assertEquals(original.order.orderId, changed.order.orderId)
            val retained = assertNotNull(saved.queryAdmissions)
            val damagedFacts = retained.entries.map { if (it.admissionSequence == original.admissionSequence) changed else it }
            val corrupted =
                FundsRoleRecoveryState(
                    saved.configuration,
                    saved.financial,
                    saved.dispatch,
                    saved.assetTotals,
                    saved.results,
                    queryAdmissions =
                        QueryFactsRecovery(
                            retained.issued,
                            retained.retiredThrough,
                            damagedFacts,
                            damagedFacts.sumOf { it.dispatchCanonicalBytes },
                        ),
                )
            val target = funds(config, initialize = false)
            assertFailsWith<IllegalArgumentException> { target.restoreFundsRecoveryState(corrupted, settlement()) }
            assertTrue(target.isBlocked)
            assertFailsWith<IllegalStateException> { target.fundsRecoveryState() }
            assertFailsWith<IllegalStateException> { target.readQueryAdmissions(1, 1, 10_000) }
        }
    }

    private fun query(
        entries: Int = 8,
        marketEntries: Int = entries,
        bytes: Long = 1_048_576,
        consumer: String = "query",
        capabilityVersion: Int = 1,
    ) = QueryRetentionConfiguration(
        consumer,
        f.contexts.associate {
            it.market.marketId to QueryRetentionLimits(marketEntries, bytes)
        },
        entries,
        bytes,
        capabilityVersion,
    )

    private fun funds(
        query: QueryRetentionConfiguration,
        initialize: Boolean = true,
    ): BoundedFundingState {
        val reserve = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))
        return BoundedFundingState(
            OrderAdmissionCalculator(reserve),
            f.manifest,
            FundsDispatchConfiguration(f.topology, queryRetention = query),
            FundsResultLimits(
                maxReceiptEntries = 1,
                maxMarketReceiptEntries = 1,
                maxReceiptBytes = 1_048_576,
                maxMarketReceiptBytes = 1_048_576,
            ),
        ).also {
            if (initialize) {
                it.initialize(f.manifest)
                it.rotateEpoch(buyer, 0, 0)
                it.rotateEpoch(seller, 0, 0)
            }
        }
    }

    private fun matching(
        query: QueryRetentionConfiguration,
        inputVersion: Int = 1,
    ): ReservedMatchingState =
        ReservedMatchingState(
            MatchingIntakeConfiguration(f.topology, f.declaration, f.limits(), inputVersion, query),
        ).also { it.initialize() }

    private fun fund(
        funds: BoundedFundingState,
        user: UserId,
        asset: AssetId,
        amount: Long,
    ) = funds.prepareDevelopmentBalance(OpeningBalance("opening-${user.value}-${asset.value}", user, asset, Amount(amount)), at)

    private fun submit(
        funds: BoundedFundingState,
        sequence: Long,
        side: Side = Side.BUY,
        quantity: Long = 1,
        market: com.exchange.core.order.MarketDefinition = f.a,
        user: UserId = buyer,
    ): AdmissionReply =
        funds.admit(
            RequestToken(user, 0, 1, sequence),
            OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(quantity)),
            at,
        )

    private fun admit(
        funds: BoundedFundingState,
        sequence: Long,
        side: Side = Side.BUY,
        quantity: Long = 1,
        market: com.exchange.core.order.MarketDefinition = f.a,
        user: UserId = buyer,
    ): AdmissionReply.Recorded =
        assertIs<AdmissionReply.Recorded>(submit(funds, sequence, side, quantity, market, user)).also {
            assertEquals(AdmissionCode.ACCEPTED, it.receipt.code)
        }

    private fun cancellation(
        input: FundsOrderDispatch,
        sequence: Long,
    ): FundsOrderDispatch =
        input.copy(
            dispatchSequence = sequence,
            cancel =
                FundsCancelDispatch(
                    TradingIdentifiers.cancel(f.funds, sequence - 1),
                    RequestToken(input.order.userId, 0, 1, sequence),
                    sequence - 1,
                    input.dispatchSequence,
                ),
        )

    private fun settlement(): OrderSettlementCalculator {
        val fees = TradingFeeReserveCalculator()
        return OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
            OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fees)),
            fees,
            SettlementLedgerTransactions(),
        )
    }
}
