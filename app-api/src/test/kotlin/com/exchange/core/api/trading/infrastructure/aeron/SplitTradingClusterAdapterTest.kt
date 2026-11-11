package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.generated.SplitResultCommand
import com.exchange.core.api.trading.infrastructure.history.FundsRoleSnapshotCodec
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
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.BoundedFundingRecoveryState
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.CancelCode
import com.exchange.core.trading.CancelInboxLookup
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.CancelReceipt
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchReadStatus
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.FundsRoleRecoveryState
import com.exchange.core.trading.InboxRejection
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultReadStatus
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.TradingCancelInboxEntrySnapshot
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingInboxChannelSnapshot
import com.exchange.core.trading.TradingInboxSnapshot
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingRequestInbox
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val resultCodec = SplitResultDeliveryCodec()
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
    fun `취소 응답을 복원한 자금 callback은 다음 snapshot과 재기동에도 최초 응답과 경계를 보존한다`() {
        val seed = fixture(TradingServiceRole.FUNDS)
        prepare(seed)
        assertEquals("ACCEPTED", seed.command(admit(1, 3)).code)
        val original = seed.funds().fundsRecoveryState()
        val financial = original.financial
        val channel = financial.inbox.channels.single()
        val target =
            assertNotNull(
                channel.entries
                    .single()
                    .receipt.orderId,
            )
        val intent = CancelIntent(market.marketId, target, 7)
        val accepted = CancelReceipt(CancelCode.ACCEPTED, "C2" + "a".repeat(38), target, 41)
        val rejected = CancelReceipt(CancelCode.REJECTED, reason = "NOT_OWNER")
        // 자연 취소 ingress 대신 시험 복구 값의 inbox만 바꾼다. 금융·전달 관계는 실제 접수 결과다.
        val inbox =
            TradingInboxSnapshot(
                listOf(
                    channel.copy(
                        epoch = 3,
                        processedThrough = 3,
                        cancelEntries =
                            listOf(
                                TradingCancelInboxEntrySnapshot(2, intent, accepted),
                                TradingCancelInboxEntrySnapshot(3, intent, rejected),
                            ),
                    ),
                    TradingInboxChannelSnapshot(user, 1, 4, 2, 2, emptyList()),
                ),
            )
        val seeded =
            FundsRoleRecoveryState(
                original.configuration,
                BoundedFundingRecoveryState(
                    financial.manifest,
                    financial.lifecycle,
                    financial.balances,
                    financial.feeRevenueByAsset,
                    financial.usedBalances,
                    financial.preparations,
                    financial.accounts,
                    financial.liveOrders,
                    financial.markets,
                    inbox,
                    financial.lastAdmissionSequence,
                    financial.lastPreparationSequence,
                    financial.preparationCanonicalBytes,
                    financial.incident,
                ),
                original.dispatch,
                original.assetTotals,
                original.results,
            )
        val initialBytes =
            ByteArrayOutputStream().also { FundsRoleSnapshotCodec().write(seeded, seed.position, it) }.toByteArray()
        val first = fixture(TradingServiceRole.FUNDS, start = false, position = seed.position)
        first.service.onStart(first.cluster, snapshotImage(listOf(initialBytes)))
        assertTrue(first.service.tradingReady)
        val recording = first.snapshot()
        val second = fixture(TradingServiceRole.FUNDS, start = false, position = first.position)
        second.service.onStart(second.cluster, snapshotImage(recording))
        assertTrue(second.service.tradingReady)
        val after = second.funds().fundsRecoveryState()
        assertEquals(inbox, after.financial.inbox)
        assertEquals(Balance(user, quote, Amount(700), Amount(300)), second.funds().currentBalance(user, quote))
        assertEquals(financial.balances, after.financial.balances)
        assertEquals(financial.liveOrders, after.financial.liveOrders)
        assertEquals(financial.markets, after.financial.markets)
        assertEquals(financial.feeRevenueByAsset, after.financial.feeRevenueByAsset)
        assertEquals(original.dispatch, after.dispatch)
        assertEquals(original.assetTotals, after.assetTotals)
        assertEquals(original.results!!.streams, after.results!!.streams)
        assertTrue(after.results!!.receipts.isEmpty())
        // 복원된 기록의 순수 조회만 실행하며 실제 취소나 반환을 시작하지 않는다.
        val recovered = TradingRequestInbox(financial.manifest.limits).apply { restore(after.financial.inbox) }
        val token = RequestToken(user, 0, 3, 2)
        assertEquals(CancelInboxLookup.Repeated(accepted), recovered.lookupCancel(token, intent))
        assertEquals(CancelInboxLookup.Repeated(rejected), recovered.lookupCancel(token.copy(sequence = 3), intent))
        assertEquals(
            CancelInboxLookup.Rejected(InboxRejection.REQUEST_CONTENT_CONFLICT),
            recovered.lookupCancel(token, intent.copy(expectedConfigurationVersion = 8)),
        )
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.FENCED_EPOCH), recovered.lookupCancel(token.copy(epoch = 2), intent))
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.UNISSUED_EPOCH), recovered.lookupCancel(token.copy(epoch = 4), intent))
        assertEquals(
            CancelInboxLookup.Rejected(InboxRejection.RETIRED_UNAVAILABLE),
            recovered.lookupCancel(RequestToken(user, 1, 4, 2), intent),
        )
        assertEquals(inbox, recovered.snapshot())
        assertTrue(first.failures.isEmpty() && second.failures.isEmpty())
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

    @Test
    fun `결과 정산은 자금 로그에서 확정하고 매칭 로그에 ACK를 기록하기 전까지 결과를 남긴다`() {
        val funds = fixture(TradingServiceRole.FUNDS, results = true)
        prepare(funds)
        funds.command(admit(1, 1))
        val input = funds.read(1).entries.single()
        val matching = fixture(TradingServiceRole.MATCHING, results = true)
        matching.initialize()
        matching.command(SplitOrderDeliveryCommand.Apply(input))
        val result = matching.readResult(1).entries.single()
        val applied = funds.resultCommand(SplitResultDeliveryCommand.Apply(result))
        assertEquals("APPLIED", applied.code)
        assertEquals(1L, applied.ack?.through)
        assertEquals(configuration.resultTopologySha256, applied.topologySha256)
        assertEquals(Balance(user, quote, Amount(900), Amount(100)), funds.funds().currentBalance(user, quote))
        assertEquals(
            1L,
            funds
                .funds()
                .progress()
                .markets
                .single()
                .executedThrough,
        )
        assertEquals(
            0,
            funds
                .funds()
                .fundsRecoveryState()
                .dispatch.dispatchEntries,
            "결과가 명시 적용 ACK보다 먼저 도착해도 전달 근거가 된다",
        )
        assertEquals(1, matching.matching().recoveryState().resultEntries, "자금 정산 응답만으로 매칭 자료를 지우지 않는다")
        val recorded = matching.resultCommand(SplitResultDeliveryCommand.RecordAck(assertNotNull(applied.ack)))
        assertEquals("ACKNOWLEDGED", recorded.code)
        assertEquals(1L, recorded.resultIssued)
        assertEquals(1L, recorded.ack?.through)
        assertEquals(0, matching.matching().recoveryState().resultEntries)
        assertEquals(1, matching.matching().restingOrders(market.marketId).size)
        assertEquals(1, matching.matching().recoveryState().activeOrders)
        assertEquals(Balance(user, quote, Amount(900), Amount(100)), funds.funds().currentBalance(user, quote))
        assertEquals(
            "ACKNOWLEDGED",
            funds
                .command(
                    SplitOrderDeliveryCommand.RecordAck(assertNotNull(matching.command(SplitOrderDeliveryCommand.Apply(input)).ack)),
                ).code,
        )
        assertEquals(
            1L,
            funds
                .funds()
                .progress()
                .markets
                .single()
                .executedThrough,
        )
    }

    @Test
    fun `전체 체결 결과로 두 사용자 자산을 정산하고 같은 결과와 새 시각은 추가 정산하지 않는다`() {
        val funds = fixture(TradingServiceRole.FUNDS, results = true)
        prepare(funds)
        val seller = UserId("seller")
        val funding =
            DevelopmentBalanceCommandPayload(
                OpeningBalance("seller-opening", seller, market.baseAssetId, Amount(1)),
                "unused",
                Instant.EPOCH,
            )
        assertEquals("PREPARED", funds.command(control(BoundedTradingCommand.Funding(funding))).code)
        assertEquals("ISSUED", funds.command(control(BoundedTradingCommand.Rotate(seller, 0, 0))).code)
        assertEquals(
            "ACCEPTED",
            funds
                .command(
                    control(
                        BoundedTradingCommand.Admit(
                            RequestToken(seller, 0, 1, 1),
                            OrderIntent(market.marketId, 7, Side.SELL, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(1)),
                        ),
                    ),
                ).code,
        )
        val matching = fixture(TradingServiceRole.MATCHING, results = true)
        matching.initialize()
        matching.command(SplitOrderDeliveryCommand.Apply(funds.read(1).entries.single()))
        funds.resultCommand(SplitResultDeliveryCommand.Apply(matching.readResult(1).entries.single()))
        funds.command(admit(1, 1))
        matching.command(SplitOrderDeliveryCommand.Apply(funds.read(2).entries.single()))
        val result = matching.readResult(2).entries.single()
        assertEquals("APPLIED", funds.resultCommand(SplitResultDeliveryCommand.Apply(result)).code)
        val before = funds.funds().fundsRecoveryState()
        assertEquals(Balance(user, quote, Amount(900), Amount(0)), funds.funds().currentBalance(user, quote))
        assertEquals(Balance(user, market.baseAssetId, Amount(1), Amount(0)), funds.funds().currentBalance(user, market.baseAssetId))
        assertEquals(Balance(seller, quote, Amount(100), Amount(0)), funds.funds().currentBalance(seller, quote))
        assertEquals(Balance(seller, market.baseAssetId, Amount(0), Amount(0)), funds.funds().currentBalance(seller, market.baseAssetId))
        assertEquals("DUPLICATE", funds.resultCommand(SplitResultDeliveryCommand.Apply(result), timestamp + 1000).code)
        val after = funds.funds().fundsRecoveryState()
        assertEquals(before.financial.balances, after.financial.balances)
        assertEquals(before.financial.feeRevenueByAsset, after.financial.feeRevenueByAsset)
        assertEquals(before.results!!.receipts, after.results!!.receipts)
        assertTrue(after.financial.liveOrders.isEmpty())
        assertEquals(
            Instant.ofEpochMilli(timestamp),
            after.results!!
                .receipts
                .last()
                .appliedAt,
        )
        assertEquals(
            2L,
            after.results!!
                .streams
                .single()
                .resultThrough,
        )
    }

    @Test
    fun `결과 조회는 매칭 리더의 제한 큐를 통해 실행되고 역할 snapshot 종료마다 무효화된다`() {
        val matching = fixture(TradingServiceRole.MATCHING, results = true)
        matching.initialize()
        val stream = configuration.matchingConfiguration.resultStream(market.marketId)
        assertNull(matching.service.activeGeneration(), "매칭의 세대를 자금 source로 공개하지 않는다")
        val generation = assertNotNull(matching.service.activeResultGeneration())
        val waiting = (1..4).map { matching.service.readResults(generation, stream, 1, 1, 4096) }
        assertTrue(waiting.none { it.isDone })
        assertFailsWith<CompletionException> { matching.service.readResults(generation, stream, 1, 1, 4096).join() }
        matching.role(Cluster.Role.FOLLOWER)
        waiting.forEach { assertFailsWith<CompletionException> { it.join() } }
        assertNull(matching.service.activeResultGeneration())
        matching.role(Cluster.Role.LEADER)
        val next = assertNotNull(matching.service.activeResultGeneration())
        assertTrue(next > generation)
        assertFailsWith<CompletionException> { matching.service.readResults(generation, stream, 1, 1, 4096).join() }
        assertFailsWith<CompletionException> { matching.service.readResults(next, stream, 1, 9, 4096).join() }
        val current = matching.service.readResults(next, stream, 1, 1, 4096)
        matching.snapshot {
            assertNull(matching.service.activeResultGeneration())
            assertEquals(0, matching.service.doBackgroundWork(0))
            assertFailsWith<CompletionException> { current.join() }
        }
        assertTrue(assertNotNull(matching.service.activeResultGeneration()) > next)
        assertEquals(MatchingResultReadStatus.BATCH, matching.readResult(1).status)
        val pending = matching.service.readResults(assertNotNull(matching.service.activeResultGeneration()), stream, 1, 1, 4096)
        matching.service.onTerminate(matching.cluster)
        assertFailsWith<CompletionException> { pending.join() }
        assertNull(matching.service.activeResultGeneration())
    }

    @Test
    fun `결과 wire의 모드 역할 준비 상태와 GAP 및 잘못된 context를 구분한다`() {
        val legacy = fixture(TradingServiceRole.FUNDS)
        assertEquals("UNSUPPORTED_MODE", legacy.resultCommand(SplitResultDeliveryCommand.DescribePeer).code)
        val funds = fixture(TradingServiceRole.FUNDS, results = true)
        assertEquals("DESCRIBED", funds.resultCommand(SplitResultDeliveryCommand.DescribePeer).code)
        val stream = configuration.matchingConfiguration.resultStream(market.marketId)
        val context =
            configuration.topology.fundsManifest.markets
                .single()
        assertEquals("NOT_READY", funds.resultCommand(SplitResultDeliveryCommand.Progress(stream, context)).code)
        prepare(funds)
        funds.command(admit(1, 1))
        funds.command(admit(2, 1))
        val matching = fixture(TradingServiceRole.MATCHING, results = true)
        matching.initialize()
        funds.read(1).entries.forEach { matching.command(SplitOrderDeliveryCommand.Apply(it)) }
        assertEquals("WRONG_ROLE", matching.resultCommand(SplitResultDeliveryCommand.Apply(matching.readResult(1).entries.first())).code)
        val future = matching.readResult(2).entries.single()
        val gap = funds.resultCommand(SplitResultDeliveryCommand.Apply(future))
        assertEquals("GAP", gap.code)
        assertEquals(1L, gap.expectedSequence)
        assertNull(gap.ack)
        assertEquals(
            0L,
            funds
                .funds()
                .progress()
                .markets
                .single()
                .executedThrough,
        )
        assertEquals(Balance(user, quote, Amount(800), Amount(200)), funds.funds().currentBalance(user, quote))
        assertEquals(
            "INVALID_STREAM",
            funds.resultCommand(SplitResultDeliveryCommand.Progress(stream.copy(configurationVersion = 99), context)).code,
        )
        val first = matching.readResult(1).entries.first()
        assertEquals("APPLIED", funds.resultCommand(SplitResultDeliveryCommand.Apply(first)).code)
        val conflict =
            MatchingResultEnvelope(
                first.stream,
                first.resultSequence,
                first.input,
                first.outcome,
                first.reason,
                first.engineSequenceBefore + 1,
                first.engineSequenceAfter + 1,
                first.events.map { (it as OrderEnteredBook).copy(engineSequence = it.engineSequence + 1) },
                first.effects,
            )
        val rejected = funds.resultCommand(SplitResultDeliveryCommand.Apply(conflict))
        assertEquals("CONTENT_CONFLICT", rejected.code)
        assertNull(rejected.ack)
        assertEquals(
            1L,
            funds
                .funds()
                .progress()
                .markets
                .single()
                .executedThrough,
        )
        assertEquals("APPLIED", funds.resultCommand(SplitResultDeliveryCommand.Apply(future)).code)
    }

    @Test
    fun `자금 snapshot 뒤 결과 tail은 최초 시각으로 정산하고 매칭 ACK tail은 payload 회수를 복원한다`() {
        val funds = fixture(TradingServiceRole.FUNDS, results = true)
        prepare(funds)
        funds.command(admit(1, 1))
        val matching = fixture(TradingServiceRole.MATCHING, results = true)
        matching.initialize()
        matching.command(SplitOrderDeliveryCommand.Apply(funds.read(1).entries.single()))
        val result = matching.readResult(1).entries.single()
        val fundsSnapshot = funds.snapshot()
        val fundsPosition = funds.position
        val matchingSnapshot = matching.snapshot()
        val matchingPosition = matching.position
        val restoredFunds = fixture(TradingServiceRole.FUNDS, start = false, position = fundsPosition, results = true)
        restoredFunds.service.onStart(restoredFunds.cluster, snapshotImage(fundsSnapshot))
        restoredFunds.replayResult(SplitResultDeliveryCommand.Apply(result))
        assertTrue(restoredFunds.resultReplies.isEmpty(), "팔로워 tail replay는 원격 응답을 송신하지 않는다")
        assertEquals(
            Instant.ofEpochMilli(timestamp),
            restoredFunds
                .funds()
                .fundsRecoveryState()
                .results!!
                .receipts
                .single()
                .appliedAt,
        )
        restoredFunds.role(Cluster.Role.LEADER)
        val duplicate = restoredFunds.resultCommand(SplitResultDeliveryCommand.Apply(result), timestamp + 999)
        assertEquals("DUPLICATE", duplicate.code)
        val restoredMatching = fixture(TradingServiceRole.MATCHING, start = false, position = matchingPosition, results = true)
        restoredMatching.service.onStart(restoredMatching.cluster, snapshotImage(matchingSnapshot))
        assertNull(restoredMatching.service.activeResultGeneration(), "onStart만으로 tail 이후 리더 송신을 열지 않는다")
        assertEquals(1, restoredMatching.matching().recoveryState().resultEntries)
        restoredMatching.replayResult(SplitResultDeliveryCommand.RecordAck(assertNotNull(duplicate.ack)))
        assertTrue(restoredMatching.resultReplies.isEmpty())
        assertEquals(0, restoredMatching.matching().recoveryState().resultEntries)
        restoredMatching.role(Cluster.Role.LEADER)
        assertEquals(MatchingResultReadStatus.PRUNED, restoredMatching.readResult(1).status)
        assertEquals(1, restoredMatching.matching().restingOrders(market.marketId).size)
        val afterAck = restoredMatching.snapshot()
        val finalMatching = fixture(TradingServiceRole.MATCHING, start = false, position = restoredMatching.position, results = true)
        finalMatching.service.onStart(finalMatching.cluster, snapshotImage(afterAck))
        assertEquals(0, finalMatching.matching().recoveryState().resultEntries)
        assertEquals(
            1L,
            finalMatching
                .matching()
                .recoveryState()
                .streams
                .single()
                .fundsSettledThrough,
        )
    }

    @Test
    fun `사건 순번 산술 overflow는 소유자를 종료하지 않고 malformed로 거절한다`() {
        val funds = fixture(TradingServiceRole.FUNDS, results = true)
        prepare(funds)
        funds.command(admit(1, 1))
        val matching = fixture(TradingServiceRole.MATCHING, results = true)
        matching.initialize()
        matching.command(SplitOrderDeliveryCommand.Apply(funds.read(1).entries.single()))
        val result = matching.readResult(1).entries.single()
        val original = resultCodec.encode(101, SplitResultDeliveryCommand.Apply(result))
        val wire = SplitResultCommand.parseFrom(original.copyOfRange(SplitResultDeliveryCodec.HEADER_BYTES, original.size))
        val body =
            wire
                .toBuilder()
                .setApply(
                    wire.apply
                        .toBuilder()
                        .setEngineSequenceBefore(Long.MAX_VALUE)
                        .setEngineSequenceAfter(Long.MAX_VALUE),
                ).build()
                .toByteArray()
        val malformed = ByteArray(SplitResultDeliveryCodec.HEADER_BYTES + body.size)
        original.copyInto(malformed, endIndex = SplitResultDeliveryCodec.HEADER_BYTES)
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(8, body.size)
        body.copyInto(malformed, SplitResultDeliveryCodec.HEADER_BYTES)
        funds.service.onSessionMessage(funds.session, timestamp, UnsafeBuffer(malformed), 0, malformed.size, Header(0, 0))
        assertEquals(1L, funds.service.malformedMessages)
        assertTrue(funds.service.started)
        assertTrue(funds.service.tradingReady)
        assertNull(funds.service.failure)
        assertTrue(funds.resultReplies.isEmpty())
        assertEquals(
            0L,
            funds
                .funds()
                .progress()
                .markets
                .single()
                .executedThrough,
        )
        assertEquals(Balance(user, quote, Amount(900), Amount(100)), funds.funds().currentBalance(user, quote))
        assertEquals(1, matching.matching().recoveryState().resultEntries)
        assertEquals("APPLIED", funds.resultCommand(SplitResultDeliveryCommand.Apply(result)).code)
        assertEquals(
            1L,
            funds
                .funds()
                .progress()
                .markets
                .single()
                .executedThrough,
        )
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
        results: Boolean = false,
    ): Fixture {
        val fixture = Fixture(role, position, results)
        if (start) {
            fixture.service.onStart(fixture.cluster, null)
            fixture.role(Cluster.Role.LEADER)
        }
        return fixture
    }

    private inner class Fixture(
        role: TradingServiceRole,
        var position: Long,
        results: Boolean,
    ) {
        val cluster = mock(Cluster::class.java)
        val session = mock(ClientSession::class.java)
        val replies = mutableListOf<SplitOrderDeliveryReply>()
        val resultReplies = mutableListOf<SplitResultDeliveryReply>()
        val failures = mutableListOf<Throwable>()
        val service = SplitTradingClusterAdapter(role, configuration, failures::add, results)
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
                if ((bytes[0].toInt() and 255) == 89 && bytes[1] == 0.toByte()) {
                    resultReplies.add(resultCodec.decodeReply(bytes))
                } else {
                    replies.add(codec.decodeReply(bytes))
                }
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

        fun resultCommand(
            command: SplitResultDeliveryCommand,
            at: Long = timestamp,
        ): SplitResultDeliveryReply {
            replayResult(command, at)
            return resultReplies.last()
        }

        fun replayResult(
            command: SplitResultDeliveryCommand,
            at: Long = timestamp,
        ) {
            val bytes = resultCodec.encode(++correlation, command)
            position += 64
            val frame = ByteArray(bytes.size + 32)
            bytes.copyInto(frame, 32)
            service.onSessionMessage(session, at, UnsafeBuffer(frame), 32, bytes.size, Header(0, 0))
        }

        fun readResult(from: Long) =
            service
                .readResults(
                    assertNotNull(service.activeResultGeneration()),
                    configuration.matchingConfiguration.resultStream(market.marketId),
                    from,
                    8,
                    2L * 1024 * 1024,
                ).also {
                    assertFalse(it.isDone)
                    service.doBackgroundWork(0)
                }.join()

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
