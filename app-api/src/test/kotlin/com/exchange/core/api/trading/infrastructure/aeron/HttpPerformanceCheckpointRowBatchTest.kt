package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real small checkpoint files and parser; no domain workload or financial-success fixture is replaced. */
@Timeout(10)
class HttpPerformanceCheckpointRowBatchTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private var fixtureNumber = 0
    private val section = "/applied/state/requests"

    @Test
    fun `0 1 31 32 33 65행을전량순서대로전달하고EOF SHA후에만End를반환한다`() {
        for (count in listOf(0, 1, 31, 32, 33, 65)) {
            val reference = references(listOf(count, count, count)).getValue(0)
            val seen = mutableListOf<Int>()
            val sizes = mutableListOf<Int>()
            ReferenceRowCursor(reference, section, AtomicBoolean(false)).use { cursor ->
                while (true) {
                    when (val message = cursor.next()) {
                        is RowMessage.Batch -> {
                            try {
                                sizes.add(message.rows.size)
                                assertTrue(message.rows.size in 1..HTTP_PERFORMANCE_CHECKPOINT_ROW_BATCH_SIZE)
                                for (row in message.rows) {
                                    assertEquals("$section/${seen.size}", row.pointer)
                                    assertEquals(seen.size, row.value.path("index").asInt())
                                    seen.add(row.value.path("index").asInt())
                                }
                            } finally {
                                message.consumed.countDown()
                            }
                        }

                        is RowMessage.End -> {
                            assertEquals(reference, message.scan.reference)
                            assertEquals(count.toLong(), message.scan.rowCounts[section])
                            break
                        }

                        is RowMessage.Failure -> {
                            throw AssertionError("unexpected reader failure", message.error)
                        }
                    }
                }
            }
            assertEquals((0 until count).toList(), seen)
            assertEquals((0 until count).chunked(32).map { it.size }, sizes)
            assertTrue(Files.isRegularFile(reference.file))
        }
    }

    @Test
    fun `batch ACK전에는다음32행을발행하지않으며ACK뒤정확히다음batch가나온다`() {
        val reference = references().getValue(0)
        val cancelled = AtomicBoolean(false)
        val cursor = ReferenceRowCursor(reference, section, cancelled)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = cursor.next() as RowMessage.Batch
            assertEquals((0 until 32).toList(), first.rows.map { it.value.path("index").asInt() })
            val next = executor.submit<RowMessage> { cursor.next() }
            assertFailsWith<TimeoutException> { next.get(100, TimeUnit.MILLISECONDS) }
            first.consumed.countDown()
            val second = next.get(5, TimeUnit.SECONDS) as RowMessage.Batch
            assertEquals((32 until 64).toList(), second.rows.map { it.value.path("index").asInt() })
            second.consumed.countDown()
            val last = cursor.next() as RowMessage.Batch
            assertEquals(listOf(64), last.rows.map { it.value.path("index").asInt() })
            last.consumed.countDown()
            assertTrue(cursor.next() is RowMessage.End)
        } finally {
            cancelled.set(true)
            try {
                cursor.close()
            } finally {
                executor.shutdownNow()
                val interrupted = Thread.interrupted()
                try {
                    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw failure
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
        assertTrue(Files.isRegularFile(reference.file))
    }

    @Test
    fun `batch경계뒤의값차이와nested배열순서차이를exact비교에서놓치지않는다`() {
        for (change in listOf("value", "array-order")) {
            val refs =
                references { member, index, row ->
                    if (member != 2) {
                        row
                    } else {
                        when {
                            change == "value" && index == 32 -> {
                                row + ("extra" to "different")
                            }

                            change == "array-order" && index == 64 -> {
                                row +
                                    ("nested" to mapOf("z" to listOf(index + 1, index), "a" to index))
                            }

                            else -> {
                                row
                            }
                        }
                    }
                }
            val failure = assertFailsWith<IllegalStateException> { compareCheckpointSection(refs.values.toList(), section) { _, _ -> } }
            assertTrue(failure.message.orEmpty().contains("전체 업무 상태가 다릅니다"))
            assertTrue(refs.values.all { Files.isRegularFile(it.file) })
        }
    }

    @Test
    fun `객체필드순서는무시하되65행callback순서와전체원본은유지한다`() {
        val refs =
            references { member, index, row ->
                if (member ==
                    1
                ) {
                    linkedMapOf("nested" to linkedMapOf("a" to index, "z" to listOf(index, index + 1)), "index" to index)
                } else {
                    row
                }
            }
        val compared = mutableListOf<Int>()
        compareCheckpointSection(refs.values.toList(), section) { pointer, row ->
            assertEquals("$section/${compared.size}", pointer)
            compared.add(row.path("index").asInt())
        }
        assertEquals((0 until 65).toList(), compared)
        assertTrue(refs.values.all { Files.isRegularFile(it.file) })
    }

    @Test
    fun `SHA실패는앞32행ACK뒤에도원인을보존하고마지막partial행이나End로숨기지않는다`() {
        val reference = references(listOf(33, 33, 33)).getValue(0).copy(sha256 = "0".repeat(64))
        ReferenceRowCursor(reference, section, AtomicBoolean(false)).use { cursor ->
            val first = cursor.next() as RowMessage.Batch
            assertEquals(32, first.rows.size)
            first.consumed.countDown()
            val failure = cursor.next() as RowMessage.Failure
            assertTrue(
                failure.error.message
                    .orEmpty()
                    .contains("SHA"),
            )
        }
        assertTrue(Files.isRegularFile(reference.file))
    }

    @Test
    fun `원문구문오류는correct SHA여도worker원인으로전파된다`() {
        val original = references(listOf(33, 33, 33)).getValue(0)
        val raw = Files.readString(original.file).dropLast(1)
        Files.writeString(original.file, raw)
        val reference = original.copy(bytes = Files.size(original.file), sha256 = sha(original.file))
        ReferenceRowCursor(reference, section, AtomicBoolean(false)).use { cursor ->
            val first = cursor.next() as RowMessage.Batch
            first.consumed.countDown()
            val failure = cursor.next() as RowMessage.Failure
            assertTrue(
                failure.error.message
                    .orEmpty()
                    .isNotEmpty(),
            )
            assertTrue(failure.error.stackTrace.any { it.className.startsWith("tools.jackson.") })
        }
        assertEquals(raw, Files.readString(reference.file))
    }

    @Test
    fun `ACK대기중cancel과close는worker를끝내며부분batch를성공으로만들지않는다`() {
        val reference = references().getValue(0)
        val cancelled = AtomicBoolean(false)
        val cursor = ReferenceRowCursor(reference, section, cancelled)
        val first = cursor.next() as RowMessage.Batch
        assertEquals(32, first.rows.size)
        cancelled.set(true)
        cursor.close()
        assertFailsWith<CancellationException> { cursor.next() }
        assertTrue(Files.isRegularFile(reference.file))
    }

    @Test
    fun `close중interrupt는worker를종료한뒤실패로전파하고flag를보존한다`() {
        val reference = references().getValue(0)
        val existing = Thread.getAllStackTraces().keys.toSet()
        val cursor = ReferenceRowCursor(reference, section, AtomicBoolean(false))
        val first = cursor.next() as RowMessage.Batch
        assertEquals(32, first.rows.size)
        val previous = Thread.interrupted()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedException> { cursor.close() }
            assertTrue(Thread.currentThread().isInterrupted)
            assertNoWorkersSince(existing)
        } finally {
            Thread.interrupted()
            cursor.close()
            if (previous) Thread.currentThread().interrupt()
        }
        assertTrue(Files.isRegularFile(reference.file))
    }

    @Test
    fun `consumer예외는원래객체를보존하고세worker를모두종료한다`() {
        val refs = references()
        val existing = Thread.getAllStackTraces().keys.toSet()
        val original = IllegalStateException("compared consumer failed")
        val failure =
            assertFailsWith<IllegalStateException> {
                compareCheckpointSection(refs.values.toList(), section) { pointer, _ -> if (pointer == "$section/32") throw original }
            }
        assertSame(original, failure)
        assertNoWorkersSince(existing)
        assertTrue(refs.values.all { Files.isRegularFile(it.file) })
    }

    @Test
    fun `마지막partial행callback interrupt도성공이되지않고flag와cleanup을유지한다`() {
        val refs = references()
        val existing = Thread.getAllStackTraces().keys.toSet()
        val previous = Thread.interrupted()
        try {
            assertFailsWith<InterruptedException> {
                compareCheckpointSection(refs.values.toList(), section) { pointer, _ ->
                    if (pointer ==
                        "$section/64"
                    ) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertNoWorkersSince(existing)
        } finally {
            Thread.interrupted()
            if (previous) Thread.currentThread().interrupt()
        }
        assertTrue(refs.values.all { Files.isRegularFile(it.file) })
    }

    @Test
    fun `64대65행차이는마지막batch End경계에서실패한다`() {
        val refs = references(listOf(64, 64, 65))
        val failure = assertFailsWith<IllegalStateException> { compareCheckpointSection(refs.values.toList(), section) { _, _ -> } }
        assertTrue(failure.message.orEmpty().contains("전체 업무 배열 크기가 다릅니다"))
        assertTrue(refs.values.all { Files.isRegularFile(it.file) })
    }

    private fun references(
        counts: List<Int> = listOf(65, 65, 65),
        change: (Int, Int, Map<String, Any>) -> Map<String, Any> = { _, _, row -> row },
    ): Map<Int, HttpPerformanceCheckpointFileReference> {
        val target = Files.createDirectories(directory.resolve("fixture-${fixtureNumber++}"))
        return (0..2).associateWith { member ->
            val rows =
                (0 until counts[member]).map { index ->
                    change(
                        member,
                        index,
                        linkedMapOf(
                            "index" to index,
                            "nested" to linkedMapOf("z" to listOf(index, index + 1), "a" to index),
                        ),
                    )
                }
            val file = target.resolve("node$member-checkpoint-final.json").toAbsolutePath()
            mapper.writeValue(
                file.toFile(),
                mapOf(
                    "kind" to "PERFORMANCE_CHECKPOINT",
                    "runId" to "batch-test",
                    "memberId" to member,
                    "label" to "final",
                    "applied" to mapOf("position" to 41L, "state" to mapOf("requests" to rows)),
                    "trading" to emptyMap<String, Any>(),
                ),
            )
            HttpPerformanceCheckpointFileReference(file, "batch-test", member, "final", Files.size(file), sha(file))
        }
    }

    private fun assertNoWorkersSince(existing: Set<Thread>) {
        val remaining = Thread.getAllStackTraces().keys.filter { it !in existing && it.isAlive && it.name.startsWith("checkpoint-") }
        assertTrue(remaining.isEmpty(), "남은 section reader: ${remaining.map { it.name }}")
    }

    private fun sha(file: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
}
