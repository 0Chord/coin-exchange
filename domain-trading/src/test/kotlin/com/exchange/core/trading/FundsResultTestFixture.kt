package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 실제 예약→전달 원본→매칭 결과를 준비한다. 기대 잔고는 각 테스트의 수기 값이다. */
internal class FundsResultTestFixture(
    makerPpm: Long = 0,
    takerPpm: Long = 0,
    val resultLimits: FundsResultLimits = FundsResultLimits(),
    limits: TradingResourceLimits = TradingResourceLimits(),
) {
    val buyer = UserId("buyer")
    val seller = UserId("seller")
    val quote = AssetId("QUOTE")
    val a =
        MarketDefinition(
            com.exchange.core.common
                .MarketId("A-QUOTE"),
            AssetId("A"),
            quote,
            0,
        )
    val b =
        MarketDefinition(
            com.exchange.core.common
                .MarketId("B-QUOTE"),
            AssetId("B"),
            quote,
            0,
        )
    val at: Instant = Instant.parse("2026-11-02T00:00:00Z")
    val fees =
        TradingFeePolicySnapshot(
            FeeProductType.SPOT,
            FeeTier.NORMAL,
            1,
            MakerTakerFeeRates(FeeRate(makerPpm), FeeRate(takerPpm)),
        )
    val contexts = listOf(TradingMarketContext(a, fees, 0), TradingMarketContext(b, fees, 1))
    val manifest =
        TradingClusterManifest(ClusterLineage(1, 2), "funds-result-test", "0".repeat(64), "1".repeat(64), 7, contexts, limits)
    val topology =
        TradingRoleTopology(manifest, contexts.map { TradingMarketAssignment(it.market.marketId, ClusterLineage(3, 4), 1) })
    val configuration = FundsDispatchConfiguration(topology)
    private val fundsDeclaration =
        TradingRoleDeclaration(
            TradingServiceRole.FUNDS,
            manifest.lineage,
            manifest.configurationVersion,
            contexts,
            contexts.associate { it.market.marketId to 1L },
        )
    private val matchingDeclaration =
        TradingRoleDeclaration(
            TradingServiceRole.MATCHING,
            ClusterLineage(3, 4),
            manifest.configurationVersion,
            contexts,
            contexts.associate { it.market.marketId to 1L },
        )
    val matchingConfiguration = MatchingIntakeConfiguration(topology, matchingDeclaration)
    val calculator: OrderSettlementCalculator
        get() {
            val feeReserve = TradingFeeReserveCalculator()
            return OrderSettlementCalculator(
                OrderFillSettlementCalculator(TradingFeeCalculator(), feeReserve),
                OrderReservationCalculator(BuyOrderFundingQuoteCalculator(feeReserve)),
                feeReserve,
                SettlementLedgerTransactions(),
            )
        }

    fun owner(initialize: Boolean = true): BoundedFundingState =
        TradingRoleAssembly
            .funds(
                topology,
                fundsDeclaration,
                OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))),
                resultLimits = resultLimits,
            ).also { if (initialize) it.initialize(manifest) }

    fun matching(limits: MatchingIntakeLimits = matchingConfiguration.limits): ReservedMatchingState =
        ReservedMatchingState(MatchingIntakeConfiguration(topology, matchingDeclaration, limits)).also { it.initialize() }

    fun fund(
        owner: BoundedFundingState,
        user: UserId,
        asset: AssetId,
        amount: Long,
    ) = owner.prepareDevelopmentBalance(OpeningBalance("${user.value}-${asset.value}", user, asset, Amount(amount)), at)

    fun admit(
        owner: BoundedFundingState,
        sequence: Long,
        user: UserId = buyer,
        side: Side = Side.BUY,
        price: Long = 100,
        quantity: Long = 1,
        market: MarketDefinition = a,
        epoch: Long = 1,
    ): FundsOrderDispatch {
        if (epoch == 1L) owner.rotateEpoch(user, 0, 0)
        val reply =
            assertIs<AdmissionReply.Recorded>(
                owner.admit(
                    RequestToken(user, 0, epoch, sequence),
                    OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity)),
                    at,
                ),
            )
        assertEquals(AdmissionCode.ACCEPTED, reply.receipt.code)
        return owner
            .pendingDispatch(configuration.stream(market.marketId), reply.receipt.marketAdmissionSequence!!, 1, 1_048_576)
            .entries
            .single()
    }

    fun match(
        matching: ReservedMatchingState,
        input: FundsOrderDispatch,
    ): MatchingResultEnvelope {
        val reply = matching.apply(input)
        assertEquals(MatchingIntakeStatus.APPLIED, reply.status)
        return reply.result!!
    }

    fun apply(
        owner: BoundedFundingState,
        result: MatchingResultEnvelope,
        time: Instant = at,
    ): FundsResultReply = owner.applyMatchingResult(result, time, calculator)

    fun assertBalance(
        owner: BoundedFundingState,
        user: UserId,
        asset: AssetId,
        available: Long,
        hold: Long = 0,
    ) = assertEquals(Balance(user, asset, Amount(available), Amount(hold)), owner.currentBalance(user, asset))

    fun assertConserved(owner: BoundedFundingState) {
        val saved = owner.fundsRecoveryState()
        for ((asset, total) in saved.assetTotals) {
            val users =
                saved.financial.balances.filter { it.assetId == asset }.fold(BigInteger.ZERO) { value, balance ->
                    value + BigInteger.valueOf(balance.available.value) + BigInteger.valueOf(balance.hold.value)
                }
            assertEquals(BigInteger.valueOf(total), users + (saved.financial.feeRevenueByAsset[asset] ?: BigInteger.ZERO))
        }
    }

    fun assertSame(
        before: FundsRoleRecoveryState,
        after: FundsRoleRecoveryState,
    ) {
        assertEquals(financialView(before.financial), financialView(after.financial))
        assertEquals(before.configuration, after.configuration)
        assertEquals(before.dispatch, after.dispatch)
        assertEquals(before.assetTotals, after.assetTotals)
        assertEquals(resultView(before.results), resultView(after.results))
    }

    private fun financialView(state: BoundedFundingRecoveryState): List<Any?> =
        listOf(
            state.manifest,
            state.lifecycle,
            state.balances,
            state.feeRevenueByAsset,
            state.usedBalances,
            state.preparations,
            state.accounts,
            state.liveOrders,
            state.markets,
            state.inbox,
            state.lastAdmissionSequence,
            state.lastPreparationSequence,
            state.preparationCanonicalBytes,
            state.incident,
        )

    private fun resultView(state: FundsResultRecoveryState?): List<Any?>? =
        state?.let {
            listOf(
                it.limits,
                it.receiptBytes,
                it.receipts.map { receipt -> receipt.result to receipt.appliedAt },
                it.streams.map { stream ->
                    listOf(
                        stream.stream,
                        stream.resultThrough,
                        stream.settledEngineSequence,
                        stream.retiredThrough,
                        stream.retiredEngineSequence,
                        stream.receiptEntries,
                        stream.receiptBytes,
                    )
                },
            )
        }

    fun changed(
        original: MatchingResultEnvelope,
        stream: MatchingResultStream = original.stream,
        sequence: Long = original.resultSequence,
        input: FundsOrderDispatch = original.input,
        outcome: MatchingOutcome = original.outcome,
        reason: MatchingNotExecutedReason? = original.reason,
        before: Long = original.engineSequenceBefore,
        after: Long = original.engineSequenceAfter,
        events: List<MatchingEvent> = original.events,
        effects: List<MatchingOrderEffect> = original.effects,
    ) = MatchingResultEnvelope(stream, sequence, input, outcome, reason, before, after, events, effects)
}
