package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity

data class OrderFillSettlementPlan(
    val updatedReservation: OrderReservation,
    val reservedAmountToReduce: Amount,
    val holdAmountToConsume: Amount,
    val holdAmountToRelease: Amount,
    val creditAssetId: AssetId,
    val creditAmount: Amount,
)

class OrderFillSettlementCalculator {
    fun calculate(
        market: MarketDefinition,
        reservation: OrderReservation,
        executionPrice: Price,
        filledQuantity: Quantity,
    ): OrderFillSettlementPlan {
        require(market.marketId == reservation.marketId) {
            "reservation market must match settlement market"
        }

        return when (reservation.side) {
            Side.BUY ->
                calculateBuy(
                    market = market,
                    reservation = reservation,
                    executionPrice = executionPrice,
                    filledQuantity = filledQuantity,
                )

            Side.SELL ->
                calculateSell(
                    market = market,
                    reservation = reservation,
                    executionPrice = executionPrice,
                    filledQuantity = filledQuantity,
                )
        }
    }

    private fun calculateBuy(
        market: MarketDefinition,
        reservation: OrderReservation,
        executionPrice: Price,
        filledQuantity: Quantity,
    ): OrderFillSettlementPlan {
        require(reservation.assetId == market.quoteAssetId) {
            "BUY reservation asset must be market quote asset"
        }

        require(executionPrice <= reservation.limitPrice) {
            "BUY execution price must not exceed limit price"
        }

        val reservedAmountToReduce =
            calculateQuoteAmount(
                price = reservation.limitPrice,
                quantity = filledQuantity,
                baseAssetScale = market.baseAssetScale,
            )

        val holdAmountToConsume =
            calculateQuoteAmount(
                price = executionPrice,
                quantity = filledQuantity,
                baseAssetScale = market.baseAssetScale,
            )

        val holdAmountToRelease =
            Amount(
                reservedAmountToReduce.value - holdAmountToConsume.value,
            )

        val updatedReservation =
            reservation.applyFill(
                filledQuantity = filledQuantity,
                reservedAmountToReduce = reservedAmountToReduce,
            )

        return OrderFillSettlementPlan(
            updatedReservation = updatedReservation,
            reservedAmountToReduce = reservedAmountToReduce,
            holdAmountToConsume = holdAmountToConsume,
            holdAmountToRelease = holdAmountToRelease,
            creditAssetId = market.baseAssetId,
            creditAmount = Amount(filledQuantity.value),
        )
    }

    private fun calculateSell(
        market: MarketDefinition,
        reservation: OrderReservation,
        executionPrice: Price,
        filledQuantity: Quantity,
    ): OrderFillSettlementPlan {
        require(reservation.assetId == market.baseAssetId) {
            "SELL reservation asset must be market base asset"
        }

        require(executionPrice >= reservation.limitPrice) {
            "SELL execution price must not be below limit price"
        }

        val reservedAmountToReduce = Amount(filledQuantity.value)

        val holdAmountToRelease = Amount.ZERO

        val creditAmount =
            calculateQuoteAmount(
                price = executionPrice,
                quantity = filledQuantity,
                baseAssetScale = market.baseAssetScale,
            )

        val updatedReservation =
            reservation.applyFill(
                filledQuantity = filledQuantity,
                reservedAmountToReduce = reservedAmountToReduce,
            )

        return OrderFillSettlementPlan(
            updatedReservation = updatedReservation,
            reservedAmountToReduce = reservedAmountToReduce,
            holdAmountToConsume = reservedAmountToReduce,
            holdAmountToRelease = holdAmountToRelease,
            creditAssetId = market.quoteAssetId,
            creditAmount = creditAmount,
        )
    }
}
