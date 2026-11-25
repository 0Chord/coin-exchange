package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation

/** 계산을 마쳤지만 아직 상태에 반영하지 않은 주문·자금·기록 묶음. */
class OrderAdmissionCandidate internal constructor(
    val order: OrderAdmissionInput,
    val market: MarketDefinition,
    val feePolicySnapshot: TradingFeePolicySnapshot,
    previousBalance: Balance,
    nextBalance: Balance,
    val reservation: OrderReservation,
    val reserveTransaction: LedgerTransaction,
) {
    // 승인 이력이 이전 Balance 객체를 계속 붙잡지 않도록 당시 값만 보존한다.
    // 현재 order/reservation에서 파생하지 않아 원본의 소유자·금액 손상 검사도 유지한다.
    private val balanceUserId = previousBalance.userId
    private val balanceAssetId = previousBalance.assetId
    private val previousAvailable = previousBalance.available.value
    private val previousHold = previousBalance.hold.value
    private val nextAvailable = nextBalance.available.value
    private val nextHold = nextBalance.hold.value

    val previousBalance: Balance
        get() = Balance(balanceUserId, balanceAssetId, Amount(previousAvailable), Amount(previousHold))

    val nextBalance: Balance
        get() = Balance(balanceUserId, balanceAssetId, Amount(nextAvailable), Amount(nextHold))

    init {
        require(nextBalance.userId == balanceUserId && nextBalance.assetId == balanceAssetId)
        validate()
    }

    internal fun hasPreviousBalance(balance: Balance): Boolean =
        balance.userId == balanceUserId && balance.assetId == balanceAssetId &&
            balance.available.value == previousAvailable && balance.hold.value == previousHold

    internal fun validate() {
        val expectedAssetId =
            validateAdmissionTransition(
                order,
                market,
                feePolicySnapshot,
                reservation,
                balanceUserId,
                balanceAssetId,
                previousAvailable,
                previousHold,
                nextAvailable,
                nextHold,
            )
        val amount = reservation.reservedAmount

        validateReserveTransaction(expectedAssetId, amount)
    }

    private fun validateReserveTransaction(
        expectedAssetId: AssetId,
        amount: Amount,
    ) {
        // 원장의 자산별 균형만으로는 잘못된 사용자·계정·방향을 찾을 수 없다.
        val account = "USER:${order.userId.value}:${expectedAssetId.value}"
        require(reserveTransaction.transactionType == LedgerTransactionType.RESERVE)
        val postings = reserveTransaction.postings
        require(postings.size == 2)
        val debit = postings[0]
        val credit = postings[1]
        require(
            debit.accountId == "$account:AVAILABLE" && debit.assetId == expectedAssetId && debit.side == LedgerPostingSide.DEBIT &&
                debit.amount == amount,
        )
        require(
            credit.accountId == "$account:HOLD" && credit.assetId == expectedAssetId && credit.side == LedgerPostingSide.CREDIT &&
                credit.amount == amount,
        )
    }
}
