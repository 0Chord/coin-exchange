package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsCancelDispatch
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MarketMatchingIntakeLimits
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingIntakeConfiguration
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.MatchingIntakeStatus
import com.exchange.core.trading.MatchingNotExecutedReason
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingRoleRecoveryState
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingLifecycle
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MatchingRoleSnapshotCodecTest {
    private val codec = MatchingRoleSnapshotCodec()
    private val funds = ClusterLineage(1, 2)
    private val matching = ClusterLineage(3, 4)
    private val quote = AssetId("QUOTE")
    private val a = MarketDefinition(MarketId("A-QUOTE"), AssetId("A"), quote, 0)
    private val b = MarketDefinition(MarketId("B-QUOTE"), AssetId("B"), quote, 0)
    private val fees = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
    private val contexts = listOf(TradingMarketContext(a, fees, 0), TradingMarketContext(b, fees, 1))
    private val manifest = definition(contexts)
    private val configuration = configuration()
    private val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))

    @Test
    fun `보존된 v1 자료의 지문은 유지하며 새 취소 형식으로 자동 복원하지 않는다`() {
        val legacy = javaClass.getResourceAsStream("/trading/funds167/matching-role-v1-multiple-fills.bin")!!.use { it.readBytes() }
        val expectedHash =
            javaClass
                .getResourceAsStream("/trading/funds167/matching-role-v1-multiple-fills.sha256")!!
                .bufferedReader()
                .use { it.readText().trim() }
        assertEquals(expectedHash, MessageDigest.getInstance("SHA-256").digest(legacy).joinToString("") { "%02x".format(it) })
        assertEquals(1, ByteBuffer.wrap(legacy).getInt(4))
        assertFails { read(legacy) }
    }

    @Test
    fun `여러 체결과 부분 잔량을 실제 바이트에서 복원하고 최초 결과를 재사용한다`() {
        val original = owner()
        val sell1 = input(1, Side.SELL, 90, 1, user = "seller")
        val sell2 = input(2, Side.SELL, 95, 1, user = "seller")
        val buy = input(3)
        original.apply(sell1)
        original.apply(sell2)
        val result = assertNotNull(original.apply(buy).result)
        assertEquals(
            listOf(
                TradeExecuted(
                    a.marketId,
                    3,
                    sell1.order.orderId,
                    buy.order.orderId,
                    UserId("seller"),
                    UserId("buyer"),
                    Side.BUY,
                    Price(90),
                    Quantity(1),
                ),
                TradeExecuted(
                    a.marketId,
                    4,
                    sell2.order.orderId,
                    buy.order.orderId,
                    UserId("seller"),
                    UserId("buyer"),
                    Side.BUY,
                    Price(95),
                    Quantity(1),
                ),
                OrderEnteredBook(a.marketId, 5, buy.order.orderId, UserId("buyer"), Side.BUY, Price(100), Quantity(1)),
            ),
            result.events,
        )
        val encoded = bytes(original.recoveryState())
        val decoded = read(encoded)
        val restored = ReservedMatchingState(configuration).also { it.restore(decoded) }
        assertTrue(encoded.size.toLong() <= configuration.snapshotByteUpperBound)
        assertContentEquals(encoded, bytes(decoded))
        assertContentEquals(encoded, bytes(restored.recoveryState()))
        assertEquals(
            1L,
            restored
                .restingOrders(a.marketId)
                .single()
                .remainingQuantity.value,
        )
        // 최초 예약은 3개이며, 현재 매칭 잔량 1개와 같아야 한다고 검사하면 안 된다.
        assertEquals(
            3L,
            restored
                .recoveryState()
                .active
                .single()
                .reservation.remainingQuantity.value,
        )
        assertEquals(MatchingIntakeStatus.DUPLICATE, restored.apply(buy).status)
        assertEquals(result, restored.apply(buy).result)
        assertContentEquals(encoded, bytes(restored.recoveryState()))
        assertFails { BoundedTradingStateCodec().read(ByteArrayInputStream(encoded), manifest, POSITION) }
    }

    @Test
    fun `결과 ACK 전후의 복원에서 활성 원본을 유지하고 같은 후속 명령열을 이어간다`() {
        for (ackThrough in listOf(1L, 3L)) {
            val original = owner()
            val buy = input(3)
            original.apply(input(1, Side.SELL, 90, 1, user = "s1"))
            original.apply(input(2, Side.SELL, 95, 1, user = "s2"))
            original.apply(buy)
            original.acknowledgeFundsSettled(ack(ackThrough))
            val before = original.recoveryState()
            val restored = ReservedMatchingState(configuration).also { it.restore(read(bytes(before))) }
            assertEquals(1, restored.recoveryState().activeOrders)
            assertEquals(
                3L,
                restored
                    .recoveryState()
                    .active
                    .single()
                    .reservation.remainingQuantity.value,
            )
            assertEquals(
                1L,
                restored
                    .restingOrders(a.marketId)
                    .single()
                    .remainingQuantity.value,
            )
            assertEquals(if (ackThrough == 3L) 0 else 2, restored.recoveryState().resultEntries)
            assertEquals(
                ackThrough,
                restored
                    .recoveryState()
                    .streams
                    .first()
                    .fundsSettledThrough,
            )
            for (state in listOf(original, restored)) {
                val repeated = state.apply(buy)
                assertEquals(if (ackThrough == 3L) MatchingIntakeStatus.PRUNED else MatchingIntakeStatus.DUPLICATE, repeated.status)
                val gap = state.apply(input(5))
                assertEquals(MatchingIntakeStatus.GAP, gap.status)
                assertEquals(4L, gap.expectedSequence)
                val sell = input(4, Side.SELL, 100, 1, user = "s3")
                val completed = assertNotNull(state.apply(sell).result)
                assertEquals(6L, completed.events.single().engineSequence)
                assertEquals(setOf(buy, sell), completed.effects.map { it.origin }.toSet())
                state.acknowledgeFundsSettled(ack(4))
                assertEquals(0, state.recoveryState().activeOrders)
                assertEquals(0, state.recoveryState().resultEntries)
                assertEquals(MatchingIntakeStatus.APPLIED, state.apply(input(1, admission = 5, market = b)).status)
            }
            assertContentEquals(bytes(original.recoveryState()), bytes(restored.recoveryState()))
        }
    }

    @Test
    fun `전체 결과 크기 거절과 기존 주문장을 복원하고 다음 사건 순번을 이어간다`() {
        // 완전한 최대 취소 한 건을 허용하는 설정에서 여러 maker 체결 결과만 단일 한도를 넘긴다.
        val small = configuration(limits(singleBytes = 2_400))
        val original = owner(small)
        original.apply(input(1, Side.SELL, 90, 1, user = "s1", expected = small))
        original.apply(input(2, Side.SELL, 95, 1, user = "s2", expected = small))
        val large = input(3, user = "u".repeat(34), expected = small)
        val rejected = assertNotNull(original.apply(large).result)
        assertEquals(MatchingOutcome.NOT_EXECUTED, rejected.outcome)
        assertEquals(MatchingNotExecutedReason.RESULT_TOO_LARGE, rejected.reason)
        assertEquals(2L, rejected.engineSequenceBefore)
        assertEquals(2L, rejected.engineSequenceAfter)
        assertEquals(2, original.restingOrders(a.marketId).size)
        val encoded = bytes(original.recoveryState())
        val restored = ReservedMatchingState(small).also { it.restore(read(encoded, small)) }
        assertContentEquals(encoded, bytes(restored.recoveryState()))
        assertEquals(MatchingIntakeStatus.DUPLICATE, restored.apply(large).status)
        val next = assertNotNull(restored.apply(input(4, quantity = 1, user = "u", expected = small)).result)
        assertEquals(MatchingOutcome.MATCHED, next.outcome)
        assertEquals(3L, next.events.single().engineSequence)
        assertEquals(4L, next.resultSequence)
    }

    @Test
    fun `기본값과 다른 한도를 실제 바이트에 보존하고 다른 고정 설정으로 읽지 않는다`() {
        val customLimits = limits(singleBytes = 2_400)
        val expected = configuration(customLimits)
        val state = owner(expected).also { it.apply(input(1, expected = expected)) }
        val encoded = bytes(state.recoveryState())
        val decoded = read(encoded, expected)
        assertEquals(customLimits, decoded.configuration.limits)
        assertContentEquals(encoded, bytes(decoded))
        val changedPolicy = contexts.map { it.copy(feePolicySnapshot = fees.copy(scheduleVersion = 2)) }
        val alternatives =
            listOf(
                configuration(customLimits, epoch = 2),
                configuration(customLimits, destination = ClusterLineage(5, 6)),
                configuration(customLimits, definition = definition(changedPolicy)),
                configuration(limits(singleBytes = 2_399)),
                configuration(
                    MatchingIntakeLimits(
                        customLimits.marketLimits.mapValues { (_, limit) ->
                            limit.copy(
                                maxActiveBytes =
                                    limit.maxActiveBytes - 1,
                            )
                        },
                        customLimits.maxResultEntries,
                        customLimits.maxResultBytes,
                        customLimits.maxActiveOrders,
                        customLimits.maxActiveBytes,
                        customLimits.maxSingleResultBytes,
                        customLimits.maxResultEvents,
                        customLimits.maxCandidateEvents,
                    ),
                ),
            )
        for (different in alternatives) assertFails { read(encoded, different) }
    }

    @Test
    fun `형식 위치 체크섬 종료표시 잘림과 뒤따른 바이트를 거절한다`() {
        val encoded = bytes(owner().also { it.apply(input(1)) }.recoveryState())
        assertFails { codec.read(ByteArrayInputStream(encoded), configuration, POSITION + 1) }
        for (offset in listOf(0, 7, encoded.size - 5, encoded.lastIndex)) {
            val damaged = encoded.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFails("offset $offset") { read(damaged) }
        }
        assertFails { read(encoded.copyOf(encoded.size / 2)) }
        assertFails { read(encoded.copyOf(encoded.size - 1)) }
        assertFails { read(encoded + byteArrayOf(1)) }
        val empty = ReservedMatchingState(configuration)
        assertEquals(TradingLifecycle.UNINITIALIZED, empty.lifecycle)
        empty.initialize()
        assertEquals(TradingLifecycle.RUNNING, empty.lifecycle)
    }

    @Test
    fun `체크섬을 다시 계산해도 과대한 문자열 예약과 행 길이는 할당 전에 거절한다`() {
        val encoded = bytes(owner().also { it.apply(input(1)) }.recoveryState())
        val offsets = offsets(encoded)
        val positions =
            listOf(
                16,
                offsets.checkpointCount,
                offsets.firstCheckpointOrderCount,
                offsets.firstCheckpointOrderStart,
                offsets.activeCount,
                offsets.firstActiveReservationLength,
                offsets.streamCount,
                offsets.firstOutstandingCount,
                offsets.firstResultEventCount,
                offsets.firstResultEffectCount,
            )
        for (offset in positions) {
            val damaged = encoded.copyOf()
            ByteBuffer.wrap(damaged).putInt(offset, Int.MAX_VALUE)
            resign(damaged)
            assertFails("length/count $offset") { read(damaged) }
        }
    }

    @Test
    fun `체크섬이 맞아도 집계 순번 사건과 활성 주문장 관계가 다르면 거절한다`() {
        val encoded = bytes(owner().also { it.apply(input(1)) }.recoveryState())
        val offsets = offsets(encoded)
        for ((offset, value) in listOf(
            offsets.resultBytes to 1L,
            offsets.firstStreamApplied to 2L,
            offsets.firstStreamSettledEngine to 1L,
            offsets.firstResultBefore to 1L,
            offsets.firstEventSequence to 2L,
        )) {
            val damaged = encoded.copyOf()
            ByteBuffer.wrap(damaged).putLong(offset, value)
            resign(damaged)
            assertFails("relation $offset") { read(damaged) }
        }
        val blocked = encoded.copyOf()
        ByteBuffer.wrap(blocked).putInt(offsets.lifecycle, TradingLifecycle.QUARANTINED.ordinal)
        resign(blocked)
        assertFails { read(blocked) }

        // book 행만 제거해도 유효한 승인 원본과 모든 active 집계는 그대로다. 이 고아 원본을 허용하지 않는다.
        val orphan =
            encoded.copyOfRange(0, offsets.firstCheckpointOrderStart) + encoded.copyOfRange(offsets.firstCheckpointOrderEnd, encoded.size)
        ByteBuffer.wrap(orphan).putInt(offsets.firstCheckpointOrderCount, 0)
        resign(orphan)
        assertFails { read(orphan) }
    }

    @Test
    fun `기존 manifest 바이트 상한을 넘는 배치는 매칭 소유자 조립 전에 거절한다`() {
        val wideContexts =
            (0 until 40).map { ordinal ->
                TradingMarketContext(MarketDefinition(MarketId("M$ordinal-QUOTE"), AssetId("B$ordinal"), quote, 0), fees, ordinal)
            }
        val wide =
            TradingClusterManifest(
                funds,
                "wide-matching-snapshot",
                "0".repeat(64),
                "1".repeat(64),
                7,
                wideContexts,
                TradingResourceLimits(maxMarkets = 40, maxAssets = 41, maxIdentifierBytes = 34),
            )
        val topology = TradingRoleTopology(wide, wideContexts.map { TradingMarketAssignment(it.market.marketId, matching, 1) })
        val declaration =
            TradingRoleDeclaration(
                TradingServiceRole.MATCHING,
                matching,
                7,
                wideContexts,
                wideContexts.associate { it.market.marketId to 1L },
            )
        val failure = assertFails { MatchingIntakeConfiguration(topology, declaration) }
        assertTrue(failure.message.orEmpty().contains("manifest"))
    }

    @Test
    fun `v2 replays unacknowledged cancellation success and rejection from the same restored prefix`() {
        for (filled in listOf(false, true)) {
            val original = owner()
            val submitted = input(1, quantity = 1)
            original.apply(submitted)
            if (filled) original.apply(input(2, Side.SELL, 100, 1, user = "seller"))
            val sequence = if (filled) 3L else 2L
            val cancel =
                submitted.copy(
                    dispatchSequence = sequence,
                    cancel =
                        FundsCancelDispatch(
                            TradingIdentifiers.cancel(funds, contexts[0].ordinal, sequence),
                            RequestToken(submitted.order.userId, 0, 1, 1),
                        ),
                )
            val result = assertNotNull(original.apply(cancel).result)
            assertEquals(if (filled) MatchingOutcome.CANCEL_REJECTED else MatchingOutcome.CANCELLED, result.outcome)
            val encoded = bytes(original.recoveryState())
            assertEquals(2, ByteBuffer.wrap(encoded).getInt(4))
            val restored = ReservedMatchingState(configuration).also { it.restore(read(encoded)) }
            assertContentEquals(encoded, bytes(restored.recoveryState()))
            assertEquals(result, restored.apply(cancel).result)
            assertTrue(restored.restingOrders(a.marketId).isEmpty())
            restored.acknowledgeFundsSettled(ack(sequence))
            assertEquals(0, restored.recoveryState().resultEntries)
        }
    }

    private fun definition(contexts: List<TradingMarketContext>) =
        TradingClusterManifest(
            funds,
            "matching-snapshot-test",
            "0".repeat(64),
            "1".repeat(64),
            7,
            contexts,
            TradingResourceLimits(maxIdentifierBytes = 34),
        )

    private fun limits(singleBytes: Long = 4_096) =
        MatchingIntakeLimits(
            contexts.associate { it.market.marketId to MarketMatchingIntakeLimits(13, 32_768, 5, 16_384) },
            maxResultEntries = 17,
            maxResultBytes = 65_536,
            maxActiveOrders = 7,
            maxActiveBytes = 24_576,
            maxSingleResultBytes = singleBytes,
            maxResultEvents = 8,
            maxCandidateEvents = 12,
        )

    private fun configuration(
        limits: MatchingIntakeLimits = limits(),
        definition: TradingClusterManifest = manifest,
        destination: ClusterLineage = matching,
        epoch: Long = 1,
    ): MatchingIntakeConfiguration {
        val layout =
            TradingRoleTopology(definition, definition.markets.map { TradingMarketAssignment(it.market.marketId, destination, epoch) })
        val declaration =
            TradingRoleDeclaration(
                TradingServiceRole.MATCHING,
                destination,
                definition.configurationVersion,
                definition.markets,
                definition.markets.associate { it.market.marketId to epoch },
            )
        return MatchingIntakeConfiguration(layout, declaration, limits)
    }

    private fun owner(expected: MatchingIntakeConfiguration = configuration) = ReservedMatchingState(expected).also { it.initialize() }

    private fun input(
        dispatch: Long,
        side: Side = Side.BUY,
        price: Long = 100,
        quantity: Long = 3,
        admission: Long = dispatch,
        market: MarketDefinition = a,
        user: String = "buyer",
        expected: MatchingIntakeConfiguration = configuration,
    ): FundsOrderDispatch {
        val context = expected.context(market.marketId)
        val order =
            OrderAdmissionInput(
                market.marketId,
                TradingIdentifiers.order(funds, admission),
                UserId(user),
                side,
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(price),
                Quantity(quantity),
            )
        val required = reservations.calculate(market, side, order.price, order.quantity, context.feePolicySnapshot)
        return FundsOrderDispatch(
            expected.stream(market.marketId),
            dispatch,
            admission,
            dispatch,
            order,
            OrderReservation.create(
                market.marketId,
                order.orderId,
                order.userId,
                side,
                order.price,
                order.quantity,
                required,
                context.feePolicySnapshot,
            ),
            context,
        )
    }

    private fun ack(through: Long) = MatchingFundsSettledAck(configuration.resultStream(a.marketId), through, contexts[0])

    private fun bytes(state: MatchingRoleRecoveryState): ByteArray =
        ByteArrayOutputStream()
            .also {
                codec.write(state, POSITION, it)
            }.toByteArray()

    private fun read(
        bytes: ByteArray,
        expected: MatchingIntakeConfiguration = configuration,
    ): MatchingRoleRecoveryState = codec.read(ByteArrayInputStream(bytes), expected, POSITION)

    private fun resign(bytes: ByteArray) {
        val digestOffset = bytes.size - 36
        MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(digestOffset)).copyInto(bytes, digestOffset)
    }

    /** 구현 codec와 독립된 v2 필드 순서 이동기다. 내용은 재구성하지 않고 바꿀 위치만 찾는다. */
    @Test
    fun `체크섬을 다시 계산해도 초기 frontier에 과거 사건을 주입할 수 없다`() {
        val completed = owner()
        completed.apply(input(1, quantity = 1))
        completed.apply(input(2, Side.SELL, 100, 1, user = "seller"))
        completed.acknowledgeFundsSettled(ack(2))
        val original = bytes(completed.recoveryState())
        assertEquals(0, read(original).activeOrders)
        val damaged = original.copyOf()
        val buffer = ByteBuffer.wrap(damaged)
        var cursor = 20 + buffer.getInt(16) + 28
        assertEquals(1, buffer.getInt(cursor))
        cursor += 4
        cursor += 4 + buffer.getInt(cursor)
        val checkpointSequence = cursor
        cursor += 8
        assertEquals(0, buffer.getInt(cursor))
        cursor += 4
        assertEquals(0, buffer.getInt(cursor))
        cursor += 4
        assertEquals(2, buffer.getInt(cursor))
        cursor += 4
        cursor += 4 + buffer.getInt(cursor)
        for (offset in listOf(0, 8, 16, 24, 32)) buffer.putLong(cursor + offset, 0)
        buffer.putLong(cursor + 40, 100)
        buffer.putLong(checkpointSequence, 100)
        resign(damaged)
        assertFails { read(damaged) }
    }

    private fun offsets(bytes: ByteArray): Offsets {
        val buffer = ByteBuffer.wrap(bytes)
        var cursor = 20 + buffer.getInt(16)
        val lifecycle = cursor
        val resultBytes = cursor + 8
        cursor += 28
        val checkpointCount = cursor
        cursor += 4
        var checkpointOrderCount = -1
        var checkpointOrderStart = -1
        var checkpointOrderEnd = -1

        fun text() {
            val count = buffer.getInt(cursor)
            cursor += 4 + count
        }
        repeat(buffer.getInt(checkpointCount)) {
            text()
            cursor += 8
            val countOffset = cursor
            val count = buffer.getInt(cursor)
            cursor += 4
            repeat(count) {
                val start = cursor
                text()
                text()
                cursor += 28
                if (checkpointOrderStart ==
                    -1
                ) {
                    checkpointOrderCount = countOffset
                    checkpointOrderStart = start
                    checkpointOrderEnd = cursor
                }
            }
        }
        val activeCount = cursor
        cursor += 4
        var firstReservationLength = -1

        fun dispatch() {
            text()
            cursor += 24
            text()
            text()
            text()
            cursor += 28
            val reservationLength = cursor
            text()
            if (firstReservationLength == -1) firstReservationLength = reservationLength
            assertEquals(0, buffer.get(cursor).toInt())
            cursor++
        }
        repeat(buffer.getInt(activeCount)) { dispatch() }
        val streamCount = cursor
        cursor += 4
        text()
        val firstApplied = cursor
        val settledEngine = cursor + 40
        cursor += 68
        val firstOutstanding = cursor
        cursor += 4
        cursor += 8
        dispatch()
        cursor += 8
        val firstResultBefore = cursor
        cursor += 16
        val eventCount = cursor
        val count = buffer.getInt(cursor)
        cursor += 4
        var firstEventSequence = -1
        repeat(count) {
            val type = buffer.getInt(cursor)
            cursor += 4
            if (firstEventSequence == -1) firstEventSequence = cursor
            cursor += 8
            repeat(if (type == 1) 4 else 2) { text() }
            cursor += 20
        }
        val effectCount = cursor
        return Offsets(
            lifecycle,
            resultBytes,
            checkpointCount,
            checkpointOrderCount,
            checkpointOrderStart,
            checkpointOrderEnd,
            activeCount,
            firstReservationLength,
            streamCount,
            firstApplied,
            settledEngine,
            firstOutstanding,
            firstResultBefore,
            eventCount,
            firstEventSequence,
            effectCount,
        )
    }

    private data class Offsets(
        val lifecycle: Int,
        val resultBytes: Int,
        val checkpointCount: Int,
        val firstCheckpointOrderCount: Int,
        val firstCheckpointOrderStart: Int,
        val firstCheckpointOrderEnd: Int,
        val activeCount: Int,
        val firstActiveReservationLength: Int,
        val streamCount: Int,
        val firstStreamApplied: Int,
        val firstStreamSettledEngine: Int,
        val firstOutstandingCount: Int,
        val firstResultBefore: Int,
        val firstResultEventCount: Int,
        val firstEventSequence: Int,
        val firstResultEffectCount: Int,
    )

    companion object {
        private const val POSITION = 4_096L
    }
}
