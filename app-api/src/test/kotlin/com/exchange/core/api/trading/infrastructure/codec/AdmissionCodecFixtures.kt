package com.exchange.core.api.trading.infrastructure.codec

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
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionState
import java.time.Instant

internal fun admissionCommand(
    requestId: String = "R1",
    orderId: String = "O1",
): AdmissionCommandPayload =
    AdmissionCommandPayload(
        requestId,
        OrderAdmissionInput(
            MarketId("BTC-USDT"),
            OrderId(orderId),
            UserId("user-1"),
            Side.BUY,
            OrderType.LIMIT,
            TimeInForce.GTC,
            Price(100_000_000),
            Quantity(300_000_000),
        ),
        MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), AssetId("USDT"), 8),
        TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)),
        "ledger-$orderId",
        Instant.parse("2026-10-16T00:00:00.123456789Z"),
    )

internal fun admissionState(available: Long = 1_000_000_000): OrderAdmissionState =
    OrderAdmissionState(
        listOf(Balance(UserId("user-1"), AssetId("USDT"), Amount(available), Amount.ZERO)),
        admissionCalculator(),
    )

internal fun admissionCalculator(): OrderAdmissionCalculator =
    OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())))

internal fun admit(
    state: OrderAdmissionState,
    command: AdmissionCommandPayload,
): OrderAdmissionState.Result =
    state.admit(
        command.requestId,
        command.order,
        command.market,
        command.feePolicySnapshot,
        command.ledgerTransactionId,
        command.occurredAt,
    )
