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
import kotlin.test.assertNull

class BoundedFundingStateTest {
    private val btc = AssetId("BTC")
    private val sui = AssetId("SUI")
    private val usdt = AssetId("USDT")
    private val buyer = UserId("buyer")
    private val seller = UserId("seller")
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val marketA = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 0)
    private val marketB = MarketDefinition(MarketId("SUI-USDT"), sui, usdt, 0)
    private val lineage = ClusterLineage(101, 202)
    private val feePolicy =
        TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))

    @Test
    fun `매칭 엔진 없이 접수한 예약과 최초 receipt를 한 번만 반영한다`() {
        val state = state().also { it.initialize(manifest()) }
        fund(state, buyer, usdt, 1_000)
        assertEquals(EpochReceipt(EpochStatus.ISSUED, 1), state.rotateEpoch(buyer, 0, 0))
        val token = RequestToken(buyer, 0, 1, 1)
        val intent = buy(marketA, 100, 3)

        val first = assertIs<AdmissionReply.Recorded>(state.admit(token, intent, at))
        val repeated = assertIs<AdmissionReply.Recorded>(state.admit(token, intent, at.plusSeconds(1)))

        assertEquals(AdmissionCode.ACCEPTED, first.receipt.code)
        assertEquals(first.receipt, repeated.receipt)
        assertFalse(repeated.newlyRecorded)
        assertEquals(Balance(buyer, usdt, Amount(700), Amount(300)), state.currentBalance(buyer, usdt))
        assertEquals(1, state.boundedRetention().liveOrders)
        assertEquals(1, state.boundedRetention().pendingOrders)
        assertEquals(1L, state.progress().lastAdmissionSequence)
        assertEquals(listOf(LiveOrderPhase.PENDING), state.recoveryState().liveOrders.map { it.phase })
    }

    @Test
    fun `같은 설정으로 조립한 두 금융 소유자는 잔고와 예약을 공유하지 않는다`() {
        val definition = manifest()
        val first = state(definition).also { it.initialize(definition) }
        val second = state(definition).also { it.initialize(definition) }
        fund(first, buyer, usdt, 1_000)
        first.rotateEpoch(buyer, 0, 0)
        first.admit(RequestToken(buyer, 0, 1, 1), buy(marketA, 100, 3), at)

        assertEquals(Balance(buyer, usdt, Amount.ZERO, Amount.ZERO), second.currentBalance(buyer, usdt))
        assertEquals(0, second.boundedRetention().balances)
        assertEquals(0, second.boundedRetention().liveOrders)
        assertEquals(0, second.boundedRetention().receipts)
        assertEquals(0L, second.progress().lastAdmissionSequence)
    }

    @Test
    fun `여러 마켓은 하나의 quote 잔고와 사용자 요청 inbox를 쓴다`() {
        val state = state().also { it.initialize(manifest()) }
        fund(state, buyer, usdt, 1_000)
        state.rotateEpoch(buyer, 0, 0)
        val tokenA = RequestToken(buyer, 0, 1, 1)
        val acceptedA = assertIs<AdmissionReply.Recorded>(state.admit(tokenA, buy(marketA, 100, 7), at))
        assertEquals(AdmissionCode.ACCEPTED, acceptedA.receipt.code)
        assertEquals(Balance(buyer, usdt, Amount(300), Amount(700)), state.currentBalance(buyer, usdt))

        val tokenRejected = RequestToken(buyer, 0, 1, 2)
        val rejected = assertIs<AdmissionReply.Recorded>(state.admit(tokenRejected, buy(marketB, 100, 4), at))
        assertEquals(AdmissionCode.REJECTED, rejected.receipt.code)
        assertEquals(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE, rejected.receipt.reason)
        assertEquals(Balance(buyer, usdt, Amount(300), Amount(700)), state.currentBalance(buyer, usdt))

        val acceptedB = assertIs<AdmissionReply.Recorded>(state.admit(RequestToken(buyer, 0, 1, 3), buy(marketB, 100, 3), at))
        assertEquals(AdmissionCode.ACCEPTED, acceptedB.receipt.code)
        assertEquals(Balance(buyer, usdt, Amount.ZERO, Amount(1_000)), state.currentBalance(buyer, usdt))
        assertEquals(2, state.boundedRetention().liveOrders)
        assertEquals(2L, state.progress().lastAdmissionSequence)
        assertEquals(listOf(1L, 1L), state.progress().markets.map { it.lastAssigned })

        val before = state.recoveryState()
        assertEquals(
            AdmissionReply.Refused(InboxRejection.REQUEST_CONTENT_CONFLICT.name),
            state.admit(tokenA, buy(marketB, 100, 7), at),
        )
        val repeatedRejection = assertIs<AdmissionReply.Recorded>(state.admit(tokenRejected, buy(marketB, 100, 4), at))
        assertEquals(rejected.receipt, repeatedRejection.receipt)
        assertFalse(repeatedRejection.newlyRecorded)
        val after = state.recoveryState()
        assertEquals(before.balances, after.balances)
        assertEquals(before.liveOrders, after.liveOrders)
        assertEquals(before.markets, after.markets)
        assertEquals(before.inbox, after.inbox)
        assertEquals(before.lastAdmissionSequence, after.lastAdmissionSequence)
    }

    @Test
    fun `조립 설정과 다른 유효한 manifest로 초기화하거나 복원할 수 없다`() {
        val expected = manifest()

        fun assembled(definition: TradingClusterManifest = expected): BoundedFundingState {
            val assignments =
                definition.markets.map {
                    TradingMarketAssignment(it.market.marketId, ClusterLineage(901, 902), 1)
                }
            val topology = TradingRoleTopology(definition, assignments)
            val declaration =
                TradingRoleDeclaration(
                    TradingServiceRole.FUNDS,
                    definition.lineage,
                    definition.configurationVersion,
                    definition.markets,
                    assignments.associate { it.marketId to it.ownershipEpoch },
                )
            val reservation = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))
            return TradingRoleAssembly.funds(topology, declaration, OrderAdmissionCalculator(reservation))
        }

        fun changed(
            lineage: ClusterLineage = expected.lineage,
            markets: List<TradingMarketContext> = expected.markets,
            version: Long = expected.configurationVersion,
        ) = TradingClusterManifest(
            lineage,
            expected.algorithmId,
            expected.executionArtifactSha256,
            expected.schemaSha256,
            version,
            markets,
            expected.limits,
        )

        val firstContext = expected.markets.first()
        val cases =
            listOf(
                "lineage" to changed(lineage = ClusterLineage(303, 404)),
                "마켓 정의" to changed(markets = listOf(firstContext.copy(market = marketA.copy(priceTick = 2)), expected.markets[1])),
                "수수료 정책" to
                    changed(
                        markets =
                            listOf(
                                firstContext.copy(
                                    feePolicySnapshot = feePolicy.copy(feeRates = MakerTakerFeeRates(FeeRate(1), FeeRate.ZERO)),
                                ),
                                expected.markets[1],
                            ),
                    ),
                "설정 버전" to changed(version = expected.configurationVersion + 1),
            )
        for ((label, foreignManifest) in cases) {
            val initializing = assembled()
            assertFailsWith<IllegalArgumentException>(label) { initializing.initialize(foreignManifest) }
            assertEquals(TradingLifecycle.UNINITIALIZED, initializing.lifecycle, label)
            assertFalse(initializing.isBlocked, label)
            assertEquals(InitializationReceipt.INITIALIZED, initializing.initialize(expected), label)
            assertEquals(InitializationReceipt.ALREADY_INITIALIZED, initializing.initialize(expected), label)
            assertEquals(Balance(buyer, usdt, Amount.ZERO, Amount.ZERO), initializing.currentBalance(buyer, usdt), label)
            assertEquals(0, initializing.boundedRetention().liveOrders, label)

            // 다른 설정 자체는 유효하고 자금을 가진다. 거절 대상은 설정과 소유자의 결속이다.
            val foreign = assembled(foreignManifest).also { it.initialize(foreignManifest) }
            fund(foreign, buyer, usdt, 1_000)
            val restoring = assembled()
            assertFailsWith<IllegalArgumentException>(label) {
                restoring.restoreRecoveryState(foreign.recoveryState(), settlementCalculator())
            }
            assertEquals(TradingLifecycle.UNINITIALIZED, restoring.lifecycle, label)
            assertFalse(restoring.isBlocked, label)
            assertEquals(InitializationReceipt.INITIALIZED, restoring.initialize(expected), label)
            assertEquals(Balance(buyer, usdt, Amount.ZERO, Amount.ZERO), restoring.currentBalance(buyer, usdt), label)
            assertEquals(0, restoring.boundedRetention().liveOrders, label)
            assertEquals(0L, restoring.progress().lastAdmissionSequence, label)
        }

        val original = assembled().also { it.initialize(expected) }
        fund(original, buyer, usdt, 1_000)
        original.rotateEpoch(buyer, 0, 0)
        original.admit(RequestToken(buyer, 0, 1, 1), buy(marketA, 100, 3), at)
        val saved = original.recoveryState()
        val restored = assembled().also { it.restoreRecoveryState(saved, settlementCalculator()) }
        assertEquals(TradingLifecycle.RUNNING, restored.lifecycle)
        assertEquals(Balance(buyer, usdt, Amount(700), Amount(300)), restored.currentBalance(buyer, usdt))
        assertEquals(1, restored.boundedRetention().liveOrders)
        assertEquals(1, restored.boundedRetention().pendingOrders)
        assertEquals(saved.liveOrders, restored.recoveryState().liveOrders)
        assertEquals(saved.markets, restored.recoveryState().markets)
        assertEquals(saved.inbox, restored.recoveryState().inbox)
    }

    @Test
    fun `금융 후보 적용이 두 참여자의 잔고 예약과 pending 진행을 함께 바꾼다`() {
        val state = state().also { it.initialize(manifest()) }
        fund(state, buyer, usdt, 1_000)
        fund(state, seller, btc, 1)
        state.rotateEpoch(buyer, 0, 0)
        state.rotateEpoch(seller, 0, 0)
        val sell = buy(marketA, 100, 1).copy(side = Side.SELL)
        val sellReceipt = assertIs<AdmissionReply.Recorded>(state.admit(RequestToken(seller, 0, 1, 1), sell, at)).receipt
        val sellInput = requireNotNull(state.executionInput(execute(sellReceipt)))
        val calculator = settlementCalculator()
        state.applyExecution(sellInput, calculator.calculateEmpty(sellInput.live.order, marketA), resting = true, nextFeeRevenue = null)

        val buyReceipt = assertIs<AdmissionReply.Recorded>(state.admit(RequestToken(buyer, 0, 1, 1), buy(marketA, 100, 1), at)).receipt
        val buyInput = requireNotNull(state.executionInput(execute(buyReceipt)))
        val reservations = listOf(sellInput.live.reservation, buyInput.live.reservation)
        // 매칭 객체 대신 이미 분리된 금융 입력으로 기존 계산기를 사용한다.
        val execution =
            SettlementExecution(
                marketA.marketId,
                2,
                requireNotNull(sellReceipt.orderId),
                requireNotNull(buyReceipt.orderId),
                seller,
                buyer,
                Side.BUY,
                Price(100),
                Quantity(1),
                TradingIdentifiers.settlement(lineage, 0, 2),
                at,
            )
        val candidate =
            calculator.calculate(
                buyInput.live.order,
                marketA,
                listOf(execution),
                reservations,
                state.participantBalances(reservations, marketA),
            )
        state.validateSettlementCurrent(candidate)
        val terminal = state.applyExecution(buyInput, candidate, resting = false, nextFeeRevenue = state.prepareFeeRevenue(candidate, usdt))

        assertEquals(setOf(sellReceipt.orderId, buyReceipt.orderId), terminal.map { it.orderId }.toSet())
        assertEquals(Balance(buyer, usdt, Amount(900), Amount.ZERO), state.currentBalance(buyer, usdt))
        assertEquals(Balance(buyer, btc, Amount(1), Amount.ZERO), state.currentBalance(buyer, btc))
        assertEquals(Balance(seller, usdt, Amount(100), Amount.ZERO), state.currentBalance(seller, usdt))
        assertEquals(Balance(seller, btc, Amount.ZERO, Amount.ZERO), state.currentBalance(seller, btc))
        assertEquals(0, state.boundedRetention().liveOrders)
        assertEquals(0, state.boundedRetention().pendingOrders)
        assertEquals(
            2L,
            state
                .progress()
                .markets
                .first()
                .executedThrough,
        )
        assertNull(state.executionInput(execute(buyReceipt)))
    }

    private fun state(expected: TradingClusterManifest? = null): BoundedFundingState {
        val reservation = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))
        return BoundedFundingState(OrderAdmissionCalculator(reservation), expected)
    }

    private fun settlementCalculator(): OrderSettlementCalculator {
        val fees = TradingFeeReserveCalculator()
        val reservation = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fees))
        return OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
            reservation,
            fees,
            SettlementLedgerTransactions(),
        )
    }

    private fun manifest(value: ClusterLineage = lineage) =
        TradingClusterManifest(
            value,
            "funding-owner-test-v1",
            "0".repeat(64),
            "1".repeat(64),
            7,
            listOf(TradingMarketContext(marketA, feePolicy, 0), TradingMarketContext(marketB, feePolicy, 1)),
            TradingResourceLimits(),
        )

    private fun fund(
        state: BoundedFundingState,
        userId: UserId,
        assetId: AssetId,
        amount: Long,
    ) = state.prepareDevelopmentBalance(OpeningBalance("${userId.value}-${assetId.value}", userId, assetId, Amount(amount)), at)

    private fun buy(
        market: MarketDefinition,
        price: Long,
        quantity: Long,
    ) = OrderIntent(market.marketId, 7, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity))

    private fun execute(receipt: AdmissionReceipt) =
        ExecuteAdmission(
            lineage,
            marketA.marketId,
            requireNotNull(receipt.marketAdmissionSequence),
            requireNotNull(receipt.admissionSequence),
        )
}
