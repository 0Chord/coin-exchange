package com.exchange.core.fee

import com.exchange.core.common.Amount
import kotlin.test.Test
import kotlin.test.assertEquals

class TradingFeeCalculatorTest {
    private val calculator = TradingFeeCalculator()

    @Test
    fun `체결 금액 9900만원에 1퍼센트 수수료를 계산한다`() {
        assertEquals(
            Amount(990_000),
            calculator.calculateFee(
                feeBaseAmount = Amount(99_000_000),
                feeRate = FeeRate(10_000),
            ),
        )
    }

    @Test
    fun `동일한 체결 금액에 maker 수수료율을 계산한다`() {
        assertEquals(
            Amount(792_000),
            calculator.calculateFee(
                feeBaseAmount = Amount(99_000_000),
                feeRate = FeeRate(8_000),
            ),
        )
    }

    @Test
    fun `수수료율이 0이면 체결 금액과 관계없이 수수료도 0이다`() {
        assertEquals(
            Amount.ZERO,
            calculator.calculateFee(
                feeBaseAmount = Amount(99_000_000),
                feeRate = FeeRate.ZERO,
            ),
        )
    }

    @Test
    fun `최소 화폐 단위보다 작은 수수료는 버림한다`() {
        assertEquals(
            Amount.ZERO,
            calculator.calculateFee(
                feeBaseAmount = Amount(999),
                feeRate = FeeRate(1_000),
            ),
        )
    }

    @Test
    fun `Long 최대 금액도 중간 곱셈 overflow 없이 계산한다`() {
        assertEquals(
            Amount(Long.MAX_VALUE),
            calculator.calculateFee(
                feeBaseAmount = Amount(Long.MAX_VALUE),
                feeRate = FeeRate(1_000_000),
            ),
        )
    }
}
