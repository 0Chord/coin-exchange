package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.LiquidityRole
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FundsAdmissionSolvencyTest {
    private val btc = AssetId("BTC")
    private val usdt = AssetId("USDT")
    private val buyer = UserId("buyer")
    private val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 0)
    private val at = Instant.parse("2026-11-02T00:00:00Z")
    private val zeroFees = policy(0, 0)
    private val fees = policy(4_000, 7_000)
    private val reserveCalculator = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))
    private val admissionCalculator = OrderAdmissionCalculator(reserveCalculator)
    private val fillCalculator = OrderFillSettlementCalculator(TradingFeeCalculator(), TradingFeeReserveCalculator())

    @Test
    fun `303을 예약한 BUY는 세 가격 개선 체결의 수수료 나머지를 이으며 714를 남긴다`() {
        val state = fundsState(manifest(feePolicy = fees))
        fund(state, buyer, usdt, 1_000)
        state.rotateEpoch(buyer, 0, 0)
        val receipt = assertIs<AdmissionReply.Recorded>(state.admit(token(1), intent(), at)).receipt
        assertEquals(AdmissionCode.ACCEPTED, receipt.code)
        val original = requireNotNull(state.liveOrder(market.marketId, requireNotNull(receipt.orderId))).reservation
        assertEquals(Amount(303), original.reservedAmount)
        assertEquals(Amount(3), original.initialFeeReserveAmount)
        assertEquals(Balance(buyer, usdt, Amount(697), Amount(303)), state.currentBalance(buyer, usdt))

        var reservation = original
        var balance = state.currentBalance(buyer, usdt)
        val expectedFees = listOf(0L, 1L, 0L)
        val expectedRemainders = listOf(630_000L, 295_000L, 995_000L)
        val expectedFeeReserves = listOf(3L, 1L, 0L)
        val expectedReturns = listOf(10L, 6L, 1L)
        val expectedBalances = listOf(707L to 203L, 713L to 101L, 714L to 0L)
        for ((index, price) in listOf(90L, 95L, 100L).withIndex()) {
            val plan = fillCalculator.calculate(market, reservation, Price(price), Quantity(1), LiquidityRole.TAKER)
            balance = balance.consumeHold(plan.holdAmountToConsume).release(plan.holdAmountToRelease)
            reservation = plan.updatedReservation

            assertEquals(Amount(expectedFees[index]), plan.actualFeeAmount, "체결 ${index + 1}")
            assertEquals(FeeRemainder(expectedRemainders[index]), reservation.feeRemainder)
            assertEquals(Amount(expectedFeeReserves[index]), reservation.remainingFeeReserveAmount)
            assertEquals(Amount(expectedReturns[index]), plan.holdAmountToRelease)
            assertEquals(Amount(1), plan.creditAmount)
            assertEquals(
                Balance(buyer, usdt, Amount(expectedBalances[index].first), Amount(expectedBalances[index].second)),
                balance,
            )
        }
        assertEquals(OrderReservationStatus.SETTLED, reservation.status)
        assertEquals(Quantity.ZERO, reservation.remainingQuantity)
        assertEquals(Amount.ZERO, reservation.remainingAmount)
        // 순수 금융 후보 계산이며 원격 결과를 실제 자금 상태에 적용한 검사가 아니다.
        assertEquals(original, state.liveOrder(market.marketId, requireNotNull(receipt.orderId))?.reservation)
        assertEquals(Balance(buyer, usdt, Amount(697), Amount(303)), state.currentBalance(buyer, usdt))
    }

    @Test
    fun `동일 가격의 maker taker 조합이 달라도 최대 요율 예약 안에서 완료한다`() {
        val order = order(Side.BUY, 100, 3)
        val admitted =
            assertIs<AdmissionTransitionResult.Calculated>(
                admissionCalculator.calculateTransition(order, market, fees, Balance(buyer, usdt, Amount(1_000), Amount.ZERO)),
            )
        val cases =
            listOf(
                Triple(listOf(LiquidityRole.MAKER, LiquidityRole.MAKER, LiquidityRole.MAKER), 1L, 200_000L),
                Triple(listOf(LiquidityRole.TAKER, LiquidityRole.MAKER, LiquidityRole.TAKER), 1L, 800_000L),
                Triple(listOf(LiquidityRole.TAKER, LiquidityRole.TAKER, LiquidityRole.TAKER), 2L, 100_000L),
            )
        for ((roles, totalFee, remainder) in cases) {
            var reservation = admitted.reservation
            var balance = admitted.nextBalance
            var actualFees = 0L
            for (role in roles) {
                val plan = fillCalculator.calculate(market, reservation, Price(100), Quantity(1), role)
                actualFees += plan.actualFeeAmount.value
                balance = balance.consumeHold(plan.holdAmountToConsume).release(plan.holdAmountToRelease)
                reservation = plan.updatedReservation
            }

            assertEquals(Amount(303), admitted.reservation.reservedAmount)
            assertEquals(totalFee, actualFees, roles.toString())
            assertEquals(FeeRemainder(remainder), reservation.feeRemainder, roles.toString())
            assertEquals(Balance(buyer, usdt, Amount(700 - totalFee), Amount.ZERO), balance, roles.toString())
            assertEquals(OrderReservationStatus.SETTLED, reservation.status)
        }
    }

    @Test
    fun `SELL은 base만 예약하고 0퍼센트와 100퍼센트 수수료를 수취 대금 안에서 낸다`() {
        val seller = UserId("seller")
        val sellerPolicy = policy(1_000_000, 0)
        val sell = order(Side.SELL, 100, 3).copy(userId = seller)
        val admitted =
            assertIs<AdmissionTransitionResult.Calculated>(
                admissionCalculator.calculateTransition(sell, market, sellerPolicy, Balance(seller, btc, Amount(3), Amount.ZERO)),
            )
        assertEquals(btc, admitted.reservation.assetId)
        assertEquals(Amount(3), admitted.reservation.reservedAmount)
        assertEquals(Amount.ZERO, admitted.reservation.initialFeeReserveAmount)
        assertEquals(Balance(seller, btc, Amount.ZERO, Amount(3)), admitted.nextBalance)

        for ((role, expectedFee, expectedCredit) in listOf(Triple(LiquidityRole.MAKER, 330L, 0L), Triple(LiquidityRole.TAKER, 0L, 330L))) {
            val plan = fillCalculator.calculate(market, admitted.reservation, Price(110), Quantity(3), role)
            assertEquals(Amount(3), plan.reservedAmountToReduce)
            assertEquals(Amount(3), plan.holdAmountToConsume)
            assertEquals(Amount.ZERO, plan.holdAmountToRelease)
            assertEquals(usdt, plan.creditAssetId)
            assertEquals(Amount(expectedFee), plan.actualFeeAmount)
            assertEquals(Amount(expectedCredit), plan.creditAmount)
            assertEquals(330L, plan.actualFeeAmount.value + plan.creditAmount.value)
            assertEquals(OrderReservationStatus.SETTLED, plan.updatedReservation.status)
        }
    }

    @Test
    fun `비정확 시장 설정과 단위 밖 주문은 거절하고 허용 부분 체결은 정확히 표현한다`() {
        assertFailsWith<IllegalArgumentException> { market.copy(baseAssetScale = 2, priceTick = 1, quantityStep = 1) }
        val fractional = market.copy(baseAssetScale = 2, priceTick = 25, quantityStep = 4)
        val state = fundsState(manifest(marketDefinition = fractional))
        fund(state, buyer, usdt, 100)
        state.rotateEpoch(buyer, 0, 0)
        val cases =
            listOf(
                intent(price = 99, quantity = 12) to OrderAdmissionResult.Reason.UNREPRESENTABLE_RESERVATION,
                intent(price = 100, quantity = 10) to OrderAdmissionResult.Reason.UNREPRESENTABLE_RESERVATION,
                intent(price = 100, quantity = 0) to OrderAdmissionResult.Reason.INVALID_QUANTITY,
            )
        for ((index, case) in cases.withIndex()) {
            val rejected = assertIs<AdmissionReply.Recorded>(state.admit(token(index.toLong() + 1), case.first, at)).receipt
            assertEquals(AdmissionCode.REJECTED, rejected.code)
            assertEquals(case.second, rejected.reason)
            assertEquals(Balance(buyer, usdt, Amount(100), Amount.ZERO), state.currentBalance(buyer, usdt))
            assertEquals(0L, state.progress().lastAdmissionSequence)
            assertEquals(0, state.boundedRetention().liveOrders)
        }

        val accepted = assertIs<AdmissionReply.Recorded>(state.admit(token(4), intent(price = 100, quantity = 12), at)).receipt
        assertEquals(AdmissionCode.ACCEPTED, accepted.code)
        assertEquals(1L, accepted.admissionSequence)
        val reservation = requireNotNull(state.liveOrder(fractional.marketId, requireNotNull(accepted.orderId))).reservation
        assertEquals(Amount(12), reservation.reservedAmount)
        val partial = fillCalculator.calculate(fractional, reservation, Price(75), Quantity(4), LiquidityRole.TAKER)
        assertEquals(Amount(3), partial.holdAmountToConsume)
        assertEquals(Amount(1), partial.holdAmountToRelease)
        assertEquals(Amount(8), partial.updatedReservation.remainingAmount)
        assertEquals(Quantity(8), partial.updatedReservation.remainingQuantity)
    }

    @Test
    fun `BUY 대금과 수수료 포함 합계 overflow는 예약과 전달 순번 없이 기록된 거절이다`() {
        val state = fundsState(manifest(feePolicy = fees))
        fund(state, buyer, usdt, Long.MAX_VALUE)
        state.rotateEpoch(buyer, 0, 0)

        // 첫 주문은 대금 자체, 둘째는 표현 가능한 대금에 수수료를 더한 합계가 넘친다.
        for ((index, quantity) in listOf(2L, 1L).withIndex()) {
            val receipt =
                assertIs<AdmissionReply.Recorded>(
                    state.admit(token(index.toLong() + 1), intent(price = Long.MAX_VALUE, quantity = quantity), at),
                ).receipt
            assertEquals(AdmissionCode.REJECTED, receipt.code)
            assertEquals(OrderAdmissionResult.Reason.UNREPRESENTABLE_RESERVATION, receipt.reason)
            assertEquals(Balance(buyer, usdt, Amount(Long.MAX_VALUE), Amount.ZERO), state.currentBalance(buyer, usdt))
            assertEquals(0L, state.progress().lastAdmissionSequence)
            assertEquals(0, state.boundedRetention().liveOrders)
            assertEquals(0, state.boundedRetention().pendingOrders)
            assertEquals(
                0L,
                state
                    .fundsRecoveryState()
                    .dispatch.streams
                    .single()
                    .issued,
            )
        }
        assertEquals(2, state.boundedRetention().receipts)
        assertFalse(state.isBlocked)
    }

    @Test
    fun `자금 역할은 사용자들을 합친 같은 자산 총량 경계를 지키며 재전송과 다른 자산은 독립이다`() {
        val state = fundsState()
        val firstUser = UserId("first")
        val secondUser = UserId("second")
        val thirdUser = UserId("third")
        fund(state, firstUser, usdt, Long.MAX_VALUE - 100)
        val last = OpeningBalance("quote-last", secondUser, usdt, Amount(100))
        val result = state.prepareDevelopmentBalance(last, at)
        assertEquals(result.copy(alreadyPrepared = true), state.prepareDevelopmentBalance(last, at.plusSeconds(1)))
        assertEquals(Long.MAX_VALUE, state.fundsRecoveryState().assetTotals.getValue(usdt))
        val before = state.boundedRetention()

        val error = assertFailsWith<TradingFundingInputException> { fund(state, thirdUser, usdt, 1) }

        assertEquals("ASSET_TOTAL_LIMIT", error.reason.name)
        assertEquals(before, state.boundedRetention())
        assertEquals(Balance(firstUser, usdt, Amount(Long.MAX_VALUE - 100), Amount.ZERO), state.currentBalance(firstUser, usdt))
        assertEquals(Balance(secondUser, usdt, Amount(100), Amount.ZERO), state.currentBalance(secondUser, usdt))
        assertEquals(Balance(thirdUser, usdt, Amount.ZERO, Amount.ZERO), state.currentBalance(thirdUser, usdt))
        assertEquals(2L, state.fundsRecoveryState().financial.lastPreparationSequence)
        assertEquals(Long.MAX_VALUE, state.fundsRecoveryState().assetTotals.getValue(usdt))
        assertFalse(state.isBlocked)

        fund(state, thirdUser, btc, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, state.fundsRecoveryState().assetTotals.getValue(btc))
        assertEquals(Long.MAX_VALUE, state.fundsRecoveryState().assetTotals.getValue(usdt))
    }

    @Test
    fun `예약 이동과 경계 snapshot 복원 뒤에도 전체 자산 상한은 변하지 않는다`() {
        val state = fundsState()
        fund(state, buyer, usdt, Long.MAX_VALUE)
        state.rotateEpoch(buyer, 0, 0)
        val token = token(1)
        val original = assertIs<AdmissionReply.Recorded>(state.admit(token, intent(), at)).receipt
        val saved = state.fundsRecoveryState()
        assertEquals(Long.MAX_VALUE, saved.assetTotals.getValue(usdt))
        assertEquals(Balance(buyer, usdt, Amount(Long.MAX_VALUE - 300), Amount(300)), state.currentBalance(buyer, usdt))

        val restored = emptyFundsState().also { it.restoreFundsRecoveryState(saved, settlementCalculator()) }

        assertEquals(saved.assetTotals, restored.fundsRecoveryState().assetTotals)
        assertEquals(Balance(buyer, usdt, Amount(Long.MAX_VALUE - 300), Amount(300)), restored.currentBalance(buyer, usdt))
        val repeated = assertIs<AdmissionReply.Recorded>(restored.admit(token, intent(), at.plusSeconds(1)))
        assertEquals(original, repeated.receipt)
        assertFalse(repeated.newlyRecorded)
        assertEquals(1, restored.boundedRetention().liveOrders)
        val error = assertFailsWith<TradingFundingInputException> { fund(restored, UserId("extra"), usdt, 1) }
        assertEquals("ASSET_TOTAL_LIMIT", error.reason.name)
        assertEquals(saved.assetTotals, restored.fundsRecoveryState().assetTotals)
        assertFalse(restored.isBlocked)
    }

    @Test
    fun `통합용 금융 투영의 합법적인 초과 총량은 새 자금 역할로 복원하지 않는다`() {
        val definition = manifest()
        val oldOwner = BoundedFundingState(admissionCalculator, definition).also { it.initialize(definition) }
        fund(oldOwner, UserId("first"), usdt, Long.MAX_VALUE)
        fund(oldOwner, UserId("second"), usdt, 1)
        val empty = fundsState(definition).fundsRecoveryState()
        val supplied =
            FundsRoleRecoveryState(
                empty.configuration,
                oldOwner.recoveryState(),
                empty.dispatch,
                empty.assetTotals + (usdt to Long.MAX_VALUE),
            )
        val restored = emptyFundsState(definition)

        val error = assertFailsWith<IllegalArgumentException> { restored.restoreFundsRecoveryState(supplied, settlementCalculator()) }

        assertEquals("asset total limit exceeded", error.message)
        assertTrue(restored.isBlocked)
        assertFailsWith<IllegalStateException> { restored.currentBalance(UserId("first"), usdt) }
        // 기존 통합 소유자의 허용 범위와 현재 상태는 바꾸지 않는다.
        assertEquals(Balance(UserId("first"), usdt, Amount(Long.MAX_VALUE), Amount.ZERO), oldOwner.currentBalance(UserId("first"), usdt))
        assertEquals(Balance(UserId("second"), usdt, Amount(1), Amount.ZERO), oldOwner.currentBalance(UserId("second"), usdt))
        assertFalse(oldOwner.isBlocked)
    }

    @Test
    fun `복원은 저장된 자산 합계를 신뢰하지 않고 금융 원본과 대조한다`() {
        val state = fundsState()
        fund(state, buyer, usdt, 1_000)
        val saved = state.fundsRecoveryState()
        val tampered = FundsRoleRecoveryState(saved.configuration, saved.financial, saved.dispatch, saved.assetTotals + (usdt to 999L))
        val restored = emptyFundsState()

        assertFailsWith<IllegalArgumentException> { restored.restoreFundsRecoveryState(tampered, settlementCalculator()) }

        assertTrue(restored.isBlocked)
        assertFailsWith<IllegalStateException> { restored.fundsRecoveryState() }
        assertEquals(Balance(buyer, usdt, Amount(1_000), Amount.ZERO), state.currentBalance(buyer, usdt))
    }

    private fun fundsState(definition: TradingClusterManifest = manifest()) = emptyFundsState(definition).also { it.initialize(definition) }

    private fun emptyFundsState(definition: TradingClusterManifest = manifest()): BoundedFundingState {
        val assignments =
            listOf(
                TradingMarketAssignment(
                    definition.markets
                        .single()
                        .market.marketId,
                    ClusterLineage(30, 40),
                    1,
                ),
            )
        val topology = TradingRoleTopology(definition, assignments)
        val declaration =
            TradingRoleDeclaration(
                TradingServiceRole.FUNDS,
                definition.lineage,
                definition.configurationVersion,
                definition.markets,
                assignments.associate { it.marketId to it.ownershipEpoch },
            )
        return TradingRoleAssembly.funds(topology, declaration, admissionCalculator)
    }

    private fun manifest(
        marketDefinition: MarketDefinition = market,
        feePolicy: TradingFeePolicySnapshot = zeroFees,
    ) = TradingClusterManifest(
        ClusterLineage(10, 20),
        "price-time-v1",
        "a".repeat(64),
        "b".repeat(64),
        1,
        listOf(TradingMarketContext(marketDefinition, feePolicy, 0)),
        TradingResourceLimits(),
    )

    private fun fund(
        state: BoundedFundingState,
        user: UserId,
        asset: AssetId,
        amount: Long,
    ) = state.prepareDevelopmentBalance(OpeningBalance("prepare-${user.value}-${asset.value}", user, asset, Amount(amount)), at)

    private fun token(sequence: Long) = RequestToken(buyer, 0, 1, sequence)

    private fun intent(
        price: Long = 100,
        quantity: Long = 3,
    ) = OrderIntent(market.marketId, 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity))

    private fun order(
        side: Side,
        price: Long,
        quantity: Long,
    ) = OrderAdmissionInput(
        market.marketId,
        OrderId("order"),
        buyer,
        side,
        OrderType.LIMIT,
        TimeInForce.GTC,
        Price(price),
        Quantity(quantity),
    )

    private fun policy(
        maker: Long,
        taker: Long,
    ) = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(maker), FeeRate(taker)))

    private fun settlementCalculator() =
        OrderSettlementCalculator(fillCalculator, reserveCalculator, TradingFeeReserveCalculator(), SettlementLedgerTransactions())
}
