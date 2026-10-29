package com.exchange.core.api.trading.infrastructure.aeron

import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.SleepingMillisIdleStrategy
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class HttpPerformanceMarkersTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `완료 표식은 요청 식별과 체결 정산 개수를 보존하고 서버 시계를 포함하지 않는다`() {
        val marker = marker().copy(tradeCount = Int.MAX_VALUE.toLong() + 1, settlementCount = Int.MAX_VALUE.toLong() + 1)
        assertEquals(marker, HttpPerformanceMarkerCodec.decode(HttpPerformanceMarkerCodec.encode(marker)))
        assertFailsWith<IllegalArgumentException> {
            HttpPerformanceMarkerCodec.decode(HttpPerformanceMarkerCodec.encode(marker) + byteArrayOf(0))
        }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceMarkerCodec.decode(byteArrayOf(0, 0, 0, 0)) }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceMarkerCodec.encode(marker.copy(requestId = "x".repeat(257))) }
        assertFailsWith<IllegalArgumentException> {
            HttpPerformanceMarkerCodec.decode(HttpPerformanceMarkerCodec.encode(marker.copy(memberId = 4)))
        }
    }

    @Test
    fun `노드 관찰 큐 초과는 고정 용량과 유실 개수로 남는다`() {
        HttpPerformanceMarkerSender(temporary.resolve("sender"), "127.0.0.1:19001", capacity = 1).use { sender ->
            sender.offer(marker())
            sender.offer(marker().copy(admissionSequence = 2))
            assertEquals(2L, sender.summary()["offered"])
            assertEquals(1L, sender.summary()["queueOverflow"])
            assertEquals(1, sender.summary()["queueSize"])
        }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceMarkerSender(temporary, "127.0.0.1:19001", Int.MAX_VALUE) }
    }

    @ParameterizedTest
    @EnumSource(HttpPerformanceMarkerIdlePolicy::class)
    internal fun `두 idle 정책 모두 실제 별도 Aeron 송수신으로 세 노드 표식을 전달한다`(policy: HttpPerformanceMarkerIdlePolicy) {
        val received = CopyOnWriteArrayList<Pair<HttpPerformanceCompletion, Long>>()
        val before = System.nanoTime()
        val collect: (HttpPerformanceCompletion, Long) -> Unit = { marker, at -> received.add(marker to at) }
        HttpPerformanceMarkerReceiver("marker-test", temporary.resolve("receiver"), collect, policy).use { receiver ->
            val senders =
                (0..2).map {
                    HttpPerformanceMarkerSender(temporary.resolve("sender$it"), "127.0.0.1:${receiver.port}", idlePolicy = policy)
                }
            try {
                senders.forEach { it.start() }
                senders.forEachIndexed { member, sender ->
                    val role = if (member == 0) "LEADER" else "FOLLOWER"
                    sender.offer(
                        marker().copy(
                            memberId = member,
                            role = role,
                        ),
                    )
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while ((received.size < 3 || senders.any { !it.isDrained }) && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(setOf(0, 1, 2), received.map { it.first.memberId }.toSet())
                assertTrue(received.all { it.second >= before && it.second <= System.nanoTime() })
                assertTrue(receiver.errors().isEmpty(), receiver.errors().toString())
                assertTrue(senders.all { (it.summary()["failures"] as List<*>).isEmpty() })
                assertEquals(3, receiver.markers().size)
                assertEquals(policy.name, receiver.manifest()["markerIdlePolicy"])
                assertTrue(senders.all { it.summary()["markerIdlePolicy"] == policy.name })
                val spans = senders.flatMap { it.summary()["senderLocalSpans"] as List<*> }
                assertEquals(3, spans.size)
                assertEquals(setOf(0, 1, 2), spans.map { assertIs<HttpPerformanceMarkerLocalSpan>(it).memberId }.toSet())
                assertTrue(spans.all { assertIs<HttpPerformanceMarkerLocalSpan>(it).enqueueToAcceptedNs >= 0 })
                assertTrue(senders.all { it.summary()["senderLocalSpanOverflow"] == 0L })
            } finally {
                senders.forEach { it.close() }
            }
        }
    }

    @Test
    fun `표식 idle은 실제 선택한 전략을 스레드별 새 인스턴스로 만든다`() {
        assertIs<SleepingMillisIdleStrategy>(HttpPerformanceMarkerIdlePolicy.SLEEP_1MS.newIdleStrategy())
        assertIs<BackoffIdleStrategy>(HttpPerformanceMarkerIdlePolicy.BACKOFF.newIdleStrategy())
        HttpPerformanceMarkerIdlePolicy.entries.forEach { policy ->
            assertNotSame(policy.newIdleStrategy(), policy.newIdleStrategy())
        }
    }

    @Test
    fun `송신 로컬 차분은 음수 절대 시각도 허용하며 노드 절대 시각은 내보내지 않는다`() {
        val spans = HttpPerformanceMarkerLocalSpans()
        spans.record(marker(), -100, -90, -88, -83, -80, -50)

        val span = spans.snapshot().single()
        assertEquals(HttpPerformanceMarkerLocalSpan("marker-test", 0, "BUYER", "M-1", "M-1", 1, 10, 5, 30, 40, 50), span)
        val json = jacksonObjectMapper().readTree(jacksonObjectMapper().writeValueAsString(span))
        assertEquals(
            setOf(
                "runId",
                "memberId",
                "userId",
                "requestId",
                "orderId",
                "admissionSequence",
                "queueWaitNs",
                "encodeNs",
                "offerToAcceptedNs",
                "dequeueToAcceptedNs",
                "enqueueToAcceptedNs",
            ),
            json.properties().map { it.key }.toSet(),
        )
        assertEquals(0L, spans.overflowCount())
        assertEquals(marker(), HttpPerformanceMarkerCodec.decode(HttpPerformanceMarkerCodec.encode(marker())))
    }

    @Test
    fun `음수 로컬 경과는 기록을 만들지 않고 버퍼 초과는 누락 개수로 남긴다`() {
        val spans = HttpPerformanceMarkerLocalSpans(capacity = 1)
        assertFailsWith<IllegalArgumentException> { spans.record(marker(), 10, 9, 11, 12, 13, 14) }
        assertTrue(spans.snapshot().isEmpty())
        spans.record(marker(), 0, 1, 2, 3, 4, 5)
        spans.record(marker().copy(admissionSequence = 2), 0, 1, 2, 3, 4, 6)
        assertEquals(1, spans.snapshot().size)
        assertEquals(1L, spans.overflowCount())
        assertEquals(1L, spans.snapshot().single().admissionSequence)
        listOf(0, -1, HTTP_PERFORMANCE_MARKER_CAPACITY + 1).forEach { capacity ->
            assertFailsWith<IllegalArgumentException> { HttpPerformanceMarkerLocalSpans(capacity) }
        }
    }

    @Test
    fun `상세 기록 off도 실제 표식 전달과 송신 개수는 유지한다`() {
        val received = CopyOnWriteArrayList<HttpPerformanceCompletion>()
        HttpPerformanceMarkerReceiver(
            "marker-test",
            temporary.resolve("receiver-off"),
            { marker, _ -> received.add(marker) },
        ).use { receiver ->
            HttpPerformanceMarkerSender(
                temporary.resolve("sender-off"),
                "127.0.0.1:${receiver.port}",
                detailedTracing = false,
            ).use { sender ->
                sender.start()
                sender.offer(marker())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while ((received.isEmpty() || !sender.isDrained) && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(listOf(marker()), received)
                assertTrue(sender.isDrained)
                assertEquals(1, sender.summary()["sentCount"])
                assertEquals(false, sender.summary()["detailedTracing"])
                assertEquals(emptyList<Any?>(), sender.summary()["senderLocalSpans"])
                assertEquals(0L, sender.summary()["senderLocalSpanOverflow"])
                assertEquals(emptyList<Any?>(), sender.summary()["failures"])
                assertTrue(receiver.errors().isEmpty())
            }
        }
    }

    private fun marker() = HttpPerformanceCompletion("marker-test", 0, "LEADER", "BUYER", "M-1", "M-1", 1, 128, 1, 1)
}
