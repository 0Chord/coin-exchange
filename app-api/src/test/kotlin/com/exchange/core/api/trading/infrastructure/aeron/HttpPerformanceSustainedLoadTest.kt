package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.EOFException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.concurrent.CompletableFuture
import javax.net.ssl.SSLSession
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fake clock and injected test-source submit only; no HTTP socket, server, Docker or performance claim. */
@Timeout(10)
class HttpPerformanceSustainedLoadTest {
    @Test
    fun `같은parentclock의고정일정과창종료대기를유지하고전량ACK완료를따로집계한다`() {
        runFixture { fixture ->
            val cutoff = fixture.load.sendAndDrain(fixture.end, 10_000_000)
            assertTrue(cutoff - fixture.end >= 0)
            val summary = fixture.load.finishWithSentMarkerVisitor(cutoff) { visit -> fixture.markers.forEach(visit) }
            assertEquals(3, fixture.submitted)
            assertEquals(3, summary.accepted)
            assertEquals(3, summary.completed)
            assertTrue(summary.completionLatencyValid)
            assertEquals(listOf(0L, 1_000_000L, 2_000_000L), fixture.measurements.requests().map { it.dueNs - fixture.first })
        }
    }

    @Test
    fun `예정대기oversleep뒤에는catchup전송대신남은행을WINDOW_ENDED로보존한다`() {
        runFixture(oversleep = true) { fixture ->
            val cutoff = fixture.load.sendAndDrain(fixture.end, 10_000_000)
            val summary = fixture.load.finishWithSentMarkerVisitor(cutoff) { visit -> fixture.markers.forEach(visit) }
            assertEquals(1, fixture.submitted)
            assertEquals(2, summary.unsent)
            val samples = fixture.measurements.requests()
            assertEquals(listOf("WINDOW_ENDED", "WINDOW_ENDED"), samples.drop(1).map { it.notSentReason })
            assertTrue(samples.drop(1).all { it.startNs == null })
            assertEquals(listOf(0L, 1_000_000L, 2_000_000L), samples.map { it.dueNs - fixture.first })
        }
    }

    @Test
    fun `입장전interrupt는N개가짜HTTP결과없이미송신원인과flag를보존한다`() {
        runFixture { fixture ->
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<InterruptedException> { fixture.load.sendAndDrain(fixture.end, 10_000_000) }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
            assertEquals(0, fixture.submitted)
            assertTrue(
                fixture.measurements.requests().all {
                    it.notSentReason == "INTERRUPTED" && it.startNs == null &&
                        it.httpOutcome == null
                },
            )
        }
    }

    @Test
    fun `Longwrap을지난예정대기도음수절대시각을서로빼서정상처리한다`() {
        runFixture(first = Long.MAX_VALUE - 1_500_000) { fixture ->
            val cutoff = fixture.load.sendAndDrain(fixture.end, 10_000_000)
            val summary = fixture.load.finishWithSentMarkerVisitor(cutoff) { visit -> fixture.markers.forEach(visit) }
            assertEquals(3_000_000L, summary.measurementWindowNs)
            assertEquals(3, summary.accepted)
            assertTrue(summary.completionLatencyValid)
        }
    }

    @Test
    fun `기존1024동시한도는유지하고1025번째를포화미송신으로남긴다`() {
        HttpClient.newHttpClient().use { http ->
            val planned = (0..1024).map { PlannedHttpRequest("BUYER", "M-$it", "M-$it", 0) }
            val measurements = HttpPerformanceMeasurements("http-load", 0, 1_000_000, planned)
            var now = 0L
            var submitted = 0
            val load =
                HttpPerformanceLoad(
                    http,
                    URI.create("http://localhost/orders"),
                    planned,
                    measurements,
                    1024,
                    HttpPerformanceClientStages(false),
                    enforceWindow = true,
                    nanoTime = { now },
                    parkNanos = { now += it },
                    submit = {
                        submitted++
                        CompletableFuture<HttpResponse<String>>()
                    },
                )
            val cutoff = load.sendAndDrain(1_000_000, 1_000_000)
            val summary = load.finish(cutoff, emptyList())
            assertEquals(1024, submitted)
            assertEquals(1, summary.generatorSaturated)
            assertEquals(1, summary.unsent)
            assertEquals(1024, summary.httpUnfinished)
            assertNull(measurements.requests().last().startNs)
        }
    }

    @Test
    fun `동기submit예외와비동기EOF는시도원문을unknown으로보존하며자동재시도하지않는다`() {
        for (synchronous in listOf(false, true)) {
            HttpClient.newHttpClient().use { http ->
                val plans = (0..2).map { PlannedHttpRequest("BUYER", "M-$it", "M-$it", it * 1_000_000L) }
                val measurements = HttpPerformanceMeasurements("http-failed", 0, 3_000_000, plans)
                var now = 0L
                var attempted = 0
                val load =
                    HttpPerformanceLoad(
                        http,
                        URI.create("http://localhost/orders"),
                        plans,
                        measurements,
                        1024,
                        HttpPerformanceClientStages(false),
                        enforceWindow = true,
                        nanoTime = { now },
                        parkNanos = { now += it },
                        submit = {
                            attempted++
                            if (synchronous) throw EOFException("synchronous peer EOF")
                            CompletableFuture.failedFuture(EOFException("asynchronous peer EOF"))
                        },
                    )
                val cutoff = load.sendAndDrain(3_000_000, 10_000_000)
                val summary = load.finish(cutoff, emptyList())
                assertEquals(3, attempted)
                assertEquals(3, summary.started)
                assertEquals(3, summary.httpUnknown)
                assertEquals(0, summary.unsent)
                assertEquals(0, summary.accepted)
                assertTrue(
                    measurements.requests().all {
                        it.startNs != null && it.ackNs == null &&
                            it.httpOutcome == HttpPerformanceOutcome.HTTP_UNKNOWN &&
                            it.httpDetail == "EOFException"
                    },
                )
                assertEquals(false, httpPerformanceSustainedCohortEvidence(3, summary, null, null, emptyList())["cohortComplete"])
            }
        }
    }

    @Test
    fun `장기조기drain은리더만으로닫지않고원래최대deadline안의세노드수신을기다린다`() {
        HttpClient.newHttpClient().use { http ->
            val end = 1_000_000L
            val measurements = HttpPerformanceMeasurements("http-drain", 0, end, listOf(PlannedHttpRequest("BUYER", "M-0", "M-0", 0)))
            val markers =
                (0..2).map {
                    HttpPerformanceCompletion(
                        "http-drain",
                        it,
                        if (it ==
                            0
                        ) {
                            "LEADER"
                        } else {
                            "FOLLOWER"
                        },
                        "BUYER",
                        "M-0",
                        "M-0",
                        1,
                        256,
                        1,
                        1,
                    )
                }
            var now = 0L
            var followerReceived: Long? = null
            lateinit var load: HttpPerformanceLoad
            load =
                HttpPerformanceLoad(
                    http,
                    URI.create("http://localhost/orders"),
                    listOf(PlannedHttpRequest("BUYER", "M-0", "M-0", 0)),
                    measurements,
                    1024,
                    HttpPerformanceClientStages(false),
                    enforceWindow = true,
                    nanoTime = { now },
                    parkNanos = {
                        now += it
                        if (now > end + 500_000 && followerReceived == null) {
                            followerReceived = now
                            load.observe(markers[2], now)
                        }
                    },
                    submit = { request ->
                        now = 10
                        load.observe(markers[0], now)
                        load.observe(markers[1], now)
                        CompletableFuture.completedFuture(response(request, 0))
                    },
                )
            val cutoff = load.sendAndDrain(end, 10_000_000)
            assertTrue(cutoff > end)
            assertTrue(cutoff <= end + 10_000_000)
            assertEquals(requireNotNull(followerReceived), cutoff)
            assertEquals(3, measurements.observedRequestMemberCount())
            val summary = load.finishWithSentMarkerVisitor(cutoff) { visit -> markers.forEach(visit) }
            assertTrue(summary.completionLatencyValid)
            assertEquals(1, summary.completionWindowCount)
        }
    }

    private class Fixture(
        val first: Long,
        oversleep: Boolean,
        val http: HttpClient,
    ) {
        val end = first + 3_000_000
        var now = first
        var submitted = 0
        val plans = (0..2).map { PlannedHttpRequest("BUYER", "M-$it", "M-$it", first + it * 1_000_000L) }
        val measurements = HttpPerformanceMeasurements("http-load", first, end, plans)
        val markers = mutableListOf<HttpPerformanceCompletion>()
        lateinit var load: HttpPerformanceLoad

        init {
            load =
                HttpPerformanceLoad(
                    http,
                    URI.create("http://localhost/orders"),
                    plans,
                    measurements,
                    1024,
                    HttpPerformanceClientStages(false),
                    enforceWindow = true,
                    nanoTime = { now },
                    parkNanos = { now = if (oversleep) end + 1 else now + it },
                    submit = { request ->
                        val index = submitted++
                        now += 10
                        for (member in 0..2) {
                            val marker =
                                HttpPerformanceCompletion(
                                    "http-load",
                                    member,
                                    if (member ==
                                        0
                                    ) {
                                        "LEADER"
                                    } else {
                                        "FOLLOWER"
                                    },
                                    "BUYER",
                                    "M-$index",
                                    "M-$index",
                                    index + 1L,
                                    256L * (index + 1),
                                    1,
                                    1,
                                )
                            markers.add(marker)
                            load.observe(marker, now)
                        }
                        CompletableFuture.completedFuture(response(request, index))
                    },
                )
        }
    }

    private fun runFixture(
        first: Long = 0,
        oversleep: Boolean = false,
        run: (Fixture) -> Unit,
    ) {
        HttpClient.newHttpClient().use { run(Fixture(first, oversleep, it)) }
    }

    companion object {
        private fun response(
            original: HttpRequest,
            index: Int,
        ): HttpResponse<String> =
            object : HttpResponse<String> {
                override fun statusCode(): Int = 202

                override fun request(): HttpRequest = original

                override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

                override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

                override fun body(): String =
                    jacksonObjectMapper().writeValueAsString(
                        mapOf(
                            "userId" to "BUYER",
                            "requestId" to "M-$index",
                            "orderId" to "M-$index",
                            "marketId" to "BTC-USDT",
                            "status" to "ACCEPTED",
                            "sequence" to index + 1,
                        ),
                    )

                override fun sslSession(): Optional<SSLSession> = Optional.empty()

                override fun uri(): URI = original.uri()

                override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
            }
    }
}
