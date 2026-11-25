package com.exchange.core.api.ledger.application

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.DevelopmentBalanceStore
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceResult

/** 명시적인 개발용 자금 준비 진입점. 생성만으로 지급하지 않는다. */
class PrepareDevelopmentBalanceUseCase(
    private val developmentBalanceStore: DevelopmentBalanceStore,
) {
    fun prepare(
        preparationId: String,
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): OpeningBalanceResult = developmentBalanceStore.prepare(OpeningBalance(preparationId, userId, assetId, amount))

    fun ensureReceivingBalance(
        userId: UserId,
        assetId: AssetId,
    ) {
        OpeningBalance.validateAccount(userId, assetId)
        developmentBalanceStore.ensureReceivingBalance(userId, assetId)
    }
}
