package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.UserId
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.Side
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingHistoryBatch
import com.exchange.core.trading.TradingHistoryValues
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rocksdb.RocksDB
import org.rocksdb.WriteOptions
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
