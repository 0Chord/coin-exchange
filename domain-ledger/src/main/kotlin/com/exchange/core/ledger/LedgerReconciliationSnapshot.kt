package com.exchange.core.ledger

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.UserId
import java.util.Collections

/** 한 마켓의 두 자산을 검사한다. 잔고 자체는 마켓별로 나뉘지 않는다. */
data class LedgerReconciliationScope(
    val marketId: MarketId,
    val baseAssetId: AssetId,
    val quoteAssetId: AssetId,
) {
    init {
        require(baseAssetId != quoteAssetId) { "서로 다른 두 자산이 필요합니다" }
        require(listOf(marketId.value, baseAssetId.value, quoteAssetId.value).none { '*' in it }) {
            "검사 범위에 와일드카드를 사용할 수 없습니다"
        }
    }

    val assets: Set<AssetId> get() = setOf(baseAssetId, quoteAssetId)
}

/** 주문 모듈 타입을 의존하지 않고, 예약 소유자·상태·남은 예약액만 전달한다. */
data class ReservationHold(
    val marketId: String,
    val orderId: String,
    val userId: UserId,
    val assetId: AssetId,
    val status: String,
    val remainingAmount: Long,
)

/** 같은 DB 시점에서 완전히 읽은 자료. 읽을 수 있지만 손상된 기록은 문제 목록에 보존한다. */
class LedgerReconciliationSnapshot(
    transactions: List<LedgerTransaction>,
    balances: List<Balance>,
    reservations: List<ReservationHold>,
    problems: List<ReconciliationDifference> = emptyList(),
) {
    val transactions: List<LedgerTransaction> = Collections.unmodifiableList(transactions.toList())
    val balances: List<Balance> = Collections.unmodifiableList(balances.toList())
    val reservations: List<ReservationHold> = Collections.unmodifiableList(reservations.toList())
    val problems: List<ReconciliationDifference> = Collections.unmodifiableList(problems.toList())
}
