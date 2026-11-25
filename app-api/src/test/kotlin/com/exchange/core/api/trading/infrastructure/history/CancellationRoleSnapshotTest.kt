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
import com.exchange.core.trading.CancelCode
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.CancelReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchConfiguration
import com.exchange.core.trading.FundsDispatchLimits
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.FundsResultLimits
import com.exchange.core.trading.FundsResultStatus
import com.exchange.core.trading.MarketFundsDispatchLimits
import com.exchange.core.trading.MatchingIntakeConfiguration
import com.exchange.core.trading.MatchingIntakeStatus
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CancellationRoleSnapshotTest {
    private val market = MarketDefinition(MarketId("A-QUOTE"), AssetId("A"), AssetId("QUOTE"), 0)
    private val context =
        TradingMarketContext(
            market,
            TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)),
            0,
        )
    private val manifest =
        TradingClusterManifest(
            ClusterLineage(1, 2),
            "cancellation-role-snapshot",
            "0".repeat(64),
            "1".repeat(64),
            7,
            listOf(context),
            TradingResourceLimits(),
        )
    private val matchingLineage = ClusterLineage(3, 4)
    private val topology = TradingRoleTopology(manifest, listOf(TradingMarketAssignment(market.marketId, matchingLineage, 1)))
    private val declaration =
        TradingRoleDeclaration(TradingServiceRole.MATCHING, matchingLineage, 7, listOf(context), mapOf(market.marketId to 1L))
    private val dispatchLimits =
        FundsDispatchLimits(
            mapOf(market.marketId to MarketFundsDispatchLimits(maxDispatchBytes = 65_536, maxActiveBytes = 65_536)),
            maxDispatchBytes = 65_536,
            maxActiveBytes = 65_536,
        )

    @Test
    fun `취소 가능한 역할은 구형 자료와 구분되는 명시적 outer 버전으로 저장한다`() {
        val fundsConfiguration = FundsDispatchConfiguration(topology, dispatchLimits, inputContractVersion = 2)
        val funds = funds(fundsConfiguration).also { it.initialize(manifest) }
        val fundsCodec = FundsRoleSnapshotCodec()
        val fundsBytes = ByteArrayOutputStream().also { fundsCodec.write(funds.fundsRecoveryState(), POSITION, it) }.toByteArray()
        assertEquals(3, ByteBuffer.wrap(fundsBytes).getInt(4))
        val fundsRead = fundsCodec.read(ByteArrayInputStream(fundsBytes), fundsConfiguration, POSITION)
        assertContentEquals(fundsBytes, ByteArrayOutputStream().also { fundsCodec.write(fundsRead, POSITION, it) }.toByteArray())

        val matchingConfiguration = MatchingIntakeConfiguration(topology, declaration, inputContractVersion = 2)
        val matching = ReservedMatchingState(matchingConfiguration).also { it.initialize() }
        val matchingCodec = MatchingRoleSnapshotCodec()
        val matchingBytes = ByteArrayOutputStream().also { matchingCodec.write(matching.recoveryState(), POSITION, it) }.toByteArray()
        assertEquals(2, ByteBuffer.wrap(matchingBytes).getInt(4))
        val matchingRead = matchingCodec.read(ByteArrayInputStream(matchingBytes), matchingConfiguration, POSITION)
        assertContentEquals(matchingBytes, ByteArrayOutputStream().also { matchingCodec.write(matchingRead, POSITION, it) }.toByteArray())
    }

    @Test
    fun `같은 manifest라도 신규 전용 계약과 취소 계약의 구성을 서로 읽지 않는다`() {
        val fundsCodec = FundsRoleSnapshotCodec()
        val legacyFunds = FundsDispatchConfiguration(topology, dispatchLimits)
        val freshFunds = FundsDispatchConfiguration(topology, dispatchLimits, inputContractVersion = 2)
        assertFalse(fundsCodec.configurationBytes(legacyFunds).contentEquals(fundsCodec.configurationBytes(freshFunds)))
        for ((actual, other) in listOf(legacyFunds to freshFunds, freshFunds to legacyFunds)) {
            val owner = funds(actual).also { it.initialize(manifest) }
            val bytes = ByteArrayOutputStream().also { fundsCodec.write(owner.fundsRecoveryState(), POSITION, it) }.toByteArray()
            assertFails { fundsCodec.read(ByteArrayInputStream(bytes), other, POSITION) }
        }
        val matchingCodec = MatchingRoleSnapshotCodec()
        val legacyMatching = MatchingIntakeConfiguration(topology, declaration)
        val freshMatching = MatchingIntakeConfiguration(topology, declaration, inputContractVersion = 2)
        assertFalse(matchingCodec.configurationBytes(legacyMatching).contentEquals(matchingCodec.configurationBytes(freshMatching)))
        for ((actual, other) in listOf(legacyMatching to freshMatching, freshMatching to legacyMatching)) {
            val owner = ReservedMatchingState(actual).also { it.initialize() }
            val bytes = ByteArrayOutputStream().also { matchingCodec.write(owner.recoveryState(), POSITION, it) }.toByteArray()
            assertFails { matchingCodec.read(ByteArrayInputStream(bytes), other, POSITION) }
        }
    }

    @Test
    fun `C18 네 중단 지점의 실제 역할 바이트를 빈 소유자로 복원해 같은 후속열을 수행한다`() {
        for (stage in Stage.entries) {
            val uninterrupted = Flow()
            val inputs = uninterrupted.prepare(stage)
            val fundsBytes = uninterrupted.fundsBytes()
            val matchingBytes = uninterrupted.matchingBytes()
            val restored = Flow(initialize = false)
            restored.funds.restoreFundsRecoveryState(
                FundsRoleSnapshotCodec().read(ByteArrayInputStream(fundsBytes), restored.fundsConfiguration, POSITION),
                settlement(),
            )
            restored.matching.restore(
                MatchingRoleSnapshotCodec().read(ByteArrayInputStream(matchingBytes), restored.matchingConfiguration, POSITION),
            )
            assertContentEquals(fundsBytes, restored.fundsBytes(), stage.name)
            assertContentEquals(matchingBytes, restored.matchingBytes(), stage.name)
            for (flow in listOf(uninterrupted, restored)) flow.finish(inputs, stage)
            assertContentEquals(uninterrupted.fundsBytes(), restored.fundsBytes(), stage.name)
            assertContentEquals(uninterrupted.matchingBytes(), restored.matchingBytes(), stage.name)
        }
    }

    @Test
    fun `C19 새 자료의 손상과 정상 checksum의 취소 원본 모순을 복원 전에 거절한다`() {
        val flow = Flow()
        flow.prepare(Stage.UNDELIVERED)
        val bytes = flow.fundsBytes()
        for (damaged in listOf(bytes.copyOf(bytes.size / 2), bytes + byteArrayOf(1), bytes.copyOf().also { it[it.size - 40]++ })) {
            assertFails { FundsRoleSnapshotCodec().read(ByteArrayInputStream(damaged), flow.fundsConfiguration, POSITION) }
        }
        val cancellation =
            flow.funds
                .fundsRecoveryState()
                .cancellations
                .single()
                .input.cancel!!
        val needle = cancellation.cancelId.toByteArray()
        val framing = ByteBuffer.wrap(bytes)
        val configurationLength = framing.getInt(16)
        val outerPayloadStart = 24 + configurationLength + framing.getInt(20 + configurationLength)
        val offset =
            (outerPayloadStart..bytes.size - needle.size).first { candidate ->
                needle.indices.all {
                    bytes[candidate + it] ==
                        needle[it]
                }
            }
        val inconsistent = bytes.copyOf()
        inconsistent[offset + needle.lastIndex] =
            if (inconsistent[offset + needle.lastIndex] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
        val digestOffset = inconsistent.size - 36
        MessageDigest.getInstance("SHA-256").digest(inconsistent.copyOf(digestOffset)).copyInto(inconsistent, digestOffset)
        assertFails { FundsRoleSnapshotCodec().read(ByteArrayInputStream(inconsistent), flow.fundsConfiguration, POSITION) }

        val matching = flow.matchingBytes()
        val incorrectVersion = matching.copyOf()
        ByteBuffer.wrap(incorrectVersion).putInt(4, 1)
        assertFails { MatchingRoleSnapshotCodec().read(ByteArrayInputStream(incorrectVersion), flow.matchingConfiguration, POSITION) }
    }

    @Test
    fun `C19 완료 응답 ACK 뒤 미발급 취소 번호는 정상 checksum의 실제 바이트에서도 거절한다`() {
        val flow = Flow()
        val inputs = flow.prepare(Stage.REFUNDED_NOT_ACKNOWLEDGED)
        val bytes = flow.fundsBytes()
        FundsRoleSnapshotCodec().read(ByteArrayInputStream(bytes), flow.fundsConfiguration, POSITION)
        val identity = assertNotNull(inputs.last().cancel)
        val idBytes = identity.cancelId.toByteArray()
        val offsets =
            (0..bytes.size - idBytes.size).filter { offset ->
                idBytes.indices.all { bytes[offset + it] == idBytes[it] }
            }
        assertEquals(1, offsets.size)
        val offset = offsets.single()
        val corrupted = bytes.copyOf()
        com.exchange.core.trading.TradingIdentifiers
            .cancel(manifest.lineage, 2)
            .toByteArray()
            .copyInto(corrupted, offset)
        val buffer = ByteBuffer.wrap(corrupted)
        val userBytes = buffer.getInt(offset + idBytes.size)
        // ID 뒤 사용자 문자열·채널·요청 세대·요청 순번 다음이 전역 취소 발급 번호다.
        val counterOffset = offset + idBytes.size + 4 + userBytes + 4 + 8 + 8
        assertEquals(1L, buffer.getLong(counterOffset))
        buffer.putLong(counterOffset, 2)
        val digestOffset = corrupted.size - 36
        MessageDigest.getInstance("SHA-256").digest(corrupted.copyOf(digestOffset)).copyInto(corrupted, digestOffset)
        assertFails { FundsRoleSnapshotCodec().read(ByteArrayInputStream(corrupted), flow.fundsConfiguration, POSITION) }
    }

    @Test
    fun `C22 최대 UTF8 원본과 종료 근거의 실제 저장은 계산한 상한 안이며 초과 구성은 거절한다`() {
        // 개시 잔고의 64 code point와 주문 원본의 UTF-8 256-byte 상한을 함께 만족한다.
        val maximumUser = UserId("😀".repeat(64))
        assertEquals(manifest.limits.maxIdentifierBytes, maximumUser.value.toByteArray().size)
        val flow = Flow(buyerUserId = maximumUser)
        flow.prepare(Stage.TARGET_TERMINATED)
        val encoded = flow.fundsBytes()
        val bound = FundsResultLimits().snapshotByteUpperBound(flow.fundsConfiguration)
        assertTrue(encoded.size.toLong() <= bound)
        val restored = Flow(initialize = false, buyerUserId = maximumUser)
        restored.funds.restoreFundsRecoveryState(
            FundsRoleSnapshotCodec().read(ByteArrayInputStream(encoded), restored.fundsConfiguration, POSITION),
            settlement(),
        )
        val cancellation =
            restored.funds
                .fundsRecoveryState()
                .cancellations
                .single()
        assertEquals(maximumUser, cancellation.input.order.userId)
        assertEquals(maximumUser, assertNotNull(cancellation.terminalReservation).userId)
        assertEquals(
            Balance(maximumUser, market.quoteAssetId, Amount(700), Amount.ZERO),
            restored.funds.currentBalance(maximumUser, market.quoteAssetId),
        )
        assertContentEquals(encoded, restored.fundsBytes())

        // 같은 큰 예산도 새 보호 공간을 더하면 고정 128MiB 안에 들어오지 않는다.
        val oversized =
            FundsDispatchLimits(
                mapOf(
                    market.marketId to
                        MarketFundsDispatchLimits(
                            maxDispatchBytes = 8L * 1_024 * 1_024,
                            maxActiveBytes = 8L * 1_024 * 1_024,
                        ),
                ),
                maxDispatchBytes = 8L * 1_024 * 1_024,
                maxActiveBytes = 8L * 1_024 * 1_024,
            )
        val legacy = FundsDispatchConfiguration(topology, oversized)
        assertTrue(legacy.snapshotByteUpperBound <= FundsDispatchConfiguration.MAX_SNAPSHOT_BYTES)
        assertTrue(flow.fundsConfiguration.snapshotByteUpperBound <= FundsDispatchConfiguration.MAX_SNAPSHOT_BYTES)
        val failure =
            assertFailsWith<IllegalArgumentException> { FundsDispatchConfiguration(topology, oversized, inputContractVersion = 2) }
        assertTrue(failure.message.orEmpty().contains("snapshot"))
    }

    private enum class Stage { UNDELIVERED, APPLIED_NOT_SETTLED, TARGET_TERMINATED, REFUNDED_NOT_ACKNOWLEDGED }

    /** 준비·전달·정산은 가짜 응답 없이 실제 두 역할을 호출한다. */
    private inner class Flow(
        initialize: Boolean = true,
        buyerUserId: UserId = UserId("buyer"),
    ) {
        val buyer = buyerUserId
        val seller = UserId("seller")
        val fundsConfiguration = FundsDispatchConfiguration(topology, dispatchLimits, inputContractVersion = 2)
        val matchingConfiguration = MatchingIntakeConfiguration(topology, declaration, inputContractVersion = 2)
        val funds = funds(fundsConfiguration)
        val matching = ReservedMatchingState(matchingConfiguration)

        init {
            if (initialize) {
                funds.initialize(manifest)
                matching.initialize()
                funds.prepareDevelopmentBalance(OpeningBalance("buyer-opening", buyer, market.quoteAssetId, Amount(1_000)), Instant.EPOCH)
                funds.prepareDevelopmentBalance(OpeningBalance("seller-opening", seller, market.baseAssetId, Amount(3)), Instant.EPOCH)
                funds.rotateEpoch(buyer, 0, 0)
                funds.rotateEpoch(seller, 0, 0)
            }
        }

        fun prepare(stage: Stage): List<FundsOrderDispatch> {
            val buy = admit(buyer, 1, Side.BUY, 100, 3)
            val sell = admit(seller, 1, Side.SELL, 100, if (stage == Stage.TARGET_TERMINATED) 3 else 1)
            val cancellation =
                assertIs<CancelReply.Recorded>(
                    funds.admitCancel(RequestToken(buyer, 0, 1, 2), CancelIntent(market.marketId, buy.order.orderId, 7)),
                )
            assertEquals(CancelCode.ACCEPTED, cancellation.receipt.code)
            val cancel = dispatch(3)
            val inputs = listOf(buy, sell, cancel)
            if (stage != Stage.UNDELIVERED) {
                for (input in inputs.take(2)) {
                    val result = assertNotNull(matching.apply(input).result)
                    assertEquals(FundsResultStatus.APPLIED, funds.applyMatchingResult(result, Instant.EPOCH, settlement()).status)
                }
            }
            if (stage == Stage.APPLIED_NOT_SETTLED || stage == Stage.REFUNDED_NOT_ACKNOWLEDGED) {
                val result = assertNotNull(matching.apply(cancel).result)
                if (stage == Stage.REFUNDED_NOT_ACKNOWLEDGED) {
                    assertEquals(FundsResultStatus.APPLIED, funds.applyMatchingResult(result, Instant.EPOCH, settlement()).status)
                }
            }
            if (stage == Stage.TARGET_TERMINATED) {
                assertNotNull(
                    funds
                        .fundsRecoveryState()
                        .cancellations
                        .single()
                        .terminalReservation,
                )
            }
            // 호출자가 응답을 회수해도 아직 끝나지 않은 취소 작업은 살아 있어야 한다.
            funds.acknowledgeReceipts(buyer, 0, 1, 2)
            return inputs
        }

        fun finish(
            inputs: List<FundsOrderDispatch>,
            stage: Stage,
        ) {
            var acknowledgement: com.exchange.core.trading.MatchingFundsSettledAck? = null
            for (input in inputs) {
                val applied = matching.apply(input)
                assertTrue(applied.status == MatchingIntakeStatus.APPLIED || applied.status == MatchingIntakeStatus.DUPLICATE)
                val settled = funds.applyMatchingResult(assertNotNull(applied.result), Instant.EPOCH, settlement())
                assertTrue(settled.status == FundsResultStatus.APPLIED || settled.status == FundsResultStatus.DUPLICATE)
                acknowledgement = assertNotNull(settled.ack)
            }
            matching.acknowledgeFundsSettled(assertNotNull(acknowledgement))
            val available = if (stage == Stage.TARGET_TERMINATED) 700L else 900L
            assertEquals(
                Balance(buyer, market.quoteAssetId, Amount(available), Amount.ZERO),
                funds.currentBalance(buyer, market.quoteAssetId),
            )
            assertTrue(funds.fundsRecoveryState().cancellations.isEmpty())
            assertEquals(0, funds.fundsRecoveryState().dispatch.dispatchEntries)
            assertTrue(matching.restingOrders(market.marketId).isEmpty())
            val next = admit(buyer, 3, Side.BUY, 50, 1)
            assertEquals(4L, next.dispatchSequence)
            assertEquals(3L, next.marketAdmissionSequence)
            assertEquals(3L, next.admissionSequence)
            val nextResult = assertNotNull(matching.apply(next).result)
            val settled = funds.applyMatchingResult(nextResult, Instant.EPOCH, settlement())
            assertEquals(FundsResultStatus.APPLIED, settled.status)
            matching.acknowledgeFundsSettled(assertNotNull(settled.ack))
            assertEquals(
                Balance(buyer, market.quoteAssetId, Amount(available - 50), Amount(50)),
                funds.currentBalance(buyer, market.quoteAssetId),
            )
        }

        private fun admit(
            user: UserId,
            sequence: Long,
            side: Side,
            price: Long,
            quantity: Long,
        ): FundsOrderDispatch {
            assertIs<AdmissionReply.Recorded>(
                funds.admit(
                    RequestToken(user, 0, 1, sequence),
                    OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity)),
                    Instant.EPOCH,
                ),
            )
            val issued =
                funds
                    .fundsRecoveryState()
                    .dispatch.streams
                    .single()
                    .issued
            return dispatch(issued)
        }

        private fun dispatch(sequence: Long): FundsOrderDispatch =
            funds.pendingDispatch(fundsConfiguration.stream(market.marketId), sequence, 1, 1_048_576).entries.single()

        fun fundsBytes(): ByteArray =
            ByteArrayOutputStream().also { FundsRoleSnapshotCodec().write(funds.fundsRecoveryState(), POSITION, it) }.toByteArray()

        fun matchingBytes(): ByteArray =
            ByteArrayOutputStream().also { MatchingRoleSnapshotCodec().write(matching.recoveryState(), POSITION, it) }.toByteArray()
    }

    private fun settlement(): OrderSettlementCalculator {
        val fees = TradingFeeReserveCalculator()
        val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fees))
        return OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
            reservations,
            fees,
            SettlementLedgerTransactions(),
        )
    }

    private fun funds(configuration: FundsDispatchConfiguration) =
        BoundedFundingState(
            OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))),
            expectedManifest = manifest,
            dispatchConfiguration = configuration,
        )

    companion object {
        private const val POSITION = 4_096L
    }
}
