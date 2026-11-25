package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import java.util.AbstractList
import java.util.Objects
import java.util.RandomAccess

/** 과거 잔고의 소유자·자산·금액만 보관한다. 원래 잔고·변경 객체·목록을 붙잡거나 복원 결과를 캐시하지 않는다. */
internal class CompactSettlementBalanceHistory(
    changes: List<OrderSettlementCandidate.BalanceChange>,
) : AbstractList<OrderSettlementCandidate.BalanceChange>(),
    RandomAccess {
    override val size = changes.size
    private val identities = arrayOfNulls<String>(Math.multiplyExact(size, FIELDS_PER_CHANGE))
    private val amounts = LongArray(Math.multiplyExact(size, FIELDS_PER_CHANGE))

    init {
        changes.forEachIndexed { index, change ->
            val start = index * FIELDS_PER_CHANGE
            identities[start] = change.previous.userId.value
            identities[start + 1] = change.previous.assetId.value
            identities[start + 2] = change.next.userId.value
            identities[start + 3] = change.next.assetId.value
            amounts[start] = change.previous.available.value
            amounts[start + 1] = change.previous.hold.value
            amounts[start + 2] = change.next.available.value
            amounts[start + 3] = change.next.hold.value
        }
    }

    override fun get(index: Int): OrderSettlementCandidate.BalanceChange {
        Objects.checkIndex(index, size)
        val start = index * FIELDS_PER_CHANGE
        return OrderSettlementCandidate.BalanceChange(
            Balance(
                UserId(identities[start]!!),
                AssetId(identities[start + 1]!!),
                Amount(amounts[start]),
                Amount(amounts[start + 1]),
            ),
            Balance(
                UserId(identities[start + 2]!!),
                AssetId(identities[start + 3]!!),
                Amount(amounts[start + 2]),
                Amount(amounts[start + 3]),
            ),
        )
    }

    private companion object {
        const val FIELDS_PER_CHANGE = 4
    }
}
