package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpPerformanceCollectorDiskIndexTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `완료 수가 cache보다 커도 오래된 위치와 중복 완료를 정확히 찾는다`() {
        HttpPerformanceCollectorDiskIndex(directory.resolve("index")).use { index ->
            repeat(4096) { value ->
                val sequence = Long.MAX_VALUE - value
                assertTrue(index.markCompleted(sequence))
                index.recordOffset(sequence, value * 71L)
                assertTrue(index.retainedCount <= HttpPerformanceCollectorDiskIndex.CACHE_ENTRIES)
            }
            repeat(4096) { value ->
                val sequence = Long.MAX_VALUE - value
                assertEquals(value * 71L, index.offset(sequence))
                assertTrue(index.completed(sequence))
                assertFalse(index.markCompleted(sequence))
            }
            assertEquals(-1, index.offset(777L))
            assertFalse(index.completed(777L))
            assertFailsWith<IllegalStateException> { index.recordOffset(Long.MAX_VALUE, 222L) }
        }
    }

    @Test
    fun `위치가 먼저 저장되어도 완료 표시가 그 위치를 보존한다`() {
        val index = HttpPerformanceCollectorDiskIndex(directory.resolve("index"))
        index.recordOffset(0, 0)
        assertFalse(index.completed(0))
        assertTrue(index.markCompleted(0))
        assertEquals(0, index.offset(0))
        index.close()
        assertFailsWith<IllegalStateException> { index.completed(0) }
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { HttpPerformanceCollectorDiskIndex(directory.resolve("index")) }
    }
}
