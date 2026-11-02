package com.exchange.core.trading

import com.exchange.core.common.AssetId
import java.util.Collections

/** 자금 역할의 금융 상태와 아직 확인되지 않은 전달 책임을 함께 복원하는 불변 값이다. */
class FundsRoleRecoveryState(
    val configuration: FundsDispatchConfiguration,
    val financial: BoundedFundingRecoveryState,
    val dispatch: FundsDispatchSnapshot,
    assetTotals: Map<AssetId, Long>,
    val results: FundsResultRecoveryState? = null,
) {
    val assetTotals: Map<AssetId, Long> = Collections.unmodifiableMap(LinkedHashMap(assetTotals))
}
