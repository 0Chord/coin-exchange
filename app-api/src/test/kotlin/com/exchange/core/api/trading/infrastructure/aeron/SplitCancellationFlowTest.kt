package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
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
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchLimits
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.FundsRoleRecoveryState
import com.exchange.core.trading.MarketFundsDispatchLimits
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.TradingClusterManifest
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
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실제 wire2 바이트와 역할 callback의 연결 검사다. 실제 Archive와 전송은 Docker 사례가 검사한다. */
class SplitCancellationFlowTest {
    private val buyer = UserId("buyer")
    private val seller = UserId("seller")
    private val quote = AssetId("QUOTE")
    private val base = AssetId("BASE")
    private val market = MarketDefinition(MarketId("BASE-QUOTE"), base, quote, 0)
    private val configuration = configuration()
    private val codec = SplitOrderDeliveryCodec(2)
    private val resultCodec = SplitResultDeliveryCodec(2)
    private val timestamp = 1_793_664_000_000L

    @Test
    fun `C01 wire2 취소는 남은 예약200을 한 번 반환하고 최초 응답을 보존한다`() {
        val funds = preparedFunds()
        val matching = Fixture(TradingServiceRole.MATCHING).also { it.initialize() }
        val buy = admit(funds, buyer, 1, Side.BUY, 3)
        settle(funds, matching, match(matching, buy))
        val sell = admit(funds, seller, 1, Side.SELL, 1)
        settle(funds, matching, match(matching, sell))
        balance(funds, 700, 200)

        val request = cancel(buy)
        val first = funds.command(request)
        assertEquals("ACCEPTED", first.code)
        assertEquals(3L, assertNotNull(first.cancellation).inputSequence)
        assertEquals(buy.order.orderId, first.cancellation.targetOrderId)
        balance(funds, 700, 200)
        assertEquals(first.cancellation, funds.command(request).cancellation)
        val cancelInput = funds.read(3).single()
        assertTrue(cancelInput.isCancellation)
        val applied = matching.command(SplitOrderDeliveryCommand.Apply(cancelInput))
        assertEquals("APPLIED", applied.code)
        assertEquals("DUPLICATE", matching.command(SplitOrderDeliveryCommand.Apply(cancelInput)).code)
        val result = matching.results(3).single()
        assertEquals(listOf(OrderCancelled(market.marketId, 3, buy.order.orderId, buyer, Quantity(2))), result.events)
        assertTrue(matching.matching().restingOrders(market.marketId).isEmpty())
        balance(funds, 700, 200)

        val settled = funds.resultCommand(SplitResultDeliveryCommand.Apply(result))
        assertEquals("APPLIED", settled.code)
        assertEquals(3L, assertNotNull(settled.ack).through)
        balance(funds, 900, 0)
        val saved = funds.funds().fundsRecoveryState()
        assertEquals(2L, saved.financial.lastAdmissionSequence)
        assertEquals(1L, saved.lastCancellationSequence)
        assertTrue(saved.cancellations.isEmpty())
        assertEquals("DUPLICATE", funds.resultCommand(SplitResultDeliveryCommand.Apply(result), timestamp + 10_000).code)
        unchanged(funds, saved)
        assertEquals(first.cancellation, funds.command(request).cancellation)
        assertEquals("ACKNOWLEDGED", funds.command(SplitOrderDeliveryCommand.RecordAck(assertNotNull(applied.ack))).code)
        assertEquals("ACKNOWLEDGED", matching.resultCommand(SplitResultDeliveryCommand.RecordAck(settled.ack)).code)
        assertEquals(0, matching.matching().recoveryState().resultEntries)
        assertEquals(
            0,
            funds
                .funds()
                .fundsRecoveryState()
                .dispatch.dispatchEntries,
        )
        balance(funds, 900, 0)
    }

    @Test
    fun `C09 취소 결과는 앞선 체결 결과를 추월해 반환할 수 없다`() {
        val funds = preparedFunds()
        val matching = Fixture(TradingServiceRole.MATCHING).also { it.initialize() }
        val buy = admit(funds, buyer, 1, Side.BUY, 3)
        settle(funds, matching, match(matching, buy))
        val trade = match(matching, admit(funds, seller, 1, Side.SELL, 1))
        assertEquals("ACCEPTED", funds.command(cancel(buy)).code)
        val cancelled = match(matching, funds.read(3).single())
        val before = funds.funds().fundsRecoveryState()

        val gap = funds.resultCommand(SplitResultDeliveryCommand.Apply(cancelled))

        assertEquals("GAP", gap.code)
        assertEquals(2L, gap.expectedSequence)
        assertNull(gap.ack)
        unchanged(funds, before)
        balance(funds, 700, 300)
        settle(funds, matching, trade)
        balance(funds, 700, 200)
        settle(funds, matching, cancelled)
        balance(funds, 900, 0)
    }

    @Test
    fun `C10 C11 양쪽 snapshot과 취소 tail은 미완료 의무와 완료 뒤 중복을 복원한다`() {
        val funds = preparedFunds()
        val matching = Fixture(TradingServiceRole.MATCHING).also { it.initialize() }
        val buy = admit(funds, buyer, 1, Side.BUY, 3)
        settle(funds, matching, match(matching, buy))
        settle(funds, matching, match(matching, admit(funds, seller, 1, Side.SELL, 1)))
        val fundsSnapshot = funds.snapshot()
        val matchingSnapshot = matching.snapshot()
        val first = funds.command(cancel(buy)).cancellation
        val input = funds.read(3).single()
        match(matching, input)

        val restoredFunds = funds.restore(fundsSnapshot)
        val restoredMatching = matching.restore(matchingSnapshot)
        balance(restoredFunds, 700, 200)
        assertEquals(first, restoredFunds.command(cancel(buy)).cancellation)
        assertEquals(input, restoredFunds.read(3).single())
        val result = restoredMatching.results(3).single()
        assertEquals("DUPLICATE", restoredMatching.command(SplitOrderDeliveryCommand.Apply(input)).code)
        settle(restoredFunds, restoredMatching, result)
        balance(restoredFunds, 900, 0)
        val completedFunds = restoredFunds.restore(restoredFunds.snapshot())
        val completedMatching = restoredMatching.restore(restoredMatching.snapshot())
        assertEquals("DUPLICATE", completedFunds.resultCommand(SplitResultDeliveryCommand.Apply(result)).code)
        assertEquals("PRUNED", completedMatching.command(SplitOrderDeliveryCommand.Apply(input)).code)
        assertEquals(first, completedFunds.command(cancel(buy)).cancellation)
        balance(completedFunds, 900, 0)
        assertTrue(
            completedFunds
                .funds()
                .fundsRecoveryState()
                .cancellations
                .isEmpty(),
        )
        assertEquals(0, completedMatching.matching().recoveryState().resultEntries)
    }

    private fun preparedFunds(): Fixture =
        Fixture(TradingServiceRole.FUNDS).also { funds ->
            funds.initialize()
            for ((user, asset, amount) in listOf(Triple(buyer, quote, 1_000L), Triple(seller, base, 3L))) {
                val opening = OpeningBalance("opening-${user.value}", user, asset, Amount(amount))
                assertEquals(
                    "PREPARED",
                    funds
                        .command(
                            SplitOrderDeliveryCommand.Control(
                                BoundedTradingCommand.Funding(DevelopmentBalanceCommandPayload(opening, "unused", Instant.EPOCH)),
                            ),
                        ).code,
                )
                assertEquals("ISSUED", funds.command(SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(user, 0, 0))).code)
            }
        }

    private fun admit(
        funds: Fixture,
        user: UserId,
        request: Long,
        side: Side,
        quantity: Long,
    ): FundsOrderDispatch {
        val intent = OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(quantity))
        val reply = funds.command(SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Admit(RequestToken(user, 0, 1, request), intent)))
        assertEquals("ACCEPTED", reply.code)
        return funds
            .read(
                funds
                    .funds()
                    .fundsRecoveryState()
                    .dispatch.streams
                    .single()
                    .issued,
            ).single()
    }

    private fun cancel(buy: FundsOrderDispatch) =
        SplitOrderDeliveryCommand.Cancel(RequestToken(buyer, 0, 1, 2), CancelIntent(market.marketId, buy.order.orderId, 7))

    private fun match(
        matching: Fixture,
        input: FundsOrderDispatch,
    ): MatchingResultEnvelope {
        assertEquals("APPLIED", matching.command(SplitOrderDeliveryCommand.Apply(input)).code)
        return matching.results(input.dispatchSequence).single()
    }

    private fun settle(
        funds: Fixture,
        matching: Fixture,
        result: MatchingResultEnvelope,
    ) {
        val settled = funds.resultCommand(SplitResultDeliveryCommand.Apply(result))
        assertEquals("APPLIED", settled.code)
        assertEquals("ACKNOWLEDGED", matching.resultCommand(SplitResultDeliveryCommand.RecordAck(assertNotNull(settled.ack))).code)
    }

    private fun balance(
        funds: Fixture,
        available: Long,
        hold: Long,
    ) {
        assertEquals(Balance(buyer, quote, Amount(available), Amount(hold)), funds.funds().currentBalance(buyer, quote))
    }

    private fun unchanged(
        funds: Fixture,
        before: FundsRoleRecoveryState,
    ) {
        val after = funds.funds().fundsRecoveryState()
        assertEquals(before.financial.balances, after.financial.balances)
        assertEquals(before.financial.feeRevenueByAsset, after.financial.feeRevenueByAsset)
        assertEquals(before.financial.liveOrders, after.financial.liveOrders)
        assertEquals(before.financial.markets, after.financial.markets)
        assertEquals(before.financial.inbox, after.financial.inbox)
        assertEquals(before.financial.lastAdmissionSequence, after.financial.lastAdmissionSequence)
        assertEquals(before.dispatch, after.dispatch)
        assertEquals(before.approvalInputs, after.approvalInputs)
        assertEquals(before.cancellations, after.cancellations)
        assertEquals(before.lastCancellationSequence, after.lastCancellationSequence)
        val beforeResults = assertNotNull(before.results)
        val afterResults = assertNotNull(after.results)
        assertEquals(beforeResults.receipts, afterResults.receipts)
        assertEquals(beforeResults.streams, afterResults.streams)
        assertFalse(funds.funds().isBlocked)
    }

    private data class Frame(
        val bytes: ByteArray,
        val at: Long,
    )

    private data class Snapshot(
        val chunks: List<ByteArray>,
        val position: Long,
        val tailStart: Int,
    )

    private inner class Fixture(
        private val role: TradingServiceRole,
        private var position: Long = 0,
        start: Boolean = true,
    ) {
        private val cluster = mock(Cluster::class.java)
        private val session = mock(ClientSession::class.java)
        private val replies = mutableListOf<SplitOrderDeliveryReply>()
        private val resultReplies = mutableListOf<SplitResultDeliveryReply>()
        private val frames = mutableListOf<Frame>()
        private var correlation = 0L
        private val service = SplitTradingClusterAdapter(role, configuration, { throw it }, true)

        init {
            `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
            `when`(cluster.role()).thenReturn(Cluster.Role.FOLLOWER)
            `when`(cluster.logPosition()).thenAnswer { position }
            `when`(cluster.idleStrategy()).thenReturn(mock(IdleStrategy::class.java))
            doAnswer { invocation ->
                val bytes = ByteArray(invocation.getArgument<Int>(2))
                invocation.getArgument<DirectBuffer>(0).getBytes(invocation.getArgument(1), bytes)
                if ((bytes[0].toInt() and 255) ==
                    89
                ) {
                    resultReplies += resultCodec.decodeReply(bytes)
                } else {
                    replies += codec.decodeReply(bytes)
                }
                1L
            }.`when`(session).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
            if (start) {
                service.onStart(cluster, null)
                lead()
            }
        }

        private fun lead() {
            `when`(cluster.role()).thenReturn(Cluster.Role.LEADER)
            service.onRoleChange(Cluster.Role.LEADER)
        }

        fun initialize() {
            assertEquals("INITIALIZED", command(SplitOrderDeliveryCommand.InitializeRole(configuration.topologySha256)).code)
        }

        fun command(command: SplitOrderDeliveryCommand): SplitOrderDeliveryReply {
            deliver(Frame(codec.encode(++correlation, command), timestamp))
            return replies.last()
        }

        fun resultCommand(
            command: SplitResultDeliveryCommand,
            at: Long = timestamp,
        ): SplitResultDeliveryReply {
            deliver(Frame(resultCodec.encode(++correlation, command), at))
            return resultReplies.last()
        }

        private fun deliver(frame: Frame) {
            frames += frame
            position += 64
            val offset = 32
            val padded = ByteArray(offset + frame.bytes.size)
            frame.bytes.copyInto(padded, offset)
            service.onSessionMessage(session, frame.at, UnsafeBuffer(padded), offset, frame.bytes.size, Header(0, 0))
        }

        fun read(from: Long): List<FundsOrderDispatch> =
            service
                .readDispatch(
                    assertNotNull(service.activeGeneration()),
                    configuration.dispatchConfiguration.stream(market.marketId),
                    from,
                    1,
                    65_536,
                ).also {
                    assertFalse(it.isDone)
                    service.doBackgroundWork(0)
                }.join()
                .entries

        fun results(from: Long): List<MatchingResultEnvelope> =
            service
                .readResults(
                    assertNotNull(service.activeResultGeneration()),
                    configuration.matchingConfiguration.resultStream(market.marketId),
                    from,
                    1,
                    2L * 1024 * 1024,
                ).also {
                    assertFalse(it.isDone)
                    service.doBackgroundWork(0)
                }.join()
                .entries

        fun funds(): BoundedFundingState = owner("funds")

        fun matching(): ReservedMatchingState = owner("matching")

        private inline fun <reified T> owner(name: String): T =
            service.javaClass.getDeclaredField(name).let {
                it.isAccessible = true
                it.get(service) as T
            }

        fun snapshot(): Snapshot {
            val chunks = mutableListOf<ByteArray>()
            val publication = mock(ExclusivePublication::class.java)
            doAnswer { invocation ->
                val bytes = ByteArray(invocation.getArgument<Int>(2))
                invocation.getArgument<DirectBuffer>(0).getBytes(invocation.getArgument(1), bytes)
                chunks += bytes
                1L
            }.`when`(publication).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
            service.onTakeSnapshot(publication)
            return Snapshot(chunks, position, frames.size)
        }

        fun restore(snapshot: Snapshot): Fixture {
            val restored = Fixture(role, snapshot.position, false)
            restored.service.onStart(restored.cluster, snapshotImage(snapshot.chunks))
            assertNull(restored.service.activeGeneration())
            frames.drop(snapshot.tailStart).forEach(restored::deliver)
            restored.lead()
            return restored
        }
    }

    private fun snapshotImage(chunks: List<ByteArray>): Image {
        val image = mock(Image::class.java)
        var index = 0
        `when`(image.isEndOfStream).thenAnswer { index == chunks.size }
        val header = mock(Header::class.java)
        `when`(header.flags()).thenReturn(FrameDescriptor.UNFRAGMENTED)
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
                TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)),
                0,
            )
        val manifest =
            TradingClusterManifest(
                ClusterLineage(10, 11),
                "cancellation-wire-test",
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
        val dispatchLimits =
            FundsDispatchLimits(
                mapOf(market.marketId to MarketFundsDispatchLimits(maxDispatchBytes = 65_536, maxActiveBytes = 65_536)),
                maxDispatchBytes = 65_536,
                maxActiveBytes = 65_536,
            )
        return SplitTradingConfiguration(
            topology,
            declaration(TradingServiceRole.FUNDS, manifest.lineage),
            declaration(TradingServiceRole.MATCHING, matchingLineage),
            dispatchLimits = dispatchLimits,
            inputContractVersion = 2,
        )
    }
}
