package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryEnvelope
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
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
import com.exchange.core.trading.FundsDispatchRead
import com.exchange.core.trading.FundsDispatchReadStatus
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.MarketMatchingIntakeLimits
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingClusterManifest
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

/** 네트워크 경계의 단위 검사다. 실제 복제·단편화 증거는 별도 6노드 검사에서 수집한다. */
class FundsOrderDeliveryAgentTest {
    @Test
    fun `매칭 활성 상한의 최종 미실행도 전달 자료만 회수하고 두 예약을 남긴다`() {
        val f = Fixture(activeOrders = 1)
        f.admit(6)
        f.admit(2)
        f.agent.use {
            f.cycles(30)
            assertEquals(1, f.matching.restingOrders(f.market.marketId).size)
            assertEquals(1L, f.matching.lastEngineSequence(f.market.marketId))
            assertEquals(2, f.matching.recoveryState().resultEntries)
            assertEquals(2L, f.pending().matchingAppliedThrough)
            assertTrue(f.pending().entries.isEmpty())
            assertEquals(Balance(f.user, f.quote, Amount(200), Amount(800)), f.funds.currentBalance(f.user, f.quote))
            assertEquals(
                2,
                f.funds
                    .fundsRecoveryState()
                    .dispatch.activeOrders,
            )
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `음수 nanoTime 원점에서도 첫 연결과 주문 전달을 시작한다`() {
        val f = Fixture()
        f.now = -1_000_000_000_000L
        f.admit(6)
        f.agent.use {
            f.cycles(30)
            assertEquals(1L, f.pending().matchingAppliedThrough)
            assertEquals(1, f.matching.recoveryState().resultEntries)
            assertEquals(Balance(f.user, f.quote, Amount(400), Amount(600)), f.funds.currentBalance(f.user, f.quote))
        }
    }

    @Test
    fun `고정 설정과 다른 전송 한도는 연결을 열기 전에 거절한다`() {
        val f = Fixture()
        f.agent.use {
            assertFailsWith<IllegalArgumentException> {
                FundsOrderDeliveryAgent(
                    f.config,
                    f.source,
                    SplitOrderDeliveryEndpoint("/unused-driver", "0=funds:2000", "localhost:2007", 198),
                    SplitOrderDeliveryEndpoint("/unused-driver", "0=matching:3000", "localhost:3007", 199),
                    limits = f.config.deliveryLimits.copy(maxReadEntries = 1),
                    connectionFactory = f.transport,
                )
            }
            assertTrue(f.transport.connections.isEmpty())
            assertTrue(f.source.queries.isEmpty())
        }
    }

    @Test
    fun `ACK1을 받기 전에 두 번째 입력을 보내고 누적 ACK2만 자금 ingress에 기록한다`() {
        val f = Fixture()
        f.admit(6)
        f.admit(2)
        f.transport.holdMatching = true
        f.agent.use { agent ->
            f.cycles(8)
            assertEquals(listOf(1L, 2L), f.transport.applies.map { (it.command as SplitOrderDeliveryCommand.Apply).input.dispatchSequence })
            assertEquals(2, f.matching.recoveryState().resultEntries)
            assertEquals(2, f.pending().entries.size)
            assertTrue(f.transport.recordedAcks.isEmpty())

            // ACK1 유실도 ACK2의 연속 prefix로 확인할 수 있다.
            f.transport.releaseMatching(onlyLast = true)
            f.cycles(10)
            assertEquals(listOf(2L), f.transport.recordedAcks.map { it.through })
            assertEquals(0, f.pending().entries.size)
            assertEquals(2, f.matching.recoveryState().resultEntries)
            assertEquals(Balance(f.user, f.quote, Amount(200), Amount(800)), f.funds.currentBalance(f.user, f.quote))
            val firstAttempt = f.transport.applies.first()
            val input = (firstAttempt.command as SplitOrderDeliveryCommand.Apply).input
            f.transport.connections
                .last { it.role == TradingServiceRole.MATCHING }
                .ready +=
                f.transport.reply(
                    firstAttempt,
                    TradingServiceRole.MATCHING,
                    "APPLIED",
                    FundsMatchingAppliedAck(input.stream, 1, input.context),
                )
            f.cycles(3)
            assertEquals(2L, f.pending().matchingAppliedThrough)
            assertEquals(listOf(2L), f.transport.recordedAcks.map { it.through })
            assertNull(agent.failure)
        }
    }

    @Test
    fun `매칭 응답 유실은 같은 승인 원본 재전송으로 복구하고 예약과 결과를 반복하지 않는다`() {
        val f = Fixture()
        f.admit(6)
        f.transport.holdMatching = true
        f.agent.use {
            f.cycles(8)
            val first =
                (
                    f.transport.applies
                        .single()
                        .command as SplitOrderDeliveryCommand.Apply
                ).input
            f.advance(f.config.deliveryLimits.attemptTimeoutNs + 1)
            f.cycles(1)
            assertTrue(f.transport.connections.all { it.closed })
            f.transport.holdMatching = false
            f.advance(f.config.deliveryLimits.reconnectDelayNs + 1)
            f.cycles(12)
            val repeated =
                (
                    f.transport.applies
                        .last()
                        .command as SplitOrderDeliveryCommand.Apply
                ).input
            assertEquals(first, repeated)
            assertEquals(1, f.matching.recoveryState().resultEntries)
            assertEquals(1L, f.pending().matchingAppliedThrough)
            assertEquals(Balance(f.user, f.quote, Amount(400), Amount(600)), f.funds.currentBalance(f.user, f.quote))
        }
    }

    @Test
    fun `자금 ACK 응답 유실 뒤에는 확정 prefix를 다시 읽고 회수한 payload를 되살리지 않는다`() {
        val f = Fixture()
        f.admit(6)
        f.transport.dropSourceReplies = true
        f.agent.use {
            f.cycles(8)
            assertEquals(1L, f.pending().matchingAppliedThrough)
            assertEquals(0, f.pending().entries.size)
            f.advance(f.config.deliveryLimits.attemptTimeoutNs + 1)
            f.cycles(1)
            f.transport.dropSourceReplies = false
            f.advance(f.config.deliveryLimits.reconnectDelayNs + 1)
            f.cycles(14)
            assertEquals(1, f.transport.applies.size)
            assertTrue(f.source.queries.count { it == 1L } >= 2)
            assertEquals(1, f.matching.recoveryState().resultEntries)
            assertEquals(Balance(f.user, f.quote, Amount(400), Amount(600)), f.funds.currentBalance(f.user, f.quote))
        }
    }

    @Test
    fun `backpressure 중에도 두 egress와 keepalive를 poll하고 건수 창을 넘지 않는다`() {
        val limits = FundsOrderDeliveryLimits(maxInFlightEntries = 2)
        val f = Fixture(limits = limits)
        repeat(5) { f.admit(1, price = 1) }
        f.transport.holdMatching = true
        f.agent.use {
            f.cycles(10)
            assertEquals(2, f.transport.applies.size)
            f.transport.applyOfferResult = Publication.BACK_PRESSURED
            f.transport.releaseMatching()
            f.advance(limits.keepAliveNs + 1)
            f.cycles(1)
            f.advance(limits.ackDelayNs + 1)
            f.cycles(8)
            assertTrue(f.transport.connections.all { it.polls > 0 && it.keepAlives > 0 })
            assertEquals(2, f.transport.applies.size)
            assertEquals(3, f.pending().entries.size)
            f.transport.applyOfferResult = null
            f.transport.holdMatching = false
            f.cycles(15)
            assertEquals(5, f.matching.recoveryState().resultEntries)
            assertEquals(5L, f.pending().matchingAppliedThrough)
            assertEquals(0, f.pending().entries.size)
        }
    }

    @Test
    fun `실제 encoded bytes 창이 건수 창보다 먼저 가득 차면 다음 입력을 보관한다`() {
        val f = Fixture(limits = FundsOrderDeliveryLimits(maxInFlightBytes = 32_768), userName = "😀".repeat(64))
        repeat(64) { f.admit(1, price = 1) }
        f.transport.holdMatching = true
        f.agent.use {
            f.cycles(10)
            val bytes =
                f.transport.applies.sumOf {
                    f.transport.codec
                        .encode(it.correlationId, it.command)
                        .size
                }
            assertTrue(f.transport.applies.size in 1..63)
            assertTrue(bytes <= 32_768)
            assertEquals(64, f.pending().entries.size)
            assertEquals(0L, f.pending().matchingAppliedThrough)
        }
    }

    @Test
    fun `NOT_CONNECTED는 연결을 다시 열고 같은 원본을 사용한다`() {
        val f = Fixture()
        f.admit(6)
        f.transport.applyOfferResult = Publication.NOT_CONNECTED
        f.agent.use {
            f.cycles(8)
            assertTrue(f.transport.applies.isEmpty())
            assertTrue(f.transport.connections.all { it.closed })
            f.transport.applyOfferResult = null
            f.advance(f.config.deliveryLimits.reconnectDelayNs + 1)
            f.cycles(15)
            assertEquals(1, f.transport.applies.size)
            assertEquals(1L, f.pending().matchingAppliedThrough)
            assertEquals(Balance(f.user, f.quote, Amount(400), Amount(600)), f.funds.currentBalance(f.user, f.quote))
        }
    }

    @Test
    fun `팔로워와 재생 중에는 연결하지 않고 세대가 바뀐 뒤 늦은 조회를 버린다`() {
        val f = Fixture()
        f.admit(6)
        f.source.generation = null
        f.source.defer = true
        f.agent.use {
            f.cycles(4)
            assertTrue(f.transport.connections.isEmpty())
            f.source.generation = 1
            f.cycles(6)
            val previous = assertNotNull(f.source.deferred)
            f.source.generation = null
            f.cycles(1)
            assertTrue(previous.isCancelled)
            previous.complete(f.pending())
            f.cycles(2)
            assertTrue(f.transport.applies.isEmpty())
            f.source.generation = 2
            f.cycles(6)
            assertTrue(f.transport.applies.isEmpty())
            assertNotNull(f.source.deferred).complete(f.pending())
            f.cycles(8)
            assertEquals(1, f.transport.applies.size)
            assertEquals(1L, f.pending().matchingAppliedThrough)
        }
    }

    @Test
    fun `BUSY는 뒤쪽 pipeline 응답을 버리고 자금 payload와 예약을 유지한다`() {
        val f = Fixture(resultEntries = 1)
        f.admit(6)
        f.admit(2)
        f.agent.use {
            f.cycles(12)
            assertEquals(1, f.matching.recoveryState().resultEntries)
            assertEquals(Balance(f.user, f.quote, Amount(200), Amount(800)), f.funds.currentBalance(f.user, f.quote))
            assertEquals(1L, f.pending().matchingAppliedThrough)
            // 앞선 ACK1은 확정하고, BUSY인 두 번째 원본만 backoff 뒤 다시 보낸다.
            f.advance(f.config.deliveryLimits.busyMaxDelayNs + 1)
            f.cycles(12)
            assertTrue(f.pending().entries.any { it.dispatchSequence == 2L })
            assertTrue(f.transport.recordedAcks.none { it.through >= 2 })
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `GAP 뒤 이전 pipeline의 성공 응답으로 원본을 회수하지 않는다`() {
        val f = Fixture()
        f.admit(6)
        f.admit(2)
        var inject = true
        f.transport.overrideMatching = { envelope ->
            if (inject) {
                val input = (envelope.command as SplitOrderDeliveryCommand.Apply).input
                if (input.dispatchSequence == 1L) {
                    f.transport.reply(envelope, TradingServiceRole.MATCHING, "GAP", expected = 1)
                } else {
                    inject = false
                    f.transport.reply(
                        envelope,
                        TradingServiceRole.MATCHING,
                        "APPLIED",
                        FundsMatchingAppliedAck(input.stream, 2, input.context),
                    )
                }
            } else {
                null
            }
        }
        f.agent.use {
            f.cycles(5)
            assertEquals(2, f.pending().entries.size)
            assertTrue(f.transport.recordedAcks.isEmpty())
            f.cycles(12)
            assertEquals(2, f.matching.recoveryState().resultEntries)
            assertEquals(2L, f.pending().matchingAppliedThrough)
        }
    }

    @Test
    fun `source에서 이미 회수한 GAP 위치는 복구 불일치로 드러내고 건너뛰지 않는다`() {
        val f = Fixture()
        f.admit(6)
        f.admit(2)
        val first = f.pending().entries.first()
        f.funds.acknowledgeMatchingApplied(FundsMatchingAppliedAck(first.stream, 1, first.context))
        f.transport.overrideMatching = { envelope -> f.transport.reply(envelope, TradingServiceRole.MATCHING, "GAP", expected = 1) }
        f.agent.use {
            f.cycles(12)
            assertNotNull(f.agent.failure)
            assertTrue(
                f.agent.diagnostic
                    .orEmpty()
                    .contains("durable source prefix"),
            )
            assertEquals(1L, f.pending().matchingAppliedThrough)
            assertTrue(f.transport.recordedAcks.isEmpty())
        }
    }

    @Test
    fun `작은 조회 예산은 requiredBytes 오류로 보이고 무한 빈 batch로 재시도하지 않는다`() {
        val f = Fixture()
        f.admit(6)
        f.source.overrideRead = {
            FundsDispatchRead(
                FundsDispatchReadStatus.BATCH_TOO_SMALL,
                issued = 1,
                requiredBytes = f.config.deliveryLimits.maxReadBytes + 1,
                nextSequence = 1,
            )
        }
        f.agent.use {
            f.cycles(12)
            assertNotNull(f.agent.failure)
            assertTrue(
                f.agent.diagnostic
                    .orEmpty()
                    .contains("requiredBytes="),
            )
            assertEquals(1, f.source.queries.size)
            assertTrue(f.transport.applies.isEmpty())
            assertEquals(1, f.pending().entries.size)
        }
    }

    @Test
    fun `미래 ACK와 순번 고갈은 성공 환류 없이 해당 stream을 중단한다`() {
        for (code in listOf("APPLIED", "SEQUENCE_EXHAUSTED")) {
            val f = Fixture()
            f.admit(6)
            f.transport.overrideMatching = { envelope ->
                val input = (envelope.command as SplitOrderDeliveryCommand.Apply).input
                f.transport.reply(
                    envelope,
                    TradingServiceRole.MATCHING,
                    code,
                    if (code == "APPLIED") FundsMatchingAppliedAck(input.stream, 2, input.context) else null,
                )
            }
            f.agent.use {
                f.cycles(12)
                assertNotNull(f.agent.failure)
                assertTrue(f.transport.recordedAcks.isEmpty())
                assertEquals(1, f.pending().entries.size)
                assertEquals(1, f.transport.applies.size)
            }
        }
    }

    @Test
    fun `Long 마지막 순번 뒤에는 null cursor를 유지하고 음수나 영으로 wrap하지 않는다`() {
        val f = Fixture()
        f.admit(6)
        val finalInput =
            f
                .pending()
                .entries
                .single()
                .copy(dispatchSequence = Long.MAX_VALUE)
        f.source.overrideRead = { from ->
            if (from == 1L) {
                FundsDispatchRead(
                    FundsDispatchReadStatus.PRUNED,
                    matchingAppliedThrough = Long.MAX_VALUE - 1,
                    issued = Long.MAX_VALUE,
                    nextSequence = Long.MAX_VALUE,
                )
            } else {
                FundsDispatchRead(
                    FundsDispatchReadStatus.BATCH,
                    listOf(finalInput),
                    matchingAppliedThrough = Long.MAX_VALUE - 1,
                    issued = Long.MAX_VALUE,
                    nextSequence = null,
                )
            }
        }
        f.transport.overrideMatching = { envelope ->
            f.transport.reply(
                envelope,
                TradingServiceRole.MATCHING,
                "APPLIED",
                FundsMatchingAppliedAck(finalInput.stream, Long.MAX_VALUE, finalInput.context),
            )
        }
        f.transport.overrideSourceAck = { envelope ->
            f.transport.reply(
                envelope,
                TradingServiceRole.FUNDS,
                "ACKNOWLEDGED",
                FundsMatchingAppliedAck(finalInput.stream, Long.MAX_VALUE, finalInput.context),
            )
        }
        f.agent.use {
            f.cycles(20)
            assertEquals(listOf(1L, Long.MAX_VALUE), f.source.queries)
            assertEquals(listOf(Long.MAX_VALUE), f.transport.recordedAcks.map { it.through })
            assertNull(f.agent.failure)
        }
    }

    @Test
    fun `다른 honest peer의 지문은 source 회수 전에 거절한다`() {
        val f = Fixture()
        f.admit(6)
        f.transport.wrongTopology = true
        f.agent.use {
            assertFailsWith<IllegalArgumentException> { f.cycles(5) }
            assertTrue(f.transport.applies.isEmpty())
            assertTrue(f.transport.recordedAcks.isEmpty())
            assertEquals(1, f.pending().entries.size)
        }
        assertTrue(f.transport.connections.all { it.closed })
    }

    @Test
    fun `연결 생성 offer poll keepalive close는 전송 스레드 하나가 소유하고 종료한다`() {
        val f = Fixture()
        f.admit(6)
        f.agent.start()
        try {
            assertTrue(f.transport.offeredApply.await(3, TimeUnit.SECONDS))
        } finally {
            f.agent.close()
        }
        assertEquals(1, f.transport.ownerThreads.size)
        val owner = f.transport.ownerThreads.single()
        assertEquals("funds-order-delivery", owner.name)
        assertFalse(owner.isAlive)
        assertTrue(f.transport.connections.all { it.closed })
        assertNull(f.agent.failure)
    }

    private class Fixture(
        limits: FundsOrderDeliveryLimits = FundsOrderDeliveryLimits(),
        resultEntries: Int = 100,
        activeOrders: Int = 8_192,
        userName: String = "buyer",
    ) {
        val quote = AssetId("QUOTE")
        val user = UserId(userName)
        val market = MarketDefinition(MarketId("A-QUOTE"), AssetId("A"), quote, 0)
        val context =
            TradingMarketContext(
                market,
                TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)),
                0,
            )
        private val manifest =
            TradingClusterManifest(
                ClusterLineage(1, 2),
                "worker-unit",
                "0".repeat(64),
                "1".repeat(64),
                7,
                listOf(context),
                TradingResourceLimits(),
            )
        private val topology = TradingRoleTopology(manifest, listOf(TradingMarketAssignment(market.marketId, ClusterLineage(3, 4), 1)))
        private val fundsDeclaration =
            TradingRoleDeclaration(
                TradingServiceRole.FUNDS,
                manifest.lineage,
                7,
                listOf(context),
                mapOf(market.marketId to 1L),
            )
        private val matchingDeclaration =
            TradingRoleDeclaration(
                TradingServiceRole.MATCHING,
                ClusterLineage(3, 4),
                7,
                listOf(context),
                mapOf(market.marketId to 1L),
            )
        val config =
            SplitTradingConfiguration(
                topology,
                fundsDeclaration,
                matchingDeclaration,
                matchingLimits =
                    MatchingIntakeLimits(
                        mapOf(
                            market.marketId to MarketMatchingIntakeLimits(maxResultEntries = resultEntries, maxActiveOrders = activeOrders),
                        ),
                        maxResultEntries = resultEntries,
                        maxActiveOrders = activeOrders,
                    ),
                deliveryLimits = limits,
            )
        val funds =
            config.funds().also {
                it.initialize(manifest)
                it.prepareDevelopmentBalance(OpeningBalance("unit-opening", user, quote, Amount(1_000)), Instant.EPOCH)
                it.rotateEpoch(user, 0, 0)
            }
        val matching = config.matching().also { it.initialize() }
        val stream = config.dispatchConfiguration.stream(market.marketId)
        val source = Source(this)
        val transport = Transport(this)
        var now = 1L
        private var nextToken = 1L
        val agent =
            FundsOrderDeliveryAgent(
                config,
                source,
                SplitOrderDeliveryEndpoint("/unused-driver", "0=funds:2000", "localhost:2007", 198),
                SplitOrderDeliveryEndpoint("/unused-driver", "0=matching:3000", "localhost:3007", 199),
                connectionFactory = transport,
                nanoTime = { now },
            )

        fun admit(
            quantity: Long,
            price: Long = 100,
        ) {
            val reply =
                funds.admit(
                    RequestToken(user, 0, 1, nextToken++),
                    OrderIntent(market.marketId, 7, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity)),
                    Instant.EPOCH,
                )
            assertIs<AdmissionReply.Recorded>(reply, "fixture admission: $reply")
        }

        fun pending(): FundsDispatchRead {
            val first = funds.pendingDispatch(stream, 1, 64, 256L * 1_024)
            return if (first.status == FundsDispatchReadStatus.PRUNED && first.matchingAppliedThrough < Long.MAX_VALUE) {
                funds.pendingDispatch(stream, first.matchingAppliedThrough + 1, 64, 256L * 1_024)
            } else {
                first
            }
        }

        fun cycles(count: Int) {
            repeat(count) { agent.doWork() }
        }

        fun advance(ns: Long) {
            now += ns
        }
    }

    private class Source(
        val f: Fixture,
    ) : FundsDispatchSource {
        var generation: Long? = 1
        var defer = false
        var deferred: CompletableFuture<FundsDispatchRead>? = null
        var overrideRead: ((Long) -> FundsDispatchRead)? = null
        val queries = mutableListOf<Long>()

        override fun activeGeneration() = generation

        override fun readDispatch(
            generation: Long,
            stream: FundsDispatchStream,
            fromSequence: Long,
            maxEntries: Int,
            maxBytes: Long,
        ): CompletableFuture<FundsDispatchRead> {
            queries += fromSequence
            assertEquals(this.generation, generation)
            if (defer) return CompletableFuture<FundsDispatchRead>().also { deferred = it }
            return CompletableFuture.completedFuture(
                overrideRead?.invoke(fromSequence) ?: f.funds.pendingDispatch(stream, fromSequence, maxEntries, maxBytes),
            )
        }
    }

    private class Transport(
        val f: Fixture,
    ) : SplitOrderDeliveryConnectionFactory {
        val codec = SplitOrderDeliveryCodec()
        val connections = mutableListOf<Connection>()
        val ownerThreads = ConcurrentHashMap.newKeySet<Thread>()
        val offeredApply = CountDownLatch(1)
        val applies = mutableListOf<SplitOrderDeliveryEnvelope>()
        val recordedAcks = mutableListOf<FundsMatchingAppliedAck>()
        var holdMatching = false
        var dropSourceReplies = false
        var wrongTopology = false
        var applyOfferResult: Long? = null
        var overrideMatching: ((SplitOrderDeliveryEnvelope) -> SplitOrderDeliveryReply?)? = null
        var overrideSourceAck: ((SplitOrderDeliveryEnvelope) -> SplitOrderDeliveryReply?)? = null

        override fun begin(
            endpoint: SplitOrderDeliveryEndpoint,
            timeoutNs: Long,
            onReply: (SplitOrderDeliveryReply) -> Unit,
        ): SplitOrderDeliveryConnectAttempt {
            ownerThreads += Thread.currentThread()
            val connection = Connection(if (endpoint.clusterId == 198) TradingServiceRole.FUNDS else TradingServiceRole.MATCHING, onReply)
            connections += connection
            return object : SplitOrderDeliveryConnectAttempt {
                override fun poll(): SplitOrderDeliveryConnection {
                    ownerThreads += Thread.currentThread()
                    return connection
                }

                override fun close() {
                    ownerThreads += Thread.currentThread()
                }
            }
        }

        fun reply(
            envelope: SplitOrderDeliveryEnvelope,
            role: TradingServiceRole,
            code: String,
            ack: FundsMatchingAppliedAck? = null,
            expected: Long? = null,
        ) = SplitOrderDeliveryReply(
            envelope.correlationId,
            code,
            role,
            if (role == TradingServiceRole.FUNDS) f.config.fundsLineage else f.config.matchingLineage,
            if (wrongTopology) "f".repeat(64) else f.config.topologySha256,
            1,
            ack,
            expected,
            lifecycle = "RUNNING",
        )

        fun releaseMatching(onlyLast: Boolean = false) {
            connections.filter { it.role == TradingServiceRole.MATCHING && !it.closed }.forEach { connection ->
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
            val onReply: (SplitOrderDeliveryReply) -> Unit,
        ) : SplitOrderDeliveryConnection {
            var closed = false
            var polls = 0
            var keepAlives = 0
            val ready = ArrayDeque<SplitOrderDeliveryReply>()
            val held = mutableListOf<SplitOrderDeliveryReply>()
            override val isClosed get() = closed

            override fun offer(bytes: ByteArray): Long {
                ownerThreads += Thread.currentThread()
                check(!closed)
                val envelope = codec.decode(bytes)
                when (val command = envelope.command) {
                    SplitOrderDeliveryCommand.DescribePeer -> {
                        ready += reply(envelope, role, "DESCRIBED")
                    }

                    is SplitOrderDeliveryCommand.Apply -> {
                        applyOfferResult?.let { return it }
                        applies += envelope
                        offeredApply.countDown()
                        val response =
                            overrideMatching?.invoke(envelope) ?: f.matching.apply(command.input).let {
                                reply(envelope, role, it.status.name, it.ack, it.expectedSequence)
                            }
                        if (holdMatching) held += response else ready += response
                    }

                    is SplitOrderDeliveryCommand.RecordAck -> {
                        recordedAcks += command.ack
                        val response =
                            overrideSourceAck?.invoke(envelope) ?: f.funds.acknowledgeMatchingApplied(command.ack).let {
                                reply(envelope, role, it.status.name, command.ack.copy(through = it.matchingAppliedThrough))
                            }
                        if (!dropSourceReplies) ready += response
                    }

                    else -> {
                        error("unexpected worker command")
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
