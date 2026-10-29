package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingState
import org.agrona.concurrent.SystemNanoClock
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpPerformanceFileBackedCollectorHistoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `여러 작은 완료 batch는 원문을 해제하고 누적 식별 완료 수와 오래된 exact 원문을 보존한다`() {
        val archive = archive("batches", limits(pending = 8))
        val history = HttpPerformanceCollectorHistory(160, archive)
        history.use {
            repeat(40) { batch ->
                val originals = (0L..3L).map { accepted(batch * 4L + it) }
                originals.forEach(history::record)
                originals.forEach { history.markCompleted(it.sequence) }
                assertTrue(history.retainedCount <= 8)
                await {
                    history.drainAcknowledgements()
                    history.retainedCount == 0
                }
                assertEquals((batch + 1) * 4, history.identityCount)
                assertEquals((batch + 1) * 4, history.completedCount)
            }
            assertEquals(accepted(0), history[0])
            assertEquals(accepted(159), history[159])
            history.record(accepted(0))
            assertEquals(160, history.identityCount)
            assertFalse(history.markCompleted(0))
            assertEquals(0, history.retainedCount)
            assertTrue(archive.writtenBytes in 1..archive.limits.maximumFileBytes)
        }
        assertEquals(0, history.retainedCount)
        assertEquals(archive.writtenBytes, Files.size(archive.evidencePath))
        assertEquals(0, archive.queuedCount)
        assertEquals(0, archive.acknowledgedCount)
    }

    @Test
    fun `저장 확인이 먼저 와도 업무 완료와 callback tail 전에는 같은 원문을 유지한다`() {
        val archive = archive("ack-first", limits(pending = 1))
        HttpPerformanceCollectorHistory(1, archive).use { history ->
            val original = accepted(1)
            history.record(original)
            await { archive.acknowledgedCount == 1 }
            history.drainAcknowledgements()
            assertEquals(1, history.retainedCount)
            assertSame(original, history[1])
            history.markCompleted(1)
            assertSame(original, history[1])
            history.drainAcknowledgements()
            assertEquals(0, history.retainedCount)
            assertEquals(original, history[1])
        }
    }

    @Test
    fun `희소한 Long 극값과 네 필드 충돌은 원문 해제 후에도 exact 비교한다`() {
        val sequences = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1024L, 1L shl 45, Long.MAX_VALUE)
        val archive = archive("sparse", limits(pending = 8))
        HttpPerformanceCollectorHistory(sequences.size, archive).use { history ->
            val originals = sequences.map { accepted(it).copy(requestId = "한글-\uD83D\uDE00-\uD800-$it") }
            originals.forEach(history::record)
            originals.forEach { history.markCompleted(it.sequence) }
            await {
                history.drainAcknowledgements()
                history.retainedCount == 0
            }
            originals.forEach { original ->
                assertEquals(original, history[original.sequence])
                history.record(original.copy())
                listOf(
                    original.copy(userId = "OTHER"),
                    original.copy(requestId = "OTHER"),
                    original.copy(marketId = "OTHER"),
                    original.copy(orderId = "OTHER"),
                ).forEach { conflict ->
                    val failure = assertFailsWith<IllegalArgumentException> { history.record(conflict) }
                    assertEquals("완료 표식 접수 순번 식별 충돌", failure.message)
                    assertEquals(original, history[original.sequence])
                }
                assertTrue(history.isCompleted(original.sequence))
            }
            assertNull(history[1025])
            assertEquals(sequences.size, history.identityCount)
            assertEquals(sequences.size, history.completedCount)
        }
    }

    @Test
    fun `pending 한도와 writer queue 한도는 누적 계획과 별도로 실패한다`() {
        val pendingArchive = archive("pending", limits(pending = 1))
        HttpPerformanceCollectorHistory(2, pendingArchive).use { history ->
            history.record(accepted(1))
            await { pendingArchive.acknowledgedCount == 1 }
            history.drainAcknowledgements()
            assertFailsWith<IllegalArgumentException> { history.record(accepted(2)) }
            assertEquals(1, history.retainedCount)
            assertEquals(1, history.identityCount)
        }
        val queueArchive =
            HttpPerformanceIdentityArchive(directory.resolve("queue.bin"), limits(pending = 2, queue = 1), startImmediately = false)
        HttpPerformanceCollectorHistory(2, queueArchive).use { history ->
            history.record(accepted(1))
            assertFailsWith<IllegalStateException> { history.record(accepted(2)) }
            assertEquals(1, queueArchive.queuedCount)
            assertEquals(1, history.identityCount)
            queueArchive.start()
        }
    }

    @Test
    fun `원문 record와 누적 파일 byte 상한은 파일이 계획보다 커지기 전에 실패한다`() {
        val recordArchive = archive("record-limit", limits(pending = 1, recordBytes = 64))
        HttpPerformanceCollectorHistory(1, recordArchive).use { history ->
            assertFailsWith<IllegalArgumentException> { history.record(accepted(1).copy(userId = "x".repeat(100))) }
            assertEquals(0, history.identityCount)
            assertEquals(0, history.retainedCount)
        }
        val fileArchive =
            HttpPerformanceIdentityArchive(
                directory.resolve("file-limit.bin"),
                limits(pending = 4, recordBytes = 128, fileBytes = 128),
                startImmediately = false,
            )
        val history = HttpPerformanceCollectorHistory(4, fileArchive)
        repeat(4) { history.record(accepted(it.toLong())) }
        fileArchive.start()
        await { fileArchive.error != null }
        assertFailsWith<IllegalStateException> { history.drainAcknowledgements() }
        assertFailsWith<IllegalStateException> { history.close() }
        assertEquals(0, history.retainedCount)
        assertTrue(Files.size(fileArchive.evidencePath) <= 128)
    }

    @Test
    fun `기존 evidence 경로 재사용과 열기 I O 실패는 collector 오류로 드러난다`() {
        val existing = directory.resolve("existing.bin")
        Files.writeString(existing, "original evidence")
        val collector = collector(existing)
        await { collector.error != null }
        collector.close()
        assertNotNull(collector.error)
        assertEquals("original evidence", Files.readString(existing))

        val missingParent = collector(directory.resolve("missing/identity.bin"))
        await { missingParent.error != null }
        assertTrue(missingParent.error.orEmpty().contains("Accepted 원문"))
        missingParent.close()
    }

    @Test
    fun `writer 쓰기와 close 실패도 숨기지 않고 원문을 해제한다`() {
        val writeFailure = mock(FileChannel::class.java)
        doThrow(IOException("injected write failure")).`when`(writeFailure).write(any(ByteBuffer::class.java), anyLong())
        val failingArchive = HttpPerformanceIdentityArchive(directory.resolve("write-failure"), limits(pending = 1), { writeFailure })
        val history = HttpPerformanceCollectorHistory(1, failingArchive)
        history.record(accepted(1))
        history.markCompleted(1)
        await { failingArchive.error != null }
        assertTrue(failingArchive.error.orEmpty().contains("injected write failure"))
        assertFailsWith<IllegalStateException> { history.close() }
        assertEquals(0, history.retainedCount)

        val closeFailure = mock(FileChannel::class.java)
        doThrow(IOException("injected force failure")).`when`(closeFailure).force(false)
        val closeArchive = HttpPerformanceIdentityArchive(directory.resolve("close-failure"), limits(pending = 1), { closeFailure })
        val closingHistory = HttpPerformanceCollectorHistory(1, closeArchive)
        assertFailsWith<IllegalStateException> { closingHistory.close() }
        assertTrue(closeArchive.error.orEmpty().contains("injected force failure"))
        assertEquals(0, closingHistory.retainedCount)
    }

    @Test
    fun `cold 읽기 I O 실패는 원본으로 오인하지 않고 history 오류가 된다`() {
        val archive = archive("truncated", limits(pending = 1))
        val history = HttpPerformanceCollectorHistory(1, archive)
        history.record(accepted(1))
        history.markCompleted(1)
        await {
            history.drainAcknowledgements()
            history.retainedCount == 0
        }
        Files.newByteChannel(archive.evidencePath, WRITE).use { it.truncate(0) }
        assertFailsWith<IllegalStateException> { history[1] }
        assertNotNull(history.error)
        assertFailsWith<IllegalStateException> { history.close() }
        assertEquals(0, history.retainedCount)
    }

    @Test
    fun `cold 원문의 유효한 UTF16 문자 byte 변조도 CRC 오류로 검출한다`() {
        val archive = archive("modified-character", limits(pending = 1))
        val history = HttpPerformanceCollectorHistory(1, archive)
        history.record(accepted(1))
        history.markCompleted(1)
        await {
            history.drainAcknowledgements()
            history.retainedCount == 0
        }
        // Header(16) + first field length(4) + the low byte of its first UTF-16 code unit.
        // U -> T remains valid text and keeps the frame's sequence, lengths and total bytes intact.
        FileChannel.open(archive.evidencePath, WRITE).use { channel ->
            channel.write(ByteBuffer.wrap(byteArrayOf('T'.code.toByte())), 21)
        }
        val failure = assertFailsWith<IllegalStateException> { history[1] }
        assertTrue(failure.message.orEmpty().contains("CRC32"))
        assertTrue(history.error.orEmpty().contains("CRC32"))
        assertFailsWith<IllegalStateException> { history.close() }
        assertEquals(0, history.retainedCount)
    }

    @Test
    fun `종료는 대기 원문을 writer에서 배출하고 시간 상한 초과는 오류로 남긴다`() {
        val writerThread = AtomicReference<String?>()
        val archive =
            HttpPerformanceIdentityArchive(directory.resolve("thread.bin"), limits(pending = 2), { path ->
                writerThread.set(Thread.currentThread().name)
                FileChannel.open(path, CREATE_NEW, READ, WRITE)
            })
        val history = HttpPerformanceCollectorHistory(2, archive)
        history.record(accepted(1))
        history.record(accepted(2))
        history.markCompleted(1)
        history.close()
        assertEquals("http-performance-identity-writer", writerThread.get())
        assertNull(archive.error)
        assertTrue(Files.size(archive.evidencePath) > 0)
        assertEquals(0, history.retainedCount)
        assertEquals(0, archive.queuedCount)
        assertEquals(0, archive.acknowledgedCount)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked =
            HttpPerformanceIdentityArchive(directory.resolve("blocked.bin"), limits(pending = 1, closeMs = 20), { path ->
                entered.countDown()
                release.await()
                FileChannel.open(path, CREATE_NEW, READ, WRITE)
            })
        val blockedHistory = HttpPerformanceCollectorHistory(1, blocked)
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            blockedHistory.record(accepted(1))
            val start = System.nanoTime()
            assertFailsWith<IllegalStateException> { blockedHistory.close() }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000)
            assertTrue(blocked.error.orEmpty().contains("종료 시간 초과"))
            assertEquals(0, blockedHistory.retainedCount)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `idle background는 새 업무 callback 없이도 저장 확인을 배출하고 완료 원문을 해제한다`() {
        val markers = mutableListOf<HttpPerformanceCompletion>()
        val collector =
            HttpPerformanceCollector(
                "collector-test",
                0,
                markers::add,
                SystemNanoClock.INSTANCE,
                HTTP_PERFORMANCE_CALLBACK_CAPACITY,
                detailedTracing = false,
                identityEvidencePath = directory.resolve("idle-release.bin"),
            )
        val (state, observations) = completedOrder()
        val completedState = state.snapshot()
        val decorated = collector.decorate(DevelopmentFundingAdapter(state, onFatal = {}))
        observations.forEach(collector::observe)
        assertEquals(1, collector.retainedIdentityCount)
        await {
            decorated.doBackgroundWork(0)
            collector.retainedIdentityCount == 0
        }
        assertEquals(1, markers.size)
        assertNull(collector.error)
        assertEquals(completedState, state.snapshot())
        assertFalse(state.isBlocked)
        collector.close()
    }

    @Test
    fun `파일 관찰 실패와 pending overflow는 완료한 금융 상태를 변경하지 않는다`() {
        val (state, observations) = completedOrder()
        val completedState = state.snapshot()
        val failing = collector(directory.resolve("not-created/identity.bin"))
        await { failing.error != null }
        observations.forEach(failing::observe)
        assertNotNull(failing.error)
        assertEquals(completedState, state.snapshot())
        assertFalse(state.isBlocked)
        failing.close()

        val limited =
            HttpPerformanceCollector(
                "collector-test",
                0,
                {},
                SystemNanoClock.INSTANCE,
                HTTP_PERFORMANCE_CALLBACK_CAPACITY,
                detailedTracing = false,
                identityEvidencePath = directory.resolve("collector-pending.bin"),
                identityArchiveLimits = limits(pending = 1),
            )
        limited.observe(observations.first())
        limited.observe(observations.first().copy(admissionResult = accepted(2)))
        assertTrue(limited.error.orEmpty().contains("pending 원문 초과"))
        assertEquals(completedState, state.snapshot())
        assertFalse(state.isBlocked)
        limited.close()
    }

    @Test
    fun `idle background는 새 업무 callback 없이도 writer 오류를 collector에 반영한다`() {
        val collector = collector(directory.resolve("absent/identity.bin"))
        val service = DevelopmentFundingAdapter(tradingState(), onFatal = {})
        val decorated = collector.decorate(service)
        await { collector.error != null }
        decorated.doBackgroundWork(0)
        assertNotNull(collector.error)
        assertTrue(service.telemetryOperational)
        collector.close()
    }

    private fun completedOrder(): Pair<TradingState, List<FundingObservation>> {
        val state = tradingState()
        val now = Instant.parse("2026-10-29T00:00:00Z")
        val market = DevelopmentMarketContext.BTC_USDT
        state.prepareDevelopmentBalance(OpeningBalance("OPEN", UserId("USER"), AssetId("USDT"), Amount(1_000_000)), "OPEN-TX", now)
        val input =
            OrderAdmissionInput(
                market.market.marketId,
                OrderId("ORDER"),
                UserId("USER"),
                Side.BUY,
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(10_000),
                Quantity(100_000_000),
            )
        val result =
            state.admit(
                "REQUEST",
                input,
                market.market,
                market.feePolicySnapshot,
                "RESERVE",
                now,
            ) as OrderAdmissionState.Result.Accepted
        val sequence = result.record.sequence
        val identity = AdmissionResultPayload.Accepted("USER", "REQUEST", "BTC-USDT", "ORDER", sequence)
        val completion = state.executePending(sequence, now)
        return state to
            listOf(
                FundingObservation(128, null, null, admissionResult = identity, appliedRole = "LEADER"),
                FundingObservation(256, null, null, executionSequence = sequence, appliedRole = "LEADER", executionCompletion = completion),
            )
    }

    private fun archive(
        name: String,
        limits: HttpPerformanceIdentityArchiveLimits,
    ) = HttpPerformanceIdentityArchive(directory.resolve("$name.bin"), limits)

    private fun collector(path: Path) =
        HttpPerformanceCollector(
            "collector-test",
            0,
            {},
            SystemNanoClock.INSTANCE,
            HTTP_PERFORMANCE_CALLBACK_CAPACITY,
            detailedTracing = false,
            identityEvidencePath = path,
        )

    private fun limits(
        pending: Int,
        queue: Int = pending,
        recordBytes: Int = 1024,
        fileBytes: Long = 1024 * 1024,
        closeMs: Long = 1000,
    ) = HttpPerformanceIdentityArchiveLimits(pending, queue, recordBytes, fileBytes, closeMs)

    private fun accepted(sequence: Long) = AdmissionResultPayload.Accepted("USER", "R-$sequence", "BTC-USDT", "O-$sequence", sequence)

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Accepted writer 상태 대기 시간 초과" }
            Thread.sleep(1)
        }
    }
}
