package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
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
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchReadStatus
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.FrameDescriptor
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.AgentTerminationException
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실제 역할 소유자를 callback에서 적용한다. 송수신과 Archive 대신 바이트 경계만 통제하는 단위 검사다. */
class SplitTradingClusterAdapterTest {
    private val codec = SplitOrderDeliveryCodec()
    private val user = UserId("buyer")
    private val quote = AssetId("QUOTE")
    private val market = MarketDefinition(MarketId("BASE-QUOTE"), AssetId("BASE"), quote, 0)
    private val configuration = configuration()
    private val stream = configuration.dispatchConfiguration.stream(market.marketId)
    private val timestamp = 1_793_664_000_000L

    @Test
    fun `자금 callback은 예약과 전달만 기록하고 적용 ACK는 payload만 회수한다`() {
        val source = fixture(TradingServiceRole.FUNDS)
        assertNull(source.service.activeGeneration())
        assertEquals("NOT_READY", source.command(control(BoundedTradingCommand.Rotate(user, 0, 0))).code)
        prepare(source)
        assertEquals("ACCEPTED", source.command(admit(1, 6)).code)

        assertEquals(Balance(user, quote, Amount(400), Amount(600)), source.funds().currentBalance(user, quote))
        val first = source.read(1).entries.single()
        val saved = source.funds().fundsRecoveryState()
        assertEquals(1, saved.dispatch.dispatchEntries)
        assertEquals(1, saved.financial.liveOrders.size)
        assertEquals(
            0L,
            saved.financial.markets
                .single()
                .executedThrough,
        )
        assertTrue(assertNotNull(saved.results).receipts.isEmpty())
        assertNull(
            source.service.javaClass
                .getDeclaredField("matching")
                .also { it.isAccessible = true }
                .get(source.service),
        )

        val ack = FundsMatchingAppliedAck(first.stream, 1, first.context)
        val recorded = source.command(SplitOrderDeliveryCommand.RecordAck(ack))
        assertEquals("ACKNOWLEDGED", recorded.code)
        assertEquals(1L, recorded.ack?.through)
        assertEquals("PRUNED", source.read(1).status.name)
        assertEquals(
            0,
            source
                .funds()
                .fundsRecoveryState()
                .dispatch.dispatchEntries,
        )
        assertEquals(
            1,
            source
                .funds()
                .fundsRecoveryState()
                .financial.liveOrders.size,
        )
        assertEquals(Balance(user, quote, Amount(400), Amount(600)), source.funds().currentBalance(user, quote))
        assertEquals("ACKNOWLEDGED", source.command(SplitOrderDeliveryCommand.RecordAck(ack)).code)
        assertEquals("FUTURE_ACK", source.command(SplitOrderDeliveryCommand.RecordAck(ack.copy(through = 2))).code)
        assertEquals(1L, source.read(2).matchingAppliedThrough)
        assertTrue(source.failures.isEmpty())
    }

    @Test
    fun `매칭 callback은 순서와 중복을 판정하고 전체 결과를 원격 응답에 넣지 않는다`() {
        val source = fixture(TradingServiceRole.FUNDS)
        prepare(source)
        source.command(admit(1, 6))
        source.command(admit(2, 2))
        val inputs = source.read(1).entries
        val target = fixture(TradingServiceRole.MATCHING)
        assertEquals("NOT_READY", target.command(SplitOrderDeliveryCommand.Apply(inputs.first())).code)
        target.initialize()
        val gap = target.command(SplitOrderDeliveryCommand.Apply(inputs[1]))
        assertEquals("GAP", gap.code)
        assertEquals(1L, gap.expectedSequence)
        assertNull(gap.ack)
        assertEquals(0L, target.matching().lastEngineSequence(market.marketId))

        val applied = target.command(SplitOrderDeliveryCommand.Apply(inputs.first()))
        assertEquals("APPLIED", applied.code)
        assertEquals(1L, applied.ack?.through)
        assertNull(applied.control)
        assertTrue(codec.encodeReply(applied).size < 1024)
        assertEquals("DUPLICATE", target.command(SplitOrderDeliveryCommand.Apply(inputs.first())).code)
        val conflict = inputs.first().copy(order = inputs.first().order.copy(quantity = Quantity(7)))
        assertEquals("CONTENT_CONFLICT", target.command(SplitOrderDeliveryCommand.Apply(conflict)).code)
        assertEquals(1L, target.matching().lastEngineSequence(market.marketId))
        assertEquals(1, target.matching().restingOrders(market.marketId).size)
        assertEquals(1, target.matching().recoveryState().resultEntries)
        assertEquals("APPLIED", target.command(SplitOrderDeliveryCommand.Apply(inputs[1])).code)
        assertEquals(2, target.matching().recoveryState().resultEntries)
        assertNull(
            target.service.javaClass
                .getDeclaredField("funds")
                .also { it.isAccessible = true }
                .get(target.service),
        )
    }

    @Test
    fun `조회는 서비스 유휴 작업에서만 완료하고 큐와 리더 세대를 제한한다`() {
        val source = fixture(TradingServiceRole.FUNDS)
        prepare(source)
        source.command(admit(1, 6))
        val generation = assertNotNull(source.service.activeGeneration())
        val waiting = List(16) { source.service.readDispatch(generation, stream, 1, 1, 4096) }
        assertTrue(waiting.none { it.isDone })
        val overflow = source.service.readDispatch(generation, stream, 1, 1, 4096)
        assertFailsWith<CompletionException> { overflow.join() }
        source.role(Cluster.Role.FOLLOWER)
        assertNull(source.service.activeGeneration())
        waiting.forEach { assertFailsWith<CompletionException> { it.join() } }
        source.role(Cluster.Role.LEADER)
        val current = assertNotNull(source.service.activeGeneration())
        assertTrue(current > generation)
        assertFailsWith<CompletionException> { source.service.readDispatch(generation, stream, 1, 1, 4096).join() }
        assertFailsWith<CompletionException> { source.service.readDispatch(current, stream, 1, 65, 4096).join() }
        val tooSmall = source.service.readDispatch(current, stream, 1, 1, 1)
        assertEquals(1, source.service.doBackgroundWork(0))
        assertEquals(FundsDispatchReadStatus.BATCH_TOO_SMALL, tooSmall.join().status)
        assertTrue(tooSmall.join().requiredBytes > 1)
        assertEquals(
            1,
            source
                .funds()
                .fundsRecoveryState()
                .dispatch.dispatchEntries,
        )
        assertEquals(Balance(user, quote, Amount(400), Amount(600)), source.funds().currentBalance(user, quote))
        val pending = source.service.readDispatch(current, stream, 1, 1, 4096)
        source.service.onTerminate(source.cluster)
        assertFailsWith<CompletionException> { pending.join() }
        assertNull(source.service.activeGeneration())
    }

    @Test
    fun `snapshot 중에는 전송 세대와 재진입 조회를 막고 완료 뒤 새 세대를 만든다`() {
        val source = fixture(TradingServiceRole.FUNDS)
        prepare(source)
        source.command(admit(1, 6))
        val generation = assertNotNull(source.service.activeGeneration())
        val pending = source.service.readDispatch(generation, stream, 1, 1, 4096)
        val recording =
            source.snapshot {
                assertNull(source.service.activeGeneration())
                assertEquals(0, source.service.doBackgroundWork(0))
                assertFailsWith<CompletionException> { pending.join() }
            }
        assertTrue(recording.isNotEmpty())
        assertTrue(assertNotNull(source.service.activeGeneration()) > generation)
        assertEquals(1, source.read(1).entries.size)
    }

    @Test
    fun `자금 snapshot과 ACK tail 재생은 보유 예약과 회수 경계를 복원한다`() {
        val original = fixture(TradingServiceRole.FUNDS)
        prepare(original)
        original.command(admit(1, 6))
        val input = original.read(1).entries.single()
        val snapshot = original.snapshot()
        val restored = fixture(TradingServiceRole.FUNDS, start = false, position = original.position)
        restored.service.onStart(restored.cluster, snapshotImage(snapshot))
        assertTrue(restored.service.tradingReady)
        assertNull(restored.service.activeGeneration(), "onStart의 snapshot 복원은 재생 완료나 송신 자격이 아니다")
        val ack = SplitOrderDeliveryCommand.RecordAck(FundsMatchingAppliedAck(input.stream, 1, input.context))
        restored.replay(ack)
        assertTrue(restored.replies.isEmpty())
        assertEquals(
            0,
            restored
                .funds()
                .fundsRecoveryState()
                .dispatch.dispatchEntries,
        )
        assertEquals(Balance(user, quote, Amount(400), Amount(600)), restored.funds().currentBalance(user, quote))
        assertEquals(
            0L,
            restored
                .funds()
                .progress()
                .markets
                .single()
                .executedThrough,
        )
        restored.role(Cluster.Role.LEADER)
        assertEquals(1L, restored.read(1).matchingAppliedThrough)
        val afterAck = restored.snapshot()
        val restoredAfterAck = fixture(TradingServiceRole.FUNDS, start = false, position = restored.position)
        restoredAfterAck.service.onStart(restoredAfterAck.cluster, snapshotImage(afterAck))
        assertEquals(
            0,
            restoredAfterAck
                .funds()
                .fundsRecoveryState()
                .dispatch.dispatchEntries,
        )
        assertEquals(
            1,
            restoredAfterAck
                .funds()
                .fundsRecoveryState()
                .financial.liveOrders.size,
        )
        assertEquals(Balance(user, quote, Amount(400), Amount(600)), restoredAfterAck.funds().currentBalance(user, quote))
    }

    @Test
    fun `매칭 snapshot과 입력 tail 재생 뒤 같은 원본은 추가 매칭하지 않는다`() {
        val source = fixture(TradingServiceRole.FUNDS)
        prepare(source)
        source.command(admit(1, 6))
        source.command(admit(2, 2))
        val inputs = source.read(1).entries
        val target = fixture(TradingServiceRole.MATCHING)
        target.initialize()
        target.command(SplitOrderDeliveryCommand.Apply(inputs[0]))
        val recording = target.snapshot()
        val restored = fixture(TradingServiceRole.MATCHING, start = false, position = target.position)
        restored.service.onStart(restored.cluster, snapshotImage(recording))
        restored.replay(SplitOrderDeliveryCommand.Apply(inputs[1]))
        assertTrue(restored.replies.isEmpty())
        restored.role(Cluster.Role.LEADER)
        inputs.forEach { assertEquals("DUPLICATE", restored.command(SplitOrderDeliveryCommand.Apply(it)).code) }
        assertEquals(2, restored.matching().restingOrders(market.marketId).size)
        assertEquals(2L, restored.matching().lastEngineSequence(market.marketId))
        assertEquals(2, restored.matching().recoveryState().resultEntries)
        assertEquals(
            2L,
            restored
                .matching()
                .recoveryState()
                .streams
                .single()
                .appliedThrough,
        )
    }

    @Test
    fun `설정 충돌 잘못된 역할과 잘린 wire는 업무 효과나 성공 응답을 만들지 않는다`() {
        val source = fixture(TradingServiceRole.FUNDS)
        assertEquals("DESCRIBED", source.command(SplitOrderDeliveryCommand.DescribePeer).code)
        assertEquals("UNINITIALIZED", source.replies.last().lifecycle)
        assertEquals("TOPOLOGY_CONFLICT", source.command(SplitOrderDeliveryCommand.InitializeRole("f".repeat(64))).code)
        assertFalse(source.service.tradingReady)
        source.initialize()
        assertEquals("ALREADY_INITIALIZED", source.command(SplitOrderDeliveryCommand.InitializeRole(configuration.topologySha256)).code)
        val replies = source.replies.size
        source.service.onSessionMessage(source.session, timestamp, UnsafeBuffer(ByteArray(8)), 0, 8, Header(0, 0))
        assertEquals(replies, source.replies.size)
        assertEquals(1L, source.service.malformedMessages)
        assertEquals(0L, source.funds().progress().lastAdmissionSequence)
        val target = fixture(TradingServiceRole.MATCHING)
        assertEquals("WRONG_ROLE", target.command(control(BoundedTradingCommand.Progress)).code)
        assertEquals("WRONG_ROLE", target.command(control(BoundedTradingCommand.Rotate(user, 0, 0))).code)
        assertFalse(target.service.tradingReady)
    }

    @Test
    fun `초기화 전 snapshot과 다른 framework 위치 복원은 정상 완료하지 않는다`() {
        val uninitialized = fixture(TradingServiceRole.FUNDS)
        assertFailsWith<AgentTerminationException> { uninitialized.snapshot() }
        assertFalse(uninitialized.service.started)
        assertNotNull(uninitialized.service.failure)
        assertEquals(1, uninitialized.failures.size)
        val original = fixture(TradingServiceRole.FUNDS)
        original.initialize()
        val recording = original.snapshot()
        val wrongPosition = fixture(TradingServiceRole.FUNDS, start = false, position = original.position + 32)
        assertFailsWith<AgentTerminationException> { wrongPosition.service.onStart(wrongPosition.cluster, snapshotImage(recording)) }
        assertFalse(wrongPosition.service.started)
        assertNull(wrongPosition.service.activeGeneration())
    }

    private fun prepare(fixture: Fixture) {
        fixture.initialize()
        val opening = OpeningBalance("opening", user, quote, Amount(1_000))
        val funding = DevelopmentBalanceCommandPayload(opening, "unused-legacy-id", Instant.EPOCH)
        assertEquals("PREPARED", fixture.command(control(BoundedTradingCommand.Funding(funding))).code)
        assertEquals("ISSUED", fixture.command(control(BoundedTradingCommand.Rotate(user, 0, 0))).code)
    }

    private fun admit(
        sequence: Long,
        quantity: Long,
    ) = control(
        BoundedTradingCommand.Admit(
            RequestToken(user, 0, 1, sequence),
            OrderIntent(market.marketId, 7, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(quantity)),
        ),
    )

    private fun control(command: BoundedTradingCommand) = SplitOrderDeliveryCommand.Control(command)

    private fun fixture(
        role: TradingServiceRole,
        start: Boolean = true,
        position: Long = 0,
    ): Fixture {
        val fixture = Fixture(role, position)
        if (start) {
            fixture.service.onStart(fixture.cluster, null)
            fixture.role(Cluster.Role.LEADER)
        }
        return fixture
    }

    private inner class Fixture(
        role: TradingServiceRole,
        var position: Long,
    ) {
        val cluster = mock(Cluster::class.java)
        val session = mock(ClientSession::class.java)
        val replies = mutableListOf<SplitOrderDeliveryReply>()
        val failures = mutableListOf<Throwable>()
        val service = SplitTradingClusterAdapter(role, configuration, failures::add)
        private var correlation = 0L

        init {
            `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
            `when`(cluster.role()).thenReturn(Cluster.Role.FOLLOWER)
            `when`(cluster.logPosition()).thenAnswer { position }
            `when`(cluster.idleStrategy()).thenReturn(mock(IdleStrategy::class.java))
            doAnswer { invocation ->
                val buffer = invocation.getArgument<DirectBuffer>(0)
                val bytes = ByteArray(invocation.getArgument<Int>(2))
                buffer.getBytes(invocation.getArgument(1), bytes)
                replies.add(codec.decodeReply(bytes))
                1L
            }.`when`(session).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
        }

        fun role(role: Cluster.Role) {
            `when`(cluster.role()).thenReturn(role)
            service.onRoleChange(role)
        }

        fun initialize() {
            assertEquals("INITIALIZED", command(SplitOrderDeliveryCommand.InitializeRole(configuration.topologySha256)).code)
        }

        fun command(command: SplitOrderDeliveryCommand): SplitOrderDeliveryReply {
            replay(command)
            return replies.last()
        }

        fun replay(command: SplitOrderDeliveryCommand) {
            val bytes = codec.encode(++correlation, command)
            position += 64
            // 실제 ingress callback처럼 offset 앞의 바이트를 건드리지 않고 본문 구간만 해석한다.
            val frame = ByteArray(bytes.size + 32)
            bytes.copyInto(frame, 32)
            service.onSessionMessage(session, timestamp, UnsafeBuffer(frame), 32, bytes.size, Header(0, 0))
        }

        fun read(from: Long) =
            service
                .readDispatch(assertNotNull(service.activeGeneration()), stream, from, 64, 256L * 1024)
                .also {
                    assertFalse(it.isDone)
                    service.doBackgroundWork(0)
                }.join()

        // 시험 callback에서만 사용하며 실행 제품에는 전체 owner 조회 입구를 추가하지 않는다.
        fun funds(): BoundedFundingState = owner("funds")

        fun matching(): ReservedMatchingState = owner("matching")

        private inline fun <reified T> owner(name: String): T =
            service.javaClass.getDeclaredField(name).let {
                it.isAccessible = true
                it.get(service) as T
            }

        fun snapshot(duringWrite: () -> Unit = {}): List<ByteArray> {
            val chunks = mutableListOf<ByteArray>()
            val publication = mock(ExclusivePublication::class.java)
            doAnswer { invocation ->
                duringWrite()
                val bytes = ByteArray(invocation.getArgument<Int>(2))
                invocation.getArgument<DirectBuffer>(0).getBytes(invocation.getArgument(1), bytes)
                chunks.add(bytes)
                1L
            }.`when`(publication).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
            service.onTakeSnapshot(publication)
            return chunks
        }
    }

    private fun snapshotImage(chunks: List<ByteArray>): Image {
        val image = mock(Image::class.java)
        var index = 0
        `when`(image.isEndOfStream).thenAnswer { index == chunks.size }
        val header = mock(Header::class.java)
        `when`(header.flags()).thenReturn(FrameDescriptor.UNFRAGMENTED.toByte())
        doAnswer { invocation ->
            if (index == chunks.size) {
                0
            } else {
                val bytes = chunks[index++]
                invocation.getArgument<FragmentHandler>(0).onFragment(UnsafeBuffer(bytes), 0, bytes.size, header)
                1
            }
        }.`when`(image).poll(any(FragmentHandler::class.java), anyInt())
        return image
    }

    private fun configuration(): SplitTradingConfiguration {
        val context =
            TradingMarketContext(
                market,
                TradingFeePolicySnapshot(
                    FeeProductType.SPOT,
                    FeeTier.NORMAL,
                    1,
                    MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO),
                ),
                0,
            )
        val manifest =
            TradingClusterManifest(
                ClusterLineage(10, 11),
                "split-service-test",
                "0".repeat(64),
                "1".repeat(64),
                7,
                listOf(context),
                TradingResourceLimits(),
            )
        val matchingLineage = ClusterLineage(20, 21)
        val topology = TradingRoleTopology(manifest, listOf(TradingMarketAssignment(market.marketId, matchingLineage, 1)))

        fun declaration(
            role: TradingServiceRole,
            lineage: ClusterLineage,
        ) = TradingRoleDeclaration(
            role,
            lineage,
            7,
            listOf(context),
            mapOf(market.marketId to 1),
        )
        return SplitTradingConfiguration(
            topology,
            declaration(TradingServiceRole.FUNDS, manifest.lineage),
            declaration(TradingServiceRole.MATCHING, matchingLineage),
        )
    }
}
