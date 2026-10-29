package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import io.aeron.logbuffer.Header
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpPerformanceJfrTest {
    @Test
    fun `enabled recording retains request identity and ends before observer work`() {
        val file = Files.createTempFile("performance-request-", ".jfr")
        var nowNs = 100L
        val observer = HttpPerformanceJfr("jfr-test", 2) { nowNs }
        try {
            Recording().use { recording ->
                recording.enable(HttpPerformanceJfrEvent::class.java).withThreshold(Duration.ZERO).withoutStackTrace()
                recording.start()
                val event = assertNotNull(observer.begin())
                nowNs = 200
                observer.end(event)
                nowNs = 9_000
                val identity = AdmissionResultPayload.Accepted("user", "request", "market", "order", 42)
                observer.commit(
                    event,
                    FundingObservation(512, null, null, appliedRole = "FOLLOWER", executionSequence = 42),
                    identity,
                    0,
                    "UNKNOWN",
                    false,
                )
                recording.stop()
                recording.dump(file)
            }
            val event = RecordingFile.readAllEvents(file).single { it.eventType.name == "exchange.HttpPerformanceCallback" }
            assertEquals("jfr-test", event.getString("runId"))
            assertEquals(2, event.getInt("memberId"))
            assertEquals("EXECUTION", event.getString("kind"))
            assertEquals("user", event.getString("userId"))
            assertEquals("request", event.getString("requestId"))
            assertEquals(42L, event.getLong("admissionSequence"))
            assertEquals(512L, event.getLong("logPosition"))
            assertEquals("FOLLOWER", event.getString("role"))
            assertEquals(100L, event.getLong("callbackStartedNs"))
            assertEquals(200L, event.getLong("callbackCompletedNs"))
            assertNotNull(event.thread)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `disabled event does not read callback clock`() {
        val observer = HttpPerformanceJfr("disabled-jfr-test", 0) { error("disabled event read clock") }
        Recording().use { recording ->
            recording.disable(HttpPerformanceJfrEvent::class.java)
            recording.disable(HttpPerformanceJfrDeliveryEvent::class.java)
            recording.start()
            assertNull(observer.begin())
            assertNull(observer.beginMarkerDelivery(marker()))
        }
    }

    @Test
    fun `marker event spans existing queue handoff and records sender event thread`() {
        val file = Files.createTempFile("performance-marker-", ".jfr")
        var nowNs = 100L
        val observer = HttpPerformanceJfr("jfr-test", 2) { nowNs }
        try {
            Recording().use { recording ->
                recording.enable(HttpPerformanceJfrDeliveryEvent::class.java).withThreshold(Duration.ZERO).withoutStackTrace()
                recording.start()
                val event = assertNotNull(observer.beginMarkerDelivery(marker()))
                var senderFailure: Throwable? = null
                val sender =
                    Thread({
                        try {
                            nowNs = 200
                            observer.markerDequeued(event)
                            nowNs = 300
                            observer.markerFirstOffer(event)
                            nowNs = 400
                            observer.completeMarkerDelivery(event)
                        } catch (error: Throwable) {
                            senderFailure = error
                        }
                    }, "marker-jfr-test-sender")
                sender.start()
                sender.join(5_000)
                assertTrue(!sender.isAlive)
                assertNull(senderFailure)
                recording.stop()
                recording.dump(file)
            }
            val event = RecordingFile.readAllEvents(file).single { it.eventType.name == "exchange.HttpPerformanceMarkerDelivery" }
            assertEquals("request", event.getString("requestId"))
            assertEquals(42L, event.getLong("admissionSequence"))
            assertEquals(512L, event.getLong("logPosition"))
            assertEquals(100L, event.getLong("enqueuedNs"))
            assertEquals(200L, event.getLong("dequeuedNs"))
            assertEquals(300L, event.getLong("firstOfferNs"))
            assertEquals(400L, event.getLong("acceptedNs"))
            assertEquals(100L, event.getLong("localQueueNs"))
            assertEquals(100L, event.getLong("offerNs"))
            assertEquals("marker-jfr-test-sender", assertNotNull(event.thread).javaName)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `marker JFR enqueue failure preserves outbox offer and reports sender failure`() {
        val observer = mock(HttpPerformanceJfr::class.java)
        val marker = marker()
        doThrow(IllegalStateException("marker jfr")).`when`(observer).beginMarkerDelivery(marker)
        val sender =
            HttpPerformanceMarkerSender(Path.of("/unused-marker-jfr-test"), "localhost:1", detailedTracing = false, requestJfr = observer)
        sender.offer(marker)
        val summary = sender.summary()
        assertEquals(1L, summary["offered"])
        assertEquals(1, summary["pendingCount"])
        assertTrue((summary["failures"] as List<*>).any { it.toString().contains("marker jfr") })
    }

    private fun marker() = HttpPerformanceCompletion("jfr-test", 2, "FOLLOWER", "user", "request", "order", 42, 512, 1, 1)

    @Test
    fun `finished marker releases JFR reference for success and failure while retaining original history`() {
        for (delivered in listOf(true, false)) {
            val outbox = HttpPerformanceMarkerOutbox(1)
            val marker = marker()
            val event = HttpPerformanceJfrDeliveryEvent()
            outbox.offer(marker, 100, event)
            val pending = assertNotNull(outbox.poll())
            assertSame(event, pending.jfrEvent)
            outbox.finish(pending, delivered)
            assertNull(pending.jfrEvent)
            assertSame(marker, pending.marker)
            assertEquals(
                if (delivered) HttpPerformanceMarkerOutbox.Status.SENT else HttpPerformanceMarkerOutbox.Status.SEND_FAILED,
                pending.status,
            )
            assertEquals(1, outbox.summary()["historyCount"])
            assertEquals(0, outbox.summary()["pendingCount"])
        }
    }

    @Test
    fun `queue overflow releases unsendable JFR reference from retained marker history`() {
        val outbox = HttpPerformanceMarkerOutbox(1)
        val marker = marker()
        val queuedEvent = HttpPerformanceJfrDeliveryEvent()
        outbox.offer(marker, 100, queuedEvent)
        outbox.offer(marker.copy(requestId = "overflow"), 200, HttpPerformanceJfrDeliveryEvent())
        // Inspect retention directly without adding a production/test observer API.
        val historyField = HttpPerformanceMarkerOutbox::class.java.getDeclaredField("history").apply { isAccessible = true }
        val history = historyField.get(outbox) as List<*>
        val overflow = history[1] as HttpPerformanceMarkerOutbox.Pending
        assertNull(overflow.jfrEvent)
        assertEquals("overflow", overflow.marker.requestId)
        assertEquals(HttpPerformanceMarkerOutbox.Status.QUEUE_OVERFLOW, overflow.status)
        assertSame(queuedEvent, assertNotNull(outbox.poll()).jfrEvent)
        assertEquals(2, outbox.summary()["historyCount"])
        assertEquals(1L, outbox.summary()["queueOverflow"])
    }

    @Test
    fun `JFR begin failure preserves product callback execution and reports observation failure`() {
        val observer = mock(HttpPerformanceJfr::class.java)
        doThrow(IllegalStateException("jfr begin")).`when`(observer).begin()
        val service = mock(DevelopmentFundingAdapter::class.java)
        `when`(service.telemetryOperational).thenReturn(true)
        val collector = collector(observer)
        val buffer = UnsafeBuffer(ByteArray(0))
        val header = mock(Header::class.java)
        collector.decorate(service).onSessionMessage(null, 0, buffer, 0, 0, header)
        verify(service).onSessionMessage(null, 0, buffer, 0, 0, header)
        assertTrue(assertNotNull(collector.error).contains("JFR 관찰 실패"))
    }

    @Test
    fun `JFR end failure does not replace original product exception`() {
        val observer = mock(HttpPerformanceJfr::class.java)
        val event = HttpPerformanceJfrEvent()
        `when`(observer.begin()).thenReturn(event)
        doThrow(IllegalStateException("jfr end")).`when`(observer).end(event)
        val service = mock(DevelopmentFundingAdapter::class.java)
        `when`(service.telemetryOperational).thenReturn(true)
        val buffer = UnsafeBuffer(ByteArray(0))
        val header = mock(Header::class.java)
        val productFailure = IllegalArgumentException("product failure")
        doThrow(productFailure).`when`(service).onSessionMessage(null, 0, buffer, 0, 0, header)
        val collector = collector(observer)
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                collector.decorate(service).onSessionMessage(null, 0, buffer, 0, 0, header)
            }
        assertSame(productFailure, thrown)
        assertTrue(assertNotNull(collector.error).contains("jfr end"))
    }

    private fun collector(observer: HttpPerformanceJfr) =
        HttpPerformanceCollector("jfr-test", 0, {
        }, { error("detailed clock used") }, HTTP_PERFORMANCE_CALLBACK_CAPACITY, false, requestJfr = observer)
}
