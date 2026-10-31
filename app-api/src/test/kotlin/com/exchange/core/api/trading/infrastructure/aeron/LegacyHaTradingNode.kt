package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.order.OrderReservationStatus
import tools.jackson.module.kotlin.jacksonObjectMapper

/** 기존 제품 API만 공유하므로 a39b0d8에서도 수정 없이 컴파일된다. */
fun main(args: Array<String>) {
    require(args.isEmpty())
    runHaTradingProbeNode(haTradingProbeSettings()) { node ->
        val snapshot = checkNotNull(node.captureObservation()?.snapshot)
        val fields =
            mapOf(
                "balances" to
                    snapshot.balances.sortedWith(compareBy({ it.userId.value }, { it.assetId.value })).map {
                        listOf(it.userId.value, it.assetId.value, it.available.value, it.hold.value)
                    },
                "activeReservations" to snapshot.reservations.count { it.status == OrderReservationStatus.ACTIVE },
                "pending" to snapshot.pendingOrders.size,
                "lastAdmissionSequence" to snapshot.lastAdmissionSequence,
                "completions" to snapshot.completions.size,
                "feeRevenue" to
                    snapshot.ledgerTransactions.sumOf { tx ->
                        tx.postings
                            .filter {
                                it.accountId == "SYSTEM:USDT:FEE_REVENUE" && it.side == LedgerPostingSide.CREDIT
                            }.sumOf { it.amount.value }
                    },
            )
        fields + ("stateSha256" to haProbeSha(jacksonObjectMapper().writeValueAsBytes(fields)))
    }
}
