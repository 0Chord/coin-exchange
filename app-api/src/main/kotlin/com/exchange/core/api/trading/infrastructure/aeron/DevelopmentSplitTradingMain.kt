package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchLimits
import com.exchange.core.trading.MarketMatchingIntakeLimits
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import java.nio.file.Files
import java.nio.file.Path

/** 업무 담당의 lineage와 실제 전송 위치를 분리한다. 연결 정보는 실행 identity에 결속한다. */
internal data class SplitMatchingTarget(
    val lineage: ClusterLineage,
    val clusterId: Int,
    val ingressEndpoints: String,
)

private data class SplitConfiguredMatchingOwner(
    val lineage: ClusterLineage,
    val ownershipEpoch: Long,
    val markets: List<TradingMarketContext>,
    val target: SplitMatchingTarget? = null,
)

/** 명시한 1~2 담당만 읽으며 기존 단일 담당 설정과의 혼용을 거절한다. */
private fun splitMatchingOwners(
    manifest: TradingClusterManifest,
    environment: Map<String, String>,
): List<SplitConfiguredMatchingOwner> {
    fun required(name: String) = requireNotNull(environment[name]) { "$name 설정이 필요합니다" }
    val legacyNames = setOf("SPLIT_MATCHING_LINEAGE_HIGH", "SPLIT_MATCHING_LINEAGE_LOW", "SPLIT_OWNERSHIP_EPOCH", "SPLIT_MATCHING_INGRESS")
    val indexedName = Regex("SPLIT_MATCHING_([0-9]+)_.*")
    val count = environment["SPLIT_MATCHING_OWNER_COUNT"]?.toInt()
    if (count == null) {
        require(environment.keys.none { indexedName.matches(it) || it.startsWith("SPLIT_SELF_MATCHING_") }) {
            "담당별 설정은 SPLIT_MATCHING_OWNER_COUNT와 함께 공급해야 합니다"
        }
        return listOf(
            SplitConfiguredMatchingOwner(
                ClusterLineage(required("SPLIT_MATCHING_LINEAGE_HIGH").toLong(), required("SPLIT_MATCHING_LINEAGE_LOW").toLong()),
                required("SPLIT_OWNERSHIP_EPOCH").toLong(),
                manifest.markets,
            ),
        )
    }
    require(count in 1..2) { "매칭 담당은 1~2개만 지원합니다" }
    require(environment.keys.none { it in legacyNames }) { "기존 단일 담당 설정과 담당별 설정을 함께 공급할 수 없습니다" }
    require(
        environment.keys
            .mapNotNull {
                indexedName
                    .matchEntire(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            }.all { it in 0 until count },
    ) { "선언한 담당 수 밖의 설정입니다" }
    return (0 until count).map { index ->
        val prefix = "SPLIT_MATCHING_${index}_"
        val lineage = ClusterLineage(required("${prefix}LINEAGE_HIGH").toLong(), required("${prefix}LINEAGE_LOW").toLong())
        val ids = required("${prefix}MARKETS").split(',').map(String::trim)
        require(ids.isNotEmpty() && ids.all(String::isNotBlank) && ids.distinct().size == ids.size) { "담당 마켓 목록이 비었거나 중복됐습니다" }
        require(ids.all { id -> manifest.markets.any { it.market.marketId.value == id } }) { "등록되지 않은 담당 마켓입니다" }
        SplitConfiguredMatchingOwner(
            lineage,
            required("${prefix}OWNERSHIP_EPOCH").toLong(),
            // manifest의 순서와 원래 ordinal을 부분집합에도 그대로 보존한다.
            manifest.markets.filter { it.market.marketId.value in ids },
            SplitMatchingTarget(lineage, required("${prefix}CLUSTER_ID").toInt(), required("${prefix}INGRESS")),
        )
    }
}

/** 고정 버전 manifest를 읽는다. 일반 HTTP나 기존 노드의 역할 차단은 변경하지 않는다. */
internal fun splitTradingConfiguration(
    manifest: TradingClusterManifest,
    environment: Map<String, String>,
): SplitTradingConfiguration {
    val owners = splitMatchingOwners(manifest, environment)
    val topology =
        TradingRoleTopology(
            manifest,
            owners.flatMap { owner ->
                owner.markets.map { TradingMarketAssignment(it.market.marketId, owner.lineage, owner.ownershipEpoch) }
            },
        )

    fun declaration(
        role: TradingServiceRole,
        lineage: ClusterLineage,
        markets: List<TradingMarketContext>,
    ) = TradingRoleDeclaration(
        role,
        lineage,
        manifest.configurationVersion,
        markets,
        topology.assignments
            .filter { assignment -> markets.any { it.market.marketId == assignment.marketId } }
            .associate { it.marketId to it.ownershipEpoch },
    )
    val matchingDeclarations = owners.map { declaration(TradingServiceRole.MATCHING, it.lineage, it.markets) }
    val selectedLineage =
        if (environment["SPLIT_MATCHING_OWNER_COUNT"] != null && environment["SPLIT_ROLE"] == TradingServiceRole.MATCHING.name) {
            ClusterLineage(
                requireNotNull(environment["SPLIT_SELF_MATCHING_LINEAGE_HIGH"]) { "SPLIT_SELF_MATCHING_LINEAGE_HIGH 설정이 필요합니다" }.toLong(),
                requireNotNull(environment["SPLIT_SELF_MATCHING_LINEAGE_LOW"]) { "SPLIT_SELF_MATCHING_LINEAGE_LOW 설정이 필요합니다" }.toLong(),
            )
        } else {
            owners.minWith(compareBy({ it.lineage.high }, { it.lineage.low })).lineage
        }
    val matchingDeclaration = matchingDeclarations.singleOrNull { it.lineage == selectedLineage }
    requireNotNull(matchingDeclaration) { "자기 매칭 담당이 전체 선언에 없습니다" }
    val resultEntries = environment["SPLIT_MAX_RESULT_ENTRIES"]?.toInt() ?: 4_096
    val activeOrders = environment["SPLIT_MAX_MATCHING_ACTIVE"]?.toInt() ?: 8_192
    val inputContractVersion = environment["SPLIT_INPUT_CONTRACT_VERSION"]?.takeIf { it.isNotBlank() }?.toInt() ?: 1
    val dispatchBytes = environment["SPLIT_MAX_FUNDS_DISPATCH_BYTES"]?.takeIf { it.isNotBlank() }?.toLong()
    val activeBytes = environment["SPLIT_MAX_FUNDS_ACTIVE_BYTES"]?.takeIf { it.isNotBlank() }?.toLong()
    val defaults = FundsDispatchLimits.defaults(manifest)
    val dispatchLimits =
        if (dispatchBytes == null && activeBytes == null) {
            defaults
        } else {
            FundsDispatchLimits(
                defaults.marketLimits.mapValues { (_, limits) ->
                    limits.copy(
                        maxDispatchBytes = dispatchBytes ?: limits.maxDispatchBytes,
                        maxActiveBytes =
                            activeBytes ?: limits.maxActiveBytes,
                    )
                },
                defaults.maxDispatchEntries,
                dispatchBytes ?: defaults.maxDispatchBytes,
                defaults.maxActiveOrders,
                activeBytes ?: defaults.maxActiveBytes,
            )
        }
    val matchingLimitsByLineage =
        matchingDeclarations.associate { declaration ->
            declaration.lineage to
                MatchingIntakeLimits(
                    declaration.markets.associate {
                        it.market.marketId to MarketMatchingIntakeLimits(maxResultEntries = resultEntries, maxActiveOrders = activeOrders)
                    },
                    maxResultEntries = resultEntries,
                    maxActiveOrders = activeOrders,
                )
        }
    val querySource = environment["EXCHANGE_ORDER_QUERY_SOURCE"] ?: "live"
    require(querySource in setOf("live", "archive")) { "잘못된 주문 조회 원본 설정입니다" }
    return SplitTradingConfiguration(
        topology,
        declaration(TradingServiceRole.FUNDS, manifest.lineage, manifest.markets),
        matchingDeclaration,
        dispatchLimits = dispatchLimits,
        matchingLimits = matchingLimitsByLineage.getValue(matchingDeclaration.lineage),
        inputContractVersion = inputContractVersion,
        matchingDeclarations = matchingDeclarations,
        matchingLimitsByLineage = matchingLimitsByLineage,
        queryRetention = splitQueryRetentionConfiguration(topology, environment, dispatchLimits, inputContractVersion),
        transferBatchEnabled = environment["SPLIT_TRANSFER_BATCH_ENABLED"]?.toBooleanStrict() ?: false,
        archiveQueryEnabled = querySource == "archive",
    )
}

internal fun splitTradingSettings(environment: Map<String, String> = System.getenv()): SplitTradingNodeSettings {
    require(environment["SPLIT_MODE"] in setOf("ORDER_DELIVERY_DEV", "RESULT_SETTLEMENT_DEV")) { "분리 노드는 명시한 개발 모드가 필요합니다" }

    fun required(name: String) = requireNotNull(environment[name]) { "$name 설정이 필요합니다" }
    val role = TradingServiceRole.parse(required("SPLIT_ROLE"))
    require(role == TradingServiceRole.FUNDS || role == TradingServiceRole.MATCHING)
    val manifestPath = Path.of(required("SPLIT_MANIFEST"))
    require(manifestPath.isAbsolute && Files.size(manifestPath) in 1..BoundedTradingStateCodec.MAX_MANIFEST_BYTES)
    val manifest = BoundedTradingStateCodec().decodeManifest(Files.readAllBytes(manifestPath))
    val configuration = splitTradingConfiguration(manifest, environment)
    val matchingTargets = splitMatchingOwners(manifest, environment).mapNotNull { it.target }
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
        if (matchingTargets.isEmpty()) {
            required("SPLIT_MATCHING_INGRESS")
        } else {
            matchingTargets
                .single {
                    it.lineage ==
                        configuration.matchingLineage
                }.ingressEndpoints
        },
        required("SPLIT_MODE"),
        matchingTargets,
    )
}

/** 명시한 개발 모드의 실제 실행 진입점이다. 일반 HTTP와 인증·인가는 변경하지 않는다. */
fun main() {
    val settings = splitTradingSettings()
    DevelopmentSplitTradingNode.start(settings).use { node ->
        Runtime.getRuntime().addShutdownHook(Thread(node::close, "split-trading-shutdown"))
        fundingEvent(
            mapOf(
                "kind" to "SPLIT_RUNTIME_READY",
                "role" to settings.role.name,
                "mode" to settings.mode,
                "memberId" to settings.transport.memberId,
                "pid" to ProcessHandle.current().pid(),
                "clusterId" to settings.clusterId,
                "storageLevels" to node.storageLevels,
                "topologySha256" to settings.configuration.topologySha256,
                "resultTopologySha256" to if (settings.resultDeliveryEnabled) settings.configuration.resultTopologySha256 else null,
                "tradingReady" to node.tradingReady,
            ),
        )
        while (!node.isClosed && node.error == null) Thread.sleep(100)
        node.error?.let { throw IllegalStateException("분리 개발 실행 실패", it) }
    }
}
