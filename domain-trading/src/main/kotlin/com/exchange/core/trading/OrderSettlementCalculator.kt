package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.LiquidityRole
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderFillSettlementPlan
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

/**
 * 한 주문의 모든 체결을 현재 값에서 순서대로 계산한다.
 * 작업용 값만 갱신하므로 실패해도 입력은 그대로이며, 부분 후보·외부 쓰기를 제공하지 않는다.
 */
class OrderSettlementCalculator(
    private val fillCalculator: OrderFillSettlementCalculator,
    private val reservationCalculator: OrderReservationCalculator,
    private val feeReserveCalculator: TradingFeeReserveCalculator,
    private val ledgerTransactions: SettlementLedgerTransactions,
) {
    private data class OrderKey(
        val marketId: MarketId,
        val orderId: OrderId,
    )

    private data class BalanceKey(
        val userId: UserId,
        val assetId: AssetId,
    )

    fun calculate(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        executions: List<SettlementExecution>,
        reservations: List<OrderReservation>,
        balances: List<Balance>,
    ): OrderSettlementCandidate {
        validateOrder(order, market)
        val trades = executions.toList()
        val initialReservations = uniqueIndex(reservations) { it.orderKey() }
        val initialBalances = uniqueIndex(balances) { BalanceKey(it.userId, it.assetId) }
        validateExecutions(order, trades)
        if (trades.isEmpty()) return emptyCandidate(order, market)
        if (trades.size == 1) return calculateSingle(order, market, trades.single(), initialReservations, initialBalances)

        val orderKeys = linkedSetOf<OrderKey>()
        trades.forEach {
            orderKeys.add(OrderKey(market.marketId, it.makerOrderId))
            orderKeys.add(OrderKey(market.marketId, it.takerOrderId))
        }
        val workingReservations = linkedMapOf<OrderKey, OrderReservation>()
        val outstanding = linkedMapOf<BalanceKey, Long>()
        for (key in orderKeys) {
            val reservation = requireNotNull(initialReservations[key]) { "settlement reservation is missing" }
            validateInitialReservation(market, reservation)
            require(reservation.status == OrderReservationStatus.ACTIVE) { "settlement reservation must be active" }
            workingReservations[key] = reservation
            val balanceKey = reservation.balanceKey()
            outstanding[balanceKey] = Math.addExact(outstanding[balanceKey] ?: 0, reservation.remainingAmount.value)
        }
        validateOriginal(order, workingReservations.getValue(OrderKey(market.marketId, order.orderId)))
        val workingBalances = initialBalances.toMutableMap()
        val unrelatedHold =
            outstanding.mapValues { (key, amount) ->
                unrelatedHoldAt(initialBalances, key, amount)
            }
        val changedBalances = linkedSetOf<BalanceKey>()

        fun updateBalance(
            key: BalanceKey,
            update: (Balance) -> Balance,
        ) {
            val previous = balanceAt(workingBalances, key)
            workingBalances[key] = update(previous)
            changedBalances.add(key)
        }

        fun accumulate(
            reservation: OrderReservation,
            plan: OrderFillSettlementPlan,
        ) {
            val next = validatePlan(market, reservation, plan)
            val key = reservation.balanceKey()
            updateBalance(key) {
                val consumed = it.consumeHold(plan.holdAmountToConsume)
                if (plan.holdAmountToRelease.isZero()) consumed else consumed.release(plan.holdAmountToRelease)
            }
            if (!plan.creditAmount.isZero()) {
                updateBalance(BalanceKey(reservation.userId, plan.creditAssetId)) { it.credit(plan.creditAmount) }
            }
            workingReservations[reservation.orderKey()] = next
            val remaining = Math.subtractExact(outstanding.getValue(key), plan.reservedAmountToReduce.value)
            outstanding[key] = remaining
            require(balanceAt(workingBalances, key).hold.value == Math.addExact(remaining, unrelatedHold.getValue(key))) {
                "settlement must preserve unrelated hold"
            }
        }

        val settlements = mutableListOf<OrderSettlementCandidate.Settlement>()
        for (trade in trades) {
            val maker = workingReservations.getValue(OrderKey(market.marketId, trade.makerOrderId))
            val taker = workingReservations.getValue(OrderKey(market.marketId, trade.takerOrderId))
            validateTrade(trade, maker, taker)
            val makerPlan = fillCalculator.calculate(market, maker, trade.price, trade.quantity, LiquidityRole.MAKER)
            val takerPlan = fillCalculator.calculate(market, taker, trade.price, trade.quantity, LiquidityRole.TAKER)
            accumulate(maker, makerPlan)
            accumulate(taker, takerPlan)
            val transaction =
                ledgerTransactions.create(
                    market,
                    trade.engineSequence,
                    maker,
                    makerPlan,
                    taker,
                    takerPlan,
                    trade.ledgerTransactionId,
                    trade.occurredAt,
                )
            settlements += OrderSettlementCandidate.Settlement(trade, makerPlan, takerPlan, transaction)
        }
        return OrderSettlementCandidate(
            order,
            market,
            settlements,
            changedBalances.mapNotNull { key ->
                val previous = balanceAt(initialBalances, key)
                val next = workingBalances.getValue(key)
                if (previous == next) null else OrderSettlementCandidate.BalanceChange(previous, next)
            },
            orderKeys.map {
                OrderSettlementCandidate.ReservationChange(
                    initialReservations.getValue(it),
                    workingReservations.getValue(it),
                )
            },
        )
    }

    /** bounded 소유자가 해당 예약 하나와 현재 잔고 키 두 개를 이미 검사한 상태다. */
    internal fun calculateEmpty(
        order: OrderAdmissionInput,
        market: MarketDefinition,
    ): OrderSettlementCandidate {
        validateOrder(order, market)
        return emptyCandidate(order, market)
    }

    private fun validateOrder(
        order: OrderAdmissionInput,
        market: MarketDefinition,
    ) {
        require(order.marketId == market.marketId && order.quantity.value > 0)
        require(order.orderType == OrderType.LIMIT && order.timeInForce == TimeInForce.GTC)
    }

    private fun emptyCandidate(
        order: OrderAdmissionInput,
        market: MarketDefinition,
    ) = OrderSettlementCandidate(order, market, emptyList(), emptyList(), emptyList())

    /** 한 체결의 두 예약은 반대 방향이므로 같은 사용자여도 서로 다른 자산에 묶인다. */
    private fun calculateSingle(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        trade: SettlementExecution,
        initialReservations: Map<OrderKey, OrderReservation>,
        initialBalances: Map<BalanceKey, Balance>,
    ): OrderSettlementCandidate {
        val maker =
            requireNotNull(initialReservations[OrderKey(market.marketId, trade.makerOrderId)]) { "settlement reservation is missing" }
        val taker =
            requireNotNull(initialReservations[OrderKey(market.marketId, trade.takerOrderId)]) { "settlement reservation is missing" }
        validateInitialReservation(market, maker)
        require(maker.status == OrderReservationStatus.ACTIVE) { "settlement reservation must be active" }
        validateInitialReservation(market, taker)
        require(taker.status == OrderReservationStatus.ACTIVE) { "settlement reservation must be active" }
        validateOriginal(order, taker)
        val makerKey = maker.balanceKey()
        val takerKey = taker.balanceKey()
        val makerUnrelatedHold = unrelatedHoldAt(initialBalances, makerKey, maker.remainingAmount.value)
        val takerUnrelatedHold = unrelatedHoldAt(initialBalances, takerKey, taker.remainingAmount.value)
        validateTrade(trade, maker, taker)
        val makerPlan = fillCalculator.calculate(market, maker, trade.price, trade.quantity, LiquidityRole.MAKER)
        val takerPlan = fillCalculator.calculate(market, taker, trade.price, trade.quantity, LiquidityRole.TAKER)
        val workingBalances = SingleBalanceSlots(initialBalances)
        accumulateSingle(market, maker, makerPlan, makerKey, makerUnrelatedHold, workingBalances)
        accumulateSingle(market, taker, takerPlan, takerKey, takerUnrelatedHold, workingBalances)
        val transaction =
            ledgerTransactions.create(
                market,
                trade.engineSequence,
                maker,
                makerPlan,
                taker,
                takerPlan,
                trade.ledgerTransactionId,
                trade.occurredAt,
            )
        return OrderSettlementCandidate(
            order,
            market,
            listOf(OrderSettlementCandidate.Settlement(trade, makerPlan, takerPlan, transaction)),
            workingBalances.changes(),
            listOf(
                OrderSettlementCandidate.ReservationChange(maker, makerPlan.updatedReservation),
                OrderSettlementCandidate.ReservationChange(taker, takerPlan.updatedReservation),
            ),
        )
    }

    private fun accumulateSingle(
        market: MarketDefinition,
        reservation: OrderReservation,
        plan: OrderFillSettlementPlan,
        key: BalanceKey,
        unrelatedHold: Long,
        balances: SingleBalanceSlots,
    ) {
        validatePlan(market, reservation, plan)
        val nextHold = balances.consume(key, plan)
        if (!plan.creditAmount.isZero()) balances.credit(BalanceKey(reservation.userId, plan.creditAssetId), plan.creditAmount)
        val remaining = Math.subtractExact(reservation.remainingAmount.value, plan.reservedAmountToReduce.value)
        require(nextHold == Math.addExact(remaining, unrelatedHold)) { "settlement must preserve unrelated hold" }
    }

    private fun unrelatedHoldAt(
        balances: Map<BalanceKey, Balance>,
        key: BalanceKey,
        amount: Long,
    ): Long {
        val hold = balanceAt(balances, key).hold.value
        require(hold >= amount) { "settlement reservations exceed current hold" }
        return hold - amount
    }

    private fun validateTrade(
        trade: SettlementExecution,
        maker: OrderReservation,
        taker: OrderReservation,
    ) {
        require(maker.userId == trade.makerUserId && taker.userId == trade.takerUserId) { "settlement owner mismatch" }
        require(taker.side == trade.side && maker.side != trade.side) { "settlement side mismatch" }
        require(maker.limitPrice == trade.price) { "execution price must match maker price" }
    }

    /** maker의 예약·수취, taker의 예약·수취에 해당하는 최대 네 키만 첫 변경 순서로 보관한다. */
    private class SingleBalanceSlots(
        private val initial: Map<BalanceKey, Balance>,
    ) {
        private class Slot(
            val key: BalanceKey,
            val previous: Balance,
            var current: Balance,
        )

        private val slots = ArrayList<Slot>(4)

        fun consume(
            key: BalanceKey,
            plan: OrderFillSettlementPlan,
        ): Long {
            val slot = slot(key)
            val consumed = slot.current.consumeHold(plan.holdAmountToConsume)
            slot.current = if (plan.holdAmountToRelease.isZero()) consumed else consumed.release(plan.holdAmountToRelease)
            return slot.current.hold.value
        }

        fun credit(
            key: BalanceKey,
            amount: Amount,
        ) {
            val slot = slot(key)
            slot.current = slot.current.credit(amount)
        }

        fun changes(): List<OrderSettlementCandidate.BalanceChange> =
            slots.mapNotNull { slot ->
                if (slot.previous == slot.current) null else OrderSettlementCandidate.BalanceChange(slot.previous, slot.current)
            }

        private fun slot(key: BalanceKey): Slot {
            for (slot in slots) if (slot.key == key) return slot
            check(slots.size < 4) { "single settlement has more than four balance keys" }
            val previous = initial[key] ?: Balance(key.userId, key.assetId, Amount.ZERO, Amount.ZERO)
            val slot = Slot(key, previous, previous)
            slots.add(slot)
            return slot
        }
    }

    private fun validatePlan(
        market: MarketDefinition,
        reservation: OrderReservation,
        plan: OrderFillSettlementPlan,
    ): OrderReservation {
        val next = plan.updatedReservation
        validateReservation(market, next)
        require(next.remainingAmount.value == Math.subtractExact(reservation.remainingAmount.value, plan.reservedAmountToReduce.value))
        require(plan.reservedAmountToReduce.value == Math.addExact(plan.holdAmountToConsume.value, plan.holdAmountToRelease.value))
        require(plan.feeAssetId == market.quoteAssetId)
        require(plan.creditAssetId == if (reservation.side == Side.BUY) market.baseAssetId else market.quoteAssetId)
        return next
    }

    private fun validateExecutions(
        order: OrderAdmissionInput,
        executions: List<SettlementExecution>,
    ) {
        val ledgerIds = if (executions.size > 1) hashSetOf<String>() else null
        executions.forEachIndexed { index, trade ->
            require(trade.marketId == order.marketId && trade.takerOrderId == order.orderId && trade.takerUserId == order.userId)
            require(trade.side == order.side && trade.makerOrderId != trade.takerOrderId)
            require(trade.engineSequence > 0 && trade.quantity.value > 0)
            require(
                trade.ledgerTransactionId.isNotBlank() && (ledgerIds == null || ledgerIds.add(trade.ledgerTransactionId)),
            ) { "duplicate or blank settlement ledger ID" }
            if (index > 0) {
                val expectedSequence = Math.addExact(executions[index - 1].engineSequence, 1L)
                require(trade.engineSequence == expectedSequence) { "settlement sequence is not contiguous" }
            }
        }
    }

    private fun validateOriginal(
        order: OrderAdmissionInput,
        reservation: OrderReservation,
    ) {
        require(reservation.marketId == order.marketId && reservation.orderId == order.orderId && reservation.userId == order.userId)
        require(reservation.side == order.side && reservation.limitPrice == order.price && reservation.initialQuantity == order.quantity) {
            "settlement reservation must match original order"
        }
    }

    private fun validateInitialReservation(
        market: MarketDefinition,
        reservation: OrderReservation,
    ) {
        try {
            validateReservation(market, reservation)
        } catch (failure: IllegalArgumentException) {
            throw InitialReservationValidationFailure(failure)
        }
    }

    internal fun validateReservation(
        market: MarketDefinition,
        reservation: OrderReservation,
    ) {
        require(reservation.marketId == market.marketId && reservation.releasedAmount == null)
        require(reservation.feePolicySnapshot.productType == FeeProductType.SPOT)
        require(reservation.status != OrderReservationStatus.RELEASED)
        val initial =
            reservationCalculator.calculate(
                market,
                reservation.side,
                reservation.limitPrice,
                reservation.initialQuantity,
                reservation.feePolicySnapshot,
            )
        require(reservation.assetId == initial.assetId)
        require(
            reservation.reservedAmount == initial.totalReserveAmount && reservation.initialFeeReserveAmount == initial.feeReserveAmount,
        ) {
            "initial settlement reservation amount mismatch"
        }
        if (reservation.status == OrderReservationStatus.SETTLED) {
            require(reservation.remainingFeeReserveAmount == Amount.ZERO)
            return
        }
        val unchangedQuantity = reservation.remainingQuantity == reservation.initialQuantity
        val current =
            if (unchangedQuantity) {
                initial
            } else {
                reservationCalculator.calculate(
                    market,
                    reservation.side,
                    reservation.limitPrice,
                    reservation.remainingQuantity,
                    reservation.feePolicySnapshot,
                )
            }
        val fee =
            if (reservation.side == Side.BUY) {
                if (reservation.feeRemainder == FeeRemainder.ZERO) {
                    current.feeReserveAmount
                } else {
                    feeReserveCalculator.calculateReserve(
                        current.tradeReserveAmount,
                        reservation.feePolicySnapshot.maximumRate(),
                        reservation.feeRemainder,
                    )
                }
            } else {
                Amount.ZERO
            }
        require(reservation.remainingFeeReserveAmount == fee)
        require(reservation.remainingAmount.value == Math.addExact(current.tradeReserveAmount.value, fee.value)) {
            "remaining settlement reservation amount mismatch"
        }
    }

    private fun OrderReservation.orderKey() = OrderKey(marketId, orderId)

    private fun OrderReservation.balanceKey() = BalanceKey(userId, assetId)

    private fun balanceAt(
        balances: Map<BalanceKey, Balance>,
        key: BalanceKey,
    ) = balances[key] ?: Balance(key.userId, key.assetId, Amount.ZERO, Amount.ZERO)

    private fun <K, V> uniqueIndex(
        values: List<V>,
        key: (V) -> K,
    ): Map<K, V> {
        val result = linkedMapOf<K, V>()
        values.forEach { require(result.put(key(it), it) == null) { "duplicate settlement input key" } }
        return result
    }
}

/** 공개 API의 인자 오류 계약을 유지하면서 기존 예약의 손상을 구분한다. */
internal class InitialReservationValidationFailure(
    failure: IllegalArgumentException,
) : IllegalArgumentException(failure.message, failure)
