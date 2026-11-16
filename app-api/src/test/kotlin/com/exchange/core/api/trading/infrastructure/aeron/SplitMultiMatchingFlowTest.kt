package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryReply
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.api.trading.infrastructure.history.FundsRoleSnapshotCodec
import com.exchange.core.api.trading.infrastructure.history.MatchingRoleSnapshotCodec
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
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingOrderDisposition
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingServiceRole
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.Publication
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
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 codec, 세 Adapter의 확정 callback, 실행 조립을 잇는다. Aeron·Archive·과반 복제 검사는 아니다. */
class SplitMultiMatchingFlowTest {
    @TempDir
    lateinit var directory: Path

    private val buyer = UserId("buyer")
    private val seller = UserId("seller")
    private val quote = AssetId("USDT")
    private val btc = MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), quote, 0)
    private val sui = MarketDefinition(MarketId("SUI-USDT"), AssetId("SUI"), quote, 0)
    private val btcOwner = ClusterLineage(20, 21)
    private val suiOwner = ClusterLineage(30, 31)
    private val orderCodec = SplitOrderDeliveryCodec(2)
    private val resultCodec = SplitResultDeliveryCodec(2)
    private val timestamp = 1_793_664_000_000L

    @Test
    fun `A01 A02 공유1000의 두 callback 순서는 한 주문만 승인하고 중복은 최초 결과를 유지한다`() {
        for (market in listOf(btc, sui)) {
            val firstQuantity = if (market == btc) 6L else 5L
            val available = if (market == btc) 400L else 500L
            val reserved = if (market == btc) 600L else 500L
            Network().use { f ->
                f.prepare()
                val other = if (market == btc) sui else btc
                val accepted = f.admit(buyer, market, firstQuantity)
                val refused = f.admit(buyer, other, if (other == btc) 6 else 5)
                assertEquals("ACCEPTED", accepted.code)
                assertEquals("REJECTED", refused.code)
                assertEquals(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE, assertNotNull(refused.control?.admission).reason)
                f.balance(available, reserved)
                f.until { f.delivered(market, 1) && f.settled(market, 1) }
                val before = f.funds.stateBytes()
                assertEquals(accepted.control?.admission, f.admit(buyer, market, firstQuantity).control?.admission)
                assertEquals(refused.control?.admission, f.admit(buyer, other, if (other == btc) 6 else 5).control?.admission)
                assertEquals("REQUEST_CONTENT_CONFLICT", f.admit(buyer, market, firstQuantity + 1).code)
                assertChunks(before, f.funds.stateBytes())
                f.balance(available, reserved)
                assertEquals(
                    1,
                    f
                        .owner(market)
                        .matching()
                        .restingOrders(market.marketId)
                        .size,
                )
                assertTrue(
                    f
                        .owner(other)
                        .matching()
                        .restingOrders(other.marketId)
                        .isEmpty(),
                )
                assertEquals(0L, f.owner(other).matching().lastEngineSequence(other.marketId))
                assertEquals(0L, f.dispatch(other).issued)
                assertEquals(0L, f.resultThrough(other))
                assertEquals(
                    1L,
                    f.funds
                        .funds()
                        .fundsRecoveryState()
                        .financial.lastAdmissionSequence,
                )
                assertEquals(listOf(market.marketId), f.transport.orderApplies().map { it.input.order.marketId })
                assertEquals(1, f.transport.resultApplies().size)
                assertTrue(
                    f.transport
                        .resultApplies()
                        .single()
                        .result.events
                        .single() is OrderEnteredBook,
                )
                f.assertHealthy()
            }
        }
    }

    @Test
    fun `A03 A04 두 담당 정산과 취소는 수수료4와 각 마켓의 예약 반환만 반영한다`() {
        for (suiFirst in listOf(false, true)) {
            Network(fee = 10_000).use { f ->
                val buys = f.buyBoth()
                f.balance(495, 505)
                f.until { f.complete(1) }
                f.transport.blockResultMarkets += btc.marketId
                f.admit(seller, btc, 1, Side.SELL)
                f.admit(seller, sui, 1, Side.SELL)
                f.until { f.resultThrough(sui) == 2L }
                assertEquals(1L, f.resultThrough(btc))
                f.transport.blockResultMarkets.clear()
                f.until { f.complete(2) }
                f.assertTraded(buys)
                val first = if (suiFirst) sui else btc
                val second = if (suiFirst) btc else sui
                val firstCancel = f.cancel(first, buys.getValue(first.marketId))
                f.until { f.delivered(first, 3) && f.settled(first, 3) }
                if (suiFirst) f.balance(697, 101) else f.balance(596, 202)
                assertEquals(2L, f.resultThrough(second))
                val otherRemaining = if (second == btc) 1L else 2L
                assertEquals(
                    Quantity(otherRemaining),
                    f
                        .owner(second)
                        .matching()
                        .restingOrders(second.marketId)
                        .single()
                        .remainingQuantity,
                )
                val secondCancel = f.cancel(second, buys.getValue(second.marketId))
                f.until { f.complete(3) }
                f.balance(798, 0)
                f.assertConserved()
                assertTrue(f.owners().all { it.matching().restingOrders(it.market()).isEmpty() })
                assertEquals(firstCancel.cancellation, f.cancel(first, buys.getValue(first.marketId)).cancellation)
                assertEquals(secondCancel.cancellation, f.cancel(second, buys.getValue(second.marketId)).cancellation)
                val before = f.funds.stateBytes()
                for (result in f.transport
                    .resultApplies()
                    .map { it.result }
                    .distinctBy { it.resultId }) {
                    assertEquals("DUPLICATE", f.funds.result(SplitResultDeliveryCommand.Apply(result)).code)
                }
                for (sent in f.transport.orderAcks()) {
                    assertEquals("ACKNOWLEDGED", f.funds.order(sent).code)
                }
                for (sent in f.transport.resultAcks()) {
                    assertEquals("ACKNOWLEDGED", f.owner(sent.ack.stream.marketId).result(sent).code)
                }
                assertChunks(before, f.funds.stateBytes())
                val cancellations =
                    f.transport
                        .resultApplies()
                        .map { it.result }
                        .filter { it.resultSequence == 3L }
                assertEquals(2, cancellations.size)
                for (result in cancellations) {
                    assertEquals(
                        listOf(
                            OrderCancelled(
                                result.stream.marketId,
                                3,
                                buys.getValue(result.stream.marketId).order.orderId,
                                buyer,
                                Quantity(
                                    if (result.stream.marketId ==
                                        btc.marketId
                                    ) {
                                        1
                                    } else {
                                        2
                                    },
                                ),
                            ),
                        ),
                        result.events,
                    )
                }
                assertEquals(
                    0,
                    f.funds
                        .funds()
                        .fundsRecoveryState()
                        .dispatch.dispatchEntries,
                )
                assertEquals(0, f.owners().sumOf { it.matching().recoveryState().resultEntries })
                f.assertHealthy()
            }
        }
    }

    @Test
    fun `A05 BTC 응답 timeout 재연결 동안 SUI 연결과 검증 세대는 유지된다`() {
        Network().use { f ->
            f.prepare()
            f.transport.dropOrderReplies += 199
            f.transport.blockResultMarkets += btc.marketId
            f.admit(buyer, btc, 6)
            f.admit(buyer, sui, 1)
            f.until {
                f.delivered(
                    sui,
                    1,
                ) && f.settled(sui, 1) && f.transport.orderApplies().any { it.input.order.marketId == btc.marketId }
            }
            val healthy = f.transport.orderConnections().single { it.endpoint.clusterId == 200 && !it.closed }
            val described = healthy.descriptions
            val epoch = f.orderPeerEpoch(suiOwner)
            f.balance(300, 700)
            assertEquals(1, f.dispatch(btc).outstanding.size)
            f.cycles((f.configuration.deliveryLimits.attemptTimeoutNs / 1_000_000 + 2).toInt())
            assertTrue(
                f.transport
                    .orderConnections()
                    .filter { it.endpoint.clusterId == 199 }
                    .all { it.closed },
            )
            assertFalse(healthy.closed)
            assertEquals(epoch, f.orderPeerEpoch(suiOwner))
            assertEquals(described, healthy.descriptions)
            assertEquals(1L, f.dispatch(sui).matchingAppliedThrough)
            f.transport.dropOrderReplies.clear()
            f.transport.blockResultMarkets.clear()
            f.advance(f.configuration.deliveryLimits.reconnectDelayNs + 1)
            f.until { f.complete(1) }
            assertSame(healthy, f.transport.orderConnections().single { it.endpoint.clusterId == 200 && !it.closed })
            assertEquals(described, healthy.descriptions)
            assertEquals(epoch, f.orderPeerEpoch(suiOwner))
            assertEquals(1L, f.owner(btc).matching().lastEngineSequence(btc.marketId))
            assertEquals(
                1,
                f.transport
                    .orderApplies()
                    .filter { it.input.order.marketId == btc.marketId }
                    .map { it.input }
                    .distinct()
                    .size,
            )
            f.balance(300, 700)
            f.assertHealthy()
        }
    }

    @Test
    fun `A05 BTC BUSY는 연결 재생성 없이 재시도하고 SUI 정산을 막지 않는다`() {
        Network(resultEntries = 1).use { f ->
            f.prepare()
            f.transport.blockResultMarkets += btc.marketId
            f.admit(buyer, btc, 1)
            f.until { f.delivered(btc, 1) }
            val connection = f.transport.orderConnections().single { it.endpoint.clusterId == 199 && !it.closed }
            f.admit(buyer, btc, 1, sequence = 2)
            f.admit(buyer, sui, 1)
            f.until {
                f.transport.observations.any { it.clusterId == 199 && it.orderReply?.code == "BUSY" } && f.delivered(sui, 1) &&
                    f.settled(sui, 1)
            }
            assertFalse(connection.closed)
            assertEquals(1L, f.dispatch(btc).matchingAppliedThrough)
            assertEquals(1, f.dispatch(btc).outstanding.size)
            f.balance(700, 300)
            f.transport.blockResultMarkets.clear()
            f.until { f.delivered(btc, 2) && f.settled(btc, 2) }
            assertSame(connection, f.transport.orderConnections().single { it.endpoint.clusterId == 199 && !it.closed })
            assertEquals(2L, f.owner(btc).matching().lastEngineSequence(btc.marketId))
            f.balance(700, 300)
            f.assertHealthy()
        }
    }

    @Test
    fun `A06 원문을 다른 owner에 보내거나 market epoch context를 바꾸면 입력과 성공 ACK가 생기지 않는다`() {
        for (damage in listOf("owner", "market", "epoch", "context")) {
            Network().use { f ->
                f.prepare()
                f.admit(buyer, btc, 1)
                val original = f.funds.dispatch(btc.marketId, 1).single()
                val input =
                    when (damage) {
                        "market" -> {
                            original.copy(
                                stream = original.stream.copy(marketId = sui.marketId),
                                order = original.order.copy(marketId = sui.marketId),
                                reservation = original.reservation.copy(marketId = sui.marketId),
                                context = f.context(sui),
                            )
                        }

                        "epoch" -> {
                            original.copy(stream = original.stream.copy(ownershipEpoch = 2))
                        }

                        "context" -> {
                            val policy = original.context.feePolicySnapshot.copy(scheduleVersion = 2)
                            original.copy(
                                context = original.context.copy(feePolicySnapshot = policy),
                                reservation = original.reservation.copy(feePolicySnapshot = policy),
                            )
                        }

                        else -> {
                            original
                        }
                    }
                val target = if (damage == "owner") f.owner(sui) else f.owner(btc)
                val before = target.stateBytes()
                val reply = target.order(SplitOrderDeliveryCommand.Apply(input))
                assertEquals(if (damage == "context") "INVALID_INPUT" else "INVALID_STREAM", reply.code)
                assertNull(reply.ack)
                assertChunks(before, target.stateBytes())
                assertEquals(0, target.matching().recoveryState().resultEntries)
                assertTrue(target.matching().restingOrders(target.market()).isEmpty())
                assertEquals("APPLIED", f.owner(btc).order(SplitOrderDeliveryCommand.Apply(original)).code)
                assertEquals(1L, f.owner(btc).matching().lastEngineSequence(btc.marketId))
                f.balance(900, 100)
            }
        }
    }

    @Test
    fun `A06 다른 lineage의 실제 응답과 미래 ACK는 worker를 차단하고 자금 원본을 남긴다`() {
        for (damage in listOf("lineage", "through")) {
            Network().use { f ->
                f.prepare()
                f.admit(buyer, btc, 1)
                f.transport.blockResultMarkets += btc.marketId
                val before = f.funds.stateBytes()
                f.transport.alterOrderReply = { endpoint, reply ->
                    if (endpoint.clusterId != 199) {
                        reply
                    } else if (damage == "lineage") {
                        reply.copy(lineage = suiOwner)
                    } else {
                        reply.copy(ack = reply.ack?.copy(through = Long.MAX_VALUE))
                    }
                }
                if (damage == "lineage") {
                    assertFailsWith<IllegalArgumentException> { f.cycles(4) }
                } else {
                    f.until(checkFailures = false) { f.failures.isNotEmpty() }
                }
                assertChunks(before, f.funds.stateBytes())
                f.balance(900, 100)
                assertEquals(0L, f.dispatch(btc).matchingAppliedThrough)
                assertEquals(1, f.dispatch(btc).outstanding.size)
                assertEquals(0L, f.resultThrough(btc))
                assertEquals(0L, f.dispatch(sui).issued)
            }
        }
    }

    @Test
    fun `A06 정산 확인과 변조 결과는 다른 stream의 자료와 금액을 바꾸지 않는다`() {
        Network(fee = 10_000).use { f ->
            f.buyBoth()
            for (market in listOf(btc, sui)) {
                val input = f.funds.dispatch(market.marketId, 1).single()
                assertEquals("APPLIED", f.owner(market).order(SplitOrderDeliveryCommand.Apply(input)).code)
            }
            val btcResult = f.owner(btc).results(1).single()
            val suiResult = f.owner(sui).results(1).single()
            val settled = f.funds.result(SplitResultDeliveryCommand.Apply(btcResult))
            val suiBefore = f.owner(sui).stateBytes()
            val wrongAck = f.owner(sui).result(SplitResultDeliveryCommand.RecordAck(assertNotNull(settled.ack)))
            assertEquals("INVALID_STREAM", wrongAck.code)
            assertNull(wrongAck.ack)
            assertChunks(suiBefore, f.owner(sui).stateBytes())
            assertEquals(
                1,
                f
                    .owner(sui)
                    .matching()
                    .recoveryState()
                    .resultEntries,
            )
            val before = f.funds.stateBytes()
            val changed = changedResult(btcResult)
            assertEquals("CONTENT_CONFLICT", f.funds.result(SplitResultDeliveryCommand.Apply(changed)).code)
            assertChunks(before, f.funds.stateBytes())
            val wrongInput = suiResult.input.copy(stream = suiResult.input.stream.copy(matchingLineage = btcOwner))
            val wrongOwner =
                MatchingResultEnvelope(
                    suiResult.stream.copy(matchingLineage = btcOwner),
                    suiResult.resultSequence,
                    wrongInput,
                    suiResult.outcome,
                    suiResult.reason,
                    suiResult.engineSequenceBefore,
                    suiResult.engineSequenceAfter,
                    suiResult.events,
                    suiResult.effects.map { it.copy(origin = wrongInput) },
                )
            assertEquals("INVALID_STREAM", f.funds.result(SplitResultDeliveryCommand.Apply(wrongOwner)).code)
            assertChunks(before, f.funds.stateBytes())
            f.balance(495, 505)
            assertEquals(1L, f.resultThrough(btc))
            assertEquals(0L, f.resultThrough(sui))
        }
    }

    @Test
    fun `A09 세 snapshot과 tail을 새 Adapter에 복원하면 미전달 미정산 의무가 같은 결과로 수렴한다`() {
        Network(fee = 10_000).use { f ->
            val buys = f.buyBoth()
            val firstBtc = assertNotNull(f.admit(buyer, btc, 2).control?.admission)
            val firstSui = assertNotNull(f.admit(buyer, sui, 3).control?.admission)
            f.transport.blockOrderMarkets += sui.marketId
            f.transport.blockResultMarkets += btc.marketId
            f.until { f.delivered(btc, 1) }
            assertEquals(1, f.dispatch(sui).outstanding.size)
            assertEquals(
                1,
                f
                    .owner(btc)
                    .matching()
                    .recoveryState()
                    .resultEntries,
            )
            val snapshots = f.fixtures().associateWith { it.snapshot() }
            val firstSell = f.admit(seller, sui, 1, Side.SELL)
            val originalInputs = f.dispatch(sui).outstanding
            f.restore(snapshots)
            f.balance(495, 505)
            assertEquals(firstBtc, f.admit(buyer, btc, 2).control?.admission)
            assertEquals(firstSui, f.admit(buyer, sui, 3).control?.admission)
            assertEquals(firstSell.control?.admission, f.admit(seller, sui, 1, Side.SELL).control?.admission)
            assertEquals(originalInputs, f.dispatch(sui).outstanding)
            assertEquals(
                buys.getValue(btc.marketId).order.orderId,
                f
                    .owner(btc)
                    .matching()
                    .restingOrders(btc.marketId)
                    .single()
                    .orderId,
            )
            f.transport.blockOrderMarkets.clear()
            f.transport.blockResultMarkets.clear()
            f.admit(seller, btc, 1, Side.SELL)
            f.until { f.complete(2) }
            f.assertTraded(buys)
            f.cancel(btc, buys.getValue(btc.marketId))
            f.cancel(sui, buys.getValue(sui.marketId))
            f.until { f.complete(3) }
            f.balance(798, 0)
            f.assertConserved()
            f.assertHealthy()
        }
    }

    @Test
    fun `A09 반대 owner snapshot과 바뀐 배치는 실제 복원 전에 거절한다`() {
        Network().use { original ->
            original.prepare()
            original.admit(buyer, btc, 1)
            val btcSnapshot = original.owner(btc).snapshot()
            assertFailsWith<AgentTerminationException> { original.owner(sui).restore(btcSnapshot) }
            Network(swapped = true).use { changed ->
                assertFailsWith<AgentTerminationException> { changed.funds.restore(original.funds.snapshot()) }
            }
        }
    }

    @Test
    fun `A10 결과 관찰 하나 누락 owner 교환 잘못된 금액 추가 반환 중복과 미완료는 검사 실패다`() {
        Network(fee = 10_000).use { f ->
            val buys = f.buyBoth()
            f.until { f.complete(1) }
            f.admit(seller, btc, 1, Side.SELL)
            f.admit(seller, sui, 1, Side.SELL)
            f.until { f.complete(2) }
            val correct = f.observation()
            f.verifyTradeObservation(correct, buys)
            val btcResults = correct.results.getValue(btc.marketId)
            val suiResults = correct.results.getValue(sui.marketId)
            val mutations =
                listOf(
                    correct.copy(adapters = correct.adapters.dropLast(1)),
                    correct.copy(results = correct.results - sui.marketId),
                    correct.copy(results = mapOf(btc.marketId to suiResults, sui.marketId to btcResults)),
                    correct.copy(available = 496),
                    correct.copy(reserved = 302),
                    correct.copy(results = correct.results + (btc.marketId to (btcResults + btcResults.last()))),
                    correct.copy(settled = correct.settled + (sui.marketId to 1L)),
                    correct.copy(orderIds = correct.orderIds - sui.marketId),
                )
            for (mutation in mutations) {
                assertFailsWith<AssertionError> { f.verifyTradeObservation(mutation, buys) }
            }
        }
    }

    @Test
    fun `A11 다른 마켓은 전역 승인 역순을 허용하고 같은 stream의 2부터 도착은 GAP expected1이다`() {
        Network().use { f ->
            f.prepare()
            f.admit(buyer, btc, 1)
            f.admit(buyer, sui, 1)
            f.admit(buyer, btc, 1, sequence = 2)
            val btcInputs = f.funds.dispatch(btc.marketId, 1)
            val suiInput = f.funds.dispatch(sui.marketId, 1).single()
            assertTrue(suiInput.admissionSequence > btcInputs.first().admissionSequence)
            assertEquals("APPLIED", f.owner(sui).order(SplitOrderDeliveryCommand.Apply(suiInput)).code)
            val before = f.owner(btc).stateBytes()
            val gap = f.owner(btc).order(SplitOrderDeliveryCommand.Apply(btcInputs[1]))
            assertEquals("GAP", gap.code)
            assertEquals(1L, gap.expectedSequence)
            assertNull(gap.ack)
            assertChunks(before, f.owner(btc).stateBytes())
            for (input in btcInputs) assertEquals("APPLIED", f.owner(btc).order(SplitOrderDeliveryCommand.Apply(input)).code)
            val btcResults = f.owner(btc).results(1)
            val beforeFunds = f.funds.stateBytes()
            val resultGap = f.funds.result(SplitResultDeliveryCommand.Apply(btcResults[1]))
            assertEquals("GAP", resultGap.code)
            assertEquals(1L, resultGap.expectedSequence)
            assertNull(resultGap.ack)
            assertChunks(beforeFunds, f.funds.stateBytes())
            val suiResult = f.owner(sui).results(1).single()
            assertEquals("APPLIED", f.funds.result(SplitResultDeliveryCommand.Apply(suiResult)).code)
            assertEquals(0L, f.resultThrough(btc))
            for (result in btcResults) assertEquals("APPLIED", f.funds.result(SplitResultDeliveryCommand.Apply(result)).code)
            f.until { f.delivered(btc, 2) && f.settled(btc, 2) && f.delivered(sui, 1) && f.settled(sui, 1) }
            assertEquals(2L, f.owner(btc).matching().lastEngineSequence(btc.marketId))
            assertEquals(1L, f.owner(sui).matching().lastEngineSequence(sui.marketId))
            assertEquals(
                3,
                assertNotNull(
                    f.funds
                        .funds()
                        .fundsRecoveryState()
                        .results,
                ).receipts.size,
            )
            f.balance(700, 300)
            f.assertHealthy()
        }
    }

    @Test
    fun `A12 실제 환경 파서와 Node 공용 조립은 같은 funds source와 설정의 두 목적지를 연결한다`() {
        for (swapped in listOf(false, true)) {
            Network(swapped = swapped).use { f ->
                f.prepare()
                assertEquals(2, f.fundsTasks.orders.size)
                assertNull(f.fundsTasks.result)
                for (agent in f.fundsTasks.orders) assertSame(f.funds.service, field(agent, "source"))
                for (owner in f.owners()) {
                    val tasks = f.tasks.getValue(owner)
                    assertTrue(tasks.orders.isEmpty())
                    assertSame(owner.service, field(assertNotNull(tasks.result), "source"))
                }
                f.admit(buyer, btc, 1)
                f.admit(buyer, sui, 1)
                f.until { f.complete(1) }
                val destination = f.transport.observations.filter { it.orderCommand is SplitOrderDeliveryCommand.Apply }
                assertEquals(2, destination.size)
                for (sent in destination) {
                    val input = (sent.orderCommand as SplitOrderDeliveryCommand.Apply).input
                    val expected = if ((input.order.marketId == btc.marketId) xor swapped) 199 else 200
                    assertEquals(expected, sent.clusterId)
                    assertEquals(
                        f
                            .owner(input.order.marketId)
                            .settings
                            .endpoint(true)
                            .ingressEndpoints,
                        sent.ingress,
                    )
                }
                assertEquals(
                    setOf(btcOwner, suiOwner),
                    f.transport
                        .resultApplies()
                        .map { it.result.stream.matchingLineage }
                        .toSet(),
                )
                f.assertHealthy()
            }
        }
    }

    private data class Observation(
        val adapters: List<ClusterLineage>,
        val results: Map<MarketId, List<MatchingResultEnvelope>>,
        val orderIds: Map<MarketId, List<String>>,
        val available: Long,
        val reserved: Long,
        val settled: Map<MarketId, Long>,
    )

    private data class CommittedFrame(
        val bytes: ByteArray,
        val at: Long,
    )

    private data class Snapshot(
        val chunks: List<ByteArray>,
        val position: Long,
        val tailStart: Int,
    )

    private inner class Network(
        fee: Long = 0,
        private val resultEntries: Int = 4_096,
        private val swapped: Boolean = false,
    ) : AutoCloseable {
        private val root = Files.createTempDirectory(directory, "flow-")
        private val manifest = manifest(fee)
        private val manifestFile =
            root
                .resolve(
                    "manifest.bin",
                ).also { Files.write(it, BoundedTradingStateCodec().encodeManifest(manifest)) }
        val failures = mutableListOf<Throwable>()
        val configuration get() = funds.settings.configuration
        var now = 0L
        private val started = System.nanoTime()
        private var polls = 0
        var funds = Fixture(settings(TradingServiceRole.FUNDS), this)
        private var first = Fixture(settings(TradingServiceRole.MATCHING, btcOwner), this)
        private var second = Fixture(settings(TradingServiceRole.MATCHING, suiOwner), this)
        val transport = VirtualTransport(this)
        val tasks = linkedMapOf<Fixture, SplitTradingDeliveryTasks>()
        val fundsTasks get() = tasks.getValue(funds)

        init {
            fixtures().forEach { it.initialize() }
            assemble()
        }

        fun settings(
            role: TradingServiceRole,
            lineage: ClusterLineage = btcOwner,
        ): SplitTradingNodeSettings {
            val base =
                if (role == TradingServiceRole.FUNDS) {
                    21_000
                } else if (lineage == btcOwner) {
                    22_000
                } else {
                    23_000
                }
            val environment =
                mapOf(
                    "SPLIT_MODE" to "RESULT_SETTLEMENT_DEV",
                    "SPLIT_ROLE" to role.name,
                    "SPLIT_MANIFEST" to manifestFile.toString(),
                    "SPLIT_INPUT_CONTRACT_VERSION" to "2",
                    "SPLIT_MAX_FUNDS_DISPATCH_BYTES" to "1048576",
                    "SPLIT_MAX_FUNDS_ACTIVE_BYTES" to "1048576",
                    "SPLIT_MAX_RESULT_ENTRIES" to resultEntries.toString(),
                    "SPLIT_MATCHING_OWNER_COUNT" to "2",
                    "SPLIT_MATCHING_0_LINEAGE_HIGH" to "20",
                    "SPLIT_MATCHING_0_LINEAGE_LOW" to "21",
                    "SPLIT_MATCHING_0_OWNERSHIP_EPOCH" to "1",
                    "SPLIT_MATCHING_0_MARKETS" to if (swapped) "SUI-USDT" else "BTC-USDT",
                    "SPLIT_MATCHING_0_CLUSTER_ID" to "199",
                    "SPLIT_MATCHING_0_INGRESS" to "0=127.0.0.1:22000",
                    "SPLIT_MATCHING_1_LINEAGE_HIGH" to "30",
                    "SPLIT_MATCHING_1_LINEAGE_LOW" to "31",
                    "SPLIT_MATCHING_1_OWNERSHIP_EPOCH" to "1",
                    "SPLIT_MATCHING_1_MARKETS" to if (swapped) "BTC-USDT" else "SUI-USDT",
                    "SPLIT_MATCHING_1_CLUSTER_ID" to "200",
                    "SPLIT_MATCHING_1_INGRESS" to "0=127.0.0.1:23000",
                    "SPLIT_SELF_MATCHING_LINEAGE_HIGH" to lineage.high.toString(),
                    "SPLIT_SELF_MATCHING_LINEAGE_LOW" to lineage.low.toString(),
                    "SPLIT_FUNDS_INGRESS" to "0=127.0.0.1:21000",
                    "FUNDING_RUN_ROOT" to
                        root.resolve(if (role == TradingServiceRole.FUNDS) "funds" else "matching-${lineage.high}").toString(),
                    "FUNDING_BASE_PORT" to base.toString(),
                    "FUNDING_MEMBER_ID" to "0",
                    "FUNDING_HOST" to "127.0.0.1",
                    "FUNDING_MEMBERS" to "0,${(0..4).joinToString(",") { "127.0.0.1:${base + it}" }}",
                    "FUNDING_RUN_ID" to "multi-flow",
                )
            return splitTradingSettings(environment)
        }

        private fun assemble() {
            for (fixture in fixtures()) {
                tasks[fixture] =
                    splitTradingDeliveryTasks(
                        fixture.settings,
                        fixture.service,
                        orderConnectionFactory = transport.orderFactory,
                        resultConnectionFactory = transport.resultFactory,
                        onFailure = { failures += it },
                        nanoTime = { now },
                    )
            }
        }

        fun fixtures() = listOf(funds, first, second)

        fun owners() = listOf(first, second)

        fun owner(market: MarketDefinition) = owner(market.marketId)

        fun owner(market: MarketId) = owners().single { it.market() == market }

        fun byCluster(id: Int) = fixtures().single { it.settings.clusterId == id }

        fun context(market: MarketDefinition) = manifest.markets.single { it.market.marketId == market.marketId }

        fun prepare() {
            for ((user, asset, amount) in listOf(
                Triple(buyer, quote, 1_000L),
                Triple(seller, btc.baseAssetId, 1L),
                Triple(seller, sui.baseAssetId, 1L),
            )) {
                val opening = OpeningBalance("opening-${user.value}-${asset.value}", user, asset, Amount(amount))
                assertEquals(
                    "PREPARED",
                    funds
                        .order(
                            SplitOrderDeliveryCommand.Control(
                                BoundedTradingCommand.Funding(DevelopmentBalanceCommandPayload(opening, "unused", Instant.EPOCH)),
                            ),
                        ).code,
                )
            }
            for (user in listOf(buyer, seller)) {
                for (channel in 0..1) {
                    assertEquals(
                        "ISSUED",
                        funds.order(SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Rotate(user, channel, 0))).code,
                    )
                }
            }
        }

        fun admit(
            user: UserId,
            market: MarketDefinition,
            quantity: Long,
            side: Side = Side.BUY,
            sequence: Long = 1,
        ): SplitOrderDeliveryReply =
            funds.order(
                SplitOrderDeliveryCommand.Control(
                    BoundedTradingCommand.Admit(
                        RequestToken(user, if (market == btc) 0 else 1, 1, sequence),
                        OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(quantity)),
                    ),
                ),
            )

        fun buyBoth(): Map<MarketId, FundsOrderDispatch> {
            prepare()
            assertEquals("ACCEPTED", admit(buyer, btc, 2).code)
            assertEquals("ACCEPTED", admit(buyer, sui, 3).code)
            return listOf(btc, sui).associate { it.marketId to funds.dispatch(it.marketId, 1).single() }
        }

        fun cancel(
            market: MarketDefinition,
            buy: FundsOrderDispatch,
        ): SplitOrderDeliveryReply =
            funds.order(
                SplitOrderDeliveryCommand.Cancel(
                    RequestToken(
                        buyer,
                        if (market ==
                            btc
                        ) {
                            0
                        } else {
                            1
                        },
                        1,
                        2,
                    ),
                    CancelIntent(market.marketId, buy.order.orderId, 7),
                ),
            )

        fun dispatch(market: MarketDefinition) =
            funds.funds().fundsRecoveryState().dispatch.streams.single {
                it.stream.marketId ==
                    market.marketId
            }

        fun resultThrough(market: MarketDefinition) =
            assertNotNull(funds.funds().fundsRecoveryState().results)
                .streams
                .single {
                    it.stream.marketId ==
                        market.marketId
                }.resultThrough

        fun delivered(
            market: MarketDefinition,
            through: Long,
        ) = dispatch(market).matchingAppliedThrough == through && dispatch(market).outstanding.isEmpty()

        fun settled(
            market: MarketDefinition,
            through: Long,
        ): Boolean {
            val state =
                owner(market)
                    .matching()
                    .recoveryState()
                    .streams
                    .single()
            return resultThrough(market) == through && state.fundsSettledThrough == through && state.outstanding.isEmpty()
        }

        fun complete(through: Long) = listOf(btc, sui).all { delivered(it, through) && settled(it, through) }

        fun advance(ns: Long) {
            now += ns
        }

        fun cycles(count: Int) {
            repeat(count) { cycle() }
        }

        private fun cycle() {
            check(++polls <= 100_000 && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30)) {
                "유한 pump 초과 polls=$polls funds=${funds.funds().fundsRecoveryState().dispatch.streams.map {
                    it.stream.marketId to it.matchingAppliedThrough
                }} failures=$failures"
            }
            tasks.values.forEach { it.doWork() }
            fixtures().forEach { it.service.doBackgroundWork(0) }
            now += 1_000_000
        }

        fun until(
            checkFailures: Boolean = true,
            condition: () -> Boolean,
        ) {
            while (!condition()) {
                cycle()
                if (checkFailures) assertHealthy()
            }
        }

        fun balance(
            available: Long,
            reserved: Long,
        ) = assertEquals(Balance(buyer, quote, Amount(available), Amount(reserved)), funds.funds().currentBalance(buyer, quote))

        fun assertConserved() {
            val state = funds.funds().fundsRecoveryState().financial
            val buyerQuote = funds.funds().currentBalance(buyer, quote)
            val sellerQuote = funds.funds().currentBalance(seller, quote)
            assertEquals(Balance(seller, quote, Amount(198), Amount.ZERO), sellerQuote)
            assertEquals(BigInteger.valueOf(4), state.feeRevenueByAsset[quote])
            assertEquals(
                1_000L,
                buyerQuote.available.value + buyerQuote.hold.value + sellerQuote.available.value + sellerQuote.hold.value +
                    state.feeRevenueByAsset.getValue(quote).toLong(),
            )
            for (market in listOf(btc, sui)) {
                assertEquals(
                    Balance(buyer, market.baseAssetId, Amount(1), Amount.ZERO),
                    funds.funds().currentBalance(buyer, market.baseAssetId),
                )
                assertEquals(
                    Balance(seller, market.baseAssetId, Amount.ZERO, Amount.ZERO),
                    funds.funds().currentBalance(seller, market.baseAssetId),
                )
            }
        }

        fun assertTraded(buys: Map<MarketId, FundsOrderDispatch>) {
            balance(495, 303)
            assertConserved()
            verifyTradeObservation(observation(), buys)
        }

        fun observation(): Observation =
            Observation(
                fixtures().map {
                    if (it.settings.role ==
                        TradingServiceRole.FUNDS
                    ) {
                        it.settings.configuration.fundsLineage
                    } else {
                        it.settings.configuration.matchingLineage
                    }
                },
                transport.resultApplies().map { it.result }.groupBy { it.stream.marketId },
                transport.orderApplies().map { it.input }.groupBy { it.order.marketId }.mapValues { (_, inputs) ->
                    inputs.map { it.order.orderId.value }
                },
                funds
                    .funds()
                    .currentBalance(buyer, quote)
                    .available.value,
                funds
                    .funds()
                    .currentBalance(buyer, quote)
                    .hold.value,
                listOf(btc, sui).associate { it.marketId to resultThrough(it) },
            )

        fun verifyTradeObservation(
            actual: Observation,
            buys: Map<MarketId, FundsOrderDispatch>,
        ) {
            assertEquals(setOf(manifest.lineage, btcOwner, suiOwner), actual.adapters.toSet())
            assertEquals(3, actual.adapters.size)
            assertEquals(setOf(btc.marketId, sui.marketId), actual.results.keys)
            assertEquals(495L, actual.available)
            assertEquals(303L, actual.reserved)
            assertEquals(mapOf(btc.marketId to 2L, sui.marketId to 2L), actual.settled)
            assertEquals(setOf(btc.marketId, sui.marketId), actual.orderIds.keys)
            for (market in listOf(btc, sui)) {
                val results = actual.results.getValue(market.marketId)
                assertEquals(listOf(1L, 2L), results.map { it.resultSequence })
                assertEquals(2, results.map { it.resultId }.distinct().size)
                assertEquals(2, actual.orderIds.getValue(market.marketId).size)
                assertEquals(
                    buys
                        .getValue(market.marketId)
                        .order.orderId.value,
                    actual.orderIds.getValue(market.marketId).first(),
                )
                assertEquals(owner(market).settings.configuration.matchingLineage, results.first().stream.matchingLineage)
                val lineage = owner(market).settings.configuration.matchingLineage
                assertEquals(
                    (1..2).map { "M2:${lineage.high}:${lineage.low}:10:11:${market.marketId.value}:1:7:$it" },
                    results.map { it.resultId },
                )
                assertEquals(buys.getValue(market.marketId), results.first().input)
                assertEquals(market.marketId, results.last().stream.marketId)
                val trade = results.last().events.single() as TradeExecuted
                assertEquals(market.marketId, trade.marketId)
                assertEquals(2L, trade.engineSequence)
                assertEquals(buys.getValue(market.marketId).order.orderId, trade.makerOrderId)
                assertEquals(
                    results
                        .last()
                        .input.order.orderId,
                    trade.takerOrderId,
                )
                assertEquals(buyer, trade.makerUserId)
                assertEquals(seller, trade.takerUserId)
                assertEquals(Side.SELL, trade.side)
                assertEquals(Price(100), trade.price)
                assertEquals(Quantity(1), trade.quantity)
                assertEquals(2, results.last().effects.size)
            }
        }

        fun orderPeerEpoch(lineage: ClusterLineage): Long {
            val agent = fundsTasks.orders.single { (field(it, "configuration") as SplitTradingConfiguration).matchingLineage == lineage }
            return field(assertNotNull(field(agent, "matching")), "epoch") as Long
        }

        fun assertHealthy() {
            assertTrue(failures.isEmpty(), "실제 callback/worker 실패: $failures")
            tasks.values.forEach { task ->
                task.orders.forEach { assertNull(it.failure) }
                task.result?.let { assertNull(it.failure) }
            }
            assertFalse(funds.funds().isBlocked)
            assertTrue(transport.maxQueuedEntries <= transport.maxEntries)
            assertTrue(transport.maxQueuedBytes <= transport.maxBytes)
        }

        fun restore(snapshots: Map<Fixture, Snapshot>) {
            tasks.values.forEach { it.close() }
            tasks.clear()
            funds = funds.restore(snapshots.getValue(funds))
            first = first.restore(snapshots.getValue(first))
            second = second.restore(snapshots.getValue(second))
            assemble()
        }

        override fun close() {
            tasks.values.forEach { it.close() }
        }
    }

    private inner class Fixture(
        val settings: SplitTradingNodeSettings,
        private val network: Network,
        private var position: Long = 0,
        start: Boolean = true,
    ) {
        val service =
            SplitTradingClusterAdapter(settings.role, settings.configuration, {
                network.failures += it
                throw it
            }, true)
        private val cluster = mock(Cluster::class.java)
        private val frames = mutableListOf<CommittedFrame>()
        private var correlation = 10_000L

        init {
            `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
            `when`(cluster.role()).thenReturn(Cluster.Role.FOLLOWER)
            `when`(cluster.logPosition()).thenAnswer { position }
            `when`(cluster.idleStrategy()).thenReturn(mock(IdleStrategy::class.java))
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
            assertEquals("INITIALIZED", order(SplitOrderDeliveryCommand.InitializeRole(settings.configuration.topologySha256)).code)
        }

        fun market() =
            settings.configuration.matchingConfiguration.markets
                .single()
                .market.marketId

        fun order(command: SplitOrderDeliveryCommand) = orderCodec.decodeReply(deliver(orderCodec.encode(++correlation, command)))

        fun result(command: SplitResultDeliveryCommand) = resultCodec.decodeReply(deliver(resultCodec.encode(++correlation, command)))

        fun deliver(
            bytes: ByteArray,
            at: Long = timestamp,
        ): ByteArray {
            val replies = mutableListOf<ByteArray>()
            val session = mock(ClientSession::class.java)
            doAnswer { invocation ->
                val reply = ByteArray(invocation.getArgument<Int>(2))
                invocation.getArgument<DirectBuffer>(0).getBytes(invocation.getArgument(1), reply)
                replies += reply
                1L
            }.`when`(session).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
            require(frames.size < 10_000) { "callback 기록 상한" }
            frames += CommittedFrame(bytes.copyOf(), at)
            position += 64
            val offset = 32
            val buffer = ByteArray(offset + bytes.size)
            bytes.copyInto(buffer, offset)
            service.onSessionMessage(session, at, UnsafeBuffer(buffer), offset, bytes.size, Header(0, 0))
            return replies.single()
        }

        fun dispatch(
            market: MarketId,
            from: Long,
        ): List<FundsOrderDispatch> =
            service
                .readDispatch(
                    assertNotNull(service.activeGeneration()),
                    settings.configuration.dispatchConfiguration.stream(market),
                    from,
                    64,
                    256L * 1_024,
                ).also {
                    assertFalse(it.isDone)
                    service.doBackgroundWork(0)
                }.join()
                .entries

        fun results(from: Long): List<MatchingResultEnvelope> =
            service
                .readResults(
                    assertNotNull(service.activeResultGeneration()),
                    settings.configuration.matchingConfiguration.resultStream(market()),
                    from,
                    8,
                    2L * 1_024 * 1_024,
                ).also {
                    assertFalse(it.isDone)
                    service.doBackgroundWork(0)
                }.join()
                .entries

        fun funds() = field(service, "funds") as BoundedFundingState

        fun matching() = field(service, "matching") as ReservedMatchingState

        fun stateBytes(): List<ByteArray> =
            listOf(
                ByteArrayOutputStream().use { output ->
                    if (settings.role == TradingServiceRole.FUNDS) {
                        FundsRoleSnapshotCodec().write(funds().fundsRecoveryState(), 0, output)
                    } else {
                        MatchingRoleSnapshotCodec().write(matching().recoveryState(), 0, output)
                    }
                    output.toByteArray()
                },
            )

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
            val restored = Fixture(settings, network, snapshot.position, false)
            restored.service.onStart(restored.cluster, snapshotImage(snapshot.chunks))
            assertNull(restored.service.activeGeneration())
            assertNull(restored.service.activeResultGeneration())
            frames.drop(snapshot.tailStart).forEach { frame ->
                val bytes = frame.bytes
                restored.position += 64
                restored.service.onSessionMessage(null, frame.at, UnsafeBuffer(bytes), 0, bytes.size, Header(0, 0))
            }
            restored.lead()
            return restored
        }
    }

    private data class Sent(
        val clusterId: Int,
        val ingress: String,
        val orderCommand: SplitOrderDeliveryCommand? = null,
        val orderReply: SplitOrderDeliveryReply? = null,
        val resultCommand: SplitResultDeliveryCommand? = null,
    )

    /** ACK는 생성하지 않는다. 실제 Adapter가 만든 응답 바이트만 유한 큐에 보관·손상·유실시킨다. */
    private inner class VirtualTransport(
        private val network: Network,
    ) {
        val observations = mutableListOf<Sent>()
        private val connections = mutableListOf<Connection>()
        val blockOrderMarkets = mutableSetOf<MarketId>()
        val blockResultMarkets = mutableSetOf<MarketId>()
        val dropOrderReplies = mutableSetOf<Int>()
        var alterOrderReply: ((SplitOrderDeliveryEndpoint, SplitOrderDeliveryReply) -> SplitOrderDeliveryReply)? = null
        val maxEntries =
            network.configuration.deliveryLimits.maxInFlightEntries + 2 * network.configuration.resultDeliveryLimits.maxInFlightEntries + 32
        val maxBytes =
            network.configuration.deliveryLimits.maxInFlightBytes + 2 * network.configuration.resultDeliveryLimits.maxInFlightBytes
        var maxQueuedEntries = 0
        var maxQueuedBytes = 0L
        private var queuedEntries = 0
        private var queuedBytes = 0L

        val orderFactory =
            SplitOrderDeliveryConnectionFactory { endpoint, _, reply ->
                resolve(endpoint)
                val connection = Connection(endpoint, false) { reply(orderCodec.decodeReply(it)) }
                connections += connection
                object : SplitOrderDeliveryConnectAttempt {
                    override fun poll(): SplitOrderDeliveryConnection = connection

                    override fun close() = Unit
                }
            }
        val resultFactory =
            SplitResultDeliveryConnectionFactory { endpoint, _, reply ->
                resolve(endpoint)
                val connection = Connection(endpoint, true) { reply(resultCodec.decodeReply(it)) }
                connections += connection
                object : SplitResultDeliveryConnectAttempt {
                    override fun poll(): SplitResultDeliveryConnection = connection

                    override fun close() = Unit
                }
            }

        fun orderConnections() = connections.filter { !it.result }

        fun orderApplies() = observations.mapNotNull { it.orderCommand as? SplitOrderDeliveryCommand.Apply }

        fun resultApplies() = observations.mapNotNull { it.resultCommand as? SplitResultDeliveryCommand.Apply }

        fun orderAcks() = observations.mapNotNull { it.orderCommand as? SplitOrderDeliveryCommand.RecordAck }

        fun resultAcks() = observations.mapNotNull { it.resultCommand as? SplitResultDeliveryCommand.RecordAck }

        private fun resolve(endpoint: SplitOrderDeliveryEndpoint): Fixture {
            val target =
                network.fixtures().single { fixture ->
                    val matching = fixture.settings.role == TradingServiceRole.MATCHING
                    fixture.settings.endpoint(matching).ingressEndpoints == endpoint.ingressEndpoints
                }
            assertEquals(target.settings.clusterId, endpoint.clusterId, "ingress와 cluster ID가 다른 담당을 가리킴")
            val expectedRole = if (endpoint.clusterId == 198) TradingServiceRole.FUNDS else TradingServiceRole.MATCHING
            assertEquals(expectedRole, target.settings.role)
            if (expectedRole == TradingServiceRole.MATCHING) {
                assertEquals(if (endpoint.clusterId == 199) btcOwner else suiOwner, target.settings.configuration.matchingLineage)
            }
            return target
        }

        inner class Connection(
            val endpoint: SplitOrderDeliveryEndpoint,
            val result: Boolean,
            private val onReply: (ByteArray) -> Unit,
        ) : SplitOrderDeliveryConnection,
            SplitResultDeliveryConnection {
            private val ready = ArrayDeque<ByteArray>()
            var closed = false
            var descriptions = 0
            override val isClosed get() = closed

            override fun offer(bytes: ByteArray): Long {
                if (closed) return Publication.CLOSED
                if (queuedEntries >= maxEntries || bytes.size > maxBytes - queuedBytes) return Publication.BACK_PRESSURED
                val target = resolve(endpoint)
                val response: ByteArray
                if (result) {
                    val command = resultCodec.decode(bytes).command
                    if (command is SplitResultDeliveryCommand.Apply &&
                        command.result.stream.marketId in blockResultMarkets
                    ) {
                        return Publication.BACK_PRESSURED
                    }
                    response = target.deliver(bytes)
                    observations += Sent(endpoint.clusterId, endpoint.ingressEndpoints, resultCommand = command)
                } else {
                    val command = orderCodec.decode(bytes).command
                    if (command is SplitOrderDeliveryCommand.Apply &&
                        command.input.stream.marketId in blockOrderMarkets
                    ) {
                        return Publication.BACK_PRESSURED
                    }
                    if (command == SplitOrderDeliveryCommand.DescribePeer) descriptions++
                    val actual = orderCodec.decodeReply(target.deliver(bytes))
                    observations += Sent(endpoint.clusterId, endpoint.ingressEndpoints, command, actual)
                    if (endpoint.clusterId in dropOrderReplies && command is SplitOrderDeliveryCommand.Apply) return 1L
                    response = orderCodec.encodeReply(alterOrderReply?.invoke(endpoint, actual) ?: actual)
                }
                check(observations.size <= 10_000) { "전송 관찰 상한" }
                check(queuedEntries < maxEntries && response.size <= maxBytes - queuedBytes) { "실제 응답 바이트 예산 초과" }
                ready += response
                queuedEntries++
                queuedBytes += response.size
                maxQueuedEntries = maxOf(maxQueuedEntries, queuedEntries)
                maxQueuedBytes = maxOf(maxQueuedBytes, queuedBytes)
                return 1L
            }

            override fun poll(): Int {
                if (closed || ready.isEmpty()) return 0
                val bytes = ready.removeFirst()
                queuedEntries--
                queuedBytes -= bytes.size
                onReply(bytes)
                return 1
            }

            override fun keepAlive() = !closed

            override fun close() {
                if (closed) return
                closed = true
                while (ready.isNotEmpty()) {
                    queuedEntries--
                    queuedBytes -= ready.removeFirst().size
                }
            }
        }
    }

    private fun manifest(fee: Long): TradingClusterManifest {
        val policy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(fee), FeeRate(fee)))
        return TradingClusterManifest(
            ClusterLineage(10, 11),
            "multi-matching-flow",
            BoundedExecutionIdentity.artifactSha256,
            BoundedExecutionIdentity.schemaSha256,
            7,
            listOf(TradingMarketContext(btc, policy, 0), TradingMarketContext(sui, policy, 1)),
            TradingResourceLimits(),
        )
    }

    private fun changedResult(result: MatchingResultEnvelope): MatchingResultEnvelope =
        matchingResult(
            result,
            effects =
                result.effects.map {
                    it.copy(afterQuantity = Quantity.ZERO, disposition = MatchingOrderDisposition.TERMINAL)
                },
        )

    private fun matchingResult(
        result: MatchingResultEnvelope,
        stream: com.exchange.core.trading.MatchingResultStream = result.stream,
        effects: List<com.exchange.core.trading.MatchingOrderEffect> = result.effects,
    ) = MatchingResultEnvelope(
        stream,
        result.resultSequence,
        result.input,
        result.outcome,
        result.reason,
        result.engineSequenceBefore,
        result.engineSequenceAfter,
        result.events,
        effects,
    )

    private fun field(
        target: Any,
        name: String,
    ): Any? =
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(target)
        }

    private fun assertChunks(
        expected: List<ByteArray>,
        actual: List<ByteArray>,
    ) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (left, right) -> assertTrue(left.contentEquals(right), "역할 snapshot 상태가 바뀜") }
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
}
