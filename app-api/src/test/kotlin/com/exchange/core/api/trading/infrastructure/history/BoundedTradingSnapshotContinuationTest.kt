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
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingState
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoundedTradingSnapshotContinuationTest {
    private val codec = BoundedTradingStateCodec()
    private val btc = AssetId("BTC")
    private val usdt = AssetId("USDT")
    private val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 0)
    private val at = Instant.parse("2026-10-30T00:00:00Z")
    private val manifest =
        TradingClusterManifest(
            ClusterLineage(71, 72),
            "snapshot-continuation-v1",
            "0".repeat(64),
            "1".repeat(64),
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

    @Test
    fun `snapshot preserves maker FIFO fee carry and pending work before identical subsequent settlements`() {
        val original = state().also { it.initialize(manifest) }
        val lowAsk = UserId("low-ask")
        val highAsk = UserId("high-ask")
        val firstBid = UserId("first-bid")
        val secondBid = UserId("second-bid")
        val initialTaker = UserId("initial-taker")
        val pendingSeller = UserId("pending-seller")
        val finalTaker = UserId("final-taker")
        for (user in listOf(firstBid, secondBid, initialTaker, finalTaker)) fund(original, user, usdt, 1_000)
        fund(original, lowAsk, btc, 1)
        fund(original, highAsk, btc, 3)
        fund(original, pendingSeller, btc, 3)
        for (user in listOf(lowAsk, highAsk, firstBid, secondBid, initialTaker, pendingSeller, finalTaker)) {
            original.rotateEpoch(user, 0, 0)
        }

        execute(original, admit(original, lowAsk, Side.SELL, 90, 1))
        val highAskReceipt = admit(original, highAsk, Side.SELL, 110, 3)
        execute(original, highAskReceipt)
        // 이 BUY는 먼저 90에 1을 체결한 뒤 taker 나머지를 유지한 채 100에 2를 남긴다.
        val firstBidReceipt = admit(original, firstBid, Side.BUY, 100, 3)
        execute(original, firstBidReceipt)
        val secondBidReceipt = admit(original, secondBid, Side.BUY, 100, 2)
        execute(original, secondBidReceipt)
        // maker 일부 체결 후 상대 주문의 예약에는 다른 나머지가 남는다.
        execute(original, admit(original, initialTaker, Side.BUY, 110, 1))
        val pendingIntent = intent(Side.SELL, 100, 3)
        val pendingToken = RequestToken(pendingSeller, 0, 1, 1)
        val pending = assertIs<AdmissionReply.Recorded>(original.admit(pendingToken, pendingIntent, at)).receipt
        assertEquals(AdmissionCode.ACCEPTED, pending.code)

        val before = original.recoveryState()
        val bookBefore = before.matching.markets.single()
        assertEquals(
            listOf(firstBidReceipt.orderId, secondBidReceipt.orderId, highAskReceipt.orderId),
            bookBefore.orders.map { it.orderId },
        )
        assertEquals(listOf(Quantity(2), Quantity(2), Quantity(2)), bookBefore.orders.map { it.remainingQuantity })
        assertEquals(
            630_000L,
            before.liveOrders
                .single { it.order.orderId == firstBidReceipt.orderId }
                .reservation.feeRemainder.numerator,
        )
        assertEquals(
            440_000L,
            before.liveOrders
                .single { it.order.orderId == highAskReceipt.orderId }
                .reservation.feeRemainder.numerator,
        )
        assertEquals(4, before.liveOrders.size)
        assertEquals(LiveOrderPhase.PENDING, before.liveOrders.single { it.order.orderId == pending.orderId }.phase)
        assertEquals(6L, before.markets.single().lastAssigned)
        assertEquals(5L, before.markets.single().executedThrough)
        assertEquals(listOf(pending.admissionSequence), before.markets.single().pendingAdmissionSequences)
        assertBalance(original, firstBid, usdt, 707, 203)
        assertBalance(original, pendingSeller, btc, 0, 3)

        val snapshot = bytes(original)
        val restored =
            state().also {
                it.restoreRecoveryState(codec.read(ByteArrayInputStream(snapshot), manifest, SNAPSHOT_POSITION))
            }
        // 직렬화 형식과 실제 복구 경로가 현재 상태의 모든 구획을 보존해야 한다.
        assertContentEquals(snapshot, bytes(restored))
        assertEquals(
            bookBefore.orders,
            restored
                .recoveryState()
                .matching.markets
                .single()
                .orders,
        )
        val repeated = assertIs<AdmissionReply.Recorded>(restored.admit(pendingToken, pendingIntent, at))
        assertFalse(repeated.newlyRecorded)
        assertEquals(pending, repeated.receipt)
        assertContentEquals(snapshot, bytes(restored))

        val uninterruptedExecution = execute(original, pending)
        val restoredExecution = execute(restored, pending)
        assertEquals(uninterruptedExecution, restoredExecution)
        assertEquals(2, restoredExecution.receipt.fillCount)
        assertFalse(restoredExecution.receipt.resting)
        assertContentEquals(bytes(original), bytes(restored))
        for (owner in listOf(original, restored)) {
            // FIFO에 따라 firstBid의 잔량 2를 모두 체결한 뒤 secondBid에서 1만 체결한다.
            val live = owner.recoveryState().liveOrders
            assertTrue(live.none { it.order.orderId == firstBidReceipt.orderId || it.order.orderId == pending.orderId })
            val second = live.single { it.order.orderId == secondBidReceipt.orderId }
            assertEquals(Quantity(1), second.reservation.remainingQuantity)
            assertEquals(400_000L, second.reservation.feeRemainder.numerator)
            assertBalance(owner, firstBid, btc, 3, 0)
            assertBalance(owner, firstBid, usdt, 709, 0)
            assertBalance(owner, secondBid, btc, 1, 0)
            assertBalance(owner, secondBid, usdt, 798, 102)
            assertBalance(owner, pendingSeller, usdt, 298, 0)
            // maker 수수료는 200*.004 + .630 = 1.430, taker 수수료는 200*.007 + 100*.007 = 2.100이다.
            assertEquals(BigInteger.valueOf(3), owner.recoveryState().feeRevenueByAsset[usdt])
            val completed = bytes(owner)
            assertEquals(ExecutionReply.AlreadyPassed, owner.executePending(command(pending), at))
            assertContentEquals(completed, bytes(owner))
        }

        // 복구한 SELL maker의 이월액 .440이 새 수수료 .880과 합쳐져 수수료 1을 청구한다.
        val finalOriginal = admit(original, finalTaker, Side.BUY, 110, 2)
        val finalRestored = admit(restored, finalTaker, Side.BUY, 110, 2)
        assertEquals(finalOriginal, finalRestored)
        assertEquals(execute(original, finalOriginal), execute(restored, finalRestored))
        assertContentEquals(bytes(original), bytes(restored))
        for (owner in listOf(original, restored)) {
            val completed = owner.recoveryState()
            assertEquals(BigInteger.valueOf(5), completed.feeRevenueByAsset[usdt])
            assertBalance(owner, highAsk, usdt, 329, 0)
            assertBalance(owner, finalTaker, usdt, 779, 0)
            assertBalance(owner, finalTaker, btc, 2, 0)
            assertEquals(
                listOf(secondBidReceipt.orderId),
                completed.matching.markets
                    .single()
                    .orders
                    .map { it.orderId },
            )
            assertEquals(
                Quantity(1),
                completed.matching.markets
                    .single()
                    .orders
                    .single()
                    .remainingQuantity,
            )
            assertEquals(1, completed.liveOrders.size)
            assertTrue(
                completed.markets
                    .single()
                    .pendingAdmissionSequences
                    .isEmpty(),
            )
            assertEquals(7L, completed.markets.single().lastAssigned)
            assertEquals(7L, completed.markets.single().executedThrough)
            assertEquals(7L, completed.balances.filter { it.assetId == btc }.sumOf { it.available.value + it.hold.value })
            assertEquals(
                4_000L,
                completed.balances.filter { it.assetId == usdt }.sumOf { it.available.value + it.hold.value } +
                    completed.feeRevenueByAsset.getValue(usdt).longValueExact(),
            )
        }
    }

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
        user: UserId,
        asset: AssetId,
        value: Long,
    ) {
        owner.prepareDevelopmentBalance(OpeningBalance("${user.value}-${asset.value}", user, asset, Amount(value)), "ignored", at)
    }

    private fun intent(
        side: Side,
        price: Long,
        quantity: Long,
    ) = OrderIntent(
        market.marketId,
        manifest.configurationVersion,
        side,
        OrderType.LIMIT,
        TimeInForce.GTC,
        Price(price),
        Quantity(quantity),
    )

    private fun admit(
        owner: TradingState,
        user: UserId,
        side: Side,
        price: Long,
        quantity: Long,
    ): AdmissionReceipt {
        val reply = assertIs<AdmissionReply.Recorded>(owner.admit(RequestToken(user, 0, 1, 1), intent(side, price, quantity), at))
        assertTrue(reply.newlyRecorded)
        assertEquals(AdmissionCode.ACCEPTED, reply.receipt.code)
        return reply.receipt
    }

    private fun command(receipt: AdmissionReceipt) =
        ExecuteAdmission(
            manifest.lineage,
            market.marketId,
            requireNotNull(receipt.marketAdmissionSequence),
            requireNotNull(receipt.admissionSequence),
        )

    private fun execute(
        owner: TradingState,
        receipt: AdmissionReceipt,
    ) = assertIs<ExecutionReply.Applied>(owner.executePending(command(receipt), at))

    private fun bytes(owner: TradingState): ByteArray =
        ByteArrayOutputStream().also { codec.write(owner.recoveryState(), SNAPSHOT_POSITION, it) }.toByteArray()

    private fun assertBalance(
        owner: TradingState,
        user: UserId,
        asset: AssetId,
        available: Long,
        hold: Long,
    ) {
        assertEquals(Balance(user, asset, Amount(available), Amount(hold)), owner.currentBalance(user, asset))
    }

    companion object {
        private const val SNAPSHOT_POSITION = 4_096L
    }
}
