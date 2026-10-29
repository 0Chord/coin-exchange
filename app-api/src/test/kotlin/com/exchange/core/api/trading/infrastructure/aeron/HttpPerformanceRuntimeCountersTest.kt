package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.AeronCounters
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.BitUtil
import org.agrona.BufferUtil
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.concurrent.status.CountersManager
import org.agrona.concurrent.status.CountersReader
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpPerformanceRuntimeCountersTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `관심 카운터의 정의와 숫자를 분리해 같은 JVM의 경과 시각으로 보존한다`() {
        val manager = manager()
        val commit = counter(manager, "Cluster commit", AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID, 41, 128)
        counter(manager, "NAKs sent", AeronCounters.DRIVER_SYSTEM_COUNTER_TYPE_ID, 5, 3)
        counter(manager, "unrelated", 9999, 42, 99)
        var now = 1_000_000_000L
        val buffer = HttpPerformanceRuntimeCounterBuffer(manager, nanoTime = { now })

        now += 250_000_000
        buffer.capture()
        val first = buffer.snapshot()
        manager.setCounterValue(commit, 512)
        now += 250_000_000
        buffer.capture()
        val second = buffer.snapshot()

        assertEquals(listOf("Cluster commit", "NAKs sent"), first.definitions.map { it.label })
        assertEquals(
            listOf(128L, 3L),
            first.frames
                .single()
                .values
                .map { it.value },
        )
        assertEquals(
            listOf(512L, 3L),
            second.frames
                .last()
                .values
                .map { it.value },
        )
        assertEquals(250_000_000L, first.frames.single().elapsedNs)
        assertEquals(1_000_000_000L, first.originNs)
        assertEquals(1_250_000_000L, requireNotNull(first.originNs) + first.frames.single().elapsedNs)
        assertEquals(500_000_000L, second.frames.last().elapsedNs)
        assertEquals(first.originNs, second.originNs)
        assertEquals(0L, second.frames.last().readDurationNs)
        assertEquals(1, first.frames.size)
        assertFailsWith<UnsupportedOperationException> { (first.frames as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (first.frames.single().values as MutableList).clear() }
        assertTrue(second.errors.isEmpty())
    }

    @Test
    fun `회수된 counter ID가 재사용되어도 옛 정의에 새 값을 섞지 않는다`() {
        val manager = manager()
        val original = counter(manager, "old recording", AeronCounters.ARCHIVE_RECORDING_POSITION_TYPE_ID, 10, 128)
        var now = 0L
        val buffer = HttpPerformanceRuntimeCounterBuffer(manager, nanoTime = { now })
        buffer.capture()

        manager.free(original)
        val replacement = counter(manager, "new recording", AeronCounters.ARCHIVE_RECORDING_POSITION_TYPE_ID, 20, 1024)
        assertEquals(original, replacement)
        now = 250_000_000
        buffer.capture()
        now = 1_000_000_000
        buffer.capture()

        val observed = buffer.snapshot()
        assertEquals(listOf(10L, 20L), observed.definitions.map { it.registrationId })
        assertEquals(listOf(HttpPerformanceRuntimeCounterValue(0, 128)), observed.frames[0].values)
        assertTrue(observed.frames[1].values.isEmpty())
        assertEquals(listOf(HttpPerformanceRuntimeCounterValue(1, 1024)), observed.frames[2].values)
        assertEquals(1L, observed.skippedCounterReads)
    }

    @Test
    fun `열거 뒤 callback 전에 ID가 재사용되면 현재 메타데이터부터 다시 읽는다`() {
        val manager = manager()
        val original = counter(manager, "old recording", AeronCounters.ARCHIVE_RECORDING_POSITION_TYPE_ID, 10, 128)
        val reader =
            object : CountersReader(manager.metaDataBuffer(), manager.valuesBuffer()) {
                private var reused = false

                override fun forEach(metadata: CountersReader.MetaData) {
                    super.forEach(
                        CountersReader.MetaData { id, type, key, label ->
                            if (id == original && !reused) {
                                reused = true
                                manager.free(id)
                                assertEquals(id, counter(manager, "new recording", type, 20, 1024))
                            }
                            metadata.accept(id, type, key, label)
                        },
                    )
                }
            }
        val buffer = HttpPerformanceRuntimeCounterBuffer(reader)

        buffer.capture()

        val observed = buffer.snapshot()
        assertEquals(listOf("new recording"), observed.definitions.map { it.label })
        assertEquals(listOf(20L), observed.definitions.map { it.registrationId })
        assertEquals(listOf(HttpPerformanceRuntimeCounterValue(0, 1024)), observed.frames.single().values)
        assertEquals(0L, observed.skippedCounterReads)
    }

    @Test
    fun `정의 구성 중 ID가 재사용되면 섞인 메타데이터를 버리고 다음 갱신에서 다시 읽는다`() {
        val manager = manager()
        val original = counter(manager, "old recording", AeronCounters.ARCHIVE_RECORDING_POSITION_TYPE_ID, 10, 128)
        val reader =
            object : CountersReader(manager.metaDataBuffer(), manager.valuesBuffer()) {
                private var reused = false

                override fun getCounterLabel(id: Int): String {
                    val label = super.getCounterLabel(id)
                    if (id == original && !reused) {
                        reused = true
                        manager.free(id)
                        assertEquals(id, counter(manager, "new recording", AeronCounters.ARCHIVE_RECORDING_POSITION_TYPE_ID, 20, 1024))
                    }
                    return label
                }
            }
        val buffer = HttpPerformanceRuntimeCounterBuffer(reader)

        buffer.capture()
        val raced = buffer.snapshot()
        buffer.capture(forceMetadataRefresh = true)

        assertTrue(raced.definitions.isEmpty())
        assertTrue(
            raced.frames
                .single()
                .values
                .isEmpty(),
        )
        assertEquals(1L, raced.skippedCounterReads)
        val recovered = buffer.snapshot()
        assertEquals(listOf("new recording"), recovered.definitions.map { it.label })
        assertEquals(listOf(20L), recovered.definitions.map { it.registrationId })
        assertEquals(listOf(HttpPerformanceRuntimeCounterValue(0, 1024)), recovered.frames.last().values)
        assertEquals(1L, recovered.skippedCounterReads)
    }

    @Test
    fun `frame 한도를 넘기면 기존 근거를 보존하고 초과와 관찰 오류를 드러낸다`() {
        val buffer = HttpPerformanceRuntimeCounterBuffer(manager(), capacity = 2)
        repeat(4) { buffer.capture() }
        buffer.recordFailure(IllegalStateException("counter read failed"))

        val observed = buffer.snapshot()
        assertEquals(2, observed.frames.size)
        assertEquals(2L, observed.overflowCount)
        assertEquals(listOf("IllegalStateException: counter read failed"), observed.errors)
        assertFailsWith<IllegalArgumentException> { HttpPerformanceRuntimeCounterBuffer(manager(), capacity = 0) }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceRuntimeCounterBuffer(manager(), capacity = 4097) }
    }

    @Test
    fun `체크포인트의 최신 capture 실패는 호출자를 중단하지 않고 기존 frame과 오류를 반환한다`() {
        val manager = manager()
        counter(manager, "Cluster commit", AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID, 41, 128)
        var unavailable = false
        val reader =
            object : CountersReader(manager.metaDataBuffer(), manager.valuesBuffer()) {
                override fun getCounterValue(counterId: Int): Long {
                    if (unavailable) throw IllegalStateException("fresh counter read failed")
                    return super.getCounterValue(counterId)
                }
            }
        val buffer = HttpPerformanceRuntimeCounterBuffer(reader)
        buffer.capture()
        val releases = AtomicInteger()
        val sampler = HttpPerformanceRuntimeCounters(buffer) { releases.incrementAndGet() }
        try {
            unavailable = true

            val failed = sampler.snapshot()

            assertEquals(1, failed.frames.size)
            assertEquals(
                listOf(128L),
                failed.frames
                    .single()
                    .values
                    .map { it.value },
            )
            assertEquals(listOf("IllegalStateException: fresh counter read failed"), failed.errors)
            assertEquals(0, releases.get(), "관측 실패로 live mapping이나 소유 노드를 종료하지 않는다")
            unavailable = false
            val recovered = sampler.snapshot()
            assertEquals(2, recovered.frames.size)
            assertEquals(failed.errors, recovered.errors, "재조회 성공이 앞선 계측 장애를 숨기지 않는다")
        } finally {
            unavailable = false
            sampler.close()
        }
        assertEquals(1, releases.get())
    }

    @Test
    fun `최신 snapshot 읽기가 끝나기 전에 close는 mapping을 해제하지 않고 종료 뒤에는 재조회하지 않는다`() {
        val manager = manager()
        counter(manager, "Cluster commit", AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID, 41, 128)
        val reading = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val closeCalled = CountDownLatch(1)
        val snapshotDone = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        val released = CountDownLatch(1)
        val releaseCount = AtomicInteger()
        val reads = AtomicInteger()
        val mappingClosed = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val observed = AtomicReference<HttpPerformanceRuntimeCounterSnapshot?>()
        val reader =
            object : CountersReader(manager.metaDataBuffer(), manager.valuesBuffer()) {
                override fun getCounterValue(counterId: Int): Long {
                    reads.incrementAndGet()
                    reading.countDown()
                    check(allowRead.await(5, TimeUnit.SECONDS)) { "snapshot read gate timed out" }
                    check(!mappingClosed.get()) { "mapping was released during snapshot read" }
                    return super.getCounterValue(counterId)
                }
            }
        val sampler =
            HttpPerformanceRuntimeCounters(HttpPerformanceRuntimeCounterBuffer(reader)) {
                mappingClosed.set(true)
                releaseCount.incrementAndGet()
                released.countDown()
            }
        val snapshotThread =
            Thread {
                try {
                    observed.set(sampler.snapshot())
                } catch (
                    error: Throwable,
                ) {
                    failure.compareAndSet(null, error)
                } finally {
                    snapshotDone.countDown()
                }
            }.apply { isDaemon = true }
        val closeThread =
            Thread {
                closeCalled.countDown()
                try {
                    sampler.close()
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    closeDone.countDown()
                }
            }.apply { isDaemon = true }
        snapshotThread.start()
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            closeThread.start()
            assertTrue(closeCalled.await(5, TimeUnit.SECONDS))
            assertFalse(released.await(100, TimeUnit.MILLISECONDS), "진행 중인 읽기의 소유권을 close가 지켜야 한다")
        } finally {
            allowRead.countDown()
        }
        assertTrue(snapshotDone.await(5, TimeUnit.SECONDS))
        assertTrue(closeDone.await(5, TimeUnit.SECONDS))
        assertNull(failure.get(), failure.get()?.stackTraceToString())
        assertTrue(mappingClosed.get())
        val before = reads.get()
        val afterClose = sampler.snapshot()
        assertEquals(before, reads.get(), "종료 뒤 snapshot은 해제한 CnC를 다시 읽지 않는다")
        assertEquals(observed.get()?.frames, afterClose.frames)
        assertTrue(afterClose.errors.isEmpty())
        sampler.close()
        assertEquals(1, releaseCount.get())
    }

    @Test
    fun `실제 Driver CnC를 읽는 별도 sampler는 처음과 종료 시점의 frame을 남긴다`() {
        MediaDriver
            .launch(
                MediaDriver
                    .Context()
                    .aeronDirectoryName(temporary.resolve("driver").toString())
                    .dirDeleteOnStart(false)
                    .dirDeleteOnShutdown(true)
                    .threadingMode(ThreadingMode.SHARED)
                    .sharedIdleStrategy(TradingAeronRuntime.idleStrategy()),
            ).use { driver ->
                val sampler = HttpPerformanceRuntimeCounters.start(driver.aeronDirectoryName())
                assertTrue(sampler.snapshot().frames.isNotEmpty())
                sampler.close()
                sampler.close()

                val observed = sampler.snapshot()
                assertTrue(observed.frames.size >= 2)
                assertTrue(observed.definitions.any { it.typeId == AeronCounters.DRIVER_SYSTEM_COUNTER_TYPE_ID && it.label == "NAKs sent" })
                assertTrue(observed.frames.all { it.values.isNotEmpty() })
                assertTrue(observed.frames.zipWithNext().all { (first, second) -> second.elapsedNs >= first.elapsedNs })
                assertEquals(0L, observed.overflowCount)
                assertTrue(observed.errors.isEmpty(), observed.errors.toString())
            }
    }

    private fun manager(): CountersManager =
        CountersManager(
            UnsafeBuffer(BufferUtil.allocateDirectAligned(CountersReader.METADATA_LENGTH * 16, BitUtil.CACHE_LINE_LENGTH)),
            UnsafeBuffer(BufferUtil.allocateDirectAligned(CountersReader.COUNTER_LENGTH * 16, BitUtil.CACHE_LINE_LENGTH)),
        )

    private fun counter(
        manager: CountersManager,
        label: String,
        type: Int,
        registration: Long,
        value: Long,
    ): Int =
        manager.allocate(label, type).also { id ->
            manager.setCounterRegistrationId(id, registration)
            manager.setCounterValue(id, value)
        }
}
