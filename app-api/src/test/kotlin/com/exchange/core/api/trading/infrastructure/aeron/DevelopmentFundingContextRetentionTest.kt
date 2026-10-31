package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingState
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.Header
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 코어가 보관한 원본을 검사한다. callback의 문맥 재사용만 검증하며 네트워크는 사용하지 않는다. */
class DevelopmentFundingContextRetentionTest {
    private val commands = AdmissionCommandCodec()
    private val context = DevelopmentMarketContext.BTC_USDT
    private val time = Instant.parse("2026-10-27T00:00:00Z")

    @Test
    fun `별도로 복원한 두 신규 주문은 검증한 등록 문맥을 실제 이력에서 공유한다`() {
        val f = fixture()
        fund(f.state)
        val first = decoded("R1", "O1")
        val second = decoded("R2", "O2")
        assertEquals(context.market, first.market)
        assertEquals(context.feePolicySnapshot, first.feePolicySnapshot)
        assertNotSame(context.market, first.market)
        assertNotSame(context.feePolicySnapshot, first.feePolicySnapshot)
        assertNotSame(first.market, second.market)
        assertNotSame(first.feePolicySnapshot, second.feePolicySnapshot)
        assertNotSame(first.feePolicySnapshot.feeRates, second.feePolicySnapshot.feeRates)

        listOf(first, second).forEach { command ->
            assertIs<AdmissionResultPayload.Accepted>(admit(f, command))
        }

        val pending = f.state.snapshot()
        assertEquals(2, pending.requests.size)
        assertEquals(2, pending.pendingOrders.size)
        pending.requests.zip(listOf(first, second)).forEach { (request, original) ->
            assertSame(context.market, request.market)
            assertSame(context.feePolicySnapshot, request.feePolicySnapshot)
            assertSame(context.feePolicySnapshot.feeRates, request.feePolicySnapshot.feeRates)
            assertEquals(original.order, request.order)
            assertEquals(original.ledgerTransactionId, request.ledgerTransactionId)
            assertEquals(original.occurredAt, request.occurredAt)
            val record = assertIs<OrderAdmissionState.Result.Accepted>(request.result).record
            assertSame(context.market, record.candidate.market)
            assertSame(context.feePolicySnapshot, record.candidate.feePolicySnapshot)
            assertSame(context.feePolicySnapshot, record.candidate.reservation.feePolicySnapshot)
            assertSame(request.order, record.candidate.order)
        }
        pending.pendingOrders.forEach { f.state.executePending(it.sequence, time) }
        val completed = f.state.snapshot()
        assertEquals(2, completed.completions.size)
        completed.completions.forEach {
            assertSame(context.market, it.admission.candidate.market)
            assertSame(context.market, it.settlement.market)
            assertSame(context.feePolicySnapshot, it.admission.candidate.reservation.feePolicySnapshot)
        }
        val balance = completed.balances.single()
        assertEquals(400_000_000L, balance.available.value)
        assertEquals(600_000_000L, balance.hold.value)
        assertEquals(3, completed.ledgerTransactions.size)
        assertTrue(f.failures.isEmpty())
    }

    @Test
    fun `유효한 신규 부족 거절도 등록 문맥과 원본 결과를 보관한다`() {
        val f = fixture()
        val command = decoded("poor", "poor-order")

        val rejected = assertIs<AdmissionResultPayload.Rejected>(admit(f, command))

        assertEquals(AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE, rejected.reason)
        val snapshot = f.state.snapshot()
        val request = snapshot.requests.single()
        assertSame(context.market, request.market)
        assertSame(context.feePolicySnapshot, request.feePolicySnapshot)
        assertEquals(command.order, request.order)
        assertEquals(command.ledgerTransactionId, request.ledgerTransactionId)
        assertEquals(command.occurredAt, request.occurredAt)
        assertTrue(snapshot.ledgerTransactions.isEmpty())
        assertTrue(snapshot.pendingOrders.isEmpty())
        assertTrue(f.failures.isEmpty())
    }

    @Test
    fun `이미 보관한 승인과 거절은 현재 설정 검사보다 먼저 원본 결과를 재사용한다`() {
        listOf(false, true).forEach { funded ->
            val f = fixture()
            if (funded) fund(f.state)
            val original =
                decoded("old", "old-order").let {
                    it.copy(
                        market = it.market.copy(priceTick = 2),
                        feePolicySnapshot = it.feePolicySnapshot.copy(scheduleVersion = 2),
                    )
                }
            val first =
                f.state.admit(
                    original.requestId,
                    original.order,
                    original.market,
                    original.feePolicySnapshot,
                    original.ledgerTransactionId,
                    original.occurredAt,
                )
            val before = f.state.snapshot()
            val recorded = before.requests.single()
            val retry =
                original.copy(
                    market = original.market.copy(baseAssetId = AssetId("ETH")),
                    feePolicySnapshot = original.feePolicySnapshot.copy(scheduleVersion = 3),
                    ledgerTransactionId = "",
                    occurredAt = Instant.MAX,
                )

            assertEquals(AdmissionResultPayload.from(original, first), admit(f, retry))
            val conflict = retry.copy(order = retry.order.copy(price = Price(retry.order.price.value + 1)))
            assertEquals(
                AdmissionResultPayload.Reason.REQUEST_CONTENT_CONFLICT,
                assertIs<AdmissionResultPayload.Rejected>(admit(f, conflict)).reason,
            )

            val after = f.state.snapshot()
            assertSame(recorded, after.requests.single())
            assertSame(first, after.requests.single().result)
            assertSame(original.market, after.requests.single().market)
            assertSame(original.feePolicySnapshot, after.requests.single().feePolicySnapshot)
            assertEquals(before.ledgerTransactions, after.ledgerTransactions)
            assertEquals(before.balances, after.balances)
            assertEquals(before.pendingOrders, after.pendingOrders)
            assertEquals(before.lastAdmissionSequence, after.lastAdmissionSequence)
            assertFalse(f.state.isBlocked)
            assertTrue(f.failures.isEmpty())
        }
    }

    @Test
    fun `신규 요청의 모든 문맥 불일치를 거절하고 고친 같은 요청만 등록 문맥으로 접수한다`() {
        val prototype = decoded("context", "context-order")
        val mismatches =
            listOf(prototype.copy(order = prototype.order.copy(marketId = MarketId("ETH-USDT")))) +
                contextChanges(prototype)
        assertEquals(12, mismatches.size)
        mismatches.forEach { mismatch ->
            val f = fixture()
            fund(f.state)
            val before = f.state.snapshot()

            val rejected = assertIs<AdmissionResultPayload.Rejected>(admit(f, mismatch))

            assertEquals(AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH, rejected.reason)
            val rejectedState = f.state.snapshot()
            assertTrue(rejectedState.requests.isEmpty())
            assertTrue(rejectedState.pendingOrders.isEmpty())
            assertEquals(before.ledgerTransactions, rejectedState.ledgerTransactions)
            assertEquals(before.balances, rejectedState.balances)
            assertIs<AdmissionResultPayload.Accepted>(admit(f, prototype))
            val acceptedState = f.state.snapshot()
            val request = acceptedState.requests.single()
            assertSame(context.market, request.market)
            assertSame(context.feePolicySnapshot, request.feePolicySnapshot)
            assertEquals(prototype.order, request.order)
            assertTrue(f.failures.isEmpty())
        }
    }

    private fun contextChanges(command: AdmissionCommandPayload): List<AdmissionCommandPayload> =
        listOf(
            command.copy(market = command.market.copy(marketId = MarketId("ETH-USDT"))),
            command.copy(market = command.market.copy(baseAssetId = AssetId("ETH"))),
            command.copy(market = command.market.copy(quoteAssetId = AssetId("KRW"))),
            command.copy(market = command.market.copy(baseAssetScale = 7)),
            command.copy(market = command.market.copy(priceTick = 2)),
            command.copy(market = command.market.copy(quantityStep = 200_000_000)),
            command.copy(feePolicySnapshot = command.feePolicySnapshot.copy(productType = FeeProductType.PERPETUAL_FUTURES)),
            command.copy(feePolicySnapshot = command.feePolicySnapshot.copy(feeTier = FeeTier.VIP)),
            command.copy(feePolicySnapshot = command.feePolicySnapshot.copy(scheduleVersion = 2)),
            command.copy(
                feePolicySnapshot =
                    command.feePolicySnapshot.copy(
                        feeRates = command.feePolicySnapshot.feeRates.copy(makerFeeRate = FeeRate(1_000)),
                    ),
            ),
            command.copy(
                feePolicySnapshot =
                    command.feePolicySnapshot.copy(
                        feeRates = command.feePolicySnapshot.feeRates.copy(takerFeeRate = FeeRate(2_000)),
                    ),
            ),
        )

    private fun decoded(
        requestId: String,
        orderId: String,
    ): AdmissionCommandPayload =
        // 서로 다른 codec이 복원한 객체를 준비해, codec 내부 재사용과 등록 문맥 재사용을 구분한다.
        AdmissionCommandCodec().decode(commands.encode(admissionCommand(requestId, orderId)))

    private fun fund(state: TradingState) {
        state.prepareDevelopmentBalance(
            OpeningBalance("P1", admissionCommand().order.userId, AssetId("USDT"), Amount(1_000_000_000)),
            "opening-ledger",
            time,
        )
    }

    private fun admit(
        f: Fixture,
        command: AdmissionCommandPayload,
    ): AdmissionResultPayload {
        val bytes = commands.encode(command)
        f.service.onSessionMessage(null, 1_000, clusterFrame(bytes), AeronCluster.SESSION_HEADER_LENGTH, bytes.size, Header(0, 0))
        return checkNotNull(f.service.observation?.admissionResult)
    }

    private fun fixture(): Fixture {
        val state = tradingState()
        val cluster = mock(Cluster::class.java)
        `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
        ClusterExecutionClaimStub(cluster)
        `when`(cluster.role()).thenReturn(Cluster.Role.LEADER)
        `when`(cluster.logPosition()).thenReturn(256L)
        val failures = mutableListOf<Throwable>()
        val service = DevelopmentFundingAdapter(state, markets = DevelopmentMarketRegistry(listOf(context))) { failures += it }
        service.onStart(cluster, null)
        return Fixture(state, service, failures)
    }

    private data class Fixture(
        val state: TradingState,
        val service: DevelopmentFundingAdapter,
        val failures: MutableList<Throwable>,
    )
}
