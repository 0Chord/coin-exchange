package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.MarketMatchingIntakeLimits
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import java.nio.file.Files
import java.nio.file.Path

/** 고정 버전 manifest를 읽는다. 일반 HTTP나 기존 노드의 역할 차단은 변경하지 않는다. */
internal fun splitTradingConfiguration(
    manifest: TradingClusterManifest,
    environment: Map<String, String>,
): SplitTradingConfiguration {
    fun required(name: String) = requireNotNull(environment[name]) { "$name 설정이 필요합니다" }
    val matching = ClusterLineage(required("SPLIT_MATCHING_LINEAGE_HIGH").toLong(), required("SPLIT_MATCHING_LINEAGE_LOW").toLong())
    val epoch = required("SPLIT_OWNERSHIP_EPOCH").toLong()
    val topology = TradingRoleTopology(manifest, manifest.markets.map { TradingMarketAssignment(it.market.marketId, matching, epoch) })

    fun declaration(
        role: TradingServiceRole,
        lineage: ClusterLineage,
    ) = TradingRoleDeclaration(
        role,
        lineage,
        manifest.configurationVersion,
        manifest.markets,
        manifest.markets.associate {
            it.market.marketId to
                epoch
        },
    )
    val matchingDeclaration = declaration(TradingServiceRole.MATCHING, matching)
    val resultEntries = environment["SPLIT_MAX_RESULT_ENTRIES"]?.toInt() ?: 4_096
    val activeOrders = environment["SPLIT_MAX_MATCHING_ACTIVE"]?.toInt() ?: 8_192
    return SplitTradingConfiguration(
        topology,
        declaration(TradingServiceRole.FUNDS, manifest.lineage),
        matchingDeclaration,
        matchingLimits =
            MatchingIntakeLimits(
                manifest.markets.associate {
                    it.market.marketId to MarketMatchingIntakeLimits(maxResultEntries = resultEntries, maxActiveOrders = activeOrders)
                },
                maxResultEntries = resultEntries,
                maxActiveOrders = activeOrders,
            ),
    )
}

internal fun splitTradingSettings(environment: Map<String, String> = System.getenv()): SplitTradingNodeSettings {
    require(environment["SPLIT_MODE"] == "ORDER_DELIVERY_DEV") { "분리 노드는 명시한 개발 모드가 필요합니다" }

    fun required(name: String) = requireNotNull(environment[name]) { "$name 설정이 필요합니다" }
    val role = TradingServiceRole.parse(required("SPLIT_ROLE"))
    require(role == TradingServiceRole.FUNDS || role == TradingServiceRole.MATCHING)
    val manifestPath = Path.of(required("SPLIT_MANIFEST"))
    require(manifestPath.isAbsolute && Files.size(manifestPath) in 1..BoundedTradingStateCodec.MAX_MANIFEST_BYTES)
    val manifest = BoundedTradingStateCodec().decodeManifest(Files.readAllBytes(manifestPath))
    val configuration = splitTradingConfiguration(manifest, environment)
    val transport =
        DevelopmentFundingSettings(
            runRoot = Path.of(required("FUNDING_RUN_ROOT")),
            basePort = required("FUNDING_BASE_PORT").toInt(),
            startupTimeoutMs = 60_000,
            memberId = required("FUNDING_MEMBER_ID").toInt(),
            advertisedHost = required("FUNDING_HOST"),
            bindHost = environment["FUNDING_BIND_HOST"] ?: "0.0.0.0",
            clusterMembers = required("FUNDING_MEMBERS"),
            runId = required("FUNDING_RUN_ID"),
            recoverExisting = environment["FUNDING_RECOVER_EXISTING"]?.toBooleanStrict() ?: false,
            boundedManifest = manifest,
            markets = DevelopmentMarketRegistry(manifest.markets.map { DevelopmentMarketContext(it.market, it.feePolicySnapshot) }),
            driverRoot = environment["FUNDING_DRIVER_ROOT"]?.let(Path::of),
        )
    return SplitTradingNodeSettings(
        transport,
        role,
        configuration,
        required("SPLIT_FUNDS_INGRESS"),
        required("SPLIT_MATCHING_INGRESS"),
        required("SPLIT_MODE"),
    )
}

/** 명시한 개발 모드의 실제 실행 진입점이다. 인증·인가 및 역방향 정산은 포함하지 않는다. */
fun main() {
    val settings = splitTradingSettings()
    DevelopmentSplitTradingNode.start(settings).use { node ->
        Runtime.getRuntime().addShutdownHook(Thread(node::close, "split-trading-shutdown"))
        fundingEvent(
            mapOf(
                "kind" to "SPLIT_RUNTIME_READY",
                "role" to settings.role.name,
                "memberId" to settings.transport.memberId,
                "pid" to ProcessHandle.current().pid(),
                "clusterId" to settings.clusterId,
                "storageLevels" to node.storageLevels,
                "topologySha256" to settings.configuration.topologySha256,
                "tradingReady" to node.tradingReady,
            ),
        )
        while (!node.isClosed && node.error == null) Thread.sleep(100)
        node.error?.let { throw IllegalStateException("분리 개발 실행 실패", it) }
    }
}
