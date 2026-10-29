package com.exchange.core.api.trading.infrastructure.aeron

import java.io.PrintWriter
import java.io.Writer
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicIntegerArray

internal const val HTTP_PERFORMANCE_RUN_HEADER = "X-performance-run-id"
internal const val HTTP_PERFORMANCE_REQUEST_HEADER = "X-performance-request-id"

/** Header correlation is diagnostic metadata; it never changes a command or its identifiers. */
internal data class HttpPerformanceHttpIdentity(
    val runId: String,
    val requestId: String,
) {
    init {
        require(runId.matches(Regex("[a-zA-Z0-9-]{1,70}")))
        require(httpPerformanceMeasuredIndex(requestId, 2000) != null)
    }
}

internal fun httpPerformanceMeasuredIndex(
    requestId: String?,
    orders: Int,
): Int? {
    if (requestId == null || !requestId.matches(Regex("M-(0|[1-9][0-9]{0,3})"))) return null
    return requestId.substring(2).toIntOrNull()?.takeIf { it in 0 until orders }
}

/** N slots and both per-failure/global text budgets bound retained raw exception data. */
internal class HttpPerformanceHttpFailures(
    private val runId: String,
    private val orders: Int,
    private val maxCharactersPerFailure: Int = 16_384,
    private val maxTotalCharacters: Int = 2_097_152,
) {
    private val lock = Any()
    private val failures = arrayOfNulls<Map<String, Any?>>(orders.also { require(it in 1..2000) })
    private val expected = AtomicIntegerArray(orders)
    private var retainedCharacters = 0
    private var duplicateCount = 0L
    private var invalidIdentityCount = 0L
    private var captureFailureCount = 0L

    init {
        require(runId.matches(Regex("[a-zA-Z0-9-]{1,70}")))
        require(orders in 1..2000)
        require(maxCharactersPerFailure in 1..16_384 && maxTotalCharacters in 1..8_388_608)
    }

    /** Called before recording the original HTTP outcome; no stack rendering or shared rendering lock. */
    fun expect(requestId: String) {
        val index = httpPerformanceMeasuredIndex(requestId, orders)
        if (index != null) expected.set(index, 1) else synchronized(lock) { invalidIdentityCount++ }
    }

    /** Render only after the original outcome timestamp, outside the client observation lock. */
    fun record(
        requestId: String,
        error: Throwable,
    ) {
        // Diagnostic failures must not replace the original HTTP outcome or escape its callback.
        try {
            synchronized(lock) {
                val index = httpPerformanceMeasuredIndex(requestId, orders)
                if (index == null) {
                    invalidIdentityCount++
                    return
                }
                expected.set(index, 1)
                if (failures[index] != null) {
                    duplicateCount++
                    return
                }
                val budget = minOf(maxCharactersPerFailure, maxTotalCharacters - retainedCharacters)
                val text = BoundedExceptionWriter(budget)
                PrintWriter(text).use { error.printStackTrace(it) }
                val seen = IdentityHashMap<Throwable, Boolean>()
                var deepest = error
                var chainTruncated = false
                while (true) {
                    seen[deepest] = true
                    val next = deepest.cause ?: break
                    if (seen.containsKey(next) || seen.size >= 32) {
                        chainTruncated = true
                        break
                    }
                    deepest = next
                }
                val stack = text.content()
                retainedCharacters += stack.length
                failures[index] =
                    mapOf(
                        "runId" to runId,
                        "requestId" to requestId,
                        "outerType" to error.javaClass.name,
                        "deepestTypeWithinChainLimit" to deepest.javaClass.name,
                        "causeInspectionLimit" to 32,
                        "causeInspectionTruncated" to chainTruncated,
                        "stackTrace" to stack,
                        "retainedCharacters" to stack.length,
                        "discardedCharacters" to text.discardedCharacters,
                        "truncated" to (text.discardedCharacters > 0),
                    )
            }
        } catch (_: Exception) {
            synchronized(lock) { captureFailureCount++ }
        }
    }

    fun snapshot(): Map<String, Any?> =
        synchronized(lock) {
            mapOf(
                "enabled" to true,
                "runId" to runId,
                "capacity" to orders,
                "maxCharactersPerFailure" to maxCharactersPerFailure,
                "maxTotalCharacters" to maxTotalCharacters,
                "retainedCharacters" to retainedCharacters,
                "duplicateCount" to duplicateCount,
                "invalidIdentityCount" to invalidIdentityCount,
                "captureFailureCount" to captureFailureCount,
                "capturePendingCount" to failures.indices.count { expected.get(it) == 1 && failures[it] == null }.toLong(),
                "capturePendingRequestIds" to failures.indices.filter { expected.get(it) == 1 && failures[it] == null }.map { "M-$it" },
                "captureScope" to "after HTTP outcome timestamp, outside client observation lock",
                "failures" to failures.filterNotNull(),
            )
        }
}

private class BoundedExceptionWriter(
    private val capacity: Int,
) : Writer() {
    private val text = StringBuilder(minOf(capacity, 1024))
    var discardedCharacters = 0L
        private set

    override fun write(
        buffer: CharArray,
        offset: Int,
        length: Int,
    ) {
        val retained = minOf(length, capacity - text.length)
        if (retained > 0) text.append(buffer, offset, retained)
        discardedCharacters += length - retained
    }

    override fun write(
        value: String,
        offset: Int,
        length: Int,
    ) {
        val retained = minOf(length, capacity - text.length)
        if (retained > 0) text.append(value, offset, offset + retained)
        discardedCharacters += length - retained
    }

    override fun flush() = Unit

    override fun close() = Unit

    fun content(): String = text.toString()
}
