package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReferenceArray

internal const val HTTP_PERFORMANCE_CONNECTION_WARMUP_REQUESTS = 1000
private const val CONNECTION_WARMUP_TEXT_LIMIT = 4096
private val connectionWarmupMapper = jacksonObjectMapper()

internal data class HttpPerformanceConnectionWarmupAttempt(
    val index: Int,
    val submittedToHttpClient: Boolean,
    val responseKnown: Boolean,
    val httpStatus: Int?,
    val responseBody: String?,
    val responseBodyChars: Int?,
    val responseBodyTruncated: Boolean,
    val exception: String?,
    val exceptionChars: Int?,
    val exceptionTruncated: Boolean,
    val validationFailures: List<String>,
) {
    val verified: Boolean get() = responseKnown && validationFailures.isEmpty() && exception == null
}

internal data class HttpPerformanceConnectionWarmupReport(
    val planned: Int,
    val expectedFirstSequence: Long,
    val elapsedNs: Long,
    val waitFailure: String?,
    val waitFailureChars: Int?,
    val waitFailureTruncated: Boolean,
    val attempts: List<HttpPerformanceConnectionWarmupAttempt>,
) {
    val failedAttempts: Int get() = attempts.count { !it.verified }
    val succeeded: Boolean get() = elapsedNs >= 0 && waitFailure == null && attempts.size == planned && failedAttempts == 0

    fun summary(): Map<String, Any?> =
        mapOf(
            "planned" to planned,
            "submittedToHttpClient" to attempts.count { it.submittedToHttpClient },
            "responsesKnown" to attempts.count { it.responseKnown },
            "verifiedResponses" to attempts.count { it.verified },
            "failedAttempts" to failedAttempts,
            "unknownResponses" to attempts.count { !it.responseKnown },
            "succeeded" to succeeded,
            "elapsedNs" to elapsedNs,
            "clockDifferenceValid" to (elapsedNs >= 0),
            "waitFailure" to waitFailure,
            "waitFailureChars" to waitFailureChars,
            "waitFailureTruncated" to waitFailureTruncated,
            "truncatedAttemptEvidence" to attempts.count { it.responseBodyTruncated || it.exceptionTruncated },
            "retainedAttemptLimit" to HTTP_PERFORMANCE_CONNECTION_WARMUP_REQUESTS,
            "textLimitPerFieldChars" to CONNECTION_WARMUP_TEXT_LIMIT,
        )
}

/** Read the original accepted W-0, rather than guessing its admission sequence from request order. */
internal fun httpPerformanceWarmConnectionSequence(checkpoints: Map<Int, JsonNode>): Long {
    check(checkpoints.keys == setOf(0, 1, 2)) { "connection warmup에 세 노드 최초 결과가 필요합니다" }
    val sequences =
        checkpoints.values.map { checkpoint ->
            val originals =
                checkpoint.path("applied").path("state").path("requests").toList().filter {
                    it.path("requestId").asString() == "W-0" && it.path("order").path("userId").asString() == "BUYER"
                }
            check(originals.size == 1) { "BUYER/W-0 최초 결과가 정확히 한 건이어야 합니다" }
            val original = originals.single()
            val order = original.path("order")
            for ((field, expected) in mapOf(
                "orderId" to "W-0",
                "userId" to "BUYER",
                "marketId" to "BTC-USDT",
                "side" to "BUY",
                "orderType" to "LIMIT",
                "timeInForce" to "GTC",
            )) {
                check(order.path(field).asString() == expected) { "W-0 원래 주문의 $field 불일치" }
            }
            check(order.path("price").asLong() == 10_000L && order.path("quantity").asLong() == 100_000_000L) { "W-0 원래 수량·가격 불일치" }
            check(original.path("result").path("outcome").asString() == "ACCEPTED") { "W-0 최초 결과가 승인이 아닙니다" }
            val sequence = original.path("result").path("record").path("sequence")
            check(sequence.isIntegralNumber && sequence.canConvertToLong() && sequence.asLong() > 0) { "W-0 최초 승인 순번이 없습니다" }
            sequence.asLong()
        }
    check(sequences.distinct().size == 1) { "세 노드 W-0 최초 승인 순번 불일치" }
    return sequences.first()
}

/** Log position may advance for duplicate admissions; no retained financial/context field may change. */
internal fun checkHttpPerformanceWarmConnectionState(
    before: Map<Int, JsonNode>,
    after: Map<Int, JsonNode>,
) {
    check(before.keys == setOf(0, 1, 2) && after.keys == before.keys) { "connection warmup 전후 세 사본 누락" }
    check(httpPerformanceWarmConnectionSequence(before) == httpPerformanceWarmConnectionSequence(after)) { "W-0 최초 승인 변경" }
    check(
        after.values
            .map { it.path("applied").path("position").asLong() }
            .distinct()
            .size == 1,
    ) { "connection warmup 후 적용 위치 불일치" }
    for (member in 0..2) {
        val previous = before.getValue(member)
        val current = after.getValue(member)
        check(current.path("applied").path("position").asLong() >= previous.path("applied").path("position").asLong()) {
            "node$member connection warmup 후 적용 위치 역행"
        }
        for ((name, old, next) in listOf(
            Triple("state", previous.path("applied").path("state"), current.path("applied").path("state")),
            Triple("trading", previous.path("trading"), current.path("trading")),
        )) {
            check(!old.isMissingNode && !old.isNull && !next.isMissingNode && !next.isNull && old == next) {
                "node$member connection warmup이 기존 $name 또는 최초 context를 변경했습니다"
            }
        }
    }
}

private fun connectionWarmupFailure(
    index: Int,
    submitted: Boolean,
    reason: String,
    failure: Throwable?,
): HttpPerformanceConnectionWarmupAttempt {
    val text = failure?.let(::connectionWarmupExceptionText)
    return HttpPerformanceConnectionWarmupAttempt(
        index,
        submitted,
        false,
        null,
        null,
        null,
        false,
        text?.take(CONNECTION_WARMUP_TEXT_LIMIT),
        text?.length,
        (text?.length ?: 0) > CONNECTION_WARMUP_TEXT_LIMIT,
        listOf(reason),
    )
}

/** Put the inspected root before the stack so truncating wrapper frames cannot erase an EOF classification. */
private fun connectionWarmupExceptionText(failure: Throwable): String {
    val seen = IdentityHashMap<Throwable, Boolean>()
    var current = failure
    var limited = false
    while (true) {
        seen[current] = true
        val next = current.cause ?: break
        if (seen.containsKey(next) || seen.size >= 32) {
            limited = true
            break
        }
        current = next
    }
    return "deepest-inspected-cause: ${current.javaClass.name}: ${current.message?.take(512)}; chain-limited=$limited\n" +
        failure.stackTraceToString()
}

private fun connectionWarmupResponse(
    index: Int,
    response: HttpResponse<String>,
    expectedSequence: Long,
): HttpPerformanceConnectionWarmupAttempt {
    val status = response.statusCode()
    val body = response.body().orEmpty()
    val failures = mutableListOf<String>()
    if (status != 202) failures.add("HTTP_STATUS_NOT_202")
    var parseFailure: String? = null
    if (body.length > CONNECTION_WARMUP_TEXT_LIMIT) {
        failures.add("RESPONSE_BODY_EXCEEDS_CAPTURE_LIMIT")
    } else {
        try {
            val value = connectionWarmupMapper.readTree(body)
            for ((field, expected) in mapOf(
                "status" to "ACCEPTED",
                "userId" to "BUYER",
                "requestId" to "W-0",
                "marketId" to "BTC-USDT",
                "orderId" to "W-0",
            )) {
                if (value.path(field).asString() != expected) failures.add("RESPONSE_IDENTITY_MISMATCH:$field")
            }
            val sequence = value.path("sequence")
            if (!sequence.isIntegralNumber || !sequence.canConvertToLong() ||
                sequence.asLong() != expectedSequence
            ) {
                failures.add("FIRST_SEQUENCE_MISMATCH")
            }
        } catch (failure: Exception) {
            failures.add("RESPONSE_JSON_INVALID")
            parseFailure = failure.stackTraceToString()
        }
    }
    return HttpPerformanceConnectionWarmupAttempt(
        index,
        true,
        true,
        status,
        body.take(CONNECTION_WARMUP_TEXT_LIMIT),
        body.length,
        body.length > CONNECTION_WARMUP_TEXT_LIMIT,
        parseFailure?.take(CONNECTION_WARMUP_TEXT_LIMIT),
        parseFailure?.length,
        (parseFailure?.length ?: 0) > CONNECTION_WARMUP_TEXT_LIMIT,
        java.util.List.copyOf(failures),
    )
}

/**
 * Test-source diagnostic only. Submit every bounded HTTP call before waiting; no serial RTT loop or retries.
 * The caller passes its existing HttpClient.sendAsync. send/await are functions for deterministic unit fixtures.
 * This attempts to warm HTTP activity and does not promise one connection per call.
 */
internal fun warmHttpPerformanceConnections(
    uri: URI,
    expectedSequence: Long,
    count: Int = HTTP_PERFORMANCE_CONNECTION_WARMUP_REQUESTS,
    awaitAll: (CompletableFuture<Void>) -> Unit = {
        it.get(30, TimeUnit.SECONDS)
        Unit
    },
    nanoTime: () -> Long = System::nanoTime,
    send: (HttpRequest) -> CompletableFuture<HttpResponse<String>>,
): HttpPerformanceConnectionWarmupReport {
    require(count in 1..HTTP_PERFORMANCE_CONNECTION_WARMUP_REQUESTS && expectedSequence > 0)
    val started = nanoTime()
    val request = performanceHttpRequest(uri, connectionWarmupMapper.writeValueAsString(performanceOrder("BUYER", "BUY", "W-0")))
    val pending = AtomicReferenceArray<CompletableFuture<HttpResponse<String>>?>(count)
    val results = ArrayList<CompletableFuture<HttpPerformanceConnectionWarmupAttempt>>(count)
    for (index in 0 until count) {
        try {
            val future = send(request)
            pending.set(index, future)
            results.add(
                future.handle { response, failure ->
                    try {
                        when {
                            failure != null -> connectionWarmupFailure(index, true, "HTTP_ASYNC_FAILURE", failure)
                            response == null -> connectionWarmupFailure(index, true, "HTTP_NULL_RESPONSE", null)
                            else -> connectionWarmupResponse(index, response, expectedSequence)
                        }
                    } catch (error: Exception) {
                        connectionWarmupFailure(index, true, "HTTP_RESPONSE_CAPTURE_FAILURE", error)
                    } finally {
                        pending.set(index, null)
                    }
                },
            )
        } catch (failure: Exception) {
            results.add(CompletableFuture.completedFuture(connectionWarmupFailure(index, false, "HTTP_SUBMIT_FAILURE", failure)))
        }
    }
    var waitFailure: Throwable? = null
    try {
        awaitAll(CompletableFuture.allOf(*results.toTypedArray()))
    } catch (failure: Exception) {
        waitFailure = failure
        for (index in 0 until count) pending.get(index)?.cancel(true)
    }
    val attempts =
        results.mapIndexed { index, result ->
            try {
                result.getNow(null) ?: connectionWarmupFailure(index, true, "HTTP_RESPONSE_UNKNOWN_AT_PREPARATION_CUTOFF", null)
            } catch (failure: Exception) {
                connectionWarmupFailure(index, true, "HTTP_RESPONSE_CAPTURE_FAILURE", failure)
            }
        }
    val waitText = waitFailure?.stackTraceToString()
    val elapsed = nanoTime() - started
    if (waitFailure is InterruptedException) Thread.currentThread().interrupt()
    return HttpPerformanceConnectionWarmupReport(
        count,
        expectedSequence,
        elapsed,
        waitText?.take(CONNECTION_WARMUP_TEXT_LIMIT),
        waitText?.length,
        (waitText?.length ?: 0) > CONNECTION_WARMUP_TEXT_LIMIT,
        java.util.List.copyOf(attempts),
    )
}
