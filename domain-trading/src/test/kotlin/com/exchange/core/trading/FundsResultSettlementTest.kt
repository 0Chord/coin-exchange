package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.Side
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FundsResultSettlementTest {
    @Test
    fun `수신 상한은 매칭이 허용한 사건 수와 단일 결과 크기를 모두 수용해야 한다`() {
        val f = FundsResultTestFixture()
        val producer = f.matchingConfiguration.limits
        val exact =
            FundsResultLimits(maxResultEvents = producer.maxResultEvents, maxSingleResultBytes = producer.maxSingleResultBytes)
        exact.requireCompatible(producer)
        exact
            .copy(maxResultEvents = producer.maxResultEvents + 1, maxSingleResultBytes = producer.maxSingleResultBytes + 1)
            .requireCompatible(producer)

        assertFailsWith<IllegalArgumentException> {
            exact.copy(maxResultEvents = producer.maxResultEvents - 1).requireCompatible(producer)
        }
        assertFailsWith<IllegalArgumentException> {
            exact.copy(maxSingleResultBytes = producer.maxSingleResultBytes - 1).requireCompatible(producer)
        }
    }

    @Test
    fun `무체결 결과는 전달 자료를 회수하되 예약과 잔량을 유지한다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.seller, f.a.baseAssetId, 1)
        val input = f.admit(funds, 1, f.seller, Side.SELL)
        val result = f.match(matching, input)

        val reply = f.apply(funds, result)

        assertEquals(FundsResultStatus.APPLIED, reply.status)
        assertEquals(1L, reply.ack!!.through)
        f.assertBalance(funds, f.seller, f.a.baseAssetId, 0, 1)
        val saved = funds.fundsRecoveryState()
        assertEquals(
            LiveOrderPhase.RESTING,
            saved.financial.liveOrders
                .single()
                .phase,
        )
        assertEquals(
            Quantity(1),
            saved.financial.liveOrders
                .single()
                .reservation.remainingQuantity,
        )
        assertEquals(
            1L,
            saved.financial.markets
                .first()
                .executedThrough,
        )
        assertTrue(
            saved.financial.markets
                .first()
                .pendingAdmissionSequences
                .isEmpty(),
        )
        assertEquals(0, saved.dispatch.dispatchEntries)
        assertEquals(1, saved.dispatch.activeOrders)
        assertEquals(
            1L,
            saved.dispatch.streams
                .first()
                .matchingAppliedThrough,
        )
        assertEquals(
            1L,
            saved.results!!
                .streams
                .first()
                .settledEngineSequence,
        )
        f.assertConserved(funds)
    }

    @Test
    fun `체결 결과는 두 사용자 자산을 정산하고 재전송은 다시 움직이지 않는다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        f.fund(funds, f.seller, f.a.baseAssetId, 1)
        val sell = f.admit(funds, 1, f.seller, Side.SELL)
        assertEquals(FundsResultStatus.APPLIED, f.apply(funds, f.match(matching, sell)).status)
        val buy = f.admit(funds, 1)
        val result = f.match(matching, buy)

        assertEquals(FundsResultStatus.APPLIED, f.apply(funds, result).status)

        f.assertBalance(funds, f.buyer, f.quote, 900)
        f.assertBalance(funds, f.buyer, f.a.baseAssetId, 1)
        f.assertBalance(funds, f.seller, f.quote, 100)
        f.assertBalance(funds, f.seller, f.a.baseAssetId, 0)
        val before = funds.fundsRecoveryState()
        assertTrue(before.financial.liveOrders.isEmpty())
        assertEquals(0, before.dispatch.activeOrders)
        assertEquals(0L, before.dispatch.activeBytes)
        assertEquals(0, before.dispatch.dispatchEntries)
        assertEquals(
            2L,
            before.results!!
                .streams
                .first()
                .resultThrough,
        )
        assertEquals(
            f.at,
            before.results!!
                .receipts
                .last()
                .appliedAt,
        )
        val repeated = f.apply(funds, result, f.at.plusSeconds(99))
        assertEquals(FundsResultStatus.DUPLICATE, repeated.status)
        assertEquals(2L, repeated.ack!!.through)
        f.assertSame(before, funds.fundsRecoveryState())
        f.assertConserved(funds)
    }

    @Test
    fun `두 체결은 같은 판매자 잔고에 합치고 구매 잔량 예약은 남긴다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        f.fund(funds, f.seller, f.a.baseAssetId, 2)
        for ((request, price) in listOf(1L to 90L, 2L to 95L)) {
            val sell = f.admit(funds, request, f.seller, Side.SELL, price)
            assertEquals(FundsResultStatus.APPLIED, f.apply(funds, f.match(matching, sell)).status)
        }
        val buy = f.admit(funds, 1, quantity = 3)
        val result = f.match(matching, buy)

        assertEquals(FundsResultStatus.APPLIED, f.apply(funds, result).status)

        f.assertBalance(funds, f.buyer, f.quote, 715, 100)
        f.assertBalance(funds, f.buyer, f.a.baseAssetId, 2)
        f.assertBalance(funds, f.seller, f.quote, 185)
        f.assertBalance(funds, f.seller, f.a.baseAssetId, 0)
        val saved = funds.fundsRecoveryState()
        val live = saved.financial.liveOrders.single()
        assertEquals(buy.order.orderId, live.order.orderId)
        assertEquals(LiveOrderPhase.RESTING, live.phase)
        assertEquals(Quantity(1), live.reservation.remainingQuantity)
        assertEquals(Amount(100), live.reservation.remainingAmount)
        assertEquals(
            3L,
            saved.results!!
                .streams
                .first()
                .resultThrough,
        )
        assertEquals(
            5L,
            saved.results!!
                .streams
                .first()
                .settledEngineSequence,
        )
        assertEquals(1, saved.dispatch.activeOrders)
        f.assertConserved(funds)
    }

    @Test
    fun `부분 체결됐던 구매자가 다음 결과의 maker가 되면 현재 예약을 쓴다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        val seller2 = UserId("seller2")
        f.fund(funds, f.buyer, f.quote, 1000)
        f.fund(funds, f.seller, f.a.baseAssetId, 1)
        f.fund(funds, seller2, f.a.baseAssetId, 1)
        val sell1 = f.admit(funds, 1, f.seller, Side.SELL, 90)
        f.apply(funds, f.match(matching, sell1))
        val buy = f.admit(funds, 1, quantity = 2)
        f.apply(funds, f.match(matching, buy))
        f.assertBalance(funds, f.buyer, f.quote, 810, 100)
        val sell2 = f.admit(funds, 1, seller2, Side.SELL, 95)
        val result = f.match(matching, sell2)
        assertEquals(
            Quantity(2),
            result.effects[1]
                .origin.reservation.remainingQuantity,
        )
        assertEquals(Quantity(1), result.effects[1].beforeQuantity)

        assertEquals(FundsResultStatus.APPLIED, f.apply(funds, result).status)

        f.assertBalance(funds, f.buyer, f.quote, 810)
        f.assertBalance(funds, f.buyer, f.a.baseAssetId, 2)
        f.assertBalance(funds, seller2, f.quote, 100)
        assertTrue(
            funds
                .fundsRecoveryState()
                .financial.liveOrders
                .isEmpty(),
        )
        f.assertConserved(funds)
    }

    @Test
    fun `한 결과의 수수료는 구매 주문 안에서 이월하고 별개 판매 주문은 합치지 않는다`() {
        val f = FundsResultTestFixture(makerPpm = 5000, takerPpm = 10000)
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        f.fund(funds, f.seller, f.a.baseAssetId, 2)
        for (request in 1L..2L) {
            f.apply(funds, f.match(matching, f.admit(funds, request, f.seller, Side.SELL, 51)))
        }
        val buy = f.admit(funds, 1, price = 51, quantity = 2)
        f.assertBalance(funds, f.buyer, f.quote, 896, 104)

        assertEquals(FundsResultStatus.APPLIED, f.apply(funds, f.match(matching, buy)).status)

        f.assertBalance(funds, f.buyer, f.quote, 897)
        f.assertBalance(funds, f.buyer, f.a.baseAssetId, 2)
        f.assertBalance(funds, f.seller, f.quote, 102)
        f.assertBalance(funds, f.seller, f.a.baseAssetId, 0)
        assertEquals(BigInteger.ONE, funds.fundsRecoveryState().financial.feeRevenueByAsset[f.quote])
        assertTrue(
            funds
                .fundsRecoveryState()
                .financial.liveOrders
                .isEmpty(),
        )
        f.assertConserved(funds)
    }

    @Test
    fun `부분 체결 수수료 나머지를 복원하고 다음 maker 체결에 이어서 사용한다`() {
        val f = FundsResultTestFixture(makerPpm = 10000, takerPpm = 10000)
        val original = f.owner()
        val matching = f.matching()
        f.fund(original, f.buyer, f.quote, 1000)
        f.fund(original, f.seller, f.a.baseAssetId, 2)
        f.apply(original, f.match(matching, f.admit(original, 1, f.seller, Side.SELL, 51)))
        val partial = f.match(matching, f.admit(original, 1, price = 51, quantity = 2))
        assertEquals(FundsResultStatus.APPLIED, f.apply(original, partial).status)
        f.assertBalance(original, f.buyer, f.quote, 896, 53)
        f.assertBalance(original, f.buyer, f.a.baseAssetId, 1)
        val saved = original.fundsRecoveryState()
        val reservation =
            saved.financial.liveOrders
                .single()
                .reservation
        assertEquals(FeeRemainder(510000), reservation.feeRemainder)
        assertEquals(Amount(2), reservation.remainingFeeReserveAmount)
        assertEquals(Quantity(1), reservation.remainingQuantity)
        val restored = f.owner(false).also { it.restoreFundsRecoveryState(saved, f.calculator) }
        val lastInput = f.admit(original, 2, f.seller, Side.SELL, 51)
        assertEquals(lastInput, f.admit(restored, 2, f.seller, Side.SELL, 51))
        val result = f.match(matching, lastInput)
        // 메시지의 승인 원본은 나머지0이다. 금융 상태의 현재 나머지510000을 덮어쓰면 안 된다.
        assertEquals(
            FeeRemainder.ZERO,
            result.effects[1]
                .origin.reservation.feeRemainder,
        )

        assertEquals(FundsResultStatus.APPLIED, f.apply(original, result).status)
        assertEquals(FundsResultStatus.APPLIED, f.apply(restored, result).status)

        f.assertSame(original.fundsRecoveryState(), restored.fundsRecoveryState())
        f.assertBalance(restored, f.buyer, f.quote, 897)
        f.assertBalance(restored, f.buyer, f.a.baseAssetId, 2)
        f.assertBalance(restored, f.seller, f.quote, 102)
        f.assertBalance(restored, f.seller, f.a.baseAssetId, 0)
        assertEquals(BigInteger.ONE, restored.fundsRecoveryState().financial.feeRevenueByAsset[f.quote])
        assertTrue(
            restored
                .fundsRecoveryState()
                .financial.liveOrders
                .isEmpty(),
        )
        f.assertConserved(restored)
    }

    @Test
    fun `동일 사용자의 양측 주문을 정산해도 다른 주문의 예약은 보존한다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        f.fund(funds, f.buyer, f.a.baseAssetId, 1)
        val unrelated = f.admit(funds, 1, price = 50, market = f.b)
        val sell = f.admit(funds, 2, f.buyer, Side.SELL)
        f.apply(funds, f.match(matching, sell))
        val buy = f.admit(funds, 3)

        assertEquals(FundsResultStatus.APPLIED, f.apply(funds, f.match(matching, buy)).status)

        f.assertBalance(funds, f.buyer, f.quote, 950, 50)
        f.assertBalance(funds, f.buyer, f.a.baseAssetId, 1)
        val live =
            funds
                .fundsRecoveryState()
                .financial.liveOrders
                .single()
        assertEquals(unrelated.order.orderId, live.order.orderId)
        assertEquals(Amount(50), live.reservation.remainingAmount)
        f.assertConserved(funds)
    }

    @Test
    fun `매칭 최종 거절은 예약을 한번 반환하고 사건 순번은 늘리지 않는다`() {
        for (reason in MatchingNotExecutedReason.entries) {
            val f = FundsResultTestFixture()
            val funds = f.owner()
            f.fund(funds, f.buyer, f.quote, 1000)
            val input = f.admit(funds, 1, price = 300)
            val result =
                MatchingResultEnvelope(
                    f.matchingConfiguration.resultStream(f.a.marketId),
                    1,
                    input,
                    MatchingOutcome.NOT_EXECUTED,
                    reason,
                    0,
                    0,
                    emptyList(),
                    listOf(MatchingOrderEffect(input, Quantity(1), Quantity(1), MatchingOrderDisposition.NOT_EXECUTED)),
                )

            assertEquals(FundsResultStatus.APPLIED, f.apply(funds, result).status)

            f.assertBalance(funds, f.buyer, f.quote, 1000)
            val saved = funds.fundsRecoveryState()
            assertEquals(
                1L,
                saved.results!!
                    .streams
                    .first()
                    .resultThrough,
            )
            assertEquals(
                0L,
                saved.results!!
                    .streams
                    .first()
                    .settledEngineSequence,
            )
            assertEquals(0, saved.dispatch.activeOrders)
            assertEquals(0, saved.dispatch.dispatchEntries)
            assertTrue(saved.financial.liveOrders.isEmpty())
            assertEquals(FundsResultStatus.DUPLICATE, f.apply(funds, result).status)
            f.assertSame(saved, funds.fundsRecoveryState())
            val original = funds.admit(RequestToken(f.buyer, 0, 1, 1), input.intent(), f.at)
            assertEquals(AdmissionCode.ACCEPTED, assertIs<AdmissionReply.Recorded>(original).receipt.code)
            assertFalse(original.newlyRecorded)
            f.assertConserved(funds)
        }
    }

    @Test
    fun `적용 확인이 먼저 도착해 원문을 회수해도 예약으로 체결을 정산한다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        val input = f.admit(funds, 1, price = 300)
        val result = f.match(matching, input)
        funds.acknowledgeMatchingApplied(FundsMatchingAppliedAck(input.stream, 1, input.context))
        assertEquals(0, funds.fundsRecoveryState().dispatch.dispatchEntries)
        f.assertBalance(funds, f.buyer, f.quote, 700, 300)

        assertEquals(FundsResultStatus.APPLIED, f.apply(funds, result).status)

        f.assertBalance(funds, f.buyer, f.quote, 700, 300)
        val before = funds.fundsRecoveryState()
        funds.acknowledgeMatchingApplied(FundsMatchingAppliedAck(input.stream, 1, input.context))
        f.assertSame(before, funds.fundsRecoveryState())
    }

    @Test
    fun `앞선 결과가 없으면 미래 체결을 반영하거나 전달을 회수하지 않는다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        f.fund(funds, f.seller, f.a.baseAssetId, 1)
        val first = f.match(matching, f.admit(funds, 1, f.seller, Side.SELL))
        val second = f.match(matching, f.admit(funds, 1))
        val before = funds.fundsRecoveryState()

        val reply = f.apply(funds, second)

        assertEquals(FundsResultStatus.GAP, reply.status)
        assertEquals(1L, reply.expectedSequence)
        assertNull(reply.ack)
        f.assertSame(before, funds.fundsRecoveryState())
        assertEquals(FundsResultStatus.APPLIED, f.apply(funds, first).status)
        assertEquals(FundsResultStatus.APPLIED, f.apply(funds, second).status)
        f.assertBalance(funds, f.buyer, f.quote, 900)
        f.assertConserved(funds)
    }

    @Test
    fun `보존한 같은 순번의 다른 내용은 충돌이고 모르는 stream은 ACK하지 않는다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        val result = f.match(matching, f.admit(funds, 1))
        f.apply(funds, result)
        val before = funds.fundsRecoveryState()
        val conflict = f.changed(result, after = result.engineSequenceAfter + 1)
        val conflicting = f.apply(funds, conflict)
        assertEquals(FundsResultStatus.CONTENT_CONFLICT, conflicting.status)
        assertNull(conflicting.ack)
        for (stream in listOf(result.stream.copy(ownershipEpoch = 2), result.stream.copy(matchingLineage = ClusterLineage(8, 9)))) {
            assertEquals(FundsResultStatus.INVALID_STREAM, f.apply(funds, f.changed(result, stream = stream)).status)
            assertNull(funds.matchingSettlementAck(stream))
        }
        f.assertSame(before, funds.fundsRecoveryState())
    }

    @Test
    fun `회수된 과거라도 잘못된 순번 마켓 버전 정책에는 정산 ACK를 주지 않는다`() {
        val f = FundsResultTestFixture(resultLimits = FundsResultLimits(maxReceiptEntries = 1, maxMarketReceiptEntries = 1))
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        val old = f.match(matching, f.admit(owner, 1))
        f.apply(owner, old)
        f.apply(owner, f.match(matching, f.admit(owner, 2)))
        assertEquals(
            1L,
            owner
                .fundsRecoveryState()
                .results!!
                .streams
                .first()
                .retiredThrough,
        )
        val before = owner.fundsRecoveryState()
        val wrongContext = old.input.context.copy(feePolicySnapshot = f.fees.copy(scheduleVersion = 2))
        val invalid =
            listOf(
                f.changed(old, sequence = 0) to FundsResultStatus.INVALID_RESULT,
                f.changed(old, sequence = -1) to FundsResultStatus.INVALID_RESULT,
                f.changed(old, stream = old.stream.copy(marketId = MarketId("UNKNOWN-QUOTE"))) to FundsResultStatus.INVALID_STREAM,
                f.changed(old, stream = old.stream.copy(configurationVersion = 8)) to FundsResultStatus.INVALID_STREAM,
                f.changed(old, input = old.input.copy(context = wrongContext)) to FundsResultStatus.INVALID_STREAM,
                f.changed(old, effects = old.effects.map { it.copy(origin = it.origin.copy(context = wrongContext)) }) to
                    FundsResultStatus.INVALID_STREAM,
            )

        for ((result, expected) in invalid) {
            val reply = f.apply(owner, result)
            assertEquals(expected, reply.status)
            assertNull(reply.ack)
            f.assertSame(before, owner.fundsRecoveryState())
        }

        assertEquals(FundsResultStatus.PRUNED, f.apply(owner, old).status)
        f.assertSame(before, owner.fundsRecoveryState())
    }

    @Test
    fun `사건 효과 잔량 또는 현재 승인 모순은 전체 무변경으로 거절한다`() {
        val f = FundsResultTestFixture()
        val funds = f.owner()
        val matching = f.matching()
        f.fund(funds, f.buyer, f.quote, 1000)
        f.fund(funds, f.seller, f.a.baseAssetId, 2)
        f.apply(funds, f.match(matching, f.admit(funds, 1, f.seller, Side.SELL, 90)))
        f.apply(funds, f.match(matching, f.admit(funds, 2, f.seller, Side.SELL, 95)))
        val result = f.match(matching, f.admit(funds, 1, quantity = 3))
        val trade = result.events.first() as TradeExecuted
        val resting = result.events.last() as OrderEnteredBook
        val malformed =
            listOf(
                f.changed(result, events = result.events.dropLast(1)),
                f.changed(result, effects = result.effects.dropLast(1)),
                f.changed(result, effects = result.effects + result.effects.last()),
                f.changed(
                    result,
                    effects =
                        result.effects.mapIndexed { index, effect ->
                            if (index ==
                                1
                            ) {
                                effect.copy(beforeQuantity = Quantity(2))
                            } else {
                                effect
                            }
                        },
                ),
                f.changed(result, events = listOf(trade.copy(price = Price(91))) + result.events.drop(1)),
                f.changed(result, events = result.events.dropLast(1) + resting.copy(remainingQuantity = Quantity(2))),
                f.changed(result, before = result.engineSequenceBefore + 1, after = result.engineSequenceAfter + 1),
                f.changed(result, input = result.input.copy(admissionSequence = result.input.admissionSequence + 1)),
                f.changed(result, reason = MatchingNotExecutedReason.ACTIVE_ORDER_LIMIT),
            )
        val before = funds.fundsRecoveryState()
        for (invalid in malformed) {
            val reply = f.apply(funds, invalid)
            assertEquals(FundsResultStatus.INVALID_RESULT, reply.status)
            assertNull(reply.ack)
            f.assertSame(before, funds.fundsRecoveryState())
            assertFalse(funds.isBlocked)
        }
        assertEquals(FundsResultStatus.APPLIED, f.apply(funds, result).status)
        f.assertBalance(funds, f.buyer, f.quote, 715, 100)
    }

    @Test
    fun `사용자 응답 ACK와 세대 회전은 금융 적용과 전달 자료를 지우지 않는다`() {
        for (rotate in listOf(false, true)) {
            val f = FundsResultTestFixture()
            val funds = f.owner()
            val matching = f.matching()
            f.fund(funds, f.buyer, f.quote, 1000)
            val input = f.admit(funds, 1, price = 300)
            val result = f.match(matching, input)
            val before = funds.fundsRecoveryState()
            if (rotate) funds.rotateEpoch(f.buyer, 0, 1) else funds.acknowledgeReceipts(f.buyer, 0, 1, 1)
            val after = funds.fundsRecoveryState()
            assertEquals(before.dispatch, after.dispatch)
            assertEquals(before.financial.liveOrders, after.financial.liveOrders)
            assertEquals(before.financial.balances, after.financial.balances)

            assertEquals(FundsResultStatus.APPLIED, f.apply(funds, result).status)

            f.assertBalance(funds, f.buyer, f.quote, 700, 300)
            assertEquals(1L, funds.matchingSettlementAck(result.stream)!!.through)
            f.assertConserved(funds)
        }
    }

    @Test
    fun `변조된 두 번째 수취 잔고의 계산 실패는 첫 체결도 실제 반영하지 않는다`() {
        val f = FundsResultTestFixture()
        val owner = f.owner()
        val matching = f.matching()
        val seller2 = UserId("seller2")
        f.fund(owner, f.buyer, f.quote, 1000)
        f.fund(owner, f.seller, f.a.baseAssetId, 1)
        f.fund(owner, seller2, f.a.baseAssetId, 1)
        f.apply(owner, f.match(matching, f.admit(owner, 1, f.seller, Side.SELL, 90)))
        f.apply(owner, f.match(matching, f.admit(owner, 1, seller2, Side.SELL, 95)))
        val result = f.match(matching, f.admit(owner, 1, quantity = 3))
        // 정상 승인·총량 상한으로는 만들 수 없는 손상을 테스트 내부에서만 주입한다.
        // 첫 maker 지급은 계산 가능하고 둘째 maker의 credit에서만 Long 범위를 넘는다.
        val data = field(owner, "bounded")!!

        @Suppress("UNCHECKED_CAST")
        val balances = field(data, "balances") as MutableMap<TradingBalanceKey, com.exchange.core.ledger.Balance>
        balances[TradingBalanceKey(seller2, f.quote)] =
            com.exchange.core.ledger
                .Balance(seller2, f.quote, Amount(Long.MAX_VALUE), Amount.ZERO)
        val before = owner.fundsRecoveryState()

        val reply = f.apply(owner, result)

        assertEquals(FundsResultStatus.INVALID_RESULT, reply.status)
        assertNull(reply.ack)
        f.assertSame(before, owner.fundsRecoveryState())
        f.assertBalance(owner, f.seller, f.quote, 0)
        f.assertBalance(owner, f.buyer, f.quote, 700, 300)
        assertFalse(owner.isBlocked)
    }

    @Test
    fun `금융 쓰기 중 오류는 부분 반영을 숨기지 않고 소유자를 차단한다`() {
        val f = FundsResultTestFixture()
        val owner = f.owner()
        val matching = f.matching()
        f.fund(owner, f.buyer, f.quote, 1000)
        f.fund(owner, f.seller, f.a.baseAssetId, 1)
        f.apply(owner, f.match(matching, f.admit(owner, 1, f.seller, Side.SELL)))
        val result = f.match(matching, f.admit(owner, 1))
        val before = owner.fundsRecoveryState()
        val data = field(owner, "bounded")!!

        @Suppress("UNCHECKED_CAST")
        val old = field(data, "balances") as Map<TradingBalanceKey, com.exchange.core.ledger.Balance>
        val failing =
            object : LinkedHashMap<TradingBalanceKey, com.exchange.core.ledger.Balance>(old) {
                var writes = 0

                override fun put(
                    key: TradingBalanceKey,
                    value: com.exchange.core.ledger.Balance,
                ): com.exchange.core.ledger.Balance? {
                    if (++writes == 2) throw IllegalStateException("injected financial write failure")
                    return super.put(key, value)
                }
            }
        data.javaClass
            .getDeclaredField("balances")
            .also { it.isAccessible = true }
            .set(data, failing)

        val failure = assertFailsWith<IllegalStateException> { f.apply(owner, result) }

        assertEquals("injected financial write failure", failure.message)
        assertTrue(owner.isBlocked)
        assertTrue(failing.values.toSet() != before.financial.balances.toSet())
        assertFailsWith<IllegalStateException> { f.apply(owner, result) }
        assertFailsWith<IllegalStateException> { owner.matchingSettlementAck(result.stream) }
        assertFailsWith<IllegalStateException> { owner.fundsRecoveryState() }
        assertFailsWith<IllegalStateException> { owner.currentBalance(f.buyer, f.quote) }
        assertFailsWith<IllegalStateException> { owner.initialize(f.manifest) }
    }

    private fun field(
        target: Any,
        name: String,
    ): Any? =
        target.javaClass
            .getDeclaredField(name)
            .also { it.isAccessible = true }
            .get(target)

    private fun FundsOrderDispatch.intent() =
        OrderIntent(
            order.marketId,
            stream.configurationVersion,
            order.side,
            order.orderType,
            order.timeInForce,
            order.price,
            order.quantity,
        )
}
