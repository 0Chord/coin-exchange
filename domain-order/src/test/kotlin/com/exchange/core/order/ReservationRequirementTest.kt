package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReservationRequirementTest {
    @Test
    fun `거래 예약액과 수수료 예약액을 합해 총 예약액을 계산한다`() {
        val requirement =
            ReservationRequirement(
                assetId = AssetId("KRW"),
                tradeReserveAmount = Amount(99_000_000),
                feeReserveAmount = Amount(990_000),
            )

        assertEquals(
            Amount(99_990_000),
            requirement.totalReserveAmount,
        )
    }

    @Test
    fun `수수료 예약이 없으면 거래 예약액이 총 예약액이다`() {
        val requirement =
            ReservationRequirement(
                assetId = AssetId("BTC"),
                tradeReserveAmount = Amount(100_000_000),
                feeReserveAmount = Amount.ZERO,
            )

        assertEquals(
            Amount(100_000_000),
            requirement.totalReserveAmount,
        )
    }

    @Test
    fun `거래 예약액과 수수료 예약액의 합이 Long 범위를 넘으면 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            ReservationRequirement(
                assetId = AssetId("KRW"),
                tradeReserveAmount = Amount(Long.MAX_VALUE),
                feeReserveAmount = Amount(1),
            )
        }
    }
}
