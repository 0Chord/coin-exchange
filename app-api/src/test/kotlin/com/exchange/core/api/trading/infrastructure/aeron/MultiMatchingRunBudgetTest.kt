package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MultiMatchingRunBudgetTest {
    private val gib = 1024L * 1024 * 1024

    @TempDir
    lateinit var directory: Path

    @Test
    fun `기존 한도의 9노드는 Docker 실제9GiB와 호스트 여유20GiB부터 허용한다`() {
        MultiMatchingRunBudget.requireCapacity(9 * gib, 20 * gib)
    }

    @Test
    fun `기존 8GiB 환경과 최소값 미만은 작은 프로필로 우회하지 않는다`() {
        for (memory in listOf(0L, 8 * gib, 9 * gib - 1)) {
            assertFailsWith<IllegalStateException> { MultiMatchingRunBudget.requireCapacity(memory, 20 * gib) }
        }
    }

    @Test
    fun `메모리가 충분해도 기록 디스크 여유가 부족하면 거절한다`() {
        for (disk in listOf(0L, 20 * gib - 1)) {
            assertFailsWith<IllegalStateException> { MultiMatchingRunBudget.requireCapacity(10 * gib, disk) }
        }
    }

    @Test
    fun `진단 합계는 정상 파일과 아직 존재하는 임시 파일도 모두 센다`() {
        Files.write(directory.resolve("result.json"), ByteArray(3))
        Files.write(directory.resolve(".state-present.tmp"), ByteArray(7))
        val nested = Files.createDirectory(directory.resolve("control"))
        Files.write(nested.resolve("state-btc0.json"), ByteArray(5))

        assertEquals(15L, MultiMatchingRunBudget.diagnosticBytes(directory))
    }

    @Test
    fun `진단 합계는 동시 atomic 게시 중 회수된 임시 파일 때문에 실패하지 않는다`() {
        Files.write(directory.resolve("stable.json"), ByteArray(3))
        val published = directory.resolve("state-btc0.json")
        Files.write(published, ByteArray(512))
        val writing = AtomicBoolean(true)
        val publishedCount = AtomicLong()
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val writer =
            executor.submit {
                while (writing.get()) {
                    val temporary = directory.resolve(".state-btc0-${publishedCount.get()}.tmp")
                    Files.write(temporary, ByteArray(512))
                    Files.move(temporary, published, ATOMIC_MOVE, REPLACE_EXISTING)
                    publishedCount.incrementAndGet()
                    started.countDown()
                }
            }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(200) {
                assertTrue(MultiMatchingRunBudget.diagnosticBytes(directory) >= 515L)
            }
        } finally {
            writing.set(false)
            try {
                writer.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(publishedCount.get() > 0)
        assertEquals(515L, MultiMatchingRunBudget.diagnosticBytes(directory))
    }

    @Test
    fun `진단 측정의 root가 없으면 임시 파일 회수로 무시하지 않는다`() {
        assertFailsWith<NoSuchFileException> {
            MultiMatchingRunBudget.diagnosticBytes(directory.resolve("missing"))
        }
    }
}
