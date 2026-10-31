package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.OpeningBalanceConflictException
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.Side
import com.exchange.core.trading.TradingStateFixtures.admit
import com.exchange.core.trading.TradingStateFixtures.balance
import com.exchange.core.trading.TradingStateFixtures.btc
import com.exchange.core.trading.TradingStateFixtures.buyer
import com.exchange.core.trading.TradingStateFixtures.fund
import com.exchange.core.trading.TradingStateFixtures.market
import com.exchange.core.trading.TradingStateFixtures.order
import com.exchange.core.trading.TradingStateFixtures.seller
import com.exchange.core.trading.TradingStateFixtures.state
import com.exchange.core.trading.TradingStateFixtures.time
import com.exchange.core.trading.TradingStateFixtures.usdt
import com.exchange.core.trading.TradingStateFixtures.zeroPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TradingCheckpointTest {
    @Test
    fun `active checkpoint resumes a partial maker and pending taker with original fees FIFO and cold first results`() {
        val history = CheckpointHistory()
        val original = state(history)
        val rejectedOrder = order("arbitrary:거절🚀", buyer, Side.BUY, 51, 1)
        val rejected = original.admit("retry:거절🚀", rejectedOrder, market, zeroPolicy, "rejected-ledger", time)
        assertIs<OrderAdmissionState.Result.Rejected>(rejected)
        val nextSeller = UserId("S2")
        val policy = zeroPolicy.copy(feeRates = MakerTakerFeeRates(FeeRate(5_000), FeeRate(10_000)))
        fund(original, buyer, usdt, 1_000)
        fund(original, seller, btc, 1)
        fund(original, nextSeller, btc, 5)
        val initialSell = admit(original, order("S1", seller, Side.SELL, 51, 1), policy = policy)
        original.executePending(initialSell.record.sequence, time.plusSeconds(1))
        val firstBuy = admit(original, order("B1", buyer, Side.BUY, 51, 5), policy = policy)
        val firstCompletion = original.executePending(firstBuy.record.sequence, time.plusSeconds(2))
        val secondBuy = admit(original, order("B2", buyer, Side.BUY, 51, 2), policy = policy)
        original.executePending(secondBuy.record.sequence, time.plusSeconds(3))
        val pending = admit(original, order("S2", nextSeller, Side.SELL, 51, 5), policy = policy.copy(scheduleVersion = 2))
        history.forbidScans = true

        val checkpoint = original.checkpoint()
        val recoveredHistory = CheckpointHistory()
        val recovered = state(recoveredHistory)
        recoveredHistory.load(history)
        recoveredHistory.forbidScans = true
        recovered.restoreCheckpoint(checkpoint)

        assertEquals(3, checkpoint.admissions.size)
        assertEquals(2, checkpoint.completions.size)
        assertEquals(listOf(pending.record.sequence), checkpoint.pendingAdmissionSequences)
        assertEquals(0, original.historyRetention().stagedBatches)
        assertEquals(checkpoint.historyOrdinal, original.historyRetention().durableOrdinal)
        assertEquals(original.book(market.marketId), recovered.book(market.marketId))
        val restoredCheckpoint = recovered.checkpoint()
        val restoredBuy = restoredCheckpoint.admissions.single { it.sequence == firstBuy.record.sequence }
        assertSame(restoredBuy, restoredCheckpoint.completions.single { it.admission.sequence == firstBuy.record.sequence }.admission)
        val currentBuy = restoredCheckpoint.reservations.single { it.orderId == OrderId("B1") }
        assertEquals(FeeRemainder(510_000), currentBuy.feeRemainder)
        assertEquals(Amount(207), currentBuy.remainingAmount)
        assertEquals(policy, currentBuy.feePolicySnapshot)
        assertEquals(rejected, recovered.admit("retry:거절🚀", rejectedOrder, market, policy, "changed-ledger", time.plusSeconds(9)))
        assertEquals(
            OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT,
            assertIs<OrderAdmissionState.Result.Rejected>(
                recovered.admit("retry:거절🚀", rejectedOrder.copy(quantity = Quantity(2)), market, policy, "other-ledger", time),
            ).reason,
        )
        assertTrue(fund(recovered, seller, btc, 1).alreadyPrepared)
        assertFailsWith<OpeningBalanceConflictException> { fund(recovered, seller, btc, 1, preparationId = "new-funding") }
        assertFalse(recovered.isBlocked)

        val expected = original.executePending(pending.record.sequence, time.plusSeconds(4))
        val actual = recovered.executePending(pending.record.sequence, time.plusSeconds(4))

        assertEquals(expected.matchingResult, actual.matchingResult)
        assertEquals(listOf(OrderId("B1"), OrderId("B2")), actual.settlement.settlements.map { it.execution.makerOrderId })
        assertEquals(
            Amount(1),
            actual.settlement.settlements
                .first()
                .makerPlan.actualFeeAmount,
        )
        assertEquals(
            FeeRemainder(530_000),
            actual.settlement.settlements
                .first()
                .makerPlan.updatedReservation.feeRemainder,
        )
        assertEquals(original.book(market.marketId), recovered.book(market.marketId))
        assertEquals(original.checkpoint().balances, recovered.checkpoint().balances)
        assertEquals(
            firstCompletion.matchingResult,
            recovered.executePending(firstBuy.record.sequence, time.plusSeconds(99)).matchingResult,
        )
        val duplicate = recovered.admit("another-request", initialSell.record.candidate.order, market, policy, "another-ledger", time)
        assertEquals(OrderAdmissionResult.Reason.DUPLICATE_ORDER, assertIs<OrderAdmissionState.Result.Rejected>(duplicate).reason)
    }

    @Test
    fun `terminal history stays outside live checkpoint while empty books retain sequences and exact replay`() {
        val history = CheckpointHistory()
        val original = state(history)
        fund(original, seller, btc, 20)
        fund(original, buyer, usdt, 2_000)
        var firstSequence = 0L
        repeat(20) { index ->
            val sell = admit(original, order("S:$index", seller, Side.SELL, 100, 1))
            if (index == 0) firstSequence = sell.record.sequence
            original.executePending(sell.record.sequence, time)
            val buy = admit(original, order("B:$index", buyer, Side.BUY, 100, 1))
            original.executePending(buy.record.sequence, time)
        }
        history.forbidScans = true
        val checkpoint = original.checkpoint()
        val recoveredHistory = CheckpointHistory()
        val recovered = state(recoveredHistory)
        recoveredHistory.load(history)
        recoveredHistory.forbidScans = true

        recovered.restoreCheckpoint(checkpoint)

        assertEquals(emptyList(), checkpoint.admissions)
        assertEquals(emptyList(), checkpoint.reservations)
        assertEquals(emptyList(), checkpoint.completions)
        assertEquals(40L, checkpoint.lastAdmissionSequence)
        assertEquals(40L, recovered.lastEngineSequence(market.marketId))
        assertEquals(history.findCompletion(firstSequence)!!.matchingResult, recovered.executePending(firstSequence, time).matchingResult)
        val next = admit(recovered, order("new-buy", seller, Side.BUY, 100, 1))
        assertEquals(41L, next.record.sequence)
        assertEquals(41L, recovered.executePending(next.record.sequence, time).matchingResult.firstEngineSequence)
        assertEquals(balance(seller, btc, 0), recovered.checkpoint().balances.single { it.userId == seller && it.assetId == btc })
    }

    @Test
    fun `invalid hold FIFO status duplicates and sequence checkpoints block before live values are installed`() {
        val history = CheckpointHistory()
        val original = state(history)
        fund(original, buyer, usdt, 1_000)
        val first = admit(original, order("B1", buyer, Side.BUY, 100, 2))
        original.executePending(first.record.sequence, time)
        val second = admit(original, order("B2", buyer, Side.BUY, 100, 2))
        original.executePending(second.record.sequence, time)
        val checkpoint = original.checkpoint()
        val marketEntry = checkpoint.matching.markets.single()
        val reversedBook = MatchingCheckpoint.Market(marketEntry.marketId, 2, marketEntry.orders.reversed())
        val staleSequence = MatchingCheckpoint.Market(marketEntry.marketId, 1, marketEntry.orders)
        val invalid =
            listOf(
                copy(checkpoint, balances = checkpoint.balances.map { it.copy(hold = Amount(it.hold.value + 1)) }),
                copy(checkpoint, balances = checkpoint.balances + checkpoint.balances.first()),
                copy(checkpoint, usedBalances = emptyList()),
                copy(checkpoint, pending = listOf(first.record.sequence)),
                copy(checkpoint, reservations = checkpoint.reservations.map { it.copy(remainingAmount = Amount(1)) }),
                copy(checkpoint, completions = checkpoint.completions + checkpoint.completions.first()),
                copy(checkpoint, matching = MatchingCheckpoint(listOf(reversedBook))),
                copy(checkpoint, matching = MatchingCheckpoint(listOf(staleSequence))),
                copy(checkpoint, lastSequence = 1),
            )
        for (bad in invalid) {
            val recoveredHistory = CheckpointHistory()
            val recovered = state(recoveredHistory)
            recoveredHistory.load(history)
            assertFailsWith<RuntimeException> { recovered.restoreCheckpoint(bad) }
            assertTrue(recovered.isBlocked)
            assertEquals(0, installedBalanceCount(recovered))
            assertFailsWith<IllegalStateException> { recovered.snapshot() }
        }
    }

    @Test
    fun `restore requires an empty owner and exact external history ordinal and cannot restart blocked owners`() {
        val history = CheckpointHistory()
        val original = state(history)
        fund(original, buyer, usdt, 100)
        val checkpoint = original.checkpoint()
        val missingHistory = state(CheckpointHistory())
        assertFailsWith<IllegalStateException> { missingHistory.restoreCheckpoint(checkpoint) }
        assertTrue(missingHistory.isBlocked)
        assertFailsWith<IllegalStateException> { missingHistory.restoreCheckpoint(checkpoint) }
        assertFailsWith<IllegalStateException> { missingHistory.checkpoint() }
        assertFailsWith<IllegalStateException> { original.restoreCheckpoint(checkpoint) }
        assertTrue(original.isBlocked)
        assertFailsWith<IllegalStateException> { state().checkpoint() }
    }

    @Test
    fun `checkpoint lists are immutable and restoration canonicalizes separately decoded admission objects`() {
        val history = CheckpointHistory()
        val original = state(history)
        fund(original, buyer, usdt, 100)
        val accepted = admit(original, order("B1", buyer, Side.BUY, 100, 1))
        original.executePending(accepted.record.sequence, time)
        val source = original.checkpoint()
        val copiedAdmission = CheckpointHistory.copyAdmission(source.admissions.single())
        val admissionList = mutableListOf(copiedAdmission)
        val checkpoint =
            TradingCheckpoint(
                source.balances,
                source.usedBalances,
                admissionList,
                source.reservations,
                source.pendingAdmissionSequences,
                source.completions,
                source.lastAdmissionSequence,
                source.historyOrdinal,
                source.matching,
            )
        admissionList.clear()
        assertEquals(1, checkpoint.admissions.size)
        assertNotSame(checkpoint.admissions.single(), checkpoint.completions.single().admission)
        assertFailsWith<UnsupportedOperationException> { (checkpoint.admissions as MutableList<OrderAdmissionState.Record>).clear() }
        val restoredHistory = CheckpointHistory()
        val restored = state(restoredHistory)
        restoredHistory.load(history)

        restored.restoreCheckpoint(checkpoint)

        val again = restored.checkpoint()
        assertSame(again.admissions.single(), again.completions.single().admission)
    }

    private fun installedBalanceCount(state: TradingState): Int {
        val owner =
            TradingState::class.java
                .getDeclaredField("admission")
                .apply { isAccessible = true }
                .get(state)
        return (
            owner.javaClass
                .getDeclaredField("balances")
                .apply { isAccessible = true }
                .get(owner) as Map<*, *>
        ).size
    }

    private fun copy(
        checkpoint: TradingCheckpoint,
        balances: List<Balance> = checkpoint.balances,
        usedBalances: List<TradingBalanceKey> = checkpoint.usedBalances,
        reservations: List<OrderReservation> = checkpoint.reservations,
        pending: List<Long> = checkpoint.pendingAdmissionSequences,
        completions: List<TradingState.Completion> = checkpoint.completions,
        matching: MatchingCheckpoint = checkpoint.matching,
        lastSequence: Long = checkpoint.lastAdmissionSequence,
    ) = TradingCheckpoint(
        balances,
        usedBalances,
        checkpoint.admissions,
        reservations,
        pending,
        completions,
        lastSequence,
        checkpoint.historyOrdinal,
        matching,
    )
}

/** 외부에서 복사한 영속 세대를 모델링하고 전체 이력 순회를 금지하는 도메인 테스트다. */
private class CheckpointHistory : TradingHistoryStore {
    private val batches = mutableListOf<TradingHistoryBatch>()
    private val requests = linkedMapOf<Pair<UserId, String>, OrderAdmissionState.RequestRecord>()
    private val admissions = linkedMapOf<Long, OrderAdmissionState.Record>()
    private val orderIndex = hashMapOf<Pair<MarketId, OrderId>, Long>()
    private val completions = linkedMapOf<Long, TradingState.Completion>()
    private val preparations = linkedMapOf<String, OrderAdmissionState.PreparationRecord>()
    private val reservations = linkedMapOf<Pair<MarketId, OrderId>, OrderReservation>()
    private val transactions = linkedMapOf<String, LedgerTransaction>()
    private val sources = hashSetOf<String>()
    var forbidScans = false
    override var durableOrdinal = 0L
        private set

    override fun append(batch: TradingHistoryBatch) {
        check(batch.ordinal == batches.size.toLong() + 1)
        batches.add(batch)
        batch.request?.let { requests[it.order.userId to it.requestId] = it }
        batch.admission?.let {
            admissions[it.sequence] = copyAdmission(it)
            orderIndex[it.candidate.order.marketId to it.candidate.order.orderId] = it.sequence
        }
        batch.completion?.let { completions[it.admission.sequence] = it }
        batch.preparation?.let { preparations[it.result.opening.preparationId] = it }
        batch.reservations.forEach { reservations[it.marketId to it.orderId] = it }
        batch.ledgerTransactions.forEach {
            transactions[it.ledgerTransactionId] = it
            sources.add(it.sourceEventId)
        }
    }

    fun load(source: CheckpointHistory) {
        source.batches.forEach(::append)
        flush()
    }

    override fun flush(): Long = batches.size.toLong().also { durableOrdinal = it }

    override fun findRequest(
        userId: UserId,
        requestId: String,
    ) = requests[userId to requestId]

    override fun findAdmission(
        marketId: MarketId,
        orderId: OrderId,
    ) = orderIndex[marketId to orderId]?.let(::findAdmission)

    override fun findAdmission(sequence: Long) = admissions[sequence]?.let(::copyAdmission)

    override fun findCompletion(sequence: Long) =
        completions[sequence]?.let {
            TradingHistoryValues.completion(copyAdmission(it.admission), it.executionId, it.occurredAt, it.matchingResult, it.settlement)
        }

    override fun findPreparation(preparationId: String) = preparations[preparationId]

    override fun containsTransactionId(id: String) = id in transactions

    override fun containsSourceEventId(id: String) = id in sources

    override fun requests() = scan(requests.values)

    override fun admissions() = scan(admissions.values)

    override fun completions() = scan(completions.values)

    override fun preparations() = scan(preparations.values)

    override fun reservations() = scan(reservations.values)

    override fun transactions() = scan(transactions.values)

    private fun <T> scan(values: Collection<T>): Sequence<T> {
        check(!forbidScans) { "active checkpoint must not scan cold history" }
        return values.asSequence()
    }

    companion object {
        fun copyAdmission(record: OrderAdmissionState.Record): OrderAdmissionState.Record {
            val value = record.candidate
            return TradingHistoryValues.admissionRecord(
                record.sequence,
                TradingHistoryValues.admissionCandidate(
                    value.order,
                    value.market,
                    value.feePolicySnapshot,
                    value.previousBalance,
                    value.nextBalance,
                    value.reservation,
                    value.reserveTransaction,
                ),
            )
        }
    }
}
