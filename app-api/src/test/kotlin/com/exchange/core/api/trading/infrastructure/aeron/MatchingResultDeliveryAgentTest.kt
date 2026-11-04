package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryEnvelope
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryReply
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
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.MarketMatchingIntakeLimits
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultRead
import com.exchange.core.trading.MatchingResultReadStatus
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import io.aeron.Publication
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 작업의 제어·재전송 경계 검사다. 실제 Aeron·복제 로그 증거는 별도 6노드 검사에서 수집한다. */
class MatchingResultDeliveryAgentTest {
    @Test
    fun `대기 결과와 체결 결과를 정산하고 자기 로그 ACK 뒤에만 결과를 회수한다`() {
        val f = Fixture()
        f.produce(side = Side.SELL, price = 100)
        f.agent.use {
            f.cycles(15)
            assertEquals(Balance(f.seller, f.asset, Amount(4), Amount(1)), f.funds.currentBalance(f.seller, f.asset))
            assertEquals(1, f.matching.restingOrders(f.market.marketId).size)
            assertEquals(0, f.matching.recoveryState().resultEntries)
            f.produce(price = 100)
            // 빈 source 조회의 1ms 재조회 지연이 지난 뒤 새 결과를 읽는다.
            f.advance(f.config.resultDeliveryLimits.busyMinDelayNs)
            f.cycles(15)
            assertEquals(Balance(f.buyer, f.quote, Amount(900), Amount.ZERO), f.funds.currentBalance(f.buyer, f.quote))
            assertEquals(Balance(f.buyer, f.asset, Amount(1), Amount.ZERO), f.funds.currentBalance(f.buyer, f.asset))
            assertEquals(Balance(f.seller, f.quote, Amount(100), Amount.ZERO), f.funds.currentBalance(f.seller, f.quote))
            assertEquals(Balance(f.seller, f.asset, Amount(4), Amount.ZERO), f.funds.currentBalance(f.seller, f.asset))
            assertEquals(2L, f.confirmed())
            assertEquals(0, f.matching.recoveryState().resultEntries)
            assertTrue(f.matching.restingOrders(f.market.marketId).isEmpty())
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `앞 정산 응답 없이 두 결과를 보내고 누적 ACK 마지막 하나로 모두 회수한다`() {
        val f = Fixture()
        repeat(2) { f.produce() }
        f.transport.holdFunds = true
        f.agent.use {
            f.cycles(8)
            assertEquals(listOf(1L, 2L), f.transport.applies.map { (it.command as SplitResultDeliveryCommand.Apply).result.resultSequence })
            assertEquals(2, f.matching.recoveryState().resultEntries)
            assertTrue(f.transport.recordedAcks.isEmpty())
            f.transport.releaseFunds(onlyLast = true)
            f.cycles(10)
            assertEquals(listOf(2L), f.transport.recordedAcks.map { it.through })
            assertEquals(2L, f.confirmed())
            assertEquals(0, f.matching.recoveryState().resultEntries)
            // 이미 누적 확인한 과거 correlation은 현재 ACK를 되돌리지 않는다.
            val old = f.transport.applies.first()
            f.transport.fundsConnection.ready += f.transport.reply(old, TradingServiceRole.FUNDS, "APPLIED", f.ack(1))
            f.cycles(2)
            assertEquals(listOf(2L), f.transport.recordedAcks.map { it.through })
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `자금 응답 유실은 같은 결과를 재전송하며 최초 금융 시각과 예약을 반복하지 않는다`() {
        val f = Fixture()
        val first = f.produce()
        f.transport.holdFunds = true
        f.agent.use {
            f.cycles(8)
            val before = f.funds.fundsRecoveryState()
            f.advance(f.config.resultDeliveryLimits.attemptTimeoutNs)
            f.cycles(1)
            assertTrue(f.transport.connections.all { it.closed })
            f.transport.holdFunds = false
            f.advance(f.config.resultDeliveryLimits.reconnectDelayNs)
            f.cycles(16)
            assertEquals(listOf(first, first), f.transport.applies.map { (it.command as SplitResultDeliveryCommand.Apply).result })
            assertEquals(
                assertNotNull(before.results).receipts.single().appliedAt,
                assertNotNull(f.funds.fundsRecoveryState().results).receipts.single().appliedAt,
            )
            assertEquals(Balance(f.buyer, f.quote, Amount(999), Amount(1)), f.funds.currentBalance(f.buyer, f.quote))
            assertEquals(1L, f.confirmed())
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `자기 ACK 응답 유실 뒤 확정 source를 읽어 회수한 결과를 되살리지 않는다`() {
        val f = Fixture()
        f.produce()
        f.transport.dropMatchingReplies = true
        f.agent.use {
            f.cycles(8)
            assertEquals(1L, f.confirmed())
            f.advance(f.config.resultDeliveryLimits.attemptTimeoutNs)
            f.cycles(1)
            f.transport.dropMatchingReplies = false
            f.advance(f.config.resultDeliveryLimits.reconnectDelayNs)
            f.cycles(16)
            assertEquals(1, f.transport.applies.size)
            assertEquals(0, f.matching.recoveryState().resultEntries)
            assertTrue(f.source.queries.count { it.from == 1L } >= 2)
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `PRUNED 자금 응답도 정확한 typed 누적 ACK로만 회수한다`() {
        val f = Fixture()
        f.produce()
        f.transport.overrideFunds = { envelope -> f.transport.reply(envelope, TradingServiceRole.FUNDS, "PRUNED", f.ack(1)) }
        f.agent.use {
            f.cycles(15)
            assertEquals(1L, f.confirmed())
            assertEquals(0, f.matching.recoveryState().resultEntries)
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `요청 결과보다 낮은 자금 ACK와 제출보다 낮은 matching ACK는 완료로 인정하지 않는다`() {
        for (matchingReply in listOf(false, true)) {
            val f = Fixture()
            f.produce()
            if (matchingReply) {
                f.transport.overrideRecorded = { envelope ->
                    f.transport.reply(envelope, TradingServiceRole.MATCHING, "ACKNOWLEDGED", f.ack(0))
                }
            } else {
                f.transport.overrideFunds = { envelope -> f.transport.reply(envelope, TradingServiceRole.FUNDS, "APPLIED", f.ack(0)) }
            }
            f.agent.use {
                f.cycles(15)
                assertNotNull(f.agent.failure)
                assertEquals(0L, f.confirmed())
                assertEquals(1, f.matching.recoveryState().resultEntries)
            }
        }
    }

    @Test
    fun `잘못된 stream context와 미래 자금 ACK는 회수하지 않는다`() {
        for (kind in 0..2) {
            val f = Fixture()
            f.produce()
            val ack =
                when (kind) {
                    0 -> f.ack(1).copy(stream = f.stream.copy(ownershipEpoch = 2))
                    1 -> f.ack(1).copy(context = f.context.copy(ordinal = 1))
                    else -> f.ack(2)
                }
            f.transport.overrideFunds = { envelope -> f.transport.reply(envelope, TradingServiceRole.FUNDS, "APPLIED", ack) }
            f.agent.use {
                f.cycles(15)
                assertNotNull(f.agent.failure)
                assertEquals(0L, f.confirmed())
                assertEquals(1, f.matching.recoveryState().resultEntries)
                assertTrue(f.transport.recordedAcks.isEmpty())
            }
        }
    }

    @Test
    fun `오래된 조회 issued보다 앞선 정상 누적 ACK는 현재 source 발급을 다시 확인한다`() {
        val f = Fixture()
        f.produce()
        f.source.defer = true
        f.agent.use {
            f.cycles(5)
            val old = f.matching.readResults(f.stream, 1, 8, 2L * 1_024 * 1_024)
            f.produce()
            f.transport.overrideFunds = { envelope -> f.transport.reply(envelope, TradingServiceRole.FUNDS, "APPLIED", f.ack(2)) }
            f.source.defer = false
            checkNotNull(f.source.deferred).complete(old)
            f.cycles(15)
            assertEquals(2L, f.confirmed())
            assertTrue(f.source.queries.any { it.bytes == 1L })
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `발급 확인 조회가 진행되는 동안 더 높은 누적 ACK가 오면 다시 조회한다`() {
        val f = Fixture()
        f.produce()
        f.transport.holdFunds = true
        f.transport.overrideFunds = { envelope -> f.transport.reply(envelope, TradingServiceRole.FUNDS, "APPLIED", f.ack(2)) }
        f.source.defer = true
        f.agent.use {
            f.cycles(3)
            val firstRead = checkNotNull(f.source.deferred)
            firstRead.complete(f.matching.readResults(f.stream, 1, 8, 2L * 1_024 * 1_024))
            f.cycles(1)
            // 다음 빈 조회는 결과2가 생기기 전에 완료한 오래된 서비스 관찰이다.
            val emptyRead = checkNotNull(f.source.deferred)
            val empty = f.matching.readResults(f.stream, 2, 8, 2L * 1_024 * 1_024)
            f.produce()
            f.transport.releaseFunds()
            emptyRead.complete(empty)
            f.cycles(1)
            val issuedTwoRead = checkNotNull(f.source.deferred)
            assertEquals(
                1L,
                f.source.queries
                    .last()
                    .bytes,
            )
            val issuedTwo = f.matching.readResults(f.stream, 1, 1, 1)
            f.produce()
            f.transport.fundsConnection.ready +=
                f.transport.reply(f.transport.applies.single(), TradingServiceRole.FUNDS, "APPLIED", f.ack(3))
            f.advance(f.config.resultDeliveryLimits.ackDelayNs)
            f.cycles(2)
            // 최신 ACK는 하나만 보관하며 issued 검증 전에는 지연 기한이 지나도 제출하지 않는다.
            assertTrue(f.transport.recordedAcks.isEmpty())
            assertEquals(0L, f.confirmed())
            assertEquals(3, f.matching.recoveryState().resultEntries)
            f.source.defer = false
            issuedTwoRead.complete(issuedTwo)
            f.cycles(12)
            assertEquals(2, f.source.queries.count { it.bytes == 1L })
            assertEquals(listOf(3L), f.transport.recordedAcks.map { it.through })
            assertEquals(3L, f.confirmed())
            assertEquals(0, f.matching.recoveryState().resultEntries)
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `GAP은 보관한 앞 결과부터 다시 보내고 미래 순번을 건너뛰지 않는다`() {
        val f = Fixture()
        repeat(2) { f.produce() }
        var rejected = false
        f.transport.overrideFunds = { envelope ->
            val sequence = (envelope.command as SplitResultDeliveryCommand.Apply).result.resultSequence
            if (!rejected) {
                rejected = true
                f.transport.reply(envelope, TradingServiceRole.FUNDS, "GAP", expected = 1)
            } else {
                assertTrue(sequence >= 1)
                null
            }
        }
        f.agent.use {
            f.cycles(25)
            assertEquals(2L, f.confirmed())
            assertTrue(f.transport.applies.count { (it.command as SplitResultDeliveryCommand.Apply).result.resultSequence == 1L } >= 2)
            assertEquals(2, assertNotNull(f.funds.fundsRecoveryState().results).receipts.size)
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `이미 회수한 GAP 순번은 자금 progress를 대조하고 뒤처진 복원은 중단한다`() {
        for (behind in listOf(false, true)) {
            val f = Fixture()
            val first = f.produce()
            f.funds.applyMatchingResult(first, Instant.EPOCH, f.config.settlementCalculator())
            f.matching.acknowledgeFundsSettled(f.ack(1))
            f.produce()
            var once = false
            f.transport.overrideFunds = { envelope ->
                if (!once) {
                    once = true
                    f.transport.reply(envelope, TradingServiceRole.FUNDS, "GAP", expected = 1)
                } else {
                    null
                }
            }
            f.transport.overrideProgress = { envelope ->
                f.transport.reply(envelope, TradingServiceRole.FUNDS, "PROGRESS", f.ack(if (behind) 0 else 1))
            }
            f.agent.use {
                f.cycles(30)
                assertEquals(1, f.transport.progressQueries)
                if (behind) {
                    assertNotNull(f.agent.failure)
                    assertEquals(1L, f.confirmed())
                    assertEquals(1, f.matching.recoveryState().resultEntries)
                } else {
                    assertNull(f.agent.failure)
                    assertEquals(2L, f.confirmed())
                    assertEquals(0, f.matching.recoveryState().resultEntries)
                }
            }
        }
    }

    @Test
    fun `NOT_READY와 offer backpressure 중에도 polling keepalive를 유지하고 같은 결과를 재개한다`() {
        val f = Fixture(limits = MatchingResultDeliveryLimits(maxInFlightEntries = 2))
        repeat(4) { f.produce() }
        f.transport.holdFunds = true
        f.agent.use {
            f.cycles(10)
            assertEquals(2, f.transport.applies.size)
            f.transport.applyOfferResult = Publication.BACK_PRESSURED
            f.transport.releaseFunds()
            f.advance(f.config.resultDeliveryLimits.keepAliveNs)
            f.cycles(1)
            assertTrue(f.transport.connections.all { it.polls > 0 && it.keepAlives > 0 })
            // 뒤 batch가 막혀도 마지막 ACK는 2ms 기한에서 flush되어야 한다.
            assertEquals(0L, f.confirmed())
            f.advance(f.config.resultDeliveryLimits.ackDelayNs - 1)
            f.cycles(1)
            assertEquals(0L, f.confirmed())
            f.advance(1)
            f.cycles(2)
            assertEquals(2L, f.confirmed())
            assertEquals(2, f.matching.recoveryState().resultEntries)
            f.transport.applyOfferResult = null
            f.transport.holdFunds = false
            var once = false
            f.transport.overrideFunds = { envelope ->
                if (!once) {
                    once = true
                    f.transport.reply(envelope, TradingServiceRole.FUNDS, "NOT_READY")
                } else {
                    null
                }
            }
            f.cycles(2)
            f.advance(f.config.resultDeliveryLimits.busyMaxDelayNs)
            f.cycles(20)
            assertEquals(4L, f.confirmed())
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `실제 frame 예산과 준비 상계가 건수 창보다 먼저 차면 다음 결과를 보존한다`() {
        val f =
            Fixture(
                limits = MatchingResultDeliveryLimits(maxInFlightBytes = 7_000),
                maxSingleResultBytes = 4_096,
                maxResultEvents = 1,
            )
        repeat(8) { f.produce() }
        f.transport.holdFunds = true
        f.agent.use {
            f.cycles(12)
            assertTrue(f.transport.applies.size in 1..7)
            val bytes =
                f.transport.applies.sumOf {
                    f.transport.codec
                        .encode(it.correlationId, it.command)
                        .size
                }
            assertTrue(bytes + f.config.resultDeliveryLimits.controlReserve(f.config) <= 7_000)
            assertEquals(8, f.matching.recoveryState().resultEntries)
            assertEquals(0L, f.confirmed())
            f.transport.releaseFunds()
            f.transport.holdFunds = false
            f.cycles(30)
            assertEquals(8L, f.confirmed())
            assertEquals(0, f.matching.recoveryState().resultEntries)
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `정산 ACK offer만 막혀도 자금 처리는 진행하고 source는 보존된다`() {
        val f = Fixture(limits = MatchingResultDeliveryLimits(maxInFlightEntries = 1))
        repeat(4) { f.produce() }
        f.transport.ackOfferResult = Publication.BACK_PRESSURED
        f.agent.use {
            f.cycles(25)
            assertEquals(4, f.transport.applies.size)
            assertEquals(4, f.matching.recoveryState().resultEntries)
            assertEquals(0L, f.confirmed())
            assertEquals(4L, f.funds.matchingSettlementAck(f.stream)?.through)
            f.transport.ackOfferResult = null
            f.advance(f.config.resultDeliveryLimits.ackDelayNs)
            f.cycles(15)
            assertEquals(4L, f.confirmed())
            assertEquals(0, f.matching.recoveryState().resultEntries)
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `세대 변경은 늦은 조회와 응답을 버리고 팔로워 동안 새 송신을 막는다`() {
        val f = Fixture()
        f.produce()
        f.source.defer = true
        f.agent.use {
            f.cycles(5)
            val deferred = checkNotNull(f.source.deferred)
            f.source.generation = null
            f.cycles(3)
            assertTrue(deferred.isCancelled)
            assertTrue(f.transport.connections.all { it.closed })
            assertTrue(f.transport.applies.isEmpty())
            f.source.defer = false
            f.source.generation = 2
            f.cycles(15)
            assertEquals(1L, f.confirmed())
            val old = f.transport.connections.first { it.role == TradingServiceRole.FUNDS }
            old.onReply(f.transport.reply(f.transport.applies.single(), TradingServiceRole.FUNDS, "APPLIED", f.ack(2)))
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `음수 nanoTime과 기한 경계에서도 연결 재시도는 지연을 정확히 지킨다`() {
        val f = Fixture()
        f.now = -1_000_000_000_000L
        f.produce()
        f.transport.applyOfferResult = Publication.NOT_CONNECTED
        f.agent.use {
            f.cycles(8)
            val count = f.transport.connections.size
            f.advance(f.config.resultDeliveryLimits.reconnectDelayNs - 1)
            f.cycles(1)
            assertEquals(count, f.transport.connections.size)
            f.transport.applyOfferResult = null
            f.advance(1)
            f.cycles(15)
            assertEquals(1L, f.confirmed())
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `마지막 Long 결과 뒤에는 null cursor로 끝나며 0으로 돌아가지 않는다`() {
        val f = Fixture()
        val original = f.produce()
        val lastInput = original.input.copy(dispatchSequence = Long.MAX_VALUE)
        val last =
            MatchingResultEnvelope(
                original.stream,
                Long.MAX_VALUE,
                lastInput,
                original.outcome,
                original.reason,
                original.engineSequenceBefore,
                original.engineSequenceAfter,
                original.events,
                original.effects.map { it.copy(origin = lastInput) },
            )
        f.source.overrideRead = { query ->
            when (query.from) {
                1L -> {
                    MatchingResultRead(
                        MatchingResultReadStatus.PRUNED,
                        fundsSettledThrough = Long.MAX_VALUE - 1,
                        resultIssued = Long.MAX_VALUE,
                        nextSequence = Long.MAX_VALUE,
                    )
                }

                Long.MAX_VALUE -> {
                    MatchingResultRead(
                        MatchingResultReadStatus.BATCH,
                        listOf(last),
                        Long.MAX_VALUE - 1,
                        Long.MAX_VALUE,
                        nextSequence = null,
                    )
                }

                else -> {
                    error("wrapped cursor: ${query.from}")
                }
            }
        }
        f.transport.overrideFunds = { envelope -> f.transport.reply(envelope, TradingServiceRole.FUNDS, "APPLIED", f.ack(Long.MAX_VALUE)) }
        f.transport.overrideRecorded = { envelope ->
            f.transport.reply(envelope, TradingServiceRole.MATCHING, "ACKNOWLEDGED", f.ack(Long.MAX_VALUE), issued = Long.MAX_VALUE)
        }
        f.agent.use {
            f.cycles(15)
            assertEquals(listOf(1L, Long.MAX_VALUE), f.source.queries.map { it.from })
            assertEquals(1, f.transport.applies.size)
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `한 건조차 못 읽는 응답과 잘못된 peer는 성공으로 숨기지 않는다`() {
        val small = Fixture()
        small.produce()
        small.source.overrideRead = {
            MatchingResultRead(MatchingResultReadStatus.BATCH_TOO_SMALL, resultIssued = 1, requiredBytes = 3_000_000)
        }
        small.agent.use {
            small.cycles(10)
            assertNotNull(small.agent.failure)
            assertTrue(small.transport.applies.isEmpty())
        }
        val wrong = Fixture()
        wrong.produce()
        wrong.transport.wrongTopology = true
        wrong.agent.use {
            assertFailsWith<IllegalArgumentException> { wrong.cycles(5) }
            assertEquals(1, wrong.matching.recoveryState().resultEntries)
            assertTrue(wrong.transport.applies.isEmpty())
        }
    }

    @Test
    fun `미지원 전송 한도는 연결 생성 전에 거절한다`() {
        val f = Fixture()
        f.agent.use {
            assertFailsWith<IllegalArgumentException> {
                MatchingResultDeliveryAgent(
                    f.config,
                    f.source,
                    f.fundsEndpoint,
                    f.matchingEndpoint,
                    limits = f.config.resultDeliveryLimits.copy(maxReadEntries = 1),
                    connectionFactory = f.transport,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                f.config.resultDeliveryLimits
                    .copy(maxReadBytes = 1)
                    .requireCompatible(f.config)
            }
            assertFailsWith<IllegalArgumentException> {
                f.config.resultDeliveryLimits
                    .copy(maxInFlightBytes = 1)
                    .requireCompatible(f.config)
            }
            assertTrue(f.transport.connections.isEmpty())
        }
    }

    @Test
    fun `연결 생성 offer poll keepalive close는 결과 작업 스레드 하나가 소유한다`() {
        val f = Fixture()
        f.produce()
        f.agent.start()
        try {
            assertTrue(f.transport.offeredApply.await(3, TimeUnit.SECONDS))
        } finally {
            f.agent.close()
        }
        assertEquals(1, f.transport.ownerThreads.size)
        val owner = f.transport.ownerThreads.single()
        assertEquals("matching-result-delivery", owner.name)
        assertFalse(owner.isAlive)
        assertTrue(f.transport.connections.all { it.closed })
        assertNull(f.agent.failure)
    }

    private class Fixture(
        limits: MatchingResultDeliveryLimits = MatchingResultDeliveryLimits(),
        maxSingleResultBytes: Long = 1L * 1_024 * 1_024,
        maxResultEvents: Int = 1_024,
    ) {
        val quote = AssetId("QUOTE")
        val asset = AssetId("A")
        val buyer = UserId("buyer")
        val seller = UserId("seller")
        val market = MarketDefinition(MarketId("A-QUOTE"), asset, quote, 0)
        val context =
            TradingMarketContext(
                market,
                TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)),
                0,
            )
        private val manifest =
            TradingClusterManifest(
                ClusterLineage(1, 2),
                "result-worker-unit",
                "0".repeat(64),
                "1".repeat(64),
                7,
                listOf(context),
                TradingResourceLimits(),
            )
        private val topology = TradingRoleTopology(manifest, listOf(TradingMarketAssignment(market.marketId, ClusterLineage(3, 4), 1)))
        private val fundsDeclaration =
            TradingRoleDeclaration(TradingServiceRole.FUNDS, manifest.lineage, 7, listOf(context), mapOf(market.marketId to 1L))
        private val matchingDeclaration =
            TradingRoleDeclaration(TradingServiceRole.MATCHING, ClusterLineage(3, 4), 7, listOf(context), mapOf(market.marketId to 1L))
        val config =
            SplitTradingConfiguration(
                topology,
                fundsDeclaration,
                matchingDeclaration,
                matchingLimits =
                    MatchingIntakeLimits(
                        mapOf(market.marketId to MarketMatchingIntakeLimits()),
                        maxSingleResultBytes = maxSingleResultBytes,
                        maxResultEvents = maxResultEvents,
                    ),
                resultDeliveryLimits = limits,
            )
        val funds =
            config.funds().also {
                it.initialize(manifest)
                it.prepareDevelopmentBalance(OpeningBalance("buyer-opening", buyer, quote, Amount(1_000)), Instant.EPOCH)
                it.prepareDevelopmentBalance(OpeningBalance("seller-opening", seller, asset, Amount(5)), Instant.EPOCH)
                it.rotateEpoch(buyer, 0, 0)
                it.rotateEpoch(seller, 0, 0)
            }
        val matching = config.matching().also { it.initialize() }
        val stream = config.matchingConfiguration.resultStream(market.marketId)
        val fundsEndpoint = SplitOrderDeliveryEndpoint("/unused-driver", "0=funds:2000", "localhost:2007", 198)
        val matchingEndpoint = SplitOrderDeliveryEndpoint("/unused-driver", "0=matching:3000", "localhost:3007", 199)
        val source = Source(this)
        val transport = Transport(this)
        var now = 1L
        private var buyerToken = 1L
        private var sellerToken = 1L
        private var dispatchSequence = 1L
        val agent =
            MatchingResultDeliveryAgent(
                config,
                source,
                fundsEndpoint,
                matchingEndpoint,
                connectionFactory = transport,
                nanoTime = { now },
            )

        fun produce(
            quantity: Long = 1,
            price: Long = 1,
            side: Side = Side.BUY,
        ): MatchingResultEnvelope {
            val user = if (side == Side.BUY) buyer else seller
            val token = if (side == Side.BUY) buyerToken++ else sellerToken++
            assertIs<AdmissionReply.Recorded>(
                funds.admit(
                    RequestToken(user, 0, 1, token),
                    OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity)),
                    Instant.EPOCH,
                ),
            )
            val dispatch =
                funds
                    .pendingDispatch(
                        config.dispatchConfiguration.stream(market.marketId),
                        dispatchSequence++,
                        1,
                        256L * 1_024,
                    ).entries
                    .single()
            return assertNotNull(matching.apply(dispatch).result)
        }

        fun ack(through: Long) = MatchingFundsSettledAck(stream, through, context)

        fun confirmed() =
            matching
                .recoveryState()
                .streams
                .single()
                .fundsSettledThrough

        fun issued() =
            matching
                .recoveryState()
                .streams
                .single()
                .resultIssued

        fun cycles(count: Int) {
            repeat(count) { agent.doWork() }
        }

        fun advance(ns: Long) {
            now += ns
        }
    }

    private data class Query(
        val from: Long,
        val entries: Int,
        val bytes: Long,
    )

    private class Source(
        val f: Fixture,
    ) : MatchingResultSource {
        var generation: Long? = 1
        var defer = false
        var deferred: CompletableFuture<MatchingResultRead>? = null
        var overrideRead: ((Query) -> MatchingResultRead)? = null
        val queries = mutableListOf<Query>()

        override fun activeResultGeneration() = generation

        override fun readResults(
            generation: Long,
            stream: MatchingResultStream,
            fromSequence: Long,
            maxEntries: Int,
            maxBytes: Long,
        ): CompletableFuture<MatchingResultRead> {
            assertEquals(this.generation, generation)
            val query = Query(fromSequence, maxEntries, maxBytes)
            queries += query
            if (defer) return CompletableFuture<MatchingResultRead>().also { deferred = it }
            return CompletableFuture.completedFuture(
                overrideRead?.invoke(query) ?: f.matching.readResults(stream, fromSequence, maxEntries, maxBytes),
            )
        }
    }

    private class Transport(
        val f: Fixture,
    ) : SplitResultDeliveryConnectionFactory {
        val codec = SplitResultDeliveryCodec()
        val connections = mutableListOf<Connection>()
        val ownerThreads = ConcurrentHashMap.newKeySet<Thread>()
        val offeredApply = CountDownLatch(1)
        val applies = mutableListOf<SplitResultDeliveryEnvelope>()
        val recordedAcks = mutableListOf<MatchingFundsSettledAck>()
        var progressQueries = 0
        var holdFunds = false
        var dropMatchingReplies = false
        var wrongTopology = false
        var applyOfferResult: Long? = null
        var ackOfferResult: Long? = null
        var overrideFunds: ((SplitResultDeliveryEnvelope) -> SplitResultDeliveryReply?)? = null
        var overrideRecorded: ((SplitResultDeliveryEnvelope) -> SplitResultDeliveryReply?)? = null
        var overrideProgress: ((SplitResultDeliveryEnvelope) -> SplitResultDeliveryReply?)? = null
        val fundsConnection get() = connections.last { it.role == TradingServiceRole.FUNDS && !it.closed }

        override fun begin(
            endpoint: SplitOrderDeliveryEndpoint,
            timeoutNs: Long,
            onReply: (SplitResultDeliveryReply) -> Unit,
        ): SplitResultDeliveryConnectAttempt {
            ownerThreads += Thread.currentThread()
            val connection = Connection(if (endpoint.clusterId == 198) TradingServiceRole.FUNDS else TradingServiceRole.MATCHING, onReply)
            connections += connection
            return object : SplitResultDeliveryConnectAttempt {
                override fun poll(): SplitResultDeliveryConnection {
                    ownerThreads += Thread.currentThread()
                    return connection
                }

                override fun close() {
                    ownerThreads += Thread.currentThread()
                }
            }
        }

        fun reply(
            envelope: SplitResultDeliveryEnvelope,
            role: TradingServiceRole,
            code: String,
            ack: MatchingFundsSettledAck? = null,
            expected: Long? = null,
            issued: Long? = if (role == TradingServiceRole.MATCHING) f.issued() else null,
        ) = SplitResultDeliveryReply(
            envelope.correlationId,
            code,
            role,
            if (role == TradingServiceRole.FUNDS) f.config.fundsLineage else f.config.matchingLineage,
            if (wrongTopology) "f".repeat(64) else f.config.resultTopologySha256,
            1,
            ack,
            expected,
            issued,
            TradingLifecycle.RUNNING,
        )

        fun releaseFunds(onlyLast: Boolean = false) {
            connections.filter { it.role == TradingServiceRole.FUNDS && !it.closed }.forEach { connection ->
                if (onlyLast && connection.held.isNotEmpty()) {
                    connection.ready += connection.held.last()
                } else {
                    connection.ready.addAll(connection.held)
                }
                connection.held.clear()
            }
        }

        inner class Connection(
            val role: TradingServiceRole,
            val onReply: (SplitResultDeliveryReply) -> Unit,
        ) : SplitResultDeliveryConnection {
            var closed = false
            var polls = 0
            var keepAlives = 0
            val ready = ArrayDeque<SplitResultDeliveryReply>()
            val held = mutableListOf<SplitResultDeliveryReply>()
            override val isClosed get() = closed

            override fun offer(bytes: ByteArray): Long {
                ownerThreads += Thread.currentThread()
                check(!closed)
                val envelope = codec.decode(bytes)
                when (val command = envelope.command) {
                    SplitResultDeliveryCommand.DescribePeer -> {
                        ready += reply(envelope, role, "DESCRIBED")
                    }

                    is SplitResultDeliveryCommand.Apply -> {
                        applyOfferResult?.let { return it }
                        applies += envelope
                        offeredApply.countDown()
                        val response =
                            overrideFunds?.invoke(envelope) ?: f.funds
                                .applyMatchingResult(command.result, Instant.ofEpochMilli(123), f.config.settlementCalculator())
                                .let {
                                    reply(envelope, role, it.status.name, it.ack, it.expectedSequence)
                                }
                        if (holdFunds) held += response else ready += response
                    }

                    is SplitResultDeliveryCommand.Progress -> {
                        progressQueries++
                        ready +=
                            overrideProgress?.invoke(envelope)
                                ?: reply(envelope, role, "PROGRESS", f.funds.matchingSettlementAck(command.stream))
                    }

                    is SplitResultDeliveryCommand.RecordAck -> {
                        ackOfferResult?.let { return it }
                        recordedAcks += command.ack
                        val response =
                            overrideRecorded?.invoke(envelope) ?: f.matching.acknowledgeFundsSettled(command.ack).let {
                                reply(envelope, role, it.status.name, command.ack.copy(through = it.fundsSettledThrough))
                            }
                        if (!dropMatchingReplies) ready += response
                    }
                }
                return 1
            }

            override fun poll(): Int {
                ownerThreads += Thread.currentThread()
                check(!closed)
                polls++
                val count = ready.size
                repeat(count) { onReply(ready.removeFirst()) }
                return count
            }

            override fun keepAlive(): Boolean {
                ownerThreads += Thread.currentThread()
                keepAlives++
                return true
            }

            override fun close() {
                ownerThreads += Thread.currentThread()
                closed = true
            }
        }
    }
}
