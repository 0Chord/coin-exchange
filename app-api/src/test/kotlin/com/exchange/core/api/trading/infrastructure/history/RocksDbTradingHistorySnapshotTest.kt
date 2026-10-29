package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingHistoryBatch
import com.exchange.core.trading.TradingHistoryValues
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rocksdb.WriteOptions
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias SnapshotRows = List<Pair<ByteArray, ByteArray>>

class RocksDbTradingHistorySnapshotTest {
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
    fun `derived index omits its WAL and restores exact history from a streamed snapshot`() {
        RocksDbTradingHistoryStore.create(directory.resolve("strict-options"), limits).use { strict ->
            assertTrue(writeOptions(strict).sync())
            assertFalse(writeOptions(strict).disableWAL())
        }
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        RocksDbTradingHistoryStore.createRebuildableIndex(directory.resolve("derived-source"), limits).use { source ->
            assertFalse(writeOptions(source).sync())
            assertTrue(writeOptions(source).disableWAL())
            for (batch in fixture.batches) {
                source.append(batch)
                batch.request?.let {
                    assertContentEquals(
                        codec.encodeRequest(it),
                        codec.encodeRequest(assertNotNull(source.findRequest(it.order.userId, it.requestId))),
                    )
                }
                assertTrue(source.pendingBatchCount <= limits.maximumPendingBatches)
                assertTrue(source.retainedPendingBytes <= limits.maximumPendingBytes)
            }
            RocksDbTradingHistoryStore.createRebuildableIndex(directory.resolve("derived-restored"), limits).use { target ->
                target.beginSnapshotRestore()
                val ordinal = source.exportSnapshot(target::restoreSnapshotEntry)
                target.completeSnapshotRestore(ordinal)
                assertEquals(fixture.batches.last().ordinal, target.durableOrdinal)
                for (batch in fixture.batches) {
                    batch.request?.let {
                        assertContentEquals(
                            codec.encodeRequest(it),
                            codec.encodeRequest(assertNotNull(target.findRequest(it.order.userId, it.requestId))),
                        )
                    }
                    batch.completion?.let {
                        assertContentEquals(
                            codec.encodeCompletion(it),
                            codec.encodeCompletion(assertNotNull(target.findCompletion(it.admission.sequence))),
                        )
                    }
                    batch.ledgerTransactions.forEach {
                        assertTrue(target.containsTransactionId(it.ledgerTransactionId))
                        assertTrue(target.containsSourceEventId(it.sourceEventId))
                    }
                }
                assertNull(target.findRequest(UserId("missing"), "missing"))
                val next = rejected(fixture)
                target.append(TradingHistoryBatch(ordinal + 1, request = next))
                assertEquals(ordinal + 1, target.flush())
                assertContentEquals(
                    codec.encodeRequest(next),
                    codec.encodeRequest(assertNotNull(target.findRequest(next.order.userId, next.requestId))),
                )
            }
        }
    }

    @Test
    fun `derived index still blocks malformed snapshot restoration`() {
        val target = RocksDbTradingHistoryStore.createRebuildableIndex(directory.resolve("derived-invalid"), limits)
        target.beginSnapshotRestore()
        assertFailsWith<IllegalStateException> { target.restoreSnapshotEntry(byteArrayOf(99), byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { target.completeSnapshotRestore(0) }
        assertFailsWith<IllegalStateException> { target.findRequest(UserId("missing"), "missing") }
        assertFailsWith<IllegalStateException> { target.close() }
        target.close()
    }

    @Test
    fun `streaming snapshot restores exact originals indexes and continuation ordinal`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        fixture.batches.add(
            TradingHistoryBatch(
                fixture.batches.last().ordinal + 1,
                request =
                    TradingHistoryValues.requestRecord(
                        "old:요청😀",
                        fixture.order("old:주문😀", "사용자😀", Side.BUY),
                        fixture.market,
                        fixture.policy,
                        "old:원장😀",
                        fixture.time,
                        OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE),
                    ),
            ),
        )
        RocksDbTradingHistoryStore.create(directory.resolve("source"), limits).use { source ->
            fixture.batches.forEach(source::append)
            RocksDbTradingHistoryStore.create(directory.resolve("restored"), limits).use { target ->
                target.beginSnapshotRestore()
                val ordinal = source.exportSnapshot(target::restoreSnapshotEntry)
                assertEquals(fixture.batches.last().ordinal, ordinal)
                target.completeSnapshotRestore(ordinal)
                assertEquals(ordinal, target.durableOrdinal)
                for (batch in fixture.batches) {
                    batch.request?.let {
                        assertContentEquals(
                            codec.encodeRequest(it),
                            codec.encodeRequest(assertNotNull(target.findRequest(it.order.userId, it.requestId))),
                        )
                    }
                    batch.admission?.let {
                        assertContentEquals(
                            codec.encodeAdmission(it),
                            codec.encodeAdmission(assertNotNull(target.findAdmission(it.sequence))),
                        )
                        assertContentEquals(
                            codec.encodeAdmission(it),
                            codec.encodeAdmission(
                                assertNotNull(target.findAdmission(it.candidate.order.marketId, it.candidate.order.orderId)),
                            ),
                        )
                    }
                    batch.completion?.let {
                        assertContentEquals(
                            codec.encodeCompletion(it),
                            codec.encodeCompletion(assertNotNull(target.findCompletion(it.admission.sequence))),
                        )
                    }
                    batch.preparation?.let {
                        assertContentEquals(
                            codec.encodePreparation(it),
                            codec.encodePreparation(assertNotNull(target.findPreparation(it.result.opening.preparationId))),
                        )
                    }
                    batch.ledgerTransactions.forEach {
                        assertTrue(target.containsTransactionId(it.ledgerTransactionId))
                        assertTrue(target.containsSourceEventId(it.sourceEventId))
                    }
                }
                assertEquals(source.transactions().count(), target.transactions().count())
                val expectedReservations = source.reservations().map(codec::encodeReservation).toList()
                val actualReservations = target.reservations().map(codec::encodeReservation).toList()
                assertEquals(expectedReservations.size, actualReservations.size)
                expectedReservations.zip(actualReservations).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
                assertNull(target.findRequest(UserId("missing"), "missing"))

                val next = rejected(fixture)
                target.append(TradingHistoryBatch(ordinal + 1, request = next))
                assertEquals(ordinal + 1, target.flush())
                assertContentEquals(
                    codec.encodeRequest(next),
                    codec.encodeRequest(assertNotNull(target.findRequest(next.order.userId, next.requestId))),
                )
            }
        }
    }

    @Test
    fun `empty snapshot restores a writable empty generation`() {
        RocksDbTradingHistoryStore.create(directory.resolve("empty"), limits).use { source ->
            RocksDbTradingHistoryStore.create(directory.resolve("empty-restored"), limits).use { target ->
                target.beginSnapshotRestore()
                val ordinal = source.exportSnapshot(target::restoreSnapshotEntry)
                assertEquals(0L, ordinal)
                target.completeSnapshotRestore(ordinal)
                assertEquals(0L, target.flush())
                assertNull(target.findRequest(UserId("new"), "new"))
                target.append(TradingHistoryBatch(1))
                assertEquals(1L, target.flush())
            }
        }
    }

    @Test
    fun `malformed duplicated missing and inconsistent snapshot rows fail closed`() {
        val snapshot = snapshot()
        val cases: List<Pair<String, (SnapshotRows) -> SnapshotRows>> =
            listOf(
                "duplicate" to { rows -> listOf(rows.first(), rows.first()) + rows.drop(1) },
                "reversed" to { rows -> rows.reversed() },
                "unknown-prefix" to { rows -> rows + (byteArrayOf(99) to byteArrayOf(1)) },
                "metadata-length" to { rows -> rows.map { if (it.first[0] == 20.toByte()) it.first to byteArrayOf(1) else it } },
                "missing-metadata" to { rows -> rows.filter { it.first[0] != 20.toByte() } },
                "missing-budget" to { rows -> rows.filter { it.first[0] != 21.toByte() } },
                "missing-index" to { rows -> rows.filter { it.first[0] != 11.toByte() } },
                "missing-admission" to { rows -> rows.filter { it.first[0] != 2.toByte() } },
                "corrupt-frame" to { rows ->
                    rows.mapIndexed { index, row ->
                        if (index == 0) {
                            row.first to row.second.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
                        } else {
                            row
                        }
                    }
                },
                "future-ordinal" to { rows ->
                    rows.map {
                        if (it.first[0] == 20.toByte()) it.first to ByteBuffer.allocate(8).putLong(snapshot.first + 1).array() else it
                    }
                },
                "bad-string-key" to { rows ->
                    rows.map { if (it.first[0] == 11.toByte()) byteArrayOf(11, 127, 127, 127, 127) to it.second else it }
                },
            )
        for ((name, change) in cases) {
            val target = RocksDbTradingHistoryStore.create(directory.resolve(name), limits)
            target.beginSnapshotRestore()
            assertFailsWith<IllegalStateException>(name) {
                change(snapshot.second).forEach { (key, value) -> target.restoreSnapshotEntry(key, value) }
                target.completeSnapshotRestore(snapshot.first)
            }
            assertFailsWith<IllegalStateException>(name) { target.findRequest(UserId("missing"), "missing") }
            assertFailsWith<IllegalStateException>(name) { target.close() }
            target.close()
        }
    }

    @Test
    fun `restore preserves cumulative byte cap rather than resetting overwritten history cost`() {
        val snapshot = snapshot()
        val written = ByteBuffer.wrap(snapshot.second.last().second).long
        val target = RocksDbTradingHistoryStore.create(directory.resolve("budget"), limits.copy(maximumWrittenBytes = written))
        target.beginSnapshotRestore()
        snapshot.second.forEach { (key, value) -> target.restoreSnapshotEntry(key, value) }
        target.completeSnapshotRestore(snapshot.first)
        assertEquals(snapshot.first, target.durableOrdinal)
        assertFailsWith<IllegalStateException> { target.append(TradingHistoryBatch(snapshot.first + 1)) }
        assertFailsWith<IllegalStateException> { target.close() }
        target.close()
    }

    @Test
    fun `restore rejects nonempty stores and live reads during incomplete restore`() {
        val nonempty = RocksDbTradingHistoryStore.create(directory.resolve("nonempty"), limits)
        nonempty.append(TradingHistoryBatch(1))
        assertFailsWith<IllegalStateException> { nonempty.beginSnapshotRestore() }
        assertFailsWith<IllegalStateException> { nonempty.close() }
        nonempty.close()

        val restoring = RocksDbTradingHistoryStore.create(directory.resolve("restoring"), limits)
        restoring.beginSnapshotRestore()
        assertFailsWith<IllegalStateException> { restoring.findRequest(UserId("missing"), "missing") }
        assertFailsWith<IllegalStateException> { restoring.completeSnapshotRestore(0) }
        assertFailsWith<IllegalStateException> { restoring.close() }
        restoring.close()
    }

    @Test
    fun `oversized rows and failed snapshot consumers block subsequent operations`() {
        val target = RocksDbTradingHistoryStore.create(directory.resolve("oversized"), limits)
        target.beginSnapshotRestore()
        assertFailsWith<IllegalStateException> { target.restoreSnapshotEntry(byteArrayOf(20), ByteArray(limits.maximumValueBytes + 1)) }
        assertFailsWith<IllegalStateException> { target.close() }
        target.close()

        val source = RocksDbTradingHistoryStore.create(directory.resolve("consumer-failure"), limits)
        assertFailsWith<IllegalStateException> { source.exportSnapshot { _, _ -> error("snapshot sink failed") } }
        assertFailsWith<IllegalStateException> { source.findRequest(UserId("missing"), "missing") }
        assertFailsWith<IllegalStateException> { source.close() }
        source.close()
    }

    private fun snapshot(): Pair<Long, List<Pair<ByteArray, ByteArray>>> {
        val rows = mutableListOf<Pair<ByteArray, ByteArray>>()
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val ordinal =
            RocksDbTradingHistoryStore.create(directory.resolve("fixture"), limits).use { source ->
                fixture.batches.forEach(source::append)
                source.exportSnapshot { key, value -> rows.add(key to value) }
            }
        return ordinal to rows
    }

    private fun rejected(fixture: TradingHistoryFixture): OrderAdmissionState.RequestRecord =
        TradingHistoryValues.requestRecord(
            "next-request",
            fixture.order("next-order", "next-user", Side.BUY),
            fixture.market,
            fixture.policy,
            "next-ledger",
            fixture.time,
            OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.DUPLICATE_ORDER),
        )

    private fun writeOptions(store: RocksDbTradingHistoryStore): WriteOptions =
        store.javaClass
            .getDeclaredField("writeOptions")
            .apply { isAccessible = true }
            .get(store) as WriteOptions
}
