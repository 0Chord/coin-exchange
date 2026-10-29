package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil

/** Joins preserved observations after the load; never instruments a request or compares JVM clock origins. */
internal fun writeHttpPerformancePipelineReport(
    directory: Path,
    runId: String,
    orders: Int,
): List<String> {
    require(orders in 1..2000)
    return HttpPerformancePipelineReport(directory, runId, orders).write()
}

private class HttpPerformancePipelineReport(
    private val directory: Path,
    private val runId: String,
    private val orders: Int,
) {
    private val mapper = jacksonObjectMapper()
    private val errors = linkedSetOf<String>()
    private val series = sortedMapOf<String, MutableList<Long>>()
    private val gatewayCallbackComparisons = mutableListOf<Long>()
    private val expected = (0 until orders).map { "M-$it" }.toSet()

    private fun read(name: String): JsonNode =
        try {
            mapper.readTree(Files.readString(directory.resolve(name)))
        } catch (failure: Exception) {
            errors.add("INPUT_UNAVAILABLE:$name:${failure.javaClass.simpleName}")
            mapper.missingNode()
        }

    private fun number(
        row: JsonNode?,
        field: String,
        label: String,
    ): Long? {
        val value = row?.path(field)
        if (value == null || !value.isIntegralNumber || !value.canConvertToLong()) {
            errors.add("MISSING_OR_NONINTEGER:$label:$field")
            return null
        }
        return value.asLong()
    }

    private fun duration(
        stage: String,
        start: Long?,
        end: Long?,
        requestId: String,
    ): Long? {
        if (start == null || end == null) return null
        return measured(stage, end - start, requestId)
    }

    private fun measured(
        stage: String,
        value: Long?,
        requestId: String,
    ): Long? {
        if (value == null) return null
        if (value < 0) {
            errors.add("NEGATIVE_DURATION:$stage:$requestId")
            return null
        }
        series.getOrPut(stage) { mutableListOf() }.add(value)
        return value
    }

    private fun phases(
        row: JsonNode?,
        field: String,
        prefix: String,
        requestId: String,
        required: Set<String>,
    ): Map<String, Long?> {
        val source = row?.path(field)
        if (source == null || !source.isObject) errors.add("MISSING_PHASES:$prefix:$requestId")
        val keys =
            required +
                source
                    ?.takeIf { it.isObject }
                    ?.properties()
                    ?.map { it.key }
                    .orEmpty()
        return keys.sorted().associateWith { phase ->
            measured("$prefix.$phase", number(source, phase, "$prefix:$requestId"), requestId)
        }
    }

    private fun indexed(
        rows: List<JsonNode>,
        label: String,
        requireUser: Boolean = true,
    ): Map<String, JsonNode> {
        val grouped = linkedMapOf<String, MutableList<JsonNode>>()
        rows.filter { it.path("requestId").asString().startsWith("M-") }.forEach { row ->
            val id = row.path("requestId").asString()
            if (id !in expected || (requireUser && row.path("userId").asString() != "BUYER")) {
                errors.add("UNEXPECTED_IDENTITY:$label:${row.path("userId").asString()}:$id")
            } else {
                grouped.getOrPut(id) { mutableListOf() }.add(row)
            }
        }
        if (grouped.keys != expected) errors.add("COVERAGE:$label:${grouped.keys.size}/$orders")
        return grouped
            .mapNotNull { (id, values) ->
                if (values.size != 1) {
                    errors.add("DUPLICATE_IDENTITY:$label:$id")
                    null
                } else {
                    id to values.single()
                }
            }.toMap()
    }

    private fun checkSource(
        source: JsonNode,
        label: String,
        needsRunId: Boolean = true,
    ) {
        if (!source.isObject) errors.add("MISSING_SOURCE:$label")
        if (needsRunId && source.path("runId").asString() != runId) errors.add("WRONG_RUN:$label")
        if (!source.path("errors").isArray || !source.path("errors").isEmpty) errors.add("SOURCE_ERRORS:$label")
    }

    fun write(): List<String> {
        val requests =
            try {
                Files.newBufferedReader(directory.resolve("requests.jsonl")).useLines { lines ->
                    lines
                        .filter { it.isNotBlank() }
                        .take(orders + 1)
                        .map { mapper.readTree(it) }
                        .toList()
                }
            } catch (failure: Exception) {
                errors.add("INPUT_UNAVAILABLE:requests.jsonl:${failure.javaClass.simpleName}")
                emptyList()
            }
        if (requests.size != orders) errors.add("COVERAGE:requests:${requests.size}/$orders")
        val clients = read("client-stages.json")
        val gateway = read("gateway-stages.json")
        val checkpoints = read("checkpoint-final.json")
        val server = gateway.path("httpBoundaries")
        checkSource(clients, "client", needsRunId = false)
        checkSource(gateway, "gateway")
        checkSource(server, "server")
        if (!clients.path("enabled").asBoolean() || number(clients, "overflow", "client") != 0L) errors.add("CLIENT_TRACE_INCOMPLETE")
        if (gateway.path("status").asString() != "CLOSED" || number(gateway, "overflowCount", "gateway") != 0L ||
            number(gateway, "recordingInProgress", "gateway") != 0L
        ) {
            errors.add("GATEWAY_TRACE_INCOMPLETE")
        }
        if (server.path("status").asString() != "CLOSED" || number(server, "enteredCount", "server") != orders.toLong() ||
            number(server, "completedCount", "server") != orders.toLong() || number(server, "observerFailureCount", "server") != 0L
        ) {
            errors.add("SERVER_TRACE_INCOMPLETE")
        }
        val byRequest = indexed(requests, "requests")
        val http = indexed(clients.path("samples").filter { it.path("kind").asString() == "HTTP" }, "client.HTTP")
        val gatewayRows = indexed(gateway.path("requestTimings").toList(), "gateway")
        // Server identity is carried by the validated headers, not by the order body.
        val serverRows = indexed(server.path("spans").toList(), "server", requireUser = false)
        val nodeRows =
            (0..2).associateWith { member ->
                val node = checkpoints.path(member.toString())
                if (node.path("runId").asString() != runId || number(node, "memberId", "node$member") != member.toLong() ||
                    (!node.path("collectorFailure").isNull && !node.path("collectorFailure").isMissingNode)
                ) {
                    errors.add("NODE_SOURCE_INVALID:$member")
                }
                if (!node.path("callbackTimings").path("raw").isArray) errors.add("NODE_TRACE_MISSING:$member")
                listOf("ADMISSION", "EXECUTION").associateWith { kind ->
                    indexed(node.path("callbackTimings").path("raw").filter { it.path("kind").asString() == kind }, "node$member.$kind")
                }
            }
        val leaders = (0..2).filter { checkpoints.path(it.toString()).path("role").asString() == "LEADER" }
        val rolesValid =
            leaders.size == 1 && (0..2).all { checkpoints.path(it.toString()).path("role").asString() in setOf("LEADER", "FOLLOWER") }
        if (!rolesValid) errors.add("LEADER_ROLE_COVERAGE")
        val leader = leaders.singleOrNull()
        val rows =
            expected.map { id ->
                val raw = byRequest[id]
                val sequence = number(raw, "admissionSequence", id)
                if (sequence == null || sequence <= 0 || raw?.path("httpOutcome")?.asString() != "ACCEPTED" ||
                    number(raw, "tradeCount", id) != 1L || number(raw, "settlementCount", id) != 1L
                ) {
                    errors.add("REQUEST_NOT_ACCEPTED_AND_SETTLED:$id")
                }
                val client = clientRow(id, raw, http[id])
                val gw = gatewayRows[id]
                val gatewayMatches =
                    number(gw, "admissionSequence", "gateway:$id") == sequence &&
                        gw?.path("orderId")?.asString() == raw?.path("orderId")?.asString()
                if (!gatewayMatches) {
                    errors.add("GATEWAY_IDENTITY_OR_SEQUENCE:$id")
                }
                val gatewayPhases = phases(gw, "phaseDurationsNs", "gateway", id, HTTP_GATEWAY_TIMER_PHASES.toSet())
                val servlet = serverRows[id]
                val points = servlet?.path("gatewayLocalPoints")
                if (points?.path("clock")?.asString() != "gateway JVM System.nanoTime only") errors.add("WRONG_SERVER_CLOCK:$id")
                val serverDurations = serverRow(id, points, gw)
                val nodes =
                    (0..2).associate { member ->
                        member.toString() to
                            listOf("ADMISSION", "EXECUTION").associateWith { kind ->
                                val callback = nodeRows.getValue(member).getValue(kind)[id]
                                if (number(callback, "admissionSequence", "node$member.$kind:$id") !=
                                    sequence
                                ) {
                                    errors.add("NODE_SEQUENCE:node$member.$kind:$id")
                                }
                                if ((number(callback, "position", "node$member.$kind:$id") ?: 0L) <=
                                    0
                                ) {
                                    errors.add("NODE_POSITION:node$member.$kind:$id")
                                }
                                val prefix = "node$member.${kind.lowercase()}"
                                mapOf(
                                    "admissionSequence" to callback?.path("admissionSequence"),
                                    "position" to callback?.path("position"),
                                    "productCallbackNs" to
                                        measured("$prefix.product", number(callback, "productCallbackNs", "$prefix:$id"), id),
                                    "observerNs" to measured("$prefix.observer", number(callback, "observerNs", "$prefix:$id"), id),
                                    "admissionToExecutionGapNs" to
                                        if (kind ==
                                            "EXECUTION"
                                        ) {
                                            measured(
                                                "node$member.admission_to_execution_gap",
                                                number(callback, "admissionToExecutionGapNs", "$prefix:$id"),
                                                id,
                                            )
                                        } else {
                                            null
                                        },
                                    "phasesNs" to phases(callback, "phases", prefix, id, requiredNodePhases(kind)),
                                    "offerEvidence" to
                                        callback?.let { value ->
                                            listOf(
                                                "executionOfferAttempts",
                                                "executionBackPressured",
                                                "executionAdminActions",
                                                "executionOfferPosition",
                                                "replyAttempts",
                                                "replyBackPressured",
                                                "replyAdminActions",
                                                "replyOfferPosition",
                                                "replyStatus",
                                            ).associateWith { value.path(it).takeUnless { point -> point.isMissingNode } }
                                        },
                                )
                            }
                    }
                val leaderAdmission = leader?.let { nodeRows.getValue(it).getValue("ADMISSION")[id] }
                for (kind in listOf("ADMISSION", "EXECUTION")) {
                    val positions =
                        (0..2).mapNotNull { member ->
                            nodeRows
                                .getValue(member)
                                .getValue(kind)[id]
                                ?.path("position")
                                ?.takeIf { it.isIntegralNumber }
                                ?.asLong()
                        }
                    if (positions.size != 3 || positions.distinct().size != 1) errors.add("NODE_POSITION_COPIES:$kind:$id")
                }
                val leaderMatches =
                    rolesValid && leaderAdmission?.path("replyStatus")?.asString() == "OFFERED" &&
                        (0..2).filter { it != leader }.all {
                            nodeRows
                                .getValue(it)
                                .getValue("ADMISSION")[id]
                                ?.path("replyStatus")
                                ?.asString() ==
                                "NOT_LEADER"
                        }
                if (!leaderMatches) errors.add("LEADER_REPLY_EVIDENCE:$id")
                val responseWait = gatewayPhases["response_wait"]
                val product = leaderAdmission?.path("productCallbackNs")?.takeIf { it.isIntegralNumber }?.asLong()
                val sequencesMatch =
                    sequence != null && sequence > 0 &&
                        (0..2).all { member ->
                            listOf("ADMISSION", "EXECUTION").all { kind ->
                                nodeRows
                                    .getValue(member)
                                    .getValue(kind)[id]
                                    ?.path("admissionSequence")
                                    ?.takeIf { it.isIntegralNumber }
                                    ?.asLong() ==
                                    sequence
                            }
                        }
                val joinedLeader = leaderMatches && gatewayMatches && sequencesMatch
                // The reply can arrive before the leader's callback returns; neither interval contains the other.
                val comparison =
                    if (joinedLeader && responseWait != null && product != null && product >= 0) {
                        (responseWait - product).also { gatewayCallbackComparisons.add(it) }
                    } else {
                        null
                    }
                mapOf(
                    "userId" to "BUYER",
                    "requestId" to id,
                    "admissionSequence" to sequence,
                    "client" to client,
                    "gateway" to gatewayPhases,
                    "server" to serverDurations,
                    "nodes" to nodes,
                    "leaderMemberId" to leader.takeIf { joinedLeader },
                    "gatewayRoundTripMinusLeaderCallbackNs" to comparison,
                )
            }
        val disk = (0..2).associate { it.toString() to archiveCounters(checkpoints.path(it.toString()), it) }
        val positions = (0..2).associate { it.toString() to positionEvidence(checkpoints.path(it.toString()), it) }
        val summaries = series.mapValues { (_, values) -> summary(values) }
        val report =
            mapOf(
                "runId" to runId,
                "orders" to orders,
                "status" to if (errors.isEmpty()) "VALID" else "ERROR",
                "errors" to errors.toList(),
                "clockRule" to
                    "Client points compare only within client JVM; server and Gateway points share " +
                    "one gateway JVM; node values are local durations only. Never subtract origins " +
                    "across JVMs.",
                "stageScope" to
                    "Parent/child phases overlap. Percentiles and phase totals are not additive. Body " +
                    "publication means handed to the JDK HTTP client, not a socket write or peer " +
                    "receipt. Optional response headers/body points are public BodyHandler/BodySubscriber " +
                    "callbacks, not socket reads; the diagnostic wrapper may add JDK getBody scheduling.",
                "comparisonScope" to
                    "Signed comparison of gateway response_wait and the matching leader admission " +
                    "callback duration. The callback includes work after offering the reply, so the " +
                    "Gateway round trip may end first. Negative values are valid; this is not an " +
                    "additive partition or transport/commit/disk latency.",
                "coverage" to
                    mapOf(
                        "requests" to byRequest.size,
                        "clientHttp" to http.size,
                        "gateway" to gatewayRows.size,
                        "server" to serverRows.size,
                        "nodes" to nodeRows.mapValues { (_, kinds) -> kinds.mapValues { (_, indexed) -> indexed.size } },
                    ),
                "stageSummaries" to summaries,
                "comparisonSummaries" to
                    mapOf(
                        "gatewayRoundTripMinusLeaderCallbackNs" to
                            (
                                summary(gatewayCallbackComparisons) +
                                    mapOf("signed" to true, "negativeCount" to gatewayCallbackComparisons.count { it < 0 })
                            ),
                    ),
                "archiveRecorderCounters" to disk,
                "positionEvidence" to positions,
                "rows" to rows,
            )
        try {
            Files.writeString(directory.resolve("pipeline-stages.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
            Files.writeString(directory.resolve("pipeline-stages.md"), markdown(summaries))
        } catch (failure: Exception) {
            errors.add("REPORT_WRITE_FAILED:${failure.javaClass.simpleName}")
        }
        return errors.toList()
    }

    private fun clientRow(
        id: String,
        raw: JsonNode?,
        trace: JsonNode?,
    ): Map<String, Any?> {
        val start = number(raw, "startNs", id)
        val bodyStart = number(trace, "bodyPublishStartedNs", "client:$id")
        val bodyEnd = number(trace, "bodyPublishCompletedNs", "client:$id")
        val callback = number(trace, "callbackEnteredNs", "client:$id")
        val lock = number(trace, "lockAcquiredNs", "client:$id")
        val parsed = number(trace, "interpretationFinishedNs", "client:$id")
        if (number(trace, "bodySubscriptions", "client:$id") != 1L) errors.add("BODY_SUBSCRIPTIONS:$id")
        val responseFields =
            listOf(
                "responseHeadersEnteredNs",
                "responseBodyFirstNextNs",
                "responseBodyLastNextNs",
                "responseBodyOnCompleteNs",
                "responseBodyCompletedNs",
                "responseBodyGetBodyEnteredNs",
            )
        val hasResponse = responseFields.any { trace?.path(it)?.let { value -> !value.isNull && !value.isMissingNode } == true }

        fun responsePoint(field: String): Long? = if (hasResponse) number(trace, field, "client:$id") else null
        val headers = responsePoint("responseHeadersEnteredNs")
        val responseComplete = responsePoint("responseBodyOnCompleteNs")
        val bodyCompleted = responsePoint("responseBodyCompletedNs")
        val getBody = responsePoint("responseBodyGetBodyEnteredNs")
        val hasBodyNext =
            listOf("responseBodyFirstNextNs", "responseBodyLastNextNs").any {
                trace?.path(it)?.let { value -> !value.isNull && !value.isMissingNode } == true
            }
        val firstNext = if (hasBodyNext) responsePoint("responseBodyFirstNextNs") else null
        val lastNext = if (hasBodyNext) responsePoint("responseBodyLastNextNs") else null
        return mapOf(
            "startToBodyPublicationNs" to duration("client.start_to_body_publication", start, bodyStart, id),
            "bodyPublicationNs" to duration("client.body_publication", bodyStart, bodyEnd, id),
            "bodyPublicationToCallbackNs" to duration("client.body_publication_to_callback", bodyEnd, callback, id),
            "bodyPublicationToResponseHeadersNs" to duration("client.body_publication_to_response_headers", bodyEnd, headers, id),
            "responseHeadersToFirstBodyNextNs" to duration("client.response_headers_to_first_body_next", headers, firstNext, id),
            "responseBodyFirstToLastNextNs" to duration("client.response_body_first_to_last_next", firstNext, lastNext, id),
            "responseBodyLastNextToOnCompleteNs" to
                duration("client.response_body_last_next_to_on_complete", lastNext, responseComplete, id),
            "responseHeadersToOnCompleteNs" to duration("client.response_headers_to_on_complete", headers, responseComplete, id),
            "responseHeadersToGetBodyNs" to duration("client.response_headers_to_get_body", headers, getBody, id),
            "responseOnCompleteToBodyCompletedNs" to
                duration("client.response_on_complete_to_body_completed", responseComplete, bodyCompleted, id),
            "responseBodyCompletedToCallbackNs" to duration("client.response_body_completed_to_callback", bodyCompleted, callback, id),
            "startToCallbackNs" to duration("client.start_to_callback", start, callback, id),
            "observationLockNs" to duration("client.observation_lock", callback, lock, id),
            "interpretationNs" to duration("client.interpretation", lock, parsed, id),
            "startToHttpResultNs" to duration("client.start_to_http_result", start, number(raw, "httpResultNs", id), id),
            "startToLeaderMarkerNs" to duration("client.start_to_leader_marker", start, number(raw, "doneNs", id), id),
        )
    }

    private fun serverRow(
        id: String,
        points: JsonNode?,
        gateway: JsonNode?,
    ): Map<String, Long?> {
        fun point(name: String): Long? = number(points, name, "server:$id")
        val entry = point("tomcatEnteredNs")
        val mvc = point("mvcEnteredNs")
        val ready = point("asyncResultReadyNs")
        val redispatch = point("mvcRedispatchEnteredNs")
        val completed = point("completedNs")
        val enqueued = number(gateway, "enqueuedNs", "gateway:$id")
        val result = number(gateway, "resultReadyNs", "gateway:$id")
        val finished = number(gateway, "completedNs", "gateway:$id")
        return mapOf(
            "containerToMvcNs" to duration("server.container_to_mvc", entry, mvc, id),
            "mvcToGatewayEnqueuedNs" to duration("server.mvc_to_gateway_enqueued", mvc, enqueued, id),
            "gatewayEnqueuedToResultReadyNs" to duration("server.gateway_enqueued_to_result_ready", enqueued, result, id),
            "gatewayResultReadyToCompletionNs" to duration("server.gateway_result_ready_to_completion", result, finished, id),
            "gatewayResultToAsyncReadyNs" to duration("server.gateway_result_to_async_ready", result, ready, id),
            "asyncReadyToRedispatchNs" to duration("server.async_ready_to_redispatch", ready, redispatch, id),
            "redispatchToCompletionNs" to duration("server.redispatch_to_completion", redispatch, completed, id),
            "containerToCompletionNs" to duration("server.container_to_completion", entry, completed, id),
        )
    }

    private fun requiredNodePhases(kind: String): Set<String> =
        if (kind ==
            "ADMISSION"
        ) {
            setOf(
                "callback",
                "decode",
                "admission_context",
                "admission_apply",
                "execution_encode",
                "execution_offer",
                "reply_encode",
                "reply_offer",
            )
        } else {
            setOf(
                "callback",
                "decode",
                "execution_total",
                "matching_candidate",
                "settlement_input_validation",
                "settlement_candidate",
                "result_validation",
                "execution_apply",
                "completion",
            )
        }

    private fun archiveCounters(
        node: JsonNode,
        member: Int,
    ): Map<String, Any?> {
        val runtime = node.path("runtimeCounters")
        val definitions = runtime.path("definitions").toList()
        val frames = runtime.path("frames").toList()
        if (!runtime.path("errors").isArray || !runtime.path("errors").isEmpty ||
            number(runtime, "overflowCount", "node$member.runtime") != 0L
        ) {
            errors.add("ARCHIVE_SAMPLER_INVALID:node$member")
        }
        if (!setOf(105, 106, 107).all { type ->
                definitions.any { it.path("typeId").isIntegralNumber && it.path("typeId").asInt() == type }
            }
        ) {
            errors.add("ARCHIVE_COUNTER_COVERAGE:node$member")
        }
        val counters =
            definitions.mapIndexedNotNull { index, definition ->
                val type = definition.path("typeId").asInt()
                if (type !in setOf(105, 106, 107)) return@mapIndexedNotNull null
                val values =
                    frames.mapNotNull { frame ->
                        frame
                            .path(
                                "values",
                            ).firstOrNull { it.path("definitionIndex").asInt() == index }
                            ?.path("value")
                            ?.takeIf { it.isIntegralNumber }
                            ?.asLong()
                    }
                if (values.isEmpty()) errors.add("ARCHIVE_COUNTER_VALUES_MISSING:node$member:$index")
                val delta = if (type in setOf(106, 107) && values.size >= 2) values.last() - values.first() else null
                if (delta != null && delta < 0) errors.add("ARCHIVE_COUNTER_DECREASED:node$member:$index")
                mapOf(
                    "definitionIndex" to index,
                    "definition" to definition,
                    "sampleCount" to values.size,
                    "firstObservedValue" to values.firstOrNull(),
                    "lastObservedValue" to values.lastOrNull(),
                    "delta" to delta?.takeIf { it >= 0 },
                )
            }
        val origin = number(runtime, "originNs", "node$member.runtime")
        val callbacks =
            node.path("callbackTimings").path("raw").filter {
                it.path("userId").asString() == "BUYER" &&
                    it.path("requestId").asString() in expected
            }
        val starts =
            callbacks
                .filter {
                    it.path("kind").asString() == "ADMISSION"
                }.mapNotNull { number(it, "callbackStartedNs", "node$member.cohort") }
        val ends =
            callbacks
                .filter {
                    it.path("kind").asString() == "EXECUTION"
                }.mapNotNull { number(it, "callbackCompletedNs", "node$member.cohort") }
        val first = origin?.let { start -> starts.minByOrNull { it - start } }
        val last = origin?.let { start -> ends.maxByOrNull { it - start } }
        val before =
            if (origin != null && first != null) {
                frames.lastOrNull { frame ->
                    frame.path("elapsedNs").isIntegralNumber && frame.path("readDurationNs").isIntegralNumber &&
                        frame.path("elapsedNs").asLong() + frame.path("readDurationNs").asLong() <= first - origin
                }
            } else {
                null
            }
        val after =
            if (origin != null && last != null) {
                frames.firstOrNull { frame ->
                    frame.path("elapsedNs").isIntegralNumber && frame.path("elapsedNs").asLong() >= last - origin
                }
            } else {
                null
            }
        if (before == null) errors.add("ARCHIVE_COHORT_BEFORE_FRAME_MISSING:node$member")
        if (after == null) errors.add("ARCHIVE_COHORT_AFTER_FRAME_MISSING:node$member")
        return mapOf(
            "scope" to
                "Archive recorder write+force batch time/cumulative bytes; whole-sampler deltas " +
                "may include preparation. Not force-only, per-order fsync or a latency " +
                "percentile.",
            "available" to counters.isNotEmpty(),
            "counters" to counters,
            "samplerErrors" to runtime.path("errors"),
            "samplerOverflowCount" to runtime.path("overflowCount"),
            "cohortBracket" to
                mapOf(
                    "clock" to "node$member JVM System.nanoTime only",
                    "originNs" to origin,
                    "firstAdmissionStartedNs" to first,
                    "lastExecutionCompletedNs" to last,
                    "complete" to (before != null && after != null),
                    "beforeFrame" to before,
                    "afterFrame" to after,
                    "scope" to
                        "250ms sequential counter reads bracketing the same node's measured callbacks. " +
                        "Missing boundary frames stay null; no interpolation or exact per-order disk " +
                        "attribution.",
                ),
        )
    }

    private fun summary(values: List<Long>): Map<String, Any?> {
        val sorted = values.sorted()

        fun percentile(fraction: Double): Long? = sorted.takeIf { it.isNotEmpty() }?.get(ceil(sorted.size * fraction).toInt() - 1)
        return mapOf(
            "sampleCount" to sorted.size,
            "p50Ns" to percentile(.50),
            "p95Ns" to percentile(.95),
            "p99Ns" to percentile(.99),
            "maxNs" to sorted.lastOrNull(),
        )
    }

    private fun positionEvidence(
        node: JsonNode,
        member: Int,
    ): Map<String, Any?> {
        val runtime = node.path("runtimeCounters")
        val definitions = runtime.path("definitions").toList()
        val relevant =
            definitions.mapIndexedNotNull { index, value ->
                val type = value.path("typeId").takeIf { it.isIntegralNumber }?.asInt()
                if (type == 100 || type == 203 || (type == 4 && "log-sc-0" in value.path("label").asString())) index to value else null
            }
        val commit =
            relevant.filter { (_, value) ->
                value.path("typeId").asInt() == 203 &&
                    Regex("""(?:^|\s)clusterId=97(?:\s|$)""").containsMatchIn(value.path("label").asString())
            }
        val services =
            relevant.filter { (_, value) ->
                value.path("typeId").asInt() == 4 && Regex("""alias=log-sc-0(?:[|\s]|$)""").containsMatchIn(value.path("label").asString())
            }
        val service = services.singleOrNull()
        val subscription =
            service?.second?.path("label")?.asString()?.let {
                Regex("""^sub-pos:\s+\d+\s+(-?\d+)\s+(-?\d+)\s+.+$""").matchEntire(it)
            }
        val recordings =
            relevant.filter { (_, value) ->
                if (value.path("typeId").asInt() != 100 || subscription == null) return@filter false
                val recording = Regex("""^rec-pos:\s+(\d+)\s+(-?\d+)\s+(-?\d+)\s+.+$""").matchEntire(value.path("label").asString())
                recording != null && recording.groupValues[2] == subscription.groupValues[1] &&
                    recording.groupValues[3] == subscription.groupValues[2] &&
                    Regex("""(?:^|\s)archiveId=97(?:\s|$)""").containsMatchIn(value.path("label").asString())
            }
        val recording = recordings.singleOrNull()
        val resolved = subscription != null && recordings.size == 1 && commit.size == 1 && services.size == 1
        if (!resolved) errors.add("POSITION_IDENTITY_UNRESOLVED:node$member")
        if (resolved) {
            val requiredIndices = listOf(checkNotNull(recording).first, commit.single().first, checkNotNull(service).first)
            if (requiredIndices.any { index ->
                    runtime.path("frames").none { frame ->
                        frame.path("values").any { value ->
                            value.path("definitionIndex").isIntegralNumber && value.path("definitionIndex").asInt() == index &&
                                value.path("value").isIntegralNumber && value.path("value").canConvertToLong() &&
                                value.path("value").asLong() >= 0
                        }
                    }
                }
            ) {
                errors.add("POSITION_COUNTER_VALUES_MISSING:node$member")
            }
        }
        val indices = relevant.map { it.first }.toSet()
        val frames =
            runtime.path("frames").toList().map { frame ->
                mapOf(
                    "elapsedNs" to frame.path("elapsedNs"),
                    "readDurationNs" to frame.path("readDurationNs"),
                    "values" to
                        frame.path("values").filter {
                            it.path("definitionIndex").isIntegralNumber &&
                                it.path("definitionIndex").asInt() in indices
                        },
                )
            }
        val callbacks =
            node.path("callbackTimings").path("raw").filter {
                it.path("userId").asString() == "BUYER" &&
                    it.path("requestId").asString() in expected
            }
        return mapOf(
            "clock" to "node$member JVM System.nanoTime only",
            "originNs" to runtime.path("originNs"),
            "scope" to
                "Existing Archive recording-pos(100), cluster commit-pos(203), and identified " +
                "log-sc-0 service subscriber-pos(4). Sequential sampled byte positions are not " +
                "per-order timings; service position publication can lag its callbacks.",
            "identity" to
                mapOf(
                    "status" to if (resolved) "RESOLVED_BY_LABEL" else "UNRESOLVED",
                    "source" to
                        "Unique clusterId=97 commit label and archiveId=97 recording label matching the " +
                        "unique log-sc-0 subscriber session/stream; key bytes are absent from this " +
                        "snapshot.",
                    "commitCandidateCount" to commit.size,
                    "serviceCandidateCount" to services.size,
                    "recordingCandidateCount" to recordings.size,
                    "commitDefinitionIndex" to commit.singleOrNull()?.first,
                    "serviceDefinitionIndex" to service?.first.takeIf { subscription != null },
                    "recordingDefinitionIndex" to recording?.first.takeIf { resolved },
                    "recordingId" to
                        recording
                            ?.second
                            ?.path("label")
                            ?.asString()
                            ?.let {
                                Regex("""^rec-pos:\s+(\d+)\s+.*$""")
                                    .matchEntire(it)
                                    ?.groupValues
                                    ?.get(1)
                                    ?.toLongOrNull()
                            }.takeIf { resolved },
                ),
            "definitions" to relevant.map { (index, definition) -> mapOf("definitionIndex" to index, "definition" to definition) },
            "frames" to frames,
            "callbackPositions" to
                listOf("ADMISSION", "EXECUTION").associateWith { kind ->
                    val values =
                        callbacks.filter { it.path("kind").asString() == kind }.mapNotNull {
                            it
                                .path("position")
                                .takeIf { value ->
                                    value.isIntegralNumber
                                }?.asLong()
                        }
                    mapOf("sampleCount" to values.size, "firstPosition" to values.minOrNull(), "lastPosition" to values.maxOrNull())
                },
        )
    }

    private fun markdown(summaries: Map<String, Map<String, Any?>>): String =
        buildString {
            appendLine("# HTTP pipeline observations")
            appendLine()
            appendLine("Run $runId; $orders requests; ${if (errors.isEmpty()) "VALID" else "ERROR (${errors.size} issues; see JSON)"}.")
            appendLine(
                "Rows join the same user/request and admission sequence. Durations stay within " +
                    "each JVM; phases overlap and percentiles must not be added.",
            )
            appendLine(
                "Body publication hands bytes to the JDK client; it is not a measured socket " +
                    "write. Gateway round trip minus leader callback is a signed duration comparison, " +
                    "can be negative, and is not a latency partition; it is excluded from the table " +
                    "below.",
            )
            appendLine()
            appendLine("| Stage | n | p50 ms | p95 ms | p99 ms |")
            appendLine("|---|---:|---:|---:|---:|")
            summaries.forEach { (stage, values) ->
                fun ms(key: String): String =
                    (values[key] as? Long)?.let { "%.3f".format(java.util.Locale.ROOT, it / 1_000_000.0) } ?: "missing"
                appendLine("| $stage | ${values["sampleCount"]} | ${ms("p50Ns")} | ${ms("p95Ns")} | ${ms("p99Ns")} |")
            }
            appendLine()
            appendLine(
                "Archive counters describe write+force batches across their sampler window; they " +
                    "are not divided by order count. Complete rows, missing observations and " +
                    "validation errors are in pipeline-stages.json.",
            )
            appendLine(
                "Position evidence preserves recording/commit/service definitions and sampled " +
                    "byte positions separately. Unresolved stream identity is marked explicitly; " +
                    "positions are not converted into request latency.",
            )
        }
}
