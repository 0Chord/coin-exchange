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
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.FundsResultLimits
import com.exchange.core.trading.FundsResultStatus
import com.exchange.core.trading.FundsRoleRecoveryState
import com.exchange.core.trading.MarketMatchingIntakeLimits
import com.exchange.core.trading.MatchingIntakeConfiguration
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingResultEnvelope
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
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FundsResultSnapshotCodecTest {
    private val codec = FundsRoleSnapshotCodec()

    @Test
    fun `여러 체결과 남은 예약을 v2에서 복원하고 최초 정산 시각을 유지한다`() {
        val f = Fixture()
        val results = f.multipleFills()
        val bytes = bytes(f.owner.fundsRecoveryState())
        assertEquals(2, ByteBuffer.wrap(bytes).getInt(4))
        val decoded = read(bytes, f)
        val restored = f.empty().also { it.restoreFundsRecoveryState(decoded, f.settlement) }
        assertContentEquals(bytes, bytes(restored.fundsRecoveryState()))
        assertEquals(Balance(f.buyer, f.quote, Amount(715), Amount(100)), restored.currentBalance(f.buyer, f.quote))
        assertEquals(Balance(f.buyer, f.base, Amount(2), Amount.ZERO), restored.currentBalance(f.buyer, f.base))
        assertEquals(Balance(f.seller, f.quote, Amount(185), Amount.ZERO), restored.currentBalance(f.seller, f.quote))
        val saved = restored.fundsRecoveryState()
        assertEquals(1, saved.financial.liveOrders.size)
        assertEquals(0, saved.dispatch.dispatchEntries)
        val progress = assertNotNull(saved.results).streams.single()
        assertEquals(3L, progress.resultThrough)
        assertEquals(5L, progress.settledEngineSequence)
        val firstTimes = assertNotNull(saved.results).receipts.map { it.appliedAt }
        assertEquals(FundsResultStatus.DUPLICATE, restored.applyMatchingResult(results.last(), f.at.plusSeconds(99), f.settlement).status)
        assertEquals(firstTimes, assertNotNull(restored.fundsRecoveryState().results).receipts.map { it.appliedAt })
        assertContentEquals(bytes, bytes(restored.fundsRecoveryState()))
    }

    @Test
    fun `적용 ACK와 정산 결과의 도착 순서가 달라도 같은 금융 상태를 복원한다`() {
        val snapshots = mutableListOf<ByteArray>()
        for (ackFirst in listOf(false, true)) {
            val f = Fixture()
            val sell = f.result(Side.SELL, 100, 1, f.seller)
            f.apply(sell)
            val buy = f.result(Side.BUY, 100, 1, f.buyer)
            val ack = FundsMatchingAppliedAck(f.configuration.stream(f.market.marketId), 2, f.context)
            if (ackFirst) f.owner.acknowledgeMatchingApplied(ack)
            f.apply(buy)
            f.owner.acknowledgeMatchingApplied(ack)
            val saved = f.owner.fundsRecoveryState()
            val restored = f.empty().also { it.restoreFundsRecoveryState(read(bytes(saved), f), f.settlement) }
            assertEquals(
                2L,
                saved.dispatch.streams
                    .single()
                    .matchingAppliedThrough,
            )
            assertEquals(0, saved.dispatch.dispatchEntries)
            assertEquals(0, saved.financial.liveOrders.size)
            assertEquals(Balance(f.buyer, f.quote, Amount(900), Amount.ZERO), restored.currentBalance(f.buyer, f.quote))
            snapshots.add(bytes(restored.fundsRecoveryState()))
        }
        assertContentEquals(snapshots[0], snapshots[1])
    }

    @Test
    fun `작은 비교 창의 회수 경계와 사건 경계를 복원하고 옛 결과를 재정산하지 않는다`() {
        val f = Fixture(FundsResultLimits(maxReceiptEntries = 1, maxMarketReceiptEntries = 1))
        val results = f.multipleFills()
        val saved = f.owner.fundsRecoveryState()
        val retained = assertNotNull(saved.results)
        assertEquals(1, retained.receipts.size)
        assertEquals(2L, retained.streams.single().retiredThrough)
        assertEquals(2L, retained.streams.single().retiredEngineSequence)
        val encoded = bytes(saved)
        val restored = f.empty().also { it.restoreFundsRecoveryState(read(encoded, f), f.settlement) }
        val reply = restored.applyMatchingResult(results.first(), f.at.plusSeconds(99), f.settlement)
        assertEquals(FundsResultStatus.PRUNED, reply.status)
        assertEquals(3L, reply.ack?.through)
        assertContentEquals(encoded, bytes(restored.fundsRecoveryState()))
    }

    @Test
    fun `최종 거절로 예약을 반환한 상태를 복원해도 같은 거절은 추가 반환하지 않는다`() {
        val f = Fixture(activeLimit = 1)
        f.apply(f.result(Side.SELL, 200, 1, f.seller))
        val rejected = f.result(Side.BUY, 100, 3, f.buyer)
        assertEquals(MatchingOutcome.NOT_EXECUTED, rejected.outcome)
        f.apply(rejected)
        val encoded = bytes(f.owner.fundsRecoveryState())
        val restored = f.empty().also { it.restoreFundsRecoveryState(read(encoded, f), f.settlement) }
        assertEquals(Balance(f.buyer, f.quote, Amount(1_000), Amount.ZERO), restored.currentBalance(f.buyer, f.quote))
        assertEquals(FundsResultStatus.DUPLICATE, restored.applyMatchingResult(rejected, f.at, f.settlement).status)
        assertEquals(
            1,
            restored
                .fundsRecoveryState()
                .financial.liveOrders.size,
        )
        assertContentEquals(encoded, bytes(restored.fundsRecoveryState()))
    }

    @Test
    fun `결과 상한도 snapshot identity에 결속하고 다른 기대값으로 읽지 않는다`() {
        val f = Fixture()
        f.multipleFills()
        val encoded = bytes(f.owner.fundsRecoveryState())
        assertFails {
            codec.read(ByteArrayInputStream(encoded), f.configuration, POSITION, f.limits.copy(maxReceiptEntries = 8))
        }
    }

    @Test
    fun `다시 서명한 잘못된 정산 경계와 회수 사건 경계 및 byte 합을 거절한다`() {
        val f = Fixture(FundsResultLimits(maxReceiptEntries = 1, maxMarketReceiptEntries = 1))
        f.multipleFills()
        val encoded = bytes(f.owner.fundsRecoveryState())
        val offsets = resultOffsets(encoded)
        for ((offset, value) in listOf(offsets.resultThrough to 4L, offsets.retiredEngine to 0L, offsets.receiptBytes to 1L)) {
            val damaged = encoded.copyOf()
            ByteBuffer.wrap(damaged).putLong(offset, value)
            resign(damaged)
            assertFails("semantic relation $offset") { read(damaged, f) }
        }
    }

    @Test
    fun `유효하지 않은 receipt 건수 시각과 잘린 결과 frame을 거절한다`() {
        val f = Fixture()
        f.multipleFills()
        val encoded = bytes(f.owner.fundsRecoveryState())
        val offsets = resultOffsets(encoded)
        for ((offset, value) in listOf(
            offsets.receiptCount to Int.MAX_VALUE,
            offsets.firstReceiptNanos to 1_000_000_000,
            offsets.firstReceiptNanos + 4 to Int.MAX_VALUE,
            offsets.firstReceiptNanos + 4 to 1,
        )) {
            val damaged = encoded.copyOf()
            ByteBuffer.wrap(damaged).putInt(offset, value)
            resign(damaged)
            assertFails("length/time $offset") { read(damaged, f) }
        }
    }

    @Test
    fun `세 원문에 두 원문 예산만 선언하면 셋째 frame 본문을 읽기 전에 멈춘다`() {
        val f = Fixture()
        f.multipleFills()
        val state = f.owner.fundsRecoveryState()
        val encoded = bytes(state)
        val original = CountingInputStream(ByteArrayInputStream(encoded))
        assertEquals(3, assertNotNull(codec.read(original, f.configuration, POSITION, f.limits).results).receipts.size)
        assertEquals(encoded.size, original.consumed)

        val offsets = resultOffsets(encoded)
        val declaredBytes = assertNotNull(state.results).receipts.take(2).sumOf { it.result.canonicalBytes }
        val damaged = encoded.copyOf()
        ByteBuffer.wrap(damaged).putLong(offsets.receiptBytes, declaredBytes)
        ByteBuffer.wrap(damaged).putLong(offsets.marketReceiptBytes, declaredBytes)
        resign(damaged)
        val source = CountingInputStream(ByteArrayInputStream(damaged))
        assertFails { codec.read(source, f.configuration, POSITION, f.limits) }
        assertEquals(offsets.frameBodies[2], source.consumed)
        assertTrue(source.consumed < damaged.size - 36)
    }

    private fun bytes(state: FundsRoleRecoveryState): ByteArray =
        ByteArrayOutputStream().also { codec.write(state, POSITION, it) }.toByteArray()

    private fun read(
        bytes: ByteArray,
        fixture: Fixture,
    ): FundsRoleRecoveryState = codec.read(ByteArrayInputStream(bytes), fixture.configuration, POSITION, fixture.limits)

    private fun resign(bytes: ByteArray) {
        val offset = bytes.size - 36
        MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(offset)).copyInto(bytes, offset)
    }

    private data class ResultOffsets(
        val resultThrough: Int,
        val retiredEngine: Int,
        val receiptBytes: Int,
        val receiptCount: Int,
        val firstReceiptNanos: Int,
        val marketReceiptBytes: Int,
        val frameBodies: List<Int>,
    )

    private class CountingInputStream(
        source: InputStream,
    ) : FilterInputStream(source) {
        var consumed: Int = 0
            private set

        override fun read(): Int = `in`.read().also { if (it >= 0) consumed++ }

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int = `in`.read(bytes, offset, length).also { if (it > 0) consumed += it }
    }

    /** 직렬화된 필드를 순서대로 건너뛰어 고정 절대 오프셋에 기대지 않는다. */
    private fun resultOffsets(bytes: ByteArray): ResultOffsets {
        val input = ByteBuffer.wrap(bytes)

        fun skipBlob() {
            val length = input.int
            input.position(input.position() + length)
        }
        input.position(16)
        skipBlob() // 고정 설정
        skipBlob() // 금융 projection
        repeat(input.int) {
            skipBlob()
            input.long
        }
        input.position(input.position() + 24) // 전달 전체 집계
        repeat(input.int) {
            skipBlob()
            input.position(input.position() + 36)
            repeat(input.int) {
                input.position(input.position() + 24)
                repeat(6) { skipBlob() }
                input.position(input.position() + 16)
                skipBlob()
            }
        }
        input.position(input.position() + 36) // 결과 보존 상한
        assertEquals(1, input.int)
        skipBlob()
        val through = input.position()
        input.position(input.position() + 44)
        val receiptBytes = input.position()
        input.long
        val count = input.position()
        val receiptCount = input.int
        assertTrue(receiptCount > 0)
        val frameBodies = mutableListOf<Int>()
        var firstNanos = -1
        repeat(receiptCount) { index ->
            skipBlob()
            input.long
            if (index == 0) firstNanos = input.position()
            input.int
            val length = input.int
            frameBodies.add(input.position())
            input.position(input.position() + length)
        }
        return ResultOffsets(through, through + 24, receiptBytes, count, firstNanos, through + 36, frameBodies)
    }

    private class Fixture(
        val limits: FundsResultLimits = FundsResultLimits(),
        activeLimit: Int = 32,
    ) {
        val quote = AssetId("QUOTE")
        val base = AssetId("BASE")
        val buyer = UserId("buyer")
        val seller = UserId("seller")
        val at: Instant = Instant.parse("2026-11-02T00:00:00Z")
        val market = MarketDefinition(MarketId("BASE-QUOTE"), base, quote, 0)
        private val fees = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
        val context = TradingMarketContext(market, fees, 0)
        private val manifest =
            TradingClusterManifest(
                ClusterLineage(11, 12),
                "funds-result-codec",
                "0".repeat(64),
                "1".repeat(64),
                7,
                listOf(context),
                TradingResourceLimits(),
            )
        private val destination = ClusterLineage(21, 22)
        private val topology = TradingRoleTopology(manifest, listOf(TradingMarketAssignment(market.marketId, destination, 1)))
        val configuration = FundsDispatchConfiguration(topology)
        private val reserve = TradingFeeReserveCalculator()
        private val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(reserve))
        val settlement =
            OrderSettlementCalculator(
                OrderFillSettlementCalculator(TradingFeeCalculator(), reserve),
                reservations,
                reserve,
                SettlementLedgerTransactions(),
            )
        val owner =
            empty().also {
                it.initialize(manifest)
                it.prepareDevelopmentBalance(OpeningBalance("buyer-quote", buyer, quote, Amount(1_000)), at)
                it.prepareDevelopmentBalance(OpeningBalance("seller-base", seller, base, Amount(2)), at)
                it.rotateEpoch(buyer, 0, 0)
                it.rotateEpoch(seller, 0, 0)
            }
        private val matching =
            ReservedMatchingState(
                MatchingIntakeConfiguration(
                    topology,
                    TradingRoleDeclaration(TradingServiceRole.MATCHING, destination, 7, listOf(context), mapOf(market.marketId to 1L)),
                    MatchingIntakeLimits(
                        mapOf(market.marketId to MarketMatchingIntakeLimits(maxActiveOrders = activeLimit)),
                        maxActiveOrders = activeLimit,
                    ),
                ),
            ).also { it.initialize() }
        private val userSequences = mutableMapOf<UserId, Long>()

        fun empty() = BoundedFundingState(OrderAdmissionCalculator(reservations), manifest, configuration, limits)

        fun result(
            side: Side,
            price: Long,
            quantity: Long,
            user: UserId,
        ): MatchingResultEnvelope {
            val sequence = (userSequences[user] ?: 0) + 1
            userSequences[user] = sequence
            val intent = OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity))
            val reply = assertIs<AdmissionReply.Recorded>(owner.admit(RequestToken(user, 0, 1, sequence), intent, at))
            val dispatch =
                owner
                    .pendingDispatch(
                        configuration.stream(market.marketId),
                        checkNotNull(reply.receipt.marketAdmissionSequence),
                        1,
                        1_048_576,
                    ).entries
                    .single()
            return assertNotNull(matching.apply(dispatch).result)
        }

        fun apply(result: MatchingResultEnvelope) {
            assertEquals(FundsResultStatus.APPLIED, owner.applyMatchingResult(result, at, settlement).status)
        }

        fun multipleFills(): List<MatchingResultEnvelope> {
            val values = mutableListOf<MatchingResultEnvelope>()
            values.add(result(Side.SELL, 90, 1, seller).also(::apply))
            values.add(result(Side.SELL, 95, 1, seller).also(::apply))
            values.add(result(Side.BUY, 100, 3, buyer).also(::apply))
            return values
        }
    }

    companion object {
        private const val POSITION = 4_096L
    }
}
