package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpPerformanceFileBackedMarkerOutboxTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `작은 순차 batch는 송신 원문을 모두 해제하고 오래된 전체 필드와 누적 순서를 파일에 보존한다`() {
        val plan = HttpPerformanceMarkerPlan("file-marker-test", 1, 4096)
        val expected = (1..plan.identitiesPerMember).map { marker(it.toLong()) }
        val outbox = outbox("batches", 4, plan)
        outbox.use {
            expected.chunked(4).forEachIndexed { batch, values ->
                values.forEach { outbox.offer(it, -100) }
                repeat(values.size) { outbox.finish(assertNotNull(outbox.poll()), true) }
                assertEquals(0, outbox.retainedPayloadCount)
                assertEquals(minOf((batch + 1) * 4, expected.size), outbox.summary()["historyCount"])
            }
            assertTrue(outbox.isDrained)
            assertEquals(expected, outbox.summary()["sentMarkers"])
            assertEquals(emptyList<Any>(), outbox.summary()["undeliveredMarkers"])
            assertEquals(8, outbox.summary()["evidenceIndexBytesPerIdentity"])
        }
        assertEquals(expected, outbox.summary()["sentMarkers"])
        assertEquals(0, outbox.retainedPayloadCount)
    }

    @Test
    fun `송신 중 표식은 순간 한도에 포함되고 callback 초과 원문은 sender flush에서만 파일에 쓴다`() {
        val evidence = directory.resolve("overflow.bin")
        HttpPerformanceMarkerOutbox(1, plan(), evidence).use { outbox ->
            outbox.offer(marker(1), 10)
            val sending = assertNotNull(outbox.poll())
            outbox.offer(marker(2), 20)
            assertFalse(Files.exists(evidence))
            assertEquals(1, outbox.summary()["pendingCount"])
            assertEquals(1, outbox.summary()["terminalEvidenceCount"])
            assertEquals(1L, outbox.summary()["queueOverflow"])
            outbox.flushEvidence()
            assertTrue(Files.exists(evidence))
            assertEquals(1, outbox.retainedPayloadCount)
            outbox.finish(sending, false)
            outbox.offer(marker(3), 30)
            outbox.finish(assertNotNull(outbox.poll()), true)
            assertEquals(0, outbox.retainedPayloadCount)
            assertEquals(listOf(marker(3)), outbox.summary()["sentMarkers"])
            assertEquals(
                listOf(
                    mapOf("marker" to marker(1), "status" to "SEND_FAILED"),
                    mapOf("marker" to marker(2), "status" to "QUEUE_OVERFLOW"),
                ),
                outbox.summary()["undeliveredMarkers"],
            )
            assertFalse(outbox.isDrained)
        }
    }

    @Test
    fun `증거 대기 큐까지 초과하면 첫 원문과 초과 개수를 남기고 hot payload는 고정 한도를 지킨다`() {
        val outbox = outbox("evidence-overflow", 1)
        outbox.use {
            outbox.offer(marker(1), null)
            outbox.offer(marker(2), null)
            outbox.offer(marker(3), null)
            outbox.offer(marker(4), null)
            assertEquals(3, outbox.retainedPayloadCount)
            assertEquals(2L, outbox.summary()["evidenceOverflow"])
            assertEquals(marker(3), outbox.summary()["firstEvidenceOverflow"])
            assertEquals(4, outbox.summary()["historyCount"])
            assertEquals(3, outbox.summary()["retainedEvidenceCount"])
            outbox.finish(assertNotNull(outbox.poll()), true)
            outbox.flushEvidence()
            assertEquals(marker(3), outbox.summary()["firstEvidenceOverflow"])
            assertEquals(listOf(marker(1)), outbox.summary()["sentMarkers"])
            assertEquals(
                listOf(marker(2), marker(3)),
                undelivered(outbox.summary()).map { it["marker"] },
            )
            assertTrue(outbox.capacityFailures().any { it.contains("원문 증거 큐 초과") })
            assertFalse(outbox.isDrained)
        }
    }

    @Test
    fun `체크포인트 snapshot은 이후 송신 완료와 새 원문 저장에도 기존 cutoff 상태를 유지한다`() {
        outbox("cutoff", 2).use { outbox ->
            outbox.offer(marker(1), null)
            outbox.finish(assertNotNull(outbox.poll()), true)
            outbox.offer(marker(2), null)
            val before = outbox.summary()
            outbox.finish(assertNotNull(outbox.poll()), true)
            outbox.offer(marker(3), null)
            outbox.finish(assertNotNull(outbox.poll()), true)
            assertEquals(listOf(marker(1)), before["sentMarkers"])
            assertEquals(listOf(mapOf("marker" to marker(2), "status" to "QUEUED")), before["undeliveredMarkers"])
            assertEquals((1L..3).map(::marker), outbox.summary()["sentMarkers"])
        }
    }

    @Test
    fun `파일 한도 실패는 원문을 해제하지 않고 후속 callback 보관도 bounded로 유지하며 성공하지 않는다`() {
        val outbox = HttpPerformanceMarkerOutbox(1, plan(), directory.resolve("limit.bin"), maximumEvidenceBytes = 8)
        outbox.use {
            outbox.offer(marker(1), null)
            outbox.finish(assertNotNull(outbox.poll()), true)
            assertEquals(1, outbox.retainedPayloadCount)
            assertTrue(assertNotNull(outbox.summary()["evidenceError"]).toString().contains("파일 한도 초과"))
            (2L..40).forEach { outbox.offer(marker(it), null) }
            assertEquals(3, outbox.retainedPayloadCount)
            assertTrue(outbox.capacityFailures().any { it.contains("파일 한도 초과") })
            assertEquals(listOf(marker(1)), outbox.summary()["sentMarkers"])
            assertFalse(outbox.isDrained)
        }
        assertEquals(8L, Files.size(directory.resolve("limit.bin")))
        assertFalse(outbox.isDrained)
    }

    @Test
    fun `송신 스레드를 시작하지 않은 close도 남은 원문 상태를 저장하고 큐 참조를 해제한다`() {
        val outbox = outbox("close-pending", 2)
        outbox.offer(marker(1), null, HttpPerformanceJfrDeliveryEvent())
        outbox.offer(marker(2), null, HttpPerformanceJfrDeliveryEvent())
        outbox.close()
        assertEquals(0, outbox.retainedPayloadCount)
        assertEquals(0, outbox.summary()["queueSize"])
        assertEquals(0, outbox.summary()["pendingCount"])
        assertEquals(
            (1L..2).map { mapOf("marker" to marker(it), "status" to "QUEUED") },
            outbox.summary()["undeliveredMarkers"],
        )
        assertFalse(outbox.isDrained)
        outbox.close()
    }

    @Test
    fun `파일 close 실패는 summary에 남기고 이미 송신한 outbox도 성공하지 않는다`() {
        val outbox = outbox("close-failure", 1)
        outbox.offer(marker(1), null)
        outbox.finish(assertNotNull(outbox.poll()), true)
        assertTrue(outbox.isDrained)
        val journalField = HttpPerformanceMarkerOutbox::class.java.getDeclaredField("journal").apply { isAccessible = true }
        val journal = assertNotNull(journalField.get(outbox))
        val channelField = HttpPerformanceMarkerJournal::class.java.getDeclaredField("channel").apply { isAccessible = true }
        (channelField.get(journal) as FileChannel).close()
        outbox.close()
        assertTrue(assertNotNull(outbox.summary()["evidenceError"]).toString().contains("증거 종료 실패"))
        assertTrue(outbox.capacityFailures().any { it.contains("증거 종료 실패") })
        assertFalse(outbox.isDrained)
        assertEquals(listOf(marker(1)), outbox.summary()["sentMarkers"])

        // A late force/close error must reach the STOPPED field checked by the cluster coordinator.
        val stopped =
            HttpPerformanceCheckpointFiles.publishStopped(
                directory,
                mapOf(
                    "kind" to "STOPPED",
                    "runId" to "file-marker-test",
                    "memberId" to 0,
                    "closed" to true,
                    "failure" to null,
                    "markerSummary" to (outbox.summary() + ("failures" to outbox.capacityFailures())),
                ),
            )
        assertTrue(assertNotNull(stopped["failure"]).toString().contains("증거 종료 실패"))
        val persisted = jacksonObjectMapper().readTree(Files.readString(directory.resolve("node0-stopped.json")))
        assertTrue(persisted.path("failure").asString().contains("증거 종료 실패"))
    }

    @Test
    fun `누적 계획 초과는 파일 원문을 보존하고 첫 초과와 기존 경로 재사용 거절을 유지한다`() {
        val evidence = directory.resolve("plan.bin")
        val outbox = HttpPerformanceMarkerOutbox(1, HttpPerformanceMarkerPlan("file-marker-test", 1, 1), evidence)
        outbox.use {
            (1L..4).forEach {
                outbox.offer(marker(it), null)
                outbox.finish(assertNotNull(outbox.poll()), true)
            }
            outbox.offer(marker(5), null)
            assertEquals((1L..4).map(::marker), outbox.summary()["sentMarkers"])
            assertEquals(marker(5), outbox.summary()["firstHistoryOverflow"])
            assertEquals(4, outbox.summary()["historyCount"])
            assertEquals(1L, outbox.summary()["historyOverflow"])
            assertFalse(outbox.isDrained)
            assertFailsWith<IllegalArgumentException> { HttpPerformanceMarkerOutbox(1, plan(), evidence) }
        }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceMarkerOutbox(1, plan(), evidence) }
        assertEquals((1L..4).map(::marker), outbox.summary()["sentMarkers"])
    }

    @Test
    fun `저장된 원문의 절단 payload 손상과 파일 버전 손상은 lazy 읽기에서 거절한다`() {
        for (damage in listOf("truncate", "payload", "status", "version")) {
            val evidence = directory.resolve("$damage.bin")
            val outbox = HttpPerformanceMarkerOutbox(1, plan(), evidence)
            outbox.offer(marker(1), null)
            outbox.finish(assertNotNull(outbox.poll()), true)
            val snapshot = outbox.summary()
            outbox.close()
            FileChannel.open(evidence, WRITE).use { file ->
                when (damage) {
                    "truncate" -> file.truncate(file.size() - 1)
                    "payload" -> file.write(ByteBuffer.wrap(byteArrayOf(0)), 20)
                    "status" -> file.write(ByteBuffer.allocate(4).putInt(HttpPerformanceMarkerOutbox.Status.SEND_FAILED.ordinal).flip(), 12)
                    "version" -> file.write(ByteBuffer.allocate(4).putInt(2).flip(), 4)
                }
            }
            assertFailsWith<IllegalStateException>(damage) { (snapshot["sentMarkers"] as List<*>).iterator().next() }
            assertNull(snapshot["evidenceError"])
        }
    }

    private fun plan() = HttpPerformanceMarkerPlan("file-marker-test", 1, 64)

    private fun outbox(
        name: String,
        capacity: Int,
        plan: HttpPerformanceMarkerPlan = plan(),
    ) = HttpPerformanceMarkerOutbox(capacity, plan, directory.resolve("$name.bin"))

    @Suppress("UNCHECKED_CAST")
    private fun undelivered(summary: Map<String, Any?>) = summary["undeliveredMarkers"] as List<Map<String, Any?>>

    private fun marker(sequence: Long) =
        HttpPerformanceCompletion(
            "file-marker-test",
            2,
            "FOLLOWER",
            "사용자-$sequence",
            "요청-$sequence",
            "주문-$sequence",
            sequence,
            sequence * 128,
            sequence + Int.MAX_VALUE,
            sequence + Int.MAX_VALUE + 1,
        )
}
