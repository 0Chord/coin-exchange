package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.api.trading.infrastructure.aeron.BoundedExecutionIdentity
import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingRun
import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingSettings
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
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReceipt
import com.exchange.core.trading.AdmissionReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.ExecuteAdmission
import com.exchange.core.trading.ExecutionReply
import com.exchange.core.trading.LiveOrderPhase
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingRecoveryState
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 변경 전 실제 바이트와 수기 금융 기대값을 함께 고정해 통합 역할의 복구 계약을 지킨다. */
class BoundedTradingRoleGoldenTest {
    @TempDir
    lateinit var directory: Path

    private val codec = BoundedTradingStateCodec()
    private val btc = AssetId("BTC")
    private val usdt = AssetId("USDT")
    private val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 0)
    private val at = Instant.parse("2026-10-30T00:00:00Z")

    @Test
    fun `변경 전 자료의 출처와 지문 및 비어 있지 않은 세 snapshot의 형식을 보존한다`() {
        val metadata = Properties().also { it.load(ByteArrayInputStream(resource("baseline.properties"))) }
        assertEquals(SOURCE_SHA, metadata.getProperty("source.sha"))
        assertEquals(BASELINE_ARTIFACT, metadata.getProperty("artifact.sha256"))
        assertEquals(BASELINE_SCHEMA, metadata.getProperty("schema.sha256"))
        assertEquals(SNAPSHOT_POSITION.toString(), metadata.getProperty("snapshot.position"))
        for ((name, expected) in GOLDEN_SHA256) {
            assertEquals(expected, metadata.getProperty("$name.sha256"), name)
            assertEquals(expected, sha256(resource(name)), name)
        }

        val manifest = manifest()
        assertContentEquals(resource("manifest-v1.bin"), codec.encodeManifest(manifest))
        assertEquals(manifest, codec.decodeManifest(resource("manifest-v1.bin")))
        for (name in SNAPSHOTS) {
            val golden = resource(name)
            val decoded = codec.read(ByteArrayInputStream(golden), manifest, SNAPSHOT_POSITION)
            assertTrue(decoded.balances.isNotEmpty())
            assertTrue(decoded.liveOrders.isNotEmpty())
            assertTrue(decoded.inbox.channels.any { it.entries.isNotEmpty() })
            assertContentEquals(golden, bytes(decoded), name)
            val restored = state().also { it.restoreRecoveryState(decoded) }
            assertContentEquals(golden, bytes(restored), name)
        }
    }

    @Test
    fun `같은 원본 입력과 골든 복원에서 pending FIFO 수수료 이월과 후속 금액이 같다`() {
        val original = beforeSnapshot()
        assertContentEquals(resource("snapshot-v2.bin"), bytes(original))
        val restored =
            state().also {
                it.restoreRecoveryState(codec.read(ByteArrayInputStream(resource("snapshot-v2.bin")), manifest(), SNAPSHOT_POSITION))
            }

        for (owner in listOf(original, restored)) {
            assertBefore(owner)
            val before = bytes(owner)
            val repeated = assertIs<AdmissionReply.Recorded>(owner.admit(token("pending-seller"), intent(Side.SELL, 100, 3), at))
            assertFalse(repeated.newlyRecorded)
            assertEquals(AdmissionCode.ACCEPTED, repeated.receipt.code)
            assertEquals(6L, repeated.receipt.admissionSequence)
            assertEquals(6L, repeated.receipt.marketAdmissionSequence)
            assertContentEquals(before, bytes(owner))

            val pendingResult = execute(owner, repeated.receipt)
            assertEquals(2, pendingResult.receipt.fillCount)
            assertFalse(pendingResult.receipt.resting)
            assertAfterPending(owner)
            assertContentEquals(resource("snapshot-after-pending-v2.bin"), bytes(owner))
            val completed = bytes(owner)
            assertEquals(ExecutionReply.AlreadyPassed, owner.executePending(command(repeated.receipt), at))
            assertContentEquals(completed, bytes(owner))

            val last = admit(owner, "final-taker", Side.BUY, 110, 2)
            assertEquals(7L, last.admissionSequence)
            assertEquals(7L, last.marketAdmissionSequence)
            assertEquals(1, execute(owner, last).receipt.fillCount)
            assertFinal(owner)
            assertContentEquals(resource("snapshot-final-v2.bin"), bytes(owner))
        }
    }

    @Test
    fun `identity 형식은 실제 실행물 지문만 명시 치환하고 같은 실행물의 복구만 허용한다`() {
        assertEquals(BASELINE_SCHEMA, BoundedExecutionIdentity.schemaSha256)
        val actualArtifact = BoundedExecutionIdentity.artifactSha256
        val currentManifest = manifest(actualArtifact)
        val config =
            DevelopmentFundingSettings(directory.resolve("current-run"), boundedManifest = currentManifest, trustedBoundedControl = true)
        // 변경된 금융 class 지문만 새 값으로 대조한다. 과거 identity를 새 실행물의 복구 자료로 승인하지 않는다.
        val expectedIdentity = replaceArtifact(resource("recovery-identity.bin"), actualArtifact)
        lateinit var snapshotIdentity: String
        DevelopmentFundingRun.open(config).use { run ->
            assertContentEquals(expectedIdentity, Files.readAllBytes(config.runRoot.resolve("recovery-identity.bin")))
            assertEquals(sha256(expectedIdentity), run.identitySha256)
            snapshotIdentity = run.snapshotIdentitySha256
            // 실제 Archive를 띄우지 않고 Run의 정체성 검사에 필요한 기록 존재 조건만 준비한다.
            Files.createDirectory(config.archiveDirectory)
            Files.createDirectory(config.clusterDirectory)
            Files.writeString(config.archiveDirectory.resolve("archive.catalog"), "검사용 메타데이터")
            Files.writeString(config.clusterDirectory.resolve("recording.log"), "검사용 메타데이터")
        }
        DevelopmentFundingRun.open(config.copy(recoverExisting = true)).use { recovered ->
            assertEquals(sha256(expectedIdentity), recovered.identitySha256)
            assertEquals(snapshotIdentity, recovered.snapshotIdentitySha256)
        }
        assertFailsWith<IllegalArgumentException> {
            DevelopmentFundingRun.open(config.copy(recoverExisting = true, trustedBoundedControl = false))
        }
        assertContentEquals(expectedIdentity, Files.readAllBytes(config.runRoot.resolve("recovery-identity.bin")))

        val differentArtifact = if (actualArtifact != BASELINE_ARTIFACT) BASELINE_ARTIFACT else "0".repeat(64)
        assertTrue(differentArtifact != actualArtifact)
        val failure =
            assertFailsWith<IllegalArgumentException> {
                DevelopmentFundingSettings(directory.resolve("different-run"), boundedManifest = manifest(differentArtifact))
            }
        assertTrue(failure.message.orEmpty().contains("financial artifact"))
        assertFalse(Files.exists(directory.resolve("different-run")))
        assertContentEquals(expectedIdentity, Files.readAllBytes(config.runRoot.resolve("recovery-identity.bin")))
    }

    private fun beforeSnapshot(): TradingState =
        state().also { owner ->
            owner.initialize(manifest())
            for (user in listOf("first-bid", "second-bid", "initial-taker", "final-taker")) fund(owner, user, usdt, 1_000)
            fund(owner, "low-ask", btc, 1)
            fund(owner, "high-ask", btc, 3)
            fund(owner, "pending-seller", btc, 3)
            for (user in listOf("low-ask", "high-ask", "first-bid", "second-bid", "initial-taker", "pending-seller", "final-taker")) {
                owner.rotateEpoch(UserId(user), 0, 0)
            }
            execute(owner, admit(owner, "low-ask", Side.SELL, 90, 1))
            execute(owner, admit(owner, "high-ask", Side.SELL, 110, 3))
            execute(owner, admit(owner, "first-bid", Side.BUY, 100, 3))
            execute(owner, admit(owner, "second-bid", Side.BUY, 100, 2))
            execute(owner, admit(owner, "initial-taker", Side.BUY, 110, 1))
            admit(owner, "pending-seller", Side.SELL, 100, 3)
        }

    private fun assertBefore(owner: TradingState) {
        val current = owner.recoveryState()
        assertEquals(13, current.balances.size)
        assertEquals(4, current.liveOrders.size)
        assertEquals(7, current.inbox.channels.size)
        assertEquals(6, current.inbox.channels.sumOf { it.entries.size })
        assertEquals(6L, current.lastAdmissionSequence)
        assertEquals(7L, current.lastPreparationSequence)
        assertProgress(current, 6, 5, listOf(6))
        assertEquals(
            listOf("first-bid", "second-bid", "high-ask"),
            current.matching.markets
                .single()
                .orders
                .map { it.userId.value },
        )
        assertEquals(
            listOf(Quantity(2), Quantity(2), Quantity(2)),
            current.matching.markets
                .single()
                .orders
                .map { it.remainingQuantity },
        )
        assertEquals(
            6L,
            current.matching.markets
                .single()
                .lastEngineSequence,
        )
        assertEquals(630_000L, live(current, "first-bid").reservation.feeRemainder.numerator)
        assertEquals(440_000L, live(current, "high-ask").reservation.feeRemainder.numerator)
        assertEquals(LiveOrderPhase.PENDING, live(current, "pending-seller").phase)
        assertBalance(owner, "first-bid", usdt, 707, 203)
        assertBalance(owner, "first-bid", btc, 1, 0)
        assertBalance(owner, "pending-seller", btc, 0, 3)
        assertHoldRelation(current)
    }

    private fun assertAfterPending(owner: TradingState) {
        val current = owner.recoveryState()
        // FIFO 첫 매수는 2개 모두, 둘째 매수는 1개만 체결된다. 같은 가격의 대기 순서를 보존해야 한다.
        assertEquals(
            listOf("second-bid", "high-ask"),
            current.matching.markets
                .single()
                .orders
                .map { it.userId.value },
        )
        assertEquals(
            listOf(Quantity(1), Quantity(2)),
            current.matching.markets
                .single()
                .orders
                .map { it.remainingQuantity },
        )
        assertEquals(2, current.liveOrders.size)
        assertEquals(400_000L, live(current, "second-bid").reservation.feeRemainder.numerator)
        assertEquals(440_000L, live(current, "high-ask").reservation.feeRemainder.numerator)
        assertProgress(current, 6, 6, emptyList())
        assertEquals(
            8L,
            current.matching.markets
                .single()
                .lastEngineSequence,
        )
        assertEquals(6, current.inbox.channels.sumOf { it.entries.size })
        assertBalance(owner, "first-bid", btc, 3, 0)
        assertBalance(owner, "first-bid", usdt, 709, 0)
        assertBalance(owner, "second-bid", btc, 1, 0)
        assertBalance(owner, "second-bid", usdt, 798, 102)
        assertBalance(owner, "pending-seller", usdt, 298, 0)
        assertBalance(owner, "pending-seller", btc, 0, 0)
        // 첫 매수 maker 수수료 floor(.630+.800)=1, 매도 taker 수수료 floor(2.100)=2다.
        assertEquals(BigInteger.valueOf(3), current.feeRevenueByAsset[usdt])
        assertHoldRelation(current)
    }

    private fun assertFinal(owner: TradingState) {
        val current = owner.recoveryState()
        // 복원한 매도 maker의 이월 .440과 새 .880에서 수수료 1, 마지막 taker에서 1이 추가된다.
        assertEquals(BigInteger.valueOf(5), current.feeRevenueByAsset[usdt])
        assertBalance(owner, "high-ask", usdt, 329, 0)
        assertBalance(owner, "high-ask", btc, 0, 0)
        assertBalance(owner, "final-taker", usdt, 779, 0)
        assertBalance(owner, "final-taker", btc, 2, 0)
        assertBalance(owner, "second-bid", usdt, 798, 102)
        assertEquals(
            listOf("second-bid"),
            current.matching.markets
                .single()
                .orders
                .map { it.userId.value },
        )
        assertEquals(
            Quantity(1),
            current.matching.markets
                .single()
                .orders
                .single()
                .remainingQuantity,
        )
        assertEquals(
            9L,
            current.matching.markets
                .single()
                .lastEngineSequence,
        )
        assertEquals(1, current.liveOrders.size)
        assertEquals(
            400_000L,
            current.liveOrders
                .single()
                .reservation.feeRemainder.numerator,
        )
        assertEquals(7L, current.lastAdmissionSequence)
        assertProgress(current, 7, 7, emptyList())
        assertEquals(7, current.inbox.channels.sumOf { it.entries.size })
        assertEquals(7L, current.balances.filter { it.assetId == btc }.sumOf { it.available.value + it.hold.value })
        assertEquals(
            4_000L,
            current.balances.filter { it.assetId == usdt }.sumOf { it.available.value + it.hold.value } +
                current.feeRevenueByAsset.getValue(usdt).longValueExact(),
        )
        assertHoldRelation(current)
    }

    private fun assertProgress(
        current: TradingRecoveryState,
        assigned: Long,
        executed: Long,
        pending: List<Long>,
    ) {
        val progress = current.markets.single()
        assertEquals(assigned, progress.lastAssigned)
        assertEquals(executed, progress.executedThrough)
        assertEquals(pending, progress.pendingAdmissionSequences)
    }

    private fun assertHoldRelation(current: TradingRecoveryState) {
        for (balance in current.balances) {
            val held = current.liveOrders.filter { it.order.userId == balance.userId && it.reservation.assetId == balance.assetId }
            assertEquals(balance.hold.value, held.sumOf { it.reservation.remainingAmount.value })
        }
    }

    private fun live(
        current: TradingRecoveryState,
        user: String,
    ) = current.liveOrders.single { it.order.userId == UserId(user) }

    private fun manifest(artifact: String = BASELINE_ARTIFACT) =
        TradingClusterManifest(
            ClusterLineage(71, 72),
            "snapshot-continuation-v1",
            artifact,
            BASELINE_SCHEMA,
            7,
            listOf(
                TradingMarketContext(
                    market,
                    TradingFeePolicySnapshot(
                        FeeProductType.SPOT,
                        FeeTier.NORMAL,
                        1,
                        MakerTakerFeeRates(FeeRate(4_000), FeeRate(7_000)),
                    ),
                    0,
                ),
            ),
            TradingResourceLimits(),
        )

    private fun state(): TradingState {
        val fees = TradingFeeReserveCalculator()
        val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fees))
        return TradingState(
            OrderAdmissionCalculator(reservations),
            OrderSettlementCalculator(
                OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
                reservations,
                fees,
                SettlementLedgerTransactions(),
            ),
            boundedMode = true,
        )
    }

    private fun fund(
        owner: TradingState,
        user: String,
        asset: AssetId,
        amount: Long,
    ) {
        owner.prepareDevelopmentBalance(OpeningBalance("$user-${asset.value}", UserId(user), asset, Amount(amount)), "ignored", at)
    }

    private fun token(user: String) = RequestToken(UserId(user), 0, 1, 1)

    private fun intent(
        side: Side,
        price: Long,
        quantity: Long,
    ) = OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity))

    private fun admit(
        owner: TradingState,
        user: String,
        side: Side,
        price: Long,
        quantity: Long,
    ): AdmissionReceipt {
        val reply = assertIs<AdmissionReply.Recorded>(owner.admit(token(user), intent(side, price, quantity), at))
        assertTrue(reply.newlyRecorded)
        assertEquals(AdmissionCode.ACCEPTED, reply.receipt.code)
        return reply.receipt
    }

    private fun command(receipt: AdmissionReceipt) =
        ExecuteAdmission(
            ClusterLineage(71, 72),
            market.marketId,
            requireNotNull(receipt.marketAdmissionSequence),
            requireNotNull(receipt.admissionSequence),
        )

    private fun execute(
        owner: TradingState,
        receipt: AdmissionReceipt,
    ) = assertIs<ExecutionReply.Applied>(owner.executePending(command(receipt), at))

    private fun assertBalance(
        owner: TradingState,
        user: String,
        asset: AssetId,
        available: Long,
        hold: Long,
    ) {
        assertEquals(Balance(UserId(user), asset, Amount(available), Amount(hold)), owner.currentBalance(UserId(user), asset))
    }

    private fun bytes(owner: TradingState) = bytes(owner.recoveryState())

    private fun bytes(current: TradingRecoveryState) =
        ByteArrayOutputStream()
            .also {
                codec.write(current, SNAPSHOT_POSITION, it)
            }.toByteArray()

    private fun replaceArtifact(
        identity: ByteArray,
        actualArtifact: String,
    ): ByteArray {
        val old = BASELINE_ARTIFACT.toByteArray(Charsets.US_ASCII)
        val offsets = (0..identity.size - old.size).filter { offset -> old.indices.all { identity[offset + it] == old[it] } }
        assertEquals(1, offsets.size, "원본 실행물 지문 위치가 유일해야 한다")
        return identity.copyOf().also { actualArtifact.toByteArray(Charsets.US_ASCII).copyInto(it, offsets.single()) }
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/bounded-role164/$name")) { "골든 자료 없음: $name" }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val SOURCE_SHA = "1b2d3bb6e7aad1e6c12dff8bfe7b861b027e373c"
        private const val BASELINE_ARTIFACT = "d5b1fdcda2f000732d479940a3ca96bc4d0e300922adc732af8807a3b0196f3c"
        private const val BASELINE_SCHEMA = "78011b23df2af00bd67d9fd0bcf48f73fed4074719a4ce497bc07435c9266513"
        private const val SNAPSHOT_POSITION = 4_096L
        private val SNAPSHOTS = listOf("snapshot-v2.bin", "snapshot-after-pending-v2.bin", "snapshot-final-v2.bin")
        private val GOLDEN_SHA256 =
            mapOf(
                "manifest-v1.bin" to "8a96c64ef27b3f38f6b9bcf3d25a887db92be12b7b8143ad87ce01dee2647aee",
                "snapshot-v2.bin" to "3a5e76f33df9bee888f991eea1cd660f6ce934bad59c130342a530f00bab93c8",
                "snapshot-after-pending-v2.bin" to "ea3b50dd0c837f6d122efc56e1afbd355672ce37a67c312f34c898a437b7581f",
                "snapshot-final-v2.bin" to "295f6ebe3fd26d18f8e0412a3bfe6317f5e96ee229467be349d1a24e86cef184",
                "recovery-identity.bin" to "43df2d7c0f1fd6a3a75f1969e4b777978981bebbfc3930826a7026bf3a12704e",
            )
    }
}
