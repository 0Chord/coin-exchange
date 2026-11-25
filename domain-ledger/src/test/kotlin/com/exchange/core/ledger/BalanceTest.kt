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
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(1_000),
                hold = Amount.ZERO,
            )
        val original = balance.copy()

        val reserved =
            balance.reserve(
                amount = Amount(400),
            )

        assertEquals(Amount(600), reserved.available)
        assertEquals(Amount(400), reserved.hold)

        assertEquals(original.userId, reserved.userId)
        assertEquals(original.assetId, reserved.assetId)
        assertEquals(original, balance)
    }

    @Test
    fun `available보다 큰 금액은 reserve할 수 없다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(300),
                hold = Amount(200),
            )
        val original = balance.copy()

        val error =
            assertFailsWith<InsufficientBalanceException> {
                balance.reserve(
                    amount = Amount(400),
                )
            }

        assertEquals(UserId("user-1"), error.userId)
        assertEquals(AssetId("KRW"), error.assetId)
        assertEquals(Amount(300), error.available)
        assertEquals(Amount(400), error.requested)

        assertEquals(original, balance)
    }

    @Test
    fun `release는 hold를 줄이고 available을 늘린다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(600),
                hold = Amount(400),
            )
        val original = balance.copy()

        val released =
            balance.release(
                amount = Amount(150),
            )

        assertEquals(Amount(750), released.available)
        assertEquals(Amount(250), released.hold)

        assertEquals(original.userId, released.userId)
        assertEquals(original.assetId, released.assetId)
        assertEquals(original, balance)
    }

    @Test
    fun `hold보다 큰 금액은 release할 수 없다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(600),
                hold = Amount(100),
            )
        val original = balance.copy()

        val error =
            assertFailsWith<InsufficientHoldException> {
                balance.release(
                    amount = Amount(200),
                )
            }

        assertEquals(UserId("user-1"), error.userId)
        assertEquals(AssetId("KRW"), error.assetId)
        assertEquals(Amount(100), error.hold)
        assertEquals(Amount(200), error.requested)

        // 실패해도 기존 객체는 변경되지 않는다.
        assertEquals(original, balance)
    }

    @Test
    fun `consumeHold는 hold만 감소시킨다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(600),
                hold = Amount(400),
            )
        val original = balance.copy()

        val consumed =
            balance.consumeHold(
                amount = Amount(150),
            )

        assertEquals(Amount(600), consumed.available)
        assertEquals(Amount(250), consumed.hold)

        assertEquals(original.userId, consumed.userId)
        assertEquals(original.assetId, consumed.assetId)
        assertEquals(original, balance)
    }

    @Test
    fun `hold보다 큰 금액은 consumeHold할 수 없다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(600),
                hold = Amount(100),
            )
        val original = balance.copy()

        val error =
            assertFailsWith<InsufficientHoldException> {
                balance.consumeHold(
                    amount = Amount(200),
                )
            }

        assertEquals(UserId("user-1"), error.userId)
        assertEquals(AssetId("KRW"), error.assetId)
        assertEquals(Amount(100), error.hold)
        assertEquals(Amount(200), error.requested)

        assertEquals(original, balance)
    }

    @Test
    fun `credit은 available만 증가시킨다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("BTC"),
                available = Amount(10),
                hold = Amount(5),
            )
        val original = balance.copy()

        val credited =
            balance.credit(
                amount = Amount(3),
            )

        assertEquals(Amount(13), credited.available)
        assertEquals(Amount(5), credited.hold)

        assertEquals(original.userId, credited.userId)
        assertEquals(original.assetId, credited.assetId)
        assertEquals(original, balance)
    }

    @Test
    fun `credit 결과가 Long 범위를 초과하면 실패한다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(Long.MAX_VALUE),
                hold = Amount.ZERO,
            )
        val original = balance.copy()

        assertFailsWith<ArithmeticException> {
            balance.credit(
                amount = Amount(1),
            )
        }

        assertEquals(original, balance)
    }

    @Test
    fun `available 전액을 reserve하면 available만 0이 된다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(7),
                hold = Amount(2),
            )
        val original = balance.copy()

        val reserved = balance.reserve(Amount(7))

        assertEquals(
            original.copy(available = Amount.ZERO, hold = Amount(9)),
            reserved,
        )
        assertEquals(original, balance)
    }

    @Test
    fun `hold 전액을 release하면 hold만 0이 된다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(2),
                hold = Amount(7),
            )
        val original = balance.copy()

        val released = balance.release(Amount(7))

        assertEquals(
            original.copy(available = Amount(9), hold = Amount.ZERO),
            released,
        )
        assertEquals(original, balance)
    }

    @Test
    fun `hold 전액을 consumeHold하면 available은 유지하고 hold가 0이 된다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(2),
                hold = Amount(7),
            )
        val original = balance.copy()

        val consumed = balance.consumeHold(Amount(7))

        assertEquals(
            original.copy(hold = Amount.ZERO),
            consumed,
        )
        assertEquals(original, balance)
    }

    @Test
    fun `reserve의 hold 덧셈이 Long 범위를 초과하면 원본을 유지한다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(1),
                hold = Amount(Long.MAX_VALUE),
            )
        val original = balance.copy()

        assertFailsWith<ArithmeticException> {
            balance.reserve(Amount(1))
        }

        assertEquals(original, balance)
    }

    @Test
    fun `release의 available 덧셈이 Long 범위를 초과하면 원본을 유지한다`() {
        val balance =
            Balance(
                userId = UserId("user-1"),
                assetId = AssetId("KRW"),
                available = Amount(Long.MAX_VALUE),
                hold = Amount(1),
            )
        val original = balance.copy()

        assertFailsWith<ArithmeticException> {
            balance.release(Amount(1))
        }

        assertEquals(original, balance)
    }
}
