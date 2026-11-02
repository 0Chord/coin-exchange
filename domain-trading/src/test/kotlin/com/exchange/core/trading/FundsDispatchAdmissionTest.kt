package com.exchange.core.trading

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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FundsDispatchAdmissionTest {
    private val quote = AssetId("QUOTE")
    private val buyer = UserId("buyer")
    private val at = Instant.parse("2026-11-02T00:00:00Z")
    private val a = MarketDefinition(MarketId("A-QUOTE"), AssetId("A"), quote, 0)
    private val b = MarketDefinition(MarketId("B-QUOTE"), AssetId("B"), quote, 0)
    private val fees = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
    private val contexts = listOf(TradingMarketContext(a, fees, 0), TradingMarketContext(b, fees, 1))
    private val manifest =
        TradingClusterManifest(ClusterLineage(1, 2), "dispatch-test", "0".repeat(64), "1".repeat(64), 7, contexts, TradingResourceLimits())
    private val topology =
        TradingRoleTopology(
            manifest,
            listOf(
                TradingMarketAssignment(a.marketId, ClusterLineage(3, 4), 1),
                TradingMarketAssignment(b.marketId, ClusterLineage(5, 6), 2),
            ),
        )
    private val configuration = FundsDispatchConfiguration(topology)

    @Test
    fun `예약과 전달 상태를 함께 만들고 재전송과 충돌로 추가하지 않는다`() {
        val owner = owner()
        fund(owner)
        val before = owner.fundsRecoveryState()
        val accepted = assertIs<AdmissionReply.Recorded>(admit(owner, 1, a, 300))
        val after = owner.fundsRecoveryState()
        assertEquals(AdmissionCode.ACCEPTED, accepted.receipt.code)
        assertEquals(Balance(buyer, quote, Amount(700), Amount(300)), owner.currentBalance(buyer, quote))
        assertEquals(1, after.financial.liveOrders.size)
        assertNotEquals(before.dispatch, after.dispatch)
        assertEquals(1, after.dispatch.dispatchEntries)
        val stream = after.dispatch.streams.single { it.stream.marketId == a.marketId }
        assertEquals(1L, stream.issued)
        assertEquals(0L, stream.matchingAppliedThrough)
        assertEquals(listOf(1L), stream.outstanding.map { it.dispatchSequence })
        assertEquals(
            after.financial.liveOrders
                .single()
                .order,
            stream.outstanding.single().order,
        )
        assertEquals(
            after.financial.liveOrders
                .single()
                .reservation,
            stream.outstanding.single().reservation,
        )
        val repeated = assertIs<AdmissionReply.Recorded>(admit(owner, 1, a, 300))
        assertFalse(repeated.newlyRecorded)
        assertEquals(accepted.receipt, repeated.receipt)
        assertSame(after, owner.fundsRecoveryState())
        assertEquals(AdmissionReply.Refused("REQUEST_CONTENT_CONFLICT"), admit(owner, 1, b, 300))
        assertSame(after, owner.fundsRecoveryState())
    }

    @Test
    fun `매칭 적용 확인은 예약 원본 사용자 결과와 금융 진행을 유지한다`() {
        val owner = owner()
        fund(owner)
        admit(owner, 1, a, 300)
        val before = owner.fundsRecoveryState()
        owner.acknowledgeMatchingApplied(FundsMatchingAppliedAck(configuration.stream(a.marketId), 1, contexts[0]))
        val after = owner.fundsRecoveryState()
        assertNotEquals(before.dispatch, after.dispatch)
        assertEquals(0, after.dispatch.dispatchEntries)
        assertEquals(0L, after.dispatch.dispatchBytes)
        assertEquals(1, after.dispatch.activeOrders)
        assertEquals(before.dispatch.activeBytes, after.dispatch.activeBytes)
        assertFinancialSame(before.financial, after.financial)
        assertEquals(
            1,
            after.financial.markets
                .first()
                .pendingAdmissionSequences.size,
        )
        assertEquals(
            0L,
            after.financial.markets
                .first()
                .executedThrough,
        )
        assertEquals(Balance(buyer, quote, Amount(700), Amount(300)), owner.currentBalance(buyer, quote))
        owner.acknowledgeMatchingApplied(FundsMatchingAppliedAck(configuration.stream(a.marketId), 1, contexts[0]))
        assertSame(after, owner.fundsRecoveryState())
    }

    @Test
    fun `사용자 확인과 세대 회전으로 전달 책임이 사라지지 않는다`() {
        for (rotate in listOf(false, true)) {
            val owner = owner()
            fund(owner)
            admit(owner, 1, a, 300)
            val before = owner.fundsRecoveryState()
            if (rotate) owner.rotateEpoch(buyer, 0, 1) else owner.acknowledgeReceipts(buyer, 0, 1, 1)
            val after = owner.fundsRecoveryState()
            assertEquals(before.dispatch, after.dispatch)
            assertEquals(before.financial.liveOrders, after.financial.liveOrders)
            assertEquals(before.financial.balances, after.financial.balances)
            assertIs<AdmissionReply.Refused>(admit(owner, 1, a, 300))
        }
    }

    @Test
    fun `두 마켓은 같은 자금을 순서대로 사용하고 부족 거절은 전달하지 않는다`() {
        for ((firstMarket, reserved) in listOf(a to 600L, b to 500L)) {
            val owner = owner()
            fund(owner)
            val secondMarket = if (firstMarket == a) b else a
            val available = if (firstMarket == a) 400L else 500L
            assertEquals(AdmissionCode.ACCEPTED, assertIs<AdmissionReply.Recorded>(admit(owner, 1, firstMarket, reserved)).receipt.code)
            val before = owner.fundsRecoveryState()
            val rejected = assertIs<AdmissionReply.Recorded>(admit(owner, 2, secondMarket, if (reserved == 600L) 500 else 600))
            assertEquals(AdmissionCode.REJECTED, rejected.receipt.code)
            assertEquals(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE, rejected.receipt.reason)
            assertEquals(before.dispatch, owner.fundsRecoveryState().dispatch)
            assertEquals(Balance(buyer, quote, Amount(available), Amount(reserved)), owner.currentBalance(buyer, quote))
        }
    }

    @Test
    fun `기록한 부족 거절은 자금이 생긴 뒤에도 최초 결과를 유지한다`() {
        val owner = owner()
        val rejected = assertIs<AdmissionReply.Recorded>(admit(owner, 1, a, 300))
        val dispatch = owner.fundsRecoveryState().dispatch
        fund(owner)
        val repeated = assertIs<AdmissionReply.Recorded>(admit(owner, 1, a, 300))
        assertEquals(rejected.receipt, repeated.receipt)
        assertFalse(repeated.newlyRecorded)
        assertEquals(dispatch, owner.fundsRecoveryState().dispatch)
        assertEquals(AdmissionCode.ACCEPTED, assertIs<AdmissionReply.Recorded>(admit(owner, 2, a, 300)).receipt.code)
    }

    @Test
    fun `확인 전후 복원과 같은 후속 명령열이 같은 금융 전달 상태를 만든다`() {
        for (ackBeforeSnapshot in listOf(false, true)) {
            val uninterrupted = owner()
            fund(uninterrupted)
            admit(uninterrupted, 1, a, 300)
            if (ackBeforeSnapshot) {
                uninterrupted.acknowledgeMatchingApplied(
                    FundsMatchingAppliedAck(configuration.stream(a.marketId), 1, contexts[0]),
                )
            }
            val saved = uninterrupted.fundsRecoveryState()
            val restored = owner(initialize = false)
            restored.restoreFundsRecoveryState(saved, settlement())
            assertSame(saved, restored.fundsRecoveryState())
            for (state in listOf(uninterrupted, restored)) {
                state.acknowledgeMatchingApplied(FundsMatchingAppliedAck(configuration.stream(a.marketId), 1, contexts[0]))
                admit(state, 2, b, 200)
                state.acknowledgeReceipts(buyer, 0, 1, 2)
                admit(state, 3, a, 100)
            }
            assertSame(uninterrupted.fundsRecoveryState(), restored.fundsRecoveryState())
            assertEquals(Balance(buyer, quote, Amount(400), Amount(600)), restored.currentBalance(buyer, quote))
        }
    }

    @Test
    fun `다른 설정이나 잘못된 총량의 복원을 게시하지 않고 차단한다`() {
        val original =
            owner()
                .also {
                    fund(it)
                    admit(it, 1, a, 300)
                }.fundsRecoveryState()
        val damaged = FundsRoleRecoveryState(original.configuration, original.financial, original.dispatch, mapOf(quote to 999L))
        val restored = owner(initialize = false)
        assertFailsWith<IllegalArgumentException> { restored.restoreFundsRecoveryState(damaged, settlement()) }
        assertTrue(restored.isBlocked)
        assertFailsWith<IllegalStateException> { restored.fundsRecoveryState() }
        assertFailsWith<IllegalStateException> { restored.initialize(manifest) }
    }

    @Test
    fun `자금 전용 객체에 기존 통합 실행 명령을 적용하지 않는다`() {
        val owner = owner().also { fund(it) }
        val receipt = assertIs<AdmissionReply.Recorded>(admit(owner, 1, a, 300)).receipt
        val before = owner.fundsRecoveryState()
        assertFailsWith<IllegalStateException> {
            owner.executionInput(
                ExecuteAdmission(manifest.lineage, a.marketId, receipt.marketAdmissionSequence!!, receipt.admissionSequence!!),
            )
        }
        assertSame(before, owner.fundsRecoveryState())
    }

    @Test
    fun `포화의 신규 요청은 아무것도 소비하지 않고 최초 결과는 계속 돌려준다`() {
        val limits = limits(dispatchEntries = 1, activeOrders = 3)
        val owner = owner(dispatchLimits = limits)
        fund(owner)
        val rejected = assertIs<AdmissionReply.Recorded>(admit(owner, 1, a, 2_000))
        admit(owner, 2, a, 300)
        val before = owner.fundsRecoveryState()
        assertEquals(rejected.receipt, assertIs<AdmissionReply.Recorded>(admit(owner, 1, a, 2_000)).receipt)
        assertFalse(assertIs<AdmissionReply.Recorded>(admit(owner, 2, a, 300)).newlyRecorded)
        assertEquals(AdmissionReply.Refused("RETRYABLE_CAPACITY"), admit(owner, 3, a, 200))
        assertSame(before, owner.fundsRecoveryState())
        owner.acknowledgeMatchingApplied(FundsMatchingAppliedAck(configuration.stream(a.marketId), 1, contexts[0]))
        val retried = assertIs<AdmissionReply.Recorded>(admit(owner, 3, a, 200))
        assertEquals(AdmissionCode.ACCEPTED, retried.receipt.code)
        assertEquals(2L, retried.receipt.admissionSequence)
        assertEquals(
            2L,
            owner
                .fundsRecoveryState()
                .dispatch.streams
                .first()
                .issued,
        )
        assertEquals(Balance(buyer, quote, Amount(500), Amount(500)), owner.currentBalance(buyer, quote))
    }

    @Test
    fun `전달 확인으로 활성 원본 한도는 회수되지 않는다`() {
        val owner = owner(dispatchLimits = limits(dispatchEntries = 1, activeOrders = 2))
        fund(owner)
        for (sequence in 1L..2L) {
            assertEquals(AdmissionCode.ACCEPTED, assertIs<AdmissionReply.Recorded>(admit(owner, sequence, a, 100)).receipt.code)
            owner.acknowledgeMatchingApplied(FundsMatchingAppliedAck(configuration.stream(a.marketId), sequence, contexts[0]))
        }
        val before = owner.fundsRecoveryState()
        assertEquals(0, before.dispatch.dispatchEntries)
        assertEquals(2, before.dispatch.activeOrders)
        assertEquals(AdmissionReply.Refused("RETRYABLE_CAPACITY"), admit(owner, 3, a, 100))
        assertSame(before, owner.fundsRecoveryState())
        assertEquals(Balance(buyer, quote, Amount(800), Amount(200)), owner.currentBalance(buyer, quote))
    }

    @Test
    fun `한 마켓 한도와 전체 한도를 구분하고 포화에서도 확인할 수 있다`() {
        val owner = owner(dispatchLimits = limits(dispatchEntries = 2, activeOrders = 4, marketDispatchEntries = 1))
        fund(owner)
        admit(owner, 1, a, 100)
        assertEquals(AdmissionReply.Refused("RETRYABLE_CAPACITY"), admit(owner, 2, a, 100))
        assertEquals(AdmissionCode.ACCEPTED, assertIs<AdmissionReply.Recorded>(admit(owner, 2, b, 100)).receipt.code)
        assertEquals(2, owner.fundsRecoveryState().dispatch.dispatchEntries)
        val before = owner.fundsRecoveryState()
        assertEquals(AdmissionReply.Refused("RETRYABLE_CAPACITY"), admit(owner, 3, a, 100))
        assertSame(before, owner.fundsRecoveryState())
        assertEquals(
            FundsDispatchAckStatus.ACKNOWLEDGED,
            owner.acknowledgeMatchingApplied(FundsMatchingAppliedAck(configuration.stream(a.marketId), 1, contexts[0])).status,
        )
        assertEquals(AdmissionCode.ACCEPTED, assertIs<AdmissionReply.Recorded>(admit(owner, 3, a, 100)).receipt.code)
    }

    @Test
    fun `잘못된 확인은 돈과 전달 상태를 바꾸거나 owner를 차단하지 않는다`() {
        val owner =
            owner().also {
                fund(it)
                admit(it, 1, a, 300)
            }
        val valid = FundsMatchingAppliedAck(configuration.stream(a.marketId), 1, contexts[0])
        val invalid =
            listOf(
                valid.copy(stream = valid.stream.copy(matchingLineage = ClusterLineage(88, 99))),
                valid.copy(stream = valid.stream.copy(ownershipEpoch = 2)),
                valid.copy(stream = valid.stream.copy(configurationVersion = 8)),
                valid.copy(through = 2),
                valid.copy(through = -1),
                valid.copy(context = contexts[0].copy(ordinal = 9)),
            )
        val before = owner.fundsRecoveryState()
        for (ack in invalid) {
            assertNotEquals(FundsDispatchAckStatus.ACKNOWLEDGED, owner.acknowledgeMatchingApplied(ack).status)
            assertFalse(owner.isBlocked)
            assertSame(before, owner.fundsRecoveryState())
        }
        // 미래 ACK를 기억해 두었다가 나중에 발급한 주문을 자동 회수하면 안 된다.
        admit(owner, 2, a, 100)
        assertEquals(2, owner.fundsRecoveryState().dispatch.dispatchEntries)
    }

    @Test
    fun `독립 byte 한도의 한 byte 부족도 예약과 요청 순번을 소비하지 않는다`() {
        // buyer/A-QUOTE/QUOTE와 34바이트 주문 ID의 charge-v1 수기값은 활성339·전달444다.
        for ((dispatchBytes, activeBytes) in listOf(444L to 339L, 443L to 339L, 444L to 338L)) {
            val local = MarketFundsDispatchLimits(maxDispatchBytes = dispatchBytes, maxActiveBytes = activeBytes)
            val limits =
                FundsDispatchLimits(
                    contexts.associate { it.market.marketId to local },
                    maxDispatchBytes = dispatchBytes,
                    maxActiveBytes = activeBytes,
                )
            val owner = owner(dispatchLimits = limits)
            fund(owner)
            val before = owner.fundsRecoveryState()
            val result = admit(owner, 1, a, 300)
            if (dispatchBytes == 444L && activeBytes == 339L) {
                assertEquals(AdmissionCode.ACCEPTED, assertIs<AdmissionReply.Recorded>(result).receipt.code)
                val saved = owner.fundsRecoveryState()
                assertEquals(444L, saved.dispatch.dispatchBytes)
                assertEquals(339L, saved.dispatch.activeBytes)
            } else {
                assertEquals(AdmissionReply.Refused("RETRYABLE_CAPACITY"), result)
                assertSame(before, owner.fundsRecoveryState())
            }
        }
    }

    @Test
    fun `새 자금 역할은 snapshot manifest에 들어가지 않는 배치를 조립 전에 거절한다`() {
        val markets =
            (0 until 40).map { ordinal ->
                TradingMarketContext(MarketDefinition(MarketId("M$ordinal-Q"), AssetId("B$ordinal"), quote, 0), fees, ordinal)
            }
        val wideManifest =
            TradingClusterManifest(
                manifest.lineage,
                "manifest-size-bound",
                "0".repeat(64),
                "1".repeat(64),
                7,
                markets,
                TradingResourceLimits(maxMarkets = 40, maxAssets = 41),
            )
        val wideTopology =
            TradingRoleTopology(wideManifest, markets.map { TradingMarketAssignment(it.market.marketId, ClusterLineage(3, 4), 1) })
        val failure = assertFailsWith<IllegalArgumentException> { FundsDispatchConfiguration(wideTopology) }
        assertEquals("funds manifest exceeds the snapshot-v1 format bound", failure.message)
        assertEquals(configuration, FundsDispatchConfiguration(topology))
    }

    private fun limits(
        dispatchEntries: Int,
        activeOrders: Int,
        marketDispatchEntries: Int = dispatchEntries,
    ) = FundsDispatchLimits(
        contexts.associate {
            it.market.marketId to
                MarketFundsDispatchLimits(maxDispatchEntries = marketDispatchEntries, maxActiveOrders = activeOrders)
        },
        maxDispatchEntries = dispatchEntries,
        maxActiveOrders = activeOrders,
    )

    private fun owner(
        initialize: Boolean = true,
        dispatchLimits: FundsDispatchLimits = configuration.limits,
    ): BoundedFundingState {
        val declaration =
            TradingRoleDeclaration(
                TradingServiceRole.FUNDS,
                manifest.lineage,
                7,
                contexts,
                mapOf(
                    a.marketId to 1,
                    b.marketId to 2,
                ),
            )
        return TradingRoleAssembly.funds(topology, declaration, admission(), dispatchLimits).also {
            if (initialize) {
                it.initialize(manifest)
                it.rotateEpoch(buyer, 0, 0)
            }
        }
    }

    private fun fund(owner: BoundedFundingState) =
        owner.prepareDevelopmentBalance(OpeningBalance("opening", buyer, quote, Amount(1000)), at)

    private fun admit(
        owner: BoundedFundingState,
        sequence: Long,
        market: MarketDefinition,
        amount: Long,
    ): AdmissionReply =
        owner.admit(
            RequestToken(buyer, 0, 1, sequence),
            OrderIntent(market.marketId, 7, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(amount), Quantity(1)),
            at,
        )

    private fun admission() =
        OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())))

    private fun settlement(): OrderSettlementCalculator {
        val fees = TradingFeeReserveCalculator()
        val reserve = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fees))
        return OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
            reserve,
            fees,
            SettlementLedgerTransactions(),
        )
    }

    private fun assertSame(
        expected: FundsRoleRecoveryState,
        actual: FundsRoleRecoveryState,
    ) {
        assertEquals(expected.configuration, actual.configuration)
        assertEquals(expected.dispatch, actual.dispatch)
        assertEquals(expected.assetTotals, actual.assetTotals)
        assertFinancialSame(expected.financial, actual.financial)
    }

    private fun assertFinancialSame(
        expected: BoundedFundingRecoveryState,
        actual: BoundedFundingRecoveryState,
    ) {
        assertEquals(expected.manifest, actual.manifest)
        assertEquals(expected.balances, actual.balances)
        assertEquals(expected.liveOrders, actual.liveOrders)
        assertEquals(expected.markets, actual.markets)
        assertEquals(expected.inbox, actual.inbox)
        assertEquals(expected.preparations, actual.preparations)
        assertEquals(expected.feeRevenueByAsset, actual.feeRevenueByAsset)
        assertEquals(expected.lastAdmissionSequence, actual.lastAdmissionSequence)
        assertEquals(expected.lastPreparationSequence, actual.lastPreparationSequence)
    }
}
