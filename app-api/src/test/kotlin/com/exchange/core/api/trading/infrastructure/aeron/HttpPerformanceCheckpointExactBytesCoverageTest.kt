package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * C1/C2 from independent-review-checkpoint-byte-20261027.md.
 * Synthetic files stay below 140 KiB each; no node, Docker, child JVM or workload is involved.
 * Padding uses 40 strings of at most 4,096 characters inside one bounded row.
 */
class HttpPerformanceCheckpointExactBytesCoverageTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `C1 두 업무 object 모두65535 65536 65537과두buffer초과를전량비교한다`() {
        for (pointer in BUSINESS_POINTERS) {
            for (length in listOf(65_535, 65_536, 65_537, MULTI_CHUNK_SPAN_BYTES)) {
                val body = boundedBusinessObject(pointer, length)
                val scans = writeAndScan { member -> document(member, pointer, body) }
                assertTrue(scans.all { it.businessByteSpans.getValue(pointer).length == length.toLong() })
                val probes = linkedMapOf<Int, ReadProbe>()

                assertTrue(compareHttpPerformanceCheckpointBusinessBytes(scans, trackedOpen(probes)))

                assertWholeOriginalReadAndClosed(scans, probes)
            }
        }
    }

    @Test
    fun `C1 첫째둘째buffer뒤의같은길이행차이를놓치지않고모든원본EOF를확인한다`() {
        for (pointer in BUSINESS_POINTERS) {
            val original = boundedBusinessObject(pointer, MULTI_CHUNK_SPAN_BYTES)
            for (boundary in listOf(65_536, 131_072)) {
                // This is an unused row string, after the boundary and before the final delimiter.
                val changedAt = original.indexOf('A', boundary + 17)
                check(changedAt > boundary && changedAt < original.lastIndex)
                val changed = original.replaceRange(changedAt, changedAt + 1, "B")
                assertEquals(original.toByteArray(Charsets.UTF_8).size, changed.toByteArray(Charsets.UTF_8).size)
                val scans = writeAndScan { member -> document(member, pointer, if (member == 2) changed else original) }
                assertTrue(scans.all { it.businessByteSpans.getValue(pointer).length == MULTI_CHUNK_SPAN_BYTES.toLong() })
                // Each reference's saved SHA describes its own valid source, including node 2's B.
                val probes = linkedMapOf<Int, ReadProbe>()

                assertFalse(compareHttpPerformanceCheckpointBusinessBytes(scans, trackedOpen(probes)))

                assertWholeOriginalReadAndClosed(scans, probes)
            }
        }
    }

    @Test
    fun `C2 읽기도중EOF I O와interrupt는비교와fallback후보경로모두예외이며전부닫는다`() {
        val previouslyInterrupted = Thread.interrupted()
        try {
            for (scenario in FaultScenario.entries) {
                for (location in FaultLocation.entries) {
                    for (kind in FaultKind.entries) {
                        Thread.interrupted()
                        val original = boundedBusinessObject("/applied/state", MULTI_CHUNK_SPAN_BYTES)
                        val other =
                            when (scenario) {
                                FaultScenario.EQUAL -> original

                                // Same semantic row and same span length; the first chunk differs.
                                FaultScenario.RAW_MISMATCH -> original.replace("\"a\":1,\"b\":2", "\"b\":2,\"a\":1")

                                // Same semantic row but different span length: comparable=false from entry.
                                FaultScenario.REPRESENTATION_MISMATCH -> original.replaceFirst("{", "{ ")
                            }
                        if (scenario == FaultScenario.RAW_MISMATCH) {
                            check(other != original && other.length == original.length)
                        }
                        val scans = writeAndScan { member -> document(member, "/applied/state", if (member == 2) other else original) }
                        // Reopening these intact original files for semantic fallback could succeed.
                        val expectedRow = firstRequestRow(scans.first().reference)
                        for (scan in scans.drop(1)) assertEquals(expectedRow, firstRequestRow(scan.reference))
                        val target = scans[2]
                        val faultAt =
                            when (location) {
                                FaultLocation.SPAN -> target.businessByteSpans.getValue("/applied/state").startInclusive + 65_536 + 128
                                FaultLocation.TAIL -> target.businessByteSpans.getValue("/trading").endExclusive + 129
                            }
                        check(faultAt < target.reference.bytes)
                        val fault = ReadFault(kind, faultAt)
                        val probes = linkedMapOf<Int, ReadProbe>()
                        val open = trackedOpen(probes, fault)
                        val case = "$scenario/$location/$kind"
                        try {
                            when (kind) {
                                FaultKind.EOF -> {
                                    val failure =
                                        assertFailsWith<IllegalArgumentException>(case) {
                                            compareHttpPerformanceCheckpointBusinessBytes(scans, open)
                                        }
                                    assertTrue(
                                        failure.message.orEmpty().contains("EOF") || failure.message.orEmpty().contains("bytes"),
                                        case,
                                    )
                                }

                                FaultKind.IO -> {
                                    assertSame(
                                        fault.error,
                                        assertFailsWith<IOException>(case) {
                                            compareHttpPerformanceCheckpointBusinessBytes(scans, open)
                                        },
                                        case,
                                    )
                                }

                                FaultKind.INTERRUPTED_IO, FaultKind.INTERRUPTED_IO_WITH_FLAG -> {
                                    assertSame(
                                        fault.error,
                                        assertFailsWith<InterruptedIOException>(
                                            case,
                                        ) { compareHttpPerformanceCheckpointBusinessBytes(scans, open) },
                                        case,
                                    )
                                }

                                FaultKind.INTERRUPT_FLAG -> {
                                    assertFailsWith<InterruptedException>(case) {
                                        compareHttpPerformanceCheckpointBusinessBytes(scans, open)
                                    }
                                }
                            }
                            assertTrue(fault.triggered, case)
                            assertEquals(faultAt, probes.getValue(2).returnedBytes, case)
                            assertEquals(kind.setsInterruptFlag, Thread.currentThread().isInterrupted, case)
                            assertEquals(setOf(0, 1, 2), probes.keys, case)
                            for (probe in probes.values) assertEquals(1, probe.closeCalls, case)
                        } finally {
                            Thread.interrupted()
                        }
                    }
                }
            }
        } finally {
            Thread.interrupted()
            if (previouslyInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun boundedBusinessObject(
        pointer: String,
        length: Int,
    ): String {
        val prefix =
            if (pointer == "/applied/state") {
                """{"balances":[],"preparations":[],"pendingOrders":[],"transactions":[],"requests":[{"a":1,"b":2,"padding":["""
            } else {
                """{"blocked":false,"currentReservations":[],"books":[],"completions":[],"matchingResults":[{"a":1,"b":2,"padding":["""
            }
        val suffix = "]}]}"
        val empty = prefix + List(40) { "\"\"" }.joinToString(",") + suffix
        var remaining = length - empty.length
        require(remaining in 0..(40 * 4_096))
        val strings =
            List(40) {
                val size = minOf(remaining, 4_096)
                remaining -= size
                "\"${"A".repeat(size)}\""
            }
        check(remaining == 0)
        return (prefix + strings.joinToString(",") + suffix).also {
            check(it.toByteArray(Charsets.UTF_8).size == length)
        }
    }

    private fun document(
        member: Int,
        pointer: String,
        business: String,
    ): String {
        val state =
            if (pointer ==
                "/applied/state"
            ) {
                business
            } else {
                """{"balances":[],"preparations":[],"pendingOrders":[],"requests":[],"transactions":[]}"""
            }
        val trading =
            if (pointer ==
                "/trading"
            ) {
                business
            } else {
                """{"blocked":false,"currentReservations":[],"books":[],"completions":[],"matchingResults":[]}"""
            }
        return """{"kind":"PERFORMANCE_CHECKPOINT","runId":"coverage-test","memberId":$member,"label":"final",""" +
            """"applied":{"position":41,"state":$state},"trading":$trading}""" +
            " ".repeat(4_096)
    }

    private fun writeAndScan(document: (Int) -> String): List<HttpPerformanceCheckpointScan> =
        (0..2).map { member ->
            val bytes = document(member).toByteArray(Charsets.UTF_8)
            check(bytes.size < 140 * 1_024)
            val path = directory.resolve("node$member-checkpoint-final.json")
            Files.write(path, bytes)
            val reference =
                HttpPerformanceCheckpointFileReference(
                    path,
                    "coverage-test",
                    member,
                    "final",
                    bytes.size.toLong(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                )
            HttpPerformanceCheckpointReader.scanRows(reference, onRow = { _, _ -> })
        }

    private fun firstRequestRow(reference: HttpPerformanceCheckpointFileReference): JsonNode {
        var result: JsonNode? = null
        HttpPerformanceCheckpointReader.scanRows(reference, onRow = { pointer, row ->
            if (pointer == "/applied/state/requests/0") result = row
        })
        return checkNotNull(result)
    }

    private fun trackedOpen(
        probes: MutableMap<Int, ReadProbe>,
        node2Fault: ReadFault? = null,
    ): (HttpPerformanceCheckpointFileReference) -> InputStream =
        { reference ->
            val probe = ReadProbe(reference, if (reference.memberId == 2) node2Fault else null)
            check(probes.put(reference.memberId, probe) == null)
            probe
        }

    private fun assertWholeOriginalReadAndClosed(
        scans: List<HttpPerformanceCheckpointScan>,
        probes: Map<Int, ReadProbe>,
    ) {
        assertEquals(setOf(0, 1, 2), probes.keys)
        for (scan in scans) {
            val probe = probes.getValue(scan.reference.memberId)
            assertEquals(scan.reference.bytes, probe.returnedBytes)
            assertTrue(probe.physicalEof)
            assertEquals(1, probe.closeCalls)
        }
    }

    private enum class FaultScenario { EQUAL, RAW_MISMATCH, REPRESENTATION_MISMATCH }

    private enum class FaultLocation { SPAN, TAIL }

    private enum class FaultKind(
        val setsInterruptFlag: Boolean = false,
    ) {
        EOF,
        IO,
        INTERRUPTED_IO,
        INTERRUPTED_IO_WITH_FLAG(true),
        INTERRUPT_FLAG(true),
    }

    private class ReadFault(
        val kind: FaultKind,
        val at: Long,
    ) {
        val error: IOException? =
            when (kind) {
                FaultKind.IO -> IOException("injected read failure")
                FaultKind.INTERRUPTED_IO, FaultKind.INTERRUPTED_IO_WITH_FLAG -> InterruptedIOException("injected interrupted read")
                else -> null
            }
        var triggered = false
    }

    /** Faults affect only this opened stream. Original scanned files and saved SHA stay intact. */
    private class ReadProbe(
        reference: HttpPerformanceCheckpointFileReference,
        private val fault: ReadFault?,
    ) : InputStream() {
        private val source = Files.newInputStream(reference.file)
        var returnedBytes = 0L
            private set
        var physicalEof = false
            private set
        var closeCalls = 0
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val active = fault?.takeUnless { it.triggered && it.kind == FaultKind.INTERRUPT_FLAG }
            if (active != null && returnedBytes >= active.at) {
                active.triggered = true
                if (active.kind.setsInterruptFlag) Thread.currentThread().interrupt()
                if (active.kind == FaultKind.EOF) return -1
                throw checkNotNull(active.error)
            }
            val amount = if (active == null) length else minOf(length.toLong(), active.at - returnedBytes).toInt()
            check(amount > 0)
            val count = source.read(buffer, offset, amount)
            if (count > 0) returnedBytes += count.toLong()
            if (count < 0) physicalEof = true
            if (active?.kind == FaultKind.INTERRUPT_FLAG && returnedBytes >= active.at) {
                active.triggered = true
                Thread.currentThread().interrupt()
            }
            return count
        }

        override fun close() {
            closeCalls++
            source.close()
        }
    }

    companion object {
        private const val MULTI_CHUNK_SPAN_BYTES = 131_329
        private val BUSINESS_POINTERS = listOf("/applied/state", "/trading")
    }
}
