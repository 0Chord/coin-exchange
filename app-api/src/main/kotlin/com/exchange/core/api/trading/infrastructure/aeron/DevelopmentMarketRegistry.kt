package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageFormat
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition

/** 노드 시작 전에 고정하는 개발 마켓의 자산·수량 단위·정책. 자금 상태는 담지 않는다. */
internal data class DevelopmentMarketContext(
    val market: MarketDefinition,
    val feePolicySnapshot: TradingFeePolicySnapshot,
) {
    init {
        require(feePolicySnapshot.productType == FeeProductType.SPOT) { "개발 접수에는 현물 수수료 정책이 필요합니다" }
        listOf(market.marketId.value, market.baseAssetId.value, market.quoteAssetId.value).forEach {
            AdmissionMessageFormat.text(it)
            require('\u0000' !in it && it.codePointCount(0, it.length) <= 64) { "개발 마켓 식별자가 잘못됐습니다" }
        }
        require(':' !in market.baseAssetId.value && ':' !in market.quoteAssetId.value) { "개발 자산 식별자에는 콜론을 쓸 수 없습니다" }
    }

    companion object {
        val BTC_USDT =
            DevelopmentMarketContext(
                MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), AssetId("USDT"), 8),
                TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)),
            )
    }
}

/** 입력 목록을 복사하고 변경 가능한 뷰를 노출하지 않는다. 같은 ID의 중복도 덮어쓰지 않는다. */
internal class DevelopmentMarketRegistry(
    contexts: Collection<DevelopmentMarketContext>,
) {
    private val byMarket: Map<MarketId, DevelopmentMarketContext> =
        buildMap {
            contexts.forEach { context ->
                require(put(context.market.marketId, context) == null) { "개발 마켓 ID를 중복 등록할 수 없습니다" }
            }
            require(isNotEmpty()) { "개발 마켓을 하나 이상 등록해야 합니다" }
        }

    operator fun get(marketId: MarketId): DevelopmentMarketContext? = byMarket[marketId]

    /** 기존 단일 마켓 CLI·READY의 접근. 다중 등록에서 임의의 기본 마켓을 선택하지 않는다. */
    fun single(): DevelopmentMarketContext {
        require(byMarket.size == 1) { "단일 마켓 실행에 여러 마켓을 사용할 수 없습니다" }
        return byMarket.values.single()
    }
}
