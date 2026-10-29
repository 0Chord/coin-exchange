package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 소형 spool 계약. 실제 부하·HTTP·전체 금융 비교는 기존 별도 검사와 구분한다. */
class HttpPerformanceCompletionProofStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `메모리와 디스크가 ID 순서와 무관하게 proof의 모든 바이트를 반환한다`() {
        val first = byteArrayOf(0, 1, -1, 0, 127, -128)
        val second = ByteArray(4096) { (it % 251).toByte() }
        for (disk in listOf(false, true)) {
            val store =
                if (disk) {
                    HttpPerformanceCompletionProofStore.disk(directory, 2, 4096)
                } else {
                    HttpPerformanceCompletionProofStore(2, 4096)
                }
            store.use {
                it.append("EXECUTE:가", first)
                it.append("EXECUTE:second", second)
                assertEquals(2, it.size)
                assertContentEquals(second, it.take("EXECUTE:second"))
                assertContentEquals(first, it.take("EXECUTE:가"))
                assertEquals(0, it.size)
                it.verifyComplete()
            }
            if (disk) {
                assertTrue(hasSpool())
                clearFixtureSpools()
            } else {
                assertFalse(hasSpool())
            }
        }
    }

    @Test
    fun `중복 ID 누락 ID 미매칭 proof와 크기 행 상한을 성공으로 처리하지 않는다`() {
        HttpPerformanceCompletionProofStore(2, 8).use { store ->
            store.append("same", byteArrayOf(1))
            val failure = assertFailsWith<IllegalStateException> { store.append("same", byteArrayOf(2)) }
            assertTrue(failure.message.orEmpty().contains("executionId 중복"))
        }
        HttpPerformanceCompletionProofStore(2, 8).use { store ->
            assertFailsWith<IllegalStateException> { store.take("absent") }
        }
        HttpPerformanceCompletionProofStore(2, 8).use { store ->
            store.append("unmatched", byteArrayOf(1))
            assertFailsWith<IllegalStateException> { store.verifyComplete() }
        }
        HttpPerformanceCompletionProofStore.disk(directory, 1, 8).use { store ->
            store.append("first", ByteArray(8))
            assertFailsWith<IllegalStateException> { store.append("second", ByteArray(8)) }
        }
        HttpPerformanceCompletionProofStore.disk(directory, 1, 8).use { store ->
            assertFailsWith<IllegalStateException> { store.append("too-large", ByteArray(9)) }
        }
        assertTrue(hasSpool())
        clearFixtureSpools()
    }

    @Test
    fun `절단 frame ID payload SHA와 EOF 뒤 추가 바이트를 모두 거절한다`() {
        val changes: List<Pair<String, (ByteArray) -> ByteArray>> =
            listOf<Pair<String, (ByteArray) -> ByteArray>>(
                "truncation" to { it.copyOf(it.size - 1) },
                "magic" to { it.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() } },
                "proof length" to { it.copyOf().apply { ByteBuffer.wrap(this).putInt(8, Int.MAX_VALUE) } },
                "execution ID" to { it.copyOf().apply { this[12] = 'b'.code.toByte() } },
                "payload" to { it.copyOf().apply { this[13] = (this[13].toInt() xor 1).toByte() } },
                "SHA" to { it.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() } },
                "trailing byte" to { it + byteArrayOf(0) },
            )
        for ((name, change) in changes) {
            HttpPerformanceCompletionProofStore.disk(directory, 1, 16).use { store ->
                store.append("a", byteArrayOf(1, 2, 3, 4))
                val file = spool()
                Files.write(file, change(Files.readAllBytes(file))) // The fixture is only 49 bytes.
                assertFailsWith<IllegalStateException>(name) { store.take("a") }
            }
            assertTrue(hasSpool(), name)
            clearFixtureSpools()
        }
    }

    @Test
    fun `이미 exact 비교한 frame을 나중에 변조해도 최종 전체 파일 SHA가 실패한다`() {
        HttpPerformanceCompletionProofStore.disk(directory, 2, 16).use { store ->
            store.append("a", byteArrayOf(1, 2))
            store.append("b", byteArrayOf(3, 4))
            assertContentEquals(byteArrayOf(1, 2), store.take("a"))
            // Equal file length, and the still-unread b frame remains valid.
            FileChannel.open(spool(), WRITE).use { channel ->
                assertEquals(1, channel.write(ByteBuffer.wrap(byteArrayOf(9)), 13L))
            }
            assertContentEquals(byteArrayOf(3, 4), store.take("b"))
            assertEquals(0, store.size)
            val failure = assertFailsWith<IllegalStateException> { store.verifyComplete() }
            assertTrue(failure.message.orEmpty().contains("전체 파일 SHA"))
        }
        assertTrue(hasSpool())
        clearFixtureSpools()
    }

    @Test
    fun `원래 실패를 보존하고 자기 파일이 아닌 교체 파일은 삭제하지 않는다`() {
        val sentinel = directory.resolve("unrelated-checkpoint.json")
        Files.writeString(sentinel, "keep")
        val store = HttpPerformanceCompletionProofStore.disk(directory, 1, 16)
        val file = spool()
        val failure =
            assertFailsWith<IllegalStateException> {
                store.use {
                    it.append("a", byteArrayOf(1))
                    // Keep the original channel open so the replacement cannot reuse its inode.
                    Files.delete(file)
                    Files.writeString(file, "replacement")
                    closeChannel(it)
                    error("original financial failure")
                }
            }
        assertEquals("original financial failure", failure.message)
        assertEquals(1, failure.suppressed.size)
        assertTrue(failure.suppressed.single() is ClosedChannelException)
        assertEquals("replacement", Files.readString(file))
        assertEquals("keep", Files.readString(sentinel))
    }

    @Test
    fun `정리 오류만 있어도 성공으로 반환하지 않는다`() {
        val store = HttpPerformanceCompletionProofStore.disk(directory, 1, 16)
        val file = spool()
        Files.delete(file)
        Files.writeString(file, "replacement")
        closeChannel(store)
        assertFailsWith<ClosedChannelException> { store.close() }
        assertEquals("replacement", Files.readString(file))
    }

    @Test
    fun `없는 디렉터리의 오픈 실패를 메모리 fallback으로 바꾸지 않는다`() {
        assertFailsWith<java.io.IOException> {
            HttpPerformanceCompletionProofStore.disk(directory.resolve("missing"), 1, 16)
        }
        assertFalse(hasSpool())
    }

    @Test
    fun `payload와 디스크 SHA를 함께 바꾸고 복원해도 원래 proof 대신 소비하지 않는다`() {
        HttpPerformanceCompletionProofStore.disk(directory, 1, 16).use { store ->
            store.append("a", byteArrayOf(1, 2, 3, 4))
            val file = spool()
            val original = Files.readAllBytes(file)
            val replacementProof = byteArrayOf(5, 6, 7, 8)
            val replacement = original.copyOf()
            replacementProof.copyInto(replacement, 13)
            MessageDigest.getInstance("SHA-256").digest(replacementProof).copyInto(replacement, 17)
            Files.write(file, replacement)
            try {
                assertFailsWith<IllegalStateException> { store.take("a") }
            } finally {
                Files.write(file, original)
            }
            assertFailsWith<IllegalStateException> { store.verifyComplete() }
        }
    }

    @Test
    fun `완료 또는 실패한 저장소의 후속 쓰기 읽기와 검증은 다시 성공하지 않는다`() {
        for (disk in listOf(false, true)) {
            val store = if (disk) HttpPerformanceCompletionProofStore.disk(directory, 2, 8) else HttpPerformanceCompletionProofStore(2, 8)
            store.use {
                it.verifyComplete()
                assertFailsWith<IllegalStateException> { it.append("late", byteArrayOf(1)) }
                assertFailsWith<IllegalStateException> { it.take("late") }
                assertFailsWith<IllegalStateException> { it.verifyComplete() }
            }
            assertFailsWith<IllegalStateException> { store.append("closed", byteArrayOf(1)) }
        }
    }

    private fun closeChannel(store: HttpPerformanceCompletionProofStore) {
        val diskField = store.javaClass.getDeclaredField("disk").apply { isAccessible = true }
        val disk = requireNotNull(diskField.get(store))
        val channelField = disk.javaClass.getDeclaredField("channel").apply { isAccessible = true }
        (channelField.get(disk) as FileChannel).close()
    }

    /** Only this test fixture owns the TempDir; the actual checker retains evidence and never unlinks paths. */
    private fun clearFixtureSpools() {
        Files.newDirectoryStream(directory, ".http-performance-completion-proofs-*.bin").use { paths ->
            paths.forEach { Files.delete(it) }
        }
    }

    private fun spool(): Path = Files.newDirectoryStream(directory, ".http-performance-completion-proofs-*.bin").use { it.single() }

    private fun hasSpool(): Boolean =
        Files.newDirectoryStream(directory, ".http-performance-completion-proofs-*.bin").use { it.iterator().hasNext() }
}
