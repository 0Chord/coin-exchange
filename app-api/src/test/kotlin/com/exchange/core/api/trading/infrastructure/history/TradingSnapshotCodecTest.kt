package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.order.Side
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class TradingSnapshotCodecTest {
    @TempDir
    lateinit var directory: Path

    private val identity = "a".repeat(64)
    private val values = TradingHistoryCodec(16 * 1_024 * 1_024)
    private val limits =
        TradingHistoryStorageLimits(
            cacheBytes = 2L * 1_024 * 1_024,
            writeBufferBytes = 256L * 1_024,
            maximumPendingBytes = 512L * 1_024,
            maximumPendingBatches = 4,
            maximumGroupBatches = 4,
            maximumOpenFiles = 16,
        )

    @Test
    fun `snapshot restores partial maker pending order and exact cold responses despite a newer original DB`() {
        RocksDbTradingHistoryStore.create(directory.resolve("original"), limits).use { originalStore ->
            val original = TradingHistoryFixture(originalStore)
            original.funding("seller", "BTC", 10)
            original.funding("buyer", "KRW", 100_000)
            original.execute(assertIs<OrderAdmissionState.Result.Accepted>(original.admit("maker", "seller", Side.SELL, 3)).record)
            original.execute(assertIs<OrderAdmissionState.Result.Accepted>(original.admit("first", "buyer", Side.BUY, 1)).record)
            assertIs<OrderAdmissionState.Result.Rejected>(original.admit("rejected", "unfunded", Side.BUY))
            val pending = assertIs<OrderAdmissionState.Result.Accepted>(original.admit("pending", "buyer", Side.BUY, 1)).record
            val expectedPrefix = original.state.snapshot()
            val expectedBook = original.state.book(original.market.marketId)
            val bytes = ByteArrayOutputStream()
            TradingSnapshotCodec(originalStore, identity).write(original.state, 7, bytes)

            // 원본 DB가 체크포인트 이후로 진행해도 최신 값이 복구 상태에 섞여서는 안 된다.
            val expectedCompletion = original.execute(pending)
            val expectedSuffix = original.state.snapshot()
            original.funding("later", "KRW", 777)
            RocksDbTradingHistoryStore.create(directory.resolve("restored"), limits).use { restoredStore ->
                val restored = TradingHistoryFixture(restoredStore)
                val malformed =
                    TradingSnapshotCodec(
                        restoredStore,
                        identity,
                    ).read(restored.state, ByteArrayInputStream(bytes.toByteArray()))
                assertEquals(7L, malformed)
                assertSnapshot(expectedPrefix, restored.state.snapshot())
                assertEquals(expectedBook, restored.state.book(original.market.marketId))
                assertContentEquals(
                    values.encodeCompletion(expectedCompletion),
                    values.encodeCompletion(restored.state.executePending(pending.sequence, expectedCompletion.occurredAt)),
                )
                assertSnapshot(expectedSuffix, restored.state.snapshot())

                val beforeRetry = restored.state.snapshot()
                val rejected =
                    restored.state.admit(
                        "request-rejected",
                        restored.order("rejected", "unfunded", Side.BUY),
                        restored.market,
                        restored.policy,
                        "new-ledger-not-used",
                        restored.time.plusSeconds(1),
                    )
                assertIs<OrderAdmissionState.Result.Rejected>(rejected)
                assertSnapshot(beforeRetry, restored.state.snapshot())
                assertNotNull(restoredStore.findAdmission(pending.sequence))
                assertFalse(restored.state.isBlocked)
            }
        }
    }

    @Test
    fun `empty snapshot and completed history both restore without using the source database`() {
        for (withHistory in listOf(false, true)) {
            val bytes = ByteArrayOutputStream()
            lateinit var expected: TradingState.Snapshot
            RocksDbTradingHistoryStore.create(directory.resolve("source-$withHistory"), limits).use { store ->
                val fixture = TradingHistoryFixture(store)
                if (withHistory) fixture.completedTrade()
                expected = fixture.state.snapshot()
                TradingSnapshotCodec(store, identity).write(fixture.state, 0, bytes)
            }
            RocksDbTradingHistoryStore.create(directory.resolve("target-$withHistory"), limits).use { store ->
                val fixture = TradingHistoryFixture(store)
                assertEquals(0L, TradingSnapshotCodec(store, identity).read(fixture.state, ByteArrayInputStream(bytes.toByteArray())))
                assertSnapshot(expected, fixture.state.snapshot())
            }
        }
    }

    @Test
    fun `unknown version wrong identity truncation corruption and trailing bytes cannot restore`() {
        val bytes = ByteArrayOutputStream()
        RocksDbTradingHistoryStore.create(directory.resolve("source"), limits).use { store ->
            val fixture = TradingHistoryFixture(store).apply { completedTrade() }
            TradingSnapshotCodec(store, identity).write(fixture.state, 0, bytes)
        }
        val valid = bytes.toByteArray()
        val invalid =
            listOf(
                valid.copyOf().also { ByteBuffer.wrap(it).putInt(4, 99) },
                valid.copyOf(valid.size - 1),
                valid.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() },
                valid + byteArrayOf(0),
            )
        invalid.forEachIndexed { index, candidate ->
            val store = RocksDbTradingHistoryStore.create(directory.resolve("invalid-$index"), limits)
            try {
                val fixture = TradingHistoryFixture(store)
                assertFails { TradingSnapshotCodec(store, identity).read(fixture.state, ByteArrayInputStream(candidate)) }
            } finally {
                // 헤더를 거절하면 복구를 시작하지 않는다. 일부만 가져온 상태를 현재 상태로 플러시해서는 안 된다.
                if (index == 0) store.close() else assertFailsWith<IllegalStateException> { store.close() }
            }
        }
        RocksDbTradingHistoryStore.create(directory.resolve("wrong-identity"), limits).use { store ->
            val fixture = TradingHistoryFixture(store)
            assertFails { TradingSnapshotCodec(store, "b".repeat(64)).read(fixture.state, ByteArrayInputStream(valid)) }
        }
    }

    private fun assertSnapshot(
        expected: TradingState.Snapshot,
        actual: TradingState.Snapshot,
    ) {
        assertEquals(expected.balances, actual.balances)
        assertEquals(expected.reservations, actual.reservations)
        assertFrames(expected.pendingOrders.map(values::encodeAdmission), actual.pendingOrders.map(values::encodeAdmission))
        assertEquals(expected.lastAdmissionSequence, actual.lastAdmissionSequence)
        assertFrames(expected.ledgerTransactions.map(values::encodeTransaction), actual.ledgerTransactions.map(values::encodeTransaction))
        assertFrames(expected.requests.map(values::encodeRequest), actual.requests.map(values::encodeRequest))
        assertFrames(expected.preparations.map(values::encodePreparation), actual.preparations.map(values::encodePreparation))
        assertFrames(expected.completions.map(values::encodeCompletion), actual.completions.map(values::encodeCompletion))
        assertEquals(expected.matchingResults, actual.matchingResults)
    }

    private fun assertFrames(
        expected: List<ByteArray>,
        actual: List<ByteArray>,
    ) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (left, right) -> assertContentEquals(left, right) }
    }
}
