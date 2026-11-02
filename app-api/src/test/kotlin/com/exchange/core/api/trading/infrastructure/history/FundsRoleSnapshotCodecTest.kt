package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionReply
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchConfiguration
import com.exchange.core.trading.FundsDispatchLimits
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.FundsRoleRecoveryState
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingRoleAssembly
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FundsRoleSnapshotCodecTest {
    private val codec = FundsRoleSnapshotCodec()
    private val btc = AssetId("BTC")
    private val usdt = AssetId("USDT")
    private val user = UserId("buyer")
    private val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 0)
    private val policy =
        TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
    private val manifest =
        TradingClusterManifest(
            ClusterLineage(101, 102),
            "funds-snapshot-v1",
            "0".repeat(64),
            "1".repeat(64),
            7,
            listOf(TradingMarketContext(market, policy, 0)),
            TradingResourceLimits(),
        )
    private val topology = topology()
    private val configuration = FundsDispatchConfiguration(topology)
    private val intent = OrderIntent(market.marketId, 7, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(3))
    private val token = RequestToken(user, 0, 1, 1)

    @Test
    fun `예약과 미확인 원본을 실제 바이트에서 빈 자금 소유자로 복원한다`() {
        val original = prepared()
        val first = assertIs<AdmissionReply.Recorded>(original.admit(token, intent, Instant.EPOCH))
        val saved = original.fundsRecoveryState()
        val bytes = bytes(saved)
        val decoded = read(bytes)
        val restored = owner().also { it.restoreFundsRecoveryState(decoded, settlementCalculator()) }

        assertTrue(bytes.size.toLong() <= configuration.snapshotByteUpperBound)
        assertContentEquals(bytes, bytes(decoded))
        assertContentEquals(bytes, bytes(restored.fundsRecoveryState()))
        assertEquals(saved.dispatch, decoded.dispatch)
        assertEquals(mapOf(usdt to 1_000L), decoded.assetTotals)
        assertEquals(Balance(user, usdt, Amount(700), Amount(300)), restored.currentBalance(user, usdt))
        val repeated = assertIs<AdmissionReply.Recorded>(restored.admit(token, intent, Instant.EPOCH.plusSeconds(1)))
        assertEquals(first.receipt, repeated.receipt)
        assertFalse(repeated.newlyRecorded)
        assertContentEquals(bytes, bytes(restored.fundsRecoveryState()))
        assertFails { BoundedTradingStateCodec().read(ByteArrayInputStream(bytes), manifest, POSITION) }
    }

    @Test
    fun `stream 순서는 마켓 이름 대신 manifest ordinal을 보존한다`() {
        val other = TradingMarketContext(MarketDefinition(MarketId("AAA-USDT"), AssetId("AAA"), usdt, 0), policy, 1)
        val definition =
            TradingClusterManifest(
                manifest.lineage,
                manifest.algorithmId,
                manifest.executionArtifactSha256,
                manifest.schemaSha256,
                manifest.configurationVersion,
                listOf(manifest.markets.single(), other),
                manifest.limits,
            )
        val assignments = definition.markets.map { TradingMarketAssignment(it.market.marketId, ClusterLineage(201, 202), 1) }
        val layout = TradingRoleTopology(definition, assignments)
        val expected = FundsDispatchConfiguration(layout)
        val funds =
            TradingRoleAssembly.funds(
                layout,
                TradingRoleDeclaration(
                    TradingServiceRole.FUNDS,
                    definition.lineage,
                    definition.configurationVersion,
                    definition.markets,
                    assignments.associate { it.marketId to it.ownershipEpoch },
                ),
                OrderAdmissionCalculator(reservations()),
            )
        funds.initialize(definition)
        val saved = funds.fundsRecoveryState()
        val encoded = bytes(saved)
        val decoded = codec.read(ByteArrayInputStream(encoded), expected, POSITION)

        assertEquals(listOf(market.marketId, other.market.marketId), decoded.dispatch.streams.map { it.stream.marketId })
        assertEquals(saved.dispatch, decoded.dispatch)
        assertContentEquals(encoded, bytes(decoded))
    }

    @Test
    fun `확인된 전달은 회수한 채 예약을 복원하고 같은 후속 명령을 적용한다`() {
        val original = prepared()
        original.admit(token, intent, Instant.EPOCH)
        val restored = owner().also { it.restoreFundsRecoveryState(read(bytes(original.fundsRecoveryState())), settlementCalculator()) }

        for (funds in listOf(original, restored)) {
            funds.acknowledgeMatchingApplied(FundsMatchingAppliedAck(configuration.stream(market.marketId), 1, manifest.markets.single()))
            funds.admit(token.copy(sequence = 2), intent.copy(quantity = Quantity(2)), Instant.EPOCH)
            funds.acknowledgeReceipts(user, 0, 1, 2)
        }
        assertContentEquals(bytes(original.fundsRecoveryState()), bytes(restored.fundsRecoveryState()))
        val suffix = original.fundsRecoveryState()
        assertEquals(
            1L,
            suffix.dispatch.streams
                .single()
                .matchingAppliedThrough,
        )
        assertEquals(
            listOf(2L),
            suffix.dispatch.streams
                .single()
                .outstanding
                .map { it.dispatchSequence },
        )
        assertEquals(2, suffix.financial.liveOrders.size)
        assertEquals(
            0L,
            suffix.financial.markets
                .single()
                .executedThrough,
        )
        assertEquals(Balance(user, usdt, Amount(500), Amount(500)), restored.currentBalance(user, usdt))

        original.acknowledgeMatchingApplied(FundsMatchingAppliedAck(configuration.stream(market.marketId), 2, manifest.markets.single()))
        val acknowledged = owner().also { it.restoreFundsRecoveryState(read(bytes(original.fundsRecoveryState())), settlementCalculator()) }
        val state = acknowledged.fundsRecoveryState()
        assertTrue(
            state.dispatch.streams
                .single()
                .outstanding
                .isEmpty(),
        )
        assertEquals(0, state.dispatch.dispatchEntries)
        assertEquals(0L, state.dispatch.dispatchBytes)
        assertEquals(2, state.financial.liveOrders.size)
        assertEquals(Balance(user, usdt, Amount(500), Amount(500)), acknowledged.currentBalance(user, usdt))
    }

    @Test
    fun `잘못된 형식 위치 체크섬 종료표시와 잘린 바이트를 복원 전에 거절한다`() {
        val bytes = bytes(prepared().fundsRecoveryState())
        assertFails { codec.read(ByteArrayInputStream(bytes), configuration, POSITION + 1) }
        for (offset in listOf(0, 7, bytes.size - 5, bytes.lastIndex)) {
            val damaged = bytes.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFails("offset $offset") { read(damaged) }
        }
        assertFails { read(bytes.copyOf(bytes.size - 1)) }
        assertFails { read(bytes.copyOf(bytes.size / 2)) }
        assertFails { read(bytes + byteArrayOf(1)) }
    }

    @Test
    fun `복구 설정에 목적 세대 정책과 전달 한도 전체를 결속한다`() {
        val bytes = bytes(prepared().fundsRecoveryState())
        val contexts = manifest.markets
        val changedPolicy = contexts.single().copy(feePolicySnapshot = policy.copy(scheduleVersion = 2))
        val foreignManifest =
            TradingClusterManifest(
                manifest.lineage,
                manifest.algorithmId,
                manifest.executionArtifactSha256,
                manifest.schemaSha256,
                manifest.configurationVersion,
                listOf(changedPolicy),
                manifest.limits,
            )
        val defaults = configuration.limits
        val alternatives =
            listOf(
                FundsDispatchConfiguration(topology(destination = ClusterLineage(301, 302))),
                FundsDispatchConfiguration(topology(epoch = 2)),
                FundsDispatchConfiguration(topology(definition = foreignManifest)),
                FundsDispatchConfiguration(
                    topology,
                    FundsDispatchLimits(
                        defaults.marketLimits,
                        defaults.maxDispatchEntries - 1,
                        defaults.maxDispatchBytes,
                        defaults.maxActiveOrders,
                        defaults.maxActiveBytes,
                    ),
                ),
                FundsDispatchConfiguration(
                    topology,
                    FundsDispatchLimits(
                        defaults.marketLimits.mapValues { (_, limit) -> limit.copy(maxActiveBytes = limit.maxActiveBytes - 1) },
                    ),
                ),
            )
        for (expected in alternatives) assertFails { codec.read(ByteArrayInputStream(bytes), expected, POSITION) }
    }

    @Test
    fun `체크섬이 맞아도 과대한 길이와 stream 행 수를 거절한다`() {
        val bytes = bytes(prepared().fundsRecoveryState())
        val buffer = ByteBuffer.wrap(bytes)
        val configurationLength = buffer.getInt(16)
        val financialLengthOffset = 20 + configurationLength
        val financialLength = buffer.getInt(financialLengthOffset)
        val totalsOffset = financialLengthOffset + 4 + financialLength
        val count = buffer.getInt(totalsOffset)
        var cursor = totalsOffset + 4
        repeat(count) { cursor += 4 + buffer.getInt(cursor) + 8 }
        val streamCountOffset = cursor + 24

        for (offset in listOf(16, financialLengthOffset, streamCountOffset)) {
            val damaged = bytes.copyOf()
            ByteBuffer.wrap(damaged).putInt(offset, Int.MAX_VALUE)
            resign(damaged)
            assertFails("length/count offset $offset") { read(damaged) }
        }
        // 바이트 해석 실패는 소유자에 접근하지 않는다. 빈 소유자는 여전히 초기화할 수 있다.
        val empty = owner()
        assertEquals(TradingLifecycle.UNINITIALIZED, empty.lifecycle)
        empty.initialize(manifest)
        assertEquals(TradingLifecycle.RUNNING, empty.lifecycle)
    }

    private fun topology(
        definition: TradingClusterManifest = manifest,
        destination: ClusterLineage = ClusterLineage(201, 202),
        epoch: Long = 1,
    ) = TradingRoleTopology(definition, listOf(TradingMarketAssignment(market.marketId, destination, epoch)))

    private fun owner(): BoundedFundingState =
        TradingRoleAssembly.funds(
            topology,
            TradingRoleDeclaration(
                TradingServiceRole.FUNDS,
                manifest.lineage,
                manifest.configurationVersion,
                manifest.markets,
                mapOf(market.marketId to 1),
            ),
            OrderAdmissionCalculator(reservations()),
        )

    private fun prepared(): BoundedFundingState =
        owner().also {
            it.initialize(manifest)
            it.prepareDevelopmentBalance(OpeningBalance("P1", user, usdt, Amount(1_000)), Instant.EPOCH)
            it.rotateEpoch(user, 0, 0)
        }

    private fun reservations() = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))

    private fun settlementCalculator() =
        OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), TradingFeeReserveCalculator()),
            reservations(),
            TradingFeeReserveCalculator(),
            SettlementLedgerTransactions(),
        )

    private fun bytes(saved: FundsRoleRecoveryState): ByteArray =
        ByteArrayOutputStream().also { codec.write(saved, POSITION, it) }.toByteArray()

    private fun read(bytes: ByteArray): FundsRoleRecoveryState = codec.read(ByteArrayInputStream(bytes), configuration, POSITION)

    private fun resign(bytes: ByteArray) {
        val digestOffset = bytes.size - 36
        MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(digestOffset)).copyInto(bytes, digestOffset)
    }

    companion object {
        private const val POSITION = 4_096L
    }
}
