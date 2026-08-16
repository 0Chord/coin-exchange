package com.exchange.core.api.order

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationNotFoundException
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderReservationStore
import org.springframework.transaction.annotation.Transactional

open class OrderReservationReleaseService(
    private val balanceStore: BalanceStore,
    private val reservationStore: OrderReservationStore,
) {
    @Transactional
    open fun release(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation {
        val reservation =
            reservationStore.findForUpdate(
                marketId = marketId,
                orderId = orderId,
            ) ?: throw OrderReservationNotFoundException(
                marketId = marketId,
                orderId = orderId,
            )

        if (reservation.status == OrderReservationStatus.RELEASED) {
            return reservation
        }

        val amountToRelease = reservation.remainingAmount
        val released = reservation.release()

        reservationStore.update(released)

        balanceStore.release(
            userId = reservation.userId,
            assetId = reservation.assetId,
            amount = amountToRelease,
        )

        return released
    }
}
