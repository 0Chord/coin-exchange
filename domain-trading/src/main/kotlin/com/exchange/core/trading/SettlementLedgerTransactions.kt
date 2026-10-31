package com.exchange.core.trading

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
        require(ledgerTransactionId.isNotBlank()) { "settlement ledger transaction ID must not be blank" }
        require(engineSequence > 0) { "settlement engine sequence must be positive" }
        require(makerReservation.marketId == market.marketId && takerReservation.marketId == market.marketId) {
            "settlement reservations must belong to the market"
        }
        require(makerReservation.side != takerReservation.side) { "settlement reservations must have opposite sides" }

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

    /** 금액 0은 제외한다. 한쪽 분개 자체는 균형이 맞지 않아 양쪽을 누적한 뒤 검증한다. */
    private fun appendPostings(
        postings: MutableList<LedgerPosting>,
        reservation: OrderReservation,
        plan: OrderFillSettlementPlan,
    ) {
        val userId = reservation.userId.value
        val reservedAssetId = reservation.assetId
        val holdAccountId = "USER:$userId:${reservedAssetId.value}:HOLD"

        if (!plan.holdAmountToConsume.isZero()) {
            postings.add(
                LedgerPosting(
                    accountId = holdAccountId,
                    assetId = reservedAssetId,
                    side = LedgerPostingSide.DEBIT,
                    amount = plan.holdAmountToConsume,
                ),
            )
        }

        if (!plan.holdAmountToRelease.isZero()) {
            postings.add(
                LedgerPosting(
                    accountId = holdAccountId,
                    assetId = reservedAssetId,
                    side = LedgerPostingSide.DEBIT,
                    amount = plan.holdAmountToRelease,
                ),
            )
            postings.add(
                LedgerPosting(
                    accountId = "USER:$userId:${reservedAssetId.value}:AVAILABLE",
                    assetId = reservedAssetId,
                    side = LedgerPostingSide.CREDIT,
                    amount = plan.holdAmountToRelease,
                ),
            )
        }

        if (!plan.creditAmount.isZero()) {
            postings.add(
                LedgerPosting(
                    accountId = "USER:$userId:${plan.creditAssetId.value}:AVAILABLE",
                    assetId = plan.creditAssetId,
                    side = LedgerPostingSide.CREDIT,
                    amount = plan.creditAmount,
                ),
            )
        }

        if (!plan.actualFeeAmount.isZero()) {
            postings.add(
                LedgerPosting(
                    accountId = "SYSTEM:${plan.feeAssetId.value}:FEE_REVENUE",
                    assetId = plan.feeAssetId,
                    side = LedgerPostingSide.CREDIT,
                    amount = plan.actualFeeAmount,
                ),
            )
        }
    }
}
