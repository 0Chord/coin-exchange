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
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FundsDispatchDataTest {
    private val lineage = ClusterLineage(1, 2)
    private val quote = AssetId("USD")
    private val a = TradingMarketContext(MarketDefinition(MarketId("A"), AssetId("BTC"), quote, 0), policy(), 0)
    private val b = TradingMarketContext(MarketDefinition(MarketId("B"), AssetId("SUI"), quote, 0), policy(), 1)
    private val contexts = listOf(a, b)
    private val calculator =
        OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())))

    @Test
    fun `logical charge v1 has independently counted fixed slots and UTF8 strings`() {
        val original = live(1, 1, a)
        // order: 28 + 5(A) + 38(ID) + 5(u) = 76.
        // reservation: 81 + 5 + 38 + 5 + 7(USD) = 136.
        // context: 56 + 5(A) + 7(BTC) + 7(USD) = 75.
        // active: 24 + 76 + 136 + 75 = 311.
        // dispatch: 80 + 5(route) + 38(reserve ID) + 287 = 410.
        assertEquals(311L, FundsDispatchCharge.active(original.order, a))
        assertEquals(410L, FundsDispatchCharge.dispatch(original.order, a))
        val korean = original.order.copy(userId = UserId("한"))
        assertEquals(315L, FundsDispatchCharge.active(korean, a))
        assertEquals(414L, FundsDispatchCharge.dispatch(korean, a))
        assertEquals(410L, FundsDispatchCharge.dispatch(original.order.copy(price = Price(1_000_000)), a))
    }

    @Test
    fun `exact dispatch bytes admit and one byte less refuses without mutation`() {
        val original = live(1, 1, a, UserId("한"))
        val exactConfiguration = configuration(dispatchBytes = 414)
        val exact = FundsDispatchData(exactConfiguration)
        assertTrue(exact.canAdmit(original.order))
        exact.record(exact.prepare(original))
        assertEquals(414L, exact.snapshot().dispatchBytes)
        val short = FundsDispatchData(configuration(dispatchBytes = 413))
        val before = short.snapshot()
        assertFalse(short.canAdmit(original.order))
        assertFailsWith<IllegalArgumentException> { short.prepare(original) }
        assertEquals(before, short.snapshot())
    }

    @Test
    fun `active byte retention remains full after delivery acknowledgement`() {
        val config = configuration(activeBytes = 315)
        val data = FundsDispatchData(config)
        val original = live(1, 1, a, UserId("한"))
        data.record(data.prepare(original))
        assertEquals(315L, data.snapshot().activeBytes)
        assertEquals(FundsDispatchAckStatus.ACKNOWLEDGED, data.acknowledge(ack(config, a, 1)).status)
        assertEquals(0L, data.snapshot().dispatchBytes)
        assertEquals(315L, data.snapshot().activeBytes)
        assertEquals(1, data.snapshot().activeOrders)
        assertFalse(data.canAdmit(live(2, 2, a).order))
    }

    @Test
    fun `market dispatch cap leaves another market available while global cap stops both`() {
        val config = configuration(dispatchEntries = 2, marketDispatchEntries = 1)
        val data = FundsDispatchData(config)
        data.record(data.prepare(live(1, 1, a)))
        assertFalse(data.canAdmit(live(2, 2, a).order))
        assertTrue(data.canAdmit(live(2, 1, b).order))
        data.record(data.prepare(live(2, 1, b)))
        assertFalse(data.canAdmit(live(3, 2, b).order))
        assertEquals(2, data.snapshot().dispatchEntries)
    }

    @Test
    fun `read is immutable nonconsuming bounded and distinguishes cursor outcomes`() {
        val config = configuration()
        val data = FundsDispatchData(config)
        data.record(data.prepare(live(1, 1, a)))
        data.record(data.prepare(live(2, 2, a)))
        val before = data.snapshot()
        val stream = config.stream(a.market.marketId)
        val small = data.read(stream, 1, 10, 409)
        assertEquals(FundsDispatchReadStatus.BATCH_TOO_SMALL, small.status)
        assertEquals(410L, small.requiredBytes)
        val first = data.read(stream, 1, 1, 1_000)
        assertEquals(listOf(1L), first.entries.map { it.dispatchSequence })
        assertEquals(2L, first.nextSequence)
        val byteLimited = data.read(stream, 1, 10, 819)
        assertEquals(listOf(1L), byteLimited.entries.map { it.dispatchSequence })
        assertEquals(listOf(1L, 2L), data.read(stream, 1, 10, 820).entries.map { it.dispatchSequence })
        assertEquals(FundsDispatchReadStatus.BATCH, data.read(stream, 3, 10, 1_000).status)
        assertEquals(3L, data.read(stream, 3, 10, 1_000).nextSequence)
        assertEquals(FundsDispatchReadStatus.INVALID_QUERY, data.read(stream, 4, 10, 1_000).status)
        assertEquals(FundsDispatchReadStatus.INVALID_QUERY, data.read(stream, 0, 10, 1_000).status)
        assertEquals(FundsDispatchReadStatus.INVALID_QUERY, data.read(stream, 1, 0, 1_000).status)
        assertEquals(FundsDispatchReadStatus.INVALID_QUERY, data.read(stream, 1, 10, 0).status)
        assertEquals(FundsDispatchReadStatus.INVALID_QUERY, data.read(stream.copy(ownershipEpoch = 2), 0, 10, 1_000).status)
        assertEquals(FundsDispatchReadStatus.INVALID_STREAM, data.read(stream.copy(ownershipEpoch = 2), 1, 10, 1_000).status)
        assertFailsWith<UnsupportedOperationException> { (first.entries as MutableList<FundsOrderDispatch>).clear() }
        assertEquals(before, data.snapshot())
        data.acknowledge(ack(config, a, 1))
        val pruned = data.read(stream, 1, 10, 1_000)
        assertEquals(FundsDispatchReadStatus.PRUNED, pruned.status)
        assertEquals(2L, pruned.nextSequence)
        assertEquals(listOf(1L), first.entries.map { it.dispatchSequence })
    }

    @Test
    fun `cumulative acknowledgement retires only its market and never active originals`() {
        val config = configuration()
        val data = FundsDispatchData(config)
        val originals = listOf(live(1, 1, a), live(2, 1, b), live(3, 2, a))
        originals.forEach { data.record(data.prepare(it)) }
        val before = data.snapshot()
        assertEquals(FundsDispatchAckStatus.ACKNOWLEDGED, data.acknowledge(ack(config, a, 2)).status)
        val saved = data.snapshot()
        assertEquals(1, saved.dispatchEntries)
        assertEquals(listOf(2L), saved.streams.flatMap { it.outstanding }.map { it.admissionSequence })
        assertEquals(3, saved.activeOrders)
        assertEquals(before.activeBytes, saved.activeBytes)
        assertEquals(2L, saved.streams.first().matchingAppliedThrough)
        assertEquals(0L, saved.streams.last().matchingAppliedThrough)
        data.acknowledge(ack(config, a, 1))
        data.acknowledge(ack(config, a, 2))
        assertEquals(saved, data.snapshot())
        assertEquals(3, before.streams.flatMap { it.outstanding }.size)
        assertFailsWith<UnsupportedOperationException> { (saved.streams as MutableList<FundsDispatchStreamSnapshot>).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (saved.streams.last().outstanding as MutableList<FundsOrderDispatch>).clear()
        }
    }

    @Test
    fun `wrong identity or policy is rejected even for a duplicate and future ack is not sticky`() {
        val config = configuration()
        val data = FundsDispatchData(config)
        data.record(data.prepare(live(1, 1, a)))
        data.acknowledge(ack(config, a, 1))
        val before = data.snapshot()
        val valid = ack(config, a, 1)
        val wrongIdentity =
            listOf(
                valid.copy(stream = valid.stream.copy(fundsLineage = ClusterLineage(9, 9))),
                valid.copy(stream = valid.stream.copy(matchingLineage = ClusterLineage(8, 8))),
                valid.copy(stream = valid.stream.copy(ownershipEpoch = 2)),
                valid.copy(stream = valid.stream.copy(configurationVersion = 2)),
            )
        wrongIdentity.forEach { assertEquals(FundsDispatchAckStatus.INVALID_STREAM, data.acknowledge(it).status) }
        assertEquals(
            FundsDispatchAckStatus.INVALID_POLICY,
            data.acknowledge(valid.copy(context = a.copy(feePolicySnapshot = policy(2)))).status,
        )
        assertEquals(FundsDispatchAckStatus.INVALID_SEQUENCE, data.acknowledge(valid.copy(through = -1)).status)
        assertEquals(FundsDispatchAckStatus.FUTURE_ACK, data.acknowledge(valid.copy(through = 2)).status)
        assertEquals(before, data.snapshot())
        data.record(data.prepare(live(2, 2, a)))
        assertEquals(1, data.snapshot().dispatchEntries)
        assertEquals(
            1L,
            data
                .snapshot()
                .streams
                .first()
                .matchingAppliedThrough,
        )
    }

    @Test
    fun `restored obligations and active retention preserve acknowledged prefix and suffix`() {
        val config = configuration()
        val data = FundsDispatchData(config)
        val originals = listOf(live(1, 1, a), live(2, 1, b), live(3, 2, a))
        originals.forEach { data.record(data.prepare(it)) }
        data.acknowledge(ack(config, a, 1))
        val snapshot = data.snapshot()
        val restored = FundsDispatchData(config)
        restored.restore(snapshot, originals, financial(originals))
        assertEquals(snapshot, restored.snapshot())
        val next = live(4, 3, a)
        for (owner in listOf(data, restored)) {
            owner.acknowledge(ack(config, a, 2))
            owner.record(owner.prepare(next))
        }
        assertEquals(data.snapshot(), restored.snapshot())
    }

    @Test
    fun `restore rejects erased obligations orphaned envelopes and altered counters without partial publication`() {
        val config = configuration()
        val data = FundsDispatchData(config)
        val original = live(1, 1, a)
        data.record(data.prepare(original))
        val good = data.snapshot()
        val first = good.streams.first()
        val emptyFirst = FundsDispatchStreamSnapshot(first.stream, 0, 0, emptyList(), 0, 1, 311)
        val damaged =
            listOf(
                FundsDispatchSnapshot(listOf(emptyFirst, good.streams.last()), 0, 0, 1, 311),
                FundsDispatchSnapshot(good.streams, 2, good.dispatchBytes, 1, 311),
                FundsDispatchSnapshot(good.streams, 1, good.dispatchBytes + 1, 1, 311),
                FundsDispatchSnapshot(
                    listOf(
                        FundsDispatchStreamSnapshot(
                            first.stream,
                            1,
                            0,
                            listOf(first.outstanding.single().copy(admissionSequence = 2)),
                            first.dispatchBytes,
                            1,
                            311,
                        ),
                        good.streams.last(),
                    ),
                    1,
                    first.dispatchBytes,
                    1,
                    311,
                ),
            )
        for (snapshot in damaged) {
            val restored = FundsDispatchData(config)
            val empty = restored.snapshot()
            assertFailsWith<IllegalArgumentException> { restored.restore(snapshot, listOf(original), financial(listOf(original))) }
            assertEquals(empty, restored.snapshot())
            restored.restore(good, listOf(original), financial(listOf(original)))
            assertEquals(good, restored.snapshot())
        }
    }

    @Test
    fun `configuration copies settings and enforces market coverage and feasible snapshot budgets`() {
        val local = linkedMapOf(a.market.marketId to MarketFundsDispatchLimits(), b.market.marketId to MarketFundsDispatchLimits())
        val limits = FundsDispatchLimits(local)
        val config = FundsDispatchConfiguration(topology(), limits)
        local.clear()
        assertEquals(2, config.limits.marketLimits.size)
        assertEquals(config, FundsDispatchConfiguration(topology(), FundsDispatchLimits(limits.marketLimits)))
        assertTrue(config.snapshotByteUpperBound <= FundsDispatchConfiguration.MAX_SNAPSHOT_BYTES)
        assertFailsWith<UnsupportedOperationException> { (limits.marketLimits as MutableMap<MarketId, MarketFundsDispatchLimits>).clear() }
        assertFailsWith<IllegalArgumentException> {
            FundsDispatchConfiguration(topology(), FundsDispatchLimits(mapOf(a.market.marketId to MarketFundsDispatchLimits())))
        }
        assertFailsWith<IllegalArgumentException> { configuration(dispatchBytes = 409) }
        assertFailsWith<IllegalArgumentException> { configuration(activeBytes = 310) }
        assertFailsWith<IllegalArgumentException> { configuration(dispatchBytes = 100L * 1_024 * 1_024) }
        assertFailsWith<IllegalArgumentException> { configuration(dispatchBytes = Long.MAX_VALUE) }
    }

    private fun configuration(
        dispatchEntries: Int = 10,
        marketDispatchEntries: Int = 10,
        dispatchBytes: Long = 10_000,
        activeBytes: Long = 10_000,
    ): FundsDispatchConfiguration =
        FundsDispatchConfiguration(
            topology(),
            FundsDispatchLimits(
                contexts.associate {
                    it.market.marketId to
                        MarketFundsDispatchLimits(
                            maxDispatchEntries = marketDispatchEntries,
                            maxDispatchBytes = dispatchBytes,
                            maxActiveOrders = 10,
                            maxActiveBytes = activeBytes,
                        )
                },
                maxDispatchEntries = dispatchEntries,
                maxDispatchBytes = dispatchBytes,
                maxActiveOrders = 10,
                maxActiveBytes = activeBytes,
            ),
        )

    private fun topology(): TradingRoleTopology {
        val manifest = TradingClusterManifest(lineage, "test", "0".repeat(64), "1".repeat(64), 1, contexts, TradingResourceLimits())
        return TradingRoleTopology(manifest, contexts.map { TradingMarketAssignment(it.market.marketId, ClusterLineage(3, 4), 1) })
    }

    private fun live(
        admission: Long,
        marketAdmission: Long,
        context: TradingMarketContext,
        user: UserId = UserId("u"),
    ): TradingLiveOrderSnapshot {
        val input =
            OrderAdmissionInput(
                context.market.marketId,
                TradingIdentifiers.order(lineage, admission),
                user,
                Side.BUY,
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(100),
                Quantity(1),
            )
        val transition =
            calculator.calculateTransition(
                input,
                context.market,
                context.feePolicySnapshot,
                Balance(user, quote, Amount(1_000), Amount.ZERO),
            )
                as AdmissionTransitionResult.Calculated
        return TradingLiveOrderSnapshot(admission, marketAdmission, input, transition.reservation, LiveOrderPhase.PENDING)
    }

    private fun financial(live: List<TradingLiveOrderSnapshot>): List<TradingMarketProgressSnapshot> =
        contexts.map { context ->
            val orders = live.filter { it.order.marketId == context.market.marketId }.sortedBy { it.marketAdmissionSequence }
            TradingMarketProgressSnapshot(context.market.marketId, orders.size.toLong(), 0, orders.map { it.admissionSequence })
        }

    private fun ack(
        configuration: FundsDispatchConfiguration,
        context: TradingMarketContext,
        through: Long,
    ) = FundsMatchingAppliedAck(configuration.stream(context.market.marketId), through, context)

    private fun policy(version: Long = 1) =
        TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, version, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
}
