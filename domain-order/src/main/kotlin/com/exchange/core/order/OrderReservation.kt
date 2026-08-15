package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
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

enum class OrderReservationStatus {
    ACTIVE,
    SETTLED,
    RELEASED,
}

data class OrderReservation(
    val marketId: MarketId,
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val assetId: AssetId,
    val limitPrice: Price,
    val initialQuantity: Quantity,
    val remainingQuantity: Quantity,
    val reservedAmount: Amount,
    val remainingAmount: Amount,
    val status: OrderReservationStatus,
) {
    init {
        require(initialQuantity.value > 0) {
            "initialQuantity must be positive"
        }

        require(remainingQuantity <= initialQuantity) {
            "remaining quantity must not exceed initial quantity"
        }
        require(reservedAmount.value > 0) {
            "reserved amount must be positive"
        }

        require(remainingAmount <= reservedAmount) {
            "remaining amount must not exceed reserved amount"
        }

        when (status) {
            OrderReservationStatus.ACTIVE -> {
                require(!remainingQuantity.isZero()) {
                    "active reservation must have remaining quantity"
                }

                require(!remainingAmount.isZero()) {
                    "active reservation must have remaining amount"
                }
            }

            OrderReservationStatus.SETTLED -> {
                require(remainingQuantity.isZero()) {
                    "settled reservation must not have remaining quantity"
                }

                require(remainingAmount.isZero()) {
                    "settled reservation must not have remaining amount"
                }
            }

            OrderReservationStatus.RELEASED -> {
                require(remainingAmount.isZero()) {
                    "released reservation must not have remaining amount"
                }
            }
        }
    }

    companion object {
        fun create(
            marketId: MarketId,
            orderId: OrderId,
            userId: UserId,
            side: Side,
            limitPrice: Price,
            quantity: Quantity,
            requirement: ReservationRequirement,
        ): OrderReservation =
            OrderReservation(
                marketId = marketId,
                orderId = orderId,
                userId = userId,
                side = side,
                assetId = requirement.assetId,
                limitPrice = limitPrice,
                initialQuantity = quantity,
                remainingQuantity = quantity,
                reservedAmount = requirement.amount,
                remainingAmount = requirement.amount,
                status = OrderReservationStatus.ACTIVE,
            )
    }
}

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
