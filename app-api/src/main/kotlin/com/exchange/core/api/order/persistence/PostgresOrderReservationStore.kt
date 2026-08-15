package com.exchange.core.api.order.persistence

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationAlreadyExistsException
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderReservationStore
import com.exchange.core.order.Side
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

open class PostgresOrderReservationStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : OrderReservationStore {
    override fun create(reservation: OrderReservation) {
        try {
            jdbcTemplate.update(
                """
                insert into order_reservations (
                    market_id,
                    order_id,
                    user_id,
                    side,
                    asset_id,
                    limit_price,
                    initial_quantity,
                    remaining_quantity,
                    reserved_amount,
                    remaining_amount,
                    status
                ) values (
                    :marketId,
                    :orderId,
                    :userId,
                    :side,
                    :assetId,
                    :limitPrice,
                    :initialQuantity,
                    :remainingQuantity,
                    :reservedAmount,
                    :remainingAmount,
                    :status
                )
                """.trimIndent(),
                mapOf(
                    "marketId" to reservation.marketId.value,
                    "orderId" to reservation.orderId.value,
                    "userId" to reservation.userId.value,
                    "side" to reservation.side.name,
                    "assetId" to reservation.assetId.value,
                    "limitPrice" to reservation.limitPrice.value,
                    "initialQuantity" to reservation.initialQuantity.value,
                    "remainingQuantity" to reservation.remainingQuantity.value,
                    "reservedAmount" to reservation.reservedAmount.value,
                    "remainingAmount" to reservation.remainingAmount.value,
                    "status" to reservation.status.name,
                ),
            )
        } catch (error: DuplicateKeyException) {
            throw OrderReservationAlreadyExistsException(
                marketId = reservation.marketId,
                orderId = reservation.orderId,
            )
        }
    }

    override fun find(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation? =
        jdbcTemplate
            .query(
                """
                select market_id,
                       order_id,
                       user_id,
                       side,
                       asset_id,
                       limit_price,
                       initial_quantity,
                       remaining_quantity,
                       reserved_amount,
                       remaining_amount,
                       status
                from order_reservations
                where market_id = :marketId
                  and order_id = :orderId
                """.trimIndent(),
                mapOf(
                    "marketId" to marketId.value,
                    "orderId" to orderId.value,
                ),
                orderReservationRowMapper,
            ).singleOrNull()

    private val orderReservationRowMapper =
        RowMapper<OrderReservation> { resultSet, _ ->
            OrderReservation(
                marketId = MarketId(resultSet.getString("market_id")),
                orderId = OrderId(resultSet.getString("order_id")),
                userId = UserId(resultSet.getString("user_id")),
                side = Side.valueOf(resultSet.getString("side")),
                assetId = AssetId(resultSet.getString("asset_id")),
                limitPrice = Price(resultSet.getLong("limit_price")),
                initialQuantity = Quantity(resultSet.getLong("initial_quantity")),
                remainingQuantity = Quantity(resultSet.getLong("remaining_quantity")),
                reservedAmount = Amount(resultSet.getLong("reserved_amount")),
                remainingAmount = Amount(resultSet.getLong("remaining_amount")),
                status = OrderReservationStatus.valueOf(resultSet.getString("status")),
            )
        }
}
