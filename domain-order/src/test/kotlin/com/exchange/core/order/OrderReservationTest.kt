package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrderReservationTest {
    private val feeFreePolicySnapshot =
        TradingFeePolicySnapshot(
            productType = FeeProductType.SPOT,
            feeTier = FeeTier.NORMAL,
            scheduleVersion = 1,
            feeRates =
                MakerTakerFeeRates(
                    makerFeeRate = FeeRate.ZERO,
                    takerFeeRate = FeeRate.ZERO,
                ),
        )

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
                feePolicySnapshot = feeFreePolicySnapshot,
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
                tradeReserveAmountToReduce = Amount(200),
                feeReserveAmountToReduce = Amount.ZERO,
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
                tradeReserveAmountToReduce = Amount(500),
                feeReserveAmountToReduce = Amount.ZERO,
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
                tradeReserveAmountToReduce = Amount(100),
                feeReserveAmountToReduce = Amount.ZERO,
            )
        }
    }

    @Test
    fun `남은 수량보다 많이 체결할 수 없다`() {
        val reservation = activeReservation()

        assertFailsWith<IllegalArgumentException> {
            reservation.applyFill(
                filledQuantity = Quantity(6),
                tradeReserveAmountToReduce = Amount(500),
                feeReserveAmountToReduce = Amount.ZERO,
            )
        }
    }

    @Test
    fun `차감할 예약 금액은 0보다 커야 한다`() {
        val reservation = activeReservation()

        assertFailsWith<IllegalArgumentException> {
            reservation.applyFill(
                filledQuantity = Quantity(1),
                tradeReserveAmountToReduce = Amount.ZERO,
                feeReserveAmountToReduce = Amount.ZERO,
            )
        }
    }

    @Test
    fun `남은 예약 금액보다 많이 차감할 수 없다`() {
        val reservation = activeReservation()

        assertFailsWith<IllegalArgumentException> {
            reservation.applyFill(
                filledQuantity = Quantity(1),
                tradeReserveAmountToReduce = Amount(501),
                feeReserveAmountToReduce = Amount.ZERO,
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
                tradeReserveAmountToReduce = Amount(100),
                feeReserveAmountToReduce = Amount.ZERO,
            )
        }

        assertFailsWith<IllegalStateException> {
            released.applyFill(
                filledQuantity = Quantity(1),
                tradeReserveAmountToReduce = Amount(100),
                feeReserveAmountToReduce = Amount.ZERO,
            )
        }
    }

    @Test
    fun `create는 주문 당시 수수료 정책과 예약액을 보존한다`() {
        val feePolicySnapshot =
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 1,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate(5_000),
                        takerFeeRate = FeeRate(10_000),
                    ),
            )

        val reservation =
            OrderReservation.create(
                marketId = MarketId("BTC-KRW"),
                orderId = OrderId("order-with-fee"),
                userId = UserId("user-1"),
                side = Side.BUY,
                limitPrice = Price(100),
                quantity = Quantity(5),
                requirement =
                    ReservationRequirement(
                        assetId = AssetId("KRW"),
                        tradeReserveAmount = Amount(500),
                        feeReserveAmount = Amount(5),
                    ),
                feePolicySnapshot = feePolicySnapshot,
            )

        assertEquals(
            feePolicySnapshot,
            reservation.feePolicySnapshot,
        )
        assertEquals(
            Amount(5),
            reservation.initialFeeReserveAmount,
        )
        assertEquals(
            Amount(5),
            reservation.remainingFeeReserveAmount,
        )
        assertEquals(
            Amount(505),
            reservation.reservedAmount,
        )
        assertEquals(
            Amount(505),
            reservation.remainingAmount,
        )
    }

    @Test
    fun `release는 남은 수수료 예약액도 함께 해제한다`() {
        val feePolicySnapshot =
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 1,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate(5_000),
                        takerFeeRate = FeeRate(10_000),
                    ),
            )

        val reservation =
            OrderReservation.create(
                marketId = MarketId("BTC-KRW"),
                orderId = OrderId("order-with-fee"),
                userId = UserId("user-1"),
                side = Side.BUY,
                limitPrice = Price(100),
                quantity = Quantity(5),
                requirement =
                    ReservationRequirement(
                        assetId = AssetId("KRW"),
                        tradeReserveAmount = Amount(500),
                        feeReserveAmount = Amount(5),
                    ),
                feePolicySnapshot = feePolicySnapshot,
            )

        val released = reservation.release()

        assertEquals(
            Amount.ZERO,
            released.remainingAmount,
        )
        assertEquals(
            Amount.ZERO,
            released.remainingFeeReserveAmount,
        )
        assertEquals(
            OrderReservationStatus.RELEASED,
            released.status,
        )
    }

    @Test
    fun `applyFill은 거래 예약액과 수수료 예약액을 함께 감소시킨다`() {
        val feePolicySnapshot =
            TradingFeePolicySnapshot(
                productType = FeeProductType.SPOT,
                feeTier = FeeTier.NORMAL,
                scheduleVersion = 1,
                feeRates =
                    MakerTakerFeeRates(
                        makerFeeRate = FeeRate(5_000),
                        takerFeeRate = FeeRate(10_000),
                    ),
            )

        val reservation =
            OrderReservation.create(
                marketId = MarketId("BTC-KRW"),
                orderId = OrderId("order-with-fee"),
                userId = UserId("user-1"),
                side = Side.BUY,
                limitPrice = Price(100),
                quantity = Quantity(5),
                requirement =
                    ReservationRequirement(
                        assetId = AssetId("KRW"),
                        tradeReserveAmount = Amount(500),
                        feeReserveAmount = Amount(5),
                    ),
                feePolicySnapshot = feePolicySnapshot,
            )

        val updated =
            reservation.applyFill(
                filledQuantity = Quantity(2),
                tradeReserveAmountToReduce = Amount(200),
                feeReserveAmountToReduce = Amount(2),
            )

        assertEquals(
            Quantity(3),
            updated.remainingQuantity,
        )
        assertEquals(
            Amount(303),
            updated.remainingAmount,
        )
        assertEquals(
            Amount(3),
            updated.remainingFeeReserveAmount,
        )
        assertEquals(
            OrderReservationStatus.ACTIVE,
            updated.status,
        )
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
            feePolicySnapshot = feeFreePolicySnapshot,
        )
}
