package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingServiceRole
import java.net.BindException
import java.net.DatagramSocket
import java.nio.file.Path
import java.util.concurrent.ThreadLocalRandom

/** 실제 HTTP 시험과 별도 서버 재기동 시험이 같은 단일 마켓 설정을 사용한다. */
internal class SplitTradingHttpTestSettings(
    private val directory: Path,
    private val lineage: ClusterLineage,
) {
    fun settingsEnvironment(
        manifest: Path,
        role: TradingServiceRole,
        base: Int,
        consumerId: String? = null,
        persistSettlements: Boolean = false,
        archiveQuery: Boolean = false,
    ): Map<String, String> {
        val port = if (role == TradingServiceRole.FUNDS) base else base + 16
        val querySettings =
            buildMap {
                if (consumerId != null) put("EXCHANGE_ORDER_QUERY_CONSUMER_ID", consumerId)
                if (persistSettlements) put("EXCHANGE_ORDER_QUERY_PERSIST_SETTLEMENTS", "true")
                if (archiveQuery) put("EXCHANGE_ORDER_QUERY_SOURCE", "archive")
            }
        return mapOf(
            "SPLIT_MODE" to "RESULT_SETTLEMENT_DEV",
            "SPLIT_ROLE" to role.name,
            "SPLIT_MANIFEST" to manifest.toString(),
            "SPLIT_INPUT_CONTRACT_VERSION" to "2",
            "SPLIT_MAX_FUNDS_DISPATCH_BYTES" to "65536",
            "SPLIT_MAX_FUNDS_ACTIVE_BYTES" to "65536",
            "SPLIT_MATCHING_LINEAGE_HIGH" to "51",
            "SPLIT_MATCHING_LINEAGE_LOW" to "52",
            "SPLIT_OWNERSHIP_EPOCH" to "1",
            "SPLIT_FUNDS_INGRESS" to "0=127.0.0.1:$base",
            "SPLIT_MATCHING_INGRESS" to "0=127.0.0.1:${base + 16}",
            "FUNDING_RUN_ROOT" to directory.resolve(role.name.lowercase()).toString(),
            "FUNDING_BASE_PORT" to port.toString(),
            "FUNDING_MEMBER_ID" to "0",
            "FUNDING_HOST" to "127.0.0.1",
            "FUNDING_MEMBERS" to "0,${(0..4).joinToString(",") { "127.0.0.1:${port + it}" }}",
            "FUNDING_RUN_ID" to "split-http",
        ) + querySettings
    }

    fun manifest(
        feeRates: MakerTakerFeeRates,
        maxFundingAccounts: Int,
        market: MarketDefinition? = null,
    ): TradingClusterManifest =
        TradingClusterManifest(
            lineage,
            "split-http",
            BoundedExecutionIdentity.artifactSha256,
            BoundedExecutionIdentity.schemaSha256,
            1,
            listOf(
                TradingMarketContext(
                    market ?: MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), AssetId("USDT"), 0),
                    TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, feeRates),
                    0,
                ),
            ),
            TradingResourceLimits(
                maxAccounts = maxFundingAccounts,
                channelsPerAccount = 1,
                maxChannels = 8,
                requestWindow = 16,
                maxInboxEntries = 128,
                maxActiveOrders = 32,
                maxPendingOrders = 32,
                maxMarketActiveOrders = 32,
                maxMarketPendingOrders = 32,
                maxBalanceKeys = maxOf(32, maxFundingAccounts),
                maxPreparations = maxOf(16, maxFundingAccounts),
                maxMarkets = 1,
                maxAssets = 2,
            ),
        )

    fun availablePorts(extraOffsets: List<Int> = emptyList()): Int {
        repeat(100) {
            // 노드 기동 뒤에 바인딩하는 WAS의 고정 포트를 OS 임시 UDP 응답 포트와 분리한다.
            val base = ThreadLocalRandom.current().nextInt(16_000, 30_000)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                for (offset in (0..8) + (16..24) + (32..33) + extraOffsets) sockets.add(DatagramSocket(base + offset))
                return base
            } catch (_: BindException) {
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("자금·매칭 노드와 WAS 응답에 필요한 포트를 확보하지 못했습니다")
    }
}
