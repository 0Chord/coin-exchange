package com.exchange.core.trading

/** 같은 manifest와 Snapshot에 포함하는 논리 자원 예산. 로컬 heap/디스크 관측값이 아니다. */
data class TradingResourceLimits(
    val maxAccounts: Int = 1_024,
    val channelsPerAccount: Int = 2,
    val maxChannels: Int = 2_048,
    val requestWindow: Int = 4_096,
    val maxInboxEntries: Int = 32_768,
    val maxInboxBytes: Long = 16L * 1_024 * 1_024,
    val maxActiveOrders: Int = 8_192,
    val maxPendingOrders: Int = 4_096,
    val maxMarketActiveOrders: Int = 4_096,
    val maxMarketPendingOrders: Int = 2_048,
    val maxBalanceKeys: Int = 4_096,
    val maxPreparations: Int = 4_096,
    val maxPreparationBytes: Long = 1L * 1_024 * 1_024,
    val maxMarkets: Int = 3,
    val maxAssets: Int = 4,
    val maxIdentifierBytes: Int = 256,
) {
    init {
        require(maxAccounts > 0 && channelsPerAccount > 0 && maxChannels > 0)
        require(maxChannels.toLong() <= maxAccounts.toLong() * channelsPerAccount)
        require(requestWindow > 0 && maxInboxEntries > 0 && maxInboxBytes > 0)
        require(maxActiveOrders > 0 && maxPendingOrders in 1..maxActiveOrders)
        require(maxMarketActiveOrders in 1..maxActiveOrders)
        require(maxMarketPendingOrders in 1..minOf(maxPendingOrders, maxMarketActiveOrders))
        require(maxBalanceKeys > 0 && maxPreparations in 1..maxBalanceKeys && maxPreparationBytes > 0)
        require(maxMarkets > 0 && maxAssets > 0)
        require(maxIdentifierBytes in 1..256)
    }
}
