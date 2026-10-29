package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

class HttpPerformanceNodeHeapDiagnosticsTest {
    @TempDir lateinit var temporary: Path
    private val runId = "heap-fixture"
    private val prepared = HttpPerformanceNodeHeapPhase.PREPARED
    private val drained = HttpPerformanceNodeHeapPhase.POST_LOAD_DRAINED
    private val full =
        " num     #instances         #bytes  class name (module)\n" +
            "-------------------------------------------------------\n" +
            "   1:             2             48  java.lang.String (java.base@25)\n" +
            "   2:             1             24  fixture.한글\n" +
            "Total             3             72\n"

    private fun directory(name: String) = Files.createDirectory(temporary.resolve(name)).toRealPath()

    private fun gc(count: Long) =
        listOf(
            mapOf<String, Any?>(
                "name" to "fixture-GC",
                "collectionCount" to count,
                "collectionTimeMs" to count * 3,
            ),
        )

    private fun tree(value: Any): JsonNode = jacksonObjectMapper().readTree(jacksonObjectMapper().writeValueAsString(value))

    private fun recorder(
        directory: Path,
        provider: HttpPerformanceLiveHistogramProvider,
        member: Int = 0,
    ): HttpPerformanceNodeHeapRecorder {
        var calls = 0L
        return HttpPerformanceNodeHeapRecorder(
            directory,
            runId,
            member,
            provider,
            nanoTime = { Long.MAX_VALUE - 1 + calls++ },
            gcSnapshot = { gc(calls) },
        )
    }

    @Test
    fun `default absent opt in has no poller or provider call`() {
        val control = directory("default")
        assertNull(HttpPerformanceNodeHeapControl.open(control, runId, 0))
        assertEquals(0, Files.list(control).use { it.count() }.toInt())
    }

    @Test
    fun `lifecycle allows one prepared and one drained capture and prohibits measurement or closed repeats`() {
        val gate = HttpPerformanceNodeHeapGate()
        assertThrows(IllegalStateException::class.java) { gate.drainedAttempt() }
        assertEquals(prepared, gate.preparationAttempt())
        assertThrows(IllegalStateException::class.java) { gate.preparationAttempt() }
        gate.beginMeasurement()
        assertThrows(IllegalStateException::class.java) { gate.preparationAttempt() }
        assertEquals(drained, gate.drainedAttempt())
        assertThrows(IllegalStateException::class.java) { gate.drainedAttempt() }
        assertThrows(IllegalStateException::class.java) { gate.beginMeasurement() }
        gate.close()
        assertThrows(IllegalStateException::class.java) { gate.preparationAttempt() }
        val unverified = HttpPerformanceNodeHeapGate()
        unverified.preparationAttempt()
        unverified.markUnverified()
        assertThrows(IllegalStateException::class.java) { unverified.beginMeasurement() }
        // Post-load-only diagnostics do not require an earlier GC/capture.
        val postOnly = HttpPerformanceNodeHeapGate()
        postOnly.beginMeasurement()
        assertEquals(drained, postOnly.drainedAttempt())
    }

    @Test
    fun `fake provider preserves entire UTF8 text EOF full hash GC and same JVM clock across nano wrap`() {
        val control = directory("full")
        var calls = 0
        val result =
            recorder(control, {
                calls++
                full
            }).capture(prepared)
        assertEquals("VERIFIED", result["status"])
        assertEquals(1, calls)
        val raw = control.resolve("node0-heap-prepared.txt")
        assertEquals(full, Files.readString(raw))
        assertEquals(full.toByteArray(Charsets.UTF_8).size.toLong(), httpPerformanceHeapFingerprint(raw).bytes)
        assertEquals(HttpPerformanceHeapTable(2, 3, 72), verifyHttpPerformanceHeapTable(raw))
        val evidence = verifyHttpPerformanceNodeHeapResponse(control, tree(result), runId, 0, prepared)
        assertEquals(true, evidence["verified"])
        assertEquals(false, evidence["baselineComparable"])
        assertNotNull(result["gcBefore"])
        assertNotNull(result["gcAfter"])
        assertEquals("REQUESTED_NOT_PROVEN_BY_RETURNED_TABLE", result["fullGcEvidence"])
        assertTrue((result["elapsedNs"] as Long) >= 0)
        assertTrue(Files.exists(control.resolve("node0-heap-prepared.ready")))
    }

    @Test
    fun `unsupported and provider failures consume one attempt and preserve unverified metadata`() {
        for ((index, error) in listOf(
            UnsupportedOperationException("no HotSpot operation"),
            IllegalStateException("VM failure"),
        ).withIndex()) {
            val control = directory("failure$index")
            var calls = 0
            val source =
                recorder(control, {
                    calls++
                    throw error
                })
            val result = source.capture(prepared)
            assertEquals("UNVERIFIED", result["status"])
            assertEquals(1, calls)
            assertTrue((result["failures"] as List<*>).isNotEmpty())
            assertNotNull(result["gcAfter"])
            assertFalse(Files.exists(control.resolve("node0-heap-prepared.txt")))
            assertEquals("UNVERIFIED", httpPerformanceHeapReadJson(control.resolve("node0-heap-prepared.json")).path("status").asString())
            assertThrows(IllegalStateException::class.java) { source.capture(prepared) }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `truncated missing Total wrong sums warning and trailing content never become verified`() {
        val variants =
            listOf(
                full.dropLast(1),
                full.substringBefore("Total"),
                full.replace("3             72", "3             73"),
                full + "extra\n",
                "ERROR: Ran out of C-heap; histogram not generated\n",
            )
        for ((index, text) in variants.withIndex()) {
            val control = directory("bad$index")
            val result = recorder(control, { text }).capture(prepared)
            assertEquals("UNVERIFIED", result["status"])
            val raw = control.resolve("node0-heap-prepared.txt")
            assertEquals(text, Files.readString(raw))
            assertTrue(httpPerformanceHeapFingerprint(raw).bytes > 0)
            assertThrows(
                IllegalArgumentException::class.java,
            ) { verifyHttpPerformanceNodeHeapResponse(control, tree(result), runId, 0, prepared) }
        }
    }

    @Test
    fun `oversized provider output retains finite partial bytes but never claims complete EOF evidence`() {
        val control = directory("oversized")
        val result = recorder(control, { "x".repeat(HTTP_PERFORMANCE_NODE_HEAP_MAX_BYTES.toInt() + 1) }).capture(prepared)
        assertEquals("UNVERIFIED", result["status"])
        val raw = control.resolve("node0-heap-prepared.txt")
        assertEquals(HTTP_PERFORMANCE_NODE_HEAP_MAX_BYTES, Files.size(raw))
        val saved = tree(result).path("raw")
        assertEquals(false, saved.path("complete").asBoolean())
        assertEquals(false, saved.path("eofAndTableVerified").asBoolean())
        assertEquals(httpPerformanceHeapFingerprint(raw).sha256, saved.path("sha256").asString())
    }

    @Test
    fun `existing raw prevents management invocation and metadata collision is an explicit persistence failure`() {
        val collision = directory("raw-collision")
        val raw = collision.resolve("node0-heap-prepared.txt")
        Files.writeString(raw, "existing evidence\n", CREATE_NEW, WRITE)
        var calls = 0
        val failed =
            recorder(collision, {
                calls++
                full
            }).capture(prepared)
        assertEquals(0, calls)
        assertEquals("UNVERIFIED", failed["status"])
        assertEquals("existing evidence\n", Files.readString(raw))
        val metadata = directory("metadata-collision")
        val response = metadata.resolve("node0-heap-prepared.json")
        val result =
            recorder(metadata, {
                // A fake provider injects a publication failure after the CREATE_NEW preflight.
                Files.writeString(response, "original metadata\n", CREATE_NEW, WRITE)
                full
            }).capture(prepared)
        assertEquals("UNVERIFIED", result["status"])
        assertEquals(false, result["responseWritten"])
        assertEquals("original metadata\n", Files.readString(response))
        assertEquals(full, Files.readString(metadata.resolve("node0-heap-prepared.txt")))
        assertFalse(Files.exists(metadata.resolve("node0-heap-prepared.ready")))
    }

    @Test
    fun `publishing a later longer phase never replaces the earlier immutable request bytes`() {
        val control = directory("immutable-requests")
        HttpPerformanceNodeHeapControl.enable(control, runId)
        HttpPerformanceNodeHeapControl.request(control, runId, prepared)
        val first = HttpPerformanceNodeHeapControl.requestFile(control)
        val original = Files.readAllBytes(first)
        HttpPerformanceNodeHeapControl.window(control, runId, "MEASUREMENT")
        HttpPerformanceNodeHeapControl.window(control, runId, "DRAINED")
        HttpPerformanceNodeHeapControl.request(control, runId, drained)
        assertArrayEquals(original, Files.readAllBytes(first))
    }

    @Test
    fun `an unclosed unpublished request cannot execute the diagnostic`() {
        val control = directory("unpublished")
        HttpPerformanceNodeHeapControl.enable(control, runId)
        var calls = 0
        val poller =
            HttpPerformanceNodeHeapPoller(control, runId, 0) {
                calls++
                full
            }
        Files.writeString(HttpPerformanceNodeHeapControl.requestFile(control), "$runId:PRE", CREATE_NEW, WRITE)
        assertNull(poller.poll())
        assertEquals(0, calls)
        Files.createFile(HttpPerformanceNodeHeapControl.requestReadyFile(control))
        assertEquals("UNVERIFIED", poller.poll()?.get("status"))
        assertEquals(0, calls)
    }

    @Test
    fun `poller recognizes only fixed two phases and never retries a consumed live GC`() {
        val control = directory("poll")
        HttpPerformanceNodeHeapControl.enable(control, runId)
        var calls = 0
        val poller =
            HttpPerformanceNodeHeapPoller(control, runId, 1, {
                calls++
                full
            })
        HttpPerformanceNodeHeapControl.request(control, runId, prepared)
        assertEquals("VERIFIED", poller.poll()?.get("status"))
        assertEquals(1, calls)
        assertNull(poller.poll())
        assertEquals(1, calls)
        HttpPerformanceNodeHeapControl.window(control, runId, "MEASUREMENT")
        assertThrows(IllegalArgumentException::class.java) { HttpPerformanceNodeHeapControl.request(control, runId, drained) }
        HttpPerformanceNodeHeapControl.window(control, runId, "DRAINED")
        HttpPerformanceNodeHeapControl.request(control, runId, drained)
        assertEquals("VERIFIED", poller.poll()?.get("status"))
        assertEquals(2, calls)
        assertNull(poller.poll())
        assertEquals(2, calls)
    }

    @Test
    fun `arbitrary control input and measurement window requests cannot execute management operations`() {
        for ((index, text) in listOf("$runId:VM.system_properties\n", "$runId:PREPARED\n").withIndex()) {
            val control = directory("protocol$index")
            HttpPerformanceNodeHeapControl.enable(control, runId)
            HttpPerformanceNodeHeapControl.window(control, runId, "MEASUREMENT")
            Files.writeString(HttpPerformanceNodeHeapControl.requestFile(control), text, CREATE_NEW, WRITE)
            Files.createFile(HttpPerformanceNodeHeapControl.requestReadyFile(control))
            var calls = 0
            val poller =
                HttpPerformanceNodeHeapPoller(control, runId, 0, {
                    calls++
                    full
                })
            assertEquals("UNVERIFIED", poller.poll()?.get("status"))
            assertEquals(0, calls)
            assertNull(poller.poll())
        }
    }

    @Test
    fun `host rejects changed full file wrong run member phase incomplete publication and symlink`() {
        val control = directory("host")
        val report = recorder(control, { full }).capture(prepared)
        val json = tree(report)
        assertEquals(true, verifyHttpPerformanceNodeHeapResponse(control, json, runId, 0, prepared)["verified"])
        assertThrows(
            IllegalArgumentException::class.java,
        ) { verifyHttpPerformanceNodeHeapResponse(control, json, "other-run", 0, prepared) }
        assertThrows(IllegalArgumentException::class.java) { verifyHttpPerformanceNodeHeapResponse(control, json, runId, 1, prepared) }
        assertThrows(IllegalArgumentException::class.java) { verifyHttpPerformanceNodeHeapResponse(control, json, runId, 0, drained) }
        val raw = control.resolve("node0-heap-prepared.txt")
        Files.writeString(raw, full.replace("fixture.한글", "fixture.다름"))
        assertThrows(IllegalArgumentException::class.java) { verifyHttpPerformanceNodeHeapResponse(control, json, runId, 0, prepared) }
        Files.writeString(raw, full)
        Files.delete(control.resolve("node0-heap-prepared.ready"))
        assertThrows(IllegalArgumentException::class.java) { verifyHttpPerformanceNodeHeapResponse(control, json, runId, 0, prepared) }
        Files.createFile(control.resolve("node0-heap-prepared.ready"))
        Files.delete(raw)
        val outside = temporary.resolve("outside.txt")
        Files.writeString(outside, full, CREATE_NEW, WRITE)
        Files.createSymbolicLink(raw, outside)
        assertThrows(IllegalArgumentException::class.java) { verifyHttpPerformanceNodeHeapResponse(control, json, runId, 0, prepared) }
        assertEquals(full, Files.readString(outside))
    }

    private fun freshFakeResponses(control: Path) {
        for (member in 0..2) assertEquals("VERIFIED", recorder(control, { full }, member).capture(prepared)["status"])
    }

    @Test
    fun `late complete responses after a one millisecond budget remain unverified even on the first wakeup`() {
        val control = directory("late-parent")
        HttpPerformanceNodeHeapControl.enable(control, runId)
        val gate = HttpPerformanceNodeHeapGate()
        gate.preparationAttempt()
        var now = -1_000_000_000L
        var pauses = 0
        val result =
            captureHttpPerformanceNodeHeapResponses(
                control,
                runId,
                prepared,
                1,
                gate,
                nanoTime = { now },
                pauseNs = { requested ->
                    assertEquals(1_000_000L, requested) // Parent requests only its remaining budget.
                    pauses++
                    now += 10_000_000L // Model a late wakeup; all three node files completed in the meantime.
                    freshFakeResponses(control)
                },
                publishRequest = { HttpPerformanceNodeHeapControl.request(control, runId, prepared) },
            )
        assertEquals(1, pauses)
        val report = tree(result)
        assertEquals("UNVERIFIED", report.path("status").asString())
        assertFalse(report.path("parentDeadline").path("satisfied").asBoolean())
        for (member in 0..2) {
            assertEquals(
                "UNVERIFIED",
                report
                    .path("nodes")
                    .path(member.toString())
                    .path("status")
                    .asString(),
            )
            assertFalse(
                report
                    .path("nodes")
                    .path(member.toString())
                    .path("acceptedBeforeParentDeadline")
                    .asBoolean(),
            )
            assertTrue(Files.exists(control.resolve("${httpPerformanceHeapBaseName(member, prepared)}.ready")))
        }
        assertThrows(IllegalStateException::class.java) { gate.beginMeasurement() }
    }

    @Test
    fun `aggregate deadline is checked even after three individually timely responses`() {
        val control = directory("aggregate-parent")
        HttpPerformanceNodeHeapControl.enable(control, runId)
        val gate = HttpPerformanceNodeHeapGate()
        gate.preparationAttempt()
        var now = Long.MAX_VALUE - 500_000L
        val result =
            captureHttpPerformanceNodeHeapResponses(
                control,
                runId,
                prepared,
                1,
                gate,
                // Each parent clock read models bounded work; final aggregation crosses the 1ms deadline.
                nanoTime = {
                    val sample = now
                    now += 111_112L
                    sample
                },
                pauseNs = { error("responses are already complete, no sleep expected") },
                publishRequest = {
                    HttpPerformanceNodeHeapControl.request(control, runId, prepared)
                    freshFakeResponses(control)
                },
            )
        val report = tree(result)
        assertEquals("UNVERIFIED", report.path("status").asString())
        assertFalse(report.path("parentDeadline").path("satisfied").asBoolean())
        assertTrue(report.path("nodes").toList().all { it.path("status").asString() == "VERIFIED" })
        assertTrue(report.path("failures").toList().any { it.asString().contains("before aggregate success") })
        assertThrows(IllegalStateException::class.java) { gate.beginMeasurement() }
    }

    @Test
    fun `preexisting valid same phase artifacts never masquerade as a fresh successful command`() {
        val control = directory("stale-parent")
        HttpPerformanceNodeHeapControl.enable(control, runId)
        freshFakeResponses(control)
        val artifacts =
            (0..2)
                .flatMap { member ->
                    val base = httpPerformanceHeapBaseName(member, prepared)
                    listOf(control.resolve("$base.txt"), control.resolve("$base.json"), control.resolve("$base.ready"))
                }.associateWith { Files.readAllBytes(it) } // These are tiny synthetic fixture files, including the empty marker.
        val gate = HttpPerformanceNodeHeapGate()
        gate.preparationAttempt()
        var requested = 0
        val result =
            captureHttpPerformanceNodeHeapResponses(
                control,
                runId,
                prepared,
                1000,
                gate,
                nanoTime = { 0L },
                pauseNs = { error("preflight collision must not wait") },
                publishRequest = {
                    requested++
                    HttpPerformanceNodeHeapControl.request(control, runId, prepared)
                },
            )
        assertEquals(0, requested)
        assertFalse(Files.exists(HttpPerformanceNodeHeapControl.requestFile(control)))
        val report = tree(result)
        assertEquals("UNVERIFIED", report.path("status").asString())
        assertTrue(report.path("failures").toList().any { it.asString().contains("preexisting phase artifacts") })
        artifacts.forEach { (file, bytes) -> assertArrayEquals(bytes, Files.readAllBytes(file)) }
        assertThrows(IllegalStateException::class.java) { gate.beginMeasurement() }
    }

    @Test
    fun `fresh complete phase artifacts within the parent deadline still verify without wall clock mixing`() {
        val control = directory("fresh-parent")
        HttpPerformanceNodeHeapControl.enable(control, runId)
        val gate = HttpPerformanceNodeHeapGate()
        gate.preparationAttempt()
        val result =
            captureHttpPerformanceNodeHeapResponses(
                control,
                runId,
                prepared,
                1000,
                gate,
                nanoTime = { -123L },
                pauseNs = { error("fresh synchronous fake responses need no sleep") },
                publishRequest = {
                    HttpPerformanceNodeHeapControl.request(control, runId, prepared)
                    freshFakeResponses(control)
                },
            )
        val report = tree(result)
        assertEquals("VERIFIED", report.path("status").asString())
        assertTrue(report.path("parentDeadline").path("satisfied").asBoolean())
        assertTrue(report.path("nodes").toList().all { it.path("acceptedBeforeParentDeadline").asBoolean() })
        gate.beginMeasurement()
    }
}
