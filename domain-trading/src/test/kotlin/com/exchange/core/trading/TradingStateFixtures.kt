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
import com.exchange.core.fee.FeeTier
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
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.time.Instant
import kotlin.test.assertIs

internal object TradingStateFixtures {
    val btc = AssetId("BTC")
    val usdt = AssetId("USDT")
    val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 0)
    val buyer = UserId("B")
    val seller = UserId("S")
    val time = Instant.parse("2026-10-25T00:00:00.123456789Z")
    val zeroPolicy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))

    fun state(): TradingState {
        val feeReserve = TradingFeeReserveCalculator()
        val reservation = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(feeReserve))
        return TradingState(
            OrderAdmissionCalculator(reservation),
            OrderSettlementCalculator(
                OrderFillSettlementCalculator(TradingFeeCalculator(), feeReserve),
                reservation,
                feeReserve,
                SettlementLedgerTransactions(),
            ),
        )
    }

    fun order(
        id: String,
        user: UserId,
        side: Side,
        price: Long,
        quantity: Long,
        definition: MarketDefinition = market,
    ) = OrderAdmissionInput(
        definition.marketId,
        OrderId(id),
        user,
        side,
        OrderType.LIMIT,
        TimeInForce.GTC,
        Price(price),
        Quantity(quantity),
    )

    fun fund(
        state: TradingState,
        user: UserId,
        asset: AssetId,
        amount: Long,
        preparationId: String = "fund-${user.value}-${asset.value}",
        ledgerId: String = "opening-$preparationId",
    ) = state.prepareDevelopmentBalance(OpeningBalance(preparationId, user, asset, Amount(amount)), ledgerId, time)

    fun admit(
        state: TradingState,
        input: OrderAdmissionInput,
        definition: MarketDefinition = market,
        policy: TradingFeePolicySnapshot = zeroPolicy,
        requestId: String = "request-${input.orderId.value}",
    ): OrderAdmissionState.Result.Accepted =
        assertIs<OrderAdmissionState.Result.Accepted>(
            state.admit(requestId, input, definition, policy, "reserve-${input.orderId.value}", time),
        )

    fun balance(
        user: UserId,
        asset: AssetId,
        available: Long,
        hold: Long = 0,
    ) = Balance(user, asset, Amount(available), Amount(hold))
}
