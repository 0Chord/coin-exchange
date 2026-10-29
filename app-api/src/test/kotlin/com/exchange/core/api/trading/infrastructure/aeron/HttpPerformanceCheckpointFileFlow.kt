package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.core.JsonToken
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Small observation metadata; no financial row, book, timing list, or marker payload is retained. */
internal data class HttpPerformanceCheckpointFileObservation(
    val reference: HttpPerformanceCheckpointFileReference,
    val scan: HttpPerformanceCheckpointScan,
    val collectorFailureToken: JsonToken?,
    val role: String?,
    val roleChanges: Long?,
    val emitted: Long?,
    val offered: Long?,
    val sent: Long?,
    val queueOverflow: Long?,
    val failuresArray: Boolean,
    val failureCount: Long,
    val sentMarkersArray: Boolean,
    val historyOverflow: Long?,
) {
    fun checkInitial() {
        val member = reference.memberId
        check(collectorFailureToken == JsonToken.VALUE_NULL) { "node$member collectorFailure: ${reference.file.fileName}" }
        check(queueOverflow == 0L && failuresArray && failureCount == 0L) { "node$member 완료 표식 송신 오류: ${reference.file.fileName}" }
        check(historyOverflow == null || historyOverflow == 0L) { "node$member 완료 표식 누적 계획 초과" }
        check(listOf(emitted, offered, sent).all { it != null && it >= 0L }) { "node$member 완료 표식 count 누락/형식 오류" }
        check(sentMarkersArray) { "node$member 완료 표식 원문 배열 누락" }
    }

    val converged: Boolean get() = sent != null && emitted != null && sent == emitted

    fun checkFinal(baseline: Pair<String, Long>?) {
        checkInitial()
        val member = reference.memberId
        baseline?.let {
            check(role != null && roleChanges != null && baseline == (role to roleChanges)) { "측정 중 node$member 리더십 또는 역할 변경" }
        }
        check(offered == sent) { "node$member 완료 표식 배출 미완료" }
        check(converged) { "node$member 표식 생성·송신 개수 불일치" }
        check(scan.rowCounts["/markerSummary/sentMarkers"] == sent) { "node$member 완료 표식 원문·송신 개수 불일치" }
    }
}

/** Reads all bytes and scalar metadata, allocating neither financial rows nor marker rows. */
internal fun readHttpPerformanceCheckpointFileObservation(
    reference: HttpPerformanceCheckpointFileReference,
): HttpPerformanceCheckpointFileObservation {
    val counts = mutableMapOf<String, Long?>()
    var collector: JsonToken? = null
    var role: String? = null
    var failuresArray = false
    var failures = 0L
    var sentMarkersArray = false
    val failureRow = Regex("/markerSummary/failures/[0-9]+")
    val countPaths =
        setOf(
            "/roleChangeCount",
            "/emittedMarkerCount",
            "/markerSummary/offered",
            "/markerSummary/sentCount",
            "/markerSummary/queueOverflow",
            "/markerSummary/historyOverflow",
        )
    val scan =
        HttpPerformanceCheckpointReader.scanRows(
            reference,
            onRow = { _, _ -> error("metadata scan must not allocate rows") },
            onScalar = { pointer, value ->
                if (pointer in
                    countPaths
                ) {
                    counts[pointer] = if (value.isIntegralNumber && value.canConvertToLong()) value.asLong() else null
                }
            },
            onStructure = { event ->
                if (event.pointer == "/collectorFailure" && event.token != JsonToken.END_OBJECT &&
                    event.token != JsonToken.END_ARRAY
                ) {
                    collector = event.token
                }
                if (event.pointer == "/role" && event.token == JsonToken.VALUE_STRING) role = event.text
                if (event.pointer == "/markerSummary/failures" && event.token == JsonToken.START_ARRAY) failuresArray = true
                if (event.pointer == "/markerSummary/sentMarkers" && event.token == JsonToken.START_ARRAY) sentMarkersArray = true
                if (failureRow.matches(event.pointer) && event.token != JsonToken.END_OBJECT &&
                    event.token != JsonToken.END_ARRAY
                ) {
                    failures = Math.incrementExact(failures)
                }
            },
            includeRow = { false },
            includeMarkerRows = true,
        )
    return HttpPerformanceCheckpointFileObservation(
        reference,
        scan,
        collector,
        role,
        counts["/roleChangeCount"],
        counts["/emittedMarkerCount"],
        counts["/markerSummary/offered"],
        counts["/markerSummary/sentCount"],
        counts["/markerSummary/queueOverflow"],
        failuresArray,
        failures,
        sentMarkersArray,
        counts["/markerSummary/historyOverflow"],
    )
}

internal data class HttpPerformanceFileCheckpoint(
    val references: Map<Int, HttpPerformanceCheckpointFileReference>,
    val observations: Map<Int, HttpPerformanceCheckpointFileObservation>,
)

/** Existing convergence policy with file references and the independent complete-copy comparator. */
internal fun collectHttpPerformanceCheckpointFiles(
    label: String,
    timeoutMs: Long,
    verifyObservation: Boolean,
    capture: (String, Long) -> Map<Int, HttpPerformanceCheckpointFileReference>,
    preserve: (String, Map<String, Any?>) -> Unit,
    roleBaseline: Map<Int, Pair<String, Long>>? = null,
    compareCopies: (
        Map<Int, HttpPerformanceCheckpointFileReference>,
    ) -> HttpPerformanceCheckpointCopiesFileCheck = ::checkHttpPerformanceCheckpointCopiesFiles,
    nanoTime: () -> Long = System::nanoTime,
    pause: () -> Unit = { Thread.sleep(250) },
    suffix: () -> String = { UUID.randomUUID().toString().take(8) },
    timeBudget: HttpPerformanceEvidenceTimeBudget = HttpPerformanceEvidenceTimeBudget.STANDARD,
): HttpPerformanceFileCheckpoint {
    require(label.matches(Regex("[a-zA-Z0-9-]{1,70}")))
    timeBudget.requireCheckpointTimeout(timeoutMs)
    val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var attempt = 0
    while (true) {
        checkCheckpointFileInterrupted()
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - nanoTime())
        check(remainingMs > 0) { "$label 세 노드의 후속 적용 위치 수렴 제한 시간 초과" }
        val references = capture("$label-${suffix()}", remainingMs).toMap()
        checkCheckpointFileInterrupted()
        check(references.keys == setOf(0, 1, 2)) { "세 노드 체크포인트 참조가 모두 필요합니다" }
        for ((member, reference) in references) check(reference.memberId == member) { "node$member 체크포인트 참조 식별 불일치" }
        // Even a later parse/equality failure leaves the exact source references in the attempt wrapper.
        preserve(
            "checkpoint-files-$label-attempt-${attempt++}.json",
            httpPerformanceCheckpointFileWrapper(label, references, emptyMap(), verifyObservation),
        )
        checkCheckpointFileInterrupted()
        val observations = references.mapValues { (_, reference) -> readHttpPerformanceCheckpointFileObservation(reference) }
        if (verifyObservation) observations.values.forEach { it.checkInitial() }
        val positions = observations.values.map { checkNotNull(it.scan.appliedPosition) { "node${it.reference.memberId} 적용 위치 누락" } }
        if (positions.distinct().size == 1) {
            val copies = compareCopies(references)
            checkCheckpointFileInterrupted()
            check(copies.appliedPosition == positions.first()) { "동일성 검사 적용 위치 불일치" }
            if (!verifyObservation || observations.values.all { it.converged }) {
                preserve(
                    "checkpoint-files-$label.json",
                    httpPerformanceCheckpointFileWrapper(label, references, observations, verifyObservation),
                )
                if (verifyObservation) observations.forEach { (member, observation) -> observation.checkFinal(roleBaseline?.get(member)) }
                checkCheckpointFileInterrupted()
                return HttpPerformanceFileCheckpoint(references, observations)
            }
        }
        pause()
    }
}

/** A compact parent wrapper; original complete node files stay untouched in control/. */
internal fun httpPerformanceCheckpointFileWrapper(
    label: String,
    references: Map<Int, HttpPerformanceCheckpointFileReference>,
    observations: Map<Int, HttpPerformanceCheckpointFileObservation>,
    verifyObservation: Boolean,
): Map<String, Any?> =
    mapOf(
        "schema" to "PERFORMANCE_CHECKPOINT_REFERENCES_V1",
        "label" to label,
        "observationVerificationRequested" to verifyObservation,
        "nodes" to
            references.mapValues { (member, reference) ->
                val observation = observations[member]
                mapOf(
                    "runId" to reference.runId,
                    "memberId" to reference.memberId,
                    "label" to reference.label,
                    "artifactFile" to "control/${reference.file.fileName}",
                    "artifactBytes" to reference.bytes,
                    "artifactSha256" to reference.sha256,
                    "position" to observation?.scan?.appliedPosition,
                )
            },
    )

/** Only typed marker payloads are cached for drain retries; no checkpoint or financial JSON is cached. */
internal data class HttpPerformanceSentMarkerFileEvidence(
    val reference: HttpPerformanceCheckpointFileReference,
    val appliedPosition: Long,
    val markers: Set<HttpPerformanceCompletion>,
)

internal fun readHttpPerformanceSentMarkerFiles(
    references: Map<Int, HttpPerformanceCheckpointFileReference>,
    maximumMarkersPerMember: Int,
): Map<Int, HttpPerformanceSentMarkerFileEvidence> {
    require(maximumMarkersPerMember > 0)
    checkCheckpointFileInterrupted()
    val mapper = jacksonObjectMapper()
    return references.mapValues { (member, reference) ->
        check(reference.memberId == member)
        val sent = HashSet<HttpPerformanceCompletion>()
        var count = 0L
        val scan =
            HttpPerformanceCheckpointReader.scanRows(
                reference,
                onRow = { _, row ->
                    count = Math.incrementExact(count)
                    check(count <= maximumMarkersPerMember) { "node$member 완료 표식 원문 계획 상한 초과" }
                    val marker = mapper.treeToValue(row, HttpPerformanceCompletion::class.java)
                    check(marker.runId == reference.runId && marker.memberId == member) { "node$member 완료 표식 원문 식별 불일치" }
                    sent.add(marker)
                },
                includeRow = { it.startsWith("/markerSummary/sentMarkers/") },
                includeMarkerRows = true,
            )
        checkCheckpointFileInterrupted()
        check(scan.rowCounts["/markerSummary/sentMarkers"] == count) { "node$member 완료 표식 원문 배열 누락" }
        HttpPerformanceSentMarkerFileEvidence(reference, checkNotNull(scan.appliedPosition), Collections.unmodifiableSet(sent))
    }
}

/** Same exact typed-payload set equality and applied-position fence as the legacy tree path. */
internal fun httpPerformanceMarkerDifferencesFiles(
    checkpoints: Map<Int, HttpPerformanceSentMarkerFileEvidence>,
    received: List<HttpPerformanceCompletion>,
): List<String> =
    buildList {
        for ((member, checkpoint) in checkpoints) {
            val sent = checkpoint.markers
            val observed = received.filter { it.memberId == member && it.logPosition <= checkpoint.appliedPosition }.toSet()
            if (sent != observed) {
                add(
                    "node$member 완료 표식 송수신 불일치: sent=${sent.size}, received=${observed.size}, missing=${(sent - observed).size}, extra=${(observed - sent).size}",
                )
            }
        }
    }

private fun checkCheckpointFileInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("checkpoint file workflow interrupted")
}
