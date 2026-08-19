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
import com.exchange.core.order.OrderReservationNotFoundException
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderReservationStore
import com.exchange.core.order.Side
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * `order_reservations` 테이블을 사용하는 [OrderReservationStore] PostgreSQL 구현체.
 *
 * `(market_id, order_id)`를 주문 예약의 business key로 사용한다. 생성 시에는 최초 값 전체를
 * 저장하고, 이후 체결 또는 취소에서는 변경 가능한 `remaining_quantity`, `remaining_amount`,
 * `status`만 갱신한다.
 *
 * @property jdbcTemplate 이름 기반 SQL parameter와 row mapping을 제공하는 Spring JDBC 도구
 */
open class PostgresOrderReservationStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : OrderReservationStore {
    /**
     * 새 주문 예약의 최초 상태를 insert한다.
     *
     * @param reservation [OrderReservation.create]로 만든 ACTIVE 예약
     * @throws OrderReservationAlreadyExistsException 같은 business key가 이미 존재하는 경우
     */
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

    /**
     * 잠금 없이 현재 주문 예약 snapshot을 조회한다.
     *
     * @param marketId 주문 마켓
     * @param orderId 주문 식별자
     * @return 저장된 예약. 없으면 `null`
     */
    override fun find(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation? =
        queryReservation(
            marketId = marketId,
            orderId = orderId,
            lockForUpdate = false,
        )

    /**
     * 변경 예정인 주문 예약을 PostgreSQL row lock과 함께 조회한다.
     *
     * 반환된 객체 자체가 lock을 들고 있는 것은 아니다. 호출 중인 Spring 트랜잭션이 끝날
     * 때까지 DB row lock이 유지되므로 같은 트랜잭션 안에서 [update]해야 한다.
     *
     * @param marketId 주문 마켓
     * @param orderId 주문 식별자
     * @return 잠금 조회한 예약. 없으면 `null`
     */
    override fun findForUpdate(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation? =
        queryReservation(
            marketId = marketId,
            orderId = orderId,
            lockForUpdate = true,
        )

    /**
     * 체결 또는 취소로 바뀐 잔량, 예약 잔액과 상태를 갱신한다.
     *
     * 주문 소유자, 방향, 지정가, 최초 수량과 최초 예약 금액은 생성 후 불변이므로
     * UPDATE하지 않는다.
     *
     * @param reservation 동일 business key의 새 상태
     * @throws OrderReservationNotFoundException UPDATE 대상 row가 정확히 1개가 아닌 경우
     */
    override fun update(reservation: OrderReservation) {
        // business key로 갱신해 정확히 하나의 주문 예약만 변경한다.
        val updatedRows =
            jdbcTemplate.update(
                """
                update order_reservations
                set remaining_quantity = :remainingQuantity,
                    remaining_amount = :remainingAmount,
                    status = :status,
                    updated_at = current_timestamp
                where market_id = :marketId
                  and order_id = :orderId
                """.trimIndent(),
                mapOf(
                    "marketId" to reservation.marketId.value,
                    "orderId" to reservation.orderId.value,
                    "remainingQuantity" to reservation.remainingQuantity.value,
                    "remainingAmount" to reservation.remainingAmount.value,
                    "status" to reservation.status.name,
                ),
            )

        if (updatedRows != 1) {
            throw OrderReservationNotFoundException(
                marketId = reservation.marketId,
                orderId = reservation.orderId,
            )
        }
    }

    /**
     * 공통 SELECT에 필요할 때만 PostgreSQL `FOR UPDATE`를 붙여 예약을 조회한다.
     *
     * [lockForUpdate]가 true이면 호출자는 반드시 트랜잭션 안에서 이 메서드를 사용하고 update
     * 또는 rollback까지 잠금을 유지해야 한다.
     *
     * @param marketId 주문 마켓
     * @param orderId 주문 식별자
     * @param lockForUpdate row-level write lock 필요 여부
     * @return domain 객체로 복원한 예약. 없으면 `null`
     */
    private fun queryReservation(
        marketId: MarketId,
        orderId: OrderId,
        lockForUpdate: Boolean,
    ): OrderReservation? {
        // 같은 SELECT 본문을 공유하되 변경 흐름에서만 row lock 절을 추가한다.
        val lockingClause =
            if (lockForUpdate) {
                "\nfor update"
            } else {
                ""
            }

        val sql =
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
            """.trimIndent() + lockingClause

        return jdbcTemplate
            .query(
                sql,
                mapOf(
                    "marketId" to marketId.value,
                    "orderId" to orderId.value,
                ),
                orderReservationRowMapper,
            ).singleOrNull()
    }

    /** DB 문자열과 정수 컬럼을 domain enum/value class로 복원하는 mapper. */
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
