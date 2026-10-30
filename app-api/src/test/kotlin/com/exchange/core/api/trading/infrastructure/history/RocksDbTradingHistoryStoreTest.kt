package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.Side
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.PreparedTradingHistoryBatch
import com.exchange.core.trading.TradingHistoryBatch
import com.exchange.core.trading.TradingHistoryStore
import com.exchange.core.trading.TradingHistoryValues
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rocksdb.FlushOptions
import org.rocksdb.RocksDB
import org.rocksdb.WriteOptions
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RocksDbTradingHistoryStoreTest {
    @TempDir
    lateinit var directory: Path

    private val limits =
        TradingHistoryStorageLimits(
            cacheBytes = 2L * 1_024 * 1_024,
            writeBufferBytes = 256L * 1_024,
            maximumPendingBytes = 512L * 1_024,
            maximumPendingBatches = 4,
            maximumValueBytes = 256 * 1_024,
            maximumGroupBatches = 4,
            targetGroupBytes = 64L * 1_024,
            maximumOpenFiles = 16,
        )
    private val codec = TradingHistoryCodec(limits.maximumValueBytes)

    @Test
    fun `encoded staging groups every row once and releases all queued and pending bytes`() {
        val fixture = TradingHistoryFixture()
        val requests = (0 until 4).map { rejected(fixture, "user", "group-$it") }
        // Exact existing row footprint: numeric request key + frame, request index + pointer,
        // and per-transition metadata. The writer coalesces only metadata within its group.
        val wireBytes =
            requests.sumOf { request ->
                val indexBytes = 1L + 2L * Int.SIZE_BYTES + (request.order.userId.value.length + request.requestId.length) * 2L
                9L + codec.encodeRequest(request).size + indexBytes + 9L + 1L + Long.SIZE_BYTES + 3L * 128L
            }
        val groupedLimits = limits.copy(targetGroupBytes = wireBytes)
        RocksDbTradingHistoryStore.createRebuildableIndex(directory.resolve("wire-groups"), groupedLimits).use { store ->
            synchronized(privateField<Any>(store, "monitor")) {
                requests.forEachIndexed { index, request -> store.append(TradingHistoryBatch(index + 1L, request = request)) }
                assertEquals(wireBytes, privateField<Long>(store, "queuedEncodedBytes"))
                assertEquals(wireBytes, store.retainedPendingBytes)
                assertTrue(store.retainedPendingBytes <= groupedLimits.maximumPendingBytes)
                assertEquals(4, store.pendingBatchCount)
                assertEquals(0L, store.durableOrdinal)
            }
            assertEquals(4L, store.flush())
            assertEquals(0L, privateField<Long>(store, "queuedEncodedBytes"))
            assertEquals(0L, store.retainedPendingBytes)
            assertEquals(0, store.pendingBatchCount)
            val database = privateField<RocksDB>(store, "database")
            // No memtable flush occurs for these tiny rows. Two rows per request plus one
            // coalesced metadata row proves they were materialized by one native WriteBatch.
            assertEquals(9L, database.getLongProperty("rocksdb.num-entries-active-mem-table"))
            requests.forEach { expected ->
                assertContentEquals(
                    codec.encodeRequest(expected),
                    codec.encodeRequest(assertNotNull(store.findRequest(expected.order.userId, expected.requestId))),
                )
            }
        }
    }

    @Test
    fun `single transition groups retain reservation writes superseded only by later queued groups`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val singleLimits =
            limits.copy(
                maximumPendingBatches = 64,
                maximumGroupBatches = 1,
                targetGroupBytes = limits.maximumPendingBytes,
            )
        RocksDbTradingHistoryStore.createRebuildableIndex(directory.resolve("reservation-group-boundary"), singleLimits).use { store ->
            val enqueuedPutCount =
                synchronized(privateField<Any>(store, "monitor")) {
                    fixture.batches.forEach(store::append)
                    assertEquals(7, store.pendingBatchCount)
                    assertEquals(0L, store.durableOrdinal)
                    privateField<Collection<Any>>(store, "queue").sumOf { pending ->
                        privateField<List<Any>>(pending, "puts").size.toLong()
                    }
                }
            assertEquals(44L, enqueuedPutCount)
            assertEquals(7L, store.flush())
            val database = privateField<RocksDB>(store, "database")
            // Every reservation is the last value in its own group, even when pendingValues
            // already contains a later group's value for the same key. Each group keeps its metadata too.
            val nativeEntries = database.getLongProperty("rocksdb.num-entries-active-mem-table")
            assertEquals(enqueuedPutCount, nativeEntries)
            assertEquals(7L, ByteBuffer.wrap(assertNotNull(database.get(byteArrayOf(20)))).long)
            assertEquals(0, store.pendingBatchCount)
            assertEquals(0L, store.retainedPendingBytes)
            assertHistoryMatches(fixture, store)
        }
    }

    @Test
    fun `partial maker fills restore latest reservations and every original completion from snapshot`() {
        val fixture = TradingHistoryFixture()
        fixture.funding("seller", "BTC", 10)
        fixture.funding("buyer", "KRW", 100_000)
        val maker = (fixture.admit("maker", "seller", Side.SELL, 3) as OrderAdmissionState.Result.Accepted).record
        fixture.execute(maker)
        val first = (fixture.admit("first", "buyer", Side.BUY, 1) as OrderAdmissionState.Result.Accepted).record
        fixture.execute(first)
        val partialMaker =
            fixture.state
                .snapshot()
                .reservations
                .single { it.orderId == maker.candidate.order.orderId }
        assertEquals(2L, partialMaker.remainingQuantity.value)
        assertEquals(OrderReservationStatus.ACTIVE, partialMaker.status)
        val second = (fixture.admit("second", "buyer", Side.BUY, 2) as OrderAdmissionState.Result.Accepted).record
        fixture.execute(second)
        val finalMaker =
            fixture.state
                .snapshot()
                .reservations
                .single { it.orderId == maker.candidate.order.orderId }
        assertEquals(0L, finalMaker.remainingQuantity.value)
        assertEquals(OrderReservationStatus.SETTLED, finalMaker.status)
        val groupedLimits =
            limits.copy(
                maximumPendingBatches = 64,
                maximumGroupBatches = 64,
                targetGroupBytes = limits.maximumPendingBytes,
            )
        RocksDbTradingHistoryStore.createRebuildableIndex(directory.resolve("partial-reservation-source"), groupedLimits).use { source ->
            synchronized(privateField<Any>(source, "monitor")) {
                fixture.batches.forEach(source::append)
                assertEquals(fixture.batches.size, source.pendingBatchCount)
                assertEquals(0L, source.durableOrdinal)
                assertTrue(privateField<Long>(source, "queuedEncodedBytes") <= groupedLimits.targetGroupBytes)
            }
            assertEquals(fixture.batches.last().ordinal, source.flush())
            assertHistoryMatches(fixture, source)
            val restoredDirectory = directory.resolve("partial-reservation-restored")
            RocksDbTradingHistoryStore.createRebuildableIndex(restoredDirectory, groupedLimits).use { target ->
                target.beginSnapshotRestore()
                val ordinal = source.exportSnapshot(target::restoreSnapshotEntry)
                target.completeSnapshotRestore(ordinal)
                assertEquals(fixture.batches.last().ordinal, ordinal)
                assertEquals(ordinal, target.durableOrdinal)
                assertHistoryMatches(fixture, target)
                val restoredMaker = target.reservations().single { it.orderId == maker.candidate.order.orderId }
                assertEquals(0L, restoredMaker.remainingQuantity.value)
                assertEquals(OrderReservationStatus.SETTLED, restoredMaker.status)
            }
        }
    }

    @Test
    fun `owner prepared derived transitions settle with disabled admission caches and preserve every cold result`() {
        val noIndexes = limits.copy(maximumCachedAdmissionIndexes = 0, maximumCachedAdmissionIndexBytes = 0)
        RocksDbTradingHistoryStore.createRebuildableIndex(directory.resolve("owner-references"), noIndexes).use { store ->
            val fixture = TradingHistoryFixture(store)
            fixture.funding("seller", "BTC", 1_000)
            fixture.funding("buyer", "KRW", 10_000_000)
            repeat(20) { index ->
                val maker = (fixture.admit("sell-$index", "seller", Side.SELL) as OrderAdmissionState.Result.Accepted).record
                fixture.execute(maker)
                val taker = (fixture.admit("buy-$index", "buyer", Side.BUY) as OrderAdmissionState.Result.Accepted).record
                fixture.execute(taker)
            }
            assertEquals(fixture.batches.last().ordinal, store.flush())
            assertEquals(0, store.cachedAdmissionIndexCount)
            assertEquals(0, store.cachedAdmissionIdentityCount)
            assertEquals(40, store.requests().count())
            assertEquals(40, store.completions().count())
            assertEquals(62, store.transactions().count())
            val expectedBalances =
                setOf(
                    Balance(UserId("seller"), AssetId("BTC"), Amount(960), Amount.ZERO),
                    Balance(UserId("seller"), AssetId("KRW"), Amount(39_960), Amount.ZERO),
                    Balance(UserId("buyer"), AssetId("BTC"), Amount(40), Amount.ZERO),
                    Balance(UserId("buyer"), AssetId("KRW"), Amount(9_959_920), Amount.ZERO),
                )
            assertEquals(
                expectedBalances,
                fixture.state
                    .snapshot()
                    .balances
                    .toSet(),
            )
            for (batch in fixture.batches) {
                batch.request?.let {
                    assertContentEquals(
                        codec.encodeRequest(it),
                        codec.encodeRequest(assertNotNull(store.findRequest(it.order.userId, it.requestId))),
                    )
                }
                batch.completion?.let {
                    assertContentEquals(
                        codec.encodeCompletion(it),
                        codec.encodeCompletion(assertNotNull(store.findCompletion(it.admission.sequence))),
                    )
                }
            }
        }
    }

    @Test
    fun `prepared transition issued to another store is rejected before any publication`() {
        RocksDbTradingHistoryStore.create(directory.resolve("proof-source"), limits).use { source ->
            var captured: PreparedTradingHistoryBatch? = null
            val ownerStore =
                object : TradingHistoryStore by source {
                    override fun appendPrepared(prepared: PreparedTradingHistoryBatch) {
                        prepared.requireOwner(this)
                        captured = prepared
                        source.append(prepared.batch)
                    }
                }
            TradingHistoryFixture(ownerStore).funding("seller", "BTC", 100)
            val target = RocksDbTradingHistoryStore.createRebuildableIndex(directory.resolve("proof-target"), limits)
            assertFailsWith<IllegalArgumentException> { target.appendPrepared(assertNotNull(captured)) }
            assertEquals(0, target.pendingBatchCount)
            assertNull(privateField<RocksDB>(target, "database").get(byteArrayOf(20)))
            assertFailsWith<IllegalStateException> { target.close() }
            assertEquals(1L, source.flush())
            assertNotNull(source.findPreparation("fund-seller-BTC"))
        }
    }

    @Test
    fun `derived admission and full accepted request reject copied nonpositive sequences before publication`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val original = fixture.batches.mapNotNull { it.admission }.first()
        val invalid = original.copy(sequence = 0)
        val request = fixture.batches.mapNotNull { it.request }.first()
        for (requestOnly in listOf(false, true)) {
            val store = RocksDbTradingHistoryStore.createRebuildableIndex(directory.resolve("invalid-sequence-$requestOnly"), limits)
            val batch =
                if (requestOnly) {
                    TradingHistoryBatch(
                        1,
                        request =
                            TradingHistoryValues.requestRecord(
                                request.requestId,
                                request.order,
                                request.market,
                                request.feePolicySnapshot,
                                request.ledgerTransactionId,
                                request.occurredAt,
                                OrderAdmissionState.Result.Accepted(invalid),
                            ),
                    )
                } else {
                    TradingHistoryBatch(1, admission = invalid)
                }
            assertFailsWith<IllegalStateException> { store.append(batch) }
            val database = privateField<RocksDB>(store, "database")
            assertNull(database.get(dataKey(2, 0)))
            assertNull(database.get(dataKey(1, 1)))
            assertNull(database.get(byteArrayOf(20)))
            assertEquals(0, store.pendingBatchCount)
            assertFailsWith<IllegalStateException> { store.close() }
        }
    }

    @Test
    fun `staged frames expose every original before writer progress and ignore mutable batch list aliases`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val derivedLimits = limits.copy(maximumPendingBatches = 64, maximumGroupBatches = 64)
        RocksDbTradingHistoryStore.createRebuildableIndex(directory.resolve("typed-overlay"), derivedLimits).use { store ->
            val database = privateField<RocksDB>(store, "database")
            // Owning the existing queue monitor prevents the real writer from claiming a group.
            // No production delay hook is needed to exercise the entire read-your-writes interval.
            synchronized(privateField<Any>(store, "monitor")) {
                for (batch in fixture.batches) {
                    val reservations = batch.reservations.toMutableList()
                    val transactions = batch.ledgerTransactions.toMutableList()
                    store.append(batch.copy(reservations = reservations, ledgerTransactions = transactions))
                    reservations.clear()
                    transactions.clear()
                    batch.request?.let {
                        assertContentEquals(
                            codec.encodeRequest(it),
                            codec.encodeRequest(assertNotNull(store.findRequest(it.order.userId, it.requestId))),
                        )
                    }
                    batch.admission?.let {
                        assertContentEquals(
                            codec.encodeAdmission(it),
                            codec.encodeAdmission(assertNotNull(store.findAdmission(it.sequence))),
                        )
                        assertContentEquals(
                            codec.encodeAdmission(it),
                            codec.encodeAdmission(
                                assertNotNull(store.findAdmission(it.candidate.order.marketId, it.candidate.order.orderId)),
                            ),
                        )
                    }
                    batch.completion?.let {
                        assertContentEquals(
                            codec.encodeCompletion(it),
                            codec.encodeCompletion(assertNotNull(store.findCompletion(it.admission.sequence))),
                        )
                    }
                    batch.preparation?.let {
                        assertContentEquals(
                            codec.encodePreparation(it),
                            codec.encodePreparation(assertNotNull(store.findPreparation(it.result.opening.preparationId))),
                        )
                    }
                    for (transaction in batch.ledgerTransactions) {
                        assertTrue(store.containsTransactionId(transaction.ledgerTransactionId))
                        assertTrue(store.containsSourceEventId(transaction.sourceEventId))
                    }
                }
                assertEquals(0L, store.durableOrdinal)
                assertNull(database.get(byteArrayOf(20)))
                val pendingRows = privateField<Map<Any, Any>>(store, "pendingValues").values
                assertTrue(pendingRows.isNotEmpty())
                for (row in pendingRows) assertTrue(privateField<ByteArray>(row, "value").isNotEmpty())
                assertEquals(fixture.batches.size, store.pendingBatchCount)
                assertTrue(store.retainedPendingBytes <= derivedLimits.maximumPendingBytes)
            }
            assertEquals(fixture.batches.last().ordinal, store.flush())
            assertEquals(0L, store.retainedPendingBytes)
            assertEquals(fixture.state.snapshot().reservations, store.reservations().toList())
            val expectedTransactions = fixture.batches.flatMap { it.ledgerTransactions }.map(codec::encodeTransaction)
            val actualTransactions = store.transactions().map(codec::encodeTransaction).toList()
            assertEquals(expectedTransactions.size, actualTransactions.size)
            expectedTransactions.zip(actualTransactions).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
            for (batch in fixture.batches) {
                batch.request?.let {
                    assertContentEquals(
                        codec.encodeRequest(it),
                        codec.encodeRequest(assertNotNull(store.findRequest(it.order.userId, it.requestId))),
                    )
                }
            }
        }
    }

    @Test
    fun `derived encoding rejects changed originals and oversize values before atomic publication`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val different =
            TradingHistoryFixture().apply {
                funding("seller", "BTC", 200)
                admit("sell", "seller", Side.SELL)
            }
        val store = RocksDbTradingHistoryStore.createRebuildableIndex(directory.resolve("typed-original"), limits)
        fixture.batches.forEach(store::append)
        val last = store.flush()
        val database = privateField<RocksDB>(store, "database")
        assertFailsWith<IllegalStateException> {
            store.append(
                TradingHistoryBatch(
                    last + 1,
                    request = different.batches.mapNotNull { it.request }.single(),
                    admission = different.batches.mapNotNull { it.admission }.single(),
                ),
            )
        }
        assertNull(database.get(dataKey(1, last + 1)))
        assertEquals(last, ByteBuffer.wrap(assertNotNull(database.get(byteArrayOf(20)))).long)
        assertFailsWith<IllegalStateException> { store.close() }

        val bounded =
            RocksDbTradingHistoryStore.createRebuildableIndex(
                directory.resolve("typed-size"),
                limits.copy(maximumValueBytes = 1_024),
            )
        val oversized = rejected(TradingHistoryFixture(), "user", "x".repeat(1_024))
        assertFailsWith<IllegalStateException> { bounded.append(TradingHistoryBatch(1, request = oversized)) }
        assertNull(privateField<RocksDB>(bounded, "database").get(dataKey(1, 1)))
        assertFailsWith<IllegalStateException> { bounded.close() }
    }

    @Test
    fun `canonical references preserve exact values with disabled or cleared bounded identity caches`() {
        for (cacheLimit in listOf(0, 1)) {
            val fixture = TradingHistoryFixture().apply { completedTrade() }
            RocksDbTradingHistoryStore
                .create(directory.resolve("references-$cacheLimit"), limits.copy(maximumCachedAdmissionIndexes = cacheLimit))
                .use { store ->
                    for (batch in fixture.batches) {
                        // Deterministically model cleared weak references, without relying on GC timing.
                        privateField<Map<Long, WeakReference<*>>>(store, "admissionIdentities").values.forEach { it.clear() }
                        store.append(batch)
                        assertTrue(store.cachedAdmissionIdentityCount <= cacheLimit)
                        batch.request?.let { expected ->
                            assertContentEquals(
                                codec.encodeRequest(expected),
                                codec.encodeRequest(assertNotNull(store.findRequest(expected.order.userId, expected.requestId))),
                            )
                        }
                        batch.completion?.let { expected ->
                            assertContentEquals(
                                codec.encodeCompletion(expected),
                                codec.encodeCompletion(assertNotNull(store.findCompletion(expected.admission.sequence))),
                            )
                        }
                    }
                    assertEquals(fixture.batches.last().ordinal, store.flush())
                    val db = privateField<RocksDB>(store, "database")
                    for (batch in fixture.batches) {
                        batch.request?.let {
                            val expectedKind = if (it.result is OrderAdmissionState.Result.Accepted) 7 else 1
                            assertEquals(expectedKind, ByteBuffer.wrap(assertNotNull(db.get(dataKey(1, batch.ordinal)))).getInt(8))
                        }
                        batch.completion?.let {
                            assertEquals(8, ByteBuffer.wrap(assertNotNull(db.get(dataKey(3, batch.ordinal)))).getInt(8))
                        }
                    }
                    val expectedRequests = fixture.batches.mapNotNull { it.request }.map(codec::encodeRequest)
                    val actualRequests = store.requests().map(codec::encodeRequest).toList()
                    assertEquals(expectedRequests.size, actualRequests.size)
                    expectedRequests.zip(actualRequests).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
                    val expectedCompletions = fixture.batches.mapNotNull { it.completion }.map(codec::encodeCompletion)
                    val actualCompletions = store.completions().map(codec::encodeCompletion).toList()
                    assertEquals(expectedCompletions.size, actualCompletions.size)
                    expectedCompletions.zip(actualCompletions).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
                }
        }
    }

    @Test
    fun `standalone and noncanonical originals retain full frames without changing their values`() {
        val original = TradingHistoryFixture().apply { completedTrade() }
        val different =
            TradingHistoryFixture().apply {
                funding("seller", "BTC", 200)
                execute((admit("sell", "seller", Side.SELL) as OrderAdmissionState.Result.Accepted).record)
            }
        val request = different.batches.mapNotNull { it.request }.single()
        val completion = different.batches.mapNotNull { it.completion }.single()
        for (withCanonical in listOf(false, true)) {
            RocksDbTradingHistoryStore.create(directory.resolve("fallback-$withCanonical"), limits).use { store ->
                if (withCanonical) original.batches.forEach(store::append)
                var ordinal = if (withCanonical) original.batches.last().ordinal else 0L
                val requestOrdinal = ++ordinal
                store.append(
                    TradingHistoryBatch(
                        requestOrdinal,
                        request = request,
                        admission = if (withCanonical) original.batches.mapNotNull { it.admission }.last() else null,
                    ),
                )
                val completionOrdinal = ++ordinal
                store.append(TradingHistoryBatch(completionOrdinal, completion = completion))
                if (withCanonical) {
                    // An identical canonical admission may be registered again, even as a fresh object.
                    val record = original.batches.mapNotNull { it.admission }.first()
                    store.append(TradingHistoryBatch(++ordinal, admission = codec.decodeAdmission(codec.encodeAdmission(record))))
                }
                assertEquals(ordinal, store.flush())
                val db = privateField<RocksDB>(store, "database")
                assertContentEquals(codec.encodeRequest(request), db.get(dataKey(1, requestOrdinal)))
                assertContentEquals(codec.encodeCompletion(completion), db.get(dataKey(3, completionOrdinal)))
                assertContentEquals(
                    codec.encodeRequest(request),
                    codec.encodeRequest(assertNotNull(store.findRequest(request.order.userId, request.requestId))),
                )
                assertContentEquals(
                    codec.encodeCompletion(completion),
                    codec.encodeCompletion(assertNotNull(store.findCompletion(completion.admission.sequence))),
                )
            }
        }
    }

    @Test
    fun `changing a canonical admission fails before publishing any part of the transition`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val original = fixture.batches.mapNotNull { it.admission }.first()
        val different =
            TradingHistoryFixture().apply {
                funding("seller", "BTC", 200)
                admit("sell", "seller", Side.SELL)
            }
        val changed = different.batches.mapNotNull { it.admission }.single()
        val store = RocksDbTradingHistoryStore.create(directory.resolve("immutable"), limits)
        fixture.batches.forEach(store::append)
        val last = store.flush()
        val db = privateField<RocksDB>(store, "database")
        assertFailsWith<IllegalStateException> {
            store.append(TradingHistoryBatch(last + 1, request = different.batches.mapNotNull { it.request }.single(), admission = changed))
        }
        assertContentEquals(codec.encodeAdmission(original), db.get(dataKey(2, original.sequence)))
        assertNull(db.get(dataKey(1, last + 1)))
        assertEquals(last, ByteBuffer.wrap(assertNotNull(db.get(byteArrayOf(20)))).long)
        assertFailsWith<IllegalStateException> { store.findAdmission(original.sequence) }
        assertFailsWith<IllegalStateException> { store.close() }
    }

    @Test
    fun `missing referenced admissions and corrupt identity cache fallbacks fail instead of replacing originals`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val original = fixture.batches.mapNotNull { it.admission }.first()
        val completion = fixture.batches.mapNotNull { it.completion }.first()
        val missing = RocksDbTradingHistoryStore.create(directory.resolve("missing-reference"), limits)
        fixture.batches.forEach(missing::append)
        missing.flush()
        privateField<RocksDB>(missing, "database").delete(dataKey(2, original.sequence))
        assertFailsWith<IllegalStateException> { missing.findCompletion(original.sequence) }
        assertFailsWith<IllegalStateException> { missing.close() }

        val corrupt =
            RocksDbTradingHistoryStore.create(
                directory.resolve("corrupt-fallback"),
                limits.copy(maximumCachedAdmissionIndexes = 0),
            )
        corrupt.append(TradingHistoryBatch(1, admission = original))
        corrupt.flush()
        val db = privateField<RocksDB>(corrupt, "database")
        val key = dataKey(2, original.sequence)
        val bytes = assertNotNull(db.get(key)).apply { this[40] = (this[40].toInt() xor 1).toByte() }
        db.put(key, bytes)
        assertFailsWith<IllegalStateException> { corrupt.append(TradingHistoryBatch(2, completion = completion)) }
        assertNull(db.get(dataKey(3, 2)))
        assertEquals(1L, ByteBuffer.wrap(assertNotNull(db.get(byteArrayOf(20)))).long)
        assertFailsWith<IllegalStateException> { corrupt.close() }
    }

    @Test
    fun `target durability acknowledges a prefix and full flush preserves all later values`() {
        val fixture = TradingHistoryFixture()
        RocksDbTradingHistoryStore.create(directory.resolve("target"), limits.copy(maximumGroupBatches = 1)).use { store ->
            assertEquals(0L, store.awaitDurable(0))
            repeat(4) { index ->
                store.append(TradingHistoryBatch(index + 1L, request = rejected(fixture, "user", "request-$index")))
            }
            assertTrue(store.awaitDurable(1) in 1L..4L)
            assertNotNull(store.findRequest(UserId("user"), "request-0"))
            assertEquals(4L, store.flush())
            assertEquals(4L, store.awaitDurable(1))
            assertEquals(0, store.pendingBatchCount)
            assertEquals((0 until 4).map { "request-$it" }, store.requests().map { it.requestId }.toList())
        }
    }

    @Test
    fun `target durability rejects negative and unappended ordinals instead of waiting`() {
        for (target in listOf(-1L, 2L)) {
            val store = RocksDbTradingHistoryStore.create(directory.resolve("invalid-target-$target"), limits)
            store.append(TradingHistoryBatch(1))
            assertFailsWith<IllegalArgumentException> { store.awaitDurable(target) }
            assertFailsWith<IllegalStateException> { store.close() }
        }
    }

    @Test
    fun `bounded admission index cache evicts and still resolves older exact order identities`() {
        val fixture =
            TradingHistoryFixture().apply {
                funding("seller", "BTC", 100)
                repeat(5) { admit("sell-$it", "seller", Side.SELL) }
            }
        val small = limits.copy(maximumCachedAdmissionIndexes = 2, maximumCachedAdmissionIndexBytes = 1_024)
        RocksDbTradingHistoryStore.create(directory.resolve("index-cache"), small).use { store ->
            fixture.batches.forEach(store::append)
            store.flush()
            assertEquals(2, store.cachedAdmissionIndexCount)
            val records = fixture.batches.mapNotNull { it.admission }
            repeat(2) {
                for (record in records) {
                    val order = record.candidate.order
                    val actual = assertNotNull(store.findAdmission(order.marketId, order.orderId))
                    assertContentEquals(codec.encodeAdmission(record), codec.encodeAdmission(actual))
                    assertTrue(store.cachedAdmissionIndexCount <= 2)
                    assertTrue(store.cachedAdmissionIndexBytes <= 1_024)
                }
            }
        }
        RocksDbTradingHistoryStore
            .create(
                directory.resolve("uncached"),
                small.copy(maximumCachedAdmissionIndexBytes = 1),
            ).use { store ->
                fixture.batches.forEach(store::append)
                store.flush()
                assertEquals(0, store.cachedAdmissionIndexCount)
                for (record in fixture.batches.mapNotNull { it.admission }) {
                    assertNotNull(store.findAdmission(record.candidate.order.marketId, record.candidate.order.orderId))
                }
            }
    }

    @Test
    fun `negative lookup filter never forgets inserted keys even after saturation`() {
        val filter = TradingHistoryKeyFilter(1)
        assertFalse(filter.mightContain("missing".toByteArray()))
        val keys = (0 until 4_096).map { "request-\u0000-$it-한".toByteArray() }
        keys.forEach(filter::add)
        assertTrue(keys.all(filter::mightContain))
        // A fully occupied filter is allowed to say maybe, not to decide that a key exists.
        assertTrue(filter.mightContain("never-inserted".toByteArray()))
    }

    @Test
    fun `filter false positives still require exact stored keys and original results`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        RocksDbTradingHistoryStore.create(directory.resolve("filter"), limits).use { store ->
            fixture.batches.forEach(store::append)
            store.flush()
            val filterField = store.javaClass.getDeclaredField("knownKeys").apply { isAccessible = true }
            val filter = filterField.get(store)
            val wordsField = filter.javaClass.getDeclaredField("words").apply { isAccessible = true }
            (wordsField.get(filter) as LongArray).fill(-1L)
            assertNull(store.findRequest(UserId("nobody"), "never-inserted"))
            assertFalse(store.containsTransactionId("never-inserted"))
            assertFalse(store.containsSourceEventId("never-inserted"))
            for (batch in fixture.batches) {
                batch.request?.let { expected ->
                    val actual = assertNotNull(store.findRequest(expected.order.userId, expected.requestId))
                    assertContentEquals(codec.encodeRequest(expected), codec.encodeRequest(actual))
                }
            }
        }
    }

    @Test
    fun `tiny cache persists every index and full historical value with bounded pending state`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val path = directory.resolve("history")
        RocksDbTradingHistoryStore.create(path, limits).use { store ->
            for (batch in fixture.batches) {
                store.append(batch)
                assertTrue(store.pendingBatchCount <= limits.maximumPendingBatches)
                assertTrue(store.retainedPendingBytes <= limits.maximumPendingBytes)
                batch.request?.let { request ->
                    assertContentEquals(
                        codec.encodeRequest(request),
                        codec.encodeRequest(assertNotNull(store.findRequest(request.order.userId, request.requestId))),
                    )
                }
                batch.admission?.let { admission ->
                    assertContentEquals(
                        codec.encodeAdmission(admission),
                        codec.encodeAdmission(assertNotNull(store.findAdmission(admission.sequence))),
                    )
                    assertContentEquals(
                        codec.encodeAdmission(admission),
                        codec.encodeAdmission(
                            assertNotNull(store.findAdmission(admission.candidate.order.marketId, admission.candidate.order.orderId)),
                        ),
                    )
                }
                batch.completion?.let { completion ->
                    assertContentEquals(
                        codec.encodeCompletion(completion),
                        codec.encodeCompletion(assertNotNull(store.findCompletion(completion.admission.sequence))),
                    )
                }
                batch.preparation?.let { preparation ->
                    assertContentEquals(
                        codec.encodePreparation(preparation),
                        codec.encodePreparation(assertNotNull(store.findPreparation(preparation.result.opening.preparationId))),
                    )
                }
                batch.ledgerTransactions.forEach { transaction ->
                    assertTrue(store.containsTransactionId(transaction.ledgerTransactionId))
                    assertTrue(store.containsSourceEventId(transaction.sourceEventId))
                }
            }
            assertEquals(fixture.batches.last().ordinal, store.flush())
            assertEquals(0, store.pendingBatchCount)
            assertEquals(0L, store.retainedPendingBytes)
            val expected = fixture.state.snapshot()
            assertEquals(expected.requests.map { it.requestId }, store.requests().map { it.requestId }.toList())
            assertEquals(expected.completions.map { it.executionId }, store.completions().map { it.executionId }.toList())
            assertEquals(expected.preparations.map { it.result }, store.preparations().map { it.result }.toList())
            assertEquals(expected.reservations, store.reservations().toList())
            assertTrue(store.reservations().all { it.status == OrderReservationStatus.SETTLED })
            assertEquals(
                expected.ledgerTransactions.map { it.ledgerTransactionId },
                store.transactions().map { it.ledgerTransactionId }.toList(),
            )
            assertEquals(listOf(1L, 2L), store.admissions().map { it.sequence }.toList())
            assertContentEquals(
                codec.encodeCompletion(expected.completions.last()),
                codec.encodeCompletion(assertNotNull(store.findCompletion(2))),
            )
            assertNull(store.findCompletion(999))
            assertNull(store.findAdmission(0))
            assertNull(store.findRequest(UserId("nobody"), "absent"))
            assertNull(store.findPreparation("absent"))
            assertFalse(store.containsTransactionId("absent"))
            assertFalse(store.containsSourceEventId("absent"))
        }
        // A committed history remains on disk; it is never silently opened as a fresh trading owner.
        RocksDB.openReadOnly(path.toString()).use { db ->
            assertEquals(fixture.batches.last().ordinal, ByteBuffer.wrap(assertNotNull(db.get(byteArrayOf(20)))).long)
        }
        assertFailsWith<FileAlreadyExistsException> { RocksDbTradingHistoryStore.create(path, limits) }
    }

    @Test
    fun `real trading owner evicts after durable acknowledgement and reloads original answers without moving funds`() {
        RocksDbTradingHistoryStore.create(directory.resolve("owner"), limits).use { store ->
            val fixture = TradingHistoryFixture(store)
            fixture.funding("seller", "BTC", 100)
            fixture.funding("buyer", "KRW", 100_000)
            var original: com.exchange.core.trading.TradingState.Completion? = null
            repeat(40) { index ->
                for ((side, user) in listOf(Side.SELL to "seller", Side.BUY to "buyer")) {
                    val id = "$user-$index"
                    val accepted =
                        fixture.state.admit(
                            "request-$id",
                            fixture.order(id, user, side),
                            fixture.market,
                            fixture.policy,
                            "reserve-$id",
                            fixture.time,
                        ) as OrderAdmissionState.Result.Accepted
                    val completion = fixture.state.executePending(accepted.record.sequence, fixture.time.plusNanos(index.toLong()))
                    if (original == null) original = completion
                }
            }
            fixture.state.flushHistory()
            val retained = fixture.state.historyRetention()
            assertEquals(0, retained.stagedBatches)
            assertEquals(0, retained.retainedRequests)
            assertEquals(0, retained.retainedAdmissions)
            assertEquals(0, retained.retainedCompletions)
            assertEquals(0, retained.retainedReservations)
            assertEquals(0, retained.retainedTransactions)
            assertEquals(0, retained.retainedTransactionIds)
            assertEquals(0, retained.retainedSourceEventIds)
            assertEquals(0, retained.retainedPreparations)
            val before = fixture.state.snapshot()
            val replay = fixture.state.executePending(1, fixture.time.plusSeconds(999))
            assertContentEquals(codec.encodeCompletion(assertNotNull(original)), codec.encodeCompletion(replay))
            val duplicate =
                fixture.state.admit(
                    "request-seller-0",
                    fixture.order("seller-0", "seller", Side.SELL),
                    fixture.market,
                    fixture.policy.copy(scheduleVersion = 99),
                    "unused-ledger",
                    fixture.time.plusSeconds(999),
                ) as OrderAdmissionState.Result.Accepted
            assertEquals(1L, duplicate.record.sequence)
            assertEquals(fixture.policy, duplicate.record.candidate.feePolicySnapshot)
            assertEquals(before.balances, fixture.state.snapshot().balances)
            assertEquals(80, before.requests.size)
            assertEquals(80, before.completions.size)
            assertEquals(122, before.ledgerTransactions.size)
        }
    }

    @Test
    fun `exact length prefixed keys preserve delimiters and UTF16 while cold scans retain insertion order`() {
        val fixture = TradingHistoryFixture()
        val pairs = listOf("a:b" to "c", "a" to "b:c", "a\uD800" to "c", "a?" to "c")
        RocksDbTradingHistoryStore.create(directory.resolve("keys"), limits).use { store ->
            pairs.forEachIndexed { index, (user, requestId) ->
                val request = rejected(fixture, user, requestId)
                store.append(TradingHistoryBatch(index + 1L, request = request))
            }
            store.flush()
            pairs.forEach { (user, requestId) ->
                assertEquals(user, assertNotNull(store.findRequest(UserId(user), requestId)).order.userId.value)
            }
            assertEquals(pairs.map { it.first }, store.requests().map { it.order.userId.value }.toList())
        }
    }

    @Test
    fun `history size does not leave a permanent Java key index and abandoned scans own no iterator`() {
        val fixture = TradingHistoryFixture()
        RocksDbTradingHistoryStore.create(directory.resolve("bounded"), limits).use { store ->
            repeat(500) { index ->
                store.append(TradingHistoryBatch(index + 1L, request = rejected(fixture, "user", "request-$index")))
            }
            assertEquals(500L, store.flush())
            assertEquals(0, store.pendingBatchCount)
            assertEquals(0L, store.retainedPendingBytes)
            repeat(30) { assertEquals("request-0", store.requests().first().requestId) }
            assertEquals(500, store.requests().count())
            assertNotNull(store.findRequest(UserId("user"), "request-0"))
            assertNotNull(store.findRequest(UserId("user"), "request-499"))
        }
    }

    @Test
    fun `default strict cache crosses repeated memtable and table reader flush boundaries`() {
        val fixture = TradingHistoryFixture()
        val defaults = TradingHistoryStorageLimits()
        val onFatal = AtomicReference<Throwable>()
        RocksDbTradingHistoryStore.create(directory.resolve("default-flush"), onFatal = { onFatal.set(it) }).use { store ->
            val database = privateField<RocksDB>(store, "database")
            var ordinal = 0L
            var firstRequest: String? = null
            FlushOptions().setWaitForFlush(true).use { flush ->
                repeat(8) { cycle ->
                    var lastRequest = ""
                    repeat(2_048) { row ->
                        lastRequest = "request-$cycle-$row".padEnd(64, 'x')
                        if (firstRequest == null) firstRequest = lastRequest
                        store.append(TradingHistoryBatch(++ordinal, request = rejected(fixture, "user", lastRequest)))
                    }
                    assertEquals(ordinal, store.flush())
                    // WAL durability does not flush a memtable. Exercise SST creation and its
                    // pinned reader reservations explicitly, using all production memory limits.
                    database.flush(flush)
                    assertNotNull(store.findRequest(UserId("user"), assertNotNull(firstRequest)))
                    assertNotNull(store.findRequest(UserId("user"), lastRequest))
                    assertTrue(database.getLongProperty("rocksdb.block-cache-usage") <= defaults.cacheBytes)
                    assertTrue(database.getLongProperty("rocksdb.block-cache-pinned-usage") <= defaults.cacheBytes)
                    assertEquals(0L, database.getLongProperty("rocksdb.background-errors"))
                    assertNull(onFatal.get())
                }
            }
            assertEquals(16_384L, store.durableOrdinal)
            assertTrue(database.liveFilesMetaData.isNotEmpty())
            assertEquals(0, store.pendingBatchCount)
            assertEquals(0L, store.retainedPendingBytes)
        }
    }

    @Test
    fun `memory limits reject caches unable to hold reservations and memtables without headroom`() {
        assertFailsWith<IllegalArgumentException> {
            TradingHistoryStorageLimits(cacheBytes = 65_536, writeBufferBytes = 65_536)
        }
        assertFailsWith<IllegalArgumentException> {
            TradingHistoryStorageLimits(cacheBytes = 2L * 1_024 * 1_024, writeBufferBytes = 2L * 1_024 * 1_024)
        }
        assertEquals(2L * 1_024 * 1_024, limits.cacheBytes)
    }

    @Test
    fun `writer failure notifies while idle and never advances acknowledgement or masquerades as missing`() {
        val noticed = CountDownLatch(1)
        val notification = AtomicReference<Throwable>()
        val store =
            RocksDbTradingHistoryStore.create(directory.resolve("write-failure"), limits) {
                notification.set(it)
                noticed.countDown()
            }
        // RocksDB rejects sync+disabled WAL at the real native write boundary; no thread or handle is closed under an active call.
        privateField<WriteOptions>(store, "writeOptions").setDisableWAL(true)
        store.append(TradingHistoryBatch(1, request = rejected(TradingHistoryFixture(), "user", "request")))
        assertTrue(noticed.await(5, TimeUnit.SECONDS))
        assertNotNull(notification.get())
        assertFailsWith<IllegalStateException> { store.awaitDurable(1) }
        assertFailsWith<IllegalStateException> { store.flush() }
        assertFailsWith<IllegalStateException> { store.findRequest(UserId("nobody"), "missing") }
        assertFailsWith<IllegalStateException> { store.durableOrdinal }
        assertFailsWith<IllegalStateException> { store.close() }
        store.close()
    }

    @Test
    fun `corrupt disk values fail the store and prohibit subsequent missing lookups`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val store = RocksDbTradingHistoryStore.create(directory.resolve("read-failure"), limits)
        fixture.batches.forEach(store::append)
        store.flush()
        val db = privateField<RocksDB>(store, "database")
        // Corrupt an existing canonical admission without modifying any secondary index.
        val key =
            ByteBuffer
                .allocate(9)
                .put(2)
                .putLong(1)
                .array()
        val bytes = assertNotNull(db.get(key))
        bytes[40] = (bytes[40].toInt() xor 1).toByte()
        db.put(key, bytes)
        assertFailsWith<IllegalStateException> { store.findAdmission(1) }
        assertFailsWith<IllegalStateException> { store.findAdmission(9_999) }
        assertFailsWith<IllegalStateException> { store.close() }
    }

    @Test
    fun `logical storage cap fails before accepting an oversized transition and closed store never returns absence`() {
        val store = RocksDbTradingHistoryStore.create(directory.resolve("limited"), limits.copy(maximumWrittenBytes = 1))
        assertFailsWith<IllegalStateException> { store.append(TradingHistoryBatch(1)) }
        assertFailsWith<IllegalStateException> { store.close() }
        val closed = RocksDbTradingHistoryStore.create(directory.resolve("closed"), limits)
        closed.close()
        assertFailsWith<IllegalStateException> { closed.findPreparation("anything") }
        assertFailsWith<IllegalStateException> { closed.append(TradingHistoryBatch(1)) }
    }

    @Test
    fun `interrupted shutdown still joins writer and releases native handles while preserving interrupt`() {
        val store = RocksDbTradingHistoryStore.create(directory.resolve("interrupted-close"), limits)
        val database = privateField<RocksDB>(store, "database")
        val writer = privateField<Thread>(store, "writer")
        try {
            Thread.currentThread().interrupt()
            store.close()
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(writer.isAlive)
            assertFalse(database.isOwningHandle)
            store.close()
        } finally {
            Thread.interrupted()
            store.close()
        }
    }

    private fun assertHistoryMatches(
        fixture: TradingHistoryFixture,
        store: RocksDbTradingHistoryStore,
    ) {
        for (batch in fixture.batches) {
            batch.request?.let {
                assertContentEquals(
                    codec.encodeRequest(it),
                    codec.encodeRequest(assertNotNull(store.findRequest(it.order.userId, it.requestId))),
                )
            }
            batch.admission?.let {
                assertContentEquals(
                    codec.encodeAdmission(it),
                    codec.encodeAdmission(assertNotNull(store.findAdmission(it.sequence))),
                )
                assertContentEquals(
                    codec.encodeAdmission(it),
                    codec.encodeAdmission(assertNotNull(store.findAdmission(it.candidate.order.marketId, it.candidate.order.orderId))),
                )
            }
            batch.completion?.let {
                assertContentEquals(
                    codec.encodeCompletion(it),
                    codec.encodeCompletion(assertNotNull(store.findCompletion(it.admission.sequence))),
                )
            }
            batch.preparation?.let {
                assertContentEquals(
                    codec.encodePreparation(it),
                    codec.encodePreparation(assertNotNull(store.findPreparation(it.result.opening.preparationId))),
                )
            }
            for (transaction in batch.ledgerTransactions) {
                assertTrue(store.containsTransactionId(transaction.ledgerTransactionId))
                assertTrue(store.containsSourceEventId(transaction.sourceEventId))
            }
        }
        val expectedTransactions = fixture.batches.flatMap { it.ledgerTransactions }.map(codec::encodeTransaction)
        val actualTransactions = store.transactions().map(codec::encodeTransaction).toList()
        assertEquals(expectedTransactions.size, actualTransactions.size)
        expectedTransactions.zip(actualTransactions).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
        val expectedReservations =
            fixture.state
                .snapshot()
                .reservations
                .map(codec::encodeReservation)
        val actualReservations = store.reservations().map(codec::encodeReservation).toList()
        assertEquals(expectedReservations.size, actualReservations.size)
        expectedReservations.zip(actualReservations).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
    }

    private fun rejected(
        fixture: TradingHistoryFixture,
        user: String,
        requestId: String,
    ): OrderAdmissionState.RequestRecord =
        TradingHistoryValues.requestRecord(
            requestId,
            fixture.order("order-$requestId", user, Side.BUY),
            fixture.market,
            fixture.policy,
            "ledger-$requestId",
            fixture.time,
            OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.DUPLICATE_ORDER),
        )

    private fun dataKey(
        kind: Int,
        ordinal: Long,
    ): ByteArray =
        ByteBuffer
            .allocate(9)
            .put(kind.toByte())
            .putLong(ordinal)
            .array()

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(
        target: Any,
        name: String,
    ): T =
        target.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(target) as T
}
