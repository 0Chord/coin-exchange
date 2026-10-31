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
import com.exchange.core.ledger.OpeningBalanceConflictException
import com.exchange.core.ledger.OpeningBalanceRequestConflictException
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.math.BigInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoundedTradingStateTest {
    private val btc = AssetId("BTC")
    private val sui = AssetId("SUI")
    private val usdt = AssetId("USDT")
    private val buyer = UserId("buyer")
    private val seller = UserId("seller")
    private val at = Instant.parse("2026-10-30T00:00:00Z")
    private val btcMarket = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 0)
    private val suiMarket = MarketDefinition(MarketId("SUI-USDT"), sui, usdt, 0)
    private val lineage = ClusterLineage(101, 202)

    private fun state(): TradingState {
        val reserve = TradingFeeReserveCalculator()
        val reservation = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(reserve))
        return TradingState(
            OrderAdmissionCalculator(reservation),
            OrderSettlementCalculator(
                OrderFillSettlementCalculator(TradingFeeCalculator(), reserve),
                reservation,
                reserve,
                SettlementLedgerTransactions(),
            ),
            boundedMode = true,
        )
    }

    private fun manifest(
        limits: TradingResourceLimits = TradingResourceLimits(),
        fee: Long = 0,
    ): TradingClusterManifest {
        val policy =
            TradingFeePolicySnapshot(
                FeeProductType.SPOT,
                FeeTier.NORMAL,
                1,
                MakerTakerFeeRates(FeeRate(fee), FeeRate(fee)),
            )
        return TradingClusterManifest(
            lineage,
            "bounded-test-v1",
            "0".repeat(64),
            "1".repeat(64),
            7,
            listOf(TradingMarketContext(btcMarket, policy, 0), TradingMarketContext(suiMarket, policy, 1)),
            limits,
        )
    }

    private fun fund(
        state: TradingState,
        user: UserId,
        asset: AssetId,
        value: Long,
        id: String = "${user.value}-${asset.value}",
    ) = state.prepareDevelopmentBalance(OpeningBalance(id, user, asset, Amount(value)), "ignored-client-id", at)

    private fun intent(
        side: Side,
        price: Long,
        quantity: Long,
        market: MarketDefinition = btcMarket,
    ) = OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(price), Quantity(quantity))

    private fun accepted(
        state: TradingState,
        user: UserId,
        sequence: Long,
        value: OrderIntent,
    ): AdmissionReceipt {
        val reply = assertIs<AdmissionReply.Recorded>(state.admit(RequestToken(user, 0, 1, sequence), value, at))
        assertTrue(reply.newlyRecorded)
        assertEquals(AdmissionCode.ACCEPTED, reply.receipt.code)
        return reply.receipt
    }

    private fun execute(
        state: TradingState,
        receipt: AdmissionReceipt,
        market: MarketDefinition = btcMarket,
        observer: TradingExecutionObserver = TradingExecutionObserver.NONE,
    ): ExecutionReply =
        state.executePending(
            ExecuteAdmission(
                lineage,
                market.marketId,
                requireNotNull(receipt.marketAdmissionSequence),
                requireNotNull(receipt.admissionSequence),
            ),
            at,
            observer,
        )

    @Test
    fun `bounded observer reports fixed phases without changing money and skips already passed execution`() {
        for (withFill in listOf(false, true)) {
            val owners = listOf(state(), state())
            val receipts =
                owners.map { owner ->
                    owner.initialize(manifest())
                    owner.rotateEpoch(buyer, 0, 0)
                    owner.rotateEpoch(seller, 0, 0)
                    fund(owner, buyer, usdt, 1000)
                    fund(owner, seller, btc, 1)
                    if (withFill) execute(owner, accepted(owner, seller, 1, intent(Side.SELL, 90, 1)))
                    accepted(owner, buyer, 1, intent(Side.BUY, 100, 1))
                }
            val observer = BoundedObserver(IllegalStateException("optional observer unavailable"))

            val baseline = execute(owners[0], receipts[0])
            val observed = execute(owners[1], receipts[1], observer = observer)

            assertEquals(baseline, observed)
            assertEquals(TradingExecutionPhase.entries, observer.completed.map { it.first })
            assertEquals(
                TradingExecutionPhase.entries.flatMap { listOf(it to true, it to false) },
                observer.boundaries,
            )
            assertTrue(observer.completed.all { it.second == null })
            val expected = owners[0].recoveryState()
            val actual = owners[1].recoveryState()
            assertEquals(expected.balances, actual.balances)
            assertEquals(expected.liveOrders, actual.liveOrders)
            assertEquals(
                expected.matching.markets.map { Triple(it.marketId, it.lastEngineSequence, it.orders) },
                actual.matching.markets.map { Triple(it.marketId, it.lastEngineSequence, it.orders) },
            )
            assertEquals(expected.inbox, actual.inbox)
            assertEquals(expected.markets, actual.markets)
            assertEquals(expected.feeRevenueByAsset, actual.feeRevenueByAsset)
            assertFalse(owners[1].isBlocked)
            val callbackCount = observer.boundaries.size
            assertEquals(ExecutionReply.AlreadyPassed, execute(owners[1], receipts[1], observer = observer))
            assertEquals(callbackCount, observer.boundaries.size)
        }
    }

    private class BoundedObserver(
        private val callbackFailure: Exception? = null,
    ) : TradingExecutionObserver {
        val boundaries = mutableListOf<Pair<TradingExecutionPhase, Boolean>>()
        val completed = mutableListOf<Pair<TradingExecutionPhase, Throwable?>>()

        override fun onPhaseStarted(
            admissionSequence: Long,
            phase: TradingExecutionPhase,
        ) {
            boundaries.add(phase to true)
            callbackFailure?.let { throw it }
        }

        override fun onPhaseCompleted(
            admissionSequence: Long,
            phase: TradingExecutionPhase,
            failure: Throwable?,
        ) {
            boundaries.add(phase to false)
            completed.add(phase to failure)
            callbackFailure?.let { throw it }
        }
    }

    @Test
    fun `bounded admission equals reserve reference and restored retry never reserves twice`() {
        val original = state()
        val definition = manifest(fee = 20_000)
        original.initialize(definition)
        original.rotateEpoch(buyer, 0, 0)
        fund(original, buyer, usdt, 1000)
        val submitted = intent(Side.BUY, 100, 3)
        val token = RequestToken(buyer, 0, 1, 1)
        val receipt = accepted(original, buyer, 1, submitted)
        val reference =
            assertIs<OrderAdmissionResult.Calculated>(
                OrderAdmissionCalculator(
                    OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())),
                ).calculate(
                    OrderAdmissionInput(
                        btcMarket.marketId,
                        requireNotNull(receipt.orderId),
                        buyer,
                        Side.BUY,
                        OrderType.LIMIT,
                        TimeInForce.GTC,
                        Price(100),
                        Quantity(3),
                    ),
                    btcMarket,
                    definition.markets.first().feePolicySnapshot,
                    Balance(buyer, usdt, Amount(1000), Amount.ZERO),
                    "reserve-reference",
                    at,
                ),
            ).candidate
        assertEquals(Balance(buyer, usdt, Amount(694), Amount(306)), original.currentBalance(buyer, usdt))
        assertEquals(reference.nextBalance, original.currentBalance(buyer, usdt))
        val saved = original.recoveryState()
        assertEquals(reference.reservation, saved.liveOrders.single().reservation)
        val recovered = state().also { it.restoreRecoveryState(saved) }
        for (owner in listOf(original, recovered)) {
            val repeated = assertIs<AdmissionReply.Recorded>(owner.admit(token, submitted, at.plusSeconds(1)))
            assertFalse(repeated.newlyRecorded)
            assertEquals(receipt, repeated.receipt)
            assertEquals(Balance(buyer, usdt, Amount(694), Amount(306)), owner.currentBalance(buyer, usdt))
            assertIs<ExecutionReply.Applied>(execute(owner, receipt))
            assertEquals(Balance(buyer, usdt, Amount(694), Amount(306)), owner.currentBalance(buyer, usdt))
        }
        assertEquals(original.recoveryState().balances, recovered.recoveryState().balances)
        assertEquals(original.recoveryState().liveOrders, recovered.recoveryState().liveOrders)
    }

    @Test
    fun `fatal observer error accompanying classified validation blocks every normal entry`() {
        val owner = state()
        owner.initialize(manifest(fee = 20_000))
        owner.rotateEpoch(buyer, 0, 0)
        fund(owner, buyer, usdt, 1000)
        val receipt = accepted(owner, buyer, 1, intent(Side.BUY, 100, 3))
        val data = TradingStateFailureTestSupport.read(owner, "bounded") as BoundedTradingData
        val live = data.liveBySequence.getValue(requireNotNull(receipt.admissionSequence))
        live.reservation = live.reservation.copy(reservedAmount = Amount(307), remainingAmount = Amount(307))
        val balances = data.balances.toMap()
        val reservation = live.reservation
        val fatal = OutOfMemoryError("synthetic fatal observer")
        val observer =
            object : TradingExecutionObserver {
                override fun onPhaseCompleted(
                    admissionSequence: Long,
                    phase: TradingExecutionPhase,
                    failure: Throwable?,
                ) {
                    if (failure != null) throw fatal
                }
            }
        val failure = assertFailsWith<TradingInvariantViolation> { execute(owner, receipt, observer = observer) }
        assertEquals(TradingInvariantReason.PARTICIPANT_RELATION_INVALID, failure.reason)
        assertEquals(listOf(fatal), failure.suppressed.toList())
        assertTrue(owner.isBlocked)
        assertEquals(balances, data.balances)
        assertEquals(reservation, live.reservation)
        assertFailsWith<IllegalStateException> { owner.recoveryState() }
        assertFailsWith<IllegalStateException> { owner.quarantine(42, failure.reason.name) }
        assertFailsWith<IllegalStateException> { execute(owner, receipt) }
    }

    @Test
    fun `initial reservation corruption keeps input quarantine classification with zero one or multiple fills`() {
        for (makerCount in 0..2) {
            val owner = state()
            owner.initialize(manifest(fee = 20_000))
            owner.rotateEpoch(buyer, 0, 0)
            owner.rotateEpoch(seller, 0, 0)
            fund(owner, buyer, usdt, 1000)
            fund(owner, seller, btc, 2)
            repeat(makerCount) { index ->
                execute(owner, accepted(owner, seller, index.toLong() + 1, intent(Side.SELL, 90 + index.toLong(), 1)))
            }
            val receipt = accepted(owner, buyer, 1, intent(Side.BUY, 100, 3))
            val data = TradingStateFailureTestSupport.read(owner, "bounded") as BoundedTradingData
            val live = data.liveBySequence.getValue(requireNotNull(receipt.admissionSequence))
            live.reservation = live.reservation.copy(reservedAmount = Amount(307), remainingAmount = Amount(307))
            val before = owner.recoveryState()
            val observer = BoundedObserver()

            val failure = assertFailsWith<TradingInvariantViolation> { execute(owner, receipt, observer = observer) }

            assertEquals(TradingInvariantReason.PARTICIPANT_RELATION_INVALID, failure.reason)
            assertEquals(TradingExecutionPhase.SETTLEMENT_INPUT_VALIDATION, failure.stage)
            assertNull(failure.cause)
            assertSame(failure, observer.completed.last().second)
            val after = owner.recoveryState()
            assertEquals(before.balances, after.balances)
            assertEquals(before.liveOrders, after.liveOrders)
            assertEquals(before.markets, after.markets)
            assertEquals(before.inbox, after.inbox)
            assertEquals(before.feeRevenueByAsset, after.feeRevenueByAsset)
            assertEquals(before.lastAdmissionSequence, after.lastAdmissionSequence)
            assertEquals(
                before.matching.markets.map { Triple(it.marketId, it.lastEngineSequence, it.orders) },
                after.matching.markets.map { Triple(it.marketId, it.lastEngineSequence, it.orders) },
            )
            assertFalse(owner.isBlocked)
            val corruptSnapshot = owner.recoveryState()
            val fresh = state()
            assertFailsWith<IllegalArgumentException> { fresh.restoreRecoveryState(corruptSnapshot) }
            assertTrue(fresh.isBlocked)
            owner.quarantine(42, failure.reason.name)
            assertEquals(TradingIncident(42, "PARTICIPANT_RELATION_INVALID"), owner.recoveryState().incident)
        }
    }

    @Test
    fun `one and two maker fills retain event order exact money and same seller accumulation`() {
        for (makerCount in 1..2) {
            val owner = state()
            owner.initialize(manifest())
            owner.rotateEpoch(buyer, 0, 0)
            owner.rotateEpoch(seller, 0, 0)
            fund(owner, buyer, usdt, 1000)
            fund(owner, seller, btc, 2)
            repeat(makerCount) { index ->
                execute(owner, accepted(owner, seller, index.toLong() + 1, intent(Side.SELL, if (index == 0) 90 else 95, 1)))
            }
            val taker = accepted(owner, buyer, 1, intent(Side.BUY, 100, makerCount.toLong() + 1))

            val result = assertIs<ExecutionReply.Applied>(execute(owner, taker))

            val quotePaid = if (makerCount == 1) 90L else 185L
            assertEquals(makerCount, result.receipt.fillCount)
            assertEquals(2L * makerCount + 1, result.receipt.lastEngineSequence)
            assertTrue(result.receipt.resting)
            assertEquals(Balance(buyer, usdt, Amount(900 - quotePaid), Amount(100)), owner.currentBalance(buyer, usdt))
            assertEquals(Balance(buyer, btc, Amount(makerCount.toLong()), Amount.ZERO), owner.currentBalance(buyer, btc))
            assertEquals(Balance(seller, usdt, Amount(quotePaid), Amount.ZERO), owner.currentBalance(seller, usdt))
            val remaining = owner.recoveryState().liveOrders.single()
            assertEquals(taker.orderId, remaining.order.orderId)
            assertEquals(Quantity(1), remaining.reservation.remainingQuantity)
            assertEquals(Amount(100), remaining.reservation.remainingAmount)
            assertEquals(LiveOrderPhase.RESTING, remaining.phase)
            assertTrue(owner.recoveryState().feeRevenueByAsset.isEmpty())
        }
    }

    @Test
    fun `no fill still rejects missing or mislabelled balance keys and insufficient hold for BUY and SELL`() {
        for (side in Side.entries) {
            for (damage in listOf("missing-base", "missing-quote", "hold", "owner", "asset")) {
                val owner = state()
                owner.initialize(manifest())
                owner.rotateEpoch(buyer, 0, 0)
                if (side == Side.BUY) fund(owner, buyer, usdt, 1000) else fund(owner, buyer, btc, 3)
                val receipt = accepted(owner, buyer, 1, intent(side, 100, 3))
                val data = TradingStateFailureTestSupport.read(owner, "bounded") as BoundedTradingData
                val baseKey = TradingBalanceKey(buyer, btc)
                val quoteKey = TradingBalanceKey(buyer, usdt)
                val reservedKey = if (side == Side.BUY) quoteKey else baseKey
                when (damage) {
                    "missing-base" -> {
                        data.balances.remove(baseKey)
                    }

                    "missing-quote" -> {
                        data.balances.remove(quoteKey)
                    }

                    "hold" -> {
                        val balance = data.balances.getValue(reservedKey)
                        data.balances[reservedKey] = balance.copy(hold = Amount(balance.hold.value - 1))
                    }

                    "owner" -> {
                        data.balances[baseKey] = data.balances.getValue(baseKey).copy(userId = UserId("wrong-owner"))
                    }

                    "asset" -> {
                        data.balances[baseKey] = data.balances.getValue(baseKey).copy(assetId = usdt)
                    }
                }
                val before = owner.recoveryState()

                val failure = assertFailsWith<TradingInvariantViolation> { execute(owner, receipt) }

                assertEquals(TradingInvariantReason.PARTICIPANT_RELATION_INVALID, failure.reason)
                assertEquals(TradingExecutionPhase.SETTLEMENT_INPUT_VALIDATION, failure.stage)
                assertFalse(owner.isBlocked)
                val after = owner.recoveryState()
                assertEquals(before.balances, after.balances)
                assertEquals(before.liveOrders, after.liveOrders)
                assertEquals(before.markets, after.markets)
                assertEquals(before.inbox, after.inbox)
                assertTrue(after.matching.markets.isEmpty())
            }
        }
    }

    private fun alterRecovery(
        saved: TradingRecoveryState,
        liveOrders: List<TradingLiveOrderSnapshot> = saved.liveOrders,
        inbox: TradingInboxSnapshot = saved.inbox,
        manifest: TradingClusterManifest = saved.manifest,
    ) = TradingRecoveryState(
        manifest,
        saved.lifecycle,
        saved.balances,
        saved.feeRevenueByAsset,
        saved.usedBalances,
        saved.preparations,
        saved.accounts,
        liveOrders,
        saved.markets,
        saved.matching,
        inbox,
        saved.lastAdmissionSequence,
        saved.lastPreparationSequence,
        saved.preparationCanonicalBytes,
        saved.incident,
    )

    @Test
    fun `recovery rejects pending order conditions that admission never accepts`() {
        val original =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
            }
        fund(original, buyer, usdt, 1000)
        accepted(original, buyer, 1, intent(Side.BUY, 100, 1))
        val saved = original.recoveryState()
        for ((type, tif) in listOf(OrderType.MARKET to TimeInForce.GTC, OrderType.LIMIT to TimeInForce.IOC)) {
            val live = saved.liveOrders.single()
            val altered = alterRecovery(saved, liveOrders = listOf(live.copy(order = live.order.copy(orderType = type, timeInForce = tif))))
            val recovered = state()
            assertFailsWith<IllegalArgumentException> { recovered.restoreRecoveryState(altered) }
            assertTrue(recovered.isBlocked)
        }
    }

    @Test
    fun `recovery rejects two request tokens claiming the same accepted admission`() {
        val original =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
            }
        fund(original, buyer, usdt, 1000)
        accepted(original, buyer, 1, intent(Side.BUY, 100, 1))
        accepted(original, buyer, 2, intent(Side.BUY, 100, 1))
        val saved = original.recoveryState()
        val channel = saved.inbox.channels.single()
        val duplicated =
            channel.entries.mapIndexed { index, entry ->
                if (index ==
                    1
                ) {
                    entry.copy(receipt = channel.entries.first().receipt)
                } else {
                    entry
                }
            }
        val altered = alterRecovery(saved, inbox = TradingInboxSnapshot(listOf(channel.copy(entries = duplicated))))
        val recovered = state()
        assertFailsWith<IllegalArgumentException> { recovered.restoreRecoveryState(altered) }
        assertTrue(recovered.isBlocked)
    }

    @Test
    fun `fully completed accepted receipts still require admissible quantity units conditions and reserve range on restore`() {
        val definitions = manifest()
        val restrictedMarket = btcMarket.copy(priceTick = 2, quantityStep = 2)
        val configured =
            TradingClusterManifest(
                definitions.lineage,
                definitions.algorithmId,
                definitions.executionArtifactSha256,
                definitions.schemaSha256,
                definitions.configurationVersion,
                definitions.markets.map {
                    if (it.market.marketId ==
                        btcMarket.marketId
                    ) {
                        it.copy(market = restrictedMarket)
                    } else {
                        it
                    }
                },
                definitions.limits,
            )
        val original =
            state().also {
                it.initialize(configured)
                it.rotateEpoch(buyer, 0, 0)
                it.rotateEpoch(seller, 0, 0)
            }
        fund(original, buyer, usdt, 200)
        fund(original, seller, btc, 2)
        execute(original, accepted(original, seller, 1, intent(Side.SELL, 100, 2, restrictedMarket)), restrictedMarket)
        val input = intent(Side.BUY, 100, 2, restrictedMarket)
        val first = accepted(original, buyer, 1, input)
        execute(original, first, restrictedMarket)
        val saved = original.recoveryState()
        assertTrue(saved.liveOrders.isEmpty())
        assertEquals(Amount.ZERO, original.currentBalance(buyer, usdt).available)

        // This valid recovery must not try to buy again using the now-empty quote balance.
        val valid = state().also { it.restoreRecoveryState(saved) }
        val repeated = assertIs<AdmissionReply.Recorded>(valid.admit(RequestToken(buyer, 0, 1, 1), input, at))
        assertEquals(first, repeated.receipt)
        assertFalse(repeated.newlyRecorded)
        assertEquals(Amount.ZERO, valid.currentBalance(buyer, usdt).available)

        val invalid =
            listOf(
                input.copy(quantity = Quantity.ZERO),
                input.copy(quantity = Quantity(1)),
                input.copy(price = Price(101)),
                input.copy(price = Price(Long.MAX_VALUE - 1)),
                input.copy(orderType = OrderType.MARKET),
                input.copy(timeInForce = TimeInForce.IOC),
            )
        for (corrupted in invalid) {
            val channels =
                saved.inbox.channels.map { channel ->
                    if (channel.userId == buyer) channel.copy(entries = channel.entries.map { it.copy(intent = corrupted) }) else channel
                }
            val recovered = state()
            assertFailsWith<IllegalArgumentException>("accepted completed intent must be rejected: $corrupted") {
                recovered.restoreRecoveryState(alterRecovery(saved, inbox = TradingInboxSnapshot(channels)))
            }
            assertTrue(recovered.isBlocked)
            assertFailsWith<IllegalStateException> { recovered.admit(RequestToken(buyer, 0, 1, 1), corrupted, at) }
        }
    }

    @Test
    fun `overlong epoch user is refused before mutation and does not poison the owner`() {
        val original = state().also { it.initialize(manifest(TradingResourceLimits(maxIdentifierBytes = 8))) }
        assertEquals(EpochStatus.INVALID_CHANNEL, original.rotateEpoch(UserId("long-user"), 0, 0).status)
        assertFalse(original.isBlocked)
        assertEquals(EpochStatus.ISSUED, original.rotateEpoch(UserId("u"), 0, 0).status)
    }

    @Test
    fun `funding and restore enforce the same identifier byte cap without changing money`() {
        val limitedManifest = manifest(TradingResourceLimits(maxIdentifierBytes = 8))
        val limited = state().also { it.initialize(limitedManifest) }
        val rejected = assertFailsWith<TradingFundingInputException> { fund(limited, UserId("long-user"), usdt, 1000, "f") }
        assertEquals(TradingFundingRejection.INVALID_IDENTIFIER, rejected.reason)
        assertFalse(limited.isBlocked)
        assertEquals(0, limited.boundedRetention().balances)
        assertEquals(
            TradingFundingRejection.UNKNOWN_ASSET,
            assertFailsWith<TradingFundingInputException> { fund(limited, buyer, AssetId("ETH"), 1000, "f") }.reason,
        )
        fund(limited, buyer, usdt, 1000, "f")
        assertEquals(Amount(1000), limited.currentBalance(buyer, usdt).available)
        val old = state().also { it.initialize(manifest()) }
        fund(old, UserId("long-user"), usdt, 1000, "f")
        val recovered = state()
        assertFailsWith<IllegalArgumentException> {
            recovered.restoreRecoveryState(
                alterRecovery(old.recoveryState(), manifest = limitedManifest),
            )
        }
        assertTrue(recovered.isBlocked)
    }

    @Test
    fun `new owner requires genesis and cannot access legacy history API`() {
        val state = state()
        assertEquals(TradingLifecycle.UNINITIALIZED, state.lifecycle)
        assertFailsWith<IllegalStateException> { fund(state, buyer, usdt, 1000) }
        assertEquals(InitializationReceipt.INITIALIZED, state.initialize(manifest()))
        assertEquals(InitializationReceipt.ALREADY_INITIALIZED, state.initialize(manifest()))
        assertFailsWith<IllegalArgumentException> { state.initialize(manifest(fee = 1000)) }
        assertFailsWith<IllegalStateException> { state.snapshot() }
        assertEquals(TradingLifecycle.RUNNING, state.lifecycle)
    }

    @Test
    fun `first insufficient funds survives funding and receipt retirement never reexecutes money`() {
        val state =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
            }
        val token = RequestToken(buyer, 0, 1, 1)
        val value = intent(Side.BUY, 100, 3)
        val first = assertIs<AdmissionReply.Recorded>(state.admit(token, value, at))
        assertEquals(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE, first.receipt.reason)
        fund(state, buyer, usdt, 1000)
        val repeated = assertIs<AdmissionReply.Recorded>(state.admit(token, value, at.plusSeconds(1)))
        assertFalse(repeated.newlyRecorded)
        assertEquals(first.receipt, repeated.receipt)
        val accepted = accepted(state, buyer, 2, value)
        assertEquals(Amount(700), state.currentBalance(buyer, usdt).available)
        assertEquals(Amount(300), state.currentBalance(buyer, usdt).hold)
        assertEquals(
            "REQUEST_CONTENT_CONFLICT",
            assertIs<AdmissionReply.Refused>(state.admit(RequestToken(buyer, 0, 1, 2), value.copy(quantity = Quantity(2)), at)).reason,
        )
        val beforeMarketConflict = state.recoveryState()
        assertEquals(
            "REQUEST_CONTENT_CONFLICT",
            assertIs<AdmissionReply.Refused>(
                state.admit(RequestToken(buyer, 0, 1, 2), value.copy(marketId = suiMarket.marketId), at),
            ).reason,
        )
        assertEquals(beforeMarketConflict.balances, state.recoveryState().balances)
        assertEquals(beforeMarketConflict.liveOrders, state.recoveryState().liveOrders)
        assertEquals(beforeMarketConflict.inbox, state.recoveryState().inbox)
        assertEquals(2, state.boundedRetention().balances) // Conflicting SUI content must not allocate a receiver key.
        assertEquals(AckStatus.ACKNOWLEDGED, state.acknowledgeReceipts(buyer, 0, 1, 2).status)
        assertEquals("RETIRED_UNAVAILABLE", assertIs<AdmissionReply.Refused>(state.admit(token, value, at)).reason)
        assertEquals(1, state.boundedRetention().pendingOrders)
        execute(state, accepted)
        assertEquals(ExecutionReply.AlreadyPassed, execute(state, accepted))
        assertEquals(Amount(300), state.currentBalance(buyer, usdt).hold)
    }

    @Test
    fun `two markets share quote funds but independent execution watermarks`() {
        val state =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
            }
        fund(state, buyer, usdt, 1000)
        val btcReceipt = accepted(state, buyer, 1, intent(Side.BUY, 100, 6))
        val denied = assertIs<AdmissionReply.Recorded>(state.admit(RequestToken(buyer, 0, 1, 2), intent(Side.BUY, 100, 5, suiMarket), at))
        assertEquals(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE, denied.receipt.reason)
        val suiReceipt = accepted(state, buyer, 3, intent(Side.BUY, 100, 1, suiMarket))
        assertIs<ExecutionReply.Applied>(execute(state, suiReceipt, suiMarket))
        assertIs<ExecutionReply.Applied>(execute(state, btcReceipt))
        assertEquals(Amount(300), state.currentBalance(buyer, usdt).available)
        assertEquals(Amount(700), state.currentBalance(buyer, usdt).hold)
        assertEquals(listOf(1L, 1L), state.progress().markets.map { it.executedThrough })
    }

    @Test
    fun `SUI reserving first leaves BTC only the same remaining shared quote balance`() {
        val state =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
            }
        fund(state, buyer, usdt, 1000)
        val suiReceipt = accepted(state, buyer, 1, intent(Side.BUY, 100, 5, suiMarket))
        val denied = assertIs<AdmissionReply.Recorded>(state.admit(RequestToken(buyer, 0, 1, 2), intent(Side.BUY, 100, 6), at))
        assertTrue(denied.newlyRecorded)
        assertEquals(AdmissionCode.REJECTED, denied.receipt.code)
        assertEquals(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE, denied.receipt.reason)
        assertEquals(Amount(500), state.currentBalance(buyer, usdt).available)
        assertEquals(Amount(500), state.currentBalance(buyer, usdt).hold)
        assertEquals(1, state.boundedRetention().liveOrders)
        assertEquals(1, state.boundedRetention().pendingOrders)
        assertEquals(
            suiMarket.marketId,
            state
                .recoveryState()
                .liveOrders
                .single()
                .order.marketId,
        )
        assertIs<ExecutionReply.Applied>(execute(state, suiReceipt, suiMarket))
        assertTrue(state.book(btcMarket.marketId).isEmpty())
        assertEquals(Amount(500), state.currentBalance(buyer, usdt).available)
        assertEquals(Amount(500), state.currentBalance(buyer, usdt).hold)
    }

    @Test
    fun `future or mismatched execution cannot bypass the first pending order or change financial state`() {
        val state =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
            }
        fund(state, buyer, usdt, 1000)
        val btcFirst = accepted(state, buyer, 1, intent(Side.BUY, 100, 1))
        val suiFirst = accepted(state, buyer, 2, intent(Side.BUY, 100, 1, suiMarket))
        val btcSecond = accepted(state, buyer, 3, intent(Side.BUY, 100, 1))
        val before = state.recoveryState()
        val invalid =
            listOf(
                ExecuteAdmission(lineage, btcMarket.marketId, 3, requireNotNull(btcSecond.admissionSequence)),
                ExecuteAdmission(
                    lineage,
                    btcMarket.marketId,
                    requireNotNull(btcSecond.marketAdmissionSequence),
                    requireNotNull(btcSecond.admissionSequence),
                ),
                ExecuteAdmission(
                    lineage,
                    btcMarket.marketId,
                    requireNotNull(btcFirst.marketAdmissionSequence),
                    requireNotNull(suiFirst.admissionSequence),
                ),
                ExecuteAdmission(
                    lineage,
                    suiMarket.marketId,
                    requireNotNull(suiFirst.marketAdmissionSequence),
                    requireNotNull(btcFirst.admissionSequence),
                ),
            )
        for (command in invalid) {
            assertFailsWith<IllegalArgumentException> { state.executePending(command, at) }
            assertFalse(state.isBlocked)
            val after = state.recoveryState()
            assertEquals(Amount(700), state.currentBalance(buyer, usdt).available)
            assertEquals(Amount(300), state.currentBalance(buyer, usdt).hold)
            assertEquals(before.balances, after.balances)
            assertEquals(before.liveOrders, after.liveOrders)
            assertEquals(before.markets, after.markets)
            assertEquals(before.inbox, after.inbox)
            assertEquals(before.feeRevenueByAsset, after.feeRevenueByAsset)
            assertTrue(state.book(btcMarket.marketId).isEmpty())
            assertTrue(state.book(suiMarket.marketId).isEmpty())
        }
        // Only each market's local order is constrained; SUI can execute before BTC.
        assertIs<ExecutionReply.Applied>(execute(state, suiFirst, suiMarket))
        assertIs<ExecutionReply.Applied>(execute(state, btcFirst))
        assertIs<ExecutionReply.Applied>(execute(state, btcSecond))
        assertEquals(0, state.boundedRetention().pendingOrders)
        assertEquals(listOf(2L, 1L), state.progress().markets.map { it.executedThrough })
        assertEquals(Amount(700), state.currentBalance(buyer, usdt).available)
        assertEquals(Amount(300), state.currentBalance(buyer, usdt).hold)
    }

    @Test
    fun `receiver asset capacity is checked before reserving a funded quote balance`() {
        val limits = TradingResourceLimits(maxBalanceKeys = 1, maxPreparations = 1)
        val state =
            state().also {
                it.initialize(manifest(limits))
                it.rotateEpoch(buyer, 0, 0)
            }
        fund(state, buyer, usdt, 1000)
        val token = RequestToken(buyer, 0, 1, 1)
        val reply = assertIs<AdmissionReply.Refused>(state.admit(token, intent(Side.BUY, 100, 1), at))
        assertEquals("RETRYABLE_CAPACITY", reply.reason)
        assertEquals(Amount(1000), state.currentBalance(buyer, usdt).available)
        assertEquals(Amount.ZERO, state.currentBalance(buyer, usdt).hold)
        assertEquals(0, state.boundedRetention().receipts)
    }

    @Test
    fun `SELL requires its receiving quote key before reserving base and settles at the exact key cap`() {
        val limited =
            state().also {
                it.initialize(manifest(TradingResourceLimits(maxBalanceKeys = 1, maxPreparations = 1)))
                it.rotateEpoch(seller, 0, 0)
            }
        fund(limited, seller, btc, 1)
        val refused = assertIs<AdmissionReply.Refused>(limited.admit(RequestToken(seller, 0, 1, 1), intent(Side.SELL, 100, 1), at))
        assertEquals("RETRYABLE_CAPACITY", refused.reason)
        assertEquals(Amount(1), limited.currentBalance(seller, btc).available)
        assertEquals(Amount.ZERO, limited.currentBalance(seller, btc).hold)
        assertEquals(1, limited.boundedRetention().balances)
        assertEquals(0, limited.boundedRetention().receipts)
        assertEquals(0, limited.boundedRetention().pendingOrders)
        assertEquals(0L, limited.progress().lastAdmissionSequence)
        assertFalse(limited.isBlocked)

        val exact =
            state().also {
                it.initialize(manifest(TradingResourceLimits(maxBalanceKeys = 4, maxPreparations = 2)))
                it.rotateEpoch(seller, 0, 0)
                it.rotateEpoch(buyer, 0, 0)
            }
        fund(exact, seller, btc, 1)
        fund(exact, buyer, usdt, 100)
        val sell = accepted(exact, seller, 1, intent(Side.SELL, 100, 1))
        assertEquals(3, exact.boundedRetention().balances)
        assertEquals(Amount.ZERO, exact.currentBalance(seller, usdt).available)
        assertEquals(Amount(1), exact.currentBalance(seller, btc).hold)
        assertIs<ExecutionReply.Applied>(execute(exact, sell))
        val buy = accepted(exact, buyer, 1, intent(Side.BUY, 100, 1))
        assertEquals(4, exact.boundedRetention().balances)
        assertIs<ExecutionReply.Applied>(execute(exact, buy))
        assertEquals(Amount(100), exact.currentBalance(seller, usdt).available)
        assertEquals(Amount.ZERO, exact.currentBalance(seller, btc).hold)
        assertEquals(Amount(1), exact.currentBalance(buyer, btc).available)
        assertEquals(Amount.ZERO, exact.currentBalance(buyer, usdt).available)
        assertEquals(Amount.ZERO, exact.currentBalance(buyer, usdt).hold)
        assertEquals(4, exact.boundedRetention().balances)
        assertEquals(0, exact.boundedRetention().liveOrders)
        assertEquals(0, exact.boundedRetention().pendingOrders)
        assertFalse(exact.isBlocked)
    }

    @Test
    fun `approval preserves existing nonreserved asset and tracks both existing and new zero keys as used`() {
        val existing =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
            }
        fund(existing, buyer, usdt, 1000)
        fund(existing, buyer, btc, 7)
        val originalBase = existing.currentBalance(buyer, btc)
        val first = accepted(existing, buyer, 1, intent(Side.BUY, 100, 2))
        assertSame(originalBase, existing.currentBalance(buyer, btc))
        assertEquals(Amount(800), existing.currentBalance(buyer, usdt).available)
        assertEquals(Amount(200), existing.currentBalance(buyer, usdt).hold)
        assertEquals(2, existing.boundedRetention().balances)
        assertEquals(TradingIdentifiers.order(lineage, 1), first.orderId)

        val newReceiver =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
            }
        fund(newReceiver, buyer, usdt, 1000)
        accepted(newReceiver, buyer, 1, intent(Side.BUY, 100, 2))
        assertEquals(Amount.ZERO, newReceiver.currentBalance(buyer, btc).available)
        assertEquals(Amount.ZERO, newReceiver.currentBalance(buyer, btc).hold)
        assertEquals(2, newReceiver.boundedRetention().balances)
        assertTrue(TradingBalanceKey(buyer, btc) in newReceiver.recoveryState().usedBalances)
        assertTrue(TradingBalanceKey(buyer, usdt) in newReceiver.recoveryState().usedBalances)
        assertFailsWith<OpeningBalanceConflictException> { fund(newReceiver, buyer, btc, 1) }
    }

    @Test
    fun `late maker fill survives receipt ack and snapshot without a full completion`() {
        val original =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
                it.rotateEpoch(seller, 0, 0)
            }
        fund(original, buyer, usdt, 1000)
        fund(original, seller, btc, 2)
        val sell = accepted(original, seller, 1, intent(Side.SELL, 90, 2))
        execute(original, sell)
        original.acknowledgeReceipts(seller, 0, 1, 1)
        val buy1 = accepted(original, buyer, 1, intent(Side.BUY, 100, 1))
        execute(original, buy1)
        original.acknowledgeReceipts(buyer, 0, 1, 1)
        val snapshot = original.recoveryState()
        assertEquals(1, snapshot.liveOrders.size)
        assertEquals(
            Quantity(1),
            snapshot.liveOrders
                .single()
                .reservation.remainingQuantity,
        )
        val restored = state().also { it.restoreRecoveryState(snapshot) }
        for (owner in listOf(original, restored)) {
            val buy2 = accepted(owner, buyer, 2, intent(Side.BUY, 100, 1))
            execute(owner, buy2)
            owner.acknowledgeReceipts(buyer, 0, 1, 2)
            assertEquals(Amount(820), owner.currentBalance(buyer, usdt).available)
            assertEquals(Amount(2), owner.currentBalance(buyer, btc).available)
            assertEquals(Amount(180), owner.currentBalance(seller, usdt).available)
            assertEquals(0, owner.boundedRetention().liveOrders)
            assertTrue(owner.book(btcMarket.marketId).isEmpty())
            assertEquals(ExecutionReply.AlreadyPassed, execute(owner, sell))
            assertFailsWith<OpeningBalanceConflictException> { fund(owner, seller, btc, 2, "new-funding-id") }
            assertTrue(fund(owner, seller, btc, 2).alreadyPrepared)
        }
        assertEquals(original.recoveryState().balances, restored.recoveryState().balances)
        assertEquals(original.recoveryState().inbox, restored.recoveryState().inbox)
        assertEquals(original.recoveryState().markets, restored.recoveryState().markets)
    }

    @Test
    fun `completed history does not accumulate after repeated matched orders and acknowledgements`() {
        val state =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
                it.rotateEpoch(seller, 0, 0)
            }
        fund(state, buyer, usdt, 100_000)
        fund(state, seller, btc, 500)
        repeat(300) { index ->
            val sequence = index.toLong() + 1
            execute(state, accepted(state, seller, sequence, intent(Side.SELL, 90, 1)))
            execute(state, accepted(state, buyer, sequence, intent(Side.BUY, 100, 1)))
            state.acknowledgeReceipts(seller, 0, 1, sequence)
            state.acknowledgeReceipts(buyer, 0, 1, sequence)
            val retention = state.boundedRetention()
            assertEquals(0, retention.liveOrders)
            assertEquals(0, retention.pendingOrders)
            assertEquals(0, retention.receipts)
            assertEquals(2, retention.preparations)
            assertEquals(4, retention.balances)
        }
        val recovery = state.recoveryState()
        assertEquals(600L, recovery.lastAdmissionSequence)
        state().restoreRecoveryState(recovery)
    }

    @Test
    fun `two markets accumulate only settled quote fees and replay preserves totals`() {
        val state =
            state().also {
                it.initialize(manifest(fee = 100_000))
                it.rotateEpoch(buyer, 0, 0)
                it.rotateEpoch(seller, 0, 0)
            }
        fund(state, buyer, usdt, 500)
        fund(state, seller, btc, 1)
        fund(state, seller, sui, 1)
        execute(state, accepted(state, seller, 1, intent(Side.SELL, 90, 1)))
        execute(state, accepted(state, seller, 2, intent(Side.SELL, 50, 1, suiMarket)), suiMarket)
        assertTrue(state.recoveryState().feeRevenueByAsset.isEmpty())
        val btcBuy = accepted(state, buyer, 1, intent(Side.BUY, 100, 1))
        execute(state, btcBuy)
        assertEquals(BigInteger.valueOf(18), state.recoveryState().feeRevenueByAsset[usdt])
        assertEquals(Amount(401), state.currentBalance(buyer, usdt).available)
        assertEquals(Amount(81), state.currentBalance(seller, usdt).available)
        val suiBuy = accepted(state, buyer, 2, intent(Side.BUY, 60, 1, suiMarket))
        execute(state, suiBuy, suiMarket)
        val saved = state.recoveryState()
        assertEquals(mapOf(usdt to BigInteger.valueOf(28)), saved.feeRevenueByAsset)
        assertEquals(Amount(346), state.currentBalance(buyer, usdt).available)
        assertEquals(Amount(126), state.currentBalance(seller, usdt).available)
        assertEquals(Amount.ZERO, state.currentBalance(buyer, usdt).hold)
        assertTrue(saved.liveOrders.isEmpty())
        assertEquals(ExecutionReply.AlreadyPassed, execute(state, suiBuy, suiMarket))
        val restored = state().also { it.restoreRecoveryState(saved) }
        assertEquals(ExecutionReply.AlreadyPassed, execute(restored, btcBuy))
        assertEquals(saved.balances, restored.recoveryState().balances)
        assertEquals(saved.feeRevenueByAsset, restored.recoveryState().feeRevenueByAsset)
    }

    @Test
    fun `fee revenue above Long range is exact and recovery preserves supply`() {
        val state =
            state().also {
                it.initialize(manifest(fee = 1_000_000))
                it.rotateEpoch(seller, 0, 0)
            }
        fund(state, seller, btc, 2)
        val price = Long.MAX_VALUE / 3
        var lastBuy: AdmissionReceipt? = null
        repeat(2) { index ->
            val customer = UserId("customer-$index")
            state.rotateEpoch(customer, 0, 0)
            fund(state, customer, usdt, Long.MAX_VALUE)
            execute(state, accepted(state, seller, index.toLong() + 1, intent(Side.SELL, price, 1)))
            val buy = accepted(state, customer, 1, intent(Side.BUY, price, 1))
            execute(state, buy)
            lastBuy = buy
        }
        val recovery = state.recoveryState()
        val expected = BigInteger.valueOf(price).multiply(BigInteger.valueOf(4))
        assertTrue(expected > BigInteger.valueOf(Long.MAX_VALUE))
        assertEquals(expected, recovery.feeRevenueByAsset[usdt])
        assertEquals(ExecutionReply.AlreadyPassed, execute(state, requireNotNull(lastBuy)))
        assertEquals(recovery.balances, state.recoveryState().balances)
        assertEquals(recovery.markets, state.recoveryState().markets)
        assertEquals(expected, state.recoveryState().feeRevenueByAsset[usdt])
        val restored = state().also { it.restoreRecoveryState(recovery) }
        assertEquals(ExecutionReply.AlreadyPassed, execute(restored, requireNotNull(lastBuy)))
        assertEquals(recovery.balances, restored.recoveryState().balances)
        assertEquals(expected, restored.recoveryState().feeRevenueByAsset[usdt])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `classified read-only validation failure can persist stable quarantine without publishing partial money`() {
        val state =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
                it.rotateEpoch(seller, 0, 0)
            }
        fund(state, buyer, usdt, 1000)
        fund(state, seller, btc, 1)
        execute(state, accepted(state, seller, 1, intent(Side.SELL, 90, 1)))
        val buy = accepted(state, buyer, 1, intent(Side.BUY, 100, 1))
        val before = state.recoveryState()
        val engine = TradingStateFailureTestSupport.read(state, "engine")
        val identities = TradingStateFailureTestSupport.map(engine, "seenOrderIds")
        val delegate = identities.getValue(btcMarket.marketId) as MutableSet<Any>
        identities[btcMarket.marketId] =
            object : MutableSet<Any> by delegate {
                override fun contains(element: Any): Boolean = throw IllegalStateException("injected-validation-detail-not-persisted")
            }
        val failure = assertFailsWith<TradingInvariantViolation> { execute(state, buy) }
        assertEquals(TradingInvariantReason.MATCHING_CANDIDATE_INVALID, failure.reason)
        assertEquals(TradingExecutionPhase.MATCHING_CANDIDATE, failure.stage)
        assertNull(failure.cause)
        assertFalse(state.isBlocked)
        assertEquals(before.balances, state.recoveryState().balances)
        assertEquals(before.liveOrders, state.recoveryState().liveOrders)
        state.quarantine(42, failure.reason.name)
        state.quarantine(42, failure.reason.name)
        val quarantined = state.recoveryState()
        assertEquals(TradingIncident(42, "MATCHING_CANDIDATE_INVALID"), quarantined.incident)
        assertEquals(TradingLifecycle.QUARANTINED, quarantined.lifecycle)
        val restored = state().also { it.restoreRecoveryState(quarantined) }
        assertEquals(quarantined.incident, restored.recoveryState().incident)
        assertFailsWith<IllegalStateException> { restored.currentBalance(buyer, usdt) }
        assertFailsWith<IllegalStateException> { execute(restored, buy) }
    }

    @Test
    fun `partial engine apply blocks reads and snapshot and only a fresh recovered owner resumes`() {
        val state =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
                it.rotateEpoch(seller, 0, 0)
            }
        fund(state, buyer, usdt, 1000)
        fund(state, seller, btc, 1)
        execute(state, accepted(state, seller, 1, intent(Side.SELL, 90, 1)))
        val buy = accepted(state, buyer, 1, intent(Side.BUY, 100, 1))
        val before = state.recoveryState()
        val failure = IllegalStateException("injected-after-engine-identity-write")
        val engine = TradingStateFailureTestSupport.read(state, "engine")
        val probe = TradingStateFailureTestSupport.failAfterNestedSetAdd(engine, "seenOrderIds", btcMarket.marketId, failure)
        assertSame(failure, assertFailsWith<IllegalStateException> { execute(state, buy) })
        assertEquals(1, probe.count)
        assertTrue(state.isBlocked)
        assertFailsWith<IllegalStateException> { state.book(btcMarket.marketId) }
        assertFailsWith<IllegalStateException> { state.recoveryState() }
        assertFailsWith<IllegalStateException> { state.currentBalance(buyer, usdt) }
        val recovered = state().also { it.restoreRecoveryState(before) }
        assertIs<ExecutionReply.Applied>(execute(recovered, buy))
        assertEquals(Amount(910), recovered.currentBalance(buyer, usdt).available)
        assertEquals(Amount.ZERO, recovered.currentBalance(buyer, usdt).hold)
        assertEquals(Amount(1), recovered.currentBalance(buyer, btc).available)
    }

    @Test
    fun `funding original result cannot be overwritten and quarantine blocks all normal entry points`() {
        val state =
            state().also {
                it.initialize(manifest())
                it.rotateEpoch(buyer, 0, 0)
            }
        val first = fund(state, buyer, usdt, 1000)
        assertEquals(TradingIdentifiers.opening(lineage, 1), first.ledgerTransactionId)
        assertFailsWith<OpeningBalanceRequestConflictException> { fund(state, buyer, usdt, 2000) }
        state.quarantine(42, "FINANCIAL_INVARIANT")
        assertEquals(TradingLifecycle.QUARANTINED, state.lifecycle)
        assertFailsWith<IllegalStateException> { state.admit(RequestToken(buyer, 0, 1, 1), intent(Side.BUY, 100, 1), at) }
        assertFailsWith<IllegalStateException> { state.currentBalance(buyer, usdt) }
        val restored = state().also { it.restoreRecoveryState(state.recoveryState()) }
        assertEquals(TradingLifecycle.QUARANTINED, restored.lifecycle)
        assertFailsWith<IllegalStateException> { fund(restored, buyer, usdt, 1000) }
    }
}
