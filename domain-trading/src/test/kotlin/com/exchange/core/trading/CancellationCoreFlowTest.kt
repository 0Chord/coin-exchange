package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CancellationCoreFlowTest {
    @Test
    fun `C01 부분 체결 뒤 취소 확정은 남은 예약 200만 한 번 반환한다`() {
        val f = CancellationFixture()
        f.fund(f.buyer, f.quote, 1_000)
        f.fund(f.seller, f.base, 3)
        val buy = f.admit(1, quantity = 3)
        f.assertBalance(f.buyer, f.quote, 700, 300)
        f.settle(f.match(buy))
        f.settle(f.match(f.admit(1, f.seller, Side.SELL)))
        f.assertBalance(f.buyer, f.quote, 700, 200)
        f.assertBalance(f.buyer, f.base, 1)

        val accepted = f.cancel(2, buy)

        assertEquals(CancelCode.ACCEPTED, accepted.receipt.code)
        assertEquals(buy.order.orderId, accepted.receipt.targetOrderId)
        assertEquals(3L, accepted.receipt.inputSequence)
        f.assertBalance(f.buyer, f.quote, 700, 200)
        val cancel = f.dispatch(3)
        val applied = f.matching.apply(cancel)
        assertEquals(MatchingIntakeStatus.APPLIED, applied.status)
        val result = assertNotNull(applied.result)
        assertEquals(MatchingOutcome.CANCELLED, result.outcome)
        assertEquals(2L, result.engineSequenceBefore)
        assertEquals(3L, result.engineSequenceAfter)
        assertEquals(
            listOf(OrderCancelled(f.market.marketId, 3, buy.order.orderId, f.buyer, Quantity(2))),
            result.events,
        )
        assertEquals(buy, result.effects.single().origin)
        assertEquals(Quantity(2), result.effects.single().beforeQuantity)
        assertEquals(Quantity.ZERO, result.effects.single().afterQuantity)
        assertTrue(f.matching.restingOrders(f.market.marketId).isEmpty())
        f.assertBalance(f.buyer, f.quote, 700, 200)
        val reservation =
            f
                .saved()
                .financial.liveOrders
                .single()
                .reservation
        val release = fundsResultReleaseTransaction(result, reservation, f.at)
        val releaseId = "F4" + accepted.receipt.cancelId!!.substring(2)
        assertEquals(releaseId, release.ledgerTransactionId)
        assertEquals("ORDER_CANCELLED:$releaseId", release.sourceEventId)
        assertEquals(LedgerTransactionType.RELEASE, release.transactionType)
        assertEquals(f.at, release.occurredAt)
        assertEquals(
            listOf(
                LedgerPosting("USER:buyer:QUOTE:HOLD", f.quote, LedgerPostingSide.DEBIT, Amount(200)),
                LedgerPosting("USER:buyer:QUOTE:AVAILABLE", f.quote, LedgerPostingSide.CREDIT, Amount(200)),
            ),
            release.postings,
        )
        assertEquals(releaseId, fundsResultReleaseTransaction(result, reservation, f.at.plusSeconds(1)).ledgerTransactionId)

        f.settle(result)
        assertEquals(FundsDispatchAckStatus.ACKNOWLEDGED, f.funds.acknowledgeMatchingApplied(applied.ack!!).status)

        f.assertBalance(f.buyer, f.quote, 900)
        f.assertBalance(f.buyer, f.base, 1)
        assertEquals(
            0,
            f.funds
                .fundsRecoveryState()
                .dispatch.activeOrders,
        )
        assertEquals(
            0,
            f.funds
                .fundsRecoveryState()
                .dispatch.dispatchEntries,
        )
        assertEquals(3L, f.resultThrough())
        assertEquals(
            FundsResultStatus.DUPLICATE,
            f.funds.applyMatchingResult(result, f.at.plusSeconds(1), f.calculator).status,
        )
        f.assertBalance(f.buyer, f.quote, 900)
        assertEquals(
            f.at,
            f
                .saved()
                .results!!
                .receipts
                .last()
                .appliedAt,
        )
        f.settle(f.match(f.admit(2, f.seller, Side.SELL, quantity = 2)))
        assertEquals(
            Quantity(2),
            f.matching
                .restingOrders(f.market.marketId)
                .single()
                .remainingQuantity,
        )
        f.assertBalance(f.buyer, f.base, 1)
        f.assertBalance(f.seller, f.quote, 100)
        f.assertConserved()
    }

    @Test
    fun `C02 C10 C11 적용 ACK와 결과 ACK는 예약과 별도 수명이며 반환을 반복하지 않는다`() {
        val f = CancellationFixture(resultLimits = FundsResultLimits(maxReceiptEntries = 1, maxMarketReceiptEntries = 1))
        f.fund(f.buyer, f.quote, 1_000)
        val original = f.admit(1, quantity = 3)
        f.settle(f.match(original))
        f.cancel(2, original)
        val input = f.dispatch(2)
        val applied = f.matching.apply(input)
        assertEquals(MatchingIntakeStatus.APPLIED, applied.status)
        val result = assertNotNull(applied.result)
        assertEquals(FundsDispatchAckStatus.ACKNOWLEDGED, f.funds.acknowledgeMatchingApplied(applied.ack!!).status)
        assertEquals(0, f.saved().dispatch.dispatchEntries)
        assertEquals(1, f.saved().cancellations.size)
        f.assertBalance(f.buyer, f.quote, 700, 300)
        assertEquals(MatchingIntakeStatus.DUPLICATE, f.matching.apply(input).status)
        f.settle(result)
        f.assertBalance(f.buyer, f.quote, 1_000)
        assertTrue(f.saved().cancellations.isEmpty())
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(result).status)
        f.acknowledgeResults()
        assertEquals(0, f.matching.recoveryState().resultEntries)
        f.settle(f.match(f.admit(3, price = 50)))
        assertEquals(FundsResultStatus.PRUNED, f.apply(result).status)
        assertEquals(3L, f.funds.matchingSettlementAck(result.stream)!!.through)
        f.assertBalance(f.buyer, f.quote, 950, 50)
        f.assertConserved()
    }

    @Test
    fun `C03 원래 주문 취소 뒤 신규는 공통 입력 순서를 따르고 신규 승인 번호는 따로 센다`() {
        val f = CancellationFixture()
        f.fund(f.buyer, f.quote, 1_000)
        val first = f.admit(1, quantity = 3)
        val accepted = f.cancel(2, first)
        val last = f.admit(3, price = 50)
        val cancellation = f.dispatch(2)
        assertEquals(2L, accepted.receipt.inputSequence)
        assertEquals(
            listOf(1L, 2L, 3L),
            f
                .saved()
                .dispatch.streams
                .first()
                .outstanding
                .map { it.dispatchSequence },
        )
        assertEquals(
            listOf(false, true, false),
            f
                .saved()
                .dispatch.streams
                .first()
                .outstanding
                .map { it.isCancellation },
        )
        assertEquals(1L, first.admissionSequence)
        assertEquals(2L, last.admissionSequence)
        assertEquals(2L, last.marketAdmissionSequence)
        assertEquals(3L, last.dispatchSequence)
        val identity = assertNotNull(cancellation.cancel)
        assertEquals(1L, identity.cancelSequence)
        assertEquals(1L, identity.targetInputSequence)
        assertEquals(2L, f.saved().financial.lastAdmissionSequence)
        assertEquals(1L, f.saved().lastCancellationSequence)
        assertEquals(MatchingIntakeStatus.GAP, f.matching.apply(cancellation).status)
        assertEquals(MatchingIntakeStatus.GAP, f.matching.apply(last).status)
        assertEquals(
            0L,
            f.matching
                .recoveryState()
                .streams
                .first()
                .appliedThrough,
        )
        f.settle(f.match(first))
        f.settle(f.match(cancellation))
        f.settle(f.match(last))
        f.assertBalance(f.buyer, f.quote, 950, 50)
        assertEquals(
            last.order.orderId,
            f.matching
                .restingOrders(f.market.marketId)
                .single()
                .orderId,
        )
        assertEquals(3L, f.resultThrough())
        f.assertConserved()
    }

    @Test
    fun `C04 C05 재전송과 요청 내용 충돌 및 진행 중 안내는 새 취소나 순서를 만들지 않는다`() {
        val f = CancellationFixture()
        f.fund(f.buyer, f.quote, 1_000)
        val target = f.admit(1, quantity = 3)
        val other = f.admit(2, price = 50)
        val accepted = f.cancel(3, target)
        val repeated = f.cancel(3, target)
        assertEquals(accepted.receipt, repeated.receipt)
        assertFalse(repeated.newlyRecorded)
        assertEquals("REQUEST_CONTENT_CONFLICT", assertIs<CancelReply.Refused>(f.cancelReply(3, other)).reason)
        val wrongKind = f.funds.admit(RequestToken(f.buyer, 0, 1, 3), f.intent(), f.at)
        assertEquals("REQUEST_CONTENT_CONFLICT", assertIs<AdmissionReply.Refused>(wrongKind).reason)
        val pending = f.cancel(4, target)
        assertEquals(CancelCode.REJECTED, pending.receipt.code)
        assertEquals("CANCEL_PENDING", pending.receipt.reason)
        assertEquals(1, f.saved().cancellations.size)
        assertEquals(1L, f.saved().lastCancellationSequence)
        assertEquals(
            3L,
            f
                .saved()
                .dispatch.streams
                .first()
                .issued,
        )
        f.assertBalance(f.buyer, f.quote, 650, 350)
        f.settle(f.match(target))
        f.settle(f.match(other))
        f.settle(f.match(f.dispatch(3)))
        assertEquals(pending.receipt, f.cancel(4, target).receipt)
        assertFalse(f.cancel(4, target).newlyRecorded)
        f.assertBalance(f.buyer, f.quote, 950, 50)
        assertEquals(1L, f.saved().lastCancellationSequence)
        f.assertConserved()
    }

    @Test
    fun `C07 C09 C13 선행 추가 체결을 먼저 정산하고 FIFO가 퇴역해도 전량 종료 근거를 보존한다`() {
        for (extraQuantity in listOf(1L, 2L)) {
            val f = CancellationFixture(resultLimits = FundsResultLimits(maxReceiptEntries = 1, maxMarketReceiptEntries = 1))
            f.fund(f.buyer, f.quote, 1_000)
            f.fund(f.seller, f.base, 3)
            val target = f.admit(1, quantity = 3)
            f.settle(f.match(target))
            f.settle(f.match(f.admit(1, f.seller, Side.SELL)))
            val extra = f.admit(2, f.seller, Side.SELL, quantity = extraQuantity)
            f.cancel(2, target)
            val execution = f.match(extra)
            val cancellation = f.match(f.dispatch(4))
            assertEquals(if (extraQuantity == 1L) MatchingOutcome.CANCELLED else MatchingOutcome.CANCEL_REJECTED, cancellation.outcome)
            assertEquals(3L, cancellation.engineSequenceBefore)
            assertEquals(4L, cancellation.engineSequenceAfter)
            if (extraQuantity == 2L) {
                assertEquals(
                    listOf(OrderCancelRejected(f.market.marketId, 4, target.order.orderId, f.buyer, "order not found")),
                    cancellation.events,
                )
            }
            val before = f.saved()
            assertEquals(FundsResultStatus.GAP, f.apply(cancellation).status)
            assertEquals(before.dispatch, f.saved().dispatch)
            assertEquals(before.financial.balances, f.saved().financial.balances)
            assertEquals(before.financial.liveOrders, f.saved().financial.liveOrders)
            assertEquals(before.cancellations, f.saved().cancellations)
            assertEquals(before.approvalInputs, f.saved().approvalInputs)
            assertEquals(2L, f.resultThrough())
            f.assertBalance(f.buyer, f.quote, 700, 200)
            f.settle(execution)
            assertEquals(
                listOf(3L),
                f
                    .saved()
                    .results!!
                    .receipts
                    .map { it.result.resultSequence },
            )
            if (extraQuantity == 2L) {
                val protected = f.saved().cancellations.single()
                val terminal = assertNotNull(protected.terminalReservation)
                assertEquals(OrderReservationStatus.SETTLED, terminal.status)
                assertEquals(Amount.ZERO, terminal.remainingAmount)
                assertEquals(3L, protected.terminalResultSequence)
                assertEquals(3L, protected.terminalEngineSequence)
                assertTrue(
                    f
                        .saved()
                        .financial.liveOrders
                        .isEmpty(),
                )
            } else {
                f.assertBalance(f.buyer, f.quote, 700, 100)
            }
            f.settle(cancellation)
            f.assertBalance(f.buyer, f.quote, if (extraQuantity == 1L) 800 else 700)
            f.assertBalance(f.buyer, f.base, if (extraQuantity == 1L) 2 else 3)
            assertTrue(f.saved().cancellations.isEmpty())
            assertEquals(0, f.saved().dispatch.activeOrders)
            assertEquals(4L, f.resultThrough())
            f.assertConserved()
        }
    }

    @Test
    fun `C12 사용자 응답 ACK나 세대 교체는 미완료 취소와 예약을 삭제하지 않는다`() {
        for (rotate in listOf(false, true)) {
            val f = CancellationFixture()
            f.fund(f.buyer, f.quote, 1_000)
            val target = f.admit(1, quantity = 3)
            f.settle(f.match(target))
            f.cancel(2, target)
            if (rotate) {
                assertEquals(EpochStatus.ISSUED, f.funds.rotateEpoch(f.buyer, 0, 1).status)
            } else {
                assertEquals(AckStatus.ACKNOWLEDGED, f.funds.acknowledgeReceipts(f.buyer, 0, 1, 2).status)
            }
            assertTrue(
                f
                    .saved()
                    .financial.inbox.channels
                    .single()
                    .cancelEntries
                    .isEmpty(),
            )
            assertEquals(1, f.saved().cancellations.size)
            assertEquals(1, f.saved().dispatch.dispatchEntries)
            assertEquals(
                if (rotate) "FENCED_EPOCH" else "RETIRED_UNAVAILABLE",
                assertIs<CancelReply.Refused>(f.cancelReply(2, target)).reason,
            )
            f.assertBalance(f.buyer, f.quote, 700, 300)
            f.settle(f.match(f.dispatch(2)))
            f.assertBalance(f.buyer, f.quote, 1_000)
            assertTrue(f.saved().cancellations.isEmpty())
            f.assertConserved()
        }
    }

    @Test
    fun `대상 300 중 100 체결 뒤 취소는 별도 주문의 공유 hold 50을 보존한다`() {
        val f = CancellationFixture()
        f.fund(f.buyer, f.quote, 1_000)
        f.fund(f.seller, f.base, 1)
        val target = f.admit(1, quantity = 3)
        f.settle(f.match(target))
        val unrelated = f.admit(2, price = 50)
        f.settle(f.match(unrelated))
        f.settle(f.match(f.admit(1, f.seller, Side.SELL)))
        f.cancel(3, target)
        f.settle(f.match(f.dispatch(4)))
        f.assertBalance(f.buyer, f.quote, 850, 50)
        assertEquals(
            unrelated.order.orderId,
            f
                .saved()
                .financial.liveOrders
                .single()
                .order.orderId,
        )
        assertEquals(
            Amount(50),
            f
                .saved()
                .financial.liveOrders
                .single()
                .reservation.remainingAmount,
        )
        assertEquals(1, f.saved().dispatch.activeOrders)
        f.assertConserved()
    }

    @Test
    fun `C15 BUY 가격 개선과 실제 taker 수수료 뒤에는 남은 2020만 반환한다`() {
        val f = CancellationFixture(takerPpm = 10_000)
        f.fund(f.buyer, f.quote, 10_000)
        f.fund(f.seller, f.base, 1)
        f.settle(f.match(f.admit(1, f.seller, Side.SELL, price = 900)))
        val buy = f.admit(1, price = 1_000, quantity = 3)
        assertEquals(Amount(3_030), buy.reservation.reservedAmount)
        f.assertBalance(f.buyer, f.quote, 6_970, 3_030)
        f.settle(f.match(buy))
        f.assertBalance(f.buyer, f.quote, 7_071, 2_020)
        assertEquals(
            Amount(20),
            f
                .saved()
                .financial.liveOrders
                .single()
                .reservation.remainingFeeReserveAmount,
        )
        assertEquals(BigInteger.valueOf(9), f.saved().financial.feeRevenueByAsset[f.quote])
        f.cancel(2, buy)
        f.settle(f.match(f.dispatch(3)))
        f.assertBalance(f.buyer, f.quote, 9_091)
        f.assertBalance(f.buyer, f.base, 1)
        f.assertBalance(f.seller, f.quote, 900)
        assertEquals(BigInteger.valueOf(9), f.saved().financial.feeRevenueByAsset[f.quote])
        assertTrue(
            f
                .saved()
                .financial.liveOrders
                .isEmpty(),
        )
        f.assertConserved()
    }

    @Test
    fun `C16 SELL 취소는 base 2만 반환하며 quote 순지급 891 수수료 9와 다른 hold를 보존한다`() {
        val f = CancellationFixture(takerPpm = 10_000)
        f.fund(f.buyer, f.quote, 1_000)
        f.fund(f.seller, f.base, 4)
        val unrelated = f.admit(1, f.seller, Side.SELL, price = 1_000)
        f.settle(f.match(unrelated))
        f.settle(f.match(f.admit(1, price = 900)))
        val target = f.admit(2, f.seller, Side.SELL, price = 900, quantity = 3)
        f.settle(f.match(target))
        f.assertBalance(f.seller, f.base, 0, 3)
        f.assertBalance(f.seller, f.quote, 891)
        f.cancel(3, target)
        f.settle(f.match(f.dispatch(4)))
        f.assertBalance(f.seller, f.base, 2, 1)
        f.assertBalance(f.seller, f.quote, 891)
        assertEquals(BigInteger.valueOf(9), f.saved().financial.feeRevenueByAsset[f.quote])
        assertEquals(
            unrelated.order.orderId,
            f
                .saved()
                .financial.liveOrders
                .single()
                .order.orderId,
        )
        f.assertConserved()
    }

    @Test
    fun `C17 소수 수수료 나머지 0점51은 취소 때 현금으로 올리거나 추가 청구하지 않는다`() {
        val f = CancellationFixture(takerPpm = 10_000)
        f.fund(f.buyer, f.quote, 1_000)
        f.fund(f.seller, f.base, 1)
        f.settle(f.match(f.admit(1, f.seller, Side.SELL, price = 51)))
        val target = f.admit(1, price = 51, quantity = 2)
        f.settle(f.match(target))
        val reservation =
            f
                .saved()
                .financial.liveOrders
                .single()
                .reservation
        assertEquals(FeeRemainder(510_000), reservation.feeRemainder)
        assertEquals(Amount(53), reservation.remainingAmount)
        assertEquals(Amount(2), reservation.remainingFeeReserveAmount)
        f.assertBalance(f.buyer, f.quote, 896, 53)
        f.cancel(2, target)
        f.settle(f.match(f.dispatch(3)))
        f.assertBalance(f.buyer, f.quote, 949)
        f.assertBalance(f.buyer, f.base, 1)
        f.assertBalance(f.seller, f.quote, 51)
        assertEquals(BigInteger.ZERO, f.saved().financial.feeRevenueByAsset[f.quote] ?: BigInteger.ZERO)
        assertTrue(
            f
                .saved()
                .financial.liveOrders
                .isEmpty(),
        )
        f.assertConserved()
    }

    @Test
    fun `C08 신규 최종 거절이 먼저 반환한 예약은 뒤 취소 거절에서 다시 반환하지 않는다`() {
        val f = CancellationFixture(matchingActiveOrders = 1)
        f.fund(f.buyer, f.quote, 1_000)
        val resting = f.admit(1, price = 50)
        f.settle(f.match(resting))
        val target = f.admit(2, quantity = 3)
        f.cancel(3, target)
        val rejected = f.match(target)
        assertEquals(MatchingOutcome.NOT_EXECUTED, rejected.outcome)
        assertEquals(MatchingNotExecutedReason.ACTIVE_ORDER_LIMIT, rejected.reason)
        val cancellation = f.match(f.dispatch(3))
        assertEquals(MatchingOutcome.CANCEL_REJECTED, cancellation.outcome)
        assertEquals(FundsResultStatus.GAP, f.apply(cancellation).status)
        f.assertBalance(f.buyer, f.quote, 650, 350)
        f.settle(rejected)
        f.assertBalance(f.buyer, f.quote, 950, 50)
        val terminal = f.saved().cancellations.single()
        assertEquals(OrderReservationStatus.RELEASED, terminal.terminalStatus)
        assertEquals(Amount(300), assertNotNull(terminal.terminalReservation).releasedAmount)
        f.settle(cancellation)
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(cancellation).status)
        f.assertBalance(f.buyer, f.quote, 950, 50)
        assertTrue(f.saved().cancellations.isEmpty())
        assertEquals(listOf(resting.order.orderId), f.matching.restingOrders(f.market.marketId).map { it.orderId })
        f.assertConserved()
    }

    @Test
    fun `C20 응답 receipt와 매칭 결과 공간이 차도 기존 취소의 정산 확인 회수는 진행한다`() {
        val f = CancellationFixture(resourceLimits = TradingResourceLimits(maxInboxEntries = 2), matchingResultEntries = 1)
        f.fund(f.buyer, f.quote, 1_000)
        val target = f.admit(1, quantity = 3)
        f.cancel(2, target)
        val cancellation = f.dispatch(2)
        val first = f.match(target)
        assertEquals("RETRYABLE_CAPACITY", assertIs<CancelReply.Refused>(f.cancelReply(3, target)).reason)
        assertEquals(MatchingIntakeStatus.BUSY, f.matching.apply(cancellation).status)
        assertEquals(
            Quantity(3),
            f.matching
                .restingOrders(f.market.marketId)
                .single()
                .remainingQuantity,
        )
        assertEquals(
            1L,
            f.matching
                .recoveryState()
                .streams
                .first()
                .appliedThrough,
        )
        assertEquals(
            1L,
            f.matching
                .recoveryState()
                .streams
                .first()
                .resultIssued,
        )
        f.settle(first)
        assertEquals(MatchingIntakeStatus.BUSY, f.matching.apply(cancellation).status)
        f.assertBalance(f.buyer, f.quote, 700, 300)
        f.acknowledgeResults()
        f.settle(f.match(cancellation))
        f.assertBalance(f.buyer, f.quote, 1_000)
        f.acknowledgeResults()
        assertEquals(0, f.matching.recoveryState().resultEntries)
        assertEquals(0, f.saved().dispatch.dispatchEntries)
        assertTrue(f.saved().cancellations.isEmpty())
        assertEquals(
            1,
            f
                .saved()
                .financial.inbox.channels
                .single()
                .entries.size,
        )
        assertEquals(
            1,
            f
                .saved()
                .financial.inbox.channels
                .single()
                .cancelEntries.size,
        )
        assertEquals(
            0L,
            f
                .saved()
                .financial.inbox.channels
                .single()
                .retiredThrough,
        )
        assertEquals(2L, f.resultThrough())
        f.assertConserved()
    }
}

/** 테스트는 실제 두 소유자를 연결하며 금융 기대값은 각 사례의 고정된 수치다. */
private class CancellationFixture(
    makerPpm: Long = 0,
    takerPpm: Long = 0,
    resultLimits: FundsResultLimits = FundsResultLimits(),
    resourceLimits: TradingResourceLimits = TradingResourceLimits(),
    matchingResultEntries: Int = 4_096,
    matchingActiveOrders: Int = 8_192,
) {
    private val existing = FundsResultTestFixture(makerPpm, takerPpm, resultLimits, resourceLimits)
    val buyer = existing.buyer
    val seller = existing.seller
    val quote = existing.quote
    val market = existing.a
    val base = market.baseAssetId
    val at = existing.at
    val calculator get() = existing.calculator
    val configuration =
        FundsDispatchConfiguration(
            existing.topology,
            FundsDispatchLimits(
                existing.contexts.associate {
                    it.market.marketId to MarketFundsDispatchLimits(maxDispatchBytes = 65_536, maxActiveBytes = 65_536)
                },
                maxDispatchBytes = 65_536,
                maxActiveBytes = 65_536,
            ),
            inputContractVersion = 2,
        )
    private val declaration =
        TradingRoleDeclaration(
            TradingServiceRole.MATCHING,
            ClusterLineage(3, 4),
            existing.manifest.configurationVersion,
            existing.contexts,
            existing.contexts.associate { it.market.marketId to 1L },
        )
    private val matchingLimits =
        MatchingIntakeLimits(
            existing.contexts.associate { it.market.marketId to MarketMatchingIntakeLimits(maxResultEntries = matchingResultEntries) },
            maxResultEntries = matchingResultEntries,
            maxActiveOrders = matchingActiveOrders,
        )
    val matchingConfiguration = MatchingIntakeConfiguration(existing.topology, declaration, matchingLimits, inputContractVersion = 2)
    val funds =
        BoundedFundingState(
            OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))),
            expectedManifest = existing.manifest,
            dispatchConfiguration = configuration,
            resultLimits = resultLimits,
        ).also { it.initialize(existing.manifest) }
    val matching = ReservedMatchingState(matchingConfiguration).also { it.initialize() }

    fun fund(
        user: UserId,
        asset: com.exchange.core.common.AssetId,
        amount: Long,
    ) = existing.fund(funds, user, asset, amount)

    fun admit(
        sequence: Long,
        user: UserId = buyer,
        side: Side = Side.BUY,
        price: Long = 100,
        quantity: Long = 1,
    ): FundsOrderDispatch {
        funds.rotateEpoch(user, 0, 0)
        val reply =
            assertIs<AdmissionReply.Recorded>(
                funds.admit(
                    RequestToken(user, 0, 1, sequence),
                    intent(side, price, quantity),
                    at,
                ),
            )
        assertEquals(AdmissionCode.ACCEPTED, reply.receipt.code)
        val issued =
            funds
                .fundsRecoveryState()
                .dispatch.streams
                .single { it.stream.marketId == market.marketId }
                .issued
        return dispatch(issued).also { assertEquals(reply.receipt.orderId, it.order.orderId) }
    }

    fun cancel(
        sequence: Long,
        target: FundsOrderDispatch,
    ): CancelReply.Recorded = assertIs<CancelReply.Recorded>(cancelReply(sequence, target))

    fun cancelReply(
        sequence: Long,
        target: FundsOrderDispatch,
    ): CancelReply =
        funds.admitCancel(
            RequestToken(target.order.userId, 0, 1, sequence),
            CancelIntent(market.marketId, target.order.orderId, 7),
        )

    fun intent(
        side: Side = Side.BUY,
        price: Long = 100,
        quantity: Long = 1,
    ) = OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity))

    fun dispatch(sequence: Long): FundsOrderDispatch =
        funds.pendingDispatch(configuration.stream(market.marketId), sequence, 1, 1_048_576).entries.single()

    fun match(input: FundsOrderDispatch): MatchingResultEnvelope =
        matching.apply(input).also { assertEquals(MatchingIntakeStatus.APPLIED, it.status) }.result!!

    fun apply(result: MatchingResultEnvelope): FundsResultReply = funds.applyMatchingResult(result, at, calculator)

    fun settle(result: MatchingResultEnvelope) {
        assertEquals(FundsResultStatus.APPLIED, apply(result).status)
    }

    fun saved(): FundsRoleRecoveryState = funds.fundsRecoveryState()

    fun acknowledgeResults() {
        val ack = funds.matchingSettlementAck(matchingConfiguration.resultStream(market.marketId))!!
        assertEquals(MatchingResultAckStatus.ACKNOWLEDGED, matching.acknowledgeFundsSettled(ack).status)
    }

    fun assertBalance(
        user: UserId,
        asset: com.exchange.core.common.AssetId,
        available: Long,
        hold: Long = 0,
    ) = existing.assertBalance(funds, user, asset, available, hold)

    fun resultThrough(): Long =
        funds
            .fundsRecoveryState()
            .results!!
            .streams
            .single { it.stream.marketId == market.marketId }
            .resultThrough

    fun assertConserved() = existing.assertConserved(funds)
}
