package com.exchange.core.api.trading.infrastructure.aeron

import kotlin.math.ceil

/** 모든 시각은 같은 부하 발생기 JVM의 nanoTime 시계에서 관측한다. */
data class PlannedHttpRequest(
    val userId: String,
    val requestId: String,
    val orderId: String,
    val dueNs: Long,
)

enum class HttpPerformanceOutcome {
    ACCEPTED,
    BUSINESS_REJECTED,
    HTTP_ERROR,
    HTTP_UNKNOWN,
    HTTP_TIMEOUT,
}

/** 인코딩은 표식 송수신기가 맡으며, 서버 시각은 이 값에 넣지 않는다. */
data class HttpPerformanceCompletion(
    val runId: String,
    val memberId: Int,
    val role: String,
    val userId: String,
    val requestId: String,
    val orderId: String,
    val admissionSequence: Long,
    val logPosition: Long,
    val tradeCount: Long,
    val settlementCount: Long,
)

data class HttpPerformanceRequestSample(
    val userId: String,
    val requestId: String,
    val orderId: String,
    val dueNs: Long,
    val startNs: Long?,
    val ackNs: Long?,
    val httpResultNs: Long?,
    val httpOutcome: HttpPerformanceOutcome?,
    val httpDetail: String?,
    val httpTimeliness: String,
    val admissionSequence: Long?,
    val doneNs: Long?,
    val completionMemberId: Int?,
    val tradeCount: Long,
    val settlementCount: Long,
    val notSentReason: String?,
    val notSentNs: Long?,
    val generatorDelayNs: Long?,
    val ackRttNs: Long?,
    val completionLatencyNs: Long?,
    val scheduledAckLatencyNs: Long?,
    val scheduledCompletionLatencyNs: Long?,
    val completionTimeliness: String,
)

/** First received marker from each expected member, on the load/receiver JVM clock. */
internal data class HttpPerformanceCompletionCopiesSample(
    val member0ReceivedNs: Long?,
    val member1ReceivedNs: Long?,
    val member2ReceivedNs: Long?,
    val allCopiesDoneNs: Long?,
)

/** Long-only raw projection; the legacy sample and its leader latency remain unchanged. */
internal data class HttpPerformanceSustainedRequestObservation(
    val sample: HttpPerformanceRequestSample,
    val completionCopies: HttpPerformanceCompletionCopiesSample,
)

/** nanoTime can wrap: compare elapsed offsets from the common origin, never signed absolute times. */
internal fun httpPerformanceCompletionCopiesSample(
    firstDueNs: Long,
    member0ReceivedNs: Long?,
    member1ReceivedNs: Long?,
    member2ReceivedNs: Long?,
): HttpPerformanceCompletionCopiesSample {
    val all =
        if (member0ReceivedNs == null || member1ReceivedNs == null || member2ReceivedNs == null) {
            null
        } else {
            listOf(member0ReceivedNs, member1ReceivedNs, member2ReceivedNs).maxBy { it - firstDueNs }
        }
    return HttpPerformanceCompletionCopiesSample(member0ReceivedNs, member1ReceivedNs, member2ReceivedNs, all)
}

/**
 * pXX는 관측된 성공 표본만의 조건부 분위수이며 timeout을 0으로 채우지 않는다.
 * censored는 성공 표본의 누락이 고정 cohort의 해당 분위수를 가릴 수 있음을 뜻한다.
 * 하한은 누락된 표본이 모두 가장 유리한 위치에 있다고 가정해 계산한다.
 */
data class HttpPerformanceLatencySummary(
    val sampleCount: Int,
    val cohortSize: Int,
    val unknownCount: Int,
    val unknownRate: Double,
    val p50Ns: Long?,
    val p95Ns: Long?,
    val p99Ns: Long?,
    val maxNs: Long?,
    val p50Censored: Boolean,
    val p95Censored: Boolean,
    val p99Censored: Boolean,
    val populationP50LowerBoundNs: Long?,
    val populationP95LowerBoundNs: Long?,
    val populationP99LowerBoundNs: Long?,
    val unknownObservationLowerBoundNs: Long?,
)

data class HttpPerformanceSummary(
    val planned: Int,
    val started: Int,
    val unsent: Int,
    val generatorSaturated: Int,
    val accepted: Int,
    val businessRejected: Int,
    val httpErrors: Int,
    val httpUnknown: Int,
    val httpTimeouts: Int,
    val httpUnfinished: Int,
    val httpAfterDrain: Int,
    val completed: Int,
    val onTimeCompleted: Int,
    val lateCompleted: Int,
    val afterDrainCompleted: Int,
    val completionUnknown: Int,
    val tradeCount: Long,
    val settlementCount: Long,
    val duplicateMarkers: Int,
    val unmatchedMarkers: Int,
    val acceptedResponseWindowCount: Int,
    val completionWindowCount: Int,
    val settlementWindowCount: Long,
    val measurementWindowNs: Long,
    val cohortDurationNs: Long,
    val drainDurationNs: Long,
    val acceptedResponsesPerSecond: Double,
    val completedOrdersObservedPerSecond: Double,
    val settlementsObservedPerSecond: Double,
    val cohortCompletedOrdersPerSecond: Double,
    val cohortSettlementsPerSecond: Double,
    val drainCompletionRate: Double,
    val httpUnknownRate: Double,
    val completionUnknownRate: Double,
    val ackRtt: HttpPerformanceLatencySummary,
    val completionLatency: HttpPerformanceLatencySummary,
    val settlementLatency: HttpPerformanceLatencySummary,
    val scheduledAckLatency: HttpPerformanceLatencySummary,
    val scheduledCompletionLatency: HttpPerformanceLatencySummary,
    val generatorDelay: HttpPerformanceLatencySummary,
    val firstHalfCompletionLatency: HttpPerformanceLatencySummary,
    val secondHalfCompletionLatency: HttpPerformanceLatencySummary,
    val completionLatencyValid: Boolean,
    val instrumentationErrors: List<String>,
)

/**
 * HTTP 콜백과 표식 수신기가 함께 쓰는 고정 cohort 기록기다. 호출 순서와 관계없이
 * 원본 (userId, requestId, orderId)로 HTTP 응답보다 먼저 도착한 완료도 연결한다.
 * 서버 시각이나 사후 상태를 지연 계산의 입력으로 사용하지 않는다.
 */
class HttpPerformanceMeasurements(
    private val runId: String,
    private val firstDueNs: Long,
    private val windowEndNs: Long,
    plannedRequests: List<PlannedHttpRequest>,
    private val completionDeadlineNs: Long = 10_000_000_000L,
    private val unmatchedMarkerCapacity: Int = 8192,
) {
    private data class RequestKey(
        val userId: String,
        val requestId: String,
    )

    private data class MarkerKey(
        val runId: String,
        val memberId: Int,
        val admissionSequence: Long,
    )

    private data class ReceivedMarker(
        val value: HttpPerformanceCompletion,
        val receivedNs: Long,
    )

    private class MutableRequest(
        val plan: PlannedHttpRequest,
    ) {
        var startNs: Long? = null
        var ackNs: Long? = null
        var resultNs: Long? = null
        var outcome: HttpPerformanceOutcome? = null
        var detail: String? = null
        var admissionSequence: Long? = null
        var notSentReason: String? = null
        var notSentNs: Long? = null
        var leader: ReceivedMarker? = null
        val members = mutableMapOf<Int, HttpPerformanceCompletion>()
    }

    private val requests = linkedMapOf<RequestKey, MutableRequest>()
    private val received = linkedMapOf<MarkerKey, ReceivedMarker>()
    private val unmatched = linkedMapOf<MarkerKey, ReceivedMarker>()
    private val early = mutableSetOf<MarkerKey>()
    private val markersByRequestMember = mutableMapOf<Pair<RequestKey, Int>, HttpPerformanceCompletion>()
    private val errors = linkedSetOf<String>()
    private var duplicates = 0
    private var finished = false
    private var finalizedDrainEndNs: Long? = null

    init {
        require(runId.isNotBlank())
        require(windowEndNs - firstDueNs > 0) { "measurement window must be positive" }
        require(completionDeadlineNs > 0)
        require(unmatchedMarkerCapacity > 0)
        require(plannedRequests.isNotEmpty())
        plannedRequests.sortedBy { it.dueNs - firstDueNs }.forEach { plan ->
            require(plan.userId.isNotBlank() && plan.requestId.isNotBlank() && plan.orderId.isNotBlank())
            require(plan.dueNs - firstDueNs >= 0 && windowEndNs - plan.dueNs > 0) { "due time must be in the fixed measurement window" }
            require(requests.put(RequestKey(plan.userId, plan.requestId), MutableRequest(plan)) == null) {
                "the fixed cohort must contain unique user/request keys"
            }
        }
    }

    @Synchronized
    fun requestStarted(
        userId: String,
        requestId: String,
        atNs: Long,
    ) {
        checkOpen()
        val request = request(userId, requestId) ?: return
        if (request.startNs != null || request.notSentReason != null) {
            invalid("REQUEST_STARTED_TWICE:$userId/$requestId")
            return
        }
        request.startNs = atNs
        if (atNs - request.plan.dueNs < 0) invalid("START_BEFORE_DUE:$userId/$requestId")
        validateTimes(request)
        early.removeAll { markerKey -> received[markerKey]?.value?.let { it.userId == userId && it.requestId == requestId } == true }
    }

    @Synchronized
    fun requestNotSent(
        userId: String,
        requestId: String,
        reason: String,
        atNs: Long,
    ) {
        checkOpen()
        val request = request(userId, requestId) ?: return
        if (request.startNs != null || request.notSentReason != null) {
            invalid("NOT_SENT_STATE_CONFLICT:$userId/$requestId")
            return
        }
        require(reason.isNotBlank())
        request.notSentReason = reason
        request.notSentNs = atNs
        if (atNs - request.plan.dueNs < 0) invalid("NOT_SENT_BEFORE_DUE:$userId/$requestId")
        if (request.leader != null) invalid("COMPLETION_FOR_UNSENT_REQUEST:$userId/$requestId")
    }

    /** A failed long sender can abandon future due times at its real failure time, without inventing sends. */
    @Synchronized
    internal fun requestAbandoned(
        userId: String,
        requestId: String,
        reason: String,
        atNs: Long,
    ) {
        checkOpen()
        require(reason in setOf("INTERRUPTED", "LOAD_ABORTED"))
        val request = request(userId, requestId) ?: return
        if (request.startNs != null || request.notSentReason != null) {
            invalid("NOT_SENT_STATE_CONFLICT:$userId/$requestId")
            return
        }
        request.notSentReason = reason
        request.notSentNs = atNs
        if (request.leader != null) invalid("COMPLETION_FOR_UNSENT_REQUEST:$userId/$requestId")
    }

    @Synchronized
    fun httpCompleted(
        userId: String,
        requestId: String,
        atNs: Long,
        outcome: HttpPerformanceOutcome,
        admissionSequence: Long? = null,
        detail: String? = null,
        responseReceived: Boolean = outcome != HttpPerformanceOutcome.HTTP_TIMEOUT,
    ) {
        checkOpen()
        val request = request(userId, requestId) ?: return
        if (request.outcome != null) {
            invalid("HTTP_RESULT_RECORDED_TWICE:$userId/$requestId")
            return
        }
        request.resultNs = atNs
        request.ackNs = if (responseReceived) atNs else null
        request.outcome = outcome
        request.detail = detail
        request.admissionSequence = admissionSequence
        if (request.startNs == null) invalid("HTTP_RESULT_WITHOUT_START:$userId/$requestId")
        if (outcome == HttpPerformanceOutcome.ACCEPTED && (admissionSequence == null || admissionSequence <= 0)) {
            invalid("ACCEPTED_WITHOUT_SEQUENCE:$userId/$requestId")
        }
        if (outcome == HttpPerformanceOutcome.ACCEPTED && !responseReceived) invalid("ACCEPTED_WITHOUT_HTTP_RESPONSE:$userId/$requestId")
        validateIdentity(request)
        validateTimes(request)
    }

    @Synchronized
    fun completionObserved(
        marker: HttpPerformanceCompletion,
        receivedNs: Long,
    ) {
        checkOpen()
        if (marker.runId != runId) return
        val markerKey = marker.key()
        val previous = received[markerKey] ?: unmatched[markerKey]
        if (previous != null) {
            if (previous.value ==
                marker
            ) {
                duplicates++
            } else {
                invalid("MARKER_CONTENT_CONFLICT:${markerKey.memberId}/${markerKey.admissionSequence}")
            }
            return
        }
        if (!validMarker(marker)) return
        val requestKey = RequestKey(marker.userId, marker.requestId)
        val request = requests[requestKey]
        val observation = ReceivedMarker(marker, receivedNs)
        if (request == null) {
            if (unmatched.size + early.size >= unmatchedMarkerCapacity) {
                invalid("EARLY_MARKER_BUFFER_OVERFLOW")
                return
            }
            unmatched[markerKey] = observation
            return
        }
        if (request.plan.orderId != marker.orderId) {
            invalid("MARKER_REQUEST_IDENTITY_CONFLICT:${marker.userId}/${marker.requestId}")
            return
        }
        val memberKey = requestKey to marker.memberId
        val priorRequestMarker = markersByRequestMember[memberKey]
        if (priorRequestMarker != null && priorRequestMarker != marker) {
            invalid("REQUEST_SEQUENCE_CONFLICT:${marker.userId}/${marker.requestId}/${marker.memberId}")
            return
        }
        if (request.startNs == null && unmatched.size + early.size >= unmatchedMarkerCapacity) {
            invalid("EARLY_MARKER_BUFFER_OVERFLOW")
            return
        }
        received[markerKey] = observation
        markersByRequestMember[memberKey] = marker
        request.members[marker.memberId] = marker
        if (request.startNs == null) early.add(markerKey)
        if (marker.role == "LEADER") {
            if (request.leader != null) {
                invalid("MULTIPLE_LEADER_COMPLETIONS:${marker.userId}/${marker.requestId}")
                return
            }
            request.leader = observation
        }
        validateIdentity(request)
        validateTimes(request)
    }

    @Synchronized
    fun recordInstrumentationError(reason: String) {
        checkOpen()
        require(reason.isNotBlank())
        invalid(reason)
    }

    /**
     * 송신 목록은 세 노드를 모두 대조한다. 주 지연 표본은 고정 drain 경계 안에서 수신한 리더 표식만 사용한다.
     * 경계 뒤의 수신도 원시 시각과 송수신 대조에 보존하되 성공 표본에 소급해 채우지 않는다.
     */
    @Synchronized
    fun finish(
        drainEndNs: Long,
        sentMarkers: Collection<HttpPerformanceCompletion>? = null,
    ): HttpPerformanceSummary =
        finishAfterReconciliation(drainEndNs) {
            if (sentMarkers == null) invalid("SENT_MARKER_MANIFEST_MISSING") else reconcile(sentMarkers)
        }

    /** Caller returns only after every selected source file passed complete EOF/bytes/SHA checks. */
    @Synchronized
    internal fun finishWithSentMarkerVisitor(
        drainEndNs: Long,
        visitSent: ((HttpPerformanceCompletion) -> Unit) -> Unit,
    ): HttpPerformanceSummary =
        finishAfterReconciliation(drainEndNs) {
            checkExportInterrupted()
            reconcileVisited(visitSent, Math.multiplyExact(requests.size, 3))
            checkExportInterrupted()
        }

    private fun finishAfterReconciliation(
        drainEndNs: Long,
        reconcileAll: () -> Unit,
    ): HttpPerformanceSummary {
        checkOpen()
        require(drainEndNs - windowEndNs >= 0) { "finish must include the entire scheduled window" }
        requests.values.forEach { request ->
            if (request.startNs != null && drainEndNs - request.startNs!! < 0) invalid("START_AFTER_DRAIN:${request.plan.requestId}")
            if (request.startNs == null && request.members.isNotEmpty()) invalid("COMPLETION_FOR_UNSENT_REQUEST:${request.plan.requestId}")
        }
        if (unmatched.isNotEmpty()) invalid("UNMATCHED_MARKERS:${unmatched.size}")
        reconcileAll()
        finalizedDrainEndNs = drainEndNs
        finished = true
        return summarize(drainEndNs)
    }

    /** O(1) drain wait hint; exact full-payload reconciliation is still mandatory for success. */
    @Synchronized
    internal fun observedRequestMemberCount(): Int = markersByRequestMember.size

    @Synchronized
    fun requests(): List<HttpPerformanceRequestSample> = requests.values.map(::sample)

    /**
     * Test-source raw export: identical per-request values/order without a second cohort List.
     * Call after stopping observation. The visitor must only consume the immutable sample and must
     * not mutate this recorder. Count is returned only after every visitor and interrupt check pass;
     * a writer still owns close/EOF/bytes/SHA verification and explicit incomplete-file metadata.
     */
    @Synchronized
    internal fun forEachRequestSample(visit: (HttpPerformanceRequestSample) -> Unit): Int {
        checkExportInterrupted()
        var count = 0
        for (request in requests.values) {
            checkExportInterrupted()
            visit(sample(request))
            checkExportInterrupted()
            count = Math.incrementExact(count)
        }
        return count
    }

    /** No new retained timestamp index: immutable rows project the existing first-received records. */
    @Synchronized
    internal fun forEachSustainedRequestObservation(visit: (HttpPerformanceSustainedRequestObservation) -> Unit): Int {
        checkExportInterrupted()
        var count = 0
        for (request in requests.values) {
            checkExportInterrupted()

            fun receivedAt(member: Int): Long? = request.members[member]?.let { received[it.key()]?.receivedNs }
            val copies = httpPerformanceCompletionCopiesSample(firstDueNs, receivedAt(0), receivedAt(1), receivedAt(2))
            visit(HttpPerformanceSustainedRequestObservation(sample(request), copies))
            checkExportInterrupted()
            count = Math.incrementExact(count)
        }
        return count
    }

    private fun checkExportInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("HTTP evidence export interrupted")
    }

    private fun summarize(drainEndNs: Long): HttpPerformanceSummary {
        val samples = requests.values.map(::sample)
        val planned = samples.size
        val started = samples.count { it.startNs != null }
        val unsent = planned - started
        val httpWithinDrain = samples.filter { it.httpTimeliness == "WITHIN_DRAIN" }
        val accepted = httpWithinDrain.count { it.httpOutcome == HttpPerformanceOutcome.ACCEPTED }
        val unfinished = samples.count { it.startNs != null && it.httpTimeliness != "WITHIN_DRAIN" }
        val unknown =
            httpWithinDrain.count {
                it.httpOutcome == HttpPerformanceOutcome.HTTP_UNKNOWN ||
                    it.httpOutcome == HttpPerformanceOutcome.HTTP_TIMEOUT
            }
        val complete = samples.filter { it.completionLatencyNs != null }
        val completionUnknown = planned - complete.size
        val acceptedSamples = httpWithinDrain.filter { it.httpOutcome == HttpPerformanceOutcome.ACCEPTED }
        val acceptedInWindow = acceptedSamples.count { it.ackNs?.inWindow() == true }
        val completeInWindow = complete.filter { it.doneNs!!.inWindow() }
        val windowNs = windowEndNs - firstDueNs
        // 미완료 cohort는 마지막 성공 표본에서 끝내지 않고 전체 drain을 분모에 포함한다.
        val cohortEndNs = if (complete.size == planned) complete.maxBy { it.doneNs!! - firstDueNs }.doneNs!! else drainEndNs
        val cohortNs = (cohortEndNs - firstDueNs).coerceAtLeast(1)
        val completionUnknownBounds =
            samples.filter { it.startNs != null && it.completionLatencyNs == null }.map {
                drainEndNs -
                    it.startNs!!
            }
        val ackUnknownBounds =
            samples
                .filter {
                    it.startNs != null &&
                        (
                            it.httpTimeliness != "WITHIN_DRAIN" || it.httpOutcome == HttpPerformanceOutcome.HTTP_UNKNOWN ||
                                it.httpOutcome == HttpPerformanceOutcome.HTTP_TIMEOUT
                        )
                }.map { drainEndNs - it.startNs!! }
        val half = (planned + 1) / 2

        fun completionSummary(group: List<HttpPerformanceRequestSample>) =
            latency(
                group.mapNotNull { it.completionLatencyNs },
                group.size,
                group.count { it.completionLatencyNs == null },
                group.filter { it.startNs != null && it.completionLatencyNs == null }.map { drainEndNs - it.startNs!! },
            )
        return HttpPerformanceSummary(
            planned = planned,
            started = started,
            unsent = unsent,
            generatorSaturated = samples.count { it.notSentReason == GENERATOR_SATURATED },
            accepted = accepted,
            businessRejected = httpWithinDrain.count { it.httpOutcome == HttpPerformanceOutcome.BUSINESS_REJECTED },
            httpErrors = httpWithinDrain.count { it.httpOutcome == HttpPerformanceOutcome.HTTP_ERROR },
            httpUnknown = unknown,
            httpTimeouts = httpWithinDrain.count { it.httpOutcome == HttpPerformanceOutcome.HTTP_TIMEOUT },
            httpUnfinished = unfinished,
            httpAfterDrain = samples.count { it.httpTimeliness == "AFTER_DRAIN" },
            completed = complete.size,
            onTimeCompleted = samples.count { it.completionTimeliness == "ON_TIME" },
            lateCompleted = samples.count { it.completionTimeliness == "LATE" },
            afterDrainCompleted = samples.count { it.completionTimeliness == "AFTER_DRAIN" },
            completionUnknown = completionUnknown,
            tradeCount = complete.sumOf { it.tradeCount },
            settlementCount = complete.sumOf { it.settlementCount },
            duplicateMarkers = duplicates,
            unmatchedMarkers = unmatched.size,
            acceptedResponseWindowCount = acceptedInWindow,
            completionWindowCount = completeInWindow.size,
            settlementWindowCount = completeInWindow.sumOf { it.settlementCount },
            measurementWindowNs = windowNs,
            cohortDurationNs = cohortNs,
            drainDurationNs = drainEndNs - windowEndNs,
            acceptedResponsesPerSecond = perSecond(acceptedInWindow.toLong(), windowNs),
            completedOrdersObservedPerSecond = perSecond(completeInWindow.size.toLong(), windowNs),
            settlementsObservedPerSecond = perSecond(completeInWindow.sumOf { it.settlementCount }, windowNs),
            cohortCompletedOrdersPerSecond = perSecond(complete.size.toLong(), cohortNs),
            cohortSettlementsPerSecond = perSecond(complete.sumOf { it.settlementCount }, cohortNs),
            drainCompletionRate = complete.size.toDouble() / planned,
            httpUnknownRate = (unknown + unfinished).toDouble() / planned,
            completionUnknownRate = completionUnknown.toDouble() / planned,
            ackRtt = latency(acceptedSamples.mapNotNull { it.ackRttNs }, planned, unknown + unfinished + unsent, ackUnknownBounds),
            completionLatency =
                latency(
                    complete.mapNotNull { it.completionLatencyNs },
                    planned,
                    completionUnknown,
                    completionUnknownBounds,
                ),
            settlementLatency =
                latency(
                    complete
                        .filter {
                            it.settlementCount > 0
                        }.mapNotNull { it.completionLatencyNs },
                    planned,
                    completionUnknown,
                    completionUnknownBounds,
                ),
            scheduledAckLatency =
                latency(
                    acceptedSamples.mapNotNull { it.scheduledAckLatencyNs },
                    planned,
                    unknown + unfinished + unsent,
                    samples
                        .filter {
                            it.startNs ==
                                null ||
                                it.httpTimeliness != "WITHIN_DRAIN" ||
                                it.httpOutcome == HttpPerformanceOutcome.HTTP_UNKNOWN ||
                                it.httpOutcome == HttpPerformanceOutcome.HTTP_TIMEOUT
                        }.map { drainEndNs - it.dueNs },
                ),
            scheduledCompletionLatency =
                latency(
                    complete.mapNotNull { it.scheduledCompletionLatencyNs },
                    planned,
                    completionUnknown,
                    samples
                        .filter {
                            it.completionLatencyNs ==
                                null
                        }.map { drainEndNs - it.dueNs },
                ),
            generatorDelay =
                latency(
                    samples.mapNotNull { it.generatorDelayNs },
                    planned,
                    unsent,
                    samples.filter { it.startNs == null }.map {
                        drainEndNs -
                            it.dueNs
                    },
                ),
            firstHalfCompletionLatency = completionSummary(samples.take(half)),
            secondHalfCompletionLatency = completionSummary(samples.drop(half)),
            completionLatencyValid = errors.isEmpty(),
            instrumentationErrors = errors.toList(),
        )
    }

    private fun sample(request: MutableRequest): HttpPerformanceRequestSample {
        val plan = request.plan
        val leader = request.leader
        val doneNs = leader?.receivedNs
        val cutoff = finalizedDrainEndNs
        val completionAfterDrain = doneNs != null && cutoff != null && cutoff - doneNs < 0
        val httpAfterDrain = request.resultNs != null && cutoff != null && cutoff - request.resultNs!! < 0
        val completionLatency = if (completionAfterDrain) null else difference(doneNs, request.startNs)
        return HttpPerformanceRequestSample(
            userId = plan.userId,
            requestId = plan.requestId,
            orderId = plan.orderId,
            dueNs = plan.dueNs,
            startNs = request.startNs,
            ackNs = request.ackNs,
            httpResultNs = request.resultNs,
            httpOutcome = request.outcome,
            httpDetail = request.detail,
            httpTimeliness =
                when {
                    httpAfterDrain -> "AFTER_DRAIN"
                    request.resultNs != null -> "WITHIN_DRAIN"
                    request.startNs == null -> "NOT_STARTED"
                    else -> "UNKNOWN"
                },
            admissionSequence = request.admissionSequence ?: leader?.value?.admissionSequence,
            doneNs = doneNs,
            completionMemberId = leader?.value?.memberId,
            tradeCount = leader?.value?.tradeCount ?: 0,
            settlementCount = leader?.value?.settlementCount ?: 0,
            notSentReason = request.notSentReason ?: if (request.startNs == null) "NOT_STARTED" else null,
            notSentNs = request.notSentNs,
            generatorDelayNs = difference(request.startNs, plan.dueNs),
            ackRttNs = if (httpAfterDrain) null else difference(request.ackNs, request.startNs),
            completionLatencyNs = completionLatency,
            scheduledAckLatencyNs = if (httpAfterDrain) null else difference(request.ackNs, plan.dueNs),
            scheduledCompletionLatencyNs = if (completionAfterDrain) null else difference(doneNs, plan.dueNs),
            completionTimeliness =
                when {
                    completionAfterDrain -> "AFTER_DRAIN"
                    completionLatency == null -> "UNKNOWN"
                    completionLatency <= completionDeadlineNs -> "ON_TIME"
                    else -> "LATE"
                },
        )
    }

    private fun reconcile(sentMarkers: Collection<HttpPerformanceCompletion>) =
        reconcileVisited({ visit ->
            sentMarkers.forEach(visit)
        })

    private fun reconcileVisited(
        visitSent: ((HttpPerformanceCompletion) -> Unit) -> Unit,
        maximum: Int = Int.MAX_VALUE,
    ) {
        val sent = linkedMapOf<MarkerKey, HttpPerformanceCompletion>()
        visitSent { marker ->
            if (marker.runId == runId) {
                val key = marker.key()
                check(key in sent || sent.size < maximum) { "측정 송신 표식의 계획 상한 초과" }
                val previous = sent.putIfAbsent(key, marker)
                if (previous != null && previous != marker) {
                    invalid("SENT_MARKER_CONTENT_CONFLICT:${marker.memberId}/${marker.admissionSequence}")
                }
            }
        }
        sent.forEach { (key, marker) ->
            val observed = received[key]?.value ?: unmatched[key]?.value
            if (observed == null) {
                invalid("MARKER_NOT_RECEIVED:${key.memberId}/${key.admissionSequence}")
            } else if (observed != marker) {
                invalid("SENT_RECEIVED_MARKER_CONFLICT:${key.memberId}/${key.admissionSequence}")
            }
        }
        (received.keys + unmatched.keys).forEach { key ->
            if (key !in sent) invalid("RECEIVED_MARKER_NOT_IN_MANIFEST:${key.memberId}/${key.admissionSequence}")
        }
    }

    private fun validMarker(marker: HttpPerformanceCompletion): Boolean {
        if (marker.memberId < 0 || marker.admissionSequence <= 0 || marker.logPosition < 0 || marker.tradeCount < 0 ||
            marker.settlementCount < 0 ||
            marker.role !in setOf("LEADER", "FOLLOWER", "CANDIDATE")
        ) {
            invalid("MALFORMED_COMPLETION_MARKER")
            return false
        }
        return true
    }

    private fun validateIdentity(request: MutableRequest) {
        if (request.members.isEmpty()) return
        if (request.outcome == HttpPerformanceOutcome.BUSINESS_REJECTED) invalid("REJECTED_REQUEST_COMPLETED:${request.plan.requestId}")
        val expectedSequence = request.admissionSequence ?: request.leader?.value?.admissionSequence
        if (expectedSequence != null &&
            request.members.values.any { it.admissionSequence != expectedSequence }
        ) {
            invalid("HTTP_COMPLETION_SEQUENCE_CONFLICT:${request.plan.requestId}")
        }
        if (request.notSentReason != null) invalid("COMPLETION_FOR_UNSENT_REQUEST:${request.plan.requestId}")
    }

    private fun validateTimes(request: MutableRequest) {
        val start = request.startNs ?: return
        if (request.resultNs != null && request.resultNs!! - start < 0) invalid("HTTP_RESULT_BEFORE_START:${request.plan.requestId}")
        if (request.members.values.any { marker ->
                received[marker.key()]!!.receivedNs - start < 0
            }
        ) {
            invalid("COMPLETION_BEFORE_START:${request.plan.requestId}")
        }
    }

    private fun request(
        userId: String,
        requestId: String,
    ): MutableRequest? =
        requests[RequestKey(userId, requestId)].also {
            if (it == null) invalid("UNPLANNED_REQUEST:$userId/$requestId")
        }

    private fun Long.inWindow(): Boolean = this - firstDueNs >= 0 && windowEndNs - this > 0

    private fun HttpPerformanceCompletion.key() = MarkerKey(runId, memberId, admissionSequence)

    private fun checkOpen() = check(!finished) { "measurement is already finalized" }

    private fun invalid(reason: String) {
        errors.add(reason)
    }

    companion object {
        const val GENERATOR_SATURATED = "GENERATOR_SATURATED"

        private fun difference(
            later: Long?,
            earlier: Long?,
        ): Long? = if (later == null || earlier == null) null else (later - earlier).takeIf { it >= 0 }

        private fun perSecond(
            count: Long,
            durationNs: Long,
        ): Double = count * 1_000_000_000.0 / durationNs

        private fun latency(
            values: List<Long>,
            cohortSize: Int,
            unknownCount: Int,
            unknownBounds: List<Long>,
        ): HttpPerformanceLatencySummary {
            val sorted = values.sorted()

            fun rank(
                percentile: Double,
                size: Int,
            ) = ceil(percentile * size).toInt()

            fun percentile(percentile: Double): Long? = if (sorted.isEmpty()) null else sorted[rank(percentile, sorted.size) - 1]

            fun censored(percentile: Double): Boolean = cohortSize - sorted.size > cohortSize - rank(percentile, cohortSize)

            fun lowerBound(percentile: Double): Long? {
                val observedRank = rank(percentile, cohortSize) - (cohortSize - sorted.size)
                return if (observedRank > 0) sorted.getOrNull(observedRank - 1) else null
            }
            return HttpPerformanceLatencySummary(
                sampleCount = sorted.size,
                cohortSize = cohortSize,
                unknownCount = unknownCount,
                unknownRate = if (cohortSize == 0) 0.0 else unknownCount.toDouble() / cohortSize,
                p50Ns = percentile(0.50),
                p95Ns = percentile(0.95),
                p99Ns = percentile(0.99),
                maxNs = sorted.lastOrNull(),
                p50Censored = censored(0.50),
                p95Censored = censored(0.95),
                p99Censored = censored(0.99),
                populationP50LowerBoundNs = lowerBound(0.50),
                populationP95LowerBoundNs = lowerBound(0.95),
                populationP99LowerBoundNs = lowerBound(0.99),
                unknownObservationLowerBoundNs = unknownBounds.filter { it >= 0 }.minOrNull(),
            )
        }
    }
}
