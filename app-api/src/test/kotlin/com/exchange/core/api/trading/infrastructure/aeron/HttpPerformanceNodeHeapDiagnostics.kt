package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.io.FilterOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.management.ManagementFactory
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import javax.management.ObjectName

internal const val HTTP_PERFORMANCE_NODE_HEAP_MAX_BYTES = 8L * 1024 * 1024
private const val HEAP_ENABLE = "node-heap-diagnostic.enabled"
private const val HEAP_WINDOW = "node-heap-diagnostic.window"
private const val HEAP_REQUEST = "node-heap-diagnostic-request"
private const val HEAP_OPERATION = "gcClassHistogram"
private val heapMapper by lazy {
    JsonMapper
        .builder(
            JsonFactory
                .builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints
                        .builder()
                        .maxNestingDepth(16)
                        .maxStringLength(8192)
                        .build(),
                ).build(),
        ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()
}

internal enum class HttpPerformanceNodeHeapPhase { PREPARED, POST_LOAD_DRAINED }

/** Caller owns the preparation/drain facts; this gate prevents capture during its measurement window. */
internal class HttpPerformanceNodeHeapGate {
    private var window = "PREPARATION"
    private var preparedAttempted = false
    private var drainedAttempted = false
    private var unverified = false

    fun preparationAttempt(): HttpPerformanceNodeHeapPhase {
        check(window == "PREPARATION" && !preparedAttempted)
        preparedAttempted = true
        return HttpPerformanceNodeHeapPhase.PREPARED
    }

    fun beginMeasurement() {
        check(window == "PREPARATION" && !unverified) { "미확인 live heap 진단 뒤에는 부하를 시작하지 않습니다" }
        window = "MEASUREMENT"
    }

    fun drainedAttempt(): HttpPerformanceNodeHeapPhase {
        check(window == "MEASUREMENT" && !drainedAttempted)
        drainedAttempted = true
        window = "DRAINED"
        return HttpPerformanceNodeHeapPhase.POST_LOAD_DRAINED
    }

    fun markUnverified() {
        unverified = true
    }

    fun close() {
        window = "CLOSED"
    }
}

/** No command name, arguments, target MBean, remote address or arbitrary JMX input is accepted. */
internal fun interface HttpPerformanceLiveHistogramProvider {
    fun liveHistogram(): String
}

internal object HttpPerformanceHotSpotLiveHistogram : HttpPerformanceLiveHistogramProvider {
    override fun liveHistogram(): String {
        val server = ManagementFactory.getPlatformMBeanServer()
        val name = ObjectName("com.sun.management:type=DiagnosticCommand")
        if (!server.isRegistered(name)) throw UnsupportedOperationException("DiagnosticCommand MBean unavailable")
        val operation =
            server.getMBeanInfo(name).operations.singleOrNull { it.name == HEAP_OPERATION }
                ?: throw UnsupportedOperationException("gcClassHistogram unavailable")
        check(
            operation.returnType == "java.lang.String" && operation.signature.size == 1 &&
                operation.signature[0].type == "[Ljava.lang.String;" &&
                operation.descriptor.getFieldValue("dcmd.name") == "GC.class_histogram" &&
                operation.descriptor.getFieldValue("dcmd.enabled") == true,
        ) { "unexpected HotSpot diagnostic operation" }
        // -all=false requests full GC. One inspection worker is fixed for this intrusive diagnostic only.
        return server.invoke(
            name,
            HEAP_OPERATION,
            arrayOf<Any>(arrayOf("-all=false", "-parallel=1")),
            arrayOf("[Ljava.lang.String;"),
        ) as? String
            ?: throw IllegalStateException("histogram did not return String")
    }
}

internal data class HttpPerformanceHeapFingerprint(
    val bytes: Long,
    val sha256: String,
)

internal data class HttpPerformanceHeapTable(
    val rows: Long,
    val instances: Long,
    val shallowBytes: Long,
)

internal fun httpPerformanceHeapNoLinks(path: Path) {
    val absolute = path.toAbsolutePath().normalize()
    var current = absolute.root
    for (part in absolute) {
        current = current.resolve(part)
        require(Files.exists(current, NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) { "heap evidence symlink/missing path: $current" }
    }
}

internal fun httpPerformanceHeapFingerprint(
    file: Path,
    maximum: Long = HTTP_PERFORMANCE_NODE_HEAP_MAX_BYTES,
): HttpPerformanceHeapFingerprint {
    httpPerformanceHeapNoLinks(file)
    val before = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(before.isRegularFile && before.size() in 1..maximum)
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            bytes = Math.addExact(bytes, count.toLong())
            require(bytes <= maximum) { "heap artifact exceeded finite byte limit" }
            digest.update(buffer, 0, count)
        }
    }
    val after = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(
        after.isRegularFile && bytes == before.size() && bytes == after.size() && before.fileKey() == after.fileKey() &&
            before.lastModifiedTime() == after.lastModifiedTime(),
    ) { "heap evidence changed during full EOF hash" }
    return HttpPerformanceHeapFingerprint(bytes, HexFormat.of().formatHex(digest.digest()))
}

/** Strict small HotSpot text framing; no class rows retained, and Total + actual EOF are mandatory. */
internal fun verifyHttpPerformanceHeapTable(file: Path): HttpPerformanceHeapTable {
    val before = httpPerformanceHeapFingerprint(file)
    val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    BufferedReader(InputStreamReader(Files.newInputStream(file, NOFOLLOW_LINKS), decoder)).use { input ->
        fun line(): String? {
            val result = StringBuilder()
            while (true) {
                val character = input.read()
                if (character == -1) {
                    require(result.isEmpty()) { "histogram missing final LF" }
                    return null
                }
                if (character == '\n'.code) return result.toString()
                require(character != '\r'.code && result.length < 16_384) { "invalid/oversized histogram line" }
                result.append(character.toChar())
            }
        }
        require(
            line()?.trim()?.matches(Regex("num\\s+#instances\\s+#bytes\\s+class name \\(module\\)")) == true,
        ) { "histogram header missing" }
        require(line()?.matches(Regex("-+")) == true) { "histogram separator missing" }
        var rows = 0L
        var instances = 0L
        var bytes = 0L
        val rowPattern = Regex("\\s*(\\d+):\\s+(\\d+)\\s+(\\d+)\\s+(.+)")
        val totalPattern = Regex("Total\\s+(\\d+)\\s+(\\d+)\\s*")
        while (true) {
            val text = line() ?: error("histogram Total/EOF missing")
            val total = totalPattern.matchEntire(text)
            if (total != null) {
                require(
                    rows > 0 && total.groupValues[1].toLong() == instances && total.groupValues[2].toLong() == bytes,
                ) { "histogram Total mismatch" }
                require(line() == null) { "histogram trailing output" }
                require(httpPerformanceHeapFingerprint(file) == before) { "histogram changed during framing scan" }
                return HttpPerformanceHeapTable(rows, instances, bytes)
            }
            val row = rowPattern.matchEntire(text) ?: error("histogram row/Total malformed")
            rows = Math.addExact(rows, 1)
            require(rows <= 100_000 && row.groupValues[1].toLong() == rows) { "histogram rank/count bound" }
            instances = Math.addExact(instances, row.groupValues[2].toLong())
            bytes = Math.addExact(bytes, row.groupValues[3].toLong())
        }
    }
    error("histogram ended without Total")
}

private fun heapFailure(error: Throwable) = "${error.javaClass.simpleName}: ${error.message?.take(2048)}"

private fun heapGcSnapshot(): List<Map<String, Any?>> =
    ManagementFactory.getGarbageCollectorMXBeans().map {
        mapOf("name" to it.name, "collectionCount" to it.collectionCount, "collectionTimeMs" to it.collectionTime)
    }

private fun heapBoundedOutput(
    target: OutputStream,
    maximum: Long,
): OutputStream =
    object : FilterOutputStream(target) {
        private var bytes = 0L

        override fun write(value: Int) {
            if (bytes >= maximum) error("histogram raw byte limit; partial file is not verified")
            out.write(value)
            bytes++
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            val permitted = minOf(length.toLong(), maximum - bytes).toInt()
            if (permitted > 0) {
                out.write(buffer, offset, permitted)
                bytes += permitted
            }
            check(permitted == length) { "histogram raw byte limit; partial file is not verified" }
        }
    }

private fun heapWriteRaw(
    file: Path,
    text: String,
) {
    // The provider already allocated one whole String. Only encoding/writing buffers and disk bytes are bounded here.
    Files.newOutputStream(file, CREATE_NEW, WRITE).use { target ->
        val bounded = heapBoundedOutput(target, HTTP_PERFORMANCE_NODE_HEAP_MAX_BYTES)
        val encoder = UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        OutputStreamWriter(bounded, encoder).use { writer -> text.reader().use { it.copyTo(writer, 4096) } }
    }
}

private fun heapWriteJson(
    file: Path,
    value: Any,
) {
    Files.newOutputStream(file, CREATE_NEW, WRITE).use { heapMapper.writeValue(heapBoundedOutput(it, 65_536), value) }
}

internal fun httpPerformanceHeapReadJson(file: Path): JsonNode {
    httpPerformanceHeapNoLinks(file)
    require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) in 1..65_536)
    return Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
        val bytes = input.readNBytes(65_537)
        require(bytes.size in 1..65_536 && input.read() == -1) { "metadata byte/EOF bound" }
        heapMapper.readTree(bytes)
    }
}

internal fun httpPerformanceHeapBaseName(
    member: Int,
    phase: HttpPerformanceNodeHeapPhase,
) = "node$member-heap-${phase.name.lowercase()}"

internal class HttpPerformanceNodeHeapRecorder(
    private val control: Path,
    private val runId: String,
    private val member: Int,
    private val provider: HttpPerformanceLiveHistogramProvider = HttpPerformanceHotSpotLiveHistogram,
    private val nanoTime: () -> Long = System::nanoTime,
    private val gcSnapshot: () -> List<Map<String, Any?>> = ::heapGcSnapshot,
) {
    private val attempted = mutableSetOf<HttpPerformanceNodeHeapPhase>()

    init {
        require(runId.matches(Regex("[a-z0-9-]{1,64}")) && member in 0..2)
        httpPerformanceHeapNoLinks(control)
    }

    fun capture(phase: HttpPerformanceNodeHeapPhase): Map<String, Any?> {
        check(attempted.size < 2 && attempted.add(phase)) { "heap diagnostic phase already consumed" }
        val base = httpPerformanceHeapBaseName(member, phase)
        val raw = control.resolve("$base.txt")
        val failures = mutableListOf<String>()
        val startedAt = Instant.now().toString()
        val startedNs = nanoTime()
        var operationStartedNs: Long? = null
        var operationFinishedNs: Long? = null
        var before: List<Map<String, Any?>>? = null
        var after: List<Map<String, Any?>>? = null
        var fingerprint: HttpPerformanceHeapFingerprint? = null
        var table: HttpPerformanceHeapTable? = null
        var rawComplete = false
        try {
            require(listOf(raw, control.resolve("$base.json"), control.resolve("$base.ready")).none { Files.exists(it, NOFOLLOW_LINKS) }) {
                "heap artifact exists; command not executed"
            }
            before = gcSnapshot()
            operationStartedNs = nanoTime()
            val text =
                try {
                    provider.liveHistogram()
                } finally {
                    operationFinishedNs = nanoTime()
                    try {
                        after = gcSnapshot()
                    } catch (error: Exception) {
                        failures.add("GC after: ${heapFailure(error)}")
                    }
                }
            heapWriteRaw(raw, text)
            rawComplete = true
            fingerprint = httpPerformanceHeapFingerprint(raw)
            table = verifyHttpPerformanceHeapTable(raw)
        } catch (error: Exception) {
            failures.add(heapFailure(error))
        } catch (_: OutOfMemoryError) {
            // Best effort only. An already exhausted node may be unable to allocate or publish even this small failure.
            failures.add("OutOfMemoryError during intrusive histogram or preservation")
        }
        if (fingerprint == null && Files.isRegularFile(raw, NOFOLLOW_LINKS)) {
            try {
                fingerprint = httpPerformanceHeapFingerprint(raw)
            } catch (
                error: Exception,
            ) {
                failures.add("partial raw: ${heapFailure(error)}")
            }
        }
        val finishedNs = nanoTime()
        val finishedAt = Instant.now().toString()
        if (finishedNs - startedNs < 0) failures.add("node nanoTime regressed")
        val verified = failures.isEmpty() && rawComplete && fingerprint != null && table != null && before != null && after != null
        val report =
            linkedMapOf<String, Any?>(
                "kind" to "PERFORMANCE_NODE_HEAP_DIAGNOSTIC",
                "schemaVersion" to 1,
                "runId" to runId,
                "memberId" to member,
                "phase" to phase.name,
                "status" to if (verified) "VERIFIED" else "UNVERIFIED",
                "intrusive" to true,
                "baselineComparable" to false,
                "command" to "GC.class_histogram -all=false -parallel=1",
                "scope" to "reported class shallow heap bytes; no owner retained graph/native/RSS attribution",
                "fullGcEvidence" to "REQUESTED_NOT_PROVEN_BY_RETURNED_TABLE",
                "populationCompleteness" to "NOT_PROVEN; native undercount/GC-locker warnings may exist only in VM log",
                "clockScope" to "this node JVM nanoTime only; no parent/peer origin subtraction",
                "startedAt" to startedAt,
                "finishedAt" to finishedAt,
                "startedNs" to startedNs,
                "finishedNs" to finishedNs,
                "operationStartedNs" to operationStartedNs,
                "operationFinishedNs" to operationFinishedNs,
                "elapsedNs" to finishedNs - startedNs,
                "gcBefore" to before,
                "gcAfter" to after,
                "javaVersion" to System.getProperty("java.version"),
                "javaVm" to System.getProperty("java.vm.name"),
                "raw" to
                    mapOf(
                        "artifactFile" to "$base.txt",
                        "bytes" to fingerprint?.bytes,
                        "sha256" to fingerprint?.sha256,
                        "complete" to rawComplete,
                        "eofAndTableVerified" to (table != null),
                        "rows" to table?.rows,
                        "instances" to table?.instances,
                        "shallowBytes" to table?.shallowBytes,
                    ),
                "failures" to failures.toList(),
                "responseWritten" to true,
            )
        try {
            heapWriteJson(control.resolve("$base.json"), report)
            // Atomic CREATE_NEW empty marker is published only after response/raw streams are closed.
            Files.createFile(control.resolve("$base.ready"))
        } catch (error: Exception) {
            report["status"] = "UNVERIFIED"
            report["responseWritten"] = false
            report["failures"] = failures + "response persistence: ${heapFailure(error)}"
        }
        return report
    }
}

/** Files are the existing run-owned test control transport. No network/HTTP/remote JMX endpoint is added. */
internal object HttpPerformanceNodeHeapControl {
    fun enable(
        control: Path,
        runId: String,
    ) {
        httpPerformanceHeapNoLinks(control)
        Files.writeString(control.resolve(HEAP_ENABLE), "NODE_HEAP_LIVE_DIAGNOSTIC_V1:$runId\n", CREATE_NEW, WRITE)
        Files.writeString(control.resolve(HEAP_WINDOW), "$runId:PREPARATION\n", CREATE_NEW, WRITE)
    }

    fun window(
        control: Path,
        runId: String,
        window: String,
    ) {
        require(window in setOf("MEASUREMENT", "DRAINED"))
        require(readSmall(control.resolve(HEAP_ENABLE)) == "NODE_HEAP_LIVE_DIAGNOSTIC_V1:$runId\n")
        val temporary = control.resolve("node-heap-window-$window.tmp")
        Files.writeString(temporary, "$runId:$window\n", CREATE_NEW, WRITE)
        Files.move(temporary, control.resolve(HEAP_WINDOW), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun request(
        control: Path,
        runId: String,
        phase: HttpPerformanceNodeHeapPhase,
    ) {
        val expectedWindow = if (phase == HttpPerformanceNodeHeapPhase.PREPARED) "PREPARATION" else "DRAINED"
        require(readSmall(control.resolve(HEAP_WINDOW)) == "$runId:$expectedWindow\n")
        // The container bind can expose new bytes with the replaced file's cached old length.
        // Each phase therefore owns a fresh immutable body; publish readiness only after closing it.
        Files.writeString(requestFile(control, phase), "$runId:${phase.name}\n", CREATE_NEW, WRITE)
        Files.createFile(requestReadyFile(control, phase))
    }

    fun readSmall(file: Path): String {
        httpPerformanceHeapNoLinks(file)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) in 1..256)
        return Files.readString(file, UTF_8)
    }

    fun open(
        control: Path,
        runId: String,
        member: Int,
    ): HttpPerformanceNodeHeapPoller? =
        if (Files.exists(control.resolve(HEAP_ENABLE), NOFOLLOW_LINKS)) HttpPerformanceNodeHeapPoller(control, runId, member) else null

    internal fun requestFile(
        control: Path,
        phase: HttpPerformanceNodeHeapPhase = HttpPerformanceNodeHeapPhase.PREPARED,
    ): Path = control.resolve("$HEAP_REQUEST-${phase.name.lowercase()}.txt")

    internal fun requestReadyFile(
        control: Path,
        phase: HttpPerformanceNodeHeapPhase = HttpPerformanceNodeHeapPhase.PREPARED,
    ): Path = control.resolve("$HEAP_REQUEST-${phase.name.lowercase()}.ready")

    internal fun enableFile(control: Path) = control.resolve(HEAP_ENABLE)

    internal fun windowFile(control: Path) = control.resolve(HEAP_WINDOW)
}

internal class HttpPerformanceNodeHeapPoller(
    private val control: Path,
    private val runId: String,
    private val member: Int,
    provider: HttpPerformanceLiveHistogramProvider = HttpPerformanceHotSpotLiveHistogram,
) {
    private val recorder = HttpPerformanceNodeHeapRecorder(control, runId, member, provider)
    private val consumed = mutableSetOf<HttpPerformanceNodeHeapPhase>()
    private var protocolFailed = false

    fun poll(): Map<String, Any?>? {
        if (protocolFailed) return null
        val phase =
            HttpPerformanceNodeHeapPhase.entries.firstOrNull {
                it !in consumed && Files.exists(HttpPerformanceNodeHeapControl.requestReadyFile(control, it), NOFOLLOW_LINKS)
            } ?: return null
        return try {
            val text = HttpPerformanceNodeHeapControl.readSmall(HttpPerformanceNodeHeapControl.requestFile(control, phase))
            require(consumed.size < 2)
            require(
                HttpPerformanceNodeHeapControl.readSmall(HttpPerformanceNodeHeapControl.enableFile(control)) ==
                    "NODE_HEAP_LIVE_DIAGNOSTIC_V1:$runId\n",
            )
            require(text == "$runId:${phase.name}\n") {
                "unknown heap phase/run: length=${text.length} value=${text.take(256).replace("\n", "\\n") }"
            }
            val expectedWindow = if (phase == HttpPerformanceNodeHeapPhase.PREPARED) "PREPARATION" else "DRAINED"
            require(
                HttpPerformanceNodeHeapControl.readSmall(HttpPerformanceNodeHeapControl.windowFile(control)) == "$runId:$expectedWindow\n",
            ) {
                "heap request prohibited inside measurement window"
            }
            consumed.add(phase) // A failure consumes the finite attempt too; no concealed retry/GC loop.
            recorder.capture(phase)
        } catch (error: Exception) {
            protocolFailed = true
            mapOf(
                "kind" to "PERFORMANCE_NODE_HEAP_DIAGNOSTIC",
                "schemaVersion" to 1,
                "runId" to runId,
                "memberId" to member,
                "status" to "UNVERIFIED",
                "responseWritten" to false,
                "intrusive" to true,
                "baselineComparable" to false,
                "failures" to listOf(heapFailure(error)),
            )
        }
    }
}

/** Host re-reads full bytes+EOF; a saved node assertion or prefix/hash alone is insufficient. */
internal fun verifyHttpPerformanceNodeHeapResponse(
    control: Path,
    report: JsonNode,
    runId: String,
    member: Int,
    phase: HttpPerformanceNodeHeapPhase,
): Map<String, Any?> {
    require(
        report.path("kind").asString() == "PERFORMANCE_NODE_HEAP_DIAGNOSTIC" &&
            httpPerformanceCheckpointMemberMatches(report.path("schemaVersion"), 1),
    )
    require(
        report.path("runId").asString() == runId && httpPerformanceCheckpointMemberMatches(report.path("memberId"), member) &&
            report.path("phase").asString() == phase.name && report.path("status").asString() == "VERIFIED" &&
            report.path("responseWritten").isBoolean && report.path("responseWritten").asBoolean() &&
            report.path("intrusive").isBoolean && report.path("intrusive").asBoolean() &&
            report.path("baselineComparable").isBoolean && !report.path("baselineComparable").asBoolean() &&
            report.path("failures").isArray && report.path("failures").isEmpty,
    ) {
        "node heap diagnostic is incomplete/failed or belongs to a different run/member/phase"
    }

    fun integer(node: JsonNode): Long {
        require(node.isIntegralNumber && node.canConvertToLong())
        return node.asLong()
    }
    val start = integer(report.path("startedNs"))
    val finish = integer(report.path("finishedNs"))
    require(finish - start >= 0 && integer(report.path("elapsedNs")) == finish - start)
    val operationStart = integer(report.path("operationStartedNs"))
    val operationFinish = integer(report.path("operationFinishedNs"))
    require(operationStart - start >= 0 && operationFinish - operationStart >= 0 && finish - operationFinish >= 0) {
        "node-local operation/capture clock order"
    }
    for (field in listOf("gcBefore", "gcAfter")) {
        val counters = report.path(field)
        require(counters.isArray && counters.size() in 1..16)
        counters.forEach {
            require(it.path("name").isString && integer(it.path("collectionCount")) >= -1 && integer(it.path("collectionTimeMs")) >= -1)
        }
    }
    val base = httpPerformanceHeapBaseName(member, phase)
    val ready = control.resolve("$base.ready")
    httpPerformanceHeapNoLinks(ready)
    require(Files.isRegularFile(ready, NOFOLLOW_LINKS) && Files.size(ready) == 0L) { "response was not completely published" }
    val raw = report.path("raw")
    val expected = "$base.txt"
    require(
        raw.path("artifactFile").asString() == expected && raw.path("complete").isBoolean && raw.path("complete").asBoolean() &&
            raw.path("eofAndTableVerified").isBoolean && raw.path("eofAndTableVerified").asBoolean(),
    )
    val file = control.resolve(expected)
    val fingerprint = httpPerformanceHeapFingerprint(file)
    require(integer(raw.path("bytes")) == fingerprint.bytes && raw.path("sha256").asString() == fingerprint.sha256)
    val table = verifyHttpPerformanceHeapTable(file)
    require(
        integer(raw.path("rows")) == table.rows && integer(raw.path("instances")) == table.instances &&
            integer(raw.path("shallowBytes")) == table.shallowBytes,
    )
    return mapOf(
        "verified" to true,
        "artifactFile" to "control/$expected",
        "bytes" to fingerprint.bytes,
        "sha256" to fingerprint.sha256,
        "table" to table,
        "intrusive" to true,
        "baselineComparable" to false,
    )
}

/** The Cluster parent path and deterministic fixtures share this one bounded, phase-specific collection loop. */
internal fun captureHttpPerformanceNodeHeapResponses(
    control: Path,
    runId: String,
    phase: HttpPerformanceNodeHeapPhase,
    timeoutMs: Long,
    gate: HttpPerformanceNodeHeapGate,
    nanoTime: () -> Long = System::nanoTime,
    pauseNs: (Long) -> Unit = { TimeUnit.NANOSECONDS.sleep(it) },
    publishRequest: () -> Unit = {
        if (phase == HttpPerformanceNodeHeapPhase.POST_LOAD_DRAINED) HttpPerformanceNodeHeapControl.window(control, runId, "DRAINED")
        HttpPerformanceNodeHeapControl.request(control, runId, phase)
    },
): MutableMap<String, Any?> {
    require(timeoutMs in 1..60_000)
    val startedNs = nanoTime()
    val timeoutNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    val results = linkedMapOf<Int, Map<String, Any?>>()
    val failures = mutableListOf<String>()

    fun remainingNs(): Long {
        val elapsed = nanoTime() - startedNs
        check(elapsed >= 0 && elapsed < timeoutNs) { "parent heap diagnostic deadline expired/regressed; response not accepted" }
        return timeoutNs - elapsed
    }
    try {
        httpPerformanceHeapNoLinks(control)
        // One attempt per phase in one run-owned directory: any prior artifact invalidates this attempt.
        // Never send a new request whose old, valid response could be mistaken for the new command result.
        val existing =
            (0..2)
                .flatMap { member ->
                    val base = httpPerformanceHeapBaseName(member, phase)
                    listOf("$base.txt", "$base.json", "$base.ready")
                }.filter { Files.exists(control.resolve(it), NOFOLLOW_LINKS) }
        check(existing.isEmpty()) { "preexisting phase artifacts; no heap command requested: $existing" }
        check(
            listOf(
                HttpPerformanceNodeHeapControl.requestFile(control, phase),
                HttpPerformanceNodeHeapControl.requestReadyFile(control, phase),
            ).none { Files.exists(it, NOFOLLOW_LINKS) },
        ) { "preexisting phase request; no heap command requested" }
        remainingNs()
        publishRequest()
        while (results.size < 3) {
            remainingNs() // A late wakeup must not read three responses and break as success.
            for (member in 0..2) {
                if (member in results) continue
                remainingNs() // Check immediately before examining/accepting each member's evidence.
                val base = httpPerformanceHeapBaseName(member, phase)
                val response = control.resolve("$base.json")
                if (!Files.exists(control.resolve("$base.ready"), NOFOLLOW_LINKS)) continue
                var nodeReport: JsonNode? = null
                var artifact: Map<String, Any?>? = null
                try {
                    val report = httpPerformanceHeapReadJson(response)
                    nodeReport = report
                    artifact = verifyHttpPerformanceNodeHeapResponse(control, report, runId, member, phase)
                    val responseFingerprint = httpPerformanceHeapFingerprint(response, 65_536)
                    remainingNs() // Full EOF/hash/table verification is part of the parent deadline too.
                    results[member] =
                        mapOf(
                            "status" to "VERIFIED",
                            "report" to report,
                            "rawEvidence" to artifact,
                            "acceptedBeforeParentDeadline" to true,
                            "responseArtifactFile" to "control/${response.fileName}",
                            "responseFingerprint" to responseFingerprint,
                        )
                } catch (error: Exception) {
                    results[member] =
                        mapOf(
                            "status" to "UNVERIFIED",
                            "responseArtifactFile" to "control/${response.fileName}",
                            "report" to nodeReport,
                            "rawEvidence" to artifact,
                            "acceptedBeforeParentDeadline" to false,
                            "failure" to heapFailure(error),
                        )
                }
            }
            if (results.size == 3) break
            pauseNs(minOf(TimeUnit.MILLISECONDS.toNanos(25), remainingNs()))
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        failures.add(heapFailure(error))
    }
    for (member in 0..2) {
        if (member !in results) {
            results[member] =
                mapOf(
                    "status" to "UNVERIFIED",
                    "responseArtifactFile" to "control/${httpPerformanceHeapBaseName(member, phase)}.json",
                    "rawArtifactFile" to "control/${httpPerformanceHeapBaseName(member, phase)}.txt",
                    "acceptedBeforeParentDeadline" to false,
                    "failure" to "no fresh complete response accepted before parent deadline/interruption; invocation may still be running",
                )
        }
    }
    val finishedNs = nanoTime()
    val elapsedNs = finishedNs - startedNs
    val withinDeadline = elapsedNs >= 0 && elapsedNs < timeoutNs // Final aggregate boundary, even with all three responses.
    if (!withinDeadline) failures.add("parent deadline expired/regressed before aggregate success")
    val verified = withinDeadline && failures.isEmpty() && results.values.all { it["status"] == "VERIFIED" }
    if (!verified) gate.markUnverified()
    return linkedMapOf(
        "kind" to "PERFORMANCE_NODE_HEAP_CAPTURE",
        "schemaVersion" to 1,
        "runId" to runId,
        "phase" to phase.name,
        "status" to if (verified) "VERIFIED" else "UNVERIFIED",
        "intrusive" to true,
        "baselineComparable" to false,
        "nodes" to results.toMap(),
        "failures" to failures.toList(),
        "parentDeadline" to
            mapOf(
                "clockScope" to "parent JVM nanoTime only",
                "timeoutNs" to timeoutNs,
                "startedNs" to startedNs,
                "finishedNs" to finishedNs,
                "elapsedNs" to elapsedNs,
                "satisfied" to withinDeadline,
            ),
    )
}
