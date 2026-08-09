package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId

interface BalanceStore {
    fun reserve(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance

    fun release(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance
}
