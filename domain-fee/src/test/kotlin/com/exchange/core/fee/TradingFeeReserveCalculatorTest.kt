package com.exchange.core.fee

import com.exchange.core.common.Amount
import kotlin.test.Test
import kotlin.test.assertEquals

class TradingFeeReserveCalculatorTest {
    private val calculator = TradingFeeReserveCalculator()

    @Test
    fun `1억원 예산에 1퍼센트 수수료를 예약한다`() {
        assertEquals(
            Amount(1_000_000),
            calculator.calculateReserve(
                feeReserveBaseAmount = Amount(100_000_000),
                maximumFeeRate = FeeRate(10_000),
            ),
        )
    }

    @Test
    fun `최소 화폐 단위의 일부라도 필요하면 올림해서 예약한다`() {
        assertEquals(
            Amount(2),
            calculator.calculateReserve(
                feeReserveBaseAmount = Amount(1_001),
                maximumFeeRate = FeeRate(1_000),
            ),
        )
    }

    @Test
    fun `무료 정책이면 수수료를 예약하지 않는다`() {
        assertEquals(
            Amount.ZERO,
            calculator.calculateReserve(
                feeReserveBaseAmount = Amount(100_000_000),
                maximumFeeRate = FeeRate.ZERO,
            ),
        )
    }

    @Test
    fun `Long 최대 금액도 중간 곱셈 overflow 없이 계산한다`() {
        assertEquals(
            Amount(Long.MAX_VALUE),
            calculator.calculateReserve(
                feeReserveBaseAmount = Amount(Long.MAX_VALUE),
                maximumFeeRate = FeeRate(1_000_000),
            ),
        )
    }
}