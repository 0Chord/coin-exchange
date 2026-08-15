package com.exchange.core.api.order

import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStore
import com.exchange.core.order.Side
import org.springframework.transaction.annotation.Transactional

open class OrderFundingService(
    private val calculator: OrderReservationCalculator,
    private val balanceStore: BalanceStore,
    private val reservationStore: OrderReservationStore,
) {
    @Transactional
    open fun reserve(
        market: MarketDefinition,
        orderId: OrderId,
        userId: UserId,
        side: Side,
        limitPrice: Price,
        quantity: Quantity,
    ): OrderReservation {
        val requirement =
            calculator.calculate(
                market = market,
                side = side,
                price = limitPrice,
                quantity = quantity,
            )

        val reservation =
            OrderReservation.create(
                marketId = market.marketId,
                orderId = orderId,
                userId = userId,
                side = side,
                limitPrice = limitPrice,
                quantity = quantity,
                requirement = requirement,
            )

        reservationStore.create(reservation)

        balanceStore.reserve(
            userId = userId,
            assetId = requirement.assetId,
            amount = requirement.amount,
        )

        return reservation
    }
}
