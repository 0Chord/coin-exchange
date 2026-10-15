package com.exchange.core.trading

import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

/** 계산을 마쳤지만 아직 상태에 반영하지 않은 주문·자금·기록 묶음. */
class OrderAdmissionCandidate internal constructor(
    val order: OrderAdmissionInput,
    val market: MarketDefinition,
    val feePolicySnapshot: TradingFeePolicySnapshot,
    val previousBalance: Balance,
    val nextBalance: Balance,
    val reservation: OrderReservation,
    val reserveTransaction: LedgerTransaction,
) {
    init {
        require(order.side == Side.BUY && order.orderType == OrderType.LIMIT && order.timeInForce == TimeInForce.GTC)
        require(order.marketId == market.marketId && reservation.marketId == order.marketId)
        require(reservation.orderId == order.orderId && reservation.userId == order.userId && reservation.side == order.side)
        require(previousBalance.userId == order.userId && nextBalance.userId == order.userId)
        require(previousBalance.assetId == market.quoteAssetId && nextBalance.assetId == market.quoteAssetId)
        require(reservation.assetId == market.quoteAssetId && reservation.limitPrice == order.price)
        require(reservation.initialQuantity == order.quantity && reservation.remainingQuantity == order.quantity)
        require(reservation.feePolicySnapshot == feePolicySnapshot)
        require(reservation.status == OrderReservationStatus.ACTIVE && reservation.releasedAmount == null)
        require(reservation.reservedAmount == reservation.remainingAmount)
        require(reservation.initialFeeReserveAmount == reservation.remainingFeeReserveAmount)
        require(reservation.feeRemainder == FeeRemainder.ZERO)
        val amount = reservation.reservedAmount
        require(nextBalance.available.value == Math.subtractExact(previousBalance.available.value, amount.value))
        require(nextBalance.hold.value == Math.addExact(previousBalance.hold.value, amount.value))

        // 원장의 자산별 균형만으로는 잘못된 사용자·계정·방향을 찾을 수 없다.
        val account = "USER:${order.userId.value}:${market.quoteAssetId.value}"
        require(reserveTransaction.transactionType == LedgerTransactionType.RESERVE)
        require(
            reserveTransaction.postings ==
                listOf(
                    LedgerPosting("$account:AVAILABLE", market.quoteAssetId, LedgerPostingSide.DEBIT, amount),
                    LedgerPosting("$account:HOLD", market.quoteAssetId, LedgerPostingSide.CREDIT, amount),
                ),
        )
    }
}
