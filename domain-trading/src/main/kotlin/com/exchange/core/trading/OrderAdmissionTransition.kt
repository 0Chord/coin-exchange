package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

/** callback 안에서만 쓰는 예약 결과다. 예약 원장이나 과거 잔고 객체 그래프는 소유하지 않는다. */
internal sealed class AdmissionTransitionResult {
    class Calculated(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        previousBalance: Balance,
        val nextBalance: Balance,
        val reservation: OrderReservation,
    ) : AdmissionTransitionResult() {
        private val balanceUserId = previousBalance.userId
        private val balanceAssetId = previousBalance.assetId
        private val previousAvailable = previousBalance.available.value
        private val previousHold = previousBalance.hold.value

        init {
            require(nextBalance.userId == balanceUserId && nextBalance.assetId == balanceAssetId)
            validateAdmissionTransition(
                order,
                market,
                feePolicySnapshot,
                reservation,
                balanceUserId,
                balanceAssetId,
                previousAvailable,
                previousHold,
                nextBalance.available.value,
                nextBalance.hold.value,
            )
        }

        fun hasPreviousBalance(balance: Balance): Boolean =
            balance.userId == balanceUserId && balance.assetId == balanceAssetId &&
                balance.available.value == previousAvailable && balance.hold.value == previousHold
    }

    data class Rejected(
        val reason: OrderAdmissionResult.Reason,
        val insufficientBalance: OrderAdmissionResult.InsufficientBalance? = null,
    ) : AdmissionTransitionResult()
}

/** 공통 금액 관계를 검사한다. 원장이 있는 호출자는 원장 항목 표현도 별도로 검증한다. */
internal fun validateAdmissionTransition(
    order: OrderAdmissionInput,
    market: MarketDefinition,
    feePolicySnapshot: TradingFeePolicySnapshot,
    reservation: OrderReservation,
    balanceUserId: UserId,
    balanceAssetId: AssetId,
    previousAvailable: Long,
    previousHold: Long,
    nextAvailable: Long,
    nextHold: Long,
): AssetId {
    require(order.orderType == OrderType.LIMIT && order.timeInForce == TimeInForce.GTC)
    market.requireOrderUnits(order.price, order.quantity)
    val expectedAssetId =
        when (order.side) {
            Side.BUY -> market.quoteAssetId
            Side.SELL -> market.baseAssetId
        }
    require(order.marketId == market.marketId && reservation.marketId == order.marketId)
    require(reservation.orderId == order.orderId && reservation.userId == order.userId && reservation.side == order.side)
    require(balanceUserId == order.userId)
    require(balanceAssetId == expectedAssetId)
    require(reservation.assetId == expectedAssetId && reservation.limitPrice == order.price)
    require(reservation.initialQuantity == order.quantity && reservation.remainingQuantity == order.quantity)
    require(reservation.feePolicySnapshot == feePolicySnapshot)
    require(reservation.status == OrderReservationStatus.ACTIVE && reservation.releasedAmount == null)
    require(reservation.reservedAmount == reservation.remainingAmount)
    require(reservation.initialFeeReserveAmount == reservation.remainingFeeReserveAmount)
    require(reservation.feeRemainder == FeeRemainder.ZERO)
    if (order.side == Side.SELL) {
        // SELL 수수료는 판매대금에서 차감하므로 팔 수량에 더해 미리 묶지 않는다.
        require(reservation.reservedAmount.value == order.quantity.value)
        require(reservation.initialFeeReserveAmount == Amount.ZERO)
    }
    val amount = reservation.reservedAmount
    require(nextAvailable == Math.subtractExact(previousAvailable, amount.value))
    require(nextHold == Math.addExact(previousHold, amount.value))
    return expectedAssetId
}
