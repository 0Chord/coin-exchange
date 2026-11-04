package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FundsCancellationTest {
    @Test
    fun `C01 C02 C03 C04 C09 원래 주문과 앞선 체결 뒤 취소 잔여만 한 번 반환한다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        f.fund(funds, f.seller, f.a.baseAssetId, 1)
        val buy = submit(f, funds, 1, quantity = 3)
        val sell = submit(f, funds, 1, f.seller, Side.SELL)
        val cancel = cancel(f, funds, buy, 2)
        assertEquals(listOf(1L, 2L, 3L), funds.pendingDispatch(buy.stream, 1, 3, 1_048_576).entries.map { it.dispatchSequence })
        assertEquals(2L, funds.fundsRecoveryState().financial.lastAdmissionSequence)
        assertEquals(2, funds.fundsRecoveryState().dispatch.activeOrders)
        f.assertBalance(funds, f.buyer, f.quote, 700, 300)

        val beforeDuplicate = funds.fundsRecoveryState()
        val originalReceipt = assertIs<CancelReply.Recorded>(funds.cancel(cancel.cancel!!.requestToken, intent(buy))).receipt
        assertEquals(cancel.cancel!!.cancelId, originalReceipt.cancelId)
        assertEquals(3L, originalReceipt.inputSequence)
        assertEquals(CancelReply.Recorded(originalReceipt, false), funds.cancel(cancel.cancel!!.requestToken, intent(buy)))
        assertEquals(
            CancelReply.Refused("REQUEST_CONTENT_CONFLICT"),
            funds.cancel(cancel.cancel!!.requestToken, intent(sell)),
        )
        assertEquals(
            AdmissionReply.Refused("REQUEST_CONTENT_CONFLICT"),
            funds.admit(cancel.cancel!!.requestToken, orderIntent(buy), f.at),
        )
        assertEquals(CancelReply.Refused("REQUEST_CONTENT_CONFLICT"), funds.cancel(RequestToken(f.buyer, 0, 1, 1), intent(buy)))
        same(f, beforeDuplicate, funds.fundsRecoveryState())

        val buyResult = f.match(matching, buy)
        val tradeResult = f.match(matching, sell)
        val cancelResult = f.match(matching, cancel)
        assertEquals(Quantity(2), (cancelResult.events.single() as OrderCancelled).remainingQuantity)
        val beforeGap = funds.fundsRecoveryState()
        val gap = f.apply(funds, cancelResult)
        assertEquals(FundsResultStatus.GAP, gap.status)
        assertEquals(1L, gap.expectedSequence)
        assertNull(gap.ack)
        same(f, beforeGap, funds.fundsRecoveryState())
        apply(f, funds, buyResult)
        apply(f, funds, tradeResult)
        f.assertBalance(funds, f.buyer, f.quote, 700, 200)
        f.assertBalance(funds, f.buyer, f.a.baseAssetId, 1)
        val reservation =
            funds
                .fundsRecoveryState()
                .financial.liveOrders
                .single()
                .reservation
        assertRelease(f, cancelResult, reservation, f.quote, 200)

        apply(f, funds, cancelResult)

        f.assertBalance(funds, f.buyer, f.quote, 900)
        f.assertBalance(funds, f.buyer, f.a.baseAssetId, 1)
        assertEquals(3L, funds.matchingSettlementAck(cancelResult.stream)!!.through)
        assertTrue(
            funds
                .fundsRecoveryState()
                .financial.liveOrders
                .isEmpty(),
        )
        assertTrue(funds.fundsRecoveryState().cancellations.isEmpty())
        assertEquals(
            2L,
            funds
                .fundsRecoveryState()
                .financial.markets
                .first()
                .executedThrough,
        )
        val completed = funds.fundsRecoveryState()
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(funds, cancelResult, f.at.plusSeconds(99)).status)
        same(f, completed, funds.fundsRecoveryState())
        val next = submit(f, funds, 3, price = 50)
        assertEquals(4L, next.dispatchSequence)
        assertEquals(3L, next.admissionSequence)
        assertEquals(3L, next.marketAdmissionSequence)
        apply(f, funds, f.match(matching, next))
        f.assertBalance(funds, f.buyer, f.quote, 850, 50)
        f.assertConserved(funds)
    }

    @Test
    fun `C17 활성 주문 한도가 가득 차도 새 슬롯이나 예약 없이 취소를 접수한다`() {
        val f =
            FundsResultTestFixture(
                limits =
                    TradingResourceLimits(
                        maxActiveOrders = 1,
                        maxPendingOrders = 1,
                        maxMarketActiveOrders = 1,
                        maxMarketPendingOrders = 1,
                    ),
            )
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        val buy = submit(f, funds, 1, quantity = 3)
        apply(f, funds, f.match(matching, buy))
        val financial = funds.fundsRecoveryState().financial

        val cancellation = cancel(f, funds, buy, 2)

        val pending = funds.fundsRecoveryState()
        assertEquals(financial.balances, pending.financial.balances)
        assertEquals(financial.liveOrders, pending.financial.liveOrders)
        assertEquals(1L, pending.financial.lastAdmissionSequence)
        assertEquals(1, pending.dispatch.activeOrders)
        assertEquals(1, pending.dispatch.dispatchEntries)
        assertEquals(1, pending.cancellations.size)
        apply(f, funds, f.match(matching, cancellation))
        f.assertBalance(funds, f.buyer, f.quote, 1000)
        f.assertConserved(funds)
    }

    @Test
    fun `C05 C07 전량 체결 뒤에도 미완료 취소 근거를 유지하고 거절 결과는 반환하지 않는다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        f.fund(funds, f.seller, f.a.baseAssetId, 1)
        val buy = submit(f, funds, 1)
        val sell = submit(f, funds, 1, f.seller, Side.SELL)
        val first = f.match(matching, buy)
        val trade = f.match(matching, sell)
        val cancellation = cancel(f, funds, buy, 2)
        val rejected = f.match(matching, cancellation)
        assertEquals(MatchingOutcome.CANCEL_REJECTED, rejected.outcome)
        apply(f, funds, first)
        apply(f, funds, trade)
        val afterTrade = funds.fundsRecoveryState()
        assertTrue(afterTrade.financial.liveOrders.isEmpty())
        assertEquals(OrderReservationStatus.SETTLED, afterTrade.cancellations.single().terminalStatus)
        assertEquals("CANCEL_PENDING", recorded(funds.cancel(RequestToken(f.buyer, 0, 1, 3), intent(buy))).reason)
        assertEquals("NOT_ACTIVE", recorded(funds.cancel(RequestToken(f.seller, 0, 1, 2), intent(buy))).reason)
        assertEquals(
            3L,
            funds
                .fundsRecoveryState()
                .dispatch.streams
                .first()
                .issued,
        )

        apply(f, funds, rejected)

        f.assertBalance(funds, f.buyer, f.quote, 900)
        f.assertBalance(funds, f.buyer, f.a.baseAssetId, 1)
        f.assertBalance(funds, f.seller, f.quote, 100)
        assertTrue(funds.fundsRecoveryState().cancellations.isEmpty())
        assertEquals("NOT_ACTIVE", recorded(funds.cancel(RequestToken(f.buyer, 0, 1, 4), intent(buy))).reason)
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(funds, rejected).status)
        val other = submit(f, funds, 5, market = f.b)
        apply(f, funds, f.match(matching, other))
        assertEquals(
            3L,
            funds
                .fundsRecoveryState()
                .financial.markets
                .sumOf { it.executedThrough },
        )
        f.assertConserved(funds)
    }

    @Test
    fun `C07 신규 최종 거절 뒤 활성 예약 없이도 취소 거절을 처리한다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching(MatchingIntakeLimits(f.matchingConfiguration.limits.marketLimits, maxActiveOrders = 1))
        f.fund(funds, f.buyer, f.quote, 1000)
        f.fund(funds, f.seller, f.a.baseAssetId, 1)
        val maker = submit(f, funds, 1, f.seller, Side.SELL, 400)
        apply(f, funds, f.match(matching, maker))
        val buy = submit(f, funds, 1, price = 300)
        val denied = f.match(matching, buy)
        assertEquals(MatchingOutcome.NOT_EXECUTED, denied.outcome)
        val cancellation = cancel(f, funds, buy, 2)
        val rejected = f.match(matching, cancellation)
        apply(f, funds, denied)
        assertEquals(
            OrderReservationStatus.RELEASED,
            funds
                .fundsRecoveryState()
                .cancellations
                .single()
                .terminalStatus,
        )
        val restored = restore(f, funds.fundsRecoveryState())

        apply(f, restored, rejected)

        f.assertBalance(restored, f.buyer, f.quote, 1000)
        assertTrue(restored.fundsRecoveryState().cancellations.isEmpty())
        assertEquals(
            1,
            restored
                .fundsRecoveryState()
                .financial.liveOrders.size,
        )
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(restored, rejected).status)
        val crossing = submit(f, restored, 3, price = 400)
        apply(f, restored, f.match(matching, crossing))
        f.assertBalance(restored, f.buyer, f.quote, 600)
        f.assertBalance(restored, f.buyer, f.a.baseAssetId, 1)
        f.assertConserved(restored)
    }

    @Test
    fun `C12 가격 개선 반환을 유지하고 취소는 남은 예약200만 반환한다`() {
        buyRemainder(
            makerPpm = 0,
            takerPpm = 0,
            opening = 1000,
            limit = 100,
            execution = 80,
            beforeAvailable = 720,
            hold = 200,
            afterAvailable = 920,
            fee = 0,
        )
    }

    @Test
    fun `C13 잔여 수수료 예약을 한 번 반환하고 이미 부과한80은 수익으로 유지한다`() {
        buyRemainder(
            makerPpm = 0,
            takerPpm = 10_000,
            opening = 100_000,
            limit = 10_000,
            execution = 8000,
            beforeAvailable = 71_720,
            hold = 20_200,
            afterAvailable = 91_920,
            fee = 80,
        )
    }

    @Test
    fun `C14 판매 취소는 남은 수량2만 반환하고 체결 대금100을 유지한다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.seller, f.a.baseAssetId, 10)
        f.fund(funds, f.buyer, f.quote, 1000)
        val sell = submit(f, funds, 1, f.seller, Side.SELL, quantity = 3)
        apply(f, funds, f.match(matching, sell))
        apply(f, funds, f.match(matching, submit(f, funds, 1)))
        f.assertBalance(funds, f.seller, f.a.baseAssetId, 7, 2)
        val reservation =
            funds
                .fundsRecoveryState()
                .financial.liveOrders
                .single()
                .reservation
        val result = f.match(matching, cancel(f, funds, sell, 2))
        assertRelease(f, result, reservation, f.a.baseAssetId, 2)

        apply(f, funds, result)

        f.assertBalance(funds, f.seller, f.a.baseAssetId, 9)
        f.assertBalance(funds, f.seller, f.quote, 100)
        f.assertBalance(funds, f.buyer, f.quote, 900)
        f.assertConserved(funds)
    }

    @Test
    fun `C15 취소 반환은 수수료 나머지와 이미 부과한 수수료를 유지한다`() {
        val f = FundsResultTestFixture(makerPpm = 10_000, takerPpm = 10_000)
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        f.fund(funds, f.seller, f.a.baseAssetId, 1)
        apply(f, funds, f.match(matching, submit(f, funds, 1, f.seller, Side.SELL, price = 151)))
        val buy = submit(f, funds, 1, price = 151, quantity = 2)
        apply(f, funds, f.match(matching, buy))
        val reservation =
            funds
                .fundsRecoveryState()
                .financial.liveOrders
                .single()
                .reservation
        assertEquals(FeeRemainder(510_000), reservation.feeRemainder)
        assertEquals(Amount(154), reservation.remainingAmount)
        assertEquals(Amount(3), reservation.remainingFeeReserveAmount)
        assertEquals(BigInteger.valueOf(2), funds.fundsRecoveryState().financial.feeRevenueByAsset[f.quote])
        val released = reservation.release()
        assertEquals(OrderReservationStatus.RELEASED, released.status)
        assertEquals(FeeRemainder(510_000), released.feeRemainder)
        assertEquals(Quantity(1), released.remainingQuantity)
        assertEquals(Amount(154), released.releasedAmount)
        assertEquals(Amount.ZERO, released.remainingAmount)
        assertEquals(Amount.ZERO, released.remainingFeeReserveAmount)
        val result = f.match(matching, cancel(f, funds, buy, 2))
        assertRelease(f, result, reservation, f.quote, 154)

        apply(f, funds, result)

        f.assertBalance(funds, f.buyer, f.quote, 848)
        f.assertBalance(funds, f.buyer, f.a.baseAssetId, 1)
        f.assertBalance(funds, f.seller, f.quote, 150)
        assertEquals(BigInteger.valueOf(2), funds.fundsRecoveryState().financial.feeRevenueByAsset[f.quote])
        f.assertConserved(funds)
    }

    @Test
    fun `C20 요청 응답 회수와 세대 교체는 미완료 취소 의무를 보존한다`() {
        for (rotate in listOf(false, true)) {
            val f = FundsResultTestFixture()
            val funds = f.owner()
            val matching = f.matching()
            f.fund(funds, f.buyer, f.quote, 1000)
            val buy = submit(f, funds, 1, quantity = 3)
            val cancellation = cancel(f, funds, buy, 2)
            val before = funds.fundsRecoveryState()
            if (rotate) {
                assertEquals(EpochStatus.ISSUED, funds.rotateEpoch(f.buyer, 0, 1).status)
            } else {
                assertEquals(AckStatus.ACKNOWLEDGED, funds.acknowledgeReceipts(f.buyer, 0, 1, 2).status)
            }
            val retained = funds.fundsRecoveryState()
            assertEquals(before.cancellations, retained.cancellations)
            assertEquals(before.approvalInputs, retained.approvalInputs)
            assertEquals(before.dispatch, retained.dispatch)
            assertEquals(before.financial.balances, retained.financial.balances)
            assertEquals(
                CancelReply.Refused(if (rotate) "FENCED_EPOCH" else "RETIRED_UNAVAILABLE"),
                funds.cancel(cancellation.cancel!!.requestToken, intent(buy)),
            )
            val restored = restore(f, retained)
            apply(f, restored, f.match(matching, buy))
            apply(f, restored, f.match(matching, cancellation))
            f.assertBalance(restored, f.buyer, f.quote, 1000)
            assertTrue(restored.fundsRecoveryState().cancellations.isEmpty())
            f.assertConserved(restored)
        }
    }

    @Test
    fun `C21 C22 매칭 적용 ACK는 전달 자료만 회수하고 결과 선도착에도 한 번 회수한다`() {
        for (ackFirst in listOf(false, true)) {
            val f = FundsResultTestFixture()
            val funds = f.owner()
            val matching = f.matching()
            f.fund(funds, f.buyer, f.quote, 1000)
            val buy = submit(f, funds, 1, quantity = 3)
            val cancellation = cancel(f, funds, buy, 2)
            val first = f.match(matching, buy)
            val result = f.match(matching, cancellation)
            val ack = FundsMatchingAppliedAck(buy.stream, 2, buy.context)
            if (ackFirst) {
                assertEquals(FundsDispatchAckStatus.ACKNOWLEDGED, funds.acknowledgeMatchingApplied(ack).status)
                val retained = funds.fundsRecoveryState()
                assertEquals(0, retained.dispatch.dispatchEntries)
                assertEquals(1, retained.cancellations.size)
                assertEquals(1, retained.approvalInputs.size)
                f.assertBalance(funds, f.buyer, f.quote, 700, 300)
                same(f, retained, restore(f, retained).fundsRecoveryState())
            }
            apply(f, funds, first)
            apply(f, funds, result)
            val completed = funds.fundsRecoveryState()
            assertEquals(0, completed.dispatch.dispatchEntries)
            assertEquals(0L, completed.dispatch.dispatchBytes)
            assertEquals(0, completed.dispatch.activeOrders)
            assertEquals(0L, completed.dispatch.activeBytes)
            assertTrue(completed.cancellations.isEmpty())
            assertTrue(completed.approvalInputs.isEmpty())
            f.assertBalance(funds, f.buyer, f.quote, 1000)
            assertEquals(FundsDispatchAckStatus.ACKNOWLEDGED, funds.acknowledgeMatchingApplied(ack).status)
            same(f, completed, funds.fundsRecoveryState())
            f.assertConserved(funds)
        }
    }

    @Test
    fun `C23 C24 취소 의무 결과 반환 ACK 상태를 복원해도 돈을 다시 반환하지 않는다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        var matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        val buy = submit(f, funds, 1, quantity = 3)
        val cancellation = cancel(f, funds, buy, 2)
        val pending = funds.fundsRecoveryState()
        var owner = restore(f, pending)
        same(f, pending, owner.fundsRecoveryState())
        apply(f, owner, f.match(matching, buy))
        val result = f.match(matching, cancellation)
        val beforeResult = owner.fundsRecoveryState()
        owner = restore(f, beforeResult)
        matching = ReservedMatchingState(matching.configuration).also { it.restore(matching.recoveryState()) }
        assertEquals(MatchingIntakeStatus.DUPLICATE, matching.apply(cancellation).status)
        assertEquals(result, matching.apply(cancellation).result)
        apply(f, owner, result)
        val returned = owner.fundsRecoveryState()
        owner = restore(f, returned)
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(owner, result, f.at.plusSeconds(10)).status)
        same(f, returned, owner.fundsRecoveryState())
        f.assertBalance(owner, f.buyer, f.quote, 1000)
        matching.acknowledgeFundsSettled(assertNotNull(owner.matchingSettlementAck(result.stream)))
        val reclaimed = matching.recoveryState()
        assertEquals(0, reclaimed.resultEntries)
        assertEquals(0L, reclaimed.resultBytes)
        matching = ReservedMatchingState(matching.configuration).also { it.restore(reclaimed) }
        assertEquals(MatchingIntakeStatus.PRUNED, matching.apply(cancellation).status)
        val next = submit(f, owner, 3)
        apply(f, owner, f.match(matching, next))
        f.assertBalance(owner, f.buyer, f.quote, 900, 100)
        f.assertConserved(owner)
    }

    @Test
    fun `C25 취소 잔량 불일치는 금융을 보존하고 활성 대상 없음 거절은 소유자를 차단한다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        val buy = submit(f, funds, 1, quantity = 3)
        apply(f, funds, f.match(matching, buy))
        val cancellation = cancel(f, funds, buy, 2)
        val result = f.match(matching, cancellation)
        val before = funds.fundsRecoveryState()
        val wrongQuantity =
            f.changed(
                result,
                events = listOf((result.events.single() as OrderCancelled).copy(remainingQuantity = Quantity(2))),
                effects = listOf(result.effects.single().copy(beforeQuantity = Quantity(2))),
            )
        val invalid = f.apply(funds, wrongQuantity)
        assertEquals(FundsResultStatus.INVALID_RESULT, invalid.status)
        assertNull(invalid.ack)
        same(f, before, funds.fundsRecoveryState())
        assertFalse(funds.isBlocked)
        val absent =
            f.changed(
                result,
                outcome = MatchingOutcome.CANCEL_REJECTED,
                events =
                    listOf(
                        OrderCancelRejected(f.a.marketId, result.engineSequenceAfter, buy.order.orderId, f.buyer, "order not found"),
                    ),
                effects = emptyList(),
            )

        val blocked = f.apply(funds, absent)

        assertEquals(FundsResultStatus.INVALID_RESULT, blocked.status)
        assertNull(blocked.ack)
        assertTrue(funds.isBlocked)
        assertFailsWith<IllegalStateException> { funds.matchingSettlementAck(result.stream) }
    }

    @Test
    fun `C25 취소 의무 원본 누락과 모순된 종료 표식은 전체 복원을 거절한다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        f.fund(funds, f.buyer, f.quote, 1000)
        val buy = submit(f, funds, 1, quantity = 3)
        cancel(f, funds, buy, 2)
        val saved = funds.fundsRecoveryState()
        val broken =
            listOf(
                copy(saved, cancellations = emptyList()),
                copy(saved, approvals = emptyList()),
                copy(saved, cancellations = listOf(saved.cancellations.single().copy(terminalStatus = OrderReservationStatus.SETTLED))),
            )
        for (state in broken) {
            val restored = f.owner(false)
            assertFailsWith<IllegalArgumentException> { restored.restoreFundsRecoveryState(state, f.calculator) }
            assertTrue(restored.isBlocked)
            assertFailsWith<IllegalStateException> { restored.fundsRecoveryState() }
        }
    }

    @Test
    fun `C25 미확인 전달 자료와 최초 응답은 같은 취소 의무를 가리켜야 한다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        f.fund(funds, f.buyer, f.quote, 1000)
        val buy = submit(f, funds, 1, quantity = 3)
        val cancellation = cancel(f, funds, buy, 2)
        val saved = funds.fundsRecoveryState()
        val stream = saved.dispatch.streams.first()

        fun delivery(entries: List<FundsOrderDispatch>): FundsDispatchSnapshot {
            val bytes = entries.sumOf { it.dispatchCanonicalBytes }
            return FundsDispatchSnapshot(
                listOf(
                    FundsDispatchStreamSnapshot(
                        stream.stream,
                        stream.issued,
                        stream.matchingAppliedThrough,
                        entries,
                        bytes,
                        stream.activeOrders,
                        stream.activeBytes,
                    ),
                ) +
                    saved.dispatch.streams.drop(1),
                entries.size,
                bytes,
                saved.dispatch.activeOrders,
                saved.dispatch.activeBytes,
            )
        }

        fun inbox(change: (TradingCancelInboxEntrySnapshot) -> TradingCancelInboxEntrySnapshot): BoundedFundingRecoveryState {
            val financial = saved.financial
            val channels = financial.inbox.channels.map { channel -> channel.copy(cancelEntries = channel.cancelEntries.map(change)) }
            return BoundedFundingRecoveryState(
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
        }
        val invalid =
            listOf(
                copy(saved, dispatch = delivery(listOf(buy))),
                copy(saved, dispatch = delivery(listOf(buy, buy.copy(dispatchSequence = 2)))),
                copy(
                    saved,
                    financial =
                        inbox {
                            it.copy(
                                intent =
                                    it.intent.copy(
                                        targetOrderId =
                                            com.exchange.core.common
                                                .OrderId("different"),
                                    ),
                            )
                        },
                ),
                copy(
                    saved,
                    financial =
                        inbox {
                            it.copy(
                                receipt =
                                    it.receipt.copy(
                                        targetOrderId =
                                            com.exchange.core.common
                                                .OrderId("different"),
                                    ),
                            )
                        },
                ),
                copy(saved, financial = inbox { it.copy(receipt = it.receipt.copy(cancelId = "C2" + "f".repeat(38))) }),
                copy(
                    saved,
                    cancellations =
                        listOf(
                            saved.cancellations.single().copy(
                                input =
                                    cancellation.copy(
                                        cancel =
                                            cancellation.cancel!!.copy(
                                                requestToken = cancellation.cancel!!.requestToken.copy(sequence = 99),
                                            ),
                                    ),
                            ),
                        ),
                ),
            )
        for (state in invalid) assertRestoreRejected(f, state)
        funds.acknowledgeMatchingApplied(FundsMatchingAppliedAck(buy.stream, 2, buy.context))
        val acknowledged = funds.fundsRecoveryState()
        assertEquals(0, acknowledged.dispatch.dispatchEntries)
        assertEquals(1, acknowledged.cancellations.size)
        assertRestoreRejected(f, copy(acknowledged, cancellations = emptyList()))
        same(f, acknowledged, restore(f, acknowledged).fundsRecoveryState())
        funds.acknowledgeReceipts(f.buyer, 0, 1, 2)
        val bothReclaimed = funds.fundsRecoveryState()
        assertTrue(
            bothReclaimed.financial.inbox.channels
                .single()
                .cancelEntries
                .isEmpty(),
        )
        assertEquals(0, bothReclaimed.dispatch.dispatchEntries)
        assertEquals(1, bothReclaimed.cancellations.size)
        assertRestoreRejected(f, copy(bothReclaimed, cancellations = emptyList()))
        same(f, bothReclaimed, restore(f, bothReclaimed).fundsRecoveryState())
    }

    private fun buyRemainder(
        makerPpm: Long,
        takerPpm: Long,
        opening: Long,
        limit: Long,
        execution: Long,
        beforeAvailable: Long,
        hold: Long,
        afterAvailable: Long,
        fee: Long,
    ) {
        val f = FundsResultTestFixture(makerPpm, takerPpm)
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, opening)
        f.fund(funds, f.seller, f.a.baseAssetId, 1)
        apply(f, funds, f.match(matching, submit(f, funds, 1, f.seller, Side.SELL, execution)))
        val buy = submit(f, funds, 1, price = limit, quantity = 3)
        apply(f, funds, f.match(matching, buy))
        f.assertBalance(funds, f.buyer, f.quote, beforeAvailable, hold)
        f.assertBalance(funds, f.buyer, f.a.baseAssetId, 1)
        val previous =
            funds
                .fundsRecoveryState()
                .financial.liveOrders
                .single()
                .reservation
        val result = f.match(matching, cancel(f, funds, buy, 2))
        assertRelease(f, result, previous, f.quote, hold)
        apply(f, funds, result)
        f.assertBalance(funds, f.buyer, f.quote, afterAvailable)
        f.assertBalance(funds, f.buyer, f.a.baseAssetId, 1)
        assertEquals(BigInteger.valueOf(fee), funds.fundsRecoveryState().financial.feeRevenueByAsset[f.quote] ?: BigInteger.ZERO)
        val completed = funds.fundsRecoveryState()
        assertEquals(FundsResultStatus.DUPLICATE, f.apply(funds, result).status)
        same(f, completed, funds.fundsRecoveryState())
        f.assertConserved(funds)
    }

    private fun assertRelease(
        f: FundsResultTestFixture,
        result: MatchingResultEnvelope,
        reservation: OrderReservation,
        asset: AssetId,
        amount: Long,
    ) {
        val release = fundsResultReleaseTransaction(result, reservation, f.at)
        assertEquals(LedgerTransactionType.RELEASE, release.transactionType)
        assertEquals(
            listOf(
                LedgerPosting("USER:${reservation.userId.value}:${asset.value}:HOLD", asset, LedgerPostingSide.DEBIT, Amount(amount)),
                LedgerPosting("USER:${reservation.userId.value}:${asset.value}:AVAILABLE", asset, LedgerPostingSide.CREDIT, Amount(amount)),
            ),
            release.postings,
        )
        assertEquals(
            release.ledgerTransactionId,
            fundsResultReleaseTransaction(result, reservation, f.at.plusSeconds(1)).ledgerTransactionId,
        )
        assertEquals(release.sourceEventId, fundsResultReleaseTransaction(result, reservation, f.at.plusSeconds(1)).sourceEventId)
    }

    @Test
    fun `C08 없는 대상 다른 소유자 마켓 설정의 취소는 예약과 전달을 바꾸지 않는다`() {
        val f = FundsResultTestFixture()
        val owner = f.owner()
        f.fund(owner, f.buyer, f.quote, 1000)
        val original = submit(f, owner, 1)
        owner.rotateEpoch(f.seller, 0, 0)
        val before = owner.fundsRecoveryState()
        val missing =
            intent(original).copy(
                targetOrderId =
                    com.exchange.core.common
                        .OrderId("missing"),
            )
        assertEquals("NOT_ACTIVE", recorded(owner.cancel(RequestToken(f.buyer, 0, 1, 2), missing)).reason)
        assertEquals("NOT_ACTIVE", recorded(owner.cancel(RequestToken(f.seller, 0, 1, 1), intent(original))).reason)
        assertEquals(
            "NOT_ACTIVE",
            recorded(owner.cancel(RequestToken(f.buyer, 0, 1, 3), intent(original).copy(marketId = f.b.marketId))).reason,
        )
        assertEquals(
            CancelReply.Refused("EXECUTION_CONTEXT_MISMATCH"),
            owner.cancel(RequestToken(f.buyer, 0, 1, 4), intent(original).copy(expectedConfigurationVersion = 8)),
        )
        val after = owner.fundsRecoveryState()
        assertEquals(before.dispatch, after.dispatch)
        assertEquals(before.financial.balances, after.financial.balances)
        assertEquals(before.financial.liveOrders, after.financial.liveOrders)
        assertTrue(after.cancellations.isEmpty())
        assertEquals(1L, after.financial.lastAdmissionSequence)
        f.assertConserved(owner)
    }

    @Test
    fun `C18 전달 또는 최초 응답 한도가 차면 취소는 무변경 거절하고 공간 회수 뒤 진행한다`() {
        for (inboxLimited in listOf(false, true)) {
            val limits =
                if (inboxLimited) {
                    TradingResourceLimits(maxInboxEntries = 1)
                } else {
                    TradingResourceLimits(maxPendingOrders = 1, maxMarketPendingOrders = 1)
                }
            val f = FundsResultTestFixture(limits = limits)
            val owner = f.owner()
            val matching = f.matching()
            f.fund(owner, f.buyer, f.quote, 1000)
            val original = submit(f, owner, 1)
            val token = RequestToken(f.buyer, 0, 1, 2)
            val before = owner.fundsRecoveryState()
            assertEquals(CancelReply.Refused("RETRYABLE_CAPACITY"), owner.cancel(token, intent(original)))
            same(f, before, owner.fundsRecoveryState())
            if (inboxLimited) owner.acknowledgeReceipts(f.buyer, 0, 1, 1)
            apply(f, owner, f.match(matching, original))
            val cancellation = cancel(f, owner, original, 2)
            f.assertBalance(owner, f.buyer, f.quote, 900, 100)
            apply(f, owner, f.match(matching, cancellation))
            f.assertBalance(owner, f.buyer, f.quote, 1000)
            assertTrue(owner.fundsRecoveryState().cancellations.isEmpty())
        }
    }

    @Test
    fun `C18 전달 자료를 회수해도 미완료 취소 한도는 유지하며 정산 뒤 공간을 돌려준다`() {
        val f = FundsResultTestFixture(limits = TradingResourceLimits(maxPendingOrders = 2, maxMarketPendingOrders = 2))
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        val originals = (1L..3L).map { n -> submit(f, owner, n).also { apply(f, owner, f.match(matching, it)) } }
        val first = cancel(f, owner, originals[0], 4)
        val result1 = f.match(matching, first)
        owner.acknowledgeMatchingApplied(FundsMatchingAppliedAck(first.stream, first.dispatchSequence, first.context))
        val second = cancel(f, owner, originals[1], 5)
        val result2 = f.match(matching, second)
        owner.acknowledgeMatchingApplied(FundsMatchingAppliedAck(second.stream, second.dispatchSequence, second.context))
        assertEquals(0, owner.fundsRecoveryState().dispatch.dispatchEntries)
        val before = owner.fundsRecoveryState()
        assertEquals(CancelReply.Refused("RETRYABLE_CAPACITY"), owner.cancel(RequestToken(f.buyer, 0, 1, 6), intent(originals[2])))
        same(f, before, owner.fundsRecoveryState())
        apply(f, owner, result1)
        val third = cancel(f, owner, originals[2], 6)
        apply(f, owner, result2)
        apply(f, owner, f.match(matching, third))
        f.assertBalance(owner, f.buyer, f.quote, 1000)
        assertEquals(0, owner.fundsRecoveryState().cancellations.size)
        f.assertConserved(owner)
    }

    @Test
    fun `C11 회수된 취소 결과를 다시 받아도 PRUNED로 답하고 돈을 움직이지 않는다`() {
        val f = FundsResultTestFixture(resultLimits = FundsResultLimits(maxReceiptEntries = 1, maxMarketReceiptEntries = 1))
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        val original = submit(f, owner, 1)
        apply(f, owner, f.match(matching, original))
        val cancellation = cancel(f, owner, original, 2)
        val result = f.match(matching, cancellation)
        apply(f, owner, result)
        val next = submit(f, owner, 3)
        apply(f, owner, f.match(matching, next))
        val before = owner.fundsRecoveryState()
        assertEquals(FundsResultStatus.PRUNED, f.apply(owner, result).status)
        same(f, before, owner.fundsRecoveryState())
        f.assertBalance(owner, f.buyer, f.quote, 900, 100)
    }

    @Test
    fun `C25 종료된 대상의 취소 원본도 보관 중인 최초 승인과 정산 원본에 정확히 일치해야 한다`() {
        val f = FundsResultTestFixture()
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        f.fund(owner, f.seller, f.a.baseAssetId, 1)
        val buy = submit(f, owner, 1)
        val result1 = f.match(matching, buy)
        val sell = submit(f, owner, 1, f.seller, Side.SELL)
        val result2 = f.match(matching, sell)
        val cancellation = cancel(f, owner, buy, 2)
        val result3 = f.match(matching, cancellation)
        apply(f, owner, result1)
        apply(f, owner, result2)
        owner.acknowledgeMatchingApplied(FundsMatchingAppliedAck(cancellation.stream, 3, cancellation.context))
        for (admissionRetired in listOf(false, true)) {
            if (admissionRetired) owner.acknowledgeReceipts(f.buyer, 0, 1, 1)
            val saved = owner.fundsRecoveryState()
            val input = saved.cancellations.single().input
            val altered =
                input.copy(
                    order = input.order.copy(quantity = Quantity(2)),
                    reservation =
                        input.reservation.copy(
                            initialQuantity = Quantity(2),
                            remainingQuantity = Quantity(2),
                            reservedAmount = Amount(200),
                            remainingAmount = Amount(200),
                        ),
                )
            val invalid = copy(saved, cancellations = listOf(saved.cancellations.single().copy(input = altered)))
            val error =
                assertFailsWith<IllegalArgumentException> {
                    f.owner(false).restoreFundsRecoveryState(invalid, f.calculator)
                }
            assertTrue(error.message.orEmpty().contains(if (admissionRetired) "retained settlement" else "retained admission"))
            val restored = restore(f, saved)
            apply(f, restored, result3)
            f.assertBalance(restored, f.buyer, f.quote, 900)
            f.assertBalance(restored, f.buyer, f.a.baseAssetId, 1)
            assertTrue(restored.fundsRecoveryState().cancellations.isEmpty())
        }
    }

    /** 취소 뒤 요청 순번, 신규 승인 순번, 공통 입력 순번이 달라도 다음 전달 원본을 읽는다. */
    private fun submit(
        f: FundsResultTestFixture,
        owner: BoundedFundingState,
        sequence: Long,
        user: UserId = f.buyer,
        side: Side = Side.BUY,
        price: Long = 100,
        quantity: Long = 1,
        market: MarketDefinition = f.a,
    ): FundsOrderDispatch {
        owner.rotateEpoch(user, 0, 0)
        val next =
            owner
                .fundsRecoveryState()
                .dispatch.streams
                .single { it.stream.marketId == market.marketId }
                .issued + 1
        val reply =
            assertIs<AdmissionReply.Recorded>(
                owner.admit(
                    RequestToken(user, 0, 1, sequence),
                    OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity)),
                    f.at,
                ),
            )
        assertEquals(AdmissionCode.ACCEPTED, reply.receipt.code)
        return owner.pendingDispatch(f.configuration.stream(market.marketId), next, 1, 1_048_576).entries.single()
    }

    private fun cancel(
        f: FundsResultTestFixture,
        owner: BoundedFundingState,
        original: FundsOrderDispatch,
        sequence: Long,
    ): FundsOrderDispatch {
        val receipt = recorded(owner.cancel(RequestToken(original.order.userId, 0, 1, sequence), intent(original)))
        assertEquals(CancelCode.ACCEPTED, receipt.code)
        assertEquals(original.order.orderId, receipt.targetOrderId)
        val input =
            owner
                .pendingDispatch(
                    f.configuration.stream(original.order.marketId),
                    assertNotNull(receipt.inputSequence),
                    1,
                    1_048_576,
                ).entries
                .single()
        assertTrue(input.isCancellation)
        assertEquals(receipt.cancelId, input.cancel!!.cancelId)
        return input
    }

    private fun intent(original: FundsOrderDispatch) =
        CancelIntent(original.order.marketId, original.order.orderId, original.stream.configurationVersion)

    private fun orderIntent(original: FundsOrderDispatch) =
        OrderIntent(
            original.order.marketId,
            7,
            original.order.side,
            original.order.orderType,
            original.order.timeInForce,
            original.order.price,
            original.order.quantity,
        )

    private fun recorded(reply: CancelReply): CancelReceipt = assertIs<CancelReply.Recorded>(reply, reply.toString()).receipt

    private fun apply(
        f: FundsResultTestFixture,
        owner: BoundedFundingState,
        result: MatchingResultEnvelope,
    ) = assertEquals(FundsResultStatus.APPLIED, f.apply(owner, result).status)

    private fun restore(
        f: FundsResultTestFixture,
        saved: FundsRoleRecoveryState,
    ): BoundedFundingState =
        f.owner(false).also {
            it.restoreFundsRecoveryState(saved, f.calculator)
        }

    private fun same(
        f: FundsResultTestFixture,
        before: FundsRoleRecoveryState,
        after: FundsRoleRecoveryState,
    ) {
        f.assertSame(before, after)
        assertEquals(before.approvalInputs, after.approvalInputs)
        assertEquals(before.cancellations, after.cancellations)
    }

    private fun assertRestoreRejected(
        f: FundsResultTestFixture,
        state: FundsRoleRecoveryState,
    ) {
        val restored = f.owner(false)
        assertFailsWith<IllegalArgumentException> { restored.restoreFundsRecoveryState(state, f.calculator) }
        assertTrue(restored.isBlocked)
        assertFailsWith<IllegalStateException> { restored.fundsRecoveryState() }
    }

    private fun copy(
        source: FundsRoleRecoveryState,
        financial: BoundedFundingRecoveryState = source.financial,
        dispatch: FundsDispatchSnapshot = source.dispatch,
        approvals: List<FundsOrderDispatch> = source.approvalInputs,
        cancellations: List<FundsCancellationSnapshot> = source.cancellations,
    ) = FundsRoleRecoveryState(source.configuration, financial, dispatch, source.assetTotals, source.results, approvals, cancellations)
}
