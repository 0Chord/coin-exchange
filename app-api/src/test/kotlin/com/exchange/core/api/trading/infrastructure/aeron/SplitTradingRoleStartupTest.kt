package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchLimits
import com.exchange.core.trading.FundsResultLimits
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import io.aeron.driver.exceptions.InvalidChannelException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 잘못된 설정·복구 기록은 Driver·Archive를 열기 전에 거절한다. 기존 일반 진입 차단도 별도로 회귀한다. */
class SplitTradingRoleStartupTest {
    @Test
    fun `취소 계약은 양방향 정산 모드에서만 선택하며 기존 root를 자동 승격하지 않는다`() {
        val original = configuration().dispatchLimits
        val bounded =
            FundsDispatchLimits(
                original.marketLimits.mapValues { (_, limits) -> limits.copy(maxDispatchBytes = 65_536, maxActiveBytes = 65_536) },
                maxDispatchBytes = 65_536,
                maxActiveBytes = 65_536,
            )
        val legacy = configuration(dispatchLimits = bounded)
        val fresh = configuration(dispatchLimits = bounded, inputContractVersion = 2)
        assertNotEquals(legacy.topologySha256, fresh.topologySha256)
        assertNotEquals(legacy.resultTopologySha256, fresh.resultTopologySha256)
        for (role in listOf(TradingServiceRole.FUNDS, TradingServiceRole.MATCHING)) {
            val freshRoot = directory.resolve("cancellation-result-${role.name}")
            val enabled = settings(freshRoot, fresh, role, "RESULT_SETTLEMENT_DEV")
            assertEquals(2, enabled.endpoint(false).inputContractVersion)
            assertEquals(2, enabled.endpoint(true).inputContractVersion)
            assertTrue(enabled.resultDeliveryEnabled)
            assertFalse(Files.exists(freshRoot))
            for (mode in listOf("ORDER_DELIVERY_DEV")) {
                val root = directory.resolve("unsupported-cancellation-${role.name}-$mode")
                val failure = assertFailsWith<IllegalArgumentException> { settings(root, fresh, role, mode) }
                assertTrue(failure.message.orEmpty().contains("양방향 정산 개발 모드"))
                assertFalse(Files.exists(root))
                val existing = Files.createDirectory(root)
                val sentinel = existing.resolve("sentinel.bin")
                val bytes = byteArrayOf(2, 7, 1, 8)
                Files.write(sentinel, bytes)
                assertFailsWith<IllegalArgumentException> {
                    settings(root, fresh, role, mode, transport(root, fresh, role).copy(recoverExisting = true))
                }
                assertEquals(setOf(sentinel), Files.list(root).use { it.toList().toSet() })
                assertContentEquals(bytes, Files.readAllBytes(sentinel))
            }
        }
    }

    @Test
    fun `입력 계약과 자금 예산은 명시한 개발 설정으로 선택하고 모르는 버전은 거절한다`() {
        val manifest = configuration().topology.fundsManifest
        val environment =
            mapOf(
                "SPLIT_MATCHING_LINEAGE_HIGH" to matchingLineage.high.toString(),
                "SPLIT_MATCHING_LINEAGE_LOW" to matchingLineage.low.toString(),
                "SPLIT_OWNERSHIP_EPOCH" to "1",
            )
        assertEquals(1, splitTradingConfiguration(manifest, environment).inputContractVersion)
        val explicit =
            environment +
                mapOf(
                    "SPLIT_INPUT_CONTRACT_VERSION" to "2",
                    "SPLIT_MAX_FUNDS_DISPATCH_BYTES" to "65536",
                    "SPLIT_MAX_FUNDS_ACTIVE_BYTES" to "65536",
                )
        val mixed = splitTradingConfiguration(manifest, explicit)
        assertEquals(2, mixed.inputContractVersion)
        assertEquals(65_536L, mixed.dispatchLimits.maxDispatchBytes)
        assertEquals(65_536L, mixed.dispatchLimits.maxActiveBytes)
        assertFailsWith<IllegalArgumentException> {
            splitTradingConfiguration(
                manifest,
                explicit + ("SPLIT_INPUT_CONTRACT_VERSION" to "3"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            splitTradingConfiguration(
                manifest,
                explicit + ("SPLIT_MAX_FUNDS_DISPATCH_BYTES" to "0"),
            )
        }
    }

    @Test
    fun `일시적 peer DNS 단절과 실제 채널 설정 업무 오류를 구분한다`() {
        assertFalse(isSplitNodeFatalError(InvalidChannelException("remote endpoint unavailable", UnknownHostException("matching0"))))
        assertTrue(isSplitNodeFatalError(InvalidChannelException("malformed channel", IllegalArgumentException("bad port"))))
        assertTrue(isSplitNodeFatalError(IllegalStateException("financial failure", UnknownHostException("business"))))
    }

    @TempDir lateinit var directory: Path
    private val market = MarketDefinition(MarketId("BASE-QUOTE"), AssetId("BASE"), AssetId("QUOTE"), 0)
    private val fundsLineage = ClusterLineage(101, 102)
    private val matchingLineage = ClusterLineage(201, 202)
    private val fundsIngress = "0=127.0.0.1:19700"
    private val matchingIngress = "0=127.0.0.1:20700"

    @Test
    fun `명시적 개발 모드만 분리 역할 설정을 만들고 초기화나 경로 생성은 하지 않는다`() {
        val configuration = configuration()
        listOf(TradingServiceRole.FUNDS, TradingServiceRole.MATCHING).forEach { role ->
            val root = directory.resolve("valid-${role.name}")
            val settings = settings(root, configuration, role)
            assertEquals(role, settings.role)
            assertEquals(if (role == TradingServiceRole.FUNDS) 198 else 199, settings.clusterId)
            assertNotEquals(settings.endpoint(false).clusterId, settings.endpoint(true).clusterId)
            assertEquals(settings.endpoint(false).driverDirectory, settings.endpoint(true).driverDirectory)
            assertNotEquals(settings.endpoint(false).egressEndpoint, settings.endpoint(true).egressEndpoint)
            assertFalse(Files.exists(root))
            assertEquals("UNINITIALIZED", configuration.funds().lifecycle.name)
            assertEquals("UNINITIALIZED", configuration.matching().lifecycle.name)
        }
    }

    @Test
    fun `개발 모드 누락과 통합 역할 및 기존 설정의 분리 역할은 경로 접근 전에 거절한다`() {
        val configuration = configuration()
        val cases: List<(Path) -> Unit> =
            listOf(
                { root ->
                    SplitTradingNodeSettings(
                        transport(root, configuration),
                        TradingServiceRole.FUNDS,
                        configuration,
                        fundsIngress,
                        matchingIngress,
                    )
                },
                { root -> settings(root, configuration, mode = "DISABLED") },
                { root -> settings(root, configuration, role = TradingServiceRole.INTEGRATED) },
                { root ->
                    val legacy = transport(root, configuration).copy(serviceRole = TradingServiceRole.FUNDS)
                    settings(root, configuration, transport = legacy)
                },
                { root ->
                    val legacy = transport(root, configuration).copy(serviceRole = TradingServiceRole.MATCHING)
                    settings(root, configuration, transport = legacy)
                },
            )
        cases.forEachIndexed { index, invalid ->
            val root = directory.resolve("disabled-$index")
            assertFailsWith<IllegalArgumentException> { invalid(root) }
            assertFalse(Files.exists(root))
        }
    }

    @Test
    fun `동일 버전의 다른 manifest와 잘못된 선언은 실행 루트와 기존 sentinel을 변경하지 않는다`() {
        val configuration = configuration()
        val root = Files.createDirectory(directory.resolve("existing"))
        val sentinel = root.resolve("sentinel.bin")
        val bytes = byteArrayOf(3, 1, 4, 1, 5)
        Files.write(sentinel, bytes)
        val before = Files.list(root).use { it.toList().toSet() }
        val different = configuration(policyVersion = 2)
        assertFailsWith<IllegalArgumentException> {
            settings(root, different, transport = transport(root, configuration).copy(recoverExisting = true))
        }
        val wrongFunds = declaration(TradingServiceRole.FUNDS, matchingLineage, configuration.topology.fundsManifest.markets)
        assertFailsWith<IllegalArgumentException> {
            SplitTradingConfiguration(configuration.topology, wrongFunds, configuration.matchingDeclaration)
        }
        val wrongMatching = declaration(TradingServiceRole.MATCHING, matchingLineage, different.topology.fundsManifest.markets)
        assertFailsWith<IllegalArgumentException> {
            SplitTradingConfiguration(configuration.topology, configuration.fundsDeclaration, wrongMatching)
        }
        assertEquals(before, Files.list(root).use { it.toList().toSet() })
        assertContentEquals(bytes, Files.readAllBytes(sentinel))
    }

    @Test
    fun `같은 endpoint와 중복 ingress 및 노드 포트와 겹친 상대 ingress는 시작 전에 거절한다`() {
        val configuration = configuration()
        val peers =
            listOf(
                fundsIngress to fundsIngress,
                fundsIngress to "1=127.0.0.1:19700",
                fundsIngress to "0=127.0.0.1:19707",
                fundsIngress to "0=127.0.0.1:19708",
                fundsIngress to "0=127.0.0.1:20700,0=127.0.0.1:20710",
                fundsIngress to "0=not-an-endpoint",
                "0=127.0.0.1:19701" to matchingIngress,
            )
        peers.forEachIndexed { index, (funds, matching) ->
            val root = directory.resolve("overlap-$index")
            assertFailsWith<IllegalArgumentException>("funds=$funds matching=$matching") {
                SplitTradingNodeSettings(
                    transport(root, configuration),
                    TradingServiceRole.FUNDS,
                    configuration,
                    funds,
                    matching,
                    "ORDER_DELIVERY_DEV",
                )
            }
            assertFalse(Files.exists(root))
        }
    }

    @Test
    fun `연속 client 포트 범위와 정규 경로를 검사하며 링크를 복구 루트로 받지 않는다`() {
        val configuration = configuration()
        val highPortRoot = directory.resolve("high-port")
        assertFailsWith<IllegalArgumentException> {
            settings(highPortRoot, configuration, transport = transport(highPortRoot, configuration).copy(basePort = 65528))
        }
        assertFalse(Files.exists(highPortRoot))
        val indirect = directory.resolve("intermediate/../indirect")
        assertFailsWith<IllegalArgumentException> { settings(indirect, configuration) }
        assertFalse(Files.exists(directory.resolve("intermediate")))
        val original = Files.createDirectory(directory.resolve("recording"))
        val link = Files.createSymbolicLink(directory.resolve("recording-link"), original)
        assertFailsWith<IllegalArgumentException> { settings(link, configuration) }
        assertTrue(Files.list(original).use { it.findAny().isEmpty })
    }

    @Test
    fun `정상 단일 원본도 읽을 수 없는 batch 예산은 구성 단계에서 거절한다`() {
        val root = directory.resolve("undersized-read")
        assertFailsWith<IllegalArgumentException> { configuration(deliveryLimits = FundsOrderDeliveryLimits(maxReadBytes = 1)) }
        assertFalse(Files.exists(root))
    }

    @Test
    fun `주문과 결과 구성 지문은 기존 바이트의 SHA256을 보존한다`() {
        // 실행물 자체의 지문은 빌드마다 달라지므로 고정된 manifest 입력으로 인코딩 계약을 검사한다.
        val configuration =
            configuration(
                artifactSha256 = "a".repeat(64),
                schemaSha256 = "b".repeat(64),
                deliveryLimits = FundsOrderDeliveryLimits(maxReadEntries = 31, maxInFlightEntries = 47, ackBatchSize = 13),
                resultDeliveryLimits = MatchingResultDeliveryLimits(maxReadEntries = 3, maxInFlightEntries = 5, ackBatchSize = 11),
            )
        // dbf021c의 기존 writer를 이 입력으로 실행해 얻은 두 지문이다.
        assertEquals("cf97094917f8cd11f86a79e16267038d4d3fb35891d5289c3033152bb3234535", configuration.topologySha256)
        assertEquals("d81ee8995db8f2d2977b79a67368c3eb80785b1900ad9ad43b20c633ace36876", configuration.resultTopologySha256)
    }

    @Test
    fun `구성 지문은 정책 담당 세대와 금융 전달 매칭 결과 및 worker 한도에 결속한다`() {
        val baseline = configuration()
        val dispatch = baseline.dispatchLimits
        val matching = baseline.matchingLimits
        val fewerDispatches = FundsDispatchLimits(dispatch.marketLimits, maxDispatchEntries = dispatch.maxDispatchEntries - 1)
        val fewerResults = MatchingIntakeLimits(matching.marketLimits, maxResultEntries = matching.maxResultEntries - 1)
        val variants =
            listOf(
                configuration(policyVersion = 2),
                configuration(ownershipEpoch = 2),
                configuration(resourceLimits = TradingResourceLimits(maxInboxEntries = 32_767)),
                configuration(dispatchLimits = fewerDispatches),
                configuration(matchingLimits = fewerResults),
                configuration(resultLimits = FundsResultLimits(maxReceiptEntries = 4_095)),
                configuration(deliveryLimits = FundsOrderDeliveryLimits(maxInFlightEntries = 63)),
                configuration(deliveryLimits = FundsOrderDeliveryLimits(attemptTimeoutNs = 2_999_999_999)),
            )
        assertEquals(baseline.topologySha256, configuration().topologySha256)
        assertTrue(baseline.topologySha256.matches(Regex("[0-9a-f]{64}")))
        variants.forEach { changed -> assertNotEquals(baseline.topologySha256, changed.topologySha256) }
        assertEquals(variants.size, variants.map { it.topologySha256 }.toSet().size)
    }

    @Test
    fun `복구 identity는 역할 상대 연결과 고정 구성의 변경을 구별한다`() {
        val root = directory.resolve("identity")
        val baseline = settings(root, configuration())
        val same = settings(root, configuration())
        assertContentEquals(baseline.identityBytes(), same.identityBytes())
        val changedPeer = baseline.copy(matchingIngressEndpoints = "0=127.0.0.1:21700")
        val changedRole = settings(root, configuration(), TradingServiceRole.MATCHING)
        val changedLimits = settings(root, configuration(deliveryLimits = FundsOrderDeliveryLimits(ackBatchSize = 15)))
        for (changed in listOf(changedPeer, changedRole, changedLimits)) {
            assertFalse(baseline.identityBytes().contentEquals(changed.identityBytes()))
        }
        val encoded = baseline.identityBytes().toString(Charsets.ISO_8859_1)
        assertTrue(encoded.contains(BoundedExecutionIdentity.artifactSha256))
        assertTrue(encoded.contains(BoundedExecutionIdentity.schemaSha256))
        assertTrue(encoded.contains(baseline.configuration.topologySha256))
        assertFalse(Files.exists(root))
    }

    @Test
    fun `다른 실행물 배치와 손상 identity의 분리 복구는 기록을 바꾸거나 빈 상태로 재개하지 않는다`() {
        for (role in listOf(TradingServiceRole.FUNDS, TradingServiceRole.MATCHING)) {
            for (variant in listOf("artifact", "topology", "truncated", "corrupted")) {
                val root = Files.createDirectory(directory.resolve("recovery-${role.name}-$variant"))
                val normal = settings(root, configuration(), role)
                val recovering = normal.copy(transport = normal.transport.copy(recoverExisting = true))
                val original = normal.identityBytes()
                val stored =
                    when (variant) {
                        "artifact" -> replaceIdentityText(original, BoundedExecutionIdentity.artifactSha256)
                        "topology" -> replaceIdentityText(original, normal.configuration.topologySha256)
                        "truncated" -> original.copyOf(original.size - 1)
                        else -> original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                    }
                val identity = root.resolve("split-recovery-identity.bin")
                Files.write(identity, stored)
                val sentinel = root.resolve("sentinel.bin")
                val preserved = byteArrayOf(3, 1, 4, 1, 5)
                Files.write(sentinel, preserved)
                val before = Files.list(root).use { it.toList().toSet() }
                val error = assertFailsWith<IllegalArgumentException> { DevelopmentSplitTradingNode.start(recovering) }
                assertTrue(error.message.orEmpty().contains("원래 기록과 다릅니다"), variant)
                assertEquals(before, Files.list(root).use { it.toList().toSet() })
                assertContentEquals(stored, Files.readAllBytes(identity))
                assertContentEquals(preserved, Files.readAllBytes(sentinel))
                assertFalse(Files.exists(Path.of(recovering.transport.driverDirectory)))
            }
        }
    }

    @Test
    fun `같은 identity라도 Archive나 recording log가 없으면 분리 복구를 거절하고 기록을 보존한다`() {
        for (role in listOf(TradingServiceRole.FUNDS, TradingServiceRole.MATCHING)) {
            for (missing in listOf("archive", "recording")) {
                val root = Files.createDirectory(directory.resolve("missing-${role.name}-$missing"))
                val normal = settings(root, configuration(), role)
                val recovering = normal.copy(transport = normal.transport.copy(recoverExisting = true))
                val identity = root.resolve("split-recovery-identity.bin")
                val bytes = normal.identityBytes()
                Files.write(identity, bytes)
                val present =
                    if (missing == "archive") {
                        normal.transport.clusterDirectory.resolve("recording.log")
                    } else {
                        normal.transport.archiveDirectory.resolve("archive.catalog")
                    }
                Files.createDirectories(present.parent)
                Files.write(present, byteArrayOf(7))
                val before = Files.walk(root).use { it.toList().toSet() }
                val error = assertFailsWith<IllegalArgumentException> { DevelopmentSplitTradingNode.start(recovering) }
                assertTrue(error.message.orEmpty().contains("복구 기록이 없습니다"), missing)
                assertEquals(before, Files.walk(root).use { it.toList().toSet() })
                assertContentEquals(bytes, Files.readAllBytes(identity))
                assertContentEquals(byteArrayOf(7), Files.readAllBytes(present))
                assertFalse(Files.exists(Path.of(recovering.transport.driverDirectory)))
            }
        }
    }

    @Test
    fun `양방향 결과 모드는 결과 작업 지문과 복구 identity를 별도로 결속한다`() {
        val baseline = configuration()
        val changed = configuration(resultDeliveryLimits = MatchingResultDeliveryLimits(maxInFlightEntries = 7))
        assertEquals(baseline.topologySha256, changed.topologySha256, "기존 schema88 배치 지문은 바꾸지 않는다")
        assertNotEquals(baseline.resultTopologySha256, changed.resultTopologySha256)
        val root = directory.resolve("result-mode")
        val old = settings(root, baseline)
        val fresh = settings(root, baseline, mode = "RESULT_SETTLEMENT_DEV")
        assertFalse(old.resultDeliveryEnabled)
        assertTrue(fresh.resultDeliveryEnabled)
        assertFalse(old.identityBytes().contentEquals(fresh.identityBytes()))
        assertTrue(fresh.identityBytes().toString(Charsets.ISO_8859_1).contains(baseline.resultTopologySha256))
        assertFalse(old.identityBytes().toString(Charsets.ISO_8859_1).contains(baseline.resultTopologySha256))
        val different = settings(root, changed, mode = "RESULT_SETTLEMENT_DEV")
        assertFalse(fresh.identityBytes().contentEquals(different.identityBytes()))
        assertFalse(Files.exists(root))
    }

    @Test
    fun `정상 결과 한 건이나 ACK 공간을 수용하지 못하는 새 모드는 자원 생성 전에 거절한다`() {
        val variants =
            listOf(
                MatchingResultDeliveryLimits(maxReadBytes = 1),
                MatchingResultDeliveryLimits(maxInFlightBytes = 1024),
            )
        variants.forEachIndexed { index, limits ->
            val root = directory.resolve("undersized-result-$index")
            val configuration = configuration(resultDeliveryLimits = limits)
            // 한 방향 실행은 이 별도 결과 한도를 사용하지 않는다.
            assertFalse(settings(root, configuration).resultDeliveryEnabled)
            assertFailsWith<IllegalArgumentException> { settings(root, configuration, mode = "RESULT_SETTLEMENT_DEV") }
            assertFalse(Files.exists(root))
        }
    }

    @Test
    fun `양방향 지문은 결과 조회 전송 ACK 재시도 종료의 모든 한도 변경을 구분한다`() {
        val limits = MatchingResultDeliveryLimits()
        val baseline = configuration(resultDeliveryLimits = limits)
        val variants =
            listOf(
                limits.copy(maxReadEntries = 7),
                limits.copy(maxReadBytes = limits.maxReadBytes - 1),
                limits.copy(maxInFlightEntries = 7),
                limits.copy(maxInFlightBytes = limits.maxInFlightBytes - 1),
                limits.copy(ackBatchSize = 15),
                limits.copy(ackDelayNs = limits.ackDelayNs - 1),
                limits.copy(attemptTimeoutNs = limits.attemptTimeoutNs - 1),
                limits.copy(reconnectDelayNs = limits.reconnectDelayNs - 1),
                limits.copy(busyMinDelayNs = limits.busyMinDelayNs - 1),
                limits.copy(busyMaxDelayNs = limits.busyMaxDelayNs - 1),
                limits.copy(keepAliveNs = limits.keepAliveNs - 1),
                limits.copy(shutdownTimeoutMs = limits.shutdownTimeoutMs - 1),
            ).map { configuration(resultDeliveryLimits = it) }
        variants.forEach {
            assertEquals(baseline.topologySha256, it.topologySha256)
            assertNotEquals(baseline.resultTopologySha256, it.resultTopologySha256)
        }
        assertEquals(variants.size, variants.map { it.resultTopologySha256 }.toSet().size)
    }

    private fun replaceIdentityText(
        original: ByteArray,
        text: String,
    ): ByteArray {
        val encoded = original.toString(Charsets.ISO_8859_1)
        check(encoded.contains(text))
        val replacement = (if (text.first() == '0') "1" else "0") + text.drop(1)
        return encoded.replace(text, replacement).toByteArray(Charsets.ISO_8859_1)
    }

    private fun settings(
        root: Path,
        configuration: SplitTradingConfiguration,
        role: TradingServiceRole = TradingServiceRole.FUNDS,
        mode: String = "ORDER_DELIVERY_DEV",
        transport: DevelopmentFundingSettings = transport(root, configuration, role),
    ) = SplitTradingNodeSettings(transport, role, configuration, fundsIngress, matchingIngress, mode)

    private fun transport(
        root: Path,
        configuration: SplitTradingConfiguration,
        role: TradingServiceRole = TradingServiceRole.FUNDS,
    ) = DevelopmentFundingSettings(
        root,
        basePort =
            if (role ==
                TradingServiceRole.MATCHING
            ) {
                20700
            } else {
                19700
            },
        boundedManifest = configuration.topology.fundsManifest,
    )

    private fun configuration(
        policyVersion: Long = 1,
        ownershipEpoch: Long = 1,
        resourceLimits: TradingResourceLimits = TradingResourceLimits(),
        artifactSha256: String = BoundedExecutionIdentity.artifactSha256,
        schemaSha256: String = BoundedExecutionIdentity.schemaSha256,
        dispatchLimits: FundsDispatchLimits? = null,
        matchingLimits: MatchingIntakeLimits? = null,
        resultLimits: FundsResultLimits = FundsResultLimits(),
        deliveryLimits: FundsOrderDeliveryLimits = FundsOrderDeliveryLimits(),
        resultDeliveryLimits: MatchingResultDeliveryLimits = MatchingResultDeliveryLimits(),
        inputContractVersion: Int = 1,
    ): SplitTradingConfiguration {
        val context =
            TradingMarketContext(
                market,
                TradingFeePolicySnapshot(
                    FeeProductType.SPOT,
                    FeeTier.NORMAL,
                    policyVersion,
                    MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO),
                ),
                0,
            )
        val manifest =
            TradingClusterManifest(
                fundsLineage,
                "split-startup-test",
                artifactSha256,
                schemaSha256,
                7,
                listOf(context),
                resourceLimits,
            )
        val topology = TradingRoleTopology(manifest, listOf(TradingMarketAssignment(market.marketId, matchingLineage, ownershipEpoch)))
        val funds = declaration(TradingServiceRole.FUNDS, fundsLineage, manifest.markets, ownershipEpoch)
        val matching = declaration(TradingServiceRole.MATCHING, matchingLineage, manifest.markets, ownershipEpoch)
        return SplitTradingConfiguration(
            topology,
            funds,
            matching,
            dispatchLimits ?: FundsDispatchLimits.defaults(manifest),
            matchingLimits ?: MatchingIntakeLimits.defaults(topology, matching),
            resultLimits,
            deliveryLimits,
            resultDeliveryLimits,
            inputContractVersion,
        )
    }

    private fun declaration(
        role: TradingServiceRole,
        lineage: ClusterLineage,
        contexts: List<TradingMarketContext>,
        ownershipEpoch: Long = 1,
    ) = TradingRoleDeclaration(role, lineage, 7, contexts, mapOf(market.marketId to ownershipEpoch))
}
