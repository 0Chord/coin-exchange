package com.exchange.core.ledger

import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId

/** 개시 원장과 잔고를 함께 커밋하고, 실패하면 둘 모두 롤백하는 저장 계약. */
interface DevelopmentBalanceStore {
    fun prepare(opening: OpeningBalance): OpeningBalanceResult

    /** 없는 수령 잔고만 0으로 만든다. 기존 잔고·갱신 시각과 원장은 바꾸지 않는다. */
    fun ensureReceivingBalance(
        userId: UserId,
        assetId: AssetId,
    )
}
