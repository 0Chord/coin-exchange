package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrderReservationTest {
    @Test
    fun `create는 신규 reservation을 ACTIVE 상태로 만든다`() {
        val reservation = activeReservation()

        assertEquals(MarketId("BTC-KRW"), reservation.marketId)
        assertEquals(OrderId("order-1"), reservation.orderId)
        assertEquals(UserId("user-1"), reservation.userId)
        assertEquals(Side.BUY, reservation.side)
        assertEquals(AssetId("KRW"), reservation.assetId)
        assertEquals(Price(100), reservation.limitPrice)
        assertEquals(Quantity(5), reservation.initialQuantity)
        assertEquals(Quantity(5), reservation.remainingQuantity)
        assertEquals(Amount(500), reservation.reservedAmount)
        assertEquals(Amount(500), reservation.remainingAmount)
        assertEquals(OrderReservationStatus.ACTIVE, reservation.status)
    }

    @Test
    fun `최초 주문 수량은 0보다 커야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            OrderReservation.create(
                marketId = MarketId("BTC-KRW"),
                orderId = OrderId("order-1"),
                userId = UserId("user-1"),
                side = Side.BUY,
                limitPrice = Price(100),
                quantity = Quantity.ZERO,
                requirement =
                    ReservationRequirement(
                        assetId = AssetId("KRW"),
                        amount = Amount(500),
                    ),
            )
        }
    }

    @Test
    fun `남은 수량은 최초 수량을 초과할 수 없다`() {
        val reservation = activeReservation()

        assertFailsWith<IllegalArgumentException> {
            reservation.copy(
                remainingQuantity = Quantity(6),
            )
        }
    }

    @Test
    fun `남은 금액은 최초 동결 금액을 초과할 수 없다`() {
        val reservation = activeReservation()

        assertFailsWith<IllegalArgumentException> {
            reservation.copy(
                remainingAmount = Amount(501),
            )
        }
    }

    @Test
    fun `ACTIVE reservation은 남은 수량과 금액이 필요하다`() {
        val reservation = activeReservation()

        assertFailsWith<IllegalArgumentException> {
            reservation.copy(
                remainingQuantity = Quantity.ZERO,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            reservation.copy(
                remainingAmount = Amount.ZERO,
            )
        }
    }

    @Test
    fun `SETTLED reservation은 남은 수량과 금액이 0이어야 한다`() {
        val reservation = activeReservation()

        val settled =
            reservation.copy(
                remainingQuantity = Quantity.ZERO,
                remainingAmount = Amount.ZERO,
                status = OrderReservationStatus.SETTLED,
            )

        assertEquals(Quantity.ZERO, settled.remainingQuantity)
        assertEquals(Amount.ZERO, settled.remainingAmount)
        assertEquals(OrderReservationStatus.SETTLED, settled.status)
    }

    @Test
    fun `RELEASED reservation은 남은 동결 금액이 없어야 한다`() {
        val reservation = activeReservation()

        assertFailsWith<IllegalArgumentException> {
            reservation.copy(
                status = OrderReservationStatus.RELEASED,
            )
        }

        val released =
            reservation.copy(
                remainingAmount = Amount.ZERO,
                status = OrderReservationStatus.RELEASED,
            )

        assertEquals(Amount.ZERO, released.remainingAmount)
        assertEquals(OrderReservationStatus.RELEASED, released.status)
    }

    @Test
    fun `release는 ACTIVE reservation의 남은 동결 금액을 해제한다`() {
        val reservation = activeReservation()

        val released = reservation.release()

        assertEquals(reservation.remainingQuantity, released.remainingQuantity)
        assertEquals(Amount.ZERO, released.remainingAmount)
        assertEquals(OrderReservationStatus.RELEASED, released.status)

        assertEquals(Amount(500), reservation.remainingAmount)
        assertEquals(OrderReservationStatus.ACTIVE, reservation.status)
    }

    @Test
    fun `ACTIVE가 아닌 reservation은 release할 수 없다`() {
        val active = activeReservation()

        val settled =
            active.copy(
                remainingQuantity = Quantity.ZERO,
                remainingAmount = Amount.ZERO,
                status = OrderReservationStatus.SETTLED,
            )

        val released =
            active.copy(
                remainingAmount = Amount.ZERO,
                status = OrderReservationStatus.RELEASED,
            )

        assertFailsWith<IllegalStateException> {
            settled.release()
        }

        assertFailsWith<IllegalStateException> {
            released.release()
        }
    }

    @Test
    fun `applyFill은 부분 체결 수량과 예약 금액을 차감한다`() {
        val reservation = activeReservation()

        val partiallyFilled =
            reservation.applyFill(
                filledQuantity = Quantity(2),
                reservedAmountToReduce = Amount(200),
            )

        assertEquals(Quantity(3), partiallyFilled.remainingQuantity)
        assertEquals(Amount(300), partiallyFilled.remainingAmount)
        assertEquals(
            OrderReservationStatus.ACTIVE,
            partiallyFilled.status,
        )

        // 원본 객체는 변경되지 않는다.
        assertEquals(Quantity(5), reservation.remainingQuantity)
        assertEquals(Amount(500), reservation.remainingAmount)
    }

    @Test
    fun `applyFill은 전량 체결되면 reservation을 SETTLED로 만든다`() {
        val reservation = activeReservation()

        val settled =
            reservation.applyFill(
                filledQuantity = Quantity(5),
                reservedAmountToReduce = Amount(500),
            )

        assertEquals(Quantity.ZERO, settled.remainingQuantity)
        assertEquals(Amount.ZERO, settled.remainingAmount)
        assertEquals(
            OrderReservationStatus.SETTLED,
            settled.status,
        )
    }

    @Test
    fun `체결 수량은 0보다 커야 한다`() {
        val reservation = activeReservation()

        assertFailsWith<IllegalArgumentException> {
            reservation.applyFill(
                filledQuantity = Quantity.ZERO,
                reservedAmountToReduce = Amount(100),
            )
        }
    }

    @Test
    fun `남은 수량보다 많이 체결할 수 없다`() {
        val reservation = activeReservation()

        assertFailsWith<IllegalArgumentException> {
            reservation.applyFill(
                filledQuantity = Quantity(6),
                reservedAmountToReduce = Amount(500),
            )
        }
    }

    @Test
    fun `차감할 예약 금액은 0보다 커야 한다`() {
        val reservation = activeReservation()

        assertFailsWith<IllegalArgumentException> {
            reservation.applyFill(
                filledQuantity = Quantity(1),
                reservedAmountToReduce = Amount.ZERO,
            )
        }
    }

    @Test
    fun `남은 예약 금액보다 많이 차감할 수 없다`() {
        val reservation = activeReservation()

        assertFailsWith<IllegalArgumentException> {
            reservation.applyFill(
                filledQuantity = Quantity(1),
                reservedAmountToReduce = Amount(501),
            )
        }
    }

    @Test
    fun `ACTIVE가 아닌 reservation에는 체결을 반영할 수 없다`() {
        val active = activeReservation()

        val settled =
            active.copy(
                remainingQuantity = Quantity.ZERO,
                remainingAmount = Amount.ZERO,
                status = OrderReservationStatus.SETTLED,
            )

        val released =
            active.copy(
                remainingAmount = Amount.ZERO,
                status = OrderReservationStatus.RELEASED,
            )

        assertFailsWith<IllegalStateException> {
            settled.applyFill(
                filledQuantity = Quantity(1),
                reservedAmountToReduce = Amount(100),
            )
        }

        assertFailsWith<IllegalStateException> {
            released.applyFill(
                filledQuantity = Quantity(1),
                reservedAmountToReduce = Amount(100),
            )
        }
    }

    private fun activeReservation(): OrderReservation =
        OrderReservation.create(
            marketId = MarketId("BTC-KRW"),
            orderId = OrderId("order-1"),
            userId = UserId("user-1"),
            side = Side.BUY,
            limitPrice = Price(100),
            quantity = Quantity(5),
            requirement =
                ReservationRequirement(
                    assetId = AssetId("KRW"),
                    amount = Amount(500),
                ),
        )
}
