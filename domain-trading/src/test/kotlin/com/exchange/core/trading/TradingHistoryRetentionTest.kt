package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.LedgerTransaction
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TradingHistoryRetentionTest {
    @Test
    fun `live owner prepares each reservation with its original admission sequence in execution order`() {
        val store = ControlledHistoryStore(autoAcknowledge = true)
        val state = state(store)
        fund(state, seller, btc, 2)
        fund(state, buyer, usdt, 200)
        assertIs<OrderAdmissionState.Result.Rejected>(
            state.admit("unfunded", order("U", UserId("unfunded"), Side.BUY, 100, 1), market, zeroPolicy, "unused", time),
        )
        val expensive = admit(state, order("expensive", seller, Side.SELL, 95, 1))
        state.executePending(expensive.record.sequence, time)
        val cheap = admit(state, order("cheap", seller, Side.SELL, 90, 1))
        state.executePending(cheap.record.sequence, time)
        assertEquals(0, state.historyRetention().stagedBatches)
        assertEquals(2, state.historyRetention().activeOrders)
        val taker = admit(state, order("taker", buyer, Side.BUY, 100, 2))
        val completion = state.executePending(taker.record.sequence, time)

        assertEquals(listOf(1L, 2L, 3L), listOf(expensive.record.sequence, cheap.record.sequence, taker.record.sequence))
        assertEquals(
            listOf(emptyList(), emptyList(), emptyList(), listOf(1L), emptyList(), listOf(2L), emptyList(), listOf(3L), listOf(2L, 3L, 1L)),
            store.preparedBatches.map { it.reservationSequences },
        )
        assertEquals((1L..9L).toList(), store.preparedBatches.map { it.batch.ordinal })
        for (prepared in store.preparedBatches) prepared.requireOwner(store)
        val settled = store.preparedBatches.last()
        assertSame(completion, settled.batch.completion)
        assertEquals(listOf(OrderId("cheap"), OrderId("taker"), OrderId("expensive")), settled.batch.reservations.map { it.orderId })
        assertEquals(listOf(OrderId("cheap"), OrderId("expensive")), completion.settlement.settlements.map { it.execution.makerOrderId })
        assertEquals(0, state.historyRetention().activeOrders)
        assertEquals(
            Amount(15),
            state
                .snapshot()
                .balances
                .single { it.userId == buyer && it.assetId == usdt }
                .available,
        )
        assertFalse(state.isBlocked)
    }

    @Test
    fun `prepared transition from another store is rejected before any history is appended`() {
        val owner = ControlledHistoryStore()
        fund(state(owner), buyer, usdt, 100)
        val prepared = owner.preparedBatches.single()
        val other = ControlledHistoryStore()

        assertFailsWith<IllegalArgumentException> { other.appendPrepared(prepared) }

        prepared.requireOwner(owner)
        assertEquals(1L, owner.lastOrdinal)
        assertEquals(0L, other.lastOrdinal)
        assertEquals(0L, other.durableOrdinal)
        assertTrue(other.preparedBatches.isEmpty())
        assertTrue(other.preparations().none())
        assertTrue(other.transactions().none())
    }

    @Test
    fun `prepared transition copies list inputs and prevents mutation through exposed aliases`() {
        val store = ControlledHistoryStore()
        val state = state(store)
        fund(state, seller, btc, 1)
        val admission = admit(state, order("S", seller, Side.SELL, 90, 1)).record
        val reservation = admission.candidate.reservation
        val transaction = admission.candidate.reserveTransaction
        val reservations = mutableListOf(reservation)
        val transactions = mutableListOf(transaction)
        val sequences = mutableListOf(admission.sequence)
        val prepared =
            PreparedTradingHistoryBatch(
                store,
                TradingHistoryBatch(1L, admission = admission, reservations = reservations, ledgerTransactions = transactions),
                sequences,
            )

        reservations.clear()
        transactions.clear()
        sequences[0] = 99L

        assertEquals(listOf(reservation), prepared.batch.reservations)
        assertEquals(listOf(transaction), prepared.batch.ledgerTransactions)
        assertEquals(listOf(admission.sequence), prepared.reservationSequences)
        assertFailsWith<UnsupportedOperationException> { (prepared.batch.reservations as MutableList<OrderReservation>).clear() }
        assertFailsWith<UnsupportedOperationException> { (prepared.batch.ledgerTransactions as MutableList<LedgerTransaction>).clear() }
        assertFailsWith<UnsupportedOperationException> { (prepared.reservationSequences as MutableList<Long>)[0] = 99L }
        assertEquals(listOf(reservation), prepared.batch.reservations)
        assertEquals(listOf(transaction), prepared.batch.ledgerTransactions)
        assertEquals(listOf(admission.sequence), prepared.reservationSequences)
    }

    @Test
    fun `context admission reads each fresh or cold request once and preserves its first result`() {
        for (funded in listOf(false, true)) {
            val store = ControlledHistoryStore(autoAcknowledge = true)
            val state = state(store)
            if (funded) fund(state, buyer, usdt, 1_000)
            val input = order("B", buyer, Side.BUY, 100, 1)
            val knownRequests = mutableListOf<Boolean>()
            val first =
                state.admitWithContextCheck("R", input, market, zeroPolicy, "reserve", time) { known ->
                    knownRequests += known
                    true
                }
            assertEquals(1, store.requestReadCount)
            assertEquals(0, state.historyRetention().retainedRequests)
            val ordinal = store.lastOrdinal
            val replay =
                state.admitWithContextCheck("R", input, market, zeroPolicy.copy(scheduleVersion = 2), "", time.plusSeconds(1)) { known ->
                    knownRequests += known
                    known
                }
            assertEquals(2, store.requestReadCount)
            assertEquals(listOf(false, true), knownRequests)
            assertEquals(ordinal, store.lastOrdinal)
            if (funded) {
                val original = assertIs<OrderAdmissionState.Result.Accepted>(first).record
                val restored = assertIs<OrderAdmissionState.Result.Accepted>(replay).record
                assertNotSame(original, restored)
                assertEquals(original.sequence, restored.sequence)
                assertEquals(original.candidate.order, restored.candidate.order)
                assertEquals(zeroPolicy, restored.candidate.feePolicySnapshot)
                assertEquals(original.candidate.reserveTransaction, restored.candidate.reserveTransaction)
            } else {
                assertEquals(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE, assertIs<OrderAdmissionState.Result.Rejected>(first).reason)
                assertEquals(first, replay)
            }
            assertEquals(
                OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT,
                assertIs<OrderAdmissionState.Result.Rejected>(
                    state.admitWithContextCheck("R", input.copy(quantity = Quantity(2)), market, zeroPolicy, "unused", time) { it },
                ).reason,
            )
            assertEquals(3, store.requestReadCount)
            assertEquals(ordinal, store.lastOrdinal)
            assertFalse(state.isBlocked)
        }
    }

    @Test
    fun `context refusal records nothing and identifier checks retain their precedence`() {
        val store = ControlledHistoryStore(autoAcknowledge = true)
        val state = state(store)
        fund(state, buyer, usdt, 1_000)
        val input = order("B", buyer, Side.BUY, 100, 1)
        val invalidOrder = input.copy(orderId = OrderId("x".repeat(65)))
        val ordinal = store.lastOrdinal
        assertNull(state.admitWithContextCheck("", invalidOrder, market, zeroPolicy, "reserve", time) { false })
        assertEquals(
            OrderAdmissionResult.Reason.INVALID_REQUEST_IDENTIFIER,
            assertIs<OrderAdmissionState.Result.Rejected>(
                state.admitWithContextCheck("", invalidOrder, market, zeroPolicy, "reserve", time) { true },
            ).reason,
        )
        assertEquals(
            OrderAdmissionResult.Reason.INVALID_ORDER_IDENTIFIER,
            assertIs<OrderAdmissionState.Result.Rejected>(
                state.admitWithContextCheck("R", invalidOrder, market, zeroPolicy, "reserve", time) { true },
            ).reason,
        )
        assertEquals(ordinal, store.lastOrdinal)
        assertEquals(0L, state.snapshot().lastAdmissionSequence)
        assertTrue(state.snapshot().requests.isEmpty())
        val first =
            assertIs<OrderAdmissionState.Result.Accepted>(
                state.admitWithContextCheck("R", input, market, zeroPolicy, "reserve", time) { true },
            )
        assertEquals(1L, first.record.sequence)
        assertEquals(
            OrderAdmissionResult.Reason.INVALID_ORDER_IDENTIFIER,
            assertIs<OrderAdmissionState.Result.Rejected>(
                state.admitWithContextCheck("R", invalidOrder, market, zeroPolicy, "unused", time) { it },
            ).reason,
        )
        assertEquals(
            OrderAdmissionResult.Reason.DUPLICATE_ORDER,
            assertIs<OrderAdmissionState.Result.Rejected>(
                state.admitWithContextCheck("other-request", input, market, zeroPolicy, "unused", time) { !it },
            ).reason,
        )
        assertEquals(6, store.requestReadCount)
        assertEquals(2, state.snapshot().requests.size)
        assertEquals(1L, state.snapshot().lastAdmissionSequence)
        store.readFailure = true
        assertEquals(
            OrderAdmissionResult.Reason.INVALID_REQUEST_IDENTIFIER,
            assertIs<OrderAdmissionState.Result.Rejected>(state.admit("", invalidOrder, market, zeroPolicy, "unused", time)).reason,
        )
        assertEquals(6, store.requestReadCount, "the original domain entry still validates identifiers before history access")
        assertFalse(state.isBlocked)
    }

    @Test
    fun `context callback cannot reenter or retain an unchecked absence after throwing`() {
        val store = ControlledHistoryStore(autoAcknowledge = true)
        val state = state(store)
        fund(state, buyer, usdt, 1_000)
        val input = order("B", buyer, Side.BUY, 100, 1)
        val before = state.snapshot()
        assertFailsWith<IllegalStateException> {
            state.admitWithContextCheck("R", input, market, zeroPolicy, "reserve", time) {
                state.admit("R", input, market, zeroPolicy, "nested-reserve", time)
                true
            }
        }
        assertEquals(1, store.requestReadCount, "reentry must fail before another lookup or mutation")
        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
        assertFailsWith<IllegalArgumentException> {
            state.admitWithContextCheck("R", input, market, zeroPolicy, "reserve", time) {
                throw IllegalArgumentException("context check failed")
            }
        }
        assertEquals(before, state.snapshot())
        val admitted =
            assertIs<OrderAdmissionState.Result.Accepted>(
                state.admitWithContextCheck("R", input, market, zeroPolicy, "reserve", time) { !it },
            )
        assertEquals(3, store.requestReadCount, "each new invocation must resolve the request again")
        assertEquals(1L, admitted.record.sequence)
        assertEquals(1, state.snapshot().requests.size)
        assertFalse(state.isBlocked)
    }

    @Test
    fun `context admission blocks on a cold lookup failure before calling external validation`() {
        val store = ControlledHistoryStore().apply { readFailure = true }
        val state = state(store)
        val input = order("B", buyer, Side.BUY, 100, 1)
        var contextChecks = 0
        val checkContext: (Boolean) -> Boolean = {
            contextChecks++
            true
        }
        assertFailsWith<HistoryFailure> {
            state.admitWithContextCheck("R", input, market, zeroPolicy, "reserve", time, checkContext)
        }
        assertTrue(state.isBlocked)
        assertEquals(0, contextChecks)
        assertEquals(1, store.requestReadCount)
        store.readFailure = false
        assertFailsWith<IllegalStateException> {
            state.admitWithContextCheck("R", input, market, zeroPolicy, "reserve", time, checkContext)
        }
        assertEquals(0, contextChecks)
        assertEquals(1, store.requestReadCount)
        assertEquals(0L, store.lastOrdinal)
    }

    @Test
    fun `full staging waits only for its oldest batch while explicit flush still acknowledges everything`() {
        val store = ControlledHistoryStore(acknowledgeRequestedOnly = true)
        val state = state(store, historyBatchLimit = 3)
        fund(state, seller, btc, 1)
        fund(state, buyer, usdt, 100)
        val sell = admit(state, order("S", seller, Side.SELL, 90, 1))
        assertEquals(3, state.historyRetention().stagedBatches)
        store.flushFailure = true

        state.executePending(sell.record.sequence, time)

        assertEquals(listOf(1L), store.awaitedOrdinals)
        assertEquals(0, store.flushCount)
        val retained = state.historyRetention()
        assertEquals(1L, retained.durableOrdinal)
        assertEquals(4L, retained.lastOrdinal)
        assertEquals(3, retained.stagedBatches)
        assertEquals(1, retained.retainedPreparations)
        assertEquals(1, retained.retainedRequests)
        assertEquals(1, retained.retainedCompletions)
        assertEquals(1, retained.activeOrders)

        store.flushFailure = false
        state.flushHistory()
        assertEquals(1, store.flushCount)
        assertEquals(4L, state.historyRetention().durableOrdinal)
        assertEquals(0, state.historyRetention().stagedBatches)
        assertEquals(0, state.historyRetention().retainedPreparations)
        assertEquals(0, state.historyRetention().retainedRequests)
        assertEquals(1, state.historyRetention().activeOrders)
        assertEquals(Quantity(1), state.book(market.marketId).single().remainingQuantity)
    }

    @Test
    fun `invalid or stalled target acknowledgements block before another transition is appended`() {
        for (reported in listOf(0L, 1L, 99L)) {
            val backend = ControlledHistoryStore()
            val store =
                object : TradingHistoryStore by backend {
                    override fun appendPrepared(prepared: PreparedTradingHistoryBatch) {
                        prepared.requireOwner(this)
                        backend.append(prepared.batch)
                    }

                    // 1 claims success without actually advancing the durable prefix.
                    override fun awaitDurable(ordinal: Long): Long = reported
                }
            val state = state(store, historyBatchLimit = 1)
            fund(state, seller, btc, 1)

            assertFailsWith<IllegalStateException> { fund(state, buyer, usdt, 100) }

            assertTrue(state.isBlocked)
            assertEquals(1L, backend.lastOrdinal)
            assertEquals(0L, backend.durableOrdinal)
            assertEquals(1, retainedCollectionSize(state, "admission", "historyBatches"))
            assertEquals(1, retainedMapSize(state, "admission", "preparations"))
        }
    }

    @Test
    fun `bounded staging releases all terminal histories while exact cold answers and ledger survive`() {
        val store = ControlledHistoryStore()
        val state = state(store, historyBatchLimit = 3)
        fund(state, seller, btc, 80)
        fund(state, buyer, usdt, 8_000)
        val firstInput = order("S0", seller, Side.SELL, 90, 1)
        var first: TradingState.Completion? = null
        repeat(80) { index ->
            val sell = admit(state, order("S$index", seller, Side.SELL, 90, 1))
            val completion = state.executePending(sell.record.sequence, time.plusSeconds(index.toLong()))
            if (index == 0) first = completion
            val buy = admit(state, order("B$index", buyer, Side.BUY, 100, 1))
            state.executePending(buy.record.sequence, time.plusSeconds(index.toLong() + 100))
            assertTrue(state.historyRetention().stagedBatches <= 3)
        }
        state.flushHistory()
        val stats = state.historyRetention()
        assertEquals(322L, stats.lastOrdinal)
        assertEquals(stats.lastOrdinal, stats.durableOrdinal)
        assertEquals(0, stats.stagedBatches)
        assertEquals(0, stats.retainedRequests)
        assertEquals(0, stats.retainedAdmissions)
        assertEquals(0, stats.retainedReservations)
        assertEquals(0, stats.retainedCompletions)
        assertEquals(0, stats.retainedTransactions)
        assertEquals(0, stats.retainedTransactionIds)
        assertEquals(0, stats.retainedSourceEventIds)
        assertEquals(0, stats.retainedPreparations)
        assertEquals(0, stats.pendingOrders)
        assertEquals(0, stats.activeOrders)
        assertTrue(store.flushCount > 1)
        assertEquals(0, retainedMapSize(state, "engine", "seenOrderIds"))
        assertEquals(0, retainedMapSize(state, "resultState", "records"))
        assertEquals(0, retainedCollectionSize(state, "resultState", "submittedOrders"))

        val snapshot = state.snapshot()
        assertEquals(160, snapshot.requests.size)
        assertEquals(160, snapshot.completions.size)
        assertEquals(160, snapshot.matchingResults.size)
        assertEquals(160, snapshot.reservations.size)
        assertEquals(242, snapshot.ledgerTransactions.size)
        assertEquals(2, snapshot.preparations.size)
        assertEquals(
            setOf(balance(seller, btc, 0), balance(seller, usdt, 7_200), balance(buyer, usdt, 800), balance(buyer, btc, 80)),
            snapshot.balances.toSet(),
        )
        val replay = state.executePending(1, time.plusSeconds(9_999))
        assertNotSame(first, replay)
        assertEquals(first!!.occurredAt, replay.occurredAt)
        assertEquals(first!!.matchingResult, replay.matchingResult)
        assertEquals(firstInput, replay.admission.candidate.order)
        val admitted =
            assertIs<OrderAdmissionState.Result.Accepted>(
                state.admit("request-S0", firstInput, market, zeroPolicy.copy(scheduleVersion = 2), "unused", time.plusSeconds(99)),
            )
        assertEquals(1L, admitted.record.sequence)
        assertEquals(zeroPolicy, admitted.record.candidate.feePolicySnapshot)
        assertEquals(time, admitted.record.candidate.reserveTransaction.occurredAt)
        assertEquals(
            OrderAdmissionResult.Reason.DUPLICATE_ORDER,
            assertIs<OrderAdmissionState.Result.Rejected>(
                state.admit("new-request", firstInput, market, zeroPolicy, "unused", time),
            ).reason,
        )
        val newOrder = order("new", buyer, Side.BUY, 1, 1)
        assertFailsWith<IllegalStateException> {
            state.admit("new-ledger-conflict", newOrder, market, zeroPolicy, "reserve-B0", time)
        }
        assertFalse(state.isBlocked)
        assertEquals(snapshot.balances, state.snapshot().balances)
    }

    @Test
    fun `old rejection retains its first shortage after new funding and conflicting retry`() {
        val store = ControlledHistoryStore(autoAcknowledge = true)
        val state = state(store, 2)
        val input = order("B", buyer, Side.BUY, 100, 1)
        val first = assertIs<OrderAdmissionState.Result.Rejected>(state.admit("R", input, market, zeroPolicy, "reserve", time))
        assertEquals(Amount.ZERO, first.insufficientBalance!!.available)
        assertEquals(1L, state.historyRetention().lastOrdinal)
        assertEquals(0L, state.snapshot().lastAdmissionSequence)
        assertEquals(0, state.historyRetention().retainedRequests)
        fund(state, buyer, usdt, 1_000)
        assertEquals(first, state.admit("R", input, market, zeroPolicy.copy(scheduleVersion = 2), "changed", time.plusSeconds(1)))
        assertEquals(
            OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT,
            assertIs<OrderAdmissionState.Result.Rejected>(
                state.admit("R", input.copy(quantity = Quantity(2)), market, zeroPolicy, "changed", time),
            ).reason,
        )
        assertEquals(2L, state.historyRetention().lastOrdinal)
        assertEquals(
            Amount(1_000),
            state
                .snapshot()
                .balances
                .single()
                .available,
        )
        assertEquals(1, state.snapshot().ledgerTransactions.size)
    }

    @Test
    fun `active canonical maker survives old history eviction and terminal eviction waits for its own acknowledgement`() {
        val store = ControlledHistoryStore(autoAcknowledge = true)
        val state = state(store, 8)
        fund(state, seller, btc, 3)
        fund(state, buyer, usdt, 1_000)
        val sell = admit(state, order("S", seller, Side.SELL, 90, 3))
        val original = state.executePending(sell.record.sequence, time)
        assertSame(original, state.executePending(sell.record.sequence, time.plusSeconds(10)))
        val firstBuy = admit(state, order("B1", buyer, Side.BUY, 100, 1))
        state.executePending(firstBuy.record.sequence, time.plusSeconds(1))
        assertSame(original, state.executePending(sell.record.sequence, time.plusSeconds(20)))
        assertEquals(1, state.historyRetention().retainedAdmissions)
        assertEquals(1, state.historyRetention().retainedCompletions)
        assertEquals(Quantity(2), state.book(market.marketId).single().remainingQuantity)

        store.autoAcknowledge = false
        val lastBuy = admit(state, order("B2", buyer, Side.BUY, 100, 2))
        store.acknowledge(store.lastOrdinal)
        state.executePending(lastBuy.record.sequence, time.plusSeconds(2))
        assertEquals(2, state.historyRetention().retainedAdmissions)
        assertEquals(2, state.historyRetention().retainedCompletions)
        assertSame(original, state.executePending(sell.record.sequence, time.plusSeconds(30)))
        state.flushHistory()
        assertEquals(0, state.historyRetention().retainedAdmissions)
        assertEquals(0, state.historyRetention().retainedCompletions)
        val replay = state.executePending(sell.record.sequence, time.plusSeconds(40))
        assertNotSame(original, replay)
        assertEquals(original.matchingResult, replay.matchingResult)
        assertEquals(Quantity(3), replay.admission.candidate.reservation.remainingQuantity)
    }

    @Test
    fun `cold failures block the common owner instead of treating old history as absent`() {
        val store = ControlledHistoryStore(autoAcknowledge = true)
        val state = state(store)
        val input = order("B", buyer, Side.BUY, 100, 1)
        state.admit("R", input, market, zeroPolicy, "reserve", time)
        store.readFailure = true
        assertFailsWith<HistoryFailure> { state.hasRequest(buyer, "R") }
        assertTrue(state.isBlocked)
        assertFailsWith<IllegalStateException> { state.snapshot() }
    }

    @Test
    fun `append failure and stalled flush stop further mutation without unbounded staging`() {
        val failedAppend = ControlledHistoryStore().apply { appendFailure = true }
        val first = state(failedAppend, 1)
        assertFailsWith<HistoryFailure> {
            first.admit("R", order("B", buyer, Side.BUY, 100, 1), market, zeroPolicy, "reserve", time)
        }
        assertTrue(first.isBlocked)

        val failedFlush = ControlledHistoryStore()
        val second = state(failedFlush, 1)
        fund(second, seller, btc, 1)
        failedFlush.flushFailure = true
        assertFailsWith<HistoryFailure> { fund(second, buyer, usdt, 100) }
        assertTrue(second.isBlocked)
        assertEquals(1L, failedFlush.lastOrdinal)
        assertEquals(1, failedFlush.preparations().count())
    }

    @Test
    fun `invalid durability acknowledgement blocks eviction`() {
        val store = ControlledHistoryStore()
        val state = state(store, 2)
        fund(state, seller, btc, 1)
        store.acknowledge(99)
        assertFailsWith<IllegalStateException> { fund(state, buyer, usdt, 100) }
        assertTrue(state.isBlocked)
        assertEquals(1L, store.lastOrdinal)
    }

    private fun retainedMapSize(
        state: TradingState,
        owner: String,
        field: String,
    ): Int = (nestedField(state, owner, field) as Map<*, *>).size

    private fun retainedCollectionSize(
        state: TradingState,
        owner: String,
        field: String,
    ): Int = (nestedField(state, owner, field) as Collection<*>).size

    private fun nestedField(
        state: TradingState,
        owner: String,
        field: String,
    ): Any {
        val nested =
            state.javaClass
                .getDeclaredField(owner)
                .apply { isAccessible = true }
                .get(state)
        return nested.javaClass
            .getDeclaredField(field)
            .apply { isAccessible = true }
            .get(nested)
    }
}

private class HistoryFailure : RuntimeException()

/** Models a lossless external history with independently controlled durability and fresh read objects. */
private class ControlledHistoryStore(
    var autoAcknowledge: Boolean = false,
    private val acknowledgeRequestedOnly: Boolean = false,
) : TradingHistoryStore {
    override var durableOrdinal = 0L
        private set
    var lastOrdinal = 0L
        private set
    var flushCount = 0
        private set
    var requestReadCount = 0
        private set
    var readFailure = false
    var appendFailure = false
    var flushFailure = false
    val awaitedOrdinals = mutableListOf<Long>()
    val preparedBatches = mutableListOf<PreparedTradingHistoryBatch>()
    private val requestValues = linkedMapOf<Pair<UserId, String>, OrderAdmissionState.RequestRecord>()
    private val admissionValues = linkedMapOf<Long, OrderAdmissionState.Record>()
    private val orderValues = linkedMapOf<Pair<MarketId, OrderId>, OrderAdmissionState.Record>()
    private val completionValues = linkedMapOf<Long, TradingState.Completion>()
    private val preparationValues = linkedMapOf<String, OrderAdmissionState.PreparationRecord>()
    private val reservationValues = linkedMapOf<Pair<MarketId, OrderId>, OrderReservation>()
    private val transactionValues = linkedMapOf<String, LedgerTransaction>()
    private val sourceValues = mutableSetOf<String>()

    override fun appendPrepared(prepared: PreparedTradingHistoryBatch) {
        super<TradingHistoryStore>.appendPrepared(prepared)
        preparedBatches += prepared
    }

    override fun append(batch: TradingHistoryBatch) {
        if (appendFailure) throw HistoryFailure()
        check(batch.ordinal == lastOrdinal + 1)
        batch.request?.let { requestValues[it.order.userId to it.requestId] = it }
        batch.admission?.let {
            admissionValues[it.sequence] = it
            orderValues[it.candidate.order.marketId to it.candidate.order.orderId] = it
        }
        batch.completion?.let { completionValues[it.admission.sequence] = it }
        batch.preparation?.let { preparationValues[it.result.opening.preparationId] = it }
        batch.reservations.forEach { reservationValues[it.marketId to it.orderId] = it }
        batch.ledgerTransactions.forEach {
            transactionValues[it.ledgerTransactionId] = it
            sourceValues += it.sourceEventId
        }
        lastOrdinal = batch.ordinal
        if (autoAcknowledge) durableOrdinal = lastOrdinal
    }

    fun acknowledge(ordinal: Long) {
        durableOrdinal = ordinal
    }

    override fun flush(): Long {
        if (flushFailure) throw HistoryFailure()
        flushCount++
        durableOrdinal = lastOrdinal
        return durableOrdinal
    }

    override fun awaitDurable(ordinal: Long): Long {
        awaitedOrdinals.add(ordinal)
        if (!acknowledgeRequestedOnly) return super<TradingHistoryStore>.awaitDurable(ordinal)
        require(ordinal in 0..lastOrdinal)
        durableOrdinal = maxOf(durableOrdinal, ordinal)
        return durableOrdinal
    }

    private fun checkReadable() {
        if (readFailure) throw HistoryFailure()
    }

    override fun findRequest(
        userId: UserId,
        requestId: String,
    ): OrderAdmissionState.RequestRecord? {
        requestReadCount++
        checkReadable()
        return requestValues[userId to requestId]?.let { original ->
            val result = original.result
            TradingHistoryValues.requestRecord(
                original.requestId,
                original.order,
                original.market,
                original.feePolicySnapshot,
                original.ledgerTransactionId,
                original.occurredAt,
                if (result is OrderAdmissionState.Result.Accepted) {
                    OrderAdmissionState.Result.Accepted(
                        copyRecord(result.record),
                    )
                } else {
                    result
                },
            )
        }
    }

    override fun findAdmission(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderAdmissionState.Record? {
        checkReadable()
        return orderValues[marketId to orderId]?.let(::copyRecord)
    }

    override fun findAdmission(sequence: Long): OrderAdmissionState.Record? {
        checkReadable()
        return admissionValues[sequence]?.let(::copyRecord)
    }

    override fun findCompletion(sequence: Long): TradingState.Completion? {
        checkReadable()
        return completionValues[sequence]?.let {
            TradingHistoryValues.completion(copyRecord(it.admission), it.executionId, it.occurredAt, it.matchingResult, it.settlement)
        }
    }

    override fun findPreparation(preparationId: String): OrderAdmissionState.PreparationRecord? {
        checkReadable()
        return preparationValues[preparationId]
    }

    override fun containsTransactionId(id: String): Boolean {
        checkReadable()
        return id in transactionValues
    }

    override fun containsSourceEventId(id: String): Boolean {
        checkReadable()
        return id in sourceValues
    }

    override fun requests(): Sequence<OrderAdmissionState.RequestRecord> {
        checkReadable()
        return requestValues.values.asSequence()
    }

    override fun admissions(): Sequence<OrderAdmissionState.Record> {
        checkReadable()
        return admissionValues.values.asSequence()
    }

    override fun completions(): Sequence<TradingState.Completion> {
        checkReadable()
        return completionValues.values.asSequence()
    }

    override fun preparations(): Sequence<OrderAdmissionState.PreparationRecord> {
        checkReadable()
        return preparationValues.values.asSequence()
    }

    override fun reservations(): Sequence<OrderReservation> {
        checkReadable()
        return reservationValues.values.asSequence()
    }

    override fun transactions(): Sequence<LedgerTransaction> {
        checkReadable()
        return transactionValues.values.asSequence()
    }

    private fun copyRecord(record: OrderAdmissionState.Record): OrderAdmissionState.Record {
        val candidate = record.candidate
        return TradingHistoryValues.admissionRecord(
            record.sequence,
            TradingHistoryValues.admissionCandidate(
                candidate.order,
                candidate.market,
                candidate.feePolicySnapshot,
                candidate.previousBalance,
                candidate.nextBalance,
                candidate.reservation,
                candidate.reserveTransaction,
            ),
        )
    }
}
