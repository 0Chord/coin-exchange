package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 고정 manifest를 실제 공급 경계에 넣어 담당 선택과 공통 구성·유한 예산을 확인한다. */
class SplitMultiMatchingSettingsTest {
    @TempDir lateinit var directory: Path
    private val funds = ClusterLineage(101, 102)
    private val btc = ClusterLineage(201, 202)
    private val sui = ClusterLineage(301, 302)
    private val btcId = MarketId("BTC-USDT")
    private val suiId = MarketId("SUI-USDT")

    @Test
    fun `각 담당은 자기 마켓과 원래 ordinal만 조립하며 전체 지문은 같다`() {
        val configuration = configuration()
        val other = configuration.forMatching(sui)
        assertEquals(listOf(btc, sui), configuration.matchingDeclarations.map { it.lineage })
        assertEquals(listOf(btcId), configuration.matchingConfiguration.markets.map { it.market.marketId })
        assertEquals(listOf(suiId), other.matchingConfiguration.markets.map { it.market.marketId })
        assertEquals(listOf(1), other.matchingConfiguration.markets.map { it.ordinal })
        assertEquals(configuration.topologySha256, other.topologySha256)
        assertEquals(configuration.resultTopologySha256, other.resultTopologySha256)
        assertEquals(configuration.dispatchConfiguration.assignments, other.dispatchConfiguration.assignments)
        assertEquals(configuration.matchingLimitsByLineage, other.matchingLimitsByLineage)
        assertEquals("UNINITIALIZED", configuration.funds().lifecycle.name)
        assertEquals("UNINITIALIZED", other.matching().lifecycle.name)
        assertFailsWith<NoSuchElementException> { configuration.forMatching(ClusterLineage(999, 999)) }
    }

    @Test
    fun `담당 나열 순서와 자기 선택을 바꿔도 공통 지문과 불변 목록은 보존한다`() {
        val configuration = configuration()
        val declarations = configuration.matchingDeclarations.reversed().toMutableList()
        val reordered = configuration(declarations = declarations, selected = sui)
        declarations.clear()
        assertEquals(listOf(btc, sui), reordered.matchingDeclarations.map { it.lineage })
        assertEquals(configuration.topologySha256, reordered.topologySha256)
        assertEquals(configuration.resultTopologySha256, reordered.resultTopologySha256)
        assertFailsWith<UnsupportedOperationException> { (reordered.matchingDeclarations as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (reordered.matchingLimitsByLineage as MutableMap<*, *>).clear() }
    }

    @Test
    fun `다른 담당의 한도와 배치 세대 정책 변경도 두 지문에 결속한다`() {
        val baseline = configuration()
        val otherLimits = baseline.matchingLimitsByLineage.getValue(sui)
        val changedLimits =
            MatchingIntakeLimits(otherLimits.marketLimits, maxResultEntries = otherLimits.maxResultEntries - 1)
        val changed =
            listOf(
                configuration(limits = baseline.matchingLimitsByLineage + (sui to changedLimits)),
                configuration(assignments = listOf(TradingMarketAssignment(btcId, sui, 1), TradingMarketAssignment(suiId, btc, 1))),
                configuration(assignments = listOf(TradingMarketAssignment(btcId, btc, 1), TradingMarketAssignment(suiId, sui, 2))),
                configuration(manifest = manifest(policyVersion = 2)),
                configuration(delivery = FundsOrderDeliveryLimits(maxReadEntries = 63)),
            )
        for (variant in changed) {
            assertNotEquals(baseline.topologySha256, variant.topologySha256)
            assertNotEquals(baseline.resultTopologySha256, variant.resultTopologySha256)
        }
        assertEquals(changed.size, changed.map { it.topologySha256 }.toSet().size)
    }

    @Test
    fun `전체 담당 선언과 한도는 누락 중복 자기 불일치를 거절한다`() {
        val valid = configuration()
        val declarations = valid.matchingDeclarations
        val invalidDeclarations =
            listOf(
                emptyList(),
                declarations.take(1),
                declarations + declarations.first(),
                declarations + declaration(valid.topology, ClusterLineage(401, 402)),
            )
        for (invalid in invalidDeclarations) {
            assertFailsWith<IllegalArgumentException> {
                SplitTradingConfiguration(valid.topology, valid.fundsDeclaration, valid.matchingDeclaration, matchingDeclarations = invalid)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            configuration(limits = valid.matchingLimitsByLineage - sui)
        }
        val original = valid.matchingLimits
        val different = MatchingIntakeLimits(original.marketLimits, maxActiveOrders = original.maxActiveOrders - 1)
        assertFailsWith<IllegalArgumentException> {
            SplitTradingConfiguration(
                valid.topology,
                valid.fundsDeclaration,
                valid.matchingDeclaration,
                matchingLimits = different,
                matchingDeclarations = valid.matchingDeclarations,
                matchingLimitsByLineage = valid.matchingLimitsByLineage,
            )
        }
    }

    @Test
    fun `실제 환경 파서는 전체 배치를 보존하고 자기 담당을 명시적으로 고른다`() {
        val manifest = manifest()
        val fundsConfiguration = splitTradingConfiguration(manifest, environment())
        val matchingConfiguration = splitTradingConfiguration(manifest, environment(TradingServiceRole.MATCHING))
        assertEquals(btc, fundsConfiguration.matchingLineage)
        assertEquals(sui, matchingConfiguration.matchingLineage)
        assertEquals(fundsConfiguration.topologySha256, matchingConfiguration.topologySha256)
        assertEquals(fundsConfiguration.resultTopologySha256, matchingConfiguration.resultTopologySha256)
        assertEquals(listOf(1), matchingConfiguration.matchingConfiguration.markets.map { it.ordinal })
        assertFailsWith<IllegalArgumentException> {
            splitTradingConfiguration(manifest, environment(TradingServiceRole.MATCHING) - "SPLIT_SELF_MATCHING_LINEAGE_HIGH")
        }
        assertFailsWith<IllegalArgumentException> {
            splitTradingConfiguration(manifest, environment(TradingServiceRole.MATCHING) + ("SPLIT_SELF_MATCHING_LINEAGE_LOW" to "999"))
        }
    }

    @Test
    fun `한 담당이 두 마켓을 맡는 명시 설정은 기존 단일 설정과 같은 지문을 쓴다`() {
        val legacy =
            mapOf(
                "SPLIT_MATCHING_LINEAGE_HIGH" to btc.high.toString(),
                "SPLIT_MATCHING_LINEAGE_LOW" to btc.low.toString(),
                "SPLIT_OWNERSHIP_EPOCH" to "1",
            )
        val explicit =
            environment().filterKeys { !it.startsWith("SPLIT_MATCHING_1_") } +
                mapOf("SPLIT_MATCHING_OWNER_COUNT" to "1", "SPLIT_MATCHING_0_MARKETS" to "SUI-USDT,BTC-USDT")
        val original = splitTradingConfiguration(manifest(), legacy)
        val configured = splitTradingConfiguration(manifest(), explicit)
        assertEquals(listOf(btcId, suiId), configured.matchingConfiguration.markets.map { it.market.marketId })
        assertEquals(original.topologySha256, configured.topologySha256)
        assertEquals(original.resultTopologySha256, configured.resultTopologySha256)
        assertEquals(configured.deliveryLimits, configured.orderDeliveryLimits())
        assertEquals(0L, configured.orderDeliveryControlReserve())
    }

    @Test
    fun `공급 배치의 누락 중복 미등록 담당수 초과와 기존 환경 혼용을 거절한다`() {
        val base = environment()
        val invalid =
            listOf(
                base + ("SPLIT_MATCHING_OWNER_COUNT" to "0"),
                base + ("SPLIT_MATCHING_OWNER_COUNT" to "3"),
                base + ("SPLIT_MATCHING_0_MARKETS" to "BTC-USDT,BTC-USDT"),
                base + ("SPLIT_MATCHING_0_MARKETS" to "UNKNOWN-USDT"),
                base + ("SPLIT_MATCHING_0_MARKETS" to ""),
                base + ("SPLIT_MATCHING_1_MARKETS" to "BTC-USDT"),
                base + mapOf("SPLIT_MATCHING_1_LINEAGE_HIGH" to btc.high.toString(), "SPLIT_MATCHING_1_LINEAGE_LOW" to btc.low.toString()),
                base - "SPLIT_MATCHING_1_MARKETS",
                base + ("SPLIT_MATCHING_2_MARKETS" to "SUI-USDT"),
                base - "SPLIT_MATCHING_OWNER_COUNT",
            ) +
                listOf("SPLIT_MATCHING_LINEAGE_HIGH", "SPLIT_MATCHING_LINEAGE_LOW", "SPLIT_OWNERSHIP_EPOCH", "SPLIT_MATCHING_INGRESS").map {
                    base +
                        (it to "1")
                }
        for (environment in invalid) {
            assertFailsWith<IllegalArgumentException>(environment.toString()) { splitTradingConfiguration(manifest(), environment) }
        }
    }

    @Test
    fun `담당별 유한 조회와 송신 창의 합은 전체 상한 안이며 ACK와 한 원본 공간을 남긴다`() {
        val global =
            FundsOrderDeliveryLimits(maxReadEntries = 63, maxInFlightEntries = 63, maxReadBytes = 262_143, maxInFlightBytes = 524_287)
        val configuration = configuration(delivery = global)
        val worker = configuration.orderDeliveryLimits()
        assertEquals(31, worker.maxReadEntries)
        assertEquals(31, worker.maxInFlightEntries)
        assertEquals(131_071L, worker.maxReadBytes)
        assertEquals(262_143L, worker.maxInFlightBytes)
        assertTrue(2 * worker.maxReadEntries <= global.maxReadEntries)
        assertTrue(2 * worker.maxReadBytes <= global.maxReadBytes)
        assertTrue(2 * worker.maxInFlightEntries <= global.maxInFlightEntries)
        assertTrue(2 * worker.maxInFlightBytes <= global.maxInFlightBytes)
        for (lineage in listOf(btc, sui)) {
            val selected = configuration.forMatching(lineage)
            assertTrue(selected.orderDeliveryControlReserve() > 0)
            assertTrue(
                SplitOrderDeliveryCodec.maximumDispatchFrameBytes() + selected.orderDeliveryControlReserve() <= worker.maxInFlightBytes,
            )
        }
    }

    @Test
    fun `담당별 최소 조회 주문 ACK 공간을 나눌 수 없는 전체 예산은 기동 전에 거절한다`() {
        val invalid =
            listOf(
                FundsOrderDeliveryLimits(maxReadEntries = 1),
                FundsOrderDeliveryLimits(maxReadBytes = 6_729),
                FundsOrderDeliveryLimits(maxInFlightEntries = 3),
                FundsOrderDeliveryLimits(maxInFlightBytes = 65_535),
            )
        for (limits in invalid) assertFailsWith<IllegalArgumentException> { configuration(delivery = limits) }
        assertFalse(Files.exists(directory.resolve("run")))
    }

    @Test
    fun `환경 settings 진입점은 두 실제 목적지를 Node 설정으로 전달하고 기록을 만들지 않는다`() {
        val base = settingsEnvironment()
        val fundsSettings = splitTradingSettings(base)
        val matchingSettings =
            splitTradingSettings(
                base + environment(TradingServiceRole.MATCHING) +
                    mapOf("FUNDING_BASE_PORT" to "21700", "FUNDING_MEMBERS" to members(21700)),
            )
        assertEquals(listOf(btc, sui), fundsSettings.matchingTargets.map { it.lineage })
        assertEquals(listOf(199, 200), fundsSettings.matchingTargets.map { it.clusterId })
        assertEquals("0=127.0.0.1:21700", matchingSettings.matchingIngressEndpoints)
        assertEquals(sui, matchingSettings.configuration.matchingLineage)
        assertEquals(fundsSettings.configuration.topologySha256, matchingSettings.configuration.topologySha256)
        assertFalse(Files.exists(directory.resolve("run")))
    }

    @Test
    fun `서로 다른 담당의 cluster ID와 ingress 및 자기 포트 충돌은 기록 생성 전에 거절한다`() {
        val base = settingsEnvironment()
        val invalid =
            listOf(
                base + ("SPLIT_MATCHING_1_CLUSTER_ID" to "199"),
                base + ("SPLIT_MATCHING_1_CLUSTER_ID" to "198"),
                base + ("SPLIT_MATCHING_1_INGRESS" to "0=127.0.0.1:20700"),
                base + ("SPLIT_MATCHING_1_INGRESS" to "0=127.0.0.1:19709"),
                base + ("SPLIT_MATCHING_0_INGRESS" to "0=127.0.0.1:19700"),
            )
        for (environment in invalid) assertFailsWith<IllegalArgumentException> { splitTradingSettings(environment) }
        assertFalse(Files.exists(directory.resolve("run")))
    }

    @Test
    fun `같은 그룹의 다른 멤버와 응답 포트가 겹쳐도 기록 생성 전에 거절한다`() {
        val normal = splitTradingSettings(withMembers(listOf(19700, 19720, 19740)))
        assertEquals("127.0.0.1:19709", normal.endpoint(false, sui).egressEndpoint)
        for (peerPort in listOf(19709, 19710, 19695)) {
            assertFailsWith<IllegalArgumentException> { splitTradingSettings(withMembers(listOf(19700, peerPort, 19740))) }
        }
        assertFalse(Files.exists(directory.resolve("run")))
    }

    @Test
    fun `자기 멤버 ingress는 실제 노드의 기본 포트와 같아야 한다`() {
        for (selfPort in listOf(19701, 19709)) {
            assertFailsWith<IllegalArgumentException> { splitTradingSettings(withMembers(listOf(selfPort, 19720, 19740))) }
        }
        // 광고 주소의 기존 별칭은 허용하되 실제 바인딩 포트의 불일치는 허용하지 않는다.
        splitTradingSettings(withMembers(listOf(19700, 19720, 19740), "localhost"))
        assertFalse(Files.exists(directory.resolve("run")))
    }

    private fun withMembers(
        ports: List<Int>,
        selfHost: String = "127.0.0.1",
    ): Map<String, String> {
        fun host(index: Int) = if (index == 0) selfHost else "127.0.0.1"
        return settingsEnvironment() +
            mapOf(
                "FUNDING_MEMBERS" to
                    ports
                        .mapIndexed {
                            index,
                            port,
                            ->
                            "$index," + (0..4).joinToString(",") { "${host(index)}:${port + it}" }
                        }.joinToString("|"),
                "SPLIT_FUNDS_INGRESS" to ports.mapIndexed { index, port -> "$index=${host(index)}:$port" }.joinToString(","),
            )
    }

    private fun configuration(
        manifest: TradingClusterManifest = manifest(),
        assignments: List<TradingMarketAssignment> = listOf(TradingMarketAssignment(btcId, btc, 1), TradingMarketAssignment(suiId, sui, 1)),
        declarations: List<TradingRoleDeclaration>? = null,
        selected: ClusterLineage = btc,
        limits: Map<ClusterLineage, MatchingIntakeLimits>? = null,
        delivery: FundsOrderDeliveryLimits = FundsOrderDeliveryLimits(),
    ): SplitTradingConfiguration {
        val topology = TradingRoleTopology(manifest, assignments)
        val owners = declarations ?: listOf(declaration(topology, btc), declaration(topology, sui))
        val chosen = owners.single { it.lineage == selected }
        val allLimits = limits ?: owners.associate { it.lineage to MatchingIntakeLimits.defaults(topology, it) }
        return SplitTradingConfiguration(
            topology,
            declaration(topology, funds, TradingServiceRole.FUNDS),
            chosen,
            matchingLimits = allLimits[selected] ?: MatchingIntakeLimits.defaults(topology, chosen),
            deliveryLimits = delivery,
            matchingDeclarations = owners,
            matchingLimitsByLineage = allLimits,
        )
    }

    private fun declaration(
        topology: TradingRoleTopology,
        lineage: ClusterLineage,
        role: TradingServiceRole = TradingServiceRole.MATCHING,
    ): TradingRoleDeclaration {
        val assignments = topology.assignments.filter { role == TradingServiceRole.FUNDS || it.matchingLineage == lineage }
        val markets = topology.fundsManifest.markets.filter { context -> assignments.any { it.marketId == context.market.marketId } }
        return TradingRoleDeclaration(
            role,
            lineage,
            topology.fundsManifest.configurationVersion,
            markets,
            assignments.associate {
                it.marketId to
                    it.ownershipEpoch
            },
        )
    }

    private fun manifest(
        policyVersion: Long = 1,
        artifactSha256: String = "a".repeat(64),
        schemaSha256: String = "b".repeat(64),
    ): TradingClusterManifest =
        TradingClusterManifest(
            funds,
            "split-multi-settings-test",
            artifactSha256,
            schemaSha256,
            7,
            listOf("BTC", "SUI").mapIndexed { ordinal, asset ->
                TradingMarketContext(
                    MarketDefinition(MarketId("$asset-USDT"), AssetId(asset), AssetId("USDT"), 0),
                    TradingFeePolicySnapshot(
                        FeeProductType.SPOT,
                        FeeTier.NORMAL,
                        policyVersion,
                        MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO),
                    ),
                    ordinal,
                )
            },
            TradingResourceLimits(),
        )

    private fun environment(role: TradingServiceRole = TradingServiceRole.FUNDS): Map<String, String> =
        mapOf(
            "SPLIT_ROLE" to role.name,
            "SPLIT_MATCHING_OWNER_COUNT" to "2",
            "SPLIT_MATCHING_0_LINEAGE_HIGH" to btc.high.toString(),
            "SPLIT_MATCHING_0_LINEAGE_LOW" to btc.low.toString(),
            "SPLIT_MATCHING_0_OWNERSHIP_EPOCH" to "1",
            "SPLIT_MATCHING_0_MARKETS" to "BTC-USDT",
            "SPLIT_MATCHING_0_CLUSTER_ID" to "199",
            "SPLIT_MATCHING_0_INGRESS" to "0=127.0.0.1:20700",
            "SPLIT_MATCHING_1_LINEAGE_HIGH" to sui.high.toString(),
            "SPLIT_MATCHING_1_LINEAGE_LOW" to sui.low.toString(),
            "SPLIT_MATCHING_1_OWNERSHIP_EPOCH" to "1",
            "SPLIT_MATCHING_1_MARKETS" to "SUI-USDT",
            "SPLIT_MATCHING_1_CLUSTER_ID" to "200",
            "SPLIT_MATCHING_1_INGRESS" to "0=127.0.0.1:21700",
        ) +
            if (role == TradingServiceRole.MATCHING) {
                mapOf("SPLIT_SELF_MATCHING_LINEAGE_HIGH" to sui.high.toString(), "SPLIT_SELF_MATCHING_LINEAGE_LOW" to sui.low.toString())
            } else {
                emptyMap()
            }

    private fun settingsEnvironment(): Map<String, String> {
        val path = directory.resolve("manifest.bin")
        val manifest =
            manifest(artifactSha256 = BoundedExecutionIdentity.artifactSha256, schemaSha256 = BoundedExecutionIdentity.schemaSha256)
        Files.write(path, BoundedTradingStateCodec().encodeManifest(manifest))
        return environment() +
            mapOf(
                "SPLIT_MODE" to "RESULT_SETTLEMENT_DEV",
                "SPLIT_MANIFEST" to path.toString(),
                "SPLIT_FUNDS_INGRESS" to "0=127.0.0.1:19700",
                "FUNDING_RUN_ROOT" to directory.resolve("run").toString(),
                "FUNDING_BASE_PORT" to "19700",
                "FUNDING_MEMBER_ID" to "0",
                "FUNDING_HOST" to "127.0.0.1",
                "FUNDING_MEMBERS" to members(19700),
                "FUNDING_RUN_ID" to "multi-settings-test",
            )
    }

    private fun members(basePort: Int): String = "0,${(0..4).joinToString(",") { "127.0.0.1:${basePort + it}" }}"
}
