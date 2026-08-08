package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import java.math.BigInteger

/**
 * 하나의 market을 구성하는 자산 정보.
 *
 * BTC-KRW 기준:
 * - base asset: BTC
 * - quote asset: KRW
 */
data class MarketDefinition(
    val marketId: MarketId,
    val baseAssetId: AssetId,
    val quoteAssetId: AssetId,
    val baseAssetScale: Int,
) {
    init {
        require(baseAssetId != quoteAssetId) {
            "base asset and quote asset must be different"
        }

        require(baseAssetScale in 0..18) {
            "baseAssetScale must be between 0 and 18"
        }
    }
}

/**
 * 주문을 MatchingEngine에 넣기 전에 동결해야 하는 자산과 금액.
 */
data class ReservationRequirement(
    val assetId: AssetId,
    val amount: Amount,
)

class OrderReservationCalculator {
    fun calculate(
        market: MarketDefinition,
        side: Side,
        price: Price,
        quantity: Quantity,
    ): ReservationRequirement {
        require(quantity.value > 0) {
            "reservation quantity must be positive"
        }

        return when (side) {
            Side.BUY ->
                ReservationRequirement(
                    assetId = market.quoteAssetId,
                    amount = calculateQuoteAmount(
                        price = price,
                        quantity = quantity,
                        baseAssetScale = market.baseAssetScale,
                    ),
                )

            Side.SELL ->
                ReservationRequirement(
                    assetId = market.baseAssetId,
                    amount = Amount(quantity.value),
                )
        }
    }

    private fun calculateQuoteAmount(
        price: Price,
        quantity: Quantity,
        baseAssetScale: Int,
    ): Amount {
        val numerator = BigInteger.valueOf(price.value)
            .multiply(BigInteger.valueOf(quantity.value))

        val baseUnit = BigInteger.TEN.pow(baseAssetScale)

        val quotientAndRemainder = numerator.divideAndRemainder(baseUnit)

        val quotient = quotientAndRemainder[0]
        val remainder = quotientAndRemainder[1]

        require(remainder == BigInteger.ZERO) {
            "reservation amount must align with base asset scale"
        }

        val amountValue =
            try {
                quotient.longValueExact()
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException(
                    "reservation amount overflow",
                    error,
                )
            }

        return Amount(amountValue)
    }
}
