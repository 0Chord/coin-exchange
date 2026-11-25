package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementPlan
import com.exchange.core.order.OrderReservation
import java.time.Instant

/** 한 체결의 양쪽 정산 계획을 원장으로 바꾼다. 저장이나 잔고 변경은 하지 않는다. */
class SettlementLedgerTransactions {
    /**
     * maker 다음 taker 순서로 분개를 만들고 자산별 균형을 검증한다.
     * ID·시각은 호출자가 공급하며, 가격차와 미사용 수수료 반환도 이 SETTLEMENT에 포함한다.
     */
    fun create(
        market: MarketDefinition,
        engineSequence: Long,
        makerReservation: OrderReservation,
        makerPlan: OrderFillSettlementPlan,
        takerReservation: OrderReservation,
        takerPlan: OrderFillSettlementPlan,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): LedgerTransaction {
        validateInputs(market, engineSequence, makerReservation, takerReservation, ledgerTransactionId)

        // 한쪽의 최대 다섯 항목을 maker 다음 taker 순서로 같은 버퍼에 넣는다.
        val postings = ArrayList<LedgerPosting>(10)
        appendPostings(postings, makerReservation, makerPlan)
        appendPostings(postings, takerReservation, takerPlan)

        return LedgerTransaction(
            ledgerTransactionId = ledgerTransactionId,
            sourceEventId = "MATCHING:${market.marketId.value}:$engineSequence",
            transactionType = LedgerTransactionType.SETTLEMENT,
            occurredAt = occurredAt,
            postings = postings,
        )
    }

    /** 원장이 필요한 경로와 같은 분개 순서에서 두 자산의 균형과 수수료를 검증한다. */
    internal fun proveSingleFillFee(
        market: MarketDefinition,
        engineSequence: Long,
        makerReservation: OrderReservation,
        makerPlan: OrderFillSettlementPlan,
        takerReservation: OrderReservation,
        takerPlan: OrderFillSettlementPlan,
        ledgerTransactionId: String,
    ): Long? {
        validateInputs(market, engineSequence, makerReservation, takerReservation, ledgerTransactionId)
        if (makerPlan.feeAssetId != market.quoteAssetId || takerPlan.feeAssetId != market.quoteAssetId) return null
        var baseDelta = 0L
        var quoteDelta = 0L
        var postedFee = 0L
        var hasPosting = false
        return try {
            for (index in 0..1) {
                val reservation = if (index == 0) makerReservation else takerReservation
                val plan = if (index == 0) makerPlan else takerPlan
                visitPostings(reservation, plan) { account, asset, side, amount ->
                    val delta = if (side == LedgerPostingSide.DEBIT) amount.value else -amount.value
                    when (asset) {
                        market.baseAssetId -> baseDelta = Math.addExact(baseDelta, delta)
                        market.quoteAssetId -> quoteDelta = Math.addExact(quoteDelta, delta)
                        else -> return null
                    }
                    if (account == Account.FEE_REVENUE) {
                        if (asset != market.quoteAssetId || side != LedgerPostingSide.CREDIT) return null
                        postedFee = Math.addExact(postedFee, amount.value)
                    }
                    hasPosting = true
                }
            }
            val plannedFee = Math.addExact(makerPlan.actualFeeAmount.value, takerPlan.actualFeeAmount.value)
            postedFee.takeIf { hasPosting && baseDelta == 0L && quoteDelta == 0L && it == plannedFee }
        } catch (_: ArithmeticException) {
            // 긴 합계나 예상 밖 자산은 기존 원장 검증과 오류 경계를 사용한다.
            null
        }
    }

    private fun validateInputs(
        market: MarketDefinition,
        engineSequence: Long,
        makerReservation: OrderReservation,
        takerReservation: OrderReservation,
        ledgerTransactionId: String,
    ) {
        require(ledgerTransactionId.isNotBlank()) { "settlement ledger transaction ID must not be blank" }
        require(engineSequence > 0) { "settlement engine sequence must be positive" }
        require(makerReservation.marketId == market.marketId && takerReservation.marketId == market.marketId) {
            "settlement reservations must belong to the market"
        }
        require(makerReservation.side != takerReservation.side) { "settlement reservations must have opposite sides" }
    }

    private enum class Account { HOLD, AVAILABLE, FEE_REVENUE }

    private fun appendPostings(
        postings: MutableList<LedgerPosting>,
        reservation: OrderReservation,
        plan: OrderFillSettlementPlan,
    ) {
        val user = reservation.userId.value
        val holdAccount = "USER:$user:${reservation.assetId.value}:HOLD"
        visitPostings(reservation, plan) { account, asset, side, amount ->
            val accountId =
                when (account) {
                    Account.HOLD -> holdAccount
                    Account.AVAILABLE -> "USER:$user:${asset.value}:AVAILABLE"
                    Account.FEE_REVENUE -> "SYSTEM:${asset.value}:FEE_REVENUE"
                }
            postings.add(LedgerPosting(accountId, asset, side, amount))
        }
    }

    /** 금액 0은 제외하며 외부에 보이는 분개 순서를 유지한다. */
    private inline fun visitPostings(
        reservation: OrderReservation,
        plan: OrderFillSettlementPlan,
        emit: (Account, AssetId, LedgerPostingSide, Amount) -> Unit,
    ) {
        if (!plan.holdAmountToConsume.isZero()) {
            emit(Account.HOLD, reservation.assetId, LedgerPostingSide.DEBIT, plan.holdAmountToConsume)
        }
        if (!plan.holdAmountToRelease.isZero()) {
            emit(Account.HOLD, reservation.assetId, LedgerPostingSide.DEBIT, plan.holdAmountToRelease)
            emit(Account.AVAILABLE, reservation.assetId, LedgerPostingSide.CREDIT, plan.holdAmountToRelease)
        }
        if (!plan.creditAmount.isZero()) {
            emit(Account.AVAILABLE, plan.creditAssetId, LedgerPostingSide.CREDIT, plan.creditAmount)
        }
        if (!plan.actualFeeAmount.isZero()) {
            emit(Account.FEE_REVENUE, plan.feeAssetId, LedgerPostingSide.CREDIT, plan.actualFeeAmount)
        }
    }
}
