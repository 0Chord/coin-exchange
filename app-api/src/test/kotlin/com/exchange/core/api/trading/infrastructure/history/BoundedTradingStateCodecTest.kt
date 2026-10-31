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
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.ExecuteAdmission
import com.exchange.core.trading.ExecutionReply
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

class BoundedTradingStateCodecTest {
    private val codec = BoundedTradingStateCodec()
    private val manifest =
        TradingClusterManifest(
            ClusterLineage(1, 2),
            "bounded-r1",
            "0".repeat(64),
            "1".repeat(64),
            1,
            listOf(
                TradingMarketContext(
                    MarketDefinition(MarketId("BTC-USDT"), AssetId("BTC"), AssetId("USDT"), 8),
                    TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)),
                    0,
                ),
            ),
            TradingResourceLimits(),
        )

    @Test
    fun `manifest and minimal snapshot restore pending reservation without a history index`() {
        assertEquals(manifest, codec.decodeManifest(codec.encodeManifest(manifest)))
        val state = state()
        state.initialize(manifest)
        val user = UserId("U1")
        state.prepareDevelopmentBalance(OpeningBalance("P1", user, AssetId("USDT"), Amount(1_000)), "IGNORED-EXTERNAL-ID", Instant.EPOCH)
        state.rotateEpoch(user, 0, 0)
        val token = RequestToken(user, 0, 1, 1)
        val intent = OrderIntent(MarketId("BTC-USDT"), 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(300_000_000))
        val receipt = assertIs<AdmissionReply.Recorded>(state.admit(token, intent, Instant.EPOCH)).receipt
        val saved = state.recoveryState()
        val bytes = ByteArrayOutputStream().also { codec.write(saved, 512, it) }.toByteArray()
        val restored = state()
        restored.restoreRecoveryState(codec.read(ByteArrayInputStream(bytes), manifest, 512))
        assertEquals(Balance(user, AssetId("USDT"), Amount(700), Amount(300)), restored.currentBalance(user, AssetId("USDT")))
        assertEquals(receipt, assertIs<AdmissionReply.Recorded>(restored.admit(token, intent, Instant.EPOCH)).receipt)
        assertIs<ExecutionReply.Applied>(
            restored.executePending(
                ExecuteAdmission(
                    manifest.lineage,
                    intent.marketId,
                    receipt.marketAdmissionSequence!!,
                    receipt.admissionSequence!!,
                ),
                Instant.EPOCH,
            ),
        )
        assertEquals(1, restored.boundedRetention().liveOrders)
    }

    @Test
    fun `wrong position artifact checksum and trailing bytes cannot restore`() {
        val state = state().also { it.initialize(manifest) }
        val bytes = ByteArrayOutputStream().also { codec.write(state.recoveryState(), 64, it) }.toByteArray()
        assertFails { codec.read(ByteArrayInputStream(bytes), manifest, 65) }
        assertFails {
            codec.read(
                ByteArrayInputStream(bytes.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }),
                manifest,
                64,
            )
        }
        assertFails { codec.read(ByteArrayInputStream(bytes + byteArrayOf(1)), manifest, 64) }
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
}
