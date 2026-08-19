package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import java.math.BigInteger

internal fun calculateQuoteAmount(
    price: Price,
    quantity: Quantity,
    baseAssetScale: Int,
): Amount {
    val numerator =
        BigInteger.valueOf(price.value)
            .multiply(BigInteger.valueOf(quantity.value))

    val baseUnit = BigInteger.TEN.pow(baseAssetScale)
    val quotientAndRemainder =
        numerator.divideAndRemainder(baseUnit)

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
