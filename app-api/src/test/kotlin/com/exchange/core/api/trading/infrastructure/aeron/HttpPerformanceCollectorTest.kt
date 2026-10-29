package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionState
import io.aeron.cluster.service.Cluster
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** control은 독립 메모리 상태를 사용해 측정 workload에 섞이지 않는다. */
class HttpPerformanceCollectorTest {
    private val now = Instant.parse("2026-10-26T00:00:00Z")
    private val market = DevelopmentMarketContext.BTC_USDT.market
    private val policy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(1000), FeeRate(2000)))

    @Test
    fun `최초 역할 변경 callback이 없는 팔로워도 실제 onStart 역할을 관찰한다`() {
        val cluster = mock(Cluster::class.java)
        `when`(cluster.role()).thenReturn(Cluster.Role.FOLLOWER)
        `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
        val service = DevelopmentFundingAdapter(tradingState(), onFatal = {})
        val collector = HttpPerformanceCollector("collector-test", 1) {}
        val decorated = collector.decorate(service)
        decorated.onStart(cluster, null)
        assertTrue(service.started)
        assertEquals("FOLLOWER", collector.role)
        assertEquals(0L, collector.roleChangeCount)
        decorated.onRoleChange(Cluster.Role.LEADER)
        assertEquals("LEADER", collector.role)
        assertEquals(1L, collector.roleChangeCount)
    }

    @Test
    fun `상대 없는 완료는 정산을 세지 않고 한 완전체결은 손계산한 경제 상태와 대응한다`() {
        val fixture = Fixture()
        fixture.fund("SELLER", "BTC", 100_000_000)
        fixture.fund("BUYER", "USDT", 11_020)
        fixture.admit("S-1", "SELLER", Side.SELL)
        fixture.execute(1)
        assertEquals(0L, fixture.markers.single().tradeCount)
        assertEquals(0L, fixture.markers.single().settlementCount)

        fixture.admit("M-1", "BUYER", Side.BUY)
        fixture.execute(2)
        val marker = fixture.markers.last()
        assertEquals("M-1", marker.requestId)
        assertEquals(2L, marker.admissionSequence)
        assertEquals(1L, marker.tradeCount)
        assertEquals(1L, marker.settlementCount)
        val balances =
            fixture.state.snapshot().balances.associate {
                (it.userId.value to it.assetId.value) to
                    (it.available.value to it.hold.value)
            }
        assertEquals(1000L to 0L, balances["BUYER" to "USDT"])
        assertEquals(100_000_000L to 0L, balances["BUYER" to "BTC"])
        assertEquals(9990L to 0L, balances["SELLER" to "USDT"])
        assertEquals(0L to 0L, balances["SELLER" to "BTC"])
        assertNull(fixture.collector.error)
    }

    @Test
    fun `완료된 요청 재전송과 내부 실행 재사용은 고유 업무 완료를 늘리지 않는다`() {
        val fixture = Fixture()
        fixture.fund("BUYER", "USDT", 20_040)
        fixture.admit("M-1", "BUYER", Side.BUY)
        fixture.execute(1)
        val original = fixture.state.snapshot()
        fixture.admit("M-1", "BUYER", Side.BUY)
        fixture.execute(1)
        assertEquals(1, fixture.markers.size)
        assertEquals(original, fixture.state.snapshot())
        assertNull(fixture.collector.error)
    }

    @Test
    fun `10019 잔고의 거절은 예약 체결 완료 표식을 추가하지 않는다`() {
        val fixture = Fixture()
        fixture.fund("POOR", "USDT", 10_019)
        val result = fixture.admit("R-POOR", "POOR", Side.BUY)
        assertIs<OrderAdmissionState.Result.Rejected>(result)
        assertEquals(0, fixture.markers.size)
        val snapshot = fixture.state.snapshot()
        assertEquals(1, snapshot.requests.size)
        assertEquals(1, snapshot.ledgerTransactions.size)
        assertTrue(snapshot.reservations.isEmpty() && snapshot.completions.isEmpty() && snapshot.pendingOrders.isEmpty())
        assertNull(fixture.collector.error)
    }

    @Test
    fun `관찰 실패가 있어도 이미 완료한 업무 상태를 거절하거나 중단하지 않는다`() {
        var sendAttempts = 0
        val fixture =
            Fixture {
                sendAttempts++
                throw IllegalStateException("observer failed")
            }
        fixture.fund("BUYER", "USDT", 10_020)
        fixture.admit("M-1", "BUYER", Side.BUY)
        fixture.execute(1)
        assertNotNull(fixture.collector.error)
        assertFalse(fixture.state.isBlocked)
        assertEquals(
            1,
            fixture.state
                .snapshot()
                .completions.size,
        )
        val completed = fixture.state.snapshot()
        fixture.execute(1)
        assertEquals(1, sendAttempts)
        assertEquals(completed, fixture.state.snapshot())
    }

    private inner class Fixture(
        extraSend: ((HttpPerformanceCompletion) -> Unit)? = null,
    ) {
        val state = tradingState()
        val markers = mutableListOf<HttpPerformanceCompletion>()
        val collector =
            HttpPerformanceCollector("collector-test", 0) { marker ->
                extraSend?.invoke(marker)
                markers.add(marker)
            }

        fun fund(
            user: String,
            asset: String,
            amount: Long,
        ) {
            state.prepareDevelopmentBalance(
                OpeningBalance("P-$user-$asset", UserId(user), AssetId(asset), Amount(amount)),
                "OPEN-$user-$asset",
                now,
            )
        }

        fun admit(
            id: String,
            user: String,
            side: Side,
        ): OrderAdmissionState.Result {
            val order =
                OrderAdmissionInput(
                    market.marketId,
                    OrderId(id),
                    UserId(user),
                    side,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    Price(10_000),
                    Quantity(100_000_000),
                )
            val result = state.admit(id, order, market, policy, "RESERVE-$id", now)
            val wire =
                when (result) {
                    is OrderAdmissionState.Result.Accepted -> {
                        AdmissionResultPayload.Accepted(
                            user,
                            id,
                            market.marketId.value,
                            id,
                            result.record.sequence,
                        )
                    }

                    is OrderAdmissionState.Result.Rejected -> {
                        AdmissionResultPayload.Rejected(
                            user,
                            id,
                            market.marketId.value,
                            id,
                            AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE,
                        )
                    }
                }
            collector.observe(FundingObservation(128, null, null, admissionResult = wire, appliedRole = "LEADER"))
            return result
        }

        fun execute(sequence: Long) {
            val completion = state.executePending(sequence, now)
            collector.observe(
                FundingObservation(
                    256 + sequence * 128,
                    null,
                    null,
                    executionSequence = sequence,
                    appliedRole = "LEADER",
                    executionCompletion = completion,
                ),
            )
        }
    }
}
