package com.exchange.core.trading

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

/** 잔고 소유자를 만들지 않고, 예약 요구량 계산으로 승인 원본만 만드는 테스트 준비다. */
internal class MatchingIntakeTestFixture(
    val identifierBytes: Int = 256,
    priceTick: Long = 1,
    quantityStep: Long = 1,
) {
    val funds = ClusterLineage(1, 2)
    val matching = ClusterLineage(3, 4)
    val quote = AssetId("QUOTE")
    val a = MarketDefinition(MarketId("A-QUOTE"), AssetId("A"), quote, 0, priceTick, quantityStep)
    val b = MarketDefinition(MarketId("B-QUOTE"), AssetId("B"), quote, 0, priceTick, quantityStep)
    val fees = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
    val contexts = listOf(TradingMarketContext(a, fees, 0), TradingMarketContext(b, fees, 1))
    val manifest =
        TradingClusterManifest(
            funds,
            "matching-intake-test",
            "0".repeat(64),
            "1".repeat(64),
            7,
            contexts,
            TradingResourceLimits(maxIdentifierBytes = identifierBytes),
        )
    val topology = TradingRoleTopology(manifest, contexts.map { TradingMarketAssignment(it.market.marketId, matching, 1) })
    val declaration =
        TradingRoleDeclaration(TradingServiceRole.MATCHING, matching, 7, contexts, contexts.associate { it.market.marketId to 1L })
    private val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))

    fun configuration(limits: MatchingIntakeLimits = MatchingIntakeLimits.defaults(topology, declaration)) =
        MatchingIntakeConfiguration(topology, declaration, limits)

    fun owner(limits: MatchingIntakeLimits = MatchingIntakeLimits.defaults(topology, declaration)) =
        ReservedMatchingState(configuration(limits)).also { it.initialize() }

    fun limits(
        resultEntries: Int = 32,
        resultBytes: Long = 1_048_576,
        activeOrders: Int = 32,
        activeBytes: Long = 1_048_576,
        maxSingleResultBytes: Long = 1_048_576,
        maxResultEvents: Int = 32,
        maxCandidateEvents: Int = maxResultEvents,
        marketResultEntries: Int = resultEntries,
        marketResultBytes: Long = resultBytes,
        marketActiveOrders: Int = activeOrders,
    ) = MatchingIntakeLimits(
        contexts.associate {
            it.market.marketId to
                MarketMatchingIntakeLimits(marketResultEntries, marketResultBytes, marketActiveOrders, activeBytes)
        },
        maxResultEntries = resultEntries,
        maxResultBytes = resultBytes,
        maxActiveOrders = activeOrders,
        maxActiveBytes = activeBytes,
        maxSingleResultBytes = maxSingleResultBytes,
        maxResultEvents = maxResultEvents,
        maxCandidateEvents = maxCandidateEvents,
    )

    fun input(
        dispatch: Long,
        side: Side = Side.BUY,
        price: Long = 100,
        quantity: Long = 3,
        admission: Long = dispatch,
        market: MarketDefinition = a,
        user: String = "buyer",
    ): FundsOrderDispatch {
        val context = contexts.single { it.market.marketId == market.marketId }
        val order =
            OrderAdmissionInput(
                market.marketId,
                TradingIdentifiers.order(funds, admission),
                UserId(user),
                side,
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(price),
                Quantity(quantity),
            )
        val requirement = reservations.calculate(market, side, order.price, order.quantity, fees)
        val reservation =
            OrderReservation.create(market.marketId, order.orderId, order.userId, side, order.price, order.quantity, requirement, fees)
        return FundsOrderDispatch(
            FundsDispatchStream(funds, matching, market.marketId, 1, 7),
            dispatch,
            admission,
            dispatch,
            order,
            reservation,
            context,
        )
    }
}
