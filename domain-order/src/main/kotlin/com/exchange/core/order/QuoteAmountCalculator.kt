package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import java.math.BigInteger

private val quoteBaseUnits =
    LongArray(19).apply {
        this[0] = 1L
        for (scale in 1 until size) this[scale] = this[scale - 1] * 10L
    }

/**
 * base 자산의 가격과 최소 단위 수량으로 quote 자산 대금을 계산한다.
 *
 * 계산식은 `price × quantity ÷ 10^baseAssetScale`이다. 예를 들어 BTC 가격이
 * 50,000,000이고 수량이 최소 단위 기준 10,000,000이며 scale이 8이면,
 * 0.1 BTC의 대금인 5,000,000을 반환한다.
 *
 * 중간 곱셈이 Long 범위 안이면 정수 연산을 사용하고, 넘으면 [BigInteger]로 계산한다. 나눗셈에
 * 나머지가 있으면 quote 자산의 최소 단위로 정확히 표현할 수 없으므로 반올림하거나
 * 버리지 않고 실패시킨다. 최종 결과가 Long 범위를 넘는 경우에도 실패한다.
 *
 * @param price base 자산 한 단위의 quote 자산 가격
 * @param quantity base 자산의 최소 단위 기준 수량
 * @param baseAssetScale base 자산 수량을 최소 단위로 표현할 때 사용하는 소수점 자릿수
 * @return quote 자산의 최소 단위 기준 금액
 * @throws IllegalArgumentException 계산 결과가 scale에 정확히 맞지 않거나 Long 범위를 초과할 경우
 */
internal fun calculateQuoteAmount(
    price: Price,
    quantity: Quantity,
    baseAssetScale: Int,
): Amount {
    if (baseAssetScale !in quoteBaseUnits.indices) {
        return calculateQuoteAmountWithBigInteger(price, quantity, baseAssetScale)
    }
    if (quantity.value > Long.MAX_VALUE / price.value) {
        return calculateQuoteAmountWithBigInteger(price, quantity, baseAssetScale)
    }
    val numerator = price.value * quantity.value
    val baseUnit = quoteBaseUnits[baseAssetScale]
    require(numerator % baseUnit == 0L) {
        "reservation amount must align with base asset scale"
    }
    return Amount(numerator / baseUnit)
}

private fun calculateQuoteAmountWithBigInteger(
    price: Price,
    quantity: Quantity,
    baseAssetScale: Int,
): Amount {
    val numerator =
        BigInteger
            .valueOf(price.value)
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
