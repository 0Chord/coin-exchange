package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BalanceTest {
    @Test
    fun `reserve는 available을 줄이고 hold를 늘린다`() {
        val balance = Balance(
            userId = UserId("user-1"),
            assetId = AssetId("KRW"),
            available = Amount(1_000),
            hold = Amount.ZERO,
        )

        val reserved = balance.reserve(
            amount = Amount(400),
        )

        assertEquals(Amount(600), reserved.available)
        assertEquals(Amount(400), reserved.hold)

        assertEquals(Amount(1_000), balance.available)
        assertEquals(Amount.ZERO, balance.hold)
    }

    @Test
    fun `available보다 큰 금액은 reserve할 수 없다`() {
        val balance = Balance(
            userId = UserId("user-1"),
            assetId = AssetId("KRW"),
            available = Amount(300),
            hold = Amount(200),
        )

        val error = assertFailsWith<InsufficientBalanceException> {
            balance.reserve(
                amount = Amount(400),
            )
        }

        assertEquals(UserId("user-1"), error.userId)
        assertEquals(AssetId("KRW"), error.assetId)
        assertEquals(Amount(300), error.available)
        assertEquals(Amount(400), error.requested)

        assertEquals(Amount(300), balance.available)
        assertEquals(Amount(200), balance.hold)
    }

    @Test
    fun `release는 hold를 줄이고 available을 늘린다`() {
        val balance = Balance(
            userId = UserId("user-1"),
            assetId = AssetId("KRW"),
            available = Amount(600),
            hold = Amount(400),
        )

        val released = balance.release(
            amount = Amount(150),
        )

        assertEquals(Amount(750), released.available)
        assertEquals(Amount(250), released.hold)

        assertEquals(Amount(600), balance.available)
        assertEquals(Amount(400), balance.hold)
    }

    @Test
    fun `hold보다 큰 금액은 release할 수 없다`() {
        val balance = Balance(
            userId = UserId("user-1"),
            assetId = AssetId("KRW"),
            available = Amount(600),
            hold = Amount(100),
        )

        val error = assertFailsWith<InsufficientHoldException> {
            balance.release(
                amount = Amount(200),
            )
        }

        assertEquals(UserId("user-1"), error.userId)
        assertEquals(AssetId("KRW"), error.assetId)
        assertEquals(Amount(100), error.hold)
        assertEquals(Amount(200), error.requested)

        // 실패해도 기존 객체는 변경되지 않는다.
        assertEquals(Amount(600), balance.available)
        assertEquals(Amount(100), balance.hold)
    }
}
